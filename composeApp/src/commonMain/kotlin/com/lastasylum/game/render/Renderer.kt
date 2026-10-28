package com.lastasylum.game.render

import com.lastasylum.game.engine.Banner
import com.lastasylum.game.engine.Box
import com.lastasylum.game.engine.FxKind
import com.lastasylum.game.engine.Game
import com.lastasylum.game.engine.PState
import com.lastasylum.game.engine.PadKind
import com.lastasylum.game.engine.Patient
import com.lastasylum.game.engine.Station
import com.lastasylum.game.engine.StationKind
import com.lastasylum.game.engine.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the game in the same 45 degree orthographic view the sprites were rendered with:
 * world (x, y, z) in meters maps to screenX = x * S, screenY = (y * K - z * KZ) * S.
 * Characters and props are pre-rendered 3D sprites (see tools/sprites). Everything below the
 * darkness pass is world, everything after it is overlays and HUD. No allocation per frame.
 */
class Renderer(private val game: Game) {
    // Camera
    private var camX = game.player.x
    private var camY = game.player.y
    private var s = 100f
    private val k = 0.7071f
    private val kz = 0.7071f
    private var w = 1f
    private var h = 1f
    private var ox = 0f
    private var oy = 0f
    private var t = 0f

    // Set by the UI.
    var insetTop = 0f
    var insetBottom = 0f
    var joyActive = false
    var joyBaseX = 0f
    var joyBaseY = 0f
    var joyKnobX = 0f
    var joyKnobY = 0f
    var joyRadius = 60f
    var fps = 0
    var showFps = false
    var hasMoved = false

    private val px = FloatArray(16)
    private val py = FloatArray(16)
    private val keys = LongArray(768)
    private var nKeys = 0

    /** Walls cut in 1 m pieces so depth sorting against characters stays right. */
    private val segs: Array<Box>

    private val coinText = CachedText()
    private val curedText = CachedText()
    private val levelText = CachedText()
    private val waveText = CachedText()
    private val padCost = Array(game.pads.size) { CachedText() }
    private val benchText = CachedText()
    private val fpsText = CachedText()
    private val bannerText = CachedText()

    // Frames per walking cycle in the atlas (5 rendered directions).
    private val runFrames = SpriteData.DOCTOR_COUNT / 5
    private val walkFrames = SpriteData.SICK_COUNT / 5
    private val ratFrames = SpriteData.RAT_COUNT / 5

    init {
        val list = ArrayList<Box>()
        for (b in World.walls) {
            val len = b.y1 - b.y0
            if (len > 1.2f) {
                var y = b.y0
                while (y < b.y1 - 0.01f) {
                    val y1 = min(b.y1, y + 1f)
                    list.add(Box(b.x0, y, b.x1, y1, b.height, b.fence))
                    y = y1
                }
            } else list.add(b)
        }
        segs = list.toTypedArray()
    }

    // ------------------------------------------------------------------ projection

    private fun sx(x: Float) = (x - camX) * s + w * 0.5f + ox
    private fun sy(y: Float, z: Float = 0f) = (y - camY) * s * k + h * 0.56f - z * s * kz + oy

    private var vx0 = 0f
    private var vx1 = 0f
    private var vy0 = 0f
    private var vy1 = 0f

    private fun visible(x: Float, y: Float, margin: Float = 2.5f) =
        x > vx0 - margin && x < vx1 + margin && y > vy0 - margin && y < vy1 + margin * 1.6f

    /** Draws atlas frame [id] with its ground anchor at world (x, y, z). */
    private fun sprite(g: Gfx, id: Int, x: Float, y: Float, z: Float, flip: Boolean = false, alpha: Float = 1f, scaleX: Float = 1f, scale: Float = 1f) {
        val o = id * 7
        val d = SpriteData.frames
        val f = s / SpriteData.PPM * scale
        val fw = f * scaleX
        val w0 = d[o + 3]
        val h0 = d[o + 4]
        val ax = d[o + 5]
        val ay = d[o + 6]
        val dx = if (flip) sx(x) - (w0 - ax) * fw else sx(x) - ax * fw
        g.image(d[o], d[o + 1], d[o + 2], w0, h0, dx, sy(y, z) - ay * f, w0 * fw, h0 * f, alpha, flip)
    }

    /** Maps a facing vector to one of the 5 rendered directions (S, SE, E, NE, N) plus a mirror flag. */
    private var dirFlip = false
    private fun dir(fx: Float, fy: Float): Int {
        val oct = kotlin.math.round(kotlin.math.atan2(fy, fx) / (PI.toFloat() / 4f)).toInt()
        dirFlip = false
        return when (oct) {
            0 -> 2
            1 -> 1
            2 -> 0
            3 -> { dirFlip = true; 1 }
            4, -4 -> { dirFlip = true; 2 }
            -3 -> { dirFlip = true; 3 }
            -2 -> 4
            else -> 3
        }
    }

    private fun walker(g: Gfx, base: Int, frames: Int, fx: Float, fy: Float, phase: Float, x: Float, y: Float, z: Float = 0f, alpha: Float = 1f) {
        val d = dir(fx, fy)
        val fr = ((phase / (2f * PI.toFloat()) * frames).toInt() % frames + frames) % frames
        sprite(g, base + d * frames + fr, x, y, z, dirFlip, alpha)
    }

    private var lastFrameNanos = -1L

    /** Seconds since the previous drawn frame, from the vsync timestamp. */
    fun frameDt(nanos: Long): Float {
        val dt = if (lastFrameNanos < 0L) 0f else ((nanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = nanos
        return dt
    }

    fun render(g: Gfx, dt: Float) {
        t += dt
        w = g.width
        h = g.height
        s = min(w / 8.4f, h / (15f * k))
        val p = game.player
        val f = min(1f, dt * 7f)
        camX += (p.x - camX) * f
        camY += (p.y - camY) * f
        val halfW = w * 0.5f / s
        camX = if (halfW * 2f >= World.WIDTH + 1f) World.WIDTH / 2f else camX.coerceIn(halfW - 0.5f, World.WIDTH + 0.5f - halfW)
        if (game.shake > 0f) {
            ox = sin(t * 61f) * game.shake * s * 0.08f
            oy = cos(t * 47f) * game.shake * s * 0.06f
        } else {
            ox = 0f; oy = 0f
        }
        vx0 = camX - halfW
        vx1 = camX + halfW
        vy0 = camY - h * 0.56f / (s * k) - 1f
        vy1 = camY + h * 0.44f / (s * k) + 1f

        g.rect(0f, 0f, w, h, Col.BG)
        drawFloors(g)
        drawFlatPads(g)
        drawSorted(g)
        drawFx(g)
        // Lighting: dark asylum, the doctor carries the light.
        g.darkness(sx(p.x), sy(p.y, 0.6f), 5.6f * s, 5.6f * s * k, 0xB8050710.toInt())
        drawLights(g)
        drawBubbles(g)
        drawPadLabels(g)
        drawHud(g)
    }

    // ------------------------------------------------------------------ floors

    private fun drawFloors(g: Gfx) {
        // Road beyond the fence
        g.rect(0f, sy(World.FENCE_Y), w, h, Col.ROAD)
        // Courtyard dirt in 2 m tiles
        var y = World.HALL_Y + 1.2f
        while (y < World.FENCE_Y + 1f) {
            var x = 1f
            while (x < World.WIDTH) {
                if (visible(x, y)) sprite(g, SpriteData.GROUND, x, y, 0f)
                x += 2f
            }
            y += 2f
        }
        // Stone path from the gate
        var py0 = World.HALL_Y + 1.2f
        while (py0 < 27f) {
            if (visible(World.GATE_X, py0)) sprite(g, SpriteData.PATH, World.GATE_X, py0, 0f)
            py0 += 2f
        }
        // Tiles of the hall and rooms
        val y0 = max(0, vy0.toInt() - 1)
        val y1 = min(20, vy1.toInt() + 2)
        val x0 = max(0, vx0.toInt() - 1)
        val x1 = min(16, vx1.toInt() + 2)
        for (ty in y0 until y1) {
            for (tx in x0 until x1) {
                val id = when {
                    ty < 7 -> SpriteData.TILE_ROOM
                    tx >= 11 -> SpriteData.TILE_BATH
                    // A rare tile with rubble, scattered so it does not form a grid.
                    ((tx * 73856093) xor (ty * 19349663)).and(0x7FFFFFFF) % 19 == 0 -> SpriteData.TILE_HALL + 1
                    else -> SpriteData.TILE_HALL
                }
                sprite(g, id, tx + 0.5f, ty + 0.5f, 0f)
            }
        }
        // Locked rooms stay in the dark.
        for (i in 0 until World.ROOM_COUNT) {
            if (game.doorOpen[i]) continue
            val rx = World.roomX[i]
            g.rect(sx(rx - 2f), sy(0f), 4f * s, sy(7.2f) - sy(0f), 0xD0080A10.toInt())
        }
    }

    private fun drawCoinIcon(g: Gfx, cx: Float, cy: Float, r: Float) {
        val o = SpriteData.COIN_ICON * 7
        val d = SpriteData.frames
        val size = r * 2.2f
        val sc = size / max(d[o + 3], d[o + 4])
        g.image(d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], cx - d[o + 3] * sc / 2, cy - d[o + 4] * sc / 2, d[o + 3] * sc, d[o + 4] * sc, 1f, false)
    }

    // ------------------------------------------------------------------ pads

    private fun drawFlatPads(g: Gfx) {
        // Coin piles: white rounded outline
        for (pile in game.piles) {
            if (!pile.enabled) continue
            g.roundRectStroke(sx(pile.x - pile.w / 2), sy(pile.y - pile.h / 2), pile.w * s, pile.h * s * k, 0.25f * s, 0.06f * s, 0xDDFFFFFF.toInt(), false)
        }
        // Drop pads of the treatment stations
        for (st in game.stations) {
            if (!st.unlocked) continue
            val occupied = st.occupant >= 0 || st.reserved >= 0
            val c = if (occupied) 0x88FFFFFF.toInt() else 0xEEFFFFFF.toInt()
            drawDashedPad(g, st.padX, st.padY, 1.25f, 1.25f, c)
            drawPersonIcon(g, sx(st.padX) - 0.25f * s, sy(st.padY), 0.22f * s, c)
            g.text(if (occupied) "1/1" else "0/1", sx(st.padX) + 0.2f * s, sy(st.padY), 0.32f * s, c, 1, true)
        }
        // Waiting bench pad
        drawDashedPad(g, game.benchPadX, game.benchPadY, 1.5f, 1.1f, 0xCCFFFFFF.toInt())
        var n = 0
        for (b in game.benchSeat) if (b >= 0) n++
        drawPersonIcon(g, sx(game.benchPadX) - 0.35f * s, sy(game.benchPadY), 0.22f * s, 0xCCFFFFFF.toInt())
        g.text(benchText.of(n, "/4"), sx(game.benchPadX) + 0.2f * s, sy(game.benchPadY), 0.32f * s, 0xCCFFFFFF.toInt(), 1, true)

        // Purchase pads
        for (i in game.pads.indices) {
            if (!game.padVisible(i)) continue
            val pd = game.pads[i]
            if (!visible(pd.x, pd.y)) continue
            val pw = 1.8f
            val ph = 1.2f
            val x = sx(pd.x - pw / 2)
            val y = sy(pd.y - ph / 2)
            val ww = pw * s
            val hh = ph * s * k
            val pulse = 0.5f + 0.5f * sin(t * 4f)
            g.roundRect(x - 0.05f * s, y - 0.05f * s, ww + 0.1f * s, hh + 0.1f * s, 0.24f * s, Col.a(Col.GREEN_LIGHT, 0.35f + 0.3f * pulse))
            g.roundRect(x, y, ww, hh, 0.2f * s, Col.GREEN_DARK)
            val prog = game.padPaid[i].toFloat() / pd.cost
            if (prog > 0f) g.roundRect(x, y, ww * prog, hh, 0.2f * s, Col.GREEN)
            g.roundRectStroke(x, y, ww, hh, 0.2f * s, 0.05f * s, Col.GREEN_LIGHT, false)
            drawPadIcon(g, pd.kind, x + 0.42f * s, y + hh * 0.5f, 0.3f * s)
        }
    }

    private fun drawDashedPad(g: Gfx, x: Float, y: Float, pw: Float, ph: Float, c: Int) {
        g.roundRectStroke(sx(x - pw / 2), sy(y - ph / 2), pw * s, ph * s * k, 0.22f * s, 0.06f * s, c, true)
    }

    private fun drawPersonIcon(g: Gfx, x: Float, y: Float, r: Float, c: Int) {
        g.oval(x, y - r * 0.55f, r * 0.42f, r * 0.42f, c)
        g.oval(x, y + r * 0.55f, r * 0.75f, r * 0.45f, c)
    }

    private fun drawPadIcon(g: Gfx, kind: Int, x: Float, y: Float, r: Float) {
        val c = Col.WHITE
        when (kind) {
            PadKind.ROOM -> { // bed
                g.rect(x - r, y - r * 0.1f, r * 2f, r * 0.55f, c)
                g.rect(x - r, y - r * 0.7f, r * 0.3f, r * 1.3f, c)
                g.rect(x - r * 0.6f, y - r * 0.4f, r * 0.6f, r * 0.3f, c)
            }
            PadKind.NURSE -> {
                drawPersonIcon(g, x, y, r, c)
                g.rect(x - r * 0.12f, y - r * 1.05f, r * 0.24f, r * 0.5f, Col.RED)
                g.rect(x - r * 0.25f, y - r * 0.92f, r * 0.5f, r * 0.24f, Col.RED)
            }
            PadKind.BATH -> {
                g.oval(x, y + r * 0.1f, r, r * 0.75f, c)
                g.oval(x, y - r * 0.3f, r * 0.8f, r * 0.25f, Col.WATER)
            }
            PadKind.CARRY -> {
                g.text("+1", x, y, r * 1.3f, c, 1, true)
            }
            PadKind.TOWER -> {
                g.arc(x, y, r * 0.9f, -70f, 140f, r * 0.2f, c)
                g.line(x, y, x - r, y, r * 0.12f, c)
            }
            PadKind.ORDERLY -> {
                drawPersonIcon(g, x, y + r * 0.2f, r * 0.9f, c)
                g.rect(x - r * 0.9f, y - r * 1.05f, r * 1.8f, r * 0.3f, c)
            }
            PadKind.SPEED -> {
                g.oval(x, y + r * 0.25f, r * 0.55f, r * 0.6f, Col.POTION)
                g.rect(x - r * 0.18f, y - r * 0.75f, r * 0.36f, r * 0.6f, Col.POTION)
            }
            PadKind.FENCE -> {
                for (i in 0 until 3) g.rect(x - r + i * r * 0.75f, y - r * 0.7f, r * 0.45f, r * 1.5f, c)
            }
            PadKind.PRICE -> {
                drawCoinIcon(g, x, y, r * 0.8f)
            }
        }
    }

    private fun drawPadLabels(g: Gfx) {
        for (i in game.pads.indices) {
            if (!game.padVisible(i)) continue
            val pd = game.pads[i]
            if (!visible(pd.x, pd.y)) continue
            val cx = sx(pd.x)
            val cy = sy(pd.y)
            val left = pd.cost - game.padPaid[i]
            drawCoinIcon(g, cx + 0.05f * s, cy, 0.17f * s)
            g.text(padCost[i].of(left, ""), cx + 0.28f * s, cy + 0.01f * s, 0.34f * s, Col.WHITE, 0, true)
            val ly = sy(pd.y - 0.6f) - 0.3f * s
            g.text(pd.label, cx + 1.5f, ly + 1.5f, 0.3f * s, 0xAA000000.toInt(), 1, true)
            g.text(pd.label, cx, ly, 0.3f * s, Col.GOLD, 1, true)
        }
    }

    // ------------------------------------------------------------------ sorted world objects

    private fun push(y: Float, type: Int, index: Int) {
        if (nKeys >= keys.size) return
        val yi = ((y + 20f) * 100f).toLong().coerceIn(0L, 0xFFFFFL)
        keys[nKeys++] = (yi shl 24) or (type.toLong() shl 16) or index.toLong()
    }

    private fun drawSorted(g: Gfx) {
        nKeys = 0
        for (i in segs.indices) {
            val b = segs[i]
            if (visible((b.x0 + b.x1) / 2, b.y1, 9f)) push(b.y1, T_WALL, i)
        }
        for (i in 0 until World.ROOM_COUNT) if (!game.doorOpen[i]) push(7.45f, T_DOOR, i)
        for (i in game.stations.indices) {
            val st = game.stations[i]
            if (!st.unlocked || !visible(st.x, st.y)) continue
            push(st.y + if (st.kind == StationKind.BATH) 0.9f else 1.1f, T_STATION, i)
            if (st.nurse) push(st.y - 0.4f, T_NURSE, i)
        }
        for (i in game.piles.indices) {
            val pl = game.piles[i]
            if (pl.enabled && pl.value > 0 && visible(pl.x, pl.y)) push(pl.y - pl.h / 2, T_PILE, i)
        }
        for (i in game.towers.indices) if (game.towers[i].active) push(game.towers[i].y + 0.6f, T_TOWER, i)
        push(game.player.y, T_PLAYER, 0)
        for (i in game.patients.indices) {
            val pt = game.patients[i]
            if (!pt.active || pt.state == PState.CARRIED || pt.state == PState.TREAT) continue
            if (!visible(pt.x, pt.y)) continue
            push(pt.y, T_PATIENT, i)
        }
        for (i in game.orderlies.indices) if (game.orderlies[i].active) push(game.orderlies[i].y, T_ORDERLY, i)
        for (i in game.rats.indices) {
            val r = game.rats[i]
            if (r.active && visible(r.x, r.y)) push(r.y, T_RAT, i)
        }
        if (game.boss.active) push(game.boss.y, T_BOSS, 0)
        for (i in game.benchCenters.indices) push(game.benchCenters[i] + 0.9f, T_BENCH, i)
        for (i in PROP_X.indices) if (visible(PROP_X[i], PROP_Y[i])) push(PROP_Y[i], T_PROP, i)

        keys.sort(0, nKeys)
        for (n in 0 until nKeys) {
            val key = keys[n]
            val type = ((key shr 16) and 0xFF).toInt()
            val idx = (key and 0xFFFF).toInt()
            when (type) {
                T_WALL -> drawWall(g, segs[idx])
                T_DOOR -> sprite(g, SpriteData.DOOR, World.roomX[idx], 7.2f, 0f)
                T_STATION -> drawStation(g, game.stations[idx])
                T_NURSE -> drawNurse(g, game.stations[idx])
                T_PILE -> drawPile(g, idx)
                T_TOWER -> sprite(g, SpriteData.TOWER, game.towers[idx].x, game.towers[idx].y, 0f)
                T_PLAYER -> drawDoctor(g)
                T_PATIENT -> drawPatient(g, game.patients[idx])
                T_ORDERLY -> drawOrderly(g, idx)
                T_RAT -> drawRat(g, idx)
                T_BOSS -> drawBoss(g)
                T_BENCH -> sprite(g, SpriteData.BENCH, game.benchX, game.benchCenters[idx], 0f)
                T_PROP -> drawProp(g, idx)
            }
        }
    }

    private fun drawWall(g: Gfx, b: Box) {
        val horizontal = b.x1 - b.x0 > b.y1 - b.y0
        val cy = (b.y0 + b.y1) / 2
        if (horizontal) {
            val len = b.x1 - b.x0
            val n = max(1, kotlin.math.round(len).toInt())
            val piece = len / n
            val id = if (b.fence) SpriteData.FENCE_H else SpriteData.WALL_H
            for (i in 0 until n) {
                val x = b.x0 + piece * (i + 0.5f)
                if (x < vx0 - 1.5f || x > vx1 + 1.5f) continue
                sprite(g, id, x, cy, 0f, scaleX = piece)
            }
        } else {
            sprite(g, if (b.fence) SpriteData.FENCE_V else SpriteData.WALL_V, (b.x0 + b.x1) / 2, cy, 0f)
        }
    }

    private fun drawStation(g: Gfx, st: Station) {
        if (st.kind == StationKind.BATH) drawCauldron(g, st) else drawBed(g, st)
    }

    private fun drawCauldron(g: Gfx, st: Station) {
        val occupied = st.occupant >= 0 && game.patients[st.occupant].state == PState.TREAT
        // Flickering fire under the pot
        val cx = sx(st.x)
        val base = sy(st.y)
        for (i in 0 until 5) {
            val fx = cx + (i - 2) * 0.28f * s
            val fh = (0.3f + 0.14f * sin(t * 13f + i * 1.7f)) * s
            px[0] = fx - 0.14f * s; py[0] = base + 0.12f * s
            px[1] = fx; py[1] = base + 0.12f * s - fh
            px[2] = fx + 0.14f * s; py[2] = base + 0.12f * s
            g.poly(px, py, 3, if (i and 1 == 0) 0xFFFF7A2A.toInt() else 0xFFFFC04A.toInt())
        }
        if (occupied) {
            sprite(g, SpriteData.CAULDRON_WATER_P, st.x, st.y, 0f)
            if (st.progress > 0.02f) sprite(g, SpriteData.CAULDRON_POTION_P, st.x, st.y, 0f, alpha = min(1f, st.progress * 1.4f))
        } else {
            sprite(g, SpriteData.CAULDRON_WATER, st.x, st.y, 0f)
        }
        // Bubbles
        val rimY = sy(st.y, 0.95f)
        for (i in 0 until 3) {
            val ph = (t * 0.9f + i * 0.33f) % 1f
            val r = 0.06f * s * (1f - ph)
            g.oval(cx + (i - 1) * 0.4f * s, rimY - ph * 0.5f * s, r, r, Col.a(Col.WHITE, 0.6f))
        }
    }

    private fun drawBed(g: Gfx, st: Station) {
        val occupied = st.occupant >= 0 && game.patients[st.occupant].state == PState.TREAT
        sprite(g, if (occupied) SpriteData.BED_P else SpriteData.BED, st.x, st.y, 0f)
    }

    private fun drawNurse(g: Gfx, st: Station) {
        val working = st.occupant >= 0 && game.patients[st.occupant].state == PState.TREAT
        val frame = if (working && sin(t * 5f) > 0f) 1 else 0
        sprite(g, SpriteData.NURSE + frame, st.x + 1.1f, st.y - 0.3f, 0f)
    }

    private fun drawPile(g: Gfx, i: Int) {
        val pile = game.piles[i]
        val v = pile.value
        val stacks = min(6, (v + 24) / 25)
        for (n in 0 until stacks) {
            val col = n % 3
            val row = n / 3
            val x = pile.x - pile.w * 0.3f + col * pile.w * 0.3f
            val y = pile.y - pile.h * 0.2f + row * pile.h * 0.4f
            val left = v - n * 25
            val id = if (left >= 60) SpriteData.COINS_L else if (left >= 20) SpriteData.COINS_M else SpriteData.COINS_S
            sprite(g, id, x, y, 0f)
        }
    }

    // ------------------------------------------------------------------ characters

    private fun drawShadow(g: Gfx, x: Float, y: Float, r: Float) {
        g.oval(sx(x), sy(y), r * s, r * s * k * 0.55f, Col.SHADOW)
    }

    private fun drawDoctor(g: Gfx) {
        val p = game.player
        // Green ring under the doctor like in the ad.
        g.ovalStroke(sx(p.x), sy(p.y), 0.6f * s, 0.6f * s * k, 0.05f * s, 0xAA59E07A.toInt())
        drawShadow(g, p.x, p.y, 0.45f)
        val n = p.stackHeight
        val stX = p.x - p.faceX * 0.32f
        val stY = p.y - p.faceY * 0.32f
        val behind = p.faceY >= -0.2f
        if (n > 0 && behind) drawCoinStack(g, stX, stY, n)
        when {
            p.carriedCount > 0 -> walker(g, SpriteData.DOCTOR_CARRY, runFrames, p.faceX, p.faceY, if (p.moving) p.walkPhase * 0.55f else 0f, p.x, p.y)
            p.moving -> walker(g, SpriteData.DOCTOR, runFrames, p.faceX, p.faceY, p.walkPhase * 0.55f, p.x, p.y)
            else -> {
                val d = dir(p.faceX, p.faceY)
                sprite(g, SpriteData.DOCTOR_IDLE + d, p.x, p.y, 0f, dirFlip)
            }
        }
        if (n > 0 && !behind) drawCoinStack(g, stX, stY, n)
        if (p.flameTarget != -1) {
            val hx = sx(p.x + p.faceX * 0.4f)
            val hy = sy(p.y + p.faceY * 0.4f, 0.8f)
            val fl = 1f + 0.25f * sin(t * 30f)
            g.oval(hx, hy, 0.14f * s * fl, 0.18f * s * fl, 0xFFFF8A2A.toInt())
            g.oval(hx, hy + 0.02f * s, 0.07f * s * fl, 0.09f * s * fl, 0xFFFFE08A.toInt())
        }
        // Carried patients ride above the doctor.
        for (i in 0 until p.carriedCount) {
            val pt = game.patients[p.carried[i]]
            sprite(g, SpriteData.LYING, pt.x, pt.y, pt.z + 0.2f, flip = pt.look and 2 != 0)
        }
    }

    private fun drawCoinStack(g: Gfx, x: Float, y: Float, n: Int) {
        for (c in 0 until n) sprite(g, SpriteData.COIN, x, y, 0.55f + c * 0.055f)
    }

    private fun drawPatient(g: Gfx, pt: Patient) {
        val flip = pt.look and 2 != 0
        when (pt.state) {
            PState.LYING -> {
                drawShadow(g, pt.x, pt.y, 0.7f)
                sprite(g, SpriteData.LYING, pt.x, pt.y, 0f, flip)
            }
            PState.FLY -> {
                drawShadow(g, pt.x, pt.y, 0.45f)
                sprite(g, SpriteData.LYING, pt.x, pt.y, pt.z, flip)
            }
            PState.DEAD -> sprite(g, SpriteData.LYING, pt.x, pt.y, 0f, flip, alpha = min(1f, pt.timer / 1.2f) * 0.7f)
            PState.BENCH -> sprite(g, SpriteData.SITTING, pt.x, pt.y, 0f)
            PState.LEAVE_HAPPY -> {
                drawShadow(g, pt.x, pt.y, 0.32f)
                walker(g, SpriteData.HEALTHY, walkFrames, pt.faceX, pt.faceY, pt.walkPhase * 0.6f, pt.x, pt.y)
            }
            else -> {
                drawShadow(g, pt.x, pt.y, 0.32f)
                walker(g, SpriteData.SICK, walkFrames, pt.faceX, pt.faceY, pt.walkPhase * 0.6f, pt.x, pt.y)
            }
        }
    }

    private fun drawOrderly(g: Gfx, i: Int) {
        val o = game.orderlies[i]
        drawShadow(g, o.x, o.y, 0.35f)
        walker(g, SpriteData.ORDERLY, runFrames, o.faceX, o.faceY, o.walkPhase * 0.5f, o.x, o.y)
        if (o.carrying >= 0) {
            val pt = game.patients[o.carrying]
            sprite(g, SpriteData.LYING, o.x, o.y + 0.01f, 1.55f, flip = pt.look and 2 != 0)
        }
    }

    private fun drawRat(g: Gfx, i: Int) {
        val r = game.rats[i]
        drawShadow(g, r.x, r.y, 0.3f)
        val l = sqrt(r.vx * r.vx + r.vy * r.vy)
        val fx = if (l > 0.01f) r.vx / l else 0f
        val fy = if (l > 0.01f) r.vy / l else 1f
        walker(g, SpriteData.RAT, ratFrames, fx, fy, r.phase, r.x, r.y, r.z, if (r.hit > 0f) 0.6f else 1f)
        if (r.hp < r.maxHp) {
            val bw = 0.5f * s
            val by = sy(r.y, 0.6f)
            g.rect(sx(r.x) - bw / 2, by, bw, 0.06f * s, 0xAA000000.toInt())
            g.rect(sx(r.x) - bw / 2, by, bw * (r.hp / r.maxHp), 0.06f * s, Col.RED)
        }
    }

    private fun drawBoss(g: Gfx) {
        val b = game.boss
        drawShadow(g, b.x, b.y, 1.7f)
        val frame = if (b.state == 1) ((b.phase * 4f).toInt() and 3) else ((b.phase * 1.5f).toInt() and 1)
        sprite(g, SpriteData.BOSS + frame, b.x, b.y, 0f, alpha = if (b.hit > 0f) 0.75f else 1f)
    }

    private fun drawProp(g: Gfx, i: Int) {
        sprite(g, PROP_ID[i], PROP_X[i], PROP_Y[i], PROP_Z[i], flip = PROP_FLIP[i])
    }

    // ------------------------------------------------------------------ effects

    private fun drawFx(g: Gfx) {
        for (a in game.arrows) {
            if (!a.active) continue
            val l = sqrt(a.vx * a.vx + a.vy * a.vy).coerceAtLeast(0.01f)
            val x0 = sx(a.x)
            val y0 = sy(a.y, 1.2f)
            g.line(x0, y0, x0 - a.vx / l * 0.5f * s, y0 - a.vy / l * 0.5f * s * k, 0.04f * s, 0xFFE8D8B0.toInt())
        }
        for (f in game.fx) {
            if (!f.active) continue
            if (!visible(f.x, f.y)) continue
            val life = f.t / f.life
            when (f.kind) {
                FxKind.COIN -> sprite(g, SpriteData.COIN, f.x, f.y, f.z)
                FxKind.PLUS -> {
                    val c = Col.a(Col.GREEN_LIGHT, 1f - life)
                    val cx = sx(f.x)
                    val cy = sy(f.y, f.z)
                    val r = 0.12f * s
                    g.rect(cx - r, cy - r * 0.3f, r * 2, r * 0.6f, c)
                    g.rect(cx - r * 0.3f, cy - r, r * 0.6f, r * 2, c)
                }
                FxKind.FLAME -> {
                    val r = (0.07f + 0.17f * life) * s
                    g.oval(sx(f.x), sy(f.y, f.z), r, r * 0.8f, Col.a(Col.mix(0xFFFFE08A.toInt(), 0xFFFF3A1A.toInt(), life), 0.9f - life * 0.7f))
                }
                FxKind.NUMBER -> {
                    g.text(NumStr.neg(f.value), sx(f.x), sy(f.y, f.z), 0.42f * s, Col.a(f.color, 1f - life * life), 1, true)
                }
                FxKind.SMOKE -> {
                    val r = (0.3f + 0.6f * life) * s
                    g.oval(sx(f.x), sy(f.y, f.z), r, r * 0.7f, Col.a(0xFF9AA0A8.toInt(), 0.5f * (1f - life)))
                }
                FxKind.CONFETTI -> {
                    val cx = sx(f.x)
                    val cy = sy(f.y, f.z)
                    val r = 0.07f * s
                    g.rect(cx - r, cy - r * 0.5f, r * 2f, r, Col.a(f.color, 1f - max(0f, life - 0.6f) * 2.5f))
                }
                FxKind.SKULL -> drawSkull(g, sx(f.x), sy(f.y, f.z), 0.2f * s, Col.a(Col.WHITE, 1f - life))
            }
        }
    }

    private fun drawLights(g: Gfx) {
        for (i in PROP_X.indices) {
            if (PROP_ID[i] != SpriteData.TORCH && PROP_ID[i] != SpriteData.LANTERN && PROP_ID[i] != SpriteData.CANDLES) continue
            if (!visible(PROP_X[i], PROP_Y[i])) continue
            val fl = 1f + 0.06f * sin(t * 11f + i * 2f)
            val r = if (PROP_ID[i] == SpriteData.CANDLES) 1.2f else 2.6f
            g.glow(sx(PROP_X[i]), sy(PROP_Y[i], PROP_Z[i] + 0.7f), r * s * fl, r * 0.8f * s * fl, 0x60FFA040)
        }
        for (st in game.stations) {
            if (!st.unlocked || !visible(st.x, st.y)) continue
            if (st.kind == StationKind.BATH) g.glow(sx(st.x), sy(st.y, 0.2f), 2.2f * s, 1.5f * s, 0x58FF7A2A)
            if (st.occupant >= 0 && game.patients[st.occupant].state == PState.TREAT) {
                g.glow(sx(st.x), sy(st.y, 0.8f), 1.5f * s, 1.2f * s, 0x4059E07A)
            }
        }
        val p = game.player
        if (p.flameTarget != -1) g.glow(sx(p.x + p.faceX * 0.4f), sy(p.y, 0.8f), 2.2f * s, 1.8f * s, 0x60FF8A2A)
        if (game.boss.active) g.glow(sx(game.boss.x), sy(game.boss.y, 1.4f), 3.4f * s, 2.8f * s, 0x50FF2030)
        for (f in game.fx) {
            if (!f.active) continue
            if (f.kind == FxKind.FLAME) {
                val life = f.t / f.life
                g.glow(sx(f.x), sy(f.y, f.z), 0.35f * s, 0.28f * s, Col.a(0xFFFF9A3A.toInt(), 0.3f * (1f - life)))
            }
        }
    }

    // ------------------------------------------------------------------ bubbles over heads

    private fun drawBubbles(g: Gfx) {
        for (pt in game.patients) {
            if (!pt.active || !visible(pt.x, pt.y)) continue
            when (pt.state) {
                PState.LYING, PState.ARRIVE -> {
                    val danger = pt.health < 0.35f && (sin(t * 10f) > 0f)
                    bubble(g, sx(pt.x), sy(pt.y, 1.0f), if (danger) Col.RED else Col.SKULL_BUBBLE)
                    drawSkull(g, sx(pt.x), sy(pt.y, 1.0f), 0.16f * s, Col.WHITE)
                    // Health ring
                    g.arc(sx(pt.x), sy(pt.y, 1.0f), 0.3f * s, -90f, 360f * pt.health, 0.05f * s, Col.a(Col.WHITE, 0.8f))
                }
                PState.BENCH -> if (pt.mood == 2) {
                    bubble(g, sx(pt.x), sy(pt.y, 1.85f), Col.WHITE)
                    drawFace(g, sx(pt.x), sy(pt.y, 1.85f), 0.22f * s, angry = true)
                } else {
                    g.rect(sx(pt.x) - 0.25f * s, sy(pt.y, 1.7f), 0.5f * s, 0.06f * s, 0x88000000.toInt())
                    g.rect(sx(pt.x) - 0.25f * s, sy(pt.y, 1.7f), 0.5f * s * pt.patience, 0.06f * s, Col.GOLD)
                }
                PState.LEAVE_HAPPY -> if (pt.y < 21f) {
                    bubble(g, sx(pt.x), sy(pt.y, 1.75f), Col.WHITE)
                    drawFace(g, sx(pt.x), sy(pt.y, 1.75f), 0.22f * s, angry = false)
                }
                PState.LEAVE_ANGRY -> if (pt.y < 21f) {
                    bubble(g, sx(pt.x), sy(pt.y, 1.75f), Col.WHITE)
                    drawFace(g, sx(pt.x), sy(pt.y, 1.75f), 0.22f * s, angry = true)
                }
            }
        }
        for (st in game.stations) {
            if (!st.unlocked || st.occupant < 0 || !visible(st.x, st.y)) continue
            val pt = game.patients[st.occupant]
            if (pt.state != PState.TREAT) continue
            val bx = sx(st.x)
            val by = sy(st.y - if (st.kind == StationKind.BED) 0.8f else 0f, 2.0f)
            val stalled = st.kind == StationKind.BED && !st.nurse && !st.doctorNear
            bubble(g, bx, by, Col.WHITE)
            if (stalled) {
                val pulse = 1f + 0.12f * sin(t * 9f)
                g.text("!", bx, by, 0.5f * s * pulse, 0xFFE0A030.toInt(), 1, true)
            } else {
                g.arc(bx, by, 0.26f * s, -90f, 360f * st.progress, 0.08f * s, Col.GREEN)
                g.oval(bx, by, 0.17f * s, 0.17f * s, Col.GREEN)
                g.rect(bx - 0.11f * s, by - 0.035f * s, 0.22f * s, 0.07f * s, Col.WHITE)
                g.rect(bx - 0.035f * s, by - 0.11f * s, 0.07f * s, 0.22f * s, Col.WHITE)
            }
        }
        for (i in 0 until World.ROOM_COUNT) {
            if (game.doorOpen[i]) continue
            val x = World.roomX[i]
            if (!visible(x, 3.5f)) continue
            val bx = sx(x)
            val by = sy(3.5f, 1.4f)
            bubble(g, bx, by, Col.WHITE)
            g.roundRect(bx - 0.14f * s, by - 0.03f * s, 0.28f * s, 0.2f * s, 0.04f * s, 0xFF2A2A2E.toInt())
            g.arc(bx, by - 0.05f * s, 0.09f * s, 180f, 180f, 0.05f * s, 0xFF2A2A2E.toInt())
        }
    }

    private fun bubble(g: Gfx, x: Float, y: Float, color: Int) {
        val r = 0.33f * s
        px[0] = x - r * 0.35f; py[0] = y + r * 0.7f
        px[1] = x + r * 0.35f; py[1] = y + r * 0.7f
        px[2] = x; py[2] = y + r * 1.35f
        g.poly(px, py, 3, color)
        g.oval(x, y + r * 0.08f, r, r, 0x55000000)
        g.oval(x, y, r, r, color)
    }

    private fun drawSkull(g: Gfx, x: Float, y: Float, r: Float, c: Int) {
        g.oval(x, y - r * 0.15f, r * 0.75f, r * 0.68f, c)
        g.rect(x - r * 0.4f, y + r * 0.2f, r * 0.8f, r * 0.42f, c)
        val hole = 0xFF3A2A20.toInt() and 0x00FFFFFF or (c and 0xFF000000.toInt())
        g.oval(x - r * 0.3f, y - r * 0.12f, r * 0.2f, r * 0.22f, hole)
        g.oval(x + r * 0.3f, y - r * 0.12f, r * 0.2f, r * 0.22f, hole)
        g.rect(x - r * 0.06f, y + r * 0.25f, r * 0.12f, r * 0.35f, hole)
    }

    private fun drawFace(g: Gfx, x: Float, y: Float, r: Float, angry: Boolean) {
        g.oval(x, y, r, r, if (angry) 0xFFF26A3A.toInt() else 0xFFFFD34A.toInt())
        val eye = 0xFF3A1A0A.toInt()
        g.oval(x - r * 0.35f, y - r * 0.15f, r * 0.12f, r * 0.15f, eye)
        g.oval(x + r * 0.35f, y - r * 0.15f, r * 0.12f, r * 0.15f, eye)
        if (angry) {
            g.line(x - r * 0.6f, y - r * 0.5f, x - r * 0.15f, y - r * 0.3f, r * 0.12f, eye)
            g.line(x + r * 0.6f, y - r * 0.5f, x + r * 0.15f, y - r * 0.3f, r * 0.12f, eye)
            g.arc(x, y + r * 0.6f, r * 0.35f, 200f, 140f, r * 0.12f, eye)
        } else {
            g.arc(x, y + r * 0.05f, r * 0.45f, 20f, 140f, r * 0.12f, eye)
        }
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(g: Gfx) {
        val u = min(w, h * 0.62f) / 400f
        val top = insetTop + 10f * u
        // Portrait with level
        g.roundRect(10f * u, top, 52f * u, 52f * u, 10f * u, 0xFF6A8AB8.toInt())
        g.roundRect(13f * u, top + 3f * u, 46f * u, 46f * u, 8f * u, 0xFF2A3A55.toInt())
        val fx = 36f * u
        val fy = top + 30f * u
        g.oval(fx, fy, 13f * u, 13f * u, Col.MASK)
        px[0] = fx - 4f * u; py[0] = fy
        px[1] = fx + 4f * u; py[1] = fy
        px[2] = fx + 14f * u; py[2] = fy + 16f * u
        g.poly(px, py, 3, Col.MASK)
        g.oval(fx - 5f * u, fy - 2f * u, 4f * u, 4f * u, Col.GOGGLE)
        g.oval(fx + 5f * u, fy - 2f * u, 4f * u, 4f * u, Col.GOGGLE)
        g.oval(fx, fy - 11f * u, 22f * u, 6f * u, Col.HAT)
        g.rect(fx - 10f * u, fy - 24f * u, 20f * u, 13f * u, Col.HAT)
        g.roundRect(40f * u, top + 38f * u, 26f * u, 18f * u, 6f * u, 0xFF1A1A22.toInt())
        g.text(levelText.of(game.chapter, ""), 53f * u, top + 47f * u, 12f * u, Col.WHITE, 1, true)

        // Coins
        val cx0 = 72f * u
        g.roundRect(cx0, top + 2f * u, 118f * u, 26f * u, 13f * u, Col.HUD_BG)
        drawCoinIcon(g, cx0 + 13f * u, top + 15f * u, 10f * u)
        g.text(coinText.ofShort(game.player.coins), cx0 + 30f * u, top + 15f * u, 15f * u, Col.WHITE, 0, true)

        // Cured / goal
        g.roundRect(cx0, top + 32f * u, 118f * u, 24f * u, 12f * u, Col.HUD_BG)
        drawPersonIcon(g, cx0 + 13f * u, top + 44f * u, 9f * u, Col.WHITE)
        val prev = game.previousGoal
        val prog = ((game.cured - prev).toFloat() / max(1, game.goal - prev)).coerceIn(0f, 1f)
        g.roundRect(cx0 + 26f * u, top + 50f * u, 86f * u, 3f * u, 1.5f * u, 0x55FFFFFF)
        g.roundRect(cx0 + 26f * u, top + 50f * u, 86f * u * prog, 3f * u, 1.5f * u, Col.GREEN_LIGHT)
        g.text(curedText.ofPair(game.cured, game.goal), cx0 + 69f * u, top + 42f * u, 13f * u, Col.WHITE, 1, true)

        // Wave timer, top right
        val rx = w - 10f * u
        g.roundRect(rx - 108f * u, top + 2f * u, 108f * u, 26f * u, 13f * u, Col.HUD_BG)
        drawRatIcon(g, rx - 92f * u, top + 15f * u, 9f * u)
        g.text(waveText.ofWave(game.wave + 1, game.waveTimer.toInt()), rx - 78f * u, top + 15f * u, 12f * u, Col.WHITE, 0, true)
        // Fence health
        if (game.fenceHp < game.fenceMax) {
            g.roundRect(rx - 108f * u, top + 32f * u, 108f * u, 12f * u, 6f * u, Col.HUD_BG)
            g.roundRect(rx - 105f * u, top + 35f * u, 102f * u * (game.fenceHp / game.fenceMax), 6f * u, 3f * u, Col.WOOD_LIGHT)
        }
        // Boss bar
        if (game.boss.active) {
            val bw = min(w * 0.7f, 300f * u)
            val bx = (w - bw) / 2
            val by = top + 66f * u
            g.roundRect(bx, by, bw, 16f * u, 8f * u, Col.HUD_BG)
            g.roundRect(bx + 18f * u, by + 4f * u, (bw - 22f * u) * (game.boss.hp / game.boss.maxHp).coerceIn(0f, 1f), 8f * u, 4f * u, Col.RED)
            g.oval(bx + 9f * u, by + 8f * u, 9f * u, 9f * u, 0xFF1A1A1A.toInt())
            drawSkull(g, bx + 9f * u, by + 8f * u, 6f * u, Col.GOLD)
        }

        // Banner
        if (game.bannerTimer > 0f) {
            val bt = game.bannerTimer
            val appear = min(1f, (2.8f - bt) * 6f).coerceAtLeast(min(1f, bt * 3f))
            val scale = 0.8f + 0.2f * min(1f, appear)
            val text = bannerString()
            val by = h * 0.3f
            val size = 34f * u * scale
            g.text(text, w / 2 + 2f * u, by + 3f * u, size, Col.a(0xFF2A1A08.toInt(), min(1f, bt * 2f)), 1, true)
            g.text(text, w / 2, by, size, Col.a(Col.GOLD, min(1f, bt * 2f)), 1, true)
        }

        // First-time hint
        if (!hasMoved) {
            val a = 0.6f + 0.4f * sin(t * 4f)
            g.text("Glisse pour bouger", w / 2, h - insetBottom - 80f * u, 18f * u, Col.a(Col.WHITE, a), 1, true)
            g.text("Ramène les malades au chaudron", w / 2, h - insetBottom - 56f * u, 13f * u, Col.a(Col.GOLD, a), 1, false)
        }

        // Joystick
        if (joyActive) {
            g.oval(joyBaseX, joyBaseY, joyRadius, joyRadius, 0x33FFFFFF)
            g.ovalStroke(joyBaseX, joyBaseY, joyRadius, joyRadius, 2f * u, 0x66FFFFFF)
            g.oval(joyKnobX, joyKnobY, joyRadius * 0.45f, joyRadius * 0.45f, 0xAAFFFFFF.toInt())
        }
        if (showFps) g.text(fpsText.of(fps, " fps"), 10f * u, h - insetBottom - 14f * u, 11f * u, 0xFF8AFF8A.toInt(), 0, false)
    }

    private fun drawRatIcon(g: Gfx, x: Float, y: Float, r: Float) {
        g.oval(x, y, r, r * 0.6f, 0xFFB0A0A8.toInt())
        g.oval(x + r * 0.9f, y - r * 0.1f, r * 0.45f, r * 0.4f, 0xFFB0A0A8.toInt())
        g.oval(x + r * 1.0f, y - r * 0.15f, r * 0.12f, r * 0.12f, Col.RED)
        g.line(x - r, y, x - r * 1.7f, y - r * 0.4f, r * 0.15f, 0xFFB0A0A8.toInt())
    }

    private fun bannerString(): String = when (game.bannerKind) {
        Banner.LEVEL -> bannerText.of(game.bannerValue, "", "NIVEAU ", " !")
        Banner.WAVE -> "LES RATS ARRIVENT !"
        Banner.BOSS -> "LA BÊTE APPROCHE !"
        Banner.FENCE -> "PALISSADE DÉTRUITE !"
        Banner.BOSS_DOWN -> "BÊTE VAINCUE !"
        Banner.UNLOCK -> game.pads[game.bannerValue].label + " !"
        else -> ""
    }

    private fun angleDeg(x: Float, y: Float): Float = (kotlin.math.atan2(y, x) * 180f / PI.toFloat())

    companion object {
        private const val T_WALL = 0
        private const val T_DOOR = 1
        private const val T_STATION = 2
        private const val T_NURSE = 3
        private const val T_PILE = 4
        private const val T_TOWER = 5
        private const val T_PLAYER = 6
        private const val T_PATIENT = 7
        private const val T_ORDERLY = 8
        private const val T_RAT = 9
        private const val T_BOSS = 10
        private const val T_BENCH = 11
        private const val T_PROP = 12

        // Decor: sprite, x, y, z, mirrored
        private val PROP_ID = intArrayOf(
            SpriteData.TORCH, SpriteData.TORCH, SpriteData.TORCH, SpriteData.TORCH, SpriteData.TORCH, SpriteData.TORCH,
            SpriteData.TABLE, SpriteData.SHELF, SpriteData.CRATES, SpriteData.BARREL, SpriteData.KEG, SpriteData.CANDLES,
            SpriteData.BARREL, SpriteData.CRATES,
            SpriteData.LANTERN, SpriteData.LANTERN, SpriteData.TREE, SpriteData.TREE, SpriteData.GRAVE, SpriteData.GRAVE,
            SpriteData.SKULL, SpriteData.RIBCAGE, SpriteData.BONE, SpriteData.BONE,
            SpriteData.KEG, SpriteData.CRATES, SpriteData.BARREL, SpriteData.BARREL,
        )
        private val PROP_X = floatArrayOf(
            0.6f, 4.7f, 8.7f, 12.7f, 6.4f, 9.6f,
            12.0f, 14.6f, 15.2f, 0.8f, 15.3f, 2.2f,
            0.7f, 11.5f,
            5.6f, 10.4f, 0.9f, 15.1f, 3.2f, 12.8f,
            4.2f, 12.1f, 6.3f, 14.4f,
            0.8f, 4.6f, 11.3f, 15.3f,
        )
        private val PROP_Y = floatArrayOf(
            7.42f, 7.42f, 7.42f, 7.42f, 20.42f, 20.42f,
            8.4f, 7.45f, 19.2f, 19.3f, 8.5f, 19.5f,
            0.7f, 0.9f,
            29.3f, 29.3f, 28.8f, 28.6f, 29.4f, 29.4f,
            24.6f, 27.1f, 28.6f, 21.3f,
            8.3f, 19.25f, 19.35f, 19.3f,
        )
        private val PROP_Z = floatArrayOf(
            0.55f, 0.55f, 0.55f, 0.55f, 0.55f, 0.55f,
            0f, 0.75f, 0f, 0f, 0f, 0f,
            0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f,
        )
        private val PROP_FLIP = booleanArrayOf(
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, true,
            false, true, false, true, false, true,
            false, false, false, true,
            true, true, false, true,
        )
    }
}

/** Remembers the last value so strings are only rebuilt when the number changes. */
class CachedText {
    private var a = Int.MIN_VALUE
    private var b = Int.MIN_VALUE
    private var str = ""

    fun of(v: Int, suffix: String, prefix: String = "", end: String = ""): String {
        if (v != a) {
            a = v; str = prefix + v + suffix + end
        }
        return str
    }

    fun ofPair(x: Int, y: Int): String {
        if (x != a || y != b) {
            a = x; b = y; str = "$x / $y"
        }
        return str
    }

    fun ofWave(wave: Int, secs: Int): String {
        if (wave != a || secs != b) {
            a = wave; b = secs; str = "Vague $wave · ${secs}s"
        }
        return str
    }

    fun ofShort(v: Int): String {
        if (v != a) {
            a = v
            str = when {
                v < 10_000 -> v.toString()
                v < 1_000_000 -> (v / 1000).toString() + "." + (v % 1000 / 100) + "K"
                else -> (v / 1_000_000).toString() + "." + (v % 1_000_000 / 100_000) + "M"
            }
        }
        return str
    }
}

/** "-123" strings for damage numbers, built once per value. */
object NumStr {
    private val cache = arrayOfNulls<String>(2000)
    fun neg(v: Int): String {
        if (v in cache.indices) {
            return cache[v] ?: "-$v".also { cache[v] = it }
        }
        return "-$v"
    }
}
