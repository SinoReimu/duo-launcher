package com.duo.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.app.SearchManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Swipe-down app finder (like One UI Finder / iOS Spotlight). Follows the finger while pulling,
 * then springs open or closed; any later gesture can retarget the spring.
 */
@SuppressLint("ViewConstructor")
class FinderView(private val L: MainActivity) : FrameLayout(L) {
    private val scrim = View(L).apply { setBackgroundColor(0x40000000) }
    private val bar = FrameLayout(L)
    val field = EditText(L)
    /** Results dissolve as they scroll up under the search bar instead of being cut off. */
    private var fadeLen = 0f
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val list = object : RecyclerView(L) {
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (fadeLen <= 0f) return
            // Fade strength grows with the scroll, so nothing pops when leaving the top.
            val k = (computeVerticalScrollOffset() / fadeLen).coerceIn(0f, 1f)
            if (k <= 0f) return
            fadePaint.alpha = (255 * k).toInt()
            c.drawRect(0f, 0f, width.toFloat(), fadeLen, fadePaint)
        }
    }
    private val adapter = Adapter()
    private val layoutManager = GridLayoutManager(L, 4)
    private val progress = SpringValue(0f, stiffness = 450f, damping = 0.88f) { apply(it) }
    val isOpen get() = visibility == VISIBLE && progress.value > 0.01f
    private var imeBottom = 0

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class App(val key: String) : Row()
        data class Web(val q: String) : Row()
    }
    private var rows: List<Row> = emptyList()
    private val itemCache = HashMap<String, AppItem>()

    init {
        visibility = GONE
        addView(scrim, LayoutParams(-1, -1))
        bar.background = GradientDrawable().apply { cornerRadius = 999f; setColor(0x40FFFFFF) }
        field.apply {
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0xB3FFFFFF.toInt())
            useWhiteCursor()
            hint = t("搜索", "Search")
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> launchFirst(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        // Magnifier + text as one group, centred in the pill both ways (grows as you type).
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        field.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(0xB3FFFFFF.toInt())
        bar.addView(field, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // The whole pill is the target, not just the text.
        bar.setOnClickListener {
            field.requestFocus()
            L.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }
        addView(bar)
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.clipToPadding = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.itemAnimator = null
        // Own layer so the fade (DST_OUT) only erases the list, not what is behind it.
        list.setLayerType(LAYER_TYPE_HARDWARE, null)
        list.setItemViewCacheSize(40)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideIme()
            }
        })
        addView(list, LayoutParams(-1, -1))
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.App) 1 else layoutManager.spanCount
        }
        // Tap on empty space closes.
        scrim.setOnClickListener { close() }
        list.setOnTouchListener(object : OnTouchListener {
            var downY = 0f; var downX = 0f
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                    MotionEvent.ACTION_UP -> if (Math.abs(e.y - downY) < 12 && Math.abs(e.x - downX) < 12 &&
                        list.findChildViewUnder(e.x, e.y) == null) close()
                }
                return false
            }
        })
        setOnApplyWindowInsetsListener { _, insets ->
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            layoutContents()
            insets
        }
    }

    fun layoutContents() {
        val p = L.profile ?: return
        val w = L.root.width.takeIf { it > 0 } ?: return
        val barW = min(w - p.dp(32f), p.dp(620f)).roundToInt()
        val barH = p.dpi(48f)
        val top = p.insets.top + p.dpi(14f)
        (bar.layoutParams as LayoutParams).apply {
            width = barW; height = barH; leftMargin = (w - barW) / 2; topMargin = top
        }
        bar.setPadding(p.dpi(20f), 0, p.dpi(20f), 0)
        field.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        field.setPadding(0, 0, 0, 0)
        field.includeFontPadding = false
        field.compoundDrawablePadding = p.dpi(8f)
        field.maxWidth = barW - p.dpi(40f)
        val cell = (p.iconSize * 1.6f)
        val listW = min(w - p.dp(24f), max(barW.toFloat(), p.dp(700f)))
        val cols = max(4, min(8, (listW / cell).toInt()))
        layoutManager.spanCount = cols
        val side = ((w - listW) / 2f).roundToInt()
        // The list starts just under the search bar and ends there: results never scroll over it,
        // they fade out over the first few dp instead of being cut.
        (list.layoutParams as LayoutParams).topMargin = top + barH + p.dpi(2f)
        list.setPadding(side, p.dpi(14f), side, max(imeBottom, p.insets.bottom) + p.dpi(16f))
        fadeLen = p.dp(36f)
        fadePaint.shader = LinearGradient(0f, 0f, 0f, fadeLen, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        list.requestLayout()
        bar.requestLayout()
    }

    // ---------------------------------------------------------------- data

    private fun visibleApps() = L.repo.apps.values.filter { L.model.searchHidden || it.key !in L.model.hidden }

    private fun refresh() {
        val q = field.text.toString().trim().lowercase()
        val out = ArrayList<Row>()
        if (q.isEmpty()) {
            val all = visibleApps()
            val top = all.filter { (L.model.launchCounts[it.key] ?: 0) > 0 }
                .sortedByDescending { L.model.launchCounts[it.key] ?: 0 }.take(layoutManager.spanCount)
            // Only suggestions before typing; the full app list is left out for now.
            if (top.isNotEmpty()) {
                out += Row.Header(t("建议", "Suggestions"))
                top.forEach { out += Row.App(it.key) }
            }
        } else {
            val scored = visibleApps().mapNotNull { a -> score(a, q)?.let { a to it } }
                .sortedWith(compareBy({ it.second }, { it.first.label.length }))
            if (scored.isNotEmpty()) out += Row.Header(t("App", "Apps"))
            scored.forEach { out += Row.App(it.first.key) }
            out += Row.Web(field.text.toString().trim())
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    /** Lower is better; null when no match. Handles pinyin and initials for CJK names. */
    private fun score(a: AppInfo, q: String): Int? {
        var best: Int? = null
        for ((i, k) in a.searchKeys.withIndex()) {
            val s = when {
                k == q -> 0
                k.startsWith(q) -> 1
                k.split(' ').any { it.startsWith(q) } -> 2
                k.contains(q) -> 3
                else -> null
            } ?: continue
            val v = s * 3 + i
            if (best == null || v < best) best = v
        }
        return best
    }

    private fun launchFirst() {
        val first = rows.firstOrNull { it is Row.App } as? Row.App
        if (first != null) {
            L.repo[first.key]?.let { L.launchApp(it, null) }
        } else (rows.firstOrNull { it is Row.Web } as? Row.Web)?.let { webSearch(it.q) }
    }

    private fun webSearch(q: String) {
        runCatching {
            L.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        close(animate = false)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.App -> 0; is Row.Header -> 1; is Row.Web -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val p = L.profile!!
            val v: View = when (type) {
                0 -> IconView(L, AppItem(0, ""), Role.FINDER).also {
                    L.configureIcon(it, Role.FINDER)
                    it.layoutParams = RecyclerView.LayoutParams(-1, (p.iconSize + p.labelGap + p.labelSize * 1.3f + p.dp(22f)).roundToInt())
                }
                else -> TextView(L).apply {
                    setTextColor(if (type == 2) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (type == 2) 16f else 14f)
                    paint.isFakeBoldText = true
                    setPadding(p.dpi(12f), p.dpi(14f), p.dpi(12f), p.dpi(8f))
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.App -> (h.itemView as IconView).apply {
                    item = itemCache.getOrPut(r.key) { AppItem(Ids.new(), r.key) }
                    L.configureIcon(this, Role.FINDER)
                    invalidate()
                }
                is Row.Header -> (h.itemView as TextView).apply { text = r.text; setOnClickListener(null) }
                is Row.Web -> (h.itemView as TextView).apply {
                    text = t("在网页中搜索 “${r.q}”", "Search the web for “${r.q}”")
                    setOnClickListener { webSearch(r.q) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- open / close

    private fun prepare() {
        if (visibility != VISIBLE) {
            layoutContents()
            field.setText("")
            refresh()
            list.scrollToPosition(0)
            visibility = VISIBLE
        }
    }

    fun beginPull() { prepare(); progress.cancel() }

    fun pull(dy: Float) {
        val d = L.profile?.dp(220f) ?: 500f
        val raw = dy / d
        progress.snap(if (raw > 1f) 1f + (raw - 1f) * 0.15f else raw.coerceAtLeast(0f))
    }

    fun endPull(vy: Float) {
        val d = L.profile?.dp(220f) ?: 500f
        if (progress.value > 0.4f || vy > 900f) open(vy / d) else close(velocity = vy / d)
    }

    fun open(velocity: Float = 0f) {
        prepare()
        progress.animateTo(1f, velocity)
        field.requestFocus()
        field.post {
            L.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
            windowInsetsController?.show(WindowInsets.Type.ime())
        }
    }

    private fun hideIme() {
        L.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    fun close(animate: Boolean = true, velocity: Float = 0f) {
        if (visibility != VISIBLE) return
        hideIme()
        field.clearFocus()
        if (!animate) { progress.snap(0f); visibility = GONE; return }
        progress.animateTo(0f, velocity) { visibility = GONE }
    }

    private fun apply(t: Float) {
        val p = L.profile
        val a = t.coerceIn(0f, 1f)
        scrim.alpha = a
        bar.alpha = a
        bar.translationY = lerp(-(p?.dp(36f) ?: 90f), 0f, t.coerceAtMost(1.2f))
        val s = lerp(0.9f, 1f, a)
        bar.scaleX = s; bar.scaleY = s
        list.alpha = ((t - 0.2f) / 0.8f).coerceIn(0f, 1f)
        list.translationY = lerp(p?.dp(40f) ?: 100f, 0f, a)
        L.setBackdrop(a, BackdropOwner.FINDER)
        L.setChromeRecede(a)
    }

    // ---------------------------------------------------------------- swipe down to close

    private val touchSlop = ViewConfiguration.get(L).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var closePull = false
    private var pullStartY = 0f
    private var vt: VelocityTracker? = null

    /**
     * A downward swipe closes the Finder whenever the list is already at the top (from the
     * search bar or the results alike). It follows the finger and can be pulled back open.
     */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; closePull = false
                vt?.recycle(); vt = VelocityTracker.obtain()
                vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (dy > touchSlop && dy > Math.abs(dx) * 1.2f && !list.canScrollVertically(-1)) {
                    closePull = true
                    pullStartY = e.y
                    progress.cancel()
                    hideIme()
                    field.clearFocus()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!closePull) return super.onTouchEvent(e)
        vt?.addMovement(e)
        val d = L.profile?.dp(260f) ?: 600f
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> progress.snap((1f - (e.y - pullStartY) / d).coerceIn(0f, 1f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                closePull = false
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (e.actionMasked == MotionEvent.ACTION_UP && (progress.value < 0.65f || vy > 800f)) {
                    close(velocity = -vy / d)
                } else {
                    progress.animateTo(1f, -vy / d)
                }
            }
        }
        return true
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { close(); return true }
        return super.dispatchKeyEvent(e)
    }
}

/** Cursor, selection handles and highlight in white (the text colour), not the theme accent. */
fun EditText.useWhiteCursor() {
    val d = resources.displayMetrics.density
    textCursorDrawable = GradientDrawable().apply {
        setColor(Color.WHITE); cornerRadius = d; setSize((2 * d).toInt(), 1)
    }
    textSelectHandle?.setTint(Color.WHITE)
    textSelectHandleLeft?.setTint(Color.WHITE)
    textSelectHandleRight?.setTint(Color.WHITE)
    highlightColor = 0x59FFFFFF
}
