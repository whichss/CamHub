package com.camhub.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JTextField

class DesktopHubFrame(
    private val displayManager: DesktopDisplayManager = DesktopDisplayManager(),
    private val pgmController: PgmFullscreenController = PgmFullscreenController()
) : JFrame("CamHub Desktop") {
    private val displayModel = DefaultComboBoxModel<DisplayTarget>()
    private val displaySelector = JComboBox(displayModel)
    private val openPgmButton = JButton("OPEN PGM")
    private val closePgmButton = JButton("CLOSE")
    private val displayStatus = JLabel()
    private val discoveryStatus = JLabel("SEARCHING")
    private val manualEndpoint = JTextField()
    private val discoveredModel = DefaultListModel<CameraEndpoint>()
    private val discoveredList = JList(discoveredModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 3
        background = HubColors.surface
        foreground = HubColors.textSecondary
        selectionBackground = HubColors.accent.darker().darker()
        selectionForeground = Color.WHITE
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    }
    private val connectButton = JButton("CONNECT")
    private val connectAllButton = JButton("CONNECT ALL")
    private val refreshButton = JButton("REFRESH")
    private val disconnectButton = JButton("DISCONNECT")
    private val manualToggleButton = JButton("MANUAL IP")
    private val manualPanel = JPanel()
    private var discoveredEndpoints: List<CameraEndpoint> = emptyList()
    private val cameraEndpointIds = arrayOfNulls<String>(4)
    private val cameraPanels = Array(4) { index -> CameraViewPanel(index + 1) }
    private val cameraRotations = AtomicIntegerArray(4)
    private val pgmPreview = VideoOutputPanel("PGM", HubColors.program)
    @Volatile private var pgmSlot = 0
    private val cameraStatusLabels = Array(4) { index ->
        JLabel("CAM ${index + 1}   WAITING").apply {
            foreground = HubColors.textMuted
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = BorderFactory.createEmptyBorder(7, 0, 7, 0)
        }
    }
    private val networkFooter = statusLabel("NETWORK", "SEARCHING")
    private val videoFooter = statusLabel("VIDEO", "WAITING")
    private val latencyFooter = statusLabel("LATENCY", "-- ms")
    private val hubController = DesktopHubController(object : DesktopHubListener {
        override fun onDiscoveryChanged(endpoints: List<CameraEndpoint>, message: String) {
            SwingUtilities.invokeLater {
                discoveredEndpoints = endpoints
                val selectedId = discoveredList.selectedValue?.id
                discoveredModel.removeAllElements()
                endpoints.forEach(discoveredModel::addElement)
                selectedId?.let { id ->
                    endpoints.indexOfFirst { it.id == id }
                        .takeIf { it >= 0 }
                        ?.let(discoveredList::setSelectedIndex)
                }
                val discoveredCount = endpoints.size
                discoveryStatus.text = if (discoveredCount == 0) "SEARCHING" else "$discoveredCount FOUND"
                discoveryStatus.foreground = if (discoveredCount == 0) HubColors.textMuted else HubColors.accent
                networkFooter.text = "NETWORK  ${if (discoveredCount == 0) "SEARCHING" else "$discoveredCount FOUND"}"
                networkFooter.toolTipText = message
                updateConnectionButtons()
            }
        }

        override fun onCameraStateChanged(slot: Int, snapshot: CameraConnectionSnapshot) {
            cameraRotations.set(slot, snapshot.rotationDegrees)
            SwingUtilities.invokeLater {
                cameraEndpointIds[slot] = snapshot.endpoint.id
                cameraPanels.getOrNull(slot)?.setSnapshot(snapshot)
                cameraStatusLabels.getOrNull(slot)?.apply {
                    text = "CAM ${slot + 1}   ${snapshot.status}"
                    toolTipText = snapshot.detail
                    foreground = when (snapshot.status) {
                        DesktopCameraStatus.STREAMING -> HubColors.accent
                        DesktopCameraStatus.CONNECTED,
                        DesktopCameraStatus.CONNECTING,
                        DesktopCameraStatus.RECONNECTING -> HubColors.preview
                        DesktopCameraStatus.ERROR -> HubColors.program
                        else -> HubColors.textMuted
                    }
                }
                updateStreamingSummary()
                updateConnectionButtons()
            }
        }

        override fun onCameraImage(slot: Int, image: BufferedImage) {
            cameraPanels.getOrNull(slot)?.setImage(image)
            if (slot == pgmSlot) {
                val frame = DesktopVideoFrame(image, cameraRotations.get(slot))
                pgmPreview.setFrame(frame)
                pgmController.setFrame(frame)
            }
        }

        override fun onCameraRemoved(slot: Int) {
            SwingUtilities.invokeLater {
                cameraEndpointIds[slot] = null
                cameraPanels.getOrNull(slot)?.clear()
                if (slot == pgmSlot) {
                    pgmPreview.clearFrame()
                    pgmController.clearFrame()
                }
                cameraStatusLabels.getOrNull(slot)?.apply {
                    text = "CAM ${slot + 1}   WAITING"
                    foreground = HubColors.textMuted
                    toolTipText = null
                }
                updateStreamingSummary()
                updateConnectionButtons()
            }
        }
    })
    private var displaySignature = ""

    private val displayPollTimer = Timer(2_000) {
        refreshDisplays(preserveSelection = true)
    }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(960, 640)
        preferredSize = Dimension(1280, 780)
        background = HubColors.background
        contentPane = buildContent()
        pack()
        setLocationRelativeTo(null)

        openPgmButton.addActionListener {
            (displaySelector.selectedItem as? DisplayTarget)?.let(pgmController::open)
            updateDisplayStatus()
        }
        closePgmButton.addActionListener {
            pgmController.close()
            updateDisplayStatus()
        }
        connectButton.addActionListener {
            discoveredList.selectedValue?.let(hubController::connect)
        }
        connectAllButton.addActionListener {
            hubController.connectAll(discoveredEndpoints)
        }
        disconnectButton.addActionListener {
            discoveredList.selectedValue?.id?.let(hubController::disconnect)
        }
        refreshButton.addActionListener {
            hubController.refreshDiscovery()
        }
        manualToggleButton.addActionListener {
            manualPanel.isVisible = !manualPanel.isVisible
            manualToggleButton.text = if (manualPanel.isVisible) "HIDE MANUAL" else "MANUAL IP"
            contentPane.revalidate()
        }
        discoveredList.addListSelectionListener { updateConnectionButtons() }
        cameraPanels.forEachIndexed { index, panel ->
            panel.onSelected = { selectPgm(index) }
            panel.setProgramSelected(index == pgmSlot)
        }

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent?) {
                displayPollTimer.stop()
                hubController.close()
                pgmController.close()
            }
        })

        refreshDisplays(preserveSelection = false)
        displayPollTimer.start()
        hubController.start()
    }

    private fun buildContent(): JPanel = JPanel(BorderLayout(12, 12)).apply {
        background = HubColors.background
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(buildHeader(), BorderLayout.NORTH)
        add(
            JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                buildWorkspace(),
                buildControlPanel()
            ).apply {
                resizeWeight = 0.78
                dividerSize = 8
                border = BorderFactory.createEmptyBorder()
                background = HubColors.background
            },
            BorderLayout.CENTER
        )
        add(buildFooter(), BorderLayout.SOUTH)
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JLabel("CAMHUB DESKTOP").apply {
            foreground = HubColors.textPrimary
            font = Font(Font.SANS_SERIF, Font.BOLD, 24)
        }, BorderLayout.WEST)
        add(discoveryStatus.apply {
            foreground = HubColors.textMuted
            font = Font(Font.MONOSPACED, Font.BOLD, 12)
            horizontalAlignment = SwingConstants.RIGHT
        }, BorderLayout.EAST)
    }

    private fun buildWorkspace(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        background = HubColors.background
        add(sectionLabel("MULTIVIEW"), BorderLayout.NORTH)
        add(JPanel(GridLayout(2, 2, 8, 8)).apply {
            background = HubColors.background
            cameraPanels.forEach(::add)
        }, BorderLayout.CENTER)
        add(JPanel(GridLayout(1, 2, 8, 0)).apply {
            background = HubColors.background
            add(OutputPlaceholder("PVW", HubColors.preview))
            add(pgmPreview)
        }, BorderLayout.SOUTH)
    }

    private fun buildControlPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = HubColors.panel
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(HubColors.border),
            BorderFactory.createEmptyBorder(16, 16, 16, 16)
        )
        minimumSize = Dimension(260, 0)
        preferredSize = Dimension(300, 0)

        add(sectionLabel("DISCOVERED CAMERAS"))
        add(Box.createVerticalStrut(10))
        add(JScrollPane(discoveredList).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 108)
            preferredSize = Dimension(0, 108)
            border = BorderFactory.createLineBorder(HubColors.border)
        })
        add(Box.createVerticalStrut(8))
        add(JPanel(GridLayout(1, 2, 8, 0)).apply {
            isOpaque = false
            add(styleButton(connectButton, HubColors.accent))
            add(styleButton(connectAllButton, HubColors.preview))
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        })
        add(Box.createVerticalStrut(8))
        add(JPanel(GridLayout(1, 3, 6, 0)).apply {
            isOpaque = false
            add(styleButton(refreshButton, HubColors.accent))
            add(styleButton(disconnectButton, HubColors.program))
            add(styleButton(manualToggleButton, HubColors.textMuted))
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        })
        add(Box.createVerticalStrut(8))
        add(buildManualPanel())
        add(Box.createVerticalStrut(24))
        add(sectionLabel("CAMERAS"))
        add(Box.createVerticalStrut(10))
        cameraStatusLabels.forEach(::add)
        add(Box.createVerticalStrut(24))
        add(sectionLabel("PGM MONITOR"))
        add(Box.createVerticalStrut(12))
        displaySelector.maximumSize = Dimension(Int.MAX_VALUE, 34)
        add(displaySelector)
        add(Box.createVerticalStrut(10))
        add(JPanel(GridLayout(1, 2, 8, 0)).apply {
            isOpaque = false
            add(styleButton(openPgmButton, HubColors.accent))
            add(styleButton(closePgmButton, HubColors.textMuted))
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        })
        add(Box.createVerticalStrut(10))
        displayStatus.foreground = HubColors.textMuted
        displayStatus.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        add(displayStatus)
        add(Box.createVerticalGlue())
        add(JLabel("ESC closes PGM fullscreen").apply {
            foreground = HubColors.textMuted
            font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        })
    }

    private fun buildFooter(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply {
        isOpaque = false
        add(networkFooter)
        add(videoFooter)
        add(latencyFooter)
        add(statusLabel("RECORD", "IDLE"))
    }

    private fun connectManualEndpoint() {
        val value = manualEndpoint.text.trim()
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) {
            manualEndpoint.toolTipText = "Use IP:PORT, for example 192.168.0.20:42115"
            return
        }
        val host = value.take(separator).trim()
        val port = value.drop(separator + 1).trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65_535) {
            manualEndpoint.toolTipText = "Use IP:PORT, for example 192.168.0.20:42115"
            return
        }
        hubController.connectManual(host, port)
        manualEndpoint.text = ""
    }

    private fun buildManualPanel(): JPanel = manualPanel.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        manualEndpoint.apply {
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            toolTipText = "Fallback only: camera IP and signaling port"
        }
        add(manualEndpoint)
        add(Box.createVerticalStrut(6))
        add(styleButton(JButton("CONNECT IP:PORT").apply {
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            addActionListener { connectManualEndpoint() }
        }, HubColors.accent))
        maximumSize = Dimension(Int.MAX_VALUE, 72)
    }

    private fun updateConnectionButtons() {
        val selected = discoveredList.selectedValue
        val connectedIds = cameraEndpointIds.filterNotNull().toSet()
        connectButton.isEnabled = selected != null &&
            selected.id !in connectedIds && connectedIds.size < cameraPanels.size
        disconnectButton.isEnabled = selected?.id in connectedIds
        connectAllButton.isEnabled = discoveredEndpoints.any { it.id !in connectedIds } &&
            connectedIds.size < cameraPanels.size
    }

    private fun updateStreamingSummary() {
        val streaming = cameraPanels.count { it.isStreaming }
        videoFooter.text = "VIDEO  $streaming STREAMING"
        val latency = cameraPanels.mapNotNull { it.latencyMs }.minOrNull()
        latencyFooter.text = "LATENCY  ${latency?.let { "$it ms" } ?: "-- ms"}"
    }

    private fun selectPgm(slot: Int) {
        pgmSlot = slot.coerceIn(cameraPanels.indices)
        cameraPanels.forEachIndexed { index, panel ->
            panel.setProgramSelected(index == pgmSlot)
        }
        val image = cameraPanels[pgmSlot].currentImage()
        if (image == null) {
            pgmPreview.clearFrame()
            pgmController.clearFrame()
        } else {
            val frame = DesktopVideoFrame(image, cameraRotations.get(pgmSlot))
            pgmPreview.setFrame(frame)
            pgmController.setFrame(frame)
        }
    }

    private fun refreshDisplays(preserveSelection: Boolean) {
        val preferredId = if (preserveSelection) {
            (displaySelector.selectedItem as? DisplayTarget)?.id
        } else null
        val displays = displayManager.displays()
        val newSignature = displays.joinToString("|") {
            "${it.id}:${it.bounds.x}:${it.bounds.y}:${it.bounds.width}:${it.bounds.height}"
        }
        pgmController.reconcile(displays.mapTo(mutableSetOf()) { it.id })
        if (newSignature != displaySignature) {
            displaySignature = newSignature
            displayModel.removeAllElements()
            displays.forEach(displayModel::addElement)
            displaySelector.selectedItem = DisplayTargetSelection.select(displays, preferredId)
        }
        openPgmButton.isEnabled = displays.isNotEmpty()
        closePgmButton.isEnabled = pgmController.isOpen
        updateDisplayStatus()
    }

    private fun updateDisplayStatus() {
        displayStatus.text = when {
            displayModel.size == 0 -> "No display detected"
            pgmController.isOpen -> "PGM fullscreen active"
            else -> "${displayModel.size} display(s) detected"
        }
        closePgmButton.isEnabled = pgmController.isOpen
    }

    private fun sectionLabel(text: String) = JLabel(text).apply {
        foreground = HubColors.textSecondary
        font = Font(Font.MONOSPACED, Font.BOLD, 12)
    }

    private fun statusLabel(name: String, value: String) = JLabel("$name  $value").apply {
        foreground = if (value.contains("READY")) HubColors.accent else HubColors.textMuted
        font = Font(Font.MONOSPACED, Font.BOLD, 11)
    }

    private fun styleButton(button: JButton, color: Color): JButton = button.apply {
        isFocusPainted = false
        foreground = Color.WHITE
        background = color.darker()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color),
            BorderFactory.createEmptyBorder(7, 10, 7, 10)
        )
    }
}

private class CameraViewPanel(private val index: Int) : JPanel() {
    private val image = AtomicReference<BufferedImage?>()
    @Volatile private var snapshot: CameraConnectionSnapshot? = null
    @Volatile private var programSelected = false
    var onSelected: (() -> Unit)? = null

    val isStreaming: Boolean get() = snapshot?.status == DesktopCameraStatus.STREAMING
    val latencyMs: Long? get() = snapshot?.latencyMs

    init {
        background = HubColors.surface
        border = BorderFactory.createLineBorder(HubColors.border)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent?) {
                onSelected?.invoke()
            }
        })
    }

    fun setSnapshot(value: CameraConnectionSnapshot) {
        snapshot = value
        toolTipText = value.detail
        repaint()
    }

    fun setImage(value: BufferedImage) {
        image.set(value)
        repaint()
    }

    fun currentImage(): BufferedImage? = image.get()

    fun setProgramSelected(selected: Boolean) {
        programSelected = selected
        border = BorderFactory.createLineBorder(
            if (selected) HubColors.program else HubColors.border,
            if (selected) 2 else 1
        )
        repaint()
    }

    fun clear() {
        image.set(null)
        snapshot = null
        toolTipText = null
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val frame = image.get()
            if (frame != null) {
                DesktopVideoPainter.draw(
                    graphics = g,
                    frame = DesktopVideoFrame(frame, snapshot?.rotationDegrees ?: 0),
                    targetWidth = width,
                    targetHeight = height,
                    highQualitySpatialScale = false
                )
            }

            val current = snapshot
            val headline = current?.let { "CAM $index · ${it.name}" } ?: "CAM $index · NO SIGNAL"
            val detail = current?.let {
                buildString {
                    append(it.status)
                    if (it.width > 0) append(" · ${it.width}×${it.height}")
                    it.latencyMs?.let { latency -> append(" · ${latency}ms") }
                    if (it.framesDropped > 0) append(" · DROP ${it.framesDropped}")
                }
            } ?: "WAITING FOR CAMERA"

            g.color = Color(0, 0, 0, 175)
            g.fillRect(0, height - 48, width, 48)
            g.font = Font(Font.MONOSPACED, Font.BOLD, 12)
            g.color = if (isStreaming) HubColors.accent else HubColors.textSecondary
            g.drawString(headline.take(45), 12, height - 27)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
            g.color = HubColors.textMuted
            g.drawString(detail.take(58), 12, height - 10)
        } finally {
            g.dispose()
        }
    }
}

private class VideoOutputPanel(label: String, tally: Color) : JPanel() {
    private val frame = AtomicReference<DesktopVideoFrame?>()
    private val title = label

    init {
        preferredSize = Dimension(0, 130)
        background = Color.BLACK
        border = BorderFactory.createLineBorder(tally, 2)
    }

    fun setFrame(value: DesktopVideoFrame) {
        frame.set(value)
        repaint()
    }

    fun clearFrame() {
        frame.set(null)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            frame.get()?.let {
                DesktopVideoPainter.draw(g, it, width, height, highQualitySpatialScale = true)
            }
            g.color = HubColors.program
            g.font = Font(Font.MONOSPACED, Font.BOLD, 11)
            g.drawString("$title · 1080 SPATIAL", 10, 18)
        } finally {
            g.dispose()
        }
    }
}

private class OutputPlaceholder(label: String, tally: Color) : JPanel(BorderLayout()) {
    init {
        preferredSize = Dimension(0, 130)
        background = HubColors.surface
        border = BorderFactory.createLineBorder(tally, 2)
        add(JLabel(label, SwingConstants.CENTER).apply {
            foreground = tally
            font = Font(Font.SANS_SERIF, Font.BOLD, 20)
        }, BorderLayout.CENTER)
    }
}

private object HubColors {
    val background = Color(7, 11, 18)
    val panel = Color(14, 21, 32)
    val surface = Color(9, 15, 24)
    val border = Color(43, 57, 73)
    val textPrimary = Color(235, 241, 247)
    val textSecondary = Color(171, 185, 200)
    val textMuted = Color(105, 121, 139)
    val accent = Color(38, 198, 218)
    val preview = Color(54, 211, 153)
    val program = Color(239, 68, 68)
}
