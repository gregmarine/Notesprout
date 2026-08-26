package com.symmetricalpalmtree.notesproutsn.templates

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.notesproutsn.databinding.ViewOptionStepperBinding

/**
 * One adjustable number on the options screen (arc 13 / G2): `label  [−] 8.0 mm [+]`, with an
 * optional read-out line and an optional trailing latch.
 *
 * **A stepper, chosen by measurement, not by taste.** On e-ink a slider drag is a stream of
 * full-panel refreshes that lands on a 0.5 mm step by luck; a text field needs the IME, which the
 * Supernote swallows `adb shell input text` into, so a device walk could never drive it. A tap is
 * one step and one small refresh, and press-and-hold repeats so a long run does not cost forty of
 * them.
 *
 * Values are **snapped to the step grid** as they move ([StepMath]) — the stock rule thickness is
 * one mdpi pixel, which is not a round millimetre.
 */
class OptionStepper(
    private val binding: ViewOptionStepperBinding,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null

    var value: Float = min
        private set

    init {
        bind(binding.btnMinus, down = true)
        bind(binding.btnPlus, down = false)
    }

    val view: View get() = binding.root

    fun label(text: String) { binding.label.text = text }

    /** Set the value without calling back — the screen owns the spec and pushes state down. */
    fun show(v: Float) {
        value = v.coerceIn(min, max)
        binding.value.text = format(value)
    }

    /** The second line under the row ("→ 27 lines"), or null to hide it. */
    fun readout(text: String?) {
        binding.readout.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.readout.text = text.orEmpty()
    }

    /**
     * The trailing latch (the axes' "Square" link). Its slot is reserved in every row and merely
     * shown here — a latch that appeared out of nowhere would move the row it is in.
     */
    fun extra(text: String, onTap: () -> Unit) {
        binding.btnExtra.visibility = View.VISIBLE
        binding.btnExtra.text = text
        binding.btnExtra.setOnClickListener { onTap() }
    }

    fun extraSelected(selected: Boolean) { binding.btnExtra.isSelected = selected }

    fun visible(show: Boolean) { binding.root.visibility = if (show) View.VISIBLE else View.GONE }

    /** Stop any repeat still in flight — the screen calls this when it goes away. */
    fun release() {
        repeat?.let { handler.removeCallbacks(it) }
        repeat = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bind(button: View, down: Boolean) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    fire(down)
                    startRepeat(down)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    release()
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    release()
                }
            }
            true
        }
        // performClick() above needs a listener to be worth calling; the work is already done.
        button.setOnClickListener { }
    }

    private fun startRepeat(down: Boolean) {
        release()
        val r = object : Runnable {
            override fun run() {
                fire(down)
                handler.postDelayed(this, REPEAT_MS)
            }
        }
        repeat = r
        handler.postDelayed(r, FIRST_REPEAT_MS)
    }

    private fun fire(down: Boolean) {
        val next = if (down) StepMath.down(value, step, min, max) else StepMath.up(value, step, min, max)
        if (next == value) return
        show(next)
        onChange(next)
    }

    private companion object {
        const val FIRST_REPEAT_MS = 420L
        const val REPEAT_MS = 110L
    }
}
