package net.posdaca.oiia.map

/**
 * Pure pixel-index math for the map preview: province-boundary detection and render-zone
 * uniformity over key arrays. No project/PSI/UI access, so the geometry can be unit-tested
 * against small synthetic arrays instead of real province bitmaps.
 */
internal object MapPixels {
    /** Sentinel key for pixels whose province/state/country/region is unknown. */
    const val UNKNOWN_KEY = -1

    /**
     * Whether the pixel at [index] sits on a region outline (map edge, differing neighbour, or
     * unknown neighbour). Horizontal neighbours wrap around, matching the game's cylindrical map.
     */
    fun isBoundaryPixel(keys: IntArray, width: Int, index: Int): Boolean {
        val key = keys[index]
        if (key < 0) return false
        val height = keys.size / width
        val x = index % width
        val y = index / width
        val rowOffset = y * width
        val leftIndex = rowOffset + if (x == 0) width - 1 else x - 1
        val rightIndex = rowOffset + if (x + 1 == width) 0 else x + 1
        return y == 0 || y + 1 == height ||
                keys[leftIndex] != key ||
                keys[rightIndex] != key ||
                keys[index - width] != key ||
                keys[index + width] != key
    }

    /** Whether the horizontal grid edge at (x, between rows [edgeY]-1 and [edgeY]) is a border. */
    fun isHorizontalPixelBorder(keys: IntArray, width: Int, height: Int, x: Int, edgeY: Int): Boolean {
        val upperKey = if (edgeY == 0) UNKNOWN_KEY else keys[(edgeY - 1) * width + x]
        val lowerKey = if (edgeY == height) UNKNOWN_KEY else keys[edgeY * width + x]
        return upperKey != lowerKey && (upperKey != UNKNOWN_KEY || lowerKey != UNKNOWN_KEY)
    }

    /** Whether the vertical grid edge at ([edgeX], between columns edgeX-1 and edgeX, row y) is a border. */
    fun isVerticalPixelBorder(keys: IntArray, width: Int, edgeX: Int, y: Int): Boolean {
        val rowOffset = y * width
        val leftKey = keys[rowOffset + if (edgeX == 0) width - 1 else edgeX - 1]
        val rightKey = keys[rowOffset + edgeX]
        return leftKey != rightKey && (leftKey != UNKNOWN_KEY || rightKey != UNKNOWN_KEY)
    }

    /** The zone's uniform colour, or null when any pixel inside differs. */
    fun sameRgbInZone(rgbKeys: IntArray, width: Int, zone: MapRenderZone): Int? {
        val first = rgbKeys[zone.y * width + zone.x]
        val maxY = zone.y + zone.height
        val maxX = zone.x + zone.width
        for (y in zone.y until maxY) {
            val rowOffset = y * width
            for (x in zone.x until maxX) {
                if (rgbKeys[rowOffset + x] != first) return null
            }
        }
        return first
    }
}
