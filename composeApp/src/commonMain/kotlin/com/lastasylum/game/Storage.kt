package com.lastasylum.game

/** Tiny persistent store for the save string of [com.lastasylum.game.engine.Game]. */
expect object Storage {
    fun load(): String?
    fun save(data: String)
}

/** Lets the platform force a save when the app goes to the background. */
object SaveHook {
    var flush: (() -> Unit)? = null
}
