package app.docscan.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

internal class FilePrefixPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPrefixes(): List<String> {
        val rawJson = prefs.getString(KEY_PREFIXES, null) ?: return DEFAULT_PREFIXES
        return try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optString(i).trim()
                if (item.isNotEmpty() && !list.contains(item)) {
                    list.add(item)
                }
            }
            if (list.isEmpty()) DEFAULT_PREFIXES else list
        } catch (_: Exception) {
            DEFAULT_PREFIXES
        }
    }

    fun savePrefixes(prefixes: List<String>) {
        val cleanList = prefixes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val jsonArray = JSONArray()
        cleanList.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_PREFIXES, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "docscan_prefix_preferences"
        private const val KEY_PREFIXES = "saved_prefixes"
        val DEFAULT_PREFIXES = listOf("договор за газ", "договор за воду", "акт")
    }
}
