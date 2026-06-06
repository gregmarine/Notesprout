package com.notesprout.android.toc

import com.notesprout.android.data.HeadingStroke

data class TocEntry(
    val pageNumber: Int,
    val pageId: String,
    val heading: HeadingStroke,
)
