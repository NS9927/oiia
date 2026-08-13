package net.posdaca.oiia.map

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.posdaca.oiia.core.files.LocalisationFiles
import net.posdaca.oiia.core.files.ResourceFiles
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
                    renderChunks = renderData.renderChunks,
                    borderChunks = renderData.borderChunks,
                    smoothBorderSegments = renderData.smoothBorderSegments,
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
        return ResourceFiles.resourceRoots(project, projectFirst = true, gameFirst = false)
    }

    private fun findFirstExisting(roots: List<Path>, relativePath: String): Path? {
        return ResourceFiles.findFirst(roots, relativePath)
    }

    private fun parseDefinitionCsv(path: Path): Map<Int, ProvinceInfo> {
        val result = mutableMapOf<Int, ProvinceInfo>()
        for ((index, rawLine) in ResourceFiles.readText(path).orEmpty().lines().withIndex()) {
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
        return ResourceFiles.listFiles(roots, listOf(STATES_PATH), setOf(".txt"), maxDepth = 8)
            .distinctBy { ResourceFiles.normalizedKey(it) }
    }


    private fun parseStateFile(path: Path, localisations: Map<String, String>): StateInfo? {
        return try {
            val text = stripComments(ResourceFiles.readText(path).orEmpty())
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
        return ResourceFiles.listFiles(roots, listOf(STRATEGIC_REGIONS_PATH), setOf(".txt"), maxDepth = 8)
            .distinctBy { ResourceFiles.normalizedKey(it) }
    }


    private fun parseStrategicRegionFile(path: Path, localisations: Map<String, String>): StrategicRegionInfo? {
        return try {
            val text = stripComments(ResourceFiles.readText(path).orEmpty())
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
        val historyFiles = ResourceFiles.listFiles(roots, listOf(COUNTRY_HISTORY_PATH), setOf(".txt"), maxDepth = 8)
        for (path in historyFiles) {
            val tag = path.fileName.toString().substringBefore('.').uppercase()
            result.putIfAbsent(tag, path.toAbsolutePath().normalize())
        }
        return result
    }


    private fun loadCountryTagPaths(roots: List<Path>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val tagFiles = ResourceFiles.listFiles(roots, listOf(COUNTRY_TAGS_PATH), setOf(".txt"), maxDepth = 8)
        for (path in tagFiles) {
            for ((tag, relativePath) in parseCountryTagFile(path)) {
                result.putIfAbsent(tag, relativePath)
            }
        }
        return result
    }


    private fun parseCountryTagFile(path: Path): Map<String, String> {
        return try {
            val text = stripComments(ResourceFiles.readText(path).orEmpty())
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
            val text = stripComments(ResourceFiles.readText(path).orEmpty())
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
            val text = stripComments(ResourceFiles.readText(path).orEmpty())
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
            .distinctBy { ResourceFiles.normalizedKey(it) }
        val overridePaths = findCountryColorOverrideFiles(roots)
        return (countryDefinitionPaths + overridePaths).distinctBy { ResourceFiles.normalizedKey(it) }
    }

    private fun findCountryColorOverrideFiles(roots: List<Path>): List<Path> {
        return roots.flatMap { root ->
            COUNTRY_COLOR_OVERRIDE_PATHS.map { root.resolve(it) }
                .filter { it.isRegularFile() }
                .map { it.toAbsolutePath().normalize() }
        }.distinctBy { ResourceFiles.normalizedKey(it) }
    }

    private data class MapRenderData(
        val pixelIndex: MapPixelIndex,
        val renderChunks: List<MapRenderChunk>,
        val borderChunks: Map<MapPreviewMode, List<MapBorderChunk>>,
        val smoothBorderSegments: Map<MapPreviewMode, List<MapLineSegment>>
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
        val rgbKeys = IntArray(size)
        val provinceBounds = mutableMapOf<Int, MutablePixelBounds>()
        val stateBounds = mutableMapOf<Int, MutablePixelBounds>()
        val countryBounds = mutableMapOf<Int, MutablePixelBounds>()
        val strategicRegionBounds = mutableMapOf<Int, MutablePixelBounds>()
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
                rgbKeys[index] = rgb
                val province = provinceByColor[rgb]
                val state = province?.let { stateByProvinceId[it.id] }
                val strategicRegion = province?.let { strategicRegionByProvinceId[it.id] }

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
        val smoothBorderSegments = buildSmoothBorderSegments(pixelIndex, width, height)
        val renderAreas = buildRenderAreas(
            rgbKeys,
            width,
            height,
            provinceByColor,
            stateByProvinceId,
            strategicRegionByProvinceId,
            stateColorById,
            countryColorByStateId,
            strategicRegionColorById
        )
        return MapRenderData(
            pixelIndex = pixelIndex,
            renderChunks = buildRenderChunks(renderAreas, width, height),
            borderChunks = buildPixelBorderChunks(pixelIndex, width, height),
            smoothBorderSegments = smoothBorderSegments
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

    private fun buildRenderAreas(
        rgbKeys: IntArray,
        width: Int,
        height: Int,
        provinceByColor: Map<Int, ProvinceInfo>,
        stateByProvinceId: Map<Int, StateInfo>,
        strategicRegionByProvinceId: Map<Int, StrategicRegionInfo>,
        stateColorById: Map<Int, Int>,
        countryColorByStateId: Map<Int, Int>,
        strategicRegionColorById: Map<Int, Int>
    ): List<MapRenderArea> {
        val areas = linkedMapOf<Int, MapRenderAreaBuilder>()
        val pending = java.util.ArrayDeque<MapRenderZone>()
        var y = 0
        while (y < height) {
            var x = 0
            val blockHeight = minOf(RENDER_ZONE_BLOCK_SIZE, height - y)
            while (x < width) {
                val blockWidth = minOf(RENDER_ZONE_BLOCK_SIZE, width - x)
                pending.add(MapRenderZone(x, y, blockWidth, blockHeight))
                x += RENDER_ZONE_BLOCK_SIZE
            }
            y += RENDER_ZONE_BLOCK_SIZE
        }

        while (!pending.isEmpty()) {
            val zone = pending.removeLast()
            val rgb = sameRgbInZone(rgbKeys, width, zone)
            if (rgb != null) {
                areas.getOrPut(rgb) {
                    createRenderAreaBuilder(
                        rgb,
                        provinceByColor,
                        stateByProvinceId,
                        strategicRegionByProvinceId,
                        stateColorById,
                        countryColorByStateId,
                        strategicRegionColorById
                    )
                }.add(zone)
            } else {
                splitRenderZone(zone, pending)
            }
        }

        return areas.values
            .map { it.toArea() }
            .sortedWith(compareBy<MapRenderArea> { it.provinceId ?: Int.MAX_VALUE }.thenBy { it.rgb })
    }

    private fun sameRgbInZone(rgbKeys: IntArray, width: Int, zone: MapRenderZone): Int? {
        val first = rgbKeys[zone.y * width + zone.x]
        val maxY = zone.y + zone.height
        val maxX = zone.x + zone.width
        for (y in zone.y until maxY) {
            val rowOffset = y * width
            for (x in zone.x until maxX) {
                if (rgbKeys[rowOffset + x] != first) return null
            }
        }
        return first
    }

    private fun splitRenderZone(zone: MapRenderZone, pending: java.util.ArrayDeque<MapRenderZone>) {
        if (zone.width <= 1 && zone.height <= 1) {
            pending.add(zone)
            return
        }
        val splitWidth = zone.width > 1
        val splitHeight = zone.height > 1
        val leftWidth = if (splitWidth) zone.width / 2 else zone.width
        val rightWidth = zone.width - leftWidth
        val topHeight = if (splitHeight) zone.height / 2 else zone.height
        val bottomHeight = zone.height - topHeight
        addRenderZoneIfNotEmpty(pending, zone.x, zone.y, leftWidth, topHeight)
        if (splitWidth) addRenderZoneIfNotEmpty(pending, zone.x + leftWidth, zone.y, rightWidth, topHeight)
        if (splitHeight) addRenderZoneIfNotEmpty(pending, zone.x, zone.y + topHeight, leftWidth, bottomHeight)
        if (splitWidth && splitHeight) {
            addRenderZoneIfNotEmpty(pending, zone.x + leftWidth, zone.y + topHeight, rightWidth, bottomHeight)
        }
    }

    private fun addRenderZoneIfNotEmpty(
        pending: java.util.ArrayDeque<MapRenderZone>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        if (width > 0 && height > 0) pending.add(MapRenderZone(x, y, width, height))
    }

    private fun createRenderAreaBuilder(
        rgb: Int,
        provinceByColor: Map<Int, ProvinceInfo>,
        stateByProvinceId: Map<Int, StateInfo>,
        strategicRegionByProvinceId: Map<Int, StrategicRegionInfo>,
        stateColorById: Map<Int, Int>,
        countryColorByStateId: Map<Int, Int>,
        strategicRegionColorById: Map<Int, Int>
    ): MapRenderAreaBuilder {
        val province = provinceByColor[rgb]
        val state = province?.let { stateByProvinceId[it.id] }
        val strategicRegion = province?.let { strategicRegionByProvinceId[it.id] }
        val stateKey = state?.id ?: UNKNOWN_KEY
        val countryKey = state?.owner?.let { mapCountryKey(it) } ?: UNKNOWN_KEY
        val strategicRegionKey = strategicRegion?.id ?: UNKNOWN_KEY
        return MapRenderAreaBuilder(
            rgb = rgb,
            provinceId = province?.id,
            stateKey = stateKey,
            countryKey = countryKey,
            strategicRegionKey = strategicRegionKey,
            provinceColor = rgb,
            stateColor = state?.let { stateColorById[it.id] } ?: rgb,
            countryColor = state?.let { countryColorByStateId[it.id] } ?: rgb,
            strategicRegionColor = strategicRegion?.let { strategicRegionColorById[it.id] } ?: rgb
        )
    }

    private class MapRenderAreaBuilder(
        private val rgb: Int,
        private val provinceId: Int?,
        private val stateKey: Int,
        private val countryKey: Int,
        private val strategicRegionKey: Int,
        private val provinceColor: Int,
        private val stateColor: Int,
        private val countryColor: Int,
        private val strategicRegionColor: Int
    ) {
        private val zones = mutableListOf<MapRenderZone>()
        private val bounds = MutablePixelBounds()

        fun add(zone: MapRenderZone) {
            zones.add(zone)
            bounds.include(zone.x, zone.y)
            bounds.include(zone.x + zone.width - 1, zone.y + zone.height - 1)
        }

        fun toArea(): MapRenderArea {
            return MapRenderArea(
                rgb = rgb,
                provinceId = provinceId,
                stateKey = stateKey,
                countryKey = countryKey,
                strategicRegionKey = strategicRegionKey,
                provinceColor = provinceColor,
                stateColor = stateColor,
                countryColor = countryColor,
                strategicRegionColor = strategicRegionColor,
                zones = zones,
                bounds = bounds.toBounds()
            )
        }
    }

    private fun buildRenderChunks(renderAreas: List<MapRenderArea>, width: Int, height: Int): List<MapRenderChunk> {
        val chunkColumns = (width + RENDER_ZONE_BLOCK_SIZE - 1) / RENDER_ZONE_BLOCK_SIZE
        val cellsByChunk = linkedMapOf<Int, MutableList<MapRenderCell>>()
        for (area in renderAreas) {
            for (zone in area.zones) {
                val chunkX = zone.x / RENDER_ZONE_BLOCK_SIZE
                val chunkY = zone.y / RENDER_ZONE_BLOCK_SIZE
                val chunkKey = chunkY * chunkColumns + chunkX
                cellsByChunk.getOrPut(chunkKey) { mutableListOf() }.add(
                    MapRenderCell(
                        zone = zone,
                        provinceId = area.provinceId,
                        stateKey = area.stateKey,
                        countryKey = area.countryKey,
                        strategicRegionKey = area.strategicRegionKey,
                        provinceColor = area.provinceColor,
                        stateColor = area.stateColor,
                        countryColor = area.countryColor,
                        strategicRegionColor = area.strategicRegionColor
                    )
                )
            }
        }

        return cellsByChunk.map { (chunkKey, cells) ->
            val chunkX = chunkKey % chunkColumns
            val chunkY = chunkKey / chunkColumns
            val x = chunkX * RENDER_ZONE_BLOCK_SIZE
            val y = chunkY * RENDER_ZONE_BLOCK_SIZE
            MapRenderChunk(
                x = x,
                y = y,
                width = minOf(RENDER_ZONE_BLOCK_SIZE, width - x),
                height = minOf(RENDER_ZONE_BLOCK_SIZE, height - y),
                cells = cells
            )
        }
    }

    private fun buildPixelBorderChunks(
        pixelIndex: MapPixelIndex,
        width: Int,
        height: Int
    ): Map<MapPreviewMode, List<MapBorderChunk>> {
        return mapOf(
            MapPreviewMode.PROVINCE to buildPixelBorderChunks(width, height, pixelIndex.provinceKeys),
            MapPreviewMode.STATE to buildPixelBorderChunks(width, height, pixelIndex.stateKeys),
            MapPreviewMode.COUNTRY to buildPixelBorderChunks(width, height, pixelIndex.countryKeys),
            MapPreviewMode.STRATEGIC_REGION to buildPixelBorderChunks(width, height, pixelIndex.strategicRegionKeys)
        )
    }

    private fun buildPixelBorderChunks(width: Int, height: Int, keys: IntArray): List<MapBorderChunk> {
        val chunkColumns = (width + RENDER_ZONE_BLOCK_SIZE - 1) / RENDER_ZONE_BLOCK_SIZE
        val segmentsByChunk = linkedMapOf<Int, MutableList<MapBorderSegment>>()
        collectHorizontalBorderSegments(segmentsByChunk, chunkColumns, width, height, keys)
        collectVerticalBorderSegments(segmentsByChunk, chunkColumns, width, height, keys)
        return segmentsByChunk.map { (chunkKey, segments) ->
            val chunkX = chunkKey % chunkColumns
            val chunkY = chunkKey / chunkColumns
            val x = chunkX * RENDER_ZONE_BLOCK_SIZE
            val y = chunkY * RENDER_ZONE_BLOCK_SIZE
            MapBorderChunk(
                x = x,
                y = y,
                width = minOf(RENDER_ZONE_BLOCK_SIZE, width - x),
                height = minOf(RENDER_ZONE_BLOCK_SIZE, height - y),
                segments = segments
            )
        }
    }

    private fun collectHorizontalBorderSegments(
        segmentsByChunk: MutableMap<Int, MutableList<MapBorderSegment>>,
        chunkColumns: Int,
        width: Int,
        height: Int,
        keys: IntArray
    ) {
        for (edgeY in 0..height) {
            var x = 0
            while (x < width) {
                if (!isHorizontalPixelBorder(keys, width, height, x, edgeY)) {
                    x++
                    continue
                }
                val startX = x
                x++
                while (x < width && isHorizontalPixelBorder(keys, width, height, x, edgeY)) x++
                addHorizontalBorderSegment(segmentsByChunk, chunkColumns, width, height, startX, x, edgeY)
            }
        }
    }

    private fun collectVerticalBorderSegments(
        segmentsByChunk: MutableMap<Int, MutableList<MapBorderSegment>>,
        chunkColumns: Int,
        width: Int,
        height: Int,
        keys: IntArray
    ) {
        for (edgeX in 0 until width) {
            var y = 0
            while (y < height) {
                if (!isVerticalPixelBorder(keys, width, edgeX, y)) {
                    y++
                    continue
                }
                val startY = y
                y++
                while (y < height && isVerticalPixelBorder(keys, width, edgeX, y)) y++
                addVerticalBorderSegment(segmentsByChunk, chunkColumns, height, edgeX, startY, y)
            }
        }
    }

    private fun addHorizontalBorderSegment(
        segmentsByChunk: MutableMap<Int, MutableList<MapBorderSegment>>,
        chunkColumns: Int,
        width: Int,
        height: Int,
        startX: Int,
        endX: Int,
        y: Int
    ) {
        var x = startX
        val chunkY = (if (y == height) height - 1 else y).coerceAtLeast(0) / RENDER_ZONE_BLOCK_SIZE
        while (x < endX) {
            val chunkX = x / RENDER_ZONE_BLOCK_SIZE
            val splitEndX = minOf(endX, (chunkX + 1) * RENDER_ZONE_BLOCK_SIZE, width)
            val chunkKey = chunkY * chunkColumns + chunkX
            segmentsByChunk.getOrPut(chunkKey) { mutableListOf() }
                .add(MapBorderSegment(x, y, splitEndX, y))
            x = splitEndX
        }
    }

    private fun addVerticalBorderSegment(
        segmentsByChunk: MutableMap<Int, MutableList<MapBorderSegment>>,
        chunkColumns: Int,
        height: Int,
        x: Int,
        startY: Int,
        endY: Int
    ) {
        var y = startY
        val chunkX = x / RENDER_ZONE_BLOCK_SIZE
        while (y < endY) {
            val chunkY = y / RENDER_ZONE_BLOCK_SIZE
            val splitEndY = minOf(endY, (chunkY + 1) * RENDER_ZONE_BLOCK_SIZE, height)
            val chunkKey = chunkY * chunkColumns + chunkX
            segmentsByChunk.getOrPut(chunkKey) { mutableListOf() }
                .add(MapBorderSegment(x, y, x, splitEndY))
            y = splitEndY
        }
    }

    private fun isHorizontalPixelBorder(keys: IntArray, width: Int, height: Int, x: Int, edgeY: Int): Boolean {
        val upperKey = if (edgeY == 0) UNKNOWN_KEY else keys[(edgeY - 1) * width + x]
        val lowerKey = if (edgeY == height) UNKNOWN_KEY else keys[edgeY * width + x]
        return upperKey != lowerKey && (upperKey != UNKNOWN_KEY || lowerKey != UNKNOWN_KEY)
    }

    private fun isVerticalPixelBorder(keys: IntArray, width: Int, edgeX: Int, y: Int): Boolean {
        val rowOffset = y * width
        val leftKey = keys[rowOffset + if (edgeX == 0) width - 1 else edgeX - 1]
        val rightKey = keys[rowOffset + edgeX]
        return leftKey != rightKey && (leftKey != UNKNOWN_KEY || rightKey != UNKNOWN_KEY)
    }

    private fun buildSmoothBorderSegments(
        pixelIndex: MapPixelIndex,
        width: Int,
        height: Int
    ): Map<MapPreviewMode, List<MapLineSegment>> {
        return mapOf(
            MapPreviewMode.PROVINCE to buildSmoothBorderSegments(width, height, pixelIndex.provinceKeys),
            MapPreviewMode.STATE to buildSmoothBorderSegments(width, height, pixelIndex.stateKeys),
            MapPreviewMode.COUNTRY to buildSmoothBorderSegments(width, height, pixelIndex.countryKeys),
            MapPreviewMode.STRATEGIC_REGION to buildSmoothBorderSegments(width, height, pixelIndex.strategicRegionKeys)
        )
    }

    private fun buildSmoothBorderSegments(width: Int, height: Int, keys: IntArray): List<MapLineSegment> {
        val edges = mutableListOf<BorderEdge>()
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val key = keys[rowOffset + x]
                if (key == UNKNOWN_KEY) continue

                val leftKey = keys[rowOffset + if (x == 0) width - 1 else x - 1]
                if (leftKey == UNKNOWN_KEY) {
                    edges.add(BorderEdge(BorderPoint(x.toDouble(), y.toDouble()), BorderPoint(x.toDouble(), (y + 1).toDouble())))
                }

                val rightKey = keys[rowOffset + if (x + 1 == width) 0 else x + 1]
                if (rightKey != key) {
                    edges.add(BorderEdge(BorderPoint(x + 1.0, y.toDouble()), BorderPoint(x + 1.0, (y + 1).toDouble())))
                }

                if (y > 0) {
                    val upKey = keys[rowOffset - width + x]
                    if (upKey == UNKNOWN_KEY) {
                        edges.add(BorderEdge(BorderPoint(x.toDouble(), y.toDouble()), BorderPoint((x + 1).toDouble(), y.toDouble())))
                    }
                }

                if (y + 1 < height) {
                    val downKey = keys[rowOffset + width + x]
                    if (downKey != key) {
                        edges.add(BorderEdge(BorderPoint(x.toDouble(), y + 1.0), BorderPoint((x + 1).toDouble(), y + 1.0)))
                    }
                }
            }
        }
        return buildBorderChains(edges)
            .flatMap { simplifyBorderChain(it).zipWithNext() }
            .map { (a, b) -> MapLineSegment(a.x, a.y, b.x, b.y) }
    }

    private fun buildBorderChains(edges: List<BorderEdge>): List<List<BorderPoint>> {
        val outgoing = linkedMapOf<BorderPoint, MutableList<BorderPoint>>()
        for (edge in edges) {
            outgoing.getOrPut(edge.start) { mutableListOf() }.add(edge.end)
            outgoing.getOrPut(edge.end) { mutableListOf() }.add(edge.start)
        }
        val visitedEdges = mutableSetOf<Pair<BorderPoint, BorderPoint>>()
        val chains = mutableListOf<List<BorderPoint>>()
        for ((start, nextPoints) in outgoing) {
            for (next in nextPoints) {
                val edgeKey = normalizedEdgeKey(start, next)
                if (!visitedEdges.add(edgeKey)) continue
                val chain = mutableListOf(start, next)
                extendBorderChain(chain, outgoing, visitedEdges, forward = true)
                extendBorderChain(chain, outgoing, visitedEdges, forward = false)
                chains.add(chain)
            }
        }
        return chains
    }

    private fun extendBorderChain(
        chain: MutableList<BorderPoint>,
        outgoing: Map<BorderPoint, List<BorderPoint>>,
        visitedEdges: MutableSet<Pair<BorderPoint, BorderPoint>>,
        forward: Boolean
    ) {
        while (true) {
            val current = if (forward) chain.last() else chain.first()
            val previous = if (forward) chain.getOrNull(chain.lastIndex - 1) else chain.getOrNull(1)
            val next = outgoing[current]
                .orEmpty()
                .filter { it != previous }
                .firstOrNull { visitedEdges.add(normalizedEdgeKey(current, it)) }
                ?: break
            if (forward) chain.add(next) else chain.add(0, next)
        }
    }

    private fun normalizedEdgeKey(a: BorderPoint, b: BorderPoint): Pair<BorderPoint, BorderPoint> {
        return if (a <= b) a to b else b to a
    }

    private fun simplifyBorderChain(points: List<BorderPoint>): List<BorderPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        simplifyBorderChain(points, 0, points.lastIndex, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun simplifyBorderChain(points: List<BorderPoint>, start: Int, end: Int, keep: BooleanArray) {
        if (end <= start + 1) return
        var farthestIndex = -1
        var farthestDistance = 0.0
        val a = points[start]
        val b = points[end]
        for (index in start + 1 until end) {
            val distance = pointLineDistance(points[index], a, b)
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthestIndex = index
            }
        }
        if (farthestDistance > SMOOTH_EDGE_SIMPLIFY_TOLERANCE && farthestIndex >= 0) {
            keep[farthestIndex] = true
            simplifyBorderChain(points, start, farthestIndex, keep)
            simplifyBorderChain(points, farthestIndex, end, keep)
        }
    }

    private fun pointLineDistance(point: BorderPoint, start: BorderPoint, end: BorderPoint): Double {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (dx == 0.0 && dy == 0.0) {
            val px = point.x - start.x
            val py = point.y - start.y
            return kotlin.math.sqrt(px * px + py * py)
        }
        val numerator = kotlin.math.abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x)
        val denominator = kotlin.math.sqrt(dx * dx + dy * dy)
        return numerator / denominator
    }

    private data class BorderPoint(val x: Double, val y: Double) : Comparable<BorderPoint> {
        override fun compareTo(other: BorderPoint): Int {
            val yCompare = y.compareTo(other.y)
            return if (yCompare != 0) yCompare else x.compareTo(other.x)
        }
    }

    private data class BorderEdge(val start: BorderPoint, val end: BorderPoint)

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
        return LocalisationFiles.findFiles(roots)
    }

    private fun loadLocalisations(paths: List<Path>, roots: List<Path>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val scoreByKey = mutableMapOf<String, Int>()
        val rootScores = LocalisationFiles.rootScores(roots)
        for (path in paths) {
            val score = localisationScore(path, rootScores)
            for ((key, value) in LocalisationFiles.parseFile(path)) {
                val currentScore = scoreByKey[key] ?: Int.MIN_VALUE
                if (score > currentScore) {
                    scoreByKey[key] = score
                    result[key] = LocalisationFiles.unescape(value)
                }
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
        return LocalisationFiles.rootScore(path, rootScores)
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
        return ResourceFiles.fileStamp(path)
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
        private const val RENDER_ZONE_BLOCK_SIZE = 256
        private const val SMOOTH_EDGE_SIMPLIFY_TOLERANCE = 0.85
        private val COUNTRY_TAG_REGEX = Regex("""[A-Z0-9_]{3}""")
        private val COUNTRY_COLOR_OVERRIDE_PATHS = listOf(
            "common/countries/color.txt",
            "common/countries/colors.txt"
        )
        private val COUNTRY_COLOR_ASSIGNMENT_REGEX = Regex("""(?im)(?:^|[\s{}])([A-Z0-9_]{3})\s*=""")
        private val LANG_PRIORITY = listOf(
            "simp_chinese", "l_simp_chinese", "chinese", "l_chinese",
            "english", "l_english"
        )
        private const val LOCALISATION_SCORE_LANGUAGE_WEIGHT = 10
        private val TOKEN_REGEX = Regex(""""[^"]*"|[^\s{}=]+""")
    }
}

