package com.lastasylum.game.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.lastasylum.game.render.Gfx
import kotlin.math.roundToInt

/**
 * [Gfx] on top of a Compose [DrawScope]. Paths, strokes, text styles, light textures and color
 * filters are created once and reused, so a frame does not allocate.
 */
class ComposeGfx(
    private val measurer: TextMeasurer,
    private val pages: List<ImageBitmap>,
    private val fontScale: Float,
) : Gfx {
    lateinit var scope: DrawScope

    override val width: Float get() = scope.size.width
    override val height: Float get() = scope.size.height

    private val path = Path()
    private val glowTex = radialTexture(floatArrayOf(0f, 0.35f, 1f), floatArrayOf(1f, 0.55f, 0f))
    private val holeTex = radialTexture(floatArrayOf(0f, 0.4f, 1f), floatArrayOf(0f, 0.3f, 1f))

    // Small fixed caches (no boxing, no maps).
    private val strokeW = FloatArray(16)
    private val strokeDash = BooleanArray(16)
    private val strokes = arrayOfNulls<Stroke>(16)
    private var strokeNext = 0
    private val filterRgb = IntArray(32)
    private val filters = arrayOfNulls<ColorFilter>(32)
    private var filterNext = 0
    private val styles = arrayOfNulls<TextStyle>(512)

    private fun radialTexture(stops: FloatArray, alphas: FloatArray): ImageBitmap {
        val size = 256
        val img = ImageBitmap(size, size)
        val canvas = Canvas(img)
        val paint = Paint()
        paint.shader = RadialGradientShader(
            center = Offset(size / 2f, size / 2f),
            radius = size / 2f,
            colors = alphas.map { Color(1f, 1f, 1f, it) },
            colorStops = stops.toList(),
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return img
    }

    private fun stroke(width: Float, dashed: Boolean, round: Boolean = false): Stroke {
        for (i in strokes.indices) {
            val s = strokes[i] ?: continue
            if (strokeW[i] == width && strokeDash[i] == dashed && (s.cap == StrokeCap.Round) == round) return s
        }
        val s = Stroke(
            width = width,
            cap = if (round) StrokeCap.Round else StrokeCap.Butt,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 2.5f, width * 2f)) else null,
        )
        val i = strokeNext
        strokeNext = (strokeNext + 1) % strokes.size
        strokes[i] = s; strokeW[i] = width; strokeDash[i] = dashed
        return s
    }

    private fun tint(rgb: Int): ColorFilter {
        for (i in filters.indices) if (filters[i] != null && filterRgb[i] == rgb) return filters[i]!!
        val f = ColorFilter.tint(Color(rgb or 0xFF000000.toInt()), BlendMode.SrcIn)
        val i = filterNext
        filterNext = (filterNext + 1) % filters.size
        filters[i] = f; filterRgb[i] = rgb
        return f
    }

    private fun style(size: Float, bold: Boolean): TextStyle {
        val px = size.roundToInt().coerceIn(1, 255)
        val i = px * 2 + if (bold) 1 else 0
        return styles[i] ?: TextStyle(
            fontSize = (px / fontScale).sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = Color.White,
        ).also { styles[i] = it }
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        scope.drawRect(Color(color), Offset(x, y), Size(w, h))
    }

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        scope.drawRoundRect(Color(color), Offset(x, y), Size(w, h), CornerRadius(r, r))
    }

    override fun roundRectStroke(x: Float, y: Float, w: Float, h: Float, r: Float, stroke: Float, color: Int, dashed: Boolean) {
        scope.drawRoundRect(Color(color), Offset(x, y), Size(w, h), CornerRadius(r, r), style = stroke(stroke, dashed))
    }

    override fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        scope.drawOval(Color(color), Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
    }

    override fun ovalStroke(cx: Float, cy: Float, rx: Float, ry: Float, stroke: Float, color: Int) {
        scope.drawOval(Color(color), Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f), style = stroke(stroke, false))
    }

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, stroke: Float, color: Int) {
        scope.drawArc(Color(color), startDeg, sweepDeg, false, Offset(cx - r, cy - r), Size(r * 2f, r * 2f), style = stroke(stroke, false, round = true))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) {
        scope.drawLine(Color(color), Offset(x1, y1), Offset(x2, y2), stroke, StrokeCap.Round)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        path.rewind()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        path.close()
        scope.drawPath(path, Color(color))
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int, bold: Boolean) {
        if (s.isEmpty()) return
        val layout = measurer.measure(s, style(size, bold))
        val w = layout.size.width
        val h = layout.size.height
        val dx = when (align) {
            1 -> x - w / 2f
            2 -> x - w
            else -> x
        }
        scope.drawText(layout, color = Color(color), topLeft = Offset(dx, y - h / 2f))
    }

    override fun image(page: Int, sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float, alpha: Float, flipX: Boolean) {
        val img = pages.getOrNull(page) ?: return
        if (img.width < sx + sw || img.height < sy + sh) return // still loading
        val dst = IntOffset(dx.roundToInt(), dy.roundToInt())
        val size = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1))
        if (flipX) {
            scope.scale(-1f, 1f, Offset(dst.x + size.width / 2f, dy + dh / 2f)) {
                drawImage(img, IntOffset(sx, sy), IntSize(sw, sh), dst, size, alpha = alpha, filterQuality = FilterQuality.Low)
            }
        } else {
            scope.drawImage(img, IntOffset(sx, sy), IntSize(sw, sh), dst, size, alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }

    override fun glow(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val a = (color ushr 24) / 255f
        if (a <= 0.004f) return
        scope.drawImage(
            glowTex, IntOffset.Zero, IntSize(256, 256),
            IntOffset((cx - rx).roundToInt(), (cy - ry).roundToInt()), IntSize((rx * 2f).roundToInt(), (ry * 2f).roundToInt()),
            alpha = a, colorFilter = tint(color and 0xFFFFFF), blendMode = BlendMode.Plus,
        )
    }

    override fun darkness(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val a = (color ushr 24) / 255f
        val c = Color(color)
        val x0 = cx - rx
        val y0 = cy - ry
        val x1 = cx + rx
        val y1 = cy + ry
        scope.drawImage(
            holeTex, IntOffset.Zero, IntSize(256, 256),
            IntOffset(x0.roundToInt(), y0.roundToInt()), IntSize((x1 - x0).roundToInt(), (y1 - y0).roundToInt()),
            alpha = a, colorFilter = tint(color and 0xFFFFFF),
        )
        val w = width
        val h = height
        val ix0 = x0.roundToInt().toFloat()
        val iy0 = y0.roundToInt().toFloat()
        val ix1 = ix0 + (x1 - x0).roundToInt()
        val iy1 = iy0 + (y1 - y0).roundToInt()
        if (iy0 > 0f) scope.drawRect(c, Offset(0f, 0f), Size(w, iy0))
        if (iy1 < h) scope.drawRect(c, Offset(0f, iy1), Size(w, h - iy1))
        if (ix0 > 0f) scope.drawRect(c, Offset(0f, iy0), Size(ix0, iy1 - iy0))
        if (ix1 < w) scope.drawRect(c, Offset(ix1, iy0), Size(w - ix1, iy1 - iy0))
    }
}
