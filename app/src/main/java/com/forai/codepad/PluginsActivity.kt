package com.forai.codepad

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max

class PluginsActivity : AppCompatActivity() {

    private lateinit var mgr: PluginManager
    private lateinit var content: LinearLayout

    private val installLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readAndConfirm(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mgr = PluginManager(this)
        mgr.seedBuiltins()
        mgr.reload()

        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Plugins"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setPadding(dp(20), dp(16), dp(20), dp(8))
        rootView.addView(title)

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content)
        rootView.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootView)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, max(b.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        render()
    }

    private fun render() {
        content.removeAllViews()

        action("+ Install plugin from file", "A .json plugin manifest") {
            installLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        action("Copy plugin template", "Starter manifest with a script, command and snippet") {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("CodePad plugin", TEMPLATE))
            toast("Template copied. Paste it into a new file, edit, save as .json, then install.")
        }
        action("Restore bundled plugins", "Re-installs the plugins that ship with CodePad") {
            mgr.seedBuiltins(true)
            mgr.reload()
            render()
        }

        header("Installed (" + mgr.plugins.size + ")")
        if (mgr.plugins.isEmpty()) note("No plugins installed.")
        for (p in mgr.plugins) pluginRow(p)

        if (mgr.errors.isNotEmpty()) {
            header("Problems")
            for (e in mgr.errors) note(e)
        }
        note("Plugin format and scripting API: see docs/PLUGINS.md in the CodePad repo.")
        content.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(32)))
    }

    // ------------------------------------------------------------------ rows

    private fun header(text: String) {
        val t = TextView(this)
        t.text = text.uppercase()
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.alpha = 0.6f
        t.setPadding(dp(20), dp(20), dp(20), dp(4))
        content.addView(t)
    }

    private fun note(text: String) {
        val t = TextView(this)
        t.text = text
        t.alpha = 0.6f
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.setPadding(dp(20), dp(8), dp(20), dp(8))
        content.addView(t)
    }

    private fun action(title: String, sub: String, onClick: () -> Unit) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(12), dp(20), dp(12))
        val a = TextView(this)
        a.text = title
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        col.addView(a)
        val b = TextView(this)
        b.text = sub
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        b.alpha = 0.6f
        col.addView(b)
        col.setOnClickListener { onClick() }
        content.addView(col)
    }

    private fun pluginRow(p: Plugin) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(10), dp(20), dp(10))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = p.name + "  v" + p.version
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        col.addView(name)
        val sub = TextView(this)
        sub.text = p.publisher + " · " + p.summary()
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        sub.alpha = 0.6f
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val sw = SwitchCompat(this)
        sw.isChecked = mgr.isEnabled(p.id)
        sw.setOnCheckedChangeListener { _, on -> mgr.setEnabled(p.id, on) }
        row.addView(sw)

        col.setOnClickListener { details(p) }
        content.addView(row)
    }

    // ------------------------------------------------------------------ dialogs

    private fun describe(p: Plugin): String {
        val sb = StringBuilder()
        if (p.description.isNotEmpty()) sb.append(p.description).append("\n\n")
        sb.append("Id: ").append(p.id).append('\n')
        sb.append("By: ").append(p.publisher).append("\n\n")
        sb.append("Permissions\n")
        if (p.permissions.isEmpty()) sb.append("  none\n")
        for (perm in p.permissions) sb.append("  • ").append(Perm.describe(perm)).append('\n')
        if (p.runners.isNotEmpty()) {
            sb.append("\nRunners\n")
            for (r in p.runners) sb.append("  • ").append(r.title).append(" (").append(r.type).append(")\n")
        }
        if (p.commands.isNotEmpty()) {
            sb.append("\nCommands\n")
            for (c in p.commands) sb.append("  • ").append(c.title).append('\n')
        }
        if (p.snippets.isNotEmpty()) {
            sb.append("\nSnippets: ").append(p.snippets.joinToString(", ") { it.prefix }).append('\n')
        }
        if (p.script.isNotEmpty()) sb.append("\nContains a script (runs in a sandbox with only the permissions above).\n")
        return sb.toString()
    }

    private fun details(p: Plugin) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setMessage(describe(p))
            .setPositiveButton("Close", null)
            .setNeutralButton("Uninstall") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Uninstall " + p.name + "?")
                    .setPositiveButton("Uninstall") { _, _ ->
                        mgr.uninstall(p.id)
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    private fun readAndConfirm(uri: Uri) {
        val loaded: Pair<String, Plugin> = try {
            val ins = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read file")
            val text = ins.use { String(it.readBytes(), Charsets.UTF_8) }
            Pair(text, PluginParser.parse(text))
        } catch (e: Exception) {
            toast("Can't install: " + (e.message ?: "invalid plugin"))
            return
        }
        val json = loaded.first
        val plugin = loaded.second
        val existing = mgr.byId(plugin.id)
        val title = if (existing != null) "Update " + plugin.name + "?" else "Install " + plugin.name + "?"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(describe(plugin) + "\nOnly install plugins you trust.")
            .setPositiveButton(if (existing != null) "Update" else "Install") { _, _ ->
                try {
                    mgr.install(json)
                    toast("Installed " + plugin.name)
                    render()
                } catch (e: Exception) {
                    toast("Install failed: " + (e.message ?: "error"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val TEMPLATE = """{
  "id": "my.hello-plugin",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "publisher": "me",
  "description": "Example plugin: one command and one snippet.",
  "engine": 1,
  "permissions": ["editor.read", "editor.write", "ui"],
  "contributes": {
    "commands": [
      { "id": "hello.shout", "title": "Hello: UPPERCASE selection" }
    ],
    "snippets": [
      { "language": "*", "prefix": "todo", "name": "TODO comment", "body": "TODO(${'$'}{1:name}): ${'$'}{2:what}${'$'}0" }
    ]
  },
  "script": "codepad.commands.register('hello.shout', function () { var t = codepad.editor.getSelectedText(); if (!t) { codepad.window.showMessage('Select some text first'); return; } codepad.editor.replaceSelection(t.toUpperCase()); });"
}
"""
    }
}
