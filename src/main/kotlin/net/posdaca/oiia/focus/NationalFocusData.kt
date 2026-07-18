package net.posdaca.oiia.focus

data class NationalFocusTreeData(
    val id: String,
    val country: String? = null,
    val focuses: List<FocusData> = emptyList(),
    val sharedFocuses: List<FocusData> = emptyList(),
    val sharedFocusReferences: List<String> = emptyList(),
    val defaultFocus: Boolean = false
)

data class FocusData(
    val id: String,
    val iconKey: String? = null,
    val iconImagePath: String? = null,
    val text: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val cost: Double = 10.0,
    val prerequisites: List<String> = emptyList(),
    val mutuallyExclusive: List<String> = emptyList(),
    val relativePositionId: String? = null,
    val localizedName: String? = null,
    val localizedDescription: String? = null,
    val descriptionKey: String? = null,
    val available: String? = null,
    val reward: String? = null,
    val aiWillDo: Double? = null,
    val isSharedFocus: Boolean = false,
    val completeTooltip: String? = null,
    val prerequisitesText: String? = null,
    val sourceFilePath: String? = null,
    val sourceOffset: Int = -1,
    val sourceLine: Int = 0
) {
    val displayName: String get() = localizedName ?: id
}
