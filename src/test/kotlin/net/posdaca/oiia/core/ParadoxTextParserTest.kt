package net.posdaca.oiia.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ParadoxTextParserTest {
    @Test
    fun parsesAssignmentsAndBlocks() {
        val entries = ParadoxTextParser.parse(
            """
            technologies = {
                tech_a = {
                    start_year = 1936
                    categories = { infantry armor }
                    path = { leads_to_tech = tech_b }
                }
            }
            """.trimIndent()
        )

        val techA = entries.firstEntry("technologies")
            ?.blockEntries()
            ?.firstEntry("tech_a")
        assertEquals(1936, techA?.blockEntries()?.firstAtom("start_year")?.toIntOrNull())
        assertEquals(
            listOf("infantry", "armor"),
            techA?.blockEntries()?.firstEntry("categories")?.enumValues()
        )
        assertEquals(
            listOf("tech_b"),
            techA?.blockEntries()?.firstEntry("path")?.blockEntries()?.entries("leads_to_tech")?.mapNotNull { it.atomValue() }
        )
    }

    @Test
    fun keepsRepeatedKeysAndBareValues() {
        val entries = ParadoxTextParser.parse(
            """
            category = land
            category = air
            bare_value
            """.trimIndent()
        )

        assertEquals(listOf("land", "air"), entries.entries("category").mapNotNull { it.atomValue() })
        val bare = entries.firstOrNull { it.key == "bare_value" }
        assertEquals("bare_value", bare?.scalarOrKey())
    }

    @Test
    fun stripsCommentsAndTracksLineNumbers() {
        val entries = ParadoxTextParser.parse(
            """
            # leading comment
            key = "value" # trailing comment
            nested = { # inside block
                inner = 1
            }
            """.trimIndent()
        )

        assertEquals("value", entries.firstAtom("key"))
        val nested = entries.firstEntry("nested")
        assertEquals(1, nested?.blockEntries()?.firstAtom("inner")?.toIntOrNull())
        assertEquals(2, entries.firstEntry("key")?.lineNumber)
    }

    @Test
    fun handlesQuotedStringsWithEscapesAndBraces() {
        val entries = ParadoxTextParser.parse(
            """
            text = "hello \"world\" {not a block}"
            empty = {}
            """.trimIndent()
        )

        assertEquals("""hello "world" {not a block}""", entries.firstAtom("text"))
        assertEquals(0, entries.firstEntry("empty")?.blockEntries()?.size)
    }

    @Test
    fun tolerantOfUnbalancedInput() {
        val entries = ParadoxTextParser.parse("key = { open")
        val inner = entries.firstEntry("key")?.blockEntries().orEmpty()
        assertEquals(listOf("open"), inner.map { it.scalarOrKey() })
    }

    @Test
    fun handlesBomAndCrlf() {
        val entries = ParadoxTextParser.parse("\uFEFFkey = 1\r\nother = 2\r\n")
        assertEquals(1, entries.firstAtom("key")?.toIntOrNull())
        assertEquals(2, entries.firstAtom("other")?.toIntOrNull())
    }
}
