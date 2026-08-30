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

    @Test
    fun mergeCentroidsWeightsByMass() {
        val merged = MapPixels.mergeCentroids(
            listOf(
                MapPixels.CentroidPart(cx = 10.0, cy = 5.0, mass = 2, minX = 0, maxX = 20),
                MapPixels.CentroidPart(cx = 20.0, cy = 15.0, mass = 2, minX = 0, maxX = 40)
            ),
            width = 1000
        )

        assertEquals(15.0, merged?.first!!, 1e-9)
        assertEquals(10.0, merged.second, 1e-9)
    }

    @Test
    fun mergeCentroidsWrapsPartsAcrossTheMapSeam() {
        // A region hugging the right edge plus a part at the left edge merge across the seam.
        val merged = MapPixels.mergeCentroids(
            listOf(
                MapPixels.CentroidPart(cx = 95.0, cy = 10.0, mass = 3, minX = 90, maxX = 99),
                MapPixels.CentroidPart(cx = 2.0, cy = 10.0, mass = 1, minX = 0, maxX = 5)
            ),
            width = 100
        )

        // Without the wrap correction the naive centroid would jump to ~73 (mid-map).
        assertEquals(96.75, merged?.first!!, 1e-9)
        assertEquals(10.0, merged.second, 1e-9)
    }

    @Test
    fun mergeCentroidsDoesNotWrapMidMapRegions() {
        val merged = MapPixels.mergeCentroids(
            listOf(
                MapPixels.CentroidPart(cx = 40.0, cy = 0.0, mass = 1, minX = 35, maxX = 45),
                MapPixels.CentroidPart(cx = 60.0, cy = 0.0, mass = 1, minX = 55, maxX = 65)
            ),
            width = 100
        )

        assertEquals(50.0, merged?.first!!, 1e-9)
    }

    @Test
    fun mergeCentroidsReturnsNullWithoutMass() {
        assertNull(MapPixels.mergeCentroids(emptyList(), width = 100))
    }

    @Test
    fun labelInkTurnsWhiteOnDarkColoursAndBlackOnLight() {
        assertTrue(MapPixels.labelInkIsWhite(0x202020))
        assertFalse(MapPixels.labelInkIsWhite(0xF0F0F0))
        // The reference weights green heavily: 0x808080 (384) just crosses the threshold.
        assertFalse(MapPixels.labelInkIsWhite(0x808080))
        assertTrue(MapPixels.labelInkIsWhite(0x787878))
        assertFalse(MapPixels.labelInkIsWhite(0x80E080))
    }
}
