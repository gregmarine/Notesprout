package com.notesprout.android.core

import android.os.Build
import java.util.Locale

fun isBooxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
