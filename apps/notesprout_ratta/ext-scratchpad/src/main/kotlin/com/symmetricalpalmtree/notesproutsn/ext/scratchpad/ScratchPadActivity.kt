package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck

/**
 * The extension-owned screen (arc 11; UI-rule tier 2). **J3 stub**: the caller check + a
 * "Scratch Pad" title + Back — the real paper screen (g-paper canvas, tools, pages, the store) is
 * J4. The host launches it with an `ActivityResultLauncher` (so `callingPackage` is set) and only
 * the recorded `EXTRA_*` / `RESULT_*` cross; data never rides the Intent.
 */
class ScratchPadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScratchPadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivityScratchPadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnBack.setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        Slog.d(TAG) { "opened (J3 stub)" }
    }

    companion object {
        private const val TAG = "ScratchPadActivity"
    }
}
