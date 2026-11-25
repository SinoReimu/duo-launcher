package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.min
import kotlin.math.roundToInt

private class LayoutState(var parent: ViewGroup?, var l: Int, var t: Int) {
    /** Window position the view was drawn at just before it changed container, or NaN. */
    var fromX = Float.NaN
    var fromY = Float.NaN
}

private val tmpLoc = IntArray(2)

/**
 * Makes a view that is about to join a container move in from a window position instead of
 * appearing in its slot, e.g. a new folder taking over from the icon it was made from.
 */
fun seedFlyFrom(v: View, fromX: Float, fromY: Float) {
    val st = (v.getTag(R.id.layout_state) as? LayoutState)
        ?: LayoutState(null, 0, 0).also { v.setTag(R.id.layout_state, it) }
    st.fromX = fromX; st.fromY = fromY
}

/** Reflow feel: soft enough to read as motion, with a hint of overshoot like iOS. */
private const val REFLOW_STIFFNESS = 165f
private const val REFLOW_DAMPING = 0.86f

/**
 * Base for every grid of items (pages, dock, open folder). Children are laid out from
 * [computeFrames]; when an item moves, it springs from where it was drawn to its new slot,
 * which makes reflow during drag-and-drop interruptible and smooth.
 */
@SuppressLint("ViewConstructor")
abstract class ItemContainer(protected val L: MainActivity, val role: Role) : ViewGroup(L) {
    var items: List<HomeItem> = emptyList()
        private set
    protected var frames: Array<Rect?> = emptyArray()
    var animateReflow = true

    init { clipChildren = false; clipToPadding = false }

    fun setItems(list: List<HomeItem>) {
        items = ArrayList(list)
        val keep = HashSet<View>()
        for (it in items) {
            if (it is PlaceholderItem) continue
            val v = L.viewFor(it, role)
            keep += v
            if (v.parent !== this) {
                val st = v.getTag(R.id.layout_state) as? LayoutState
                // Remember where it is on screen so it can fly to its new slot (e.g. page -> dock,
                // or the last icon pushed onto the next page) instead of popping.
                if (st != null && v.isAttachedToWindow && v.visibility == View.VISIBLE && v.alpha > 0.5f) {
                    v.getLocationInWindow(tmpLoc)
                    st.fromX = tmpLoc[0].toFloat(); st.fromY = tmpLoc[1].toFloat()
                } else if (v.isAttachedToWindow) st?.let { it.fromX = Float.NaN }
                // A detached view keeps a start point given by seedFlyFrom.
                (v.parent as? ViewGroup)?.removeView(v)
                st?.parent = null
                v.spring(DynamicAnimation.TRANSLATION_X).cancel()
                v.spring(DynamicAnimation.TRANSLATION_Y).cancel()
                v.translationX = 0f; v.translationY = 0f
                addView(v)
            }
            if (v is IconView) { v.role = role; L.configureIcon(v, role) }
        }
        for (i in childCount - 1 downTo 0) if (getChildAt(i) !in keep) removeViewAt(i)
        requestLayout()
        invalidate()
    }

    abstract fun computeFrames(): Array<Rect?>

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        frames = computeFrames()
        for ((i, item) in items.withIndex()) {
            if (item is PlaceholderItem) continue
            val v = L.viewFor(item, role)
            if (v.parent !== this) continue
            val f = frames.getOrNull(i)
            if (f == null) {
                // Pushed off a full page while dragging: fade out in place; it lands on the next
                // page when the drag ends.
                if (v.visibility == View.VISIBLE && v.width > 0) {
                    v.springTo(DynamicAnimation.ALPHA, 0f, 700f, 1f)
                    v.springScale(0.6f, 700f, 1f)
                } else v.visibility = View.INVISIBLE
                continue
            }
            if (!L.drag.isHiddenForDrag(v)) {
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                if (v.alpha < 1f && v.getTag(R.id.layout_state) != null) {
                    v.springTo(DynamicAnimation.ALPHA, 1f, 700f, 1f)
                    v.springScale(1f, 700f, 0.8f)
                }
            }
            layoutChild(v, f)
        }
    }

    private fun layoutChild(v: View, f: Rect) {
        v.measure(
            MeasureSpec.makeMeasureSpec(f.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(f.height(), MeasureSpec.EXACTLY),
        )
        val st = v.getTag(R.id.layout_state) as? LayoutState
        val canAnimate = animateReflow && !L.suppressReflow
        if (st != null && st.parent === this && canAnimate && (st.l != f.left || st.t != f.top)) {
            val visX = st.l + v.translationX
            val visY = st.t + v.translationY
            v.layout(f.left, f.top, f.right, f.bottom)
            v.springFrom(DynamicAnimation.TRANSLATION_X, visX - f.left, 0f, REFLOW_STIFFNESS, REFLOW_DAMPING)
            v.springFrom(DynamicAnimation.TRANSLATION_Y, visY - f.top, 0f, REFLOW_STIFFNESS, REFLOW_DAMPING)
        } else if (st != null && st.parent == null && canAnimate && !st.fromX.isNaN() && isAttachedToWindow) {
            // Changed container: fly from the old on-screen spot if both ends are on screen.
            v.layout(f.left, f.top, f.right, f.bottom)
            getLocationInWindow(tmpLoc)
            val dx = st.fromX - (tmpLoc[0] + f.left)
            val dy = st.fromY - (tmpLoc[1] + f.top)
            val rw = rootView.width.toFloat()
            val onScreen = tmpLoc[0] + f.left in 0..rootView.width && st.fromX in 0f..rw
            if (onScreen && (dx != 0f || dy != 0f)) {
                v.springFrom(DynamicAnimation.TRANSLATION_X, dx, 0f, REFLOW_STIFFNESS, REFLOW_DAMPING)
                v.springFrom(DynamicAnimation.TRANSLATION_Y, dy, 0f, REFLOW_STIFFNESS, REFLOW_DAMPING)
            }
        } else {
            v.layout(f.left, f.top, f.right, f.bottom)
        }
        st?.fromX = Float.NaN
        if (st == null) v.setTag(R.id.layout_state, LayoutState(this, f.left, f.top))
        else { st.parent = this; st.l = f.left; st.t = f.top }
    }

    /** Index of the item whose frame contains (x, y) in this view's coordinates, or -1. */
    fun itemIndexAt(x: Float, y: Float): Int {
        for (i in frames.indices) {
            val f = frames[i] ?: continue
            if (items.getOrNull(i) is PlaceholderItem) continue
            if (f.contains(x.roundToInt(), y.roundToInt())) return i
        }
        return -1
    }

    fun frameOf(index: Int): Rect? = frames.getOrNull(index)
}

/** One Home Screen page: a COLS x ROWS grid with iOS-style flow packing. */
@SuppressLint("ViewConstructor")
class PageView(L: MainActivity) : ItemContainer(L, Role.HOME) {
    // Icons never overlap, so page-level alpha can be applied per child without an offscreen layer.
    override fun hasOverlappingRendering() = false

    var positions = IntArray(0)
        private set

    override fun computeFrames(): Array<Rect?> {
        val p = L.profile ?: return arrayOfNulls(items.size)
        positions = Packer.pack(items)
        return Array(items.size) { i ->
            val cell = positions[i]
            if (cell < 0) null else Rect().also { r ->
                val it = items[i]
                p.cellFrame(cell, it.spanX, it.spanY, r)
                if (it is WidgetItem) {
                    r.left += p.widgetInsetX; r.right -= p.widgetInsetX
                    r.top += p.widgetInsetTop; r.bottom -= p.widgetInsetBottom
                }
            }
        }
    }

    /** Cell index under a point in page coordinates. */
    fun cellAt(x: Float, y: Float): Int {
        val p = L.profile ?: return 0
        val c = (x / p.cellW).toInt().coerceIn(0, COLS - 1)
        val r = (y / p.cellH).toInt().coerceIn(0, ROWS - 1)
        return r * COLS + c
    }

    /** Insert position (ignoring placeholders) for a drop at the given cell, iOS reorder semantics. */
    fun insertIndexForCell(cell: Int): Int {
        val real = items.filter { it !is PlaceholderItem }
        val pos = Packer.pack(real)
        for (i in real.indices) {
            val pc = pos[i]
            if (pc < 0) return i
            val it = real[i]
            val r0 = pc / COLS; val c0 = pc % COLS
            val r1 = cell / COLS; val c1 = cell % COLS
            // The hovered cell lies within this item, or before it in reading order.
            val inside = r1 in r0 until r0 + it.spanY && c1 in c0 until c0 + it.spanX
            if (inside || pc >= cell) return i
        }
        return real.size
    }
}

/** The dock: a glass panel with up to DOCK_MAX items, vertical in inner landscape. */
@SuppressLint("ViewConstructor")
class DockView(L: MainActivity) : ItemContainer(L, Role.DOCK) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFFFFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x38FFFFFF
    }
    private val rect = RectF()

    init { setWillNotDraw(false) }

    val vertical get() = L.profile?.dockVertical == true

    override fun onDraw(c: Canvas) {
        val p = L.profile ?: return
        stroke.strokeWidth = p.dp(1f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = min(p.dp(30f), min(width, height) * 0.42f)
        c.drawRoundRect(rect, r, r, bg)
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        c.drawRoundRect(rect, r, r, stroke)
    }

    fun slotLength(): Float {
        val len = if (vertical) height else width
        return len / DOCK_MAX.toFloat()
    }

    /** Position of slot i (of n) along the dock axis: start and length. */
    private fun slot(i: Int, n: Int): Pair<Float, Float> {
        val p = L.profile
        if (vertical && p != null) {
            // Vertical dock: fixed icon pitch with the panel's inner padding, centred.
            val pitch = p.dockIconSize + p.dockGap
            val total = n * p.dockIconSize + (n - 1).coerceAtLeast(0) * p.dockGap
            val start = (height - total) / 2f
            return (start + i * pitch - p.dockGap / 2f) to pitch
        }
        val s = slotLength()
        val len = if (vertical) height else width
        return ((len - s * n) / 2f + i * s) to s
    }

    override fun computeFrames(): Array<Rect?> {
        val n = items.size
        return Array(n) { i ->
            val (a0, l) = slot(i, n)
            val a = a0.roundToInt(); val b = (a0 + l).roundToInt()
            if (vertical) Rect(0, a, width, b) else Rect(a, 0, b, height)
        }
    }

    /** Insert index for a point along the dock axis given `count` real items. */
    fun insertIndexAt(x: Float, y: Float, count: Int): Int {
        val pos = if (vertical) y else x
        val n = count + 1
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val (a, l) = slot(i, n)
            val d = kotlin.math.abs(pos - (a + l / 2f))
            if (d < bestD) { bestD = d; best = i }
        }
        return best.coerceIn(0, count)
    }
}

/** Grid inside an open folder. */
@SuppressLint("ViewConstructor")
class FolderGrid(L: MainActivity) : ItemContainer(L, Role.FOLDER) {
    var cols = 3
    var cellW = 0
    var cellH = 0

    override fun computeFrames(): Array<Rect?> = Array(items.size) { i ->
        val c = i % cols; val r = i / cols
        Rect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH)
    }

    fun insertIndexAt(x: Float, y: Float, count: Int): Int {
        if (cellW == 0 || cellH == 0) return count
        val c = (x / cellW).toInt().coerceIn(0, cols - 1)
        val r = (y / cellH).toInt().coerceAtLeast(0)
        return (r * cols + c).coerceIn(0, count)
    }
}
