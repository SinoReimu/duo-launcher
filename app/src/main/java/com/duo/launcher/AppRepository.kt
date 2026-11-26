package com.duo.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.Drawable
import android.icu.text.Transliterator
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign

class AppInfo(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val isSystem: Boolean,
) {
    @Volatile var icon: Bitmap? = null
    /** Name of the icon's file in the disk cache (it encodes the app's version). */
    @Volatile var iconFile: String? = null
    /** Lowercase label, full pinyin and pinyin initials, for Finder matching. */
    @Volatile var searchKeys: Array<String> = arrayOf(label.lowercase())
    val packageName: String get() = component.packageName
}

/** iOS-style continuous-corner squircle (superellipse, n = 5). */
object Squircle {
    fun path(size: Float, inset: Float = 0f): Path {
        val p = Path()
        val r = size / 2f - inset
        val c = size / 2f
        val n = 5.0
        val steps = 96
        for (i in 0 until steps) {
            val t = 2 * Math.PI * i / steps
            val ct = cos(t); val st = sin(t)
            val x = c + r * sign(ct) * abs(ct).pow(2 / n)
            val y = c + r * sign(st) * abs(st).pow(2 / n)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }
}

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val iconDir = File(context.cacheDir, "icons").apply { mkdirs() }
    val iconPx = (84 * context.resources.displayMetrics.density).toInt()

    /** Main-thread view of all launchable activities, keyed by "component#userSerial". */
    val apps = LinkedHashMap<String, AppInfo>()
    var loaded = false
        private set
    private val pendingRefresh = ArrayList<Pair<String, UserHandle>>()
    private val listeners = ArrayList<() -> Unit>()
    private val iconListeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun addIconListener(l: () -> Unit) { iconListeners += l }
    operator fun get(key: String) = apps[key]

    fun keyOf(cn: ComponentName, user: UserHandle) =
        cn.flattenToShortString() + "#" + userManager.getSerialNumberForUser(user)

    /**
     * True once the list comes from the system rather than from last time's cache. Only then may
     * Home conclude that an app is new or gone.
     */
    @Volatile var authoritative = false
        private set
    /** The last authoritative list was the same as the cached one Home had already shown. */
    var unchangedSinceCache = false
        private set
    /** Keys whose icons are wanted first (what Home shows), asked on the worker thread. */
    @Volatile var firstIcons: () -> List<String> = { emptyList() }
    private val listCache = File(context.cacheDir, "apps_v1.tsv")

    /**
     * Asking the system for every app's name takes about a second and a half on a cold start
     * (150 apps: each label is read out of that app's own resources). So Home is first filled
     * from what was found last time, names and icons straight off the disk, and the real query
     * then runs behind it and only disturbs Home if something has changed.
     */
    fun load() {
        worker.execute {
            val t0 = android.os.SystemClock.uptimeMillis()
            val cached = readListCache()
            if (cached.isNotEmpty()) {
                main.post {
                    if (!loaded) {
                        apps.clear()
                        cached.forEach { apps[it.key] = it }
                        loaded = true
                        listeners.forEach { it() }
                    }
                }
                loadCachedIcons(cached)
            }
            val t1 = android.os.SystemClock.uptimeMillis()
            val old = cached.associateBy { it.key }
            val list = queryAll(old, cachedLocale == localeTag())
            val t2 = android.os.SystemClock.uptimeMillis()
            // What the cache already gave keeps its bitmap: same app, same version, same icon.
            for (info in list) {
                val o = old[info.key] ?: continue
                if (o.icon != null && o.iconFile == info.iconFile) info.icon = o.icon
            }
            val same = cached.size == list.size && list.all { old[it.key]?.label == it.label && old[it.key]?.iconFile == it.iconFile }
            main.post {
                apps.clear()
                list.forEach { apps[it.key] = it }
                loaded = true
                authoritative = true
                unchangedSinceCache = same
                listeners.forEach { it() }
                // Package events that arrived while the first query was running: apply them now.
                val late = ArrayList(pendingRefresh)
                pendingRefresh.clear()
                late.forEach { (pkg, user) -> refreshPackage(pkg, user) }
            }
            loadIcons(list.filter { it.icon == null })
            val t3 = android.os.SystemClock.uptimeMillis()
            computeSearchKeys(list)
            writeListCache(list)
            pruneIconCache(list)
            dlog { "load: ${list.size} apps; from cache ${t1 - t0} ms (${cached.size}), query ${t2 - t1} ms, missing icons ${t3 - t2} ms, same=$same" }
        }
    }

    private fun localeTag() = context.resources.configuration.locales.toLanguageTags()
    private var cachedLocale: String? = null

    private fun readListCache(): List<AppInfo> = runCatching {
        if (!listCache.exists()) return emptyList()
        val out = ArrayList<AppInfo>()
        listCache.forEachLine { line ->
            if (line.startsWith("#locale=")) { cachedLocale = line.substring(8); return@forEachLine }
            val f = line.split('\t', limit = 6)
            if (f.size < 6) return@forEachLine
            val cn = ComponentName.unflattenFromString(f[1]) ?: return@forEachLine
            val user = userManager.getUserForSerialNumber(f[2].toLongOrNull() ?: return@forEachLine) ?: return@forEachLine
            out += AppInfo(f[0], cn, user, f[5], f[3] == "1").also { it.iconFile = f[4].ifEmpty { null } }
        }
        out
    }.getOrDefault(emptyList())

    private fun writeListCache(list: List<AppInfo>) {
        runCatching {
            val tmp = File(listCache.parentFile, listCache.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write("#locale=" + localeTag()); w.newLine()
                for (i in list) {
                    w.write(listOf(
                        i.key, i.component.flattenToString(), userManager.getSerialNumberForUser(i.user).toString(),
                        if (i.isSystem) "1" else "0", i.iconFile.orEmpty(), i.label.replace('\t', ' ').replace('\n', ' '),
                    ).joinToString("\t"))
                    w.newLine()
                }
            }
            tmp.renameTo(listCache)
        }
    }

    /** Icons of a cached list, straight from their files; what Home shows comes first. */
    private fun loadCachedIcons(list: List<AppInfo>) {
        val want = runCatching { firstIcons() }.getOrDefault(emptyList())
        val rank = HashMap<String, Int>().apply { want.forEachIndexed { i, k -> putIfAbsent(k, i) } }
        var lastPost = 0L
        for (info in list.sortedBy { rank[it.key] ?: Int.MAX_VALUE }) {
            val name = info.iconFile ?: continue
            info.icon = decodeIcon(File(iconDir, name)) ?: continue
            val now = System.nanoTime()
            if (now - lastPost > 30_000_000L) {
                lastPost = now
                main.post { iconListeners.forEach { it() } }
            }
        }
        main.post { iconListeners.forEach { it() } }
    }

    /** Icon files of versions that are no longer installed. */
    private fun pruneIconCache(list: List<AppInfo>) {
        val keep = list.mapNotNullTo(HashSet()) { it.iconFile }
        runCatching { iconDir.listFiles()?.forEach { if (it.name !in keep) it.delete() } }
    }

    /**
     * Every launchable activity. An app's name is read out of that app's own resources, which
     * is what makes this slow and, for a few seconds, maps a few hundred megabytes of other
     * apps' packages into this process; so a name found last time is kept as long as the app
     * has not been updated since (its icon file name says so) and the language is the same.
     */
    private fun queryAll(old: Map<String, AppInfo> = emptyMap(), sameLocale: Boolean = false): List<AppInfo> {
        val out = ArrayList<AppInfo>()
        for (user in launcherApps.profiles) {
            for (ai in launcherApps.getActivityList(null, user)) {
                val key = keyOf(ai.componentName, ai.user)
                val file = iconFileName(key, ai)
                val known = old[key]?.takeIf { sameLocale && it.iconFile == file }
                out += toInfo(ai, key, known?.label).also { it.iconFile = file }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun toInfo(ai: LauncherActivityInfo, key: String = keyOf(ai.componentName, ai.user), label: String? = null) = AppInfo(
        key,
        ai.componentName,
        ai.user,
        label ?: ai.label?.toString()?.trim().orEmpty().ifEmpty { ai.componentName.packageName },
        (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
    ).also { it.tag = ai }

    private var AppInfo.tag: LauncherActivityInfo?
        get() = tags[key]
        set(v) { if (v != null) tags[key] = v else tags.remove(key) }
    private val tags = java.util.concurrent.ConcurrentHashMap<String, LauncherActivityInfo>()

    private fun loadIcons(list: List<AppInfo>) {
        // Batch UI invalidations so a cold start does not redraw 200 times.
        var lastPost = 0L
        for (info in list) {
            info.icon = loadIcon(info)
            val now = System.nanoTime()
            if (now - lastPost > 40_000_000L) {
                lastPost = now
                main.post { iconListeners.forEach { it() } }
            }
        }
        main.post { iconListeners.forEach { it() } }
    }

    /** The icon's file: its name changes when the app is updated, so a stale one is never read. */
    private fun iconFileName(key: String, ai: LauncherActivityInfo?): String {
        val pkg = ai?.componentName?.packageName ?: key.substringBefore('/')
        val stamp = runCatching { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        return "v2_" + (key.hashCode().toUInt().toString(16)) + "_" + stamp + "_" + iconPx + "_" +
            (ai?.applicationInfo?.icon ?: 0) + ".png"
    }

    private fun cacheFile(info: AppInfo) = File(iconDir, info.iconFile ?: iconFileName(info.key, info.tag))

    private fun loadIcon(info: AppInfo): Bitmap? {
        val f = cacheFile(info)
        info.iconFile = f.name
        decodeIcon(f)?.let { return it }
        val ai = info.tag ?: return null
        val d = runCatching { ai.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull() ?: return null
        val badged = if (info.user != Process.myUserHandle())
            context.packageManager.getUserBadgedIcon(d, info.user) else d
        val bmp = render(badged)
        runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
    }

    /** A cached icon as a hardware bitmap (it lives in graphics memory, not on the Java heap). */
    private fun decodeIcon(f: File): Bitmap? {
        if (!f.exists()) return null
        val sw = BitmapFactory.decodeFile(f.path) ?: return null
        return sw.copy(Bitmap.Config.HARDWARE, false)?.also { sw.recycle() } ?: sw
    }

    private val mask by lazy { Squircle.path(iconPx.toFloat()) }

    /** Renders any icon into the squircle shape. Adaptive icons fill the shape edge to edge. */
    fun render(d: Drawable): Bitmap {
        val s = iconPx
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(mask)
        val legacy = legacyArt(d)
        if (legacy == null && d is AdaptiveIconDrawable) {
            // The visible 72dp area of a 108dp adaptive icon should fill the whole squircle.
            val pad = (s * 0.25f).toInt()
            d.background?.let { it.setBounds(-pad, -pad, s + pad, s + pad); it.draw(c) }
            d.foreground?.let { it.setBounds(-pad, -pad, s + pad, s + pad); it.draw(c) }
        } else {
            drawLegacy(c, legacy ?: d, s)
        }
        return bmp
    }

    /**
     * Legacy (pre-adaptive) artwork, either a plain drawable or one the system wrapped into an
     * adaptive icon on a flat background. Returns null for real adaptive icons.
     */
    private fun legacyArt(d: Drawable): Drawable? {
        if (d !is AdaptiveIconDrawable) return d
        val fg = d.foreground ?: return null
        val bg = d.background
        val flatBg = bg == null || bg is ColorDrawable
        val inner = (fg as? DrawableWrapper)?.drawable
        return if (flatBg && inner is BitmapDrawable) inner else null
    }

    private class Art(val box: Rect, val coverage: Float, val edgeColor: Int)

    /** Opaque bounds, how much of them is filled, and the colour along the artwork's edge. */
    private fun analyze(b: Bitmap): Art? {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        var solid = 0
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val ring = maxOf(2, (maxX - minX) / 20)
        for (y in minY..maxY) for (x in minX..maxX) {
            val p = px[y * w + x]
            val a = p ushr 24
            if (a > 128) solid++
            val nearEdge = x - minX < ring || maxX - x < ring || y - minY < ring || maxY - y < ring
            if (a > 220 && nearEdge) { r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bl += p and 0xff; n++ }
        }
        val area = (maxX - minX + 1) * (maxY - minY + 1)
        val edge = if (n == 0) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        return Art(Rect(minX, minY, maxX + 1, maxY + 1), solid / area.toFloat(), edge)
    }

    private fun drawLegacy(c: Canvas, d: Drawable, s: Int) {
        val tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(tmp))
        val art = analyze(tmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (art == null) { c.drawColor(Color.WHITE); return }
        val box = art.box
        val squareish = box.width() > 0 && box.height().toFloat() / box.width() in 0.85f..1.18f
        when {
            // Rounded-square artwork: blow it up so its own (smaller) corners fall outside our
            // mask. This is what removes the thin white/transparent rim.
            squareish && art.coverage > 0.86f -> {
                c.drawColor(art.edgeColor)
                val o = s * 0.045f
                c.drawBitmap(tmp, box, RectF(-o, -o, s + o, s + o), paint)
            }
            // Round artwork: fill the corners with the artwork's own edge colour, not white.
            squareish && art.coverage > 0.70f -> {
                c.drawColor(art.edgeColor)
                c.drawBitmap(tmp, box, RectF(0f, 0f, s.toFloat(), s.toFloat()), paint)
            }
            // Free-form logo: white tile with the logo centred, like iOS does for odd shapes.
            else -> {
                c.drawColor(Color.WHITE)
                val k = s * 0.72f / maxOf(box.width(), box.height())
                val dw = box.width() * k; val dh = box.height() * k
                c.drawBitmap(tmp, box, RectF((s - dw) / 2, (s - dh) / 2, (s + dw) / 2, (s + dh) / 2), paint)
            }
        }
        tmp.recycle()
    }

    private fun computeSearchKeys(list: List<AppInfo>) {
        val tr = runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }.getOrNull()
        for (info in list) {
            val lower = info.label.lowercase()
            if (tr == null || lower.all { it.code < 128 }) {
                info.searchKeys = arrayOf(lower, initials(lower))
                continue
            }
            val py = tr.transliterate(info.label)
            info.searchKeys = arrayOf(lower, py.replace(" ", ""), initials(py))
        }
    }

    private fun initials(s: String) = s.split(' ', '-', '_', '.').filter { it.isNotEmpty() }
        .joinToString("") { it.first().toString() }

    // ---------------------------------------------------------------- package changes

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refreshPackage(packageName, user)
        override fun onPackageAdded(packageName: String, user: UserHandle) = refreshPackage(packageName, user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = refreshPackage(packageName, user)
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = p.forEach { refreshPackage(it, user) }
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = p.forEach { refreshPackage(it, user) }
    }

    fun register() = launcherApps.registerCallback(callback, main)

    private fun refreshPackage(pkg: String, user: UserHandle) {
        worker.execute {
            val fresh = launcherApps.getActivityList(pkg, user).map { toInfo(it) }
            fresh.forEach { it.icon = loadIcon(it) }
            computeSearchKeys(fresh)
            main.post {
                // Before the first full list is in, `apps` is empty: telling listeners now would
                // make every app on the Home Screen look uninstalled. Redo this after the load.
                if (!loaded) { pendingRefresh += pkg to user; return@post }
                val serial = "#" + userManager.getSerialNumberForUser(user)
                apps.keys.filter { it.startsWith("$pkg/") && it.endsWith(serial) }.forEach { apps.remove(it) }
                fresh.forEach { apps[it.key] = it }
                val sorted = apps.values.sortedBy { it.label.lowercase() }
                apps.clear(); sorted.forEach { apps[it.key] = it }
                unchangedSinceCache = false
                listeners.forEach { it() }
                val snapshot = ArrayList(apps.values)
                worker.execute { writeListCache(snapshot) }
            }
        }
    }

    // ---------------------------------------------------------------- launching & shortcuts

    fun start(info: AppInfo, bounds: android.graphics.Rect?, opts: android.os.Bundle?) {
        launcherApps.startMainActivity(info.component, info.user, bounds, opts)
    }

    fun openDetails(info: AppInfo) {
        launcherApps.startAppDetailsActivity(info.component, info.user, null, null)
    }

    fun shortcuts(info: AppInfo): List<android.content.pm.ShortcutInfo> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(info.packageName)
            .setActivity(info.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        launcherApps.getShortcuts(q, info.user).orEmpty()
            .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
            .take(4)
    }.getOrDefault(emptyList())

    /** An app's shortcuts with their icons, as the long-press menu shows them. */
    class Shortcut(val info: android.content.pm.ShortcutInfo, val icon: Drawable?)

    @Volatile private var readyKey: String? = null
    @Volatile private var readyAt = 0L
    @Volatile private var ready: List<Shortcut> = emptyList()

    private fun loadShortcuts(info: AppInfo) = shortcuts(info).map { Shortcut(it, shortcutIcon(it)) }

    /**
     * Asked when a finger goes down on an icon: the query and the icons are several calls into
     * the system (some 70 ms together), and made when the menu opens they hold up its first
     * frame. A long press takes half a second, which is plenty to have them ready.
     */
    fun prefetchShortcuts(info: AppInfo) {
        if (readyKey == info.key && android.os.SystemClock.uptimeMillis() - readyAt < 1500) return
        worker.execute {
            val list = runCatching { loadShortcuts(info) }.getOrDefault(emptyList())
            ready = list; readyKey = info.key; readyAt = android.os.SystemClock.uptimeMillis()
        }
    }

    fun shortcutsWithIcons(info: AppInfo): List<Shortcut> =
        if (readyKey == info.key && android.os.SystemClock.uptimeMillis() - readyAt < 4000) ready else loadShortcuts(info)

    fun shortcutIcon(s: android.content.pm.ShortcutInfo): Drawable? =
        runCatching { launcherApps.getShortcutIconDrawable(s, context.resources.displayMetrics.densityDpi) }.getOrNull()

    fun startShortcut(s: android.content.pm.ShortcutInfo, bounds: android.graphics.Rect?) {
        runCatching { launcherApps.startShortcut(s, bounds, null) }
    }
}
