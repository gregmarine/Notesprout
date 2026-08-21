package com.symmetricalpalmtree.notesproutsn.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityLibraryBinding
import kotlinx.coroutines.launch

/**
 * **R1 shell.** The real library — folders, notebook cards, sort, search, the bottom bar — is R2.
 * What exists here is the frame the rest of the app lands on: the index is proven open (the
 * `IndexGuard` check every index-touching screen opens with), the pinned-list sentinel is created
 * on demand exactly once, and the debug ⋯ hangs off the top bar so unlock testing is possible on a
 * device before any real screen exists.
 *
 * The top bar sits flush against the top edge: on Ratta the top guard is 0 (no status-bar hazard).
 */
class LibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        val binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DebugMenu.install(this, binding.topBar)

        lifecycleScope.launch { IndexRepository().ensurePinnedListExists() }
    }
}
