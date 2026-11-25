package com.duo.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

class MenuAction(
    val label: String,
    val icon: Drawable? = null,
    val destructive: Boolean = false,
    val separatorBefore: Boolean = false,
    val run: () -> Unit,
)

/** Long-press menu: lifted icon over a blurred home screen, card anchored above/below it. */
@SuppressLint("ViewConstructor")
class ContextMenuOverlay(private val L: MainActivity) : FrameLayout(L) {
    private val scrim = View(L).apply { setBackgroundColor(0x26000000) }
    private val card = LinearLayout(L).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = ScrollView(L)
    private var lifted: DragImage? = null
    private var anchorView: View? = null
    private val progress = SpringValue(0f, stiffness = 600f, damping = 0.8f) { apply(it) }
    val isShowing get() = visibility == VISIBLE

    init {
        visibility = GONE
        addView(scrim, LayoutParams(-1, -1))
        scroller.addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        scrim.setOnClickListener { dismiss() }
        scroller.clipToOutline = true
        scroller.isVerticalScrollBarEnabled = false
        scroller.overScrollMode = OVER_SCROLL_NEVER
    }

    fun show(anchor: View, iconRect: RectF, snapshot: Bitmap?, actions: List<MenuAction>) {
        val p = L.profile ?: return
        finishDismiss?.invoke()
        anchorView = anchor
        card.removeAllViews()
        scroller.background = GradientDrawable().apply { cornerRadius = p.dp(22f); setColor(0xF2242426.toInt()) }
        card.setPadding(0, p.dpi(6f), 0, p.dpi(6f))
        val w = p.dpi(264f)
        for (a in actions) {
            if (a.separatorBefore && card.childCount > 0) {
                // Group break: a slim darker band edge to edge.
                card.addView(View(L).apply { setBackgroundColor(0xFF161618.toInt()) },
                    LinearLayout.LayoutParams(-1, p.dpi(6f)).apply { topMargin = p.dpi(6f); bottomMargin = p.dpi(6f) })
            } else if (card.childCount > 0) {
                // Hairline inset from both sides.
                card.addView(View(L).apply { setBackgroundColor(0x1FFFFFFF) },
                    LinearLayout.LayoutParams(-1, maxOf(1, p.dpi(0.5f))).apply {
                        leftMargin = p.dpi(20f); rightMargin = p.dpi(20f)
                    })
            }
            card.addView(row(a, p), LinearLayout.LayoutParams(w, p.dpi(50f)))
        }
        card.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val full = card.measuredHeight
        val gap = p.dp(12f)
        val H = L.root.height
        // Space above / below the lifted icon; the menu never covers the icon.
        val spaceBelow = H - p.insets.bottom - p.dp(10f) - (iconRect.bottom + gap)
        val spaceAbove = iconRect.top - gap - (p.insets.top + p.dp(10f))
        val below = full <= spaceBelow || (full > spaceAbove && spaceBelow >= spaceAbove)
        // Too tall for either side (cover screen): cap the height and let the rows scroll.
        val ch = minOf(full.toFloat(), if (below) spaceBelow else spaceAbove).roundToInt()
        val top = if (below) iconRect.bottom + gap else iconRect.top - gap - ch
        // Align the menu with the icon edge nearer the screen edge (like iOS).
        var left = if (iconRect.centerX() > L.root.width / 2f) iconRect.right - w else iconRect.left
        left = left.coerceIn(p.dp(12f), L.root.width - w - p.dp(12f))
        (scroller.layoutParams as LayoutParams).apply {
            leftMargin = left.roundToInt(); topMargin = top.roundToInt(); height = ch
        }
        scroller.scrollTo(0, 0)
        scroller.pivotX = (iconRect.centerX() - left).coerceIn(0f, w.toFloat())
        scroller.pivotY = if (below) 0f else ch.toFloat()

        lifted?.let { removeView(it) }
        lifted = null
        if (snapshot != null) {
            val img = DragImage(L, snapshot)
            addView(img, LayoutParams(snapshot.width, snapshot.height))
            img.translationX = iconRect.left; img.translationY = iconRect.top
            img.scaleX = 1f; img.scaleY = 1f
            img.springScale(1.08f, 500f, 0.6f)
            lifted = img
            // An icon keeps its label in place (blurred along with Home), so nothing pops in
            // when the menu goes away; only the icon itself is lifted.
            if (anchor is IconView) anchor.iconHidden = true else anchor.visibility = INVISIBLE
        }
        visibility = VISIBLE
        progress.snap(0f)
        progress.animateTo(1f)
    }

    private fun row(a: MenuAction, p: DeviceProfile): View {
        val r = LinearLayout(L).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p.dpi(20f), 0, p.dpi(20f), 0)
            isClickable = true
            // Plain highlight while pressed (no ripple), like iOS menus.
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x1FFFFFFF))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0x00000000))
                setExitFadeDuration(180)
            }
            setOnClickListener { dismiss(immediate = true); a.run() }
        }
        r.addView(TextView(L).apply {
            text = a.label
            setTextColor(if (a.destructive) 0xFFFF453A.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (a.icon != null) {
            r.addView(ImageView(L).apply { setImageDrawable(a.icon) },
                LinearLayout.LayoutParams(p.dpi(20f), p.dpi(20f)).apply { leftMargin = p.dpi(14f) })
        }
        return r
    }

    private var finishDismiss: (() -> Unit)? = null

    private fun apply(t: Float) {
        // Closing: hand back to the real icon as soon as the lifted copy has landed instead of
        // waiting out the spring's tail.
        if (finishDismiss != null && t < 0.03f) { finishDismiss?.invoke(); return }
        scrim.alpha = t.coerceIn(0f, 1f)
        scroller.alpha = t.coerceIn(0f, 1f)
        val s = lerp(0.5f, 1f, t)
        scroller.scaleX = s; scroller.scaleY = s
        L.setBackdrop(t.coerceIn(0f, 1f), BackdropOwner.MENU)
    }

    fun dismiss(immediate: Boolean = false) {
        if (!isShowing) return
        val img = lifted
        val anchor = anchorView
        val finish = {
            finishDismiss = null
            progress.cancel()
            scrim.alpha = 0f; scroller.alpha = 0f
            visibility = GONE
            img?.let { removeView(it) }
            if (lifted === img) lifted = null
            (anchor as? IconView)?.iconHidden = false
            if (anchor != null && !L.drag.isHiddenForDrag(anchor) && anchor.parent != null) anchor.visibility = VISIBLE
            L.setBackdrop(0f, BackdropOwner.MENU)
        }
        if (immediate) { finishDismiss = null; progress.snap(0f); finish(); return }
        img?.springScale(1f, 600f, 0.9f)
        finishDismiss = finish
        progress.animateTo(0f) { finishDismiss?.invoke() }
    }
}

/** Bottom sheet used for the widget gallery and settings. */
@SuppressLint("ViewConstructor")
open class Sheet(protected val L: MainActivity, titleText: String) : FrameLayout(L) {
    private val scrim = View(L).apply { setBackgroundColor(0x66000000) }
    protected val panel = LinearLayout(L).apply { orientation = LinearLayout.VERTICAL }
    protected val body = LinearLayout(L).apply { orientation = LinearLayout.VERTICAL }
    private val progress = SpringValue(0f, stiffness = 500f, damping = 0.9f) { apply(it) }

    init {
        val p = L.profile!!
        addView(scrim, LayoutParams(-1, -1))
        val w = min(L.root.width - p.dpi(24f), p.dpi(640f))
        val h = (L.root.height * 0.78f).roundToInt()
        panel.background = GradientDrawable().apply {
            cornerRadius = p.dp(30f); setColor(0xF51C1C1E.toInt())
        }
        panel.setPadding(p.dpi(20f), p.dpi(18f), p.dpi(20f), p.dpi(10f))
        val header = LinearLayout(L).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(L).apply {
            text = titleText; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(L).apply {
            text = t("完成", "Done"); setTextColor(0xFF0A84FF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(p.dpi(12f), p.dpi(8f), 0, p.dpi(8f))
            setOnClickListener { dismiss() }
        })
        panel.addView(header)
        val sv = ScrollView(L).apply { isVerticalScrollBarEnabled = false }
        sv.addView(body)
        panel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(panel, LayoutParams(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = p.insets.bottom + p.dpi(8f)
        })
        scrim.setOnClickListener { dismiss() }
        panel.isClickable = true
    }

    fun show() {
        L.root.addView(this, LayoutParams(-1, -1))
        progress.snap(0f)
        progress.animateTo(1f)
    }

    fun dismiss() {
        progress.animateTo(0f) { (parent as? android.view.ViewGroup)?.removeView(this); L.onSheetClosed(this) }
    }

    private fun apply(t: Float) {
        scrim.alpha = t.coerceIn(0f, 1f)
        panel.translationY = (1f - t) * (panel.height.takeIf { it > 0 } ?: L.root.height)
    }

    protected fun sectionTitle(s: String) = TextView(L).apply {
        text = s; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, L.profile!!.dpi(18f), 0, L.profile!!.dpi(6f))
    }
}

class WidgetPicker(L: MainActivity) : Sheet(L, t("添加小组件", "Add Widget")) {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    init {
        val p = L.profile!!
        val providers = L.widgetManager.installedProviders
            .groupBy { it.provider.packageName }
            .toSortedMap(compareBy { pkg -> appLabel(pkg).lowercase() })
        for ((pkg, list) in providers) {
            body.addView(sectionTitle(appLabel(pkg)))
            for (info in list) body.addView(providerRow(info, p))
        }
        if (providers.isEmpty()) body.addView(sectionTitle(t("没有可用的小组件", "No widgets available")))
    }

    private fun appLabel(pkg: String): String = runCatching {
        L.packageManager.getApplicationLabel(L.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun providerRow(info: AppWidgetProviderInfo, p: DeviceProfile): View {
        val (sx, sy) = WidgetSizing.defaultSpan(info, p)
        val row = LinearLayout(L).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p.dpi(6f), 0, p.dpi(6f))
            isClickable = true
            setOnClickListener { dismiss(); L.addWidget(info) }
        }
        val img = ImageView(L).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = p.dp(14f); setColor(0x22FFFFFF) }
            clipToOutline = true
        }
        row.addView(img, LinearLayout.LayoutParams(p.dpi(110f), p.dpi(76f)))
        val texts = LinearLayout(L).apply { orientation = LinearLayout.VERTICAL; setPadding(p.dpi(14f), 0, 0, 0) }
        texts.addView(TextView(L).apply {
            text = info.loadLabel(L.packageManager); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        texts.addView(TextView(L).apply {
            text = "$sx × $sy"; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val density = L.resources.displayMetrics.densityDpi
        io.execute {
            val d = runCatching { info.loadPreviewImage(L, density) ?: info.loadIcon(L, density) }.getOrNull()
            main.post { img.setImageDrawable(d) }
        }
        return row
    }
}

class SettingsSheet(L: MainActivity) : Sheet(L, t("桌面设置", "Home Settings")) {
    init {
        val m = L.model
        body.addView(sectionTitle(t("手势", "Gestures")))
        body.addView(toggle(t("下滑打开通知面板（关闭则打开搜索；上滑始终打开搜索）", "Swipe down opens notifications (off: Finder; swipe up always opens Finder)"), m.swipeDownNotifications) { m.swipeDownNotifications = it })
        body.addView(toggle(t("双击空白处锁屏", "Double-tap empty space to lock"), m.doubleTapLock) { m.doubleTapLock = it })
        body.addView(toggle(t("桌面自绘的 App 打开/关闭动画（关闭则只用系统动画）", "Launcher-drawn app open/close animation (off: system only)"), m.appAnimation) { m.appAnimation = it })
        body.addView(button(
            if (L.hasUsageAccess()) t("返回桌面动画：已精确定位到图标 ✓", "Return animation: exact icon ✓")
            else t("开启“使用情况访问”，让返回动画缩回正确的图标…", "Allow usage access so apps shrink back into the right icon…"),
        ) {
            runCatching { L.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        body.addView(sectionTitle(t("主屏幕", "Home Screen")))
        body.addView(toggle(t("新安装的 App 添加到主屏幕", "Add new apps to Home Screen"), m.addNewApps) { m.addNewApps = it })
        body.addView(toggle(t("显示图标名称", "Show icon labels"), m.showLabels) { m.showLabels = it; L.onProfileInputsChanged() })
        body.addView(toggle(t("搜索结果包含隐藏的 App", "Show hidden apps in search"), m.searchHidden) { m.searchHidden = it })
        body.addView(sectionTitle(t("隐藏的 App（点按以取消隐藏）", "Hidden apps (tap to unhide)")))
        if (m.hidden.isEmpty()) body.addView(TextView(L).apply {
            text = t("无", "None"); setTextColor(0x88FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        for (k in m.hidden.toList()) {
            val label = L.repo[k]?.label ?: k
            body.addView(TextView(L).apply {
                text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, L.profile!!.dpi(10f), 0, L.profile!!.dpi(10f))
                setOnClickListener { L.unhide(k); visibility = GONE }
            })
        }
        body.addView(sectionTitle(t("其他", "Other")))
        body.addView(button(t("设为默认桌面…", "Set as default Home app…")) {
            runCatching { L.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        })
        body.addView(button(t("重置主屏幕布局", "Reset Home Screen layout"), destructive = true) { dismiss(); L.resetLayout() })
    }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit): View {
        val p = L.profile!!
        val row = LinearLayout(L).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p.dpi(10f), 0, p.dpi(10f))
        }
        row.addView(TextView(L).apply {
            text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = p.dpi(16f) })
        val sw = ToggleView(L, value) { set(it); L.save() }
        row.addView(sw, LinearLayout.LayoutParams(p.dpi(51f), p.dpi(31f)))
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun button(label: String, destructive: Boolean = false, run: () -> Unit) = TextView(L).apply {
        text = label
        setTextColor(if (destructive) 0xFFFF453A.toInt() else 0xFF0A84FF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, L.profile!!.dpi(12f), 0, L.profile!!.dpi(12f))
        setOnClickListener { run() }
    }
}

/** Top bar shown in edit (jiggle) mode. */
@SuppressLint("ViewConstructor")
class EditBar(private val L: MainActivity) : FrameLayout(L) {
    init {
        visibility = GONE
        val row = LinearLayout(L).apply { gravity = Gravity.CENTER_VERTICAL }
        fun pill(text: String, accent: Boolean = false, onClick: () -> Unit) = TextView(L).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            val p = L.profile
            val d = p?.density ?: 2.6f
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(if (accent) 0xCC0A84FF.toInt() else 0x40FFFFFF) }
            setOnClickListener { onClick() }
        }
        val d = L.resources.displayMetrics.density
        row.addView(pill("＋") { L.openWidgetPicker() })
        row.addView(View(L), LinearLayout.LayoutParams((10 * d).toInt(), 1))
        row.addView(pill(t("设置", "Settings")) { L.openSettings() })
        row.addView(View(L), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(pill(t("完成", "Done"), accent = true) { L.exitEditMode() })
        addView(row, LayoutParams(-1, -2))
    }

    fun setShown(shown: Boolean) {
        if (shown) {
            if (visibility != VISIBLE) { visibility = VISIBLE; alpha = 0f; translationY = -40f }
            springTo(DynamicAnimation.ALPHA, 1f, 600f, 1f)
            springTo(DynamicAnimation.TRANSLATION_Y, 0f, 600f, 0.8f)
        } else if (visibility == VISIBLE) {
            springTo(DynamicAnimation.ALPHA, 0f, 600f, 1f) { visibility = GONE }
            springTo(DynamicAnimation.TRANSLATION_Y, -40f, 600f, 1f)
        }
    }
}

/** iOS-style switch: green track when on, white knob that springs across. */
@SuppressLint("ViewConstructor")
class ToggleView(L: MainActivity, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(L) {
    var checked = initial
        private set
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; setShadowLayer(4f, 0f, 2f, 0x40000000)
    }
    private val pos = SpringValue(if (initial) 1f else 0f, stiffness = 700f, damping = 0.8f) { invalidate() }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // knob shadow
        setOnClickListener { toggle() }
    }

    fun toggle() {
        checked = !checked
        pos.animateTo(if (checked) 1f else 0f)
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        onChange(checked)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val t = pos.value.coerceIn(0f, 1f)
        val h = height.toFloat(); val w = width.toFloat()
        val off = 0x40787880; val on = 0xFF34C759.toInt()
        track.color = android.animation.ArgbEvaluator().evaluate(t, off, on) as Int
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, track)
        val r = h / 2 - h * 0.07f
        val cx = lerp(h / 2, w - h / 2, pos.value)
        c.drawCircle(cx, h / 2, r, knob)
    }
}
