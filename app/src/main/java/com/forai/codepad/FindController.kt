package com.forai.codepad

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.max
import kotlin.math.min

class FindController(
    private val bar: View,
    private val editor: EditorView,
    private val findInput: EditText,
    private val replaceInput: EditText,
    private val countView: TextView,
    private val caseToggle: TextView,
    private val regexToggle: TextView
) {
    private val ui = Handler(Looper.getMainLooper())
    private var pattern: Pattern? = null
    private var caseSensitive = false
    private var useRegex = false
    private val countRunnable = Runnable { updateCount() }

    val isVisible: Boolean get() = bar.visibility == View.VISIBLE

    init {
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                rebuild()
            }
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            next()
            true
        }
        caseToggle.setOnClickListener {
            caseSensitive = !caseSensitive
            refreshToggles()
            rebuild()
        }
        regexToggle.setOnClickListener {
            useRegex = !useRegex
            refreshToggles()
            rebuild()
        }
        refreshToggles()
    }

    private fun refreshToggles() {
        caseToggle.alpha = if (caseSensitive) 1f else 0.4f
        regexToggle.alpha = if (useRegex) 1f else 0.4f
    }

    fun show() {
        bar.visibility = View.VISIBLE
        val t = editor.buffer
        val a = min(editor.selectionStart, editor.selectionEnd)
        val b = max(editor.selectionStart, editor.selectionEnd)
        if (a >= 0 && b > a && b - a < 200) {
            val sel = t.subSequence(a, b).toString()
            if (!sel.contains('\n')) findInput.setText(sel)
        }
        findInput.requestFocus()
        findInput.selectAll()
        val imm = findInput.context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        rebuild()
    }

    fun hide() {
        bar.visibility = View.GONE
        pattern = null
        editor.findPattern = null
        editor.clearMatch()
        editor.scheduleSpans(0L)
        editor.requestFocus()
    }

    /** Called when the document in the editor was switched. */
    fun onDocChanged() {
        if (isVisible) rebuild()
    }

    /** Called after every edit; recounts matches after a short pause. */
    fun onTextEdited() {
        if (!isVisible || pattern == null) return
        ui.removeCallbacks(countRunnable)
        ui.postDelayed(countRunnable, 300L)
    }

    private fun rebuild() {
        val q = findInput.text.toString()
        if (q.isEmpty()) {
            pattern = null
            countView.text = ""
            editor.findPattern = null
            editor.clearMatch()
            editor.scheduleSpans(0L)
            return
        }
        try {
            var flags = 0
            if (!caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (useRegex) flags = flags or Pattern.MULTILINE
            pattern = Pattern.compile(if (useRegex) q else Pattern.quote(q), flags)
        } catch (e: PatternSyntaxException) {
            pattern = null
            countView.text = "bad regex"
            editor.findPattern = null
            editor.scheduleSpans(0L)
            return
        }
        editor.findPattern = pattern
        editor.scheduleSpans(0L)
        updateCount()
    }

    private fun updateCount() {
        val p = pattern ?: return
        val t = editor.buffer
        if (t.length > 3_000_000) {
            countView.text = "many"
            return
        }
        var total = 0
        var cur = 0
        try {
            val m = p.matcher(t)
            val a = min(editor.selectionStart, editor.selectionEnd)
            val b = max(editor.selectionStart, editor.selectionEnd)
            while (m.find()) {
                total++
                if (m.start() == a && m.end() == b) cur = total
                if (total >= 99999) break
            }
        } catch (e: Throwable) {
            countView.text = "?"
            return
        }
        countView.text = if (total == 0) "0" else if (cur > 0) "$cur/$total" else "$total"
    }

    private fun select(s: Int, e: Int) {
        editor.showMatch(s, e)
    }

    fun next() {
        val p = pattern ?: return
        val t = editor.buffer
        val from = if (editor.hasMatch()) editor.matchEnd() else max(editor.selectionStart, editor.selectionEnd)
        try {
            val m = p.matcher(t)
            var found = m.find(min(max(0, from), t.length))
            if (!found) found = m.find(0)
            if (found) {
                select(m.start(), m.end())
                countView.text = "${indexOfMatch(p, m.start())}/${totalMatches(p)}"
            } else {
                countView.text = "0"
            }
        } catch (e: Throwable) {
            countView.text = "?"
        }
    }

    fun prev() {
        val p = pattern ?: return
        val t = editor.buffer
        val from = if (editor.hasMatch()) editor.matchStart() else min(editor.selectionStart, editor.selectionEnd)
        var ls = -1
        var le = -1
        var lastS = -1
        var lastE = -1
        try {
            val m = p.matcher(t)
            while (m.find()) {
                if (m.end() == m.start()) continue
                lastS = m.start()
                lastE = m.end()
                if (m.start() < from) {
                    ls = m.start()
                    le = m.end()
                }
            }
        } catch (e: Throwable) {
            countView.text = "?"
            return
        }
        if (ls < 0) {
            ls = lastS
            le = lastE
        }
        if (ls >= 0) {
            select(ls, le)
            countView.text = "${indexOfMatch(p, ls)}/${totalMatches(p)}"
        } else {
            countView.text = "0"
        }
    }

    private fun totalMatches(p: Pattern): Int {
        var n = 0
        val m = p.matcher(editor.buffer)
        while (m.find() && n < 99999) n++
        return n
    }

    private fun indexOfMatch(p: Pattern, start: Int): Int {
        var n = 0
        val m = p.matcher(editor.buffer)
        while (m.find()) {
            n++
            if (m.start() == start) return n
            if (n >= 99999) break
        }
        return 0
    }

    fun replaceOne() {
        val p = pattern ?: return
        if (!editor.hasMatch()) {
            next()
            return
        }
        val t = editor.buffer
        val s = editor.matchStart()
        val e = editor.matchEnd()
        if (e > t.length) {
            next()
            return
        }
        val original = t.subSequence(s, e).toString()
        val repl = replaceInput.text.toString()
        val result = try {
            if (!p.matcher(original).matches()) {
                next()
                return
            }
            if (useRegex) p.matcher(original).replaceFirst(repl) else repl
        } catch (ex: Exception) {
            countView.text = "bad replacement"
            return
        }
        editor.replaceRange(s, e, result)
        editor.setSelection(min(s + result.length, t.length))
        next()
    }

    fun replaceAll() {
        val p = pattern ?: return
        val t = editor.buffer
        val repl = replaceInput.text.toString()
        try {
            val before = totalMatches(p)
            if (before == 0) {
                countView.text = "0"
                return
            }
            val result = p.matcher(t.toString()).replaceAll(if (useRegex) repl else Matcher.quoteReplacement(repl))
            editor.replaceAllText(result)
            editor.clearMatch()
            countView.text = "replaced $before"
        } catch (ex: Exception) {
            countView.text = "bad replacement"
        }
    }
}
