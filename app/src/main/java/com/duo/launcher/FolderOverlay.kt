package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS-style open folder: blurred background, glass panel that grows out of the folder icon.
 * As on the iPhone Duo the panel is a fixed 3x3 window (more apps scroll), spaced like the Home
 * grid, and in landscape it opens over the page that holds the folder rather than mid-screen.
 */
@SuppressLint("ViewConstructor")
class FolderOverlay(private val L: MainActivity) : FrameLayout(L) {
    var folder: FolderItem? = null
        private set
    val isOpen get() = folder != null
    /** Working list while open; written back to the folder on every change. */
    val items = ArrayList<HomeItem>()

    private val scrim = View(L).apply { setBackgroundColor(0x33000000) }
    private val panel = FrameLayout(L)
    private val scroll = ScrollView(L).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    val grid = FolderGrid(L)
    private val title = EditText(L)
    private val source = RectF()
    private val target = RectF()
    private val progress = SpringValue(0f, stiffness = 420f, damping = 0.86f) { apply(it) }
    private var sourceView: IconView? = null
    private var closing = false
    private var finishClose: (() -> Unit)? = null

    init {
        visibility = GONE
        addView(scrim, LayoutParams(-1, -1))
        scroll.addView(grid, LayoutParams(-1, -2))
        panel.addView(scroll)
        addView(panel)
        addView(title)
        panel.clipToOutline = true
        title.apply {
            background = null
            setTextColor(Color.WHITE)
            useWhiteCursor()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> commitName(); clearFocus(); hideIme(); true }
            setOnFocusChangeListener { _, has -> if (!has) commitName() }
        }
        scrim.setOnClickListener { close() }
        isClickable = true
    }

    private fun hideIme() {
        L.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun commitName() {
        val f = folder ?: return
        val n = title.text.toString().trim()
        if (n.isNotEmpty() && n != f.name) { f.name = n; L.viewFor(f, Role.HOME).invalidate(); L.save() }
    }

    fun open(icon: IconView) {
        val f = icon.item as? FolderItem ?: return
        val p = L.profile ?: return
        if (isOpen) return
        folder = f
        sourceView = icon
        closing = false
        finishClose = null
        panel.alpha = 1f
        items.clear()
        f.apps.forEach { items += AppItem(Ids.new(), it) }
        title.setText(f.name)

        val cols = p.folderCols
        // Same column pitch as the landscape Home grid (88pt) and rows of 103pt.
        val cellW = p.r(88f).roundToInt()
        val cellH = p.r(103f).roundToInt()
        grid.cols = cols; grid.cellW = cellW; grid.cellH = cellH
        val rowsAll = max(1, ceil(items.size / cols.toFloat()).toInt())
        val rows = cols
        val padX = p.r(13f).roundToInt()
        val padY = p.r(18f).roundToInt()
        val pw = cols * cellW + padX * 2
        val ph = rows * cellH + padY * 2
        // One spare row only when the folder scrolls, as the drop target for a dragged icon.
        grid.layoutParams = LayoutParams(cols * cellW, max(rowsAll + if (rowsAll >= rows) 1 else 0, rows) * cellH)
        scroll.layoutParams = LayoutParams(cols * cellW, rows * cellH).apply { leftMargin = padX; topMargin = padY }
        iconRectInRoot(icon, source)
        val W = L.root.width.toFloat()
        // Over the folder's own page; a folder in the dock opens over the grid as a whole.
        var cx = (p.pagesRect.left + p.pagesRect.right) / 2f
        if (p.landscape && icon.role == Role.HOME) {
            val firstPageRight = p.pagesRect.left + p.pageW + p.pageGap / 2f
            cx = if (source.centerX() < firstPageRight) p.pagesRect.left + p.pageW / 2f
            else p.pagesRect.left + p.pageStride + p.pageW / 2f
        }
        cx = cx.coerceIn(pw / 2f + p.r(8f), W - pw / 2f - p.r(8f))
        // The title sits above the panel: keep the pair, not the panel alone, centred.
        val cy = (p.insets.top + L.root.height - p.insets.bottom) / 2f + p.r(14f)
        target.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)
        panel.layoutParams = LayoutParams(pw, ph).apply { leftMargin = target.left.roundToInt(); topMargin = target.top.roundToInt() }
        panel.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x3DFFFFFF, 0x26FFFFFF),
        ).apply { cornerRadius = p.cornerRadius; setStroke(p.dpi(1f), 0x59FFFFFF) }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.r(30f))
        title.layoutParams = LayoutParams(pw, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = target.left.roundToInt(); topMargin = (target.top - p.r(70f)).roundToInt()
        }
        grid.setItems(items)
        visibility = VISIBLE
        icon.visibility = INVISIBLE
        progress.animateTo(1f)
    }

    private fun iconRectInRoot(icon: IconView, out: RectF) {
        val loc = IntArray(2)
        icon.getLocationInWindow(loc)
        icon.iconRect(out)
        out.offset(loc[0].toFloat(), loc[1].toFloat())
    }

    fun onItemsChanged() {
        val f = folder ?: return
        f.apps.clear()
        items.forEach { if (it is AppItem) f.apps += it.key }
        grid.setItems(items)
        L.viewFor(f, Role.HOME).invalidate()
    }

    fun close(animate: Boolean = true) {
        val f = folder ?: return
        title.clearFocus(); hideIme(); commitName()
        onItemsChanged()
        // Fly back to wherever the folder icon is now.
        val v = L.viewFor(f, Role.HOME) as? IconView
        if (v != null && v.isAttachedToWindow) { iconRectInRoot(v, source); sourceView = v }
        val finish = {
            finishClose = null
            closing = false
            progress.snap(0f)   // also clears the backdrop blur
            panel.alpha = 1f
            visibility = GONE
            sourceView?.visibility = VISIBLE
            sourceView = null
            folder = null
            items.forEach { L.forgetView(it) }
            items.clear()
            grid.setItems(emptyList())
            L.model.dissolveSmallFolders()
            L.model.normalize(keepEmptyPages = false)
            L.rebindAll()
            L.save()
        }
        if (!animate) { finish(); return }
        closing = true
        finishClose = finish
        progress.animateTo(0f) { finishClose?.invoke() }
    }

    private fun apply(t: Float) {
        val tt = t.coerceAtLeast(0f)
        if (closing) {
            // Hand over to the real folder icon for the last stretch instead of leaving an empty
            // glass square on it while the spring's long tail settles.
            if (tt < 0.3f) {
                sourceView?.visibility = VISIBLE
                panel.alpha = (tt / 0.3f).coerceIn(0f, 1f)
            }
            if (tt < 0.04f) { finishClose?.invoke(); return }
        }
        scrim.alpha = tt.coerceAtMost(1f)
        title.alpha = ((tt - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val s = lerp(source.width() / max(1f, target.width()), 1f, tt)
        val sy = lerp(source.height() / max(1f, target.height()), 1f, tt)
        panel.pivotX = target.width() / 2f
        panel.pivotY = target.height() / 2f
        panel.scaleX = s
        panel.scaleY = sy
        panel.translationX = lerp(source.centerX() - target.centerX(), 0f, tt)
        panel.translationY = lerp(source.centerY() - target.centerY(), 0f, tt)
        scroll.alpha = ((tt - 0.15f) / 0.6f).coerceIn(0f, 1f)
        L.setBackdrop(tt.coerceAtMost(1f), BackdropOwner.FOLDER)
        if (t <= 0.001f && !progress.running) L.setBackdrop(0f, BackdropOwner.FOLDER)
    }

    fun panelRectInRoot(out: RectF): RectF {
        out.set(target)
        out.inset(-(L.profile?.dp(16f) ?: 40f), -(L.profile?.dp(16f) ?: 40f))
        return out
    }
}
