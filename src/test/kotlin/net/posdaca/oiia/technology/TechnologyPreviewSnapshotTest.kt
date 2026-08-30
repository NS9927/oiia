package net.posdaca.oiia.technology

import org.junit.Assert.assertEquals
import org.junit.Test

class TechnologyPreviewSnapshotTest {
    @Test
    fun withResolvedKeepsFolderCoordinates() {
        val tech = TechnologyData(
            id = "infantry_weapons",
            folderName = "infantry_folder",
            x = 1.0,
            y = 2.0
        )
        val snapshot = TechnologyPreviewSnapshot(
            listOf(TechnologyTreeData("infantry_folder", listOf(tech)))
        )

        val resolved = snapshot.withResolved(
            mapOf(
                "infantry_weapons" to tech.copy(
                    x = 10.0,
                    y = 20.0,
                    localizedName = "步兵武器",
                    localizedDescription = "desc",
                    iconImagePath = "gfx/tech.dds"
                )
            )
        )

        val next = resolved.allTechnologies.single()
        assertEquals(1.0, next.x, 0.0)
        assertEquals(2.0, next.y, 0.0)
        assertEquals("步兵武器", next.localizedName)
        assertEquals("desc", next.localizedDescription)
        assertEquals("gfx/tech.dds", next.iconImagePath)
        assertEquals("infantry_folder", next.folderName)
    }

    @Test
    fun splitFolderIntoTreesSplitsDisconnectedChains() {
        val trees = splitFolderIntoTrees(
            "infantry_folder",
            listOf(
                tech("a", "b"),
                tech("b"),
                tech("x", "y"),
                tech("y")
            )
        )

        assertEquals(2, trees.size)
        assertEquals("a", trees[0].startTechnology)
        assertEquals(listOf("a", "b"), trees[0].technologies.map { it.id })
        assertEquals("x", trees[1].startTechnology)
        assertEquals(listOf("x", "y"), trees[1].technologies.map { it.id })
    }

    @Test
    fun splitFolderIntoTreesKeepsMergedBranchesTogether() {
        val trees = splitFolderIntoTrees(
            "armor_folder",
            listOf(
                tech("root", "left", "right"),
                tech("left", "join"),
                tech("right", "join"),
                tech("join")
            )
        )

        assertEquals(1, trees.size)
        assertEquals("root", trees[0].startTechnology)
        assertEquals(4, trees[0].technologies.size)
    }

    @Test
    fun splitFolderIntoTreesPrefersRootWithoutIncomingLeads() {
        // Defined out of order: the lead source appears after its target.
        val trees = splitFolderIntoTrees(
            "naval_folder",
            listOf(tech("child"), tech("root", "child"))
        )

        assertEquals(1, trees.size)
        assertEquals("root", trees[0].startTechnology)
    }

    @Test
    fun splitFolderIntoTreesFallsBackToFirstMemberOnCycles() {
        val trees = splitFolderIntoTrees(
            "loop_folder",
            listOf(tech("a", "b"), tech("b", "a"))
        )

        assertEquals(1, trees.size)
        assertEquals("a", trees[0].startTechnology)
    }

    @Test
    fun splitFolderIntoTreesIgnoresLeadsToUnknownTechnologies() {
        val trees = splitFolderIntoTrees(
            "cross_folder",
            listOf(tech("a", "ghost_id", "b"), tech("b"))
        )

        assertEquals(1, trees.size)
        assertEquals("a", trees[0].startTechnology)
        assertEquals(listOf("a", "b"), trees[0].technologies.map { it.id })
    }

    private fun tech(id: String, vararg leadsTo: String): TechnologyData =
        TechnologyData(id = id, folderName = "folder", leadsTo = leadsTo.toList())
}