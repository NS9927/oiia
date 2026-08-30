package net.posdaca.oiia.core.files

import icu.windea.pls.lang.settings.ParadoxModDependencySettingsState
import icu.windea.pls.lang.settings.ParadoxModSettingsState
import icu.windea.pls.model.ParadoxGameType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Anchors the HOI4 resource-root planning: priority-mod matching (including sub-directory
 * projects), enabled-dependency traversal, and `$USER_HOME$` expansion. Pure-logic only; no
 * IDE platform is required because [HoI4ResourceRoots.orderedModRootPlan] works on plain
 * settings states and real temporary directories.
 */
class HoI4ResourceRootsTest {

    @Test
    fun orderedModRootPlanPutsPriorityModFirstThenEnabledDependencies() {
        val base = Files.createTempDirectory("roots")
        val modA = Files.createDirectories(base.resolve("mods/a"))
        val modB = Files.createDirectories(base.resolve("mods/b"))
        val settings = listOf(mod(modA, deps = listOf(modB to true)))

        val plan = HoI4ResourceRoots.orderedModRootPlan(settings, modA)

        assertEquals(listOf(modA, modB), plan.roots)
        assertEquals(listOf(modA), plan.settings.map { it.modDirectory?.let(Path::of) })
    }

    @Test
    fun orderedModRootPlanSkipsDisabledDependencies() {
        val base = Files.createTempDirectory("roots")
        val modA = Files.createDirectories(base.resolve("mods/a"))
        val modB = Files.createDirectories(base.resolve("mods/b"))
        val settings = listOf(mod(modA, deps = listOf(modB to false)))

        val plan = HoI4ResourceRoots.orderedModRootPlan(settings, modA)

        assertEquals(listOf(modA), plan.roots)
    }

    @Test
    fun orderedModRootPlanMatchesModWhoseRootContainsThePriorityDirectory() {
        val base = Files.createTempDirectory("roots")
        val modA = Files.createDirectories(base.resolve("mods/a"))
        val projectDir = Files.createDirectories(modA.resolve("hearts_of_iron_iv_mod"))
        val settings = listOf(mod(modA), mod(Files.createDirectories(base.resolve("mods/b"))))

        val plan = HoI4ResourceRoots.orderedModRootPlan(settings, projectDir)

        assertEquals(listOf(modA), plan.roots)
    }

    @Test
    fun orderedModRootPlanReturnsEmptyWithoutMatch() {
        val base = Files.createTempDirectory("roots")
        val unrelated = Files.createDirectories(base.resolve("somewhere/else"))

        val plan = HoI4ResourceRoots.orderedModRootPlan(listOf(mod(unrelated)), base.resolve("nope"))

        assertEquals(emptyList<Path>(), plan.roots)
    }

    @Test
    fun expandPathHandlesUserHomePlaceholderAndTilde() {
        val home = System.getProperty("user.home")

        assertEquals("$home/x", HoI4ResourceRoots.expandPath("\$USER_HOME\$/x"))
        assertEquals(home, HoI4ResourceRoots.expandPath("~"))
        assertEquals("$home/y", HoI4ResourceRoots.expandPath("~/y"))
        assertEquals("C:/plain", HoI4ResourceRoots.expandPath(" C:/plain "))
    }

    @Test
    fun expandPathKeepsPlainPathsUnchanged() {
        assertEquals("", HoI4ResourceRoots.expandPath("   "))
    }

    private fun mod(
        directory: Path,
        deps: List<Pair<Path, Boolean>> = emptyList()
    ): ParadoxModSettingsState {
        val state = ParadoxModSettingsState()
        state.gameType = ParadoxGameType.Hoi4
        state.modDirectory = directory.toString()
        for ((path, enabled) in deps) {
            val dependency = ParadoxModDependencySettingsState()
            dependency.modDirectory = path.toString()
            dependency.enabled = enabled
            state.modDependencies.add(dependency)
        }
        return state
    }
}
