package com.lastasylum.game

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

actual object Storage {
    internal var context: Context? = null
    private val prefs get() = context?.getSharedPreferences("lastasylum", Context.MODE_PRIVATE)
    actual fun load(): String? = prefs?.getString("save", null)
    actual fun save(data: String) {
        prefs?.edit()?.putString("save", data)?.apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Storage.context = applicationContext
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestHighestRefreshRate()
        setContent { App() }
    }

    /** Ask for the fastest display mode (90/120/144 Hz) at the current resolution. */
    @Suppress("DEPRECATION")
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also {
            it.preferredDisplayModeId = best.modeId
            it.preferredRefreshRate = best.refreshRate
        }
    }
}
