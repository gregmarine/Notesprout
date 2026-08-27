package com.symmetricalpalmtree.notesproutsn.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
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
import com.symmetricalpalmtree.notesproutsn.data.template.PagePaper
import com.symmetricalpalmtree.notesproutsn.data.template.PaperSource
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNewNotebookBinding
import com.symmetricalpalmtree.notesproutsn.templates.TemplateBrowser
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePick
import com.symmetricalpalmtree.notesproutsn.templates.TemplatePicks
import com.symmetricalpalmtree.notesproutsn.templates.TemplateRecents
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
 * **The paper is picked from the template library itself** (arc 13 / G3), not from four radios: the
 * whole [TemplateBrowser] lives under this screen's header, with the same folders, the same
 * long-press management and the same Default folder as the Templates screen. One browser, three
 * hosts — a second, smaller way to choose paper would drift from the real one within an arc.
 *
 * **Creation order matters and is the format contract** (`RATTA_PLAN.md` R2, Paper's `docs/data.md`):
 *
 * 1. mint a UUID — the id is the filename and the notebook row's primary key at once;
 * 2. `SoilDatabase.create` the encrypted file (refuses to write over anything that exists);
 * 3. the **notebook** row (`text` = name, `refId` = the first page, so a reopen knows where to land);
 * 4. the **template** row for whatever paper was picked — drawn from arithmetic for a built-in,
 *    fitted from the library's stored pixels for an imported one, and baked to a lossless WEBP blob
 *    at page size either way ([PagePaper]). **Blank writes no template row at all** and the page's
 *    `refId` stays `""`;
 * 5. **page 1**, sized to the full portrait screen in pixels, order 0;
 * 6. `notebook_meta` — the file's self-description, including the folder path, so it is portable
 *    on its own;
 * 7. `seal()` — checkpoint the WAL back into the file, close;
 * 8. only **then** the index row. The index is the library's truth, so it is written last: a crash
 *    anywhere earlier leaves an orphan file in `Garden/`, never a card pointing at nothing.
 */
class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private lateinit var browser: TemplateBrowser
    private val repo by lazy { IndexRepository() }
    private var parentFolderId: String? = null
    private var creating = false

    /** The card the user has chosen. Blank until they say otherwise — the honest default for a
     *  notebook nobody has told anything about yet, and the one every other paper is measured
     *  against. */
    private var pick: TemplatePick = TemplatePick.Blank

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
        // No followIme, and the window is adjustNothing (manifest): this screen has a page on it
        // now, and resizing for the keyboard would squash the grid it measured itself against. The
        // name field sits in the top row instead, where the IME can never reach it.
        TopGuard.applyInsetPadding(binding.root)

        parentFolderId = intent.getStringExtra(EXTRA_PARENT_FOLDER_ID)

        // The library resolves the folder's naming scheme before it launches this screen and hands
        // the expanded name in. This screen stays naming-agnostic: it is a prefill like any other,
        // still fully editable, and the Create-time duplicate check already covers a collision.
        val prefill = intent.getStringExtra(EXTRA_DEFAULT_NAME)?.trim()
        binding.nameField.setText(if (!prefill.isNullOrBlank()) prefill else defaultName())
        binding.nameField.selectAll()

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnCreate.setOnClickListener { attemptCreate() }

        browser = TemplateBrowser(
            activity = this,
            binding = binding.browser,
            // A tap here chooses; it does not create. The name still has to be right and Create is
            // still the act — so the card ticks and the screen stays where it is.
            onPick = { chosen -> pick = chosen; browser.refreshSelection() },
            selection = { TemplateBrowser.Selection(cardId = pick.cardId) },
        )

        // The export in flight, if this screen was killed behind a system picker (G4).
        browser.restoreState(savedInstanceState)
        // And the card the user had chosen (G6). Same reason as the export: SAF is another process
        // on a memory-tight device and this screen can be killed behind it — coming back with the
        // pick reset to Blank and Create still armed would make a notebook on paper nobody asked
        // for. `pick` is a card name, so its own wire form carries it.
        savedInstanceState?.getString(KEY_PICK)
            ?.let { TemplatePick.decode(it) }
            ?.let { pick = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PICK, pick.encode())
        if (::browser.isInitialized) browser.saveState(outState)
    }

    /** The browser's one-finger page flip — forwarded, never consumed (`ListSwipe`). It arms on
     *  the grid alone, so a drag across the name field above it is not a page turn. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::browser.isInitialized) browser.onDispatchTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Back peels one layer: up a folder in the browser, then out of the screen. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.onBackPressed()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** A timestamp, because the honest default for an unnamed notebook is when it started. */
    private fun defaultName(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    private fun attemptCreate() {
        if (creating) return
        val name = binding.nameField.text.toString().trim()
        NameRules.validate(name)?.let { problem ->
            Dialogs.problem(this, R.string.name_problem_title, NameDialog.problemMessage(this, problem))
            return
        }
        val chosen = pick
        setCreating(true)

        lifecycleScope.launch {
            if (repo.nameTaken(parentFolderId, ObjectType.NOTEBOOK, name)) {
                setCreating(false)
                // A tap that did nothing is a dialog, never a toast — on e-ink a toast is missable
                // and a create that silently didn't happen reads as a broken app.
                Dialogs.problem(this@NewNotebookActivity, R.string.name_problem_title, getString(R.string.new_notebook_duplicate, name))
                return@launch
            }
            // The pixels are resolved before the file is touched. A template deleted from another
            // screen while this one was open must stop the create with an explanation, not leave a
            // notebook on blank paper the user did not ask for.
            val paper = withContext(Dispatchers.IO) { TemplatePicks.paper(repo, chosen) }
            if (paper == null) {
                setCreating(false)
                Dialogs.problem(
                    this@NewNotebookActivity,
                    R.string.template_gone_title,
                    R.string.template_gone_body,
                )
                return@launch
            }
            val result = runCatching { withContext(Dispatchers.IO) { createNotebook(name, chosen, paper) } }
            result.onSuccess { id ->
                // Baking page 1 is an apply, and an apply is the only thing that makes paper
                // recent (arc 13 / G5). After the create, never before: a notebook that failed to
                // be written is not paper the user has been working on.
                TemplateRecents.record(this@NewNotebookActivity, chosen)
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
    private suspend fun createNotebook(name: String, pick: TemplatePick, paper: PaperSource): String {
        val notebookId = UUID.randomUUID().toString()
        // The passphrase lives only in process RAM (KeySession) — never an extra, never the index.
        // Absent means this process never went through bootstrap; bounce the way IndexGuard does.
        val passphrase = KeySession.get() ?: error("no key session")
        val file = soilFile(this, notebookId)
        val now = System.currentTimeMillis()

        val metrics = resources.displayMetrics
        val pageW = minOf(metrics.widthPixels, metrics.heightPixels)
        val pageH = maxOf(metrics.widthPixels, metrics.heightPixels)

        // The paper is drawn BEFORE the file exists. Blank draws nothing by definition; anything
        // else that comes back null is paper that will not render — bytes that no longer decode, an
        // allocation the device refused — and creating a notebook on silently blank paper because
        // of it is the arc's one forbidden outcome. Throwing here (rather than after step 2) means
        // the failure costs no orphan `.soil` either.
        val pageBlob = if (paper is PaperSource.Blank) null else {
            val bitmap = PagePaper.render(paper, pageW, pageH, metrics.densityDpi.toFloat())
                ?: error("template render failed")
            try { BuiltInTemplates.toWebp(bitmap) } finally { bitmap.recycle() }
        }

        val db = SoilDatabase.create(this, notebookId, file, passphrase)
        try {
            val dao = db.dao()
            val pageId = UUID.randomUUID().toString()

            dao.upsert(SoilObjectEntity(
                id = notebookId, parentId = SoilSchema.ROOT_PARENT, type = SoilSchema.TYPE_NOTEBOOK,
                createdAt = now, updatedAt = now, text = name, refId = pageId,
            ))

            // Blank writes no template row at all — that is what blank IS in this format. Every
            // other paper has pixels by now or the create never got here.
            var templateId: String? = null
            if (pageBlob != null) {
                templateId = UUID.randomUUID().toString()
                dao.upsert(SoilObjectEntity(
                    id = templateId, parentId = notebookId, type = SoilSchema.TYPE_TEMPLATE,
                    createdAt = now, updatedAt = now, text = PagePaper.token(paper),
                    width = pageW.toFloat(), height = pageH.toFloat(), blob = pageBlob,
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

        repo.createNotebook(notebookId, name, parentFolderId, TemplatePicks.birthKind(pick), pageCount = 1, now = now)
        return notebookId
    }

    companion object {
        private const val TAG = "NewNotebook"
        private const val KEY_PICK = "templatePick"
        const val EXTRA_PARENT_FOLDER_ID = "parentFolderId"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        /** The name field's prefill, already expanded from the folder's scheme. Null = timestamp. */
        const val EXTRA_DEFAULT_NAME = "defaultName"

        fun intent(context: Context, parentFolderId: String?, defaultName: String? = null): Intent =
            Intent(context, NewNotebookActivity::class.java)
                .putExtra(EXTRA_PARENT_FOLDER_ID, parentFolderId)
                .putExtra(EXTRA_DEFAULT_NAME, defaultName)
    }
}
