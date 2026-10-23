package br.com.emanuel.navegador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PageRecord(val title: String, val url: String, val time: Long = System.currentTimeMillis())
data class SavedTab(val id: Long, val title: String, val url: String?, val desktop: Boolean = false)

class BrowserStore(context: Context) {
    val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    fun pages(key: String): List<PageRecord> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val url = item.optString("url")
            if (!UrlPolicy.isWebUrl(url)) null else PageRecord(item.optString("title").take(300), url, item.optLong("time"))
        }
    }.getOrDefault(emptyList())
    fun savePages(key: String, pages: List<PageRecord>) {
        val array = JSONArray()
        pages.take(500).forEach { p -> array.put(JSONObject().put("title", p.title.take(300)).put("url", p.url).put("time", p.time)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
    fun visit(title: String, url: String) {
        if (!UrlPolicy.isWebUrl(url)) return
        savePages("history", listOf(PageRecord(title, url)) + pages("history").filterNot { it.url == url })
    }
    fun toggleBookmark(title: String, url: String): Boolean {
        val all = pages("bookmarks")
        val adding = all.none { it.url == url }
        savePages("bookmarks", if (adding) listOf(PageRecord(title, url)) + all else all.filterNot { it.url == url })
        return adding
    }
    fun tabs(): List<SavedTab> = runCatching {
        val array = JSONArray(prefs.getString("tabs", "[]"))
        (0 until minOf(array.length(), 30)).map { i ->
            val item = array.getJSONObject(i)
            val url = item.optString("url").takeIf { UrlPolicy.isWebUrl(it) }
            SavedTab(item.getLong("id"), item.optString("title").take(300), url, item.optBoolean("desktop"))
        }
    }.getOrDefault(emptyList())
    fun saveTabs(tabs: List<SavedTab>, active: Long) {
        val array = JSONArray()
        tabs.forEach { t -> array.put(JSONObject().put("id", t.id).put("title", t.title).put("url", t.url ?: "").put("desktop", t.desktop)) }
        prefs.edit().putString("tabs", array.toString()).putLong("active", active).apply()
    }
}
