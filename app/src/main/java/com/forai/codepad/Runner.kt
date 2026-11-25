package com.forai.codepad

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat

class RunResult(
    val id: Int,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val error: String?
)

/** Matches results coming back from Termux to whoever started the run. */
object RunBus {
    private val handlers = HashMap<Int, (RunResult) -> Unit>()
    private var next = 1
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun register(cb: (RunResult) -> Unit): Int {
        val id = next++
        handlers[id] = cb
        return id
    }

    @Synchronized
    fun cancel(id: Int) {
        handlers.remove(id)
    }

    fun deliver(r: RunResult) {
        val h = synchronized(this) { handlers.remove(r.id) }
        if (h != null) main.post { h(r) }
    }
}

/** Receives the PendingIntent that Termux fires when a background command finishes. */
class RunResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("run_id", -1)
        val b = intent.getBundleExtra("result")
        if (b == null) {
            RunBus.deliver(RunResult(id, "", "", -1, "Termux returned no result. Update Termux (F-Droid version) and try again."))
            return
        }
        val out = b.getString("stdout") ?: ""
        val err = b.getString("stderr") ?: ""
        val code = b.getInt("exitCode", -1)
        val errCode = b.getInt("err", -1)
        val errMsg = if (errCode > 0) (b.getString("errmsg") ?: "Termux error $errCode") else null
        RunBus.deliver(RunResult(id, out, err, code, errMsg))
    }
}

object TermuxRunner {
    const val PKG = "com.termux"
    const val PERM = "com.termux.permission.RUN_COMMAND"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"
    const val MAX_CHARS = 60_000

    const val SETUP_HELP =
        "One-time Termux setup:\n" +
                "1) In Termux run:\n" +
                "   mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n" +
                "   then close Termux completely and open it again.\n" +
                "2) Android Settings > Apps > CodePad > Permissions > allow \"Run commands in Termux environment\".\n"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERM) == PackageManager.PERMISSION_GRANTED

    private fun safeName(n: String): String {
        val s = n.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (s.isEmpty() || s.startsWith(".")) "main_$s" else s
    }

    /**
     * Shell script that recreates the file inside Termux's own storage and then runs [template].
     * Variables: ${file} ${fileName} ${fileBase} ${fileDir} ${home}
     */
    fun buildScript(fileName: String, content: String, template: String, pauseAtEnd: Boolean): String {
        val name = safeName(fileName)
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val dir = "\$HOME/.codepad/run"
        val file = "$dir/$name"
        val cmd = template
            .replace("\${file}", file)
            .replace("\${fileName}", name)
            .replace("\${fileBase}", base)
            .replace("\${fileDir}", dir)
            .replace("\${home}", "\$HOME")
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tail = if (pauseAtEnd) "; echo; echo '[finished - press Enter to close]'; read" else ""
        return "mkdir -p \"$dir\" && printf '%s' '$b64' | base64 -d > \"$file\" && cd \"$dir\" && $cmd$tail"
    }

    /**
     * Runs [script] in Termux. In background mode the result comes back through [cb];
     * in terminal mode Termux opens a session and [cb] fires immediately.
     */
    fun run(ctx: Context, script: String, background: Boolean, cb: (RunResult) -> Unit) {
        val i = Intent("com.termux.RUN_COMMAND")
        i.setClassName(PKG, "com.termux.app.RunCommandService")
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")

        var runId = -1
        if (background) {
            runId = RunBus.register(cb)
            val ri = Intent(ctx, RunResultReceiver::class.java)
            ri.putExtra("run_id", runId)
            val pi = PendingIntent.getBroadcast(
                ctx, runId, ri,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }
        try {
            try {
                ctx.startService(i)
            } catch (e: IllegalStateException) {
                ContextCompat.startForegroundService(ctx, i)
            }
            if (!background) cb(RunResult(-1, "Opened in Termux.\n", "", 0, null))
        } catch (e: Exception) {
            if (runId >= 0) RunBus.cancel(runId)
            cb(RunResult(-1, "", "", -1, (e.message ?: e.javaClass.simpleName) + "\n\n" + SETUP_HELP))
        }
    }
}

/** Runs JavaScript in a throw-away WebView with no network and captures console output. */
object SandboxRunner {
    private var current: WebView? = null

    class Bridge(val onLog: (String) -> Unit, val onDone: () -> Unit) {
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun log(level: String, text: String) {
            val prefix = when (level) {
                "error" -> "ERROR: "
                "warn" -> "WARN: "
                else -> ""
            }
            main.post { onLog(prefix + text) }
        }

        @JavascriptInterface
        fun done() {
            main.post { onDone() }
        }
    }

    fun cancel() {
        current?.destroy()
        current = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun runJs(ctx: Context, code: String, timeoutMs: Int, onLog: (String) -> Unit, onDone: () -> Unit) {
        current?.destroy()
        val w = WebView(ctx)
        current = w
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.blockNetworkLoads = true
        w.addJavascriptInterface(Bridge(onLog) {
            onDone()
            w.destroy()
            if (current === w) current = null
        }, "RunBridge")
        val safe = code.replace("</script", "<\\/script")
        val head = "<!doctype html><html><body><script>\n" +
                "(function () {\n" +
                "  function fmt(a) { var out = []; for (var i = 0; i < a.length; i++) { var x = a[i];\n" +
                "    if (typeof x === 'string') out.push(x);\n" +
                "    else if (x instanceof Error) out.push(x.stack || String(x));\n" +
                "    else { try { out.push(JSON.stringify(x, null, 2)); } catch (e) { out.push(String(x)); } } }\n" +
                "    return out.join(' '); }\n" +
                "  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (k) {\n" +
                "    console[k] = function () { RunBridge.log(k === 'info' || k === 'debug' ? 'log' : k, fmt(arguments)); }; });\n" +
                "  window.onerror = function (m, s, l) { RunBridge.log('error', m + ' (line ' + (l - @@OFF@@) + ')'); };\n" +
                "  window.addEventListener('unhandledrejection', function (e) { RunBridge.log('error', 'Unhandled promise rejection: ' + e.reason); });\n" +
                "  setTimeout(function () { RunBridge.done(); }, " + timeoutMs + ");\n" +
                "})();\n" +
                "</script><script>\n"
        val offset = head.count { it == '\n' }
        val html = head.replace("@@OFF@@", offset.toString()) + safe + "\n</script></body></html>"
        w.loadDataWithBaseURL("https://sandbox.codepad.local/", html, "text/html", "utf-8", null)
        // A script stuck in an endless loop never reports back, so stop it after a grace period.
        Handler(Looper.getMainLooper()).postDelayed({
            if (current === w) {
                onLog("ERROR: the script did not finish (endless loop?) and was stopped.")
                onDone()
                w.destroy()
                current = null
            }
        }, timeoutMs + 4000L)
    }
}
