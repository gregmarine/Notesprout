package com.symmetricalpalmtree.notesprout.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.IndexGuard
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
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
import com.symmetricalpalmtree.notesprout.databinding.ActivityNewNotebookBinding
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallerCheck
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.LinkCreateRelay
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.TemplateChoice
import com.symmetricalpalmtree.notesprout.extension.TemplateInfo
import com.symmetricalpalmtree.notesprout.extension.TemplateProviderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private val repo by lazy { IndexRepository() }
    private var parentFolderId: String? = null
    /** Launched by the link picker via ACTION_LINK_NEW_NOTEBOOK_SCREEN (arc 7 / L3): the folder +
     *  default name come from [LinkCreateRelay] and the created identity goes back through it. */
    private var relayMode = false
    private var creating = false
    /** Identity of the template checked before a recreation (provider radios are rebuilt async). */
    private var restoreIdentity: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        // The screen is exported since L3 (the link picker launches it via
        // ACTION_LINK_NEW_NOTEBOOK_SCREEN) — every launch must come for-a-result from a
        // signature-matched caller (the host itself passes trivially); anything else is refused.
        if (!ExtensionCallerCheck.enforceActivity(this)) return
        relayMode = intent.action == ExtensionContract.ACTION_LINK_NEW_NOTEBOOK_SCREEN
        binding = ActivityNewNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Relay mode (arc 7 / L3): nothing rides the Intent — the picker's browsed folder and the
        // naming-scheme default were parked by ILinkCatalog.prepareNewNotebook. An empty relay
        // means the pick showing is gone (revoked / host restarted): leave plain.
        val prepared = if (relayMode) LinkCreateRelay.prepared() else null
        if (relayMode && prepared == null) {
            Slog.d(TAG) { "relay mode with nothing armed — showing gone" }
            finish()
            return
        }
        parentFolderId = if (relayMode) prepared?.parentFolderId else intent.getStringExtra(EXTRA_PARENT_FOLDER_ID)

        // A caller-supplied default (the library's namer prefill, or the relay's) is untrusted: used
        // only if it passes the core's name rule + length cap, else this screen's own default.
        // Extension-agnostic either way — the namer was resolved by the caller.
        val candidate = if (relayMode) prepared?.defaultName else intent.getStringExtra(EXTRA_DEFAULT_NAME)
        binding.nameField.setText(acceptDefaultName(candidate) ?: defaultName())
        binding.nameField.selectAll()

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnCreate.setOnClickListener { attemptCreate() }

        // Recreation (keyboard attach on Ratta, locale…): the provider radios are gone until discovery
        // rebuilds them, and radioBlank restores as unchecked if a provider radio had been chosen —
        // re-check Blank now so the group is never left with nothing selected, and remember the
        // chosen identity so discovery can re-check it (or leave Blank if it is no longer offered).
        restoreIdentity = savedInstanceState?.getString(STATE_TEMPLATE_IDENTITY)
        if (binding.templateGroup.checkedRadioButtonId == -1) binding.radioBlank.isChecked = true

        lifecycleScope.launch { discoverTemplates() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedTemplate()?.let { outState.putString(STATE_TEMPLATE_IDENTITY, it.identity) }
    }

    /**
     * Templates come only from discovered, trusted providers ([ExtensionRegistry]); the core has no
     * renderer. A provider that fails to list is skipped silently (log only). The section appears only
     * if at least one template exists; otherwise it stays GONE and the notebook is created blank.
     */
    private suspend fun discoverTemplates() {
        val providers = try {
            ExtensionRegistry.templateProviders(this)
        } catch (e: Exception) {   // a PackageManager hiccup must not take the screen down: Blank only
            Slog.d(TAG) { "discovery failed: ${e.message}" }
            return
        }
        val listed = ArrayList<Pair<ProviderRef, List<TemplateInfo>>>(providers.size)
        for (ref in providers) {
            val templates = try {
                TemplateProviderClient(this, ref).list()
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "provider ${ref.packageName} skipped: ${e.message}" }
                continue
            }
            if (templates.isNotEmpty()) listed += ref to templates
        }
        if (listed.isEmpty()) return
        val group = binding.templateGroup
        for ((ref, templates) in listed) {
            if (listed.size > 1) group.addView(providerHeading(ref))
            for (t in templates) {
                val radio = layoutInflater.inflate(R.layout.item_template_radio, group, false) as RadioButton
                val choice = TemplateChoice(ref, t.id, t.name)
                radio.text = t.name
                radio.tag = choice
                group.addView(radio)
                if (choice.identity == restoreIdentity) radio.isChecked = true
            }
        }
        restoreIdentity = null
        binding.templateSection.isVisible = true
    }

    private fun providerHeading(ref: ProviderRef): TextView = TextView(this).apply {
        text = ref.label
        setTextAppearance(R.style.TextAppearance_Notesprout_BodyMedium)
    }

    private fun defaultName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    /** The checked provider radio's [TemplateChoice], or null for Blank. */
    private fun selectedTemplate(): TemplateChoice? {
        val id = binding.templateGroup.checkedRadioButtonId
        if (id == -1) return null
        return binding.templateGroup.findViewById<RadioButton>(id)?.tag as? TemplateChoice
    }

    /**
     * A name the screen won't accept (rule or duplicate) is a dialog, not a toast: CREATE was tapped
     * and nothing happened — a toast is easy to miss on e-ink and reads as "creation failed".
     */
    private fun showNameProblem(message: String) = showProblem(getString(R.string.new_notebook_name_problem_title), message)

    private fun showProblem(title: String, message: String) = Dialogs.problem(this, title, message)

    private fun attemptCreate() {
        if (creating) return
        val name = binding.nameField.text.toString().trim()
        val err = validateName(name)
        if (err != null) {
            showNameProblem(err)
            return
        }
        creating = true
        binding.btnCreate.text = getString(R.string.new_notebook_creating)
        binding.btnCreate.isClickable = false
        val choice = selectedTemplate()
        lifecycleScope.launch {
            if (repo.nameTaken(parentFolderId, ObjectType.NOTEBOOK, name)) {
                resetCreateButton()
                showNameProblem(getString(R.string.new_notebook_duplicate))
                return@launch
            }
            // Render BEFORE any file is created. On failure stay on the screen — never silently
            // downgrade to Blank; the user chose a template.
            var webp: ByteArray? = null
            if (choice != null) {
                webp = try {
                    TemplateProviderClient(this@NewNotebookActivity, choice.provider)
                        .render(choice.id, pageWidthPx(), pageHeightPx(), dpi())
                } catch (e: ExtensionCallException) {
                    Slog.d(TAG) { "render ${choice.identity} failed: ${e.message}" }
                    null
                }
                if (webp == null || webp.isEmpty()) {
                    resetCreateButton()
                    showProblem(getString(R.string.new_notebook_template_failed_title), getString(R.string.new_notebook_template_failed))
                    return@launch
                }
            }
            val notebookId = withContext(Dispatchers.IO) { createNotebook(name, choice, webp) }
            if (relayMode) {
                // The created identity goes back through the relay + ILinkCatalog.takeCreatedNotebook
                // — never the result Intent (arc 7 / L3; the pick flow's no-data-on-Intents rule).
                LinkCreateRelay.setCreated(notebookId, name)
                setResult(Activity.RESULT_OK)
            } else {
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                    putExtra(EXTRA_NOTEBOOK_NAME, name)
                })
            }
            finish()
        }
    }

    private fun resetCreateButton() {
        creating = false
        binding.btnCreate.text = getString(R.string.new_notebook_create)
        binding.btnCreate.isClickable = true
    }

    private fun pageWidthPx(): Int = resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
    private fun pageHeightPx(): Int = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
    private fun dpi(): Float = resources.displayMetrics.densityDpi.toFloat()

    /**
     * [choice] + [webp] travel together: both null for Blank, both non-null for a templated notebook
     * (the WEBP was rendered by the extension before this is called).
     */
    private suspend fun createNotebook(name: String, choice: TemplateChoice?, webp: ByteArray?): String {
        val notebookId = UUID.randomUUID().toString()
        val passphrase = KeySession.get() ?: error("No key session")
        val file = soilFile(this, notebookId)
        val now = System.currentTimeMillis()

        val pageW = pageWidthPx()
        val pageH = pageHeightPx()
        val templateKind = choice?.identity ?: SoilSchema.TEMPLATE_BLANK

        val db = withContext(Dispatchers.IO) { SoilDatabase.create(this@NewNotebookActivity, notebookId, file, passphrase) }
        try {
            val dao = db.dao()
            val pageId = UUID.randomUUID().toString()

            dao.upsert(SoilObjectEntity(
                id = notebookId, parentId = SoilSchema.ROOT_PARENT, type = SoilSchema.TYPE_NOTEBOOK,
                createdAt = now, updatedAt = now, text = name, refId = pageId,
            ))

            var templateId: String? = null
            if (choice != null && webp != null) {
                templateId = UUID.randomUUID().toString()
                dao.upsert(SoilObjectEntity(
                    id = templateId, parentId = notebookId, type = SoilSchema.TYPE_TEMPLATE,
                    createdAt = now, updatedAt = now, text = choice.identity,
                    width = pageW.toFloat(), height = pageH.toFloat(), blob = webp,
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

        repo.createNotebook(notebookId, name, parentFolderId, templateKind, pageCount = 1, now = now)
        return notebookId
    }

    companion object {
        private const val TAG = "NewNotebookActivity"
        private const val STATE_TEMPLATE_IDENTITY = "templateIdentity"
        const val EXTRA_PARENT_FOLDER_ID = "parentFolderId"
        const val EXTRA_DEFAULT_NAME = "defaultName"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        private val NAME_PATTERN = Regex("^[a-zA-Z0-9_\\-. ]+$")

        fun validateName(name: String): String? = when {
            name.isEmpty() -> "Name cannot be empty"
            name == "." || name == ".." -> "Invalid name"
            !NAME_PATTERN.matches(name) -> "Only letters, numbers, spaces, hyphens, underscores, and dots"
            else -> null
        }

        /** [candidate] if it satisfies [validateName] and is ≤ [ExtensionContract.MAX_NAME_CHARS] chars, else null. */
        fun acceptDefaultName(candidate: String?): String? =
            candidate?.takeIf { validateName(it) == null && it.length <= ExtensionContract.MAX_NAME_CHARS }

        fun intent(context: Context, parentFolderId: String?, defaultName: String? = null): Intent =
            Intent(context, NewNotebookActivity::class.java).apply {
                putExtra(EXTRA_PARENT_FOLDER_ID, parentFolderId)
                if (defaultName != null) putExtra(EXTRA_DEFAULT_NAME, defaultName)
            }
    }
}
