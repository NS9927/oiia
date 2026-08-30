package net.posdaca.oiia.technology

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.util.ParadoxDefinitionManager
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import net.posdaca.oiia.core.ParadoxLocalisationResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.files.LocalisationFiles
import net.posdaca.oiia.core.files.ResourceFiles
import net.posdaca.oiia.core.parseParadoxBoolean
import net.posdaca.oiia.gui.GuiPreviewService
import java.util.concurrent.atomic.AtomicInteger

class TechnologyService(private val project: Project) {

    private val resolutionVersion = AtomicInteger(0)
    private val spriteResolver = ParadoxSpriteResolver(project)
    private val localisationResolver =
        ParadoxLocalisationResolver(project, ParadoxLocalisationPreference.DEFAULT_FALLBACK_LANGUAGES)

    /** Reused so GUI-file parsing for tree layouts keeps the shared sprite/localisation caches warm. */
    private val guiService by lazy { GuiPreviewService(project) }

    fun parseTechnologyTreesFromFile(psiFile: PsiFile): List<TechnologyTreeData> {
        val technologies = mutableListOf<TechnologyData>()
        if (psiFile is ParadoxScriptFile) parseFromPlsPsi(psiFile, technologies)
        val grouped = sortedMapOf<String, MutableList<TechnologyData>>(String.CASE_INSENSITIVE_ORDER)
        for (technology in technologies) {
            val folderNames = technology.folders.keys.takeIf { it.isNotEmpty() }
                ?: listOf(technology.folderName ?: DEFAULT_FOLDER)
            for (folder in folderNames) {
                grouped.getOrPut(folder) { mutableListOf() }.add(technology)
            }
        }
        return grouped.map { (folder, items) ->
                TechnologyTreeData(
                    folder,
                    items.sortedWith(
                        compareBy<TechnologyData> { it.positionIn(folder)?.y ?: it.y }
                            .thenBy { it.positionIn(folder)?.x ?: it.x }
                    )
                )
            }
    }

    private fun parseFromPlsPsi(psiFile: ParadoxScriptFile, technologies: MutableList<TechnologyData>) {
        val rootBlock = psiFile.block
        if (rootBlock != null) {
            for (prop in rootBlock.propertyList) parseRootProperty(prop, technologies)
        }
        if (technologies.isEmpty()) {
            for (child in psiFile.children) {
                if (child is ParadoxScriptProperty) parseRootProperty(child, technologies)
            }
        }
    }

    private fun parseRootProperty(prop: ParadoxScriptProperty, technologies: MutableList<TechnologyData>) {
        val block = prop.block ?: return
        if (prop.propertyKey.text == "technologies") {
            parseTechnologyContainer(block, technologies)
        } else {
            parseTechnologyProperty(prop)?.let { technologies.add(it) }
        }
    }

    private fun parseTechnologyContainer(block: ParadoxScriptBlock, technologies: MutableList<TechnologyData>) {
        for (prop in block.propertyList) parseTechnologyProperty(prop)?.let { technologies.add(it) }
    }

    private fun parseTechnologyProperty(prop: ParadoxScriptProperty): TechnologyData? {
        val block = prop.block ?: return null
        val id = prop.propertyKey.text.takeIf { it != "technologies" } ?: return null
        if (id in NON_TECHNOLOGY_KEYS) return null

        val folders = linkedMapOf<String, TechnologyFolderData>()
        var startYear: Int? = null
        val categories = mutableListOf<String>()
        val leadsTo = mutableListOf<String>()
        val xor = mutableListOf<String>()
        val subTechnologies = mutableListOf<String>()
        var enableEquipments = false
        var forceUseSmallTechLayout = false

        for (field in block.propertyList) {
            when (field.propertyKey.text) {
                "folder" -> extractFolders(field).forEach { folders[it.name] = it }
                "start_year" -> startYear = field.value?.toIntOrNull()
                "categories" -> categories.addAll(extractBlockValues(field))
                "path" -> leadsTo.addAll(extractPathLeads(field))
                "xor" -> xor.addAll(extractBlockValues(field))
                "sub_technologies" -> subTechnologies.addAll(extractBlockValues(field))
                "enable_equipments" -> enableEquipments = field.block != null || !field.value.isNullOrBlank()
                "force_use_small_tech_layout" -> forceUseSmallTechLayout = field.value.parseParadoxBoolean() ?: false
            }
        }

        val vf = prop.containingFile.virtualFile
        val doc = vf?.let { PsiManager.getInstance(project).findViewProvider(it)?.document }
        val line = if (doc != null) doc.getLineNumber(prop.textOffset) + 1 else 0
        val localisations = resolvePrimaryLocalisations(prop)
        val localizedName = ParadoxDefinitionManager.getPresentableName(prop)
        val primaryFolder = folders.values.firstOrNull()

        return TechnologyData(
            id = id,
            folderName = primaryFolder?.name,
            folders = folders,
            iconImagePath = resolvePrimaryImagePath(prop),
            localizedName = localizedName,
            localizedDescription = localisations.firstOrNull { it != localizedName },
            x = primaryFolder?.x ?: 0.0,
            y = primaryFolder?.y ?: 0.0,
            startYear = startYear,
            categories = categories.distinct(),
            leadsTo = leadsTo.distinct(),
            xor = xor.distinct(),
            subTechnologies = subTechnologies.distinct(),
            enableEquipments = enableEquipments,
            forceUseSmallTechLayout = forceUseSmallTechLayout,
            sourceFilePath = vf?.path,
            sourceLine = line
        )
    }

    private fun extractFolders(property: ParadoxScriptProperty): List<TechnologyFolderData> {
        val block = property.block ?: return property.value?.let { listOf(TechnologyFolderData(it)) } ?: emptyList()
        val directName = block.propertyList.firstOrNull { it.propertyKey.text == "name" }?.value
        if (directName != null) {
            val position = block.propertyList.firstOrNull { it.propertyKey.text == "position" }?.block
            return listOf(TechnologyFolderData(directName, extractPositionX(position), extractPositionY(position)))
        }

        val result = mutableListOf<TechnologyFolderData>()
        for (folderEntry in block.propertyList) {
            val folderBlock = folderEntry.block ?: continue
            if (folderEntry.propertyKey.text == "position") continue
            val position = folderBlock.propertyList.firstOrNull { it.propertyKey.text == "position" }?.block
            result.add(
                TechnologyFolderData(
                    folderEntry.propertyKey.text,
                    extractPositionX(position),
                    extractPositionY(position)
                )
            )
        }
        return result
    }

    private fun extractPositionX(block: ParadoxScriptBlock?): Double {
        if (block == null) return 0.0
        return extractNamedNumber(block, "x") ?: extractIndexedNumber(block, 0) ?: 0.0
    }

    private fun extractPositionY(block: ParadoxScriptBlock?): Double {
        if (block == null) return 0.0
        return extractNamedNumber(block, "y") ?: extractIndexedNumber(block, 1) ?: 0.0
    }

    private fun extractNamedNumber(block: ParadoxScriptBlock, key: String): Double? {
        val property = block.propertyList.firstOrNull { it.propertyKey.text == key } ?: return null
        return property.value?.toDoubleOrNull()
    }

    private fun extractIndexedNumber(block: ParadoxScriptBlock, index: Int): Double? {
        val property = block.propertyList.getOrNull(index)
        return property?.propertyKey?.text?.toDoubleOrNull() ?: property?.value?.toDoubleOrNull()
    }

    private fun extractBlockValues(property: ParadoxScriptProperty): List<String> {
        val block = property.block ?: return property.value?.let { listOf(it) } ?: emptyList()
        return block.propertyList.mapNotNull { p -> p.value ?: p.propertyKey.text }.filter { it != "{" }
    }

    private fun extractPathLeads(property: ParadoxScriptProperty): List<String> {
        val block = property.block ?: return property.value?.let { listOf(it) } ?: emptyList()
        val leads = block.propertyList.mapNotNull { p ->
            if (p.propertyKey.text == "leads_to_tech") p.value else null
        }
        if (leads.isNotEmpty()) return leads
        return extractBlockValues(property).filter { it != "leads_to_tech" }
    }

    private fun resolvePrimaryImagePath(prop: ParadoxScriptProperty): String? {
        return spriteResolver.resolveDefinitionImage(prop)
    }

    private fun resolvePrimaryLocalisations(prop: ParadoxScriptProperty): List<String> {
        return try {
            ParadoxDefinitionManager.getPrimaryLocalisations(prop).mapNotNull {
                ParadoxLocalisationService.resolvePresentableText(it)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadSnapshot(psiFile: PsiFile): TechnologyPreviewSnapshot {
        val trees = parseTechnologyTreesFromFile(psiFile).toMutableList()
        if (trees.isEmpty()) {
            val parent = psiFile.virtualFile?.parent
            if (parent != null) {
                val manager = PsiManager.getInstance(project)
                for (child in parent.children) {
                    val childPsi = manager.findFile(child) ?: continue
                    trees.addAll(parseTechnologyTreesFromFile(childPsi))
                }
            }
        }
        val merged = mergeTrees(trees)
        return TechnologyPreviewSnapshot(attachTreeLayouts(merged))
    }

    /**
     * Reads `countrytechtreeview.gui` / `countrydoctrinetreeview.gui` and maps each
     * `<startTech>_tree` gridbox to a [TechTreeGridLayout]. Must run inside a read action.
     */
    private fun attachTreeLayouts(trees: List<TechnologyTreeData>): List<TechnologyTreeData> {
        if (trees.none { it.startTechnology != null }) return trees
        val gui = try {
            loadTreeGuiInfo()
        } catch (e: Exception) {
            LOG.warn("Tech tree GUI layout load failed", e)
            TechTreeGuiInfo(emptyMap(), emptyMap())
        }
        if (gui.layouts.isEmpty()) return trees
        val attached = trees.map { tree ->
            // The game names gridboxes after a tree's start tech; fall back to any member id
            // when our computed root differs from the game's choice.
            val layout = tree.startTechnology?.let { gui.layouts[it] }
                ?: tree.technologies.firstNotNullOfOrNull { gui.layouts[it.id] }
            layout?.let { tree.copy(layout = it) } ?: tree
        }
        return sortTreesByLayout(attached, gui.folderOrder)
    }

    /** Keeps folders in the game's tab order and trees of one folder in gridbox page order. */
    private fun sortTreesByLayout(trees: List<TechnologyTreeData>, folderOrder: Map<String, Int>): List<TechnologyTreeData> {
        if (folderOrder.isEmpty() && trees.none { it.layout != null }) return trees
        val orderedByFolder = if (folderOrder.isEmpty()) {
            trees
        } else {
            // Stable: folders without a gui entry keep their relative order at the end.
            trees.sortedWith(compareBy { folderOrder[it.folderName] ?: Int.MAX_VALUE })
        }
        if (orderedByFolder.none { it.layout != null }) return orderedByFolder
        val result = orderedByFolder.toMutableList()
        var start = 0
        while (start < result.size) {
            var end = start + 1
            while (end < result.size && result[end].folderName == result[start].folderName) end++
            val withOrigins = result.subList(start, end).all { it.layout != null }
            if (withOrigins) {
                result.subList(start, end).sortWith(
                    compareBy({ it.layout!!.originY }, { it.layout!!.originX }, { it.startTechnology ?: "" })
                )
            }
            start = end
        }
        return result
    }

    private class TechTreeGuiInfo(
        val layouts: Map<String, TechTreeGridLayout>,
        val folderOrder: Map<String, Int>
    )

    private fun loadTreeGuiInfo(): TechTreeGuiInfo {
        val layouts = mutableMapOf<String, TechTreeGridLayout>()
        val folderOrder = mutableMapOf<String, Int>()
        for (fileName in TREE_VIEW_GUI_FILES) {
            val psiFile = findGuiPsi("interface/$fileName") ?: continue
            val roots = try {
                guiService.parseGuiFile(psiFile).roots
            } catch (_: Exception) {
                continue
            }
            val treeViews = roots.filter { it.name.equals(TREE_VIEW_WINDOW, ignoreCase = true) || it.name.equals(DOCTRINE_VIEW_WINDOW, ignoreCase = true) }
            for (view in treeViews) {
                for (folderView in view.children) {
                    if (!folderView.type.equals(FOLDER_VIEW_TYPE, ignoreCase = true)) continue
                    folderView.name?.let { name -> folderOrder.putIfAbsent(name, folderOrder.size) }
                    // Gridboxes can sit in intermediate containers (e.g. techtree_stripes), so walk descendants.
                    for (gridbox in folderView.descendants(GRIDBOX_TYPE)) {
                        val name = gridbox.name ?: continue
                        val treeName = name.removeSuffix(TREE_GRIDBOX_SUFFIX)
                        if (treeName == name) continue
                        layouts[treeName] = TechTreeGridLayout(
                            format = gridbox.format?.trim()?.trim('"')?.lowercase(),
                            slotWidth = gridbox.slotSize?.width ?: 0,
                            slotHeight = gridbox.slotSize?.height ?: 0,
                            originX = gridbox.position.x,
                            originY = gridbox.position.y
                        )
                    }
                }
            }
        }
        return TechTreeGuiInfo(layouts, folderOrder)
    }

    private fun findGuiPsi(relativePath: String): ParadoxScriptFile? {
        val roots = ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
        val manager = PsiManager.getInstance(project)
        for (root in roots) {
            val path = root.resolve(relativePath).normalize()
            if (!java.nio.file.Files.isRegularFile(path)) continue
            val vf = ResourceFiles.toVirtualFile(path) ?: continue
            val psi = manager.findFile(vf) as? ParadoxScriptFile ?: continue
            return psi
        }
        return null
    }

    fun resolve(snapshot: TechnologyPreviewSnapshot, onReady: (TechnologyPreviewSnapshot) -> Unit) {
        val version = resolutionVersion.incrementAndGet()
        val allTechnologies = snapshot.allTechnologies
        ApplicationManager.getApplication().executeOnPooledThread {
            var nextSnapshot = snapshot
            try {
                val neededKeys = neededLocalisationKeys(allTechnologies)
                val locMap = ApplicationManager.getApplication().runReadAction<MutableMap<String, String>> {
                    localisationResolver.resolveAll(neededKeys).toMutableMap()
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                val roots = ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
                for ((key, value) in LocalisationFiles.mergeFromRoots(
                    roots,
                    ParadoxLocalisationPreference.DEFAULT_FALLBACK_LANGUAGES,
                    keys = neededKeys - locMap.keys,
                    maxDepth = 3
                )) {
                    locMap.putIfAbsent(key, value)
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                val iconNamesById = buildIconNames(allTechnologies)
                val iconMap = spriteResolver.resolveForCandidates(iconNamesById).toMutableMap()
                if (version != resolutionVersion.get()) return@executeOnPooledThread

                // Nodes whose own sprite is missing fall back to the game's generic tech icon.
                val fallbackIcon = spriteResolver.resolveSprite(FALLBACK_ICON_SPRITE)
                if (fallbackIcon != null) {
                    for (technology in allTechnologies) {
                        iconMap.putIfAbsent(technology.id, fallbackIcon)
                    }
                }

                val nextResolvedData = mutableMapOf<String, TechnologyData>()
                for (technology in allTechnologies) {
                    val name = technology.localizedName ?: locMap[technology.id]
                    val description = technology.localizedDescription ?: locMap["${technology.id}_desc"]
                    val icon = technology.iconImagePath ?: iconMap[technology.id]
                    if (name != null || description != null || icon != null) {
                        nextResolvedData[technology.id] = technology.copy(
                            localizedName = name,
                            localizedDescription = description,
                            iconImagePath = icon
                        )
                    }
                }
                nextSnapshot = snapshot.withResolved(nextResolvedData)
            } catch (e: Exception) {
                LOG.warn("Technology resolution failed", e)
            }
            ApplicationManager.getApplication().invokeLater { onReady(nextSnapshot) }
        }
    }

    private fun mergeTrees(trees: List<TechnologyTreeData>): List<TechnologyTreeData> {
        return trees.groupBy { it.folderName }.flatMap { (folder, grouped) ->
            splitFolderIntoTrees(folder, grouped.flatMap { it.technologies }.distinctBy { it.id })
        }
    }

    private fun neededLocalisationKeys(technologies: List<TechnologyData>): Set<String> {
        val keys = linkedSetOf<String>()
        for (technology in technologies) {
            keys.add(technology.id)
            keys.add("${technology.id}_desc")
        }
        return keys
    }

    private fun buildIconNames(technologies: List<TechnologyData>): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (technology in technologies) {
            // Mirrors the game's icon chain: technologies.gfx defines `GFX_<techid>_medium`.
            val names = linkedSetOf(
                "GFX_${technology.id}_medium",
                technology.id,
                "GFX_${technology.id}",
                "GFX_tech_${technology.id}",
                "tech_${technology.id}",
                "technologies/${technology.id}",
                "interface/technologies/${technology.id}"
            )
            map[technology.id] = names.toList()
        }
        return map
    }

    companion object {
        private val LOG = Logger.getInstance(TechnologyService::class.java)
        private const val DEFAULT_FOLDER = "Technologies"
        private const val FALLBACK_ICON_SPRITE = "GFX_technology_medium"
        private const val TREE_VIEW_WINDOW = "countrytechtreeview"
        private const val DOCTRINE_VIEW_WINDOW = "countrydoctrineview"
        private const val FOLDER_VIEW_TYPE = "containerWindowType"
        private const val GRIDBOX_TYPE = "gridboxType"
        private const val TREE_GRIDBOX_SUFFIX = "_tree"
        private val TREE_VIEW_GUI_FILES = listOf("countrytechtreeview.gui", "countrydoctrinetreeview.gui")
        private val NON_TECHNOLOGY_KEYS = setOf(
            "technologies", "folders", "folder", "path", "categories", "doctrine", "doctrine_name",
            "allow", "allow_branch", "ai_will_do", "research_cost", "start_year", "enable_equipments",
            "force_use_small_tech_layout", "xor", "sub_technologies", "on_research_complete"
        )
    }
}

/**
 * Splits one folder's technologies into connected components joined by [TechnologyData.leadsTo],
 * mirroring how the game splits a folder page into one gridbox per tree. The component root is the
 * member no other member leads to, falling back to the first member on ties or cycles.
 */
internal fun splitFolderIntoTrees(folder: String, technologies: List<TechnologyData>): List<TechnologyTreeData> {
    if (technologies.isEmpty()) return emptyList()
    val byId = technologies.associateByTo(linkedMapOf()) { it.id }
    val parent = byId.keys.associateWith { it }.toMutableMap()

    fun find(id: String): String {
        var root = id
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var current = id
        while (parent.getValue(current) != current) {
            val next = parent.getValue(current)
            parent[current] = root
            current = next
        }
        return root
    }

    for (technology in technologies) {
        for (child in technology.leadsTo) {
            if (child in byId) {
                val rootTech = find(technology.id)
                val rootChild = find(child)
                if (rootTech != rootChild) parent[rootChild] = rootTech
            }
        }
    }

    val groups = linkedMapOf<String, MutableList<TechnologyData>>()
    for (technology in technologies) {
        groups.getOrPut(find(technology.id)) { mutableListOf() }.add(technology)
    }

    return groups.values.map { members ->
        val root = members.firstOrNull { member ->
            members.none { other -> other.id != member.id && member.id in other.leadsTo }
        } ?: members.first()
        TechnologyTreeData(folder, members, root.id)
    }
}
