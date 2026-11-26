package com.duo.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.hypot

class LauncherWidgetHost(context: Context) : AppWidgetHost(context, 0x4455) {
    override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo?): AppWidgetHostView =
        AppWidgetHostView(context)
}

object WidgetSizing {
    /** Default span for a provider on the 4x6 page grid. */
    fun defaultSpan(info: AppWidgetProviderInfo, p: DeviceProfile?): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, COLS) to info.targetCellHeight.coerceIn(1, ROWS)
        }
        val cw = (p?.cellW ?: 200f) / (p?.density ?: 2.6f)
        val ch = (p?.cellH ?: 200f) / (p?.density ?: 2.6f)
        val sx = ceil(info.minWidth / (p?.density ?: 1f) / cw).toInt()
        val sy = ceil(info.minHeight / (p?.density ?: 1f) / ch).toInt()
        return sx.coerceIn(1, COLS) to sy.coerceIn(1, ROWS)
    }
}

/**
 * Hosts an AppWidgetHostView with iOS-like rounded corners. Steals the touch stream on long-press
 * so widgets can be moved, and draws the remove badge in edit mode.
 */
@SuppressLint("ViewConstructor")
class WidgetContainer(private val L: MainActivity, val item: WidgetItem) : FrameLayout(L) {
    var hostView: View? = null
        private set
    private val slop = ViewConfiguration.get(L).scaledTouchSlop
    /** The long-press menu opened for this press: no drag out of it. */
    private var menuOpened = false
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var stolen = false
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEBE4E4E8.toInt() }
    private val badgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C3C43.toInt(); strokeCap = Paint.Cap.ROUND }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, (L.profile?.dp(24f) ?: 60f))
            }
        }
        setWillNotDraw(false)
        attach()
    }

    fun attach() {
        removeAllViews()
        val info = L.widgetManager.getAppWidgetInfo(item.widgetId)
        val v: View = if (info == null) {
            View(L).apply { setBackgroundColor(0x33FFFFFF) }
        } else {
            L.widgetHost.createView(L.applicationContext, item.widgetId, info)
        }
        hostView = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        invalidateOutline()
        val d = resources.displayMetrics.density
        val hv = hostView as? AppWidgetHostView ?: return
        if (w == 0 || h == 0) return
        val size = SizeF(w / d, h / d)
        val opts = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (w / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (w / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (h / d).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (h / d).toInt())
        }
        post { runCatching { hv.updateAppWidgetOptions(opts) } }
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (L.editMode) {
            val br = (L.profile?.dp(11f) ?: 28f)
            val cx = br * 1.2f; val cy = br * 1.2f
            c.drawCircle(cx, cy, br, badgePaint)
            badgeLine.strokeWidth = br * 0.2f
            c.drawLine(cx - br * 0.42f, cy, cx + br * 0.42f, cy, badgeLine)
        }
    }

    private val longPress = Runnable {
        longPressed = true
        stolen = true
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!L.editMode) {
            menuOpened = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            L.showContextMenu(this)
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; longPressed = false; stolen = false; menuOpened = false
                if (L.editMode) {
                    val br = (L.profile?.dp(11f) ?: 28f)
                    if (hypot(e.x - br * 1.2f, e.y - br * 1.2f) < br * 2f) {
                        L.onRemoveBadge(this); return true
                    }
                    // In edit mode the widget content is inert; only moving is allowed.
                    postDelayed(longPress, 170)
                    return true
                }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong().coerceAtMost(420L))
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed && hypot(e.x - downX, e.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return stolen
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(e.x - downX, e.y - downY)
                if (menuOpened) {
                    // Menu is up: nothing gets dragged.
                } else if (longPressed && d > slop) {
                    longPressed = false
                    L.drag.startDrag(this, e.rawX, e.rawY, e.rawX - (e.x - downX), e.rawY - (e.y - downY))
                } else if (!longPressed && d > slop) {
                    removeCallbacks(longPress)
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (L.editMode && !longPressed && e.actionMasked == MotionEvent.ACTION_UP &&
                    hypot(e.x - downX, e.y - downY) < slop) L.exitEditMode()
            }
        }
        return true
    }
}
