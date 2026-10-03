package net.posdaca.oiia.core.script

import com.intellij.psi.util.PsiTreeUtil
import icu.windea.pls.lang.codeInsight.color.ParadoxColorFactory
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptColor
import icu.windea.pls.script.psi.ParadoxScriptProperty
import icu.windea.pls.script.psi.ParadoxScriptString

/**
 * Reads a Paradox colour field as packed `0xRRGGBB`.
 *
 * HOI4 accepts four shapes for the same field, but only the lowercase keyword forms reach the PSI
 * as [ParadoxScriptColor] (`COLOR_TOKEN` is matched case-sensitively by Chronicle's lexer):
 *
 * - `color = rgb { 201 56 93 }` / `hsv { 0.1 0.47 0.8 }` / `hsv360 { 180 47 80 }`
 * - `color = { 106 119 89 }` — a bare block, read as RGB; the shape the country definition files
 *   under `common/countries` use
 * - `color = HSV { 0.1 0.15 0.4 }` — vanilla's uppercase spelling; the keyword lexes as a plain
 *   value and the channel block becomes a separate sibling member
 *
 * Channel conversion is delegated to Chronicle's [ParadoxColorFactory] (integer/float RGB, HSV and
 * HSV360), so this facade only recognises the shape. `color_ui` (counters) is never matched, as
 * it is unrelated to the map fill.
 */
internal object ParadoxScriptColors {

    private val COLOR_TYPES = setOf("rgb", "hsv", "hsv360")

    /**
     * Colour of the first readable `color` property in [properties]. Nested blocks are searched
     * because country colours also sit inside tag or conditional blocks; `color_ui` (counters)
     * never matches.
     */
    fun colorOf(properties: List<ParadoxScriptProperty>): Int? {
        for (property in properties) {
            if (property.propertyKey.text.equals("color", ignoreCase = true)) {
                colorOf(property)?.let { return it }
            }
            property.block?.let { block -> colorOf(block.propertyList)?.let { return it } }
        }
        return null
    }

    /** Colour value of a single `color = ...` property, or null when it is not a colour. */
    fun colorOf(property: ParadoxScriptProperty): Int? {
        return when (val value = property.propertyValue) {
            is ParadoxScriptColor -> toRgb(value.colorType, value.colorArgs)
            // A bare block carries no keyword; HOI4 reads it as RGB.
            is ParadoxScriptBlock -> toRgb("rgb", value.valueList.map { it.text })
            is ParadoxScriptString -> {
                val type = value.value.trim().trim('"')
                if (type.lowercase() !in COLOR_TYPES) return null
                val channels = PsiTreeUtil.getNextSiblingOfType(property, ParadoxScriptBlock::class.java)
                    ?: return null
                toRgb(type, channels.valueList.map { it.text })
            }
            else -> null
        }
    }

    private fun toRgb(type: String?, channels: List<String>): Int? {
        val colorType = type?.trim()?.trim('"')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val args = channels.map { it.trim().trim('"') }
        val color = ParadoxColorFactory.getColor(colorType, args) ?: return null
        return (color.red shl 16) or (color.green shl 8) or color.blue
    }
}
