package net.posdaca.oiia.core.script

import com.intellij.testFramework.ParsingTestCase
import icu.windea.pls.script.ParadoxScriptParserDefinition
import icu.windea.pls.script.psi.ParadoxScriptFile
import java.awt.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Anchors the colour shapes HOI4 accepts for a country colour. Only `rgb`/`hsv`/`hsv360` reach the
 * PSI as Chronicle's `ParadoxScriptColor` (its lexer matches the keyword case-sensitively), so the
 * bare block and vanilla's uppercase `HSV` spelling are the interesting cases.
 */
@RunWith(JUnit4::class)
class ParadoxScriptColorsTest : ParsingTestCase("", "txt", ParadoxScriptParserDefinition()) {

    override fun getTestDataPath() = "src/test/testData"

    @Test
    fun readsLowercaseRgbColor() {
        val color = colorOfTag("ENG = {\n\tcolor = rgb { 201 56 93 }\n\tcolor_ui = rgb { 255 73 121 }\n}\n", "ENG")

        assertEquals(0xC9385D, color)
    }

    @Test
    fun readsUppercaseHsvColor() {
        // Vanilla writes `HSV { 0.1 0.15 0.4 }`; the keyword is not a COLOR_TOKEN, so the
        // channel block ends up as a sibling member of the `color` property.
        val color = colorOfTag("GER = {\n\tcolor = HSV { 0.1 0.15 0.4 }\n\tcolor_ui = rgb { 138 155 116 }\n}\n", "GER")

        assertEquals(hsv(0.1f, 0.15f, 0.4f), color)
    }

    @Test
    fun readsLowercaseHsvColor() {
        val color = colorOfTag("TAG = {\n\tcolor = hsv { 0.1 0.47 0.8 }\n}\n", "TAG")

        assertEquals(hsv(0.1f, 0.47f, 0.8f), color)
    }

    @Test
    fun readsHsv360Color() {
        val color = colorOfTag("TAG = {\n\tcolor = hsv360 { 180 100 50 }\n}\n", "TAG")

        assertEquals(hsv(0.5f, 1f, 0.5f), color)
    }

    @Test
    fun readsBareBlockAsRgb() {
        // `common/countries/Germany.txt` uses this shape at the top level.
        val color = colorOf("graphical_culture = western_european_gfx\ncolor = { 106  119  89 } # { 76  97  121 }\n")

        assertEquals(0x6A7759, color)
    }

    @Test
    fun ignoresColorUiAndUnreadableValues() {
        assertNull(colorOfTag("TAG = {\n\tcolor_ui = rgb { 138 155 116 }\n}\n", "TAG"))
        assertNull(colorOfTag("TAG = {\n\tcolor = something_else { 1 2 3 }\n}\n", "TAG"))
        assertNull(colorOfTag("TAG = {\n\tcolor = { 1 2 }\n}\n", "TAG"))
    }

    /** Mirrors `MapPreviewService.parseCountryColorOverrideFile`: the tag block holds `color`. */
    private fun colorOfTag(text: String, tag: String): Int? {
        val root = (parseFile("colors.txt", text) as ParadoxScriptFile).block ?: return null
        val block = root.propertyList.first { it.propertyKey.text.equals(tag, ignoreCase = true) }.block
            ?: return null
        return ParadoxScriptColors.colorOf(block.propertyList)
    }

    /** Mirrors `MapPreviewService.parseCountryColor`: `color` sits at the top level. */
    private fun colorOf(text: String): Int? {
        val root = (parseFile("country.txt", text) as ParadoxScriptFile).block ?: return null
        return ParadoxScriptColors.colorOf(root.propertyList)
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val color = Color.getHSBColor(h, s, v)
        return (color.red shl 16) or (color.green shl 8) or color.blue
    }
}
