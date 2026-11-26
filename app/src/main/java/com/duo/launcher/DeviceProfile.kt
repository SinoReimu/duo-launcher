package com.duo.launcher

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode { COVER, INNER_PORTRAIT, INNER_LANDSCAPE }

/**
 * All geometry for one window size. Pages are always COLS x ROWS; what changes per mode is
 * how many pages are visible (2 in inner landscape), where the dock sits, and cell sizes.
 *
 * The proportions follow the iPhone Duo Home Screen as measured in the simulator
 * (tools/duoref): 60pt icons with 12pt labels, a hugging glass dock capsule, and in the two
 * rail modes one spacing rule, "left margin = gutter between pages = gap before the dock".
 * They are not copied point for point: the Fold 8 screens have other aspect ratios, so every
 * mode solves its own column pitch from those ratios and spreads the height over the six rows.
 */
class DeviceProfile(
    val width: Int,
    val height: Int,
    val insets: Rect,
    val density: Float,
    smallestWidthDp: Int,
    showLabels: Boolean,
) {
    val mode: Mode = when {
        smallestWidthDp < 560 -> Mode.COVER
        width > height -> Mode.INNER_LANDSCAPE
        else -> Mode.INNER_PORTRAIT
    }
    val landscape get() = mode == Mode.INNER_LANDSCAPE
    val inner get() = mode != Mode.COVER

    fun dp(v: Float) = v * density
    fun dpi(v: Float) = (v * density).roundToInt()

    /**
     * Pixels per reference point. The reference screens are 951x669 (open, landscape), 669x951
     * (open, portrait) and 466x678 (closed); the layout is scaled so both sides fit, within limits
     * so a changed display size cannot make icons tiny or huge.
     */
    val unit: Float = run {
        val (rw, rh) = when (mode) {
            Mode.INNER_LANDSCAPE -> 951f to 669f
            Mode.INNER_PORTRAIT -> 669f to 951f
            Mode.COVER -> 466f to 678f
        }
        density * min(width / density / rw, height / density / rh).coerceIn(0.84f, 1.12f)
    }

    /** A length given in reference points, in pixels. */
    fun r(v: Float) = v * unit

    val visiblePages = if (landscape) 2 else 1

    /** Area in which the visible pages sit (window coordinates). */
    val pagesRect = RectF()
    val cellW: Float
    val cellH: Float
    val pageW: Float
    val pageH: Float
    /** Horizontal distance between page origins. */
    val pageStride: Float
    /**
     * Distance from the last visible slot to where the next page waits. In landscape that is
     * past the screen edge, further than one stride: nothing shows under the dock at rest, and
     * the incoming page slides in whole from behind it.
     */
    val pageStrideIn: Float
    /**
     * Distance from the first visible slot to where the previous page waits: just far enough
     * left that its last column, labels included, is off the screen.
     */
    val pageStrideOut: Float
    /** Gutter between the two side-by-side pages in inner landscape. */
    var pageGap = 0f
        private set

    val iconSize: Int = r(60f).roundToInt()
    val labelSize: Float = r(12f)
    val labelGap: Float
    val labelH: Float = if (showLabels) labelSize * 1.3f else 0f

    /** Vertical dock in a rail on the right: inner landscape and the cover screen. */
    val dockVertical = mode != Mode.INNER_PORTRAIT
    val dockIconSize: Int = if (dockVertical) r(48f).roundToInt() else iconSize
    /** Inner padding of the dock capsule and spacing between dock icons. */
    val dockPad = r(if (dockVertical) 12f else 16f)
    val dockGap = r(16f)
    /** The dock capsule when it is full; with fewer icons the capsule shrinks around them. */
    val dockRect = RectF()
    val indicatorRect = RectF()
    /** The round search button: bottom of the rail, or right of the horizontal dock. */
    val searchRect = RectF()
    /** Centre line of the rail (dock, search and edit buttons), or of the screen in portrait. */
    val railCenterX: Float

    val folderCols = 3
    val cornerRadius = r(36f)

    init {
        val W = width.toFloat()
        val H = height.toFloat()
        val icon = iconSize.toFloat()
        val search = r(48f)
        val topIcon: Float
        val bottomIcon: Float
        val pitch: Float
        val rowPitch: Float
        fun rows(top: Float, bottom: Float, maxRatio: Float, p: Float): Triple<Float, Float, Float> {
            // Spread six rows over the band; on a very tall screen stop growing and centre instead.
            val free = (bottom - top - icon) / (ROWS - 1)
            val rp = min(free, p * maxRatio)
            val slack = (free - rp) * (ROWS - 1)
            return Triple(rp, top + slack / 2f, bottom - slack / 2f)
        }

        if (dockVertical) {
            val capsuleW = dockIconSize + dockPad * 2
            val capsuleRight = W - insets.right - r(12f)
            val capsuleLeft = capsuleRight - capsuleW
            railCenterX = capsuleRight - capsuleW / 2f
            val avail = capsuleLeft - insets.left
            val top0 = max(insets.top + r(20f), r(54f))
            val bottom0 = H - max(insets.bottom + r(48f), r(65f))
            val gridLeft: Float
            if (mode == Mode.INNER_LANDSCAPE) {
                // avail = 2 pages (3 pitches + icon) + 3 equal spaces, each K icon gaps wide.
                val k = 2.6f
                pitch = (avail - 2 * icon + 3 * k * icon) / (6 + 3 * k)
                val space = k * (pitch - icon)
                gridLeft = insets.left + space
                pageGap = icon + space - pitch
            } else {
                // One page: side margins equal to the gap between icons.
                pitch = (avail + icon) / (COLS + 1)
                gridLeft = insets.left + (pitch - icon)
            }
            // Rows a little taller than columns are wide, as on the Duo; the closed Fold is much
            // taller than the closed Duo, so its rows may open up a bit more.
            val (rp, t, b) = rows(top0, bottom0, if (landscape) 1.18f else 1.22f, pitch)
            rowPitch = rp; topIcon = t; bottomIcon = b
            cellW = pitch
            pageW = pitch * COLS
            val left = gridLeft - (pitch - icon) / 2f
            if (landscape) {
                pageStride = pageW + pageGap
                // The waiting page's first icon sits 15pt past the right edge.
                pageStrideIn = W + r(15f) - (gridLeft + pageStride)
                pageStrideOut = left + pageW + r(2f)
                pagesRect.set(left, 0f, left + 2 * pageW + pageGap, 0f)
            } else {
                pageStride = W
                pageStrideIn = W
                pageStrideOut = W
                pagesRect.set(left, 0f, left + pageW, 0f)
            }
            // The dock is centred on the icon grid, the search button sits in the rail's corner.
            val dockH = DOCK_MAX * dockIconSize + (DOCK_MAX - 1) * dockGap + dockPad * 2
            val cy = (topIcon + bottomIcon) / 2f
            dockRect.set(capsuleLeft, cy - dockH / 2f, capsuleRight, cy + dockH / 2f)
            val searchBottom = H - max(insets.bottom + r(10f), r(24f))
            searchRect.set(railCenterX - search / 2f, searchBottom - search, railCenterX + search / 2f, searchBottom)
        } else {
            railCenterX = W / 2f
            val avail = W - insets.left - insets.right
            // Side margins about three icon gaps wide: the grid floats in the middle.
            val k = 3.17f
            pitch = (avail - icon + 2 * k * icon) / (COLS - 1 + 2 * k)
            val gridLeft = insets.left + k * (pitch - icon)
            val capsuleH = icon + dockPad * 2
            val capsuleBottom = H - max(insets.bottom + r(6f), r(10f))
            val capsuleTop = capsuleBottom - capsuleH
            val dockW = DOCK_MAX * icon + (DOCK_MAX - 1) * dockGap + dockPad * 2
            dockRect.set(W / 2f - dockW / 2f, capsuleTop, W / 2f + dockW / 2f, capsuleBottom)
            val top0 = max(insets.top + r(44f), r(82f))
            val bottom0 = capsuleTop - r(119f)
            val (rp, t, b) = rows(top0, bottom0, 1.2f, pitch)
            rowPitch = rp; topIcon = t; bottomIcon = b
            cellW = pitch
            pageW = pitch * COLS
            val left = gridLeft - (pitch - icon) / 2f
            pageStride = W
            pageStrideIn = W
            pageStrideOut = W
            pagesRect.set(left, 0f, left + pageW, 0f)
            val sx = W - insets.right - r(24f)
            val scy = (capsuleTop + capsuleBottom) / 2f
            searchRect.set(sx - search, scy - search / 2f, sx, scy + search / 2f)
        }

        // Taller rows get a little more air between icon and label (5pt at 98pt rows, 11 at 118).
        labelGap = r(5f) + ((rowPitch - r(98f)) * 0.3f).coerceIn(0f, r(6f))
        cellH = rowPitch
        pageH = rowPitch * ROWS
        // Rows are placed so the first icon's top lands on topIcon (as laid out with labels shown).
        val bandTop = topIcon - ((rowPitch - icon - labelGap - labelSize * 1.3f) / 2f).coerceAtLeast(0f)
        pagesRect.top = bandTop
        pagesRect.bottom = bandTop + pageH

        // Page dots: between the last row's label and whatever is below (dock or screen edge).
        val below = if (dockVertical) H - insets.bottom else dockRect.top
        val labelBottom = bottomIcon + labelGap + labelSize * 1.4f
        val dotsCy = (labelBottom + below) / 2f
        val dotsH = r(36f)
        indicatorRect.set(pagesRect.left, dotsCy - dotsH / 2f, pagesRect.right, dotsCy + dotsH / 2f)
    }

    /** Offset of the icon from the top of its cell, so icon+label are vertically centred. */
    val iconTop: Float get() = ((cellH - iconSize - labelGap - labelH) / 2f).coerceAtLeast(0f)

    /** Frame for an item at a cell, relative to the page origin. */
    fun cellFrame(cell: Int, spanX: Int, spanY: Int, out: Rect) {
        val c = cell % COLS
        val r = cell / COLS
        out.set(
            (c * cellW).roundToInt(), (r * cellH).roundToInt(),
            ((c + spanX) * cellW).roundToInt(), ((r + spanY) * cellH).roundToInt(),
        )
    }

    /** Widgets line up with icon edges like on iOS. */
    val widgetInsetX: Int get() = ((cellW - iconSize) / 2f).roundToInt()
    val widgetInsetTop: Int get() = iconTop.roundToInt()
    val widgetInsetBottom: Int get() = (cellH - iconTop - iconSize).roundToInt().coerceAtLeast(0) / 2

    fun sameShapeAs(o: DeviceProfile?) = o != null && o.width == width && o.height == height &&
        o.insets == insets && o.mode == mode
}
