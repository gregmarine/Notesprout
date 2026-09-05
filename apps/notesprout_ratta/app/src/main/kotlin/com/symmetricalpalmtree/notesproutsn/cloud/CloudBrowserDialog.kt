package com.symmetricalpalmtree.notesproutsn.cloud

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudNotConnectedException
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import com.symmetricalpalmtree.notesproutsn.library.GridMath
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * **The cloud browser** (arc 25 / V3, `DRIVE_PLAN.md` decision 7) — the host draws the provider's
 * folders and files itself. The extension is asked one thing, `list`, and answers rows; every
 * decision about what those rows mean is made here, on this side of the seam, where the family's
 * chrome rules live.
 *
 * A full-screen `Dialog` in the Contents dialog's shape: top bar = **Up · breadcrumb · Cancel ·
 * the action** (F2 — the action button lives on the top bar, after Cancel), a 1 dp inkBlack rule,
 * rows that **paginate and never scroll** (the e-ink rule) with the pager-only bar beneath, and the
 * same one-finger flip [ListSwipe] gives every paginated list in the app.
 *
 * The rules it keeps, each of them one the seam or the family already wrote:
 *
 *  - **Browsing creates nothing.** Every navigation is one `list`. A folder comes into existence
 *    only from the *New folder…* row — and `Exports/` itself is made by the upload on the way past,
 *    which is why opening this browser on a tree that does not exist yet is an empty list rather
 *    than a failure.
 *  - **Up stops at the folder it was opened on.** Cancel is the way out of that one; an Up that
 *    climbed out of `Exports/` would offer to save somewhere the host has no business writing.
 *  - **Every wait says so.** A `list` is a bind and a network round trip; on e-ink a screen that
 *    does not change reads as a screen that is broken, so the body says *Loading…* through each one.
 *  - **The three failures answer differently**, because they mean different things:
 *    [CloudNotConnectedException] closes the browser into the caller's Connect offer (there is
 *    nothing to browse and the caller owns that door); [CloudNetworkException] and a provider that
 *    did not answer are problem dialogs **over** the browser, which stays exactly where it was —
 *    nothing changed, so nothing about the view should either.
 *  - **A tap that did nothing gets a dialog, never a toast** (the family rule), and no log line ever
 *    carries a folder name, a file name or the account: counts, depths and durations only.
 *
 * **[Mode.PICK_FILE] is declared, not yet wired.** The browser navigates identically in both modes;
 * what a file *row* does is the difference, and in this phase a file row is drawn and inert in both.
 * V5 (import from cloud) makes a file row tappable under [Mode.PICK_FILE] and answers
 * [Pick.File] — the result type is here already so that the two consumers were designed together
 * rather than one being bent around the other later. Under [Mode.PICK_FILE] the action button and
 * the *New folder…* row are both absent: picking a file is not a place to save, and making a folder
 * is not part of finding one.
 *
 * Exactly one of [onPicked], [onNotConnected] and [onCancelled] runs, once. The caller's busy latch
 * is held across the whole showing, so a cancel here is the SAF picker's cancel — the caller drops
 * whatever it collected at the tap and unlatches.
 */
class CloudBrowserDialog(
    private val activity: AppCompatActivity,
    private val ref: ProviderRef,
    /** What the browser calls the provider, in the breadcrumb and in its own sentences. */
    private val providerName: String,
    private val mode: Mode,
    /** The folder the browser opens on, and the floor Up will not climb above — names under the
     *  provider's root, e.g. `["Exports"]`. */
    private val basePath: List<String>,
    /** A folder was chosen ([Mode.PICK_FOLDER]) — or, from V5, a file ([Mode.PICK_FILE]). */
    private val onPicked: (Pick) -> Unit,
    /** The provider says no account is connected: the browser has closed, and the caller offers
     *  Connect. Nothing was changed. */
    private val onNotConnected: () -> Unit,
    /** Cancel, back, or the screen going away. Nothing was chosen and nothing was changed. */
    private val onCancelled: () -> Unit,
) {

    /** What the browser is being opened to answer. */
    enum class Mode {
        /** Pick a folder to save into: folders enter on tap, the action is *Save here*. */
        PICK_FOLDER,

        /** Pick a file to read (V5): folders still enter, and a file row becomes the answer. */
        PICK_FILE,
    }

    /** What the browser answers with. */
    sealed class Pick {
        /**
         * A folder, named by [path] (names under the provider's root), together with [listing] —
         * the rows the browser last drew of it. The listing travels with the answer because the
         * caller's next question is about it: *is a file of the name I am about to write already
         * here?* Asking the provider again would be a second round trip for something already known.
         */
        class Folder(val path: List<String>, val listing: List<CloudEntry>) : Pick()

        /** One file, and the folder it was found in (V5's answer — see the class doc). */
        class File(val entry: CloudEntry, val path: List<String>) : Pick()
    }

    private val listSwipe = ListSwipe(
        region = { body },
        onFlipNext = { goToListPage(listPage + 1) },
        onFlipPrevious = { goToListPage(listPage - 1) },
        standDown = { loading },
    )

    private val dialog = object : Dialog(activity, R.style.Theme_Notesprout) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            listSwipe.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    private var path: List<String> = basePath
    private var entries: List<CloudEntry> = emptyList()
    private var listPage = 0
    private var itemsPerPage = 1
    private var measured = false
    private var loading = false

    /** True once one of the three callbacks has run — the dismiss listener is what turns a back
     *  press or a lost window into [onCancelled], and it must not fire a second answer. */
    private var answered = false

    private var body: View? = null
    private lateinit var rows: LinearLayout
    private lateinit var message: TextView
    private lateinit var crumb: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView
    private lateinit var btnUp: AppCompatImageButton
    private lateinit var btnAction: AppCompatButton

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { answer { onCancelled() }; return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_cloud_browser)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { answer { onCancelled() } }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Before show(): the decor view exists but is not attached, and the insets controller
            // needs an attached view — the legacy flags here, the modern call once the window is up
            // (a Dialog's window resets bar visibility as it shows).
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }

        TopGuard.applyRootPadding(dialog.findViewById(R.id.cloudBrowserRoot))
        body = dialog.findViewById(R.id.cloudBody)
        rows = dialog.findViewById(R.id.cloudRows)
        message = dialog.findViewById(R.id.cloudMessage)
        crumb = dialog.findViewById(R.id.cloudCrumb)
        pager = dialog.findViewById(R.id.cloudPager)
        pageLabel = dialog.findViewById(R.id.cloudPageLabel)
        btnUp = dialog.findViewById(R.id.btnCloudUp)
        btnAction = dialog.findViewById(R.id.btnCloudAction)
        val btnCancel = dialog.findViewById<AppCompatButton>(R.id.btnCloudCancel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudLast)
        listOf(btnUp, btnFirst, btnPrev, btnNext, btnLast).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }

        btnUp.setOnClickListener { goUp() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        // GONE, never disabled: in PICK_FILE there is no "save here" to be had, and a control that
        // cannot be operated is invisible on e-ink either way.
        btnAction.visibility = if (mode == Mode.PICK_FOLDER) View.VISIBLE else View.GONE
        btnAction.setOnClickListener { onSaveHere() }
        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener {
            goToListPage(CloudBrowserRules.pageCount(rowCount(), itemsPerPage) - 1)
        }

        renderCrumb()
        showMessage(R.string.cloud_browser_loading)

        // itemsPerPage from the real body height, measured once after the first layout — no
        // estimate. The first listing is fetched from here so the rows it produces already know
        // how many of them fit.
        rows.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                itemsPerPage = CloudBrowserRules.itemsPerPage(
                    rows.height - rows.paddingTop - rows.paddingBottom,
                    activity.resources.displayMetrics.density,
                )
                measured = true
                Slog.d(TAG) { "shown: rows/page=$itemsPerPage depth=${path.size} mode=$mode" }
                navigate(path)
            }
        })

        dialog.show()
        dialog.window?.let { w -> Immersive.apply(w, w.decorView) }
    }

    /** Safe when nothing is showing — the caller's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    // ── Navigation ───────────────────────────────────────────────────────────

    /**
     * List [target] and, **only if that succeeds**, move there. A failed navigation leaves the
     * browser exactly where it was: nothing changed in the cloud, so nothing changes on screen
     * either, and the person still knows which folder they are looking at.
     */
    private fun navigate(target: List<String>) {
        if (loading) { Slog.d(TAG) { "navigate ignored: a listing is already running" }; return }
        loading = true
        showMessage(R.string.cloud_browser_loading)
        activity.lifecycleScope.launch {
            val listed = try {
                CloudClient.list(activity, ref, target.toTypedArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                if (dialog.isShowing) onListFailed(e)
                return@launch
            }
            loading = false
            if (!dialog.isShowing) return@launch
            path = target
            entries = listed
            listPage = 0
            renderCrumb()
            render()
        }
    }

    private fun goUp() {
        if (loading) return
        if (!CloudBrowserRules.canGoUp(path.size, basePath.size)) {
            // A no-op, deliberately silent: the arrow is at the floor the browser was opened on and
            // Cancel is the way out. The same rule the pager keeps at either bound.
            Slog.d(TAG) { "up at the base folder: nothing to do" }
            return
        }
        navigate(path.dropLast(1))
    }

    /**
     * The three ways a `list` (or an `ensureFolder`) can fail, each answered as what it means.
     * The class name is what gets logged — a failure message can carry more than a host wants in a
     * log line.
     */
    private fun onListFailed(e: Exception) {
        Slog.d(TAG) { "listing failed: ${e.javaClass.simpleName}" }
        when (e) {
            is CloudNotConnectedException -> {
                // There is nothing to browse. The Connect offer is the caller's door, not this
                // dialog's, so this closes into it.
                answer { onNotConnected() }
                dismiss()
            }
            is CloudNetworkException -> problem(
                activity.getString(R.string.cloud_browser_network_title, providerName),
                activity.getString(R.string.cloud_browser_network_body, providerName),
            )
            else -> problem(
                activity.getString(R.string.cloud_browser_failed_title),
                activity.getString(R.string.cloud_browser_failed_body),
            )
        }
        // Whatever it was, the browser stays where it was — so the last good listing goes back up.
        if (dialog.isShowing && !answered) render()
    }

    // ── The action, and the New folder row ───────────────────────────────────

    private fun onSaveHere() {
        if (loading) { Slog.d(TAG) { "save here ignored: a listing is running" }; return }
        Slog.d(TAG) { "picked a folder at depth ${path.size} (${entries.size} entries listed)" }
        answer { onPicked(Pick.Folder(path, entries)) }
        dismiss()
    }

    /**
     * *New folder…* — the only thing in this browser that creates anything.
     *
     * The name is judged before anything is sent ([CloudBrowserRules.newFolderOutcome]): a name the
     * seam cannot carry, or one that would sit past [com.symmetricalpalmtree.notesproutsn.extension.CloudContract.MAX_PATH_DEPTH],
     * is refused here and the typing is kept — a refusal must never cost a bind, and never a
     * retype. A name already listed as a folder is simply entered: the person asked for that folder
     * to exist, and it does.
     */
    private fun onNewFolder() {
        if (loading) return
        NameDialog.show(
            activity,
            titleRes = R.string.cloud_browser_new_folder_title,
            confirmRes = R.string.cloud_browser_new_folder_confirm,
            hintRes = R.string.cloud_browser_new_folder_hint,
        ) { name, dismissNameDialog ->
            when (CloudBrowserRules.newFolderOutcome(name, entries, path.size)) {
                CloudBrowserRules.NewFolderOutcome.REFUSED -> problem(
                    activity.getString(R.string.cloud_browser_name_title),
                    activity.getString(R.string.cloud_browser_name_body),
                )
                CloudBrowserRules.NewFolderOutcome.ENTER_EXISTING -> {
                    dismissNameDialog()
                    navigate(path + name)
                }
                CloudBrowserRules.NewFolderOutcome.CREATE -> {
                    dismissNameDialog()
                    createFolder(name)
                }
            }
        }
    }

    /** `ensureFolder` on the way in — find-or-create, then enter what it answered. */
    private fun createFolder(name: String) {
        if (loading) return
        loading = true
        showMessage(R.string.cloud_browser_creating)
        val target = path + name
        activity.lifecycleScope.launch {
            try {
                CloudClient.ensureFolder(activity, ref, target.toTypedArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                if (dialog.isShowing) onListFailed(e)
                return@launch
            }
            loading = false
            if (!dialog.isShowing) return@launch
            navigate(target)
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private fun renderCrumb() {
        crumb.text = CloudBrowserRules.crumb(
            providerName, path, activity.getString(R.string.cloud_browser_crumb_separator),
        )
        // INVISIBLE, not GONE: the crumb must not step sideways when the floor is reached.
        btnUp.visibility =
            if (CloudBrowserRules.canGoUp(path.size, basePath.size)) View.VISIBLE else View.INVISIBLE
    }

    /** The waiting/empty body — one TextView over the rows, so the list never half-shows a
     *  listing that is being replaced. */
    private fun showMessage(@StringRes textRes: Int) {
        rows.removeAllViews()
        message.setText(textRes)
        message.visibility = View.VISIBLE
        pager.visibility = View.INVISIBLE
    }

    private fun rowCount(): Int =
        entries.size + if (mode == Mode.PICK_FOLDER) 1 else 0

    private fun goToListPage(page: Int) {
        if (loading) return
        val clamped = GridMath.clampPage(page, CloudBrowserRules.pageCount(rowCount(), itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op, never a disabled look
        listPage = clamped
        render()
    }

    private fun render() {
        if (!measured) return
        val all = CloudBrowserRules.rows(entries, offersNewFolder = mode == Mode.PICK_FOLDER)
        if (all.isEmpty()) {
            showMessage(R.string.cloud_browser_empty)
            return
        }
        message.visibility = View.GONE
        rows.removeAllViews()
        val pageCount = CloudBrowserRules.pageCount(all.size, itemsPerPage)
        listPage = GridMath.clampPage(listPage, pageCount)
        val inflater = LayoutInflater.from(activity)
        for (row in CloudBrowserRules.page(all, listPage, itemsPerPage)) {
            rows.addView(buildRow(inflater, row))
        }
        pageLabel.text = activity.getString(R.string.page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private fun buildRow(inflater: LayoutInflater, row: CloudBrowserRules.Row): View {
        val view = inflater.inflate(R.layout.item_cloud_entry, rows, false)
        val icon = view.findViewById<AppCompatImageView>(R.id.cloudRowIcon)
        val label = view.findViewById<TextView>(R.id.cloudRowLabel)
        when (row) {
            is CloudBrowserRules.Row.NewFolder -> {
                icon.setImageResource(R.drawable.ic_folder_plus)
                label.setText(R.string.cloud_browser_new_folder)
                view.setOnClickListener { onNewFolder() }
            }
            is CloudBrowserRules.Row.Entry -> {
                val entry = row.entry
                icon.setImageResource(if (entry.isFolder) R.drawable.ic_folder else R.drawable.ic_file_text)
                label.text = entry.name
                if (entry.isFolder) {
                    view.setOnClickListener { navigate(path + entry.name) }
                }
                // A file row carries no listener in this phase — see the class doc. Not disabled,
                // not greyed: it is a thing that is there, drawn as what it is.
            }
        }
        return view
    }

    /** Answer once, whichever way the showing ended. */
    private inline fun answer(block: () -> Unit) {
        if (answered) return
        answered = true
        block()
    }

    /** A problem over the browser — the browser stays, because nothing about it changed. */
    private fun problem(title: CharSequence, body: CharSequence) {
        if (!dialog.isShowing) return
        Dialogs.problem(activity, title, body)
    }

    private companion object {
        const val TAG = "CloudBrowser"
    }
}
