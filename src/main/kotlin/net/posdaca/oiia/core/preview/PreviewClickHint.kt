package net.posdaca.oiia.core.preview

import com.intellij.ui.LightweightHint
import net.posdaca.oiia.core.PreviewHintSupport
import java.awt.Point
import javax.swing.JComponent
import javax.swing.Timer

internal class PreviewClickHint(private val owner: JComponent) {
    private var lockedHint: LightweightHint? = null
    private var timer: Timer? = null

    val isVisible: Boolean get() = lockedHint != null

    fun schedule(point: Point, html: () -> String) {
        cancel()
        val hintPoint = Point(point)
        timer = Timer(PreviewHintSupport.multiClickInterval()) {
            timer = null
            if (owner.isShowing) show(hintPoint, html())
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun show(point: Point, html: String) {
        hide()
        lockedHint = PreviewHintSupport.showHint(owner, point, html) { hiddenHint ->
            if (lockedHint === hiddenHint) lockedHint = null
        }
    }

    fun cancel() {
        timer?.stop()
        timer = null
    }

    fun hide() {
        val hint = lockedHint
        lockedHint = null
        PreviewHintSupport.hideHint(hint)
    }

    fun dispose() {
        cancel()
        hide()
    }
}