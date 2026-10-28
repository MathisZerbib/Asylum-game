package com.lastasylum.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.lastasylum.game.engine.Game
import com.lastasylum.game.engine.GameEvent
import com.lastasylum.game.render.Renderer
import com.lastasylum.game.ui.ComposeGfx
import com.lastasylum.game.ui.atlasPages
import kotlin.math.sqrt

/** Keys held on desktop (ZQSD / WASD / arrows). */
private class Keys {
    var up = false
    var down = false
    var left = false
    var right = false
    val any get() = up || down || left || right
}

@Composable
fun App() {
    val game = remember { Game().also { it.load(Storage.load()) } }
    val renderer = remember { Renderer(game) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val pages = atlasPages()
    val gfx = remember(measurer, pages, density) { ComposeGfx(measurer, pages, density.density * density.fontScale) }
    val haptics = LocalHapticFeedback.current
    val frame = remember { mutableLongStateOf(0L) }
    val keys = remember { Keys() }
    val focus = remember { FocusRequester() }

    renderer.insetTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    renderer.insetBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val joyRadius = with(density) { 58.dp.toPx() }
    val dpPx = density.density
    renderer.joyRadius = joyRadius

    DisposableEffect(game) {
        SaveHook.flush = { Storage.save(game.save()) }
        onDispose {
            Storage.save(game.save())
            SaveHook.flush = null
        }
    }

    // Game loop: one update per display vsync (60, 90, 120 Hz...), driven by real delta time.
    LaunchedEffect(game) {
        var last = -1L
        var fpsFrames = 0
        var fpsTime = 0f
        var hapticCooldown = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last >= 0L) (now - last) / 1_000_000_000f else 0f
                last = now
                if (keys.any) {
                    var x = 0f
                    var y = 0f
                    if (keys.left) x -= 1f
                    if (keys.right) x += 1f
                    if (keys.up) y -= 1f
                    if (keys.down) y += 1f
                    val l = sqrt(x * x + y * y)
                    if (l > 0f) {
                        game.inputX = x / l; game.inputY = y / l
                    } else {
                        game.inputX = 0f; game.inputY = 0f
                    }
                    renderer.hasMoved = true
                }
                game.update(dt)

                hapticCooldown -= dt
                while (true) {
                    val e = game.pollEvent()
                    if (e == 0) break
                    when (e) {
                        GameEvent.BUY, GameEvent.LEVEL, GameEvent.BOSS -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hapticCooldown = 0.15f
                        }
                        GameEvent.DROP, GameEvent.CURED -> if (hapticCooldown <= 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            hapticCooldown = 0.08f
                        }
                    }
                }
                if (game.saveRequested) {
                    game.saveRequested = false
                    Storage.save(game.save())
                }

                fpsFrames++
                fpsTime += dt
                if (fpsTime >= 0.5f) {
                    renderer.fps = (fpsFrames / fpsTime + 0.5f).toInt()
                    fpsFrames = 0; fpsTime = 0f
                }
                frame.longValue = now
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A10))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown && ev.type != KeyEventType.KeyUp) return@onKeyEvent false
                val down = ev.type == KeyEventType.KeyDown
                when (ev.key) {
                    Key.W, Key.Z, Key.DirectionUp -> keys.up = down
                    Key.S, Key.DirectionDown -> keys.down = down
                    Key.A, Key.Q, Key.DirectionLeft -> keys.left = down
                    Key.D, Key.DirectionRight -> keys.right = down
                    Key.F -> if (down) renderer.showFps = !renderer.showFps
                    else -> return@onKeyEvent false
                }
                if (!keys.any) {
                    game.inputX = 0f; game.inputY = 0f
                }
                true
            }
            .pointerInput(game) {
                // Floating joystick: wherever the finger lands becomes the stick's center.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val id = first.id
                    val bx = first.position.x
                    val by = first.position.y
                    val downAt = first.uptimeMillis
                    var moved = false
                    renderer.joyActive = true
                    renderer.joyBaseX = bx; renderer.joyBaseY = by
                    renderer.joyKnobX = bx; renderer.joyKnobY = by
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == id } ?: break
                        if (!ch.pressed) {
                            // A quick tap on the top-left avatar shows the FPS counter.
                            if (!moved && ch.uptimeMillis - downAt < 250 && by < renderer.insetTop + 140f * dpPx && bx < 120f * dpPx) {
                                renderer.showFps = !renderer.showFps
                            }
                            break
                        }
                        var dx = ch.position.x - bx
                        var dy = ch.position.y - by
                        val d = sqrt(dx * dx + dy * dy)
                        if (d > joyRadius * 0.12f) moved = true
                        if (d > joyRadius) {
                            dx = dx / d * joyRadius; dy = dy / d * joyRadius
                        }
                        renderer.joyKnobX = bx + dx
                        renderer.joyKnobY = by + dy
                        // Small dead zone, then full speed quickly so it feels snappy.
                        val m = ((d / joyRadius - 0.12f) / 0.5f).coerceIn(0f, 1f)
                        if (d > 0.01f && m > 0f) {
                            game.inputX = dx / sqrt(dx * dx + dy * dy) * m
                            game.inputY = dy / sqrt(dx * dx + dy * dy) * m
                            renderer.hasMoved = true
                        } else {
                            game.inputX = 0f; game.inputY = 0f
                        }
                        ch.consume()
                    }
                    renderer.joyActive = false
                    game.inputX = 0f; game.inputY = 0f
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading the frame state here only invalidates the draw phase: no recomposition per frame.
            val now = frame.longValue
            gfx.scope = this
            renderer.render(gfx, renderer.frameDt(now))
        }
    }
}
