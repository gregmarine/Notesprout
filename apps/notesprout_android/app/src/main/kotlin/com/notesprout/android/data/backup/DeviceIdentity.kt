package com.notesprout.android.data.backup

import android.os.Build
import java.util.UUID

object DeviceIdentity {

    fun defaultDeviceFolderName(): String {
        val sanitized = Build.MODEL
            .replace(Regex("[^a-zA-Z0-9_-]+"), "-")
            .trim('-')
        val shortId = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$sanitized-$shortId"
    }
}
