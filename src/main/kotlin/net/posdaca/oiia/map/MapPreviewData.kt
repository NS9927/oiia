package net.posdaca.oiia.map

import java.awt.image.BufferedImage
import java.nio.file.Path

class MapPixelIndex(
    val width: Int,
    val provinceKeys: IntArray,
    val stateKeys: IntArray,
    val countryKeys: IntArray,
    val strategicRegionKeys: IntArray,
    val provinceBounds: Map<Int, PixelBounds>,
    val stateBounds: Map<Int, PixelBounds>,
    val countryBounds: Map<Int, PixelBounds>,
    val strategicRegionBounds: Map<Int, PixelBounds>
)

data class PixelBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

data class MapLineSegment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double
)

data class MapRenderZone(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class MapRenderArea(
    val rgb: Int,
    val provinceId: Int?,
    val stateKey: Int,
    val countryKey: Int,
    val strategicRegionKey: Int,
    val provinceColor: Int,
    val stateColor: Int,
    val countryColor: Int,
    val strategicRegionColor: Int,
    val zones: List<MapRenderZone>,
    val bounds: PixelBounds
) {
    fun colorFor(mode: MapPreviewMode): Int {
        return when (mode) {
            MapPreviewMode.PROVINCE -> provinceColor
            MapPreviewMode.STATE -> stateColor
            MapPreviewMode.COUNTRY -> countryColor
            MapPreviewMode.STRATEGIC_REGION -> strategicRegionColor
        }
    }
}

data class MapRenderCell(
    val zone: MapRenderZone,
    val provinceId: Int?,
    val stateKey: Int,
    val countryKey: Int,
    val strategicRegionKey: Int,
    val provinceColor: Int,
    val stateColor: Int,
    val countryColor: Int,
    val strategicRegionColor: Int
) {
    fun colorFor(mode: MapPreviewMode): Int {
        return when (mode) {
            MapPreviewMode.PROVINCE -> provinceColor
            MapPreviewMode.STATE -> stateColor
            MapPreviewMode.COUNTRY -> countryColor
            MapPreviewMode.STRATEGIC_REGION -> strategicRegionColor
        }
    }
}

data class MapRenderChunk(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val cells: List<MapRenderCell>
)

data class MapBorderSegment(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int
)

data class MapBorderChunk(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val segments: List<MapBorderSegment>
)

enum class MapPreviewMode(val messageKey: String) {
    PROVINCE("toolwindow.MapPreview.mode.province"),
    STATE("toolwindow.MapPreview.mode.state"),
    COUNTRY("toolwindow.MapPreview.mode.country"),
    STRATEGIC_REGION("toolwindow.MapPreview.mode.strategic.region")
}

enum class MapLoadStep(val messageKey: String) {
    LOCATING("loading.locating"),
    READING_PROVINCES("loading.reading.provinces"),
    LOCALISATION("loading.localisation"),
    DEFINITIONS("loading.definitions"),
    STATES("loading.states"),
    STRATEGIC_REGIONS("loading.strategic.regions"),
    COUNTRIES("loading.countries"),
    INDEX("loading.index"),
    IMAGES("loading.images")
}

data class ProvinceInfo(
    val id: Int,
    val rgb: Int,
    val type: String? = null,
    val coastal: Boolean? = null,
    val terrain: String? = null,
    val continent: Int? = null,
    val localizedName: String? = null,
    val sourceLine: Int = 0
)

data class StateInfo(
    val id: Int,
    val name: String?,
    val localizedName: String?,
    val owner: String?,
    val category: String?,
    val manpower: Int?,
    val provinces: List<Int>,
    val cores: List<String>,
    val resources: Map<String, Int>,
    val stateBuildings: Map<String, Int>,
    val provinceBuildings: Map<Int, Map<String, Int>>,
    val victoryPoints: Map<Int, Int>,
    val path: Path
)

data class CountryInfo(
    val tag: String,
    val mapKey: Int,
    val localizedName: String?,
    val color: Int?,
    val definitionPath: Path?,
    val historyPath: Path?,
    val colorSourcePath: Path?,
    val stateIds: List<Int>,
    val provinceIds: List<Int>
)

data class StrategicRegionInfo(
    val id: Int,
    val name: String?,
    val localizedName: String?,
    val provinces: List<Int>,
    val navalTerrain: String?,
    val weather: Map<String, Int>,
    val path: Path
)

data class LoadedMapData(
    val provincesImage: BufferedImage,
    val renderChunks: List<MapRenderChunk>,
    val borderChunks: Map<MapPreviewMode, List<MapBorderChunk>>,
    val smoothBorderSegments: Map<MapPreviewMode, List<MapLineSegment>>,
    val pixelIndex: MapPixelIndex,
    val provinceByColor: Map<Int, ProvinceInfo>,
    val provinceById: Map<Int, ProvinceInfo>,
    val stateById: Map<Int, StateInfo>,
    val stateByProvinceId: Map<Int, StateInfo>,
    val countryByTag: Map<String, CountryInfo>,
    val strategicRegionById: Map<Int, StrategicRegionInfo>,
    val strategicRegionByProvinceId: Map<Int, StrategicRegionInfo>,
    val provincesPath: Path,
    val definitionPath: Path?,
    val statePaths: List<Path>,
    val countryPaths: List<Path>,
    val strategicRegionPaths: List<Path>,
    val localisationPaths: List<Path>,
    val localisations: Map<String, String>,
    val sourceStamp: Long
) {
    fun borderChunksFor(mode: MapPreviewMode): List<MapBorderChunk> = borderChunks[mode].orEmpty()

    fun smoothBorderSegmentsFor(mode: MapPreviewMode): List<MapLineSegment> = smoothBorderSegments[mode].orEmpty()
}

internal fun mapCountryKey(tag: String): Int {
    val normalized = tag.uppercase()
    var result = 0
    for (char in normalized.take(4)) {
        result = (result shl 8) or char.code
    }
    return result
}

sealed class MapLoadResult {
    data class Loaded(val data: LoadedMapData) : MapLoadResult()
    data class Missing(val searchedRoots: List<Path>) : MapLoadResult()
    data class Failed(val message: String) : MapLoadResult()
}

typealias MapPreviewSnapshot = LoadedMapData
