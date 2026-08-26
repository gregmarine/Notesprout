package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules

/**
 * The name rules for anything in the template library, with their dialogs — one copy, because two
 * screens now name templates (the library's New folder / Rename, and the options screen's
 * **Save as template…**) and a rule enforced in one of them is a rule the user can walk around.
 */
object TemplateNaming {

    /**
     * The rules in order — the family charset first, then the reserved root name. True when the
     * name was refused **and the dialog was told why**, which is the caller's cue to keep it open
     * with the typing intact.
     *
     * The database's own duplicate check is not here: it is a round-trip, so only the caller knows
     * when it can be asked.
     */
    fun reject(activity: Activity, name: String, parentId: String?): Boolean {
        NameRules.validate(name)?.let { problem ->
            Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
            return true
        }
        if (TemplateLibrary.isReservedName(parentId, name)) {
            Dialogs.problem(
                activity, R.string.name_problem_title,
                activity.getString(R.string.template_name_reserved, TemplateLibrary.RESERVED_ROOT_NAME),
            )
            return true
        }
        return false
    }
}
