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

data class MapColorLineSegment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val positiveSideRgb: Int,
    val negativeSideRgb: Int
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
    val stateImage: BufferedImage?,
    val countryImage: BufferedImage?,
    val strategicRegionImage: BufferedImage?,
    val borderImages: Map<MapPreviewMode, BufferedImage>,
    val smoothBorderSegments: Map<MapPreviewMode, List<MapLineSegment>>,
    val smoothColorSegments: Map<MapPreviewMode, List<MapColorLineSegment>>,
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
    fun imageFor(mode: MapPreviewMode): BufferedImage {
        return when (mode) {
            MapPreviewMode.PROVINCE -> provincesImage
            MapPreviewMode.STATE -> stateImage ?: provincesImage
            MapPreviewMode.COUNTRY -> countryImage ?: provincesImage
            MapPreviewMode.STRATEGIC_REGION -> strategicRegionImage ?: provincesImage
        }
    }

    fun borderImageFor(mode: MapPreviewMode): BufferedImage? = borderImages[mode]

    fun smoothBorderSegmentsFor(mode: MapPreviewMode): List<MapLineSegment> = smoothBorderSegments[mode].orEmpty()

    fun smoothColorSegmentsFor(mode: MapPreviewMode): List<MapColorLineSegment> = smoothColorSegments[mode].orEmpty()
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
