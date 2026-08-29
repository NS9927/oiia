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
}