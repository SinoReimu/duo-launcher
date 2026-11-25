package com.duo.launcher

import androidx.dynamicanimation.animation.DynamicAnimation
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Horizontal pager of Home Screen pages. In inner landscape two pages are visible side by side
 * and a swipe advances by ONE page (the right page slides over to the left), as requested.
 */
@SuppressLint("ViewConstructor")
class Workspace(private val L: MainActivity) : ViewGroup(L) {
    val pages = ArrayList<PageView>()

    private val scroller = SpringValue(0f, stiffness = 380f, damping = 0.9f) { applyPos(it) }
    val pos: Float get() = scroller.value
    val currentPage: Int get() = pos.roundToInt()

    private val slop = ViewConfiguration.get(L).scaledTouchSlop
    private val minFling = ViewConfiguration.get(L).scaledMinimumFlingVelocity
    private var velocity: VelocityTracker? = null

    private enum class Mode { NONE, SCROLL, PULL_DOWN, PULL_UP }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startPos = 0f
    private var startPage = 0
    private var notifTriggered = false
    private var longPressFired = false

    init { clipChildren = true }

    fun bind(model: List<List<HomeItem>>) {
        while (pages.size < model.size) PageView(L).also { pages += it; addView(it) }
        while (pages.size > model.size) removeView(pages.removeAt(pages.size - 1))
        for (i in model.indices) pages[i].setItems(model[i])
        if (pos > maxPos()) scroller.snap(maxPos().toFloat())
        applyPos(pos)
    }

    fun maxPos(): Int = max(0, pages.size - (L.profile?.visiblePages ?: 1))

    override fun onMeasure(w: Int, h: Int) {
        val p = L.profile
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        if (p == null) return
        val ws = MeasureSpec.makeMeasureSpec(p.pageW.roundToInt(), MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(p.pageH.roundToInt(), MeasureSpec.EXACTLY)
        pages.forEach { it.measure(ws, hs) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val p = L.profile ?: return
        // No clip on the left: pages leave fully past the screen edge. The view itself ends at the
        // dock's centre line (workspaceClipRight), so incoming pages appear from under the dock.
        clipBounds = null
        for ((i, pg) in pages.withIndex()) {
            val x = (p.pagesRect.left + i * p.pageStride).roundToInt()
            val y = p.pagesRect.top.roundToInt()
            pg.layout(x, y, x + p.pageW.roundToInt(), y + p.pageH.roundToInt())
        }
        applyPos(pos)
    }

    private fun applyPos(v: Float) {
        val p = L.profile ?: return
        val tx = -v * p.pageStride
        // In landscape, a page beyond the visible pair fades in while it slides out from under
        // the dock, so nothing peeks out at rest and there is no hard clip line.
        val lastSlotLeft = p.pagesRect.left + (p.visiblePages - 1) * p.pageStride
        for (pg in pages) {
            pg.translationX = tx
            val left = pg.left + tx
            val a = if (p.landscape && left > lastSlotLeft + 0.5f)
                (1f - (left - lastSlotLeft) / p.pageStride).coerceIn(0f, 1f) else 1f
            if (pg.alpha != a) pg.alpha = a
            val vis = a > 0f && left < width && left + pg.width > 0
            val want = if (vis) View.VISIBLE else View.INVISIBLE
            if (pg.visibility != want) pg.visibility = want
        }
        L.onWorkspaceScrolled(v)
    }

    fun snapTo(target: Int, velocityPages: Float = 0f, animate: Boolean = true) {
        val t = target.coerceIn(0, maxPos()).toFloat()
        if (!animate) { scroller.snap(t); return }
        scroller.animateTo(t, velocityPages)
    }

    /** Snaps to the nearest page immediately unless a settle is already running. */
    fun settleNow() {
        if (scroller.running || mode == Mode.SCROLL) {
            mode = Mode.NONE
            scroller.cancel()
        }
        val r = pos.roundToInt().coerceIn(0, maxPos()).toFloat()
        if (pos != r) scroller.snap(r)
    }

    /** After any touch ends, never leave the pager between pages. */
    private fun ensureSettled() {
        if (!scroller.running && abs(pos - pos.roundToInt()) > 0.001f) snapTo(pos.roundToInt())
    }

    /** Index of the page under an x coordinate of this view, or -1. */
    fun pageIndexAt(x: Float): Int {
        val p = L.profile ?: return -1
        val i = ((x - p.pagesRect.left) / p.pageStride + pos).toInt()
        return if (x < p.pagesRect.left - p.dp(8f)) -1 else i.coerceIn(0, pages.size - 1)
    }

    // ---------------------------------------------------------------- touch

    private val longPress = Runnable {
        if (mode == Mode.NONE && !L.editMode) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            L.enterEditMode()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (L.drag.dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(e)
                // Catch a settling page instead of tapping through it.
                if (scroller.running && abs(pos - pos.roundToInt()) > 0.02f) {
                    scroller.cancel(); startPos = pos; startPage = pos.roundToInt(); mode = Mode.SCROLL
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (decide(e)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return false
    }

    private fun begin(e: MotionEvent) {
        downX = e.x; downY = e.y
        startPos = pos
        startPage = pos.roundToInt()
        mode = Mode.NONE
        notifTriggered = false
        longPressFired = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    private fun decide(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy)) {
            mode = Mode.SCROLL
            scroller.cancel()
            startPos = pos
            downX = e.x
        } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.1f && !L.editMode) {
            mode = if (dy > 0) Mode.PULL_DOWN else Mode.PULL_UP
            downY = e.y
            // Swipe down: Finder or notifications (setting). Swipe up: always Finder, so search
            // stays reachable when swipe-down is set to notifications.
            if ((mode == Mode.PULL_DOWN && !L.model.swipeDownNotifications) || mode == Mode.PULL_UP) L.finder.beginPull()
        } else return false
        removeCallbacks(longPress)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (L.drag.dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A DOWN that caught a settling page was already set up as a scroll.
                if (mode != Mode.SCROLL) {
                    begin(e)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong().coerceAtMost(450L))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (mode == Mode.NONE && !decide(e)) return true
                when (mode) {
                    Mode.SCROLL -> dragScroll(e.x)
                    Mode.PULL_DOWN -> {
                        val dy = e.y - downY
                        if (L.model.swipeDownNotifications) {
                            if (!notifTriggered && dy > slop * 2) { notifTriggered = true; L.expandNotifications() }
                        } else L.finder.pull(dy)
                    }
                    Mode.PULL_UP -> L.finder.pull(downY - e.y)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                when (mode) {
                    Mode.SCROLL -> release(vx)
                    Mode.PULL_DOWN -> if (!L.model.swipeDownNotifications) L.finder.endPull(vy)
                    Mode.PULL_UP -> L.finder.endPull(-vy)
                    Mode.NONE -> if (L.editMode) { if (!longPressFired) L.exitEditMode() } else if (!longPressFired) onEmptyTap(e)
                    else -> {}
                }
                mode = Mode.NONE
                recycle()
                ensureSettled()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == Mode.SCROLL) release(0f)
                if ((mode == Mode.PULL_DOWN && !L.model.swipeDownNotifications) || mode == Mode.PULL_UP) L.finder.endPull(0f)
                mode = Mode.NONE
                recycle()
                ensureSettled()
            }
        }
        return true
    }

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private fun onEmptyTap(e: MotionEvent) {
        val near = kotlin.math.hypot(e.x - lastTapX, e.y - lastTapY) < slop * 4
        if (e.eventTime - lastTapTime < ViewConfiguration.getDoubleTapTimeout() && near) {
            lastTapTime = 0L
            L.onDoubleTapEmpty()
        } else {
            lastTapTime = e.eventTime; lastTapX = e.x; lastTapY = e.y
        }
    }

    private fun dragScroll(x: Float) {
        val p = L.profile ?: return
        var v = startPos - (x - downX) / p.pageStride
        val maxP = maxPos().toFloat()
        // Rubber band past the first/last page.
        if (v < 0f) v *= 0.3f
        if (v > maxP) v = maxP + (v - maxP) * 0.3f
        scroller.snap(v)
    }

    private fun release(vx: Float) {
        val p = L.profile ?: return
        val vPages = -vx / p.pageStride
        val target = if (abs(vx) > minFling * 3) {
            // One page per swipe, like iOS.
            startPage + sign(vPages).toInt()
        } else pos.roundToInt()
        val t = target.coerceIn(startPage - 1, startPage + 1)
        snapTo(t, vPages.coerceIn(-12f, 12f))
    }

    private fun recycle() { velocity?.recycle(); velocity = null }
}

/** Page dots. In landscape, the two visible pages are both lit. */
@SuppressLint("ViewConstructor")
class PageIndicator(private val L: MainActivity) : View(L) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    var count = 1
        set(v) { if (field != v) { field = v; invalidate() } }
    var pos = 0f
        set(v) { field = v; invalidate() }

    private var held = false
    private var shown = true
    private val hideRunnable = Runnable { setShown(false) }

    private fun setShown(v: Boolean) {
        if (shown == v) return
        shown = v
        // Quick to appear, slow to leave.
        if (v) springTo(DynamicAnimation.ALPHA, 1f, 700f, 1f) else springTo(DynamicAnimation.ALPHA, 0f, 320f, 1f)
    }

    /**
     * Interaction started (finger down, page change, coming back Home): show the dots and keep
     * them while it lasts.
     */
    fun hold() {
        held = true
        removeCallbacks(hideRunnable)
        setShown(true)
    }

    /** Interaction ended: the dots fade away after 1.5 s without further use (never in edit mode). */
    fun release() {
        held = false
        removeCallbacks(hideRunnable)
        if (!L.editMode) postDelayed(hideRunnable, 1500)
    }

    /** One-off nudge, e.g. the page changed without a touch. */
    fun poke() {
        if (held) return
        hold(); release()
    }

    override fun onDraw(c: Canvas) {
        val p = L.profile ?: return
        if (count <= 1 && !L.editMode) return
        val d = p.dp(7f)
        val gap = p.dp(9f)
        val total = count * d + (count - 1) * gap
        var x = (width - total) / 2f + d / 2
        val cy = height / 2f
        val vis = p.visiblePages
        for (i in 0 until count) {
            // How much of dot i's page lies inside the visible window [pos, pos+vis).
            val overlap = max(0f, min(i + 1f, pos + vis) - max(i.toFloat(), pos))
            paint.alpha = (255 * lerp(0.32f, 1f, overlap.coerceIn(0f, 1f))).toInt()
            c.drawCircle(x, cy, d / 2, paint)
            x += d + gap
        }
    }
}
