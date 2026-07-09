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
import net.posdaca.oiia.core.HoI4LocalisationFiles
import net.posdaca.oiia.core.HoI4ResourceRoots
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import net.posdaca.oiia.core.ParadoxLocalisationResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.PrefixIconLookup
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class TechnologyService(private val project: Project) {

    private data class SpriteDefinition(val name: String, val textureFile: String, val root: Path)
    private data class TextToken(val text: String, val lineNumber: Int)
    private sealed class TextValue {
        data class Atom(val value: String) : TextValue()
        data class Block(val entries: List<TextEntry>) : TextValue()
    }
    private data class TextEntry(val key: String, val value: TextValue?, val lineNumber: Int)

    private val resolvedData = ConcurrentHashMap<String, TechnologyData>()
    private val resolutionVersion = AtomicInteger(0)
    private val cachedRoots = mutableListOf<Path>()
    private val cachedIconFiles = mutableMapOf<String, String>()
    private val cachedSpriteIconFiles = mutableMapOf<String, String>()
    private val spriteResolver = ParadoxSpriteResolver(project)
    private val localisationResolver = ParadoxLocalisationResolver(project, LANG_PRIORITY)
    private var cachesValid = false

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
        val localizedName = ParadoxDefinitionManager.getLocalizedName(prop)
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
                ParadoxLocalisationService.resolveLocalizedText(it)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolveTechnologyData(technology: TechnologyData): TechnologyData = resolvedData[technology.id] ?: technology

    fun scheduleResolution(allTechnologies: List<TechnologyData>, onDone: () -> Unit) {
        val version = resolutionVersion.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val locMap = ApplicationManager.getApplication().runReadAction<MutableMap<String, String>> {
                    resolveNeededLocalisations(allTechnologies).toMutableMap()
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                for ((key, value) in resolveNeededLocalisationsFromFiles(allTechnologies, locMap.keys)) {
                    locMap.putIfAbsent(key, value)
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                val iconNamesById = buildIconNames(allTechnologies)
                val iconMap = spriteResolver.resolveForCandidates(iconNamesById).toMutableMap()
                val missingIconNamesById = iconNamesById.filterKeys { it !in iconMap }
                if (missingIconNamesById.isNotEmpty()) {
                    iconMap.putAll(searchIconsCached(missingIconNamesById))
                }

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
                resolvedData.clear()
                resolvedData.putAll(nextResolvedData)
                cachesValid = true
            } catch (e: Exception) {
                LOG.warn("Technology resolution failed", e)
            }
            ApplicationManager.getApplication().invokeLater { onDone() }
        }
    }

    private fun getLanguagePriority(path: String): Int {
        return ParadoxLocalisationPreference.languagePriority(path, LANG_PRIORITY, LOCALISATION_LANGUAGE_WEIGHT)
    }

    private fun neededLocalisationKeys(technologies: List<TechnologyData>): Set<String> {
        val keys = linkedSetOf<String>()
        for (technology in technologies) {
            keys.add(technology.id)
            keys.add("${technology.id}_desc")
        }
        return keys
    }

    private fun resolveNeededLocalisations(technologies: List<TechnologyData>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (key in neededLocalisationKeys(technologies)) {
            localisationResolver.resolve(key)?.let { result[key] = it }
        }
        return result
    }

    private fun resolveNeededLocalisationsFromFiles(
        technologies: List<TechnologyData>,
        alreadyResolved: Set<String>
    ): Map<String, String> {
        val neededKeys = neededLocalisationKeys(technologies) - alreadyResolved
        if (neededKeys.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        val scoreByKey = mutableMapOf<String, Int>()
        for (path in findLocFilePaths()) {
            val langScore = getLanguagePriority(path.toString()) + localisationRootScore(path)
            val parsed = parseLocFileText(path)
            for (key in neededKeys) {
                val value = parsed[key] ?: continue
                val existing = scoreByKey[key] ?: -1
                if (langScore > existing) {
                    scoreByKey[key] = langScore
                    result[key] = value
                }
            }
        }
        return result
    }

    private fun getPlsRoots(): List<Path> {
        if (cachesValid && cachedRoots.isNotEmpty()) return cachedRoots

        val roots = HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
        if (roots.isNotEmpty()) {
            cachedRoots.clear()
            cachedRoots.addAll(roots)
        }
        return roots
    }

    private fun findLocFilePaths(): List<Path> {
        return HoI4LocalisationFiles.findFiles(getResourceRoots(), maxDepth = 3)
    }

    private fun localisationRootScore(path: Path): Int {
        return HoI4LocalisationFiles.rootScoreForRoots(path, getResourceRoots())
    }

    private fun parseLocFileText(path: Path): Map<String, String> {
        return HoI4LocalisationFiles.parseFile(path)
    }

    private fun ensureIconCache() {
        if (cachesValid && (cachedIconFiles.isNotEmpty() || cachedSpriteIconFiles.isNotEmpty())) return
        cachedIconFiles.clear()
        cachedSpriteIconFiles.clear()

        val spriteDefinitions = mutableListOf<SpriteDefinition>()
        for (root in getResourceRoots()) {
            val gfxDir = root.resolve("gfx")
            if (gfxDir.isDirectory()) {
                try {
                    Files.walk(gfxDir, 6).use { stream ->
                        stream.filter { it.isRegularFile() }.forEach { file ->
                            val name = file.fileName.toString().lowercase()
                            if (ICON_EXTENSIONS.any { name.endsWith(it) }) {
                                cacheIconFile(root, file)
                            } else if (name.endsWith(".gfx")) {
                                parseGfxSpriteFile(root, file, spriteDefinitions)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val interfaceDir = root.resolve("interface")
            if (interfaceDir.isDirectory()) {
                try {
                    Files.walk(interfaceDir, 3).use { stream ->
                        stream.filter {
                            it.isRegularFile() && it.fileName.toString().endsWith(".gfx", ignoreCase = true)
                        }.forEach { parseGfxSpriteFile(root, it, spriteDefinitions) }
                    }
                } catch (_: Exception) {
                }
            }
        }

        for (sprite in spriteDefinitions) {
            val directPath = resolveGamePath(sprite.root, sprite.textureFile)
            val iconPath = if (directPath.isRegularFile()) {
                directPath.toAbsolutePath().normalize().toString()
            } else {
                findCachedIconPath(sprite.textureFile)
            }
            if (iconPath != null) putIconAliases(cachedSpriteIconFiles, sprite.name, iconPath)
        }
    }

    private fun searchIconsCached(iconNamesById: Map<String, List<String>>): Map<String, String> {
        ensureIconCache()
        val map = mutableMapOf<String, String>()
        val prefixLookup = PrefixIconLookup(cachedIconFiles)
        for ((technologyId, names) in iconNamesById) {
            for (name in names) {
                var path = findCachedSpriteIconPath(name) ?: findCachedIconPath(name)
                if (path != null) {
                    map[technologyId] = path
                    break
                }
                val aliases = iconAliases(name)
                path = prefixLookup.find(aliases)
                if (path != null) {
                    map[technologyId] = path
                    break
                }
            }
        }
        return map
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

    private fun getResourceRoots(): List<Path> {
        return getPlsRoots()
    }

    private fun cacheIconFile(root: Path, file: Path) {
        val absolutePath = file.toAbsolutePath().normalize().toString()
        val fileName = file.fileName.toString().substringBeforeLast(".")
        putIconAliases(cachedIconFiles, fileName, absolutePath)
        try {
            val relativePath = root.relativize(file).toString().substringBeforeLast(".")
            putIconAliases(cachedIconFiles, relativePath, absolutePath)
        } catch (_: Exception) {
        }
    }

    private fun parseGfxSpriteFile(root: Path, file: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        try {
            var inSpriteType = false
            var spriteDepth = 0
            val blockLines = mutableListOf<String>()
            for (rawLine in Files.readString(file).lines()) {
                val line = stripLineComment(rawLine).trim()
                if (line.isEmpty()) continue
                if (!inSpriteType && Regex("""(?i)\bspriteType\s*=""").containsMatchIn(line)) {
                    inSpriteType = true
                    spriteDepth = braceDelta(line)
                    blockLines.clear()
                    blockLines.add(line)
                    if (spriteDepth <= 0 && line.contains("{")) {
                        collectSpriteDefinition(root, blockLines, spriteDefinitions)
                        inSpriteType = false
                    }
                    continue
                }

                if (inSpriteType) {
                    blockLines.add(line)
                    spriteDepth += braceDelta(line)
                    if (spriteDepth <= 0) {
                        collectSpriteDefinition(root, blockLines, spriteDefinitions)
                        inSpriteType = false
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun collectSpriteDefinition(
        root: Path,
        lines: List<String>,
        spriteDefinitions: MutableList<SpriteDefinition>
    ) {
        val name = lines.firstNotNullOfOrNull { extractAssignmentValue(it, "name") } ?: return
        val textureFile = lines.firstNotNullOfOrNull { extractAssignmentValue(it, "texturefile") } ?: return
        spriteDefinitions.add(SpriteDefinition(name, textureFile, root))
    }

    private fun findCachedSpriteIconPath(name: String): String? = findCachedPath(cachedSpriteIconFiles, name)

    private fun findCachedIconPath(name: String): String? = findCachedPath(cachedIconFiles, name)

    private fun findCachedPath(cache: Map<String, String>, name: String): String? {
        for (alias in iconAliases(name)) {
            cache[alias]?.let { return it }
        }
        return null
    }

    private fun putIconAliases(cache: MutableMap<String, String>, name: String, path: String) {
        for (alias in iconAliases(name)) {
            cache.putIfAbsent(alias, path)
        }
    }

    private fun iconAliases(name: String): Set<String> {
        val normalized = removeIconExtension(name.trim().trim('"').replace('\\', '/').lowercase())
        if (normalized.isBlank()) return emptySet()

        val aliases = linkedSetOf(normalized)
        if (normalized.startsWith("gfx/")) aliases.add(normalized.removePrefix("gfx/"))
        aliases.add(normalized.substringAfterLast('/'))

        val strippedGfxAliases = aliases.toList().mapNotNull { alias ->
            alias.takeIf { it.startsWith("gfx_") }?.removePrefix("gfx_")
        }
        aliases.addAll(strippedGfxAliases)
        return aliases.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun removeIconExtension(name: String): String {
        for (extension in ICON_EXTENSIONS) {
            if (name.endsWith(extension)) return name.removeSuffix(extension)
        }
        return name
    }

    private fun resolveGamePath(root: Path, rawPath: String): Path {
        val normalizedPath = rawPath.trim().trim('"').replace('\\', '/')
        val path = Path.of(normalizedPath)
        return if (path.isAbsolute) path.normalize() else root.resolve(normalizedPath).normalize()
    }

    private fun extractAssignmentValue(line: String, key: String): String? {
        val regex = Regex("""(?i)(?:^|\s)${Regex.escape(key)}\s*=\s*(?:"([^"]*)"|([^\s{}#]+))""")
        val match = regex.find(line) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun parseFromText(filePath: String?, technologies: MutableList<TechnologyData>) {
        if (filePath == null) return
        val path = Path.of(filePath)
        if (!Files.isRegularFile(path)) return

        val entries = TextParser(tokenizeText(Files.readString(path))).parseEntries()
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

    private fun stripLineComment(line: String): String {
        var inQuote = false
        var escaped = false
        for ((index, char) in line.withIndex()) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inQuote = !inQuote
                char == '#' && !inQuote -> return line.substring(0, index)
            }
        }
        return line
    }

    private fun braceDelta(line: String): Int = line.count { it == '{' } - line.count { it == '}' }

    companion object {
        private val LOG = Logger.getInstance(TechnologyService::class.java)
        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        private const val LOCALISATION_LANGUAGE_WEIGHT = 10000
        private val ICON_EXTENSIONS = setOf(".dds", ".tga", ".png")
        private const val DEFAULT_FOLDER = "Technologies"
        private val NON_TECHNOLOGY_KEYS = setOf(
            "technologies", "folders", "folder", "path", "categories", "doctrine", "doctrine_name",
            "allow", "allow_branch", "ai_will_do", "research_cost", "start_year", "enable_equipments",
            "force_use_small_tech_layout", "xor", "sub_technologies", "on_research_complete"
        )
    }
}
