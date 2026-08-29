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
import net.posdaca.oiia.core.ParadoxLocalisationResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.files.LocalisationFiles
import net.posdaca.oiia.core.files.ResourceFiles
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class TechnologyService(private val project: Project) {

    private data class TextToken(val text: String, val lineNumber: Int)
    private sealed class TextValue {
        data class Atom(val value: String) : TextValue()
        data class Block(val entries: List<TextEntry>) : TextValue()
    }
    private data class TextEntry(val key: String, val value: TextValue?, val lineNumber: Int)

    private val resolutionVersion = AtomicInteger(0)
    private val spriteResolver = ParadoxSpriteResolver(project)
    private val localisationResolver = ParadoxLocalisationResolver(project, LANG_PRIORITY)

    fun parseTechnologyTreesFromFile(psiFile: PsiFile): List<TechnologyTreeData> {
        val technologies = mutableListOf<TechnologyData>()
        if (psiFile is ParadoxScriptFile) parseFromPlsPsi(psiFile, technologies)
        if (technologies.isEmpty()) parseFromText(psiFile.virtualFile?.path, technologies)
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
                "force_use_small_tech_layout" -> forceUseSmallTechLayout = field.value?.toBoolean() ?: false
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
        return TechnologyPreviewSnapshot(mergeTrees(trees))
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
                for ((key, value) in LocalisationFiles.mergeFromRoots(roots, LANG_PRIORITY, keys = neededKeys - locMap.keys, maxDepth = 3)) {
                    locMap.putIfAbsent(key, value)
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                val iconNamesById = buildIconNames(allTechnologies)
                val iconMap = spriteResolver.resolveForCandidates(iconNamesById).toMutableMap()
                if (version != resolutionVersion.get()) return@executeOnPooledThread

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
        return trees.groupBy { it.folderName }.map { (folder, grouped) ->
            TechnologyTreeData(
                folderName = folder,
                technologies = grouped.flatMap { it.technologies }.distinctBy { it.id }
            )
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
            val names = linkedSetOf<String>()
            names.add(technology.id)
            names.add("GFX_${technology.id}")
            names.add("GFX_tech_${technology.id}")
            names.add("tech_${technology.id}")
            names.add("technologies/${technology.id}")
            names.add("interface/technologies/${technology.id}")
            map[technology.id] = names.toList()
        }
        return map
    }

    private fun parseFromText(filePath: String?, technologies: MutableList<TechnologyData>) {
        if (filePath == null) return
        val path = Path.of(filePath)
        if (!ResourceFiles.isRegularFile(path)) return

        val entries = TextParser(tokenizeText(ResourceFiles.readText(path) ?: return)).parseEntries()
        val technologyEntries = entries
            .filter { it.key == "technologies" }
            .flatMap { it.value.blockEntries() }
            .ifEmpty { entries }

        for (entry in technologyEntries) {
            parseTextTechnology(filePath, entry)?.let { technologies.add(it) }
        }
    }

    private fun parseTextTechnology(filePath: String, entry: TextEntry): TechnologyData? {
        if (entry.key in NON_TECHNOLOGY_KEYS) return null
        val blockEntries = entry.value.blockEntries().takeIf { it.isNotEmpty() } ?: return null
        val folders = linkedMapOf<String, TechnologyFolderData>()
        var startYear: Int? = null
        val categories = mutableListOf<String>()
        val leadsTo = mutableListOf<String>()
        val xor = mutableListOf<String>()
        val subTechnologies = mutableListOf<String>()
        var enableEquipments = false
        var forceUseSmallTechLayout = false

        for (field in blockEntries) {
            when (field.key) {
                "folder" -> extractTextFolders(field).forEach { folders[it.name] = it }
                "start_year" -> startYear = field.value.atomValue()?.toIntOrNull()
                "categories" -> categories.addAll(extractTextEnumValues(field))
                "path" -> leadsTo.addAll(extractTextPathLeads(field))
                "xor" -> xor.addAll(extractTextEnumValues(field))
                "sub_technologies" -> subTechnologies.addAll(extractTextEnumValues(field))
                "enable_equipments" -> enableEquipments = field.value != null
                "force_use_small_tech_layout" -> forceUseSmallTechLayout = field.value.atomValue()?.toBoolean() ?: false
            }
        }

        val primaryFolder = folders.values.firstOrNull()
        return TechnologyData(
            id = entry.key,
            folderName = primaryFolder?.name,
            folders = folders,
            x = primaryFolder?.x ?: 0.0,
            y = primaryFolder?.y ?: 0.0,
            startYear = startYear,
            categories = categories.distinct(),
            leadsTo = leadsTo.distinct(),
            xor = xor.distinct(),
            subTechnologies = subTechnologies.distinct(),
            enableEquipments = enableEquipments,
            forceUseSmallTechLayout = forceUseSmallTechLayout,
            sourceFilePath = filePath,
            sourceLine = entry.lineNumber
        )
    }

    private fun extractTextFolders(entry: TextEntry): List<TechnologyFolderData> {
        val blockEntries = entry.value.blockEntries()
        if (blockEntries.isEmpty()) {
            return entry.value.atomValue()?.let { listOf(TechnologyFolderData(it)) } ?: emptyList()
        }

        val directName = blockEntries.firstOrNull { it.key == "name" }?.value.atomValue()
        if (directName != null) {
            val position = blockEntries.firstOrNull { it.key == "position" }
            return listOf(TechnologyFolderData(directName, extractTextPositionX(position), extractTextPositionY(position)))
        }

        val result = mutableListOf<TechnologyFolderData>()
        for (folderEntry in blockEntries) {
            val nestedEntries = folderEntry.value.blockEntries()
            if (folderEntry.key == "position" || nestedEntries.isEmpty()) continue
            val nestedName = nestedEntries.firstOrNull { it.key == "name" }?.value.atomValue() ?: folderEntry.key
            val position = nestedEntries.firstOrNull { it.key == "position" }
            result.add(TechnologyFolderData(nestedName, extractTextPositionX(position), extractTextPositionY(position)))
        }
        return result
    }

    private fun extractTextPositionX(position: TextEntry?): Double {
        val entries = position?.value.blockEntries()
        return extractTextNamedNumber(entries, "x") ?: extractTextIndexedNumber(entries, 0) ?: 0.0
    }

    private fun extractTextPositionY(position: TextEntry?): Double {
        val entries = position?.value.blockEntries()
        return extractTextNamedNumber(entries, "y") ?: extractTextIndexedNumber(entries, 1) ?: 0.0
    }

    private fun extractTextNamedNumber(entries: List<TextEntry>?, key: String): Double? {
        return entries?.firstOrNull { it.key == key }?.value.atomValue()?.toDoubleOrNull()
    }

    private fun extractTextIndexedNumber(entries: List<TextEntry>?, index: Int): Double? {
        val entry = entries?.getOrNull(index) ?: return null
        return entry.value.atomValue()?.toDoubleOrNull() ?: entry.key.toDoubleOrNull()
    }

    private fun extractTextPathLeads(entry: TextEntry): List<String> {
        val direct = entry.value.atomValue()
        if (direct != null) return listOf(direct)
        return entry.value.blockEntries().mapNotNull { pathField ->
            if (pathField.key == "leads_to_tech") pathField.value.atomValue() else null
        }
    }

    private fun extractTextEnumValues(entry: TextEntry): List<String> {
        val direct = entry.value.atomValue()
        if (direct != null) return listOf(direct)
        return entry.value.blockEntries().mapNotNull { child ->
            child.value.atomValue() ?: child.key.takeIf { it != "{" && it != "=" }
        }
    }

    private fun TextValue?.atomValue(): String? = (this as? TextValue.Atom)?.value

    private fun TextValue?.blockEntries(): List<TextEntry> = (this as? TextValue.Block)?.entries.orEmpty()

    private fun tokenizeText(content: String): List<TextToken> {
        val tokens = mutableListOf<TextToken>()
        var lineNumber = 1
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '\n' -> {
                    lineNumber++
                    index++
                }

                char.isWhitespace() -> index++
                char == '#' -> {
                    while (index < content.length && content[index] != '\n') index++
                }

                char == '{' || char == '}' || char == '=' -> {
                    tokens.add(TextToken(char.toString(), lineNumber))
                    index++
                }

                char == '"' -> {
                    val startLine = lineNumber
                    index++
                    val sb = StringBuilder()
                    var escaped = false
                    while (index < content.length) {
                        val c = content[index]
                        if (c == '\n') lineNumber++
                        when {
                            escaped -> {
                                sb.append(c)
                                escaped = false
                            }

                            c == '\\' -> escaped = true
                            c == '"' -> {
                                index++
                                break
                            }

                            else -> sb.append(c)
                        }
                        index++
                    }
                    tokens.add(TextToken(sb.toString(), startLine))
                }

                else -> {
                    val start = index
                    val startLine = lineNumber
                    while (
                        index < content.length &&
                        !content[index].isWhitespace() &&
                        content[index] !in charArrayOf('{', '}', '=', '#')
                    ) {
                        index++
                    }
                    tokens.add(TextToken(content.substring(start, index), startLine))
                }
            }
        }
        return tokens
    }

    private class TextParser(private val tokens: List<TextToken>) {
        private var index = 0

        fun parseEntries(stopOnBrace: Boolean = false): List<TextEntry> {
            val entries = mutableListOf<TextEntry>()
            while (index < tokens.size) {
                val token = tokens[index]
                when (token.text) {
                    "}" -> {
                        index++
                        if (stopOnBrace) return entries
                    }

                    "{", "=" -> index++
                    else -> {
                        index++
                        val value = if (peekText() == "=") {
                            index++
                            parseValue()
                        } else {
                            null
                        }
                        entries.add(TextEntry(token.text, value, token.lineNumber))
                    }
                }
            }
            return entries
        }

        private fun parseValue(): TextValue? {
            val token = tokens.getOrNull(index) ?: return null
            index++
            return when (token.text) {
                "{" -> TextValue.Block(parseEntries(stopOnBrace = true))
                "}" -> null
                else -> TextValue.Atom(token.text)
            }
        }

        private fun peekText(): String? = tokens.getOrNull(index)?.text
    }

    companion object {
        private val LOG = Logger.getInstance(TechnologyService::class.java)
        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        private const val DEFAULT_FOLDER = "Technologies"
        private val NON_TECHNOLOGY_KEYS = setOf(
            "technologies", "folders", "folder", "path", "categories", "doctrine", "doctrine_name",
            "allow", "allow_branch", "ai_will_do", "research_cost", "start_year", "enable_equipments",
            "force_use_small_tech_layout", "xor", "sub_technologies", "on_research_complete"
        )
    }
}
