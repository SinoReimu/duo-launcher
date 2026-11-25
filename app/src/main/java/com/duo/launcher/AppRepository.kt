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
    /** Background colour of the icon (fallback for the launch card). */
    @Volatile var color: Int = 0xFF2C2C2E.toInt()
    /** The colour the app's own window starts with (its splash/window background), or 0. */
    @Volatile var splash: Int = 0
    val launchColor: Int get() = if (splash != 0) splash else color
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
    private val listeners = ArrayList<() -> Unit>()
    private val iconListeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun addIconListener(l: () -> Unit) { iconListeners += l }
    operator fun get(key: String) = apps[key]

    fun keyOf(cn: ComponentName, user: UserHandle) =
        cn.flattenToShortString() + "#" + userManager.getSerialNumberForUser(user)

    fun load() {
        worker.execute {
            val list = queryAll()
            main.post {
                apps.clear()
                list.forEach { apps[it.key] = it }
                loaded = true
                listeners.forEach { it() }
            }
            loadIcons(list)
            computeSearchKeys(list)
            list.forEach { it.splash = splashColor(it) }
        }
    }

    private fun queryAll(): List<AppInfo> {
        val out = ArrayList<AppInfo>()
        for (user in launcherApps.profiles) {
            for (ai in launcherApps.getActivityList(null, user)) out += toInfo(ai)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun toInfo(ai: LauncherActivityInfo) = AppInfo(
        keyOf(ai.componentName, ai.user),
        ai.componentName,
        ai.user,
        ai.label?.toString()?.trim().orEmpty().ifEmpty { ai.componentName.packageName },
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

    private fun cacheFile(info: AppInfo): File {
        val ai = info.tag
        val stamp = runCatching {
            context.packageManager.getPackageInfo(info.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val name = "v2_" + (info.key.hashCode().toUInt().toString(16)) + "_" + stamp + "_" + iconPx + "_" +
            (ai?.applicationInfo?.icon ?: 0) + ".png"
        return File(iconDir, name)
    }

    private fun loadIcon(info: AppInfo): Bitmap? {
        val f = cacheFile(info)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { sw ->
                info.color = edgeColor(sw)
                return sw.copy(Bitmap.Config.HARDWARE, false)?.also { sw.recycle() } ?: sw
            }
        }
        val ai = info.tag ?: return null
        val d = runCatching { ai.getIcon(context.resources.displayMetrics.densityDpi) }.getOrNull() ?: return null
        val badged = if (info.user != Process.myUserHandle())
            context.packageManager.getUserBadgedIcon(d, info.user) else d
        val bmp = render(badged)
        info.color = edgeColor(bmp)
        runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
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

    /** Average colour just inside the icon's edges, i.e. its background. */
    private fun edgeColor(b: Bitmap): Int {
        val s = b.width
        var r = 0; var g = 0; var bl = 0; var n = 0
        val inset = (s * 0.1f).toInt()
        for (k in 0 until 16) {
            val t = inset + (s - 2 * inset) * k / 15
            for ((x, y) in arrayOf(t to inset, t to s - 1 - inset, inset to t, s - 1 - inset to t)) {
                val p = b.getPixel(x, y)
                if ((p ushr 24) < 200) continue
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bl += p and 0xff; n++
            }
        }
        return if (n == 0) 0xFF2C2C2E.toInt() else Color.rgb(r / n, g / n, bl / n)
    }

    /**
     * Resolves the launch activity's theme to find the colour its window starts with: the
     * Android 12+ splash background, else a plain-colour window background. This makes the
     * launch card match what the app paints first, so the system cross-fade is seamless.
     */
    private fun splashColor(info: AppInfo): Int = runCatching {
        val pm = context.packageManager
        val ai = pm.getActivityInfo(info.component, 0)
        val themeRes = if (ai.themeResource != 0) ai.themeResource else ai.applicationInfo.theme
        if (themeRes == 0) return 0
        val pctx = context.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val theme = pctx.resources.newTheme().apply { applyStyle(themeRes, true) }
        val attrs = intArrayOf(
            android.R.attr.windowSplashScreenBackground,
            android.R.attr.windowBackground,
            android.R.attr.colorBackground,
        )
        val tv = android.util.TypedValue()
        for (attr in attrs) {
            // One attribute per call: theme lookups don't need a sorted array that way.
            val a = theme.obtainStyledAttributes(intArrayOf(attr))
            try {
                if (!a.getValue(0, tv)) continue
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                    tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) return tv.data or 0xFF000000.toInt()
                val d = runCatching { a.getDrawable(0) }.getOrNull()
                if (d is ColorDrawable) return d.color or 0xFF000000.toInt()
            } finally { a.recycle() }
        }
        0
    }.getOrDefault(0)

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
            fresh.forEach { it.splash = splashColor(it) }
            main.post {
                val serial = "#" + userManager.getSerialNumberForUser(user)
                apps.keys.filter { it.startsWith("$pkg/") && it.endsWith(serial) }.forEach { apps.remove(it) }
                fresh.forEach { apps[it.key] = it }
                val sorted = apps.values.sortedBy { it.label.lowercase() }
                apps.clear(); sorted.forEach { apps[it.key] = it }
                listeners.forEach { it() }
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

    fun shortcutIcon(s: android.content.pm.ShortcutInfo): Drawable? =
        runCatching { launcherApps.getShortcutIconDrawable(s, context.resources.displayMetrics.densityDpi) }.getOrNull()

    fun startShortcut(s: android.content.pm.ShortcutInfo, bounds: android.graphics.Rect?) {
        runCatching { launcherApps.startShortcut(s, bounds, null) }
    }
}
