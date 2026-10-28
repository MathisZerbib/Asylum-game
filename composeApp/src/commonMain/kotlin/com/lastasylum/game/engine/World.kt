package com.lastasylum.game.engine

import kotlin.math.abs

/**
 * Static map of the asylum, in meters. +x goes east, +y goes south (towards the camera).
 *
 *   y 0..7    four bed rooms (doors on their south wall, locked at start)
 *   y 7..20   main hall, with the bath cauldrons on the east side
 *   y 20..30  courtyard where the sick lie down, closed by a wooden fence at y = 30
 */
class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val height: Float, val fence: Boolean = false)

object World {
    const val WIDTH = 16f
    const val ROOM_Y = 7f
    const val HALL_Y = 20f
    const val FENCE_Y = 30f
    const val GATE_X0 = 7f
    const val GATE_X1 = 9f
    const val GATE_X = 8f
    const val ROOM_COUNT = 4
    const val WALL_H = 1.05f
    const val FENCE_H = 0.65f

    val roomX = floatArrayOf(2f, 6f, 10f, 14f)

    const val ZONE_COURT = -1
    const val ZONE_HALL = -2

    val walls: Array<Box> = arrayOf(
        // outer shell of the building
        Box(-0.4f, -0.4f, 16.4f, 0f, WALL_H),
        Box(-0.4f, 0f, 0f, 20.4f, WALL_H),
        Box(16f, 0f, 16.4f, 20.4f, WALL_H),
        // room dividers
        Box(3.8f, 0f, 4.2f, 7.4f, WALL_H),
        Box(7.8f, 0f, 8.2f, 7.4f, WALL_H),
        Box(11.8f, 0f, 12.2f, 7.4f, WALL_H),
        // south wall of the rooms, with a door gap in front of every bed
        Box(0f, 7f, 1.2f, 7.4f, WALL_H),
        Box(2.8f, 7f, 3.8f, 7.4f, WALL_H),
        Box(4.2f, 7f, 5.2f, 7.4f, WALL_H),
        Box(6.8f, 7f, 7.8f, 7.4f, WALL_H),
        Box(8.2f, 7f, 9.2f, 7.4f, WALL_H),
        Box(10.8f, 7f, 11.8f, 7.4f, WALL_H),
        Box(12.2f, 7f, 13.2f, 7.4f, WALL_H),
        Box(14.8f, 7f, 16f, 7.4f, WALL_H),
        // south wall of the hall, gate in the middle
        Box(-0.4f, 20f, GATE_X0, 20.4f, WALL_H),
        Box(GATE_X1, 20f, 16.4f, 20.4f, WALL_H),
        // courtyard fences
        Box(-0.25f, 20.4f, 0f, 30f, FENCE_H, fence = true),
        Box(16f, 20.4f, 16.25f, 30f, FENCE_H, fence = true),
        Box(-0.25f, 30f, 16.25f, 30.25f, FENCE_H, fence = true),
    )

    /** Invisible blockers in the doors of rooms that are still locked. */
    val doorBlockers: Array<Box> = Array(ROOM_COUNT) { i ->
        Box(roomX[i] - 0.8f, 7f, roomX[i] + 0.8f, 7.4f, 0f)
    }

    fun zoneOf(x: Float, y: Float): Int = when {
        y > HALL_Y + 0.2f -> ZONE_COURT
        y < ROOM_Y + 0.2f -> (x / 4f).toInt().coerceIn(0, ROOM_COUNT - 1)
        else -> ZONE_HALL
    }
}

/** A short list of waypoints. Fixed storage, so no allocation while walking. */
class Path {
    private val pts = FloatArray(16)
    var count = 0
        private set
    var index = 0
        private set

    val done get() = index >= count
    val tx get() = pts[index * 2]
    val ty get() = pts[index * 2 + 1]

    fun clear() {
        count = 0; index = 0
    }

    private fun add(x: Float, y: Float) {
        if (count < 8) {
            pts[count * 2] = x; pts[count * 2 + 1] = y; count++
        }
    }

    /**
     * Walks through a doorway at [x] from [yIn] to [yOut]. The entry point is skipped when we
     * already stand in the doorway, so a route rebuilt mid-way never walks back out of it.
     */
    private fun cross(fx: Float, fy: Float, x: Float, yIn: Float, yOut: Float) {
        val inside = abs(fx - x) < 0.6f && (fy - yIn) * (yOut - yIn) > 0f && (fy - yOut) * (yIn - yOut) > 0f
        if (!inside) add(x, yIn)
        add(x, yOut)
    }

    fun next() {
        index++
    }

    /** Builds a route from (fx,fy) to (tx,ty) through doors and the gate. */
    fun route(fx: Float, fy: Float, tx: Float, ty: Float) {
        clear()
        val zf = World.zoneOf(fx, fy)
        val zt = World.zoneOf(tx, ty)
        if (zf != zt) {
            if (zf >= 0) cross(fx, fy, World.roomX[zf], 6.4f, 8.0f)
            else if (zf == World.ZONE_COURT) cross(fx, fy, World.GATE_X, 21.2f, 19.0f)
            if (zt >= 0) cross(fx, fy, World.roomX[zt], 8.0f, 6.4f)
            else if (zt == World.ZONE_COURT) cross(fx, fy, World.GATE_X, 19.0f, 21.2f)
        }
        add(tx, ty)
    }
}

/** Small allocation-free xorshift random generator. */
class Rng(seed: Long) {
    private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextInt(): Int {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return (s ushr 33).toInt()
    }

    fun f(): Float = (nextInt() and 0xFFFFFF) / 16777216f
    fun range(a: Float, b: Float): Float = a + (b - a) * f()
    fun int(n: Int): Int = (nextInt() and 0x7FFFFFFF) % n
}
