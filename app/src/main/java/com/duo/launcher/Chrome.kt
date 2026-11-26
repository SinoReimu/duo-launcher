package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.min
import kotlin.math.roundToInt

enum class Glyph { SEARCH, DONE, ADD, SETTINGS }

/**
 * What makes the rail's glass read as frosted. In the rail modes pages slide behind the dock
 * capsule and the round buttons; the workspace cuts those shapes out of its own drawing
 * ([Workspace.dispatchDraw]) and each piece of glass draws the pages back, blurred, inside its
 * shape. The blur covers only the glass itself, a few thousand pixels, and the recording just
 * references the pages' render nodes, so a page turn costs no re-recording.
 */
class GlassBackdrop(private val L: MainActivity, private val host: View) {
    private val node = RenderNode("glassBackdrop")
    private val a = Rect()
    private val b = Rect()
    private var radius = 0f

    fun draw(c: Canvas, shape: Path) {
        val p = L.profile ?: return
        if (!p.dockVertical || !c.isHardwareAccelerated || host.width == 0) return
        val ws = L.workspace
        if (!ws.isLaidOut || !host.isAttachedToWindow || !ws.isAttachedToWindow) return
        // Layout positions (no translation: a receding dock takes its backdrop along).
        a.set(0, 0, 0, 0); L.root.offsetDescendantRectToMyCoords(host, a)
        b.set(0, 0, 0, 0); L.root.offsetDescendantRectToMyCoords(ws, b)
        val r = p.r(12f)
        val m = (r * 2).toInt()
        if (radius != r) {
            radius = r
            node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
        }
        node.setPosition(-m, -m, host.width + m, host.height + m)
        val rc = node.beginRecording()
        rc.translate((b.left - a.left + m).toFloat(), (b.top - a.top + m).toFloat())
        ws.drawPagesInto(rc)
        node.endRecording()
        c.save()
        c.clipPath(shape)
        c.drawRenderNode(node)
        c.restore()
    }
}

/**
 * A round glass button as in the iPhone Duo rail: the search button under the dock, and the
 * edit-mode buttons that take the top of the rail. Draws itself (one View, no children) and
 * animates only RenderNode properties (scale, alpha).
 */
@SuppressLint("ViewConstructor")
class GlassButton(private val L: MainActivity, private val glyph: Glyph, private val onTap: () -> Unit) : View(L) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()
    private val circle = Path()
    private val backdrop = GlassBackdrop(L, this)
    var shown = true
        private set
    private var inside = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x42FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x99FFFFFF.toInt(), 0x24FFFFFF, 0x5CFFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        pivotX = w / 2f; pivotY = h / 2f
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f; val cy = height / 2f
        val d = resources.displayMetrics.density
        rim.strokeWidth = d
        circle.rewind(); circle.addCircle(cx, cy, s / 2f, Path.Direction.CW)
        backdrop.draw(c, circle)
        c.drawCircle(cx, cy, s / 2f, fill)
        c.drawCircle(cx, cy, s / 2f - d / 2f, rim)
        line.strokeWidth = s * 0.05f
        fun x(v: Float) = cx + (v - 0.5f) * s
        fun y(v: Float) = cy + (v - 0.5f) * s
        when (glyph) {
            Glyph.SEARCH -> {
                c.drawCircle(x(0.465f), y(0.465f), s * 0.135f, line)
                c.drawLine(x(0.565f), y(0.565f), x(0.675f), y(0.675f), line)
            }
            Glyph.DONE -> {
                path.rewind()
                path.moveTo(x(0.34f), y(0.52f)); path.lineTo(x(0.45f), y(0.63f)); path.lineTo(x(0.67f), y(0.38f))
                c.drawPath(path, line)
            }
            Glyph.ADD -> {
                c.drawLine(x(0.33f), y(0.5f), x(0.67f), y(0.5f), line)
                c.drawLine(x(0.5f), y(0.33f), x(0.5f), y(0.67f), line)
            }
            Glyph.SETTINGS -> {
                // Two sliders.
                c.drawLine(x(0.32f), y(0.41f), x(0.68f), y(0.41f), line)
                c.drawLine(x(0.32f), y(0.59f), x(0.68f), y(0.59f), line)
                c.drawCircle(x(0.58f), y(0.41f), s * 0.062f, dot)
                c.drawCircle(x(0.42f), y(0.59f), s * 0.062f, dot)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!shown) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inside = true
                // Glass gives under the finger: a quick squeeze, then a springy release.
                springScale(0.88f, 900f, 1f)
            }
            MotionEvent.ACTION_MOVE -> {
                val now = e.x >= -width * 0.3f && e.x <= width * 1.3f && e.y >= -height * 0.3f && e.y <= height * 1.3f
                if (now != inside) { inside = now; springScale(if (now) 0.88f else 1f, 700f, 0.8f) }
            }
            MotionEvent.ACTION_UP -> {
                springScale(1f, 520f, 0.55f)
                if (inside) { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
            }
            MotionEvent.ACTION_CANCEL -> springScale(1f, 520f, 0.8f)
        }
        return true
    }

    /** Pops in / shrinks away. Hidden buttons take no touches. */
    fun setShown(v: Boolean, animate: Boolean = true) {
        if (shown == v && (visibility == VISIBLE) == v) return
        shown = v
        L.workspace.invalidate()
        if (!animate) {
            cancelSprings()
            alpha = if (v) 1f else 0f
            scaleX = 1f; scaleY = 1f
            visibility = if (v) VISIBLE else INVISIBLE
            return
        }
        if (v) {
            if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f; scaleX = 0.6f; scaleY = 0.6f }
            springTo(DynamicAnimation.ALPHA, 1f, 600f, 1f)
            springScale(1f, 420f, 0.7f)
        } else if (visibility == VISIBLE) {
            springScale(0.6f, 700f, 1f)
            springTo(DynamicAnimation.ALPHA, 0f, 700f, 1f) { if (!shown) visibility = INVISIBLE }
        }
    }
}

/**
 * Edit (jiggle) mode buttons. In the rail modes they stack at the top of the rail, where the
 * iPhone Duo swaps its status items for "done" and "customise"; in portrait they sit in the
 * (hidden) status-bar strip, add/settings on the left and done on the right.
 */
class EditBar(private val L: MainActivity) {
    private val done = GlassButton(L, Glyph.DONE) { L.exitEditMode() }
    private val add = GlassButton(L, Glyph.ADD) { L.openWidgetPicker() }
    private val settings = GlassButton(L, Glyph.SETTINGS) { L.openSettings() }
    private val all = listOf(done, add, settings)

    fun attach(root: FrameLayout) {
        for (b in all) { b.setShown(false, animate = false); root.addView(b) }
    }

    fun layout(p: DeviceProfile) {
        val size = p.r(48f).roundToInt()
        fun place(b: GlassButton, cx: Float, cy: Float) {
            b.layoutParams = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (cx - size / 2f).roundToInt(); topMargin = (cy - size / 2f).roundToInt()
            }
        }
        if (p.dockVertical) {
            val first = p.r(53f)
            // Three buttons above the dock capsule; squeeze the pitch if the dock is close.
            val pitch = min(p.r(62f), (p.dockRect.top - p.r(8f) - size / 2f - first) / 2f).coerceAtLeast(size + p.r(4f))
            place(done, p.railCenterX, first)
            place(add, p.railCenterX, first + pitch)
            place(settings, p.railCenterX, first + pitch * 2)
        } else {
            val cy = p.r(46f)
            val edge = p.r(24f) + size / 2f
            place(add, p.insets.left + edge, cy)
            place(settings, p.insets.left + edge + p.r(60f), cy)
            place(done, p.width - p.insets.right - edge, cy)
        }
    }

    fun setShown(shown: Boolean) {
        for (b in all) b.setShown(shown)
    }

    /** The shown buttons, for the workspace's glass cut-outs. */
    val buttons: List<GlassButton> get() = all
}

/**
 * A blurred copy of the Home layer at one fixed radius, cross-faded with the sharp one behind
 * the Finder, an open folder and the long-press menu. The blur radius itself is never animated:
 * the system blur changes its internal resolution at certain radii, so a radius sweeping through
 * them makes the whole screen step visibly, and at full size it cost 20-35 ms a frame here. The
 * copy is drawn at a quarter of the size (a heavy blur loses nothing by it) and records
 * references to Home's children, so it follows whatever changes in them.
 */
@SuppressLint("ViewConstructor")
class HomeBlur(private val L: MainActivity, private val src: View) : View(L) {
    private val node = RenderNode("homeBlur")
    private var radius = -1f

    override fun onDraw(c: Canvas) {
        val p = L.profile ?: return
        if (!c.isHardwareAccelerated || width == 0 || height == 0) return
        val r = p.dp(26f) / DOWN
        if (radius != r) {
            radius = r
            node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
        }
        node.setPosition(0, 0, (width + DOWN - 1) / DOWN, (height + DOWN - 1) / DOWN)
        val rc = node.beginRecording()
        rc.scale(1f / DOWN, 1f / DOWN)
        // Records references to Home's children, so later changes in them show up here too.
        src.draw(rc)
        node.endRecording()
        c.save()
        c.scale(DOWN.toFloat(), DOWN.toFloat())
        c.drawRenderNode(node)
        c.restore()
    }

    private companion object { const val DOWN = 4 }
}
