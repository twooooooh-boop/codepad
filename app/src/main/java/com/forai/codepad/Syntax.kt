package com.forai.codepad

import java.util.regex.Matcher
import java.util.regex.Pattern

object Kind {
    const val COMMENT = 1
    const val STRING = 2
    const val NUMBER = 3
    const val KEYWORD = 4
    const val TYPE = 5
    const val FUNCTION = 6
    const val ATTR = 7
    const val TAG = 8
    const val ANNOT = 9
}

class IntList(cap: Int = 256) {
    private var a = IntArray(cap)
    var size = 0
        private set

    fun add(x: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = x
    }

    fun add3(x: Int, y: Int, z: Int) {
        add(x); add(y); add(z)
    }

    fun toArray(): IntArray = a.copyOf(size)
}

/** A language: a combined regex plus a function that turns each match into tokens (start, end, kind). */
class Lang(
    val name: String,
    val lineComment: String?,
    private val pattern: Pattern?,
    private val emit: (Matcher, CharSequence, IntList) -> Unit
) {
    val hasSyntax: Boolean get() = pattern != null

    fun tokenize(text: CharSequence): IntArray {
        val p = pattern ?: return IntArray(0)
        val out = IntList(1024)
        val m = p.matcher(text)
        while (m.find()) emit(m, text, out)
        return out.toArray()
    }
}

private const val NEVER = "(?!)"
private const val T3 = "\"\"\""

/** Body of a single-line string literal opened by quote [c] (without the closing quote). */
private fun strOpen(c: String): String =
    c + "[^" + c + "\\\\\\n]*+(?:\\\\[\\s\\S][^" + c + "\\\\\\n]*+)*+"

private fun strRe(c: String): String = strOpen(c) + c + "?"

private fun emitGeneric(m: Matcher, text: CharSequence, out: IntList, kw: Set<String>, ci: Boolean) {
    if (m.start(1) >= 0) {
        out.add3(m.start(1), m.end(1), Kind.COMMENT)
    } else if (m.start(2) >= 0) {
        out.add3(m.start(2), m.end(2), Kind.COMMENT)
    } else if (m.start(3) >= 0) {
        out.add3(m.start(3), m.end(3), Kind.STRING)
    } else if (m.start(4) >= 0) {
        out.add3(m.start(4), m.end(4), Kind.STRING)
    } else if (m.start(5) >= 0) {
        out.add3(m.start(5), m.end(5), Kind.NUMBER)
    } else if (m.start(6) >= 0) {
        out.add3(m.start(6), m.end(6), Kind.ANNOT)
    } else if (m.start(7) >= 0) {
        val s = m.start(7)
        val e = m.end(7)
        val w0 = text.subSequence(s, e).toString()
        val w = if (ci) w0.lowercase() else w0
        if (kw.contains(w)) {
            out.add3(s, e, Kind.KEYWORD)
        } else if (w0[0].isUpperCase()) {
            out.add3(s, e, Kind.TYPE)
        } else {
            var i = e
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i < text.length && text[i] == '(') out.add3(s, e, Kind.FUNCTION)
        }
    }
}

private fun generic(
    name: String,
    keywords: String,
    line: String? = "//",
    blockStart: String? = "/*",
    blockEnd: String? = "*/",
    quotes: String = "\"'",
    backtick: Boolean = false,
    triple: Boolean = false,
    annot: String? = null,
    ci: Boolean = false
): Lang {
    val kw = HashSet<String>()
    for (w in keywords.split(' ')) if (w.isNotEmpty()) kw.add(if (ci) w.lowercase() else w)

    val blk = if (blockStart != null && blockEnd != null)
        Pattern.quote(blockStart) + "[\\s\\S]*?(?:" + Pattern.quote(blockEnd) + "|\\z)"
    else NEVER
    val lin = if (line != null) Pattern.quote(line) + "[^\\n]*" else NEVER
    val tri = if (triple) T3 + "[\\s\\S]*?(?:" + T3 + "|\\z)|'''[\\s\\S]*?(?:'''|\\z)" else NEVER

    val sb = StringBuilder()
    for (ch in quotes) {
        if (sb.isNotEmpty()) sb.append('|')
        sb.append(strRe(ch.toString()))
    }
    if (backtick) {
        if (sb.isNotEmpty()) sb.append('|')
        sb.append("`[^`\\\\]*+(?:\\\\[\\s\\S][^`\\\\]*+)*+`?")
    }
    val str = if (sb.isEmpty()) NEVER else sb.toString()
    val num = "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|[0-9][0-9_]*(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)[uUlLfFdD]*\\b"
    val ann = annot ?: NEVER
    val ide = "[A-Za-z_][A-Za-z0-9_]*"

    val p = Pattern.compile("(" + blk + ")|(" + lin + ")|(" + tri + ")|(" + str + ")|(" + num + ")|(" + ann + ")|(" + ide + ")")
    return Lang(name, line, p) { m, t, o -> emitGeneric(m, t, o, kw, ci) }
}

// ---------- JSON ----------
private val JSON_P: Pattern = Pattern.compile(
    "(" + strOpen("\"") + "\"(?=\\s*:))" +
            "|(" + strRe("\"") + ")" +
            "|(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)" +
            "|(\\b(?:true|false|null)\\b)"
)

private fun emitJson(m: Matcher, text: CharSequence, out: IntList) {
    if (m.start(1) >= 0) out.add3(m.start(1), m.end(1), Kind.ATTR)
    else if (m.start(2) >= 0) out.add3(m.start(2), m.end(2), Kind.STRING)
    else if (m.start(3) >= 0) out.add3(m.start(3), m.end(3), Kind.NUMBER)
    else if (m.start(4) >= 0) out.add3(m.start(4), m.end(4), Kind.KEYWORD)
}

// ---------- XML / HTML ----------
private val XML_P: Pattern = Pattern.compile(
    "(<!--[\\s\\S]*?(?:-->|\\z))" +
            "|(<[!?][^>]*+>?)" +
            "|(</?[A-Za-z][A-Za-z0-9:._-]*)" +
            "|(/?>)" +
            "|(?:([A-Za-z_:][A-Za-z0-9:._-]*)\\s*=\\s*(\"[^\"]*+\"?|'[^']*+'?))" +
            "|(&[A-Za-z0-9#]+;)"
)

private fun emitXml(m: Matcher, text: CharSequence, out: IntList) {
    if (m.start(1) >= 0) {
        out.add3(m.start(1), m.end(1), Kind.COMMENT)
    } else if (m.start(2) >= 0) {
        out.add3(m.start(2), m.end(2), Kind.KEYWORD)
    } else if (m.start(3) >= 0) {
        out.add3(m.start(3), m.end(3), Kind.TAG)
    } else if (m.start(4) >= 0) {
        out.add3(m.start(4), m.end(4), Kind.TAG)
    } else if (m.start(5) >= 0) {
        out.add3(m.start(5), m.end(5), Kind.ATTR)
        out.add3(m.start(6), m.end(6), Kind.STRING)
    } else if (m.start(7) >= 0) {
        out.add3(m.start(7), m.end(7), Kind.NUMBER)
    }
}

// ---------- CSS ----------
private val CSS_P: Pattern = Pattern.compile(
    "(/\\*[\\s\\S]*?(?:\\*/|\\z))" +
            "|(" + strRe("\"") + "|" + strRe("'") + ")" +
            "|(#[0-9a-fA-F]{3,8}(?![0-9A-Za-z_-]))" +
            "|(@[A-Za-z-]+)" +
            "|([A-Za-z-]+(?=\\s*:))" +
            "|([.#][A-Za-z_][A-Za-z0-9_-]*)" +
            "|([A-Za-z_-][A-Za-z0-9_-]*)" +
            "|([0-9]*\\.?[0-9]+[A-Za-z%]*)"
)

private fun emitCss(m: Matcher, text: CharSequence, out: IntList) {
    if (m.start(1) >= 0) out.add3(m.start(1), m.end(1), Kind.COMMENT)
    else if (m.start(2) >= 0) out.add3(m.start(2), m.end(2), Kind.STRING)
    else if (m.start(3) >= 0) out.add3(m.start(3), m.end(3), Kind.NUMBER)
    else if (m.start(4) >= 0) out.add3(m.start(4), m.end(4), Kind.KEYWORD)
    else if (m.start(5) >= 0) out.add3(m.start(5), m.end(5), Kind.ATTR)
    else if (m.start(6) >= 0) out.add3(m.start(6), m.end(6), Kind.TYPE)
    else if (m.start(8) >= 0) out.add3(m.start(8), m.end(8), Kind.NUMBER)
}

// ---------- Markdown ----------
private val MD_P: Pattern = Pattern.compile(
    "(^```[\\s\\S]*?(?:^```[^\\n]*|\\z))" +
            "|(^#{1,6}[ \\t][^\\n]*)" +
            "|(^>[^\\n]*)" +
            "|(^[ \\t]*(?:[-*+]|[0-9]+\\.)(?=[ \\t]))" +
            "|(`[^`\\n]+`)" +
            "|(\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)" +
            "|(\\[[^\\]\\n]*\\]\\([^)\\n]*\\))",
    Pattern.MULTILINE
)

private fun emitMd(m: Matcher, text: CharSequence, out: IntList) {
    if (m.start(1) >= 0) out.add3(m.start(1), m.end(1), Kind.STRING)
    else if (m.start(2) >= 0) out.add3(m.start(2), m.end(2), Kind.KEYWORD)
    else if (m.start(3) >= 0) out.add3(m.start(3), m.end(3), Kind.COMMENT)
    else if (m.start(4) >= 0) out.add3(m.start(4), m.end(4), Kind.NUMBER)
    else if (m.start(5) >= 0) out.add3(m.start(5), m.end(5), Kind.STRING)
    else if (m.start(6) >= 0) out.add3(m.start(6), m.end(6), Kind.TYPE)
    else if (m.start(7) >= 0) out.add3(m.start(7), m.end(7), Kind.FUNCTION)
}

// ---------- keyword lists ----------
private const val AT = "@[A-Za-z_][A-Za-z0-9_.]*"
private const val HASH_DIR = "#[ \\t]*[A-Za-z_]+"

private const val KW_KOTLIN = "abstract actual annotation as break by catch class companion const constructor continue crossinline data delegate do dynamic else enum expect external false final finally for fun get if import in infix init inline inner interface internal is lateinit noinline null object open operator out override package private protected public reified return sealed set super suspend tailrec this throw true try typealias val value var vararg when where while"
private const val KW_JAVA = "abstract assert boolean break byte case catch char class const continue default do double else enum extends false final finally float for goto if implements import instanceof int interface long native new null package private protected public record return sealed short static strictfp super switch synchronized this throw throws transient true try var void volatile while yield"
private const val KW_JS = "async await break case catch class const continue debugger default delete do else enum export extends false finally for from function get if implements import in instanceof interface let new null of package private protected public return set static super switch this throw true try typeof undefined var void while with yield as type namespace readonly abstract declare keyof"
private const val KW_PY = "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self match case"
private const val KW_C = "auto bool break case char const continue default do double else enum extern false float for goto if inline int long register return short signed sizeof static struct switch true typedef union unsigned void volatile while NULL"
private const val KW_CPP = " alignas alignof and catch class constexpr delete explicit export friend mutable namespace new noexcept nullptr operator or private protected public static_cast template this throw try typename using virtual"
private const val KW_CS = "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile while async await"
private const val KW_GO = "break case chan const continue default defer else fallthrough false for func go goto if import interface map nil package range return select struct switch true type var"
private const val KW_RUST = "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while"
private const val KW_SWIFT = "as break case catch class continue default defer do else enum extension fallthrough false for func guard if import in init inout internal is let nil operator private protocol public repeat return self static struct subscript super switch throw throws true try typealias var where while"
private const val KW_DART = "abstract as assert async await break case catch class const continue default deferred do dynamic else enum export extends extension external factory false final finally for get if implements import in interface is late library mixin new null on operator part required rethrow return set static super switch sync this throw true try typedef var void while with yield"
private const val KW_PHP = "abstract and array as break case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends false final finally fn for foreach function global if implements include include_once instanceof interface isset list namespace new null or print private protected public require require_once return static switch throw trait true try unset use var while yield"
private const val KW_SH = "if then else elif fi for while until do done case esac in function select time return exit break continue local export readonly declare unset echo cd source alias true false"
private const val KW_RB = "BEGIN END alias and begin break case class def do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield"
private const val KW_LUA = "and break do else elseif end false for function goto if in local nil not or repeat return then true until while self"
private const val KW_SQL = "select from where insert into values update set delete create table alter drop index view join inner left right outer on group by order having limit offset as and or not null is in like between distinct union all case when then else end primary key foreign references default unique constraint database if exists count sum avg min max asc desc"
private const val KW_YAML = "true false null yes no on off"
private const val KW_GROOVY = "as assert break case catch class continue def default else enum extends false finally for if implements import in instanceof interface new null package return super switch this throw throws true try var while"

object Langs {
    val plain = Lang("Plain Text", null, null) { _, _, _ -> }
    val kotlin = generic("Kotlin", KW_KOTLIN, annot = AT, triple = true)
    val java = generic("Java", KW_JAVA, annot = AT)
    val javascript = generic("JavaScript", KW_JS, backtick = true)
    val typescript = generic("TypeScript", KW_JS, backtick = true, annot = AT)
    val python = generic("Python", KW_PY, line = "#", blockStart = null, blockEnd = null, triple = true, annot = AT)
    val c = generic("C", KW_C, annot = HASH_DIR)
    val cpp = generic("C++", KW_C + KW_CPP, annot = HASH_DIR)
    val csharp = generic("C#", KW_CS, annot = HASH_DIR)
    val go = generic("Go", KW_GO, backtick = true)
    val rust = generic("Rust", KW_RUST, quotes = "\"")
    val swift = generic("Swift", KW_SWIFT, annot = AT)
    val dart = generic("Dart", KW_DART, annot = AT)
    val php = generic("PHP", KW_PHP)
    val shell = generic("Shell", KW_SH, line = "#", blockStart = null, blockEnd = null)
    val ruby = generic("Ruby", KW_RB, line = "#", blockStart = null, blockEnd = null)
    val lua = generic("Lua", KW_LUA, line = "--", blockStart = "--[[", blockEnd = "]]")
    val sql = generic("SQL", KW_SQL, line = "--", ci = true)
    val yaml = generic("YAML", KW_YAML, line = "#", blockStart = null, blockEnd = null, ci = true)
    val toml = generic("TOML", KW_YAML, line = "#", blockStart = null, blockEnd = null, ci = true)
    val ini = generic("INI", KW_YAML, line = ";", blockStart = null, blockEnd = null, ci = true)
    val groovy = generic("Groovy / Gradle", KW_GROOVY, annot = AT)
    val json = Lang("JSON", null, JSON_P) { m, t, o -> emitJson(m, t, o) }
    val xml = Lang("XML", null, XML_P) { m, t, o -> emitXml(m, t, o) }
    val html = Lang("HTML", null, XML_P) { m, t, o -> emitXml(m, t, o) }
    val css = Lang("CSS", null, CSS_P) { m, t, o -> emitCss(m, t, o) }
    val markdown = Lang("Markdown", null, MD_P) { m, t, o -> emitMd(m, t, o) }

    val all: List<Lang> = listOf(
        plain, kotlin, java, javascript, typescript, python, c, cpp, csharp, go, rust, swift, dart,
        php, shell, ruby, lua, sql, json, xml, html, css, markdown, yaml, toml, ini, groovy
    )

    private val byExt = HashMap<String, Lang>()

    private fun reg(l: Lang, exts: String) {
        for (e in exts.split(' ')) if (e.isNotEmpty()) byExt[e] = l
    }

    init {
        reg(kotlin, "kt kts")
        reg(java, "java")
        reg(javascript, "js mjs cjs jsx")
        reg(typescript, "ts tsx")
        reg(python, "py pyw")
        reg(c, "c h")
        reg(cpp, "cpp cc cxx hpp hh hxx")
        reg(csharp, "cs")
        reg(go, "go")
        reg(rust, "rs")
        reg(swift, "swift")
        reg(dart, "dart")
        reg(php, "php")
        reg(shell, "sh bash zsh")
        reg(ruby, "rb")
        reg(lua, "lua")
        reg(sql, "sql")
        reg(json, "json")
        reg(xml, "xml svg xsd xsl plist")
        reg(html, "html htm xhtml")
        reg(css, "css scss less")
        reg(markdown, "md markdown")
        reg(yaml, "yml yaml")
        reg(toml, "toml")
        reg(ini, "ini cfg conf properties")
        reg(groovy, "gradle groovy")
    }

    fun forFileName(name: String?): Lang {
        if (name == null) return plain
        val n = name.lowercase()
        val ext = n.substringAfterLast('.', "")
        return byExt[ext] ?: plain
    }
}
