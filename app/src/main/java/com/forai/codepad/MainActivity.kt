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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.BaseAdapter
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.ScrollView
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
    private lateinit var cfg: AppSettings
    private var checkingExternal = false
    private var writesInFlight = 0

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
        cfg = AppSettings(this)

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

        status.movementMethod = LinkMovementMethod.getInstance()
        status.highlightColor = Color.TRANSPARENT
        setupSymbols()
        setupPlugins()
        setupUi2()
        applySettings()

        editor.onCaretMoved = { updateStatus() }
        editor.onEdited = { onEdited() }
        editor.onZoom = { size -> prefs.edit().putFloat("font", size).apply() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (explorer.isOpen) {
                    explorer.hide()
                } else if (find.isVisible) {
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
            showEmptyState()
        }

        if (savedInstanceState == null) handleIntent(intent)
        showCrashIfAny()
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

    override fun onResume() {
        super.onResume()
        syncPlugins()
        applySettings()
        checkExternalChange()
    }

    private fun checkExternalChange() {
        val d = active ?: return
        val uri = d.uri ?: return
        if (!d.loaded || d.diskTime == 0L || checkingExternal || writesInFlight > 0) return
        checkingExternal = true
        io.execute {
            val t = FileIO.lastModified(contentResolver, uri)
            ui.post {
                checkingExternal = false
                if (t != 0L && t > d.diskTime && d === active) promptExternal(d, uri, t)
            }
        }
    }

    private fun promptExternal(d: Doc, uri: Uri, t: Long) {
        d.diskTime = t
        val warn = if (d.isModified) " Reloading discards your unsaved changes." else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("File changed on disk")
            .setMessage("\"${d.name}\" was modified outside CodePad.$warn")
            .setPositiveButton("Reload") { _, _ -> reloadDoc(d, uri) }
            .setNegativeButton("Keep mine") { _, _ ->
                d.undo.forceModified()
                refreshAll()
            }
            .show()
    }

    private fun reloadDoc(d: Doc, uri: Uri) {
        io.execute {
            var res: FileIO.Loaded? = null
            var err: Throwable? = null
            try {
                res = FileIO.read(contentResolver, uri)
            } catch (e: Throwable) {
                err = e
            }
            val t = FileIO.lastModified(contentResolver, uri)
            val r = res
            val e2 = err
            ui.post {
                if (r != null && r.kind == FileIO.KIND_TEXT) {
                    d.content = r.text
                    d.crlf = r.crlf
                    d.charset = r.charset
                    d.bom = r.bom
                    d.diskTime = t
                    d.undo.reset()
                    if (d === active) showInEditor(d)
                    toast("Reloaded " + d.name)
                } else {
                    toast("Reload failed: " + (e2?.message ?: "the file is now binary or too large"))
                }
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_F5) {
            runActive()
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_S -> {
                    if (e.isShiftPressed) active?.let { saveAs(it) } else saveActive()
                    return true
                }
                KeyEvent.KEYCODE_O -> { openLauncher.launch(arrayOf("*/*")); return true }
                KeyEvent.KEYCODE_N -> { newDoc(); return true }
                KeyEvent.KEYCODE_F -> { if (active == null) return true; find.show(); return true }
                KeyEvent.KEYCODE_Z -> { if (active != null) { if (e.isShiftPressed) editor.redo() else editor.undo() }; return true }
                KeyEvent.KEYCODE_B -> { explorer.toggle(); return true }
                KeyEvent.KEYCODE_Y -> { editor.redo(); return true }
                KeyEvent.KEYCODE_W -> { active?.let { closeDoc(it) }; return true }
                KeyEvent.KEYCODE_G -> { if (active == null) return true; gotoLine(); return true }
                KeyEvent.KEYCODE_ENTER -> { runActive(); return true }
                KeyEvent.KEYCODE_P -> { if (e.isShiftPressed) { showPalette(); return true } }
                KeyEvent.KEYCODE_D -> { if (active == null) return true; editor.duplicateLines(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ settings / theme

    private fun applySettings() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        edTheme = Themes.byName(cfg.theme) ?: if (night) Themes.all[0] else Themes.all[4]
        val cur = active
        if (cur != null && cur.forceWrap) {
            if (!editor.wordWrap) editor.wordWrap = true
        } else if (editor.wordWrap != cfg.wrap) {
            editor.wordWrap = cfg.wrap
        }
        if (editor.showLineNumbers != cfg.lines) editor.showLineNumbers = cfg.lines
        editor.autoIndent = cfg.autoIndent
        editor.tabSize = cfg.tabSize
        editor.useSpaces = cfg.spaces
        editor.autoClose = cfg.autoClose
        editor.pairs = cfg.pairs
        if (editor.smoothCaret != cfg.smoothCaret) editor.smoothCaret = cfg.smoothCaret
        if (editor.smoothSelection != cfg.smoothSelection) editor.smoothSelection = cfg.smoothSelection
        editor.smartBackspace = cfg.pairBackspace
        if (editor.showWhitespace != cfg.showWhitespace) editor.showWhitespace = cfg.showWhitespace
        if (editor.bracketMatch != cfg.bracketMatch) editor.bracketMatch = cfg.bracketMatch
        if (editor.showGuides != cfg.guides) editor.showGuides = cfg.guides
        if (editor.fontSp != cfg.font) editor.applyFontSize(cfg.font)
        val m = toolbar.menu
        m.findItem(R.id.m_wrap)?.isChecked = editor.wordWrap
        m.findItem(R.id.m_lines)?.isChecked = editor.showLineNumbers
        m.findItem(R.id.m_autoindent)?.isChecked = editor.autoIndent
        m.findItem(R.id.m_autosave)?.isChecked = cfg.autosave
        active?.let { applyDocIndent(it) }
        applyTheme()
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
        tabRow.setBackgroundColor(t.bar)
        welcomePanel.setBackgroundColor(t.bg)
        welcomeTitle.setTextColor(t.fg)
        welcomeSub.setTextColor((t.fg and 0x00FFFFFF) or 0x99000000.toInt())
        welcomeRecentHeader.setTextColor((t.fg and 0x00FFFFFF) or 0x88000000.toInt())
        for ((i, b) in welcomeButtons.withIndex()) styleButton(b, i == 0)
        styleButton(findViewById(R.id.ex_empty_open), true)
        findViewById<TextView>(R.id.ex_empty_text).setTextColor(t.barFg)
        explorer.applyTheme(t)
        viewerPanel.setBackgroundColor(t.bg)
        viewerBar.setBackgroundColor(t.bar)
        viewerInfo.setTextColor(t.barFg)
        for (b2 in viewerButtons) b2.setTextColor(t.barFg)
        viewer.setTheme(t)
        refreshWelcomeRecent()
        btnNew.setColorFilter(t.barFg)
        btnOpen.setColorFilter(t.barFg)
        outputPanel.setBackgroundColor(t.gutterBg)
        outputText.setTextColor(t.fg)
        outputTitle.setTextColor(t.barFg)
        for (v in outputButtons) v.setTextColor(t.barFg)
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

    private val needsEditor by lazy {
        setOf(
            R.id.m_save, R.id.m_save_as, R.id.m_undo, R.id.m_redo, R.id.m_find, R.id.m_goto, R.id.m_selline,
            R.id.m_dup, R.id.m_del, R.id.m_up, R.id.m_down, R.id.m_comment, R.id.m_indent, R.id.m_unindent,
            R.id.m_tools, R.id.m_bracket, R.id.m_outline, R.id.m_snippet, R.id.m_lang, R.id.m_enc, R.id.m_eol,
            R.id.m_share, R.id.m_run, R.id.m_runwith
        )
    }

    private val needsDoc by lazy {
        setOf(
            R.id.m_save, R.id.m_save_as, R.id.m_undo, R.id.m_redo, R.id.m_find, R.id.m_goto, R.id.m_selline,
            R.id.m_dup, R.id.m_del, R.id.m_up, R.id.m_down, R.id.m_comment, R.id.m_indent, R.id.m_unindent,
            R.id.m_tools, R.id.m_bracket, R.id.m_outline, R.id.m_snippet, R.id.m_lang, R.id.m_enc, R.id.m_eol,
            R.id.m_share, R.id.m_close, R.id.m_run, R.id.m_runwith
        )
    }

    private fun onMenu(id: Int): Boolean {
        val cur0 = active
        if (cur0 != null && cur0.viewer && id in needsEditor) {
            when (id) {
                R.id.m_find -> viewerFind()
                R.id.m_goto -> viewerGoto()
                else -> toast("Not available in the large-file viewer. Tap Edit to open part of the file.")
            }
            return true
        }
        if (active == null && id in needsDoc) {
            toast("Open or create a file first")
            return true
        }
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
            R.id.m_close_others -> closeOthers()
            R.id.m_find_all -> globalSearch()
            R.id.m_more -> showMoreMenu()
            R.id.m_open_folder -> folderLauncher.launch(null)
            R.id.m_explorer -> explorer.toggle()
            R.id.m_outline -> showOutline()
            R.id.m_close_all -> closeAll()
            R.id.m_save_all -> saveAll()
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
            R.id.m_tools -> showTools()
            R.id.m_bracket -> if (!editor.jumpToMatchingBracket()) toast("No matching bracket here")
            R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.m_run -> runActive()
            R.id.m_runwith -> runActive(true)
            R.id.m_output -> outputPanel.visibility = if (outputPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            R.id.m_palette -> showPalette()
            R.id.m_snippet -> pickSnippet()
            R.id.m_plugins -> startActivity(Intent(this, PluginsActivity::class.java))
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

    private fun pill(color: Int): GradientDrawable {
        val g = GradientDrawable()
        val r = dp(9).toFloat()
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        g.setColor(color)
        return g
    }

    private fun refreshTabs() {
        val cur = pendingDoc ?: active
        for (i in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(i) ?: continue
            val d = tab.tag as? Doc ?: continue
            val v = tab.customView ?: continue
            val title = v.findViewById<TextView>(R.id.tab_title)
            val close = v.findViewById<TextView>(R.id.tab_close)
            val dot = v.findViewById<View>(R.id.tab_dot)
            val text = (if (d.isModified) "● " else "") + d.name + (if (d.viewer) " (view)" else "")
            if (title.text.toString() != text) title.text = text
            val sel = d === cur
            val key = "" + sel + edTheme.bg
            if (v.tag != key) {
                v.tag = key
                v.background = if (sel) pill(edTheme.bg) else null
            }
            dot.background?.mutate()?.setTint((edTheme.barFg and 0x00FFFFFF) or 0x66000000)
            dot.visibility = if (i == tabs.tabCount - 1) View.GONE else View.VISIBLE
            title.setTextColor(if (sel) edTheme.fg else edTheme.barFg)
            title.alpha = if (sel) 1f else 0.65f
            close.setTextColor(if (sel) edTheme.fg else edTheme.barFg)
            close.alpha = if (sel) 0.9f else 0.5f
        }
    }

    private fun syncActive() {
        val d = active ?: return
        if (!d.loaded) return
        if (d.viewer) {
            d.vStart = viewer.currentOffset()
            d.vHex = viewer.hexMode
            return
        }
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
        if (d.viewer) {
            showViewer(d)
            return
        }
        viewer.close()
        viewerPanel.visibility = View.GONE
        editor.visibility = View.VISIBLE
        welcomePanel.visibility = View.GONE
        editor.undoManager = d.undo
        editor.setLanguage(d.lang)
        applyDocIndent(d)
        applyDocWrap(d)
        host.onLanguage(d.lang.name)
        try {
            editor.setContent(d.content)
        } catch (e: OutOfMemoryError) {
            editor.setContent("")
            toast("Not enough memory to open " + d.name + " in the editor")
            removeDoc(d)
            return
        }
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
            var err: Throwable? = null
            try {
                res = FileIO.read(contentResolver, uri)
                d.diskTime = FileIO.lastModified(contentResolver, uri)
            } catch (e: Throwable) {
                err = e
            }
            val r = res
            val e2 = err
            ui.post {
                if (r != null && r.kind == FileIO.KIND_TEXT) {
                    d.content = r.text
                    d.crlf = r.crlf
                    d.charset = r.charset
                    d.bom = r.bom
                    d.loaded = true
                    then()
                } else if (r != null) {
                    val name = d.name
                    removeDoc(d)
                    openViewerDoc(uri, name)
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
            showEmptyState()
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
        if (d.viewer) {
            toast("The large-file viewer is read-only")
            return
        }
        if (d === active) syncActive()
        applySaveTransforms(d)
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
        if (d.viewer) return
        FileIO.fixCharset(d)
        val text = d.content
        val crlf = d.crlf
        val cs = d.charset
        val bom = d.bom
        val ver = d.undo.version
        writesInFlight++
        io.execute {
            var err: Throwable? = null
            try {
                FileIO.write(contentResolver, uri, FileIO.encode(text, crlf, cs, bom))
                d.diskTime = FileIO.lastModified(contentResolver, uri)
            } catch (e: Throwable) {
                err = e
            }
            val e2 = err
            ui.post {
                writesInFlight--
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

    private val draftRunnable = Runnable {
        syncActive()
        store.save(docs, max(0, docs.indexOf(active)))
    }

    private fun onEdited() {
        if (active == null) return
        ui.removeCallbacks(draftRunnable)
        ui.postDelayed(draftRunnable, if (editor.buffer.length > 500_000) 30000L else 4000L)
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
        val um = if (active != null) editor.undoManager else null
        val canU = um?.canUndo() == true
        val canR = um?.canRedo() == true
        u?.isEnabled = canU
        r?.isEnabled = canR
        u?.icon?.alpha = if (canU) 255 else 90
        r?.icon?.alpha = if (canR) 255 else 90
        menu.findItem(R.id.m_save)?.icon?.alpha = if (active?.isModified == true) 255 else 90
        menu.findItem(R.id.m_run)?.icon?.alpha = if (active != null) 255 else 90
    }

    private fun statusLink(sb: SpannableStringBuilder, text: String, action: () -> Unit) {
        val st = sb.length
        sb.append(text)
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                action()
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = false
            }
        }, st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Status bar: the parts that can be changed (position, indentation, encoding, line ending, language) are tappable. */
    private fun updateStatus() {
        val d = active
        if (d == null) {
            status.text = ""
            return
        }
        if (d.viewer) {
            status.text = "Large-file viewer  ·  read-only"
            return
        }
        val off = max(0, editor.selectionStart)
        val line = editor.lineOfOffset(off) + 1
        val col = off - editor.lineStart(off) + 1
        val sel = abs(editor.selectionEnd - editor.selectionStart)
        val sb = SpannableStringBuilder()
        statusLink(sb, "Ln " + line + ", Col " + col) { gotoLine() }
        if (sel > 0) sb.append(" (").append(sel.toString()).append(" sel)")
        sb.append("   ").append(editor.buffer.length.toString()).append(" chars   ")
        statusLink(sb, if (editor.useSpaces) "Spaces: " + editor.tabSize else "Tabs") { pickIndentation() }
        sb.append("   ")
        statusLink(sb, d.charset + (if (d.bom) "+BOM" else "")) { pickEncoding() }
        sb.append("   ")
        statusLink(sb, if (d.crlf) "CRLF" else "LF") { pickLineEnding() }
        sb.append("   ")
        statusLink(sb, d.lang.name) { pickLanguage() }
        status.text = sb
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

    // ------------------------------------------------------------------ explorer, welcome screen, grouped menu, special files

    private lateinit var explorer: Explorer
    private lateinit var editorArea: View
    private lateinit var welcomePanel: View
    private lateinit var welcomeTitle: TextView
    private lateinit var welcomeSub: TextView
    private lateinit var welcomeRecentHeader: TextView
    private lateinit var welcomeRecent: LinearLayout
    private var welcomeButtons: List<TextView> = emptyList()

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private fun setupUi2() {
        editorArea = findViewById(R.id.editor_area)
        welcomePanel = findViewById(R.id.welcome_panel)
        welcomeTitle = findViewById(R.id.welcome_title)
        welcomeSub = findViewById(R.id.welcome_sub)
        welcomeRecentHeader = findViewById(R.id.w_recent_header)
        welcomeRecent = findViewById(R.id.w_recent)
        val wNew = findViewById<TextView>(R.id.w_new)
        val wOpen = findViewById<TextView>(R.id.w_open)
        val wFolder = findViewById<TextView>(R.id.w_folder)
        welcomeButtons = listOf(wNew, wOpen, wFolder)
        wNew.setOnClickListener { newDoc() }
        wOpen.setOnClickListener { openLauncher.launch(arrayOf("*/*")) }
        wFolder.setOnClickListener { folderLauncher.launch(null) }

        val exNewFile = findViewById<TextView>(R.id.ex_new_file)
        val exNewFolder = findViewById<TextView>(R.id.ex_new_folder)
        val exRefresh = findViewById<TextView>(R.id.ex_refresh)
        val exOpen = findViewById<TextView>(R.id.ex_open)
        explorer = Explorer(
            this,
            findViewById(R.id.explorer_panel),
            findViewById(R.id.explorer_handle),
            findViewById(R.id.explorer_title),
            findViewById(R.id.explorer_list),
            findViewById(R.id.explorer_empty),
            listOf(exNewFile, exNewFolder, exRefresh, exOpen),
            cfg.prefs,
            { uri ->
                openUri(uri)
                explorer.hide()
            },
            { old, new, name ->
                val d = docs.firstOrNull { it.uri == old }
                if (d != null) {
                    d.uri = new
                    d.name = name
                    if (d === active) editor.setLanguage(d.lang)
                    refreshAll()
                }
            },
            { uri ->
                val d = docs.firstOrNull { it.uri == uri }
                if (d != null) removeDoc(d)
            },
            { msg -> toast(msg) }
        )
        exNewFile.setOnClickListener { explorer.newFile() }
        exNewFolder.setOnClickListener { explorer.newFolder() }
        exRefresh.setOnClickListener { explorer.refresh() }
        exOpen.setOnClickListener { folderLauncher.launch(null) }
        findViewById<TextView>(R.id.ex_empty_open).setOnClickListener { folderLauncher.launch(null) }
        editorArea.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> explorer.layout(r - l) }
        editorArea.post { explorer.layout(editorArea.width) }
        setupViewer()
        explorer.restore()
    }

    private fun onFolderPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            toast("Access to this folder is only temporary")
        }
        explorer.setTree(uri, true)
        explorer.show()
    }

    private fun styleButton(tv: TextView, filled: Boolean) {
        val g = GradientDrawable()
        g.cornerRadius = dp(10).toFloat()
        if (filled) {
            g.setColor(edTheme.accent)
            tv.setTextColor(edTheme.bg)
        } else {
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(1), (edTheme.barFg and 0x00FFFFFF) or 0x55000000)
            tv.setTextColor(edTheme.fg)
        }
        tv.background = g
    }

    /** No tabs open: show the welcome screen instead of inventing an "Untitled" file. */
    private fun showEmptyState() {
        active = null
        pendingDoc = null
        untitledCounter = 1
        rebuildTabs()
        viewer.close()
        viewerPanel.visibility = View.GONE
        editor.visibility = View.GONE
        welcomePanel.visibility = View.VISIBLE
        findBar.visibility = View.GONE
        refreshWelcomeRecent()
        status.text = ""
        updateUndoRedo()
    }

    private fun refreshWelcomeRecent() {
        if (!::welcomeRecent.isInitialized) return
        welcomeRecent.removeAllViews()
        val names = ArrayList<String>()
        val uris = ArrayList<String>()
        try {
            val arr = JSONArray(prefs.getString("recent", "[]") ?: "[]")
            for (k in 0 until min(arr.length(), 6)) {
                val o = arr.getJSONObject(k)
                uris.add(o.optString("u"))
                names.add(o.optString("n"))
            }
        } catch (e: Exception) {
            // ignore
        }
        welcomeRecentHeader.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        for (i in names.indices) {
            val tv = TextView(this)
            tv.text = names[i]
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.setTextColor(edTheme.accent)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setPadding(0, dp(7), 0, dp(7))
            tv.setOnClickListener { openUri(Uri.parse(uris[i])) }
            welcomeRecent.addView(tv)
        }
    }

    private class MenuRow(val id: Int, val title: String, val header: Boolean, val checked: Boolean)

    /** The "more" menu: grouped, with section headers and dividers. */
    private fun showMoreMenu() {
        val sections = listOf(
            "File" to listOf(R.id.m_new, R.id.m_open, R.id.m_open_folder, R.id.m_save_as, R.id.m_save_all, R.id.m_recent, R.id.m_close, R.id.m_close_others, R.id.m_close_all),
            "Edit" to listOf(R.id.m_dup, R.id.m_del, R.id.m_up, R.id.m_down, R.id.m_selline, R.id.m_comment, R.id.m_indent, R.id.m_unindent, R.id.m_tools),
            "Go to" to listOf(R.id.m_goto, R.id.m_outline, R.id.m_bracket, R.id.m_find_all, R.id.m_palette),
            "Run and extend" to listOf(R.id.m_runwith, R.id.m_output, R.id.m_snippet, R.id.m_plugins),
            "View" to listOf(R.id.m_explorer, R.id.m_wrap, R.id.m_lines, R.id.m_theme, R.id.m_fontup, R.id.m_fontdown),
            "This file" to listOf(R.id.m_lang, R.id.m_indentation, R.id.m_enc, R.id.m_eol),
            "Options" to listOf(R.id.m_autoindent, R.id.m_autosave, R.id.m_share)
        )
        val menu = toolbar.menu
        val rows = ArrayList<MenuRow>()
        for ((name, ids) in sections) {
            rows.add(MenuRow(0, name, true, false))
            for (id in ids) {
                val mi = menu.findItem(id) ?: continue
                rows.add(MenuRow(id, mi.title?.toString() ?: "", false, mi.isCheckable && mi.isChecked))
            }
        }
        val t = edTheme
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun isEnabled(position: Int): Boolean = !rows[position].header

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val r = rows[position]
                if (r.header) {
                    val box = LinearLayout(this@MainActivity)
                    box.orientation = LinearLayout.VERTICAL
                    if (position > 0) {
                        val line = View(this@MainActivity)
                        line.setBackgroundColor((t.barFg and 0x00FFFFFF) or 0x30000000)
                        box.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
                    }
                    val tv = TextView(this@MainActivity)
                    tv.text = r.title.uppercase()
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    tv.typeface = Typeface.DEFAULT_BOLD
                    tv.setTextColor(t.accent)
                    tv.setPadding(dp(16), dp(12), dp(16), dp(4))
                    box.addView(tv)
                    return box
                }
                val box = LinearLayout(this@MainActivity)
                box.orientation = LinearLayout.VERTICAL
                val tv = TextView(this@MainActivity)
                tv.text = (if (r.checked) "✓   " else "      ") + r.title
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                tv.setTextColor(t.barFg)
                tv.setPadding(dp(16), dp(11), dp(16), dp(11))
                box.addView(tv)
                if (position + 1 < rows.size && !rows[position + 1].header) {
                    val line = View(this@MainActivity)
                    line.setBackgroundColor((t.barFg and 0x00FFFFFF) or 0x24000000)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    lp.setMargins(dp(14), 0, dp(14), 0)
                    box.addView(line, lp)
                }
                return box
            }
        }
        val pw = ListPopupWindow(this)
        pw.anchorView = toolbar
        pw.setAdapter(adapter)
        pw.width = dp(280)
        pw.height = min(dp(600), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        pw.isModal = true
        pw.setDropDownGravity(Gravity.END)
        val bg = GradientDrawable()
        bg.setColor(t.bar)
        bg.cornerRadius = dp(12).toFloat()
        bg.setStroke(dp(1), (t.barFg and 0x00FFFFFF) or 0x33000000)
        pw.setBackgroundDrawable(bg)
        pw.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            if (!r.header) {
                pw.dismiss()
                onMenu(r.id)
            }
        }
        pw.show()
    }

    // ---- binary and very large files

    private fun humanSize(b: Long): String = when {
        b < 0 -> "unknown size"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> String.format("%.1f MB", b / 1048576.0)
    }

    // ---- large-file viewer

    private lateinit var viewerPanel: View
    private lateinit var viewerBar: View
    private lateinit var viewer: LargeFileView
    private lateinit var viewerInfo: TextView
    private lateinit var viewerModeBtn: TextView
    private var viewerButtons: List<TextView> = emptyList()
    private var viewerToken = 0
    private var lastSearch = ""
    private var lastFound = -1L

    private fun setupViewer() {
        viewerPanel = findViewById(R.id.viewer_panel)
        viewerBar = findViewById(R.id.viewer_bar)
        viewer = findViewById(R.id.viewer)
        viewerInfo = findViewById(R.id.viewer_info)
        viewerModeBtn = findViewById(R.id.viewer_mode)
        val vFind = findViewById<TextView>(R.id.viewer_find)
        val vGoto = findViewById<TextView>(R.id.viewer_goto)
        val vEdit = findViewById<TextView>(R.id.viewer_edit)
        viewerButtons = listOf(viewerModeBtn, vFind, vGoto, vEdit)
        viewer.onStatus = { text -> viewerInfo.text = text }
        viewerModeBtn.setOnClickListener {
            viewer.toggleHex()
            updateViewerBar()
        }
        vFind.setOnClickListener { viewerFind() }
        vGoto.setOnClickListener { viewerGoto() }
        vEdit.setOnClickListener { viewerEdit() }
    }

    private fun updateViewerBar() {
        viewerModeBtn.text = if (viewer.hexMode) "Text" else "Hex"
        viewerInfo.text = viewer.statusText()
    }

    /** Opens a file in the read-only viewer: it streams from disk, so any file size is safe. */
    private fun openViewerDoc(uri: Uri, name: String) {
        val existing = docs.firstOrNull { it.uri == uri && it.viewer }
        if (existing != null) {
            activate(existing)
            return
        }
        val d = Doc(uri, name)
        d.viewer = true
        d.loaded = true
        docs.add(d)
        rebuildTabs()
        activate(d)
    }

    private fun showViewer(d: Doc) {
        editor.visibility = View.GONE
        welcomePanel.visibility = View.GONE
        findBar.visibility = View.GONE
        viewerPanel.visibility = View.VISIBLE
        viewerInfo.text = "Opening…"
        val uri = d.uri
        if (uri == null) {
            toast("This viewer tab has no file")
            removeDoc(d)
            return
        }
        val token = ++viewerToken
        io.execute {
            var opened: LargeFile.Opened? = null
            var err: Throwable? = null
            try {
                opened = LargeFile.open(this, contentResolver, uri)
            } catch (e: Throwable) {
                err = e
            }
            val o = opened
            val e2 = err
            ui.post {
                if (token != viewerToken || active !== d) {
                    if (o != null) LargeFile.release(o)
                } else if (o == null) {
                    toast("Can't open " + d.name + ": " + (e2?.message ?: "unknown error"))
                    removeDoc(d)
                } else {
                    viewer.attach(o, d.vStart, d.vHex)
                    updateViewerBar()
                }
            }
        }
    }

    private fun viewerFind() {
        val et = EditText(this)
        et.setSingleLine()
        et.hint = "Text to find"
        et.setText(lastSearch)
        et.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle("Find in file")
            .setView(boxed(et))
            .setPositiveButton("Find") { _, _ -> runViewerSearch(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runViewerSearch(q: String) {
        if (q.isEmpty()) return
        val again = q == lastSearch && lastFound >= 0
        lastSearch = q
        val from = if (again) lastFound + 1 else viewer.currentOffset()
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Searching")
            .setMessage("0%")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> viewer.cancelSearch() }
            .show()
        viewer.search(q, from, true, { pct -> dlg.setMessage("$pct%") }) { off ->
            dlg.dismiss()
            if (off < 0) {
                toast("Not found")
            } else {
                lastFound = off
                viewer.jumpTo(off)
            }
        }
    }

    private fun parseOffset(text: String, size: Long): Long? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        return try {
            when {
                t.endsWith("%") -> (t.dropLast(1).trim().toDouble() / 100.0 * size).toLong()
                t.startsWith("0x") -> t.substring(2).toLong(16)
                else -> t.toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun viewerGoto() {
        val et = EditText(this)
        et.setSingleLine()
        et.hint = "Offset: 1234, 0x4D2 or 50%"
        MaterialAlertDialogBuilder(this)
            .setTitle("Go to")
            .setView(boxed(et))
            .setPositiveButton("Go") { _, _ ->
                val off = parseOffset(et.text.toString(), viewer.fileSize)
                if (off == null) toast("Not a valid offset") else viewer.jumpTo(off)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun viewerEdit() {
        val d = active ?: return
        val uri = d.uri ?: return
        val size = viewer.fileSize
        val full = size <= FileIO.MAX_BYTES
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit in the text editor")
            .setMessage(
                if (full) "The whole file (" + humanSize(size) + ") will be loaded into the editor. Big files can be slow."
                else "This file is too big to edit completely. The first 1 MB opens as an editable copy; saving asks for a new file name."
            )
            .setPositiveButton("Open") { _, _ ->
                openSpecial(uri, d.name, if (full) FileIO.MODE_FORCE else FileIO.MODE_HEAD)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSpecial(uri: Uri, name: String, mode: Int) {
        status.text = "Loading…"
        io.execute {
            var res: FileIO.Loaded? = null
            var err: Throwable? = null
            try {
                res = FileIO.read(contentResolver, uri, mode)
            } catch (e: Throwable) {
                err = e
            }
            val lm = FileIO.lastModified(contentResolver, uri)
            val r = res
            val e2 = err
            ui.post {
                if (r == null || r.kind != FileIO.KIND_TEXT) {
                    toast("Can't open $name: " + (e2?.message ?: "unknown error"))
                } else {
                    val label = if (mode == FileIO.MODE_HEAD) " (first 1 MB)" else " (text view)"
                    val d = if (r.partial) Doc(null, name + label) else Doc(uri, name)
                    d.content = r.text
                    d.crlf = r.crlf
                    d.charset = r.charset
                    d.bom = r.bom
                    d.loaded = true
                    if (!r.partial) d.diskTime = lm
                    docs.add(d)
                    rebuildTabs()
                    activate(d)
                    if (r.partial) toast("This is a partial copy. Saving asks for a new file name.")
                }
            }
        }
    }

    /** Very long single lines break text rendering when wrapping is off, so force wrapping for such files. */
    private fun applyDocWrap(d: Doc) {
        if (!d.hugeChecked && d.loaded) {
            d.hugeChecked = true
            d.forceWrap = hasHugeLine(d.content)
            if (d.forceWrap) toast("Very long lines: word wrap is kept on for this file")
        }
        if (d.forceWrap) {
            if (!editor.wordWrap) editor.wordWrap = true
        } else if (editor.wordWrap != cfg.wrap) {
            editor.wordWrap = cfg.wrap
        }
    }

    private fun hasHugeLine(t: String): Boolean {
        if (t.length < 50_000) return false
        var run = 0
        for (ch in t) {
            if (ch == '\n') {
                run = 0
            } else {
                run++
                if (run > 20_000) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ indentation, search, outline

    /** Uses the file's own indentation style (spaces/tabs and width) while it is open. */
    private fun applyDocIndent(d: Doc) {
        if (!d.indentChecked && d.loaded) {
            d.indentChecked = true
            val det = IndentDetect.detect(d.content)
            if (det != null) {
                d.indentSpaces = det.first
                d.indentSize = det.second
            }
        }
        val sp = d.indentSpaces
        if (sp != null) {
            editor.useSpaces = sp
            editor.tabSize = d.indentSize
        }
    }

    private fun jumpTo(d: Doc, line: Int, col: Int, len: Int) {
        activate(d)
        if (active === d) {
            val n = editor.buffer.length
            val off = min(editor.offsetOfLine(line + 1) + col, n)
            editor.requestFocus()
            editor.setSelection(off, min(off + len, n))
            editor.revealOffset(off)
        }
    }

    private fun globalSearch() {
        syncActive()
        val et = EditText(this)
        et.hint = "Search in all open tabs"
        et.setSingleLine()
        MaterialAlertDialogBuilder(this)
            .setTitle("Search in open tabs")
            .setView(boxed(et))
            .setPositiveButton("Search") { _, _ -> runGlobalSearch(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class Hit(val doc: Doc, val line: Int, val col: Int, val text: String)

    private fun runGlobalSearch(q: String) {
        if (q.isEmpty()) return
        val hits = ArrayList<Hit>()
        var skipped = 0
        for (d in docs) {
            if (!d.loaded) {
                skipped++
                continue
            }
            val lines = d.content.split("\n")
            for ((li, ln) in lines.withIndex()) {
                var from = 0
                while (hits.size < 300) {
                    val at = ln.indexOf(q, from, ignoreCase = true)
                    if (at < 0) break
                    hits.add(Hit(d, li, at, ln.trim().take(90)))
                    from = at + max(1, q.length)
                }
                if (hits.size >= 300) break
            }
            if (hits.size >= 300) break
        }
        if (hits.isEmpty()) {
            toast("No matches" + (if (skipped > 0) " ($skipped tab(s) not opened yet)" else ""))
            return
        }
        val names = hits.map { it.doc.name + ":" + (it.line + 1) + "   " + it.text }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(hits.size.toString() + " match(es)")
            .setItems(names) { _, which ->
                val h = hits[which]
                jumpTo(h.doc, h.line, h.col, q.length)
            }
            .show()
    }

    private fun showOutline() {
        val d = active ?: return
        syncActive()
        val items = Outline.find(d.lang.name, d.content)
        if (items.isEmpty()) {
            toast(if (Outline.supports(d.lang.name)) "No symbols found" else "No outline for " + d.lang.name)
            return
        }
        val names = items.map { it.label + "   - " + (it.line + 1) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Go to symbol")
            .setItems(names) { _, which -> jumpTo(d, items[which].line, 0, 0) }
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacks(draftRunnable)
        if (::host.isInitialized) host.stop()
        if (::viewer.isInitialized) viewer.close()
        SandboxRunner.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tabs: bulk actions, crash report

    private fun saveAll() {
        syncActive()
        val todo = docs.filter { it.isModified && it.uri != null }
        val untitled = docs.count { it.isModified && it.uri == null }
        for (d in todo) saveDoc(d)
        if (todo.isEmpty() && untitled == 0) {
            toast("Nothing to save")
        } else if (untitled > 0) {
            toast("$untitled untitled file(s) need Save as")
        }
    }

    private fun confirmDiscard(count: Int, what: String, action: () -> Unit) {
        val dirty = docs.count { it.isModified && (what != "others" || it !== active) }
        if (dirty == 0) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Unsaved changes")
            .setMessage("$dirty of the $count tab(s) have unsaved changes that will be lost.")
            .setPositiveButton("Close anyway") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun closeOthers() {
        val keep = active ?: return
        syncActive()
        val n = docs.size - 1
        if (n <= 0) return
        confirmDiscard(n, "others") {
            docs.removeAll { it !== keep }
            pendingDoc = null
            rebuildTabs()
            toast("Closed $n tab(s)")
        }
    }

    private fun closeAll() {
        syncActive()
        confirmDiscard(docs.size, "all") {
            docs.clear()
            showEmptyState()
        }
    }

    private fun showCrashIfAny() {
        val report = CrashReporter.pending(this) ?: return
        CrashReporter.clear(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("CodePad crashed last time")
            .setMessage("Your open files were kept as drafts. Copy or share the report so the bug can be fixed.\n\n" + report.take(500))
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("CodePad crash report", report))
                toast("Copied")
            }
            .setNeutralButton("Share") { _, _ ->
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(i, "Share crash report"))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // ------------------------------------------------------------------ plugins / run

    private lateinit var tabRow: View
    private lateinit var btnNew: ImageView
    private lateinit var btnOpen: ImageView
    private lateinit var outputPanel: View
    private lateinit var outputTitle: TextView
    private lateinit var outputText: TextView
    private lateinit var outputScroll: ScrollView
    private var outputButtons: List<TextView> = emptyList()
    private lateinit var plugins: PluginManager
    private lateinit var host: PluginHost
    private var pluginSig = ""
    private var lastRun: Pair<Doc, RunnerDef>? = null
    private var pendingTermuxRun: (() -> Unit)? = null
    private var pendingTermuxFail: ((String) -> Unit)? = null
    private val outputBuf = StringBuilder()

    private val termuxPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val run = pendingTermuxRun
            val fail = pendingTermuxFail
            pendingTermuxRun = null
            pendingTermuxFail = null
            if (granted && run != null) {
                run()
            } else {
                fail?.invoke("Permission to run commands in Termux was not granted.")
            }
        }

    private val hostApi = object : HostApi {
        override fun getText(): String = editor.buffer.toString()

        override fun getSelectedText(): String {
            val a = min(editor.selectionStart, editor.selectionEnd)
            val b = max(editor.selectionStart, editor.selectionEnd)
            return if (a >= 0 && b > a) editor.buffer.subSequence(a, b).toString() else ""
        }

        override fun replaceSelection(text: String) {
            editor.replaceSelectionRaw(text)
        }

        override fun replaceAll(text: String) {
            editor.replaceAllText(text)
        }

        override fun fileName(): String = active?.name ?: ""

        override fun language(): String = active?.lang?.name ?: ""

        override fun message(text: String, error: Boolean) {
            toast(if (error) "Plugin: $text" else text)
        }

        override fun askInput(prompt: String, cb: (String?) -> Unit) {
            val et = EditText(this@MainActivity)
            et.setSingleLine()
            var answered = false
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(prompt)
                .setView(boxed(et))
                .setPositiveButton("OK") { _, _ ->
                    answered = true
                    cb(et.text.toString())
                }
                .setNegativeButton("Cancel") { _, _ ->
                    answered = true
                    cb(null)
                }
                .setOnDismissListener { if (!answered) cb(null) }
                .show()
        }

        override fun outputAppend(text: String) {
            appendOutput(text)
        }

        override fun outputClear() {
            clearOutput()
        }

        override fun outputShow(title: String) {
            showOutput(title)
        }

        override fun runTermux(command: String, cb: (RunResult) -> Unit) {
            withTermux({ msg -> cb(RunResult(-1, "", "", -1, msg)) }) {
                TermuxRunner.run(this@MainActivity, "cd \"\$HOME\" && $command", true) { res -> cb(res) }
            }
        }
    }

    private fun setupPlugins() {
        tabRow = findViewById(R.id.tab_row)
        btnNew = findViewById(R.id.btn_new)
        btnOpen = findViewById(R.id.btn_open)
        outputPanel = findViewById(R.id.output_panel)
        outputTitle = findViewById(R.id.output_title)
        outputText = findViewById(R.id.output_text)
        outputScroll = findViewById(R.id.output_scroll)
        val rerun = findViewById<TextView>(R.id.output_rerun)
        val clear = findViewById<TextView>(R.id.output_clear)
        val close = findViewById<TextView>(R.id.output_close)
        outputButtons = listOf(rerun, clear, close)
        btnNew.setOnClickListener { newDoc() }
        btnOpen.setOnClickListener { openLauncher.launch(arrayOf("*/*")) }
        rerun.setOnClickListener { rerunLast() }
        clear.setOnClickListener { clearOutput() }
        close.setOnClickListener { outputPanel.visibility = View.GONE }

        plugins = PluginManager(this)
        plugins.seedBuiltins()
        plugins.reload()
        host = PluginHost(this, plugins, hostApi)
        pluginSig = plugins.signature()
        host.restart()
        editor.tabHook = { expandSnippetAtCaret() }
    }

    /** Called when returning from the Plugins screen: reload manifests and restart the script host if anything changed. */
    private fun syncPlugins() {
        plugins.reload()
        val sig = plugins.signature()
        if (sig != pluginSig) {
            pluginSig = sig
            host.restart()
        }
    }

    private fun showOutput(title: String) {
        outputTitle.text = title
        outputPanel.visibility = View.VISIBLE
    }

    private fun appendOutput(text: String) {
        outputBuf.append(text)
        if (outputBuf.length > 100_000) outputBuf.delete(0, outputBuf.length - 80_000)
        outputText.text = outputBuf.toString()
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearOutput() {
        outputBuf.setLength(0)
        outputText.text = ""
    }

    private fun runActive(forceChoose: Boolean = false) {
        val d = active ?: return
        syncActive()
        val rs = plugins.runnersFor(d.name, d.lang.name)
        if (rs.isEmpty()) {
            toast("No runner for " + d.lang.name + ". Install one in Plugins.")
            return
        }
        if (!forceChoose) {
            val saved = cfg.prefs.getString("runner_" + d.lang.name, null)
            val pick = rs.firstOrNull { it.key == saved } ?: if (rs.size == 1) rs[0] else null
            if (pick != null) {
                startRun(d, pick)
                return
            }
        }
        val names = rs.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Run " + d.name + " with")
            .setItems(names) { _, which -> startRun(d, rs[which]) }
            .show()
    }

    private fun rerunLast() {
        val last = lastRun ?: return
        if (last.first === active) syncActive()
        startRun(last.first, last.second)
    }

    private fun startRun(d: Doc, r: RunnerDef) {
        if (d.viewer) {
            toast("Running isn't available in the large-file viewer")
            return
        }
        lastRun = Pair(d, r)
        cfg.prefs.edit().putString("runner_" + d.lang.name, r.key).apply()
        val content = d.content
        when (r.type) {
            RunnerType.TERMUX -> execTermux(d, r)
            RunnerType.SANDBOX_JS -> {
                showOutput(r.title + " - " + d.name)
                clearOutput()
                appendOutput("Running...\n")
                SandboxRunner.runJs(this, content, r.timeoutMs, { line -> appendOutput(line + "\n") }) {
                    appendOutput("\n[finished]\n")
                }
            }
            RunnerType.PREVIEW_HTML -> {
                val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                PreviewActivity.html = if (content.contains("viewport", ignoreCase = true)) content else viewport + content
                PreviewActivity.title = d.name
                startActivity(Intent(this, PreviewActivity::class.java))
            }
            RunnerType.PREVIEW_MD -> {
                PreviewActivity.html = Markdown.toHtml(content)
                PreviewActivity.title = d.name
                startActivity(Intent(this, PreviewActivity::class.java))
            }
        }
    }

    private fun withTermux(onFail: (String) -> Unit, then: () -> Unit) {
        if (!TermuxRunner.installed(this)) {
            onFail("Termux is not installed. Get it from F-Droid (not the Play Store).")
            return
        }
        if (!TermuxRunner.hasPermission(this)) {
            pendingTermuxRun = then
            pendingTermuxFail = onFail
            termuxPermLauncher.launch(TermuxRunner.PERM)
            return
        }
        then()
    }

    private fun execTermux(d: Doc, r: RunnerDef) {
        if (d.content.length > TermuxRunner.MAX_CHARS) {
            showOutput(r.title)
            appendOutput("This file is too large to send to Termux (limit " + TermuxRunner.MAX_CHARS / 1000 + " KB).\n")
            return
        }
        val title = r.title + " - " + d.name
        val onFail = { msg: String ->
            showOutput(title)
            appendOutput("\n$msg\n\n" + TermuxRunner.SETUP_HELP)
        }
        withTermux(onFail) {
            val interactive = r.mode == "terminal"
            showOutput(title)
            clearOutput()
            appendOutput(if (interactive) "Opening a Termux session...\n" else "Running...\n")
            val script = TermuxRunner.buildScript(d.name, d.content, r.command, interactive)
            val waiting = Runnable {
                appendOutput("\n...still waiting for Termux.\n" + TermuxRunner.SETUP_HELP)
            }
            if (!interactive) ui.postDelayed(waiting, 25000L)
            TermuxRunner.run(this, script, !interactive) { res ->
                ui.removeCallbacks(waiting)
                showResult(res, r)
            }
        }
    }

    private fun showResult(res: RunResult, r: RunnerDef) {
        if (res.error != null) {
            appendOutput("\n" + res.error + "\n")
            return
        }
        if (res.stdout.isNotEmpty()) appendOutput(res.stdout.trimEnd('\n') + "\n")
        if (res.stderr.isNotEmpty()) appendOutput(res.stderr.trimEnd('\n') + "\n")
        if (res.id >= 0) appendOutput("\n[exit code " + res.exitCode + "]\n")
        if ((res.exitCode == 127 || res.stderr.contains("not found")) && r.hint.isNotEmpty()) {
            appendOutput("Looks like it is not installed. In Termux run:  " + r.hint + "\n")
        }
    }

    // ------------------------------------------------------------------ snippets

    private fun insertSnippetAt(s: SnippetDef, from: Int, to: Int) {
        val t = editor.buffer
        val ls = editor.lineStart(from)
        var k = ls
        while (k < from && (t[k] == ' ' || t[k] == '\t')) k++
        val indent = t.subSequence(ls, k).toString()
        val ex = SnippetEngine.expand(s.body, indent)
        editor.replaceRange(from, to, ex.text)
        editor.setSelection(from + ex.selStart, from + ex.selEnd)
    }

    private fun insertSnippetAtCaret(s: SnippetDef) {
        val a = max(0, min(editor.selectionStart, editor.selectionEnd))
        val b = max(0, max(editor.selectionStart, editor.selectionEnd))
        insertSnippetAt(s, a, b)
    }

    private fun pickSnippet() {
        val d = active ?: return
        val list = plugins.snippetsFor(d.lang.name)
        if (list.isEmpty()) {
            toast("No snippets for " + d.lang.name)
            return
        }
        val names = list.map { it.prefix + "  -  " + it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Insert snippet")
            .setItems(names) { _, which -> insertSnippetAtCaret(list[which]) }
            .show()
    }

    /** Tab after a snippet prefix expands it. */
    private fun expandSnippetAtCaret(): Boolean {
        val d = active ?: return false
        val pos = editor.selectionStart
        if (pos <= 0 || pos != editor.selectionEnd) return false
        val t = editor.buffer
        var s = pos
        while (s > 0 && (t[s - 1].isLetterOrDigit() || t[s - 1] == '_')) s--
        if (s == pos) return false
        val word = t.subSequence(s, pos).toString()
        val sn = plugins.snippetsFor(d.lang.name).firstOrNull { it.prefix == word } ?: return false
        insertSnippetAt(sn, s, pos)
        return true
    }

    // ------------------------------------------------------------------ command palette

    private class PaletteItem(val title: String, val action: () -> Unit)

    private fun showPalette() {
        val items = ArrayList<PaletteItem>()
        val d = active
        if (d != null) {
            for (r in plugins.runnersFor(d.name, d.lang.name)) {
                items.add(PaletteItem("Run: " + r.title) {
                    syncActive()
                    startRun(d, r)
                })
            }
            for (sn in plugins.snippetsFor(d.lang.name)) {
                items.add(PaletteItem("Snippet: " + sn.name + "  (" + sn.prefix + ")") { insertSnippetAtCaret(sn) })
            }
        }
        for (c in plugins.commands()) {
            items.add(PaletteItem(c.title) {
                val rid = c.runner
                if (rid != null) {
                    val rd = plugins.runner(c.pluginId, rid)
                    if (rd != null && d != null) {
                        syncActive()
                        startRun(d, rd)
                    }
                } else {
                    host.runCommand(c.pluginId, c.id)
                }
            })
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val mi = menu.getItem(i)
            if (mi.itemId == R.id.m_palette || mi.itemId == R.id.m_more) continue
            val t = mi.title?.toString() ?: continue
            items.add(PaletteItem(t) { onMenu(mi.itemId) })
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), 0)
        val input = EditText(this)
        input.hint = "Type a command"
        input.setSingleLine()
        box.addView(input)
        val shown = ArrayList<PaletteItem>(items)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.title }.toMutableList())
        val list = ListView(this)
        list.adapter = adapter
        box.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Command palette").setView(box).create()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val words = (s?.toString() ?: "").trim().lowercase().split(' ').filter { it.isNotEmpty() }
                shown.clear()
                for (it2 in items) {
                    val tl = it2.title.lowercase()
                    if (words.all { w -> tl.contains(w) }) shown.add(it2)
                }
                adapter.clear()
                adapter.addAll(shown.map { it.title })
                adapter.notifyDataSetChanged()
            }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in 0 until shown.size) {
                val chosen = shown[pos]
                dlg.dismiss()
                chosen.action()
            }
        }
        dlg.show()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    // ------------------------------------------------------------------ text tools

    private fun applySaveTransforms(d: Doc) {
        if (d !== active) return
        val trim = cfg.trimOnSave
        val nl = cfg.finalNewline
        if (!trim && !nl) return
        syncActive()
        val src = d.content
        var out = src
        if (trim) out = Tools.trimTrailing(out)
        if (nl && out.isNotEmpty() && !out.endsWith("\n")) out += "\n"
        if (out != src) {
            editor.replaceAllText(out)
            syncActive()
        }
    }

    private fun showTools() {
        val names = arrayOf(
            "Format JSON", "Minify JSON", "Sort lines A→Z", "Sort lines Z→A", "Remove duplicate lines",
            "UPPERCASE", "lowercase", "Trim trailing spaces", "Tabs → spaces", "Spaces → tabs"
        )
        val scope = if (editor.selectionStart != editor.selectionEnd) "selection" else "whole file"
        MaterialAlertDialogBuilder(this)
            .setTitle("Text tools ($scope)")
            .setItems(names) { _, which ->
                val unit = editor.indentUnit()
                val tab = editor.tabSize
                val err = when (which) {
                    0 -> editor.transformText { Tools.formatJson(it, unit) }
                    1 -> editor.transformText { Tools.minifyJson(it) }
                    2 -> editor.transformText { Tools.sortLines(it, false) }
                    3 -> editor.transformText { Tools.sortLines(it, true) }
                    4 -> editor.transformText { Tools.uniqueLines(it) }
                    5 -> editor.transformText { it.uppercase() }
                    6 -> editor.transformText { it.lowercase() }
                    7 -> editor.transformText { Tools.trimTrailing(it) }
                    8 -> editor.transformText { Tools.tabsToSpaces(it, tab) }
                    else -> editor.transformText { Tools.spacesToTabs(it, tab) }
                }
                if (err != null) toast(err)
            }
            .show()
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
