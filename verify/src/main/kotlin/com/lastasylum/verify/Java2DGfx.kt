package com.lastasylum.verify

import com.lastasylum.game.render.Gfx
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Java2D implementation of [Gfx], used to render headless screenshots of the real renderer. */
class Java2DGfx(private val img: BufferedImage, atlasDir: File) : Gfx {
    private val g: Graphics2D = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }
    private val pages: List<BufferedImage> = generateSequence(0) { it + 1 }
        .map { File(atlasDir, "atlas$it.png") }
        .takeWhile { it.exists() }
        .map { ImageIO.read(it) }
        .toList()

    override val width get() = img.width.toFloat()
    override val height get() = img.height.toFloat()

    private fun col(c: Int) = Color(c, true)

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        g.color = col(color); g.fill(Rectangle2D.Float(x, y, w, h))
    }

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        g.color = col(color); g.fill(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2))
    }

    override fun roundRectStroke(x: Float, y: Float, w: Float, h: Float, r: Float, stroke: Float, color: Int, dashed: Boolean) {
        g.color = col(color)
        g.stroke = if (dashed) BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(stroke * 2.5f, stroke * 2f), 0f)
        else BasicStroke(stroke)
        g.draw(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2))
    }

    override fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        g.color = col(color); g.fill(Ellipse2D.Float(cx - rx, cy - ry, rx * 2, ry * 2))
    }

    override fun ovalStroke(cx: Float, cy: Float, rx: Float, ry: Float, stroke: Float, color: Int) {
        g.color = col(color); g.stroke = BasicStroke(stroke); g.draw(Ellipse2D.Float(cx - rx, cy - ry, rx * 2, ry * 2))
    }

    override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, stroke: Float, color: Int) {
        g.color = col(color)
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // Java2D angles are counter-clockwise; Compose's are clockwise.
        g.draw(Arc2D.Float(cx - r, cy - r, r * 2, r * 2, -startDeg, -sweepDeg, Arc2D.OPEN))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float, color: Int) {
        g.color = col(color)
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    override fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        val p = Path2D.Float()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until n) p.lineTo(xs[i], ys[i])
        p.closePath()
        g.color = col(color); g.fill(p)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int, bold: Boolean) {
        g.font = Font("SansSerif", if (bold) Font.BOLD else Font.PLAIN, size.toInt().coerceAtLeast(1))
        val fm = g.fontMetrics
        val w = fm.stringWidth(s)
        val dx = when (align) {
            1 -> x - w / 2f
            2 -> x - w
            else -> x
        }
        g.color = col(color)
        g.drawString(s, dx, y + (fm.ascent - fm.descent) / 2f)
    }

    override fun image(page: Int, sx: Int, sy: Int, sw: Int, sh: Int, dx: Float, dy: Float, dw: Float, dh: Float, alpha: Float, flipX: Boolean) {
        val src = pages.getOrNull(page) ?: return
        val old = g.composite
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        val at = AffineTransform()
        if (flipX) {
            at.translate((dx + dw).toDouble(), dy.toDouble()); at.scale(-dw.toDouble() / sw, dh.toDouble() / sh)
        } else {
            at.translate(dx.toDouble(), dy.toDouble()); at.scale(dw.toDouble() / sw, dh.toDouble() / sh)
        }
        val sub = src.getSubimage(sx, sy, sw, sh)
        g.drawImage(sub, at, null)
        g.composite = old
    }

    override fun glow(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        if (rx <= 0f || ry <= 0f) return
        val old = g.transform
        g.translate(cx.toDouble(), cy.toDouble())
        g.scale(1.0, (ry / rx).toDouble())
        g.paint = RadialGradientPaint(Point2D.Float(0f, 0f), rx, floatArrayOf(0f, 1f), arrayOf(col(color), Color(color and 0x00FFFFFF, true)))
        g.fill(Ellipse2D.Float(-rx, -rx, rx * 2, rx * 2))
        g.transform = old
    }

    override fun darkness(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val old = g.transform
        g.translate(cx.toDouble(), cy.toDouble())
        g.scale(1.0, (ry / rx).toDouble())
        val clear = Color(color and 0x00FFFFFF, true)
        g.paint = RadialGradientPaint(
            Point2D.Float(0f, 0f), rx, floatArrayOf(0f, 0.45f, 1f), arrayOf(clear, Color(color and 0x00FFFFFF or (((color ushr 24) * 3 / 10) shl 24), true), col(color)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        val big = (img.width + img.height) * 2f
        g.fill(Rectangle2D.Float(-big, -big, big * 2, big * 2))
        g.transform = old
    }

    fun dispose() = g.dispose()
}
