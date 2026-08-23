package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import kotlinx.coroutines.launch

/**
 * "Default notebook name" — the one place a folder's [SchemeEngine] scheme is typed.
 *
 * Three things it insists on:
 *
 *  - **The dialog survives a rejected scheme.** The positive button is wired *after* `show()` (the
 *    [NameDialog] pattern): the default listener dismisses before anything can object, and throwing
 *    away a half-typed scheme because of one bad token is the worst moment to lose text.
 *  - **Blank means clear.** Emptying the field is how a folder gives its scheme back — there is no
 *    separate "remove" control to find.
 *  - **It never throws.** Every index read and write here runs inside a `try`; a failure is a
 *    problem dialog (the dialog-explains rule) and the typing stays on screen. Nothing about naming
 *    is worth taking the library down for.
 *
 * The field itself ([buildField]) is shared with the New-folder dialog, so the two places a scheme
 * can be typed look and read the same: caption above, hint inside, one help line below. The help
 * line is inkBlack made *smaller* rather than inkLight — it carries the token list, which is
 * information, and grey text on e-ink is for things not meant to be read.
 */
object SchemeDialog {

    private const val TAG = "SchemeDialog"

    /** The three views of one scheme field, in the order they stack. */
    class FieldViews(val caption: TextView, val input: EditText, val help: TextView) {

        fun addTo(parent: LinearLayout) {
            parent.addView(caption)
            parent.addView(input)
            parent.addView(help)
        }

        /** What the user typed, trimmed — empty means "no scheme". */
        fun text(): String = input.text.toString().trim()
    }

    /** Build the caption / field / help stack, prefilled with [initial] (select-all'd when present). */
    fun buildField(
        context: Context,
        captionRes: Int = R.string.scheme_caption,
        hintRes: Int = R.string.scheme_hint,
        helpRes: Int = R.string.scheme_help,
        initial: String? = null,
    ): FieldViews {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val caption = TextView(context).apply {
            setText(captionRes)
            textSize = 14f
            setTextColor(ink)
            setPadding(0, (16 * d).toInt(), 0, (4 * d).toInt())
        }
        val input = NameDialog.input(context, hintRes, initial)
        val help = TextView(context).apply {
            setText(helpRes)
            // Secondary, so it is smaller — never inkLight: the token list is meant to be read.
            textSize = 12f
            setTextColor(ink)
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        return FieldViews(caption, input, help)
    }

    /**
     * Read [folderId]'s current scheme, then show the dialog. [folderId] null is the library root,
     * whose title is the root crumb's own word. A read failure explains itself and opens nothing —
     * showing an empty field would silently offer to overwrite a scheme that is actually there.
     */
    fun open(activity: AppCompatActivity, repo: IndexRepository, folderId: String?, folderName: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.lifecycleScope.launch {
            val current = try {
                repo.scheme(folderId)
            } catch (e: Exception) {
                Log.w(TAG, "scheme read failed", e)
                Dialogs.problem(activity, R.string.naming_problem_title, R.string.naming_load_failed)
                return@launch
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            show(activity, repo, folderId, folderName, current)
        }
    }

    private fun show(
        activity: AppCompatActivity,
        repo: IndexRepository,
        folderId: String?,
        folderName: String,
        current: String?,
    ) {
        val d = activity.resources.displayMetrics.density
        val views = buildField(activity, initial = current)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, 0, side, 0)
            views.addTo(this)
        }

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(folderName)
                .setView(wrapper)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            val scheme = views.text()
            // Never isEnabled = false: a disabled control is invisible on e-ink (the click guard).
            ok.isClickable = false
            activity.lifecycleScope.launch {
                try {
                    if (scheme.isEmpty()) {
                        repo.clearScheme(folderId)
                        Slog.d(TAG) { "scheme cleared for ${folderId ?: "root"}" }
                        dialog.dismiss()
                        return@launch
                    }
                    val problem = SchemeEngine.validate(scheme)
                    if (problem != null) {
                        Dialogs.problem(activity, R.string.naming_problem_title, message(activity, problem))
                        return@launch
                    }
                    repo.setScheme(folderId, scheme)
                    Slog.d(TAG) { "scheme saved for ${folderId ?: "root"}" }
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Degrade, never throw: this runs in lifecycleScope, which has no handler.
                    Log.w(TAG, "scheme save failed", e)
                    Dialogs.problem(activity, R.string.naming_problem_title, R.string.scheme_save_failed)
                } finally {
                    ok.isClickable = true
                }
            }
        }
    }

    /** Turn a [SchemeEngine.SchemeException] into the sentence the user sees. */
    fun message(context: Context, e: SchemeEngine.SchemeException): String = when (e.error) {
        SchemeEngine.Error.UNKNOWN_TOKEN -> context.getString(R.string.err_scheme_unknown_token, e.detail)
        SchemeEngine.Error.UNCLOSED_BRACE -> context.getString(R.string.err_scheme_unclosed_brace)
        SchemeEngine.Error.COUNTER_TWICE -> context.getString(R.string.err_scheme_counter_twice)
        SchemeEngine.Error.ILLEGAL_CHAR -> context.getString(R.string.err_scheme_illegal_char)
        SchemeEngine.Error.EMPTY -> context.getString(R.string.err_scheme_empty)
        SchemeEngine.Error.TOO_LONG -> context.getString(R.string.err_scheme_too_long, SchemeEngine.MAX_SCHEME_CHARS)
    }
}
