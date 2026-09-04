package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.view.View
import android.widget.Button
import androidx.annotation.StringRes

/**
 * The seven buttons of `view_count_latches.xml`, wired (arc 24 / Z5b) — the one control the repeat
 * interval, the "after N times" count and the reminder's amount all share, so a count is asked for
 * the same way in all three places. Which latch is down and what More says are [CountPresets]'; this
 * class is views and listeners only.
 *
 * **It reports, it does not remember.** A preset tap and a keypad Done both come out of [onValue],
 * and the caller writes them into whatever it holds and calls [render] back with the answer — the
 * same "one road" every other control on these dialogs takes. The value kept here is only what the
 * keypad needs to open on.
 *
 * @param row the `<include>`d latch row.
 * @param titleRes what the keypad calls the number it is asking for ("Every", "After", "Remind me").
 * @param range the field's own range — the keypad's typing width and its clamp.
 */
class CountLatches(
    row: View,
    private val activity: Activity,
    private val range: IntRange,
    @param:StringRes private val titleRes: Int,
    private val onValue: (Int) -> Unit,
) {

    private val presets: List<Button> = listOf(
        row.findViewById(R.id.latchCount1),
        row.findViewById(R.id.latchCount2),
        row.findViewById(R.id.latchCount3),
        row.findViewById(R.id.latchCount4),
        row.findViewById(R.id.latchCount5),
        row.findViewById(R.id.latchCount6),
    )
    private val more: Button = row.findViewById(R.id.latchCountMore)
    private val moreWord: String = activity.getString(R.string.editor_more)

    /** What the keypad opens on. Only [render] writes it — a tap answers through [onValue] and
     *  comes back as a render, so this can never disagree with what the caller holds. */
    private var value: Int = range.first

    init {
        for ((index, preset) in presets.withIndex()) {
            val n = CountPresets.PRESETS.first + index
            preset.text = n.toString()
            preset.setOnClickListener { onValue(n) }
        }
        more.setOnClickListener {
            KeypadDialog.show(activity, titleRes, value, range) { picked -> onValue(picked) }
        }
    }

    /** The row redrawn for [value] — the one latch that is down, and More's own label. */
    fun render(value: Int) {
        this.value = value
        val down = CountPresets.pressed(value)
        for ((index, preset) in presets.withIndex()) preset.isSelected = down[index]
        more.isSelected = down[CountPresets.SIZE - 1]
        more.text = CountPresets.moreLabel(value, moreWord)
    }
}
