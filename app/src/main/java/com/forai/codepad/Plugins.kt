package com.forai.codepad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val PLUGIN_API = 1
private const val SEED_VERSION = 1

/** Capabilities a plugin must declare in its manifest. Undeclared capabilities are refused at runtime. */
object Perm {
    const val EDITOR_READ = "editor.read"
    const val EDITOR_WRITE = "editor.write"
    const val RUN_TERMUX = "run.termux"
    const val RUN_SANDBOX = "run.sandbox"
    const val UI = "ui"

    val ALL = listOf(EDITOR_READ, EDITOR_WRITE, RUN_TERMUX, RUN_SANDBOX, UI)

    fun describe(p: String): String = when (p) {
        EDITOR_READ -> "Read the text of the open file"
        EDITOR_WRITE -> "Change the text of the open file"
        RUN_TERMUX -> "Run commands in Termux (can do anything Termux can)"
        RUN_SANDBOX -> "Run code in the built-in sandbox (no network, no files)"
        UI -> "Show messages and ask you for input"
        else -> p
    }
}

object RunnerType {
    const val TERMUX = "termux"
    const val SANDBOX_JS = "sandbox-js"
    const val PREVIEW_HTML = "preview-html"
    const val PREVIEW_MD = "preview-markdown"
    val ALL = listOf(TERMUX, SANDBOX_JS, PREVIEW_HTML, PREVIEW_MD)
}

class RunnerDef(
    val pluginId: String,
    val id: String,
    val title: String,
    val languages: List<String>,
    val extensions: List<String>,
    val type: String,
    val command: String,
    val mode: String,
    val timeoutMs: Int,
    val hint: String
) {
    val key: String get() = pluginId + ":" + id
}

class SnippetDef(
    val pluginId: String,
    val language: String,
    val prefix: String,
    val name: String,
    val body: String
)

class CommandDef(val pluginId: String, val id: String, val title: String, val runner: String?)

class Plugin(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val permissions: List<String>,
    val activation: List<String>,
    val script: String,
    val runners: List<RunnerDef>,
    val snippets: List<SnippetDef>,
    val commands: List<CommandDef>,
    val builtin: Boolean,
    val json: String
) {
    fun summary(): String {
        val parts = ArrayList<String>()
        if (runners.isNotEmpty()) parts.add(runners.size.toString() + " runner" + (if (runners.size == 1) "" else "s"))
        if (snippets.isNotEmpty()) parts.add(snippets.size.toString() + " snippet" + (if (snippets.size == 1) "" else "s"))
        if (commands.isNotEmpty()) parts.add(commands.size.toString() + " command" + (if (commands.size == 1) "" else "s"))
        if (script.isNotEmpty()) parts.add("script")
        return parts.joinToString(" · ")
    }
}

object PluginParser {
    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

    private fun strings(a: JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    /** Parses and validates a plugin manifest. Throws IllegalArgumentException with a readable message. */
    fun parse(json: String): Plugin {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not valid JSON: " + (e.message ?: ""))
        }
        val id = o.optString("id", "")
        require(ID_RE.matches(id)) { "\"id\" must be 3-64 chars: lowercase letters, digits, . _ -" }
        val engine = o.optInt("engine", PLUGIN_API)
        require(engine <= PLUGIN_API) { "Plugin needs a newer CodePad (plugin API $engine)" }

        val perms = strings(o.optJSONArray("permissions"))
        for (p in perms) require(p in Perm.ALL) { "Unknown permission: $p" }

        val c = o.optJSONObject("contributes") ?: JSONObject()

        val runners = ArrayList<RunnerDef>()
        val ra = c.optJSONArray("runners")
        if (ra != null) {
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i)
                val rid = r.optString("id", "")
                require(rid.isNotEmpty()) { "Runner #" + (i + 1) + " has no id" }
                require(runners.none { it.id == rid }) { "Duplicate runner id: $rid" }
                val type = r.optString("type", "")
                require(type in RunnerType.ALL) { "Runner $rid: unknown type \"$type\"" }
                if (type == RunnerType.TERMUX) {
                    require(Perm.RUN_TERMUX in perms) { "Runner $rid needs permission run.termux" }
                    require(r.optString("command", "").isNotEmpty()) { "Runner $rid needs a \"command\"" }
                } else {
                    require(Perm.RUN_SANDBOX in perms) { "Runner $rid needs permission run.sandbox" }
                }
                val mode = r.optString("mode", "background")
                require(mode == "background" || mode == "terminal") { "Runner $rid: mode must be background or terminal" }
                runners.add(
                    RunnerDef(
                        id, rid, r.optString("title", rid),
                        strings(r.optJSONArray("languages")),
                        strings(r.optJSONArray("extensions")).map { it.lowercase().removePrefix(".") },
                        type, r.optString("command", ""), mode,
                        r.optInt("timeoutMs", 1500).coerceIn(200, 30000),
                        r.optString("hint", "")
                    )
                )
            }
        }

        val snippets = ArrayList<SnippetDef>()
        val sa = c.optJSONArray("snippets")
        if (sa != null) {
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                val prefix = s.optString("prefix", "")
                require(prefix.isNotEmpty() && prefix.all { it.isLetterOrDigit() || it == '_' }) {
                    "Snippet #" + (i + 1) + ": prefix must be letters, digits or _"
                }
                snippets.add(SnippetDef(id, s.optString("language", "*"), prefix, s.optString("name", prefix), s.optString("body", "")))
            }
        }

        val script = o.optString("script", "")
        val commands = ArrayList<CommandDef>()
        val ca = c.optJSONArray("commands")
        if (ca != null) {
            for (i in 0 until ca.length()) {
                val cd = ca.getJSONObject(i)
                val cid = cd.optString("id", "")
                require(cid.isNotEmpty()) { "Command #" + (i + 1) + " has no id" }
                val runner = if (cd.has("runner")) cd.getString("runner") else null
                require(runner != null || script.isNotEmpty()) { "Command $cid needs a \"runner\" or the plugin needs a \"script\"" }
                if (runner != null) require(runners.any { it.id == runner }) { "Command $cid: unknown runner $runner" }
                commands.add(CommandDef(id, cid, cd.optString("title", cid), runner))
            }
        }
        if (script.isNotEmpty()) {
            require(Perm.UI in perms || Perm.EDITOR_READ in perms || Perm.EDITOR_WRITE in perms || Perm.RUN_TERMUX in perms) {
                "A script plugin must declare at least one permission"
            }
        }

        return Plugin(
            id,
            o.optString("name", id),
            o.optString("version", "0.0.0"),
            o.optString("publisher", "unknown"),
            o.optString("description", ""),
            perms,
            strings(o.optJSONArray("activationEvents")),
            script,
            runners, snippets, commands,
            o.optBoolean("builtin", false),
            json
        )
    }
}

class PluginManager(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "plugins").also { it.mkdirs() }
    private val prefs = ctx.getSharedPreferences("codepad", Context.MODE_PRIVATE)

    var plugins: List<Plugin> = emptyList()
        private set
    val errors = ArrayList<String>()

    /** Copies the bundled plugins into the plugin folder (they are normal, editable plugins). */
    fun seedBuiltins(force: Boolean = false) {
        if (!force && prefs.getInt("plugins_seed", 0) >= SEED_VERSION) return
        try {
            for (n in ctx.assets.list("plugins") ?: emptyArray()) {
                if (!n.endsWith(".json")) continue
                val text = ctx.assets.open("plugins/$n").bufferedReader().use { it.readText() }
                File(dir, n).writeText(text)
            }
            prefs.edit().putInt("plugins_seed", SEED_VERSION).apply()
        } catch (e: Exception) {
            errors.add("Could not install bundled plugins: " + e.message)
        }
    }

    fun reload() {
        errors.clear()
        val list = ArrayList<Plugin>()
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        for (f in files.sortedBy { it.name }) {
            try {
                list.add(PluginParser.parse(f.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
                errors.add(f.name + ": " + (e.message ?: "invalid"))
            }
        }
        plugins = list
    }

    private fun disabled(): Set<String> = prefs.getStringSet("plugins_disabled", emptySet()) ?: emptySet()

    fun isEnabled(id: String): Boolean = id !in disabled()

    fun setEnabled(id: String, on: Boolean) {
        val s = HashSet(disabled())
        if (on) s.remove(id) else s.add(id)
        prefs.edit().putStringSet("plugins_disabled", s).apply()
    }

    fun byId(id: String): Plugin? = plugins.firstOrNull { it.id == id }

    fun enabledPlugins(): List<Plugin> = plugins.filter { isEnabled(it.id) }

    fun install(json: String): Plugin {
        val p = PluginParser.parse(json)
        File(dir, p.id + ".json").writeText(json, Charsets.UTF_8)
        reload()
        return p
    }

    fun uninstall(id: String) {
        File(dir, "$id.json").delete()
        reload()
    }

    fun runnersFor(fileName: String, language: String): List<RunnerDef> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val out = ArrayList<RunnerDef>()
        for (p in enabledPlugins()) for (r in p.runners) {
            val byLang = r.languages.any { it.equals(language, ignoreCase = true) }
            val byExt = ext.isNotEmpty() && ext in r.extensions
            if (byLang || byExt) out.add(r)
        }
        return out
    }

    fun snippetsFor(language: String): List<SnippetDef> {
        val out = ArrayList<SnippetDef>()
        for (p in enabledPlugins()) for (s in p.snippets) {
            if (s.language == "*" || s.language.equals(language, ignoreCase = true)) out.add(s)
        }
        return out
    }

    fun commands(): List<CommandDef> = enabledPlugins().flatMap { it.commands }

    fun runner(pluginId: String, runnerId: String): RunnerDef? =
        byId(pluginId)?.runners?.firstOrNull { it.id == runnerId }

    /** Changes whenever the set / state / content of plugins changes. */
    fun signature(): String =
        plugins.joinToString("|") { it.id + "@" + it.version + (if (isEnabled(it.id)) "+" else "-") + it.json.hashCode() }
}

/** VS Code-style snippet bodies: $1, ${1:default}, $0 and \$ escapes. */
object SnippetEngine {
    class Expanded(val text: String, val selStart: Int, val selEnd: Int)

    fun expand(body: String, indent: String): Expanded {
        val sb = StringBuilder()
        var bestN = Int.MAX_VALUE
        var bestStart = -1
        var bestEnd = -1
        var finalPos = -1

        fun put(ch: Char) {
            sb.append(ch)
            if (ch == '\n') sb.append(indent)
        }

        var i = 0
        val n = body.length
        while (i < n) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < n && (body[i + 1] == '$' || body[i + 1] == '\\')) {
                put(body[i + 1])
                i += 2
                continue
            }
            if (ch == '$' && i + 1 < n) {
                val nx = body[i + 1]
                if (nx.isDigit()) {
                    var j = i + 1
                    while (j < n && body[j].isDigit()) j++
                    val num = body.substring(i + 1, j).toInt()
                    if (num == 0) {
                        finalPos = sb.length
                    } else if (num < bestN) {
                        bestN = num
                        bestStart = sb.length
                        bestEnd = sb.length
                    }
                    i = j
                    continue
                }
                if (nx == '{') {
                    val close = body.indexOf('}', i + 2)
                    if (close > 0) {
                        val inner = body.substring(i + 2, close)
                        val colon = inner.indexOf(':')
                        val numStr = if (colon >= 0) inner.substring(0, colon) else inner
                        val def = if (colon >= 0) inner.substring(colon + 1) else ""
                        val num = numStr.toIntOrNull()
                        if (num != null) {
                            val st = sb.length
                            for (d in def) put(d)
                            if (num == 0) {
                                finalPos = st
                            } else if (num < bestN) {
                                bestN = num
                                bestStart = st
                                bestEnd = sb.length
                            }
                            i = close + 1
                            continue
                        }
                    }
                }
            }
            put(ch)
            i++
        }
        return when {
            bestStart >= 0 -> Expanded(sb.toString(), bestStart, bestEnd)
            finalPos >= 0 -> Expanded(sb.toString(), finalPos, finalPos)
            else -> Expanded(sb.toString(), sb.length, sb.length)
        }
    }
}
