package net.posdaca.oiia.core

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.ui.scale.JBUIScale
import java.awt.Point
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.JComponent

object PreviewHintSupport {
    fun multiClickInterval(): Int {
        val interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int
        return (interval ?: 250).coerceAtLeast(150)
    }

    fun showHint(
        owner: JComponent,
        point: Point,
        html: String,
        onHidden: (LightweightHint) -> Unit = {}
    ): LightweightHint {
        val content = HintUtil.createInformationLabel(html)
        content.border = BorderFactory.createCompoundBorder(
            HintUtil.createHintBorder(),
            BorderFactory.createEmptyBorder(
                JBUIScale.scale(10),
                JBUIScale.scale(12),
                JBUIScale.scale(10),
                JBUIScale.scale(12)
            )
        )
        content.background = HintUtil.getInformationColor()
        content.isOpaque = true

        val hintPoint = computeHintPoint(owner, point, content)
        val hint = LightweightHint(content)
        hint.setForceShowAsPopup(true)
        hint.setCancelOnClickOutside(true)
        hint.setCancelOnOtherWindowOpen(true)
        hint.addHintListener { onHidden(hint) }

        val hintHint = HintHint(owner, hintPoint)
            .setTextBg(HintUtil.getInformationColor())
            .setTextFg(JBColor.foreground())
            .setBorderColor(HintUtil.getHintBorderColor())
            .setShowImmediately(true)
        hint.show(owner, hintPoint.x, hintPoint.y, owner, hintHint)
        return hint
    }

    fun hideHint(hint: LightweightHint?) {
        if (hint?.isVisible == true) hint.hide()
    }

    private fun computeHintPoint(owner: JComponent, point: Point, content: JComponent): Point {
        val gap = JBUIScale.scale(12)
        val viewport = owner.visibleRect
        val size = content.preferredSize
        val rightLimit = viewport.x + viewport.width - gap
        val bottomLimit = viewport.y + viewport.height - gap
        var x = point.x + gap
        var y = point.y + gap
        if (x + size.width > rightLimit) x = point.x - size.width - gap
        if (y + size.height > bottomLimit) y = point.y - size.height - gap
        return Point(x.coerceAtLeast(viewport.x + gap), y.coerceAtLeast(viewport.y + gap))
    }
}
