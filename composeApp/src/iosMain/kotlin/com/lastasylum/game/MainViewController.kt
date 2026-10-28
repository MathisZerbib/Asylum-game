package com.lastasylum.game

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSUserDefaults

actual object Storage {
    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey("lastasylum.save")
    actual fun save(data: String) = NSUserDefaults.standardUserDefaults.setObject(data, "lastasylum.save")
}

/** Entry point used by the Xcode project (see iosApp/). */
fun MainViewController() = ComposeUIViewController { App() }
