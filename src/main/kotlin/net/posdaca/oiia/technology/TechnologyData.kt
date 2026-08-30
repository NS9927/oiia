package net.posdaca.oiia.technology

import net.posdaca.oiia.core.preview.PreviewSnapshot

data class TechnologyTreeData(
    val folderName: String,
    val technologies: List<TechnologyData> = emptyList(),
    /** Root technology of this connected component; matches the `<startTech>_tree` gridbox naming in the game GUI. */
    val startTechnology: String? = null,
    /** Grid layout declared by the matching gridbox in `countrytechtreeview.gui`, when found. */
    val layout: TechTreeGridLayout? = null
)

data class TechTreeGridLayout(
    /** Gridbox `format`: up / down / left / right — the tree's main axis. */
    val format: String? = null,
    val slotWidth: Int = 0,
    val slotHeight: Int = 0,
    /** Gridbox position inside the folder view, used to keep trees in the game's page order. */
    val originX: Int = 0,
    val originY: Int = 0
) {
    val isHorizontal: Boolean get() = format.equals("left", true) || format.equals("right", true)
}

data class TechnologyFolderData(
    val name: String,
    val x: Double = 0.0,
    val y: Double = 0.0
)

data class TechnologyPreviewSnapshot(
    val trees: List<TechnologyTreeData>
) : PreviewSnapshot {
    override val isEmpty: Boolean get() = trees.isEmpty()

    val allTechnologies: List<TechnologyData>
        get() = trees.flatMap { it.technologies }.distinctBy { it.id }

    fun withResolved(resolvedById: Map<String, TechnologyData>): TechnologyPreviewSnapshot {
        if (resolvedById.isEmpty()) return this
        return copy(
            trees = trees.map { tree ->
                tree.copy(technologies = tree.technologies.map { it.withResolvedPresentation(resolvedById[it.id]) })
            }
        )
    }
}

data class TechnologyData(
    val id: String,
    val folderName: String? = null,
    val folders: Map<String, TechnologyFolderData> = emptyMap(),
    val iconImagePath: String? = null,
    val localizedName: String? = null,
    val localizedDescription: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val startYear: Int? = null,
    val categories: List<String> = emptyList(),
    val leadsTo: List<String> = emptyList(),
    val xor: List<String> = emptyList(),
    val subTechnologies: List<String> = emptyList(),
    val enableEquipments: Boolean = false,
    val forceUseSmallTechLayout: Boolean = false,
    val sourceFilePath: String? = null,
    val sourceLine: Int = 0
) {
    val displayName: String get() = localizedName ?: id

    fun positionIn(folder: String): TechnologyFolderData? {
        return folders[folder]
            ?: folderName?.takeIf { it == folder }?.let { TechnologyFolderData(it, x, y) }
            ?: if (folders.isEmpty() && folderName == null) TechnologyFolderData(folder, x, y) else null
    }
}

internal fun TechnologyData.withResolvedPresentation(resolved: TechnologyData?): TechnologyData {
    if (resolved == null) return this
    return copy(
        localizedName = resolved.localizedName ?: localizedName,
        localizedDescription = resolved.localizedDescription ?: localizedDescription,
        iconImagePath = resolved.iconImagePath ?: iconImagePath
    )
}