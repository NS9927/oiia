package net.posdaca.oiia.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-array anchors for the map preview's pure pixel math ([MapPixels]) — province
 * boundaries, render-zone uniformity, and the unknown-pixel sentinel.
 */
class MapPixelsTest {

    @Test
    fun isBoundaryPixelFlagsMapEdgesAndDifferingNeighbours() {
        // 4-wide, 3-tall grid of province 1 with a single province 2 pixel in the middle row.
        val keys = intArrayOf(
            1, 1, 1, 1,
            1, 2, 1, 1,
            1, 1, 1, 1
        )

        // The province 2 pixel: its left neighbour differs.
        assertTrue(MapPixels.isBoundaryPixel(keys, width = 4, index = 5))
        // Top-left corner: top map edge.
        assertTrue(MapPixels.isBoundaryPixel(keys, width = 4, index = 0))
        // Bottom pixel: bottom map edge.
        assertTrue(MapPixels.isBoundaryPixel(keys, width = 4, index = 9))
        // Interior pixel of province 1: all neighbours identical, no map edge.
        assertFalse(MapPixels.isBoundaryPixel(keys, width = 4, index = 7))
    }

    @Test
    fun isBoundaryPixelIgnoresUnknownPixels() {
        val keys = intArrayOf(1, MapPixels.UNKNOWN_KEY, 1)

        assertFalse(MapPixels.isBoundaryPixel(keys, width = 3, index = 1))
    }

    @Test
    fun horizontalBorderFollowsKeyChangeBetweenRows() {
        // Two rows: top all province 1, bottom all province 2.
        val keys = intArrayOf(
            1, 1,
            2, 2
        )

        assertTrue(MapPixels.isHorizontalPixelBorder(keys, width = 2, height = 2, x = 0, edgeY = 1))
        assertTrue(MapPixels.isHorizontalPixelBorder(keys, width = 2, height = 2, x = 1, edgeY = 1))
        // Uniform rows: no border along the shared edge.
        val uniform = intArrayOf(
            1, 1,
            1, 1
        )
        assertFalse(MapPixels.isHorizontalPixelBorder(uniform, width = 2, height = 2, x = 0, edgeY = 1))
    }

    @Test
    fun horizontalBorderTreatsMapEdgesAsBorders() {
        // Single row: both the top and the bottom map edge compare against the unknown sentinel.
        val keys = intArrayOf(1, 1)

        assertTrue(MapPixels.isHorizontalPixelBorder(keys, width = 2, height = 1, x = 0, edgeY = 0))
        assertTrue(MapPixels.isHorizontalPixelBorder(keys, width = 2, height = 1, x = 0, edgeY = 1))
    }

    @Test
    fun verticalBorderFollowsKeyChangeWithinRow() {
        val keys = intArrayOf(1, 2)

        assertTrue(MapPixels.isVerticalPixelBorder(keys, width = 2, edgeX = 1, y = 0))
        // Uniform row: no border across the edge.
        val uniform = intArrayOf(1, 1)
        assertFalse(MapPixels.isVerticalPixelBorder(uniform, width = 2, edgeX = 1, y = 0))
    }

    @Test
    fun verticalBorderWrapsAtLeftEdge() {
        // Row [9, 5]: the edge at x=0 compares column 0 against the wrapped last column.
        val keys = intArrayOf(9, 5)

        assertTrue(MapPixels.isVerticalPixelBorder(keys, width = 2, edgeX = 0, y = 0))
    }

    @Test
    fun sameRgbInZoneReturnsUniformColourOrNull() {
        val keys = intArrayOf(
            7, 7, 9,
            7, 7, 9
        )

        assertEquals(7, MapPixels.sameRgbInZone(keys, width = 3, zone = MapRenderZone(0, 0, 2, 2)))
        assertNull(MapPixels.sameRgbInZone(keys, width = 3, zone = MapRenderZone(0, 0, 3, 2)))
    }
}
