package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.bible.databinding.ActivityBibleBinding
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * The Bible reader's placeholder screen (arc 37 / B0; UI-rule tier 2) — SN's **fifth**
 * screen-owning point and, like the tag manager, one whose screen carries **no paper**. There is
 * no `PaperView`, no g-paper call and therefore **no EPD handoff**. Do not add one.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is
 * exported (it has to be — the host launches it by action) and only a
 * `startActivityForResult` from the host package gets in. A plain `am start` from a shell has a
 * null `callingPackage` and is refused.
 *
 * There is no chrome flag riding this screen (the tag manager's recorded answer): B1 is what
 * replaces the empty reader band with the actual reader, over the store this screen already
 * holds via [BibleSession].
 */
class BibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBibleBinding
    private var store: IExtensionStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            super.onCreate(savedInstanceState)
            return
        }
        super.onCreate(savedInstanceState)
        binding = ActivityBibleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Null-tolerant: a null store just means the placeholder shows with nothing to read from
        // yet. B1's reader is what actually needs it.
        store = BibleSession.store

        binding.title.setText(R.string.bible_title)
        binding.btnBack.setOnClickListener { leave() }
        binding.btnBack.setOnLongClickListener {
            hint(R.string.cd_bible_back)
        }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun leave() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    /** Every icon button names itself on a long press — words read better than glyphs on e-ink. */
    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }
}
