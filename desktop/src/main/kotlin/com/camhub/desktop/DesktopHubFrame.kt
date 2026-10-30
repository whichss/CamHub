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
import java.awt.Rectangle
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingWorker

class DesktopHubFrame(
    private val displayManager: DesktopDisplayManager = DesktopDisplayManager(),
    private val pgmController: PgmFullscreenController = PgmFullscreenController()
) : JFrame("CamHub Desktop") {
    private val displayModel = DefaultComboBoxModel<DisplayTarget>()
    private val displaySelector = JComboBox(displayModel)
    private val openPgmButton = JButton("OPEN PGM")
    private val closePgmButton = JButton("CLOSE")
    private val cutButton = JButton("CUT")
    private val autoButton = JButton("AUTO 300ms")
    private val livePtzLockButton = JToggleButton("LIVE PTZ LOCK · OFF")
    private val screenInputButton = JButton("SCREEN INPUT")
    private val desktopCameraSourceButton = JButton("START CAMERA SOURCE")
    private val obsSourceButton = JButton("ADD PGM TO OBS")
    private val obsStopButton = JButton("STOP OBS OUTPUT").apply { isEnabled = false }
    private val pgmRecordButton = JButton("RECORD PGM")
    private val displayStatus = JLabel()
    private val screenStatus = JLabel("Screen input inactive")
    private val desktopCameraSourceStatus = JLabel("Not advertised")
    private val desktopCameraPairingInfo = JLabel("VERIFY / PAIR PIN will appear when started")
    private val obsStatus = JLabel("OBS output inactive")
    private val pgmRecordStatus = statusLabel("RECORD", "IDLE")
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
    private val previewFramings = Array(4) { AtomicReference(DesktopFraming.IDENTITY) }
    private val programFramings = Array(4) { AtomicReference(DesktopFraming.IDENTITY) }
    private val remotePtzFramings = Array(4) { AtomicReference(DesktopRemoteFraming()) }
    private val pendingRemotePtz = arrayOfNulls<DesktopFraming>(4)
    private val remotePtzDebounceTimer = Timer(120) { flushRemotePtzRequests() }.apply { isRepeats = false }
    private val previewOutput = VideoOutputPanel("PVW", HubColors.preview)
    private val pgmPreview = VideoOutputPanel("PGM", HubColors.program)
    private val sourceSwitcher = PgmSourceSwitcher()
    @Volatile private var selectedCameraPgmSlot = 0
    private var autoTransitionTimer: Timer? = null
    private var autoTransitionPhase = 0
    private var autoTransitionStartedAt = 0L
    @Volatile private var screenCaptureRunning = false
    @Volatile private var screenCaptureDisplayId: String? = null
    @Volatile private var screenCaptureRegion: Rectangle? = null
    @Volatile private var desktopCameraServer: DesktopCameraSourceServer? = null
    @Volatile private var closing = false
    private val desktopCameraSourceStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val desktopCameraSourceGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val latestScreenFrame = AtomicReference<DesktopVideoFrame?>()
    private val currentProgramFrame = AtomicReference<DesktopVideoFrame?>()
    private val noSignalRecordingFrame = DesktopVideoFrame(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB))
    private val pgmRecorder = DesktopPgmRecorder(onState = { state ->
        SwingUtilities.invokeLater {
            pgmRecordButton.text = when (state.state) {
                DesktopRecordingState.STARTING -> "STARTING…"
                DesktopRecordingState.RECORDING -> "STOP RECORDING"
                DesktopRecordingState.STOPPING -> "SAVING…"
                else -> "RECORD PGM"
            }
            pgmRecordButton.isEnabled = state.state != DesktopRecordingState.STOPPING
            pgmRecordStatus.text = when (state.state) {
                DesktopRecordingState.IDLE -> state.file?.let { "RECORD  SAVED · ${it.fileName}" } ?: "RECORD  IDLE"
                DesktopRecordingState.STARTING -> "RECORD  PREPARING"
                DesktopRecordingState.RECORDING -> "RECORD  ${state.framesWritten} FRAMES"
                DesktopRecordingState.STOPPING -> "RECORD  FINALIZING"
                DesktopRecordingState.FAILED -> "RECORD  ERROR · ${state.message.take(70)}"
            }
            pgmRecordStatus.toolTipText = state.file?.toString() ?: state.message
        }
    })
    private val screenPgmRefreshTimer = Timer(33) {
        if (sourceSwitcher.program == PgmSource.DesktopScreen ||
            sourceSwitcher.preview == PgmSource.DesktopScreen) {
            latestScreenFrame.get()?.let { frame ->
                updateSourceFrame(PgmSource.DesktopScreen, frame)
            }
        }
    }
    private val obsOutput = DesktopPgmObsOutput(
        frameProvider = pgmController::currentFrame,
        transitionAlphaProvider = pgmController::transitionAlpha
    )
    private var obsStatusOverrideUntilNanos = 0L
    private val obsOutputStatusTimer = Timer(500) { refreshObsOutputStatus() }
    private val screenCapture = DesktopScreenCapture(
        onFrame = { image ->
            latestScreenFrame.set(DesktopVideoFrame(image))
            desktopCameraServer?.offerScreenFrame(image)
        },
        onError = { message, isCurrent ->
            SwingUtilities.invokeLater {
                if (!isCurrent()) return@invokeLater
                screenCaptureRunning = false
                screenCaptureDisplayId = null
                screenCaptureRegion = null
                screenInputButton.text = "SCREEN INPUT"
                screenStatus.text = "Screen capture unavailable: ${message.take(72)}"
                stopDesktopCameraSource()
                sourceSwitcher.clear(PgmSource.DesktopScreen)
                restoreCameraPreview()
                refreshBusOutputs()
            }
        }
    )
    private val cameraStatusLabels = Array(4) { index ->
        JLabel("CAM ${index + 1}   WAITING").apply {
            foreground = HubColors.textMuted
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = BorderFactory.createEmptyBorder(7, 0, 7, 0)
        }
    }
    private val networkFooter = statusLabel("NETWORK", "SEARCHING")
    private val videoFooter = statusLabel("VIDEO", "WAITING")
    private val latencyFooter = statusLabel("MAX CAM C→D P50/P95/P99", "--/--/-- ms")
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
                val displayCenter = DesktopPtzCoordinates.cameraToDisplay(
                    snapshot.remotePtzCenterX.toDouble(),
                    snapshot.remotePtzCenterY.toDouble(),
                    snapshot.rotationDegrees
                )
                remotePtzFramings[slot].set(
                    DesktopRemoteFraming(
                        zoom = snapshot.remotePtzZoom.toDouble().coerceIn(1.0, 32.0),
                        centerX = displayCenter.first,
                        centerY = displayCenter.second
                    )
                )
                cameraEndpointIds[slot] = snapshot.endpoint.id
                cameraPanels.getOrNull(slot)?.setSnapshot(snapshot)
                if (snapshot.status == DesktopCameraStatus.RECONNECTING ||
                    snapshot.status == DesktopCameraStatus.DISCONNECTED ||
                    snapshot.status == DesktopCameraStatus.ERROR) {
                    cameraPanels.getOrNull(slot)?.clearImage()
                    if (sourceSwitcher.program == PgmSource.Camera(slot)) refreshProgramOutput()
                    if (sourceSwitcher.preview == PgmSource.Camera(slot)) refreshPreviewOutput()
                }
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
            updateSourceFrame(PgmSource.Camera(slot), DesktopVideoFrame(image, cameraRotations.get(slot)))
        }

        override fun onCameraRemoved(slot: Int) {
            SwingUtilities.invokeLater {
                cameraEndpointIds[slot] = null
                pendingRemotePtz[slot] = null
                previewFramings[slot].set(DesktopFraming.IDENTITY)
                programFramings[slot].set(DesktopFraming.IDENTITY)
                remotePtzFramings[slot].set(DesktopRemoteFraming())
                cameraPanels.getOrNull(slot)?.clear()
                sourceSwitcher.clear(PgmSource.Camera(slot))
                refreshBusOutputs()
                updateCameraTallies()
                updateTransitionButtons()
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
            (displaySelector.selectedItem as? DisplayTarget)?.let { display ->
                if (sourceSwitcher.program == PgmSource.DesktopScreen && screenCaptureDisplayId == display.id) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Choose a different display for PGM output to avoid capturing the output back into itself.",
                        "Screen Capture Feedback",
                        JOptionPane.WARNING_MESSAGE
                    )
                } else {
                    pgmController.open(display)
                }
            }
            updateDisplayStatus()
        }
        closePgmButton.addActionListener {
            pgmController.close()
            updateDisplayStatus()
        }
        cutButton.addActionListener { cutPreviewToProgram() }
        autoButton.addActionListener { startAutoTransition() }
        screenInputButton.addActionListener {
            if (screenCaptureRunning) {
                stopDesktopCameraSource()
                screenCapture.close()
                screenCaptureRunning = false
                screenCaptureDisplayId = null
                screenCaptureRegion = null
                screenInputButton.text = "SCREEN INPUT"
                screenStatus.text = "Screen input inactive"
                sourceSwitcher.clear(PgmSource.DesktopScreen)
                restoreCameraPreview()
                refreshBusOutputs()
            } else {
                startScreenInput()
            }
        }
        desktopCameraSourceButton.addActionListener {
            if (desktopCameraServer?.isRunning == true) {
                stopDesktopCameraSource()
            } else {
                if (!screenCaptureRunning && !startScreenInput()) return@addActionListener
                startDesktopCameraSource()
            }
        }
        obsSourceButton.addActionListener {
            runCatching { obsOutput.start() }
                .onSuccess { url ->
                    obsStopButton.isEnabled = true
                    obsStatus.text = "Local PGM source ready · connecting to OBS…"
                    obsStatus.toolTipText = url
                    obsStatusOverrideUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    obsOutputStatusTimer.start()
                    connectPgmToObs(url)
                }
                .onFailure { error ->
                    obsStatus.text = "OBS output unavailable"
                    JOptionPane.showMessageDialog(
                        this,
                        error.message ?: "Could not start the local OBS source.",
                        "CamHub OBS Output",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
        }
        obsStopButton.addActionListener {
            obsOutputStatusTimer.stop()
            obsStatusOverrideUntilNanos = 0L
            obsOutput.close()
            obsStopButton.isEnabled = false
            obsStatus.text = "OBS output inactive"
            obsStatus.toolTipText = null
        }
        pgmRecordButton.addActionListener { togglePgmRecording() }
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
            panel.onSelected = { selectPreview(index) }
        }
        previewOutput.onPtzGesture = { gesture -> applyPtzGesture(sourceSwitcher.preview, false, gesture) }
        pgmPreview.onPtzGesture = { gesture ->
            if (livePtzLockButton.isSelected) applyPtzGesture(sourceSwitcher.program, true, gesture)
        }
        livePtzLockButton.addActionListener {
            livePtzLockButton.text = if (livePtzLockButton.isSelected) {
                "LIVE PTZ LOCK · ON"
            } else {
                "LIVE PTZ LOCK · OFF"
            }
        }
        updateCameraTallies()
        updateTransitionButtons()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent?) {
                closing = true
                displayPollTimer.stop()
                screenPgmRefreshTimer.stop()
                obsOutputStatusTimer.stop()
                screenCapture.close()
                autoTransitionTimer?.stop()
                remotePtzDebounceTimer.stop()
                pendingRemotePtz.fill(null)
                Thread({ pgmRecorder.close() }, "DesktopPgmRecorderClose").apply {
                    isDaemon = true
                    start()
                }
                desktopCameraServer?.let { source ->
                    Thread({ source.close() }, "DesktopCameraSourceClose").apply {
                        isDaemon = true
                        start()
                    }
                }
                desktopCameraServer = null
                obsOutput.close()
                hubController.close()
                pgmController.close()
            }
        })

        refreshDisplays(preserveSelection = false)
        displayPollTimer.start()
        screenPgmRefreshTimer.start()
        hubController.start()
    }

    private fun connectPgmToObs(url: String) {
        val portField = JTextField("4455", 7)
        val passwordField = JPasswordField(18)
        val fields = JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(JLabel("OBS WebSocket port (default 4455)"))
            add(portField)
            add(JLabel("OBS WebSocket password (leave empty if authentication is disabled)"))
            add(passwordField)
            add(JLabel("CamHub connects to OBS on this computer and adds a low-buffering Media Source to the current scene."))
        }
        if (JOptionPane.showConfirmDialog(
                this,
                fields,
                "Add CamHub PGM to OBS",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            ) != JOptionPane.OK_OPTION
        ) return

        val port = portField.text.trim().toIntOrNull()?.takeIf { it in 1..65_535 }
        if (port == null) {
            JOptionPane.showMessageDialog(this, "Enter a valid OBS WebSocket port.", "OBS connection", JOptionPane.ERROR_MESSAGE)
            return
        }
        val password = passwordField.password
        val manualSourceUrl = java.net.URI(url).resolve("pgm.mjpg").toString()
        obsSourceButton.isEnabled = false
        obsStatus.text = "Connecting to OBS WebSocket…"
        object : SwingWorker<String, Void>() {
            override fun doInBackground(): String = try {
                ObsWebSocketClient(port, password).use { client ->
                    client.addPgmSource(url)
                }
            } finally {
                password.fill('\u0000')
            }

            override fun done() {
                obsSourceButton.isEnabled = true
                runCatching { get() }
                    .onSuccess { message ->
                        obsStatus.text = "OBS source configured · waiting for video"
                        obsStatusOverrideUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        JOptionPane.showMessageDialog(
                            this@DesktopHubFrame,
                            "$message\n\nOBS uses its built-in FFmpeg Media Source with a zero-MB buffering setting for the local MJPEG PGM feed (up to 30 fps).",
                            "CamHub added to OBS",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                    .onFailure { error ->
                        val detail = error.cause?.message ?: error.message ?: "Unknown OBS connection error."
                        val copied = runCatching {
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(manualSourceUrl), null)
                        }.isSuccess
                        obsStatus.text = "OBS connection failed · " + if (copied) "manual URL copied" else "manual URL available"
                        obsStatusOverrideUntilNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                        JOptionPane.showMessageDialog(
                            this@DesktopHubFrame,
                            "Could not add the source through OBS WebSocket. Check that OBS is open, WebSocket is enabled, and the port/password are correct.\n\n$detail\n\nManual fallback: OBS → Sources → Media Source → disable Local File → Input URL\n$manualSourceUrl\nInput Format: mpjpeg\nBuffering MB: 0\nReconnect Delay: 1 second",
                            "OBS connection failed",
                            JOptionPane.WARNING_MESSAGE
                        )
                    }
            }
        }.execute()
    }

    private fun refreshObsOutputStatus() {
        if (!obsOutput.isRunning) {
            obsOutputStatusTimer.stop()
            return
        }
        if (System.nanoTime() < obsStatusOverrideUntilNanos) return

        val clients = obsOutput.activeStreamClientCount
        val frameAge = obsOutput.lastFrameAgeMs
        obsStatus.text = when {
            clients == 0 -> "Waiting for OBS stream · check source visibility"
            frameAge == null -> "OBS connected · waiting for first frame"
            frameAge > 2_000L -> "OBS stream stalled · reconnect the source"
            !obsOutput.hasProgramSignal -> "OBS stream active · PGM has no signal"
            clients == 1 -> "OBS stream active · PGM signal"
            else -> "OBS stream active · $clients local clients"
        }
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
        add(JPanel(BorderLayout(0, 8)).apply {
            background = HubColors.background
            add(JPanel(GridLayout(1, 2, 8, 0)).apply {
                background = HubColors.background
                add(previewOutput)
                add(pgmPreview)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
                background = HubColors.background
                add(styleButton(cutButton, HubColors.program))
                add(styleButton(autoButton, HubColors.preview))
                add(livePtzLockButton.apply {
                    foreground = Color.WHITE
                    background = HubColors.panel
                    toolTipText = "Allow direct framing changes to the live PGM camera."
                })
            }, BorderLayout.SOUTH)
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
        add(Box.createVerticalStrut(18))
        add(sectionLabel("DESKTOP SOURCE"))
        add(Box.createVerticalStrut(8))
        screenInputButton.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(styleButton(screenInputButton, HubColors.preview))
        screenStatus.foreground = HubColors.textMuted
        screenStatus.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        add(Box.createVerticalStrut(6))
        add(screenStatus)
        add(Box.createVerticalStrut(12))
        desktopCameraSourceButton.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(styleButton(desktopCameraSourceButton, HubColors.accent))
        desktopCameraSourceStatus.foreground = HubColors.textMuted
        desktopCameraSourceStatus.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        desktopCameraSourceStatus.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(Box.createVerticalStrut(5))
        add(desktopCameraSourceStatus)
        desktopCameraPairingInfo.foreground = HubColors.preview
        desktopCameraPairingInfo.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        desktopCameraPairingInfo.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(Box.createVerticalStrut(4))
        add(desktopCameraPairingInfo)
        add(Box.createVerticalStrut(18))
        add(sectionLabel("OBS OUTPUT"))
        add(Box.createVerticalStrut(8))
        obsSourceButton.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(styleButton(obsSourceButton, HubColors.accent))
        obsStopButton.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(styleButton(obsStopButton, HubColors.textMuted))
        obsStatus.foreground = HubColors.textMuted
        obsStatus.font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        add(Box.createVerticalStrut(6))
        add(obsStatus)
        add(Box.createVerticalStrut(14))
        add(sectionLabel("PGM RECORDING"))
        add(Box.createVerticalStrut(8))
        pgmRecordButton.maximumSize = Dimension(Int.MAX_VALUE, 36)
        add(styleButton(pgmRecordButton, HubColors.program))
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
        add(pgmRecordStatus)
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
        val snapshots = cameraPanels.mapNotNull { it.currentSnapshot() }
        val p50 = snapshots.mapNotNull { it.captureToDecodeP50Ms }.maxOrNull()
        val p95 = snapshots.mapNotNull { it.captureToDecodeP95Ms }.maxOrNull()
        val p99 = snapshots.mapNotNull { it.captureToDecodeP99Ms }.maxOrNull()
        fun format(value: Long?): String = value?.let(Long::toString) ?: "--"
        latencyFooter.text = "MAX CAM C→D P50/P95/P99 ${format(p50)}/${format(p95)}/${format(p99)} ms"
        latencyFooter.toolTipText =
            "Maximum per-camera capture-to-decoder latency percentiles. Does not include Swing paint or monitor presentation."
    }

    private fun selectPreview(slot: Int) {
        val selectedSlot = slot.coerceIn(cameraPanels.indices)
        sourceSwitcher.selectPreview(PgmSource.Camera(selectedSlot))
        refreshPreviewOutput()
        updateCameraTallies()
        updateTransitionButtons()
    }

    private fun cutPreviewToProgram() {
        val next = sourceSwitcher.cut() ?: return
        if (next is PgmSource.Camera) {
            selectedCameraPgmSlot = next.slot
            programFramings[next.slot].set(previewFramings[next.slot].get())
            if (cameraPanels[next.slot].currentSnapshot()?.supportsRemotePtz == true) {
                queueRemotePtz(next.slot, programFramings[next.slot].get())
            }
        }
        refreshProgramOutput()
        updateCameraTallies()
    }

    private fun startAutoTransition() {
        if (autoTransitionTimer?.isRunning == true || sourceSwitcher.preview == PgmSource.None) return
        autoTransitionPhase = 0
        autoTransitionStartedAt = System.nanoTime()
        val durationNanos = 150_000_000L
        autoTransitionTimer = Timer(16) {
            val progress = ((System.nanoTime() - autoTransitionStartedAt).toDouble() / durationNanos)
                .coerceIn(0.0, 1.0)
            if (autoTransitionPhase == 0) {
                setTransitionAlpha(progress.toFloat())
                if (progress >= 1.0) {
                    cutPreviewToProgram()
                    autoTransitionPhase = 1
                    autoTransitionStartedAt = System.nanoTime()
                }
            } else {
                setTransitionAlpha((1.0 - progress).toFloat())
                if (progress >= 1.0) {
                    setTransitionAlpha(0f)
                    autoTransitionTimer?.stop()
                    updateTransitionButtons()
                }
            }
        }.apply { start() }
        updateTransitionButtons()
    }

    private fun setTransitionAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        pgmPreview.setTransitionAlpha(clamped)
        pgmController.setTransitionAlpha(clamped)
        if (pgmRecorder.isActive) {
            pgmRecorder.offerFrame(
                currentProgramFrame.get() ?: noSignalRecordingFrame,
                clamped
            )
        }
    }

    private fun updateCameraTallies() {
        cameraPanels.forEachIndexed { index, panel ->
            panel.setTally(
                isPreview = sourceSwitcher.preview == PgmSource.Camera(index),
                isProgram = sourceSwitcher.program == PgmSource.Camera(index)
            )
        }
    }

    private fun updateTransitionButtons() {
        val hasPreview = sourceSwitcher.preview != PgmSource.None
        cutButton.isEnabled = hasPreview
        autoButton.isEnabled = hasPreview && autoTransitionTimer?.isRunning != true
    }

    private fun updateSourceFrame(source: PgmSource, frame: DesktopVideoFrame) {
        if (sourceSwitcher.preview == source) {
            previewOutput.setFrame(frameForBus(source, frame, isProgram = false))
        }
        if (sourceSwitcher.program == source) {
            val programFrame = frameForBus(source, frame, isProgram = true)
            currentProgramFrame.set(programFrame)
            pgmPreview.setFrame(programFrame)
            pgmController.setFrame(programFrame)
            if (pgmRecorder.isActive) pgmRecorder.offerFrame(programFrame, pgmController.transitionAlpha())
        }
    }

    private fun refreshPreviewOutput() {
        frameFor(sourceSwitcher.preview, isProgram = false)?.let(previewOutput::setFrame) ?: previewOutput.clearFrame()
    }

    private fun refreshProgramOutput() {
        val frame = frameFor(sourceSwitcher.program, isProgram = true)
        currentProgramFrame.set(frame)
        frame?.let {
            pgmPreview.setFrame(it)
            pgmController.setFrame(it)
            if (pgmRecorder.isActive) pgmRecorder.offerFrame(it, pgmController.transitionAlpha())
        } ?: run {
            pgmPreview.clearFrame()
            pgmController.clearFrame()
            if (pgmRecorder.isActive) pgmRecorder.offerFrame(noSignalRecordingFrame, pgmController.transitionAlpha())
        }
    }

    private fun refreshBusOutputs() {
        refreshPreviewOutput()
        refreshProgramOutput()
    }

    private fun frameFor(source: PgmSource, isProgram: Boolean): DesktopVideoFrame? = when (source) {
        is PgmSource.Camera -> cameraPanels[source.slot].currentImage()?.let { image ->
            frameForBus(source, DesktopVideoFrame(image, cameraRotations.get(source.slot)), isProgram)
        }
        PgmSource.DesktopScreen -> latestScreenFrame.get()
        PgmSource.None -> null
    }

    private fun frameForBus(source: PgmSource, frame: DesktopVideoFrame, isProgram: Boolean): DesktopVideoFrame =
        if (source is PgmSource.Camera) {
            val framings = if (isProgram) programFramings else previewFramings
            frame.copy(
                framing = framings[source.slot].get()
                    .residualAfter(remotePtzFramings[source.slot].get())
            )
        } else {
            frame
        }

    private fun applyPtzGesture(source: PgmSource, isProgram: Boolean, gesture: DesktopPtzGesture) {
        val camera = source as? PgmSource.Camera ?: return
        val state = (if (isProgram) programFramings else previewFramings)[camera.slot]
        val current = state.get()
        val updated = when {
            gesture.reset -> current.reset()
            gesture.zoomFactor != 1.0 -> current.zoomBy(gesture.zoomFactor, gesture.anchorX, gesture.anchorY)
            else -> current
        }.panBy(gesture.panX, gesture.panY)
        state.set(updated)
        if (livePtzLockButton.isSelected) {
            if (isProgram) {
                if (sourceSwitcher.preview == source) previewFramings[camera.slot].set(updated)
            } else {
                programFramings[camera.slot].set(updated)
            }
        }
        if (sourceSwitcher.preview == source) refreshPreviewOutput()
        if (sourceSwitcher.program == source) refreshProgramOutput()
        if ((livePtzLockButton.isSelected || sourceSwitcher.program != source) &&
            cameraPanels[camera.slot].currentSnapshot()?.supportsRemotePtz == true
        ) {
            queueRemotePtz(camera.slot, updated)
        }
    }

    private fun queueRemotePtz(slot: Int, framing: DesktopFraming) {
        pendingRemotePtz[slot] = framing
        remotePtzDebounceTimer.restart()
    }

    private fun flushRemotePtzRequests() {
        pendingRemotePtz.forEachIndexed { slot, framing ->
            if (framing == null) return@forEachIndexed
            pendingRemotePtz[slot] = null
            val (sourceX, sourceY) = DesktopPtzCoordinates.displayToCamera(
                framing.centerX,
                framing.centerY,
                cameraRotations.get(slot)
            )
            hubController.requestPtz(slot, framing.zoom.toFloat(), sourceX.toFloat(), sourceY.toFloat())
        }
    }

    private fun restoreCameraPreview() {
        val slot = selectedCameraPgmSlot
        if (cameraEndpointIds.getOrNull(slot) != null) {
            selectPreview(slot)
            return
        }
        sourceSwitcher.selectPreview(PgmSource.None)
        refreshPreviewOutput()
        updateCameraTallies()
        updateTransitionButtons()
    }

    private fun togglePgmRecording() {
        when (pgmRecorder.currentSnapshot.state) {
            DesktopRecordingState.STARTING,
            DesktopRecordingState.RECORDING -> {
                pgmRecorder.stop()
                return
            }
            DesktopRecordingState.STOPPING -> return
            DesktopRecordingState.IDLE,
            DesktopRecordingState.FAILED -> Unit
        }

        val chooser = JFileChooser().apply {
            dialogTitle = "Record PGM to MP4"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("MP4 video", "mp4"))
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            selectedFile = Path.of(System.getProperty("user.home"), "Movies", "CamHub-PGM-$stamp.mp4").toFile()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        var output = chooser.selectedFile.toPath()
        if (!output.fileName.toString().endsWith(".mp4", ignoreCase = true)) {
            output = output.resolveSibling("${output.fileName}.mp4")
        }
        if (output.toFile().exists()) {
            JOptionPane.showMessageDialog(
                this,
                "That file already exists. Choose a new filename so an existing recording is not replaced.",
                "Recording File Exists",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        runCatching { pgmRecorder.start(output) }
            .onSuccess {
                pgmRecorder.offerFrame(
                    currentProgramFrame.get() ?: noSignalRecordingFrame,
                    pgmController.transitionAlpha()
                )
            }
            .onFailure { error ->
                JOptionPane.showMessageDialog(
                    this,
                    error.message ?: "Could not start PGM recording.",
                    "CamHub PGM Recording",
                    JOptionPane.ERROR_MESSAGE
                )
            }
    }

    private fun startScreenInput(): Boolean {
        val displays = displayManager.displays()
        if (displays.isEmpty()) {
            screenStatus.text = "No display available for capture"
            return false
        }
        val displayPicker = JComboBox(DefaultComboBoxModel(displays.toTypedArray()))
        displayPicker.selectedItem = DisplayTargetSelection.select(displays, null)
        val panel = JPanel(BorderLayout(0, 8)).apply {
            add(JLabel("Choose a display, then drag across the area to send."), BorderLayout.NORTH)
            add(displayPicker, BorderLayout.CENTER)
        }
        val selected = JOptionPane.showConfirmDialog(
            this,
            panel,
            "Select desktop display",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (selected != JOptionPane.OK_OPTION) return false
        val selectedDisplay = displayPicker.selectedItem as? DisplayTarget ?: return false
        val display = displayManager.displays().firstOrNull { it.id == selectedDisplay.id }
        if (display == null) {
            screenStatus.text = "Selected display is no longer available"
            return false
        }
        if (pgmController.isOpenOn(display.id)) {
            JOptionPane.showMessageDialog(
                this,
                "Choose a display other than the active PGM output. Capturing the PGM display would create a feedback loop.",
                "Screen Capture Feedback",
                JOptionPane.WARNING_MESSAGE
            )
            return false
        }
        val region = ScreenRegionSelector.select(this, display) ?: return false
        val operatorDisplayId = runCatching { getGraphicsConfiguration().device.getIDstring() }.getOrNull()
        val operatorWindowVisible = isShowing && (extendedState and JFrame.ICONIFIED) == 0
        if (operatorWindowVisible && operatorDisplayId == display.id && region.intersects(bounds)) {
            JOptionPane.showMessageDialog(
                this,
                "The selected area contains the CamHub control window. Move CamHub outside the capture area or choose a smaller region to avoid feeding its controls back into the video.",
                "Screen Capture Feedback",
                JOptionPane.WARNING_MESSAGE
            )
            return false
        }
        return runCatching {
            screenCapture.start(ScreenCaptureRequest(display, region))
            screenCaptureRunning = true
            screenCaptureDisplayId = display.id
            screenCaptureRegion = Rectangle(region)
            sourceSwitcher.selectPreview(PgmSource.DesktopScreen)
            latestScreenFrame.set(null)
            refreshPreviewOutput()
            updateCameraTallies()
            screenInputButton.text = "STOP SCREEN"
            screenStatus.text = "${display.label} · ${region.width}×${region.height} · 30 fps"
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                screenStatus.toolTipText = "If blank, allow CamHub Desktop under Privacy & Security → Screen Recording."
            }
            true
        }.onFailure { error ->
            screenStatus.text = error.message?.take(96) ?: "Invalid capture area"
        }.getOrDefault(false)
    }

    private fun startDesktopCameraSource() {
        if (!desktopCameraSourceStarting.compareAndSet(false, true)) return
        val generation = desktopCameraSourceGeneration.incrementAndGet()
        desktopCameraSourceButton.isEnabled = false
        desktopCameraSourceStatus.text = "Starting secure camera source…"
        Thread({
            val server = DesktopCameraSourceServer(onStatus = { status ->
                SwingUtilities.invokeLater {
                    if (desktopCameraSourceGeneration.get() == generation && !closing) {
                        desktopCameraSourceStatus.text = status.take(110)
                    }
                }
            }, onFatalError = { error ->
                SwingUtilities.invokeLater {
                    if (desktopCameraSourceGeneration.get() == generation && !closing) {
                        stopDesktopCameraSource(error.take(110))
                    }
                }
            })
            try {
                val info = server.start()
                if (closing || !screenCaptureRunning || desktopCameraSourceGeneration.get() != generation) {
                    server.close()
                    SwingUtilities.invokeLater {
                        desktopCameraSourceStarting.set(false)
                        desktopCameraSourceButton.isEnabled = true
                        desktopCameraSourceStatus.text = if (closing) "Offline" else "Screen capture stopped"
                    }
                } else {
                    SwingUtilities.invokeLater {
                        if (closing || !screenCaptureRunning || desktopCameraSourceGeneration.get() != generation) {
                            desktopCameraSourceStatus.text = if (closing) "Offline" else "Screen capture stopped"
                            Thread({
                                server.close()
                                SwingUtilities.invokeLater {
                                    desktopCameraSourceStarting.set(false)
                                    desktopCameraSourceButton.isEnabled = !closing
                                }
                            }, "DesktopCameraSourceClose").apply {
                                isDaemon = true
                                start()
                            }
                        } else {
                            desktopCameraSourceStarting.set(false)
                            desktopCameraSourceButton.isEnabled = true
                            desktopCameraServer = server
                            desktopCameraSourceButton.text = "STOP CAMERA SOURCE"
                            desktopCameraPairingInfo.text = "${info.deviceName} · VERIFY ${info.fingerprintPrefix} · PIN ${info.pairingPin}"
                            desktopCameraPairingInfo.toolTipText = "Compare VERIFY with the Android trust prompt, then enter the PIN."
                        }
                    }
                }
            } catch (error: Exception) {
                server.close()
                SwingUtilities.invokeLater {
                    desktopCameraSourceStarting.set(false)
                    if (desktopCameraSourceGeneration.get() != generation) {
                        if (desktopCameraServer == null && !desktopCameraSourceStarting.get()) {
                            desktopCameraSourceButton.isEnabled = true
                        }
                        return@invokeLater
                    }
                    desktopCameraSourceButton.isEnabled = true
                    desktopCameraSourceStatus.text = error.message?.take(100) ?: "Camera source failed to start"
                    desktopCameraPairingInfo.text = "VERIFY / PAIR PIN unavailable"
                }
            }
        }, "DesktopCameraSourceStart").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopDesktopCameraSource(finalStatus: String = "Not advertised") {
        desktopCameraSourceGeneration.incrementAndGet()
        val server = desktopCameraServer ?: return
        if (!desktopCameraSourceStarting.compareAndSet(false, true)) return
        desktopCameraServer = null
        desktopCameraSourceButton.text = "START CAMERA SOURCE"
        desktopCameraSourceButton.isEnabled = false
        desktopCameraPairingInfo.text = "VERIFY / PAIR PIN will appear when started"
        desktopCameraSourceStatus.text = "Stopping camera source…"
        Thread({
            server.close()
            SwingUtilities.invokeLater {
                desktopCameraSourceStatus.text = finalStatus
                desktopCameraSourceButton.isEnabled = true
                desktopCameraSourceStarting.set(false)
            }
        }, "DesktopCameraSourceStop").apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshDisplays(preserveSelection: Boolean) {
        val preferredId = if (preserveSelection) {
            (displaySelector.selectedItem as? DisplayTarget)?.id
        } else null
        val displays = displayManager.displays()
        val captureDisplay = screenCaptureDisplayId
        val currentCaptureDisplay = displays.firstOrNull { it.id == captureDisplay }
        val captureRegion = screenCaptureRegion
        if (screenCaptureRunning && captureDisplay != null &&
            (currentCaptureDisplay == null || captureRegion == null || !currentCaptureDisplay.bounds.contains(captureRegion))) {
            stopDesktopCameraSource()
            screenCapture.close()
            screenCaptureRunning = false
            screenCaptureDisplayId = null
            screenCaptureRegion = null
            screenInputButton.text = "SCREEN INPUT"
            screenStatus.text = if (currentCaptureDisplay == null) "Capture display disconnected"
                                else "Capture area is no longer on the selected display"
            sourceSwitcher.clear(PgmSource.DesktopScreen)
            restoreCameraPreview()
            refreshBusOutputs()
        }
        val newSignature = displays.joinToString("|") {
            "${it.id}:${it.bounds.x}:${it.bounds.y}:${it.bounds.width}:${it.bounds.height}:${it.refreshRateHz}:${it.isPrimary}"
        }
        pgmController.reconcile(displays)
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
    @Volatile private var previewSelected = false
    var onSelected: (() -> Unit)? = null

    val isStreaming: Boolean get() = snapshot?.status == DesktopCameraStatus.STREAMING
    fun currentSnapshot(): CameraConnectionSnapshot? = snapshot

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

    fun clearImage() {
        image.set(null)
        repaint()
    }

    fun setTally(isPreview: Boolean, isProgram: Boolean) {
        previewSelected = isPreview
        programSelected = isProgram
        val color = when {
            isProgram -> HubColors.program
            isPreview -> HubColors.preview
            else -> HubColors.border
        }
        border = BorderFactory.createLineBorder(
            color,
            if (isProgram || isPreview) 2 else 1
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
                    it.captureToDecodeP95Ms?.let { latency -> append(" · C→D P95 ${latency}ms") }
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

private data class DesktopPtzGesture(
    val zoomFactor: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
    val anchorX: Double = 0.5,
    val anchorY: Double = 0.5,
    val reset: Boolean = false
)

private class VideoOutputPanel(label: String, tally: Color) : JPanel() {
    private val frame = AtomicReference<DesktopVideoFrame?>()
    private val transitionAlpha = java.util.concurrent.atomic.AtomicReference(0f)
    private val title = label
    private val titleColor = tally
    var onPtzGesture: ((DesktopPtzGesture) -> Unit)? = null

    init {
        preferredSize = Dimension(0, 130)
        background = Color.BLACK
        border = BorderFactory.createLineBorder(tally, 2)
        var dragPoint: Point? = null
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                dragPoint = Point(event.point)
            }

            override fun mouseReleased(event: MouseEvent) {
                dragPoint = null
            }

            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                val current = frame.get()?.framing ?: DesktopFraming.IDENTITY
                val (anchorX, anchorY) = anchorAt(event.x, event.y)
                onPtzGesture?.invoke(
                    if (current.zoom > 1.05) DesktopPtzGesture(reset = true)
                    else DesktopPtzGesture(zoomFactor = 2.0, anchorX = anchorX, anchorY = anchorY)
                )
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                val previous = dragPoint ?: return
                val dx = event.x - previous.x
                val dy = event.y - previous.y
                dragPoint = Point(event.point)
                val (panX, panY) = normalizedDelta(dx, dy)
                onPtzGesture?.invoke(DesktopPtzGesture(panX = panX, panY = panY))
            }
        })
        addMouseWheelListener { event ->
            val (anchorX, anchorY) = anchorAt(event.x, event.y)
            onPtzGesture?.invoke(
                DesktopPtzGesture(
                    zoomFactor = Math.pow(1.15, -event.wheelRotation.toDouble()),
                    anchorX = anchorX,
                    anchorY = anchorY
                )
            )
        }
    }

    fun setFrame(value: DesktopVideoFrame) {
        frame.set(value)
        repaint()
    }

    fun clearFrame() {
        frame.set(null)
        repaint()
    }

    fun setTransitionAlpha(alpha: Float) {
        transitionAlpha.set(alpha.coerceIn(0f, 1f))
        repaint()
    }

    private fun anchorAt(x: Int, y: Int): Pair<Double, Double> {
        val current = frame.get() ?: return 0.5 to 0.5
        val rotation = ((current.rotationDegrees % 360) + 360) % 360
        val swapsDimensions = rotation == 90 || rotation == 270
        val contentWidth = if (swapsDimensions) current.image.height else current.image.width
        val contentHeight = if (swapsDimensions) current.image.width else current.image.height
        if (width <= 0 || height <= 0 || contentWidth <= 0 || contentHeight <= 0) return 0.5 to 0.5
        val fitScale = minOf(width.toDouble() / contentWidth, height.toDouble() / contentHeight)
        return 0.5 + (x - width / 2.0) / (contentWidth * fitScale) to
            0.5 + (y - height / 2.0) / (contentHeight * fitScale)
    }

    private fun normalizedDelta(dx: Int, dy: Int): Pair<Double, Double> {
        val current = frame.get() ?: return 0.0 to 0.0
        val rotation = ((current.rotationDegrees % 360) + 360) % 360
        val contentWidth = if (rotation == 90 || rotation == 270) current.image.height else current.image.width
        val contentHeight = if (rotation == 90 || rotation == 270) current.image.width else current.image.height
        if (width <= 0 || height <= 0 || contentWidth <= 0 || contentHeight <= 0) return 0.0 to 0.0
        val fitScale = minOf(width.toDouble() / contentWidth, height.toDouble() / contentHeight)
        return dx / (contentWidth * fitScale) to dy / (contentHeight * fitScale)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            frame.get()?.let {
                DesktopVideoPainter.draw(g, it, width, height, highQualitySpatialScale = true)
            }
            val alpha = transitionAlpha.get()
            if (alpha > 0f) {
                g.color = Color(0, 0, 0, (alpha * 255).toInt())
                g.fillRect(0, 0, width, height)
            }
            g.color = titleColor
            g.font = Font(Font.MONOSPACED, Font.BOLD, 11)
            val zoom = frame.get()?.framing?.zoom ?: 1.0
            g.drawString("$title · ${"%.1f".format(zoom)}× · 1080 SPATIAL", 10, 18)
            g.color = Color(230, 238, 246, 210)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 9)
            g.drawString(
                if (title == "PVW") "DRAG PAN · WHEEL ZOOM · DOUBLE-CLICK ZOOM/RESET"
                else "DIRECT PTZ · ARM LIVE LOCK TO EDIT",
                10,
                32
            )
        } finally {
            g.dispose()
        }
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
