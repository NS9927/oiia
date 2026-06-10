package net.posdaca.oiia.map

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ImageUtil
import net.posdaca.oiia.core.HoI4ResourceRoots
import net.posdaca.oiia.core.ParadoxLocalisationPreference
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class MapPreviewService(private val project: Project) {

    fun loadMap(onProgress: (MapLoadStep) -> Unit = {}): MapLoadResult {
        onProgress(MapLoadStep.LOCATING)
        val roots = getResourceRoots()
        val provincesPath = findFirstExisting(roots, PROVINCES_PATH)
            ?: return MapLoadResult.Missing(roots)
        val definitionPath = findFirstExisting(roots, DEFINITION_PATH)

        return try {
            onProgress(MapLoadStep.READING_PROVINCES)
            val image = ImageIO.read(provincesPath.toFile())
                ?: return MapLoadResult.Failed("Unable to read ${provincesPath.name}")
            onProgress(MapLoadStep.LOCALISATION)
            val localisationPaths = findLocFilePaths(roots)
            val localisations = loadLocalisations(localisationPaths, roots)
            onProgress(MapLoadStep.DEFINITIONS)
            val provinces = definitionPath?.let { parseDefinitionCsv(it) } ?: emptyMap()
            val provinceById = provinces.values.associateBy { it.id }
            onProgress(MapLoadStep.STATES)
            val states = loadStates(roots, localisations)
            val stateByProvinceId = buildStateByProvinceId(states)
            onProgress(MapLoadStep.STRATEGIC_REGIONS)
            val strategicRegions = loadStrategicRegions(roots, localisations)
            val strategicRegionByProvinceId = buildStrategicRegionByProvinceId(strategicRegions)
            onProgress(MapLoadStep.COUNTRIES)
            val countryDefinitions = loadCountryDefinitions(roots)
            val countryHistories = loadCountryHistories(roots)
            val countries = buildCountries(states, countryDefinitions, countryHistories, localisations)
            onProgress(MapLoadStep.INDEX)
            onProgress(MapLoadStep.IMAGES)
            val renderData = buildRenderData(image, provinces, stateByProvinceId, strategicRegionByProvinceId, countryDefinitions)
            val statePaths = states.map { it.path }.distinct()
            val countryPaths = findCountryFiles(roots, countryDefinitions)
            val strategicRegionPaths = strategicRegions.map { it.path }.distinct()
            MapLoadResult.Loaded(
                LoadedMapData(
                    provincesImage = image,
                    stateImage = renderData.stateImage,
                    countryImage = renderData.countryImage,
                    strategicRegionImage = renderData.strategicRegionImage,
                    borderImages = renderData.borderImages,
                    pixelIndex = renderData.pixelIndex,
                    provinceByColor = provinces,
                    provinceById = provinceById,
                    stateById = states.associateBy { it.id },
                    stateByProvinceId = stateByProvinceId,
                    countryByTag = countries,
                    strategicRegionById = strategicRegions.associateBy { it.id },
                    strategicRegionByProvinceId = strategicRegionByProvinceId,
                    provincesPath = provincesPath,
                    definitionPath = definitionPath,
                    statePaths = statePaths,
                    countryPaths = countryPaths,
                    strategicRegionPaths = strategicRegionPaths,
                    localisationPaths = localisationPaths,
                    localisations = localisations,
                    sourceStamp = computeSourceStamp(
                        provincesPath,
                        definitionPath,
                        statePaths,
                        countryPaths,
                        strategicRegionPaths,
                        localisationPaths
                    )
                )
            )
        } catch (e: Exception) {
            LOG.warn("Map preview load failed", e)
            MapLoadResult.Failed(e.message ?: "Map preview load failed")
        }
    }

    fun currentSourceStamp(data: LoadedMapData?): Long {
        if (data == null) return 0L
        val roots = getResourceRoots()
        val provincesPath = findFirstExisting(roots, PROVINCES_PATH) ?: return 0L
        val definitionPath = findFirstExisting(roots, DEFINITION_PATH)
        val statePaths = data.statePaths.takeIf { it.isNotEmpty() } ?: findStateFiles(roots)
        val countryPaths = data.countryPaths.takeIf { it.isNotEmpty() } ?: findCountryFiles(roots)
        val strategicRegionPaths = data.strategicRegionPaths.takeIf { it.isNotEmpty() } ?: findStrategicRegionFiles(roots)
        val localisationPaths = data.localisationPaths.takeIf { it.isNotEmpty() } ?: findLocFilePaths(roots)
        return computeSourceStamp(provincesPath, definitionPath, statePaths, countryPaths, strategicRegionPaths, localisationPaths)
    }

    fun isMapSourcePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return normalized.endsWith("/$PROVINCES_PATH") ||
                normalized.endsWith("/$DEFINITION_PATH") ||
                normalized.contains("/$STATES_PATH/") ||
                normalized.contains("/$STRATEGIC_REGIONS_PATH/") ||
                normalized.contains("/$COUNTRY_TAGS_PATH/") ||
                normalized.contains("/$COUNTRIES_PATH/") ||
                normalized.contains("/localisation/") ||
                normalized.contains("/localization/")
    }

    private fun getResourceRoots(): List<Path> {
        return HoI4ResourceRoots.resourceRoots(project, projectFirst = true, gameFirst = false)
    }

    private fun findFirstExisting(roots: List<Path>, relativePath: String): Path? {
        for (root in roots) {
            val path = root.resolve(relativePath)
            if (path.isRegularFile()) return path.toAbsolutePath().normalize()
        }
        return null
    }

    private fun parseDefinitionCsv(path: Path): Map<Int, ProvinceInfo> {
        val result = mutableMapOf<Int, ProvinceInfo>()
        for ((index, rawLine) in Files.readAllLines(path).withIndex()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) continue

            val parts = line.split(';')
            if (parts.size < 4) continue
            val id = parts[0].trim().toIntOrNull() ?: continue
            val red = parts[1].trim().toIntOrNull() ?: continue
            val green = parts[2].trim().toIntOrNull() ?: continue
            val blue = parts[3].trim().toIntOrNull() ?: continue
            val rgb = (red shl 16) or (green shl 8) or blue

            result[rgb] = ProvinceInfo(
                id = id,
                rgb = rgb,
                type = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
                coastal = parts.getOrNull(5)?.trim()?.let { it.equals("true", ignoreCase = true) || it == "1" },
                terrain = parts.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() },
                continent = parts.getOrNull(7)?.trim()?.toIntOrNull(),
                localizedName = null,
                sourceLine = index + 1
            )
        }
        return result
    }

    private fun loadStates(roots: List<Path>, localisations: Map<String, String>): List<StateInfo> {
        val byId = linkedMapOf<Int, StateInfo>()
        for (path in findStateFiles(roots)) {
            val state = parseStateFile(path, localisations) ?: continue
            byId.putIfAbsent(state.id, state)
        }
        return byId.values.toList()
    }

    private fun findStateFiles(roots: List<Path>): List<Path> {
        val files = mutableListOf<Path>()
        for (root in roots) {
            val dir = root.resolve(STATES_PATH)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.toString().endsWith(".txt", ignoreCase = true) }
                    .forEach { files.add(it.toAbsolutePath().normalize()) }
            }
        }
        return files.distinctBy { HoI4ResourceRoots.normalizedKey(it) }
    }

    private fun parseStateFile(path: Path, localisations: Map<String, String>): StateInfo? {
        return try {
            val text = stripComments(Files.readString(path))
            val stateBlock = findNamedBlock(text, "state") ?: text
            val id = findInt(stateBlock, "id") ?: return null
            val history = findBlock(stateBlock, "history")
            val buildings = history?.let { findBlock(it, "buildings") }
            val stateName = findValue(stateBlock, "name")
            StateInfo(
                id = id,
                name = stateName,
                localizedName = resolveLocalisation(localisations, stateName, "STATE_$id"),
                owner = history?.let { findValue(it, "owner") },
                category = findValue(stateBlock, "state_category"),
                manpower = findInt(stateBlock, "manpower"),
                provinces = parseIntList(findBlock(stateBlock, "provinces")),
                cores = history?.let { findValues(it, "add_core_of") } ?: emptyList(),
                resources = parseResourceBlock(findBlock(stateBlock, "resources")),
                stateBuildings = parseStateBuildings(buildings),
                provinceBuildings = parseProvinceBuildings(buildings),
                victoryPoints = history?.let { parseVictoryPoints(it) } ?: emptyMap(),
                path = path.toAbsolutePath().normalize()
            )
        } catch (e: Exception) {
            LOG.warn("State file parse failed: $path", e)
            null
        }
    }

    private fun buildStateByProvinceId(states: List<StateInfo>): Map<Int, StateInfo> {
        val result = mutableMapOf<Int, StateInfo>()
        for (state in states) {
            for (provinceId in state.provinces) {
                result.putIfAbsent(provinceId, state)
            }
        }
        return result
    }

    private fun loadStrategicRegions(roots: List<Path>, localisations: Map<String, String>): List<StrategicRegionInfo> {
        val byId = linkedMapOf<Int, StrategicRegionInfo>()
        for (path in findStrategicRegionFiles(roots)) {
            val region = parseStrategicRegionFile(path, localisations) ?: continue
            byId.putIfAbsent(region.id, region)
        }
        return byId.values.toList()
    }

    private fun findStrategicRegionFiles(roots: List<Path>): List<Path> {
        val files = mutableListOf<Path>()
        for (root in roots) {
            val dir = root.resolve(STRATEGIC_REGIONS_PATH)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.toString().endsWith(".txt", ignoreCase = true) }
                    .forEach { files.add(it.toAbsolutePath().normalize()) }
            }
        }
        return files.distinctBy { HoI4ResourceRoots.normalizedKey(it) }
    }

    private fun parseStrategicRegionFile(path: Path, localisations: Map<String, String>): StrategicRegionInfo? {
        return try {
            val text = stripComments(Files.readString(path))
            val id = findInt(text, "id") ?: path.fileName.toString().substringBefore('.').toIntOrNull() ?: return null
            val name = findValue(text, "name")
            StrategicRegionInfo(
                id = id,
                name = name,
                localizedName = resolveLocalisation(localisations, name, "STRATEGICREGION_$id"),
                provinces = parseIntList(findBlock(text, "provinces")),
                navalTerrain = findValue(text, "naval_terrain"),
                weather = parseWeatherBlock(findBlock(text, "weather")),
                path = path.toAbsolutePath().normalize()
            )
        } catch (e: Exception) {
            LOG.warn("Strategic region file parse failed: $path", e)
            null
        }
    }

    private fun buildStrategicRegionByProvinceId(regions: List<StrategicRegionInfo>): Map<Int, StrategicRegionInfo> {
        val result = mutableMapOf<Int, StrategicRegionInfo>()
        for (region in regions) {
            for (provinceId in region.provinces) {
                result.putIfAbsent(provinceId, region)
            }
        }
        return result
    }

    private fun buildCountries(
        states: List<StateInfo>,
        countryDefinitions: Map<String, CountryDefinition>,
        countryHistories: Map<String, Path>,
        localisations: Map<String, String>
    ): Map<String, CountryInfo> {
        return states
            .filter { !it.owner.isNullOrBlank() }
            .groupBy { it.owner!!.uppercase() }
            .mapValues { (tag, ownedStates) ->
                val definition = countryDefinitions[tag]
                CountryInfo(
                    tag = tag,
                    mapKey = mapCountryKey(tag),
                    localizedName = resolveCountryName(localisations, tag),
                    color = definition?.color,
                    definitionPath = definition?.definitionPath,
                    historyPath = countryHistories[tag],
                    colorSourcePath = definition?.colorSourcePath,
                    stateIds = ownedStates.map { it.id }.sorted(),
                    provinceIds = ownedStates.flatMap { it.provinces }.distinct().sorted()
                )
            }
    }

    private data class CountryDefinition(
        val tag: String,
        val definitionPath: Path?,
        val color: Int?,
        val colorSourcePath: Path?
    )

    private fun loadCountryDefinitions(roots: List<Path>): Map<String, CountryDefinition> {
        val result = linkedMapOf<String, CountryDefinition>()
        for ((tag, relativePath) in loadCountryTagPaths(roots)) {
            val path = findFirstExisting(roots, relativePath) ?: continue
            val color = parseCountryColor(path)
            result.putIfAbsent(
                tag,
                CountryDefinition(
                    tag = tag,
                    definitionPath = path,
                    color = color,
                    colorSourcePath = if (color != null) path else null
                )
            )
        }
        for ((tag, overrideColor) in loadCountryColorOverrides(roots)) {
            val current = result[tag]
            if (current != null) {
                result[tag] = current.copy(color = overrideColor.color, colorSourcePath = overrideColor.path)
            } else {
                result[tag] = CountryDefinition(
                    tag = tag,
                    definitionPath = null,
                    color = overrideColor.color,
                    colorSourcePath = overrideColor.path
                )
            }
        }
        return result
    }

    private fun loadCountryHistories(roots: List<Path>): Map<String, Path> {
        val result = linkedMapOf<String, Path>()
        for (root in roots) {
            val dir = root.resolve(COUNTRY_HISTORY_PATH)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.toString().endsWith(".txt", ignoreCase = true) }
                    .forEach { path ->
                        val tag = path.fileName.toString().substringBefore(" -").substringBefore('.').uppercase()
                        if (tag.matches(COUNTRY_TAG_REGEX)) {
                            result.putIfAbsent(tag, path.toAbsolutePath().normalize())
                        }
                    }
            }
        }
        return result
    }

    private fun loadCountryTagPaths(roots: List<Path>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (root in roots) {
            val dir = root.resolve(COUNTRY_TAGS_PATH)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.toString().endsWith(".txt", ignoreCase = true) }
                    .forEach { path ->
                        for ((tag, relativePath) in parseCountryTagFile(path)) {
                            result.putIfAbsent(tag, relativePath)
                        }
                    }
            }
        }
        return result
    }

    private fun parseCountryTagFile(path: Path): Map<String, String> {
        return try {
            val text = stripComments(Files.readString(path))
            Regex("""(?im)([A-Z0-9_]{3})\s*=\s*(?:"([^"]+)"|([^\s#]+))""")
                .findAll(text)
                .associate {
                    val value = it.groupValues[2].ifEmpty { it.groupValues[3] }
                    it.groupValues[1].uppercase() to normalizeCountryPath(value)
                }
        } catch (e: Exception) {
            LOG.warn("Country tag file parse failed: $path", e)
            emptyMap()
        }
    }

    private fun normalizeCountryPath(path: String): String {
        val normalized = path.trim().trim('"').replace('\\', '/').removePrefix("common/")
        return if (normalized.startsWith("countries/")) {
            "common/$normalized"
        } else {
            "$COUNTRIES_PATH/$normalized"
        }
    }

    private fun parseCountryColor(path: Path): Int? {
        return try {
            val text = stripComments(Files.readString(path))
            parseColorValue(text)
        } catch (e: Exception) {
            LOG.warn("Country color parse failed: $path", e)
            null
        }
    }

    private data class CountryColorOverride(val path: Path, val color: Int)

    private fun loadCountryColorOverrides(roots: List<Path>): Map<String, CountryColorOverride> {
        val result = linkedMapOf<String, CountryColorOverride>()
        for (path in findCountryColorOverrideFiles(roots)) {
            for ((tag, color) in parseCountryColorOverrideFile(path)) {
                result.putIfAbsent(tag, CountryColorOverride(path, color))
            }
        }
        return result
    }

    private fun parseCountryColorOverrideFile(path: Path): Map<String, Int> {
        return try {
            val text = stripComments(Files.readString(path))
            val result = linkedMapOf<String, Int>()
            COUNTRY_COLOR_ASSIGNMENT_REGEX.findAll(text).forEach { match ->
                val tag = match.groupValues[1].uppercase()
                val block = extractColorBlockAfterEquals(text, match.range.last + 1) ?: return@forEach
                val color = parseColorValue(block) ?: parseColorBlock(block) ?: return@forEach
                result[tag] = color
            }
            result
        } catch (e: Exception) {
            LOG.warn("Country color override parse failed: $path", e)
            emptyMap()
        }
    }

    private fun parseColorValue(text: String): Int? {
        val match = Regex("""(?i)(?:^|\s)color\s*=\s*(?:rgb\s*)?\{([^{}]*)}""").find(text) ?: return null
        return parseColorBlock(match.groupValues[1])
    }

    private fun parseColorBlock(block: String?): Int? {
        if (block.isNullOrBlank()) return null
        val values = Regex("""-?\d+""")
            .findAll(block)
            .mapNotNull { it.value.toIntOrNull() }
            .take(3)
            .toList()
        if (values.size < 3) return null
        val red = values[0].coerceIn(0, 255)
        val green = values[1].coerceIn(0, 255)
        val blue = values[2].coerceIn(0, 255)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun findCountryFiles(
        roots: List<Path>,
        countryDefinitions: Map<String, CountryDefinition> = loadCountryDefinitions(roots)
    ): List<Path> {
        val countryDefinitionPaths = countryDefinitions.values
            .flatMap { listOfNotNull(it.definitionPath, it.colorSourcePath) }
            .distinctBy { HoI4ResourceRoots.normalizedKey(it) }
        val overridePaths = findCountryColorOverrideFiles(roots)
        return (countryDefinitionPaths + overridePaths).distinctBy { HoI4ResourceRoots.normalizedKey(it) }
    }

    private fun findCountryColorOverrideFiles(roots: List<Path>): List<Path> {
        return roots.flatMap { root ->
            COUNTRY_COLOR_OVERRIDE_PATHS.map { root.resolve(it) }
                .filter { it.isRegularFile() }
                .map { it.toAbsolutePath().normalize() }
        }.distinctBy { HoI4ResourceRoots.normalizedKey(it) }
    }

    private data class MapRenderData(
        val pixelIndex: MapPixelIndex,
        val stateImage: BufferedImage?,
        val countryImage: BufferedImage?,
        val strategicRegionImage: BufferedImage?,
        val borderImages: Map<MapPreviewMode, BufferedImage>
    )

    private fun buildRenderData(
        provincesImage: BufferedImage,
        provinceByColor: Map<Int, ProvinceInfo>,
        stateByProvinceId: Map<Int, StateInfo>,
        strategicRegionByProvinceId: Map<Int, StrategicRegionInfo>,
        countryDefinitions: Map<String, CountryDefinition>
    ): MapRenderData {
        val width = provincesImage.width
        val height = provincesImage.height
        val size = width * height
        val provinceKeys = IntArray(size) { UNKNOWN_KEY }
        val stateKeys = IntArray(size) { UNKNOWN_KEY }
        val countryKeys = IntArray(size) { UNKNOWN_KEY }
        val strategicRegionKeys = IntArray(size) { UNKNOWN_KEY }
        val provinceBounds = mutableMapOf<Int, MutablePixelBounds>()
        val stateBounds = mutableMapOf<Int, MutablePixelBounds>()
        val countryBounds = mutableMapOf<Int, MutablePixelBounds>()
        val strategicRegionBounds = mutableMapOf<Int, MutablePixelBounds>()
        val stateImage = if (stateByProvinceId.isEmpty()) null else ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_RGB)
        val countryImage = if (stateByProvinceId.isEmpty()) null else ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_RGB)
        val strategicRegionImage = if (strategicRegionByProvinceId.isEmpty()) {
            null
        } else {
            ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_RGB)
        }
        val states = stateByProvinceId.values.distinctBy { it.id }
        val stateColorById = states.associate { it.id to colorForId(it.id) }
        val countryColorByStateId = states.associate { state ->
            val countryColor = countryDefinitions[state.owner?.uppercase()]?.color ?: colorForKey(state.owner)
            state.id to renderCountryMapColor(countryColor)
        }
        val strategicRegionColorById = strategicRegionByProvinceId.values
            .distinctBy { it.id }
            .associate { it.id to colorForId(it.id) }

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val index = rowOffset + x
                val rgb = provincesImage.getRGB(x, y) and RGB_MASK
                val province = provinceByColor[rgb]
                val state = province?.let { stateByProvinceId[it.id] }
                val strategicRegion = province?.let { strategicRegionByProvinceId[it.id] }

                stateImage?.setRGB(x, y, state?.let { stateColorById[it.id] } ?: rgb)
                countryImage?.setRGB(x, y, state?.let { countryColorByStateId[it.id] } ?: rgb)
                strategicRegionImage?.setRGB(x, y, strategicRegion?.let { strategicRegionColorById[it.id] } ?: rgb)
                if (province == null) continue

                val provinceKey = province.id
                val stateKey = state?.id ?: UNKNOWN_KEY
                val countryKey = state?.owner?.let { mapCountryKey(it) } ?: UNKNOWN_KEY
                val strategicRegionKey = strategicRegion?.id ?: UNKNOWN_KEY

                provinceKeys[index] = provinceKey
                stateKeys[index] = stateKey
                countryKeys[index] = countryKey
                strategicRegionKeys[index] = strategicRegionKey
                provinceBounds.getOrPut(provinceKey) { MutablePixelBounds() }.include(x, y)
                if (stateKey != UNKNOWN_KEY) stateBounds.getOrPut(stateKey) { MutablePixelBounds() }.include(x, y)
                if (countryKey != UNKNOWN_KEY) countryBounds.getOrPut(countryKey) { MutablePixelBounds() }.include(x, y)
                if (strategicRegionKey != UNKNOWN_KEY) {
                    strategicRegionBounds.getOrPut(strategicRegionKey) { MutablePixelBounds() }.include(x, y)
                }
            }
        }

        val pixelIndex = MapPixelIndex(
            width = width,
            provinceKeys = provinceKeys,
            stateKeys = stateKeys,
            countryKeys = countryKeys,
            strategicRegionKeys = strategicRegionKeys,
            provinceBounds = provinceBounds.mapValues { it.value.toBounds() },
            stateBounds = stateBounds.mapValues { it.value.toBounds() },
            countryBounds = countryBounds.mapValues { it.value.toBounds() },
            strategicRegionBounds = strategicRegionBounds.mapValues { it.value.toBounds() }
        )
        return MapRenderData(
            pixelIndex = pixelIndex,
            stateImage = stateImage,
            countryImage = countryImage,
            strategicRegionImage = strategicRegionImage,
            borderImages = buildBorderImages(pixelIndex, width, height)
        )
    }

    private class MutablePixelBounds {
        private var minX = Int.MAX_VALUE
        private var minY = Int.MAX_VALUE
        private var maxX = Int.MIN_VALUE
        private var maxY = Int.MIN_VALUE

        fun include(x: Int, y: Int) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        fun toBounds(): PixelBounds = PixelBounds(minX, minY, maxX, maxY)
    }

    private fun buildBorderImages(pixelIndex: MapPixelIndex, width: Int, height: Int): Map<MapPreviewMode, BufferedImage> {
        val provinceImage = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val stateImage = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val countryImage = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val strategicRegionImage = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val border = 0xAA000000.toInt()
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val leftIndex = rowOffset + if (x == 0) width - 1 else x - 1
                val rightIndex = rowOffset + if (x + 1 == width) 0 else x + 1
                val upIndex = if (y > 0) rowOffset - width + x else -1
                val downIndex = if (y + 1 < height) rowOffset + width + x else -1
                setBorderPixel(pixelIndex.provinceKeys, provinceImage, rowOffset + x, x, y, leftIndex, rightIndex, upIndex, downIndex, border)
                setBorderPixel(pixelIndex.stateKeys, stateImage, rowOffset + x, x, y, leftIndex, rightIndex, upIndex, downIndex, border)
                setBorderPixel(pixelIndex.countryKeys, countryImage, rowOffset + x, x, y, leftIndex, rightIndex, upIndex, downIndex, border)
                setBorderPixel(
                    pixelIndex.strategicRegionKeys,
                    strategicRegionImage,
                    rowOffset + x,
                    x,
                    y,
                    leftIndex,
                    rightIndex,
                    upIndex,
                    downIndex,
                    border
                )
            }
        }
        return mapOf(
            MapPreviewMode.PROVINCE to provinceImage,
            MapPreviewMode.STATE to stateImage,
            MapPreviewMode.COUNTRY to countryImage,
            MapPreviewMode.STRATEGIC_REGION to strategicRegionImage
        )
    }

    private fun setBorderPixel(
        keys: IntArray,
        image: BufferedImage,
        index: Int,
        x: Int,
        y: Int,
        leftIndex: Int,
        rightIndex: Int,
        upIndex: Int,
        downIndex: Int,
        border: Int
    ) {
        val key = keys[index]
        if (key == UNKNOWN_KEY) return
        if (keys[leftIndex] != key ||
            keys[rightIndex] != key ||
            upIndex >= 0 && keys[upIndex] != key ||
            downIndex >= 0 && keys[downIndex] != key
        ) {
            image.setRGB(x, y, border)
        }
    }

    private fun colorForId(id: Int): Int {
        val hash = id * 1103515245 + 12345
        return brightenHashColor(hash)
    }

    private fun colorForKey(key: String?): Int {
        if (key.isNullOrBlank()) return 0x555555
        return brightenHashColor(key.uppercase().hashCode())
    }

    private fun renderCountryMapColor(rgb: Int): Int {
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        val hsb = Color.RGBtoHSB(red, green, blue, null)
        val saturation = (hsb[1] * 0.72f).coerceIn(0.18f, 0.70f)
        val brightness = (0.30f + hsb[2] * 0.48f).coerceIn(0.34f, 0.76f)
        val shaded = Color.HSBtoRGB(hsb[0], saturation, brightness) and RGB_MASK
        val sr = shaded shr 16 and 0xFF
        val sg = shaded shr 8 and 0xFF
        val sb = shaded and 0xFF
        val nr = ((sr * 0.82 + sg * 0.04 + sb * 0.02 + 28).toInt()).coerceIn(0, 255)
        val ng = ((sg * 0.82 + sr * 0.04 + sb * 0.03 + 24).toInt()).coerceIn(0, 255)
        val nb = ((sb * 0.80 + sr * 0.03 + sg * 0.05 + 22).toInt()).coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    private fun brightenHashColor(hash: Int): Int {
        val red = 70 + (hash ushr 16 and 0x7F)
        val green = 70 + (hash ushr 8 and 0x7F)
        val blue = 70 + (hash and 0x7F)
        return (red shl 16) or (green shl 8) or blue
    }

    private fun stripComments(text: String): String {
        val result = StringBuilder(text.length)
        var inQuote = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> {
                    result.append(char)
                    escaped = false
                }

                char == '\\' -> {
                    result.append(char)
                    escaped = true
                }

                char == '"' -> {
                    result.append(char)
                    inQuote = !inQuote
                }

                char == '#' && !inQuote -> {
                    while (index < text.length && text[index] != '\n') index++
                    if (index < text.length) result.append('\n')
                }

                else -> result.append(char)
            }
            index++
        }
        return result.toString()
    }

    private fun findNamedBlock(text: String, key: String): String? {
        val match = Regex("""(?i)(?:^|\s)${Regex.escape(key)}\s*=""").find(text) ?: return null
        return extractBlockAfterEquals(text, match.range.last + 1)
    }

    private fun findBlock(text: String, key: String): String? = findNamedBlock(text, key)

    private fun findValue(text: String, key: String): String? {
        return findValues(text, key).firstOrNull()
    }

    private fun findValues(text: String, key: String): List<String> {
        return Regex("""(?i)(?:^|\s)${Regex.escape(key)}\s*=\s*("[^"]*"|[^\s{}#]+|\{[^{}]*})""")
            .findAll(text)
            .flatMap { match ->
                val value = match.groupValues[1].trim()
                if (value.startsWith("{") && value.endsWith("}")) {
                    parseStringList(value.substring(1, value.length - 1)).asSequence()
                } else {
                    sequenceOf(value.trim('"'))
                }
            }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun findInt(text: String, key: String): Int? = findValue(text, key)?.toIntOrNull()

    private fun extractBlockAfterEquals(text: String, startIndex: Int): String? {
        var index = startIndex
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length || text[index] != '{') return null
        return extractBlockAt(text, index)
    }

    private fun extractColorBlockAfterEquals(text: String, startIndex: Int): String? {
        var index = startIndex
        while (index < text.length && text[index].isWhitespace()) index++
        if (index + 3 <= text.length && text.regionMatches(index, "rgb", 0, 3, ignoreCase = true)) {
            val nextIndex = index + 3
            if (nextIndex >= text.length || !text[nextIndex].isLetterOrDigit() && text[nextIndex] != '_') {
                index = nextIndex
                while (index < text.length && text[index].isWhitespace()) index++
            }
        }
        if (index >= text.length || text[index] != '{') return null
        return extractBlockAt(text, index)
    }

    private fun extractBlockAt(text: String, openingBraceIndex: Int): String? {
        var index = openingBraceIndex
        var depth = 0
        var inQuote = false
        var escaped = false
        val contentStart = index + 1
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inQuote = !inQuote
                !inQuote && char == '{' -> depth++
                !inQuote && char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(contentStart, index)
                }
            }
            index++
        }
        return null
    }

    private fun parseIntList(block: String?): List<Int> {
        if (block.isNullOrBlank()) return emptyList()
        return TOKEN_REGEX.findAll(block)
            .mapNotNull { it.value.trim('"').toIntOrNull() }
            .toList()
    }

    private fun parseStringList(block: String?): List<String> {
        if (block.isNullOrBlank()) return emptyList()
        return TOKEN_REGEX.findAll(block)
            .map { it.value.trim('"') }
            .filter { it.isNotBlank() && it != "=" }
            .toList()
    }

    private fun parseResourceBlock(block: String?): Map<String, Int> {
        if (block.isNullOrBlank()) return emptyMap()
        return Regex("""(?i)([A-Za-z_][\w.-]*)\s*=\s*(-?\d+)""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun parseStateBuildings(block: String?): Map<String, Int> {
        if (block.isNullOrBlank()) return emptyMap()
        return Regex("""(?i)([A-Za-z_][\w.-]*)\s*=\s*(-?\d+)""")
            .findAll(removeNestedBlocks(block))
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun parseProvinceBuildings(block: String?): Map<Int, Map<String, Int>> {
        if (block.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<Int, Map<String, Int>>()
        Regex("""(?m)(\d+)\s*=""").findAll(block).forEach { match ->
            val provinceId = match.groupValues[1].toIntOrNull() ?: return@forEach
            val provinceBlock = extractBlockAfterEquals(block, match.range.last + 1) ?: return@forEach
            val buildings = Regex("""(?i)([A-Za-z_][\w.-]*)\s*=\s*(-?\d+)""")
                .findAll(provinceBlock)
                .associate { it.groupValues[1] to it.groupValues[2].toInt() }
            if (buildings.isNotEmpty()) result[provinceId] = buildings
        }
        return result
    }

    private fun parseVictoryPoints(historyBlock: String): Map<Int, Int> {
        val result = linkedMapOf<Int, Int>()
        Regex("""(?i)(?:^|\s)victory_points\s*=""").findAll(historyBlock).forEach { match ->
            val block = extractBlockAfterEquals(historyBlock, match.range.last + 1) ?: return@forEach
            val values = Regex("""-?\d+""").findAll(block).mapNotNull { it.value.toIntOrNull() }.take(2).toList()
            if (values.size == 2) result[values[0]] = values[1]
        }
        return result
    }

    private fun removeNestedBlocks(block: String): String {
        val result = StringBuilder(block.length)
        var depth = 0
        var inQuote = false
        var escaped = false
        for (char in block) {
            when {
                escaped -> {
                    if (depth == 0) result.append(char)
                    escaped = false
                }

                char == '\\' -> {
                    if (depth == 0) result.append(char)
                    escaped = true
                }

                char == '"' -> {
                    if (depth == 0) result.append(char)
                    inQuote = !inQuote
                }

                !inQuote && char == '{' -> depth++
                !inQuote && char == '}' -> if (depth > 0) depth--
                depth == 0 -> result.append(char)
            }
        }
        return result.toString()
    }

    private fun parseWeatherBlock(block: String?): Map<String, Int> {
        if (block.isNullOrBlank()) return emptyMap()
        return Regex("""(?i)([A-Za-z_][\w.-]*)\s*=\s*(-?\d+)""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun findLocFilePaths(roots: List<Path>): List<Path> {
        val files = mutableListOf<Path>()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            for (locDir in listOf(root.resolve("localisation"), root.resolve("localization"))) {
                if (!Files.isDirectory(locDir)) continue
                try {
                    Files.walk(locDir, 4).use { stream ->
                        stream
                            .filter { it.isRegularFile() && it.fileName.toString().endsWith(".yml", ignoreCase = true) }
                            .forEach {
                                val path = it.toAbsolutePath().normalize()
                                if (seen.add(HoI4ResourceRoots.normalizedKey(path))) files.add(path)
                            }
                    }
                } catch (e: Exception) {
                    LOG.warn("Localisation directory scan failed: $locDir", e)
                }
            }
        }
        return files
    }

    private fun loadLocalisations(paths: List<Path>, roots: List<Path>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val scoreByKey = mutableMapOf<String, Int>()
        val rootScores = roots
            .mapIndexed { index, root -> HoI4ResourceRoots.normalizedKey(root) to roots.size - index }
        for (path in paths) {
            val score = localisationScore(path, rootScores)
            try {
                val content = Files.readString(path).removePrefix("\uFEFF")
                LOCALISATION_REGEX.findAll(content).forEach { match ->
                    val key = match.groupValues[1]
                    val currentScore = scoreByKey[key] ?: Int.MIN_VALUE
                    if (score > currentScore) {
                        scoreByKey[key] = score
                        result[key] = unescapeLocalisation(match.groupValues[2])
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Localisation file parse failed: $path", e)
            }
        }
        return result
    }

    private fun resolveLocalisation(localisations: Map<String, String>, vararg keys: String?): String? {
        for (key in keys) {
            if (key.isNullOrBlank()) continue
            localisations[key]?.let { return it }
        }
        return null
    }

    private fun resolveCountryName(localisations: Map<String, String>, tag: String): String? {
        return resolveLocalisation(localisations, tag, "${tag}_DEF", "${tag}_ADJ")
    }

    private fun languagePriority(path: String): Int {
        return ParadoxLocalisationPreference.languagePriority(path, LANG_PRIORITY)
    }

    private fun localisationScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        return languagePriority(path.toString()) * LOCALISATION_SCORE_LANGUAGE_WEIGHT +
                localisationRootScore(path, rootScores)
    }

    private fun localisationRootScore(path: Path, rootScores: List<Pair<String, Int>>): Int {
        val key = HoI4ResourceRoots.normalizedKey(path)
        return rootScores.firstOrNull { key.startsWith(it.first) }?.second ?: 0
    }

    private fun unescapeLocalisation(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")
    }

    private fun computeSourceStamp(
        provincesPath: Path,
        definitionPath: Path?,
        statePaths: List<Path>,
        countryPaths: List<Path>,
        strategicRegionPaths: List<Path>,
        localisationPaths: List<Path>
    ): Long {
        var result = ParadoxLocalisationPreference.preferenceKey().hashCode().toLong()
        result = result * 31 + fileStamp(provincesPath)
        result = result * 31 + fileStamp(definitionPath)
        for (path in statePaths + countryPaths + strategicRegionPaths + localisationPaths) {
            result = result * 31 + fileStamp(path)
        }
        return result
    }

    private fun fileStamp(path: Path?): Long {
        if (path == null || !path.exists()) return 0L
        return try {
            Files.getLastModifiedTime(path).toMillis() xor path.fileSize() xor HoI4ResourceRoots.normalizedKey(path)
                .hashCode().toLong()
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        private val LOG = Logger.getInstance(MapPreviewService::class.java)
        private const val PROVINCES_PATH = "map/provinces.bmp"
        private const val DEFINITION_PATH = "map/definition.csv"
        private const val STATES_PATH = "history/states"
        private const val COUNTRY_HISTORY_PATH = "history/countries"
        private const val STRATEGIC_REGIONS_PATH = "map/strategicregions"
        private const val COUNTRY_TAGS_PATH = "common/country_tags"
        private const val COUNTRIES_PATH = "common/countries"
        private const val RGB_MASK = 0xFFFFFF
        private const val UNKNOWN_KEY = -1
        private val COUNTRY_TAG_REGEX = Regex("""[A-Z0-9_]{3}""")
        private val COUNTRY_COLOR_OVERRIDE_PATHS = listOf(
            "common/countries/color.txt",
            "common/countries/colors.txt"
        )
        private val COUNTRY_COLOR_ASSIGNMENT_REGEX = Regex("""(?im)(?:^|[\s{}])([A-Z0-9_]{3})\s*=""")
        private val LOCALISATION_REGEX = Regex("""(?m)^\s*([^\s:#]+)\s*:\d*\s*"((?:\\.|[^"])*)"""")
        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        private const val LOCALISATION_SCORE_LANGUAGE_WEIGHT = 10
        private val TOKEN_REGEX = Regex(""""[^"]*"|[^\s{}=]+""")
    }
}
