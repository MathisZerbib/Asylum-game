package com.lastasylum.game.render

/**
 * The few drawing calls the renderer needs. Colors are ARGB ints.
 * Implemented with Compose Canvas on device, and with Java2D for headless screenshots.
 * Implementations must not allocate per call on the hot path.
 */
interface Gfx {
    val width: Float
    val height: Float

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int)
    fun roundRectStroke(x: Float, y: Float, w: Float, h: Float, r: Float, stroke: Float, color: Int, dashed: Boolean)
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int)
    fun ovalStroke(cx: Float, cy: Float, rx: Float, ry: Float, stroke: Float, color: Int)
    fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, stroke: Float, color: Int)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int)

    /** Filled polygon from the first [n] points of [xs] / [ys]. */
    fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int)

    /** Text vertically centered on [y]. align: 0 left, 1 center, 2 right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int, bold: Boolean)

    /**
     * Draws the (sx, sy, sw, sh) rectangle of atlas page [page] into (dx, dy, dw, dh).
     * [flipX] mirrors the image horizontally inside its destination rectangle.
     */
    fun image(page: Int, sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float, alpha: Float, flipX: Boolean)

    /** Soft radial light, [color] at the center fading to transparent. */
    fun glow(cx: Float, cy: Float, rx: Float, ry: Float, color: Int)

    /** Fills the whole screen with [color] except a soft elliptical hole around (cx, cy). */
    fun darkness(cx: Float, cy: Float, rx: Float, ry: Float, color: Int)
}

object Col {
    fun a(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun mix(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a = ((c1 ushr 24) + (((c2 ushr 24) - (c1 ushr 24)) * tt)).toInt()
        val r = (((c1 shr 16) and 255) + ((((c2 shr 16) and 255) - ((c1 shr 16) and 255)) * tt)).toInt()
        val g = (((c1 shr 8) and 255) + ((((c2 shr 8) and 255) - ((c1 shr 8) and 255)) * tt)).toInt()
        val b = ((c1 and 255) + (((c2 and 255) - (c1 and 255)) * tt)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    const val BG = 0xFF0C0E14.toInt()
    const val HALL_TILE = 0xFF3B4250.toInt()
    const val HALL_TILE2 = 0xFF353B48.toInt()
    const val TILE_LINE = 0xFF2A2F3A.toInt()
    const val ROOM_TILE = 0xFF464B57.toInt()
    const val BATH_TILE = 0xFF3A3F4A.toInt()
    const val COURT = 0xFF262B26.toInt()
    const val COURT2 = 0xFF2C322B.toInt()
    const val ROAD = 0xFF1A1C1F.toInt()
    const val WALL_TOP = 0xFF4E4A47.toInt()
    const val WALL_FRONT = 0xFF2A2626.toInt()
    const val WALL_TRIM = 0xFF3A3230.toInt()
    const val WOOD = 0xFF5C3D26.toInt()
    const val WOOD_DARK = 0xFF3A2617.toInt()
    const val WOOD_LIGHT = 0xFF7A5434.toInt()
    const val COAT = 0xFF4A3A2E.toInt()
    const val COAT_DARK = 0xFF2F241C.toInt()
    const val HAT = 0xFF2A2420.toInt()
    const val MASK = 0xFFE9DFC9.toInt()
    const val GOGGLE = 0xFF59E07A.toInt()
    const val COIN = 0xFFC9773F.toInt()
    const val COIN_DARK = 0xFF8A4A22.toInt()
    const val COIN_LIGHT = 0xFFF0A365.toInt()
    const val SHADOW = 0x66000000
    const val SKIN = 0xFFE3B48E.toInt()
    const val SKIN_SICK = 0xFFA9BF8A.toInt()
    const val VEST = 0xFF2E4D7C.toInt()
    const val SHIRT = 0xFFE8E6E0.toInt()
    const val TROUSERS = 0xFF3B3A40.toInt()
    const val GREEN = 0xFF3FB34F.toInt()
    const val GREEN_LIGHT = 0xFF6FE07C.toInt()
    const val GREEN_DARK = 0xFF2A7A35.toInt()
    const val RED = 0xFFE0453A.toInt()
    const val GOLD = 0xFFF4D27A.toInt()
    const val GOLD_DARK = 0xFF9A6A1E.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val SKULL_BUBBLE = 0xFFB07A52.toInt()
    const val TORCH = 0xFFFFB347.toInt()
    const val WATER = 0xFF2F6DB2.toInt()
    const val POTION = 0xFF5BDB6C.toInt()
    const val HUD_BG = 0xCC14161C.toInt()
}
