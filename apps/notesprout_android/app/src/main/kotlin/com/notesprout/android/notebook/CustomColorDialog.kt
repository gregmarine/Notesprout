package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.notesprout.android.R
import com.notesprout.android.core.InkColor
import com.notesprout.android.databinding.DialogCustomColorBinding

/**
 * Mix an arbitrary ink colour: an SV field + hue strip, R/G/B sliders, and a hex field — three ways
 * into one value, all kept in sync.
 *
 * **Sync without feedback loops.** Every input writes through [apply], and [applying] suppresses the
 * listeners while it pushes the new value back out to the other two. Without that guard, setting the
 * hex field from a slider would re-enter the hex watcher and fight the slider that started it.
 *
 * **The hex field is the one input that may be mid-nonsense.** A user typing `#1A` has an incomplete
 * value, so its watcher only commits on a well-formed 7-character string and otherwise leaves the
 * model alone — it never rewrites what is being typed. On dismiss the field is normalised back to the
 * committed colour, so an abandoned partial edit cannot leak out.
 */
class CustomColorDialog(
    private val context: Context,
    private val initial: String,
    private val onChosen: (String) -> Unit,
) {

    private lateinit var binding: DialogCustomColorBinding

    /** Guards the two-way sync — see the class docs. */
    private var applying = false

    private var current: Int = InkColor.toInt(initial)

    fun show() {
        binding = DialogCustomColorBinding.inflate(LayoutInflater.from(context))

        binding.svField.onPick = { s, v ->
            val hsv = floatArrayOf(binding.hueStrip.hue, s, v)
            apply(Color.HSVToColor(hsv), from = Source.FIELD)
        }
        binding.hueStrip.onPick = { h ->
            binding.svField.hue = h
            val hsv = floatArrayOf(h, binding.svField.saturation, binding.svField.brightness)
            apply(Color.HSVToColor(hsv), from = Source.FIELD)
        }

        wireSeek(binding.seekRed) { v -> withChannel(red = v) }
        wireSeek(binding.seekGreen) { v -> withChannel(green = v) }
        wireSeek(binding.seekBlue) { v -> withChannel(blue = v) }

        binding.editHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applying) return
                val text = s?.toString()?.trim().orEmpty()
                // Only commit a complete value; a half-typed "#1A" must not rewrite the field.
                if (!Regex("^#[0-9a-fA-F]{6}$").matches(text)) return
                apply(InkColor.toInt(text), from = Source.HEX)
            }
        })

        apply(current, from = Source.INIT)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Custom color")
            .setView(binding.root)
            .setPositiveButton("Use", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        // Both buttons hide the IME explicitly, while the dialog window still exists — on some BOOX
        // devices it does not dismiss on its own, and Cancel must be a real listener for that reason.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            imm.hideSoftInputFromWindow(binding.editHex.windowToken, 0)
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            imm.hideSoftInputFromWindow(binding.editHex.windowToken, 0)
            dialog.dismiss()
            onChosen(InkColor.toHex(current))
        }
    }

    /** Which input started this change — it is the one [apply] must not write back to. */
    private enum class Source { INIT, FIELD, SLIDER, HEX }

    private fun withChannel(red: Int? = null, green: Int? = null, blue: Int? = null) {
        val argb = Color.rgb(
            red ?: Color.red(current),
            green ?: Color.green(current),
            blue ?: Color.blue(current),
        )
        apply(argb, from = Source.SLIDER)
    }

    /** The single write path: set the value, then refresh every input except [from]. */
    private fun apply(argb: Int, from: Source) {
        current = argb
        applying = true
        try {
            val r = Color.red(argb)
            val g = Color.green(argb)
            val b = Color.blue(argb)

            if (from != Source.SLIDER) {
                binding.seekRed.progress = r
                binding.seekGreen.progress = g
                binding.seekBlue.progress = b
            }
            binding.valRed.text = r.toString()
            binding.valGreen.text = g.toString()
            binding.valBlue.text = b.toString()

            if (from != Source.HEX) {
                binding.editHex.setText(InkColor.toHex(argb))
            }

            if (from != Source.FIELD) {
                val hsv = FloatArray(3)
                Color.colorToHSV(argb, hsv)
                // A greyscale or black value has no meaningful hue; keep the strip where it is so the
                // field does not lurch to red when someone drags the value slider to zero.
                if (hsv[1] > 0f && hsv[2] > 0f) binding.hueStrip.hue = hsv[0]
                binding.svField.hue = binding.hueStrip.hue
                binding.svField.setSelection(hsv[1], hsv[2])
            }

            binding.colorPreview.background = GradientDrawable().apply {
                setColor(argb)
                cornerRadius = 4f * context.resources.displayMetrics.density
                setStroke(
                    (context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    Color.BLACK,
                )
            }

            // The Kaleido brightness floor, surfaced before committing rather than discovered as ink
            // that looks black until something forces a refresh. It describes a *preview* limit —
            // the stroke is stored and drawn in its true colour either way — so it never blocks.
            binding.tvDimWarning.visibility =
                if (InkColor.isOverlaySafe(InkColor.toHex(argb))) View.GONE else View.VISIBLE
        } finally {
            applying = false
        }
    }

    private fun wireSeek(seek: SeekBar, onValue: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || applying) return
                onValue(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }
}
