package com.brainstormity.voicecontrol.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry under fold/unfold/rotation, with the sizes of the Pixel-Fold-like test AVD (bubble window 189 px). */
class OverlayGeometryTest {
    private val size = 189
    private val folded = Area(1248, 1972, Box(0, 110, 1248, 1909))
    private val unfolded = Area(2448, 1848, Box(0, 74, 2448, 1764))
    private val foldedLandscape = Area(1972, 1248, Box(110, 74, 1972, 1185))

    @Test fun clampKeepsTheBubbleInsideTheSafeArea() {
        assertEquals(Pos(0, 110), OverlayGeometry.clamp(Pos(-50, -50), folded, size))
        assertEquals(Pos(1248 - size, 1909 - size), OverlayGeometry.clamp(Pos(5000, 5000), folded, size))
        assertEquals(Pos(300, 900), OverlayGeometry.clamp(Pos(300, 900), folded, size))
    }

    @Test fun dragRangeCoversTheWholeNewScreenAfterUnfold() {
        val r = OverlayGeometry.bubbleRange(unfolded, size)
        assertEquals(0, r.minX)
        assertEquals(2448 - size, r.maxX)   // not the folded 1248 - size
        assertEquals(74, r.minY)
        assertEquals(1764 - size, r.maxY)
        // A drag to the far right of the unfolded screen gets there.
        assertEquals(Pos(2448 - size, 500), OverlayGeometry.clamp(Pos(2400, 500), unfolded, size))
    }

    @Test fun snapGoesToTheNearestEdgeOfTheCurrentScreen() {
        // x = 1000 is on the right half of the folded screen but on the left half of the unfolded one.
        assertEquals(Pos(1248 - size, 800), OverlayGeometry.snap(Pos(1000, 800), folded, size))
        assertEquals(Pos(0, 800), OverlayGeometry.snap(Pos(1000, 800), unfolded, size))
        // Snapping also clamps y.
        assertEquals(Pos(0, 74), OverlayGeometry.snap(Pos(10, 0), unfolded, size))
    }

    @Test fun rightEdgeStaysOnTheRightEdgeAcrossUnfoldAndFold() {
        val onFolded = OverlayGeometry.positionFor(Anchor(left = false, yFraction = 0.5f), folded, size)
        assertEquals(1248 - size, onFolded.x)
        val onUnfolded = OverlayGeometry.relocate(onFolded, folded, unfolded, size)
        assertEquals(2448 - size, onUnfolded.x)
        assertEquals(74 + (1764 - size - 74) / 2, onUnfolded.y, 1)
        val back = OverlayGeometry.relocate(onUnfolded, unfolded, folded, size)
        assertEquals(onFolded.x, back.x)
        assertEquals(onFolded.y.toDouble(), back.y.toDouble(), 1.0)
    }

    @Test fun bubbleFromTheBigScreenIsNeverOffScreenAfterFolding() {
        // Bottom-right corner of the unfolded screen -> bottom-right corner of the folded one.
        val corner = Pos(2448 - size, 1764 - size)
        val p = OverlayGeometry.relocate(corner, unfolded, folded, size)
        assertEquals(Pos(1248 - size, 1909 - size), p)
        assertTrue(p.x + size <= folded.width && p.y + size <= folded.height)
    }

    @Test fun anchorRoundTripsAndSurvivesRotation() {
        val a = OverlayGeometry.anchorOf(Pos(0, 600), folded, size)
        assertTrue(a.left)
        val inLandscape = OverlayGeometry.positionFor(a, foldedLandscape, size)
        assertEquals(110, inLandscape.x)   // left edge = right of the cutout inset
        val r = OverlayGeometry.bubbleRange(foldedLandscape, size)
        assertTrue(inLandscape.y in r.minY..r.maxY)
        assertEquals(a.yFraction, OverlayGeometry.anchorOf(inLandscape, foldedLandscape, size).yFraction, 0.01f)
    }

    @Test fun badSavedFractionFallsBackSafely() {
        val nan = OverlayGeometry.positionFor(Anchor(false, Float.NaN), folded, size)
        val r = OverlayGeometry.bubbleRange(folded, size)
        assertTrue(nan.y in r.minY..r.maxY)
        assertEquals(r.maxY, OverlayGeometry.positionFor(Anchor(false, 7f), folded, size).y)
    }

    @Test fun tinyScreenDoesNotInvertTheRange() {
        val tiny = Area(150, 150, Box(0, 0, 150, 150))
        val r = OverlayGeometry.bubbleRange(tiny, size)
        assertEquals(0, r.maxX)
        assertEquals(0, r.maxY)
        assertEquals(Pos(0, 0), OverlayGeometry.clamp(Pos(100, 100), tiny, size))
    }

    @Test fun stripFollowsTheScreenWidth() {
        val f = OverlayGeometry.strip(folded, 0.6f, 63, 95)
        assertEquals(749, f.width)
        assertEquals((1248 - 749) / 2, f.x)
        assertEquals(110, f.y)
        assertEquals(f.x + f.width - 95, f.stopX)
        val u = OverlayGeometry.strip(unfolded, 0.6f, 63, 95)
        assertEquals(1469, u.width)
        assertEquals((2448 - 1469) / 2, u.x)
        assertEquals(74, u.y)
        assertEquals(u.x + u.width - 95, u.stopX)
    }

    @Test fun pillSitsBesideTheBubbleOnTheInnerSideAndOnScreen() {
        val right = OverlayGeometry.pill(Pos(2448 - size, 800), size, 500, 60, 5, unfolded)
        assertEquals(2448 - size - 500 - 5, right.x)
        val left = OverlayGeometry.pill(Pos(0, 800), size, 500, 60, 5, unfolded)
        assertEquals(size + 5, left.x)
        assertFalse(left.x < 0)
        // Near the top, the pill is clamped below the status bar.
        assertEquals(74, OverlayGeometry.pill(Pos(0, 74), size, 500, 400, 5, unfolded).y)
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
}
