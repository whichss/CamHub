package com.camhub.desktop

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

data class DisplayTarget(
    val id: String,
    val label: String,
    val bounds: Rectangle,
    val refreshRateHz: Int,
    val isPrimary: Boolean,
    val device: GraphicsDevice? = null
) {
    override fun toString(): String = label
}

object DisplayTargetSelection {
    fun select(displays: List<DisplayTarget>, preferredId: String?): DisplayTarget? =
        displays.firstOrNull { it.id == preferredId }
            ?: displays.firstOrNull { it.isPrimary }
            ?: displays.firstOrNull()
}

class DesktopDisplayManager {
    fun displays(): List<DisplayTarget> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        return environment.screenDevices.mapIndexed { index, device ->
            val mode = device.displayMode
            val bounds = device.defaultConfiguration.bounds
            val refresh = mode.refreshRate.takeIf { it > 0 } ?: 0
            DisplayTarget(
                id = device.getIDstring(),
                label = buildString {
                    append(if (device == primary) "Primary" else "Display ${index + 1}")
                    append(" · ${bounds.width}×${bounds.height}")
                    if (refresh > 0) append(" @ ${refresh}Hz")
                },
                bounds = Rectangle(bounds),
                refreshRateHz = refresh,
                isPrimary = device == primary,
                device = device
            )
        }
    }
}

class PgmFullscreenController {
    private var window: JFrame? = null
    private var activeDisplayId: String? = null
    private val currentFrame = AtomicReference<DesktopVideoFrame?>()
    private val transitionAlpha = AtomicReference(0f)

    val isOpen: Boolean get() = window?.isDisplayable == true

    fun isOpenOn(displayId: String): Boolean = isOpen && activeDisplayId == displayId

    fun currentFrame(): DesktopVideoFrame? = currentFrame.get()

    fun transitionAlpha(): Float = transitionAlpha.get()

    fun open(display: DisplayTarget) {
        close()
        val device = display.device ?: return
        val frame = JFrame("CamHub PGM", device.defaultConfiguration).apply {
            isUndecorated = true
            background = Color.BLACK
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            contentPane = PgmOutputPanel(currentFrame, transitionAlpha)
            rootPane.border = BorderFactory.createEmptyBorder()
            rootPane.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "close-pgm"
            )
            rootPane.actionMap.put("close-pgm", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) = close()
            })
            bounds = Rectangle(display.bounds)
            isAlwaysOnTop = true
            isVisible = true
            toFront()
            requestFocus()
        }
        window = frame
        activeDisplayId = display.id
    }

    fun reconcile(displays: List<DisplayTarget>) {
        val id = activeDisplayId ?: return
        val target = displays.firstOrNull { it.id == id }
        if (target == null) {
            close()
        } else if (window?.bounds != target.bounds) {
            window?.bounds = Rectangle(target.bounds)
        }
    }

    fun setFrame(frame: DesktopVideoFrame) {
        currentFrame.set(frame)
        window?.contentPane?.repaint()
    }

    fun clearFrame() {
        currentFrame.set(null)
        window?.contentPane?.repaint()
    }

    fun setTransitionAlpha(alpha: Float) {
        transitionAlpha.set(alpha.coerceIn(0f, 1f))
        window?.contentPane?.repaint()
    }

    fun close() {
        val current = window
        window = null
        activeDisplayId = null
        current?.isAlwaysOnTop = false
        current?.dispose()
    }
}

private class PgmOutputPanel(
    private val currentFrame: AtomicReference<DesktopVideoFrame?>,
    private val transitionAlpha: AtomicReference<Float>
) : JPanel() {
    init {
        background = Color.BLACK
        isFocusable = true
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            val frame = currentFrame.get()
            if (frame != null) {
                g.color = Color.BLACK
                g.fillRect(0, 0, width, height)
                DesktopVideoPainter.draw(
                    graphics = g,
                    frame = frame,
                    targetWidth = width,
                    targetHeight = height,
                    highQualitySpatialScale = true
                )
            }
            val alpha = transitionAlpha.get()
            if (alpha > 0f) {
                g.color = Color(0, 0, 0, (alpha * 255).toInt())
                g.fillRect(0, 0, width, height)
            }
            if (frame != null) return
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(
                0f,
                0f,
                Color(8, 13, 22),
                width.toFloat(),
                height.toFloat(),
                Color(2, 5, 10)
            )
            g.fillRect(0, 0, width, height)

            val title = "PGM OUTPUT"
            val status = "NO SIGNAL"
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (height / 28).coerceIn(24, 64))
            g.color = Color(230, 239, 248)
            drawCentered(g, title, height / 2 - 18)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, (height / 55).coerceIn(14, 30))
            g.color = Color(76, 201, 240)
            drawCentered(g, status, height / 2 + 28)
        } finally {
            g.dispose()
        }
    }

    private fun drawCentered(graphics: Graphics2D, text: String, centerY: Int) {
        val metrics = graphics.fontMetrics
        graphics.drawString(
            text,
            (width - metrics.stringWidth(text)) / 2,
            centerY - (metrics.ascent + metrics.descent) / 2
        )
    }
}
