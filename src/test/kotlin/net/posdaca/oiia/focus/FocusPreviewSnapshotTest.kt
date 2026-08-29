package net.posdaca.oiia.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusPreviewSnapshotTest {
    @Test
    fun withResolvedKeepsWorkingCoordinates() {
        val focus = FocusData(id = "army", x = 3.0, y = 4.0, sourceFilePath = "common/national_focus/army.txt")
        val snapshot = FocusPreviewSnapshot(listOf(NationalFocusTreeData(id = "tree", focuses = listOf(focus))))

        val resolved = snapshot.withResolved(
            mapOf(
                "army" to focus.copy(
                    x = 99.0,
                    y = 88.0,
                    localizedName = "陆军",
                    localizedDescription = "desc",
                    iconImagePath = "gfx/army.dds",
                    completeTooltip = "done"
                )
            )
        )

        val next = resolved.allFocuses.single()
        assertEquals(3.0, next.x, 0.0)
        assertEquals(4.0, next.y, 0.0)
        assertEquals("陆军", next.localizedName)
        assertEquals("desc", next.localizedDescription)
        assertEquals("gfx/army.dds", next.iconImagePath)
        assertEquals("done", next.completeTooltip)
        assertEquals("common/national_focus/army.txt", next.sourceFilePath)
    }

    @Test
    fun withResolvedIgnoresUnknownIds() {
        val focus = FocusData(id = "navy")
        val snapshot = FocusPreviewSnapshot(listOf(NationalFocusTreeData(id = "tree", focuses = listOf(focus))))
        val next = snapshot.withResolved(mapOf("army" to focus.copy(localizedName = "nope")))
        assertEquals("navy", next.allFocuses.single().id)
        assertEquals(null, next.allFocuses.single().localizedName)
    }
}