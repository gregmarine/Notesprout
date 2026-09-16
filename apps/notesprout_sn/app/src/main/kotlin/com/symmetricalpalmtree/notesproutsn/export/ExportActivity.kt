package com.symmetricalpalmtree.notesproutsn.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.ExportKeying
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.ExportPrefs
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.cloud.CloudBrowserDialog
import com.symmetricalpalmtree.notesproutsn.cloud.CloudBrowserRules
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityExportBinding
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudConnectEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudNotConnectedException
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExporterClient
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookActivity
import com.symmetricalpalmtree.notesproutsn.notebook.PageLabels
import com.symmetricalpalmtree.notesproutsn.notebook.HeadingRows
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Export** (arc 15 / E1) — the host's whole side of getting a notebook out of the app.
 *
 * **Over the ~800-line rule, with reason — three growths, one justification (arc 19 / M9 · arc 25 /
 * V3 · arc 31 / HV1):** the first was the third source kind (the Source row, the `hasDocument` gate
 * and the document/preview branches of the prepare step); the second is the **cloud destination**
 * (the Destination row, the browser and replace-by-name confirmation in place of the SAF picker, and
 * the upload leg after verification); the third is **per-page delivery** (the folder pick on both
 * legs and the loop that follows it — step 4c below). All three stay here because the screen's one
 * `runExport` flow is the
 * invariant: the guard order, the keying lifecycle, the conditional-deletion rule and the per-kind
 * verification all run in one sequence that must not be split across files to be auditable — every
 * reviewer of a keying or deletion change reads the whole flow, and a split would hide half of it.
 * The cloud destination makes that stricter, not looser: the file the exporter writes and the bytes
 * that go up are the same bytes, and the only way to see that is to read the sequence whole. What
 * *is* split out is everything pure — [ExportDestination]'s rules, [CloudBrowserRules]'s, and
 * [ExportVerification.cloudVerdict] — and the browser, which is a screen, not a step of this flow.
 *
 * The seam in one sentence: *the host keys, the extension delivers.* This screen owns the entry, the
 * choice of format, the options panel, the transient checkpoint, the cache copy and the SAF
 * destination; an exporter extension receives two `ParcelFileDescriptor`s and an [ExportSpec] and
 * produces the bytes. No passphrase, path or SQLCipher ever crosses.
 *
 * What the screen does, in order, and each step is a rule:
 *
 *  1. **Discover and describe.** Every trusted exporter is asked what it offers. A `describe()` that
 *     fails, or a descriptor this build cannot draw ([ExportOptions.isRenderable] — E1 renders
 *     single-choice and toggle, not passphrase), **drops that exporter with a log line, never a
 *     crash**. None left is a problem dialog and the screen closes: a screen with nothing on it
 *     would be a tap that did nothing.
 *  2. **Show what there is to decide.** One exporter collapses the chooser to a plain label, and a
 *     single-choice option with one choice collapses the same way — a control that cannot be
 *     operated reads as broken, not as settled. Re-run on every resume, because a package can be
 *     disabled or replaced under a standing screen.
 *  2b. **Ask where it goes** (arc 25 / V3). The Destination row exists only while a trusted cloud
 *     provider is installed ([ExportDestination]) — **GONE otherwise, never disabled** — and its
 *     `status()` is re-asked at every discovery, because the Connect door changes it under a
 *     standing screen. A standing *cloud* answer is forced back to *local* the moment the row
 *     leaves, exactly as the Source row's answer is.
 *  3. **Export → the picker.** SAF `ACTION_CREATE_DOCUMENT` with the exporter's MIME type and the
 *     filename [ExportNaming] made from the index name. (An `ActivityResultContracts.CreateDocument`
 *     takes its MIME at *registration*, and this screen does not know it until `describe()` has
 *     answered — so it is the family's explicit-Intent form, as the templates screen uses.)
 *     **Or → the cloud browser** ([CloudBrowserDialog]) when the destination is the cloud: the
 *     filename is [ExportNaming]'s and is not offered for editing (there is no field to offer it
 *     in), so a folder already holding that name gets the *Replace <name>?* dialog that stands in
 *     for SAF's overwrite confirmation — an upload is replace-by-name, and a silent replace is not
 *     the family's way. A cancel anywhere in there is the picker's cancel, to the letter.
 *  4. **Prepare, key, hand over, verify.** What gets prepared depends on the exporter's declared
 *     source kind, and that is the only place the kinds differ (arc 18 / D1): a
 *     [ExporterContract.SOURCE_SOIL] exporter streams the `.soil` — [ExportArtifact] seals a cold
 *     copy into the cache and [ExportKeying] runs the reserved keying option's transform on it (E2
 *     — the exporter never learns which, and the typed passphrase stays in this process) — while a
 *     [ExporterContract.SOURCE_PAGES] exporter never could (no key crosses), so [ExportRender]
 *     draws every page here and hands over a bundle of images, with no keying step at all. Arc 19 /
 *     M9 adds the notebook's **document** to both halves of that: a
 *     [ExporterContract.SOURCE_DOCUMENT] exporter receives the final text bytes [ExportText]
 *     assembles (host-executed format choice and all), and a page exporter can be pointed at the
 *     document instead of the ink by the host's own Source row — [DocumentPdfRender] lays the
 *     Markdown out on white pages and hands over an ordinary bundle, so the extension never learns
 *     a second kind of page exists. From the fds on, the flow does not ask which it was. Then
 *     `export()` runs under its own timeout and [ExportVerification] judges the result **per source
 *     kind** before anything says the word "exported". An exporter that died mid-stream must never
 *     read as success.
 *  4b. **Upload, for a cloud destination** (arc 25 / V3). The exporter never learns the difference:
 *     it is handed a write fd on a file in this app's own export cache, and step 4's verification
 *     runs against that file's real length exactly as it always has. Only then does the flow send
 *     the bytes — `CloudClient.upload`, replace-by-name — and judge the provider's account of them
 *     with [ExportVerification.cloudVerdict], which is corroboration and never authority: a
 *     disagreement is *check the file*, **never** a delete. Nothing in this phase deletes anything
 *     in the cloud, and every failure before the upload says so in as many words.
 *  4c. **Per-page delivery (arc 31 / HV1).** A raster exporter (PNG) has no notion of a multi-page
 *     document, so it declares [ExporterContract.DELIVERY_PER_PAGE] and, at a scope of more than one
 *     page ([ExportDelivery.perPage]), the destination is a **folder** rather than a file: a SAF tree
 *     locally, the picked cloud folder on the other leg. The seam does not move an inch — the
 *     exporter is still called once per file, with one page in front of it: the render bakes the
 *     whole bundle exactly as it always has and [BundleSplit] re-frames it into one-page bundles,
 *     so each call, each [ExportVerification] verdict and each destination is the single-file one
 *     this flow already ran. What is new is only the loop around them: a name per page
 *     ([ExportNaming.pageStem] over the titles the bake read), an [ExportSpec] per page (the values
 *     are shared, the name is not), and the honest arithmetic when one of them fails — the files
 *     already written are **kept**, the failing one is removed where the host made it, and the
 *     dialog says *N of M*. Never a silent partial success.
 *  5. **Confirm and finish**, back to the library: a dialog, not a toast, because this screen is
 *     closing under it and a toast would confirm something the user no longer has a screen to read.
 *     Every failure instead explains itself in a dialog naming what went wrong —
 *     never a path, never a secret — and removes the half-written destination where removing it
 *     cannot cost the user anything: a pre-existing file the picker offered to overwrite is
 *     deleted only after the truncating open has already destroyed its old content, and the
 *     dialog says what actually happened to the file either way.
 */
class ExportActivity : AppCompatActivity(), ExportPresetRow.Host {

    private lateinit var binding: ActivityExportBinding
    private lateinit var panel: ExportPanel

    /** **The Preset row** (arc 31 / HV3) — the saved combinations, and every view and dialog that
     *  belongs to them. This screen keeps only its side of the contract ([ExportPresetRow.Host]). */
    private lateinit var presets: ExportPresetRow

    /** True only while [applyPreset] writes the panel's answers: those writes run through the same
     *  fields a tap does, and a preset applying itself must not read as the user dropping the pick. */
    private var applyingPreset = false

    /** The cloud folder this export is aimed at (names under the provider's root), or **null** =
     *  *chosen at export*, which is what every cloud export did before this arc. Screen state, so
     *  a preset can carry it (arc 31 / HV3): kept across a flip to Local, and forced to null only
     *  when the Destination row itself leaves. */
    private var cloudPath: List<String>? = null
    private val repo by lazy { IndexRepository() }
    private val exportPrefs by lazy { ExportPrefs(this) }

    private lateinit var notebookId: String
    private lateinit var notebookName: String

    /**
     * **The page-sheet door** (arc 30 / PE2): the page this screen was opened *for*, or null from
     * the library. Non-null seeds [scope] to that page and puts the Scope row on screen; null is
     * the library door exactly as it has always been — whole notebook, no row.
     */
    private var pageId: String? = null

    /** Whether this screen was entered by closing a notebook that expects to come back (the
     *  page-sheet door, decision 5): every way out — Cancel, done, a problem dialog, a cancelled
     *  passphrase — relaunches the notebook first ([finish]). Never set by the library door. */
    private var returnToNotebook = false

    /** The relaunch is owed once; `finish()` can be reached twice (a dialog's dismiss after an
     *  explicit call). */
    private var relaunched = false

    /**
     * **The calendar door** (arc 31 / HV4): the calendar page this screen was opened *for*, or null
     * for either notebook door. Non-null is *calendar mode* — [notebookId] is empty, no `.soil` is
     * ever opened, and every question this screen asks about a notebook is simply not asked.
     *
     * It comes off the Intent (a kind, a date and a half — host-internal, and no more a secret than
     * a date is), so there is nothing to save into instance state: a screen rebuilt behind the
     * picker reads it again from the Intent that is still there.
     */
    private var calendarTarget: CalendarTarget? = null

    /** Calendar mode: everything about this screen that reads a notebook is skipped. */
    private val calendarMode: Boolean get() = calendarTarget != null

    /** The one trusted calendar, found at every discovery like every other provider — a package can
     *  be disabled or replaced under a standing screen, and in calendar mode a screen with no
     *  calendar behind it has nothing to draw. Never saved. */
    private var calendarRef: ProviderRef? = null

    /** What the export covers (arc 30 / PE2). Owned by the host like the pick, saved and restored
     *  with it, and only ever [ExportScope.Page] while [pageId] is set, or [ExportScope.Calendar]
     *  in calendar mode (where it is the door's whole question and never changes). */
    private var scope: ExportScope = ExportScope.Whole

    /** What the one open answered about the door's page (arc 30 / PE2): its 1-based position, its
     *  topmost heading (the Contents rule), whether it has its own document, and whether it carries
     *  a sketch (arc 43 / K7 — a per-page exporter then writes two files, [ExportDelivery.perPage]).
     *  Null when the page is not among the notebook's live pages any more. */
    private class PageFacts(val number: Int, val title: String?, val hasDocument: Boolean, val hasSketch: Boolean)

    /**
     * Everything the **one** `.soil` open answers, together (arc 34 / L14 — they had been three
     * fields and a fourth saying whether the third had been asked, which could only ever describe
     * the same read): whether the notebook holds a document, whether it holds a sticky note with
     * content, and, from the page-sheet door, that page's own facts.
     *
     * Kept for the life of the screen (the M11 review). The exporter re-discovery on every resume
     * is deliberate — a package can be disabled or replaced under a standing screen — but **none
     * of these** can change while the screen stands: Export is only ever entered with the notebook
     * closed (from the library, or from the page sheet after the notebook has closed itself — arc
     * 30 / PE2), and there is no way from here into the notebook or the document editor. Re-asking
     * was a full SQLCipher open, KDF and all, per resume for booleans that were already known.
     *
     * Null while unanswered, which includes a read that could not answer — "cannot answer" is not
     * an answer worth remembering, and `null` is the whole gate.
     */
    private class NotebookAnswers(val hasDocument: Boolean, val hasSticky: Boolean, val page: PageFacts?)
    private var answers: NotebookAnswers? = null

    /** One installed exporter, what it said it offers, and how many files it delivers per export
     *  ([ExportDelivery.delivery] — the descriptor's tail, read only from a service that declares
     *  it can be split, so the answer is settled once at discovery and never re-derived). */
    private class Candidate(val ref: ProviderRef, val info: ExporterInfo, val delivery: Int)

    /** Every exporter the last discovery described and this build can draw — before scope. The
     *  Scope row flips [candidates] out of this list without asking the extensions again. */
    private var described: List<Candidate> = emptyList()

    private var candidates: List<Candidate> = emptyList()

    /** The pick is state the **host** owns (the G6 lesson) — saved, restored, re-matched by package. */
    private var chosenPackage: String? = null

    /** Option id → chosen value, for the exporter in [chosenPackage]. Validated again at spec time. */
    private val values = LinkedHashMap<String, String>()

    /** Whether what is being exported has anything written in it (arc 19 / M9) — it decides both
     *  what is listed and whether the Source row exists. Derived, never stored: the notebook-wide
     *  answer at [ExportScope.Whole], the page's own at [ExportScope.Page] (arc 30 / PE2 — a page
     *  export of the Document is that page's document or nothing). */
    private val hasDocument: Boolean
        get() = when (scope) {
            is ExportScope.Page -> answers?.page?.hasDocument ?: false
            ExportScope.Whole -> answers?.hasDocument ?: false
            // A calendar page is ink. There is no document behind it and no Source row to ask about
            // one (arc 31 / HV4) — and no notebook was opened to find out.
            is ExportScope.Calendar -> false
        }

    /** Whether this notebook holds a sticky note with content (arc 28 / D7) — read on the same one
     *  open as everything else in [NotebookAnswers]. It decides one line: a page exporter that
     *  reads only the version-1 bundle gets the notes as icons, and the screen says so before the
     *  tap rather than after. */
    private val hasStickyContent: Boolean
        get() = answers?.hasSticky ?: false

    /** The host's own Source answer for a [ExporterContract.SOURCE_PAGES] exporter: false = the
     *  notebook's pages (what this screen has always exported), true = the document laid out on
     *  paper. Saved and restored like the pick, and forced back to false whenever the row that
     *  asks the question is not on screen — a standing true must not survive a switch to an
     *  exporter that never offered it. */
    private var documentSource = false

    /** The host's own Destination answer (arc 25 / V3): local, or the one installed cloud provider.
     *  Saved and restored like the pick, and forced back to local whenever the row that asks the
     *  question is not on screen ([ExportDestination.settled]) — the Source row's rule, for the
     *  same reason: a provider uninstalled under a standing screen must not leave an export aimed
     *  at a cloud that is no longer there. */
    private var destinationChoice = ExportDestination.Choice.LOCAL

    /** The connect door, and the provider behind it. Registered in `onCreate` (a launcher may not
     *  be registered later) and closed in `onDestroy` — the Backup screen's backstop, so a bind
     *  cannot outlive the screen that opened it. */
    private var cloud: CloudConnectEntry? = null

    /** The provider found at the last discovery, or null. **Not saved into instance state** — a
     *  package can be disabled or replaced under a standing screen, so the answer is only ever the
     *  fresh one. */
    private var cloudRef: ProviderRef? = null

    /** What that provider last said about its account; null when it did not answer. Re-asked at
     *  **every** discovery on purpose (unlike `hasDocument`, which cannot change under this
     *  screen): the Connect door changes it, and a stale "connected" would send an export at an
     *  account that has since been disconnected. */
    private var cloudStatus: CloudStatus? = null

    /** Set by the connect result so the next discovery can adopt the cloud answer for the person —
     *  they went to the trouble of signing in from this screen's own offer. Cleared as it is read. */
    private var selectCloudOnDiscovery = false

    /** True from the Export tap until the flow ends. A second tap in the e-ink feedback gap does
     *  nothing — and it also stands down the resume-time re-discovery, which would otherwise
     *  rebuild the panel under an export that is already running. */
    private var busy = false

    private var discovering = false

    /** The passphrase typed for a *rekey*, held only from the Export tap to the end of the flow.
     *  It is never saved into instance state, never put in an Intent, never logged — the host
     *  collects it, the host consumes it, and nothing about it crosses the exporter seam. */
    private var typedPassphrase: String? = null

    /**
     * **The key this screen reads the notebook with** (arc 26 / U4) — resolved once, at the first
     * discovery, and held for this screen's lifetime.
     *
     * A `GLOBAL` notebook resolves prompt-free ([SoilDatabase.resolve], which also carries a
     * rotation's second candidate). A `NOTEBOOK`-scope one is asked for **once, up front**
     * ([NotebookPassphrasePrompt]) rather than at each of the three or four reads this screen makes
     * — the document question, the artifact copy, the keying transform — because being asked the
     * same passphrase three times to export one notebook is not a security property, it is a bug.
     * A cancelled prompt leaves quietly: it is an answer, not an error.
     *
     * The same hygiene as [typedPassphrase] and [typedExportSecret], and for the same reasons: it
     * dies with the screen, is **never** written to `onSaveInstanceState`, never put in an Intent,
     * never logged, and never reaches [KeySession] — a notebook passphrase is not the device's.
     * A screen rebuilt behind the picker asks again, exactly as the rekey field is re-collected.
     */
    private var sourceKey: KeyResolver.Resolved? = null

    /** The scope [sourceKey] answers for — what tells the keying step whose passphrase the source
     *  file is under (a `NOTEBOOK` notebook's is the typed one, never the session's). */
    private var sourceScope: KeyScope = KeyScope.GLOBAL

    /** The password typed for a *protected* export (arc 18 / D2) — the same lifecycle as
     *  [typedPassphrase] to the letter, and for the same reasons, with one difference: this one is
     *  handed to the exporter on [ExportSpec.exportSecret], because protecting the output is the
     *  extension's work. It is still never saved, never in an Intent, never logged. */
    private var typedExportSecret: String? = null

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runExport(Destination.Saf(uri))
        } else {
            cancelledAtThePicker()
        }
    }

    /**
     * The **folder** door for a per-page export (arc 31 / HV1) — SAF's tree pick rather than a
     * document creation: the host names every file after its own page, and there is no field here
     * to type a name into. Registered beside [saveLauncher] for the same reason it is: a launcher
     * may not be registered after STARTED.
     *
     * **No `takePersistableUriPermission`** — the grant is for this flow. An export has no business
     * remembering a folder the person opened once, and a permission held past the flow is a
     * permission nobody asked to give.
     */
    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runExport(Destination.SafTree(uri))
        } else {
            cancelledAtThePicker()
        }
    }

    /**
     * The picker (SAF's, or the cloud browser's, or the replace confirmation) came back with no
     * destination. Nothing was created, nothing to explain, the screen stays.
     *
     * The secret collected at the tap goes with the flow it was collected for — its documented
     * lifetime ends here, not at the next tap (arc-15 review) — and the latch comes off.
     */
    private fun cancelledAtThePicker() {
        typedPassphrase = null
        typedExportSecret = null
        busy = false
        Slog.d(TAG) { "destination picker cancelled" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID).orEmpty()
        notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty()
        // A malformed extra reads as no target at all (CalendarTarget's constructor is the
        // validation, both directions) — and with no notebook either, there is nothing to export.
        calendarTarget = parseCalendarTarget(intent.getStringExtra(EXTRA_CALENDAR_TARGET))
        if (notebookId.isEmpty() && calendarTarget == null) { finish(); return }
        pageId = intent.getStringExtra(EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }
        returnToNotebook = intent.getBooleanExtra(EXTRA_RETURN_TO_NOTEBOOK, false)
        scope = calendarTarget?.let { ExportScope.Calendar(it) } ?: ExportScope.seeded(pageId)

        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The guard is 0 on Ratta — chrome sits flush at the top edge; the inset pass is still how
        // the screen clears a navigation bar if the device has one.
        TopGuard.applyInsetPadding(binding.root)
        panel = ExportPanel(this)
        presets = ExportPresetRow(this, panel, binding.presets, repo, this)

        // The title stays "Export"; the line under it says what is being exported — the notebook's
        // name, or, in calendar mode, the calendar and the page it came from (arc 31 / HV4).
        binding.notebookName.text = calendarTarget
            ?.let { getString(R.string.export_calendar_line, CalendarRenderPlan.of(it, false).label) }
            ?: notebookName
        // Leaving mid-export would cancel the flow past its verification and cleanup while the
        // extension's un-cancellable Binder stream keeps writing — an unverified file standing
        // silently (arc-15 review). Both doors out are latched on busy; the dialog explains why
        // the tap did nothing (the toast-vs-dialog rule).
        binding.btnBack.setOnClickListener { if (busy) showBusyGuard() else finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) showBusyGuard() else finish()
            }
        })
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnExport.setOnClickListener { onExportTap() }

        // Registered here and nowhere else: a launcher may not be registered after STARTED, so the
        // door has to exist before anyone can be offered it. A sign-in that succeeded takes the
        // cloud answer with it at the discovery its result triggers.
        cloud = CloudConnectEntry(this) { wasConnected ->
            if (wasConnected) selectCloudOnDiscovery = true
            discover()
        }

        savedInstanceState?.let { state ->
            chosenPackage = state.getString(KEY_PACKAGE)
            documentSource = state.getBoolean(KEY_SOURCE)
            // Whole is restorable only where the row that offers it is; Page is the seed, and
            // calendar mode has no row at all — its scope is the door's, never the person's.
            if (!calendarMode && state.getBoolean(KEY_SCOPE_WHOLE)) scope = ExportScope.Whole
            if (state.getBoolean(KEY_DESTINATION)) destinationChoice = ExportDestination.Choice.CLOUD
            state.getBundle(KEY_VALUES)?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { values[k] = it } } }
            // The preset pick survives a rebuild behind the picker exactly as the format pick does.
            presets.selectedId = state.getString(KEY_PRESET)
            state.getStringArrayList(KEY_CLOUD_PATH)?.let { cloudPath = it.toList() }
        }
        discover()
    }

    override fun onResume() {
        super.onResume()
        // A package can be disabled or replaced under a standing screen — but not under a running
        // export, which is also the resume that arrives when the picker comes back.
        if (!busy && ::binding.isInitialized) discover()
    }

    override fun onDestroy() {
        // The guard bounce still runs this callback, and the dialog is attached to this window —
        // leaving it up past the teardown leaks it.
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        hideProgress()
        // The browser is attached to this window, and the connect bind must not outlive the screen
        // that opened it (the Backup screen's backstop).
        browser?.dismiss()
        browser = null
        cloud?.close()
        cloud = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PACKAGE, chosenPackage)
        outState.putBoolean(KEY_SOURCE, documentSource)
        outState.putBoolean(KEY_SCOPE_WHOLE, scope is ExportScope.Whole)
        outState.putBoolean(KEY_DESTINATION, destinationChoice == ExportDestination.Choice.CLOUD)
        outState.putBundle(KEY_VALUES, Bundle().also { b -> values.forEach { (k, v) -> b.putString(k, v) } })
        if (::presets.isInitialized) outState.putString(KEY_PRESET, presets.selectedId)
        cloudPath?.let { outState.putStringArrayList(KEY_CLOUD_PATH, ArrayList(it)) }
    }

    /**
     * **The reopen** (arc 30 / PE2, decision 5). From the page-sheet door the notebook was closed
     * to let this screen read a cold file, and a notebook the person was in is a notebook they
     * come back to — whether the export was written, cancelled or refused. Every way out of this
     * screen already funnels through `finish()` (Back, the done dialog's dismiss, `problemAndClose`,
     * the cancelled passphrase prompt), so the relaunch lives here once rather than at each. It
     * opens at its bookmark, which the close wrote for the page the sheet was on. The library door
     * never sets the flag and is untouched; a guard bounce finishes before the flag is read.
     */
    override fun finish() {
        if (returnToNotebook && !relaunched && ::notebookId.isInitialized && notebookId.isNotEmpty()) {
            relaunched = true
            startActivity(NotebookActivity.intent(this, notebookId, notebookName))
        }
        super.finish()
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    /**
     * Ask every trusted exporter what it offers. Inward is untrusted: a call that fails and a
     * descriptor over the [ExporterContract] caps both surface as an exception inside `describe()`,
     * and either way that exporter is simply not in the list.
     */
    private fun discover() {
        if (discovering) return
        discovering = true
        lifecycleScope.launch {
            val kept = try {
                loadCandidates()
            } finally {
                discovering = false
            }
            // Never under a running export (arc-15 review): a screen rebuilt behind the picker
            // starts this discovery from onCreate, and the pending SAF result lands before the
            // continuation — a substitution or a problemAndClose here would swap the exporter
            // (values reset, keying back to Keep) or close the screen under the flow. runExport
            // does its own no-substitution reselect.
            if (busy || isFinishing || isDestroyed) return@launch
            candidates = kept
            // Re-listed at every discovery (arc 31 / HV3): an exporter disabled under a standing
            // screen takes its presets with it, and re-enabling brings them back.
            presets.reload()
            Slog.d(TAG) { "${kept.size} usable exporter(s)" }
            if (kept.isEmpty()) {
                problemAndClose(R.string.export_none_title, R.string.export_none_body)
                return@launch
            }
            // Calendar mode with no calendar behind it (arc 31 / HV4): the package was disabled or
            // removed while this screen stood, and the pages it would draw cannot be asked for.
            // GONE is for a button; a screen that cannot do the one thing it is for says so.
            if (calendarMode && calendarRef == null) {
                problemAndClose(R.string.export_calendar_failed_title, R.string.export_calendar_gone_body)
                return@launch
            }
            // The connect offer's follow-through: the person signed in from this screen, so the
            // answer they were reaching for is taken for them. Only when the fresh status agrees —
            // a sign-in that came back OK but does not read as connected is not an answer.
            if (selectCloudOnDiscovery) {
                selectCloudOnDiscovery = false
                if (cloudStatus?.connected == true) destinationChoice = ExportDestination.Choice.CLOUD
            }
            reselect()
        }
    }

    /**
     * Pick the exporter the panel shows from [candidates]: the standing choice if it is still
     * listed, else the one the last successful export used (the user's 2026-08-30 call — discovery
     * order is PackageManager's and means nothing), else the first. A re-discovery keeps what the
     * user already answered — the descriptor is usually the same one — and falling back to another
     * exporter starts from its own defaults. Also the Scope row's road (arc 30 / PE2): a remembered
     * Soil at page scope is not listed and falls to the first shown, as the rule already says.
     */
    private fun reselect() {
        val standing = candidates.firstOrNull { it.ref.packageName == chosenPackage }
        val remembered = candidates.firstOrNull { it.ref.packageName == exportPrefs.lastExporter }
        select(standing ?: remembered ?: candidates.first(), keepValues = standing != null)
    }

    /** [described] narrowed to what [scope] lists (arc 30 / PE2): the document-format gate against
     *  the scope's own document answer, and the Soil rule ([ExportScope.lists]). */
    private fun listedNow(): List<Candidate> = described.filter { c ->
        ExportDocumentRules.listed(c.info.sourceKind, hasDocument) && ExportScope.lists(c.info.sourceKind, scope)
    }

    /**
     * The Scope row's tap. The candidate list is re-cut from [described] — no extension is asked
     * again — and the pick re-settled; a pick that survives keeps its values, one that does not
     * starts from the new exporter's defaults (the chooser's own rule).
     */
    private fun setScope(next: ExportScope) {
        if (next == scope) return
        scope = next
        candidates = listedNow()
        // Not a hand change — the scope is the door's question, never a preset's. The row re-cuts
        // itself to what is listed now and drops the pick only if its exporter went with the scope.
        presets.recut()
        if (candidates.isEmpty()) {
            // Cannot happen while the row is on screen (it is offered only when page scope lists
            // something, and Whole lists everything) — but a screen must never stand empty.
            problemAndClose(R.string.export_none_title, R.string.export_none_body)
            return
        }
        reselect()
    }

    /** Discovery + `describe()` with nothing of the screen in it, so the export flow can run it too
     *  when it finds itself rebuilt behind the picker with no descriptors in hand.
     *
     *  It is also the shared door for the **document question** (arc 19 / M9), asked here rather
     *  than in `discover()` for exactly that reason: the flow's own re-discovery must come back
     *  knowing the same thing the panel did. [SoilDatabase.readOnce] is safe from this screen —
     *  Export is only ever entered with the notebook closed (both doors, arc 30 / PE2) — and its
     *  null means
     *  "cannot answer", which is not an answer to build a chooser row on. Asked **once** and then
     *  remembered ([answers]): the exporters can change under a standing screen, the document
     *  cannot.
     *
     *  It is also where the notebook's own key is settled ([resolveSourceKey]) — **before** the
     *  document question, because that question is the screen's first read of the file. */
    private suspend fun loadCandidates(): List<Candidate> {
        // Calendar mode reads no notebook at all (arc 31 / HV4): no key to resolve, no `.soil` to
        // open, no document or sticky question to ask — the pages come from the extension. What it
        // asks instead is whether the calendar is still installed.
        if (!calendarMode && !resolveSourceKey()) return emptyList()
        loadCloud()
        if (calendarMode) calendarRef = ExtensionRegistry.calendar(this)
        if (!calendarMode && answers == null) {
            // One open answers every question (the M11 finding: a SQLCipher open per boolean) —
            // the two notebook-wide ones and, from the page-sheet door, the page's own (PE2).
            val door = pageId
            val read = SoilDatabase.readOnce(this, notebookId, sourceKey!!) { dao ->
                val facts: PageFacts? = door?.let { id ->
                    val pages = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE)
                    val index = pages.indexOfFirst { it.id == id }
                    if (index < 0) return@let null
                    // The heading-as-page-name rule, loose or link-wrapped (PageLabels): the same
                    // answer the Contents and the link picker give to "what is this page called".
                    val headings = ArrayList(dao.childrenOfType(id, SoilSchema.TYPE_HEADING))
                    for (link in dao.linksOf(id)) headings += dao.childrenOfType(link.id, SoilSchema.TYPE_HEADING)
                    val title = PageLabels.titleOf(headings.mapNotNull { HeadingRows.toHeading(it) })
                    // The page's own document row (a child of the page); blank means absent — the
                    // repository's read rule, kept by hand as ExportText keeps it.
                    val pageDoc = dao.childrenOfType(id, SoilSchema.TYPE_DOCUMENT).any { !it.text.isNullOrBlank() }
                    PageFacts(index + 1, title, pageDoc, dao.hasLiveSketch(id))
                }
                NotebookAnswers(dao.hasLiveDocument(), dao.stickyIdsWithContent().isNotEmpty(), facts)
            }
            if (read != null) answers = read
        }
        val refs = ExtensionRegistry.exporters(this)
        val kept = ArrayList<Candidate>(refs.size)
        for (ref in refs) {
            val info = describe(ref) ?: continue
            if (!ExportOptions.isRenderable(info)) {
                Slog.d(TAG) { "dropping ${ref.packageName}: an option kind this build cannot draw" }
                continue
            }
            kept += Candidate(ref, info, ExportDelivery.delivery(ref.apiVersion, info))
        }
        described = kept
        // Page scope is offered only when something serves it (ExportScope.offerable); a page door
        // over a Soil-only install falls back to the whole notebook with no row to say otherwise —
        // GONE, never a latch into an empty chooser.
        if (scope is ExportScope.Page && !ExportScope.offerable(kept.map { it.info.sourceKind })) {
            scope = ExportScope.Whole
        }
        return listedNow()
    }

    /** Whether the Scope row is on screen: the page-sheet door, and something to serve page scope. */
    private fun scopeRowVisible(): Boolean =
        pageId != null && ExportScope.offerable(described.map { it.info.sourceKind })

    /**
     * Fill [sourceKey] — the one prompt this screen may put up (arc 26 / U4).
     *
     * False means *leave*: the person cancelled the passphrase, which is an answer and not a
     * failure, so the screen finishes with no dialog behind it (the family's cancelled-prompt
     * rule). Every caller treats that as "no candidates" and the standing `isFinishing` guards
     * upstream keep the empty list from being reported as "no exporters".
     */
    private suspend fun resolveSourceKey(): Boolean {
        if (sourceKey != null) return true
        val scope = repo.keyScope(notebookId)
        sourceScope = scope
        if (scope == KeyScope.GLOBAL) {
            sourceKey = SoilDatabase.resolve(this, notebookId)
            return true
        }
        val typed = NotebookPassphrasePrompt.ask(this, notebookId, notebookName)
        if (typed == null) {
            Slog.d(TAG) { "notebook passphrase cancelled — leaving" }
            finish()
            return false
        }
        // The typed value itself, not a wait on the raw-key warm (~9 s on the Nomad): every read
        // this screen makes happens in the next few seconds.
        sourceKey = KeyResolver.Resolved.Passphrases(typed)
        return true
    }

    /** The source file's own passphrase when it has one of its own — what the keying transform
     *  must read the artifact with. Null for a `GLOBAL` notebook, whose key is the session's. */
    private fun sourceNotebookPassphrase(): String? =
        if (sourceScope == KeyScope.NOTEBOOK) {
            (sourceKey as? KeyResolver.Resolved.Passphrases)?.candidates?.firstOrNull()
        } else null

    /**
     * The cloud half of a discovery (arc 25 / V3): is a trusted provider installed, and what does
     * it say about its account.
     *
     * **Both are asked every time**, and neither is remembered across a resume: a package can be
     * disabled or replaced under a standing screen (the discovery rule the exporters already keep),
     * and the account can be connected or disconnected from the Backup screen or from this screen's
     * own offer while this one stands. A provider that does not answer keeps its row with a null
     * status — GONE is for *not installed*, and the tap will say what it can (`OFFER_CONNECT`).
     *
     * `status()` never touches the network by contract, which is what makes it cheap enough to be a
     * discovery step at all.
     */
    private suspend fun loadCloud() {
        val ref = cloud?.discover()
        cloudRef = ref
        if (ref == null) { cloudStatus = null; return }
        cloudStatus = try {
            CloudClient.status(this, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
    }

    /** What every cloud sentence on this screen calls the provider — its own name, or the
     *  extension's label when it gave none. */
    private fun cloudName(): String =
        ExportDestination.providerName(cloudStatus, cloudRef?.label?.toString().orEmpty())

    private suspend fun describe(ref: ProviderRef): ExporterInfo? = try {
        ExporterClient(this, ref).describe()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Slog.d(TAG) { "dropping ${ref.packageName}: describe failed (${e.message})" }
        null
    }

    /**
     * Adopt [c] as the chosen exporter and rebuild the panel from its descriptor. The values go
     * through [ExportOptions.specValues], which is what guarantees the panel is showing exactly
     * what an export would send: a value that is not a declared choice becomes that option's
     * default here, not silently at spec time.
     */
    private fun select(c: Candidate, keepValues: Boolean) {
        chosenPackage = c.ref.packageName
        val merged = ExportOptions.specValues(c.info, if (keepValues) values else emptyMap())
        values.clear()
        values.putAll(merged)
        // A different exporter is a different question: what was typed for the last one goes with it.
        if (!keepValues) {
            binding.editPassphrase.setText("")
            binding.editPassphraseConfirm.setText("")
        }
        render()
    }

    private fun current(): Candidate? = candidates.firstOrNull { it.ref.packageName == chosenPackage }

    // ── The panel ────────────────────────────────────────────────────────────

    /** The chooser and the options, rebuilt whole after every pick — one frame, one deliberate act. */
    private fun render() {
        val c = current() ?: return
        // First in the panel: one tap on a preset answers every question below it. The container's
        // visibility is the row's own (GONE with nothing to list and nothing to save), and a render
        // before the first reload has answered simply draws none.
        presets.render()
        // The host's first question (arc 30 / PE2), above the format because it decides which
        // formats are listed: this page, or the whole notebook. Present only from the page-sheet
        // door — the library door has no row, not a settled one (GONE, never disabled).
        binding.scope.removeAllViews()
        val scopeVisible = scopeRowVisible()
        binding.scope.visibility = if (scopeVisible) View.VISIBLE else View.GONE
        if (scopeVisible) {
            binding.scope.addView(panel.caption(getString(R.string.export_scope_caption)))
            val page = scope is ExportScope.Page
            binding.scope.addView(
                panel.choice(getString(R.string.export_scope_page), page) {
                    if (!page) setScope(ExportScope.seeded(pageId))
                }
            )
            binding.scope.addView(
                panel.choice(getString(R.string.export_scope_notebook), !page) {
                    if (page) setScope(ExportScope.Whole)
                }
            )
        }
        binding.chooser.removeAllViews()
        if (candidates.size == 1) {
            // No radio for a choice that does not exist.
            binding.chooser.addView(panel.value(c.info.formatLabel))
        } else {
            for (candidate in candidates) {
                val checked = candidate.ref.packageName == chosenPackage
                binding.chooser.addView(
                    // Re-tapping the checked radio is not a change of question: select(keepValues
                    // = false) would silently reset every option and wipe a typed secret on a
                    // grazed tap (easy on e-ink) — the D3 review's finding.
                    panel.choice(candidate.info.formatLabel, checked) {
                        if (!checked) { handChanged(); select(candidate, keepValues = false) }
                    }
                )
            }
        }

        binding.options.removeAllViews()
        // The host's own question (arc 19 / M9), above the exporter's: this notebook's pages, or
        // the document written in it. It is not an OptionDescriptor because no extension declared
        // it and none may — both answers reach the exporter as the same page bundle.
        if (ExportDocumentRules.sourceRowVisible(hasDocument, c.info.sourceKind)) {
            binding.options.addView(panel.caption(getString(R.string.export_source_caption)))
            for ((labelRes, isDocument) in SOURCE_ROWS) {
                val checked = documentSource == isDocument
                binding.options.addView(
                    // Re-tapping the checked radio is a no-op, the chooser's rule for the same
                    // reason: a grazed tap on e-ink must not rebuild the panel under a half-typed
                    // secret.
                    panel.choice(getString(labelRes), checked) {
                        if (!checked) { handChanged(); documentSource = isDocument; render() }
                    }
                )
            }
        } else {
            // The row that asks the question is gone, so the answer goes with it — a standing true
            // must never survive into an exporter that never offered the choice.
            documentSource = false
        }

        for (d in c.info.options) {
            // The page-template toggle is the render's own step, and the document render draws on
            // plain white always (the M9 call). GONE rather than disabled — the family rule — and
            // its value still crosses in the spec, where it is simply inert.
            if (documentSource && d.id == ExporterContract.OPTION_PAGE_TEMPLATE) continue
            when {
                ExportOptions.isFixed(d) -> {
                    val value = values[d.id] ?: d.defaultValue
                    binding.options.addView(panel.caption(d.label))
                    binding.options.addView(
                        panel.value(optionLabel(d, value, ExportOptions.choiceLabel(d, value)))
                    )
                }
                d.kind == ExporterContract.KIND_SINGLE_CHOICE -> {
                    binding.options.addView(panel.caption(d.label))
                    d.choiceIds.forEachIndexed { i, choiceId ->
                        binding.options.addView(
                            panel.choice(optionLabel(d, choiceId, d.choiceLabels[i]), values[d.id] == choiceId) {
                                handChanged()
                                values[d.id] = choiceId
                                render()
                            }
                        )
                    }
                }
                d.kind == ExporterContract.KIND_TOGGLE -> {
                    val on = values[d.id] == "1"
                    binding.options.addView(
                        panel.toggle(d.label, on) {
                            handChanged()
                            values[d.id] = if (on) "0" else "1"
                            render()
                        }
                    )
                }
                // No other kind reaches here: an exporter declaring one was dropped at discovery.
            }
        }

        // Arc 28 / D7: the one line a version-1 page exporter owes the user when the notebook has
        // notes with content — they go out as icons only, and the endnotes need a newer exporter.
        // Not shown for the Document source, which draws no page and so no icon.
        if (ExportDocumentRules.endnotesUnavailable(
                c.info.sourceKind, c.info.bundleVersion, hasStickyContent, documentSource && hasDocument,
            )
        ) {
            binding.options.addView(panel.caption(getString(R.string.export_endnotes_unavailable)))
        }

        // Where the finished file goes (arc 25 / V3) — the host's second question, after everything
        // about what is in the file and before the secret block, which is about neither.
        renderDestination()

        // The consequences the host owns. Both blocks are XML-static, so a half-typed secret
        // survives the rebuild the options loop above just did — which is why nothing here clears a
        // field: a mere toggle is not a change of question, and only picking another exporter is
        // (`select(keepValues = false)`).
        val info = c.info
        // One block, two tenants (E2's rekey passphrase · D2's export password), never both at
        // once — isRenderable dropped any exporter that could ask for the pair. The mode is what
        // the words say, so the caption and both hints are set here rather than in the layout.
        val protect = ExportOptions.wantsExportSecret(info, values)
        binding.passphraseBlock.visibility =
            if (ExportOptions.needsPassphrase(info, values) || protect) View.VISIBLE else View.GONE
        binding.passphraseCaption.setText(
            if (protect) R.string.export_password_caption else R.string.export_passphrase_caption
        )
        binding.editPassphrase.setHint(
            if (protect) R.string.export_password_hint else R.string.export_passphrase_hint
        )
        binding.editPassphraseConfirm.setHint(
            if (protect) R.string.export_password_confirm_hint else R.string.export_passphrase_confirm_hint
        )
        binding.plainWarning.visibility =
            if (ExportOptions.showsPlainWarning(info, values)) View.VISIBLE else View.GONE
    }

    /**
     * The label an option's choice is drawn with — the exporter's own, except for the one place the
     * host knows better (arc 26 / U5). *Keep encryption* is the extension's wording for "the file
     * goes out under the key it is under here", and for a `NOTEBOOK`-scope notebook that key is the
     * notebook's own passphrase, not this device's — so the host says which. The extension is not
     * touched for it: it declares the choice, the host executes the keying, and the sentence is the
     * host's to get right.
     */
    private fun optionLabel(d: OptionDescriptor, choiceId: String, label: String): String =
        if (d.id == ExporterContract.OPTION_KEYING &&
            choiceId == ExporterContract.KEYING_KEEP &&
            sourceScope == KeyScope.NOTEBOOK
        ) getString(R.string.export_keying_keep_notebook) else label

    /**
     * The Destination row (arc 25 / V3), rebuilt with the rest of the panel.
     *
     * The row is on screen only while a provider is installed; when it is not, the answer goes with
     * it ([ExportDestination.settled]) — the Source row's rule. The two radios are the panel's, and
     * re-tapping the checked one is a no-op for the reason the chooser has: a grazed tap on e-ink
     * must not rebuild the panel under a half-typed secret.
     */
    private fun renderDestination() {
        binding.destination.removeAllViews()
        val visible = ExportDestination.rowVisible(cloudRef != null)
        destinationChoice = ExportDestination.settled(destinationChoice, visible)
        // GONE, never disabled: with no provider there is only one place a file can go, and a
        // control that cannot be operated reads as broken rather than as settled.
        binding.destination.visibility = if (visible) View.VISIBLE else View.GONE
        // The row left the screen, so its folder goes with it — the Source row's rule again.
        if (!visible) { cloudPath = null; return }

        binding.destination.addView(panel.caption(getString(R.string.export_destination_caption)))
        val local = destinationChoice == ExportDestination.Choice.LOCAL
        binding.destination.addView(
            panel.choice(getString(R.string.export_destination_local), local) {
                if (!local) { handChanged(); destinationChoice = ExportDestination.Choice.LOCAL; render() }
            }
        )
        binding.destination.addView(
            panel.choice(cloudName(), !local) {
                if (local) onCloudDestinationTap()
            }
        )
        // The folder, under the radio that makes it mean anything (arc 31 / HV3): a value with one
        // thing behind it, defaulting to what every cloud export did before presets existed.
        if (!local) {
            val label = ExportDestination.folderLabel(cloudPath, CLOUD_EXPORTS_FOLDER)
                ?: getString(R.string.export_cloud_folder_ask)
            binding.destination.addView(
                panel.value(getString(R.string.export_cloud_folder_row, label)).apply {
                    isClickable = true
                    setOnClickListener { onFolderRowTap() }
                }
            )
        }
    }

    /** The folder row's tap: the export leg's own browser, answering nothing but the folder. **No
     *  busy latch** (this is not an export), but a second showing is guarded, and a tap during an
     *  export is not a question this screen is taking. */
    private fun onFolderRowTap() {
        if (busy || browser != null) return
        browse { pick ->
            cloudPath = pick.path
            handChanged()
            render()
        }
    }

    /** A hand-written answer anywhere on the panel drops back to *None* — unless the hand is this
     *  screen's own, applying a preset. */
    private fun handChanged() {
        if (!applyingPreset) presets.onHandChange()
    }

    /**
     * A tap on the cloud radio. Only a live connection takes the answer; everything else says why
     * not, and then the panel is rebuilt so the tick goes back where it was — a radio left standing
     * on an answer the screen refused would be the screen lying about its own state.
     */
    private fun onCloudDestinationTap() {
        when (ExportDestination.onCloudTap(cloudStatus)) {
            ExportDestination.Tap.SELECT -> {
                handChanged()
                destinationChoice = ExportDestination.Choice.CLOUD
                render()
            }
            ExportDestination.Tap.NOT_CONFIGURED -> {
                render()
                Dialogs.problem(this, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body)
            }
            ExportDestination.Tap.OFFER_CONNECT -> {
                render()
                offerConnect()
            }
        }
    }

    /**
     * The inline Connect offer — the same door the Backup screen has, put where the person is
     * standing. Two buttons and nothing else: Connect opens the provider's own sign-in, and the
     * result comes back through [CloudConnectEntry]'s callback, which re-runs discovery and takes
     * the cloud answer when the fresh status says it can.
     */
    private fun offerConnect() {
        val entry = cloud ?: return
        if (!entry.isAvailable) return
        val name = cloudName()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cloud_connect_offer_title, name))
                .setMessage(getString(R.string.cloud_connect_offer_body, name))
                .setPositiveButton(R.string.cloud_connect) { _, _ -> entry.open() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /** The flow's modal progress dialog while an export is running (the Backup screen's pattern —
     *  the user's 2026-08-30 call: the running commentary belongs in a dialog, not an inline line).
     *  Non-cancelable: leaving mid-export is exactly what the busy latch exists to prevent, and
     *  the dialog covering the screen is the latch made visible. */
    private var progress: AlertDialog? = null

    private fun showProgress(@StringRes textRes: Int) {
        if (isFinishing || isDestroyed) return
        progress = Dialogs.style(
            AlertDialog.Builder(this)
                .setMessage(textRes)
                .setCancelable(false)
                .create()
        ).also { it.show() }
    }

    private fun hideProgress() {
        progress?.let { runCatching { it.dismiss() } }
        progress = null
    }

    /** The running commentary — the stages are long enough on e-ink that a single unchanging
     *  "Exporting…" would read as a stall. Updates the dialog already up; a stage with no dialog
     *  to land on (the screen is going down) is a stage nobody is watching. */
    private fun stage(@StringRes textRes: Int) {
        progress?.setMessage(getString(textRes))
    }

    /** The same commentary with a count in it — the render's per-page line. Main thread only. */
    private fun stage(text: String) {
        progress?.setMessage(text)
    }

    // ── The export ───────────────────────────────────────────────────────────

    /**
     * Ask where to put it. The filename is offered, not imposed — the picker's field is the user's.
     *
     * The dual fields are checked **before** the picker, because a secret problem found after the
     * user has named a file is a dialog on top of a document that then has to be deleted. Every
     * refusal is a dialog, not a toast: each explains why the tap did nothing. The IME is left
     * exactly as it is — on Ratta, hiding it takes the hardware keys with it.
     */
    /** What this export is called — the notebook's name, or at page scope the page's (arc 30 /
     *  PE2, [ExportNaming.pageStem]: its heading, else `page N`). One stem for the file on disk
     *  and the name inside it. */
    private fun stem(): String = when (val s = scope) {
        ExportScope.Whole -> ExportNaming.base(notebookName, notebookId)
        is ExportScope.Page -> {
            val facts = answers?.page
            ExportNaming.pageStem(notebookName, notebookId, facts?.number ?: 0, facts?.title)
        }
        // The period, with no notebook in it and no ` AM` / ` PM` — that suffix only means
        // something when a Day's two files sit beside each other (arc 31 / HV4).
        is ExportScope.Calendar -> ExportNaming.calendarStem(s.target)
    }

    /**
     * The stem of file [index] of a per-page export (arc 31 / HV1, grown HV4 and arc 43 / K7). For
     * the notebook it is the page's own name — built from what the bake read for **that bundle
     * page**, which since sketches are interleaved is no longer the same thing as its position in
     * the list; for a calendar it is the plan's stem, used **verbatim** — a calendar page has no
     * heading to fall back on and no notebook to be prefixed with, and the ` AM` / ` PM` that tells
     * a Day's two files apart is already in it.
     */
    /** [ExportDelivery.perPage] for [c] at this screen's scope, with the door page's sketch fact
     *  (arc 43 / K7) — the one place the three doors to a folder ask it. */
    private fun perPage(c: Candidate): Boolean =
        ExportDelivery.perPage(c.delivery, scope, answers?.page?.hasSketch == true)

    private fun stemFor(index: Int, pageNames: List<ExportNaming.PageName>): String {
        val name = pageNames.getOrNull(index)
        if (scope is ExportScope.Calendar) return name?.title ?: stem()
        return ExportNaming.pageStem(
            notebookName, notebookId, name?.number ?: (index + 1), name?.title, name?.sketch == true,
        )
    }

    private fun onExportTap() {
        if (busy) { Slog.d(TAG) { "export tap ignored: already running" }; return }
        val c = current() ?: return
        // Exactly one of these can be armed (isRenderable), so the block's contents mean one thing
        // at a time; whichever is not armed leaves its holder empty rather than stale.
        val protect = ExportOptions.wantsExportSecret(c.info, values)
        val rekey = ExportOptions.needsPassphrase(c.info, values)
        typedPassphrase = null
        typedExportSecret = null
        if (rekey || protect) {
            val typed = binding.editPassphrase.text?.toString().orEmpty()
            val confirm = binding.editPassphraseConfirm.text?.toString().orEmpty()
            if (typed.isEmpty() || confirm.isEmpty()) {
                Dialogs.problem(
                    this,
                    if (protect) R.string.export_password_missing_title else R.string.export_passphrase_missing_title,
                    if (protect) R.string.export_password_missing_body else R.string.export_passphrase_missing_body,
                )
                return
            }
            if (typed != confirm) {
                Dialogs.problem(
                    this,
                    if (protect) R.string.export_password_mismatch_title else R.string.export_passphrase_mismatch_title,
                    if (protect) R.string.export_password_mismatch_body else R.string.export_passphrase_mismatch_body,
                )
                return
            }
            // The carrier's cap, refused here rather than at the ExportSpec constructor: that
            // `require` fires behind the picker, where it can only surface as the generic
            // "didn't finish" over a file the user has already named.
            if (protect && typed.length > ExporterContract.MAX_EXPORT_SECRET_CHARS) {
                Dialogs.problem(
                    this, R.string.export_password_long_title, R.string.export_password_long_body
                )
                return
            }
            if (protect) typedExportSecret = typed else typedPassphrase = typed
        }
        // The cloud fork (arc 25 / V3). The secret checks above are shared — they are about what is
        // in the file, not where it goes — and the latch is taken here for the same reason it is
        // taken for the picker: the browser is a showing, and a second Export tap under it would
        // start a second flow.
        if (destinationChoice == ExportDestination.Choice.CLOUD) {
            busy = true
            // A folder already chosen (arc 31 / HV3 — a preset's, or this screen's own folder row)
            // is not asked for again: the browser exists to answer a question that has an answer.
            val remembered = cloudPath
            if (remembered != null) listThenExport(c, remembered)
            // A per-page export cannot name its files before the bake, so it asks about the folder
            // once, up front (arc 31 / HV1) instead of about one name.
            else browse { pick ->
                if (perPage(c)) confirmFolderThenExport(pick.path)
                else confirmThenUpload(c, pick.path, pick.listing)
            }
            return
        }
        // The per-page fork (arc 31 / HV1). A folder, not a file: the host names one file per page
        // and there is nothing to type over, so the door is SAF's tree pick rather than a document
        // creation. Everything above is shared — it is about what is in the files, not how many.
        if (perPage(c)) {
            busy = true
            try {
                treeLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            } catch (e: Exception) {
                busy = false
                Log.w(TAG, "no folder picker: $e")
                Dialogs.problem(this, R.string.export_no_picker_title, R.string.export_no_picker_body)
            }
            return
        }
        // Both the type and the name come from ExportDocumentRules, not from the descriptor: a
        // document exporter's format choice is host-executed, and a `.txt` export must not be
        // offered to the picker as `text/markdown` under a `.md` name (arc 19 / M9). Every other
        // source kind keeps its descriptor's own answers.
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(ExportDocumentRules.mimeType(c.info, values))
            .putExtra(
                Intent.EXTRA_TITLE,
                ExportNaming.fileName(stem(), ExportDocumentRules.fileExtension(c.info, values)),
            )
        busy = true
        try {
            saveLauncher.launch(intent)
        } catch (e: Exception) {
            busy = false
            Log.w(TAG, "no document creator: $e")
            Dialogs.problem(this, R.string.export_no_picker_title, R.string.export_no_picker_body)
        }
    }

    /** Where the finished bytes go. The flow only parts on this twice — at the destination fd and
     *  at the end — which is what keeps the one sequence one sequence. */
    private sealed class Destination {
        /** A document the SAF picker named on this device. */
        class Saf(val uri: Uri) : Destination()

        /** A file called [name] under [path] in the provider's tree, uploaded replace-by-name. */
        class Cloud(val path: List<String>, val name: String, val mime: String) : Destination()

        /** **A folder on this device** (arc 31 / HV1) — the SAF tree a per-page export writes its
         *  documents into, one per page, named by the host. Not persisted: the grant lives for
         *  this flow. */
        class SafTree(val tree: Uri) : Destination()

        /** **A folder in the provider's tree** (arc 31 / HV1) — the per-page leg's cloud
         *  destination. One confirmation was given for the whole folder before any work started,
         *  because the names are not known until the bake has run. */
        class CloudFolder(val path: List<String>) : Destination()
    }

    /** The browser while it is up, so `onDestroy` can take it down with the screen. */
    private var browser: CloudBrowserDialog? = null

    /**
     * The cloud's stand-in for the SAF picker: browse the provider's `Exports/` tree and choose a
     * folder. The filename is not asked for — it is [ExportNaming]'s, as it is for the picker's
     * offered title, and there is nowhere here to type over it (decision 7).
     *
     * Every way out of the browser lands on one of three things, and the latch comes off in all of
     * them: a folder (which may still stop at the replace confirmation), the Connect offer (the
     * provider has no account, so there was nothing to browse), or the picker-cancel rule.
     *
     * Shared since arc 31 / HV3 by the export leg and the Destination row's own folder row — one
     * question, two uses for the answer, which is why [onFolder] is the caller's.
     */
    private fun browse(onFolder: (CloudBrowserDialog.Pick.Folder) -> Unit) {
        val ref = cloudRef
        if (ref == null) {
            cancelledAtThePicker()
            Dialogs.problem(this, R.string.export_failed_title, getString(R.string.export_cloud_gone_body))
            return
        }
        browser?.dismiss()
        val dialog = CloudBrowserDialog(
            activity = this,
            ref = ref,
            providerName = cloudName(),
            mode = CloudBrowserDialog.Mode.PICK_FOLDER,
            basePath = listOf(CLOUD_EXPORTS_FOLDER),
            onPicked = { pick ->
                browser = null
                when (pick) {
                    is CloudBrowserDialog.Pick.Folder -> onFolder(pick)
                    // PICK_FOLDER cannot answer with a file; if it ever did, it is not a place to
                    // save and the honest thing is to end the flow rather than to guess.
                    is CloudBrowserDialog.Pick.File -> cancelledAtThePicker()
                }
            },
            onNotConnected = {
                browser = null
                cancelledAtThePicker()
                // The account went away between the tap and the listing. The offer is the only
                // thing that can help, and nothing was uploaded to say otherwise.
                lifecycleScope.launch {
                    loadCloud()
                    if (isFinishing || isDestroyed) return@launch
                    render()
                    offerConnect()
                }
            },
            onCancelled = {
                browser = null
                cancelledAtThePicker()
            },
        )
        browser = dialog
        dialog.show()
    }

    /**
     * **The remembered folder's leg** (arc 31 / HV3) — the browser skipped, but not what it hands
     * on: the *Replace <name>?* question is asked against a listing, so one `list` is read in its
     * place, behind the progress dialog (an unchanging screen reads as a stall on e-ink).
     *
     * Not connected and no network are the browser's own two answers. Any **other** refusal does
     * not stop the export (the phase-start call): the folder may have been moved or removed since
     * the preset was saved, and an upload creates its folders on the way past and replaces by name
     * — so the path is applied and the upload's own failure, if any, is what explains it. Only the
     * "is this name already there?" question goes unanswered, and its worst case is the replace
     * that would have happened anyway.
     */
    private fun listThenExport(c: Candidate, path: List<String>) {
        showProgress(R.string.export_stage_listing)
        lifecycleScope.launch {
            val ref = cloudRef
            if (ref == null) {
                hideProgress()
                cancelledAtThePicker()
                Dialogs.problem(this@ExportActivity, R.string.export_failed_title, getString(R.string.export_cloud_gone_body))
                return@launch
            }
            val listing = try {
                CloudClient.list(this@ExportActivity, ref, path.toTypedArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNotConnectedException) {
                hideProgress()
                cancelledAtThePicker()
                loadCloud()
                if (isFinishing || isDestroyed) return@launch
                render()
                offerConnect()
                return@launch
            } catch (e: CloudNetworkException) {
                hideProgress()
                cancelledAtThePicker()
                Dialogs.problem(
                    this@ExportActivity, R.string.export_failed_title,
                    getString(R.string.export_cloud_network_body, cloudName()),
                )
                return@launch
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "the saved folder would not list: ${e.javaClass.simpleName}" }
                emptyList()
            }
            hideProgress()
            if (isFinishing || isDestroyed) { cancelledAtThePicker(); return@launch }
            if (perPage(c)) confirmFolderThenExport(path)
            else confirmThenUpload(c, path, listing)
        }
    }

    /**
     * The chosen folder, and the one question SAF asks for us everywhere else: *is something of
     * this name already there?* An upload is replace-by-name, so a same-named file would be
     * replaced silently — and a silent replace is not the family's way.
     *
     * The listing is the one the browser last drew, so this costs no second round trip; a file that
     * appeared in the folder since is a race the upload's own replace-by-name handles, and the
     * person's own cloud is where they would see it.
     */
    private fun confirmThenUpload(c: Candidate, path: List<String>, listing: List<CloudEntry>) {
        val name = ExportNaming.fileName(stem(), ExportDocumentRules.fileExtension(c.info, values))
        val destination = Destination.Cloud(path, name, ExportDocumentRules.mimeType(c.info, values))
        if (CloudBrowserRules.fileNamed(listing, name) == null) {
            runExport(destination)
            return
        }
        if (isFinishing || isDestroyed) { cancelledAtThePicker(); return }
        var replacing = false
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cloud_replace_title, name))
                .setMessage(R.string.cloud_replace_body)
                .setPositiveButton(R.string.cloud_replace_confirm) { _, _ ->
                    replacing = true
                    runExport(destination)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).also {
            // Back-dismissed as well as cancelled: either way nothing was chosen, and the flow ends
            // exactly as a cancel at the picker does.
            it.setOnDismissListener { if (!replacing) cancelledAtThePicker() }
        }.show()
    }

    /**
     * **The per-page leg's one question** (arc 31 / HV1). An upload is replace-by-name, and a
     * per-page export does not know a single one of its names until the bake has run — so the
     * *Replace <name>?* dialog cannot be asked file by file without asking it after the work has
     * already been done. It is asked once instead, about the folder, and it says plainly that
     * same-named files will be replaced. Cancel and a back-dismiss are both the picker's cancel.
     */
    private fun confirmFolderThenExport(path: List<String>) {
        if (isFinishing || isDestroyed) { cancelledAtThePicker(); return }
        // The folder's own name; the provider's name stands in for its root, which has none.
        val where = path.lastOrNull() ?: cloudName()
        var uploading = false
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.export_cloud_folder_title, where))
                .setMessage(R.string.export_cloud_folder_body)
                .setPositiveButton(R.string.export_upload_confirm) { _, _ ->
                    uploading = true
                    runExport(Destination.CloudFolder(path))
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).also {
            it.setOnDismissListener { if (!uploading) cancelledAtThePicker() }
        }.show()
    }

    /**
     * The whole flow behind the picker: prepare, hand over, verify, confirm — and, for a cloud
     * destination, upload and verify that too. Every failure deletes the document the picker
     * created — a partial file must never stand there silently — and says what happened. Nothing
     * in the cloud is ever deleted: an upload is replace-by-name, so retrying is safe and removing
     * a file the host is not sure about would be the one irreversible thing here.
     */
    private fun runExport(destination: Destination) {
        busy = true
        showProgress(R.string.export_preparing)
        lifecycleScope.launch {
            val saf = destination as? Destination.Saf
            // What a failure may delete (arc-15 review): the picker's overwrite confirmation hands
            // back a PRE-EXISTING document's URI, and a failure that never wrote a byte must not
            // take the user's previous good file with it. Deletion is allowed only once the
            // truncating open has destroyed the old content anyway — or when the document was
            // verifiably empty to begin with (a fresh creation).
            //
            // None of it applies to the cloud leg: what a cloud destination writes to first is a
            // file in this app's own cache, wiped in the `finally` whatever happens, and nothing
            // has reached the provider until the upload — so every failure before it says exactly
            // that and deletes nothing anywhere.
            val sizesAtStart =
                if (saf != null) withContext(Dispatchers.IO) { destinationSizes(saf.uri) } else emptyList()
            val emptyAtStart = sizesAtStart.isNotEmpty() && sizesAtStart.all { it == 0L }
            var destinationTouched = false
            suspend fun failed(@StringRes titleRes: Int, message: String) = when {
                saf != null ->
                    fail(saf.uri, titleRes, message, mayDelete = destinationTouched || emptyAtStart)
                // Arc 31 / HV1: a SAF tree is a place, not a document. Nothing has been created in
                // it at this point, so there is no file to say anything about — and nothing was
                // uploaded either, which is what the cloud sentence would have claimed.
                destination is Destination.SafTree -> failNothing(titleRes, message)
                else -> failCloud(titleRes, message)
            }
            try {
                // DocumentsUI is another process on a memory-tight e-ink device, so this screen can
                // be rebuilt behind it: the result arrives before the recreated screen's discovery
                // has answered. Ask again rather than drop the tap and leave an empty document.
                val c = current() ?: reselectAfterRestore()
                if (c == null) {
                    failed(R.string.export_failed_title, getString(R.string.export_gone_body))
                    return@launch
                }
                // Armed at the tap, checked again here: the fields are saveEnabled=false, so a
                // screen rebuilt behind the picker comes back with the password gone. Say so —
                // exporting unprotected in silence would hand the user a file keyed the way they
                // asked it not to be, which is the same honesty the rekey path owes (E2).
                //
                // The check must fail CLOSED (the D3 review): wantsExportSecret validates against
                // the freshly re-described descriptor, and an exporter upgraded in place behind
                // the picker (the recorded trap) can come back without the protect toggle — which
                // would skip the guard and export unprotected with a success dialog. So the raw
                // tap-time answer is consulted too: protect armed then means a secret is owed now,
                // whatever today's descriptor says, and a secret in hand that the descriptor can
                // no longer carry is the same honest refusal rather than a silent drop.
                val wantsSecret = ExportOptions.wantsExportSecret(c.info, values)
                val armedAtTap = values[ExporterContract.OPTION_PROTECT] == "1"
                if ((wantsSecret || armedAtTap) && (typedExportSecret == null || !wantsSecret)) {
                    failed(R.string.export_failed_title, getString(R.string.export_password_lost_body))
                    return@launch
                }
                // Built before either source kind's preparation: the spec depends on neither, and a
                // spec the contract rejects has no business costing a cache copy or a page bake
                // first. The secret rides its own carrier — never the value map, which is the whole
                // point of the carrier existing.
                // The option values are the same for every file this export writes; the name in
                // the spec is not (arc 31 / HV1 — a per-page export names each file after its own
                // page), which is why the values are settled here and the spec itself is built per
                // file down the per-page branch.
                val specValues = ExportOptions.specValues(c.info, values)
                val perPage = destination is Destination.SafTree || destination is Destination.CloudFolder
                val spec = if (perPage) null else try {
                    ExportSpec(
                        values = specValues,
                        notebookName = ExportNaming.specNameOf(stem()),
                        exportSecret = if (wantsSecret) typedExportSecret else null,
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "spec rejected", e)
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                // The source kinds part here and rejoin at the fds — a keyed `.soil` artifact, the
                // document's final text bytes, or a bundle of pages this host drew (of the
                // notebook, or of the document laid out on paper). From the open below, nothing
                // asks which. The document branch is gated on hasDocument as well as the answer:
                // a document deleted under a standing screen leaves the Source row's true behind,
                // and rendering nothing is not what the tap asked for.
                val prepared = when {
                    // First, because it answers before every question below it: in calendar mode
                    // there is no notebook to have a source kind about (arc 31 / HV4), and the one
                    // exporter kind that is listed here is the page-bundle one anyway.
                    scope is ExportScope.Calendar ->
                        renderedCalendarPages(ExportOptions.includeTemplate(c.info, values))
                    c.info.sourceKind == ExporterContract.SOURCE_DOCUMENT -> assembledDocument(c)
                    c.info.sourceKind != ExporterContract.SOURCE_PAGES -> keyedArtifact(c)
                    documentSource && hasDocument -> renderedDocumentPages()
                    else -> renderedPages(
                        includeTemplate = ExportOptions.includeTemplate(c.info, values),
                        bundleVersion = c.info.bundleVersion,
                    )
                    // Each render takes scope.pageIds and filters its own page rows (PE2).
                }
                val streamFile = when (prepared) {
                    is StreamSource.Failed -> {
                        failed(R.string.export_failed_title, prepared.message)
                        return@launch
                    }
                    is StreamSource.Ready -> prepared.file
                }
                // **The split** (arc 31 / HV1). The bake above ran exactly as it always has; from
                // here a per-page exporter is handed one page at a time into the folder that was
                // picked, and this flow's single-file sequence below is untouched.
                if (perPage) {
                    exportPerPage(
                        c, destination, streamFile,
                        (prepared as? StreamSource.Ready)?.pageNames.orEmpty(),
                        specValues,
                        if (wantsSecret) typedExportSecret else null,
                    )
                    return@launch
                }
                // Structurally impossible past the branch above — the spec is null only for a
                // folder destination, and a folder destination has already returned. It stands as
                // the smart cast for everything below, which is the single-file flow unchanged.
                checkNotNull(spec)
                val streamBytes = withContext(Dispatchers.IO) { streamFile.length() }
                // A protected export encrypts on the extension's side of the call, so the one line
                // the user has to look at through it says which of the two is happening.
                stage(if (spec.exportSecret != null) R.string.export_protecting else R.string.export_exporting)

                val source = withContext(Dispatchers.IO) {
                    runCatching {
                        ParcelFileDescriptor.open(streamFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    }.getOrNull()
                }
                if (source == null) {
                    failed(R.string.export_failed_title, getString(R.string.export_prepare_failed_body))
                    return@launch
                }
                // The one place the flow parts on where the file is going. A cloud destination
                // writes into this app's own export cache — the same directory `prepare` just
                // made, so this open comes AFTER it — and the exporter is never told: an fd is an
                // fd, which is exactly why no exporter needed changing for this arc.
                val cloudFile =
                    if (saf == null) File(File(cacheDir, ExportArtifact.DIR), "out." + ExportDocumentRules.fileExtension(c.info, values))
                    else null
                val sink = withContext(Dispatchers.IO) {
                    if (saf != null) openDestination(saf.uri) else openCacheDestination(cloudFile!!)
                }
                if (sink == null) {
                    withContext(Dispatchers.IO) { runCatching { source.close() } }
                    failed(R.string.export_failed_title, getString(R.string.export_destination_body))
                    return@launch
                }
                // The truncating open has run: whatever the document held is gone, and from here a
                // failure's delete removes only wreckage, never the user's old file. (Meaningless
                // for the cloud leg, where the file is this app's own cache copy.)
                destinationTouched = true

                // Both descriptors are the client's from here — it closes them in `finally`,
                // success, failure or timeout.
                val result = try {
                    ExporterClient(this@ExportActivity, c.ref).export(source, sink, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.message}" }
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }

                // What "exported" is allowed to mean lives in ExportVerification, per source kind
                // (arc 18 / D1): the verbatim-streaming equality against the file actually handed
                // over is the soil contract and no other, while the destination's own account is
                // corroboration for both — never authority (arc-15 review). A cloud provider's
                // metadata can lag the write it just took, and deleting a fully-written export
                // over a stale answer would destroy the very thing that was just made; any
                // agreeing answer is enough, and a unanimous disagreement is an honest
                // check-the-file dialog rather than a delete.
                // The cloud's destination account is the cache file's own length: the exporter
                // wrote it here, in this process, and there is no provider metadata in it to lag.
                val onDisk = withContext(Dispatchers.IO) {
                    if (saf != null) destinationSizes(saf.uri) else listOf(cloudFile!!.length())
                }
                when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, streamBytes, onDisk)) {
                    ExportVerification.Verdict.SHORT -> {
                        Log.w(TAG, "short export: ${result.bytesWritten} written, $streamBytes streamed, destination $onDisk")
                        failed(R.string.export_failed_title, getString(R.string.export_short_body))
                        return@launch
                    }
                    ExportVerification.Verdict.UNCONFIRMED -> {
                        Log.w(TAG, "destination reports $onDisk for ${result.bytesWritten} bytes")
                        hideProgress()
                        if (!isFinishing && !isDestroyed) {
                            Dialogs.problem(
                                this@ExportActivity, R.string.export_verify_title, getString(R.string.export_verify_body)
                            )
                        }
                        return@launch
                    }
                    ExportVerification.Verdict.OK -> Unit
                }

                Slog.d(TAG) { "exported ${result.bytesWritten} bytes" }

                // The cloud leg (arc 25 / V3). Everything above has already run and passed: the
                // file is whole, in this app's cache, and this is the only step that can still put
                // it somewhere the person will look for it. Nothing before this line touched the
                // provider, which is why every failure above says "nothing was uploaded" and means
                // it.
                val cloudDestination = destination as? Destination.Cloud
                if (cloudDestination != null) {
                    uploadAndConfirm(c, cloudDestination, cloudFile!!)
                    return@launch
                }

                // "Last used" is written by an export that finished, never by a tap the picker
                // then abandoned — the next fresh Export screen defaults to this format.
                exportPrefs.lastExporter = c.ref.packageName
                hideProgress()
                if (isFinishing || isDestroyed) return@launch
                Dialogs.confirm(this@ExportActivity, R.string.export_done_title, doneBodyRes()) {
                    finish()
                }
            } finally {
                // The artifact is a copy of the user's notes; it has no business outliving the
                // export that made it, whichever way the export ended — a screen destroyed
                // mid-export included, which is why the wipe is NonCancellable: a plain
                // withContext in an already-cancelled scope throws before it runs anything.
                // The wipe takes the transform's output with it — it is a sibling in the same
                // cache directory — and the secret that made it has no business outliving the
                // flow either.
                withContext(NonCancellable + Dispatchers.IO) { ExportArtifact.clean(applicationContext) }
                typedPassphrase = null
                typedExportSecret = null
                busy = false
                // The result paths each dismiss before their own dialog; this is the net under
                // them, so no way out of the flow leaves a non-cancelable dialog standing.
                hideProgress()
            }
        }
    }

    /** The file the exporter will actually stream, or the sentence saying why there is none. The
     *  two source kinds answer with the same two shapes, which is what lets the flow stop caring
     *  which one it asked at the line after this. */
    private sealed class StreamSource {
        /** [pageNames] is the per-page delivery's naming answer (arc 31 / HV1, grown arc 43 /
         *  K7) — one entry per page of the bundle, in its order, empty for every source kind that
         *  has no pages of the notebook to name (the `.soil`, the assembled text, the document
         *  laid out on paper). */
        class Ready(val file: File, val pageNames: List<ExportNaming.PageName> = emptyList()) : StreamSource()
        class Failed(val message: String) : StreamSource()
    }

    /**
     * [ExporterContract.SOURCE_SOIL]: the cold cache copy, then the host-executed keying step (E2).
     * The transform runs on the artifact, beside the crypto, and produces the file the exporter
     * will stream — which from there on is the only file this flow talks about.
     */
    private suspend fun keyedArtifact(c: Candidate): StreamSource {
        val prepared = ExportArtifact.prepare(applicationContext, notebookId, repo, versionCode(), sourceKey)
        if (prepared is ExportArtifact.Outcome.Failed) {
            return StreamSource.Failed(getString(ExportMessages.of(prepared.problem)))
        }
        val artifact = prepared as ExportArtifact.Outcome.Ready
        val keying = ExportOptions.keying(c.info, values)
        val plan = try {
            ExportKeying.plan(keying, typedPassphrase != null)
        } catch (e: IllegalArgumentException) {
            // Rekey armed with nothing in hand: the screen was rebuilt behind the picker and the
            // fields went with it (saveEnabled=false, deliberately). Say so — a silent Keep would
            // hand the user a file keyed the way they asked it not to be.
            Log.w(TAG, "keying plan rejected: ${e.javaClass.simpleName}")
            return StreamSource.Failed(getString(R.string.export_passphrase_lost_body))
        }
        if (plan == ExportKeying.Plan.KEEP) return StreamSource.Ready(artifact.file)
        // Both transforms read the artifact, so both need **the source file's own** key — the
        // device's for a `GLOBAL` notebook, the one the person typed for a `NOTEBOOK`-scope one
        // (arc 26 / U4). Keep needs none, which is why either is only asked for here.
        val devicePassphrase = sourceNotebookPassphrase()
            ?: KeySession.get()
            ?: return StreamSource.Failed(getString(R.string.export_locked_body))
        stage(if (plan == ExportKeying.Plan.REKEY) R.string.export_rekeying else R.string.export_decrypting)
        return try {
            StreamSource.Ready(ExportKeying.apply(artifact.file, devicePassphrase, plan, typedPassphrase))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: a transform message can carry a path, and a passphrase is never
            // anywhere near a log line.
            Log.w(TAG, "keying transform failed: ${e.javaClass.simpleName}")
            StreamSource.Failed(getString(R.string.export_transform_body))
        }
    }

    /**
     * [ExporterContract.SOURCE_PAGES]: the host draws the notebook and hands over the pages.
     *
     * **No keying, and nothing to skip past.** Such an exporter declares no keying option, so
     * [ExportOptions.needsPassphrase] was false at the tap and there is no typed passphrase in this
     * process to consult — the key is used here only to open the notebook for reading, and never
     * leaves. A password-protected export is not a keying: the secret goes to the extension whole
     * and nothing about this render changes.
     *
     * [includeTemplate] is the one option the **host** executes for this source kind (D2): off, the
     * page bakes on white ground. It has to be answered here because there is no paper left in the
     * bundle for an extension to add or remove afterwards.
     *
     * [bundleVersion] is the exporter's declared ceiling (arc 28 / D7): the sticky-note endnotes
     * and their link trailer go out only to an exporter that said it reads version 2.
     */
    private suspend fun renderedPages(includeTemplate: Boolean, bundleVersion: Int): StreamSource {
        val outcome = ExportRender.render(
            applicationContext, notebookId, includeTemplate, pageProgress(), sourceKey, bundleVersion,
            scope.pageIds,
        )
        return when (outcome) {
            is ExportRender.Outcome.Ready -> StreamSource.Ready(outcome.file, outcome.pageNames)
            is ExportRender.Outcome.Failed -> StreamSource.Failed(getString(ExportMessages.of(outcome.problem)))
        }
    }

    /**
     * [ExporterContract.SOURCE_DOCUMENT]: the host assembles what was *written*, and the exporter
     * copies it. Nothing is drawn, nothing is keyed, and the format choice is executed here rather
     * than sent — [ExportText] hands over final bytes, which is the whole reason
     * [ExportVerification] can hold this kind to the same verbatim equality the soil path answers.
     */
    private suspend fun assembledDocument(c: Candidate): StreamSource {
        stage(R.string.export_assembling)
        val outcome = ExportText.assemble(
            applicationContext, notebookId, ExportOptions.textFormat(c.info, values), sourceKey, scope.pageIds,
        )
        return when (outcome) {
            is ExportText.Outcome.Ready -> StreamSource.Ready(outcome.file)
            is ExportText.Outcome.Failed -> StreamSource.Failed(getString(ExportMessages.of(outcome.problem)))
        }
    }

    /**
     * **Calendar mode's producer** (arc 31 / HV4): the pages are drawn by the calendar extension
     * and arrive as the same bundle every other page render writes, so from the flow's side this is
     * one more [StreamSource.Ready] and nothing downstream knows the difference.
     *
     * [includeGrid] is the exporter's page-template toggle — the same control, the same label, and
     * here it means the calendar's ruling. Ink, today's ring and the day marks are always drawn
     * (the user's phase-start call), so there is nothing else to ask.
     *
     * The size handed over is this device's page in **portrait pixels** — the size the library
     * mints a new notebook at — and it is used only for a target the calendar has never minted a
     * page for; a page that exists keeps its own.
     */
    private suspend fun renderedCalendarPages(includeGrid: Boolean): StreamSource {
        val target = calendarTarget
            ?: return StreamSource.Failed(getString(R.string.export_calendar_failed_body))
        val ref = calendarRef
            ?: return StreamSource.Failed(getString(R.string.export_calendar_gone_body))
        stage(R.string.export_rendering_calendar)
        val metrics = resources.displayMetrics
        val plan = CalendarRenderPlan.of(target, includeGrid)
        val outcome = CalendarRender.render(
            applicationContext, ref, plan,
            minOf(metrics.widthPixels, metrics.heightPixels),
            maxOf(metrics.widthPixels, metrics.heightPixels),
        )
        return when (outcome) {
            is CalendarRender.Outcome.Ready -> StreamSource.Ready(outcome.file, outcome.pageNames)
            is CalendarRender.Outcome.Failed -> StreamSource.Failed(outcome.message)
        }
    }

    /**
     * [ExporterContract.SOURCE_PAGES] with the Source row answered *Document*: the same bundle of
     * images, of the document laid out on white paper instead of the notebook's pages
     * ([DocumentPdfRender]). The exporter is not told, and there is nothing it could do with the
     * knowledge — a page bundle is a page bundle.
     *
     * The template toggle takes no part: Document mode is white ground always, which is why its
     * row is not even on screen while this branch is the live one.
     */
    private suspend fun renderedDocumentPages(): StreamSource {
        val outcome = DocumentPdfRender.render(applicationContext, notebookId, pageProgress(), sourceKey, scope.pageIds)
        return when (outcome) {
            is DocumentPdfRender.Outcome.Ready -> StreamSource.Ready(outcome.file)
            is DocumentPdfRender.Outcome.Failed -> StreamSource.Failed(getString(ExportMessages.of(outcome.problem)))
        }
    }

    /** The per-page stage line both renders report through. The callback arrives on IO, so it hops
     *  to Main to touch the view; the count is the point of the line — a long notebook is otherwise
     *  a long silence, and on e-ink an unchanging stage reads as a stall. */
    private fun pageProgress(): suspend (Int, Int) -> Unit = { page, count ->
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) stage(getString(R.string.export_rendering, page, count))
        }
    }

    /**
     * Re-run discovery for an export whose screen was rebuilt behind the picker, and take back the
     * exporter the user actually chose. **Only that one**: falling back to whatever else is
     * installed would export a different format into a file already named for the first, so a
     * chosen exporter that has gone is a problem dialog, not a substitution.
     */
    private suspend fun reselectAfterRestore(): Candidate? {
        candidates = loadCandidates()
        // A cancelled passphrase prompt has already called finish(): the flow's own problem dialog
        // would be a second sentence about a decision the person already made.
        if (isFinishing || isDestroyed) return null
        val pick = candidates.firstOrNull { it.ref.packageName == chosenPackage }
            ?: candidates.takeIf { chosenPackage == null }?.firstOrNull()
        if (pick != null && !isFinishing && !isDestroyed) select(pick, keepValues = true)
        return pick
    }

    /**
     * **The upload leg** (arc 25 / V3), and the only step of the flow that touches the provider.
     *
     * It runs after the export has already been verified whole against the cache file, so what goes
     * up is known-good bytes and the only question left is whether they arrived. The provider's
     * account of the finished file is corroboration ([ExportVerification.cloudVerdict]) and never
     * authority: a disagreement is the arc-15 *check the file* dialog, `lastExporter` is not
     * written, and **nothing is deleted** — a provider's metadata can lag its own write, and
     * deleting over a stale answer would destroy the very thing that was just made.
     *
     * The three failures are three different sentences because they mean three different things,
     * and the one that matters is the last: a provider that did not answer at all says nothing
     * about whether the bytes landed, so the honest wording is *check it before exporting again* —
     * and a retry is safe, because an upload is replace-by-name.
     */
    private suspend fun uploadAndConfirm(c: Candidate, cloud: Destination.Cloud, file: File) {
        val ref = cloudRef
        if (ref == null) {
            failCloud(R.string.export_failed_title, getString(R.string.export_cloud_gone_body))
            return
        }
        if (!uploadOne(ref, cloud.path, cloud.name, cloud.mime, file, prefix = "")) return
        exportPrefs.lastExporter = c.ref.packageName
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.confirm(
            this@ExportActivity,
            R.string.export_done_title,
            getString(R.string.export_cloud_done_body, cloudName()),
        ) { finish() }
    }

    /**
     * One file up, and the honest sentence when it does not land — the upload leg's body, shared
     * since arc 31 / HV1 by the single-file export and the per-page loop. Answers whether the
     * bytes are up **and** the provider's account of them agrees.
     *
     * [prefix] is what the per-page loop puts in front of every sentence ("3 of 8 images were
     * exported."), and empty for a single file — which is also what decides the *Nothing was
     * uploaded.* note: it is true of one file and false of a loop that has already put some there,
     * so a run that has uploaded something says the count instead.
     */
    private suspend fun uploadOne(
        ref: ProviderRef,
        path: List<String>,
        name: String,
        mime: String,
        file: File,
        prefix: String,
    ): Boolean {
        val provider = cloudName()
        // The note belongs to the single-file leg's own wording; a loop says the count instead.
        fun report(@StringRes titleRes: Int, message: String, note: Boolean) {
            if (note && prefix.isEmpty()) {
                failCloud(titleRes, message)
                return
            }
            hideProgress()
            if (isFinishing || isDestroyed) return
            Dialogs.problem(this@ExportActivity, titleRes, prefix + message)
        }
        stage(getString(R.string.export_uploading, provider))
        val bytes = withContext(Dispatchers.IO) { file.length() }
        val pfd = withContext(Dispatchers.IO) {
            runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        }
        if (pfd == null) {
            report(R.string.export_failed_title, getString(R.string.export_prepare_failed_body), note = true)
            return false
        }
        // The client owns the descriptor from here and closes it on every path, refusals included.
        val entry = try {
            CloudClient.upload(
                this@ExportActivity, ref, path.toTypedArray(), name, mime, pfd, bytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudNotConnectedException) {
            hideProgress()
            if (isFinishing || isDestroyed) return false
            // Offered from the dialog, because Connect is the only thing that helps here.
            Dialogs.style(
                AlertDialog.Builder(this@ExportActivity)
                    .setTitle(R.string.export_failed_title)
                    .setMessage(prefix + getString(R.string.export_cloud_not_connected_body, provider))
                    .setPositiveButton(R.string.cloud_connect) { _, _ -> offerConnectAfterFailure() }
                    .setNegativeButton(R.string.ok, null)
                    .create()
            ).show()
            return false
        } catch (e: CloudNetworkException) {
            report(R.string.export_failed_title, getString(R.string.export_cloud_network_body, provider), note = true)
            return false
        } catch (e: Exception) {
            // No answer, or a timeout: the file may or may not have arrived, and the host has no
            // way to find out without asking again. It says so, and deletes nothing.
            Slog.d(TAG) { "upload failed: ${e.javaClass.simpleName}" }
            report(R.string.export_failed_title, getString(R.string.export_cloud_unanswered_body, provider), note = false)
            return false
        }

        when (ExportVerification.cloudVerdict(entry.sizeBytes, bytes)) {
            ExportVerification.Verdict.OK -> Unit
            else -> {
                Log.w(TAG, "the provider reports ${entry.sizeBytes} for $bytes uploaded bytes")
                report(R.string.export_verify_title, getString(R.string.export_cloud_verify_body, provider), note = false)
                return false
            }
        }

        Slog.d(TAG) { "uploaded $bytes bytes" }
        return true
    }

    /**
     * **The per-page loop** (arc 31 / HV1) — one file per page, into the folder that was picked.
     *
     * Everything about *one* file is the single-file flow's, unchanged and on purpose: one
     * `export()` call with one page in front of it, one [ExportVerification] verdict against that
     * file's own bytes, one upload on the cloud leg. What this adds is the arithmetic around them,
     * and every rule in it is about honesty:
     *
     *  - **The bake ran once.** [BundleSplit] re-frames it into one-page bundles in the same cache
     *    directory the flow's `finally` wipes; no notebook is opened a second time.
     *  - **Each file is named for its own page** ([ExportNaming.pageStem] over the titles the bake
     *    read), and the spec's name is that same stem — the file on disk and the name inside it
     *    agree, page by page, exactly as they do at page scope. Two pages under one heading make
     *    two files of one name, which is the *provider's* question: SAF de-dupes with `(1)`, an
     *    upload replaces by name, and the host renames nothing behind the user's back.
     *  - **A failure stops, and keeps what is already written.** The failing file is removed where
     *    the host created it (a SAF document it made itself, never anything in the cloud), and the
     *    sentence leads with *N of M images were exported.* — never a silent partial success.
     */
    private suspend fun exportPerPage(
        c: Candidate,
        destination: Destination,
        bundle: File,
        pageNames: List<ExportNaming.PageName>,
        specValues: Map<String, String>,
        secret: String?,
    ) {
        val dir = File(cacheDir, ExportArtifact.DIR)
        val parts = withContext(Dispatchers.IO) {
            runCatching { BundleSplit.split(bundle, dir) }
                .onFailure { Log.w(TAG, "the bundle would not split: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        if (parts.isNullOrEmpty()) {
            // Nothing has been created anywhere: the split is host-side work in this app's cache.
            val message = getString(R.string.export_render_failed_body)
            if (destination is Destination.CloudFolder) failCloud(R.string.export_failed_title, message)
            else failNothing(R.string.export_failed_title, message)
            return
        }
        val total = parts.size
        val extension = ExportDocumentRules.fileExtension(c.info, values)
        val mime = ExportDocumentRules.mimeType(c.info, values)
        // The tree's own document id, resolved once — every document this loop creates is a child
        // of it. A tree that will not answer is the destination refusing before anything was made.
        val treeRoot = (destination as? Destination.SafTree)?.let { picked ->
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    picked.tree, DocumentsContract.getTreeDocumentId(picked.tree),
                )
            }.onFailure { Log.w(TAG, "the picked folder would not resolve: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        if (destination is Destination.SafTree && treeRoot == null) {
            failNothing(R.string.export_failed_title, getString(R.string.export_destination_body))
            return
        }
        var written = 0
        // One bind for the whole loop, one call per page (arc 34 / M9a): a bind that fails here is
        // the export call failing before any page — nothing has been created.
        val exporter = try {
            ExporterClient(this@ExportActivity, c.ref).hold()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "exporter bind failed: ${e.message}" }
            stopPerPage(destination, null, written, total, getString(R.string.export_failed_body))
            return
        }
        try {
            exportPerPageHeld(exporter, c, destination, parts, pageNames, specValues, secret, extension, mime, treeRoot)
        } finally {
            exporter.close()
        }
    }

    /** [exportPerPage]'s loop and its ending, over the one held bind (arc 34 / M9a). */
    private suspend fun exportPerPageHeld(
        exporter: ExporterClient.Held,
        c: Candidate,
        destination: Destination,
        parts: List<File>,
        pageNames: List<ExportNaming.PageName>,
        specValues: Map<String, String>,
        secret: String?,
        extension: String,
        mime: String,
        treeRoot: Uri?,
    ) {
        val dir = File(cacheDir, ExportArtifact.DIR)
        val total = parts.size
        var written = 0
        for (index in parts.indices) {
            val part = parts[index]
            val stem = stemFor(index, pageNames)
            val name = ExportNaming.fileName(stem, extension)
            stage(getString(R.string.export_exporting_image, index + 1, total))
            val spec = try {
                ExportSpec(
                    values = specValues,
                    notebookName = ExportNaming.specNameOf(stem),
                    exportSecret = secret,
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "spec rejected", e)
                stopPerPage(destination, null, written, total, getString(R.string.export_failed_body))
                return
            }
            val partBytes = withContext(Dispatchers.IO) { part.length() }
            val source = withContext(Dispatchers.IO) {
                runCatching { ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
            }
            if (source == null) {
                stopPerPage(destination, null, written, total, getString(R.string.export_prepare_failed_body))
                return
            }
            // One document per page, created as the loop reaches it: a failure at page four leaves
            // the three before it exactly as they were written, and creates no fifth.
            val document = if (treeRoot != null) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.createDocument(contentResolver, treeRoot, mime, name) }
                        .onFailure { Log.w(TAG, "could not create the destination document: ${it.javaClass.simpleName}") }
                        .getOrNull()
                }
            } else null
            val cacheFile = if (destination is Destination.CloudFolder) File(dir, "out-$index.$extension") else null
            val sink = withContext(Dispatchers.IO) {
                if (cacheFile != null) openCacheDestination(cacheFile)
                else document?.let { openDestination(it) }
            }
            if (sink == null) {
                withContext(Dispatchers.IO) { runCatching { source.close() } }
                stopPerPage(destination, document, written, total, getString(R.string.export_destination_body))
                return
            }
            // Both descriptors are the client's from here — it closes them in `finally`.
            val result = try {
                exporter.export(source, sink, spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Slog.d(TAG) { "export call failed: ${e.message}" }
                stopPerPage(destination, document, written, total, getString(R.string.export_failed_body))
                return
            }
            val onDisk = withContext(Dispatchers.IO) {
                if (cacheFile != null) listOf(cacheFile.length()) else destinationSizes(document!!)
            }
            when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, partBytes, onDisk)) {
                ExportVerification.Verdict.SHORT -> {
                    Log.w(TAG, "short export: ${result.bytesWritten} written, destination $onDisk")
                    stopPerPage(destination, document, written, total, getString(R.string.export_short_body))
                    return
                }
                ExportVerification.Verdict.UNCONFIRMED -> {
                    // The stream itself completed; only the destination's account disagrees. Check
                    // the file, never a delete — and never a claim that the run finished.
                    Log.w(TAG, "destination reports $onDisk for ${result.bytesWritten} bytes")
                    hideProgress()
                    if (isFinishing || isDestroyed) return
                    Dialogs.problem(
                        this@ExportActivity,
                        R.string.export_verify_title,
                        partialPrefix(written, total) + getString(R.string.export_verify_body),
                    )
                    return
                }
                ExportVerification.Verdict.OK -> Unit
            }
            if (destination is Destination.CloudFolder) {
                val ref = cloudRef
                if (ref == null) {
                    stopPerPage(destination, null, written, total, getString(R.string.export_cloud_gone_body))
                    return
                }
                // The upload leg's own sentences, with the count in front of them.
                if (!uploadOne(ref, destination.path, name, mime, cacheFile!!, partialPrefix(written, total))) return
            }
            written++
        }

        // "Last used" is written by an export that finished — every file of it (the D1 rule read
        // per page): a run that stopped at page four has not exported this format.
        exportPrefs.lastExporter = c.ref.packageName
        hideProgress()
        if (isFinishing || isDestroyed) return
        val body = if (destination is Destination.CloudFolder) {
            resources.getQuantityString(R.plurals.export_cloud_done_images, written, written, cloudName())
        } else {
            resources.getQuantityString(R.plurals.export_done_images, written, written)
        }
        Dialogs.confirm(this@ExportActivity, R.string.export_done_title, body) { finish() }
    }

    /**
     * The per-page loop's one way to stop (arc 31 / HV1): say how far it got, then the failure's
     * own sentence. [document] is the file this page created and did not finish — removed under
     * [fail]'s rules, because the host made it in this loop and nothing of the user's was ever at
     * that name. Nothing already written is touched, and nothing in the cloud is ever deleted.
     */
    private suspend fun stopPerPage(
        destination: Destination,
        document: Uri?,
        written: Int,
        total: Int,
        message: String,
    ) {
        val body = partialPrefix(written, total) + message
        when {
            document != null -> fail(document, R.string.export_failed_title, body, mayDelete = true)
            // The note is true only while nothing has gone up yet; after that the count says it.
            destination is Destination.CloudFolder && written == 0 ->
                failCloud(R.string.export_failed_title, body)
            else -> failNothing(R.string.export_failed_title, body)
        }
    }

    /** *N of M images were exported.* — what every per-page failure leads with. */
    private fun partialPrefix(written: Int, total: Int): String =
        resources.getQuantityString(R.plurals.export_done_images_partial, written, written, total) + " "

    /** A failure with nothing created anywhere (arc 31 / HV1 — a folder is a place, not a file):
     *  the sentence alone, with no note about a file that was never made. */
    private fun failNothing(@StringRes titleRes: Int, message: String) {
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, titleRes, message)
    }

    /** The Connect offer reached from a failed upload. The status is re-read first: the account may
     *  simply have been disconnected elsewhere, and the offer should be built on what is true now. */
    private fun offerConnectAfterFailure() {
        lifecycleScope.launch {
            loadCloud()
            if (isFinishing || isDestroyed) return@launch
            render()
            offerConnect()
        }
    }

    /**
     * Every cloud failure **before** the upload ends here, and every one of them means the same
     * thing about the provider: nothing reached it. The sentence says so, and nothing anywhere is
     * deleted — the cache copy goes with the flow's own `finally`, as it always did.
     */
    private fun failCloud(@StringRes titleRes: Int, message: String) {
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, titleRes, "$message ${getString(R.string.export_nothing_uploaded_note)}")
    }

    /** The cloud leg's destination: a plain file in this app's export cache, opened `rwt`. It is
     *  made **after** `prepare`, which wipes that directory, and taken away by the same `clean`
     *  that takes the artifact. */
    private fun openCacheDestination(file: File): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }.onFailure { Log.w(TAG, "could not open the cache destination: ${it.javaClass.simpleName}") }.getOrNull()

    /** Best-effort truncating write handle. Providers differ on which modes they accept, so the
     *  documented one is tried first and plain "w" is the fallback. */
    private fun openDestination(uri: Uri): ParcelFileDescriptor? {
        for (mode in arrayOf("rwt", "wt", "w")) {
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
            if (pfd != null) return pfd
        }
        Log.w(TAG, "could not open the destination for writing")
        return null
    }

    /** Every account the destination provider will give of what it now holds — the SIZE column and
     *  the reopened fd's stat, in that order. Empty when it will not say at all. Two answers rather
     *  than one because neither is authoritative alone: a cloud provider's metadata can lag a write
     *  it just took, and a proxy fd can refuse to stat (arc-15 review). */
    private fun destinationSizes(uri: Uri): List<Long> {
        val sizes = ArrayList<Long>(2)
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0)
            }
        }
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0L }?.let { sizes += it }
            }
        }
        return sizes
    }

    /** The busy latch's voice: why the door out did nothing (a silent ignore reads as broken). */
    private fun showBusyGuard() {
        Dialogs.problem(this, R.string.export_busy_title, getString(R.string.export_busy_body))
    }

    /**
     * Every failure path ends here. The created document goes **only when [mayDelete]** — the
     * caller answers for whether what stands at [uri] is this export's wreckage (a truncating
     * open ran, or the document was verifiably empty at the start) or a pre-existing file the
     * picker offered to overwrite, which a failure that never wrote a byte must not destroy
     * (arc-15 review). The dialog then says what actually happened to the file — removed, possibly
     * remaining, or untouched — because the delete is best-effort and a claim of removal that
     * did not happen is a partial file the user was told is gone. No path and no secret in the
     * message.
     */
    private suspend fun fail(uri: Uri, @StringRes titleRes: Int, message: String, mayDelete: Boolean) {
        hideProgress()
        val removed = if (mayDelete) withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                .onFailure { Log.w(TAG, "could not remove the partial export: ${it.message}") }
                .getOrDefault(false)
        } else false
        if (isFinishing || isDestroyed) return
        val note = getString(
            when {
                removed -> R.string.export_removed_note
                mayDelete -> R.string.export_remains_note
                else -> R.string.export_untouched_note
            }
        )
        Dialogs.problem(this, titleRes, "$message $note")
    }

    /** The done sentence names what went out: the notebook, or the one page (arc 30 / PE2). */
    @StringRes
    private fun doneBodyRes(): Int = when (scope) {
        is ExportScope.Page -> R.string.export_done_page_body
        is ExportScope.Calendar -> R.string.export_done_calendar_body
        ExportScope.Whole -> R.string.export_done_body
    }

    /** A dialog that explains why there is nothing to do here, and closes the screen behind it. */
    private fun problemAndClose(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
        if (isFinishing || isDestroyed) { finish(); return }
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).also { it.setOnDismissListener { finish() } }.show()
    }

    // ── The Preset row's host (arc 31 / HV3) ─────────────────────────────────

    /** What a preset would capture: this screen's answers, minus the scope and minus any secret —
     *  neither is in [ExportPresets.State], and neither may ever be. Null before a format has been
     *  described: there is nothing to save yet. */
    override fun currentState(): ExportPresets.State? = current()?.let {
        ExportPresets.State(
            exporter = it.ref.packageName,
            values = LinkedHashMap(values),
            documentSource = documentSource,
            destination = destinationChoice,
            cloudPath = cloudPath,
        )
    }

    override fun listedPackages(): Set<String> = candidates.map { it.ref.packageName }.toSet()

    override fun cloudAvailable(): Boolean = cloudRef != null && cloudStatus?.connected == true

    /**
     * Adopt a preset's answers — every write through the same fields a tap would use, under the
     * latch that tells [handChanged] this hand is the screen's own.
     *
     * `select(keepValues = true)` runs the values back through [ExportOptions.specValues], so a
     * value the chosen exporter does not declare becomes that option's default rather than crossing
     * as it stands. That is also the secret filter — a passphrase is never a declared choice — and
     * the password fields are cleared because a preset never carried one.
     */
    override fun applyPreset(state: ExportPresets.State) {
        applyingPreset = true
        try {
            chosenPackage = state.exporter
            values.clear()
            values.putAll(state.values)
            documentSource = state.documentSource
            destinationChoice = state.destination
            cloudPath = state.cloudPath
            binding.editPassphrase.setText("")
            binding.editPassphraseConfirm.setText("")
            // Gone between the listing and the tap: the panel is left exactly as it was, and the
            // next discovery re-lists without it.
            val c = current() ?: return
            select(c, keepValues = true)
        } finally {
            applyingPreset = false
        }
    }

    override fun presetsChanged() = render()

    private fun versionCode(): Int = runCatching {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    companion object {
        private const val TAG = "ExportActivity"
        private const val KEY_PACKAGE = "export.package"
        private const val KEY_VALUES = "export.values"
        private const val KEY_SOURCE = "export.documentSource"
        private const val KEY_SCOPE_WHOLE = "export.scopeWhole"
        private const val KEY_DESTINATION = "export.cloudDestination"
        private const val KEY_PRESET = "export.preset"
        private const val KEY_CLOUD_PATH = "export.cloudPath"

        /** The one folder of the provider's tree an export ever writes into (decision 5). The
         *  browser opens on it and never climbs above it; `Exports/` itself is created by the
         *  upload on the way past, because browsing creates nothing. */
        private const val CLOUD_EXPORTS_FOLDER = "Exports"

        /** The Source row's two answers, in the order they read: the notebook first, because that
         *  is what this screen has always exported and what the default false means. */
        private val SOURCE_ROWS = listOf(
            R.string.export_source_pages to false,
            R.string.export_source_document to true,
        )

        /** Identity travels as id + name — never a `File`, never a path (the family rule). */
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        /** The page-sheet door (arc 30 / PE2): the page the export is seeded to. Absent from the
         *  library door. Host-internal — an id, never content. */
        const val EXTRA_PAGE_ID = "pageId"

        /** The page-sheet door: relaunch the notebook when this screen finishes, however it
         *  finishes (decision 5). Absent from the library door. */
        const val EXTRA_RETURN_TO_NOTEBOOK = "returnToNotebook"

        /**
         * **The calendar door** (arc 31 / HV4): the page the calendar was showing, as
         * `kind/date/half`. Three numbers and an ISO day — host-internal, not transfer content and
         * not a secret (a date is not one), and the only thing this door carries. Present instead
         * of [EXTRA_NOTEBOOK_ID], never beside it.
         */
        const val EXTRA_CALENDAR_TARGET = "calendarTarget"

        /** The extra's wire form, and the only place it is written. */
        private fun encodeCalendarTarget(target: CalendarTarget): String =
            "${target.kind}/${target.date}/${target.half}"

        /** The extra read back, or null — a bad one is *no target*, which the screen treats as no
         *  door at all rather than guessing at a page. [CalendarTarget]'s constructor is the
         *  validation, exactly as it is on the seam. */
        internal fun parseCalendarTarget(extra: String?): CalendarTarget? {
            val parts = extra?.split("/") ?: return null
            if (parts.size != 3) return null
            val kind = parts[0].toIntOrNull() ?: return null
            val half = parts[2].toIntOrNull() ?: return null
            return runCatching { CalendarTarget(kind, parts[1], half) }.getOrNull()
        }

        fun intent(
            context: Context,
            notebookId: String,
            notebookName: String,
            pageId: String? = null,
            returnToNotebook: Boolean = false,
        ): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_RETURN_TO_NOTEBOOK, returnToNotebook)

        /** **The calendar door's Intent** (arc 31 / HV4) — no notebook id, no name, no page: a
         *  calendar export is about a period, and the caller reopens the calendar itself. */
        fun intent(context: Context, calendarTarget: CalendarTarget): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(EXTRA_CALENDAR_TARGET, encodeCalendarTarget(calendarTarget))
    }
}
