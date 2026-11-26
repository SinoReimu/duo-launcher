package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The icon's own gesture when its app opens or closes.
 *
 * One UI does not let a third-party launcher animate app windows: it ignores every
 * `ActivityOptions` animation, always fades the app's window in at 85% and grows it, and on the
 * way back shrinks it toward the middle of the screen and fades it. (Its swipe-Home intent even
 * carries AOSP's `gesture_nav_contract_v1`, but the answer is only logged, not acted on.) Cards
 * the size of the window that tried to take over from it were built and dropped: the launcher
 * cannot know where that window is when the finger lifts, and a lone tile crossing half the
 * screen looked wrong.
 *
 * So the launcher plays something small that is entirely its own. Opening, a copy of the icon
 * swells to about one and two thirds sizes, drifting toward the middle of the screen (a short hop
 * for an icon near it, more for one in a corner), and fades almost away as the window comes up.
 * Coming back, the same in reverse lands on the icon. If Home is back before the app has
 * covered it, the copy turns round where it is.
 */
@SuppressLint("ViewConstructor")
class LaunchCard(private val L: MainActivity) : View(L) {
    private val from = RectF()
    private val tmp = RectF()
    private val dst = RectF()
    private val toScreen = Matrix()
    private val fromScreen = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bmp: Bitmap? = null
    private var icon: IconView? = null
    /** A folder stands in for the app: the copy dissolves onto it, and the folder stays as it is. */
    private var ontoFolder = false
    private val progress = SpringValue(0f, stiffness = OUT_K, damping = 1f) { invalidate() }
    private var retracting = false
    /** Placed for a return and waiting for [retract]. */
    private var poised = false
    private var dx = 0f
    private var dy = 0f
    /** When the launch began (uptime): the copy does not wait for the app, see [onDraw]. */
    private var launchedAt = 0L

    val active get() = visibility == VISIBLE
    /** Prepared for, or on, the way back onto its icon. */
    val returning get() = active && (retracting || poised)
    /** Still out from a launch: the app has not covered Home (yet), and the icon is not back. */
    val opening get() = active && !retracting && !poised

    init {
        // INVISIBLE, never GONE: that would send a layout pass through all of Home, at the very
        // frame the icon takes over.
        visibility = INVISIBLE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The icon's rect in this view's coordinates; false if it is detached or not actually on
     * screen. Through the views' own matrices, in floats: getLocationInWindow rounds to whole
     * pixels, and a copy drawn half a pixel off the icon shifts visibly when the icon takes over.
     */
    private fun iconRectOf(v: IconView?, out: RectF): Boolean {
        // Not isShown: that is false for every view between onStop and onResume, which is exactly
        // when a return is prepared.
        if (v == null || !v.isAttachedToWindow || !L.shownInRoot(v)) return false
        v.iconRect(out)
        toScreen.reset()
        v.transformMatrixToGlobal(toScreen)
        toScreen.mapRect(out)
        if (isAttachedToWindow) {
            fromScreen.reset()
            transformMatrixToGlobal(fromScreen)
            if (fromScreen.invert(fromScreen)) fromScreen.mapRect(out)
        }
        return out.centerX() in 0f..L.root.width.toFloat() && out.centerY() in 0f..L.root.height.toFloat()
    }

    /** Takes over the icon's picture; false (and nothing shown) if there is no icon to play on. */
    private fun bind(v: IconView?, image: Bitmap?): Boolean {
        if (icon !== v) icon?.iconHidden = false
        icon = v
        bmp = image
        ontoFolder = v?.item is FolderItem
        if (image == null || !iconRectOf(v, from)) { finish(); return false }
        // Only the icon's picture is taken over; its label stays where it is.
        v?.iconHidden = !ontoFolder
        place()
        visibility = VISIBLE
        return true
    }

    /** How far the copy goes, and which way: toward the middle of the screen, never all the way. */
    private fun place() {
        val x = L.root.width / 2f - from.centerX()
        val y = L.root.height / 2f - from.centerY()
        val dist = hypot(x, y)
        // The farther the icon is from the middle, the longer its path.
        val travel = min(from.width() * HOP + dist * SHARE, from.width() * REACH)
        dx = if (dist > 1f) x / dist * travel else 0f
        dy = if (dist > 1f) y / dist * travel else 0f
    }

    /** Opening: the icon swells toward the middle and fades. */
    fun launch(v: IconView, image: Bitmap?) {
        finish()
        if (!bind(v, image)) return
        progress.anim.spring.stiffness = OUT_K
        progress.anim.spring.dampingRatio = 1f
        progress.snap(0f)
        launchedAt = android.os.SystemClock.uptimeMillis()
        // Off at once, not from rest: it answers a tap, and a spring starting at rest spends its
        // first frames hardly moving.
        progress.animateTo(1f, velocity = OUT_V0 * sqrt(OUT_K))
    }

    /** Coming back: poised a little way out from [target], faint, until [retract]. */
    fun prepareReturn(target: IconView?, image: Bitmap?) {
        if (retracting) return
        progress.cancel()
        if (!bind(target, image)) return
        poised = true
        progress.snap(1f)
    }

    /** Back onto the icon, from wherever the copy is. */
    fun retract() {
        if (!active || retracting) return
        // Out from a launch and already dissolving, its icon coming back in place: let that finish.
        if (opening && android.os.SystemClock.uptimeMillis() - launchedAt > GONE_FROM) return
        retracting = true
        poised = false
        if (!iconRectOf(icon, from)) { finish(); return }
        place()
        // Critically damped: a soft landing, never through the icon.
        progress.anim.spring.stiffness = BACK_K
        progress.anim.spring.dampingRatio = 1f
        progress.animateTo(0f) { finish() }
    }

    fun finish() {
        retracting = false
        poised = false
        progress.cancel()
        visibility = INVISIBLE
        icon?.iconHidden = false
        icon = null
        bmp = null
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        // Track the icon live (Home draws back as the app opens and opens out again as it
        // leaves), so the copy leaves from the icon and lands exactly on it.
        tmp.set(from)
        if (!iconRectOf(icon, from)) from.set(tmp)
        if (retracting && progress.value < LANDED) post { if (retracting) finish() }
        // On the icon exactly for the last frame, which is the one the real icon takes over from.
        val p = if (retracting && progress.value < LANDED) 0f else progress.value.coerceIn(0f, 1.05f)
        // Solid at the icon and fading from the moment it leaves, so that by the time it is over
        // its neighbours it is only a trace of itself (compared on the phone: a copy that stays
        // solid for the first part of the way sits on the next icon's label); never quite gone.
        // Onto a folder standing in for the app it also dissolves as it lands.
        var a = lerp(1f, ALPHA_MIN, smooth(FADE_FROM, FADE_TO, p))
        if (ontoFolder) a *= smooth(0f, 0.3f, p)
        if (opening) {
            // Out from a launch it does not wait for the app's window. An app that is slow to
            // come up (a cold start, a busy phone) would leave it hanging there, a faint icon
            // in mid-air over an empty place. So after a moment it goes altogether and its icon
            // comes back where it belongs; normally the app's window is over Home by then and
            // none of this is seen.
            val t = (android.os.SystemClock.uptimeMillis() - launchedAt).toFloat()
            a *= 1f - smooth(GONE_FROM.toFloat(), GONE_TO, t)
            val back = if (ontoFolder) 0f else smooth(BACK_FROM, BACK_TO, t)
            if (back > 0f) {
                paint.alpha = (255 * back).toInt()
                c.drawBitmap(b, null, from, paint)
            }
            if (t >= BACK_TO) { post { if (opening) finish() }; if (back >= 1f && a <= 0.004f) return }
            else postInvalidateOnAnimation()
        }
        if (a <= 0.004f) return
        val half = from.width() * lerp(1f, SCALE, p) / 2f
        val cx = from.centerX() + dx * p
        val cy = from.centerY() + dy * p
        dst.set(cx - half, cy - half, cx + half, cy + half)
        paint.alpha = (255 * a).toInt()
        c.drawBitmap(b, null, dst, paint)
    }

    private companion object {
        /** Progress below which the copy counts as landed (well under a pixel from the icon). */
        const val LANDED = 0.004f
        /** How large the copy gets. */
        const val SCALE = 1.7f
        /** Its path: this many icon widths, plus this share of the way to the middle, at most REACH icon widths. */
        const val HOP = 0.3f
        const val SHARE = 0.17f
        const val REACH = 2.4f
        /** It fades between these points of the way out, down to ALPHA_MIN. */
        const val FADE_FROM = 0f
        const val FADE_TO = 0.65f
        const val ALPHA_MIN = 0.08f
        /** Out from a launch: ms after the tap between which the copy goes altogether, and its icon comes back. */
        const val GONE_FROM = 240L
        const val GONE_TO = 400f
        const val BACK_FROM = 330f
        const val BACK_TO = 520f
        /**
         * Spring stiffness out of the icon and back onto it, both critically damped. Out, it
         * starts at OUT_V0 of its natural rate: 90% of the way in 0.09 s, at rest in about 0.17 s.
         * Back, from rest: 90% in 0.14 s, at rest in about 0.25 s.
         */
        const val OUT_K = 1300f
        const val OUT_V0 = 0.45f
        const val BACK_K = 800f
    }
}
