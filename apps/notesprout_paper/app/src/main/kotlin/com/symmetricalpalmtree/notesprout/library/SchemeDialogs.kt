package com.symmetricalpalmtree.notesprout.library

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.NamerClient
import com.symmetricalpalmtree.notesprout.extension.SchemeField
import kotlinx.coroutines.launch

/**
 * The one text field the core draws from a namer's [SchemeField] (caption above, hint inside, one
 * help line below) — used by the New-folder dialog and by the folder long-press "Default notebook
 * name…" dialog. The core never interprets the scheme: validation and storage are the extension's,
 * reached through [NamerClient]; the core only decides what the user sees on failure.
 */
object SchemeDialogs {

    private const val TAG = "SchemeDialogs"

    /** The views of one scheme field; [input] holds the scheme text. */
    class FieldViews(val caption: TextView, val input: EditText, val help: TextView) {
        fun addTo(parent: LinearLayout) {
            parent.addView(caption)
            parent.addView(input)
            parent.addView(help)
        }
    }

    /** Build the field for [field], prefilled with [initial] (select-all'd when non-empty). */
    fun buildField(context: Context, field: SchemeField, initial: String?): FieldViews {
        val density = context.resources.displayMetrics.density
        val inkBlack = ContextCompat.getColor(context, R.color.inkBlack)
        val caption = TextView(context).apply {
            text = field.label
            setTextAppearance(R.style.TextAppearance_Notesprout_BodyMedium)
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        val input = EditText(context).apply {
            hint = field.hint
            textSize = 16f
            setTextColor(inkBlack)
            background = ContextCompat.getDrawable(context, R.drawable.shape_bordered)
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            if (!initial.isNullOrEmpty()) { setText(initial); selectAll() }
        }
        val help = TextView(context).apply {
            text = field.help
            setTextAppearance(R.style.TextAppearance_Notesprout_BodySmall)
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        return FieldViews(caption, input, help)
    }

    /**
     * The folder long-press dialog: [field] + the folder's [current] scheme (both fetched by the caller
     * **before** this shows). OK → blank clears; otherwise validate (error → problem dialog, this dialog
     * stays) then save. Any failure to reach the extension → [R.string.naming_unavailable] problem
     * dialog; this dialog stays so the text isn't lost.
     */
    fun showSchemeDialog(
        activity: AppCompatActivity,
        client: NamerClient,
        folderId: String,
        folderName: String,
        field: SchemeField,
        current: String?,
    ) {
        val density = activity.resources.displayMetrics.density
        val views = buildField(activity, field, current)
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, 0, pad, 0)
            views.addTo(this)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(folderName)
            .setView(wrapper)
            .setPositiveButton(activity.getString(R.string.ok), null)
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .create()
        Dialogs.style(dialog)
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val scheme = views.input.text.toString().trim()
                ok.isClickable = false
                activity.lifecycleScope.launch {
                    try {
                        val err = if (scheme.isEmpty()) null else client.validate(scheme)
                        if (err != null) {
                            Dialogs.problem(activity, R.string.naming_problem_title, err)
                            return@launch
                        }
                        client.save(folderId, scheme)
                        dialog.dismiss()
                    } catch (e: ExtensionCallException) {
                        Slog.d(TAG) { "scheme dialog: ${e.message}" }
                        Dialogs.problem(activity, R.string.naming_problem_title, R.string.naming_unavailable)
                    } finally {
                        ok.isClickable = true
                    }
                }
            }
        }
        dialog.show()
    }
}
