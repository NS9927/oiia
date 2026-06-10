package net.posdaca.oiia.core

import icu.windea.pls.lang.settings.PlsSettings

object ParadoxLocalisationPreference {
    fun preferenceKey(): String = preferredLocale().orEmpty()

    fun languagePriority(path: String, fallback: List<String>, weight: Int = 1): Int {
        val lower = path.lowercase().replace('\\', '/')
        val tags = orderedTags(fallback)
        for ((index, tag) in tags.withIndex()) {
            if (tag in lower) return (tags.size - index) * weight
        }
        return 0
    }

    private fun orderedTags(fallback: List<String>): List<String> {
        val result = linkedSetOf<String>()
        preferredLocale()?.let { result.addAll(expandLocaleTags(it)) }
        fallback.forEach { result.addAll(expandLocaleTags(it)) }
        return result.toList()
    }

    private fun preferredLocale(): String? {
        return runCatching {
            PlsSettings.getInstance().state.preferredLocale?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun expandLocaleTags(locale: String): List<String> {
        val normalized = normalizeLocale(locale)
        if (normalized.isBlank()) return emptyList()
        val base = normalized.removePrefix("l_")
        val tags = when (base) {
            "zh", "zh_cn", "zh_hans", "cn", "simp_chinese", "chinese" -> listOf("simp_chinese", "l_simp_chinese", "chinese", "l_chinese")
            "en", "en_us", "en_gb", "english" -> listOf("english", "l_english")
            "pt", "pt_br", "braz_por" -> listOf("braz_por", "l_braz_por")
            "fr", "fr_fr", "french" -> listOf("french", "l_french")
            "de", "de_de", "german" -> listOf("german", "l_german")
            "pl", "pl_pl", "polish" -> listOf("polish", "l_polish")
            "ru", "ru_ru", "russian" -> listOf("russian", "l_russian")
            "es", "es_es", "spanish" -> listOf("spanish", "l_spanish")
            "ja", "ja_jp", "japanese" -> listOf("japanese", "l_japanese")
            else -> listOf(base, "l_$base")
        }
        return tags.filterTo(linkedSetOf()) { it.isNotBlank() }.toList()
    }

    private fun normalizeLocale(locale: String): String {
        return locale.trim().trim('"').lowercase()
            .replace('-', '_')
            .replace('.', '_')
            .replace(' ', '_')
            .replace('/', '_')
            .replace('\\', '_')
    }
}
