package com.notesprout.android.data

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * Guards the copy contract behind colour ink: **a stroke that is copied, moved, or pasted keeps the
 * ink it was written in.**
 *
 * Before colour existed, every copy path rebuilt strokes with the two-argument
 * `LiveStroke(id, points)` constructor, which silently reset `color` to black, `strokeWidth` to 3.0,
 * and `srcPoints` to null. That was invisible while all ink was black and became data loss the moment
 * it wasn't — 37 call sites across 8 files. They now route through [deepCopy] / [translated], and
 * these tests pin those two helpers so a later "simplification" can't quietly reintroduce the flatten.
 *
 * **Why no coordinate assertions:** `android.graphics.PointF` is stubbed in this project's JVM unit
 * tests — the constructor runs but `x`/`y` always read back `0`. That is why the other tests here
 * avoid `android.graphics` entirely. Point *values* are therefore unverifiable at this level (the
 * binary geometry contract is covered by `StrokeCodecTest`, which is pure `FloatArray`), so this file
 * asserts the non-geometry fields that actually used to be dropped, plus point-instance identity —
 * which is meaningful without reading coordinates.
 */
class StrokeColorPreservationTest {

    private fun coloured() = LiveStroke(
        id = "stroke-1",
        points = listOf(PointF(10f, 20f), PointF(30f, 40f)),
        color = "#D0021B",
        strokeWidth = 7.5f,
        srcPoints = listOf(
            StrokePoint(x = 10f, y = 20f, pressure = 0.5f, tilt = 0.25f),
            StrokePoint(x = 30f, y = 40f, pressure = 0.9f, tilt = 0.10f),
        ),
    )

    @Test
    fun deepCopy_preservesColorWidthAndPressure() {
        val copy = coloured().deepCopy()
        assertEquals("#D0021B", copy.color)
        assertEquals(7.5f, copy.strokeWidth, 0f)
        assertNotNull("pressure/tilt samples must survive a copy", copy.srcPoints)
        assertEquals(2, copy.srcPoints!!.size)
        assertEquals(0.5f, copy.srcPoints!![0].pressure!!, 0f)
    }

    /**
     * `PointF` is mutable and shared by reference, so a copy that reuses the same instances would let
     * a later move silently drag the original with it. Instance identity is checkable even though the
     * stubbed coordinates are not.
     */
    @Test
    fun deepCopy_clonesPointInstancesRatherThanAliasing() {
        val original = coloured()
        val copy = original.deepCopy()
        assertEquals(original.points.size, copy.points.size)
        for (i in original.points.indices) {
            assertNotSame(
                "copied points must be fresh instances, not shared references",
                original.points[i],
                copy.points[i],
            )
        }
    }

    @Test
    fun translated_keepsEveryNonGeometryField() {
        val moved = coloured().translated(dx = 5f, dy = -3f)
        assertEquals("#D0021B", moved.color)
        assertEquals(7.5f, moved.strokeWidth, 0f)
        assertEquals("stroke-1", moved.id)
        assertNotNull(moved.srcPoints)
        assertEquals(2, moved.srcPoints!!.size)
        assertNotSame(coloured().points[0], moved.points[0])
    }

    @Test
    fun translated_withNewId_isTheCopyPastePath() {
        val pasted = coloured().translated(dx = 1f, dy = 1f, newId = "stroke-2")
        assertEquals("stroke-2", pasted.id)
        assertEquals("#D0021B", pasted.color)
    }

    /**
     * The persistence half: a translated stroke still serializes its colour and its original
     * pressure/tilt. `toStrokeData` only emits pressure when `srcPoints` is index-aligned with
     * `points`, so this also pins that `translated` keeps the two in step.
     */
    @Test
    fun translatedStroke_serializesOriginalPressureAndColor() {
        val sd = coloured().translated(dx = 100f, dy = 0f).toStrokeData()
        assertEquals("#D0021B", sd.color)
        assertEquals(7.5f, sd.strokeWidth, 0f)
        assertEquals(2, sd.points.size)
        assertEquals(0.5f, sd.points[0].pressure!!, 0f)
        assertEquals(0.25f, sd.points[0].tilt!!, 0f)
    }

    /** A stroke row round-trips its colour through the columnar form. */
    @Test
    fun strokeRow_roundTripsColor() {
        val row = coloured().toStrokeRow(parentId = "layer-1", order = 0, createdAt = 1L, updatedAt = 1L)
        assertEquals("#D0021B", row.color)
        assertEquals(7.5f, row.strokeWidth!!, 0f)
        val back = LiveStroke.fromRow(row)!!
        assertEquals("#D0021B", back.color)
        assertEquals(7.5f, back.strokeWidth, 0f)
    }
}
