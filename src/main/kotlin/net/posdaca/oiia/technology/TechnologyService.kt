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

    private data class TextLine(val lineNumber: Int, val text: String)
    private data class SpriteDefinition(val name: String, val textureFile: String, val root: Path)

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
        return technologies.groupBy { it.folderName ?: "Technologies" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (folder, items) ->
                TechnologyTreeData(
                    folder,
                    items.sortedWith(compareBy<TechnologyData> { it.y }.thenBy { it.x })
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

        var folderName: String? = null
        var x = 0.0
        var y = 0.0
        var startYear: Int? = null
        val categories = mutableListOf<String>()
        val leadsTo = mutableListOf<String>()

        for (field in block.propertyList) {
            when (field.propertyKey.text) {
                "folder" -> {
                    folderName = field.block?.propertyList?.firstOrNull()?.propertyKey?.text ?: field.value
                    val position = field.block?.propertyList?.firstOrNull { it.propertyKey.text == "position" }
                    position?.block?.let { posBlock ->
                        x = extractIndexedNumber(posBlock, 0) ?: x
                        y = extractIndexedNumber(posBlock, 1) ?: y
                    }
                }

                "start_year" -> startYear = field.value?.toIntOrNull()
                "categories" -> categories.addAll(extractBlockValues(field))
                "path" -> leadsTo.addAll(extractBlockValues(field))
            }
        }

        val vf = prop.containingFile.virtualFile
        val doc = vf?.let { PsiManager.getInstance(project).findViewProvider(it)?.document }
        val line = if (doc != null) doc.getLineNumber(prop.textOffset) + 1 else 0
        val localisations = resolvePrimaryLocalisations(prop)
        val localizedName = ParadoxDefinitionManager.getLocalizedName(prop)

        return TechnologyData(
            id = id,
            folderName = folderName,
            iconImagePath = resolvePrimaryImagePath(prop),
            localizedName = localizedName,
            localizedDescription = localisations.firstOrNull { it != localizedName },
            x = x,
            y = y,
            startYear = startYear,
            categories = categories.distinct(),
            leadsTo = leadsTo.distinct(),
            sourceFilePath = vf?.path,
            sourceLine = line
        )
    }

    private fun extractIndexedNumber(block: ParadoxScriptBlock, index: Int): Double? {
        val property = block.propertyList.getOrNull(index)
        return property?.propertyKey?.text?.toDoubleOrNull() ?: property?.value?.toDoubleOrNull()
    }

    private fun extractBlockValues(property: ParadoxScriptProperty): List<String> {
        val block = property.block ?: return property.value?.let { listOf(it) } ?: emptyList()
        return block.propertyList.mapNotNull { p -> p.value ?: p.propertyKey.text }.filter { it != "{" }
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

        val lines = Files.readString(path).lines()
        var inContainer = false
        var containerDepth = 0
        val searchLines = mutableListOf<TextLine>()
        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1
            val stripped = stripLineComment(line).trim()
            if (stripped.isEmpty()) continue
            if (!inContainer && Regex("""^technologies\s*=\s*\{""").containsMatchIn(stripped)) {
                inContainer = true
                containerDepth = braceDelta(stripped)
                continue
            }
            if (inContainer) {
                containerDepth += braceDelta(stripped)
                if (containerDepth <= 0) {
                    inContainer = false
                } else {
                    searchLines.add(TextLine(lineNumber, stripped))
                }
            } else {
                searchLines.add(TextLine(lineNumber, stripped))
            }
        }
        parseTextTechnologyBlocks(filePath, searchLines, technologies)
    }

    private fun parseTextTechnologyBlocks(
        filePath: String,
        lines: List<TextLine>,
        technologies: MutableList<TechnologyData>
    ) {
        var inTechnology = false
        var depth = 0
        var startLine = 0
        val blockLines = mutableListOf<String>()

        for (line in lines) {
            val text = line.text
            val key = Regex("""^([A-Za-z0-9_.:-]+)\s*=\s*\{""").find(text)?.groupValues?.get(1)
            if (!inTechnology && key != null && key !in NON_TECHNOLOGY_KEYS) {
                inTechnology = true
                startLine = line.lineNumber
                depth = braceDelta(text)
                blockLines.clear()
                blockLines.add(text)
                if (depth <= 0) {
                    parseTextTechnology(filePath, startLine, blockLines)?.let { technologies.add(it) }
                    inTechnology = false
                }
                continue
            }

            if (inTechnology) {
                blockLines.add(text)
                depth += braceDelta(text)
                if (depth <= 0) {
                    parseTextTechnology(filePath, startLine, blockLines)?.let { technologies.add(it) }
                    inTechnology = false
                }
            }
        }
    }

    private fun parseTextTechnology(filePath: String, startLine: Int, lines: List<String>): TechnologyData? {
        val first = lines.firstOrNull() ?: return null
        val id = Regex("""^([A-Za-z0-9_.:-]+)\s*=""").find(first)?.groupValues?.get(1) ?: return null
        var folderName: String? = null
        var x = 0.0
        var y = 0.0
        var startYear: Int? = null
        val categories = mutableListOf<String>()
        val leadsTo = mutableListOf<String>()

        var inFolder = false
        var folderDepth = 0
        var inCategories = false
        var categoriesDepth = 0
        var inPath = false
        var pathDepth = 0

        for (line in lines) {
            if (!inFolder && Regex("""^folder\s*=\s*\{""").containsMatchIn(line)) {
                inFolder = true
                folderDepth = braceDelta(line)
                continue
            }
            if (inFolder) {
                val folderMatch = Regex("""^([A-Za-z0-9_.:-]+)\s*=\s*\{""").find(line)
                if (folderName == null && folderMatch != null && folderMatch.groupValues[1] != "position") {
                    folderName = folderMatch.groupValues[1]
                }
                val positionValues = Regex("""position\s*=\s*\{\s*([-0-9.]+)\s+([-0-9.]+)""").find(line)
                if (positionValues != null) {
                    x = positionValues.groupValues[1].toDoubleOrNull() ?: x
                    y = positionValues.groupValues[2].toDoubleOrNull() ?: y
                }
                folderDepth += braceDelta(line)
                if (folderDepth <= 0) inFolder = false
                continue
            }

            extractStartYearValue(line)?.toIntOrNull()?.let { startYear = it }

            collectSimpleBlock(line, "categories", categories, inCategories, categoriesDepth).also {
                inCategories = it.first
                categoriesDepth = it.second
            }
            collectSimpleBlock(line, "path", leadsTo, inPath, pathDepth).also {
                inPath = it.first
                pathDepth = it.second
            }
        }

        return TechnologyData(
            id = id,
            folderName = folderName,
            x = x,
            y = y,
            startYear = startYear,
            categories = categories.distinct(),
            leadsTo = leadsTo.distinct(),
            sourceFilePath = filePath,
            sourceLine = startLine
        )
    }

    private fun collectSimpleBlock(
        line: String,
        key: String,
        target: MutableList<String>,
        inBlock: Boolean,
        depth: Int
    ): Pair<Boolean, Int> {
        var nextInBlock = inBlock
        var nextDepth = depth
        if (!nextInBlock && Regex("""^$key\s*=\s*\{""").containsMatchIn(line)) {
            nextInBlock = true
            nextDepth = braceDelta(line)
            target.addAll(extractBareWords(line.substringAfter("{")))
        } else if (nextInBlock) {
            target.addAll(extractBareWords(line))
            nextDepth += braceDelta(line)
        }
        if (nextInBlock && nextDepth <= 0) nextInBlock = false
        return nextInBlock to nextDepth
    }

    private fun extractBareWords(line: String): List<String> {
        return line.replace("{", " ").replace("}", " ").split(Regex("\\s+"))
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() && it != "=" }
    }

    private fun extractStartYearValue(line: String): String? {
        val regex = Regex("""(?i)(?:^|\s)start_year\s*=\s*(?:"([^"]*)"|([^\s{}#]+))""")
        val match = regex.find(line) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
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
        private val NON_TECHNOLOGY_KEYS = setOf(
            "technologies", "folders", "folder", "path", "categories", "doctrine", "doctrine_name",
            "allow", "ai_will_do", "research_cost", "start_year", "enable_equipments", "on_research_complete"
        )
    }
}
