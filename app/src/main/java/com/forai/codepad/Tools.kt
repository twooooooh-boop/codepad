package com.forai.codepad

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Text transformations used by the "Text tools" menu. Pure functions on strings. */
object Tools {

    /** Throws IllegalArgumentException with a readable message if [src] isn't valid JSON. */
    private fun validateJson(src: String) {
        val s = src.trim()
        try {
            if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid JSON: " + (e.message ?: "parse error"))
        }
    }

    fun formatJson(src: String, unit: String): String {
        validateJson(src)
        val sb = StringBuilder()
        var depth = 0
        var inStr = false
        var esc = false
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (inStr) {
                sb.append(c)
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                i++
                continue
            }
            when (c) {
                '"' -> {
                    inStr = true
                    sb.append(c)
                }
                '{', '[' -> {
                    var j = i + 1
                    while (j < n && src[j].isWhitespace()) j++
                    if (j < n && (src[j] == '}' || src[j] == ']')) {
                        sb.append(c).append(src[j])
                        i = j
                    } else {
                        sb.append(c)
                        depth++
                        sb.append('\n')
                        for (k in 0 until depth) sb.append(unit)
                    }
                }
                '}', ']' -> {
                    depth = if (depth > 0) depth - 1 else 0
                    sb.append('\n')
                    for (k in 0 until depth) sb.append(unit)
                    sb.append(c)
                }
                ',' -> {
                    sb.append(c)
                    sb.append('\n')
                    for (k in 0 until depth) sb.append(unit)
                }
                ':' -> sb.append(": ")
                ' ', '\t', '\n', '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun minifyJson(src: String): String {
        validateJson(src)
        val sb = StringBuilder()
        var inStr = false
        var esc = false
        for (c in src) {
            if (inStr) {
                sb.append(c)
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else if (c == '"') {
                inStr = true
                sb.append(c)
            } else if (!c.isWhitespace()) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun sortLines(src: String, descending: Boolean): String {
        val lines = src.split("\n").sortedWith(String.CASE_INSENSITIVE_ORDER)
        return (if (descending) lines.reversed() else lines).joinToString("\n")
    }

    fun uniqueLines(src: String): String = src.split("\n").distinct().joinToString("\n")

    fun trimTrailing(src: String): String =
        src.split("\n").joinToString("\n") { it.trimEnd(' ', '\t') }

    fun tabsToSpaces(src: String, tabSize: Int): String = src.replace("\t", " ".repeat(tabSize))

    fun spacesToTabs(src: String, tabSize: Int): String =
        src.split("\n").joinToString("\n") { line ->
            var k = 0
            while (k < line.length && line[k] == ' ') k++
            "\t".repeat(k / tabSize) + " ".repeat(k % tabSize) + line.substring(k)
        }
}

/** Guesses whether a file is indented with tabs or spaces, and how wide one level is. */
object IndentDetect {
    /** Returns (useSpaces, width) or null when the file gives no clear signal. */
    fun detect(text: String): Pair<Boolean, Int>? {
        var tabLines = 0
        var spaceLines = 0
        val deltas = IntArray(9)
        var prev = 0
        var pos = 0
        var seen = 0
        val n = text.length
        while (pos < n && seen < 3000) {
            var e = text.indexOf('\n', pos)
            if (e < 0) e = n
            var i = pos
            var sp = 0
            while (i < e && text[i] == ' ') {
                sp++
                i++
            }
            val blank = i >= e || text[i] == '\r'
            if (!blank) {
                seen++
                if (text[i] == '\t') {
                    tabLines++
                } else {
                    if (sp > 0) spaceLines++
                    val d = if (sp > prev) sp - prev else prev - sp
                    if (d in 1..8) deltas[d]++
                    prev = sp
                }
            }
            pos = e + 1
        }
        if (tabLines >= 3 && tabLines >= spaceLines * 2) return Pair(false, 4)
        if (spaceLines >= 3 && spaceLines >= tabLines * 2) {
            var best = 4
            var bestScore = -1
            for (k in intArrayOf(4, 2, 8, 3)) {
                if (deltas[k] > bestScore) {
                    bestScore = deltas[k]
                    best = k
                }
            }
            return Pair(true, best)
        }
        return null
    }
}
