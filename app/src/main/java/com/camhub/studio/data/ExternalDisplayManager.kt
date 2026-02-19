package com.camhub.studio.data

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.widget.ImageView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalDisplayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: PgmPresentation? = null

    private val _isExternalDisplayConnected = MutableStateFlow(false)
    val isExternalDisplayConnected: StateFlow<Boolean> = _isExternalDisplayConnected.asStateFlow()

    private val _isOutputEnabled = MutableStateFlow(false)
    val isOutputEnabled: StateFlow<Boolean> = _isOutputEnabled.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { checkDisplays() }
        override fun onDisplayRemoved(displayId: Int) {
            checkDisplays()
            if (!_isExternalDisplayConnected.value) {
                dismiss()
            }
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun startListening() {
        displayManager.registerDisplayListener(displayListener, null)
        checkDisplays()
    }

    fun stopListening() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun checkDisplays(): Display? {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        _isExternalDisplayConnected.value = displays.isNotEmpty()
        return displays.firstOrNull()
    }

    fun enableOutput(activity: Activity) {
        val externalDisplay = checkDisplays() ?: return
        presentation?.dismiss()
        presentation = PgmPresentation(activity, externalDisplay).apply { show() }
        _isOutputEnabled.value = true
    }

    fun disableOutput() {
        dismiss()
        _isOutputEnabled.value = false
    }

    fun updateFrame(bitmap: Bitmap) {
        presentation?.updateFrame(bitmap)
    }

    private fun dismiss() {
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        _isOutputEnabled.value = false
    }
}

/** Fullscreen clean PGM feed — no UI overlays */
private class PgmPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    private var imageView: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContentView(iv)
        imageView = iv
    }

    fun updateFrame(bitmap: Bitmap) {
        imageView?.post { imageView?.setImageBitmap(bitmap) }
    }
}
