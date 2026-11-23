package com.forai.codepad

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var editor: EditorView
    private lateinit var status: TextView
    private lateinit var symbolScroll: HorizontalScrollView
    private lateinit var symbolRow: LinearLayout
    private lateinit var findBar: View
    private lateinit var find: FindController
    private lateinit var prefs: SharedPreferences
    private lateinit var store: SessionStore

    private val docs = ArrayList<Doc>()
    private var active: Doc? = null
    private var pendingDoc: Doc? = null
    private var suppressTab = false
    private var untitledCounter = 1
    private var edTheme: EditorTheme = Themes.all[0]
    private val symbolViews = ArrayList<TextView>()
    private var findViews: List<TextView> = emptyList()
    private var findInputs: List<EditText> = emptyList()

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var pendingSaveDoc: Doc? = null
    private var pendingSaveThen: (() -> Unit)? = null

    private val openLauncher = registerForActivityResult(OpenDocs()) { uris ->
        for (u in uris) openUri(u)
    }

    private val createLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            onSaveAsResult(uri)
        }

    private val symbols = listOf(
        "TAB", "←", "→", "↑", "↓", "{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":",
        "\"", "'", "/", "\\", "|", "&", "!", "?", "#", "\$", "%", "*", "+", "-", "_", "~", "`", "@", "^"
    )

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("codepad", MODE_PRIVATE)
        store = SessionStore(this)

        root = findViewById(R.id.main)
        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)
        symbolScroll = findViewById(R.id.symbol_scroll)
        symbolRow = findViewById(R.id.symbol_row)
        findBar = findViewById(R.id.find_bar)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val findCount = findViewById<TextView>(R.id.find_count)
        val findPrev = findViewById<TextView>(R.id.find_prev)
        val findNext = findViewById<TextView>(R.id.find_next)
        val findClose = findViewById<TextView>(R.id.find_close)
        val replaceBtn = findViewById<TextView>(R.id.find_replace)
        val replaceAllBtn = findViewById<TextView>(R.id.find_replace_all)
        val caseToggle = findViewById<TextView>(R.id.find_case)
        val regexToggle = findViewById<TextView>(R.id.find_regex)
        val findInput = findViewById<EditText>(R.id.find_input)
        val replaceInput = findViewById<EditText>(R.id.replace_input)
        find = FindController(findBar, editor, findInput, replaceInput, findCount, caseToggle, regexToggle)
        findPrev.setOnClickListener { find.prev() }
        findNext.setOnClickListener { find.next() }
        findClose.setOnClickListener { find.hide() }
        replaceBtn.setOnClickListener { find.replaceOne() }
        replaceAllBtn.setOnClickListener { find.replaceAll() }
        findViews = listOf(findCount, findPrev, findNext, findClose, replaceBtn, replaceAllBtn, caseToggle, regexToggle)
        findInputs = listOf(findInput, replaceInput)

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.title = ""
        toolbar.setOnMenuItemClickListener { item -> onMenu(item.itemId) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (suppressTab) return
                val d = tab.tag as? Doc ?: return
                activate(d)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setupSymbols()
        loadSettings()

        editor.onCaretMoved = { updateStatus() }
        editor.onEdited = { onEdited() }
        editor.onZoom = { size -> prefs.edit().putFloat("font", size).apply() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (find.isVisible) {
                    find.hide()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        applyTheme()

        val restored = store.load()
        if (restored != null && restored.first.isNotEmpty()) {
            docs.addAll(restored.first)
            for (d in docs) {
                if (d.name.startsWith("Untitled-")) {
                    val n = d.name.removePrefix("Untitled-").toIntOrNull()
                    if (n != null && n >= untitledCounter) untitledCounter = n + 1
                }
            }
            rebuildTabs()
            activate(docs[restored.second.coerceIn(0, docs.size - 1)])
        } else {
            newDoc()
        }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        syncActive()
        if (prefs.getBoolean("autosave", false)) {
            for (d in docs) {
                val u = d.uri
                if (u != null && d.loaded && d.isModified) writeDoc(d, u, null, silent = true)
            }
        }
        store.save(docs, max(0, docs.indexOf(active)))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_S -> {
                    if (e.isShiftPressed) active?.let { saveAs(it) } else saveActive()
                    return true
                }
                KeyEvent.KEYCODE_O -> { openLauncher.launch(arrayOf("*/*")); return true }
                KeyEvent.KEYCODE_N -> { newDoc(); return true }
                KeyEvent.KEYCODE_F -> { find.show(); return true }
                KeyEvent.KEYCODE_Z -> { if (e.isShiftPressed) editor.redo() else editor.undo(); return true }
                KeyEvent.KEYCODE_Y -> { editor.redo(); return true }
                KeyEvent.KEYCODE_W -> { active?.let { closeDoc(it) }; return true }
                KeyEvent.KEYCODE_G -> { gotoLine(); return true }
                KeyEvent.KEYCODE_D -> { editor.duplicateLines(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ settings / theme

    private fun loadSettings() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val saved = Themes.byName(prefs.getString("theme", null))
        edTheme = saved ?: if (night) Themes.all[0] else Themes.all[4]
        editor.wordWrap = prefs.getBoolean("wrap", true)
        editor.showLineNumbers = prefs.getBoolean("lines", true)
        editor.autoIndent = prefs.getBoolean("indent", true)
        editor.tabSize = prefs.getInt("tabsize", 4)
        editor.useSpaces = prefs.getBoolean("spaces", true)
        editor.applyFontSize(prefs.getFloat("font", 14f))
        val m = toolbar.menu
        m.findItem(R.id.m_wrap)?.isChecked = editor.wordWrap
        m.findItem(R.id.m_lines)?.isChecked = editor.showLineNumbers
        m.findItem(R.id.m_autoindent)?.isChecked = editor.autoIndent
        m.findItem(R.id.m_autosave)?.isChecked = prefs.getBoolean("autosave", false)
    }

    private fun applyTheme() {
        val t = edTheme
        root.setBackgroundColor(t.bar)
        toolbar.setBackgroundColor(t.bar)
        tabs.setBackgroundColor(t.bar)
        tabs.setSelectedTabIndicatorColor(t.accent)
        status.setBackgroundColor(t.bar)
        status.setTextColor((t.barFg and 0x00FFFFFF) or (0xB0 shl 24))
        symbolScroll.setBackgroundColor(t.gutterBg)
        for (v in symbolViews) v.setTextColor(t.fg)
        findBar.setBackgroundColor(t.bar)
        for (v in findViews) v.setTextColor(t.barFg)
        for (v in findInputs) {
            v.setTextColor(t.barFg)
            v.setHintTextColor((t.barFg and 0x00FFFFFF) or (0x80 shl 24))
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val mi = menu.getItem(i)
            val ic = mi.icon
            if (ic != null) {
                val d = ic.mutate()
                d.setTint(t.barFg)
                mi.icon = d
            }
        }
        val oi = toolbar.overflowIcon
        if (oi != null) {
            val d = oi.mutate()
            d.setTint(t.barFg)
            toolbar.overflowIcon = d
        }
        editor.edTheme = t
        val wic = WindowCompat.getInsetsController(window, root)
        wic.isAppearanceLightStatusBars = t.isLight
        wic.isAppearanceLightNavigationBars = t.isLight
        refreshTabs()
        updateUndoRedo()
    }

    private fun pickTheme() {
        val names = Themes.all.map { it.name }.toTypedArray()
        val cur = Themes.all.indexOfFirst { it.name == edTheme.name }
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(names, cur) { dlg, which ->
                edTheme = Themes.all[which]
                prefs.edit().putString("theme", edTheme.name).apply()
                applyTheme()
                dlg.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ symbol bar

    private fun setupSymbols() {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        for (s in symbols) {
            val b = TextView(this)
            b.text = s
            b.gravity = Gravity.CENTER
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (s == "TAB") 13f else 17f)
            b.minWidth = dp(if (s == "TAB") 52 else 40)
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
            b.setPadding(dp(6), 0, dp(6), 0)
            b.isClickable = true
            if (tv.resourceId != 0) b.setBackgroundResource(tv.resourceId)
            b.setOnClickListener { onSymbol(s) }
            symbolRow.addView(b)
            symbolViews.add(b)
        }
    }

    private fun onSymbol(s: String) {
        when (s) {
            "TAB" -> editor.insertTab()
            "←" -> editor.moveCaret(-1)
            "→" -> editor.moveCaret(1)
            "↑" -> editor.moveCaretVertical(true)
            "↓" -> editor.moveCaretVertical(false)
            else -> editor.insertAtCaret(s)
        }
    }

    // ------------------------------------------------------------------ menu

    private fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.m_open -> openLauncher.launch(arrayOf("*/*"))
            R.id.m_save -> saveActive()
            R.id.m_undo -> editor.undo()
            R.id.m_redo -> editor.redo()
            R.id.m_find -> find.show()
            R.id.m_new -> newDoc()
            R.id.m_save_as -> active?.let { saveAs(it) }
            R.id.m_recent -> showRecent()
            R.id.m_close -> active?.let { closeDoc(it) }
            R.id.m_goto -> gotoLine()
            R.id.m_selline -> editor.selectLine()
            R.id.m_dup -> editor.duplicateLines()
            R.id.m_del -> editor.deleteLines()
            R.id.m_up -> editor.moveLines(true)
            R.id.m_down -> editor.moveLines(false)
            R.id.m_comment -> toggleComment()
            R.id.m_indent -> editor.indentSelection(false)
            R.id.m_unindent -> editor.indentSelection(true)
            R.id.m_wrap -> {
                editor.wordWrap = !editor.wordWrap
                toolbar.menu.findItem(R.id.m_wrap)?.isChecked = editor.wordWrap
                prefs.edit().putBoolean("wrap", editor.wordWrap).apply()
            }
            R.id.m_lines -> {
                editor.showLineNumbers = !editor.showLineNumbers
                toolbar.menu.findItem(R.id.m_lines)?.isChecked = editor.showLineNumbers
                prefs.edit().putBoolean("lines", editor.showLineNumbers).apply()
            }
            R.id.m_autoindent -> {
                editor.autoIndent = !editor.autoIndent
                toolbar.menu.findItem(R.id.m_autoindent)?.isChecked = editor.autoIndent
                prefs.edit().putBoolean("indent", editor.autoIndent).apply()
            }
            R.id.m_autosave -> {
                val on = !prefs.getBoolean("autosave", false)
                prefs.edit().putBoolean("autosave", on).apply()
                toolbar.menu.findItem(R.id.m_autosave)?.isChecked = on
            }
            R.id.m_indentation -> pickIndentation()
            R.id.m_theme -> pickTheme()
            R.id.m_fontup -> changeFont(1f)
            R.id.m_fontdown -> changeFont(-1f)
            R.id.m_lang -> pickLanguage()
            R.id.m_enc -> pickEncoding()
            R.id.m_eol -> pickLineEnding()
            R.id.m_share -> shareText()
        }
        return true
    }

    private fun changeFont(delta: Float) {
        editor.applyFontSize(editor.fontSp + delta)
        prefs.edit().putFloat("font", editor.fontSp).apply()
    }

    private fun toggleComment() {
        val prefix = active?.lang?.lineComment
        if (prefix == null) toast("No line comment for this language") else editor.toggleComment(prefix)
    }

    // ------------------------------------------------------------------ documents / tabs

    private fun newDoc(text: String = ""): Doc {
        val d = Doc(null, "Untitled-" + (untitledCounter++))
        d.content = text
        d.loaded = true
        if (text.isNotEmpty()) d.undo.forceModified()
        docs.add(d)
        rebuildTabs()
        activate(d)
        return d
    }

    private fun rebuildTabs() {
        suppressTab = true
        tabs.removeAllTabs()
        for (d in docs) {
            val tab = tabs.newTab()
            val v = layoutInflater.inflate(R.layout.tab_item, tabs, false)
            v.findViewById<TextView>(R.id.tab_close).setOnClickListener { closeDoc(d) }
            tab.customView = v
            tab.tag = d
            tabs.addTab(tab, false)
        }
        val cur = pendingDoc ?: active
        val i = if (cur != null) docs.indexOf(cur) else -1
        if (i >= 0) tabs.getTabAt(i)?.select()
        suppressTab = false
        refreshTabs()
    }

    private fun selectTabQuiet(d: Doc) {
        val i = docs.indexOf(d)
        if (i < 0) return
        suppressTab = true
        tabs.getTabAt(i)?.select()
        suppressTab = false
    }

    private fun refreshTabs() {
        val cur = pendingDoc ?: active
        for (i in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(i) ?: continue
            val d = tab.tag as? Doc ?: continue
            val v = tab.customView ?: continue
            val title = v.findViewById<TextView>(R.id.tab_title)
            val close = v.findViewById<TextView>(R.id.tab_close)
            val text = (if (d.isModified) "● " else "") + d.name
            if (title.text.toString() != text) title.text = text
            val sel = d === cur
            title.setTextColor(edTheme.barFg)
            title.alpha = if (sel) 1f else 0.6f
            close.setTextColor(edTheme.barFg)
            close.alpha = if (sel) 0.9f else 0.5f
        }
    }

    private fun syncActive() {
        val d = active ?: return
        if (!d.loaded) return
        d.content = editor.buffer.toString()
        d.selStart = editor.selectionStart
        d.selEnd = editor.selectionEnd
    }

    private fun activate(d: Doc) {
        if (d === active && pendingDoc == null) {
            selectTabQuiet(d)
            return
        }
        syncActive()
        pendingDoc = d
        selectTabQuiet(d)
        refreshTabs()
        ensureLoaded(d) {
            if (pendingDoc === d) {
                pendingDoc = null
                active = d
                showInEditor(d)
            }
        }
    }

    private fun showInEditor(d: Doc) {
        editor.undoManager = d.undo
        editor.setLanguage(d.lang)
        editor.setContent(d.content)
        val len = editor.buffer.length
        editor.setSelection(d.selStart.coerceIn(0, len), d.selEnd.coerceIn(0, len))
        editor.requestFocus()
        find.onDocChanged()
        refreshAll()
    }

    private fun ensureLoaded(d: Doc, then: () -> Unit) {
        if (d.loaded) {
            then()
            return
        }
        val uri = d.uri
        if (uri == null) {
            d.loaded = true
            then()
            return
        }
        status.text = "Loading…"
        io.execute {
            var res: FileIO.Loaded? = null
            var err: Exception? = null
            try {
                res = FileIO.read(contentResolver, uri)
            } catch (e: Exception) {
                err = e
            }
            val r = res
            val e2 = err
            ui.post {
                if (r != null) {
                    d.content = r.text
                    d.crlf = r.crlf
                    d.charset = r.charset
                    d.bom = r.bom
                    d.loaded = true
                    then()
                } else {
                    toast("Can't open ${d.name}: ${e2?.message ?: "unknown error"}")
                    removeDoc(d)
                }
            }
        }
    }

    private fun closeDoc(d: Doc) {
        if (d === active) syncActive()
        if (!d.isModified) {
            removeDoc(d)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save \"${d.name}\" before closing?")
            .setPositiveButton("Save") { _, _ -> saveDoc(d) { removeDoc(d) } }
            .setNegativeButton("Discard") { _, _ -> removeDoc(d) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun removeDoc(d: Doc) {
        val i = docs.indexOf(d)
        if (i < 0) return
        val wasShown = d === active || d === pendingDoc
        docs.removeAt(i)
        if (d === active) active = null
        if (d === pendingDoc) pendingDoc = null
        if (docs.isEmpty()) {
            newDoc()
            return
        }
        rebuildTabs()
        if (wasShown) activate(docs[min(i, docs.size - 1)])
    }

    private fun openUri(uri: Uri) {
        val existing = docs.firstOrNull { it.uri == uri }
        if (existing != null) {
            activate(existing)
            return
        }
        persistPermission(uri)
        val name = FileIO.displayName(contentResolver, uri)
        val d = Doc(uri, name)
        docs.add(d)
        addRecent(uri, name)
        rebuildTabs()
        activate(d)
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                // temporary grant only
            }
        }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> i.data?.let { openUri(it) }
            Intent.ACTION_SEND -> {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)
                if (t != null) {
                    newDoc(t)
                } else {
                    val u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    if (u != null) openUri(u)
                }
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private fun saveActive() {
        val d = active ?: return
        saveDoc(d)
    }

    private fun saveDoc(d: Doc, then: (() -> Unit)? = null) {
        if (d === active) syncActive()
        val uri = d.uri
        if (uri == null) {
            saveAs(d, then)
            return
        }
        writeDoc(d, uri, then)
    }

    private fun saveAs(d: Doc, then: (() -> Unit)? = null) {
        if (d === active) syncActive()
        pendingSaveDoc = d
        pendingSaveThen = then
        try {
            createLauncher.launch(d.name)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    private fun onSaveAsResult(uri: Uri?) {
        val d = pendingSaveDoc
        val then = pendingSaveThen
        pendingSaveDoc = null
        pendingSaveThen = null
        if (uri == null || d == null) return
        persistPermission(uri)
        d.uri = uri
        d.name = FileIO.displayName(contentResolver, uri)
        if (d === active) editor.setLanguage(d.lang)
        addRecent(uri, d.name)
        writeDoc(d, uri, then)
        refreshAll()
    }

    private fun writeDoc(d: Doc, uri: Uri, then: (() -> Unit)?, silent: Boolean = false) {
        FileIO.fixCharset(d)
        val text = d.content
        val crlf = d.crlf
        val cs = d.charset
        val bom = d.bom
        val ver = d.undo.version
        io.execute {
            var err: Exception? = null
            try {
                FileIO.write(contentResolver, uri, FileIO.encode(text, crlf, cs, bom))
            } catch (e: Exception) {
                err = e
            }
            val e2 = err
            ui.post {
                if (e2 == null) {
                    if (d.undo.version == ver) d.undo.markSaved()
                    if (!silent) toast("Saved ${d.name}")
                    refreshAll()
                    then?.invoke()
                } else if (!silent) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Couldn't save")
                        .setMessage((e2.message ?: e2.javaClass.simpleName) + "\n\nSave a copy somewhere else?")
                        .setPositiveButton("Save as…") { _, _ -> saveAs(d, then) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI refresh

    private fun onEdited() {
        if (active == null) return
        refreshTabs()
        updateUndoRedo()
        updateStatus()
        find.onTextEdited()
    }

    private fun refreshAll() {
        refreshTabs()
        updateUndoRedo()
        updateStatus()
    }

    private fun updateUndoRedo() {
        val menu = toolbar.menu
        val u = menu.findItem(R.id.m_undo)
        val r = menu.findItem(R.id.m_redo)
        val um = editor.undoManager
        u?.isEnabled = um.canUndo()
        r?.isEnabled = um.canRedo()
        u?.icon?.alpha = if (um.canUndo()) 255 else 90
        r?.icon?.alpha = if (um.canRedo()) 255 else 90
    }

    private fun updateStatus() {
        val d = active ?: return
        val off = max(0, editor.selectionStart)
        val line = editor.lineOfOffset(off) + 1
        val col = off - editor.lineStart(off) + 1
        val sel = abs(editor.selectionEnd - editor.selectionStart)
        val sb = StringBuilder()
        sb.append("Ln ").append(line).append(", Col ").append(col)
        if (sel > 0) sb.append(" (").append(sel).append(" sel)")
        sb.append("   ").append(editor.buffer.length).append(" chars")
        sb.append("   ").append(d.charset)
        if (d.bom) sb.append("+BOM")
        sb.append("   ").append(if (d.crlf) "CRLF" else "LF")
        sb.append("   ").append(d.lang.name)
        status.text = sb.toString()
    }

    // ------------------------------------------------------------------ dialogs

    private fun gotoLine() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "Line number (1–" + editor.totalLines() + ")"
        MaterialAlertDialogBuilder(this)
            .setTitle("Go to line")
            .setView(boxed(et))
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().trim().toIntOrNull()
                if (n != null) {
                    val off = editor.offsetOfLine(n)
                    editor.requestFocus()
                    editor.setSelection(off)
                    editor.revealOffset(off)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun boxed(v: View): View {
        val f = FrameLayout(this)
        f.setPadding(dp(20), dp(8), dp(20), 0)
        f.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return f
    }

    private fun pickLanguage() {
        val d = active ?: return
        val names = (listOf("Auto-detect") + Langs.all.map { it.name }).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Language")
            .setItems(names) { _, which ->
                d.langOverride = if (which == 0) null else Langs.all[which - 1]
                editor.setLanguage(d.lang)
                updateStatus()
            }
            .show()
    }

    private fun pickEncoding() {
        val d = active ?: return
        val names = arrayOf("UTF-8", "UTF-8 with BOM", "UTF-16 LE", "UTF-16 BE", "ISO-8859-1 (Latin-1)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Save encoding")
            .setItems(names) { _, which ->
                when (which) {
                    0 -> { d.charset = "UTF-8"; d.bom = false }
                    1 -> { d.charset = "UTF-8"; d.bom = true }
                    2 -> { d.charset = "UTF-16LE"; d.bom = true }
                    3 -> { d.charset = "UTF-16BE"; d.bom = true }
                    else -> { d.charset = "ISO-8859-1"; d.bom = false }
                }
                d.undo.forceModified()
                refreshAll()
            }
            .show()
    }

    private fun pickLineEnding() {
        val d = active ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Line endings")
            .setSingleChoiceItems(arrayOf("LF (Unix, Android)", "CRLF (Windows)"), if (d.crlf) 1 else 0) { dlg, which ->
                d.crlf = which == 1
                d.undo.forceModified()
                refreshAll()
                dlg.dismiss()
            }
            .show()
    }

    private fun pickIndentation() {
        val names = arrayOf("2 spaces", "4 spaces", "8 spaces", "Tabs")
        MaterialAlertDialogBuilder(this)
            .setTitle("Indentation")
            .setItems(names) { _, which ->
                when (which) {
                    0 -> { editor.useSpaces = true; editor.tabSize = 2 }
                    1 -> { editor.useSpaces = true; editor.tabSize = 4 }
                    2 -> { editor.useSpaces = true; editor.tabSize = 8 }
                    else -> { editor.useSpaces = false; editor.tabSize = 4 }
                }
                prefs.edit().putBoolean("spaces", editor.useSpaces).putInt("tabsize", editor.tabSize).apply()
            }
            .show()
    }

    private fun shareText() {
        val t = editor.buffer
        if (t.length > 500_000) {
            toast("Text too large to share")
            return
        }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, t.toString())
        startActivity(Intent.createChooser(i, "Share text"))
    }

    // ------------------------------------------------------------------ recent files

    private fun addRecent(uri: Uri, name: String) {
        try {
            val old = JSONArray(prefs.getString("recent", "[]") ?: "[]")
            val out = JSONArray()
            val first = JSONObject()
            first.put("u", uri.toString())
            first.put("n", name)
            out.put(first)
            for (k in 0 until old.length()) {
                val o = old.getJSONObject(k)
                if (o.optString("u") != uri.toString() && out.length() < 12) out.put(o)
            }
            prefs.edit().putString("recent", out.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showRecent() {
        val uris = ArrayList<String>()
        val names = ArrayList<String>()
        try {
            val arr = JSONArray(prefs.getString("recent", "[]") ?: "[]")
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                uris.add(o.optString("u"))
                names.add(o.optString("n"))
            }
        } catch (e: Exception) {
            // ignore
        }
        if (names.isEmpty()) {
            toast("No recent files yet")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Recent files")
            .setItems(names.toTypedArray()) { _, which -> openUri(Uri.parse(uris[which])) }
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
