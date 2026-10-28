package com.lastasylum.game

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.prefs.Preferences

actual object Storage {
    private val prefs = Preferences.userRoot().node("com/lastasylum/game")
    actual fun load(): String? = prefs.get("save", null)
    actual fun save(data: String) = prefs.put("save", data)
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Last Asylum",
        // Phone-shaped window: the game is made for portrait screens.
        state = rememberWindowState(width = 420.dp, height = 900.dp),
    ) {
        App()
    }
}
