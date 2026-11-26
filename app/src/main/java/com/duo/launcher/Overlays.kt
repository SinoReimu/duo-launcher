package com.duo.launcher

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
    val glyph: MenuGlyph? = null,
    val run: () -> Unit,
)

enum class MenuGlyph { EDIT, REMOVE, HIDE, INFO, TRASH, RENAME, ADD, RESIZE, TUNE, SEARCH, CLEAR }

/** Line icons for the menu's leading column, drawn in the row's text colour. */
class MenuGlyphDrawable(private val glyph: MenuGlyph, color: Int) : Drawable() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()).toFloat()
        val ox = b.left + (b.width() - s) / 2f; val oy = b.top + (b.height() - s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        line.strokeWidth = s * 0.078f
        path.rewind()
        when (glyph) {
            MenuGlyph.EDIT -> {
                // A phone with a grid of apps.
                c.drawRoundRect(x(0.27f), y(0.1f), x(0.73f), y(0.9f), s * 0.11f, s * 0.11f, line)
                for (r in 0..2) for (k in 0..1) c.drawCircle(x(0.42f + k * 0.16f), y(0.34f + r * 0.16f), s * 0.042f, fill)
            }
            MenuGlyph.REMOVE -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.39f, line)
                c.drawLine(x(0.32f), y(0.5f), x(0.68f), y(0.5f), line)
            }
            MenuGlyph.HIDE -> {
                path.moveTo(x(0.08f), y(0.5f)); path.quadTo(x(0.5f), y(0.06f), x(0.92f), y(0.5f))
                path.quadTo(x(0.5f), y(0.94f), x(0.08f), y(0.5f)); path.close()
                c.drawPath(path, line)
                c.drawCircle(x(0.5f), y(0.5f), s * 0.12f, line)
                c.drawLine(x(0.2f), y(0.14f), x(0.8f), y(0.86f), line)
            }
            MenuGlyph.INFO -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.39f, line)
                c.drawCircle(x(0.5f), y(0.32f), s * 0.05f, fill)
                c.drawLine(x(0.5f), y(0.47f), x(0.5f), y(0.7f), line)
            }
            MenuGlyph.TRASH -> {
                c.drawLine(x(0.17f), y(0.26f), x(0.83f), y(0.26f), line)
                path.moveTo(x(0.38f), y(0.26f)); path.lineTo(x(0.41f), y(0.13f)); path.lineTo(x(0.59f), y(0.13f)); path.lineTo(x(0.62f), y(0.26f))
                path.moveTo(x(0.26f), y(0.26f)); path.lineTo(x(0.31f), y(0.88f)); path.lineTo(x(0.69f), y(0.88f)); path.lineTo(x(0.74f), y(0.26f))
                path.moveTo(x(0.42f), y(0.42f)); path.lineTo(x(0.43f), y(0.72f))
                path.moveTo(x(0.58f), y(0.42f)); path.lineTo(x(0.57f), y(0.72f))
                c.drawPath(path, line)
            }
            MenuGlyph.RENAME -> {
                path.moveTo(x(0.16f), y(0.84f)); path.lineTo(x(0.22f), y(0.62f)); path.lineTo(x(0.64f), y(0.2f))
                path.lineTo(x(0.8f), y(0.36f)); path.lineTo(x(0.38f), y(0.78f)); path.close()
                path.moveTo(x(0.55f), y(0.29f)); path.lineTo(x(0.71f), y(0.45f))
                c.drawPath(path, line)
            }
            MenuGlyph.ADD -> {
                c.drawRoundRect(x(0.13f), y(0.13f), x(0.87f), y(0.87f), s * 0.2f, s * 0.2f, line)
                c.drawLine(x(0.34f), y(0.5f), x(0.66f), y(0.5f), line)
                c.drawLine(x(0.5f), y(0.34f), x(0.5f), y(0.66f), line)
            }
            MenuGlyph.RESIZE -> {
                c.drawRoundRect(x(0.13f), y(0.13f), x(0.87f), y(0.87f), s * 0.2f, s * 0.2f, line)
                c.drawRoundRect(x(0.3f), y(0.3f), x(0.53f), y(0.53f), s * 0.05f, s * 0.05f, fill)
            }
            MenuGlyph.SEARCH -> {
                line.strokeWidth = s * 0.1f
                c.drawCircle(x(0.43f), y(0.43f), s * 0.3f, line)
                c.drawLine(x(0.66f), y(0.66f), x(0.9f), y(0.9f), line)
            }
            MenuGlyph.CLEAR -> {
                c.drawCircle(x(0.5f), y(0.5f), s * 0.5f, fill.apply { alpha = 0x55 })
                line.strokeWidth = s * 0.09f
                c.drawLine(x(0.34f), y(0.34f), x(0.66f), y(0.66f), line)
                c.drawLine(x(0.66f), y(0.34f), x(0.34f), y(0.66f), line)
            }
            MenuGlyph.TUNE -> {
                c.drawLine(x(0.14f), y(0.33f), x(0.86f), y(0.33f), line)
                c.drawLine(x(0.14f), y(0.67f), x(0.86f), y(0.67f), line)
                c.drawCircle(x(0.64f), y(0.33f), s * 0.1f, fill)
                c.drawCircle(x(0.36f), y(0.67f), s * 0.1f, fill)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Long-press menu: the icon lifts over a blurred home screen and a light glass card with leading
 * icons unfolds above or below it, as on the iPhone Duo.
 */
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
        // Light glass: Home is already blurred behind it, so a bright translucent sheet with a
        // lit rim reads as frosted without a second blur pass.
        scroller.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xEBFFFFFF.toInt(), 0xDBF1F1F5.toInt()),
        ).apply { cornerRadius = p.r(28f); setStroke(maxOf(1, p.dpi(1f)), 0x99FFFFFF.toInt()) }
        scroller.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        card.setPadding(0, p.r(9f).roundToInt(), 0, p.r(9f).roundToInt())
        val rowH = p.r(44f).roundToInt()
        val rows = ArrayList<View>()
        for (a in actions) {
            if (a.separatorBefore && card.childCount > 0) {
                // Group break: a hairline with air around it. Rows inside a group have no rules.
                card.addView(View(L).apply { setBackgroundColor(0x24000000) },
                    LinearLayout.LayoutParams(-1, maxOf(1, p.dpi(0.7f))).apply {
                        topMargin = p.r(8f).roundToInt(); bottomMargin = p.r(8f).roundToInt()
                        leftMargin = p.r(24f).roundToInt(); rightMargin = p.r(24f).roundToInt()
                    })
            }
            val r = row(a, p)
            rows += r
            card.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, rowH))
        }
        // Wide enough for the longest label (as the rows themselves measure it), within reason.
        val natural = rows.maxOfOrNull {
            it.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY)); it.measuredWidth
        } ?: 0
        val w = (natural + p.r(4f)).coerceIn(p.r(236f), min(p.r(330f), L.root.width - p.dp(24f))).roundToInt()
        for (r in rows) r.layoutParams.width = w
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
            img.springScale(1.12f, 500f, 0.6f)
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
        val color = if (a.destructive) 0xFFFF3B30.toInt() else 0xFF1C1C1E.toInt()
        val r = LinearLayout(L).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p.r(24f).roundToInt(), 0, p.r(20f).roundToInt(), 0)
            isClickable = true
            // Plain highlight while pressed (no ripple), like iOS menus.
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x1A000000))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0x00000000))
                setExitFadeDuration(180)
            }
            setOnClickListener { dismiss(immediate = true); a.run() }
        }
        // Leading column: the action's glyph, or an app shortcut's own icon. Always reserved, so
        // labels line up.
        val lead = a.icon ?: a.glyph?.let { MenuGlyphDrawable(it, color) }
        val leadSize = p.r(if (a.icon != null) 26f else 22f).roundToInt()
        r.addView(ImageView(L).apply { setImageDrawable(lead) },
            LinearLayout.LayoutParams(leadSize, leadSize).apply {
                val pad = ((p.r(26f) - leadSize) / 2f).roundToInt()
                leftMargin = pad; rightMargin = pad + p.r(14f).roundToInt()
            })
        r.addView(TextView(L).apply {
            text = a.label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, p.r(17f))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
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

    open fun dismiss() {
        progress.animateTo(0f) { (parent as? android.view.ViewGroup)?.removeView(this); L.onSheetClosed(this) }
    }

    /** Extra offset of the panel (negative = up), e.g. to clear the keyboard while searching. */
    protected var liftY = 0f
        set(v) { field = v; apply(progress.value) }

    private fun apply(t: Float) {
        scrim.alpha = t.coerceIn(0f, 1f)
        panel.translationY = (1f - t) * (panel.height.takeIf { it > 0 } ?: L.root.height) + liftY * t.coerceIn(0f, 1f)
    }

    protected fun sectionTitle(s: String) = TextView(L).apply {
        text = s; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, L.profile!!.dpi(18f), 0, L.profile!!.dpi(6f))
    }
}

class WidgetPicker(L: MainActivity) : Sheet(L, t("添加小组件", "Add Widget")) {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** One app's widgets: its title and rows, with what each row can be found by. */
    private class Section(val title: View, val appKeys: List<String>, val rows: List<Pair<View, String>>)
    private val sections = ArrayList<Section>()
    private val field = android.widget.EditText(L)
    private val empty = sectionTitle(t("没有匹配的小组件", "No matching widgets"))
    private val lift = SpringValue(0f, stiffness = 420f, damping = 1f) { liftY = it }

    init {
        val p = L.profile!!
        panel.addView(searchBar(p), 1, LinearLayout.LayoutParams(-1, p.dpi(40f)).apply {
            topMargin = p.dpi(12f); bottomMargin = p.dpi(2f)
        })
        val providers = L.widgetManager.installedProviders
            .groupBy { it.provider.packageName }
            .toSortedMap(compareBy { pkg -> appLabel(pkg).lowercase() })
        for ((pkg, list) in providers) {
            val label = appLabel(pkg)
            val title = sectionTitle(label)
            body.addView(title)
            // The app's Finder keys too, so pinyin and initials find its widgets.
            val keys = listOf(label.lowercase()) +
                L.repo.apps.values.filter { it.packageName == pkg }.flatMap { it.searchKeys.asList() }
            val rows = list.map { info ->
                val row = providerRow(info, p)
                body.addView(row)
                row to info.loadLabel(L.packageManager).lowercase()
            }
            sections += Section(title, keys.distinct(), rows)
        }
        if (providers.isEmpty()) body.addView(sectionTitle(t("没有可用的小组件", "No widgets available")))
        empty.visibility = GONE
        body.addView(empty)
    }

    private fun searchBar(p: DeviceProfile): View {
        val bar = LinearLayout(L).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = p.dp(12f); setColor(0x1FFFFFFF) }
            setPadding(p.dpi(10f), 0, p.dpi(6f), 0)
        }
        bar.addView(ImageView(L).apply { setImageDrawable(MenuGlyphDrawable(MenuGlyph.SEARCH, 0x99FFFFFF.toInt())) },
            LinearLayout.LayoutParams(p.dpi(18f), p.dpi(18f)).apply { rightMargin = p.dpi(8f) })
        field.apply {
            background = null
            setPadding(0, 0, 0, 0)
            hint = t("搜索小组件", "Search widgets")
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            useWhiteCursor()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) = filter(e?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
            setOnFocusChangeListener { _, has -> setSearching(has) }
        }
        bar.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        val clear = ImageView(L).apply {
            setImageDrawable(MenuGlyphDrawable(MenuGlyph.CLEAR, 0x99FFFFFF.toInt()))
            setPadding(p.dpi(7f), p.dpi(7f), p.dpi(7f), p.dpi(7f))
            visibility = INVISIBLE
            setOnClickListener { field.setText("") }
        }
        bar.addView(clear, LinearLayout.LayoutParams(p.dpi(32f), p.dpi(32f)))
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { clear.visibility = if (e.isNullOrEmpty()) INVISIBLE else VISIBLE }
        })
        bar.setOnClickListener {
            field.requestFocus()
            L.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0)
        }
        return bar
    }

    /** Shows the widgets whose own name or app name (pinyin and initials too) contains [query]. */
    private fun filter(query: String) {
        val q = query.trim().lowercase()
        var any = false
        for (sec in sections) {
            val appHit = q.isEmpty() || sec.appKeys.any { it.contains(q) }
            var shown = false
            for ((row, label) in sec.rows) {
                val hit = appHit || label.contains(q)
                row.visibility = if (hit) VISIBLE else GONE
                shown = shown || hit
            }
            sec.title.visibility = if (shown) VISIBLE else GONE
            any = any || shown
        }
        empty.visibility = if (any || sections.isEmpty()) GONE else VISIBLE
        (body.parent as? ScrollView)?.scrollTo(0, 0)
    }

    /**
     * The window does not resize for the keyboard, so while the field has focus the sheet moves
     * up under the status bar and the list gets room to scroll its end above the keyboard.
     */
    private fun setSearching(on: Boolean) {
        val p = L.profile ?: return
        val up = if (on) -(panel.top - (p.insets.top + p.dpi(8f))).toFloat().coerceAtLeast(0f) else 0f
        lift.animateTo(up)
        body.setPadding(0, 0, 0, if (on) (L.root.height * 0.5f).roundToInt() else 0)
    }

    private fun hideKeyboard() {
        L.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        field.clearFocus()
    }

    override fun dismiss() {
        hideKeyboard()
        super.dismiss()
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
