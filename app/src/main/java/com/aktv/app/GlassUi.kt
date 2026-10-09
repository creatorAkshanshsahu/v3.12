package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Brand palette + colour extraction from channel logos. */
object Accent {
    val ABYSS = 0xFF05060D.toInt()
    val SIGNAL = 0xFF3D8BFF.toInt()
    val ION = 0xFF7FE7FF.toInt()
    val INDIGO = 0xFF4B3BD6.toInt()
    val DEEP = 0xFF1B1A6B.toInt()
    val LIVE = 0xFFFF375F.toInt()
    val TEXT = 0xFFF4F6FF.toInt()
    val DIM = 0xFF9AA0C3.toInt()
    val FAINT = 0xFF5F6590.toInt()

    /** Makes any logo colour usable as a glow: saturated, mid-light. Greys fall back to brand blue. */
    fun normalize(c: Int?): Int {
        if (c == null) return SIGNAL
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return SIGNAL
        hsl[1] = max(hsl[1], 0.65f); hsl[2] = hsl[2].coerceIn(0.48f, 0.62f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** A neighbouring hue so the backdrop has depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = (hsl[0] + 48f) % 360f; hsl[2] = 0.42f
        return ColorUtils.HSLToColor(hsl)
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(SIGNAL); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/**
 * Frosted-glass surface: translucent fill, a soft top sheen and a lit edge that
 * picks up the item's accent colour when focused.
 * radiusDp < 0 gives a pill / circle.
 */
class GlassDrawable(private val dp: Float, private val radiusDp: Float = -1f) : Drawable() {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var rad = 0f

    var accent: Int = Accent.SIGNAL
        set(v) { if (field != v) { field = v; rebuild(); invalidateSelf() } }
    var focused = false
        set(v) { if (field != v) { field = v; rebuild(); invalidateSelf() } }
    var base: Int = Color.argb(20, 255, 255, 255)
        set(v) { field = v; rebuild(); invalidateSelf() }
    var showFill = true
    /** false = only drawn while focused (used for list rows in the channel guide). */
    var idleVisible = true
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    override fun onBoundsChange(b: Rect) { rebuild() }

    private fun radiusFor(b: Rect) = if (radiusDp < 0) min(b.width(), b.height()) / 2f else radiusDp * dp

    private fun rebuild() {
        val b = bounds; if (b.isEmpty) return
        val sw = (if (focused) 2f else 1f) * dp
        r.set(b.left + sw / 2, b.top + sw / 2, b.right - sw / 2, b.bottom - sw / 2)
        rad = radiusFor(b)
        fillP.color = if (focused) ColorUtils.blendARGB(base, ColorUtils.setAlphaComponent(accent, 70), 0.6f) else base
        sheenP.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.6f,
            Color.argb(if (focused) 40 else 22, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        edgeP.strokeWidth = sw
        edgeP.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(
                Color.argb(if (focused) 255 else 80, 255, 255, 255),
                ColorUtils.setAlphaComponent(accent, if (focused) 200 else 30),
                Color.argb(if (focused) 170 else 36, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        if (!focused && !idleVisible) return
        if (showFill) c.drawRoundRect(r, rad, rad, fillP)
        c.drawRoundRect(r, rad, rad, sheenP)
        c.drawRoundRect(r, rad, rad, edgeP)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: Outline) { o.setRoundRect(bounds, radiusFor(bounds)); o.alpha = 0.9f }
}

/** Living backdrop: three slow-drifting light fields that flow into the focused item's colours. */
class AuroraView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val cols = intArrayOf(Accent.SIGNAL, Accent.INDIGO, Accent.DEEP)
    // x, y (fractions of size), radius (fraction of width), phase
    private val blobs = arrayOf(
        floatArrayOf(0.80f, 0.22f, 0.70f, 0f),
        floatArrayOf(0.08f, 0.00f, 0.55f, 2.1f),
        floatArrayOf(0.45f, 1.10f, 0.80f, 4.2f))
    private val alphas = intArrayOf(150, 105, 120)
    private val shaders = arrayOfNulls<RadialGradient>(3)
    private val m = Matrix()
    private val p = Paint()
    private var t = 0f
    private var last = 0L
    private var colorAnim: ValueAnimator? = null
    private val drift = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 48000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            val now = SystemClock.uptimeMillis()
            if (now - last > 40) { last = now; invalidate() }   // ~25fps is plenty for slow light
        }
    }

    fun setPalette(a: Int, b: Int) {
        val fromA = cols[0]; val fromB = cols[1]
        if (fromA == a && fromB == b) return
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                cols[0] = ColorUtils.blendARGB(fromA, a, f); cols[1] = ColorUtils.blendARGB(fromB, b, f)
                rebuild(); invalidate()
            }
            start()
        }
    }

    fun pause() { if (drift.isRunning) drift.pause() }
    fun resume() { if (drift.isPaused) drift.resume() else if (!drift.isStarted && isAttachedToWindow) drift.start() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!drift.isStarted) drift.start() }
    override fun onDetachedFromWindow() { drift.cancel(); colorAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }

    private fun rebuild() {
        if (width == 0) return
        for (i in 0..2) {
            val rad = blobs[i][2] * width; val c = cols[i]
            shaders[i] = RadialGradient(0f, 0f, rad,
                intArrayOf(ColorUtils.setAlphaComponent(c, alphas[i]), ColorUtils.setAlphaComponent(c, alphas[i] / 3), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Accent.ABYSS)
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0..2) {
            val s = shaders[i] ?: continue; val b = blobs[i]
            val cx = w * (b[0] + 0.05f * sin(t + b[3])); val cy = h * (b[1] + 0.07f * cos(2 * t + b[3]))
            m.setTranslate(cx, cy); s.setLocalMatrix(m); p.shader = s
            cv.drawRect(0f, 0f, w, h, p)
        }
    }
}

/** Keeps the focused item pinned to the start edge, like Netflix rows. */
class SnapLayoutManager(c: Context, orientation: Int) : LinearLayoutManager(c, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val d = if (orientation == RecyclerView.HORIZONTAL) child.left - lp.leftMargin - parent.paddingLeft
                else child.top - lp.topMargin - parent.paddingTop
        if (d == 0) return false
        if (orientation == RecyclerView.HORIZONTAL) { if (immediate) parent.scrollBy(d, 0) else parent.smoothScrollBy(d, 0) }
        else { if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d) }
        return true
    }
}
