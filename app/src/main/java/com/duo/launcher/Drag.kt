package com.duo.launcher

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.hypot

/** Window root. While a drag is active it owns the whole touch stream, bypassing children. */
@SuppressLint("ViewConstructor")
class DragLayer(private val L: MainActivity) : FrameLayout(L) {
    private var childrenCancelled = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (L.drag.dragging) {
            if (!childrenCancelled) {
                val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c)
                c.recycle()
                childrenCancelled = true
            }
            L.drag.onTouch(e)
            return true
        }
        childrenCancelled = false
        return super.dispatchTouchEvent(e)
    }
}

/** The floating picture of what is being dragged, with a count badge for multi-select. */
@SuppressLint("ViewConstructor")
class DragImage(L: MainActivity, val bmp: Bitmap) : View(L) {
    var count = 1
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        if (count > 1) {
            // Stacked cards behind the primary icon.
            shadow.alpha = 110
            c.save(); c.rotate(-7f, width / 2f, height / 2f); c.drawBitmap(bmp, 0f, 0f, shadow); c.restore()
            if (count > 2) { c.save(); c.rotate(6f, width / 2f, height / 2f); c.drawBitmap(bmp, 0f, 0f, shadow); c.restore() }
        }
        c.drawBitmap(bmp, 0f, 0f, paint)
        if (count > 1) {
            val r = width * 0.17f
            c.drawCircle(width - r * 0.6f, r * 0.6f, r, badge)
            text.textSize = r * 1.15f
            c.drawText(count.toString(), width - r * 0.6f, r * 0.6f + text.textSize * 0.36f, text)
        }
    }
}

sealed class Zone {
    object Dock : Zone()
    data class Page(val index: Int) : Zone()
    object Folder : Zone()
}

private const val LIFT_SCALE = 1.12f

class DragController(private val L: MainActivity) {
    var dragging = false
        private set
    private val items = ArrayList<HomeItem>()
    private var image: DragImage? = null
    private val hidden = HashSet<View>()
    private var placeholder = PlaceholderItem(1, 1)
    private var phZone: Zone? = null
    private var offX = 0f
    private var offY = 0f
    private var activePointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var sourceRole = Role.HOME
    private val tmp = RectF()
    private val loc = IntArray(2)

    fun isHiddenForDrag(v: View) = v in hidden

    private fun itemOf(v: View): HomeItem? = when (v) {
        is IconView -> v.item
        is WidgetContainer -> v.item
        else -> null
    }

    private fun zoneList(z: Zone): ArrayList<HomeItem>? = when (z) {
        Zone.Dock -> L.model.dock
        is Zone.Page -> L.model.pages.getOrNull(z.index)
        Zone.Folder -> if (L.folder.isOpen) L.folder.items else null
    }

    private fun rebind(z: Zone?) {
        when (z) {
            null -> {}
            Zone.Dock -> L.dock.setItems(L.model.dock)
            is Zone.Page -> L.workspace.pages.getOrNull(z.index)?.setItems(L.model.pages[z.index])
            Zone.Folder -> L.folder.onItemsChanged()
        }
    }

    /** Bitmap of just the icon (no label, no badge), or the whole widget. */
    fun snapshot(v: View, outRect: RectF): Bitmap {
        v.getLocationInWindow(loc)
        if (v is IconView) {
            v.iconRect(tmp)
            val s = v.iconSize.coerceAtLeast(1)
            // Icons are HARDWARE bitmaps: record into a Picture so the copy is GPU-rendered too.
            val pic = android.graphics.Picture()
            val c = pic.beginRecording(s, s)
            c.translate(-tmp.left, -tmp.top)
            v.snapshotMode = true
            v.draw(c)
            v.snapshotMode = false
            pic.endRecording()
            val bmp = Bitmap.createBitmap(pic)
            outRect.set(loc[0] + tmp.left, loc[1] + tmp.top, loc[0] + tmp.right, loc[1] + tmp.bottom)
            return bmp
        }
        val pic = android.graphics.Picture()
        v.draw(pic.beginRecording(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1)))
        pic.endRecording()
        val bmp = Bitmap.createBitmap(pic)
        outRect.set(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
        return bmp
    }

    fun startDrag(v: View, rawX: Float, rawY: Float, downRawX: Float, downRawY: Float) {
        if (dragging) return
        finishDropAnimation()
        val item = itemOf(v) ?: return
        sourceRole = (v as? IconView)?.role ?: Role.HOME
        L.dismissContextMenu(immediate = true)
        L.performHaptic(HapticFeedbackConstants.DRAG_START)

        val r = RectF()
        val bmp = snapshot(v, r)
        L.root.getLocationOnScreen(loc)
        val img = DragImage(L, bmp)
        L.root.addView(img, FrameLayout.LayoutParams(bmp.width, bmp.height))
        img.translationX = r.left
        img.translationY = r.top
        offX = downRawX - loc[0] - r.left
        offY = downRawY - loc[1] - r.top
        image = img
        // Lifted look: a soft shadow under the icon shape, and a springy scale-up.
        img.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) {
                val radius = if (v is IconView) view.width * 0.23f else (L.profile?.dp(24f) ?: 60f)
                o.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        img.elevation = L.profile?.dp(14f) ?: 36f
        img.springScale(LIFT_SCALE, 520f, 0.62f)

        items.clear()
        placeholder = PlaceholderItem(item.spanX, item.spanY)
        phZone = null
        when (sourceRole) {
            Role.HOME, Role.DOCK -> {
                items += item
                val list = L.model.containerOf(item)
                val idx = list?.indexOfFirst { it === item } ?: -1
                if (list != null && idx >= 0) {
                    list[idx] = placeholder
                    phZone = if (list === L.model.dock) Zone.Dock else Zone.Page(L.model.pages.indexOf(list))
                }
            }
            Role.FOLDER -> {
                items += item
                val idx = L.folder.items.indexOfFirst { it === item }
                if (idx >= 0) { L.folder.items[idx] = placeholder; phZone = Zone.Folder }
            }
            Role.FINDER -> {
                items += AppItem(Ids.new(), (item as AppItem).key)
                L.finder.close(animate = true)
            }
        }
        dragging = true
        if (!L.editMode) L.enterEditMode()
        L.ensureTrailingPage()
        if (phZone == Zone.Folder) L.folder.onItemsChanged()
        L.rebindAll()
        activePointer = -1
        lastX = rawX - loc[0]; lastY = rawY - loc[1]
        moveTo(lastX, lastY)
    }

    // ---------------------------------------------------------------- touch

    fun onTouch(e: MotionEvent) {
        if (activePointer == -1) activePointer = e.getPointerId(e.actionIndex.coerceAtMost(e.pointerCount - 1))
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activePointer).takeIf { it >= 0 } ?: 0
                lastX = e.getX(i); lastY = e.getY(i)
                moveTo(lastX, lastY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Tap other icons with a second finger to add them to the stack (iOS multi-select).
                val i = e.actionIndex
                pickUpAt(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) drop()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
        }
    }

    private fun moveTo(x: Float, y: Float) {
        val img = image ?: return
        img.translationX = x - offX
        img.translationY = y - offY
        // What the user lines up is the lifted icon, not the finger (which is often on the label):
        // hover is judged at the icon's centre. Widgets keep the finger.
        if (items.firstOrNull() is WidgetItem) updateHover(x, y)
        else updateHover(x - offX + img.width / 2f, y - offY + img.height / 2f)
    }

    // ---------------------------------------------------------------- hover logic

    private var folderCandidate: IconView? = null
    private var folderArmed = false
    private val armFolder = Runnable {
        folderCandidate?.let {
            it.setHighlighted(true); folderArmed = true
            L.performHaptic(HapticFeedbackConstants.CLOCK_TICK)
            // The target grows and the dragged icon shrinks toward it, like iOS.
            it.springScale(1.12f, 500f, 0.6f)
            image?.springScale(0.86f, 500f, 0.75f)
        }
    }
    private var pendingZone: Zone? = null
    private var pendingIndex = -1
    private val applyPending = Runnable { pendingZone?.let { movePlaceholder(it, pendingIndex) } }
    private var edgeDir = 0
    private val edgeScroll = Runnable {
        val ws = L.workspace
        val target = ws.currentPage + edgeDir
        // Past the last page: open a new one, like iOS.
        if (edgeDir > 0 && target > ws.maxPos()) L.addPageForDrag()
        if (target in 0..ws.maxPos()) {
            ws.snapTo(target)
            L.performHaptic(HapticFeedbackConstants.CLOCK_TICK)
        }
        edgeDir = 0
    }
    private val leaveFolder = Runnable {
        if (L.folder.isOpen) {
            if (phZone == Zone.Folder) {
                L.folder.items.remove(placeholder); phZone = null
            }
            L.folder.close()
        }
    }
    private var leaveFolderPosted = false

    private fun setFolderCandidate(v: IconView?) {
        if (folderCandidate === v) return
        folderCandidate?.let {
            it.setHighlighted(false)
            if (folderArmed) { it.springScale(1f, 500f, 0.7f); image?.springScale(LIFT_SCALE, 500f, 0.7f) }
        }
        L.root.removeCallbacks(armFolder)
        folderArmed = false
        folderCandidate = v
        if (v != null) L.root.postDelayed(armFolder, 280)
    }

    private fun requestPlaceholder(z: Zone, index: Int, delay: Long = 130L) {
        if (phZone == z) {
            val cur = zoneList(z)?.indexOfFirst { it === placeholder } ?: -1
            if (cur == index) { cancelPending(); return }
        }
        if (pendingZone == z && pendingIndex == index) return
        pendingZone = z; pendingIndex = index
        L.root.removeCallbacks(applyPending)
        L.root.postDelayed(applyPending, delay)
    }

    private fun cancelPending() {
        pendingZone = null; pendingIndex = -1
        L.root.removeCallbacks(applyPending)
    }

    private fun movePlaceholder(z: Zone, index: Int) {
        val old = phZone
        old?.let { zoneList(it)?.removeAll { i -> i === placeholder } }
        val list = zoneList(z) ?: return
        list.add(index.coerceIn(0, list.size), placeholder)
        phZone = z
        pendingZone = null
        if (old != z) rebind(old)
        rebind(z)
        L.indicator.count = L.workspace.pages.size
    }

    private val allApps get() = items.all { it is AppItem }

    private fun updateHover(x: Float, y: Float) {
        val p = L.profile ?: return

        // 1. An open folder captures the drag while the finger is over its panel.
        if (L.folder.isOpen) {
            val panel = L.folder.panelRectInRoot(tmp)
            if (panel.contains(x, y) && allApps) {
                if (leaveFolderPosted) { L.root.removeCallbacks(leaveFolder); leaveFolderPosted = false }
                val g = L.folder.grid
                g.getLocationInWindow(loc)
                val lx = x - loc[0]; val ly = y - loc[1]
                val count = L.folder.items.count { it !is PlaceholderItem }
                requestPlaceholder(Zone.Folder, g.insertIndexAt(lx, ly, count))
            } else if (!leaveFolderPosted) {
                leaveFolderPosted = true
                L.root.postDelayed(leaveFolder, if (allApps) 280 else 0)
            }
            return
        }

        // Hysteresis: once over an icon, stay its folder target until clearly off it.
        folderCandidate?.let { c ->
            if (allApps && c.isAttachedToWindow && c.isShown && nearIconCenter(c, x, y, FOLDER_KEEP)) {
                edge(0); cancelPending(); return
            }
        }

        // 2. Dock.
        val dockR = RectF(p.dockRect).apply { inset(-p.dp(10f), -p.dp(10f)) }
        if (dockR.contains(x, y) && items.first() !is WidgetItem) {
            edge(0)
            val d = L.dock
            val lx = x - d.left; val ly = y - d.top
            val hit = d.itemIndexAt(lx, ly)
            val hv = (d.items.getOrNull(hit)?.let { L.viewFor(it, Role.DOCK) } as? IconView)
            if (hv != null && allApps && nearIconCenter(hv, x, y, FOLDER_ENTER)) {
                setFolderCandidate(hv); cancelPending(); return
            }
            setFolderCandidate(null)
            val count = L.model.dock.count { it !is PlaceholderItem }
            val already = phZone == Zone.Dock
            if (already || count + items.size <= DOCK_MAX) {
                requestPlaceholder(Zone.Dock, d.insertIndexAt(lx, ly, count))
            }
            return
        }

        // 3. Pages.
        val ws = L.workspace
        // Page-turn zones are the outer margins, never the first/last icon column.
        edge(
            when {
                // The finger, not the icon, reaching the margin turns the page.
                lastX < p.pagesRect.left + p.dp(6f) -> -1
                lastX > p.pagesRect.right - p.dp(6f) -> 1
                else -> 0
            },
        )
        val pi = ws.pageIndexAt(x)
        if (pi < 0) return
        val page = ws.pages[pi]
        val lx = x - page.left - page.translationX
        val ly = y - page.top
        val hit = page.itemIndexAt(lx, ly)
        val hitItem = page.items.getOrNull(hit)
        val hv = hitItem?.let { L.viewFor(it, Role.HOME) } as? IconView
        if (hv != null && allApps && nearIconCenter(hv, x, y, FOLDER_ENTER)) {
            setFolderCandidate(hv); cancelPending(); return
        }
        setFolderCandidate(null)
        // Over an icon's cell but not its centre: probably on the way onto it, so give the
        // reorder more time before that icon moves out of the way.
        requestPlaceholder(Zone.Page(pi), page.insertIndexForCell(page.cellAt(lx, ly)), if (hv != null) 300L else 130L)
    }

    private fun edge(dir: Int) {
        if (dir == edgeDir) return
        L.root.removeCallbacks(edgeScroll)
        edgeDir = dir
        if (dir != 0) L.root.postDelayed(edgeScroll, 520)
    }

    /** Folder target radius as a fraction of the icon size: to become one, and to stay one. */
    private val FOLDER_ENTER = 0.5f
    private val FOLDER_KEEP = 0.72f

    private fun nearIconCenter(v: IconView, x: Float, y: Float, frac: Float): Boolean {
        v.getLocationInWindow(loc)
        v.iconRect(tmp)
        val cx = loc[0] + tmp.centerX(); val cy = loc[1] + tmp.centerY()
        return hypot(x - cx, y - cy) < v.iconSize * frac
    }

    // ---------------------------------------------------------------- multi-select

    private fun pickUpAt(x: Float, y: Float) {
        val target = findIconAt(x, y) ?: return
        val item = target.item
        if (items.any { it === item } || item is WidgetItem) return
        if (items.first() is WidgetItem) return
        val list = L.model.containerOf(item) ?: return
        val r = RectF()
        val bmp = snapshot(target, r)
        list.removeAll { it === item }
        items += item
        L.rebindAll()
        image?.count = items.size
        L.performHaptic(HapticFeedbackConstants.CLOCK_TICK)
        // Fly a ghost from the picked icon into the stack.
        val ghost = DragImage(L, bmp)
        L.root.getLocationOnScreen(loc)
        L.root.addView(ghost, FrameLayout.LayoutParams(bmp.width, bmp.height))
        ghost.translationX = r.left; ghost.translationY = r.top
        val img = image ?: return
        ghost.springTo(DynamicAnimation.TRANSLATION_X, img.translationX, 700f, 0.9f)
        ghost.springTo(DynamicAnimation.TRANSLATION_Y, img.translationY, 700f, 0.9f) { L.root.removeView(ghost) }
        ghost.springTo(DynamicAnimation.ALPHA, 0.3f, 700f, 1f)
    }

    private fun findIconAt(x: Float, y: Float): IconView? {
        val candidates = ArrayList<View>()
        L.workspace.pages.filter { it.visibility == View.VISIBLE }.forEach { pg ->
            for (i in 0 until pg.childCount) candidates += pg.getChildAt(i)
        }
        for (i in 0 until L.dock.childCount) candidates += L.dock.getChildAt(i)
        for (v in candidates) {
            if (v !is IconView || v.visibility != View.VISIBLE) continue
            v.getLocationInWindow(loc)
            if (x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height) return v
        }
        return null
    }

    // ---------------------------------------------------------------- drop

    private var dropping: Runnable? = null

    private fun drop() {
        if (!dragging) return
        L.root.removeCallbacks(applyPending)
        L.root.removeCallbacks(edgeScroll)
        L.root.removeCallbacks(leaveFolder)
        leaveFolderPosted = false
        edgeDir = 0
        pendingZone?.let { if (pendingIndex >= 0) movePlaceholder(it, pendingIndex) }
        cancelPending()
        dragging = false
        activePointer = -1

        val candidate = folderCandidate
        val armed = folderArmed
        setFolderCandidate(null)

        var landing: View? = null
        if (candidate != null && armed && allApps) {
            landing = dropIntoFolder(candidate)
        } else {
            var z = phZone
            if (z == null || zoneList(z) == null) {
                // Nothing hovered yet (e.g. straight from Finder): use the visible page.
                val pi = L.workspace.currentPage.coerceIn(0, L.model.pages.size - 1)
                L.model.pages[pi].add(placeholder); z = Zone.Page(pi)
            }
            val list = zoneList(z)!!
            val idx = list.indexOfFirst { it === placeholder }.coerceAtLeast(0)
            list.removeAll { it === placeholder }
            val toPlace = ArrayList(items)
            if (z == Zone.Dock) {
                val room = DOCK_MAX - list.size
                val fit = toPlace.take(room.coerceAtLeast(0))
                list.addAll(idx.coerceAtMost(list.size), fit)
                toPlace.removeAll(fit.toSet())
                toPlace.forEach { L.model.pages[L.workspace.currentPage.coerceIn(0, L.model.pages.size - 1)].add(it) }
            } else if (z == Zone.Folder) {
                list.addAll(idx.coerceAtMost(list.size), toPlace.filterIsInstance<AppItem>())
                L.folder.onItemsChanged()
            } else {
                list.addAll(idx.coerceAtMost(list.size), toPlace)
            }
            L.model.normalize(keepEmptyPages = false)
            L.ensureTrailingPage()
            L.rebindAll()
            landing = L.viewFor(items.first(), if (z == Zone.Folder) Role.FOLDER else Role.HOME)
        }
        phZone = null
        L.model.dissolveSmallFolders()
        L.save()
        animateLanding(landing)
    }

    private fun dropIntoFolder(target: IconView): View {
        val keys = items.filterIsInstance<AppItem>().map { it.key }
        val list = L.model.containerOf(target.item)
        phZone?.let { zoneList(it)?.removeAll { i -> i === placeholder } }
        when (val t = target.item) {
            is FolderItem -> t.apps.addAll(keys)
            is AppItem -> if (list != null) {
                val f = FolderItem(Ids.new(), L.suggestFolderName(listOf(t.key) + keys), (listOf(t.key) + keys).toMutableList())
                val i = list.indexOfFirst { it === t }
                if (i >= 0) list[i] = f
                L.forgetView(t)
            }
            else -> {}
        }
        L.model.normalize(keepEmptyPages = false)
        L.ensureTrailingPage()
        L.rebindAll()
        val newTarget = list?.firstOrNull { it is FolderItem && keys.all { k -> k in it.apps } }
        val v = newTarget?.let { L.viewFor(it, target.role) } ?: target
        // Pop the folder as the icon lands in it.
        v.scaleX = 1.14f; v.scaleY = 1.14f
        v.postDelayed({ v.springScale(1f, 420f, 0.55f) }, 120)
        target.scaleX = 1f; target.scaleY = 1f
        return v
    }

    /** Springs the drag image into the landing view's icon, then reveals the real view. */
    private fun animateLanding(target: View?) {
        val img = image ?: return
        image = null
        target?.let { hidden += it; it.visibility = View.INVISIBLE }
        val land = Runnable {
            target?.let { hidden -= it; it.visibility = View.VISIBLE }
            L.root.removeView(img)
            dropping = null
        }
        dropping = land
        L.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                L.root.viewTreeObserver.removeOnPreDrawListener(this)
                if (target == null || !target.isAttachedToWindow) { land.run(); return true }
                val dst = RectF()
                target.getLocationInWindow(loc)
                if (target is IconView) {
                    target.iconRect(tmp)
                    dst.set(loc[0] + tmp.left, loc[1] + tmp.top, loc[0] + tmp.right, loc[1] + tmp.bottom)
                } else dst.set(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + target.width).toFloat(), (loc[1] + target.height).toFloat())
                // Account for a reflow spring still moving the target.
                dst.offset(target.translationX, target.translationY)
                val folderDrop = target is IconView && target.item is FolderItem && items.firstOrNull() !is FolderItem &&
                    (target.item as FolderItem).apps.contains((items.firstOrNull() as? AppItem)?.key)
                val endScale = if (folderDrop) 0.3f else dst.width() / img.width
                img.pivotX = 0f; img.pivotY = 0f
                val ox = if (folderDrop) dst.centerX() - img.width * endScale / 2 else dst.left
                val oy = if (folderDrop) dst.centerY() - img.height * endScale / 2 else dst.top
                img.animate().translationZ(-img.elevation).setDuration(220).start()
                img.springTo(DynamicAnimation.SCALE_X, endScale, 600f, 0.85f)
                img.springTo(DynamicAnimation.SCALE_Y, endScale, 600f, 0.85f)
                img.springTo(DynamicAnimation.TRANSLATION_X, ox, 600f, 0.85f)
                img.springTo(DynamicAnimation.TRANSLATION_Y, oy, 600f, 0.85f) { if (dropping === land) land.run() }
                if (folderDrop) img.springTo(DynamicAnimation.ALPHA, 0f, 600f, 1f)
                return true
            }
        })
        L.root.invalidate()
    }

    private fun finishDropAnimation() {
        dropping?.run()
        dropping = null
    }

    /** Abort without moving anything, e.g. when the screen folds mid-drag. */
    fun cancel() {
        if (dragging) drop()
        finishDropAnimation()
    }
}
