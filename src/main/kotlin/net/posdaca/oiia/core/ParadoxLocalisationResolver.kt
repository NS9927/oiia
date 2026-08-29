package net.posdaca.oiia.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.search.ParadoxLocalisationSearch
import icu.windea.pls.lang.search.util.locale
import icu.windea.pls.localisation.psi.ParadoxLocalisationProperty
import icu.windea.pls.localisation.psi.ParadoxLocalisationPropertyList

internal class ParadoxLocalisationResolver(
    private val project: Project,
    private val fallbackLanguages: List<String>,
    /**
     * Optional file-based fallback tried between the preferred-locale PLS pass and the
     * general PLS pass; keeps every PLS resolution inside this single shared entry point.
     */
    private val fileFallback: ((String) -> String?)? = null
) {
    private val cache = mutableMapOf<String, String?>()
    private var cachePreferenceKey: String? = null
    @Volatile private var lastSweepMillis = Long.MIN_VALUE

    fun resolve(key: String?): String? {
        val normalized = key?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        val preferenceKey = ParadoxLocalisationPreference.cacheKey(fallbackLanguages)
        synchronized(cache) {
            val now = System.currentTimeMillis()
            if (cachePreferenceKey != preferenceKey || now - lastSweepMillis > CACHE_TTL_MS) {
                cache.clear()
                cachePreferenceKey = preferenceKey
                lastSweepMillis = now
            }
            if (cache.containsKey(normalized)) return cache[normalized]
        }

        val resolved = resolveWithPls(normalized, preferCurrentLocaleOnly = true)
            ?: fileFallback?.invoke(normalized)
            ?: resolveWithPls(normalized, preferCurrentLocaleOnly = false)

        synchronized(cache) {
            if (cachePreferenceKey == preferenceKey) cache[normalized] = resolved
        }
        return resolved
    }

    fun resolveAll(keys: Iterable<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (key in keys) {
            resolve(key)?.let { result[key] = it }
        }
        return result
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
            cachePreferenceKey = null
        }
    }

    private fun resolveWithPls(key: String, preferCurrentLocaleOnly: Boolean): String? {
        return runCatching {
            ApplicationManager.getApplication().runReadAction<String?> {
                val preferredLocale = ParadoxLocalisationPreference.preferredLocaleConfig()
                val selector = ParadoxLocalisationSearch.selector(project, null)
                    .let { if (preferCurrentLocaleOnly) it.locale(preferredLocale) else it }
                    .distinct()
                ParadoxLocalisationSearch.searchNormal(key, selector)
                    .findAll()
                    .maxByOrNull { localisationPriority(it) }
                    ?.let { ParadoxLocalisationService.resolvePresentableText(it) ?: it.value }
                    ?.takeIf { it.isNotBlank() && it != key }
            }
        }.getOrNull()
    }

    private fun localisationPriority(property: ParadoxLocalisationProperty): Int {
        val localisationFile = property.containingFile
        val locale = (property.parent as? ParadoxLocalisationPropertyList)?.locale?.name
        val paths = listOfNotNull(
            locale,
            localisationFile?.virtualFile?.path,
            localisationFile?.name
        )
        return paths.maxOfOrNull { localisationPriority(it) } ?: 0
    }

    private fun localisationPriority(path: String?): Int {
        return path?.let { ParadoxLocalisationPreference.languagePriority(it, fallbackLanguages) } ?: 0
    }

    companion object {
        /**
         * Entries live at most this long, so edits to `.yml` files become visible without
         * waiting for a preference change. PLS lookups are cheap enough to redo per refresh.
         */
        private const val CACHE_TTL_MS = 2000L
    }
}
