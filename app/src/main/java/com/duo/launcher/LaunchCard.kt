package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * The "app grows out of its icon / shrinks back into it" motion, drawn by the launcher.
 *
 * One UI does not let third-party launchers animate app windows, so the launcher draws a card
 * that morphs between the icon and the full screen:
 *  - the card is painted in the colour the app's own window starts with (its splash/window
 *    background), so the system's cross-fade to the real app lands on the same colour;
 *  - the icon image itself fills the card and cross-fades into that colour in the first part of
 *    the motion (no separately scaled-up icon);
 *  - one spring drives both directions, so a launch can be reversed mid-flight and vice versa.
 */
@SuppressLint("ViewConstructor")
class LaunchCard(private val L: MainActivity) : View(L) {
    private val from = RectF()
    private val rect = RectF()
    private val dst = RectF()
    private val path = Path()
    private var bmp: Bitmap? = null
    private var icon: IconView? = null
    /** No icon to land on: shrink toward the centre and fade instead. */
    private var fadeOut = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val progress = SpringValue(0f, stiffness = 520f, damping = 1f) { invalidate() }
    private val loc = IntArray(2)
    private var retracting = false

    val active get() = visibility == VISIBLE

    init {
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The icon's on-screen rect; false if it is detached or not actually on screen. */
    private fun iconRectOf(v: IconView?, out: RectF): Boolean {
        if (v == null || !v.isAttachedToWindow || !v.isShown) return false
        v.getLocationInWindow(loc)
        v.iconRect(out)
        out.offset(loc[0].toFloat(), loc[1].toFloat())
        val w = (rootView?.width ?: 0).toFloat()
        val h = (rootView?.height ?: 0).toFloat()
        return out.centerX() in 0f..w && out.centerY() in 0f..h
    }

    private fun centreRect(out: RectF) {
        val s = (L.profile?.iconSize ?: 150).toFloat()
        val cx = L.root.width / 2f; val cy = L.root.height / 2f
        out.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    }

    private var iconColor = 0
    /** The rect the card has at p = 1: the screen when opening, One UI's end pose when closing. */
    private val big = RectF()
    private var bigRadius = 0f

    private fun bind(v: IconView?, image: Bitmap?, iconBg: Int) {
        if (icon !== v) icon?.alpha = 1f
        icon = v
        bmp = image
        iconColor = iconBg
        fadeOut = !iconRectOf(v, from)
        if (fadeOut) centreRect(from)
        v?.alpha = 0f
        alpha = 1f
        visibility = VISIBLE
    }

    private fun screenCornerRadius(): Float {
        val rc = rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
        return rc?.radius?.toFloat() ?: (L.profile?.dp(28f) ?: 70f)
    }

    /** Icon -> full screen, in the icon's own background colour. */
    fun launch(v: IconView, image: Bitmap?, iconBg: Int) {
        finish()
        bind(v, image, iconBg)
        fromSystem = false
        // This view may never have been laid out yet (it is GONE between launches): use the root.
        big.set(0f, 0f, L.root.width.toFloat(), L.root.height.toFloat())
        bigRadius = screenCornerRadius()
        retracting = false
        progress.anim.spring.stiffness = 700f
        progress.anim.spring.dampingRatio = 1f
        progress.snap(0f)
        progress.animateTo(1f)
    }

    /**
     * Pose for coming back Home: a small card where One UI's own close gesture leaves the app
     * window (about a third of the screen, just above centre), about to fly into [target] (or
     * fade at the centre if that icon isn't on screen). Starting there instead of full screen
     * avoids replaying a shrink the system has already shown.
     */
    fun prepareReturn(target: IconView?, image: Bitmap?, iconBg: Int) {
        if (retracting) return
        progress.cancel()
        bind(target, image, iconBg)
        val w = L.root.width.toFloat(); val h = L.root.height.toFloat()
        val cw = w * 0.3f; val ch = h * 0.3f
        val cx = w / 2f; val cy = h * 0.47f
        big.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2)
        bigRadius = L.profile?.dp(22f) ?: 55f
        fromSystem = true
        progress.snap(1f)
    }

    /** Return after One UI's own shrink: fade in while its window fades out (no hard swap). */
    private var fromSystem = false

    fun retract() {
        if (!active || retracting) return
        retracting = true
        if (!fadeOut && !iconRectOf(icon, from)) { fadeOut = true; centreRect(from) }
        progress.anim.spring.stiffness = if (fadeOut) 380f else 260f
        progress.anim.spring.dampingRatio = 0.9f
        progress.animateTo(0f) { finish() }
    }

    fun finish() {
        retracting = false
        fromSystem = false
        progress.cancel()
        visibility = GONE
        icon?.alpha = 1f
        icon = null
        bmp = null
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    override fun onDraw(c: Canvas) {
        val p = progress.value
        val pc = p.coerceIn(0f, 1f)
        if (retracting && !fadeOut) {
            // Track the icon live (Home is still zooming back), so the landing is exact.
            iconRectOf(icon, from)
            // Hand over to the real icon as soon as we are on it: no waiting for the spring tail.
            if (p < 0.015f) { post { if (retracting) finish() }; return }
        }
        rect.set(
            lerp(from.left, big.left, p), lerp(from.top, big.top, p),
            lerp(from.right, big.right, p), lerp(from.bottom, big.bottom, p),
        )
        val radius = lerp(from.width() * 0.225f, bigRadius, pc)
        path.rewind()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        var cardAlpha = if (fadeOut) smooth(0f, 0.6f, pc) else 1f
        if (fromSystem) cardAlpha *= smooth(1f, 0.72f, pc)
        if (cardAlpha <= 0f) return

        // One surface in one colour: the icon's background, so it reads as the icon itself
        // growing into the window (and shrinking back).
        bg.color = iconColor
        bg.alpha = (255 * cardAlpha).toInt()
        c.drawPath(path, bg)

        // The logo stays centred, grows a little, and is gone just before full size so the
        // system's cross-fade goes from a clean colour field to the app.
        val b = bmp ?: return
        val logoAlpha = cardAlpha * (1f - smooth(0.55f, 0.9f, pc))
        if (logoAlpha <= 0.004f) return
        val s = from.width() * lerp(1f, 1.4f, smooth(0f, 0.6f, pc))
        dst.set(rect.centerX() - s / 2, rect.centerY() - s / 2, rect.centerX() + s / 2, rect.centerY() + s / 2)
        bmpPaint.alpha = (255 * logoAlpha).toInt()
        c.drawBitmap(b, null, dst, bmpPaint)
    }
}
