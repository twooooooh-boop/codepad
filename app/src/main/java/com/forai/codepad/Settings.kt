package com.forai.codepad

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One auto-close rule: typing [open] inserts [close] after it. Both may be several characters. */
class PairDef(val open: String, val close: String)

class AppSettings(ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("codepad", Context.MODE_PRIVATE)

    companion object {
        val DEFAULT_PAIRS: List<PairDef> = listOf(
            PairDef("(", ")"),
            PairDef("[", "]"),
            PairDef("{", "}"),
            PairDef("\"", "\""),
            PairDef("'", "'"),
            PairDef("`", "`")
        )

        private val KEYS = listOf(
            "theme", "font", "wrap", "lines", "indent", "tabsize", "spaces", "autosave",
            "autoclose", "bracket", "guides", "trim", "finalnl", "pairs", "ws", "pairbs"
        )
    }

    var theme: String?
        get() = prefs.getString("theme", null)
        set(v) {
            if (v == null) prefs.edit().remove("theme").apply() else prefs.edit().putString("theme", v).apply()
        }

    var font: Float
        get() = prefs.getFloat("font", 14f)
        set(v) = prefs.edit().putFloat("font", v).apply()

    var wrap: Boolean
        get() = prefs.getBoolean("wrap", true)
        set(v) = prefs.edit().putBoolean("wrap", v).apply()

    var lines: Boolean
        get() = prefs.getBoolean("lines", true)
        set(v) = prefs.edit().putBoolean("lines", v).apply()

    var autoIndent: Boolean
        get() = prefs.getBoolean("indent", true)
        set(v) = prefs.edit().putBoolean("indent", v).apply()

    var tabSize: Int
        get() = prefs.getInt("tabsize", 4)
        set(v) = prefs.edit().putInt("tabsize", v).apply()

    var spaces: Boolean
        get() = prefs.getBoolean("spaces", true)
        set(v) = prefs.edit().putBoolean("spaces", v).apply()

    var autosave: Boolean
        get() = prefs.getBoolean("autosave", false)
        set(v) = prefs.edit().putBoolean("autosave", v).apply()

    var autoClose: Boolean
        get() = prefs.getBoolean("autoclose", true)
        set(v) = prefs.edit().putBoolean("autoclose", v).apply()

    var bracketMatch: Boolean
        get() = prefs.getBoolean("bracket", true)
        set(v) = prefs.edit().putBoolean("bracket", v).apply()

    var guides: Boolean
        get() = prefs.getBoolean("guides", true)
        set(v) = prefs.edit().putBoolean("guides", v).apply()

    var showWhitespace: Boolean
        get() = prefs.getBoolean("ws", false)
        set(v) = prefs.edit().putBoolean("ws", v).apply()

    var pairBackspace: Boolean
        get() = prefs.getBoolean("pairbs", true)
        set(v) = prefs.edit().putBoolean("pairbs", v).apply()

    var trimOnSave: Boolean
        get() = prefs.getBoolean("trim", false)
        set(v) = prefs.edit().putBoolean("trim", v).apply()

    var finalNewline: Boolean
        get() = prefs.getBoolean("finalnl", false)
        set(v) = prefs.edit().putBoolean("finalnl", v).apply()

    var pairs: List<PairDef>
        get() {
            val raw = prefs.getString("pairs", null) ?: return DEFAULT_PAIRS
            return try {
                parsePairs(JSONArray(raw))
            } catch (e: Exception) {
                DEFAULT_PAIRS
            }
        }
        set(v) = prefs.edit().putString("pairs", pairsToJson(v).toString()).apply()

    private fun pairsToJson(list: List<PairDef>): JSONArray {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("trigger", p.open)
            o.put("completion", p.close)
            arr.put(o)
        }
        return arr
    }

    private fun parsePairs(arr: JSONArray): List<PairDef> {
        val out = ArrayList<PairDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optString("trigger", "")
            val c = o.optString("completion", "")
            if (t.isNotEmpty() && c.isNotEmpty() && out.none { it.open == t }) out.add(PairDef(t, c))
        }
        return out
    }

    /** All preferences as pretty JSON (recent-files list and open tabs are not included). */
    fun exportJson(): String {
        val o = JSONObject()
        o.put("app", "CodePad")
        o.put("version", 1)
        theme?.let { o.put("theme", it) }
        o.put("fontSize", font.toDouble())
        o.put("wordWrap", wrap)
        o.put("lineNumbers", lines)
        o.put("autoIndent", autoIndent)
        o.put("tabSize", tabSize)
        o.put("useSpaces", spaces)
        o.put("autoSaveOnExit", autosave)
        o.put("autoClose", autoClose)
        o.put("bracketMatching", bracketMatch)
        o.put("indentGuides", guides)
        o.put("showWhitespace", showWhitespace)
        o.put("smartBackspace", pairBackspace)
        o.put("trimTrailingOnSave", trimOnSave)
        o.put("finalNewlineOnSave", finalNewline)
        o.put("autoClosePairs", pairsToJson(pairs))
        return o.toString(2)
    }

    /** Applies every recognised key found in [json]. Returns how many settings were applied. */
    fun importJson(json: String): Int {
        val o = JSONObject(json)
        var n = 0
        if (o.has("theme") && Themes.byName(o.optString("theme")) != null) { theme = o.getString("theme"); n++ }
        if (o.has("fontSize")) { font = o.getDouble("fontSize").toFloat().coerceIn(8f, 40f); n++ }
        if (o.has("wordWrap")) { wrap = o.getBoolean("wordWrap"); n++ }
        if (o.has("lineNumbers")) { lines = o.getBoolean("lineNumbers"); n++ }
        if (o.has("autoIndent")) { autoIndent = o.getBoolean("autoIndent"); n++ }
        if (o.has("tabSize")) { tabSize = o.getInt("tabSize").coerceIn(1, 16); n++ }
        if (o.has("useSpaces")) { spaces = o.getBoolean("useSpaces"); n++ }
        if (o.has("autoSaveOnExit")) { autosave = o.getBoolean("autoSaveOnExit"); n++ }
        if (o.has("autoClose")) { autoClose = o.getBoolean("autoClose"); n++ }
        if (o.has("bracketMatching")) { bracketMatch = o.getBoolean("bracketMatching"); n++ }
        if (o.has("indentGuides")) { guides = o.getBoolean("indentGuides"); n++ }
        if (o.has("showWhitespace")) { showWhitespace = o.getBoolean("showWhitespace"); n++ }
        if (o.has("smartBackspace")) { pairBackspace = o.getBoolean("smartBackspace"); n++ }
        if (o.has("trimTrailingOnSave")) { trimOnSave = o.getBoolean("trimTrailingOnSave"); n++ }
        if (o.has("finalNewlineOnSave")) { finalNewline = o.getBoolean("finalNewlineOnSave"); n++ }
        if (o.has("autoClosePairs")) { pairs = parsePairs(o.getJSONArray("autoClosePairs")); n++ }
        if (n == 0) throw IllegalArgumentException("No CodePad settings found in this file")
        return n
    }

    fun resetAll() {
        val e = prefs.edit()
        for (k in KEYS) e.remove(k)
        e.apply()
    }
}
