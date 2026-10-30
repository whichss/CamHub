package com.camhub.desktop

import java.awt.Color
import java.awt.Dialog
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke

object ScreenRegionGeometry {
    fun fromDrag(displayBounds: Rectangle, start: Point, end: Point): Rectangle {
        val left = minOf(start.x, end.x)
        val top = minOf(start.y, end.y)
        val right = maxOf(start.x, end.x)
        val bottom = maxOf(start.y, end.y)
        return Rectangle(
            displayBounds.x + left,
            displayBounds.y + top,
            right - left,
            bottom - top
        ).intersection(displayBounds)
    }
}

/** Full-screen drag selector; Enter accepts the outlined area and Escape cancels. */
object ScreenRegionSelector {
    fun select(owner: Window, display: DisplayTarget): Rectangle? {
        val result = SelectionResult()
        lateinit var dialog: JDialog
        val panel = SelectionPanel(display.bounds, result) { dialog.dispose() }
        dialog = JDialog(owner, "Select screen area", Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            bounds = Rectangle(display.bounds)
            contentPane = panel
            rootPane.registerKeyboardAction(
                { dispose() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
            )
            rootPane.registerKeyboardAction(
                { panel.acceptSelection() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
            )
        }
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent?) {
                panel.requestFocusInWindow()
            }
        })
        dialog.isVisible = true
        return result.region
    }

    private class SelectionResult {
        @Volatile var region: Rectangle? = null
    }

    private class SelectionPanel(
        private val displayBounds: Rectangle,
        private val result: SelectionResult,
        private val close: () -> Unit
    ) : JPanel() {
        private var dragStart: Point? = null
        private var dragEnd: Point? = null
        private var completedSelection: Rectangle? = null

        init {
            isOpaque = false
            isFocusable = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
                        close()
                        return
                    }
                    dragStart = event.point
                    dragEnd = event.point
                    completedSelection = null
                    repaint()
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (dragStart == null) return
                    dragEnd = event.point
                    completedSelection = ScreenRegionGeometry.fromDrag(displayBounds, dragStart!!, event.point)
                    dragStart = null
                    repaint()
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    dragEnd = event.point
                    repaint()
                }
            })
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "accept")
            actionMap.put("accept", object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) = acceptSelection()
            })
        }

        fun acceptSelection() {
            val selected = completedSelection ?: return
            if (selected.width < MIN_REGION_SIZE || selected.height < MIN_REGION_SIZE) return
            result.region = selected
            close()
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.color = Color(4, 10, 18, 145)
                g.fillRect(0, 0, width, height)
                val current = completedSelection ?: dragStart?.let { start ->
                    dragEnd?.let { ScreenRegionGeometry.fromDrag(displayBounds, start, it) }
                }
                if (current != null && current.width > 0 && current.height > 0) {
                    val local = Rectangle(current).apply {
                        translate(-displayBounds.x, -displayBounds.y)
                    }
                    g.color = Color(38, 198, 218, 48)
                    g.fill(local)
                    g.color = Color(38, 198, 218)
                    g.stroke = java.awt.BasicStroke(3f)
                    g.draw(local)
                    g.color = Color.WHITE
                    g.drawString("${current.width} × ${current.height}", local.x + 12, local.y + 24)
                }
                val hint = if (completedSelection == null) {
                    "Drag to select an area · Enter to apply · Esc to cancel"
                } else {
                    "${completedSelection!!.width} × ${completedSelection!!.height} · Enter to apply · drag again or Esc to cancel"
                }
                g.font = g.font.deriveFont(java.awt.Font.BOLD, 18f)
                val metrics = g.fontMetrics
                val x = (width - metrics.stringWidth(hint)) / 2
                val y = 44
                g.color = Color(0, 0, 0, 190)
                g.fillRoundRect(x - 16, y - metrics.ascent - 8, metrics.stringWidth(hint) + 32, metrics.height + 16, 12, 12)
                g.color = Color.WHITE
                g.drawString(hint, x, y)
            } finally {
                g.dispose()
            }
        }
    }

    private const val MIN_REGION_SIZE = 16
}
