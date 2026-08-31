package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renaming a text document from its own title (arc 19 / M8).
 *
 * A text document has no library card on screen and no cover to long-press: this header **is** its
 * chrome, so the title is where its name is changed. The control is deliberately quiet — the title
 * simply becomes tappable, with a long-press hint saying so. There is no visual affordance beyond
 * that, and there could not usefully be one: on e-ink there is no ripple to promise, and a boxed or
 * underlined title would read as a field rather than a heading.
 *
 * Only a **text document** offers it ([TextDocumentRules.offersRename]) and it offers it in **either
 * scope**: the name belongs to the notebook, not to the document on screen.
 *
 * **The host is the only judge of a name.** This side refuses exactly two things, and neither is an
 * error worth a dialog: blank, and the name it already has. Everything else goes to
 * `IDocumentHost.renameNotebook`, which validates against the notebook's siblings and refuses with an
 * `IllegalArgumentException` **whose message is the sentence the writer should read** — the seam's
 * one deliberate exception message, crafted host-side for exactly this. Anything else that comes back
 * is a failure that does not pretend to know more than that.
 *
 * The positive button is wired **after** `show()` (the library's `NameDialog` recipe): the default
 * listener dismisses before anything can object, which would throw the typing away on every refusal.
 * A refused name therefore leaves the dialog standing with the words still in it.
 *
 * **The IME is never hidden from here** — the screen-wide Ratta rule (hardware keys are translated by
 * the IME and delivered only while it is shown), and a dialog is not an exception to it.
 *
 * **The name is user content**: it is never logged, on any path. Lengths and class names only.
 */
internal class RenameControl(
    private val activity: Activity,
    private val binding: ActivityDocumentEditorBinding,
    /** The screen's lifecycle scope — a rename that outlives the screen has nothing to redraw. */
    private val scope: CoroutineScope,
    /** The adopted state, or null before the first one has landed. */
    private val stateNow: () -> DocumentPageState?,
    /** The screen is on its way out; a rename would push into a closing showing. */
    private val leaving: () -> Boolean,
    /** The host took the name: the state, rebuilt around it, for the Activity to hold onto. */
    private val onRetitled: (DocumentPageState) -> Unit,
) {

    /** One rename in flight at a time — the accept path crosses a coroutine, and on e-ink an
     *  unguarded OK gets double-tapped (the library's own latch lesson). */
    private var accepting = false

    /** Listener and hint. Called once, from the Activity's chrome build; [apply] decides whether the
     *  title actually answers a tap. */
    fun install() {
        binding.title.setOnClickListener { tap() }
        apply()
    }

    /** Draw the title for the adopted target: tappable, and hinted, only for a text document. */
    fun apply() {
        val on = offered()
        binding.title.isClickable = on
        binding.title.isLongClickable = on
        TooltipCompat.setTooltipText(
            binding.title,
            if (on) activity.getString(R.string.document_rename_hint) else null,
        )
    }

    /** One tap on the title — the ordinary way. Silent when it may not run. */
    fun tap() {
        if (accepting || leaving() || !offered()) return
        prompt()
    }

    /** Whether this notebook's name may be edited from here at all. */
    private fun offered(): Boolean =
        TextDocumentRules.offersRename(stateNow()?.textDocument == true)

    /** The notebook's name as the last adopted state gave it. */
    private fun titleNow(): String = stateNow()?.title.orEmpty()

    /**
     * Rename without the dialog — the debug hook's path, because a walk cannot type into an
     * `AlertDialog` any more than it can type into the editor. Same guards, same host call, same
     * failure dialogs. Returns whether it started.
     */
    fun rename(name: String): Boolean {
        if (accepting || leaving() || !offered()) return false
        // The cap the dialog's field enforces with a filter — the hook has no field to filter, and a
        // title past it is a state that cannot be built at all.
        val typed = name.trim().take(DocumentContract.MAX_TITLE_CHARS)
        if (!TextDocumentRules.renameWorthAsking(typed, titleNow())) return false
        submit(typed, dismiss = null)
        return true
    }

    private fun prompt() {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val input = EditText(activity).apply {
            setHint(R.string.document_rename_field_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            background = ContextCompat.getDrawable(activity, R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            // The contract's cap, enforced where the typing happens: a state whose title is longer
            // than this fails to unmarshal at all, so the field must not be able to make one.
            filters = arrayOf(InputFilter.LengthFilter(DocumentContract.MAX_TITLE_CHARS))
            val current = titleNow()
            if (current.isNotEmpty()) {
                setText(current)
                setSelection(0, current.length)
            }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(input)
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.document_rename_title)
                .setView(wrapper)
                .setPositiveButton(R.string.document_rename_confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create(),
        )
        dialog.show()
        // After show(), so a refusal keeps the dialog — and the writer's typing — standing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = input.text?.toString().orEmpty()
            if (accepting) return@setOnClickListener
            if (!TextDocumentRules.renameWorthAsking(typed, titleNow())) {
                // Blank, or the name it already has. Neither is a failure; neither is worth a word.
                dialog.dismiss()
                return@setOnClickListener
            }
            submit(typed.trim()) { dialog.dismiss() }
        }
    }

    /** Ask the host, off Main, and answer for what comes back. [dismiss] closes the input dialog on
     *  success — the hook's path has none to close. */
    private fun submit(name: String, dismiss: (() -> Unit)?) {
        accepting = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val host = EditorSession.host ?: throw IllegalStateException("no showing")
                    host.renameNotebook(name)
                    null
                } catch (e: Exception) {
                    e
                }
            }
            accepting = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (error) {
                null -> {
                    landed(name)
                    dismiss?.invoke()
                    Slog.d(TAG) { "renamed (${name.length} chars)" }
                }
                // The ONE message this side reads: the host crafted it for the writer, and it names
                // the actual refusal (taken, reserved, an illegal character) in words this screen
                // could not reconstruct.
                is IllegalArgumentException -> Dialogs.problem(
                    activity,
                    R.string.document_rename_problem_title,
                    error.message ?: activity.getString(R.string.document_rename_failed_body),
                )

                else -> {
                    // The class name only: any other exception's message could carry a path.
                    Slog.d(TAG) { "rename failed: ${error.javaClass.simpleName}" }
                    Dialogs.problem(
                        activity,
                        R.string.document_rename_problem_title,
                        R.string.document_rename_failed_body,
                    )
                }
            }
        }
    }

    /**
     * The host took the name. The header says so at once, and the adopted state is rebuilt around it
     * so a redraw from anywhere else — a Bring in, a flip that abandons — cannot put the old name
     * back. [DocumentPageState] is immutable and validating; every field but the title is exactly
     * what the host last answered with, and the title is one the host has just accepted.
     */
    private fun landed(name: String) {
        binding.title.text = name.ifEmpty { activity.getString(R.string.document_title) }
        val state = stateNow() ?: return
        onRetitled(
            DocumentPageState(
                pageKey = state.pageKey,
                scope = state.scope,
                pageIndex = state.pageIndex,
                pageCount = state.pageCount,
                title = name,
                textDocument = state.textDocument,
                source = state.source,
                textChars = state.textChars,
                textChunks = state.textChunks,
                seeded = state.seeded,
            ),
        )
    }

    private companion object {
        const val TAG = "DocumentEditor"
    }
}
