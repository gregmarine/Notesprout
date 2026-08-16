package com.symmetricalpalmtree.notesprout.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.data.soilFile
import com.symmetricalpalmtree.notesprout.data.index.IndexRepository
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import com.symmetricalpalmtree.notesprout.data.soil.FolderRef
import com.symmetricalpalmtree.notesprout.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesprout.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesprout.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesprout.data.template.TemplateKind
import com.symmetricalpalmtree.notesprout.databinding.ActivityNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private val repo by lazy { IndexRepository() }
    private var parentFolderId: String? = null
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityNewNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        parentFolderId = intent.getStringExtra(EXTRA_PARENT_FOLDER_ID)

        binding.nameField.setText(defaultName())
        binding.nameField.selectAll()

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnCreate.setOnClickListener { attemptCreate() }
    }

    private fun defaultName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    private fun selectedTemplate(): TemplateKind = when (binding.templateGroup.checkedRadioButtonId) {
        R.id.radioLined -> TemplateKind.LINED
        R.id.radioDotted -> TemplateKind.DOTTED
        R.id.radioGrid -> TemplateKind.GRID
        else -> TemplateKind.BLANK
    }

    private fun attemptCreate() {
        if (creating) return
        val name = binding.nameField.text.toString().trim()
        val err = validateName(name)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            return
        }
        creating = true
        binding.btnCreate.text = getString(R.string.new_notebook_creating)
        binding.btnCreate.isClickable = false
        val kind = selectedTemplate()
        lifecycleScope.launch {
            if (repo.nameTaken(parentFolderId, ObjectType.NOTEBOOK, name)) {
                creating = false
                binding.btnCreate.text = getString(R.string.new_notebook_create)
                binding.btnCreate.isClickable = true
                Toast.makeText(this@NewNotebookActivity, R.string.new_notebook_duplicate, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val notebookId = withContext(Dispatchers.IO) { createNotebook(name, kind) }
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(EXTRA_NOTEBOOK_NAME, name)
            })
            finish()
        }
    }

    private suspend fun createNotebook(name: String, kind: TemplateKind): String {
        val notebookId = UUID.randomUUID().toString()
        val passphrase = KeySession.get() ?: error("No key session")
        val file = soilFile(this, notebookId)
        val now = System.currentTimeMillis()

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val pageW = minOf(screenW, screenH)
        val pageH = maxOf(screenW, screenH)

        val db = withContext(Dispatchers.IO) { SoilDatabase.create(this@NewNotebookActivity, notebookId, file, passphrase) }
        try {
            val dao = db.dao()
            val pageId = UUID.randomUUID().toString()

            dao.upsert(SoilObjectEntity(
                id = notebookId, parentId = SoilSchema.ROOT_PARENT, type = SoilSchema.TYPE_NOTEBOOK,
                createdAt = now, updatedAt = now, text = name, refId = pageId,
            ))

            var templateId: String? = null
            if (kind != TemplateKind.BLANK) {
                templateId = UUID.randomUUID().toString()
                val dpi = metrics.densityDpi.toFloat()
                val bitmap = BuiltInTemplates.render(kind, pageW, pageH, dpi)
                val blob = bitmap?.let { bitmapToWebp(it) }
                bitmap?.recycle()
                dao.upsert(SoilObjectEntity(
                    id = templateId, parentId = notebookId, type = SoilSchema.TYPE_TEMPLATE,
                    createdAt = now, updatedAt = now, text = kind.name,
                    width = pageW.toFloat(), height = pageH.toFloat(), blob = blob,
                ))
            }

            dao.upsert(SoilObjectEntity(
                id = pageId, parentId = notebookId, type = SoilSchema.TYPE_PAGE,
                order = 0, createdAt = now, updatedAt = now,
                refId = templateId ?: "", width = pageW.toFloat(), height = pageH.toFloat(),
            ))

            val folderPath = repo.ancestry(parentFolderId)
            NotebookMetaStore.write(db.raw(), NotebookMeta(
                notebookId = notebookId, name = name, createdAt = now, updatedAt = now,
                folderPath = folderPath,
                appVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt(),
            ))

            withContext(Dispatchers.IO) { db.seal(file) }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { runCatching { db.seal(file) } }
            throw e
        }

        repo.createNotebook(notebookId, name, parentFolderId, kind.name, pageCount = 1, now = now)
        return notebookId
    }

    private fun bitmapToWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        return out.toByteArray()
    }

    companion object {
        const val EXTRA_PARENT_FOLDER_ID = "parentFolderId"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        private val NAME_PATTERN = Regex("^[a-zA-Z0-9_\\-. ]+$")

        fun validateName(name: String): String? = when {
            name.isEmpty() -> "Name cannot be empty"
            name == "." || name == ".." -> "Invalid name"
            !NAME_PATTERN.matches(name) -> "Only letters, numbers, spaces, hyphens, underscores, and dots"
            else -> null
        }

        fun intent(context: Context, parentFolderId: String?): Intent =
            Intent(context, NewNotebookActivity::class.java).apply {
                putExtra(EXTRA_PARENT_FOLDER_ID, parentFolderId)
            }
    }
}
