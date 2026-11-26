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

enum class BackdropOwner { FOLDER, FINDER, MENU, APP }

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
    private lateinit var searchButton: GlassButton
    private lateinit var editBar: EditBar
    lateinit var folder: FolderOverlay
    lateinit var finder: FinderView
    private lateinit var menu: ContextMenuOverlay
    private lateinit var launchCard: LaunchCard
    private val cardActive get() = launchCard.active

    private fun finishCards() = launchCard.finish()
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
    private var stoppedSinceLaunch = true
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
        searchButton = GlassButton(this, Glyph.SEARCH) { if (!editMode) finder.open() }
        editBar = EditBar(this)
        folder = FolderOverlay(this)
        finder = FinderView(this)
        menu = ContextMenuOverlay(this)
        launchCard = LaunchCard(this)

        home.addView(workspace)
        home.addView(indicator)
        home.addView(dock)
        home.addView(searchButton)
        content.addView(home, FrameLayout.LayoutParams(-1, -1))
        homeBlur = HomeBlur(this, home).apply { visibility = View.INVISIBLE; alpha = 0f }
        // Faded as they are drawn, not through an off-screen copy: at this screen size such a
        // copy is 18 MB a time, and the renderer kept a hundred megabytes of them after one
        // look at the Finder. Nothing in Home overlaps enough for the difference to show.
        home.forceHasOverlappingRendering(false)
        homeBlur.forceHasOverlappingRendering(false)
        content.addView(homeBlur, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        editBar.attach(root)
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

        repo.firstIcons = {
            // Read on the worker thread while Home is being built: a snapshot, dock first.
            runCatching { (model.dock.toList() + model.pages.toList().flatMap { it.toList() }).flatMap { item ->
                when (item) { is AppItem -> listOf(item.key); is FolderItem -> item.apps.toList(); else -> emptyList() }
            } }.getOrDefault(emptyList())
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
        dlog { "life: onStart focus=${hasWindowFocus()}" }
        started = true
        workspace.settleNow()
        runCatching { widgetHost.startListening() }
        if (pendingUnlock) {
            if (!keyguard.isKeyguardLocked) playUnlock()
        } else playReturnHome()
    }

    override fun onStop() {
        super.onStop()
        dlog { "life: onStop" }
        started = false
        stoppedSinceResume = true
        stoppedSinceLaunch = true
        // Fully covered now: drop what the launch animation left (full-screen card, zoomed
        // Home), so that nothing of it can show if the app window is pulled away again.
        if (!returnScheduled) {
            finishCards()
            content.cancelSprings()
            content.scaleX = 1f; content.scaleY = 1f
            content.translationX = 0f; content.translationY = 0f
            content.alpha = 1f
            appDepth.snap(0f)
            sharpening = false
        }
        runCatching { widgetHost.stopListening() }
        drag.cancel()
        menu.dismiss(immediate = true)
    }

    override fun onPause() {
        super.onPause()
        dlog { "life: onPause" }
        pausedSinceLaunch = true
        leftHomeAt = System.currentTimeMillis()
        // Interrupted mid-swipe (e.g. an app brought itself to the front): land on a page.
        workspace.settleNow()
    }

    override fun onResume() {
        super.onResume()
        dlog { "life: onResume focus=${hasWindowFocus()}" }
        settleContent()
        stoppedSinceResume = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        dlog { "life: focus=$hasFocus" }
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
        if (content.scaleX != 1f || content.alpha != 1f || content.translationX != 0f || cardActive || appDepth.value != 0f) playReturnHome()
    }

    override fun onTopResumedActivityChanged(isTop: Boolean) {
        super.onTopResumedActivityChanged(isTop)
        dlog { "life: topResumed=$isTop" }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        dlog { "life: enterAnimationComplete" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dlog { "life: onNewIntent ${intent.action} ${intent.categories}" }
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

    /**
     * The swipe-Home gesture's contract (AOSP Launcher3's GestureNavContract, which One UI's
     * recents keeps): the Home intent names the app being left and carries a callback; Home
     * answers with where that app's icon is, and the system shrinks the app's window into it.
     */
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

    /**
     * The first blurred backdrop after a rotation or fold used to cost a few frames of 20-60 ms
     * (measured: the renderer sets up the blurred copy for the new screen shape on first use).
     * So that is done here, out of sight, as soon as the shape is known: the copy is drawn for
     * a couple of frames at the faintest alpha there is.
     */
    private fun warmBlur() {
        root.postDelayed({
            if (backdrop.max() > 0f || !::homeBlur.isInitialized) return@postDelayed
            homeBlur.alpha = 0.005f
            homeBlur.visibility = View.VISIBLE
            homeBlur.invalidate()
            root.postDelayed({
                if (backdrop.max() <= 0f) { homeBlur.visibility = View.INVISIBLE; homeBlur.alpha = 0f }
            }, 120)
        }, 400)
    }

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
        warmBlur()
        requestedOrientation = if (np.mode == Mode.COVER) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        drag.cancel()
        menu.dismiss(immediate = true)
        finishCards()
        // Keep an open folder open across fold/unfold/rotation: close it now, reopen once laid out.
        val reopen = folder.folder
        folder.close(animate = false)
        sheet?.let { root.removeView(it); sheet = null }
        applyProfile(np)
        if (old != null) playModeTransition(old, np)
        if (finder.isOpen) {
            // Search stays open across a fold/rotation: the dock stays put away.
            dock.cancelSprings(); dock.translationX = 0f; dock.translationY = 0f
            lastRecede = -1f
            setChromeRecede(1f)
        }
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
        // Pages run under the dock to the screen edge: the workspace is the whole window.
        workspace.layoutParams = FrameLayout.LayoutParams(-1, -1)
        indicator.layoutParams = lp(p.indicatorRect)
        dock.layoutParams = lp(p.dockRect)
        searchButton.layoutParams = lp(p.searchRect)
        editBar.layout(p)
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
        lastRecede = 0f
        searchButton.setShown(!editMode, animate = false)
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
            Role.FOLDER -> { v.iconSize = p.iconSize; v.showLabel = true; v.iconTop = p.r(11f); v.labelGap = p.r(5f) }
            Role.FINDER -> { v.iconSize = p.iconSize; v.showLabel = true; v.iconTop = p.dp(10f); v.labelGap = p.r(5f) }
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

    /** Home's views have been built from the model at least once. */
    private var boundOnce = false

    fun rebindAll() {
        if (profile == null) return
        boundOnce = true
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

    private val holeRect = RectF()
    private val holeRad = FloatArray(1)
    private val holeA = Rect()
    private val holeB = Rect()

    /** Adds the rail's glass shapes (dock capsule, shown round buttons) to [out], in [ws] coordinates. */
    fun addGlassHoles(out: android.graphics.Path, ws: View) {
        if (!::editBar.isInitialized) return
        holeB.set(0, 0, 0, 0); root.offsetDescendantRectToMyCoords(ws, holeB)
        fun origin(v: View): Boolean {
            if (!v.isAttachedToWindow || v.width == 0) return false
            holeA.set(0, 0, 0, 0); root.offsetDescendantRectToMyCoords(v, holeA)
            return true
        }
        if (origin(dock) && dock.capsuleRect(holeRect, holeRad)) {
            holeRect.offset((holeA.left - holeB.left).toFloat(), (holeA.top - holeB.top).toFloat())
            out.addRoundRect(holeRect, holeRad[0], holeRad[0], android.graphics.Path.Direction.CW)
        }
        fun button(b: GlassButton) {
            if (!b.shown || b.visibility != View.VISIBLE || !origin(b)) return
            out.addCircle(holeA.left - holeB.left + b.width / 2f, holeA.top - holeB.top + b.height / 2f,
                minOf(b.width, b.height) / 2f, android.graphics.Path.Direction.CW)
        }
        button(searchButton)
        for (b in editBar.buttons) button(b)
    }

    /** The set of pages behind the rail's glass changed: its backdrops re-record. */
    fun invalidateGlass() {
        if (!::editBar.isInitialized) return
        dock.invalidate()
        searchButton.invalidate()
        for (b in editBar.buttons) b.invalidate()
    }

    fun onWorkspaceScrolled(pos: Float) {
        if (indicator.pos != pos) indicator.pos = pos
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

    /** Scale currently applied to the Home layer (it recedes behind folders, menus and apps). */
    val homeScale: Float get() = home.scaleX * content.scaleX

    /**
     * 0 = Home in front, 1 = an app in front. As on the iPhone, Home blurs, dims and recedes
     * behind the app that is opening, and comes back with a long soft tail.
     */
    private val appDepth = SpringValue(0f, stiffness = 260f, damping = 1f) { v ->
        val e = v.coerceIn(0f, 1f)
        // Home only draws back a little behind the app, sharp and undimmed: enough for the app
        // to seem to come forward (and Home to open out again when it leaves), and it moves the
        // same way as the icon's own gesture, toward the middle.
        setBackdrop(e * APP_RECEDE, BackdropOwner.APP)
        // The icon's copy follows its icon, which has just moved.
        if (launchCard.active) launchCard.invalidate()
    }
    private lateinit var homeBlur: HomeBlur

    private fun smoothStep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }


    /** Blur + recede the home screen behind folders, the finder and menus. */
    fun setBackdrop(t: Float, owner: BackdropOwner) {
        backdrop[owner.ordinal] = t
        val e = backdrop.max()
        // Always apply the exact rest state, however small the last step.
        if (kotlin.math.abs(e - lastBackdrop) < 0.002f && !(e == 0f && lastBackdrop != 0f)) return
        lastBackdrop = e
        val p = profile ?: return
        // An app in front only makes Home recede here; its blur is HomeBlur's cross-fade.
        var blur = 0f
        for (o in BackdropOwner.values()) if (o != BackdropOwner.APP) blur = maxOf(blur, backdrop[o.ordinal])
        // Sharp Home and a blurred copy of it cross-fade (see HomeBlur); the blur radius itself is
        // never animated. A full-size blur whose radius changes every frame cost 20-35 ms a frame
        // on the Fold 8 (measured on the search pull) and stepped visibly; the copy is a quarter
        // of the size, blurred once.
        val want = if (blur > 0.003f) View.VISIBLE else View.INVISIBLE
        if (homeBlur.visibility != want) homeBlur.visibility = want
        homeBlur.alpha = smoothStep(0f, 0.6f, blur)
        // Never quite zero: Home keeps being drawn (invisibly, under its copy), so the frame in
        // which it starts to show again has nothing to set up.
        home.alpha = 1f - 0.99f * smoothStep(0.4f, 0.95f, blur)
        val s = 1f - 0.05f * e
        home.scaleX = s; home.scaleY = s
        homeBlur.scaleX = s; homeBlur.scaleY = s
    }

    private var lastRecede = 0f

    /**
     * Search takes the whole screen: the dock slides off toward its edge and the search button
     * fades, as on the Duo. Driven by the Finder's progress, so it follows the finger on a pull.
     */
    fun setChromeRecede(t: Float) {
        val e = t.coerceIn(0f, 1f)
        if (e == lastRecede) return
        lastRecede = e
        val p = profile ?: return
        if (p.dockVertical) dock.translationX = e * (p.width - p.dockRect.left)
        else dock.translationY = e * (p.height - p.dockRect.top)
        val a = 1f - (e * 2f).coerceAtMost(1f)
        searchButton.alpha = if (editMode) 0f else a
    }

    // ================================================================ apps

    private fun onAppsChanged() {
        // An empty list is never the truth (it means "not loaded yet" or a failed query), and
        // acting on it would strip the whole layout as uninstalled.
        if (!repo.loaded || repo.apps.isEmpty()) return
        // Filled from last time's cache while the system is still being asked: enough to show
        // Home, not to decide that an app is new or gone.
        if (!repo.authoritative) { if (model.initialized) rebindAll(); return }
        if (!model.initialized) buildDefaultLayout()
        val before = model.known.size
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
        // The system's list confirmed what the cache had already put on screen: leave it be.
        if (repo.unchangedSinceCache && boundOnce && gone.isEmpty() && model.known.size == before) return
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
        sharpening = false
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
        if (withCard) launchCard.launch(from!!, info.icon)
        if (own) playLaunch() else playFadeOut()
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
        if (withCard) root.postDelayed(start, 33) else start.run()
        if (finder.visibility == View.VISIBLE) root.postDelayed({ finder.close(animate = false) }, 350)
        // If the app never came up at all (we were never paused), undo the zoom. A slow cold
        // start (Camera) still pauses us, so it is left alone.
        pausedSinceLaunch = false
        stoppedSinceLaunch = false
        val stamp = lastLaunch
        root.postDelayed({ if (lastLaunch == stamp && !pausedSinceLaunch && hasWindowFocus()) playReturnHome() }, 2500)
        // An app that opens as a pop-up or with a see-through window never covers Home (we are
        // paused but not stopped): the full-screen card must not stay behind it.
        root.postDelayed({
            if (lastLaunch == stamp && pausedSinceLaunch && !stoppedSinceLaunch && started && !returnScheduled &&
                (cardActive || content.alpha != 1f || appDepth.value != 0f)) runReturnHome()
        }, 1000)
    }

    /** Home blurs, dims and recedes while the app grows out of its icon. */
    private fun playLaunch() {
        appDepth.anim.spring.stiffness = 375f
        appDepth.animateTo(1f)
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
    /** How far Home draws back behind an app, as a share of the 5% it gives a folder or the Finder. */
    private val APP_RECEDE = 0.5f
    private var lastViaGesture = true
    private var returnNow = false
    /** Home is already on its way back to sharp (started when the finger lifted). */
    private var sharpening = false
    private val returnRunnable = Runnable { runReturnHome() }

    private fun playReturnHome() {
        if (pendingUnlock || returnScheduled) return
        if (!model.appAnimation) {
            finishCards()
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
        val viaGesture = if (realReturn) prepareReturnCard() else false
        if (realReturn) {
            // Our window is not on screen yet: take the starting pose outright (Home blurred,
            // dim and receded behind the app), whatever the launch left behind.
            content.cancelSprings()
            content.translationX = 0f; content.translationY = 0f
            content.scaleX = 1f; content.scaleY = 1f
            content.alpha = 1f
            appDepth.snap(1f)
        }
        lastLaunchedIcon?.resetTouchState()
        if (!realReturn) runReturnHome()
        else {
            // One UI fades our window in over a quarter of a second from when the finger lifts
            // (after Back or the Home button it shows bare wallpaper first): the icon comes in
            // once there is something of it to see.
            returnScheduled = true
            root.postDelayed(returnRunnable, if (returnNow) 0 else if (viaGesture) 75 else 100)
            // Home opens out from when the finger lifts: One UI is fading it in just then.
            if (viaGesture) { appDepth.anim.spring.stiffness = 290f; appDepth.animateTo(0f); sharpening = true }
        }
    }

    private fun runReturnHome() {
        returnScheduled = false
        if (!model.appAnimation) { fadeHomeIn(); return }
        content.springScale(1f, 320f, 1f)
        content.springTo(DynamicAnimation.ALPHA, 1f, 320f, 1f)
        // Home takes its time to sharpen, well after the icon has landed.
        if (!sharpening) { appDepth.anim.spring.stiffness = 320f; appDepth.animateTo(0f) }
        sharpening = false
        if (launchCard.active) launchCard.retract()
        lastLaunchedIcon = null
        lastLaunchedInfo = null
    }

    /**
     * Decides which icon the closing app shrinks into. The app being left is not necessarily the
     * one launched from here: the user may have switched apps in Recents, opened one from a
     * notification, or swiped the launched one away in Recents (then there is nothing to shrink).
     * With usage access the app really in front is known; without it, only our own launch is.
     */
    private fun prepareReturnCard(): Boolean {
        // Home is back before the app had covered it (a quick swipe or Back right after the tap):
        // the icon is still out from the launch and turns round where it is, at once.
        returnNow = launchCard.opening
        if (returnNow) return lastViaGesture
        // Already on its way (Home is told more than once that it is coming back).
        if (launchCard.returning) return lastViaGesture
        val front = frontBeforeHome()
        val info: AppInfo? = when {
            // No usage access: trust our own launch, the best guess there is.
            front == null -> lastLaunchedInfo
            front.pkg.isEmpty() -> null
            else -> lastLaunchedInfo?.takeIf { it.packageName == front.pkg }
                ?: repo.apps.values.firstOrNull { it.packageName == front.pkg && it.user == android.os.Process.myUserHandle() }
                ?: repo.apps.values.firstOrNull { it.packageName == front.pkg }
        }
        dlog { "return: front=${front?.pkg} gesture=${front?.viaGesture} app=${info?.key}" }
        val viaGesture = front?.viaGesture ?: true
        lastViaGesture = viaGesture
        if (info == null) { finishCards(); return viaGesture }
        launchCard.prepareReturn(locateReturnIcon(info), info.icon)
        return viaGesture
    }

    private fun pkgOf(key: String) = repo[key]?.packageName

    /** True when [v] and everything up to the root is set visible (the window itself may not be yet). */
    fun shownInRoot(v: View): Boolean {
        var x: View? = v
        while (x != null && x !== root) {
            if (x.visibility != View.VISIBLE) return false
            x = x.parent as? View
        }
        return x === root
    }

    /**
     * The icon the app being left should shrink into, brought on screen if need be: the icon it
     * was opened from when that is still there (also inside an open folder), else its icon in
     * the dock, else on a page, turning to that page first like iOS does. A folder holding the
     * app stands in for it. Null when the app is not on the Home Screen at all.
     */
    private fun locateReturnIcon(info: AppInfo): IconView? {
        val pkg = info.packageName
        fun holds(it: HomeItem, exact: Boolean): Boolean = when (it) {
            is AppItem -> if (exact) it.key == info.key else pkgOf(it.key) == pkg
            is FolderItem -> it.apps.any { k -> if (exact) k == info.key else pkgOf(k) == pkg }
            else -> false
        }
        lastLaunchedIcon?.let { v ->
            if (v.role != Role.FINDER && v.isAttachedToWindow && shownInRoot(v) && holds(v.item, false) &&
                v.width > 0 && onScreen(v)) return v
        }
        // An open folder that is not where we are going would cover the landing.
        if (folder.isOpen) folder.close(animate = false)
        for (exact in booleanArrayOf(true, false)) {
            model.dock.firstOrNull { holds(it, exact) }?.let { return viewFor(it, Role.DOCK) as? IconView }
        }
        val vis = profile?.visiblePages ?: 1
        val cur = workspace.currentPage
        // Best page hit: the exact activity before another one of the same package, the app's
        // own icon before a folder holding it, a page already on screen before a page turn.
        var best: HomeItem? = null
        var bestPage = -1
        var bestScore = Int.MAX_VALUE
        for ((pi, page) in model.pages.withIndex()) for (it in page) {
            val exact = holds(it, true)
            if (!exact && !holds(it, false)) continue
            val score = (if (exact) 0 else 4) + (if (it is AppItem) 0 else 2) + (if (pi in cur until cur + vis) 0 else 1)
            if (score < bestScore) { best = it; bestPage = pi; bestScore = score }
        }
        val item = best ?: return null
        if (bestPage !in cur until cur + vis) {
            val to = if (bestPage < cur) bestPage else bestPage - (vis - 1)
            workspace.snapTo(to, animate = false)
        }
        return viewFor(item, Role.HOME) as? IconView
    }

    /** Whether the centre of [v] is inside the window (pages off to the side are not). */
    private fun onScreen(v: View): Boolean {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val cx = loc[0] + v.width * v.scaleX / 2f
        return cx > 0 && cx < root.width
    }

    fun hasUsageAccess(): Boolean {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * What was in front when Home was asked for: [pkg] is "" when no app window is on its way
     * out (Home came up from the Recents overview, where the last app had already left the
     * screen or was swiped away). [viaGesture]: the swipe-up gesture is bringing us back, during
     * which the system itself shrinks the app window to a card.
     */
    private class Front(val pkg: String, val viaGesture: Boolean)

    /** The package that draws Recents (and handles the Home gesture) on this device. */
    private val recentsPackage: String? by lazy {
        runCatching {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_recentsComponentName", "string", "android")
            android.content.ComponentName.unflattenFromString(res.getString(id))?.packageName
        }.getOrNull()
    }

    /** Wall-clock time Home last left the foreground; activity events after it tell what is in front. */
    private var leftHomeAt = 0L

    /** Null without usage access. */
    private fun frontBeforeHome(): Front? {
        if (!hasUsageAccess()) return null
        return runCatching {
            val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val begin = if (leftHomeAt in 1..now) leftHomeAt - 3_000L else now - 60 * 60_000L
            val events = usm.queryEvents(begin, now + 1_000L)
            val e = android.app.usage.UsageEvents.Event()
            var pkg = ""
            var cls: String? = null
            var stopped = false
            var recents = false
            val tail = if (debugLog) ArrayDeque<String>() else null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val t = e.eventType
                val resumed = t == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                val stop = t == android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
                if (!resumed && !stop) continue
                val p = e.packageName ?: continue
                tail?.let { it += "${now - e.timeStamp}ms ${if (resumed) "resumed" else "stopped"} $p/${e.className?.substringAfterLast('.')}"; if (it.size > 10) it.removeFirst() }
                if (p == packageName) {
                    // Home itself was in front again: whatever came before is not what we return from.
                    if (resumed) { pkg = ""; cls = null; stopped = false; recents = false }
                    continue
                }
                val isRecents = p == recentsPackage && e.className?.contains("Recents") == true
                if (resumed) {
                    // The Recents activity also runs under the Home gesture; it is never "the app".
                    if (isRecents) recents = true
                    else { pkg = p; cls = e.className; stopped = false; recents = false }
                } else if (p == pkg && e.className == cls) stopped = true
            }
            tail?.let { dlog { "events since leaving Home:\n" + it.joinToString("\n") } }
            // An app that was stopped is no longer on screen: it sat in the Recents overview (or
            // was swiped away there). During the gesture and after Back/Home it is only paused.
            Front(if (stopped) "" else pkg, recents)
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
        searchButton.setShown(false)
        indicator.setEditing(true)
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
        searchButton.setShown(true)
        indicator.setEditing(false)
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
        val editAction = MenuAction(t("编辑主屏幕", "Edit Home Screen"), separatorBefore = true, glyph = MenuGlyph.EDIT) { enterEditMode() }
        when {
            v is WidgetContainer -> {
                val info = widgetManager.getAppWidgetInfo(v.item.widgetId)
                if (info?.configure != null) actions += MenuAction(t("编辑小组件", "Edit Widget"), glyph = MenuGlyph.TUNE) {
                    runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, v.item.widgetId, 0, REQ_RECONFIG, null) }
                }
                for ((label, sx, sy) in listOf(
                    Triple(t("小 2×2", "Small 2×2"), 2, 2),
                    Triple(t("中 4×2", "Medium 4×2"), 4, 2),
                    Triple(t("大 4×4", "Large 4×4"), 4, 4),
                )) {
                    if (v.item.sx == sx && v.item.sy == sy) continue
                    actions += MenuAction(label, glyph = MenuGlyph.RESIZE) { resizeWidget(v.item, sx, sy) }
                }
                actions += editAction
                actions += MenuAction(t("移除小组件", "Remove Widget"), destructive = true, glyph = MenuGlyph.REMOVE) { onRemoveBadge(v) }
            }
            v is IconView && v.item is FolderItem -> {
                val f = v.item as FolderItem
                actions += MenuAction(t("重命名", "Rename"), glyph = MenuGlyph.RENAME) { folder.open(v) }
                actions += editAction
                actions += MenuAction(t("从主屏幕移除", "Remove from Home Screen"), destructive = true, glyph = MenuGlyph.REMOVE) {
                    removeWithAnimation(v) { model.remove(f); forgetView(f) }
                }
            }
            v is IconView -> {
                val info = v.appInfo ?: return
                for (sc in repo.shortcutsWithIcons(info)) {
                    actions += MenuAction(sc.info.shortLabel?.toString() ?: "", sc.icon) {
                        repo.startShortcut(sc.info, null)
                    }
                }
                if (v.role != Role.FINDER) actions += editAction
                if (v.role == Role.FINDER && !model.isOnHome(info.key)) {
                    actions += MenuAction(t("添加到主屏幕", "Add to Home Screen"), separatorBefore = actions.isNotEmpty(), glyph = MenuGlyph.ADD) {
                        model.appendToHome(AppItem(Ids.new(), info.key)); rebindAll(); save()
                    }
                }
                if (v.role == Role.HOME || v.role == Role.DOCK) {
                    actions += MenuAction(t("从主屏幕移除", "Remove from Home Screen"), glyph = MenuGlyph.REMOVE) {
                        removeWithAnimation(v) { model.remove(v.item); forgetView(v.item) }
                    }
                }
                if (v.role == Role.FOLDER) {
                    actions += MenuAction(t("移出文件夹", "Remove from Folder"), glyph = MenuGlyph.REMOVE) { onRemoveBadge(v) }
                }
                actions += MenuAction(t("隐藏 App", "Hide App"), glyph = MenuGlyph.HIDE) { if (v.role == Role.FOLDER) folder.close(animate = false); hideApp(info.key) }
                actions += MenuAction(t("App 信息", "App Info"), glyph = MenuGlyph.INFO) { repo.openDetails(info) }
                if (!info.isSystem) actions += MenuAction(t("卸载 App", "Uninstall App"), destructive = true, glyph = MenuGlyph.TRASH) {
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
