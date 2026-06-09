package net.posdaca.oiia.technology

data class TechnologyTreeData(
    val folderName: String,
    val technologies: List<TechnologyData> = emptyList()
)

data class TechnologyData(
    val id: String,
    val folderName: String? = null,
    val iconImagePath: String? = null,
    val localizedName: String? = null,
    val localizedDescription: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val startYear: Int? = null,
    val categories: List<String> = emptyList(),
    val leadsTo: List<String> = emptyList(),
    val sourceFilePath: String? = null,
    val sourceLine: Int = 0
) {
    val displayName: String get() = localizedName ?: id
}
