package com.forai.codepad

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import java.security.MessageDigest
import kotlin.math.max

class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: AppSettings
    private lateinit var content: LinearLayout

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cfg = AppSettings(this)

        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Settings"
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

    // ------------------------------------------------------------------ screen

    private fun render() {
        content.removeAllViews()

        header("Extensions")
        choiceRow("Plugins", "Runners, snippets, commands and scripts") {
            startActivity(android.content.Intent(this, PluginsActivity::class.java))
        }

        header("Editor")
        choiceRow("Theme", cfg.theme ?: "Automatic (follows system)") { pickTheme() }
        choiceRow("Font size", cfg.font.toInt().toString() + " sp") { pickFont() }
        choiceRow("Indentation", indentationLabel()) { pickIndentation() }
        switchRow("Word wrap", null, cfg.wrap) { cfg.wrap = it }
        switchRow("Line numbers", null, cfg.lines) { cfg.lines = it }
        switchRow("Auto-indent", "Keep indentation on Enter, expand {|} blocks", cfg.autoIndent) { cfg.autoIndent = it }
        switchRow("Bracket matching", "Highlight the partner of the bracket at the cursor", cfg.bracketMatch) { cfg.bracketMatch = it }
        switchRow("Indent guides", "Vertical lines at each indentation level", cfg.guides) { cfg.guides = it }

        header("Saving")
        switchRow("Auto-save on exit", "Write modified files when you leave the app", cfg.autosave) { cfg.autosave = it }
        switchRow("Trim trailing spaces on save", null, cfg.trimOnSave) { cfg.trimOnSave = it }
        switchRow("Add final newline on save", null, cfg.finalNewline) { cfg.finalNewline = it }

        header("Auto-close")
        switchRow("Auto-close pairs", "Typing a trigger inserts its completion. Selected text gets wrapped.", cfg.autoClose) {
            cfg.autoClose = it
        }
        val list = cfg.pairs
        if (list.isEmpty()) {
            note("No pairs. Tap \"Add pair\" to create one.")
        }
        for (p in list) pairRow(p)
        choiceRow("+ Add pair", "Trigger and completion, e.g.  <  →  >") { addPairDialog() }
        choiceRow("Reset pairs to default", "( )  [ ]  { }  \" \"  ' '  ` `") {
            cfg.pairs = AppSettings.DEFAULT_PAIRS
            render()
        }

        header("Backup")
        choiceRow("Export settings", "Save every preference as a JSON file") {
            exportLauncher.launch("codepad-settings.json")
        }
        choiceRow("Import settings", "Load a JSON file made by Export") {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        choiceRow("Reset all settings", "Back to factory defaults") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset all settings?")
                .setMessage("Your files are not touched.")
                .setPositiveButton("Reset") { _, _ ->
                    cfg.resetAll()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        header("About")
        choiceRow("CodePad", aboutLine()) { showAbout() }

        val pad = View(this)
        content.addView(pad, LinearLayout.LayoutParams(1, dp(32)))
    }

    private fun aboutLine(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "Version " + pi.versionName + " (build " + pi.longVersionCode + ")"
    } catch (e: Exception) {
        "Version unknown"
    }

    private fun showAbout() {
        val text = try {
            val pi = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val sig = pi.signingInfo?.apkContentsSigners?.firstOrNull()
            val fp = if (sig != null) {
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
            } else {
                "unknown"
            }
            "Version " + pi.versionName + " (build " + pi.longVersionCode + ")\n\nSigning key (SHA-256):\n" + fp +
                    "\n\nIf this fingerprint is the same in every build, new APKs install over old ones and keep your data."
        } catch (e: Exception) {
            "Could not read app info: " + e.message
        }
        MaterialAlertDialogBuilder(this).setTitle("About CodePad").setMessage(text).setPositiveButton("Close", null).show()
    }

    private fun indentationLabel(): String =
        if (!cfg.spaces) "Tabs" else cfg.tabSize.toString() + " spaces"

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
        t.setPadding(dp(20), dp(8), dp(20), dp(8))
        content.addView(t)
    }

    private fun twoLine(title: String, sub: String?): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val a = TextView(this)
        a.text = title
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        col.addView(a)
        if (!sub.isNullOrEmpty()) {
            val b = TextView(this)
            b.text = sub
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            b.alpha = 0.6f
            col.addView(b)
        }
        return col
    }

    private fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(10), dp(20), dp(10))
        val col = twoLine(title, sub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        content.addView(row)
    }

    private fun choiceRow(title: String, sub: String?, onClick: () -> Unit) {
        val row = twoLine(title, sub)
        row.setPadding(dp(20), dp(12), dp(20), dp(12))
        row.setOnClickListener { onClick() }
        content.addView(row)
    }

    private fun pairRow(p: PairDef) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(6), dp(8), dp(6))

        val label = TextView(this)
        label.text = shown(p.open) + "   →   " + shown(p.close)
        label.typeface = Typeface.MONOSPACE
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val del = TextView(this)
        del.text = "✕"
        del.gravity = Gravity.CENTER
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        del.setPadding(dp(14), dp(10), dp(14), dp(10))
        del.setOnClickListener {
            cfg.pairs = cfg.pairs.filter { it.open != p.open }
            render()
        }
        row.addView(del)
        content.addView(row)
    }

    private fun shown(s: String): String = s.replace("\n", "⏎").replace("\t", "⇥").replace(" ", "␣")

    // ------------------------------------------------------------------ dialogs

    private fun pickTheme() {
        val names = (listOf("Automatic (follows system)") + Themes.all.map { it.name }).toTypedArray()
        val cur = if (cfg.theme == null) 0 else Themes.all.indexOfFirst { it.name == cfg.theme } + 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                cfg.theme = if (which == 0) null else Themes.all[which - 1].name
                dlg.dismiss()
                render()
            }
            .show()
    }

    private fun pickFont() {
        val sizes = intArrayOf(10, 12, 13, 14, 15, 16, 18, 20, 24)
        val names = sizes.map { "$it sp" }.toTypedArray()
        val cur = sizes.indexOf(cfg.font.toInt())
        MaterialAlertDialogBuilder(this)
            .setTitle("Font size")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                cfg.font = sizes[which].toFloat()
                dlg.dismiss()
                render()
            }
            .show()
    }

    private fun pickIndentation() {
        val names = arrayOf("2 spaces", "4 spaces", "8 spaces", "Tabs")
        MaterialAlertDialogBuilder(this)
            .setTitle("Indentation")
            .setItems(names) { _, which ->
                when (which) {
                    0 -> { cfg.spaces = true; cfg.tabSize = 2 }
                    1 -> { cfg.spaces = true; cfg.tabSize = 4 }
                    2 -> { cfg.spaces = true; cfg.tabSize = 8 }
                    else -> { cfg.spaces = false; cfg.tabSize = 4 }
                }
                render()
            }
            .show()
    }

    private fun addPairDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val trigger = EditText(this)
        trigger.hint = "Trigger  (e.g.  <  or  <!--)"
        trigger.typeface = Typeface.MONOSPACE
        trigger.setSingleLine()
        box.addView(trigger)

        val completion = EditText(this)
        completion.hint = "Completes with  (e.g.  >  or  -->)"
        completion.typeface = Typeface.MONOSPACE
        completion.setSingleLine()
        box.addView(completion)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add auto-close pair")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val t = trigger.text.toString()
                val c = completion.text.toString()
                if (t.isEmpty() || c.isEmpty()) {
                    toast("Both fields are required")
                } else {
                    val kept = cfg.pairs.filter { it.open != t }
                    cfg.pairs = kept + PairDef(t, c)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ backup

    private fun doExport(uri: Uri) {
        try {
            val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Cannot write file")
            out.use { it.write(cfg.exportJson().toByteArray(Charsets.UTF_8)) }
            toast("Settings exported")
        } catch (e: Exception) {
            toast("Export failed: " + (e.message ?: "unknown error"))
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val ins = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot read file")
            val text = ins.use { String(it.readBytes(), Charsets.UTF_8) }
            val n = cfg.importJson(text)
            toast("Imported $n settings")
            render()
        } catch (e: Exception) {
            toast("Import failed: " + (e.message ?: "invalid file"))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
