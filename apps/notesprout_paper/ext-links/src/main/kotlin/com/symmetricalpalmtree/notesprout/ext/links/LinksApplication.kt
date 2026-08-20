package com.symmetricalpalmtree.notesprout.ext.links

import android.app.Application

/**
 * The extension's Application — nothing to register. Unlike the Scratch Pad (arc 6) this process
 * never hosts a paper surface: the picker is an ordinary screen, so g-paper's engines stay
 * unregistered even though the library rides in on `:paper-screen` (which is here for the design
 * system alone).
 */
class LinksApplication : Application()
