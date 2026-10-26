package com.camhub.desktop

import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    SwingUtilities.invokeLater {
        DesktopHubFrame().isVisible = true
    }
}
