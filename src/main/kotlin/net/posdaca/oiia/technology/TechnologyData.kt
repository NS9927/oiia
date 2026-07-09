package net.posdaca.oiia.technology

data class TechnologyTreeData(
    val folderName: String,
    val technologies: List<TechnologyData> = emptyList()
)

data class TechnologyFolderData(
    val name: String,
    val x: Double = 0.0,
    val y: Double = 0.0
)

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
