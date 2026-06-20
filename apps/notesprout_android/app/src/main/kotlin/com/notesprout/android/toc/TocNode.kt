package com.notesprout.android.toc

import com.notesprout.android.data.HeadingStroke

data class TocNode(
    val pageNumber: Int,
    val pageIndex: Int,
    val pageId: String,
    val level: Int,            // 1, 2, or 3
    val title: String,         // prefix-stripped; "" if unrecognized
    val heading: HeadingStroke,
    val children: MutableList<TocNode> = mutableListOf(),
)
