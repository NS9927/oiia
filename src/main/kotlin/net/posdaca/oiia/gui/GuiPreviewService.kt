package net.posdaca.oiia.gui

import net.posdaca.oiia.core.files.ResourceFiles

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.search.ParadoxLocalisationSearch
import icu.windea.pls.lang.search.util.locale
import icu.windea.pls.localisation.psi.ParadoxLocalisationProperty
import icu.windea.pls.localisation.psi.ParadoxLocalisationPropertyList
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import icu.windea.pls.script.psi.ParadoxScriptValue
import net.posdaca.oiia.core.files.LocalisationFiles
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import net.posdaca.oiia.core.script.ScriptBlocks
import net.posdaca.oiia.core.ParadoxSpriteResolver
import net.posdaca.oiia.core.ParadoxSpriteResolver.SpriteInfo
import net.posdaca.oiia.core.PreviewImageLoader
import java.awt.image.BufferedImage
import java.nio.file.Path

class GuiPreviewService(private val project: Project) {

    private val localisationCache = mutableMapOf<String, String?>()
    private var localisationCachePreferenceKey: String? = null
    private val localisationFallbackLock = Any()
    private var localisationFallbackRootsKey: String? = null
    private var localisationFallbackCache: Map<String, String> = emptyMap()
    private val spriteResolver = ParadoxSpriteResolver(project)

    fun loadSnapshot(psiFile: PsiFile): GuiPreviewSnapshot {
        return GuiPreviewSnapshot(parseGuiFile(psiFile), GuiPreviewResources(localisationCacheKey()))
    }

    fun resolve(snapshot: GuiPreviewSnapshot, shouldCancel: () -> Boolean = { false }): GuiPreviewSnapshot {
        if (snapshot.isEmpty) return snapshot
        return snapshot.copy(resources = loadResources(snapshot.file.roots, shouldCancel))
    }
    fun parseGuiFile(psiFile: PsiFile): GuiPreviewFile {
        val roots = if (psiFile is ParadoxScriptFile) {
            parseFromPlsPsi(psiFile)
        } else {
            emptyList()
        }
        return GuiPreviewFile(psiFile.virtualFile?.path, roots)
    }

    fun resolveLocalisation(key: String?): String? {
        val trimmed = key?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        synchronized(localisationCache) {
            val preferenceKey = guiLocalisationCacheKey()
            if (localisationCachePreferenceKey != preferenceKey) {
                localisationCache.clear()
                localisationCachePreferenceKey = preferenceKey
            }
            if (localisationCache.containsKey(trimmed)) return localisationCache[trimmed]
            val preferredPlsResolved = resolveLocalisationWithPls(trimmed, preferCurrentLocaleOnly = true)
            if (preferredPlsResolved != null) {
                LOG.info("GUI localisation resolved: key=$trimmed source=PLS preferred value=$preferredPlsResolved")
                localisationCache[trimmed] = preferredPlsResolved
                return preferredPlsResolved
            }
            val fallbackResolved = resolveLocalisationFromFiles(trimmed)
            if (fallbackResolved != null) {
                LOG.info("GUI localisation resolved: key=$trimmed source=fallback value=$fallbackResolved")
                localisationCache[trimmed] = fallbackResolved
                return fallbackResolved
            }
            val resolved = resolveLocalisationWithPls(trimmed, preferCurrentLocaleOnly = false)
            if (resolved == null) {
                LOG.info("GUI localisation not resolved: key=$trimmed")
            } else {
                LOG.info("GUI localisation resolved: key=$trimmed source=PLS value=$resolved")
            }
            localisationCache[trimmed] = resolved
            return resolved
        }
    }

    fun resolveSpriteInfo(spriteName: String?): SpriteInfo? {
        return spriteResolver.resolveSpriteInfo(spriteName)
    }

    private fun resolveLocalisationWithPls(key: String, preferCurrentLocaleOnly: Boolean): String? {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<Pair<String, Int>?> {
                val preferredLocale = ParadoxLocalisationPreference.preferredLocaleConfig()
                val selector = ParadoxLocalisationSearch.selector(project, null)
                    .let { if (preferCurrentLocaleOnly) it.locale(preferredLocale) else it }
                    .distinct()
                ParadoxLocalisationSearch.searchNormal(key, selector)
                    .findAll()
                    .asSequence()
                    .mapNotNull { property ->
                        val value = ParadoxLocalisationService.resolvePresentableText(property) ?: property.value
                        value?.takeIf { it.isNotBlank() && it != key }
                            ?.let { it to localisationPriority(property) }
                    }
                    .maxByOrNull { it.second }
            }
        }.getOrNull()?.first
    }

    fun loadResources(roots: List<GuiElement>, shouldCancel: () -> Boolean = { false }): GuiPreviewResources {
        val spriteNames = collectSpriteCandidates(roots)
        val textKeys = collectTextKeys(roots)
        val localisationKey = guiLocalisationCacheKey()

        val sprites = linkedMapOf<String, SpriteInfo?>()
        for (sprite in spriteNames) {
            if (shouldCancel()) return GuiPreviewResources(localisationKey)
            sprites[sprite] = runCatching { resolveSpriteInfo(sprite) }.getOrNull()
        }

        val localisations = linkedMapOf<String, String?>()
        for (key in textKeys) {
            if (shouldCancel()) return GuiPreviewResources(localisationKey)
            localisations[key] = runCatching { resolveLocalisation(key) }.getOrNull()
        }

        val images = mutableMapOf<String, BufferedImage>()
        for (path in sprites.values.flatMap { it.imagePaths() }.distinct()) {
            if (shouldCancel()) return GuiPreviewResources(localisationKey)
            PreviewImageLoader.load(path, images)
        }

        return GuiPreviewResources(localisationKey, sprites, localisations, images)
    }

    private fun parseFromPlsPsi(psiFile: ParadoxScriptFile): List<GuiElement> {
        val rootBlock = psiFile.block
        val properties = mutableListOf<ParadoxScriptProperty>()
        if (rootBlock != null) properties.addAll(rootBlock.propertyList)
        if (properties.isEmpty()) {
            for (child in psiFile.children) {
                if (child is ParadoxScriptProperty) properties.add(child)
            }
        }
        return collectRootContainers(properties)
            .mapNotNull { parseElement(it) }
            .renameDuplicateRoots()
    }

    private fun collectRootContainers(properties: List<ParadoxScriptProperty>): List<ParadoxScriptProperty> {
        val directRoots = properties.filter { it.propertyKey.text == ROOT_TYPE && it.block != null }
        if (directRoots.isNotEmpty()) return directRoots

        val wrappedRoots = properties
            .filter { it.propertyKey.text in ROOT_WRAPPER_TYPES }
            .flatMap { wrapper ->
                wrapper.block?.propertyList
                    ?.filter { it.propertyKey.text == ROOT_TYPE && it.block != null }
                    .orEmpty()
            }
        if (wrappedRoots.isNotEmpty()) return wrappedRoots

        return properties.flatMap { collectNestedRootContainers(it) }
    }

    private fun collectNestedRootContainers(prop: ParadoxScriptProperty): List<ParadoxScriptProperty> {
        val block = prop.block ?: return emptyList()
        return block.propertyList.flatMap { child ->
            if (child.propertyKey.text == ROOT_TYPE && child.block != null) {
                listOf(child)
            } else if (child.propertyKey.text in ROOT_WRAPPER_TYPES) {
                collectNestedRootContainers(child)
            } else {
                emptyList()
            }
        }
    }

    private fun parseElement(prop: ParadoxScriptProperty): GuiElement? {
        val block = prop.block ?: return null
        val type = prop.propertyKey.text
        if (type !in GUI_ELEMENT_TYPES) return null

        var name: String? = null
        var position = GuiPoint.ZERO
        var size: GuiSize? = null
        var text: String? = null
        var font: String? = null
        var buttonFont: String? = null
        var format: String? = null
        var verticalAlignment: String? = null
        var sprite: String? = null
        var quadTextureSprite: String? = null
        var background: String? = null
        var buttonSprite: String? = null
        var hoverSprite: String? = null
        var disabledSprite: String? = null
        var orientation: String? = null
        var origo: String? = null
        var scale: Double? = null
        var centerPosition = false
        var preserveAspectRatio = false
        var fullscreen = false
        var clipping = false
        var frame: Int? = null
        var horizontal: Boolean? = null
        var startValue: Double? = null
        var maxValue: Double? = null
        var minValue: Double? = null
        var maxWidth: GuiValue? = null
        var maxHeight: GuiValue? = null
        var fixedSize = false
        val properties = linkedMapOf<String, String>()
        val spriteCandidates = mutableListOf<String>()
        val children = mutableListOf<GuiElement>()

        for (field in block.propertyList) {
            when (val key = field.propertyKey.text) {
                "name" -> name = scalarValue(field)
                "position" -> position = parsePoint(field.block) ?: position
                "size" -> size = parseSize(field.block) ?: size
                "text" -> text = scalarValue(field)
                "buttonText", "buttontext" -> text = scalarValue(field)
                "font" -> font = scalarValue(field)
                "buttonFont", "buttonfont" -> buttonFont = scalarValue(field)
                "format" -> format = scalarValue(field)
                "vertical_alignment", "verticalAlignment" -> verticalAlignment = scalarValue(field)
                "spriteType" -> sprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "quadTextureSprite" -> quadTextureSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "textureFile" -> scalarValue(field)?.let(spriteCandidates::add)
                "backGround" -> background = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "background", "Background" -> {
                    parseElement(field)?.let { children.add(it) }
                }
                "buttonSpriteType" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "buttonSprite" -> buttonSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSpriteType" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "hoverSprite" -> hoverSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSpriteType" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "disabledSprite" -> disabledSprite = scalarValue(field).also { it?.let(spriteCandidates::add) }
                "Orientation" -> orientation = scalarValue(field)
                "orientation" -> orientation = scalarValue(field)
                "origo", "Origo" -> origo = scalarValue(field)
                "scale" -> scale = scalarValue(field)?.parseGuiDouble()
                "centerposition" -> centerPosition = scalarValue(field).parseGuiBoolean() ?: centerPosition
                "centerPosition" -> centerPosition = scalarValue(field).parseGuiBoolean() ?: centerPosition
                "preserve_aspect_ratio" -> preserveAspectRatio = scalarValue(field).parseGuiBoolean() ?: preserveAspectRatio
                "fullscreen" -> fullscreen = scalarValue(field).parseGuiBoolean() ?: fullscreen
                "clipping" -> clipping = scalarValue(field).parseGuiBoolean() ?: clipping
                "frame" -> frame = scalarValue(field)?.parseGuiNumber()
                "horizontal" -> horizontal = scalarValue(field).parseGuiBoolean()
                "startValue" -> startValue = scalarValue(field)?.parseGuiDouble()
                "maxValue" -> maxValue = scalarValue(field)?.parseGuiDouble()
                "minValue" -> minValue = scalarValue(field)?.parseGuiDouble()
                "maxWidth", "maxwidth" -> maxWidth = scalarValue(field)?.parseGuiValue()
                "maxHeight", "maxheight" -> maxHeight = scalarValue(field)?.parseGuiValue()
                "fixedsize", "fixedSize" -> fixedSize = scalarValue(field).parseGuiBoolean() ?: fixedSize
                in GUI_ELEMENT_TYPES -> parseElement(field)?.let { children.add(it) }
                else -> scalarValue(field)?.let { properties[key] = it }
            }
        }

        val vf = prop.containingFile.virtualFile
        val doc = vf?.let { PsiManager.getInstance(project).findViewProvider(it)?.document }
        val line = if (doc != null) doc.getLineNumber(prop.textOffset) + 1 else 0

        return GuiElement(
            type = type,
            name = name,
            position = position,
            size = size,
            text = text,
            font = font,
            buttonFont = buttonFont,
            format = format,
            verticalAlignment = verticalAlignment,
            sprite = sprite,
            quadTextureSprite = quadTextureSprite,
            background = background,
            buttonSprite = buttonSprite,
            hoverSprite = hoverSprite,
            disabledSprite = disabledSprite,
            orientation = orientation,
            origo = origo,
            scale = scale,
            centerPosition = centerPosition,
            preserveAspectRatio = preserveAspectRatio,
            fullscreen = fullscreen,
            clipping = clipping,
            frame = frame,
            horizontal = horizontal,
            startValue = startValue,
            maxValue = maxValue,
            minValue = minValue,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            fixedSize = fixedSize,
            sourceFilePath = vf?.path,
            sourceOffset = prop.textOffset,
            sourceLine = line,
            properties = properties,
            spriteCandidates = spriteCandidates.distinct(),
            children = children
        )
    }

    private fun scalarValue(prop: ParadoxScriptProperty): String? {
        return prop.value
            ?: prop.block?.propertyList?.firstOrNull { it.propertyKey.text == "value" }?.value
            ?: prop.block?.propertyList?.firstOrNull { it.propertyKey.text == "name" }?.value
            ?: prop.block?.valueList?.firstOrNull()?.text?.trim()
            ?: prop.propertyValue?.text?.trim()
    }

    private fun parsePoint(block: ParadoxScriptBlock?): GuiPoint? {
        val properties = block?.propertyList.orEmpty()
        val x = properties.firstOrNull { it.propertyKey.text == "x" }?.value?.parseGuiValue()
        val y = properties.firstOrNull { it.propertyKey.text == "y" }?.value?.parseGuiValue()
        if (x != null || y != null) return GuiPoint(x ?: GuiValue.ZERO, y ?: GuiValue.ZERO)
        val values = blockValues(block)
        if (values.size < 2) return null
        return GuiPoint(values[0].parseGuiValue() ?: GuiValue.ZERO, values[1].parseGuiValue() ?: GuiValue.ZERO)
    }

    private fun parseSize(block: ParadoxScriptBlock?): GuiSize? {
        val properties = block?.propertyList.orEmpty()
        val widthByAxis = properties.firstOrNull { it.propertyKey.text == "x" }?.value?.parseGuiValue()
        val heightByAxis = properties.firstOrNull { it.propertyKey.text == "y" }?.value?.parseGuiValue()
        if (widthByAxis != null || heightByAxis != null) {
            return GuiSize(widthByAxis ?: GuiValue.ZERO, heightByAxis ?: GuiValue.ZERO)
        }
        val widthByName = properties.firstOrNull { it.propertyKey.text == "width" }?.value?.parseGuiValue()
        val heightByName = properties.firstOrNull { it.propertyKey.text == "height" }?.value?.parseGuiValue()
        if (widthByName != null || heightByName != null) {
            return GuiSize(widthByName ?: GuiValue.ZERO, heightByName ?: GuiValue.ZERO)
        }
        val values = blockValues(block)
        if (values.size < 2) return null
        val width = values[0].parseGuiValue() ?: return null
        val height = values[1].parseGuiValue() ?: return null
        return GuiSize(width, height)
    }

    private fun String.parseGuiNumber(): Int? {
        return parseGuiValue()?.asFallbackPixels()
    }

    private fun String.parseGuiValue(): GuiValue? {
        val clean = trim().trim('"')
        val percent = clean.endsWith("%")
        val number = clean.trimEnd('%').toDoubleOrNull() ?: return null
        return GuiValue.of(number, percent)
    }

    private fun String?.parseGuiDouble(): Double? {
        val clean = this?.trim()?.trim('"')?.removeSuffix("%") ?: return null
        return clean.toDoubleOrNull()
    }

    private fun String?.parseGuiBoolean(): Boolean? {
        return when (this?.trim()?.trim('"')?.lowercase()) {
            "yes", "true", "1" -> true
            "no", "false", "0" -> false
            else -> null
        }
    }

    private fun blockValues(block: ParadoxScriptBlock?): List<String> {
        if (block == null) return emptyList()
        val fromValues = block.valueList.map { it.cleanValueText() }
        if (fromValues.isNotEmpty()) return fromValues
        val fromProperties = block.propertyList.mapNotNull { field ->
            field.value ?: field.propertyKey.text
        }
        if (fromProperties.isNotEmpty()) return fromProperties.map { it.trim().trim('"') }
        return TOKEN_REGEX.findAll(block.text)
            .map { it.value.trim('"') }
            .filterNot { it == "{" || it == "}" }
            .toList()
    }

    private fun ParadoxScriptValue.cleanValueText(): String {
        return text.trim().trim('"')
    }

    private fun resolveLocalisationFromFiles(key: String): String? {
        return loadLocalisationFallbackCache()[key]
    }

    private fun loadLocalisationFallbackCache(): Map<String, String> {
        val roots = ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
        val rootsKey = guiLocalisationCacheKey() + "|" +
                roots.joinToString("|") { ResourceFiles.normalizedKey(it) }
        synchronized(localisationFallbackLock) {
            if (localisationFallbackRootsKey == rootsKey) return localisationFallbackCache

            val paths = LocalisationFiles.findFiles(roots)
            val result = LocalisationFiles.mergePreferred(
                paths,
                score = { path ->
                    languagePriority(path.toString()) * LOCALISATION_SCORE_LANGUAGE_WEIGHT +
                        LocalisationFiles.rootScore(path, LocalisationFiles.rootScores(roots))
                },
                unescapeValues = true,
            )

            localisationFallbackRootsKey = rootsKey
            localisationFallbackCache = result
            LOG.info("GUI localisation fallback loaded: roots=${roots.size} files=${paths.size} entries=${result.size}")
            return result
        }
    }

    private fun languagePriority(path: String): Int {
        return ParadoxLocalisationPreference.languagePriority(path, LANG_PRIORITY)
    }

    private fun guiLocalisationCacheKey(): String {
        return localisationCacheKey()
    }

    private fun localisationPriority(property: ParadoxLocalisationProperty): Int {
        val localisationFile = property.containingFile
        val locale = (property.parent as? ParadoxLocalisationPropertyList)?.locale?.name
        val paths = listOfNotNull(
            locale,
            localisationFile?.virtualFile?.path,
            localisationFile?.name
        )
        return paths.maxOfOrNull { languagePriority(it) } ?: 0
    }

    private fun collectSpriteCandidates(roots: List<GuiElement>): List<String> {
        val result = linkedSetOf<String>()
        roots.forEach { collectSpriteCandidates(it, result) }
        return result.toList()
    }

    private fun collectSpriteCandidates(element: GuiElement, result: MutableSet<String>) {
        result.addAll(spriteCandidates(element))
        element.children.forEach { collectSpriteCandidates(it, result) }
    }

    private fun collectTextKeys(roots: List<GuiElement>): List<String> {
        val result = linkedSetOf<String>()
        roots.forEach { collectTextKeys(it, result) }
        return result.toList()
    }

    private fun collectTextKeys(element: GuiElement, result: MutableSet<String>) {
        normalisedLocalisationKey(element.text)?.let(result::add)
        element.children.forEach { collectTextKeys(it, result) }
    }

    private fun spriteCandidates(element: GuiElement): List<String> {
        return element.resolvedSpriteCandidates()
    }

    private fun normalisedLocalisationKey(value: String?): String? {
        return value?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private fun SpriteInfo?.imagePaths(): List<String> {
        if (this == null) return emptyList()
        return listOfNotNull(primaryImagePath, imagePath1, imagePath2).distinct()
    }

    private fun List<GuiElement>.renameDuplicateRoots(): List<GuiElement> {
        val counts = groupingBy { it.name.orEmpty() }.eachCount()
        return map { root ->
            if (root.name.isNullOrBlank() || counts[root.name] == 1) root else root.copy(
                properties = root.properties + ("previewDisplayName" to "${root.name} (line ${root.sourceLine})")
            )
        }
    }

    fun updateElementPosition(element: GuiElement, x: Int, y: Int): Boolean {
        val path = element.sourceFilePath ?: return false
        if (element.sourceOffset < 0) return false
        val vf = ResourceFiles.toVirtualFile(path) ?: return false
        val psiFile = PsiManager.getInstance(project).findFile(vf) as? ParadoxScriptFile ?: return false
        return WriteCommandAction.writeCommandAction(project, psiFile).withName("Update GUI position").compute<Boolean, RuntimeException> {
            val documentManager = PsiDocumentManager.getInstance(project)
            val document = documentManager.getDocument(psiFile) ?: return@compute false
            documentManager.commitDocument(document)
            val target = findElementProperty(psiFile, element) ?: return@compute false
            val block = target.block ?: return@compute false
            val positionProp = block.propertyList.firstOrNull { it.propertyKey.text == "position" }
            val positionText = formatPositionBlock(x, y, positionProp)
            if (positionProp != null) {
                replacePropertyText(positionProp, positionText)
            } else {
                insertPositionProperty(block, positionText)
            }
            val updatedDocument = documentManager.getDocument(psiFile) ?: return@compute false
            documentManager.commitDocument(updatedDocument)
            true
        }
    }

    private fun findElementProperty(psiFile: ParadoxScriptFile, element: GuiElement): ParadoxScriptProperty? {
        val candidates = PsiTreeUtil.collectElementsOfType(psiFile, ParadoxScriptProperty::class.java)
            .filter { it.propertyKey.text == element.type }
            .filter { it.block != null }
        if (candidates.isEmpty()) return null

        val offsetMatches = candidates.filter { it.textOffset == element.sourceOffset }
        if (offsetMatches.size == 1) return offsetMatches.first()

        val lineMatches = if (element.sourceLine > 0) {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            if (document != null) {
                candidates.filter { document.getLineNumber(it.textOffset) + 1 == element.sourceLine }
            } else emptyList()
        } else emptyList()
        if (lineMatches.size == 1) return lineMatches.first()

        val nameMatches = if (!element.name.isNullOrBlank()) {
            candidates.filter { prop ->
                prop.block?.propertyList?.any {
                    it.propertyKey.text == "name" && scalarValue(it)?.trim()?.trim('"') == element.name
                } == true
            }
        } else emptyList()
        if (nameMatches.size == 1) return nameMatches.first()

        return offsetMatches.firstOrNull()
            ?: lineMatches.firstOrNull()
            ?: nameMatches.firstOrNull()
            ?: candidates.minByOrNull { kotlin.math.abs(it.textOffset - element.sourceOffset) }
    }

    private fun formatPositionBlock(x: Int, y: Int, existing: ParadoxScriptProperty?): String {
        val block = existing?.block
        val properties = block?.propertyList.orEmpty()
        val usesNamedAxes = properties.any { it.propertyKey.text == "x" || it.propertyKey.text == "y" }
        val compact = block?.text?.let { !it.contains('\n') } ?: true
        return if (usesNamedAxes || existing == null) {
            if (compact) {
                "position = { x = $x y = $y }"
            } else {
                buildString {
                    append("position = {")
                    append('\n')
                    append("\t\tx = ")
                    append(x)
                    append('\n')
                    append("\t\ty = ")
                    append(y)
                    append('\n')
                    append("\t}")
                }
            }
        } else {
            if (compact) {
                "position = { $x $y }"
            } else {
                buildString {
                    append("position = {")
                    append('\n')
                    append("\t\t")
                    append(x)
                    append(' ')
                    append(y)
                    append('\n')
                    append("\t}")
                }
            }
        }
    }

    private fun replacePropertyText(property: ParadoxScriptProperty, newText: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(property.containingFile) ?: return
        val range = property.textRange
        document.replaceString(range.startOffset, range.endOffset, newText)
    }

    private fun insertPositionProperty(block: ParadoxScriptBlock, positionText: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(block.containingFile) ?: return
        val leftBound = block.leftBound ?: return
        val insertOffset = leftBound.textRange.endOffset
        val indent = detectInnerIndent(block)
        val insertion = buildString {
            append('\n')
            append(indent)
            append(positionText)
        }
        document.insertString(insertOffset, insertion)
    }

    private fun detectInnerIndent(block: ParadoxScriptBlock): String = ScriptBlocks.innerIndent(project, block)

    companion object {
        private val LOG = Logger.getInstance(GuiPreviewService::class.java)
        private const val ROOT_TYPE = "containerWindowType"
        private const val LOCALISATION_SCORE_LANGUAGE_WEIGHT = 10000
        private val ROOT_WRAPPER_TYPES = setOf("guiTypes", "windowTypes")
        private val LANG_PRIORITY = listOf(
            "simp_chinese",
            "english",
            "braz_por",
            "french",
            "german",
            "polish",
            "russian",
            "spanish",
            "japanese"
        )
        fun localisationCacheKey(): String {
            return ParadoxLocalisationPreference.cacheKey(LANG_PRIORITY)
        }

        private val GUI_ELEMENT_TYPES = setOf(
            "background",
            "Background",
            "containerWindowType",
            "iconType",
            "buttonType",
            "instantTextBoxType",
            "instantTextboxType",
            "listboxType",
            "scrollbarType",
            "editBoxType",
            "checkboxType",
            "gridboxType",
            "gridBoxType",
            "gridboxtype",
            "smoothListboxType",
            "smoothListBoxType",
            "overlappingElementsBoxType",
            "OverlappingElementsBoxType",
            "dropDownBoxType",
            "browserType",
            "positionType",
            "extendedScrollbarType",
            "guiButtonType",
            "slider",
            "track",
            "increaseButton",
            "decreaseButton"
        )
        private val TOKEN_REGEX = Regex(""""[^"]*"|[^\s{}=]+""")
    }
}
