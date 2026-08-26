package com.symmetricalpalmtree.notesproutsn.templates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityTemplatesBinding

/**
 * **Templates** — the template library (arc 13). The library screen's shape applied to paper:
 * breadcrumbs, the same paginated non-scrolling card grid, the same sort sheet, the same long-press
 * management. All of that is [TemplateBrowser] now (G3); what is left here is the screen around it.
 *
 * The screen has two modes, and they are the same browser:
 *
 *  - **browse** (from the library's bottom bar) — a tap on a paper card does nothing. This is a
 *    library, not a picker: you came here to organise, import and name.
 *  - **pick** (from a notebook's page-paper row, launched with an `ActivityResultLauncher`) — a tap
 *    on a paper card *is* the answer: it goes back as a [TemplatePick] and the screen closes. The
 *    caller passes the page's current token so the card in force is ticked.
 *
 * It is not a paper surface, so a notebook launching it does **not** hand over the EPD pipeline —
 * chrome only, and the notebook's session, undo stack and unsaved page are all still there when the
 * result comes back. And it never opens a `.soil`: it returns a pick, and the notebook does the
 * write.
 */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplatesBinding
    private lateinit var browser: TemplateBrowser

    private var picking = false
    private var selectedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        picking = intent.getBooleanExtra(EXTRA_PICK_MODE, false)
        selectedToken = intent.getStringExtra(EXTRA_SELECTED_TOKEN)

        browser = TemplateBrowser(
            activity = this,
            binding = binding.browser,
            onPick = { pick -> if (picking) finishWith(pick) },
            // In browse mode nothing is in force, so nothing ticks. In pick mode the page's own
            // token is the only thing this screen knows about it — by design: the browser has never
            // heard of the notebook, and the notebook has never heard of the library row.
            selection = { TemplateBrowser.Selection(token = selectedToken.takeIf { picking }) },
        )

        binding.browser.btnClose.visibility = View.VISIBLE
        binding.browser.btnClose.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.browser.btnClose, binding.browser.btnClose.contentDescription)
    }

    private fun finishWith(pick: TemplatePick) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICK, pick.encode()))
        finish()
    }

    /** Back peels one layer: up a folder, then out of the screen. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.onBackPressed()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val EXTRA_PICK_MODE = "pickMode"
        private const val EXTRA_SELECTED_TOKEN = "selectedToken"

        /** The chosen card, [TemplatePick.encode]d, on a `RESULT_OK` from [pickIntent]. */
        const val EXTRA_PICK = "pick"

        /** Browse the library — a tap on a paper card means nothing. */
        fun intent(context: Context): Intent = Intent(context, TemplatesActivity::class.java)

        /**
         * Pick paper for a page. [currentToken] is the page's `.soil` template token (`""` for
         * blank) so the card in force is ticked; **null** ticks nothing, which is the right answer
         * when the page's row has vanished and the app genuinely does not know.
         */
        fun pickIntent(context: Context, currentToken: String?): Intent =
            Intent(context, TemplatesActivity::class.java)
                .putExtra(EXTRA_PICK_MODE, true)
                .putExtra(EXTRA_SELECTED_TOKEN, currentToken)
    }
}
