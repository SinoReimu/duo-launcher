package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Horizontal pager of Home Screen pages. In inner landscape two pages are visible side by side
 * and a swipe advances by ONE page (the right page slides over to the left), as on the iPhone Duo.
 */
@SuppressLint("ViewConstructor")
class Workspace(private val L: MainActivity) : ViewGroup(L) {
    val pages = ArrayList<PageView>()

    // Page settle as measured on the Duo: a critically damped spring with a natural frequency of
    // about 20 rad/s (settles in ~0.3 s, no overshoot), carrying the finger's velocity over.
    private val scroller = SpringValue(0f, stiffness = 400f, damping = 1f) { applyPos(it) }
    val pos: Float get() = scroller.value
    val currentPage: Int get() = pos.roundToInt()

    private val slop = ViewConfiguration.get(L).scaledTouchSlop
    private val minFling = ViewConfiguration.get(L).scaledMinimumFlingVelocity
    private var velocity: VelocityTracker? = null

    private enum class Mode { NONE, SCROLL, PULL_DOWN, PULL_UP, SYSTEM }
    /** The touch went down in the strip along the bottom where the system's own gestures start. */
    private var fromEdge = false
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
        // Every page is laid out in the first slot and moved by translation only (a RenderNode
        // property), so scrolling never re-lays anything out.
        val x = p.pagesRect.left.roundToInt()
        val y = p.pagesRect.top.roundToInt()
        for (pg in pages) pg.layout(x, y, x + p.pageW.roundToInt(), y + p.pageH.roundToInt())
        applyPos(pos)
        L.invalidateGlass()
    }

    /**
     * Offset of a page that is [d] pages after the scroll position. Visible slots are one stride
     * apart; the page after the last slot waits further out ([DeviceProfile.pageStrideIn]), past
     * the screen edge, and slides in whole from behind the dock, at full opacity, no fade needed.
     * The page before the first slot likewise waits fully off the left edge
     * ([DeviceProfile.pageStrideOut]), so none of its labels peek in.
     */
    private fun slotOffset(p: DeviceProfile, d: Float): Float {
        val last = p.visiblePages - 1
        return when {
            d < 0 -> d * p.pageStrideOut
            d <= last -> d * p.pageStride
            else -> last * p.pageStride + (d - last) * p.pageStrideIn
        }
    }

    private val holes = Path()

    /**
     * In the rail modes the glass over the pages (dock capsule, round buttons) is cut out here
     * and filled in by the glass itself with a blurred copy, see [GlassBackdrop].
     */
    override fun dispatchDraw(c: Canvas) {
        if (L.profile?.dockVertical == true) {
            holes.rewind()
            L.addGlassHoles(holes, this)
            if (!holes.isEmpty) {
                c.save()
                c.clipOutPath(holes)
                super.dispatchDraw(c)
                c.restore()
                return
            }
        }
        super.dispatchDraw(c)
    }

    /** Draws the visible pages, uncut, for a [GlassBackdrop]. */
    fun drawPagesInto(c: Canvas) {
        for (pg in pages) if (pg.visibility == View.VISIBLE && pg.parent === this) drawChild(c, pg, drawingTime)
    }

    private fun applyPos(v: Float) {
        val p = L.profile ?: return
        var changed = false
        for ((i, pg) in pages.withIndex()) {
            val tx = slotOffset(p, i - v)
            val left = pg.left + tx
            val vis = left < width && left + pg.width > 0
            pg.translationX = tx
            val want = if (vis) View.VISIBLE else View.INVISIBLE
            if (pg.visibility != want) { pg.visibility = want; changed = true }
        }
        // The glass backdrops list the visible pages.
        if (changed) L.invalidateGlass()
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

    /**
     * Index of the page under an x coordinate of this view, or -1. Only pages in a visible slot
     * count: the space between the right page and the dock belongs to the right page.
     */
    fun pageIndexAt(x: Float): Int {
        val p = L.profile ?: return -1
        if (x < p.pagesRect.left - p.dp(8f) || pages.isEmpty()) return -1
        val first = currentPage.coerceIn(0, pages.size - 1)
        val last = (first + p.visiblePages - 1).coerceAtMost(pages.size - 1)
        val i = first + ((x - p.pagesRect.left) / p.pageStride).toInt()
        return i.coerceIn(first, last)
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
        fromEdge = inSystemGestureStrip(e)
        startPos = pos
        startPage = pos.roundToInt()
        mode = Mode.NONE
        notifTriggered = false
        longPressFired = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    /**
     * A swipe from the bottom edge is the system's: up for Home (which brings page 1 back, see
     * MainActivity.onNewIntent), up and hold for the recent apps, sideways for the last app. Home
     * still gets the touch until the system claims it, and must not answer it with a gesture of
     * its own (the search pull, a page turn) that then has to be undone.
     */
    private fun inSystemGestureStrip(e: MotionEvent): Boolean {
        val insets = rootWindowInsets ?: return false
        val strip = maxOf(
            insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom,
            insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
        )
        if (strip <= 0) return false
        val root = rootView ?: return false
        return e.rawY >= root.height - strip - (L.profile?.dp(6f) ?: 16f)
    }

    private fun decide(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (fromEdge) {
            if (abs(dx) <= slop && abs(dy) <= slop) return false
            mode = Mode.SYSTEM
        } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
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
                // Taken away (by a system gesture): never finish as "opened", however far it got.
                if ((mode == Mode.PULL_DOWN && !L.model.swipeDownNotifications) || mode == Mode.PULL_UP) L.finder.close()
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
        // Rubber band past the first/last page (the UIScrollView curve: resistance grows with
        // distance and the page can never be pulled further than one screen).
        if (v < 0f) v = -rubber(-v)
        if (v > maxP) v = maxP + rubber(v - maxP)
        scroller.snap(v)
    }

    /** Overscroll in pages for a finger travel of [d] pages past the edge. */
    private fun rubber(d: Float): Float {
        val p = L.profile ?: return d * 0.3f
        val dim = width / p.pageStride
        return (1f - 1f / (d * 0.55f / dim + 1f)) * dim
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

/** Page dots, always visible. In landscape, the two visible pages are both lit. In edit mode they sit in a glass pill. */
@SuppressLint("ViewConstructor")
class PageIndicator(private val L: MainActivity) : View(L) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x40FFFFFF }
    /** 0..1, how much of the edit-mode pill is shown. */
    private val pillAmount = SpringValue(0f, stiffness = 500f, damping = 1f) { invalidate() }

    fun setEditing(on: Boolean) = pillAmount.animateTo(if (on) 1f else 0f)
    var count = 1
        set(v) { if (field != v) { field = v; invalidate() } }
    var pos = 0f
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val p = L.profile ?: return
        if (count <= 1 && !L.editMode) return
        val d = p.r(8f)
        val gap = p.r(9.5f)
        val total = count * d + (count - 1) * gap
        var x = (width - total) / 2f + d / 2
        val cy = height / 2f
        val vis = p.visiblePages
        val k = pillAmount.value.coerceIn(0f, 1f)
        if (k > 0.01f) {
            val hw = total / 2f + p.r(14f)
            val hh = p.r(15f) * lerp(0.7f, 1f, k)
            pill.alpha = (0x2E * k).toInt()
            pillStroke.alpha = (0x40 * k).toInt()
            pillStroke.strokeWidth = p.dp(1f)
            c.drawRoundRect(width / 2f - hw, cy - hh, width / 2f + hw, cy + hh, hh, hh, pill)
            c.drawRoundRect(width / 2f - hw, cy - hh, width / 2f + hw, cy + hh, hh, hh, pillStroke)
        }
        for (i in 0 until count) {
            // How much of dot i's page lies inside the visible window [pos, pos+vis).
            val overlap = max(0f, min(i + 1f, pos + vis) - max(i.toFloat(), pos))
            paint.alpha = (255 * lerp(0.3f, 1f, overlap.coerceIn(0f, 1f))).toInt()
            c.drawCircle(x, cy, d / 2, paint)
            x += d + gap
        }
    }
}
