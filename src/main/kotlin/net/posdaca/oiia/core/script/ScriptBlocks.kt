package net.posdaca.oiia.core.script

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import icu.windea.pls.script.psi.ParadoxScriptBlock

internal object ScriptBlocks {
    fun innerIndent(project: Project, block: ParadoxScriptBlock): String {
        val firstProperty = block.propertyList.firstOrNull() ?: return "\t"
        val document = PsiDocumentManager.getInstance(project).getDocument(firstProperty.containingFile) ?: return "\t"
        val line = document.getLineNumber(firstProperty.textOffset)
        val lineStart = document.getLineStartOffset(line)
        val prefix = document.charsSequence.subSequence(lineStart, firstProperty.textOffset).toString()
        val indent = prefix.takeWhile { it == ' ' || it == '\t' }
        return indent.ifEmpty { "\t" }
    }
}
