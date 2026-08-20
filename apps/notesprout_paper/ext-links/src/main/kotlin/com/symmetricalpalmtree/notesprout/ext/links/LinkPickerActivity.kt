package com.symmetricalpalmtree.notesprout.ext.links

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.ext.links.databinding.ActivityLinkPickerBinding
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck

/**
 * The extension-owned link picker (arc 7; UI-rule tier 2) — **an L0 stub**: it proves the caller
 * check and the launch-for-a-result plumbing, and shows nothing but its title and a Back button.
 * L2 replaces the body with the three-mode target chooser, the chrome toggle and the `LinkChoice`
 * the host drains through `takeResult`.
 *
 * The host launches it with an `ActivityResultLauncher` after `beginPick` on the held bind, so it
 * verifies its caller **first thing** ([HostCallerCheck.enforceActivity] — a plain `am start` has no
 * calling package and is refused). Nothing but `EXTRA_LINK_EDIT` ever rides the Intent: the payload
 * itself reaches the screen through [PickSession].
 */
class LinkPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivityLinkPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        TooltipCompat.setTooltipText(binding.btnBack, getString(R.string.cd_links_back))
        binding.btnBack.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        private const val TAG = "LinkPickerActivity"
    }
}
