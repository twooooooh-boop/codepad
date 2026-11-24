package com.forai.codepad

/** Small Markdown to HTML converter used by the Markdown preview runner. */
object Markdown {
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val ITALIC = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])|(?<![_\\w])_([^_\\n]+)_(?![_\\w])")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val HR = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val UL = Regex("^\\s*[-*+]\\s+(.*)$")
    private val OL = Regex("^\\s*\\d+[.)]\\s+(.*)$")

    private fun inline(src: String): String {
        var s = esc(src)
        s = CODE.replace(s) { "<code>" + it.groupValues[1] + "</code>" }
        s = BOLD.replace(s) { "<strong>" + it.groupValues[1] + it.groupValues[2] + "</strong>" }
        s = ITALIC.replace(s) { "<em>" + it.groupValues[1] + it.groupValues[2] + "</em>" }
        s = LINK.replace(s) { m ->
            val url = m.groupValues[2]
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("#")) {
                "<a href=\"" + url + "\">" + m.groupValues[1] + "</a>"
            } else {
                m.value
            }
        }
        return s
    }

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var para = StringBuilder()
        var list: String? = null

        fun flushPara() {
            if (para.isNotEmpty()) {
                out.append("<p>").append(inline(para.toString().trim())).append("</p>\n")
                para = StringBuilder()
            }
        }

        fun closeList() {
            if (list != null) {
                out.append("</").append(list).append(">\n")
                list = null
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                flushPara()
                closeList()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(esc(lines[i])).append('\n')
                    i++
                }
                out.append("<pre><code>").append(code).append("</code></pre>\n")
                i++
                continue
            }
            val h = HEADING.find(line)
            if (h != null) {
                flushPara()
                closeList()
                val n = h.groupValues[1].length
                out.append("<h").append(n).append('>').append(inline(h.groupValues[2])).append("</h").append(n).append(">\n")
                i++
                continue
            }
            if (HR.matches(line)) {
                flushPara()
                closeList()
                out.append("<hr>\n")
                i++
                continue
            }
            if (line.startsWith(">")) {
                flushPara()
                closeList()
                out.append("<blockquote>").append(inline(line.removePrefix(">").trim())).append("</blockquote>\n")
                i++
                continue
            }
            val ul = UL.find(line)
            val ol = OL.find(line)
            if (ul != null || ol != null) {
                flushPara()
                val kind = if (ul != null) "ul" else "ol"
                if (list != kind) {
                    closeList()
                    out.append("<").append(kind).append(">\n")
                    list = kind
                }
                val text = if (ul != null) ul.groupValues[1] else ol!!.groupValues[1]
                out.append("<li>").append(inline(text)).append("</li>\n")
                i++
                continue
            }
            if (line.isBlank()) {
                flushPara()
                closeList()
                i++
                continue
            }
            closeList()
            para.append(line).append(' ')
            i++
        }
        flushPara()
        closeList()

        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>" +
                ":root{color-scheme:light dark}" +
                "body{font:16px/1.6 -apple-system,Roboto,sans-serif;margin:16px;max-width:760px}" +
                "pre{background:rgba(127,127,127,.15);padding:12px;border-radius:8px;overflow-x:auto}" +
                "code{background:rgba(127,127,127,.15);padding:1px 4px;border-radius:4px;font-family:monospace}" +
                "pre code{background:none;padding:0}" +
                "blockquote{margin:0;padding-left:12px;border-left:4px solid rgba(127,127,127,.5);opacity:.85}" +
                "hr{border:0;border-top:1px solid rgba(127,127,127,.4)}" +
                "</style></head><body>" + out + "</body></html>"
    }
}
