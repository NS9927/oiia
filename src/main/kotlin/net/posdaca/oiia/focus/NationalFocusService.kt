package net.posdaca.oiia.focus

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import icu.windea.pls.lang.util.ParadoxDefinitionManager
import icu.windea.pls.lang.util.ParadoxImageManager
import icu.windea.pls.localisation.ParadoxLocalisationFileType
import icu.windea.pls.localisation.psi.ParadoxLocalisationFile
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import net.posdaca.oiia.core.HoI4ResourceRoots
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class NationalFocusService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(NationalFocusService::class.java)

        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        private const val LOCALISATION_LANGUAGE_WEIGHT = 10000
        private val ICON_EXTENSIONS = setOf(".dds", ".tga", ".png")
    }

    private data class SpriteDefinition(val name: String, val textureFile: String, val root: Path)
    private data class TextLine(val lineNumber: Int, val text: String)

    private val resolvedData = ConcurrentHashMap<String, FocusData>()
    private val resolutionVersion = AtomicInteger(0)
    private val cachedRoots = mutableListOf<Path>()
    private val cachedIconFiles = mutableMapOf<String, String>()
    private val cachedSpriteIconFiles = mutableMapOf<String, String>()
    private var cachesValid = false

    fun parseFocusTreeFromFile(psiFile: PsiFile): List<NationalFocusTreeData> {
        val focusTrees = mutableListOf<NationalFocusTreeData>()

        if (psiFile is ParadoxScriptFile) {
            parseFocusTreeFromPlsPsi(psiFile, focusTrees)
        }

        if (focusTrees.isEmpty()) {
            parseFocusTreeFromText(psiFile.virtualFile?.path, focusTrees)
        }

        return focusTrees
    }

    private fun parseFocusTreeFromPlsPsi(psiFile: ParadoxScriptFile, focusTrees: MutableList<NationalFocusTreeData>) {
        val rootBlock = psiFile.block
        if (rootBlock != null) {
            for (prop in rootBlock.propertyList) parseRootProperty(prop, focusTrees)
        }
        if (focusTrees.isEmpty()) {
            for (child in psiFile.children) {
                if (child is ParadoxScriptProperty) parseRootProperty(child, focusTrees)
            }
        }
        if (focusTrees.isEmpty()) {
            for (member in psiFile.block?.members ?: emptyList()) {
                if (member is ParadoxScriptProperty) parseRootProperty(member, focusTrees)
            }
        }
        LOG.info("PLS PSI parsed ${focusTrees.size} trees from ${psiFile.virtualFile?.path ?: "?"}")
    }

    private fun parseFocusTreeFromText(filePath: String?, focusTrees: MutableList<NationalFocusTreeData>) {
        if (filePath == null) return
        val path = Path.of(filePath)
        if (!Files.isRegularFile(path)) return

        try {
            val content = Files.readString(path)
            val lines = content.lines()
            var inFocusTree = false
            var braceDepth = 0
            val treeLines = mutableListOf<TextLine>()

            for ((index, line) in lines.withIndex()) {
                val lineNumber = index + 1
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val stripped = stripLineComment(trimmed).trim()
                if (stripped == "focus_tree = {" || stripped == "shared_focus = {") {
                    inFocusTree = true
                    braceDepth = stripped.count { it == '{' } - stripped.count { it == '}' }
                    treeLines.clear()
                    treeLines.add(TextLine(lineNumber, stripped))
                    continue
                }
                if (inFocusTree) {
                    treeLines.add(TextLine(lineNumber, stripped))
                    braceDepth += stripped.count { it == '{' } - stripped.count { it == '}' }
                    if (braceDepth <= 0) {
                        parseTextFocusTree(filePath, treeLines, focusTrees)
                        inFocusTree = false
                        treeLines.clear()
                    }
                }
            }
            LOG.info("Text parser found ${focusTrees.size} trees in $filePath")
        } catch (e: Exception) {
            LOG.warn("Text parsing failed for $filePath", e)
        }
    }

    private fun parseTextFocusTree(
        filePath: String,
        lines: List<TextLine>,
        focusTrees: MutableList<NationalFocusTreeData>
    ) {
        val rawLines = lines.map { it.text }
        val id = extractValue(rawLines, "id") ?: return
        val country = extractValue(rawLines, "country")
        val defaultFocus = extractValue(rawLines, "default")?.toBoolean() ?: false

        val focuses = mutableListOf<FocusData>()
        val sharedFocuses = mutableListOf<FocusData>()

        var inFocus = false
        var focusBraceDepth = 0
        var focusStartLine = 0
        val focusBlockLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.text.trim()
            if (trimmed.startsWith("focus = {") || trimmed.startsWith("shared_focus = {")) {
                inFocus = true
                focusStartLine = line.lineNumber
                focusBraceDepth = trimmed.count { it == '{' } - trimmed.count { it == '}' }
                focusBlockLines.clear()
                focusBlockLines.add(trimmed)
                continue
            }
            if (inFocus) {
                focusBlockLines.add(trimmed)
                focusBraceDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
                if (focusBraceDepth <= 0) {
                    val fd = parseTextFocus(focusBlockLines, filePath, focusStartLine)
                    if (fd != null) {
                        if (lines.firstOrNull()?.text?.trim()?.startsWith("shared_focus") == true) sharedFocuses.add(fd)
                        else focuses.add(fd)
                    }
                    inFocus = false
                    focusStartLine = 0
                    focusBlockLines.clear()
                }
            }
        }

        focusTrees.add(
            NationalFocusTreeData(
                id = id, country = country,
                focuses = focuses, sharedFocuses = sharedFocuses, defaultFocus = defaultFocus
            )
        )
    }

    private fun parseTextFocus(lines: List<String>, sourceFilePath: String, sourceLine: Int): FocusData? {
        val id = extractValue(lines, "id") ?: return null
        val iconKey = extractValue(lines, "icon") ?: extractIconValue(lines)
        val text = extractValue(lines, "text")
        val x = extractValue(lines, "x")?.toDoubleOrNull() ?: 0.0
        val y = extractValue(lines, "y")?.toDoubleOrNull() ?: 0.0
        val cost = extractValue(lines, "cost")?.toDoubleOrNull() ?: 10.0
        val relativePositionId = extractValue(lines, "relative_position_id")
        val prerequisites = extractMultiValue(lines, "prerequisite")
        val mutuallyExclusive = extractMultiValue(lines, "mutually_exclusive")
        val completeTooltip = extractValue(lines, "complete_tooltip")

        var aiWillDo: Double? = null
        val aiIdx = lines.indexOfFirst { it.trim().startsWith("ai_will_da") || it.trim().startsWith("ai_will_do") }
        if (aiIdx >= 0) {
            for (i in aiIdx until (aiIdx + 10).coerceAtMost(lines.size)) {
                val t = lines[i].trim()
                if (t.startsWith("base_factor")) {
                    aiWillDo = t.substringAfter("=").trim().toDoubleOrNull()
                    break
                }
            }
        }

        return FocusData(
            id = id, iconKey = iconKey, text = text,
            x = x, y = y, cost = cost,
            prerequisites = prerequisites, mutuallyExclusive = mutuallyExclusive,
            relativePositionId = relativePositionId, aiWillDo = aiWillDo,
            completeTooltip = completeTooltip,
            prerequisitesText = if (prerequisites.isNotEmpty()) prerequisites.joinToString(", ") else null,
            sourceFilePath = sourceFilePath,
            sourceLine = sourceLine
        )
    }

    private fun extractValue(lines: List<String>, key: String): String? {
        for (line in lines) {
            val trimmed = line.trim()
            val regex = Regex("""^\s*$key\s*=\s*"([^"]*)"\s*$""")
            val match = regex.find(trimmed)
            if (match != null) return match.groupValues[1]
            val simpleRegex = Regex("""^\s*$key\s*=\s*(\S+)""")
            val simpleMatch = simpleRegex.find(trimmed)
            if (simpleMatch != null) {
                val v = simpleMatch.groupValues[1]
                if (v != "{" && !v.startsWith("\"")) return v
            }
        }
        return null
    }

    private fun extractIconValue(lines: List<String>): String? {
        var inBlock = false
        var blockDepth = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (!inBlock && Regex("""^\s*icon\s*=\s*\{""").containsMatchIn(trimmed)) {
                inBlock = true
                blockDepth = braceDelta(trimmed)
                extractAssignmentValue(trimmed, "value")?.let { return it }
                if (blockDepth <= 0) inBlock = false
                continue
            }
            if (inBlock) {
                extractAssignmentValue(trimmed, "value")?.let { return it }
                blockDepth += braceDelta(trimmed)
                if (blockDepth <= 0) inBlock = false
            }
        }
        return null
    }

    private fun extractMultiValue(lines: List<String>, blockKey: String): List<String> {
        val result = mutableListOf<String>()
        var inBlock = false
        var blockDepth = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("$blockKey = {")) {
                inBlock = true
                blockDepth = braceDelta(trimmed)
                continue
            }
            if (inBlock) {
                extractAssignmentValue(trimmed, "focus")?.let { result.add(it) }
                blockDepth += braceDelta(trimmed)
                if (blockDepth <= 0) inBlock = false
            }
        }
        return result
    }

    private fun parseRootProperty(p: ParadoxScriptProperty, trees: MutableList<NationalFocusTreeData>) {
        val k = p.propertyKey.text
        if ((k == "focus_tree" || k == "shared_focus") && p.block != null) {
            parseFocusTreeBlock(p.block!!)?.let { trees.add(it) }
        }
    }

    private fun parseFocusTreeBlock(block: ParadoxScriptBlock): NationalFocusTreeData? {
        var treeId: String? = null
        var country: String? = null
        var defaultFocus = false
        val focuses = mutableListOf<FocusData>()
        val sharedFocuses = mutableListOf<FocusData>()
        for (prop in block.propertyList) {
            when (prop.propertyKey.text) {
                "id" -> treeId = prop.value
                "country" -> {
                    val cb = prop.block
                    country = if (cb != null) cb.propertyList.firstOrNull()?.propertyKey?.text else prop.value
                }

                "default" -> defaultFocus = prop.value?.toBoolean() ?: false
                "focus" -> {
                    parseFocusProperty(prop, false)?.let { focuses.add(it) }
                }

                "shared_focus" -> {
                    parseFocusProperty(prop, true)?.let { sharedFocuses.add(it) }
                }
            }
        }
        val id = treeId ?: return null
        return NationalFocusTreeData(
            id = id,
            country = country,
            focuses = focuses,
            sharedFocuses = sharedFocuses,
            defaultFocus = defaultFocus
        )
    }

    private fun parseFocusProperty(prop: ParadoxScriptProperty, isShared: Boolean): FocusData? {
        val block = prop.block ?: return null
        var id: String? = null
        var iconKey: String? = null
        var text: String? = null
        var x = 0.0
        var y = 0.0
        var cost = 10.0
        val prerequisites = mutableListOf<String>()
        val mutuallyExclusive = mutableListOf<String>()
        var relativePositionId: String? = null
        var aiWillDo: Double? = null
        var completeTooltip: String? = null

        for (field in block.propertyList) {
            when (field.propertyKey.text) {
                "id" -> id = field.value
                "icon" -> iconKey = resolveIconKey(field)
                "text" -> text = field.value
                "x" -> x = field.value?.toDoubleOrNull() ?: 0.0
                "y" -> y = field.value?.toDoubleOrNull() ?: 0.0
                "cost" -> cost = field.value?.toDoubleOrNull() ?: 10.0
                "relative_position_id" -> relativePositionId = field.value
                "prerequisite" -> field.block?.propertyList?.forEach {
                    if (it.propertyKey.text == "focus") it.value?.let { v ->
                        prerequisites.add(v)
                    }
                }

                "mutually_exclusive" -> field.block?.propertyList?.forEach {
                    if (it.propertyKey.text == "focus") it.value?.let { v ->
                        mutuallyExclusive.add(v)
                    }
                }

                "ai_will_do" -> field.block?.propertyList?.forEach {
                    if (it.propertyKey.text == "base_factor") aiWillDo = it.value?.toDoubleOrNull()
                }

                "complete_tooltip" -> completeTooltip = field.value
            }
        }

        val focusId = id ?: return null
        val vf = prop.containingFile.virtualFile
        val doc = PsiManager.getInstance(project).findViewProvider(vf)?.document
        val line = if (doc != null) doc.getLineNumber(prop.textOffset) + 1 else 0
        val plsIconPath = resolvePlsPrimaryImagePath(prop)
        return FocusData(
            id = focusId,
            iconKey = iconKey,
            iconImagePath = plsIconPath,
            text = text,
            x = x,
            y = y,
            cost = cost,
            prerequisites = prerequisites,
            mutuallyExclusive = mutuallyExclusive,
            relativePositionId = relativePositionId,
            aiWillDo = aiWillDo,
            completeTooltip = completeTooltip,
            prerequisitesText = if (prerequisites.isNotEmpty()) prerequisites.joinToString(", ") else null,
            sourceFilePath = vf?.path,
            sourceLine = line,
            isSharedFocus = isShared
        )
    }

    private fun resolvePlsPrimaryImagePath(focusProperty: ParadoxScriptProperty): String? {
        return try {
            ParadoxImageManager.resolveUrlByDefinition(focusProperty)
                ?: ParadoxDefinitionManager.getPrimaryImages(focusProperty)
                    .firstOrNull()
                    ?.virtualFile
                    ?.let { vf -> ParadoxImageManager.resolveUrlByFile(vf, project) ?: vf.path }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveFocusData(focus: FocusData): FocusData = resolvedData[focus.id] ?: focus

    fun scheduleResolution(allFocuses: List<FocusData>, onDone: () -> Unit) {
        val version = resolutionVersion.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val locScoreMap = mutableMapOf<String, Int>()

                val locMap = ApplicationManager.getApplication().runReadAction<MutableMap<String, String>> {
                    val m = mutableMapOf<String, String>()
                    val psiManager = PsiManager.getInstance(project)
                    for (vf in FileTypeIndex.getFiles(
                        ParadoxLocalisationFileType,
                        GlobalSearchScope.allScope(project)
                    )) {
                        val psiFile = psiManager.findFile(vf) as? ParadoxLocalisationFile ?: continue
                        val langScore = getLanguagePriority(vf.path)
                        for (prop in psiFile.properties) {
                            val n = prop.name
                            val v = prop.value ?: continue
                            val existing = locScoreMap[n] ?: -1
                            if (langScore > existing) {
                                locScoreMap[n] = langScore
                                m[n] = v
                            }
                        }
                    }
                    m
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                for (path in findLocFilePaths()) {
                    val parsed = parseLocFileText(path)
                    val langScore = getLanguagePriority(path.toString()) + localisationRootScore(path)
                    for ((k, v) in parsed) {
                        val existing = locScoreMap[k] ?: -1
                        if (langScore > existing) {
                            locScoreMap[k] = langScore
                            locMap[k] = v
                        }
                    }
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                LOG.info("Loaded ${locMap.size} loc entries")

                val iconNamesById = buildIconNames(allFocuses)
                val iconMap = searchIconsCached(iconNamesById)

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                LOG.info("Matched ${iconMap.size} icons")

                val nextResolvedData = mutableMapOf<String, FocusData>()
                for (f in allFocuses) {
                    val name = f.text?.let { locMap[it] } ?: locMap[f.id]
                    val desc = f.descriptionKey?.let { locMap[it] } ?: locMap["${f.id}_desc"]
                    val icon = f.iconImagePath ?: iconMap[f.id]
                    val completeTooltip = f.completeTooltip?.let { locMap[it] ?: it }
                    if (name != null || desc != null || icon != null || completeTooltip != f.completeTooltip) {
                        nextResolvedData[f.id] = f.copy(
                            localizedName = name,
                            localizedDescription = desc,
                            iconImagePath = icon,
                            completeTooltip = completeTooltip
                        )
                    }
                }
                resolvedData.clear()
                resolvedData.putAll(nextResolvedData)
                LOG.info("Resolved ${nextResolvedData.size}/${allFocuses.size} focuses")

                cachesValid = true
            } catch (e: Exception) {
                LOG.warn("Resolution failed", e)
            }
            ApplicationManager.getApplication().invokeLater { onDone() }
        }
    }

    private fun getLanguagePriority(path: String): Int {
        val lower = path.lowercase()
        for ((i, tag) in LANG_PRIORITY.withIndex()) {
            if (tag in lower) return (LANG_PRIORITY.size - i) * LOCALISATION_LANGUAGE_WEIGHT
        }
        return 0
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

    private fun parseLocFileText(path: Path): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val content = Files.readString(path)
            val regex = Regex("""^\s*([^\s:#]+)\s*:\d*\s*"((?:\\.|[^"])*)"""", RegexOption.MULTILINE)
            for (match in regex.findAll(content)) {
                map[match.groupValues[1]] = match.groupValues[2]
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun findLocFilePaths(): List<Path> {
        val files = mutableListOf<Path>()
        val seen = mutableSetOf<String>()

        val roots = getResourceRoots()

        for (root in roots) {
            for (locDir in listOf(root.resolve("localisation"), root.resolve("localization"))) {
                if (!locDir.isDirectory()) continue
                try {
                    Files.walk(locDir, 3).use { stream ->
                        stream.filter { it.fileName.toString().lowercase().endsWith(".yml") }.forEach {
                            if (seen.add(it.toString().lowercase())) files.add(it)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        LOG.info("Found ${files.size} loc files (NIO)")
        return files
    }

    private fun localisationRootScore(path: Path): Int {
        val key = HoI4ResourceRoots.normalizedKey(path)
        val roots = getResourceRoots()
        val rootIndex = roots.indexOfFirst { key.startsWith(HoI4ResourceRoots.normalizedKey(it)) }
        return if (rootIndex < 0) 0 else roots.size - rootIndex
    }

    private fun ensureIconCache() {
        if (cachesValid && (cachedIconFiles.isNotEmpty() || cachedSpriteIconFiles.isNotEmpty())) return
        cachedIconFiles.clear()
        cachedSpriteIconFiles.clear()

        val roots = getResourceRoots()
        val spriteDefinitions = mutableListOf<SpriteDefinition>()
        LOG.info("Scanning gfx directories for focus icons...")
        for (root in roots) {
            val gfxDir = root.resolve("gfx")
            if (gfxDir.isDirectory()) {
                try {
                    Files.walk(gfxDir, 6).use { stream ->
                        stream.filter { it.isRegularFile() }.forEach { f ->
                            val n = f.fileName.toString().lowercase()
                            if (ICON_EXTENSIONS.any { n.endsWith(it) }) {
                                cacheIconFile(root, f)
                            } else if (n.endsWith(".gfx")) {
                                parseGfxSpriteFile(root, f, spriteDefinitions)
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
                        }
                            .forEach { parseGfxSpriteFile(root, it, spriteDefinitions) }
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

        LOG.info("Cached ${cachedIconFiles.size} icon aliases and ${cachedSpriteIconFiles.size} sprite icon aliases")
    }

    private fun searchIconsCached(iconNamesById: Map<String, List<String>>): Map<String, String> {
        ensureIconCache()

        val map = mutableMapOf<String, String>()

        for ((focusId, names) in iconNamesById) {
            for (name in names) {
                var path = findCachedSpriteIconPath(name) ?: findCachedIconPath(name)
                if (path != null) {
                    map[focusId] = path
                    break
                }
                val aliases = iconAliases(name)
                path = cachedIconFiles.entries.firstOrNull { (key, _) ->
                    aliases.any { alias -> key.startsWith(alias) || alias.startsWith(key) }
                }?.value
                if (path != null) {
                    map[focusId] = path
                    break
                }
            }
        }

        return map
    }

    private fun buildIconNames(focuses: List<FocusData>): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        for (f in focuses) {
            val list = linkedSetOf<String>()
            f.iconKey?.let {
                list.add(it)
                if (!it.startsWith("GFX_", ignoreCase = true)) list.add("GFX_$it")
            }
            list.add("GFX_focus_${f.id}")
            list.add("GFX_goal_${f.id}")
            list.add("focus_${f.id}")
            list.add("goal_${f.id}")
            map[f.id] = list.toList()
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

    private fun resolveIconKey(field: ParadoxScriptProperty): String? {
        field.block?.propertyList?.forEach { if (it.propertyKey.text == "value") return it.value }
        return field.value
    }

    fun clearCache() {
        resolvedData.clear()
        cachedRoots.clear()
        cachedIconFiles.clear()
        cachedSpriteIconFiles.clear()
        cachesValid = false
    }
}
