package com.lastasylum.verify

import com.lastasylum.game.engine.Game
import com.lastasylum.game.engine.PState
import com.lastasylum.game.engine.StationKind
import com.lastasylum.game.engine.Path
import com.lastasylum.game.render.Renderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Headless check of the game: a bot plays for a while, we check the economy progresses,
 * time the simulation step and write screenshots made by the real renderer.
 *
 *   gradle run --args="<out dir> <minutes>"
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val minutes = args.getOrElse(1) { "12" }.toFloat()
    val atlas = File("../composeApp/src/commonMain/composeResources/drawable")

    val game = Game(seed = 7)
    val bot = Bot(game)
    val renderer = Renderer(game)
    renderer.hasMoved = true
    val dt = 1f / 60f
    val frames = (minutes * 60f * 60f).toInt()
    val shotsAt = setOf(5f, 40f, 120f, 240f, 420f, 600f).map { (it * 60).toInt() }.toSet()
    var worst = 0L
    var total = 0L
    var lastLog = 0
    for (f in 0 until frames) {
        bot.think()
        val t0 = System.nanoTime()
        game.update(dt)
        val el = System.nanoTime() - t0
        if (f > 600) {
            total += el; if (el > worst) worst = el
        }
        while (game.pollEvent() != 0) Unit
        if (f in shotsAt || f == frames - 1) shot(game, renderer, atlas, File(out, "shot_%04ds.png".format(f / 60)))
        if (f - lastLog >= 60 * 60) {
            lastLog = f
            println("t=%3ds coins=%5d cured=%4d level=%d bought=%2d/%d wave=%d capacity=%d".format(
                f / 60, game.player.coins, game.cured, game.chapter, java.lang.Long.bitCount(game.bought), game.pads.size, game.wave, game.player.capacity))
            if (System.getenv("DBG") != null) {
                println("  player %.1f,%.1f carry=%d".format(game.player.x, game.player.y, game.player.carriedCount))
                for (st in game.stations) if (st.unlocked) println("  station occ=%d res=%d prog=%.2f state=%d".format(st.occupant, st.reserved, st.progress, if (st.occupant >= 0) game.patients[st.occupant].state else -1))
                println("  bench " + game.benchSeat.joinToString { if (it < 0) "-" else game.patients[it].state.toString() })
            }
        }
    }
    println("simulation step: avg %.3f ms, worst %.3f ms".format(total / 1e6 / (frames - 600), worst / 1e6))
    val save = game.save()
    val g2 = Game()
    g2.load(save)
    check(g2.cured == game.cured && g2.bought == game.bought) { "save/load mismatch" }
    println("save/load ok: $save")
}

private fun shot(game: Game, renderer: Renderer, atlas: File, file: File) {
    val img = BufferedImage(1080, 2400, BufferedImage.TYPE_INT_ARGB)
    val g = Java2DGfx(img, atlas)
    renderer.insetTop = 90f
    // Warm the camera up so it is centered on the doctor.
    repeat(30) { renderer.render(g, 1f / 60f) }
    g.dispose()
    val small = BufferedImage(540, 1200, BufferedImage.TYPE_INT_RGB)
    val sg = small.createGraphics()
    sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    sg.drawImage(img, 0, 0, 540, 1200, null)
    sg.dispose()
    ImageIO.write(small, "png", file)
    println("wrote $file")
}

/** Plays like a human would: fetch the sick, drop them at a free spot, collect coins, buy upgrades. */
class Bot(private val game: Game) {
    private val path = Path()
    private var goalX = 0f
    private var goalY = 0f
    private var replan = 0

    fun think() {
        val p = game.player
        if (--replan <= 0) {
            replan = 20
            pickGoal()
            path.route(p.x, p.y, goalX, goalY)
        }
        while (!path.done) {
            val dx = path.tx - p.x
            val dy = path.ty - p.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < 0.15f) {
                path.next(); continue
            }
            game.inputX = dx / d
            game.inputY = dy / d
            return
        }
        game.inputX = 0f; game.inputY = 0f
    }

    private fun pickGoal() {
        val p = game.player
        // 1. Deliver carried patients: a free station, else the bench, else wait by a bed.
        if (p.carriedCount > 0) {
            for (st in game.stations) if (st.free) {
                goalX = st.padX; goalY = st.padY; return
            }
            if (game.benchSeat.any { it < 0 }) {
                goalX = game.benchPadX; goalY = game.benchPadY; return
            }
            if (bedNeedingDoctor()) return
        }
        // 2. Spend coins on the cheapest visible pad we can finish.
        var bestPad = -1
        for (i in game.pads.indices) {
            if (!game.padVisible(i)) continue
            val left = game.pads[i].cost - game.padPaid[i]
            if (p.coins >= left && (bestPad < 0 || left < game.pads[bestPad].cost - game.padPaid[bestPad])) bestPad = i
        }
        if (bestPad >= 0) {
            goalX = game.pads[bestPad].x; goalY = game.pads[bestPad].y; return
        }
        // 3. Beds without a nurse only heal while the doctor stands close.
        if (bedNeedingDoctor()) return
        // 4. Collect coins.
        var best = -1
        for (i in game.piles.indices) if (game.piles[i].value > 30 && (best < 0 || game.piles[i].value > game.piles[best].value)) best = i
        if (best >= 0) {
            goalX = game.piles[best].x; goalY = game.piles[best].y; return
        }
        // 5. Pick up the nearest sick person.
        var bd = Float.MAX_VALUE
        for (pt in game.patients) {
            if (!pt.active || pt.state != PState.LYING) continue
            val d = (pt.x - p.x) * (pt.x - p.x) + (pt.y - p.y) * (pt.y - p.y)
            if (d < bd) {
                bd = d; goalX = pt.x; goalY = pt.y
            }
        }
        if (bd == Float.MAX_VALUE) {
            goalX = 8f; goalY = 24f
        }
    }

    private fun bedNeedingDoctor(): Boolean {
        for (st in game.stations) {
            if (st.kind != StationKind.BED || st.nurse || st.occupant < 0) continue
            if (game.patients[st.occupant].state != PState.TREAT) continue
            goalX = st.padX; goalY = st.padY
            return true
        }
        return false
    }
}
