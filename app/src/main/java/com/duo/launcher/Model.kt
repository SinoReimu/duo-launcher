package com.duo.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Every Home Screen page uses the iPhone 4x6 grid, in all three screen modes. */
const val COLS = 4
const val ROWS = 6
const val DOCK_MAX = 4

object Ids {
    private var next = System.currentTimeMillis()
    @Synchronized fun new(): Long = ++next
}

sealed class HomeItem {
    abstract val id: Long
    open val spanX: Int get() = 1
    open val spanY: Int get() = 1
}

class AppItem(override val id: Long, val key: String) : HomeItem()

class FolderItem(override val id: Long, var name: String, val apps: MutableList<String>) : HomeItem()

class WidgetItem(
    override val id: Long,
    val widgetId: Int,
    val provider: String,
    var sx: Int,
    var sy: Int,
) : HomeItem() {
    override val spanX get() = sx
    override val spanY get() = sy
}

/** Reserves the cells where a dragged item will land. Never persisted. */
class PlaceholderItem(override val spanX: Int, override val spanY: Int) : HomeItem() {
    override val id: Long = -1L
}

/** iOS-style flow layout: items are placed in order, each into the first free cell that fits. */
object Packer {
    /** Returns the top-left cell index (row * COLS + col) of every item, or -1 when it does not fit. */
    fun pack(items: List<HomeItem>): IntArray {
        val occ = BooleanArray(COLS * ROWS)
        val out = IntArray(items.size) { -1 }
        for ((i, it) in items.withIndex()) {
            val sx = it.spanX.coerceIn(1, COLS)
            val sy = it.spanY.coerceIn(1, ROWS)
            search@ for (r in 0..ROWS - sy) for (c in 0..COLS - sx) {
                var free = true
                loop@ for (dy in 0 until sy) for (dx in 0 until sx) {
                    if (occ[(r + dy) * COLS + c + dx]) { free = false; break@loop }
                }
                if (free) {
                    for (dy in 0 until sy) for (dx in 0 until sx) occ[(r + dy) * COLS + c + dx] = true
                    out[i] = r * COLS + c
                    break@search
                }
            }
        }
        return out
    }

    fun fits(items: List<HomeItem>) = pack(items).none { it < 0 }
}

class HomeModel {
    val pages = ArrayList<ArrayList<HomeItem>>()
    val dock = ArrayList<HomeItem>()
    val hidden = LinkedHashSet<String>()
    val known = HashSet<String>()
    val launchCounts = HashMap<String, Int>()

    var swipeDownNotifications = false
    var addNewApps = true
    var showLabels = true
    var searchHidden = false
    var doubleTapLock = true
    /** Launcher-drawn app open/close motion; off = leave it entirely to the system. */
    var appAnimation = true
    var initialized = false

    fun allItems(): Sequence<HomeItem> = pages.asSequence().flatten() + dock.asSequence()

    fun isOnHome(key: String): Boolean = allItems().any {
        (it is AppItem && it.key == key) || (it is FolderItem && key in it.apps)
    }

    fun containerOf(item: HomeItem): ArrayList<HomeItem>? {
        if (dock.any { it === item }) return dock
        return pages.firstOrNull { p -> p.any { it === item } }
    }

    fun remove(item: HomeItem) {
        containerOf(item)?.removeAll { it === item }
    }

    fun folderOf(key: String): FolderItem? =
        allItems().filterIsInstance<FolderItem>().firstOrNull { key in it.apps }

    /** Removes every reference to an app key; returns the folders that changed. */
    fun removeKey(key: String) {
        for (list in pages + listOf(dock)) {
            list.removeAll { it is AppItem && it.key == key }
            for (f in list.filterIsInstance<FolderItem>()) f.apps.remove(key)
        }
        dissolveSmallFolders()
    }

    /** A folder with one app turns back into that app; an empty one disappears. */
    fun dissolveSmallFolders() {
        for (list in pages + listOf(dock)) {
            for (i in list.indices.reversed()) {
                val f = list[i] as? FolderItem ?: continue
                when (f.apps.size) {
                    0 -> list.removeAt(i)
                    1 -> list[i] = AppItem(Ids.new(), f.apps[0])
                }
            }
        }
    }

    /** Pushes items that no longer fit on a page to the front of the next page, cascading. */
    fun normalize(keepEmptyPages: Boolean) {
        var p = 0
        while (p < pages.size) {
            val page = pages[p]
            page.removeAll { it is PlaceholderItem }
            val pos = Packer.pack(page)
            val overflow = page.filterIndexed { i, _ -> pos[i] < 0 }
            if (overflow.isNotEmpty()) {
                page.removeAll { o -> overflow.any { it === o } }
                if (p + 1 >= pages.size) pages.add(ArrayList())
                pages[p + 1].addAll(0, overflow)
            }
            p++
        }
        if (!keepEmptyPages) pages.removeAll { it.isEmpty() }
        if (pages.isEmpty()) pages.add(ArrayList())
    }

    /** Puts an item on the last page that has room, creating a page when needed. */
    fun appendToHome(item: HomeItem) {
        if (pages.isEmpty()) pages.add(ArrayList())
        val last = pages.last()
        last.add(item)
        if (!Packer.fits(last)) {
            last.removeAt(last.size - 1)
            pages.add(arrayListOf(item))
        }
    }

    // ---------------------------------------------------------------- persistence

    fun toJson(): JSONObject {
        fun item(it: HomeItem): JSONObject? = when (it) {
            is AppItem -> JSONObject().put("t", "a").put("id", it.id).put("k", it.key)
            is FolderItem -> JSONObject().put("t", "f").put("id", it.id).put("n", it.name)
                .put("apps", JSONArray(it.apps))
            is WidgetItem -> JSONObject().put("t", "w").put("id", it.id).put("wid", it.widgetId)
                .put("p", it.provider).put("sx", it.sx).put("sy", it.sy)
            is PlaceholderItem -> null
        }
        fun list(l: List<HomeItem>) = JSONArray().also { a -> l.forEach { i -> item(i)?.let(a::put) } }
        return JSONObject()
            .put("v", 1)
            .put("pages", JSONArray().also { a -> pages.forEach { a.put(list(it)) } })
            .put("dock", list(dock))
            .put("hidden", JSONArray(hidden.toList()))
            .put("known", JSONArray(known.toList()))
            .put("counts", JSONObject(launchCounts as Map<*, *>))
            .put("swipeNotif", swipeDownNotifications)
            .put("addNew", addNewApps)
            .put("labels", showLabels)
            .put("searchHidden", searchHidden)
            .put("dtLock", doubleTapLock)
            .put("appAnim", appAnimation)
    }

    fun fromJson(o: JSONObject) {
        fun item(j: JSONObject): HomeItem? = when (j.optString("t")) {
            "a" -> AppItem(j.getLong("id"), j.getString("k"))
            "f" -> {
                val a = j.getJSONArray("apps")
                FolderItem(j.getLong("id"), j.optString("n"), MutableList(a.length()) { a.getString(it) })
            }
            "w" -> WidgetItem(j.getLong("id"), j.getInt("wid"), j.optString("p"), j.optInt("sx", 2), j.optInt("sy", 2))
            else -> null
        }
        fun list(a: JSONArray) = ArrayList<HomeItem>().also { l ->
            for (i in 0 until a.length()) item(a.getJSONObject(i))?.let(l::add)
        }
        pages.clear(); dock.clear(); hidden.clear(); known.clear(); launchCounts.clear()
        val ps = o.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until ps.length()) pages.add(list(ps.getJSONArray(i)))
        dock.addAll(list(o.optJSONArray("dock") ?: JSONArray()))
        o.optJSONArray("hidden")?.let { for (i in 0 until it.length()) hidden.add(it.getString(i)) }
        o.optJSONArray("known")?.let { for (i in 0 until it.length()) known.add(it.getString(i)) }
        o.optJSONObject("counts")?.let { c -> c.keys().forEach { k -> launchCounts[k] = c.optInt(k) } }
        swipeDownNotifications = o.optBoolean("swipeNotif", false)
        addNewApps = o.optBoolean("addNew", true)
        showLabels = o.optBoolean("labels", true)
        searchHidden = o.optBoolean("searchHidden", false)
        doubleTapLock = o.optBoolean("dtLock", true)
        appAnimation = o.optBoolean("appAnim", true)
        initialized = true
    }
}

/** Writes the model off the main thread, coalescing bursts of edits. */
class ModelStore(context: Context) {
    private val file = File(context.filesDir, "home.json")
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var pending: String? = null

    fun load(model: HomeModel) {
        if (!file.exists()) return
        runCatching { model.fromJson(JSONObject(file.readText())) }
    }

    fun save(model: HomeModel) {
        pending = model.toJson().toString()
        io.execute {
            val s = pending ?: return@execute
            pending = null
            val tmp = File(file.parentFile, "home.json.tmp")
            tmp.writeText(s)
            tmp.renameTo(file)
        }
    }
}
