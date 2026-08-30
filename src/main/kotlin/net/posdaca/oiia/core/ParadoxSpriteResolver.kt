package net.posdaca.oiia.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import icu.windea.pls.lang.search.ParadoxDefinitionSearch
import icu.windea.pls.lang.search.ParadoxFilePathSearch
import icu.windea.pls.lang.util.ParadoxDefinitionManager
import icu.windea.pls.lang.util.ParadoxImageManager
import icu.windea.pls.script.psi.ParadoxScriptBlock
import icu.windea.pls.script.psi.ParadoxScriptFile
import icu.windea.pls.script.psi.ParadoxScriptProperty
import net.posdaca.oiia.core.files.ResourceFiles
import net.posdaca.oiia.core.PrefixIconLookup
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

    /**
     * Immutable cache snapshot swapped atomically, so readers never observe a half-cleared cache
     * and rebuilds cannot race with memoisation.
     */
    private data class SpriteCacheSnapshot(
        val rootsKey: String? = null,
        val stamp: Long = Long.MIN_VALUE,
        val iconFiles: Map<String, String> = emptyMap(),
        val spriteIconFiles: Map<String, String> = emptyMap(),
        val spriteDefinitionsByName: Map<String, SpriteDefinition> = emptyMap(),
        val iconPrefixLookup: PrefixIconLookup? = null,
        val resolvedSpriteInfos: Map<String, SpriteInfo?> = emptyMap(),
    )

    @Volatile private var cache = SpriteCacheSnapshot()
    @Volatile private var lastStampCheckMillis = Long.MIN_VALUE

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
        val snapshot = cache
        if (snapshot.resolvedSpriteInfos.containsKey(normalized)) return snapshot.resolvedSpriteInfos[normalized]
        val resolved = resolveSpriteInfoWithPls(normalized)
            ?: resolveSpriteInfoFromCache(normalized)
        synchronized(this) {
            if (cache === snapshot && cache.resolvedSpriteInfos.size < MAX_RESOLVED_SPRITE_INFOS) {
                cache = cache.copy(resolvedSpriteInfos = cache.resolvedSpriteInfos + (normalized to resolved))
            }
        }
        return resolved
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
        val textureFile = block?.propertyValue("textureFile")?.let { ParadoxGfxParser.cleanToken(it) }
        val textureFile1 = block?.propertyValue("textureFile1")?.let { ParadoxGfxParser.cleanToken(it) }
        val textureFile2 = block?.propertyValue("textureFile2")?.let { ParadoxGfxParser.cleanToken(it) }
        val effectFile = block?.propertyValue("effectFile")?.let { ParadoxGfxParser.cleanToken(it) }
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
                ?: ParadoxGfxParser.subtypeFromPropertyKey(propertyKey.text),
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
        if (key.lowercase() in ParadoxGfxParser.SPRITE_TYPE_KEYS) return true
        val type = runCatching { ParadoxDefinitionManager.getType(this) }.getOrNull()
        return type != null && type.contains("sprite", ignoreCase = true)
    }

    private fun ensureIconCache() {
        val now = System.currentTimeMillis()
        val rootsKey = currentRootsKey()
        val snapshot = cache
        if (snapshot.rootsKey == rootsKey && now - lastStampCheckMillis < STAMP_CHECK_INTERVAL_MS) return
        synchronized(this) {
            if (cache.rootsKey == rootsKey && now - lastStampCheckMillis < STAMP_CHECK_INTERVAL_MS) return
            lastStampCheckMillis = now
            val roots = ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
            val freshRootsKey = roots.joinToString("|") { ResourceFiles.normalizedKey(it) }
            val stamp = computeGfxStamp(roots)
            if (cache.rootsKey == freshRootsKey && cache.stamp == stamp) return

            val iconFiles = HashMap<String, String>()
            val spriteIconFiles = HashMap<String, String>()
            val spriteDefinitionsByName = HashMap<String, SpriteDefinition>()
            val spriteDefinitions = mutableListOf<SpriteDefinition>()
            for (root in roots) {
                cacheImages(root, iconFiles)
                cacheSpriteDefinitions(root, spriteDefinitions)
            }
            for (sprite in spriteDefinitions) {
                val iconPath = resolveTexturePathIn(sprite.textureFile, sprite.root, iconFiles)
                    ?: resolveTexturePathIn(sprite.textureFile1, sprite.root, iconFiles)
                    ?: resolveTexturePathIn(sprite.textureFile2, sprite.root, iconFiles)
                if (iconPath != null) putAliases(spriteIconFiles, sprite.name, iconPath)
                putDefinitionAliases(spriteDefinitionsByName, sprite)
            }

            cache = SpriteCacheSnapshot(
                rootsKey = freshRootsKey,
                stamp = stamp,
                iconFiles = iconFiles,
                spriteIconFiles = spriteIconFiles,
                spriteDefinitionsByName = spriteDefinitionsByName,
                iconPrefixLookup = PrefixIconLookup(iconFiles),
            )
            LOG.info("GUI sprite cache rebuilt: roots=${roots.size} images=${iconFiles.size} spriteImages=${spriteIconFiles.size} definitions=${spriteDefinitionsByName.size}")
        }
    }

    private fun currentRootsKey(): String {
        return ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
            .joinToString("|") { ResourceFiles.normalizedKey(it) }
    }

    /**
     * Cheap fingerprint over the `.gfx` files backing the cache, so edits to those files
     * invalidate the cache within [STAMP_CHECK_INTERVAL_MS] instead of living until roots change.
     */
    private fun computeGfxStamp(roots: List<Path>): Long {
        var stamp = roots.size.toLong()
        for (root in roots) {
            val files = ResourceFiles.listFiles(listOf(root), listOf("gfx", "interface"), setOf(".gfx"), maxDepth = 6)
            for (path in files.sortedBy { ResourceFiles.normalizedKey(it) }) {
                stamp = stamp * 31 + ResourceFiles.fileStamp(path)
            }
        }
        return stamp
    }

    private fun cacheImages(root: Path, iconFiles: MutableMap<String, String>) {
        val files = ResourceFiles.listFiles(listOf(root), listOf("gfx"), ParadoxGfxParser.ICON_EXTENSIONS, maxDepth = 6)
        for (file in files) cacheIconFile(root, file, iconFiles)
    }


    private fun cacheSpriteDefinitions(root: Path, spriteDefinitions: MutableList<SpriteDefinition>) {
        val gfxFiles = ResourceFiles.listFiles(listOf(root), listOf("gfx", "interface"), setOf(".gfx"), maxDepth = 6)
        for (path in gfxFiles) {
            // `.gfx` is a Paradox-script extension, so the file parses to ParadoxScriptFile PSI;
            // per-file read actions keep write actions interleavable during the bulk scan.
            val vf = ResourceFiles.toVirtualFile(path) ?: continue
            val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
                PsiManager.getInstance(project).findFile(vf)
            } as? ParadoxScriptFile ?: continue
            val rootBlock = psiFile.block ?: continue
            collectSpriteDefinitions(rootBlock.propertyList, root, spriteDefinitions)
        }
    }

    private fun collectSpriteDefinitions(
        properties: List<ParadoxScriptProperty>,
        root: Path,
        spriteDefinitions: MutableList<SpriteDefinition>
    ) {
        for (prop in properties) {
            val key = prop.propertyKey.text.lowercase()
            val inner = prop.block
            when {
                inner == null -> continue
                key in ParadoxGfxParser.SPRITE_TYPE_KEYS -> spriteDefinition(prop, root)?.let { spriteDefinitions.add(it) }
                else -> collectSpriteDefinitions(inner.propertyList, root, spriteDefinitions)
            }
        }
    }

    private fun spriteDefinition(prop: ParadoxScriptProperty, root: Path): SpriteDefinition? {
        val block = prop.block ?: return null
        val name = block.propertyValue("name")?.let { ParadoxGfxParser.cleanToken(it) }?.takeIf { it.isNotBlank() }
            ?: return null
        val texture = block.propertyValue("textureFile")?.let { ParadoxGfxParser.cleanToken(it) }
        val texture1 = block.propertyValue("textureFile1")?.let { ParadoxGfxParser.cleanToken(it) }
        val texture2 = block.propertyValue("textureFile2")?.let { ParadoxGfxParser.cleanToken(it) }
        if (texture == null && texture1 == null && texture2 == null) return null
        return SpriteDefinition(
            name = name,
            textureFile = texture,
            root = root,
            subtype = ParadoxGfxParser.subtypeFromPropertyKey(prop.propertyKey.text),
            borderSize = block.propertyBlock("borderSize")?.let(::parseInsets),
            size = block.propertyBlock("size")?.let(::parseSize),
            tilingCenter = block.propertyValue("tilingCenter").parseParadoxBoolean() ?: false,
            noOfFrames = block.propertyValue("noOfFrames")?.parseParadoxInt(),
            defaultFrame = block.propertyValue("default_frame")?.parseParadoxInt(),
            textureFile1 = texture1,
            textureFile2 = texture2,
            effectFile = block.propertyValue("effectFile")?.let { ParadoxGfxParser.cleanToken(it) },
            horizontal = block.propertyValue("horizontal").parseParadoxBoolean(),
            steps = block.propertyValue("steps")?.parseParadoxInt(),
            rotation = block.propertyValue("rotation")?.parseParadoxInt(),
            amount = block.propertyValue("amount")?.parseParadoxInt(),
            alwaysTransparent = block.propertyValue("alwaystransparent").parseParadoxBoolean()
                ?: block.propertyValue("allwaystransparent").parseParadoxBoolean()
        )
    }

    private fun cacheIconFile(root: Path, file: Path, iconFiles: MutableMap<String, String>) {
        val absolutePath = file.toAbsolutePath().normalize().toString()
        putAliases(iconFiles, file.fileName.toString().substringBeforeLast("."), absolutePath)
        runCatching {
            putAliases(iconFiles, root.relativize(file).toString().substringBeforeLast("."), absolutePath)
        }
    }

    private fun findCachedIconPath(name: String, snapshot: SpriteCacheSnapshot): String? {
        lookupCachedPath(snapshot.iconFiles, name)?.let { return it }
        return snapshot.iconPrefixLookup?.find(ParadoxGfxParser.spriteAliases(name))
    }

    private fun lookupCachedPath(cache: Map<String, String>, name: String): String? {
        for (alias in ParadoxGfxParser.spriteAliases(name)) {
            cache[alias]?.let { return it }
        }
        return null
    }

    private fun putAliases(cache: MutableMap<String, String>, name: String, path: String) {
        for (alias in ParadoxGfxParser.spriteAliases(name)) {
            cache.putIfAbsent(alias, path)
        }
    }

    private fun putAliases(cache: MutableMap<String, SpriteDefinition>, name: String, definition: SpriteDefinition) {
        for (alias in ParadoxGfxParser.spriteAliases(name)) {
            cache.putIfAbsent(alias, definition)
        }
    }

    private fun putDefinitionAliases(cache: MutableMap<String, SpriteDefinition>, definition: SpriteDefinition) {
        putAliases(cache, definition.name, definition)
        definition.textureFile?.let { putAliases(cache, it, definition) }
    }

    private fun resolveSpriteInfoFromCache(name: String): SpriteInfo? {
        ensureIconCache()
        val snapshot = cache
        val definition = lookupSpriteDefinition(name, snapshot.spriteDefinitionsByName)
        val imagePath1 = resolveTexturePathIn(definition?.textureFile1, definition?.root, snapshot.iconFiles)
        val imagePath2 = resolveTexturePathIn(definition?.textureFile2, definition?.root, snapshot.iconFiles)
        val path = resolveTexturePathIn(definition?.textureFile, definition?.root, snapshot.iconFiles)
            ?: lookupCachedPath(snapshot.spriteIconFiles, name)
            ?: findCachedIconPath(name, snapshot)
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
            LOG.info("GUI sprite resolved by image fallback without gfx definition: name=$name aliases=${ParadoxGfxParser.spriteAliases(name)} image=${info.primaryImagePath}")
        } else {
            LOG.info("GUI sprite resolved by cache: name=$name type=${definition.subtype} root=${definition.root} texture=${definition.textureFile} image=${info.primaryImagePath} frames=${info.noOfFrames} size=${info.size} effect=${info.effectFile}")
        }
        return info
    }

    private fun lookupSpriteDefinition(name: String, definitionsByName: Map<String, SpriteDefinition>): SpriteDefinition? {
        for (alias in ParadoxGfxParser.spriteAliases(name)) {
            definitionsByName[alias]?.let { return it }
        }
        return null
    }

    private fun resolveGamePath(root: Path, rawPath: String): Path {
        val normalizedPath = ParadoxGfxParser.cleanToken(rawPath).replace('\\', '/')
        val path = runCatching { Path.of(normalizedPath) }.getOrNull()
        return if (path != null && path.isAbsolute) path.normalize() else root.resolve(normalizedPath).normalize()
    }

    private fun resolveTexturePath(rawPath: String?, rootHint: Path?, ensureCache: Boolean = true): String? {
        val cleanPath = rawPath?.let { ParadoxGfxParser.cleanToken(it) }?.takeIf { it.isNotBlank() } ?: return null
        if (ensureCache) ensureIconCache()
        return resolveTexturePathIn(cleanPath, rootHint, cache.iconFiles)
    }

    private fun resolveTexturePathIn(rawPath: String?, rootHint: Path?, iconFiles: Map<String, String>): String? {
        val cleanPath = rawPath?.let { ParadoxGfxParser.cleanToken(it) }?.takeIf { it.isNotBlank() } ?: return null
        if (rootHint != null) {
            val directPath = resolveGamePath(rootHint, cleanPath)
            if (directPath.isRegularFile()) return directPath.toAbsolutePath().normalize().toString()
        }
        val absolute = runCatching { Path.of(cleanPath) }.getOrNull()
        if (absolute != null && absolute.isAbsolute && absolute.isRegularFile()) {
            return absolute.toAbsolutePath().normalize().toString()
        }
        resolveTexturePathWithPls(cleanPath)?.let { return it }
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
        return ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
            .firstOrNull { root -> file.startsWith(root.toAbsolutePath().normalize()) }
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
        val dimensions = block.dimensionValues()
        return SpriteInsets(
            left = dimensions.horizontalOrFirst,
            top = dimensions.verticalOrSecond,
            right = dimensions.horizontalOrThird,
            bottom = dimensions.verticalOrFourth
        )
    }

    private fun parseSize(block: icu.windea.pls.script.psi.ParadoxScriptBlock): SpriteSize {
        val dimensions = block.dimensionValues()
        return SpriteSize(
            width = dimensions.horizontalOrFirst,
            height = dimensions.verticalOrSecond
        )
    }

    private fun icu.windea.pls.script.psi.ParadoxScriptBlock.dimensionValues(): DimensionValues {
        return DimensionValues(
            x = propertyValue("x")?.toDoubleOrNull()?.toInt(),
            y = propertyValue("y")?.toDoubleOrNull()?.toInt(),
            width = propertyValue("width")?.toDoubleOrNull()?.toInt(),
            height = propertyValue("height")?.toDoubleOrNull()?.toInt(),
            values = valueList.mapNotNull { it.text.trim().trim('"').toDoubleOrNull()?.toInt() }
        )
    }

    private data class DimensionValues(
        val x: Int?,
        val y: Int?,
        val width: Int?,
        val height: Int?,
        val values: List<Int>
    ) {
        val horizontalOrFirst: Int
            get() = x ?: width ?: values.getOrNull(0) ?: 0
        val verticalOrSecond: Int
            get() = y ?: height ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
        val horizontalOrThird: Int
            get() = x ?: width ?: values.getOrNull(2) ?: values.getOrNull(0) ?: 0
        val verticalOrFourth: Int
            get() = y ?: height ?: values.getOrNull(3) ?: values.getOrNull(1) ?: values.getOrNull(0) ?: 0
    }

    companion object {
        private val LOG = Logger.getInstance(ParadoxSpriteResolver::class.java)
        private const val STAMP_CHECK_INTERVAL_MS = 2000L
        private const val MAX_RESOLVED_SPRITE_INFOS = 4096
    }
}


