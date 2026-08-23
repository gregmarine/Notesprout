package com.symmetricalpalmtree.notesproutsn.library

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import kotlinx.coroutines.launch

/**
 * **New folder**, with its default-notebook-name scheme in the same dialog — naming a folder and
 * saying what goes in it is one thought. Extracted from the library (arc 6 / K3) so the link
 * picker's New folder is not a second implementation that can drift from it: one dialog, one
 * validation order, one set of words, two entry points.
 *
 * The order is deliberate: name rule → **scheme** → duplicate check → create → save the scheme.
 * The scheme is validated *before* the folder exists, so a mistyped token keeps the dialog (and
 * everything typed in it) rather than leaving a folder behind. Once the folder is created it
 * stands: a scheme that then fails to save is explained, not rolled back — the user asked for a
 * folder and got one, and the scheme can be set again from its long-press.
 *
 * `accepting` is the OK button's re-entry guard (S2 review finding): the accept path crosses a
 * coroutine, and an e-ink double-tap landing in that window would run two creates whose duplicate
 * checks both read before either insert — two identically named folders. Armed only once the checks
 * that keep the dialog open have passed; released in `finally`.
 */
object NewFolderFlow {

    private const val TAG = "NewFolderFlow"

    /**
     * Show the dialog for a folder created under [parentFolderId] (null = the library root).
     * [onCreated] runs **after** the dialog dismisses, with the row that was just written — the
     * library refreshes its grid, the picker navigates into it. It runs on a scheme-save failure
     * too: the folder exists either way, and the caller's view of the library must say so.
     */
    fun show(
        activity: AppCompatActivity,
        repo: IndexRepository,
        parentFolderId: String?,
        onCreated: suspend (ObjectEntity) -> Unit,
    ) {
        val schemeField = SchemeDialog.buildField(activity)
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.new_folder_title,
            confirmRes = R.string.new_notebook_create,
            hintRes = R.string.new_folder_hint,
            extraField = schemeField,
        ) { name, dismiss ->
            if (accepting) return@show
            val scheme = schemeField.text()
            val problem = NameRules.validate(name)
            if (problem != null) {
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            if (scheme.isNotEmpty()) {
                val bad = SchemeEngine.validate(scheme)
                if (bad != null) {
                    Dialogs.problem(activity, R.string.naming_problem_title, SchemeDialog.message(activity, bad))
                    return@show
                }
            }
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    if (repo.nameTaken(parentFolderId, ObjectType.FOLDER, name)) {
                        Dialogs.problem(
                            activity, R.string.name_problem_title,
                            activity.getString(R.string.new_folder_duplicate, name),
                        )
                        return@launch
                    }
                    val folder = repo.createFolder(name, parentFolderId)
                    if (scheme.isNotEmpty()) {
                        try {
                            repo.setScheme(folder.id, scheme)
                        } catch (e: Exception) {
                            Log.w(TAG, "new folder: scheme save failed", e)
                            Dialogs.problem(activity, R.string.naming_problem_title, R.string.naming_save_failed)
                        }
                    }
                    dismiss()
                    onCreated(folder)
                } finally {
                    accepting = false
                }
            }
        }
    }
}
