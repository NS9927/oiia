package net.posdaca.oiia.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point

class FocusPrerequisitesTest {
    @Test
    fun formatKeepsOrInsideABlockAndAndAcrossBlocks() {
        assertEquals("A", FocusPrerequisites.format(listOf(listOf("A"))))
        assertEquals("A OR B", FocusPrerequisites.format(listOf(listOf("A", "B"))))
        assertEquals("A AND B", FocusPrerequisites.format(listOf(listOf("A"), listOf("B"))))
        assertEquals(
            "(A OR B) AND C",
            FocusPrerequisites.format(listOf(listOf("A", "B"), listOf("C")))
        )
        assertNull(FocusPrerequisites.format(emptyList()))
    }

    @Test
    fun normalizeDropsEmptyAndQuotedDuplicates() {
        val groups = FocusPrerequisites.normalizeGroups(
            listOf(
                listOf(" A ", "\"A\"", ""),
                listOf("B"),
                emptyList()
            )
        )
        assertEquals(listOf(listOf("A"), listOf("B")), groups)
    }

    @Test
    fun orBlockIsVisibleWhenAnyMemberIsInTheTree() {
        val groups = listOf(listOf("foreign", "local"))
        assertTrue(FocusPrerequisites.isVisible(groups, setOf("local")))
        assertFalse(FocusPrerequisites.isVisible(groups, setOf("other")))
    }

    @Test
    fun andBlocksRequireEveryBlockToHaveAnInTreeMember() {
        val groups = listOf(listOf("local"), listOf("foreign"))
        assertFalse(FocusPrerequisites.isVisible(groups, setOf("local")))
        assertTrue(FocusPrerequisites.isVisible(groups, setOf("local", "foreign")))
    }

    @Test
    fun hiddenParentsAreDroppedSoTheirChildrenDoNotFloat() {
        val focuses = listOf(
            focus("root"),
            focus("hidden", listOf(listOf("missing"))),
            focus("child", listOf(listOf("hidden"))),
            focus("join", listOf(listOf("root", "hidden")))
        )

        assertEquals(listOf("root", "join"), FocusPrerequisites.visibleFocuses(focuses).map { it.id })
    }

    @Test
    fun planLinksJoinAndParentsWithASolidBar() {
        val positions = mapOf(
            "left" to Point(0, 0),
            "right" to Point(100, 0),
            "child" to Point(40, 80)
        )

        val andPlans = FocusPrerequisites.planLinks(
            groups = listOf(listOf("left"), listOf("right")),
            targetId = "child",
            positions = positions,
            nodeWidth = 40,
            nodeHeight = 20
        )
        assertEquals(1, andPlans.size)
        val andPlan = andPlans.single()
        assertTrue(andPlan.joint)
        assertFalse(andPlan.dashed)
        assertEquals(listOf("left", "right"), andPlan.fromIds)
        assertTrue(andPlan.segments.any { it.y1 == andPlan.jointY && it.y2 == andPlan.jointY })
        assertEquals(andPlan.arrowX, andPlan.jointX)
        assertEquals(80, andPlan.arrowY)

        val orPlans = FocusPrerequisites.planLinks(
            groups = listOf(listOf("left", "right")),
            targetId = "child",
            positions = positions,
            nodeWidth = 40,
            nodeHeight = 20,
            minJointGap = 8
        )
        assertEquals(1, orPlans.size)
        val orPlan = orPlans.single()
        assertTrue(orPlan.joint)
        assertTrue(orPlan.dashed)
        assertEquals(listOf("left", "right"), orPlan.fromIds)
        assertTrue(orPlan.segments.any { it.y1 == orPlan.jointY && it.y2 == orPlan.jointY })
        assertEquals(orPlan.arrowX, orPlan.jointX)
        assertEquals(80, orPlan.arrowY)
    }

    @Test
    fun orGroupWithOneInTreeParentFallsBackToASimpleLink() {
        val positions = mapOf(
            "local" to Point(0, 0),
            "child" to Point(0, 80)
        )
        val plans = FocusPrerequisites.planLinks(
            groups = listOf(listOf("local", "foreign")),
            targetId = "child",
            positions = positions,
            nodeWidth = 40,
            nodeHeight = 20
        )
        assertEquals(1, plans.size)
        assertEquals(listOf("local"), plans.single().fromIds)
        assertFalse(plans.single().joint)
        assertFalse(plans.single().dashed)
    }

    @Test
    fun mixedAndOrDashesOnlyTheOrJoint() {
        val positions = mapOf(
            "left" to Point(0, 0),
            "right" to Point(100, 0),
            "extra" to Point(50, 0),
            "child" to Point(40, 80)
        )
        val plans = FocusPrerequisites.planLinks(
            groups = listOf(listOf("left", "right"), listOf("extra")),
            targetId = "child",
            positions = positions,
            nodeWidth = 40,
            nodeHeight = 20
        )
        assertEquals(2, plans.size)
        val orPlan = plans.single { it.joint }
        val andPlan = plans.single { !it.joint }
        assertTrue(orPlan.dashed)
        assertFalse(andPlan.dashed)
        assertEquals(listOf("left", "right"), orPlan.fromIds)
        assertEquals(listOf("extra"), andPlan.fromIds)
    }

    private fun focus(id: String, groups: List<List<String>> = emptyList()): FocusData {
        return FocusData(id = id, prerequisiteGroups = groups)
    }
}
