package org.havenapp.neruppu.data.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaplacianSharpnessTest {

    /** A 64x64 frame with a vertical edge in the middle, softened by a box blur of [blurRadius] pixels. */
    private fun edgeFrame(blurRadius: Int): IntArray {
        val width = 64
        val height = 64
        val row = IntArray(width) { x -> if (x < width / 2) 0 else 255 }
        val softRow = IntArray(width) { x ->
            val from = (x - blurRadius).coerceAtLeast(0)
            val to = (x + blurRadius).coerceAtMost(width - 1)
            (from..to).sumOf { row[it] } / (to - from + 1)
        }
        return IntArray(width * height) { i -> softRow[i % width] }
    }

    @Test
    fun sharpEdgeScoresHigherThanBlurredEdge() {
        val sharp = LaplacianSharpness.variance(edgeFrame(0), 64, 64)
        val blurred = LaplacianSharpness.variance(edgeFrame(4), 64, 64)
        assertTrue("sharp=$sharp blurred=$blurred", sharp > blurred * 2)
    }

    @Test
    fun flatFrameHasZeroSharpness() {
        val flat = IntArray(32 * 32) { 128 }
        assertEquals(0.0, LaplacianSharpness.variance(flat, 32, 32), 1e-9)
    }

    @Test
    fun frameSmallerThan3x3HasZeroSharpness() {
        assertEquals(0.0, LaplacianSharpness.variance(IntArray(4) { 7 }, 2, 2), 0.0)
    }

    @Test
    fun lumaOfPrimaryColours() {
        val argb = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        assertEquals(listOf(255, 76, 149, 29), LaplacianSharpness.lumaFromArgb(argb).toList())
    }
}
