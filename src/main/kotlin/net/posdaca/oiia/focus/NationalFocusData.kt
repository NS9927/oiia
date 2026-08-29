package net.posdaca.oiia.focus

import net.posdaca.oiia.core.preview.PreviewSnapshot

data class NationalFocusTreeData(
    val id: String,
    val country: String? = null,
    val focuses: List<FocusData> = emptyList(),
    val sharedFocuses: List<FocusData> = emptyList(),
    val sharedFocusReferences: List<String> = emptyList(),
    val defaultFocus: Boolean = false
)

data class FocusPreviewSnapshot(
    val trees: List<NationalFocusTreeData>
) : PreviewSnapshot {
    override val isEmpty: Boolean get() = trees.isEmpty()

    val allFocuses: List<FocusData>
        get() = trees.flatMap { it.focuses + it.sharedFocuses }

    fun withResolved(resolvedById: Map<String, FocusData>): FocusPreviewSnapshot {
        if (resolvedById.isEmpty()) return this
        return copy(
            trees = trees.map { tree ->
                tree.copy(
                    focuses = tree.focuses.map { it.withResolvedPresentation(resolvedById[it.id]) },
                    sharedFocuses = tree.sharedFocuses.map { it.withResolvedPresentation(resolvedById[it.id]) }
                )
            }
        )
    }
}

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

internal fun FocusData.withResolvedPresentation(resolved: FocusData?): FocusData {
    if (resolved == null) return this
    return copy(
        localizedName = resolved.localizedName ?: localizedName,
        localizedDescription = resolved.localizedDescription ?: localizedDescription,
        iconImagePath = resolved.iconImagePath ?: iconImagePath,
        completeTooltip = resolved.completeTooltip ?: completeTooltip
    )
}