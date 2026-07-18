package net.posdaca.oiia.core

import java.awt.Graphics2D
import java.awt.Rectangle

internal object PreviewTextLayout {
    fun drawCenteredTitle(
        g2d: Graphics2D,
        text: String,
        rect: Rectangle,
        maxLines: Int = 2
    ) {
        val lines = splitTextToLines(g2d, text, rect.width, maxLines)
        val fm = g2d.fontMetrics
        val lineHeight = fm.height - fm.leading
        var y = rect.y + (rect.height - lineHeight * lines.size) / 2 + fm.ascent
        for (line in lines) {
            val x = rect.x + (rect.width - fm.stringWidth(line)) / 2
            g2d.drawString(line, x, y)
            y += lineHeight
        }
    }

    fun splitTextToLines(
        g2d: Graphics2D,
        text: String,
        maxW: Int,
        maxLines: Int = 2
    ): List<String> {
        val chars = text.trim().codePoints().toArray().map { String(Character.toChars(it)) }
        if (chars.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var line = ""
        var index = 0
        while (index < chars.size) {
            val next = line + chars[index]
            if (line.isNotEmpty() && g2d.fontMetrics.stringWidth(next) > maxW) {
                lines.add(line.trim())
                line = ""
                if (lines.size == maxLines - 1) break
            } else {
                line = next
                index++
            }
        }
        val rest = (line + chars.drop(index).joinToString("")).trim()
        if (rest.isNotEmpty() && lines.size < maxLines) {
            lines.add(if (g2d.fontMetrics.stringWidth(rest) > maxW) truncate(g2d, rest, maxW) else rest)
        }
        return lines.ifEmpty { listOf(truncate(g2d, text, maxW)) }
    }

    fun truncate(g2d: Graphics2D, text: String, maxW: Int): String {
        if (g2d.fontMetrics.stringWidth(text) <= maxW) return text
        var result = text
        while (result.isNotEmpty() && g2d.fontMetrics.stringWidth("$result...") > maxW) {
            result = result.dropLast(1)
        }
        return "$result..."
    }
}
