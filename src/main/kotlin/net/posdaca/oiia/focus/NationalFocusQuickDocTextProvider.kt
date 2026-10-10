package net.posdaca.oiia.focus

import com.intellij.psi.PsiElement
import icu.windea.pls.ep.codeInsight.documentation.ParadoxQuickDocTextProvider
import icu.windea.pls.lang.psi.ParadoxDefinitionElement
import icu.windea.pls.lang.resolve.ParadoxLocalisationService
import icu.windea.pls.lang.util.ParadoxDefinitionManager

class NationalFocusQuickDocTextProvider : ParadoxQuickDocTextProvider {
    override fun getQuickDocText(element: PsiElement): String? {
        if (element !is ParadoxDefinitionElement) return null

        val info = ParadoxDefinitionManager.getInfo(element) ?: return null
        val type = info.type

        if (type != "focus" && type != "focus_tree" && type != "shared_focus") return null

        // Chronicle 3.0.4 dropped the singular getPresentableName; the primary presentable names
        // are what it used to return, with the raw definition name as the fallback.
        val name = ParadoxDefinitionManager.getPresentableNames(element).firstOrNull()
            ?: ParadoxDefinitionManager.getName(element)
            ?: return null

        val sb = StringBuilder()
        sb.appendLine("<b>").append(name).appendLine("</b>")
        sb.appendLine("<br>")
        sb.appendLine("<i>Type: ").append(type).appendLine("</i>")
        sb.appendLine("<br>")

        val images = ParadoxDefinitionManager.getRelatedImages(element, onlyPrimary = true)
        val firstImage = images.firstOrNull()
        if (firstImage != null) {
            val path = firstImage.virtualFile?.path
            if (path != null) {
                sb.appendLine("<img src=\"file://").append(path).appendLine("\" width=\"64\" height=\"64\"><br>")
            }
        }

        val localisations = ParadoxDefinitionManager.getRelatedLocalisations(element, onlyPrimary = true)
        val firstLoc = localisations.firstOrNull()
        if (firstLoc != null) {
            val text = ParadoxLocalisationService.resolvePresentableText(firstLoc)
            if (text != null && text != name) {
                sb.appendLine("<p>").append(text).appendLine("</p>")
            }
        }

        return sb.toString()
    }
}
