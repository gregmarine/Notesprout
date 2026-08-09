package com.notesprout.android.core

import android.os.Build
import java.util.Locale

fun isBooxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")

// "supernote", not "ratta": measured on both devices (Nomad + Manta), ro.product.manufacturer
// is "Supernote" and the company name appears nowhere in the build properties. The two devices
// are byte-identical in every ro.product.* prop (the Manta even reports MODEL "Supernote Nomad"),
// so any per-device branching must key off screen size, never Build.MODEL.
fun isRattaDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")
