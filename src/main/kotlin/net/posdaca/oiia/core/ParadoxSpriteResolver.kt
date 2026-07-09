package net.posdaca.oiia.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import icu.windea.pls.lang.search.ParadoxDefinitionSearch
import icu.windea.pls.lang.search.ParadoxFilePathSearch
import icu.windea.pls.lang.util.ParadoxDefinitionManager
import icu.windea.pls.lang.util.ParadoxImageManager
import icu.windea.pls.script.psi.ParadoxScriptProperty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class ParadoxSpriteResolver(private val project: Project) {

    data class SpriteInfo(
        val name: String,
        val imagePath: String?,
        val imagePath1: String? = null,
        val imagePath2: String? = null,
        val subtype: String? = null,
        val textureFile: String? = null,
        val borderSize: SpriteInsets? = null,
        val size: SpriteSize? = null,
        val tilingCenter: Boolean = false,
        val noOfFrames: Int? = null,
        val defaultFrame: Int? = null,
        val textureFile1: String? = null,
        val textureFile2: String? = null,
        val effectFile: String? = null,
        val horizontal: Boolean? = null,
        val steps: Int? = null,
        val rotation: Int? = null,
        val amount: Int? = null,
        val alwaysTransparent: Boolean? = null
    ) {
        val primaryImagePath: String?
            get() = imagePath ?: imagePath1 ?: imagePath2
    }

    data class SpriteInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class SpriteSize(
        val width: Int,
        val height: Int
    )

    private data class SpriteDefinition(
        val name: String,
        val textureFile: String?,
        val root: Path,
        val subtype: String? = null,
        val borderSize: SpriteInsets? = null,
        val size: SpriteSize? = null,
        val tilingCenter: Boolean = false,
        val noOfFrames: Int? = null,
        val defaultFrame: Int? = null,
        val textureFile1: String? = null,
        val textureFile2: String? = null,
        val effectFile: String? = null,
        val horizontal: Boolean? = null,
        val steps: Int? = null,
        val rotation: Int? = null,
        val amount: Int? = null,
        val alwaysTransparent: Boolean? = null
    )

    private data class SpriteBlock(
        val key: String,
        val body: String
    )

    private val resolvedSpriteInfos = mutableMapOf<String, SpriteInfo?>()
    private val iconFiles = mutableMapOf<String, String>()
    private val spriteIconFiles = mutableMapOf<String, String>()
    private val spriteDefinitionsByName = mutableMapOf<String, SpriteDefinition>()
    private var cacheRootsKey: String? = null

    fun resolveDefinitionImage(definition: ParadoxScriptProperty): String? {
        return runCatching {
            ParadoxImageManager.resolveUrlByDefinition(definition)
                ?: ParadoxDefinitionManager.getPrimaryImages(definition).firstOrNull()?.virtualFile?.let { vf ->
                    ParadoxImageManager.resolveUrlByFile(vf, project) ?: vf.path
                }
        }.getOrNull()
    }

    fun resolveSprite(name: String?): String? {
        return resolveSpriteInfo(name)?.primaryImagePath
    }

    fun resolveSpriteInfo(name: String?): SpriteInfo? {
        val normalized = name?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        synchronized(resolvedSpriteInfos) {
            if (resolvedSpriteInfos.containsKey(normalized)) return resolvedSpriteInfos[normalized]
            val resolved = resolveSpriteInfoWithPls(normalized)
                ?: resolveSpriteInfoFromCache(normalized)
            resolvedSpriteInfos[normalized] = resolved
            return resolved
        }
    }

    fun resolveFirst(names: Iterable<String?>): String? {
        for (name in names) {
            val resolved = resolveSprite(name)
            if (resolved != null) return resolved
        }
        return null
    }

    fun resolveForCandidates(candidatesById: Map<String, List<String>>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((id, candidates) in candidatesById) {
            resolveFirst(candidates)?.let { result[id] = it }
        }
        return result
    }

    private fun resolveSpriteInfoWithPls(name: String): SpriteInfo? {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<SpriteInfo?> {
                val selector = ParadoxDefinitionSearch.selector(project, null).distinct()
                val definitions = ParadoxDefinitionSearch.searchProperty(name, "sprite", selector).findAll()
                    .ifEmpty { ParadoxDefinitionSearch.searchProperty(name, null, selector).findAll() }
                definitions
                    .asSequence()
                    .filter { it.isSpriteDefinition() }
                    .firstNotNullOfOrNull { definition -> definition.toSpriteInfo(name) }
            }
        }.getOrNull()
    }

    private fun ParadoxScriptProperty.toSpriteInfo(name: String): SpriteInfo? {
        val block = block
        val textureFile = block?.propertyValue("textureFile")?.let(::cleanToken)
        val textureFile1 = block?.propertyValue("textureFile1")?.let(::cleanToken)
        val textureFile2 = block?.propertyValue("textureFile2")?.let(::cleanToken)
        val effectFile = block?.propertyValue("effectFile")?.let(::cleanToken)
        val rootHint = resourceRootFor(containingFile?.virtualFile?.path)
        val imagePath1 = resolveTexturePath(textureFile1, rootHint)
        val imagePath2 = resolveTexturePath(textureFile2, rootHint)
        val imagePath = resolveDefinitionImage(this)
            ?: resolveTexturePath(textureFile, rootHint)
            ?: imagePath1
            ?: imagePath2
        if (imagePath == null && imagePath1 == null && imagePath2 == null) return null
        val info = SpriteInfo(
            name = name,
            imagePath = imagePath,
            imagePath1 = imagePath1,
            imagePath2 = imagePath2,
            subtype = runCatching { ParadoxDefinitionManager.getSubtypes(this)?.firstOrNull() }.getOrNull()
                ?: subtypeFromPropertyKey(propertyKey.text),
            textureFile = textureFile,
            borderSize = block?.propertyBlock("borderSize")?.let(::parseInsets),
            size = block?.propertyBlock("size")?.let(::parseSize),
            tilingCenter = block?.propertyValue("tilingCenter").parseParadoxBoolean() ?: false,
            noOfFrames = block?.propertyValue("noOfFrames")?.parseParadoxInt(),
            defaultFrame = block?.propertyValue("default_frame")?.parseParadoxInt(),
            textureFile1 = textureFile1,
            textureFile2 = textureFile2,
            effectFile = effectFile,
            horizontal = block?.propertyValue("horizontal").parseParadoxBoolean(),
            steps = block?.propertyValue("steps")?.parseParadoxInt(),
            rotation = block?.propertyValue("rotation")?.parseParadoxInt(),
            amount = block?.propertyValue("amount")?.parseParadoxInt(),
            alwaysTransparent = block?.propertyValue("alwaystransparent").parseParadoxBoolean()
                ?: block?.propertyValue("allwaystransparent").parseParadoxBoolean()
        )
        LOG.info("GUI sprite resolved by PLS: name=$name type=${propertyKey.text} file=${containingFile?.virtualFile?.path} texture=${info.textureFile} image=${info.primaryImagePath} frames=${info.noOfFrames} size=${info.size} effect=${info.effectFile}")
        return info
    }

    private fun ParadoxScriptProperty.isSpriteDefinition(): Boolean {
        val key = propertyKey.text
        if (key.lowercase() in SPRITE_DEFINITION_PROPERTY_KEYS) return true
        val type = runCatching { ParadoxDefinitionManager.getType(this) }.getOrNull()
        return type != null && type.contains("sprite", ignoreCase = true)
    }

    private fun ensureIconCache() {
        val roots = HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
        val rootsKey = roots.joinToString("|") { HoI4ResourceRoots.normalizedKey(it) }
        if (cacheRootsKey == rootsKey) return
        synchronized(this) {
            if (cacheRootsKey == rootsKey) return
            iconFiles.clear()
            spriteIconFiles.clear()
            spriteDefinitionsByName.clear()

            val spriteDefinitions = mutableListOf<SpriteDefinition>()
            for (root in roots) {
                cacheImages(root)
                cacheSpriteDefinitions(root, spriteDefinitions)
            }

            for (sprite in spriteDefinitions) {
                val iconPath = resolveTexturePath(sprite.textureFile, sprite.root, ensureCache = false)
                    ?: resolveTexturePath(sprite.textureFile1, sprite.root, ensureCache = false)
                    ?: resolveTexturePath(sprite.textureFile2, sprite.root, ensureCache = false)
                if (iconPath != null) putAliases(spriteIconFiles, sprite.name, iconPath)
                putDefinitionAliases(sprite)
            }

            cacheRootsKey = rootsKey
            resolvedSpriteInfos.clear()
            LOG.info("GUI sprite cache rebuilt: roots=${roots.size} images=${iconFiles.size} spriteImages=${spriteIconFiles.size} definitions=${spriteDefinitionsByName.size}")
        }
    }

    private fun cacheImages(root: Path) {
        val gfxDir = root.resolve("gfx")
        if (!Files.isDirectory(gfxDir)) return
        runCatching {
            Files.walk(gfxDir, 6).use { stream ->
                stream
                    .filter { it.isRegularFile() }
                    .filter { path -> ICON_EXTENSIONS.any { path.fileName.toString().endsWith(it, ignoreCase = true) } }
                    .forEach { cacheIconFile(root, it) }
            }
        }
    }

    private fun cacheSpriteDefinitions(root: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        for (dir in listOf(root.resolve("interface"), root.resolve("gfx"))) {
            if (!Files.isDirectory(dir)) continue
            runCatching {
                Files.walk(dir, 6).use { stream ->
                    stream
                        .filter { it.isRegularFile() && it.fileName.toString().endsWith(".gfx", ignoreCase = true) }
                        .forEach { parseGfxFile(root, it, spriteDefinitions) }
                }
            }
        }
    }

    private fun parseGfxFile(root: Path, path: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        val content = runCatching { Files.readString(path) }.getOrNull() ?: return
        findSpriteBlocks(content).forEach { spriteBlock ->
            val body = spriteBlock.body
            val assignmentBody = stripLineComments(body)
            val name = NAME_ASSIGNMENT_REGEX.find(assignmentBody)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val texture = parseAssignmentValue(assignmentBody, "textureFile")
            val texture1 = parseAssignmentValue(assignmentBody, "textureFile1")
            val texture2 = parseAssignmentValue(assignmentBody, "textureFile2")
            val effectFile = parseAssignmentValue(assignmentBody, "effectFile")
            if (texture == null && texture1 == null && texture2 == null) return@forEach
            val cleanName = cleanToken(name)
            spriteDefinitions.add(
                SpriteDefinition(
                    name = cleanName,
                    textureFile = texture,
                    root = root,
                    subtype = subtypeFromPropertyKey(spriteBlock.key),
                    borderSize = parseBorderSize(assignmentBody),
                    size = parseSpriteSize(assignmentBody),
                    tilingCenter = parseAssignmentValue(assignmentBody, "tilingCenter").parseParadoxBoolean() ?: false,
                    noOfFrames = parseAssignmentValue(assignmentBody, "noOfFrames")?.parseParadoxInt(),
                    defaultFrame = parseAssignmentValue(assignmentBody, "default_frame")?.parseParadoxInt(),
                    textureFile1 = texture1,
                    textureFile2 = texture2,
                    effectFile = effectFile,
                    horizontal = parseAssignmentValue(assignmentBody, "horizontal").parseParadoxBoolean(),
                    steps = parseAssignmentValue(assignmentBody, "steps")?.parseParadoxInt(),
                    rotation = parseAssignmentValue(assignmentBody, "rotation")?.parseParadoxInt(),
                    amount = parseAssignmentValue(assignmentBody, "amount")?.parseParadoxInt(),
                    alwaysTransparent = parseAssignmentValue(assignmentBody, "alwaystransparent").parseParadoxBoolean()
                        ?: parseAssignmentValue(assignmentBody, "allwaystransparent").parseParadoxBoolean()
                )
            )
        }
    }

    private fun findSpriteBlocks(content: String): Sequence<SpriteBlock> {
        val searchableContent = stripLineComments(content)
        return sequence {
            var index = 0
            while (index < searchableContent.length) {
                val match = SPRITE_BLOCK_START_REGEX.find(searchableContent, index) ?: break
                val key = match.groupValues[1]
                val openBraceIndex = match.range.last
                val closeBraceIndex = findMatchingBrace(searchableContent, openBraceIndex)
                if (closeBraceIndex == null) {
                    index = openBraceIndex + 1
                    continue
                }
                yield(SpriteBlock(key, content.substring(openBraceIndex + 1, closeBraceIndex)))
                index = closeBraceIndex + 1
            }
        }
    }

    private fun stripLineComments(content: String): String {
        val result = StringBuilder(content.length)
        var inString = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            if (inString) {
                result.append(char)
                if (char == '\\' && index + 1 < content.length) {
                    index++
                    result.append(content[index])
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> {
                        inString = true
                        result.append(char)
                    }
                    '#' -> {
                        result.append(' ')
                        while (index + 1 < content.length && content[index + 1] != '\n' && content[index + 1] != '\r') {
                            index++
                            result.append(' ')
                        }
                    }
                    else -> result.append(char)
                }
            }
            index++
        }
        return result.toString()
    }

    private fun findMatchingBrace(content: String, openBraceIndex: Int): Int? {
        var depth = 0
        var inString = false
        var index = openBraceIndex
        while (index < content.length) {
            val c = content[index]
            if (inString) {
                if (c == '\\') {
                    index += 2
                    continue
                }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '#' -> {
                        index = content.indexOf('\n', index).takeIf { it >= 0 } ?: content.length
                        continue
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return null
    }

    private fun cacheIconFile(root: Path, file: Path) {
        val absolutePath = file.toAbsolutePath().normalize().toString()
        putAliases(iconFiles, file.fileName.toString().substringBeforeLast("."), absolutePath)
        runCatching {
            putAliases(iconFiles, root.relativize(file).toString().substringBeforeLast("."), absolutePath)
        }
    }

    private fun findCachedSpriteIconPath(name: String): String? {
        ensureIconCache()
        return lookupCachedPath(spriteIconFiles, name)
    }

    private fun findCachedIconPath(name: String): String? {
        ensureIconCache()
        return lookupCachedPath(iconFiles, name)
    }

    private fun lookupCachedPath(cache: Map<String, String>, name: String): String? {
        for (alias in aliases(name)) {
            cache[alias]?.let { return it }
        }
        return null
    }

    private fun putAliases(cache: MutableMap<String, String>, name: String, path: String) {
        for (alias in aliases(name)) {
            cache.putIfAbsent(alias, path)
        }
    }

    private fun putAliases(cache: MutableMap<String, SpriteDefinition>, name: String, definition: SpriteDefinition) {
        for (alias in aliases(name)) {
            cache.putIfAbsent(alias, definition)
        }
    }

    private fun putDefinitionAliases(definition: SpriteDefinition) {
        putAliases(spriteDefinitionsByName, definition.name, definition)
        definition.textureFile?.let { putAliases(spriteDefinitionsByName, it, definition) }
    }

    private fun resolveSpriteInfoFromCache(name: String): SpriteInfo? {
        ensureIconCache()
        val definition = lookupSpriteDefinition(name)
        val imagePath1 = resolveTexturePath(definition?.textureFile1, definition?.root, ensureCache = false)
        val imagePath2 = resolveTexturePath(definition?.textureFile2, definition?.root, ensureCache = false)
        val path = resolveTexturePath(definition?.textureFile, definition?.root, ensureCache = false)
            ?: findCachedSpriteIconPath(name)
            ?: findCachedIconPath(name)
            ?: imagePath1
            ?: imagePath2
        if (definition == null && path == null && imagePath1 == null && imagePath2 == null) return null
        val info = SpriteInfo(
            name = name,
            imagePath = path,
            imagePath1 = imagePath1,
            imagePath2 = imagePath2,
            subtype = definition?.subtype,
            textureFile = definition?.textureFile,
            borderSize = definition?.borderSize,
            size = definition?.size,
            tilingCenter = definition?.tilingCenter ?: false,
            noOfFrames = definition?.noOfFrames,
            defaultFrame = definition?.defaultFrame,
            textureFile1 = definition?.textureFile1,
            textureFile2 = definition?.textureFile2,
            effectFile = definition?.effectFile,
            horizontal = definition?.horizontal,
            steps = definition?.steps,
            rotation = definition?.rotation,
            amount = definition?.amount,
            alwaysTransparent = definition?.alwaysTransparent
        )
        if (definition == null) {
            LOG.info("GUI sprite resolved by image fallback without gfx definition: name=$name aliases=${aliases(name)} image=${info.primaryImagePath}")
        } else {
            LOG.info("GUI sprite resolved by cache: name=$name type=${definition.subtype} root=${definition.root} texture=${definition.textureFile} image=${info.primaryImagePath} frames=${info.noOfFrames} size=${info.size} effect=${info.effectFile}")
        }
        return info
    }

    private fun lookupSpriteDefinition(name: String): SpriteDefinition? {
        for (alias in aliases(name)) {
            spriteDefinitionsByName[alias]?.let { return it }
        }
        return null
    }

    private fun aliases(name: String): Set<String> {
        val normalized = removeExtension(cleanToken(name).replace('\\', '/').lowercase())
        if (normalized.isBlank()) return emptySet()

        val aliases = linkedSetOf(normalized)
        if (normalized.startsWith("gfx/")) aliases.add(normalized.removePrefix("gfx/"))
        if (normalized.startsWith("interface/")) aliases.add(normalized.removePrefix("interface/"))
        aliases.add(normalized.substringAfterLast('/'))

        val strippedGfxAliases = aliases.toList().mapNotNull { alias ->
            alias.takeIf { it.startsWith("gfx_") }?.removePrefix("gfx_")
        }
        aliases.addAll(strippedGfxAliases)
        return aliases.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun removeExtension(name: String): String {
        for (extension in ICON_EXTENSIONS) {
            if (name.endsWith(extension)) return name.removeSuffix(extension)
        }
        return name
    }

    private fun resolveGamePath(root: Path, rawPath: String): Path {
        val normalizedPath = cleanToken(rawPath).replace('\\', '/')
        val path = runCatching { Path.of(normalizedPath) }.getOrNull()
        return if (path != null && path.isAbsolute) path.normalize() else root.resolve(normalizedPath).normalize()
    }

    private fun resolveTexturePath(rawPath: String?, rootHint: Path?, ensureCache: Boolean = true): String? {
        val cleanPath = rawPath?.let(::cleanToken)?.takeIf { it.isNotBlank() } ?: return null
        if (rootHint != null) {
            val directPath = resolveGamePath(rootHint, cleanPath)
            if (directPath.isRegularFile()) return directPath.toAbsolutePath().normalize().toString()
        }
        val absolute = runCatching { Path.of(cleanPath) }.getOrNull()
        if (absolute != null && absolute.isAbsolute && absolute.isRegularFile()) {
            return absolute.toAbsolutePath().normalize().toString()
        }
        resolveTexturePathWithPls(cleanPath)?.let { return it }
        if (ensureCache) ensureIconCache()
        return lookupCachedPath(iconFiles, cleanPath)
    }

    private fun resolveTexturePathWithPls(cleanPath: String): String? {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<String?> {
                val selector = ParadoxFilePathSearch.selector(project, null).distinct()
                val file = ParadoxFilePathSearch.searchIcon(cleanPath, selector, ignoreLocale = true).find()
                    ?: ParadoxFilePathSearch.search(cleanPath, selector = selector, ignoreLocale = true).find()
                file?.let { ParadoxImageManager.resolveUrlByFile(it, project) ?: it.path }
            }
        }.getOrNull()
    }

    private fun resourceRootFor(filePath: String?): Path? {
        val file = filePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } ?: return null
        return HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
            .firstOrNull { root -> file.startsWith(root.toAbsolutePath().normalize()) }
    }

    private fun subtypeFromPropertyKey(key: String): String? {
        return when (key.lowercase()) {
            "spritetype" -> "normal"
            "frameanimatedspritetype" -> "frame_animated_sprite"
            "corneredtilespritetype" -> "cornered_tile_sprite"
            "maskedshieldtype" -> "masked_shield"
            "textspritetype" -> "text_sprite"
            "progressbartype" -> "progressbar"
            "circularprogressbartype" -> "circular_progressbar"
            "piecharttype" -> "pie_chart"
            "linecharttype" -> "line_chart"
            "quadtexturesprite" -> "quad_texture"
            else -> null
        }
    }

    private fun icu.windea.pls.script.psi.ParadoxScriptBlock.propertyValue(key: String): String? {
        val property = propertyList.firstOrNull { it.propertyKey.text.equals(key, ignoreCase = true) } ?: return null
        return property.value
            ?: property.block?.propertyList?.firstOrNull { it.propertyKey.text.equals("value", ignoreCase = true) }?.value
            ?: property.block?.valueList?.firstOrNull()?.text?.trim()
            ?: property.propertyValue?.text?.trim()
    }

    private fun icu.windea.pls.script.psi.ParadoxScriptBlock.propertyBlock(key: String): icu.windea.pls.script.psi.ParadoxScriptBlock? {
        return propertyList.firstOrNull { it.propertyKey.text.equals(key, ignoreCase = true) }?.block
    }

    private fun parseInsets(block: icu.windea.pls.script.psi.ParadoxScriptBlock): SpriteInsets {
        val sideLeft = block.propertyValue("left")?.toDoubleOrNull()?.toInt()
        val sideTop = block.propertyValue("top")?.toDoubleOrNull()?.toInt()
        val sideRight = block.propertyValue("right")?.toDoubleOrNull()?.toInt()
        val sideBottom = block.propertyValue("bottom")?.toDoubleOrNull()?.toInt()
        if (sideLeft != null || sideTop != null || sideRight != null || sideBottom != null) {
            return SpriteInsets(
                left = sideLeft ?: 0,
                top = sideTop ?: 0,
                right = sideRight ?: sideLeft ?: 0,
                bottom = sideBottom ?: sideTop ?: 0
            )
        }
        val x = block.propertyValue("x")?.toDoubleOrNull()?.toInt()
        val y = block.propertyValue("y")?.toDoubleOrNull()?.toInt()
        val width = block.propertyValue("width")?.toDoubleOrNull()?.toInt()
        val height = block.propertyValue("height")?.toDoubleOrNull()?.toInt()
        val values = block.valueList.mapNotNull { it.text.trim().trim('"').toDoubleOrNull()?.toInt() }
        return SpriteInsets(
            left = x ?: width ?: values.getOrNull(0) ?: 0,
            top = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0,
            right = x ?: width ?: values.getOrNull(2) ?: values.getOrNull(0) ?: 0,
            bottom = y ?: height ?: values.getOrNull(3) ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        )
    }

    private fun parseSize(block: icu.windea.pls.script.psi.ParadoxScriptBlock): SpriteSize {
        val x = block.propertyValue("x")?.toDoubleOrNull()?.toInt()
        val y = block.propertyValue("y")?.toDoubleOrNull()?.toInt()
        val width = block.propertyValue("width")?.toDoubleOrNull()?.toInt()
        val height = block.propertyValue("height")?.toDoubleOrNull()?.toInt()
        val values = block.valueList.mapNotNull { it.text.trim().trim('"').toDoubleOrNull()?.toInt() }
        return SpriteSize(
            width = x ?: width ?: values.getOrNull(0) ?: 0,
            height = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        )
    }

    private fun parseBorderSize(body: String): SpriteInsets? {
        val block = Regex("""(?is)\bborderSize\s*=\s*\{(.*?)\}""").find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val sideLeft = parseAssignmentValue(block, "left")?.toDoubleOrNull()?.toInt()
        val sideTop = parseAssignmentValue(block, "top")?.toDoubleOrNull()?.toInt()
        val sideRight = parseAssignmentValue(block, "right")?.toDoubleOrNull()?.toInt()
        val sideBottom = parseAssignmentValue(block, "bottom")?.toDoubleOrNull()?.toInt()
        if (sideLeft != null || sideTop != null || sideRight != null || sideBottom != null) {
            return SpriteInsets(
                left = sideLeft ?: 0,
                top = sideTop ?: 0,
                right = sideRight ?: sideLeft ?: 0,
                bottom = sideBottom ?: sideTop ?: 0
            )
        }
        val x = parseAssignmentValue(block, "x")?.toDoubleOrNull()?.toInt()
        val y = parseAssignmentValue(block, "y")?.toDoubleOrNull()?.toInt()
        val width = parseAssignmentValue(block, "width")?.toDoubleOrNull()?.toInt()
        val height = parseAssignmentValue(block, "height")?.toDoubleOrNull()?.toInt()
        val values = NUMBER_REGEX.findAll(block).mapNotNull { it.value.toDoubleOrNull()?.toInt() }.toList()
        return SpriteInsets(
            left = x ?: width ?: values.getOrNull(0) ?: 0,
            top = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0,
            right = x ?: width ?: values.getOrNull(2) ?: values.getOrNull(0) ?: 0,
            bottom = y ?: height ?: values.getOrNull(3) ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        )
    }

    private fun parseSpriteSize(body: String): SpriteSize? {
        val block = Regex("""(?is)\bsize\s*=\s*\{(.*?)\}""").find(body)?.groupValues?.getOrNull(1)
            ?: return null
        val x = parseAssignmentValue(block, "x")?.toDoubleOrNull()?.toInt()
        val y = parseAssignmentValue(block, "y")?.toDoubleOrNull()?.toInt()
        val width = parseAssignmentValue(block, "width")?.toDoubleOrNull()?.toInt()
        val height = parseAssignmentValue(block, "height")?.toDoubleOrNull()?.toInt()
        val values = NUMBER_REGEX.findAll(block).mapNotNull { it.value.toDoubleOrNull()?.toInt() }.toList()
        return SpriteSize(
            width = x ?: width ?: values.getOrNull(0) ?: 0,
            height = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        )
    }

    private fun parseAssignmentValue(body: String, key: String): String? {
        return Regex("""(?im)\b${Regex.escape(key)}\s*=\s*("[^"]+"|[^\s#{}]+)""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanToken)
    }

    private fun cleanToken(value: String): String {
        return value.trim().trim('"')
    }

    private fun String.parseParadoxInt(): Int? {
        return cleanToken(this).toDoubleOrNull()?.toInt()
    }

    private fun String?.parseParadoxBoolean(): Boolean? {
        return when (this?.let(::cleanToken)?.lowercase()) {
            "yes", "true", "1" -> true
            "no", "false", "0" -> false
            else -> null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ParadoxSpriteResolver::class.java)
        private val ICON_EXTENSIONS = setOf(".dds", ".tga", ".png")
        private val SPRITE_DEFINITION_PROPERTY_KEYS = setOf(
            "spritetype",
            "frameanimatedspritetype",
            "corneredtilespritetype",
            "maskedshieldtype",
            "textspritetype",
            "progressbartype",
            "circularprogressbartype",
            "piecharttype",
            "linecharttype",
            "quadtexturesprite"
        )
        private val SPRITE_BLOCK_START_REGEX = Regex(
            """(?i)\b(spriteType|frameAnimatedSpriteType|corneredTileSpriteType|maskedShieldType|textSpriteType|progressbartype|circularProgressBarType|pieChartType|LineChartType|quadTextureSprite)\s*=\s*\{"""
        )
        private val NAME_ASSIGNMENT_REGEX = Regex("""(?im)\bname\s*=\s*("[^"]+"|[^\s#]+)""")
        private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?""")
    }
}
