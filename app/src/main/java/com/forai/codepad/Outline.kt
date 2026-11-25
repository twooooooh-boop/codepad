package com.forai.codepad

import java.util.regex.Pattern

/** Finds functions, classes and headings in the open file for "Go to symbol". Regex based, so it is approximate. */
object Outline {
    class Item(val line: Int, val label: String)

    private class Rule(val pattern: Pattern, val kindGroup: Int, val nameGroup: Int)

    private fun rule(re: String, kind: Int, name: Int, flags: Int = 0): Rule =
        Rule(Pattern.compile(re, Pattern.MULTILINE or flags), kind, name)

    private val MODS_KT = "(?:(?:private|public|internal|protected|override|suspend|inline|open|abstract|data|sealed|enum|annotation|companion|operator|infix|tailrec|external)[ \\t]+)*"
    private val MODS_JAVA = "(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|sealed|partial|internal|async|override|virtual)[ \\t]+)*"

    private val rules: Map<String, List<Rule>> = mapOf(
        "Kotlin" to listOf(rule("^[ \\t]*" + MODS_KT + "(fun|class|interface|object)[ \\t]+(?:<[^>]*>[ \\t]*)?([A-Za-z_][A-Za-z0-9_.]*)", 1, 2)),
        "Java" to listOf(
            rule("^[ \\t]*" + MODS_JAVA + "(class|interface|enum|record)[ \\t]+([A-Za-z_]\\w*)", 1, 2),
            rule("^[ \\t]*(?:(?:public|private|protected|static|final|abstract|synchronized|native)[ \\t]+)+[\\w<>\\[\\],.? ]+?[ \\t]+([A-Za-z_]\\w*)[ \\t]*\\([^;{)]*\\)[ \\t]*(?:throws[ \\t]+[\\w., ]+)?[ \\t]*\\{", 0, 1)
        ),
        "C#" to listOf(
            rule("^[ \\t]*" + MODS_JAVA + "(class|interface|enum|struct|record)[ \\t]+([A-Za-z_]\\w*)", 1, 2),
            rule("^[ \\t]*(?:(?:public|private|protected|internal|static|async|override|virtual|abstract)[ \\t]+)+[\\w<>\\[\\],.? ]+?[ \\t]+([A-Za-z_]\\w*)[ \\t]*\\([^;{)]*\\)[ \\t]*\\{?[ \\t]*$", 0, 1)
        ),
        "JavaScript" to listOf(
            rule("^[ \\t]*(?:export[ \\t]+)?(?:default[ \\t]+)?(?:async[ \\t]+)?(function\\*?|class)[ \\t]+([A-Za-z_]\\w*)", 1, 2),
            rule("^[ \\t]*(?:export[ \\t]+)?(?:const|let|var)[ \\t]+([A-Za-z_]\\w*)[ \\t]*=[ \\t]*(?:async[ \\t]*)?(?:\\([^)]*\\)|[A-Za-z_]\\w*)[ \\t]*=>", 0, 1)
        ),
        "TypeScript" to listOf(
            rule("^[ \\t]*(?:export[ \\t]+)?(?:default[ \\t]+)?(?:async[ \\t]+)?(function\\*?|class|interface|type|enum)[ \\t]+([A-Za-z_]\\w*)", 1, 2),
            rule("^[ \\t]*(?:export[ \\t]+)?(?:const|let|var)[ \\t]+([A-Za-z_]\\w*)[ \\t]*=[ \\t]*(?:async[ \\t]*)?(?:\\([^)]*\\)|[A-Za-z_]\\w*)[ \\t]*=>", 0, 1)
        ),
        "Python" to listOf(rule("^[ \\t]*(?:async[ \\t]+)?(def|class)[ \\t]+([A-Za-z_]\\w*)", 1, 2)),
        "Go" to listOf(
            rule("^func[ \\t]+(?:\\([^)]*\\)[ \\t]*)?([A-Za-z_]\\w*)", 0, 1),
            rule("^type[ \\t]+([A-Za-z_]\\w*)[ \\t]+(?:struct|interface)", 0, 1)
        ),
        "Rust" to listOf(rule("^[ \\t]*(?:pub(?:\\([^)]*\\))?[ \\t]+)?(?:async[ \\t]+)?(?:unsafe[ \\t]+)?(fn|struct|enum|trait|impl|mod)[ \\t]+([A-Za-z_]\\w*)", 1, 2)),
        "Lua" to listOf(rule("^[ \\t]*(?:local[ \\t]+)?function[ \\t]+([A-Za-z_][\\w.:]*)", 0, 1)),
        "C" to listOf(
            rule("^[ \\t]*(struct|enum|union)[ \\t]+([A-Za-z_]\\w*)[ \\t]*\\{?[ \\t]*$", 1, 2),
            rule("^(?:[A-Za-z_][\\w*&:<>]*[ \\t]+)+\\**([A-Za-z_]\\w*)[ \\t]*\\(", 0, 1)
        ),
        "C++" to listOf(
            rule("^[ \\t]*(class|struct|enum|namespace)[ \\t]+([A-Za-z_]\\w*)", 1, 2),
            rule("^(?:[A-Za-z_][\\w*&:<>~]*[ \\t]+)+\\**([A-Za-z_~][\\w:~]*)[ \\t]*\\(", 0, 1)
        ),
        "PHP" to listOf(rule("^[ \\t]*(?:(?:public|private|protected|static|abstract|final)[ \\t]+)*(function|class|interface|trait)[ \\t]+([A-Za-z_]\\w*)", 1, 2)),
        "Ruby" to listOf(rule("^[ \\t]*(def|class|module)[ \\t]+([A-Za-z_][\\w.?!]*)", 1, 2)),
        "Swift" to listOf(rule("^[ \\t]*(?:(?:public|private|internal|fileprivate|open|static|final|override)[ \\t]+)*(func|class|struct|enum|protocol|extension)[ \\t]+([A-Za-z_]\\w*)", 1, 2)),
        "Shell" to listOf(rule("^[ \\t]*(?:function[ \\t]+)?([A-Za-z_][\\w-]*)[ \\t]*\\(\\)[ \\t]*\\{?", 0, 1)),
        "SQL" to listOf(rule("^[ \\t]*create[ \\t]+(?:or[ \\t]+replace[ \\t]+)?(table|view|function|procedure|index)[ \\t]+(?:if[ \\t]+not[ \\t]+exists[ \\t]+)?([\\w.]+)", 1, 2, Pattern.CASE_INSENSITIVE)),
        "Groovy / Gradle" to listOf(rule("^[ \\t]*(?:def|class)[ \\t]+([A-Za-z_]\\w*)", 0, 1))
    )

    private val MD = Pattern.compile("^(#{1,6})[ \\t]+(.+?)[ \\t]*$", Pattern.MULTILINE)

    fun supports(language: String): Boolean = language == "Markdown" || rules.containsKey(language)

    fun find(language: String, text: String): List<Item> {
        val found = ArrayList<Pair<Int, String>>()   // start offset, label
        if (language == "Markdown") {
            val m = MD.matcher(text)
            while (m.find() && found.size < 3000) {
                val level = m.group(1)!!.length
                found.add(Pair(m.start(), " ".repeat((level - 1) * 2) + m.group(2))) 
            }
        } else {
            val rs = rules[language] ?: return emptyList()
            for (r in rs) {
                val m = r.pattern.matcher(text)
                while (m.find() && found.size < 3000) {
                    val name = m.group(r.nameGroup) ?: continue
                    val kind = if (r.kindGroup > 0) m.group(r.kindGroup) + " " else ""
                    var ws = 0
                    var i = m.start()
                    while (i < text.length && (text[i] == ' ' || text[i] == '\t') && ws < 8) {
                        ws++
                        i++
                    }
                    found.add(Pair(m.start(), " ".repeat(ws) + kind + name))
                }
            }
        }
        found.sortBy { it.first }
        val out = ArrayList<Item>(found.size)
        var line = 0
        var pos = 0
        for (f in found) {
            while (pos < f.first && pos < text.length) {
                if (text[pos] == '\n') line++
                pos++
            }
            out.add(Item(line, f.second))
        }
        return out
    }
}
