package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.bootstrap.BootstrapActivity
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMetaStore
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNewNotebookBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Name the notebook, pick its paper, create it.
 *
 * **Creation order matters and is the format contract** (`RATTA_PLAN.md` R2, Paper's `docs/data.md`):
 *
 * 1. mint a UUID — the id is the filename and the notebook row's primary key at once;
 * 2. `SoilDatabase.create` the encrypted file (refuses to write over anything that exists);
 * 3. the **notebook** row (`text` = name, `refId` = the first page, so a reopen knows where to land);
 * 4. the **template** row for Lined/Dotted/Grid — the pattern is baked to a lossless WEBP blob at
 *    page size. **Blank writes no template row at all** and the page's `refId` stays `""`;
 * 5. **page 1**, sized to the full portrait screen in pixels, order 0;
 * 6. `notebook_meta` — the file's self-description, including the folder path, so it is portable
 *    on its own;
 * 7. `seal()` — checkpoint the WAL back into the file, close;
 * 8. only **then** the index row. The index is the library's truth, so it is written last: a crash
 *    anywhere earlier leaves an orphan file in `Garden/`, never a card pointing at nothing.
 */
class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private val repo by lazy { IndexRepository() }
    private var parentFolderId: String? = null
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        // Creating a file needs the passphrase, which lives only in this process's RAM. If the
        // index is open the bootstrap has run and it is there; the belt-and-braces case (a
        // debug "forget key" mid-flight) goes back through the bootstrap the way IndexGuard does,
        // rather than reaching step 2 and throwing with a half-typed name on screen.
        if (KeySession.get() == null) {
            startActivity(
                Intent(this, BootstrapActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        binding = ActivityNewNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // followIme: the name field must stay above the on-screen keyboard (the only text entry
        // in the library, and on Ratta the IME is the only thing that delivers key events).
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        parentFolderId = intent.getStringExtra(EXTRA_PARENT_FOLDER_ID)

        binding.nameField.setText(defaultName())
        binding.nameField.selectAll()

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnCreate.setOnClickListener { attemptCreate() }
    }

    /** A timestamp, because the honest default for an unnamed notebook is when it started. */
    private fun defaultName(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    private fun selectedTemplate(): TemplateKind = when (binding.templateGroup.checkedRadioButtonId) {
        R.id.radioLined -> TemplateKind.LINED
        R.id.radioDotted -> TemplateKind.DOTTED
        R.id.radioGrid -> TemplateKind.GRID
        else -> TemplateKind.BLANK
    }

    private fun attemptCreate() {
        if (creating) return
        val name = binding.nameField.text.toString().trim()
        NameRules.validate(name)?.let { problem ->
            Dialogs.problem(this, R.string.name_problem_title, NameDialog.problemMessage(this, problem))
            return
        }
        val kind = selectedTemplate()
        setCreating(true)

        lifecycleScope.launch {
            if (repo.nameTaken(parentFolderId, ObjectType.NOTEBOOK, name)) {
                setCreating(false)
                // A tap that did nothing is a dialog, never a toast — on e-ink a toast is missable
                // and a create that silently didn't happen reads as a broken app.
                Dialogs.problem(this@NewNotebookActivity, R.string.name_problem_title, getString(R.string.new_notebook_duplicate, name))
                return@launch
            }
            val result = runCatching { withContext(Dispatchers.IO) { createNotebook(name, kind) } }
            result.onSuccess { id ->
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(EXTRA_NOTEBOOK_ID, id)
                    putExtra(EXTRA_NOTEBOOK_NAME, name)
                })
                finish()
            }.onFailure { e ->
                setCreating(false)
                Slog.d(TAG) { "create failed: $e" }
                Dialogs.problem(
                    this@NewNotebookActivity,
                    R.string.new_notebook_failed_title,
                    getString(R.string.new_notebook_failed_body, e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    private fun setCreating(value: Boolean) {
        creating = value
        binding.btnCreate.setText(if (value) R.string.new_notebook_creating else R.string.new_notebook_create)
        // Never isEnabled = false: a disabled control is invisible on e-ink. The click guard is the flag.
        binding.btnCreate.isClickable = !value
    }

    /** Everything from step 2 to step 8 of the class note. Runs on IO; throws on failure. */
    private suspend fun createNotebook(name: String, kind: TemplateKind): String {
        val notebookId = UUID.randomUUID().toString()
        // The passphrase lives only in process RAM (KeySession) — never an extra, never the index.
        // Absent means this process never went through bootstrap; bounce the way IndexGuard does.
        val passphrase = KeySession.get() ?: error("no key session")
        val file = soilFile(this, notebookId)
        val now = System.currentTimeMillis()

        val metrics = resources.displayMetrics
        val pageW = minOf(metrics.widthPixels, metrics.heightPixels)
        val pageH = maxOf(metrics.widthPixels, metrics.heightPixels)

        val db = SoilDatabase.create(this, notebookId, file, passphrase)
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
                val bitmap = BuiltInTemplates.render(kind, pageW, pageH, metrics.densityDpi.toFloat())
                val blob = bitmap?.let { BuiltInTemplates.toWebp(it) }
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

            NotebookMetaStore.write(db.raw(), NotebookMeta(
                notebookId = notebookId, name = name, createdAt = now, updatedAt = now,
                folderPath = repo.ancestry(parentFolderId),
                appVersionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt(),
            ))
            db.seal(file)
        } catch (e: Exception) {
            // Seal regardless so the handle is closed; the half-written file is left on disk (never
            // delete data on failure) and no index row ever names it.
            runCatching { db.seal(file) }
            throw e
        }

        repo.createNotebook(notebookId, name, parentFolderId, kind.name, pageCount = 1, now = now)
        return notebookId
    }

    companion object {
        private const val TAG = "NewNotebook"
        const val EXTRA_PARENT_FOLDER_ID = "parentFolderId"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        fun intent(context: Context, parentFolderId: String?): Intent =
            Intent(context, NewNotebookActivity::class.java)
                .putExtra(EXTRA_PARENT_FOLDER_ID, parentFolderId)
    }
}
