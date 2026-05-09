package com.notesprout.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notesprout.android.NoteSproutApp
import com.notesprout.android.R
import com.notesprout.android.plugins.structural.NotebookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebookListActivity : AppCompatActivity() {

    private lateinit var notebookManager: NotebookManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: NotebookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_list)

        val app = application as NoteSproutApp
        notebookManager = NotebookManager(app.databaseManager, app.pluginEngine)

        recyclerView = findViewById(R.id.notebookList)
        emptyView = findViewById(R.id.emptyView)

        adapter = NotebookAdapter(emptyList()) { file -> openNotebook(file) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<TextView>(R.id.newButton).setOnClickListener {
            showNewNotebookDialog()
        }

        loadNotebooks()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveMode.enter(window)
    }

    override fun onResume() {
        super.onResume()
        loadNotebooks()
    }

    private fun loadNotebooks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val notebooks = notebookManager.listNotebooks()
                .sortedByDescending { it.lastModified() }
            withContext(Dispatchers.Main) {
                adapter.update(notebooks)
                val hasItems = notebooks.isNotEmpty()
                recyclerView.visibility = if (hasItems) View.VISIBLE else View.GONE
                emptyView.visibility = if (hasItems) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openNotebook(file: File) {
        val intent = Intent(this, NotebookCanvasActivity::class.java).apply {
            putExtra(NotebookCanvasActivity.EXTRA_SOIL_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    private fun showNewNotebookDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.new_notebook_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_notebook_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.new_notebook_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) createNotebook(name)
            }
            .setNegativeButton(getString(R.string.new_notebook_cancel), null)
            .show()
    }

    private fun createNotebook(name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val notebookObj = notebookManager.createNotebook(
                name = name,
                pageWidth = 1404.0,
                pageHeight = 1872.0
            )
            val app = application as NoteSproutApp
            val soilPath = app.databaseManager.currentNotebookPath
            withContext(Dispatchers.Main) {
                if (soilPath != null) {
                    val intent = Intent(this@NotebookListActivity, NotebookCanvasActivity::class.java).apply {
                        putExtra(NotebookCanvasActivity.EXTRA_NOTEBOOK_ID, notebookObj.id)
                        putExtra(NotebookCanvasActivity.EXTRA_SOIL_PATH, soilPath)
                    }
                    startActivity(intent)
                }
            }
        }
    }
}

class NotebookAdapter(
    private var notebooks: List<File>,
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<NotebookAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameView: TextView = view.findViewById(R.id.notebookName)
        val dateView: TextView = view.findViewById(R.id.notebookDate)
        val borderView: View = view.findViewById(R.id.itemBorder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notebook, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = notebooks[position]
        holder.nameView.text = file.nameWithoutExtension
        holder.dateView.text = dateFormat.format(Date(file.lastModified()))
        holder.borderView.visibility = if (position == itemCount - 1) View.INVISIBLE else View.VISIBLE
        holder.itemView.setOnClickListener { onItemClick(file) }
    }

    override fun getItemCount(): Int = notebooks.size

    fun update(newList: List<File>) {
        notebooks = newList
        notifyDataSetChanged()
    }
}
