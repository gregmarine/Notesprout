package com.notesprout.android.data.backup

data class DestResult(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val indexCopied: Boolean,
    val errors: List<String>,
)

data class BackupResult(val perDestination: Map<BackupKind, DestResult>)
