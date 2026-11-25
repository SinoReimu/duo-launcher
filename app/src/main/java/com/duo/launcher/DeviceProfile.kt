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

    val visiblePages = if (landscape) 2 else 1

    /** Area in which the visible pages sit (window coordinates). */
    val pagesRect = RectF()
    val cellW: Float
    val cellH: Float
    val pageW: Float
    val pageH: Float
    /** Horizontal distance between page origins. */
    val pageStride: Float
    /** Gutter between the two side-by-side pages in inner landscape. */
    var pageGap = 0f
        private set

    val iconSize: Int
    val labelSize: Float = dp(if (mode == Mode.COVER) 11.5f else 12.5f)
    val labelGap: Float = dp(5f)
    val labelH: Float = if (showLabels) labelSize * 1.3f else 0f

    /** Vertical dock on the right: inner landscape and the cover screen (like iPhone Duo folded). */
    val dockVertical = mode != Mode.INNER_PORTRAIT
    val dockIconSize: Int
    val dockRect = RectF()
    val indicatorRect = RectF()
    /** Right edge for page clipping in landscape so pages slide behind the dock. */
    val workspaceClipRight: Int

    val folderCols = if (inner) 4 else 3
    val cornerRadius = dp(if (inner) 34f else 30f)

    /** Inner padding of the dock panel and spacing between dock icons. */
    val dockPad = dp(if (mode == Mode.COVER) 12f else 18f)
    val dockGap = dp(if (mode == Mode.COVER) 16f else 20f)

    init {
        // Tuned for balance on the Fold 8 rather than copied from iOS: comfortable outer
        // margins, icon gaps of roughly a third of an icon, and horizontal spacing that matches
        // the vertical rhythm of the rows.
        val W = width.toFloat()
        val H = height.toFloat()
        val labelBlock = labelGap + labelH
        var icon: Float
        when (mode) {
            Mode.INNER_LANDSCAPE -> {
                val gridTop = insets.top + dp(14f)
                val gridBottom = H - insets.bottom - dp(40f)
                cellH = (gridBottom - gridTop) / ROWS
                // Dock: 14dp from the right edge, generous inner padding.
                val dockRight = W - insets.right - dp(14f)
                val estIcon = min((cellH - labelBlock) * 0.8f, dp(60f))
                val dIcon0 = estIcon * 0.9f
                val dockLeft = dockRight - (dIcon0 + dockPad * 2)
                val left = insets.left + dp(44f)
                val right = dockLeft - dp(28f)
                pageGap = dp(48f)
                pageW = (right - left - pageGap) / 2f
                cellW = pageW / COLS
                icon = min(cellW * 0.62f, (cellH - labelBlock) * 0.86f).coerceAtMost(dp(60f))
                pageStride = pageW + pageGap
                pageH = gridBottom - gridTop
                pagesRect.set(left, gridTop, right, gridBottom)
                val dIcon = icon * 0.9f
                val dockW = dIcon + dockPad * 2
                val dockH = DOCK_MAX * dIcon + (DOCK_MAX - 1) * dockGap + dockPad * 2
                val cy = (gridTop + gridBottom) / 2f
                dockRect.set(dockRight - dockW, cy - dockH / 2f, dockRight, cy + dockH / 2f)
                // No clipping: pages run under the dock to the screen edge.
                workspaceClipRight = width
                indicatorRect.set(left, gridBottom, right, H - insets.bottom)
            }
            Mode.COVER -> {
                // 4x6 grid on the left, a narrow vertical dock on the right, centred on the grid.
                val gridTop = insets.top + dp(12f)
                val gridBottom = H - insets.bottom - dp(30f)
                cellH = (gridBottom - gridTop) / ROWS
                val dockRight = W - insets.right - dp(10f)
                val estIcon = min((cellH - labelBlock) * 0.86f, dp(58f))
                val dockLeft = dockRight - (estIcon * 0.86f + dockPad * 2)
                val left = insets.left + dp(8f)
                val right = dockLeft - dp(6f)
                pageW = right - left
                cellW = pageW / COLS
                icon = min(cellW * 0.66f, (cellH - labelBlock) * 0.86f).coerceAtMost(dp(58f))
                pageH = gridBottom - gridTop
                pagesRect.set(left, gridTop, right, gridBottom)
                pageStride = W
                val dIcon = icon * 0.86f
                val dockW = dIcon + dockPad * 2
                val dockH = DOCK_MAX * dIcon + (DOCK_MAX - 1) * dockGap + dockPad * 2
                val cy = (gridTop + gridBottom) / 2f
                dockRect.set(dockRight - dockW, cy - dockH / 2f, dockRight, cy + dockH / 2f)
                workspaceClipRight = width
                indicatorRect.set(left, gridBottom, right, H - insets.bottom)
            }
            Mode.INNER_PORTRAIT -> {
                val cover = false
                val iconCap = dp(62f)
                // Dock first (its height depends on the icon), then rows, then columns.
                val estIcon = iconCap
                val dockBottom = H - insets.bottom - dp(if (cover) 10f else 14f)
                val dockTop = dockBottom - (estIcon + dockPad * 2)
                val gridTop = insets.top + dp(if (cover) 14f else 20f)
                val gridBottom = dockTop - dp(34f)
                cellH = (gridBottom - gridTop) / ROWS
                icon = min(iconCap, (cellH - labelBlock) * 0.84f)
                val maxCellW = (W - insets.left - insets.right - dp(if (cover) 24f else 48f)) / COLS
                // Columns roughly as far apart as rows, so spacing reads evenly both ways.
                cellW = min(maxCellW, cellH * (if (cover) 1.12f else 1.05f))
                pageW = cellW * COLS
                pageH = gridBottom - gridTop
                val left = (W - pageW) / 2f
                pagesRect.set(left, gridTop, left + pageW, gridBottom)
                pageStride = W
                workspaceClipRight = width
                val dockH = icon + dockPad * 2
                // Dock slots line up with the grid columns.
                dockRect.set(left, dockBottom - dockH, left + pageW, dockBottom)
                indicatorRect.set(left, gridBottom, left + pageW, dockRect.top)
            }
        }
        iconSize = icon.roundToInt()
        dockIconSize = when (mode) {
            Mode.INNER_LANDSCAPE -> (icon * 0.9f).roundToInt()
            Mode.COVER -> (icon * 0.86f).roundToInt()
            Mode.INNER_PORTRAIT -> iconSize
        }
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
