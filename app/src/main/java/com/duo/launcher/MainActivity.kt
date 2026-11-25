package com.duo.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class BackdropOwner { FOLDER, FINDER, MENU }

class MainActivity : Activity() {
    lateinit var repo: AppRepository
    val model = HomeModel()
    private lateinit var store: ModelStore
    lateinit var widgetManager: AppWidgetManager
    lateinit var widgetHost: LauncherWidgetHost

    var profile: DeviceProfile? = null
        private set
    /** True while views are being re-laid out for a new profile: no reflow springs then. */
    var suppressReflow = false
        private set

    lateinit var root: DragLayer
    private lateinit var content: FrameLayout
    private lateinit var home: FrameLayout
    lateinit var workspace: Workspace
    lateinit var dock: DockView
    lateinit var indicator: PageIndicator
    private lateinit var editBar: EditBar
    lateinit var folder: FolderOverlay
    lateinit var finder: FinderView
    private lateinit var menu: ContextMenuOverlay
    private lateinit var launchCard: LaunchCard
    val drag = DragController(this)
    private var sheet: Sheet? = null

    var editMode = false
        private set
    private val viewCache = HashMap<Long, View>()
    private val insets = Rect()
    private val jiggle = Jiggle { out -> collectJiggleViews(out) }

    private var started = false
    private var stoppedSinceResume = false
    private var pausedSinceLaunch = true
    private var pendingUnlock = false
    private var lastLaunch = 0L
    private var lastLaunchedIcon: IconView? = null
    private var lastLaunchedInfo: AppInfo? = null
    private lateinit var keyguard: KeyguardManager

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        keyguard = getSystemService(KeyguardManager::class.java)
        repo = AppRepository(applicationContext)
        store = ModelStore(this)
        store.load(model)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = LauncherWidgetHost(applicationContext)

        root = DragLayer(this)
        content = FrameLayout(this)
        home = FrameLayout(this)
        workspace = Workspace(this)
        dock = DockView(this)
        indicator = PageIndicator(this)
        editBar = EditBar(this)
        folder = FolderOverlay(this)
        finder = FinderView(this)
        menu = ContextMenuOverlay(this)
        launchCard = LaunchCard(this)

        home.addView(workspace)
        home.addView(indicator)
        home.addView(dock)
        content.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(editBar)
        root.addView(folder, FrameLayout.LayoutParams(-1, -1))
        root.addView(finder, FrameLayout.LayoutParams(-1, -1))
        root.addView(launchCard, FrameLayout.LayoutParams(-1, -1))
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, ins ->
            // Ignore visibility: hiding the status bar in edit mode must not reflow the grid.
            val i = android.graphics.Insets.max(
                ins.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()),
                ins.getInsets(WindowInsets.Type.displayCutout()),
            )
            if (insets.left != i.left || insets.top != i.top || insets.right != i.right || insets.bottom != i.bottom) {
                insets.set(i.left, i.top, i.right, i.bottom)
                root.post { updateProfile() }
            }
            ins
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { updateProfile() }
        }

        repo.addListener { onAppsChanged() }
        repo.addIconListener { invalidateIcons() }
        repo.register()
        repo.load()

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenReceiver) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        indicator.poke()
        workspace.settleNow()
        runCatching { widgetHost.startListening() }
        if (pendingUnlock) {
            if (!keyguard.isKeyguardLocked) playUnlock()
        } else playReturnHome()
    }

    override fun onStop() {
        super.onStop()
        started = false
        stoppedSinceResume = true
        runCatching { widgetHost.stopListening() }
        drag.cancel()
        menu.dismiss(immediate = true)
    }

    override fun onPause() {
        super.onPause()
        pausedSinceLaunch = true
        // Interrupted mid-swipe (e.g. an app brought itself to the front): land on a page.
        workspace.settleNow()
    }

    override fun onResume() {
        super.onResume()
        settleContent()
        stoppedSinceResume = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus arrives while One UI is still showing bare wallpaper, so it does not mean our
        // window is visible yet; a scheduled return keeps its own timing.
        if (hasFocus && !returnScheduled) settleContent()
    }

    /**
     * Whenever Home is in front, make sure it ends at rest. Covers returns that never stop the
     * activity (swiping home while an app is still opening, launching another app mid-return):
     * onStart is skipped then, and the zoom from the launch would otherwise stick.
     */
    private fun settleContent() {
        if (pendingUnlock) {
            if (!keyguard.isKeyguardLocked) playUnlock()
            return
        }
        if (content.scaleX != 1f || content.alpha != 1f || content.translationX != 0f || launchCard.active) playReturnHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) return
        // Home pressed while Home was already in front closes things, then goes to page 1.
        // Coming back from an app must not move pages: onNewIntent arrives after onStart there,
        // so "started" is no signal; "not stopped since the last resume" + focus is.
        val alreadyHome = !stoppedSinceResume && hasWindowFocus() &&
            SystemClock.uptimeMillis() - lastLaunch > 600
        if (!alreadyHome) {
            menu.dismiss(immediate = true)
            finder.close(animate = false)
            return
        }
        when {
            menu.isShowing -> menu.dismiss()
            sheet != null -> sheet?.dismiss()
            finder.isOpen -> finder.close()
            folder.isOpen -> folder.close()
            editMode -> exitEditMode()
            else -> workspace.snapTo(0)
        }
    }

    private fun handleBack() {
        when {
            menu.isShowing -> menu.dismiss()
            sheet != null -> sheet?.dismiss()
            finder.isOpen -> finder.close()
            folder.isOpen -> folder.close()
            editMode -> exitEditMode()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { updateProfile() }
    }

    // ================================================================ profile & layout

    private fun updateProfile() {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val np = DeviceProfile(
            w, h, Rect(insets), resources.displayMetrics.density,
            resources.configuration.smallestScreenWidthDp, model.showLabels,
        )
        val old = profile
        if (np.sameShapeAs(old)) return
        profile = np
        requestedOrientation = if (np.mode == Mode.COVER) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        drag.cancel()
        menu.dismiss(immediate = true)
        launchCard.finish()
        // Keep an open folder open across fold/unfold/rotation: close it now, reopen once laid out.
        val reopen = folder.folder
        folder.close(animate = false)
        sheet?.let { root.removeView(it); sheet = null }
        applyProfile(np)
        if (old != null) playModeTransition(old, np)
        if (reopen != null) root.postDelayed({
            (viewCache[reopen.id] as? IconView)?.takeIf { it.isAttachedToWindow && it.isShown }?.let { folder.open(it) }
        }, 200)
    }

    fun onProfileInputsChanged() {
        profile = null
        updateProfile()
    }

    private fun applyProfile(p: DeviceProfile) {
        suppressReflow = true
        fun lp(r: RectF) = FrameLayout.LayoutParams(r.width().roundToInt(), r.height().roundToInt()).apply {
            leftMargin = r.left.roundToInt(); topMargin = r.top.roundToInt()
        }
        workspace.layoutParams = FrameLayout.LayoutParams(p.workspaceClipRight, FrameLayout.LayoutParams.MATCH_PARENT)
        indicator.layoutParams = lp(p.indicatorRect)
        dock.layoutParams = lp(p.dockRect)
        // Edit-mode buttons sit in the (hidden) status-bar strip.
        // "Done" keeps its edge margin (in line with the dock in landscape); "+" mirrors it.
        val edge = if (p.landscape) p.width - p.dockRect.right else p.dp(16f)
        editBar.layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
            topMargin = p.dpi(10f)
            leftMargin = (edge + p.insets.left).roundToInt()
            rightMargin = (if (p.landscape) edge else edge + p.insets.right).roundToInt()
        }
        // Keep the visible page when the number of visible pages changes.
        val keep = workspace.currentPage
        rebindAll()
        workspace.snapTo(keep, animate = false)
        finder.layoutContents()
        root.requestLayout()
        root.post { suppressReflow = false }
    }

    /** Fold/unfold or rotate: content refocuses in, the dock slides in from its new edge. */
    private fun playModeTransition(old: DeviceProfile, p: DeviceProfile) {
        home.alpha = 0f
        home.scaleX = 0.94f; home.scaleY = 0.94f
        home.springTo(DynamicAnimation.ALPHA, 1f, 260f, 1f)
        home.springScale(1f, 260f, 0.9f)
        if (old.dockVertical != p.dockVertical || old.mode != p.mode) {
            dock.cancelSprings()
            if (p.dockVertical) { dock.translationX = p.dp(140f); dock.translationY = 0f }
            else { dock.translationY = p.dp(140f); dock.translationX = 0f }
            dock.springTo(DynamicAnimation.TRANSLATION_X, 0f, 300f, 0.82f)
            dock.springTo(DynamicAnimation.TRANSLATION_Y, 0f, 300f, 0.82f)
        }
    }

    fun configureIcon(v: IconView, role: Role) {
        val p = profile ?: return
        v.labelSize = p.labelSize
        v.labelGap = p.labelGap
        when (role) {
            Role.DOCK -> { v.iconSize = p.dockIconSize; v.showLabel = false }
            Role.HOME -> { v.iconSize = p.iconSize; v.showLabel = model.showLabels; v.iconTop = p.iconTop }
            Role.FOLDER -> { v.iconSize = p.iconSize; v.showLabel = true; v.iconTop = p.dp(9f) }
            Role.FINDER -> { v.iconSize = p.iconSize; v.showLabel = true; v.iconTop = p.dp(10f) }
        }
        v.invalidate()
    }

    fun viewFor(item: HomeItem, role: Role): View = viewCache.getOrPut(item.id) {
        when (item) {
            is WidgetItem -> WidgetContainer(this, item)
            else -> IconView(this, item, role).also { configureIcon(it, role) }
        }
    }

    fun forgetView(item: HomeItem) { viewCache.remove(item.id) }

    fun rebindAll() {
        if (profile == null) return
        workspace.bind(model.pages)
        dock.setItems(model.dock)
        indicator.count = model.pages.size
        indicator.pos = workspace.pos
        // Drop cached views for items that no longer exist anywhere.
        val live = HashSet<Long>()
        model.allItems().forEach { live += it.id }
        folder.items.forEach { live += it.id }
        viewCache.keys.retainAll { it in live || (viewCache[it]?.parent != null) }
    }

    /**
     * Pages needed while dragging: in landscape the right half must have a page to drop on.
     * No empty page is added just for being in edit mode; one appears only when an icon is
     * dragged past the last page (see [addPageForDrag]).
     */
    fun ensureTrailingPage() {
        if (drag.dragging && profile?.landscape == true && model.pages.size < 2) model.pages.add(ArrayList())
    }

    /** Called when a drag holds at the right edge of the last page: open a new empty page. */
    fun addPageForDrag(): Boolean {
        val last = model.pages.lastOrNull() ?: return false
        if (last.none { it !is PlaceholderItem }) return false
        model.pages.add(ArrayList())
        rebindAll()
        return true
    }

    fun onWorkspaceScrolled(pos: Float) {
        if (indicator.pos != pos) {
            indicator.pos = pos
            indicator.poke()
        }
    }

    /** Any touch counts as use: the page dots stay while a finger is down, then fade 3 s later. */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> indicator.hold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> indicator.release()
        }
        return super.dispatchTouchEvent(e)
    }

    fun invalidateIcons() {
        for (v in viewCache.values) v.invalidate()
        finder.invalidate()
    }

    private fun collectJiggleViews(out: MutableList<View>) {
        for (pg in workspace.pages) {
            if (pg.visibility != View.VISIBLE) continue
            for (i in 0 until pg.childCount) out += pg.getChildAt(i)
        }
        for (i in 0 until dock.childCount) out += dock.getChildAt(i)
        if (folder.isOpen) for (i in 0 until folder.grid.childCount) out += folder.grid.getChildAt(i)
    }

    private var backdrop = FloatArray(BackdropOwner.values().size)
    private var lastBackdrop = -1f

    /** Blur + recede the home screen behind folders, the finder and menus. */
    fun setBackdrop(t: Float, owner: BackdropOwner) {
        backdrop[owner.ordinal] = t
        val e = backdrop.max()
        if (kotlin.math.abs(e - lastBackdrop) < 0.002f) return
        lastBackdrop = e
        val p = profile ?: return
        Blur.apply(home, p.dp(26f) * e)
        val s = 1f - 0.05f * e
        home.scaleX = s; home.scaleY = s
    }

    // ================================================================ apps

    private fun onAppsChanged() {
        if (!model.initialized) buildDefaultLayout()
        val present = repo.apps.keys
        // Newly installed apps.
        for (k in present) {
            if (k !in model.known) {
                model.known += k
                if (model.addNewApps && k !in model.hidden && !model.isOnHome(k)) model.appendToHome(AppItem(Ids.new(), k))
            }
        }
        // Something on Home is not hidden (keeps the two lists consistent).
        model.hidden.removeAll { model.isOnHome(it) }
        // Uninstalled apps.
        val gone = model.known.filter { it !in present }
        for (k in gone) { model.known -= k; model.removeKey(k); model.hidden -= k }
        model.normalize(keepEmptyPages = false)
        ensureTrailingPage()
        rebindAll()
        save()
        if (finder.visibility == View.VISIBLE) finder.invalidate()
    }

    private fun resolve(intent: Intent): String? {
        val ri = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        return repo.apps.keys.firstOrNull { it.startsWith("$pkg/") }
    }

    private fun buildDefaultLayout() {
        model.pages.clear(); model.dock.clear()
        val dockKeys = listOfNotNull(
            resolve(Intent(Intent.ACTION_DIAL)),
            resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)),
            resolve(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
                ?: resolve(Intent("android.media.action.STILL_IMAGE_CAMERA")),
        ).distinct().take(DOCK_MAX)
        dockKeys.forEach { model.dock += AppItem(Ids.new(), it) }
        for (k in repo.apps.keys) {
            model.known += k
            if (k !in dockKeys) model.appendToHome(AppItem(Ids.new(), k))
        }
        model.initialized = true
    }

    fun resetLayout() {
        exitEditMode()
        model.allItems().filterIsInstance<WidgetItem>().forEach { widgetHost.deleteAppWidgetId(it.widgetId) }
        viewCache.clear()
        model.known.clear()
        model.initialized = false
        onAppsChanged()
        workspace.snapTo(0)
    }

    fun save() = store.save(model)

    fun suggestFolderName(keys: List<String>): String {
        val cats = keys.mapNotNull { k ->
            repo[k]?.packageName?.let { pkg ->
                runCatching { packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
            }
        }
        return when (cats.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key) {
            ApplicationInfo.CATEGORY_GAME -> t("游戏", "Games")
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> t("效率", "Productivity")
            ApplicationInfo.CATEGORY_SOCIAL -> t("社交", "Social")
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> t("影音", "Entertainment")
            ApplicationInfo.CATEGORY_IMAGE -> t("照片与视频", "Photo & Video")
            ApplicationInfo.CATEGORY_NEWS -> t("新闻", "News")
            ApplicationInfo.CATEGORY_MAPS -> t("出行", "Travel")
            else -> t("文件夹", "Folder")
        }
    }

    // ================================================================ launching

    fun launchApp(info: AppInfo, from: IconView?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 350) return
        lastLaunch = now
        // A new launch interrupts a return that is still waiting to start.
        root.removeCallbacks(returnRunnable)
        returnScheduled = false
        model.launchCounts[info.key] = (model.launchCounts[info.key] ?: 0) + 1
        save()
        var opts: Bundle? = null
        var bounds: Rect? = null
        val r = RectF()
        if (from != null && from.isAttachedToWindow) {
            from.iconRect(r)
            val loc = IntArray(2)
            from.getLocationOnScreen(loc)
            bounds = Rect((loc[0] + r.left).toInt(), (loc[1] + r.top).toInt(), (loc[0] + r.right).toInt(), (loc[1] + r.bottom).toInt())
            lastLaunchedIcon = from
        } else lastLaunchedIcon = null
        // One UI only lets the system Home animate app windows, so the zoom out of the icon is
        // drawn here (LaunchCard) and the system's own fade lands on top of it.
        val own = model.appAnimation
        opts = if (!own && from != null && from.isAttachedToWindow) {
            // Ask the system to scale the window up from the icon (ignored if One UI won't).
            ActivityOptions.makeScaleUpAnimation(from, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()).toBundle()
        } else ActivityOptions.makeBasic().toBundle()
        val withCard = own && from != null && from.isAttachedToWindow
        lastLaunchedInfo = info
        if (withCard) launchCard.launch(from!!, info.icon, info.color)
        if (own) playLaunch(bounds) else playFadeOut()
        // Give the card a short head start so the zoom out of the icon reads before the system
        // fades the app window in on top of it.
        val start = Runnable {
            try {
                repo.start(info, bounds, opts)
            } catch (e: Exception) {
                Toast.makeText(this, t("无法打开 App", "Can't open app"), Toast.LENGTH_SHORT).show()
                playReturnHome()
            }
        }
        if (withCard) root.postDelayed(start, 140) else start.run()
        if (finder.visibility == View.VISIBLE) root.postDelayed({ finder.close(animate = false) }, 350)
        // If the app never came up at all (we were never paused), undo the zoom. A slow cold
        // start (Camera) still pauses us, so it is left alone.
        pausedSinceLaunch = false
        root.postDelayed({ if (!pausedSinceLaunch && hasWindowFocus()) playReturnHome() }, 2500)
    }

    /** Home zooms toward the tapped icon while the app grows out of it. */
    private fun playLaunch(iconScreen: Rect?) {
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        movePivot(
            (iconScreen?.exactCenterX() ?: (content.width / 2f)) - loc[0],
            (iconScreen?.exactCenterY() ?: (content.height / 2f)) - loc[1],
        )
        content.springScale(1.08f, 260f, 1f)
        content.springTo(DynamicAnimation.ALPHA, 0.55f, 260f, 1f)
    }

    /** System-animation mode, opening: the icons just fade out quickly while the app comes up. */
    private fun playFadeOut() {
        content.springTo(DynamicAnimation.ALPHA, 0f, 1500f, 1f)
    }

    /** System-animation mode, coming back: Home starts transparent and at rest. */
    private fun prepareFadeIn() {
        content.cancelSprings()
        content.scaleX = 1f; content.scaleY = 1f
        content.translationX = 0f; content.translationY = 0f
        content.alpha = 0f
    }

    /** Fades Home back in (and undoes anything left from a launch). */
    private fun fadeHomeIn() {
        content.springScale(1f, 400f, 1f)
        content.springTo(DynamicAnimation.TRANSLATION_X, 0f, 400f, 1f)
        content.springTo(DynamicAnimation.TRANSLATION_Y, 0f, 400f, 1f)
        content.springTo(DynamicAnimation.ALPHA, 1f, 700f, 1f)
    }

    /**
     * Moves the zoom pivot without a visual jump, even while the content is mid-zoom (e.g. a new
     * app is launched while Home is still settling from the last one). The offset this needs is
     * put into translation and sprung away.
     */
    private fun movePivot(px: Float, py: Float) {
        val s = content.scaleX
        val tx = content.translationX + (content.pivotX - px) * (1f - s)
        val ty = content.translationY + (content.pivotY - py) * (1f - s)
        content.pivotX = px; content.pivotY = py
        content.springFrom(DynamicAnimation.TRANSLATION_X, tx, 0f, 300f, 1f)
        content.springFrom(DynamicAnimation.TRANSLATION_Y, ty, 0f, 300f, 1f)
    }

    /**
     * Coming back to Home (swipe up, back, Home button, app finished). Starts from whatever state
     * the content is in, so a swipe-up during the launch animation reverses smoothly.
     */
    private var returnScheduled = false
    private val returnRunnable = Runnable { runReturnHome() }

    private fun playReturnHome() {
        if (pendingUnlock || returnScheduled) return
        if (!model.appAnimation) {
            launchCard.finish()
            lastLaunchedIcon?.resetTouchState()
            if (!hasWindowFocus()) {
                // Really coming back. One UI shows bare wallpaper for a moment before our
                // window, so hold the starting pose until it is on screen.
                prepareFadeIn()
                lastLaunchedIcon = null
                lastLaunchedInfo = null
                returnScheduled = true
                root.postDelayed(returnRunnable, 120)
            } else {
                lastLaunchedIcon = null
                lastLaunchedInfo = null
                fadeHomeIn()
            }
            return
        }
        // Window not focused yet = we are really coming back from another app (onStart/onResume),
        // as opposed to e.g. the launch fallback timer while Home is still in front.
        val realReturn = !hasWindowFocus()
        if (realReturn) prepareReturnCard()
        val atRest = content.scaleX == 1f && content.alpha == 1f
        if (atRest && !launchCard.active) {
            movePivot(content.width / 2f, content.height / 2f)
            content.scaleX = 1.06f; content.scaleY = 1.06f
            content.alpha = 0.6f
        }
        lastLaunchedIcon?.resetTouchState()
        if (!realReturn) runReturnHome()
        else {
            // One UI shows the wallpaper first and fades our window in ~150ms later; hold the
            // starting pose until then so the shrink into the icon is actually seen.
            returnScheduled = true
            // One UI shows ~200ms of bare wallpaper before fading our window in.
            root.postDelayed(returnRunnable, 230)
        }
    }

    private fun runReturnHome() {
        returnScheduled = false
        if (!model.appAnimation) { fadeHomeIn(); return }
        content.springScale(1f, 320f, 0.9f)
        content.springTo(DynamicAnimation.ALPHA, 1f, 320f, 1f)
        if (launchCard.active) launchCard.retract()
        lastLaunchedIcon = null
        lastLaunchedInfo = null
    }

    /**
     * Decides which icon the closing app shrinks into. The app being left is not necessarily the
     * one launched from here: the user may have switched apps in Recents, or opened one from a
     * notification. With usage access the real last foreground app is known; without it, only
     * our own launch is trusted.
     */
    private fun prepareReturnCard() {
        val pkg = foregroundPackageBeforeHome()
        val info: AppInfo?
        val target: IconView?
        if (pkg == null) {
            if (!launchCard.active) return
            info = lastLaunchedInfo
            target = lastLaunchedIcon
        } else {
            info = lastLaunchedInfo?.takeIf { it.packageName == pkg }
                ?: repo.apps.values.firstOrNull { it.packageName == pkg && it.user == android.os.Process.myUserHandle() }
                ?: repo.apps.values.firstOrNull { it.packageName == pkg }
            target = returnTarget(pkg)
        }
        if (info == null) { launchCard.finish(); return }
        launchCard.prepareReturn(target, info.icon, info.color)
    }

    /** The on-screen icon of the app being left (a folder holding it counts), if any. */
    private fun returnTarget(pkg: String?): IconView? =
        if (pkg == null) lastLaunchedIcon?.takeIf { it.isAttachedToWindow && it.isShown }
        else lastLaunchedIcon?.takeIf { it.isAttachedToWindow && iconShows(it, pkg) } ?: findVisibleIcon(pkg)

    private fun pkgOf(key: String) = repo[key]?.packageName

    private fun iconShows(v: IconView, pkg: String): Boolean = when (val it = v.item) {
        is AppItem -> pkgOf(it.key) == pkg
        is FolderItem -> it.apps.any { k -> pkgOf(k) == pkg }
        else -> false
    }

    /** The app's icon on the pages currently on screen or in the dock (a folder counts). */
    private fun findVisibleIcon(pkg: String): IconView? {
        var folderHit: IconView? = null
        val containers = workspace.pages.filter { it.visibility == View.VISIBLE && it.alpha >= 1f } + dock
        for (c in containers) for (i in 0 until c.childCount) {
            val v = c.getChildAt(i) as? IconView ?: continue
            when (val it = v.item) {
                is AppItem -> if (pkgOf(it.key) == pkg) return v
                is FolderItem -> if (folderHit == null && it.apps.any { k -> pkgOf(k) == pkg }) folderHit = v
                else -> {}
            }
        }
        return folderHit
    }

    fun hasUsageAccess(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Package of the last activity in front before Home, "" if none, null if unknown. */
    private fun foregroundPackageBeforeHome(): String? {
        if (!hasUsageAccess()) return null
        return runCatching {
            val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10 * 60_000L, now + 1_000L)
            val e = android.app.usage.UsageEvents.Event()
            var last = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val t = e.eventType
                if ((t == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                        t == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED) && e.packageName != packageName) {
                    last = e.packageName
                }
            }
            last
        }.getOrNull()
    }

    // ================================================================ unlock

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pendingUnlock = true
                    drag.cancel()
                    menu.dismiss(immediate = true)
                    finder.close(animate = false)
                    if (editMode) exitEditMode()
                    content.cancelSprings()
                    content.alpha = 0f
                    content.pivotX = content.width / 2f
                    content.pivotY = content.height / 2f
                    content.scaleX = 1.18f; content.scaleY = 1.18f
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (started) playUnlock()
                    else root.postDelayed({
                        // Unlocked into another app: reset silently so the next visit is normal.
                        if (pendingUnlock && !started) {
                            pendingUnlock = false
                            content.scaleX = 1f; content.scaleY = 1f; content.alpha = 1f
                        }
                    }, 800)
                }
            }
        }
    }

    /** iOS-style unlock: the home screen settles in from a zoomed state, icons converge inward. */
    private fun playUnlock() {
        if (!pendingUnlock) return
        pendingUnlock = false
        val p = profile ?: return
        content.springScale(1f, 200f, 0.86f)
        content.springTo(DynamicAnimation.ALPHA, 1f, 260f, 1f)
        val cx = root.width / 2f; val cy = root.height / 2f
        val maxD = hypot(cx, cy)
        val loc = IntArray(2)
        val views = ArrayList<View>()
        collectJiggleViews(views)
        for (v in views) {
            v.getLocationInWindow(loc)
            val vx = loc[0] + v.width / 2f - cx
            val vy = loc[1] + v.height / 2f - cy
            val d = hypot(vx, vy) / maxD
            v.translationX = vx * 0.22f
            v.translationY = vy * 0.22f
            // Outer icons travel further and settle slightly later.
            val stiff = lerp(320f, 170f, d)
            v.springTo(DynamicAnimation.TRANSLATION_X, 0f, stiff, 0.84f)
            v.springTo(DynamicAnimation.TRANSLATION_Y, 0f, stiff, 0.84f)
        }
    }

    // ================================================================ edit mode

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        menu.dismiss(immediate = true)
        ensureTrailingPage()
        rebindAll()
        jiggle.start()
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        editBar.setShown(true)
        indicator.hold()
        invalidateIcons()
    }

    fun exitEditMode() {
        if (!editMode) return
        if (drag.dragging) return
        editMode = false
        model.normalize(keepEmptyPages = false)
        rebindAll()
        if (workspace.pos > workspace.maxPos()) workspace.snapTo(workspace.maxPos())
        jiggle.stop()
        window.insetsController?.show(WindowInsets.Type.statusBars())
        editBar.setShown(false)
        indicator.release()
        invalidateIcons()
        save()
    }

    fun performHaptic(c: Int) { root.performHapticFeedback(c) }

    /** Double tap on empty Home space locks the screen (needs the accessibility service). */
    fun onDoubleTapEmpty() {
        if (!model.doubleTapLock) return
        if (LockService.lock()) return
        Toast.makeText(
            this,
            t("双击锁屏需要开启无障碍服务：Duo Launcher 双击锁屏", "Double-tap to lock needs the Duo Launcher accessibility service"),
            Toast.LENGTH_LONG,
        ).show()
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    fun expandNotifications() {
        runCatching {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    // ================================================================ clicks & menus

    fun onIconClick(v: IconView) {
        when (val item = v.item) {
            is FolderItem -> if (v.role != Role.FINDER) folder.open(v)
            is AppItem -> {
                if (editMode && v.role != Role.FINDER) return
                repo[item.key]?.let { launchApp(it, v) }
            }
            else -> {}
        }
    }

    fun onRemoveBadge(v: View) {
        when (v) {
            is WidgetContainer -> removeWithAnimation(v) {
                model.remove(v.item); widgetHost.deleteAppWidgetId(v.item.widgetId); forgetView(v.item)
            }
            is IconView -> when (v.role) {
                Role.FOLDER -> {
                    val idx = folder.items.indexOfFirst { it === v.item }
                    if (idx >= 0) { folder.items.removeAt(idx); folder.onItemsChanged(); save() }
                }
                Role.HOME, Role.DOCK -> removeWithAnimation(v) { model.remove(v.item); forgetView(v.item) }
                else -> {}
            }
        }
    }

    private fun removeWithAnimation(v: View, action: () -> Unit) {
        v.springScale(0f, 600f, 1f)
        v.springTo(DynamicAnimation.ALPHA, 0f, 600f, 1f) {
            action()
            model.normalize(keepEmptyPages = false)
            ensureTrailingPage()
            rebindAll()
            save()
            v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        }
    }

    fun hideApp(key: String) {
        model.hidden += key
        model.removeKey(key)
        model.normalize(keepEmptyPages = false)
        rebindAll(); save()
        Toast.makeText(this, t("已隐藏，可在桌面设置中恢复", "Hidden. Restore it in Home Settings."), Toast.LENGTH_SHORT).show()
    }

    fun unhide(key: String) {
        model.hidden -= key
        if (!model.isOnHome(key)) model.appendToHome(AppItem(Ids.new(), key))
        rebindAll(); save()
    }

    fun dismissContextMenu(immediate: Boolean) = menu.dismiss(immediate)

    fun showContextMenu(v: View) {
        val p = profile ?: return
        // The menu takes over: put the keyboard away (Finder search, folder title).
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(root.windowToken, 0)
        val rect = RectF()
        val snap = if (v is IconView || v is WidgetContainer) drag.snapshot(v, rect) else null
        val actions = ArrayList<MenuAction>()
        val editAction = MenuAction(t("编辑主屏幕", "Edit Home Screen"), separatorBefore = true) { enterEditMode() }
        when {
            v is WidgetContainer -> {
                val info = widgetManager.getAppWidgetInfo(v.item.widgetId)
                if (info?.configure != null) actions += MenuAction(t("编辑小组件", "Edit Widget")) {
                    runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, v.item.widgetId, 0, REQ_RECONFIG, null) }
                }
                for ((label, sx, sy) in listOf(
                    Triple(t("小 2×2", "Small 2×2"), 2, 2),
                    Triple(t("中 4×2", "Medium 4×2"), 4, 2),
                    Triple(t("大 4×4", "Large 4×4"), 4, 4),
                )) {
                    if (v.item.sx == sx && v.item.sy == sy) continue
                    actions += MenuAction(label) { resizeWidget(v.item, sx, sy) }
                }
                actions += editAction
                actions += MenuAction(t("移除小组件", "Remove Widget"), destructive = true) { onRemoveBadge(v) }
            }
            v is IconView && v.item is FolderItem -> {
                val f = v.item as FolderItem
                actions += MenuAction(t("重命名", "Rename")) { folder.open(v) }
                actions += editAction
                actions += MenuAction(t("从主屏幕移除", "Remove from Home Screen"), destructive = true) {
                    removeWithAnimation(v) { model.remove(f); forgetView(f) }
                }
            }
            v is IconView -> {
                val info = v.appInfo ?: return
                for (s in repo.shortcuts(info)) {
                    actions += MenuAction(s.shortLabel?.toString() ?: "", repo.shortcutIcon(s)) {
                        repo.startShortcut(s, null)
                    }
                }
                if (v.role != Role.FINDER) actions += editAction
                if (v.role == Role.FINDER && !model.isOnHome(info.key)) {
                    actions += MenuAction(t("添加到主屏幕", "Add to Home Screen"), separatorBefore = actions.isNotEmpty()) {
                        model.appendToHome(AppItem(Ids.new(), info.key)); rebindAll(); save()
                    }
                }
                if (v.role == Role.HOME || v.role == Role.DOCK) {
                    actions += MenuAction(t("从主屏幕移除", "Remove from Home Screen")) {
                        removeWithAnimation(v) { model.remove(v.item); forgetView(v.item) }
                    }
                }
                if (v.role == Role.FOLDER) {
                    actions += MenuAction(t("移出文件夹", "Remove from Folder")) { onRemoveBadge(v) }
                }
                actions += MenuAction(t("隐藏 App", "Hide App")) { if (v.role == Role.FOLDER) folder.close(animate = false); hideApp(info.key) }
                actions += MenuAction(t("App 信息", "App Info")) { repo.openDetails(info) }
                if (!info.isSystem) actions += MenuAction(t("卸载 App", "Uninstall App"), destructive = true) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${info.packageName}")))
                    }
                }
            }
        }
        if (v is IconView && v.role == Role.FINDER) finder.field.clearFocus()
        menu.bringToFront()
        menu.show(v, rect, snap, actions)
    }

    private fun resizeWidget(w: WidgetItem, sx: Int, sy: Int) {
        w.sx = sx; w.sy = sy
        model.normalize(keepEmptyPages = false)
        ensureTrailingPage()
        rebindAll(); save()
    }

    // ================================================================ widgets & sheets

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    fun openWidgetPicker() {
        if (sheet != null) return
        sheet = WidgetPicker(this).also { it.show() }
    }

    fun openSettings() {
        if (sheet != null) return
        sheet = SettingsSheet(this).also { it.show() }
    }

    fun onSheetClosed(s: Sheet) { if (sheet === s) sheet = null }

    fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrPlace(id, info)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
                REQ_BIND,
            )
        }
    }

    private fun configureOrPlace(id: Int, info: AppWidgetProviderInfo) {
        val optional = (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
            runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null) }
                .onFailure { placeWidget(id, info) }
        } else placeWidget(id, info)
    }

    private fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        val (sx, sy) = WidgetSizing.defaultSpan(info, profile)
        val item = WidgetItem(Ids.new(), id, info.provider.flattenToString(), sx, sy)
        val page = workspace.currentPage.coerceIn(0, model.pages.size - 1)
        model.pages[page].add(0, item)
        model.normalize(keepEmptyPages = false)
        ensureTrailingPage()
        rebindAll(); save()
        workspace.snapTo(page)
        pendingWidgetId = -1; pendingWidgetInfo = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        val info = pendingWidgetInfo
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK && info != null) configureOrPlace(id, info)
            else if (id != -1) widgetHost.deleteAppWidgetId(id)
            REQ_CONFIG -> if (resultCode == RESULT_OK && info != null) placeWidget(id, info)
            else if (id != -1) widgetHost.deleteAppWidgetId(id)
        }
    }

    companion object {
        const val REQ_BIND = 11
        const val REQ_CONFIG = 12
        const val REQ_RECONFIG = 13
    }
}
