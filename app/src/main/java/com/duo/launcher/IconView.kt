package com.duo.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.hypot

enum class Role { HOME, DOCK, FOLDER, FINDER }

fun t(zh: String, en: String) = if (Locale.getDefault().language == "zh") zh else en

/** An app or folder icon. Draws everything itself: one View, no child views, no layout passes. */
@SuppressLint("ViewConstructor")
class IconView(private val L: MainActivity, var item: HomeItem, var role: Role) : View(L) {

    var iconSize = 0
        set(v) { if (field != v) { field = v; maskPath = null; invalidate() } }
    var showLabel = true
    /** Set while the drag controller captures the icon bitmap: no label, no badge, no dimming. */
    var snapshotMode = false
    /** The long-press menu shows a lifted copy of the icon: draw only the label here. */
    var iconHidden = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var iconTop = 0f
    var labelSize = 0f
    var labelGap = 0f

    /** 0..1, grows a translucent rounded square behind the icon while a drop would make a folder. */
    private var highlight = 0f
    private var highlightAnim: ValueAnimator? = null
    private var pressAmount = 0f
    private var pressAnim: ValueAnimator? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // Medium weight like the iOS Home Screen labels; a soft shadow keeps them readable on
        // bright wallpapers.
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setShadowLayer(5f, 0f, 1f, 0x59000000)
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5EFFFFFF }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    // Remove badge: a light glass bead with a dark minus, sitting on the icon's corner.
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEBE4E4E8.toInt() }
    private val badgeRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val badgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C3C43.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private var maskPath: Path? = null
    private val tmpRect = RectF()
    private val tmpSrc = Rect()

    private var rawLabel: String? = null
    private var cachedLabel: String? = null
    private var cachedLabelWidth = 0

    val appInfo: AppInfo? get() = (item as? AppItem)?.let { L.repo[it.key] }

    fun label(): String = when (val i = item) {
        is AppItem -> L.repo[i.key]?.label ?: ""
        is FolderItem -> i.name
        else -> ""
    }

    /** Icon rect in this view's coordinates. */
    fun iconRect(out: RectF): RectF {
        val l = (width - iconSize) / 2f
        val tp = if (showLabel) iconTop else (height - iconSize) / 2f
        out.set(l, tp, l + iconSize, tp + iconSize)
        return out
    }

    override fun onDraw(c: Canvas) {
        if (iconSize <= 0) return
        val r = iconRect(tmpRect)
        if (maskPath == null) maskPath = Squircle.path(iconSize.toFloat())
        if (iconHidden && !snapshotMode) { drawLabel(c, r); return }

        if (highlight > 0f && !snapshotMode) {
            val grow = iconSize * 0.14f * highlight
            c.save()
            c.translate(r.left - grow, r.top - grow)
            val s = (iconSize + grow * 2) / iconSize
            c.scale(s, s)
            placeholderPaint.alpha = (90 * highlight).toInt()
            c.drawPath(maskPath!!, placeholderPaint)
            c.restore()
        }

        bmpPaint.colorFilter = if (pressAmount > 0.01f && !snapshotMode)
            PorterDuffColorFilter(Color.argb((pressAmount * 90).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP) else null

        when (val i = item) {
            is AppItem -> drawApp(c, L.repo[i.key]?.icon, r)
            is FolderItem -> drawFolder(c, i, r)
            else -> {}
        }

        if (snapshotMode) return
        drawLabel(c, r)

        if (L.editMode && role != Role.FINDER && role != Role.FOLDER) drawMinus(c, r)
        if (L.editMode && role == Role.FOLDER) drawMinus(c, r)
    }

    private fun drawLabel(c: Canvas, r: RectF) {
        if (!showLabel || labelSize <= 0) return
        labelPaint.textSize = labelSize
        val text = label()
        if (text != rawLabel || cachedLabelWidth != width) {
            rawLabel = text
            cachedLabel = TextUtils.ellipsize(text, labelPaint, width * 0.98f, TextUtils.TruncateAt.END).toString()
            cachedLabelWidth = width
        }
        c.drawText(cachedLabel!!, width / 2f, r.bottom + labelGap + labelSize, labelPaint)
    }

    private fun drawApp(c: Canvas, bmp: Bitmap?, r: RectF) {
        if (bmp == null) {
            c.save(); c.translate(r.left, r.top); c.drawPath(maskPath!!, placeholderPaint); c.restore()
            return
        }
        tmpSrc.set(0, 0, bmp.width, bmp.height)
        c.drawBitmap(bmp, tmpSrc, r, bmpPaint)
    }

    private fun drawFolder(c: Canvas, f: FolderItem, r: RectF) {
        c.save()
        c.translate(r.left, r.top)
        folderPaint.colorFilter = bmpPaint.colorFilter
        c.drawPath(maskPath!!, folderPaint)
        c.restore()
        // 3x3 miniature grid like iOS.
        val pad = iconSize * 0.12f
        val gap = iconSize * 0.06f
        val cell = (iconSize - pad * 2 - gap * 2) / 3f
        for (k in 0 until minOf(9, f.apps.size)) {
            val bmp = L.repo[f.apps[k]]?.icon ?: continue
            val x = r.left + pad + (k % 3) * (cell + gap)
            val y = r.top + pad + (k / 3) * (cell + gap)
            tmpSrc.set(0, 0, bmp.width, bmp.height)
            tmpRect2.set(x, y, x + cell, y + cell)
            c.drawBitmap(bmp, tmpSrc, tmpRect2, bmpPaint)
        }
    }
    private val tmpRect2 = RectF()

    private fun badgeCenterAndRadius(r: RectF): Triple<Float, Float, Float> {
        val br = iconSize * 0.185f
        return Triple(r.left + br * 0.3f, r.top + br * 0.3f, br)
    }

    private fun drawMinus(c: Canvas, r: RectF) {
        if (item !is AppItem && item !is FolderItem) return
        val (cx, cy, br) = badgeCenterAndRadius(r)
        c.drawCircle(cx, cy, br, badgePaint)
        badgeRim.strokeWidth = br * 0.09f
        c.drawCircle(cx, cy, br - badgeRim.strokeWidth / 2f, badgeRim)
        badgeLine.strokeWidth = br * 0.2f
        c.drawLine(cx - br * 0.42f, cy, cx + br * 0.42f, cy, badgeLine)
    }

    private fun hitsBadge(x: Float, y: Float): Boolean {
        if (!L.editMode || role == Role.FINDER) return false
        val (cx, cy, br) = badgeCenterAndRadius(iconRect(tmpRect))
        return hypot(x - cx, y - cy) < br * 1.6f
    }

    fun setHighlighted(on: Boolean) {
        val target = if (on) 1f else 0f
        if (highlight == target && highlightAnim?.isRunning != true) return
        highlightAnim?.cancel()
        highlightAnim = ValueAnimator.ofFloat(highlight, target).apply {
            duration = 180
            addUpdateListener { highlight = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun setPressedAnimated(on: Boolean) {
        pressAnim?.cancel()
        val target = if (on) 1f else 0f
        if (pressAmount == target) return
        pressAnim = ValueAnimator.ofFloat(pressAmount, target).apply {
            duration = if (on) 90 else 260
            addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var tracking = false
    private var onBadge = false
    /** The long-press menu opened for this press: moving the finger then does nothing (no drag). */
    private var menuOpened = false
    private val slop = ViewConfiguration.get(L).scaledTouchSlop

    private val longPressRunnable = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!L.editMode) {
            menuOpened = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            L.showContextMenu(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (L.drag.dragging) return false
                downX = e.x; downY = e.y
                longPressed = false
                menuOpened = false
                tracking = true
                // The long-press menu's shortcuts, fetched while the press is still being held.
                appInfo?.let { L.repo.prefetchShortcuts(it) }
                onBadge = hitsBadge(e.x, e.y)
                if (!onBadge) setPressedAnimated(true)
                val delay = if (L.editMode) 170L else ViewConfiguration.getLongPressTimeout().toLong().coerceAtMost(420L)
                postDelayed(longPressRunnable, delay)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val d = hypot(e.x - downX, e.y - downY)
                if (menuOpened) {
                    // Menu is up: the finger may wander, nothing gets dragged.
                    if (d > slop) setPressedAnimated(false)
                } else if (longPressed && d > slop && !onBadge) {
                    tracking = false
                    setPressedAnimated(false)
                    L.drag.startDrag(this, e.rawX, e.rawY, e.rawX - (e.x - downX), e.rawY - (e.y - downY))
                } else if (!longPressed && d > slop) {
                    removeCallbacks(longPressRunnable)
                    tracking = false
                    setPressedAnimated(false)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                setPressedAnimated(false)
                if (tracking) {
                    if (onBadge && hitsBadge(e.x, e.y)) L.onRemoveBadge(this)
                    else if (!longPressed) L.onIconClick(this)
                }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                setPressedAnimated(false)
                tracking = false
            }
        }
        return true
    }

    /** Clears transient state, e.g. when returning from an app mid-press. */
    fun resetTouchState() {
        removeCallbacks(longPressRunnable)
        tracking = false
        pressAnim?.cancel(); pressAmount = 0f
        invalidate()
    }
}
