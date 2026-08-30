package net.posdaca.oiia.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTreeTextParserTest {
    @Test
    fun parsesFocusTreeWithFocusesAndSharedReferences() {
        val content = """
            # focus tree
            focus_tree = {
                id = "germany_tree"
                country = GER
                default = yes
                shared_focus = shared_tree_a
                focus = {
                    id = army_effort
                    icon = GFX_goal_army_effort
                    text = "army_effort_title"
                    x = 1
                    y = 0
                    cost = 5.5
                    prerequisite = { focus = navy_effort }
                    prerequisite = { focus = air_effort focus = radar_effort }
                    mutually_exclusive = { focus = fortify }
                    complete_tooltip = army_effort_tt
                    ai_will_do = {
                        base_factor = 0.5
                    }
                }
                shared_focus = {
                    id = political_effort
                    icon = { value = GFX_idea_political_effort }
                    relative_position_id = army_effort
                    x = 0 y = 1
                }
            }
        """.trimIndent()

        val result = FocusTreeTextParser.parse("common/national_focus/germany.txt", content)

        val tree = result.trees.single()
        assertEquals("germany_tree", tree.id)
        assertEquals("GER", tree.country)
        assertEquals(true, tree.defaultFocus)
        assertEquals(listOf("shared_tree_a"), tree.sharedFocusReferences)

        val focus = tree.focuses.single()
        assertEquals("army_effort", focus.id)
        assertEquals("GFX_goal_army_effort", focus.iconKey)
        assertEquals("army_effort_title", focus.text)
        assertEquals(1.0, focus.x, 0.0)
        assertEquals(0.0, focus.y, 0.0)
        assertEquals(5.5, focus.cost, 0.0)
        assertEquals(listOf("navy_effort", "air_effort", "radar_effort"), focus.prerequisites)
        assertEquals("navy_effort, air_effort, radar_effort", focus.prerequisitesText)
        assertEquals(listOf("fortify"), focus.mutuallyExclusive)
        assertEquals("army_effort_tt", focus.completeTooltip)
        assertEquals(0.5, focus.aiWillDo!!, 0.0)
        assertEquals(false, focus.isSharedFocus)
        assertEquals("common/national_focus/germany.txt", focus.sourceFilePath)
        assertEquals(7, focus.sourceLine)

        val shared = tree.sharedFocuses.single()
        assertEquals("political_effort", shared.id)
        assertEquals(true, shared.isSharedFocus)
        assertEquals("GFX_idea_political_effort", shared.iconKey)
        assertEquals("army_effort", shared.relativePositionId)
        assertEquals(1.0, shared.y, 0.0)
        assertNull(shared.prerequisitesText)
    }

    @Test
    fun parsesStandaloneSharedFocus() {
        val content = """
            shared_focus = {
                id = shared_effort
                text = shared_effort_title
                x = 2
                y = 3
            }
        """.trimIndent()

        val result = FocusTreeTextParser.parse("common/national_focus/shared.txt", content)

        assertEquals(0, result.trees.size)
        val focus = result.standaloneSharedFocuses.single()
        assertEquals("shared_effort", focus.id)
        assertEquals(true, focus.isSharedFocus)
        assertEquals("shared_effort_title", focus.text)
        assertEquals(2.0, focus.x, 0.0)
        assertEquals(3.0, focus.y, 0.0)
        assertEquals(10.0, focus.cost, 0.0)
        assertEquals(1, focus.sourceLine)
    }

    @Test
    fun skipsTreeWithoutIdAndSurvivesQuotedBraces() {
        val content = """
            focus_tree = {
                # id = commented_out_tree
                focus = { id = "orphan \"quoted\" {x}" }
            }
            focus_tree = { id = real_tree country = { GER = { factor = 1 } } }
        """.trimIndent()

        val result = FocusTreeTextParser.parse("f.txt", content)

        assertEquals(listOf("real_tree"), result.trees.map { it.id })
        assertEquals("GER", result.trees.single().country)
        assertEquals(0, result.trees.single().focuses.size)
    }

    @Test
    fun handlesCrlfAndBlockCountryForm() {
        val content = "focus_tree = {\r\n    id = crlf_tree\r\n    country = {\r\n        SOV = { factor = 1 }\r\n    }\r\n}\r\n"

        val result = FocusTreeTextParser.parse("f.txt", content)

        val tree = result.trees.single()
        assertEquals("crlf_tree", tree.id)
        assertEquals("SOV", tree.country)
    }
}
