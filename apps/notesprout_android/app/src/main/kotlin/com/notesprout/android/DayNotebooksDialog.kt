package com.notesprout.android

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.DayHistoryRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The calendar's **long-press day list**: every notebook created / opened / edited on one day, as a
 * plain tappable list (name + containing folder + what happened). A deliberately lighter read than
 * the day window's paginated card grid — it is a shortcut, not a browser.
 *
 * One row per notebook, all of that day's activity tags on the same row (see
 * [DayHistoryRepository.notebooksForDay]). Tapping a row opens the notebook through the normal
 * [NotebookActivity] flow, so encrypted notebooks still route through their unlock path.
 *
 * Long-pressing a day with no activity still opens the dialog, showing an empty message — the
 * gesture always does the same thing, so a quiet day never reads as a missed press.
 */
object DayNotebooksDialog {

    private val HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

    fun show(activity: AppCompatActivity, date: LocalDate) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(activity, 16)
            setPadding(p, p, p, dp(activity, 8))
        }
        root.addView(TextView(activity).apply {
            text = date.format(HEADER_FORMAT)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(activity, R.color.inkBlack))
            setPadding(0, 0, 0, dp(activity, 12))
        })

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(rows)
        })

        val dlg = AlertDialog.Builder(activity)
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        activity.lifecycleScope.launch {
            val entries = DayHistoryRepository().notebooksForDay(date)
            if (!dlg.isShowing) return@launch
            if (entries.isEmpty()) {
                rows.addView(emptyRow(activity))
                return@launch
            }
            for (entry in entries) {
                rows.addView(notebookRow(activity, entry) {
                    dlg.dismiss()
                    activity.startActivity(
                        Intent(activity, NotebookActivity::class.java)
                            .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, entry.notebookId)
                            .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, entry.notebookName)
                    )
                })
            }
        }
    }

    // ── Rows ─────────────────────────────────────────────────────────────────────

    /** Notebook name on top, `folder · what happened` beneath it in secondary ink. */
    private fun notebookRow(
        activity: AppCompatActivity,
        entry: DayHistoryRepository.DayNotebook,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val v = dp(activity, 10)
        setPadding(0, v, 0, v)
        addView(TextView(activity).apply {
            text = entry.notebookName
            textSize = 15f
            setTextColor(color(activity, R.color.inkBlack))
        })
        addView(TextView(activity).apply {
            text = "${entry.folderPath} · ${entry.activityLabel}"
            textSize = 12f
            setTextColor(color(activity, R.color.inkLight))
        })
        setOnClickListener { onClick() }
    }

    private fun emptyRow(activity: AppCompatActivity): TextView = TextView(activity).apply {
        text = "No notebooks on this day"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(color(activity, R.color.inkLight))
        setPadding(0, dp(activity, 24), 0, dp(activity, 24))
    }

    private fun dp(activity: AppCompatActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private fun color(activity: AppCompatActivity, res: Int): Int = ContextCompat.getColor(activity, res)
}
