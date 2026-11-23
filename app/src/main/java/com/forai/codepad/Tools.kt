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
