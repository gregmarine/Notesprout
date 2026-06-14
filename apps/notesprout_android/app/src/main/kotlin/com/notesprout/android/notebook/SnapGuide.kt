package com.notesprout.android.notebook

sealed class SnapGuide {
    data class Vertical(val x: Float) : SnapGuide()
    data class Horizontal(val y: Float) : SnapGuide()
}

data class SnapResult(
    val snappedDx: Float,
    val snappedDy: Float,
    val activeGuides: List<SnapGuide>,
)
