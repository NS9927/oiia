package net.posdaca.oiia.focus

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import net.posdaca.oiia.core.ParadoxLocalisationResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.files.LocalisationFiles
import net.posdaca.oiia.core.files.ResourceFiles
import net.posdaca.oiia.core.script.ScriptBlocks
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class NationalFocusService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(NationalFocusService::class.java)

        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        fun localisationCacheKey(): String {
            return ParadoxLocalisationPreference.cacheKey(LANG_PRIORITY)
        }
    }

    private data class TextLine(val lineNumber: Int, val text: String)

    private val resolutionVersion = AtomicInteger(0)
    private val spriteResolver = ParadoxSpriteResolver(project)
    private val localisationResolver = ParadoxLocalisationResolver(project, LANG_PRIORITY)
    private var lastLocalisationCacheKey: String? = null

    fun parseFocusTreeFromFile(psiFile: PsiFile): List<NationalFocusTreeData> {
        val focusTrees = mutableListOf<NationalFocusTreeData>()
        val standaloneSharedFocuses = mutableListOf<FocusData>()

        if (psiFile is ParadoxScriptFile) {
            parseFocusTreeFromPlsPsi(psiFile, focusTrees, standaloneSharedFocuses)
        }

        if (focusTrees.isEmpty() && standaloneSharedFocuses.isEmpty()) {
            parseFocusTreeFromText(psiFile.virtualFile?.path, focusTrees, standaloneSharedFocuses)
        }

        if (focusTrees.isEmpty() && standaloneSharedFocuses.isNotEmpty()) {
            return listOf(
                NationalFocusTreeData(
                    id = "shared_focuses",
                    sharedFocuses = standaloneSharedFocuses
                )
            )
        }

        return expandSharedFocusReferences(focusTrees)
    }

    fun expandSharedFocusReferences(focusTrees: List<NationalFocusTreeData>): List<NationalFocusTreeData> {
        if (focusTrees.isEmpty()) return focusTrees
        val needsExpansion = focusTrees.any { it.sharedFocusReferences.isNotEmpty() }
        if (!needsExpansion) return focusTrees

        val definitionsByFile = loadSharedFocusDefinitionsByFile()
        return focusTrees.map { tree ->
            if (tree.sharedFocusReferences.isEmpty()) return@map tree
            val expanded = SharedFocusChainResolver.expand(
                referencedIds = tree.sharedFocusReferences,
                definitionsByFile = definitionsByFile,
                inlineSharedFocuses = tree.sharedFocuses
            )
            tree.copy(sharedFocuses = expanded)
        }
    }

    private fun parseFocusTreeFromPlsPsi(
        psiFile: ParadoxScriptFile,
        focusTrees: MutableList<NationalFocusTreeData>,
        standaloneSharedFocuses: MutableList<FocusData>
    ) {
        val rootBlock = psiFile.block
        if (rootBlock != null) {
            for (prop in rootBlock.propertyList) parseRootProperty(prop, focusTrees, standaloneSharedFocuses)
        }
        if (focusTrees.isEmpty() && standaloneSharedFocuses.isEmpty()) {
            for (child in psiFile.children) {
                if (child is ParadoxScriptProperty) parseRootProperty(child, focusTrees, standaloneSharedFocuses)
            }
        }
        if (focusTrees.isEmpty() && standaloneSharedFocuses.isEmpty()) {
            for (member in psiFile.block?.members ?: emptyList()) {
                if (member is ParadoxScriptProperty) parseRootProperty(member, focusTrees, standaloneSharedFocuses)
            }
        }
        LOG.info(
            "PLS PSI parsed ${focusTrees.size} trees and ${standaloneSharedFocuses.size} shared focuses from ${psiFile.virtualFile?.path ?: "?"}"
        )
    }

    private fun parseFocusTreeFromText(
        filePath: String?,
        focusTrees: MutableList<NationalFocusTreeData>,
        standaloneSharedFocuses: MutableList<FocusData>
    ) {
        if (filePath == null) return
        val path = Path.of(filePath)
        if (!ResourceFiles.isRegularFile(path)) return

        try {
            val content = ResourceFiles.readText(path) ?: return
            val lines = content.lines()
            var inBlock = false
            var blockKind: String? = null
            var braceDepth = 0
            val blockLines = mutableListOf<TextLine>()

            for ((index, line) in lines.withIndex()) {
                val lineNumber = index + 1
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val stripped = stripLineComment(trimmed).trim()
                if (!inBlock && Regex("""^focus_tree\s*=\s*\{""").containsMatchIn(stripped)) {
                    inBlock = true
                    blockKind = "focus_tree"
                    braceDepth = braceDelta(stripped)
                    blockLines.clear()
                    blockLines.add(TextLine(lineNumber, stripped))
                    if (braceDepth <= 0) {
                        parseTextFocusTree(filePath, blockLines, focusTrees)
                        inBlock = false
                        blockKind = null
                        blockLines.clear()
                    }
                    continue
                }
                if (!inBlock && Regex("""^shared_focus\s*=\s*\{""").containsMatchIn(stripped)) {
                    inBlock = true
                    blockKind = "shared_focus"
                    braceDepth = braceDelta(stripped)
                    blockLines.clear()
                    blockLines.add(TextLine(lineNumber, stripped))
                    if (braceDepth <= 0) {
                        parseTextSharedFocus(filePath, blockLines)?.let { standaloneSharedFocuses.add(it) }
                        inBlock = false
                        blockKind = null
                        blockLines.clear()
                    }
                    continue
                }
                if (inBlock) {
                    blockLines.add(TextLine(lineNumber, stripped))
                    braceDepth += braceDelta(stripped)
                    if (braceDepth <= 0) {
                        when (blockKind) {
                            "focus_tree" -> parseTextFocusTree(filePath, blockLines, focusTrees)
                            "shared_focus" -> parseTextSharedFocus(filePath, blockLines)?.let {
                                standaloneSharedFocuses.add(it)
                            }
                        }
                        inBlock = false
                        blockKind = null
                        blockLines.clear()
                    }
                }
            }
            LOG.info(
                "Text parser found ${focusTrees.size} trees and ${standaloneSharedFocuses.size} shared focuses in $filePath"
            )
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
        val sharedFocusReferences = mutableListOf<String>()

        var inFocus = false
        var focusIsShared = false
        var focusBraceDepth = 0
        var focusStartLine = 0
        val focusBlockLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.text.trim()
            if (!inFocus) {
                val sharedRef = extractAssignmentValue(trimmed, "shared_focus")
                if (sharedRef != null && !trimmed.contains("{")) {
                    sharedFocusReferences.add(sharedRef)
                    continue
                }
                if (trimmed.startsWith("focus = {") || trimmed.startsWith("shared_focus = {")) {
                    inFocus = true
                    focusIsShared = trimmed.startsWith("shared_focus")
                    focusStartLine = line.lineNumber
                    focusBraceDepth = braceDelta(trimmed)
                    focusBlockLines.clear()
                    focusBlockLines.add(trimmed)
                    if (focusBraceDepth <= 0) {
                        val fd = parseTextFocus(focusBlockLines, filePath, focusStartLine)
                        if (fd != null) {
                            if (focusIsShared) sharedFocuses.add(fd.copy(isSharedFocus = true))
                            else focuses.add(fd)
                        }
                        inFocus = false
                        focusIsShared = false
                        focusStartLine = 0
                        focusBlockLines.clear()
                    }
                    continue
                }
            }
            if (inFocus) {
                focusBlockLines.add(trimmed)
                focusBraceDepth += braceDelta(trimmed)
                if (focusBraceDepth <= 0) {
                    val fd = parseTextFocus(focusBlockLines, filePath, focusStartLine)
                    if (fd != null) {
                        if (focusIsShared) sharedFocuses.add(fd.copy(isSharedFocus = true))
                        else focuses.add(fd)
                    }
                    inFocus = false
                    focusIsShared = false
                    focusStartLine = 0
                    focusBlockLines.clear()
                }
            }
        }

        focusTrees.add(
            NationalFocusTreeData(
                id = id,
                country = country,
                focuses = focuses,
                sharedFocuses = sharedFocuses,
                sharedFocusReferences = sharedFocusReferences,
                defaultFocus = defaultFocus
            )
        )
    }

    private fun parseTextSharedFocus(filePath: String, lines: List<TextLine>): FocusData? {
        val startLine = lines.firstOrNull()?.lineNumber ?: 0
        return parseTextFocus(lines.map { it.text }, filePath, startLine)?.copy(isSharedFocus = true)
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
            sourceOffset = -1,
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

    private fun parseRootProperty(
        p: ParadoxScriptProperty,
        trees: MutableList<NationalFocusTreeData>,
        standaloneSharedFocuses: MutableList<FocusData>
    ) {
        when (p.propertyKey.text) {
            "focus_tree" -> {
                p.block?.let { parseFocusTreeBlock(it) }?.let { trees.add(it) }
            }

            "shared_focus" -> {
                parseFocusProperty(p, true)?.let { standaloneSharedFocuses.add(it) }
            }
        }
    }

    private fun parseFocusTreeBlock(block: ParadoxScriptBlock): NationalFocusTreeData? {
        var treeId: String? = null
        var country: String? = null
        var defaultFocus = false
        val focuses = mutableListOf<FocusData>()
        val sharedFocuses = mutableListOf<FocusData>()
        val sharedFocusReferences = mutableListOf<String>()
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
                    val inline = parseFocusProperty(prop, true)
                    if (inline != null) {
                        sharedFocuses.add(inline)
                    } else {
                        prop.value?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let {
                            sharedFocusReferences.add(it)
                        }
                    }
                }
            }
        }
        val id = treeId ?: return null
        return NationalFocusTreeData(
            id = id,
            country = country,
            focuses = focuses,
            sharedFocuses = sharedFocuses,
            sharedFocusReferences = sharedFocusReferences,
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
            sourceOffset = prop.textOffset,
            sourceLine = line,
            isSharedFocus = isShared
        )
    }

    private fun resolvePlsPrimaryImagePath(focusProperty: ParadoxScriptProperty): String? {
        return spriteResolver.resolveDefinitionImage(focusProperty)
    }

    fun loadSnapshot(psiFile: PsiFile): FocusPreviewSnapshot {
        val trees = parseFocusTreeFromFile(psiFile).toMutableList()
        if (trees.isEmpty()) {
            val parent = psiFile.virtualFile?.parent
            if (parent != null) {
                val manager = PsiManager.getInstance(project)
                for (child in parent.children) {
                    val childPsi = manager.findFile(child) ?: continue
                    trees.addAll(parseFocusTreeFromFile(childPsi))
                }
            }
        }
        return FocusPreviewSnapshot(trees)
    }

    fun resolve(snapshot: FocusPreviewSnapshot, onReady: (FocusPreviewSnapshot) -> Unit) {
        refreshLocalisationPreference()
        val version = resolutionVersion.incrementAndGet()
        val allFocuses = snapshot.allFocuses
        ApplicationManager.getApplication().executeOnPooledThread {
            var nextSnapshot = snapshot
            try {
                val neededKeys = neededLocalisationKeys(allFocuses)
                val locMap = ApplicationManager.getApplication().runReadAction<MutableMap<String, String>> {
                    localisationResolver.resolveAll(neededKeys).toMutableMap()
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                val roots = ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
                for ((k, v) in LocalisationFiles.mergeFromRoots(roots, LANG_PRIORITY, keys = neededKeys - locMap.keys, maxDepth = 3)) {
                    locMap.putIfAbsent(k, v)
                }

                if (version != resolutionVersion.get()) return@executeOnPooledThread

                LOG.info("Loaded ${locMap.size} loc entries")

                val iconNamesById = buildIconNames(allFocuses)
                val iconMap = spriteResolver.resolveForCandidates(iconNamesById).toMutableMap()
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
                nextSnapshot = snapshot.withResolved(nextResolvedData)
                LOG.info("Resolved ${nextResolvedData.size}/${allFocuses.size} focuses")
            } catch (e: Exception) {
                LOG.warn("Resolution failed", e)
            }
            ApplicationManager.getApplication().invokeLater { onReady(nextSnapshot) }
        }
    }

    private fun refreshLocalisationPreference() {
        val currentKey = localisationCacheKey()
        if (lastLocalisationCacheKey != currentKey) {
                localisationResolver.clearCache()
            lastLocalisationCacheKey = currentKey
        }
    }

    private fun neededLocalisationKeys(focuses: List<FocusData>): Set<String> {
        val keys = linkedSetOf<String>()
        for (focus in focuses) {
            focus.text?.let { keys.add(it) }
            keys.add(focus.id)
            focus.descriptionKey?.let { keys.add(it) }
            keys.add("${focus.id}_desc")
            focus.completeTooltip?.let { keys.add(it) }
        }
        return keys
    }

    private fun loadSharedFocusDefinitionsByFile(): Map<String, List<FocusData>> {
        val result = linkedMapOf<String, List<FocusData>>()
        for (path in findNationalFocusScriptFiles()) {
            val standalone = mutableListOf<FocusData>()
            parseFocusTreeFromText(path.toString(), mutableListOf(), standalone)
            if (standalone.isNotEmpty()) {
                result[path.toAbsolutePath().normalize().toString()] = standalone
            }
        }
        LOG.info("Loaded shared focus definitions from ${result.size} files")
        return result
    }

    private fun findNationalFocusScriptFiles(): List<Path> {
        return ResourceFiles.listFiles(
            project,
            listOf("common/national_focus", "common/continuous_focus"),
            setOf(".txt"),
            maxDepth = 4,
            projectFirst = true,
            gameFirst = false
        )
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

    fun updateFocusPosition(focus: FocusData, x: Int, y: Int): Boolean {
        val path = focus.sourceFilePath ?: return false
        if (focus.id.isBlank()) return false
        val vf = ResourceFiles.toVirtualFile(path) ?: return false
        val psiFile = PsiManager.getInstance(project).findFile(vf) as? ParadoxScriptFile ?: return false
        return WriteCommandAction.writeCommandAction(project, psiFile).withName("Update focus position").compute<Boolean, RuntimeException> {
            val documentManager = PsiDocumentManager.getInstance(project)
            val document = documentManager.getDocument(psiFile) ?: return@compute false
            documentManager.commitDocument(document)
            val target = findFocusProperty(psiFile, focus) ?: return@compute false
            val block = target.block ?: return@compute false
            upsertAxisProperty(block, "x", x)
            upsertAxisProperty(block, "y", y)
            val updatedDocument = documentManager.getDocument(psiFile) ?: return@compute false
            documentManager.commitDocument(updatedDocument)
            true
        }
    }

    private fun findFocusProperty(psiFile: ParadoxScriptFile, focus: FocusData): ParadoxScriptProperty? {
        val candidates = PsiTreeUtil.collectElementsOfType(psiFile, ParadoxScriptProperty::class.java)
            .filter { it.propertyKey.text == "focus" || it.propertyKey.text == "shared_focus" }
            .filter { it.block != null }
            .filter { prop ->
                prop.block?.propertyList?.any {
                    it.propertyKey.text == "id" && it.value?.trim()?.trim('"') == focus.id
                } == true
            }
        if (candidates.isEmpty()) return null
        if (focus.sourceOffset >= 0) {
            val offsetMatches = candidates.filter { it.textOffset == focus.sourceOffset }
            if (offsetMatches.size == 1) return offsetMatches.first()
        }
        if (focus.sourceLine > 0) {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            if (document != null) {
                val lineMatches = candidates.filter { document.getLineNumber(it.textOffset) + 1 == focus.sourceLine }
                if (lineMatches.size == 1) return lineMatches.first()
            }
        }
        return candidates.firstOrNull()
    }

    private fun upsertAxisProperty(block: ParadoxScriptBlock, key: String, value: Int) {
        val document = PsiDocumentManager.getInstance(project).getDocument(block.containingFile) ?: return
        val existing = block.propertyList.firstOrNull { it.propertyKey.text == key }
        if (existing != null) {
            val propertyValue = existing.propertyValue
            if (propertyValue != null) {
                val range = propertyValue.textRange
                document.replaceString(range.startOffset, range.endOffset, value.toString())
            } else {
                val range = existing.textRange
                document.replaceString(range.startOffset, range.endOffset, "$key = $value")
            }
            return
        }
        val leftBound = block.leftBound ?: return
        val insertOffset = leftBound.textRange.endOffset
        val indent = detectInnerIndent(block)
        val insertion = buildString {
            append('\n')
            append(indent)
            append(key)
            append(" = ")
            append(value)
        }
        document.insertString(insertOffset, insertion)
    }

    private fun detectInnerIndent(block: ParadoxScriptBlock): String = ScriptBlocks.innerIndent(project, block)

    fun clearCache() {
        localisationResolver.clearCache()
        lastLocalisationCacheKey = null
    }

}
