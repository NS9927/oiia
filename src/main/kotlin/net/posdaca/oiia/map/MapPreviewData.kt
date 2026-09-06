package net.posdaca.oiia.map

import kotlin.math.pow

import net.posdaca.oiia.core.preview.PreviewSnapshot
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
    val strategicRegionBounds: Map<Int, PixelBounds>,
    val provinceLabelAnchors: Map<Int, MapLabelAnchor> = emptyMap(),
    val stateLabelAnchors: Map<Int, MapLabelAnchor> = emptyMap(),
    val countryLabelAnchors: Map<Int, MapLabelAnchor> = emptyMap(),
    val strategicRegionLabelAnchors: Map<Int, MapLabelAnchor> = emptyMap()
)

/** Mass-weighted centroid of a region plus its rendered colour, for map label placement. */
data class MapLabelAnchor(
    val x: Double,
    val y: Double,
    val mass: Int,
    val renderRgb: Int
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
    val terrainColor: Int,
    val controllerColor: Int,
    val manpowerColor: Int,
    val victoryPointColor: Int,
    val resourcesColor: Int,
    val stateCategoryColor: Int,
    val provinceTypeColor: Int,
    val continentColor: Int,
    val zones: List<MapRenderZone>,
    val bounds: PixelBounds
) {
    fun colorFor(colorSet: MapColorSet): Int {
        return when (colorSet) {
            MapColorSet.PROVINCE -> provinceColor
            MapColorSet.STATE -> stateColor
            MapColorSet.COUNTRY -> countryColor
            MapColorSet.STRATEGIC_REGION -> strategicRegionColor
            MapColorSet.TERRAIN -> terrainColor
            MapColorSet.CONTROLLER -> controllerColor
            MapColorSet.MANPOWER -> manpowerColor
            MapColorSet.VICTORY_POINTS -> victoryPointColor
            MapColorSet.RESOURCES -> resourcesColor
            MapColorSet.STATE_CATEGORY -> stateCategoryColor
            MapColorSet.PROVINCE_TYPE -> provinceTypeColor
            MapColorSet.CONTINENT -> continentColor
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
    val strategicRegionColor: Int,
    val terrainColor: Int,
    val controllerColor: Int,
    val manpowerColor: Int,
    val victoryPointColor: Int,
    val resourcesColor: Int,
    val stateCategoryColor: Int,
    val provinceTypeColor: Int,
    val continentColor: Int
) {
    fun colorFor(colorSet: MapColorSet): Int {
        return when (colorSet) {
            MapColorSet.PROVINCE -> provinceColor
            MapColorSet.STATE -> stateColor
            MapColorSet.COUNTRY -> countryColor
            MapColorSet.STRATEGIC_REGION -> strategicRegionColor
            MapColorSet.TERRAIN -> terrainColor
            MapColorSet.CONTROLLER -> controllerColor
            MapColorSet.MANPOWER -> manpowerColor
            MapColorSet.VICTORY_POINTS -> victoryPointColor
            MapColorSet.RESOURCES -> resourcesColor
            MapColorSet.STATE_CATEGORY -> stateCategoryColor
            MapColorSet.PROVINCE_TYPE -> provinceTypeColor
            MapColorSet.CONTINENT -> continentColor
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

/** Common option for the map toolbar selectors; labels come from the message bundle. */
interface MapModeOption {
    val messageKey: String
}

enum class MapPreviewMode(override val messageKey: String) : MapModeOption {
    PROVINCE("toolwindow.MapPreview.mode.province"),
    STATE("toolwindow.MapPreview.mode.state"),
    COUNTRY("toolwindow.MapPreview.mode.country"),
    STRATEGIC_REGION("toolwindow.MapPreview.mode.strategic.region")
}

/**
 * Fill colouring, independent of the view mode: any color set can combine with any view mode
 * (e.g. state outlines + terrain fill).
 */
enum class MapColorSet(override val messageKey: String) : MapModeOption {
    PROVINCE("toolwindow.MapPreview.mode.province"),
    STATE("toolwindow.MapPreview.mode.state"),
    COUNTRY("toolwindow.MapPreview.mode.country"),
    STRATEGIC_REGION("toolwindow.MapPreview.mode.strategic.region"),
    TERRAIN("toolwindow.MapPreview.mode.terrain"),
    CONTROLLER("toolwindow.MapPreview.mode.controller"),
    MANPOWER("toolwindow.MapPreview.colorset.manpower"),
    VICTORY_POINTS("toolwindow.MapPreview.colorset.victory.points"),
    RESOURCES("toolwindow.MapPreview.colorset.resources"),
    STATE_CATEGORY("toolwindow.MapPreview.colorset.state.category"),
    PROVINCE_TYPE("toolwindow.MapPreview.colorset.province.type"),
    CONTINENT("toolwindow.MapPreview.colorset.continent")
}

/**
 * Colour math for value-based map color sets, ported from the hoi4modutilities reference:
 * green→yellow→red heat ramps with power scaling so small values stay distinguishable.
 */
object MapHeatColors {

    /** [value] in 0..1; 0 = green, 0.5 = yellow, 1 = red. */
    fun valueToGyr(value: Double): Int {
        val clamped = value.coerceIn(0.0, 1.0)
        return if (clamped < 0.5) {
            0xFF00 or ((255.0 * 2.0 * clamped).toInt() shl 16)
        } else {
            0xFF0000 or ((255.0 * 2.0 * (1.0 - clamped)).toInt() shl 8)
        }
    }

    /** Pseudo colour from a value within [0, max]; mimics the reference's id-like spread. */
    fun valueAndMaxToColor(value: Int, max: Int): Int {
        if (max <= 0) return 0
        return (value * (0xFFFFFF / max))
    }

    fun manpowerScale(manpower: Int): Double = pow(manpower, 0.2)

    fun victoryPointScale(victoryPoints: Int): Double = pow(victoryPoints, 0.5)

    fun provinceTypeColor(type: String?, coastal: Boolean?): Int {
        val base = when (type?.trim()?.trim('"')?.lowercase()) {
            "land" -> 0x007F00
            "lake" -> 0x00FFFF
            else -> 0x00007F
        }
        return if (coastal == true) base or 0x7F0000 else base
    }

    private fun pow(value: Int, exponent: Double): Double = value.coerceAtLeast(0).toDouble().pow(exponent)
}

/** Fill palette for terrain-mode colouring; unknown or missing terrain falls back to grey. */
object MapTerrainColors {
    private val COLORS = mapOf(
        "plains" to 0xA8C078,
        "forest" to 0x55884F,
        "hills" to 0xC2A36B,
        "mountain" to 0x8A8A92,
        "marsh" to 0x7FA08A,
        "desert" to 0xE3D29B,
        "urban" to 0xB06A6A,
        "ocean" to 0x4A7EBB,
        "lake" to 0x4A7EBB,
        "unknown" to 0x606060
    )
    private const val FALLBACK = 0x606060

    fun colorFor(terrain: String?): Int {
        val key = terrain?.trim()?.trim('"')?.lowercase() ?: return FALLBACK
        return COLORS[key] ?: FALLBACK
    }
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
    val path: Path,
    val impassable: Boolean = false,
    val controller: String? = null,
    val demilitarizedZone: Boolean = false,
    /** Owner/controller changes from the state history, including dated and `has_dlc`-gated ones. */
    val stateChanges: List<MapStateChange> = emptyList()
)

/**
 * One owner/controller change inside a state history. [year]/[month]/[day] are null for
 * undated (game-start) changes; [requiredDlc] is the `has_dlc` name gating the change
 * (null = unconditional; [unlessDlc] inverts it for `else` branches).
 */
data class MapStateChange(
    val year: Int?,
    val month: Int?,
    val day: Int?,
    val requiredDlc: String?,
    val unlessDlc: Boolean,
    val owner: String?,
    val controller: String?
) {
    /** True when this change happens on or before the given timeline date. */
    fun isOnOrBefore(year: Int, month: Int, day: Int): Boolean {
        val cy = this.year ?: return true
        if (cy != year) return cy < year
        val cm = this.month ?: return true
        if (cm != month) return cm < month
        val cd = this.day ?: return true
        return cd <= day
    }
}

data class MapBookmark(
    val nameKey: String,
    /** `y.m.d` (hour, when present in the file, is dropped). */
    val year: Int,
    val month: Int,
    val day: Int
)

data class MapWarning(
    val message: String,
    /** View mode + key for locate-on-click, when the warning maps onto the map. */
    val mode: MapPreviewMode? = null,
    val key: Int? = null
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

/**
 * The resolved map snapshot. Load failures are expressed by [MapLoadResult.Missing]/[MapLoadResult.Failed],
 * so a constructed instance is never empty.
 */
data class LoadedMapData(
    val provincesImage: BufferedImage,
    val renderChunks: List<MapRenderChunk>,
    val borderChunks: Map<MapPreviewMode, List<MapBorderChunk>>,
    /** Builds smooth segments for a mode on first request; segments are large, so keep them lazy. */
    val smoothBorderProvider: (MapPreviewMode) -> List<MapLineSegment>,
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
    val sourceStamp: Long,
    /** Border segments around regions whose passability differs; always drawn in red. */
    val impassableBorderChunks: List<MapBorderChunk> = emptyList(),
    /** Per-pixel 0/1 mask of demilitarized-zone provinces, drawn as a hatch overlay. */
    val demilitarizedZoneMask: ByteArray? = null,
    /** Rendered country colour per tag, for timeline recolouring of owner/controller fills. */
    val countryColorByTag: Map<String, Int> = emptyMap(),
    val bookmarks: List<MapBookmark> = emptyList(),
    /** Provinces.bmp colours missing from definition.csv, for the issue tint. */
    val unknownProvinceColors: Set<Int> = emptySet(),
    val warnings: List<MapWarning> = emptyList(),
    /** `has_dlc` names referenced by the loaded state histories. */
    val referencedDlcNames: Set<String> = emptySet(),
    /** DLC display names declared by installed dlc metadata (.dlc) files. */
    val installedDlcNames: Set<String> = emptySet()
) : PreviewSnapshot {
    override val isEmpty: Boolean
        get() = false

    fun borderChunksFor(mode: MapPreviewMode): List<MapBorderChunk> = borderChunks[mode].orEmpty()

    private val smoothBorderCache = java.util.concurrent.ConcurrentHashMap<MapPreviewMode, List<MapLineSegment>>()

    fun smoothBorderSegmentsFor(mode: MapPreviewMode): List<MapLineSegment> =
        smoothBorderCache.computeIfAbsent(mode, smoothBorderProvider)

    /** Only non-null when at least one state is a demilitarized zone. */
    val hasDemilitarizedZones: Boolean
        get() = demilitarizedZoneMask != null
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
