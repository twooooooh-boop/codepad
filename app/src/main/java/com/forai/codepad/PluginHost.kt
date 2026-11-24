package com.forai.codepad

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** What plugins may touch. Implemented by the main screen; every call arrives on the UI thread. */
interface HostApi {
    fun getText(): String
    fun getSelectedText(): String
    fun replaceSelection(text: String)
    fun replaceAll(text: String)
    fun fileName(): String
    fun language(): String
    fun message(text: String, error: Boolean)
    fun askInput(prompt: String, cb: (String?) -> Unit)
    fun outputAppend(text: String)
    fun outputClear()
    fun outputShow(title: String)
    fun runTermux(command: String, cb: (RunResult) -> Unit)
}

/**
 * Runs plugin scripts inside one hidden, network-less WebView. Scripts only see the `codepad` object;
 * each call crosses a single bridge where the plugin's declared permissions are enforced.
 */
class PluginHost(
    private val ctx: Context,
    private val mgr: PluginManager,
    private val api: HostApi
) {
    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private val activated = HashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        stop()
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.blockNetworkLoads = true
        w.addJavascriptInterface(Bridge(), "__cp")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                for (q in queue) w.evaluateJavascript(q, null)
                queue.clear()
                activateStartup()
            }
        }
        web = w
        ready = false
        w.loadDataWithBaseURL("https://plugins.codepad.local/", HOST_HTML, "text/html", "utf-8", null)
    }

    fun stop() {
        activated.clear()
        queue.clear()
        ready = false
        web?.destroy()
        web = null
    }

    private fun js(code: String) {
        ui.post {
            val w = web
            if (w != null) {
                if (ready) w.evaluateJavascript(code, null) else queue.add(code)
            }
        }
    }

    fun activate(p: Plugin) {
        if (p.script.isEmpty() || !mgr.isEnabled(p.id) || !activated.add(p.id)) return
        js("__cpLoad(" + JSONObject.quote(p.id) + "," + JSONObject.quote(p.script) + ");")
    }

    private fun activateStartup() {
        for (p in mgr.enabledPlugins()) if (p.activation.contains("onStartup")) activate(p)
    }

    fun onLanguage(language: String) {
        for (p in mgr.enabledPlugins()) if (p.activation.contains("onLanguage:$language")) activate(p)
    }

    fun runCommand(pluginId: String, commandId: String) {
        val p = mgr.byId(pluginId) ?: return
        activate(p)
        js("__cpRunCommand(" + JSONObject.quote(pluginId) + "," + JSONObject.quote(commandId) + ");")
    }

    // ------------------------------------------------------------------ bridge

    private fun err(m: String): String = "{\"__cpError\":" + JSONObject.quote(m) + "}"

    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val task = FutureTask(Callable { f() })
        ui.post(task)
        return task.get(5, TimeUnit.SECONDS)
    }

    inner class Bridge {
        @JavascriptInterface
        fun call(pluginId: String, method: String, args: String): String {
            return try {
                dispatch(pluginId, method, args)
            } catch (e: Exception) {
                err(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun dispatch(pluginId: String, method: String, args: String): String {
        val p = mgr.byId(pluginId) ?: return err("Unknown plugin")
        if (!mgr.isEnabled(pluginId)) return err("Plugin is disabled")

        fun need(perm: String): String? =
            if (perm in p.permissions) null else err("Plugin \"" + p.id + "\" did not declare permission " + perm)

        val v: Any? = JSONArray("[$args]").opt(0)
        val str = v as? String ?: ""
        val obj = v as? JSONObject

        when (method) {
            "ui.message", "ui.error" -> {
                need(Perm.UI)?.let { return it }
                val isErr = method == "ui.error"
                onMain { api.message(str, isErr) }
                return "null"
            }
            "ui.input" -> {
                need(Perm.UI)?.let { return it }
                val prompt = obj?.optString("prompt", "") ?: ""
                val cb = obj?.optInt("cb", -1) ?: -1
                onMain {
                    api.askInput(prompt) { value ->
                        val json = if (value == null) "null" else JSONObject.quote(value)
                        js("__cpCallback(" + cb + "," + JSONObject.quote(json) + ");")
                    }
                }
                return "null"
            }
            "editor.getText" -> {
                need(Perm.EDITOR_READ)?.let { return it }
                return JSONObject.quote(onMain { api.getText() })
            }
            "editor.getSelectedText" -> {
                need(Perm.EDITOR_READ)?.let { return it }
                return JSONObject.quote(onMain { api.getSelectedText() })
            }
            "editor.getFileName" -> {
                need(Perm.EDITOR_READ)?.let { return it }
                return JSONObject.quote(onMain { api.fileName() })
            }
            "editor.getLanguage" -> {
                need(Perm.EDITOR_READ)?.let { return it }
                return JSONObject.quote(onMain { api.language() })
            }
            "editor.replaceSelection" -> {
                need(Perm.EDITOR_WRITE)?.let { return it }
                onMain { api.replaceSelection(str) }
                return "null"
            }
            "editor.replaceAll" -> {
                need(Perm.EDITOR_WRITE)?.let { return it }
                onMain { api.replaceAll(str) }
                return "null"
            }
            "output.append" -> {
                need(Perm.UI)?.let { return it }
                onMain { api.outputAppend(str) }
                return "null"
            }
            "output.clear" -> {
                need(Perm.UI)?.let { return it }
                onMain { api.outputClear() }
                return "null"
            }
            "output.show" -> {
                need(Perm.UI)?.let { return it }
                onMain { api.outputShow(p.name) }
                return "null"
            }
            "terminal.run" -> {
                need(Perm.RUN_TERMUX)?.let { return it }
                val cmd = obj?.optString("cmd", "") ?: ""
                val cb = obj?.optInt("cb", -1) ?: -1
                if (cmd.isEmpty()) return err("Empty command")
                onMain {
                    api.runTermux(cmd) { res ->
                        val o = JSONObject()
                        o.put("stdout", res.stdout)
                        o.put("stderr", res.stderr)
                        o.put("exitCode", res.exitCode)
                        o.put("error", res.error ?: JSONObject.NULL)
                        js("__cpCallback(" + cb + "," + JSONObject.quote(o.toString()) + ");")
                    }
                }
                return "null"
            }
        }
        return err("Unknown API method: $method")
    }

    companion object {
        private const val HOST_HTML = """<!doctype html><html><body><script>
(function () {
  var cbs = {}, cbId = 0, handlers = {};
  function call(pid, m, a) {
    var r = __cp.call(pid, m, JSON.stringify(a === undefined ? null : a));
    var v = r ? JSON.parse(r) : null;
    if (v && v.__cpError) { throw new Error(v.__cpError); }
    return v;
  }
  window.__cpCallback = function (id, json) {
    var f = cbs[id];
    if (f) { delete cbs[id]; try { f(JSON.parse(json)); } catch (e) { } }
  };
  window.__cpRunCommand = function (pid, cid) {
    var h = handlers[pid] && handlers[pid][cid];
    if (!h) { try { call(pid, 'ui.error', 'Command not registered by the plugin script: ' + cid); } catch (e) { } return; }
    try { h(); } catch (e) { try { call(pid, 'ui.error', String(e)); } catch (e2) { } }
  };
  window.__cpLoad = function (pid, src) {
    var api = Object.freeze({
      commands: Object.freeze({
        register: function (id, fn) { (handlers[pid] = handlers[pid] || {})[String(id)] = fn; }
      }),
      window: Object.freeze({
        showMessage: function (m) { call(pid, 'ui.message', String(m)); },
        showError: function (m) { call(pid, 'ui.error', String(m)); },
        showInputBox: function (prompt, cb) { var id = ++cbId; cbs[id] = cb; call(pid, 'ui.input', { prompt: String(prompt), cb: id }); }
      }),
      editor: Object.freeze({
        getText: function () { return call(pid, 'editor.getText'); },
        getSelectedText: function () { return call(pid, 'editor.getSelectedText'); },
        getFileName: function () { return call(pid, 'editor.getFileName'); },
        getLanguage: function () { return call(pid, 'editor.getLanguage'); },
        replaceSelection: function (t) { call(pid, 'editor.replaceSelection', String(t)); },
        replaceAll: function (t) { call(pid, 'editor.replaceAll', String(t)); }
      }),
      output: Object.freeze({
        append: function (t) { call(pid, 'output.append', String(t)); },
        clear: function () { call(pid, 'output.clear'); },
        show: function () { call(pid, 'output.show'); }
      }),
      terminal: Object.freeze({
        run: function (cmd, cb) { var id = ++cbId; cbs[id] = cb; call(pid, 'terminal.run', { cmd: String(cmd), cb: id }); }
      })
    });
    try { (new Function('codepad', src))(api); }
    catch (e) { try { call(pid, 'ui.error', 'Plugin failed to load: ' + e); } catch (e2) { } }
  };
})();
</script></body></html>"""
    }
}
