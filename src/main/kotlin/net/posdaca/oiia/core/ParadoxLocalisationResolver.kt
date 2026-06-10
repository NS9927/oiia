package net.posdaca.oiia.core

import com.intellij.openapi.project.Project
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.search.ParadoxLocalisationSearch

internal class ParadoxLocalisationResolver(
    private val project: Project,
    private val fallbackLanguages: List<String>
) {
    private val cache = mutableMapOf<String, String?>()

    fun resolve(key: String?): String? {
        val normalized = key?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        synchronized(cache) {
            if (cache.containsKey(normalized)) return cache[normalized]
        }

        val resolved = runCatching {
            val selector = ParadoxLocalisationSearch.selector(project, null).distinct()
            ParadoxLocalisationSearch.searchNormal(normalized, selector)
                .findAll()
                .maxByOrNull { localisationPriority(it.containingFile?.virtualFile?.path) }
                ?.let { ParadoxLocalisationService.resolveLocalizedText(it) ?: it.value }
                ?.takeIf { it.isNotBlank() && it != normalized }
        }.getOrNull()

        synchronized(cache) {
            cache[normalized] = resolved
        }
        return resolved
    }

    private fun localisationPriority(path: String?): Int {
        return path?.let { ParadoxLocalisationPreference.languagePriority(it, fallbackLanguages) } ?: 0
    }
}
