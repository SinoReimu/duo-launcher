package com.duo.launcher

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.sin

/**
 * Springs are used for every motion so any animation can be retargeted mid-flight:
 * a new target simply continues from the current value and velocity.
 */
private val springsKey = R.id.springs

@Suppress("UNCHECKED_CAST")
fun View.spring(prop: DynamicAnimation.ViewProperty): SpringAnimation {
    val map = (getTag(springsKey) as? HashMap<DynamicAnimation.ViewProperty, SpringAnimation>)
        ?: HashMap<DynamicAnimation.ViewProperty, SpringAnimation>().also { setTag(springsKey, it) }
    return map.getOrPut(prop) {
        SpringAnimation(this, prop).setSpring(SpringForce().setDampingRatio(0.86f).setStiffness(500f))
    }
}

fun View.springTo(
    prop: DynamicAnimation.ViewProperty,
    target: Float,
    stiffness: Float = 500f,
    damping: Float = 0.86f,
    velocity: Float? = null,
    onEnd: (() -> Unit)? = null,
) {
    val s = spring(prop)
    s.spring.stiffness = stiffness
    s.spring.dampingRatio = damping
    if (velocity != null) s.setStartVelocity(velocity)
    if (onEnd != null) {
        s.addEndListener(object : DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(a: DynamicAnimation<*>?, canceled: Boolean, v: Float, vel: Float) {
                a?.removeEndListener(this)
                if (!canceled) onEnd()
            }
        })
    }
    s.animateToFinalPosition(target)
}

/**
 * Jumps a property to [from] and springs it to [to]. A spring that is already running is
 * cancelled first; otherwise its next frame would overwrite [from] with its own stale value and
 * the view would visibly teleport.
 */
fun View.springFrom(
    prop: DynamicAnimation.ViewProperty,
    from: Float,
    to: Float,
    stiffness: Float = 500f,
    damping: Float = 0.86f,
) {
    val s = spring(prop)
    if (s.isRunning) s.cancel()
    prop.setValue(this, from)
    springTo(prop, to, stiffness, damping)
}

fun View.springScale(target: Float, stiffness: Float = 500f, damping: Float = 0.86f) {
    springTo(DynamicAnimation.SCALE_X, target, stiffness, damping)
    springTo(DynamicAnimation.SCALE_Y, target, stiffness, damping)
}

fun View.cancelSprings() {
    @Suppress("UNCHECKED_CAST")
    (getTag(springsKey) as? HashMap<DynamicAnimation.ViewProperty, SpringAnimation>)?.values?.forEach { it.cancel() }
}

/** A plain float driven by a spring, e.g. the progress of an overlay. */
class SpringValue(
    initial: Float,
    stiffness: Float = 500f,
    damping: Float = 0.9f,
    private val onUpdate: (Float) -> Unit,
) {
    var value = initial
        private set
    private val prop = object : FloatPropertyCompat<SpringValue>("v") {
        override fun getValue(o: SpringValue) = o.value
        override fun setValue(o: SpringValue, v: Float) { o.value = v; onUpdate(v) }
    }
    val anim = SpringAnimation(this, prop).apply {
        spring = SpringForce().setStiffness(stiffness).setDampingRatio(damping)
        minimumVisibleChange = 0.001f
    }
    private var endAction: (() -> Unit)? = null

    init {
        anim.addEndListener { _, canceled, _, _ ->
            val e = endAction; endAction = null
            if (!canceled) e?.invoke()
        }
    }

    fun snap(v: Float) { anim.cancel(); value = v; onUpdate(v) }

    fun animateTo(v: Float, velocity: Float? = null, end: (() -> Unit)? = null) {
        endAction = end
        if (velocity != null) anim.setStartVelocity(velocity)
        anim.animateToFinalPosition(v)
    }

    val running get() = anim.isRunning
    fun cancel() { endAction = null; anim.cancel() }
}

object Blur {
    fun apply(v: View, radius: Float) {
        v.setRenderEffect(if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Drives the jiggle of every icon in edit mode from one frame callback.
 * Only rotation is touched, which is a RenderNode property: no re-recording of display lists.
 */
class Jiggle(private val collect: (MutableList<View>) -> Unit) : Choreographer.FrameCallback {
    private var running = false
    private val views = ArrayList<View>(64)
    private var amplitude = 0f
    private var targetAmp = 0f

    fun start() {
        targetAmp = 1f
        if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) }
    }

    fun stop() { targetAmp = 0f }

    override fun doFrame(frameTimeNanos: Long) {
        amplitude += (targetAmp - amplitude) * 0.25f
        views.clear()
        collect(views)
        val t = frameTimeNanos / 1e9
        for (v in views) {
            val phase = (v.hashCode() and 0xff) / 255.0 * 2 * PI
            // Large views (widgets, folders in a stack) wobble by a smaller angle, like iOS.
            val sizeK = if (v.width > 0) minOf(1f, 260f / v.width) else 1f
            v.rotation = (sin(t * 2 * PI / 0.27 + phase) * 1.7 * amplitude * sizeK).toFloat()
        }
        if (targetAmp == 0f && amplitude < 0.01f) {
            for (v in views) v.rotation = 0f
            amplitude = 0f
            running = false
            return
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
