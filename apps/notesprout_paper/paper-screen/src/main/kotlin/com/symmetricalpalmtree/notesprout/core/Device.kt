package com.symmetricalpalmtree.notesprout.core

import android.os.Build
import java.util.Locale

fun isBooxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")

fun isRattaDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")
