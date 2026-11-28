package com.forai.codepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatEditText
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        const val MAX_HIGHLIGHT = 300_000
        private const val BR_SCAN = 30_000
        private val EMPTY_F = FloatArray(0)
    }

    private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)
    private class FindSpan(color: Int) : BackgroundColorSpan(color)

    // ---- settings ----
    var edTheme: EditorTheme? = null
        set(value) {
            field = value
            if (value != null) applyTheme(value)
        }

    var showLineNumbers: Boolean = true
        set(value) {
            field = value
            refreshGutterWidth(true)
            invalidate()
        }

    var wordWrap: Boolean = true
        set(value) {
            field = value
            setHorizontallyScrolling(!value)
            if (value) scrollTo(0, scrollY)
            requestLayout()
        }

    var autoIndent: Boolean = true
    var tabSize: Int = 4
    var useSpaces: Boolean = true
    var autoClose: Boolean = true
    var smartBackspace: Boolean = true

    var smoothCaret: Boolean = false
        set(value) {
            field = value
            updateCursorDrawable()
            invalidate()
        }

    var smoothSelection: Boolean = false
        set(value) {
            field = value
            val t = edTheme
            if (t != null) highlightColor = if (value) Color.TRANSPARENT else t.selection
            animInit = false
            invalidate()
        }

    var showWhitespace: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var bracketMatch: Boolean = true
        set(value) {
            field = value
            updateBracketMatch()
            invalidate()
        }

    var showGuides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Auto-close rules (trigger -> completion). */
    var pairs: List<PairDef> = emptyList()
        set(value) {
            field = value
            pairsSorted = value.sortedByDescending { it.open.length }
            rebuildBrackets()
            updateBracketMatch()
            invalidate()
        }

    var fontSp: Float = 14f
        private set

    var undoManager: UndoManager = UndoManager()
    var findPattern: Pattern? = null

    var lang: Lang = Langs.plain
        private set

    var onCaretMoved: (() -> Unit)? = null
    var onEdited: (() -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null

    /** Called when Tab is pressed with no selection; return true if it consumed the key (snippet expansion). */
    var tabHook: (() -> Boolean)? = null

    // ---- internals ----
    /** The editable text (never null once the view exists). */
    val buffer: Editable get() = text!!

    private val uiHandler = Handler(Looper.getMainLooper())
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterBgPaint = Paint()
    private val dividerPaint = Paint()
    private val linePaint = Paint()
    private val matchPaint = Paint()
    private val matchPath = Path()
    private var gutterWidth = 0

    private val guidePaint = Paint()
    private val bracketPaint = Paint()
    private val bracketBadPaint = Paint()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretPaint = Paint()
    private val selPaint = Paint()
    private val selPath = Path()
    private var caretInit = false
    private var caretX = 0f
    private var caretTop = 0f
    private var caretBottom = 0f
    private var caretTargetPos = -1
    private var caretMovedAt = 0L
    private var animInit = false
    private var animS = 0f
    private var animE = 0f

    private var pairsSorted: List<PairDef> = emptyList()
    private var openChars = "([{"
    private var closeChars = ")]}"
    private var bmA = -1
    private var bmB = -1
    private var rawEdit = false
    private var lastDelAt = 0L
    private var lastDelText = ""

    private var thumbUntil = 0L
    private var draggingThumb = false
    private var dragOffset = 0f

    private var lineStarts = IntArray(1)
    private var lineCount = 1
    private var linesDirty = true
    private var charBuf = CharArray(0)

    private var programmatic = false
    private var pendingBefore = ""
    private var blockColon = false

    private var editVersion = 0
    private var tokens = IntArray(0)
    private var tokensVersion = -1

    private var curS = -1
    private var curE = -1

    private var scaleAccum = 14f

    private val tokenizeRunnable = Runnable { tokenizeNow() }
    private val spansRunnable = Runnable { applySpansNow() }

    private val scroller = OverScroller(context)

    private val flingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.abortAnimation()
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val maxY = maxScrollY()
            if (maxY <= 0) return false
            scroller.fling(scrollX, scrollY, 0, (-velocityY).toInt(), scrollX, scrollX, 0, maxY)
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleAccum = fontSp
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAccum = (scaleAccum * detector.scaleFactor).coerceIn(8f, 40f)
            val r = (scaleAccum * 2f).roundToInt() / 2f
            if (r != fontSp) {
                applyFontSize(r)
                onZoom?.invoke(r)
            }
            return true
        }
    })

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (programmatic || s == null) return
            pendingBefore = if (count > 0) s.subSequence(start, start + count).toString() else ""
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val sb = s ?: return
            editVersion++
            curS = -1
            if (programmatic) {
                linesDirty = true
            } else {
                val added = if (count > 0) sb.subSequence(start, start + count).toString() else ""
                val removed = pendingBefore
                undoManager.record(start, removed, added)
                if (removed.isNotEmpty() && added.isEmpty()) {
                    lastDelAt = SystemClock.uptimeMillis()
                    lastDelText = removed
                }
                if (!linesDirty) {
                    if (added.indexOf('\n') >= 0 || removed.indexOf('\n') >= 0) {
                        linesDirty = true
                    } else {
                        val delta = added.length - removed.length
                        if (delta != 0) shiftLines(start, delta)
                    }
                }
            }
            if (showLineNumbers) refreshGutterWidth(false)
            updateBracketMatch()
            scheduleTokenize(if (programmatic) 0L else 250L)
            onEdited?.invoke()
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    private val inputFilter = InputFilter { source, start, end, dest, dstart, dend ->
        if (programmatic || rawEdit || source == null || end - start != 1) {
            null
        } else {
            val c = source[start]
            if (c == '\n') {
                handleEnter(dest, dstart, dend)
            } else if (autoClose) {
                handlePair(c, dest, dstart, dend)
            } else {
                null
            }
        }
    }

    /**
     * Moves the caret after the filter's edit has been applied. [bump] is how many text changes the edit
     * will cause (1 for an insertion, 0 for a swallowed key). If anything else edited the text in between,
     * the move is dropped so it can never jump the caret to a stale position.
     */
    private fun postCaret(pos: Int, bump: Int = 1) {
        val v = editVersion
        post {
            if (editVersion == v + bump) setSelection(pos.coerceIn(0, buffer.length))
        }
    }

    private fun postSelect(a: Int, b: Int) {
        val v = editVersion
        post {
            if (editVersion == v + 1) {
                val n = buffer.length
                setSelection(a.coerceIn(0, n), b.coerceIn(0, n))
            }
        }
    }

    /** True when the replaced range overlaps text the keyboard is still composing (i.e. it is re-typing, not the user). */
    private fun touchesComposing(dest: CharSequence, ds: Int, de: Int): Boolean {
        val sp = dest as? Spannable ?: return false
        val cs = BaseInputConnection.getComposingSpanStart(sp)
        val ce = BaseInputConnection.getComposingSpanEnd(sp)
        if (cs < 0 || ce < 0) return false
        val lo = min(cs, ce)
        val hi = max(cs, ce)
        return ds < hi && de > lo
    }

    private fun isCloseChar(c: Char): Boolean {
        for (p in pairsSorted) if (p.close.length == 1 && p.close[0] == c) return true
        return false
    }

    private fun handleEnter(dest: CharSequence, dstart: Int, dend: Int): CharSequence? {
        if (!autoIndent) return null
        var ls = dstart
        while (ls > 0 && dest[ls - 1] != '\n') ls--
        var i = ls
        while (i < dstart && (dest[i] == ' ' || dest[i] == '\t')) i++
        val indent = dest.subSequence(ls, i).toString()
        var j = dstart - 1
        while (j >= ls && (dest[j] == ' ' || dest[j] == '\t')) j--
        var extra = ""
        var between = false
        if (j >= ls) {
            val ch = dest[j]
            if (ch == '{' || ch == '(' || ch == '[' || (ch == ':' && blockColon)) extra = indentUnit()
            val oi = openChars.indexOf(ch)
            if (oi >= 0 && j == dstart - 1 && dend == dstart && dend < dest.length && dest[dend] == closeChars[oi]) {
                between = true
            }
        }
        if (between) {
            postCaret(dstart + 1 + indent.length + extra.length)
            return "\n" + indent + extra + "\n" + indent
        }
        return "\n" + indent + extra
    }

    private fun handlePair(c: Char, dest: CharSequence, dstart: Int, dend: Int): CharSequence? {
        // Only react to a character the user really typed. Keyboards re-send text while editing
        // (e.g. after backspace); those edits must never create new brackets or quotes.
        if (touchesComposing(dest, dstart, dend)) return null
        if (SystemClock.uptimeMillis() - lastDelAt < 250L && lastDelText.indexOf(c) >= 0) return null
        val hasSel = dend > dstart
        if (hasSel && (Selection.getSelectionStart(dest) != dstart || Selection.getSelectionEnd(dest) != dend)) return null
        val next: Char? = if (dend < dest.length) dest[dend] else null
        val prev: Char? = if (dstart > 0) dest[dstart - 1] else null

        // HTML / XML: typing > after an opening tag inserts the closing tag
        if (c == '>' && !hasSel && (lang.name == "HTML" || lang.name == "XML")) {
            val tag = tagBefore(dest, dstart)
            if (tag != null) {
                postCaret(dstart + 1)
                return ">" + "</" + tag + ">"
            }
        }

        // typing a closing character that is already there: just step over it
        if (!hasSel && next == c && isCloseChar(c)) {
            postCaret(dstart + 1, 0)
            return ""
        }

        for (p in pairsSorted) {
            val tr = p.open
            if (tr[tr.length - 1] != c) continue
            val pre = tr.length - 1
            if (pre > 0) {
                if (dstart < pre) continue
                if (dest.subSequence(dstart - pre, dstart).toString() != tr.substring(0, pre)) continue
            }
            if (hasSel) {
                if (pre > 0) continue
                val sel = dest.subSequence(dstart, dend).toString()
                postSelect(dstart + 1, dstart + 1 + sel.length)
                return tr + sel + p.close
            }
            if (p.open == p.close) {
                if (prev != null && (prev.isLetterOrDigit() || prev == '\\' || prev == c)) continue
                if (next != null && next.isLetterOrDigit()) continue
            } else {
                if (next != null && !(next.isWhitespace() || isCloseChar(next) || ",;:.".indexOf(next) >= 0)) continue
            }
            postCaret(dstart + 1)
            return c.toString() + p.close
        }
        return null
    }

    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    /** Name of the tag being opened right before [pos] (e.g. div for `<div class="a"`), or null. */
    private fun tagBefore(dest: CharSequence, pos: Int): String? {
        var i = pos - 1
        val lim = max(0, pos - 400)
        while (i >= lim && dest[i] != '<' && dest[i] != '>') i--
        if (i < lim || dest[i] != '<') return null
        var j = i + 1
        val sb = StringBuilder()
        while (j < pos && (dest[j].isLetterOrDigit() || dest[j] == '-' || dest[j] == ':' || dest[j] == '_' || dest[j] == '.')) {
            sb.append(dest[j])
            j++
        }
        if (sb.isEmpty() || !sb[0].isLetter()) return null
        if (pos > 0 && dest[pos - 1] == '/') return null
        val name = sb.toString()
        if (lang.name == "HTML" && voidTags.contains(name.lowercase())) return null
        return name
    }

    /** Backspace between an auto-inserted pair removes both halves. */
    private fun pairBackspace(): Boolean {
        if (!autoClose || !smartBackspace || programmatic) return false
        val a = selectionStart
        if (a < 0 || a != selectionEnd) return false
        val t = buffer
        for (p in pairsSorted) {
            val o = p.open
            val c = p.close
            if (a >= o.length && a + c.length <= t.length &&
                t.subSequence(a - o.length, a).toString() == o &&
                t.subSequence(a, a + c.length).toString() == c
            ) {
                t.delete(a - o.length, a + c.length)
                return true
            }
        }
        return false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && pairBackspace()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && pairBackspace()) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && pairBackspace()) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    init {
        typeface = Typeface.MONOSPACE
        gutterPaint.typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        setLineSpacing(0f, 1.1f)
        filters = arrayOf(inputFilter)
        addTextChangedListener(watcher)
        bubbleTextPaint.textSize = dp(13f)
        bubbleTextPaint.typeface = Typeface.DEFAULT_BOLD
        applyFontSize(14f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---- theme ----
    private fun applyTheme(t: EditorTheme) {
        setBackgroundColor(t.bg)
        setTextColor(t.fg)
        highlightColor = if (smoothSelection) Color.TRANSPARENT else t.selection
        caretPaint.color = t.cursor
        selPaint.color = t.selection
        gutterBgPaint.color = t.gutterBg
        dividerPaint.color = (t.gutterFg and 0x00FFFFFF) or 0x40000000
        linePaint.color = t.currentLine
        matchPaint.color = t.findMatch
        guidePaint.color = (t.gutterFg and 0x00FFFFFF) or 0x55000000
        bracketPaint.color = (t.accent and 0x00FFFFFF) or 0x66000000
        bracketBadPaint.color = 0x66FF5555
        bubbleTextPaint.color = t.bg
        wsPaint.color = (t.gutterFg and 0x00FFFFFF) or 0x90000000.toInt()
        updateCursorDrawable()
        scheduleSpans(0L)
        invalidate()
    }

    fun applyFontSize(sp: Float) {
        fontSp = sp.coerceIn(8f, 40f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        gutterPaint.textSize = textSize * 0.9f
        refreshGutterWidth(true)
        invalidate()
    }

    fun indentUnit(): String = if (useSpaces) " ".repeat(tabSize) else "\t"

    // ---- language / highlighting ----
    fun setLanguage(l: Lang) {
        lang = l
        blockColon = (l.name == "Python" || l.name == "YAML")
        tokens = IntArray(0)
        tokensVersion = -1
        clearSyntaxSpans()
        scheduleTokenize(0L)
    }

    fun scheduleTokenize(delay: Long = 250L) {
        uiHandler.removeCallbacks(tokenizeRunnable)
        uiHandler.postDelayed(tokenizeRunnable, delay)
    }

    fun scheduleSpans(delay: Long = 40L) {
        uiHandler.removeCallbacks(spansRunnable)
        uiHandler.postDelayed(spansRunnable, delay)
    }

    private fun tokenizeNow() {
        val l = lang
        val t = text ?: return
        if (!l.hasSyntax || t.length > MAX_HIGHLIGHT) {
            tokens = IntArray(0)
            tokensVersion = editVersion
            clearSyntaxSpans()
            return
        }
        val v = editVersion
        val s = t.toString()
        worker.execute {
            val tk = try {
                l.tokenize(s)
            } catch (e: Throwable) {
                IntArray(0)
            }
            uiHandler.post {
                if (v == editVersion && l === lang) {
                    tokens = tk
                    tokensVersion = v
                    applySpansNow()
                }
            }
        }
    }

    private fun clearSyntaxSpans() {
        val t = text ?: return
        for (sp in t.getSpans(0, t.length, SyntaxSpan::class.java)) t.removeSpan(sp)
    }

    private fun applySpansNow() {
        val t = text ?: return
        val l = layout ?: return
        val th = edTheme ?: return
        if (t.length == 0) return
        for (sp in t.getSpans(0, t.length, SyntaxSpan::class.java)) t.removeSpan(sp)
        for (sp in t.getSpans(0, t.length, FindSpan::class.java)) t.removeSpan(sp)

        val first = l.getLineForVertical(max(0, scrollY - height))
        val last = l.getLineForVertical(max(0, scrollY + 2 * height))
        val ws = l.getLineStart(first)
        val we = min(l.getLineEnd(last), t.length)

        val tk = tokens
        if (tk.isNotEmpty() && tokensVersion == editVersion) {
            val n = tk.size / 3
            var lo = 0
            var hi = n
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tk[mid * 3 + 1] <= ws) lo = mid + 1 else hi = mid
            }
            var i = lo
            while (i < n) {
                val s = tk[i * 3]
                if (s >= we) break
                val e = tk[i * 3 + 1]
                if (e <= t.length && s < e) {
                    t.setSpan(SyntaxSpan(th.colorFor(tk[i * 3 + 2])), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i++
            }
        }

        val fp = findPattern
        if (fp != null) {
            try {
                val m = fp.matcher(t)
                m.region(ws, we)
                m.useTransparentBounds(true)
                m.useAnchoringBounds(false)
                var count = 0
                while (m.find() && count < 500) {
                    if (m.end() > m.start()) {
                        t.setSpan(FindSpan(th.findMatch), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        count++
                    }
                }
            } catch (e: Throwable) {
                // ignore bad pattern / stack issues
            }
        }
    }

    // ---- content ----
    fun setContent(s: String) {
        programmatic = true
        try {
            setText(s)
        } finally {
            programmatic = false
        }
        curS = -1
    }

    // ---- line index cache (for the gutter and status bar) ----
    private fun ensureLines() {
        if (!linesDirty) return
        val t = text ?: return
        val n = t.length
        if (charBuf.size < n) charBuf = CharArray(n + n / 2 + 16)
        android.text.TextUtils.getChars(t, 0, n, charBuf, 0)
        var cnt = 1
        for (i in 0 until n) if (charBuf[i] == '\n') cnt++
        val arr = IntArray(cnt)
        var k = 1
        for (i in 0 until n) if (charBuf[i] == '\n') arr[k++] = i + 1
        lineStarts = arr
        lineCount = cnt
        linesDirty = false
    }

    private fun shiftLines(start: Int, delta: Int) {
        val li = lineIndexOf(start)
        for (k in li + 1 until lineCount) lineStarts[k] += delta
    }

    private fun lineIndexOf(off: Int): Int {
        ensureLines()
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineOfOffset(off: Int): Int = lineIndexOf(off)

    fun totalLines(): Int {
        ensureLines()
        return lineCount
    }

    fun offsetOfLine(oneBased: Int): Int {
        ensureLines()
        return lineStarts[(oneBased - 1).coerceIn(0, lineCount - 1)]
    }

    private fun refreshGutterWidth(force: Boolean) {
        ensureLines()
        val digits = max(3, lineCount.toString().length)
        val w = if (showLineNumbers) (gutterPaint.measureText("0") * digits + dp(14f)).toInt() else 0
        if (w != gutterWidth || force) {
            gutterWidth = w
            setPadding(w + dp(8f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(6f).toInt())
        }
    }

    fun lineStart(pos: Int): Int {
        val t = buffer
        var i = pos.coerceIn(0, t.length)
        while (i > 0 && t[i - 1] != '\n') i--
        return i
    }

    fun lineEnd(pos: Int): Int {
        val t = buffer
        var i = pos.coerceIn(0, t.length)
        while (i < t.length && t[i] != '\n') i++
        return i
    }

    // ---- drawing ----
    override fun onDraw(canvas: Canvas) {
        val l = layout
        val th = edTheme
        if (l != null && th != null) {
            val top = extendedPaddingTop.toFloat()
            val sx = scrollX.toFloat()
            val a = selectionStart
            val b = selectionEnd
            if (a >= 0 && a == b) {
                val line = l.getLineForOffset(a)
                canvas.drawRect(sx, top + l.getLineTop(line), sx + width, top + l.getLineBottom(line), linePaint)
            }
            if (smoothSelection) drawSmoothSelection(canvas, l)
            if (showGuides) drawGuides(canvas, l)
            if (showWhitespace) drawWhitespace(canvas, l)
            if (bmA >= 0) {
                drawRange(canvas, l, bmA, bmA + 1, if (bmB >= 0) bracketPaint else bracketBadPaint)
                if (bmB >= 0) drawRange(canvas, l, bmB, bmB + 1, bracketPaint)
            }
            if (curS >= 0 && curE > curS && curE <= buffer.length) {
                matchPath.reset()
                l.getSelectionPath(curS, curE, matchPath)
                canvas.save()
                canvas.translate(compoundPaddingLeft.toFloat(), top)
                canvas.drawPath(matchPath, matchPaint)
                canvas.restore()
            }
        }
        super.onDraw(canvas)
        if (smoothCaretActive() && l != null && th != null) drawSmoothCaret(canvas, l)
        if (showLineNumbers && l != null && th != null) drawGutter(canvas, l, th)
        if (th != null) drawThumb(canvas, l)
    }

    private fun drawGutter(canvas: Canvas, l: Layout, th: EditorTheme) {
        val sx = scrollX.toFloat()
        val sy = scrollY.toFloat()
        val gw = gutterWidth.toFloat()
        canvas.drawRect(sx, sy, sx + gw, sy + height, gutterBgPaint)
        canvas.drawRect(sx + gw - 1f, sy, sx + gw, sy + height, dividerPaint)
        val top = extendedPaddingTop
        val first = l.getLineForVertical(max(0, scrollY - top))
        val last = l.getLineForVertical(max(0, scrollY + height - top))
        val t = buffer
        val caretLine = lineIndexOf(max(0, selectionStart))
        gutterPaint.textAlign = Paint.Align.RIGHT
        val x = sx + gw - dp(8f)
        for (row in first..last) {
            val start = l.getLineStart(row)
            if (start == 0 || t[start - 1] == '\n') {
                val idx = lineIndexOf(start)
                gutterPaint.color = if (idx == caretLine) th.fg else th.gutterFg
                canvas.drawText((idx + 1).toString(), x, (top + l.getLineBaseline(row)).toFloat(), gutterPaint)
            }
        }
    }

    // ---- events ----
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        curS = -1
        updateBracketMatch()
        invalidate()
        onCaretMoved?.invoke()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        thumbUntil = SystemClock.uptimeMillis() + 1200L
        scheduleSpans(40L)
        invalidate()
    }

    private fun maxScrollY(): Int {
        val l = layout ?: return 0
        val visible = height - compoundPaddingTop - compoundPaddingBottom
        return max(0, l.height - visible)
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scrollX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleThumbTouch(event)) return true
        flingDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) {
            if (event.isShiftPressed) indentSelection(true) else insertTab()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- undo / redo ----
    private fun applyEdit(start: Int, removeLen: Int, insert: String) {
        val t = text ?: return
        val s = start.coerceIn(0, t.length)
        val e = (start + removeLen).coerceIn(s, t.length)
        programmatic = true
        try {
            t.replace(s, e, insert)
            setSelection(min(s + insert.length, t.length))
        } finally {
            programmatic = false
        }
    }

    fun undo(): Boolean {
        val e = undoManager.popUndo() ?: return false
        applyEdit(e.start, e.after.length, e.before)
        return true
    }

    fun redo(): Boolean {
        val e = undoManager.popRedo() ?: return false
        applyEdit(e.start, e.before.length, e.after)
        return true
    }

    // ---- editing helpers ----
    /** Replaces the selection (or inserts at the caret) without auto-close / auto-indent side effects. */
    fun replaceSelectionRaw(str: String) {
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        replaceRange(a, b, str)
        setSelection(min(a + str.length, buffer.length))
    }

    fun insertAtCaret(s: String) {
        val t = buffer
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        t.replace(a, b, s)
        setSelection(min(a + s.length, t.length))
    }

    fun insertTab() {
        val t = buffer
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        if (a == b && tabHook?.invoke() == true) return
        if (t.subSequence(a, b).indexOf('\n') >= 0) {
            indentSelection(false)
            return
        }
        val str = if (useSpaces) {
            val col = a - lineStart(a)
            " ".repeat(tabSize - col % tabSize)
        } else {
            "\t"
        }
        t.replace(a, b, str)
        setSelection(a + str.length)
    }

    fun moveCaret(d: Int) {
        setSelection((selectionEnd + d).coerceIn(0, buffer.length))
    }

    fun moveCaretVertical(up: Boolean) {
        val l = layout ?: return
        val t = buffer
        Selection.setSelection(t, max(0, selectionEnd))
        if (up) Selection.moveUp(t, l) else Selection.moveDown(t, l)
    }

    /** Start of first selected line and end (exclusive of newline) of last selected line. */
    private fun selLineRange(): IntArray {
        val t = buffer
        val a = max(0, min(selectionStart, selectionEnd))
        var b = max(0, max(selectionStart, selectionEnd))
        if (b > a && b > 0 && t[b - 1] == '\n') b--
        return intArrayOf(lineStart(a), lineEnd(b))
    }

    fun selectLine() {
        val r = selLineRange()
        setSelection(r[0], r[1])
    }

    fun duplicateLines() {
        val t = buffer
        val r = selLineRange()
        val a = selectionStart
        val b = selectionEnd
        val block = t.subSequence(r[0], r[1]).toString()
        replaceRange(r[1], r[1], "\n" + block)
        val d = block.length + 1
        setSelection(min(a + d, t.length), min(b + d, t.length))
    }

    fun deleteLines() {
        val t = buffer
        val r = selLineRange()
        var s = r[0]
        var e = r[1]
        if (e < t.length) e++ else if (s > 0) s--
        replaceRange(s, e, "")
        setSelection(min(s, t.length))
    }

    fun moveLines(up: Boolean) {
        val t = buffer
        val r = selLineRange()
        val s = r[0]
        val e = r[1]
        val selA = selectionStart
        val selB = selectionEnd
        if (up) {
            if (s == 0) return
            val ps = lineStart(s - 1)
            val prev = t.subSequence(ps, s - 1).toString()
            val block = t.subSequence(s, e).toString()
            replaceRange(ps, e, block + "\n" + prev)
            val d = prev.length + 1
            setSelection((selA - d).coerceIn(0, t.length), (selB - d).coerceIn(0, t.length))
        } else {
            if (e >= t.length) return
            val ne = lineEnd(e + 1)
            val next = t.subSequence(e + 1, ne).toString()
            val block = t.subSequence(s, e).toString()
            replaceRange(s, ne, next + "\n" + block)
            val d = next.length + 1
            setSelection((selA + d).coerceIn(0, t.length), (selB + d).coerceIn(0, t.length))
        }
    }

    fun toggleComment(prefix: String) {
        val t = buffer
        val r = selLineRange()
        val lines = t.subSequence(r[0], r[1]).toString().split("\n")
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(prefix) }
        val out = StringBuilder()
        for ((i, ln) in lines.withIndex()) {
            if (i > 0) out.append('\n')
            if (ln.isBlank()) {
                out.append(ln)
                continue
            }
            val indent = ln.length - ln.trimStart().length
            if (allCommented) {
                var rest = ln.substring(indent + prefix.length)
                if (rest.startsWith(" ")) rest = rest.substring(1)
                out.append(ln, 0, indent).append(rest)
            } else {
                out.append(ln, 0, indent).append(prefix).append(' ').append(ln, indent, ln.length)
            }
        }
        replaceRange(r[0], r[1], out.toString())
        setSelection(r[0], min(r[0] + out.length, t.length))
    }

    fun indentSelection(unindent: Boolean) {
        val t = buffer
        val r = selLineRange()
        val lines = t.subSequence(r[0], r[1]).toString().split("\n")
        val unit = indentUnit()
        val out = StringBuilder()
        for ((i, ln) in lines.withIndex()) {
            if (i > 0) out.append('\n')
            if (!unindent) {
                if (ln.isEmpty()) out.append(ln) else out.append(unit).append(ln)
            } else {
                var n = 0
                if (ln.startsWith("\t")) {
                    n = 1
                } else {
                    while (n < tabSize && n < ln.length && ln[n] == ' ') n++
                }
                out.append(ln, n, ln.length)
            }
        }
        replaceRange(r[0], r[1], out.toString())
        setSelection(r[0], min(r[0] + out.length, t.length))
    }

    fun replaceAllText(s: String) {
        val t = buffer
        val keep = min(max(0, selectionStart), s.length)
        replaceRange(0, t.length, s)
        setSelection(keep)
    }

    // ---- brackets ----
    private fun rebuildBrackets() {
        val o = StringBuilder("([{")
        val c = StringBuilder(")]}")
        for (p in pairs) {
            if (p.open.length == 1 && p.close.length == 1 && p.open != p.close &&
                o.indexOf(p.open) < 0 && c.indexOf(p.close) < 0
            ) {
                o.append(p.open)
                c.append(p.close)
            }
        }
        openChars = o.toString()
        closeChars = c.toString()
    }

    private fun isBr(ch: Char): Boolean = openChars.indexOf(ch) >= 0 || closeChars.indexOf(ch) >= 0

    private fun updateBracketMatch() {
        bmA = -1
        bmB = -1
        if (!bracketMatch) return
        val t = text ?: return
        val pos = selectionStart
        if (pos < 0 || pos != selectionEnd) return
        val n = t.length
        var idx = -1
        if (pos > 0 && pos <= n && isBr(t[pos - 1])) idx = pos - 1
        else if (pos < n && isBr(t[pos])) idx = pos
        if (idx < 0) return
        val ch = t[idx]
        var found = -1
        val oi = openChars.indexOf(ch)
        if (oi >= 0) {
            val close = closeChars[oi]
            val end = min(n, idx + 1 + BR_SCAN)
            val buf = CharArray(end - idx - 1)
            TextUtils.getChars(t, idx + 1, end, buf, 0)
            var depth = 1
            for (k in buf.indices) {
                val c = buf[k]
                if (c == ch) {
                    depth++
                } else if (c == close) {
                    depth--
                    if (depth == 0) {
                        found = idx + 1 + k
                        break
                    }
                }
            }
        } else {
            val open = openChars[closeChars.indexOf(ch)]
            val start = max(0, idx - BR_SCAN)
            val buf = CharArray(idx - start)
            TextUtils.getChars(t, start, idx, buf, 0)
            var depth = 1
            for (k in buf.indices.reversed()) {
                val c = buf[k]
                if (c == ch) {
                    depth++
                } else if (c == open) {
                    depth--
                    if (depth == 0) {
                        found = start + k
                        break
                    }
                }
            }
        }
        bmA = idx
        bmB = found
    }

    fun jumpToMatchingBracket(): Boolean {
        updateBracketMatch()
        if (bmA < 0 || bmB < 0) return false
        val target = if (bmB > bmA) bmB + 1 else bmB
        setSelection(target)
        revealOffset(target)
        return true
    }

    private fun drawRange(canvas: Canvas, l: Layout, s: Int, e: Int, p: Paint) {
        matchPath.reset()
        l.getSelectionPath(s, e, matchPath)
        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
        canvas.drawPath(matchPath, p)
        canvas.restore()
    }

    // ---- indent guides ----
    private fun firstNonWs(t: CharSequence, ls: Int): Int {
        val lim = min(t.length, ls + 400)
        var i = ls
        while (i < lim && (t[i] == ' ' || t[i] == '\t')) i++
        if (i >= t.length || t[i] == '\n') return -1
        return i
    }

    private fun guidesFor(l: Layout, t: Editable, ls0: Int): FloatArray {
        var ls = ls0
        var fn = firstNonWs(t, ls)
        var tries = 0
        while (fn < 0 && tries < 30) {
            val e = lineEnd(ls)
            if (e >= t.length) return EMPTY_F
            ls = e + 1
            fn = firstNonWs(t, ls)
            tries++
        }
        if (fn < 0) return EMPTY_F
        val out = ArrayList<Float>(4)
        var cols = 0
        var i = ls
        while (i < fn) {
            val ch = t[i]
            if (ch == '\t' || cols % tabSize == 0) out.add(l.getPrimaryHorizontal(i))
            cols += if (ch == '\t') tabSize else 1
            i++
        }
        return out.toFloatArray()
    }

    private fun drawGuides(canvas: Canvas, l: Layout) {
        val t = buffer
        val top = extendedPaddingTop
        val first = l.getLineForVertical(max(0, scrollY - top))
        val last = l.getLineForVertical(max(0, scrollY + height - top))
        val left = compoundPaddingLeft.toFloat()
        val w = max(1f, dp(1f))
        val cache = HashMap<Int, FloatArray>()
        for (row in first..last) {
            var r = row
            var steps = 0
            while (r > 0 && steps < 200 && t[l.getLineStart(r) - 1] != '\n') {
                r--
                steps++
            }
            if (r > 0 && t[l.getLineStart(r) - 1] != '\n') continue
            val ls = l.getLineStart(r)
            var g = cache[ls]
            if (g == null) {
                g = guidesFor(l, t, ls)
                cache[ls] = g
            }
            if (g.isEmpty()) continue
            val y1 = (top + l.getLineTop(row)).toFloat()
            val y2 = (top + l.getLineBottom(row)).toFloat()
            for (x in g) canvas.drawRect(left + x, y1, left + x + w, y2, guidePaint)
        }
    }

    // ---- smooth caret / selection ----
    private fun smoothCaretActive(): Boolean = smoothCaret && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** With the smooth caret on, the system caret is made invisible and we draw our own, animated one. */
    private fun updateCursorDrawable() {
        val t = edTheme ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val d = GradientDrawable()
                d.setColor(if (smoothCaretActive()) Color.TRANSPARENT else t.cursor)
                d.setSize(max(2, dp(2f).toInt()), 0)
                setTextCursorDrawable(d)
            } catch (e: Throwable) {
                // keep the default cursor
            }
        }
        caretInit = false
    }

    private fun drawSmoothCaret(canvas: Canvas, l: Layout) {
        val a = selectionStart
        if (!isFocused || a < 0 || a != selectionEnd) {
            caretInit = false
            return
        }
        val line = l.getLineForOffset(a)
        val tx = compoundPaddingLeft + l.getPrimaryHorizontal(a)
        val tTop = (extendedPaddingTop + l.getLineTop(line)).toFloat()
        val tBot = (extendedPaddingTop + l.getLineBottom(line)).toFloat()
        val now = SystemClock.uptimeMillis()
        if (!caretInit) {
            caretX = tx
            caretTop = tTop
            caretBottom = tBot
            caretInit = true
            caretMovedAt = now
            caretTargetPos = a
        } else {
            if (a != caretTargetPos) {
                caretTargetPos = a
                caretMovedAt = now
            }
            val dx = tx - caretX
            val dt = tTop - caretTop
            val db = tBot - caretBottom
            if (abs(dx) < 0.5f && abs(dt) < 0.5f && abs(db) < 0.5f) {
                caretX = tx
                caretTop = tTop
                caretBottom = tBot
            } else {
                caretX += dx * 0.45f
                caretTop += dt * 0.45f
                caretBottom += db * 0.45f
                postInvalidateOnAnimation()
            }
        }
        val moving = abs(tx - caretX) > 0.5f || abs(tTop - caretTop) > 0.5f
        val visible = moving || ((now - caretMovedAt) / 530L) % 2L == 0L
        if (visible) canvas.drawRect(caretX, caretTop, caretX + max(2f, dp(2f)), caretBottom, caretPaint)
        if (!moving) postInvalidateDelayed(265L)
    }

    private fun drawSmoothSelection(canvas: Canvas, l: Layout) {
        val a = min(selectionStart, selectionEnd)
        val b = max(selectionStart, selectionEnd)
        if (a < 0 || a == b) {
            animInit = false
            return
        }
        val huge = b - a > 20000
        if (!animInit) {
            animS = a.toFloat()
            animE = if (huge) b.toFloat() else a.toFloat()
            animInit = true
        }
        if (huge) {
            animS = a.toFloat()
            animE = b.toFloat()
        }
        var moving = false
        val ds = a - animS
        val de = b - animE
        if (abs(ds) < 0.5f) {
            animS = a.toFloat()
        } else {
            animS += ds * 0.35f
            moving = true
        }
        if (abs(de) < 0.5f) {
            animE = b.toFloat()
        } else {
            animE += de * 0.35f
            moving = true
        }
        val n = buffer.length
        val s0 = Math.round(animS).coerceIn(0, n)
        val e0 = Math.round(animE).coerceIn(0, n)
        if (e0 > s0) {
            selPath.reset()
            l.getSelectionPath(s0, e0, selPath)
            canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            canvas.drawPath(selPath, selPaint)
            canvas.restore()
        }
        if (moving) postInvalidateOnAnimation()
    }

    // ---- whitespace markers ----
    private fun drawWhitespace(canvas: Canvas, l: Layout) {
        val t = buffer
        val top = extendedPaddingTop
        val first = l.getLineForVertical(max(0, scrollY - top))
        val last = l.getLineForVertical(max(0, scrollY + height - top))
        val left = compoundPaddingLeft.toFloat()
        val spaceW = paint.measureText(" ")
        val r = max(1f, dp(1.1f))
        wsPaint.strokeWidth = max(1f, dp(1f))
        for (row in first..last) {
            val rs = l.getLineStart(row)
            val re = min(min(l.getLineEnd(row), t.length), rs + 800)
            val midY = (top + l.getLineBaseline(row)).toFloat() - textSize * 0.3f
            var i = rs
            while (i < re) {
                val ch = t[i]
                if (ch == ' ') {
                    canvas.drawCircle(left + l.getPrimaryHorizontal(i) + spaceW / 2f, midY, r, wsPaint)
                } else if (ch == '\t') {
                    val x1 = left + l.getPrimaryHorizontal(i)
                    val x2 = if (i + 1 <= t.length) left + l.getPrimaryHorizontal(i + 1) else x1 + spaceW * tabSize
                    if (x2 > x1 + 4f) {
                        canvas.drawLine(x1 + 2f, midY, x2 - 2f, midY, wsPaint)
                        canvas.drawLine(x2 - 2f - r * 3f, midY - r * 2f, x2 - 2f, midY, wsPaint)
                        canvas.drawLine(x2 - 2f - r * 3f, midY + r * 2f, x2 - 2f, midY, wsPaint)
                    }
                }
                i++
            }
        }
    }

    // ---- fast scroller ----
    /** [top, height] of the scroll thumb in view coordinates, or null when nothing can scroll. */
    private fun thumbMetrics(): FloatArray? {
        val l = layout ?: return null
        val maxY = maxScrollY()
        if (maxY <= 0) return null
        val pad = dp(4f)
        val track = height - 2 * pad
        if (track <= 0f) return null
        val total = (l.height + compoundPaddingTop + compoundPaddingBottom).toFloat()
        val minH = min(dp(48f), track)
        val h = (track * height / total).coerceIn(minH, track)
        val frac = (scrollY.toFloat() / maxY).coerceIn(0f, 1f)
        return floatArrayOf(pad + frac * (track - h), h)
    }

    private fun drawThumb(canvas: Canvas, l: Layout?) {
        val th = edTheme ?: return
        val m = thumbMetrics() ?: return
        val now = SystemClock.uptimeMillis()
        val a = if (draggingThumb) 1f else ((thumbUntil - now) / 300f).coerceIn(0f, 1f)
        if (a <= 0f) return
        val w = if (draggingThumb) dp(8f) else dp(5f)
        val right = scrollX + width - dp(3f)
        val top = scrollY + m[0]
        thumbPaint.color = th.fg
        thumbPaint.alpha = ((if (draggingThumb) 190 else 110) * a).toInt()
        canvas.drawRoundRect(RectF(right - w, top, right, top + m[1]), w / 2, w / 2, thumbPaint)
        if (draggingThumb && l != null) {
            val row = l.getLineForVertical(max(0, scrollY - extendedPaddingTop))
            val label = "Ln " + (lineIndexOf(l.getLineStart(row)) + 1)
            val tw = bubbleTextPaint.measureText(label)
            val bh = dp(28f)
            val padH = dp(10f)
            val bRight = right - w - dp(10f)
            val lo = scrollY + dp(4f)
            val hi = scrollY + height - bh - dp(4f)
            val bTop = (top + m[1] / 2 - bh / 2).coerceIn(lo, max(lo, hi))
            bubblePaint.color = th.accent
            canvas.drawRoundRect(RectF(bRight - tw - 2 * padH, bTop, bRight, bTop + bh), dp(8f), dp(8f), bubblePaint)
            canvas.drawText(label, bRight - tw - padH, bTop + bh / 2 + bubbleTextPaint.textSize / 3f, bubbleTextPaint)
        }
        if (!draggingThumb) postInvalidateOnAnimation()
    }

    private fun handleThumbTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!draggingThumb && SystemClock.uptimeMillis() >= thumbUntil) return false
                val m = thumbMetrics() ?: return false
                if (e.x >= width - dp(30f) && e.y >= m[0] - dp(14f) && e.y <= m[0] + m[1] + dp(14f)) {
                    draggingThumb = true
                    dragOffset = e.y - m[0]
                    scroller.abortAnimation()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!draggingThumb) return false
                val m = thumbMetrics() ?: return true
                val pad = dp(4f)
                val range = height - 2 * pad - m[1]
                if (range > 0f) {
                    val frac = ((e.y - dragOffset - pad) / range).coerceIn(0f, 1f)
                    scrollTo(scrollX, (frac * maxScrollY()).toInt())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!draggingThumb) return false
                draggingThumb = false
                thumbUntil = SystemClock.uptimeMillis() + 1200L
                invalidate()
                return true
            }
        }
        return false
    }

    // ---- text tools ----
    /** Replaces [s,e) without triggering auto-close / auto-indent. */
    fun replaceRange(s: Int, e: Int, str: String) {
        rawEdit = true
        try {
            buffer.replace(s, e, str)
        } finally {
            rawEdit = false
        }
    }

    /** Applies [f] to the selection (or the whole text). Returns an error message, or null on success. */
    fun transformText(f: (String) -> String): String? {
        val t = buffer
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        val hasSel = b > a
        val s0 = if (hasSel) a else 0
        val e0 = if (hasSel) b else t.length
        val src = t.subSequence(s0, e0).toString()
        val out = try {
            f(src)
        } catch (ex: Exception) {
            return ex.message ?: "Failed"
        }
        if (out == src) return null
        replaceRange(s0, e0, out)
        if (hasSel) setSelection(s0, min(s0 + out.length, t.length)) else setSelection(min(a, t.length))
        return null
    }

    // ---- find support ----
    fun showMatch(s: Int, e: Int) {
        curS = s
        curE = e
        revealOffset(s)
        invalidate()
    }

    fun hasMatch(): Boolean = curS >= 0 && curE > curS && curE <= buffer.length
    fun matchStart(): Int = curS
    fun matchEnd(): Int = curE

    fun clearMatch() {
        curS = -1
        invalidate()
    }

    fun revealOffset(off: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(off.coerceIn(0, buffer.length))
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        val visible = height - compoundPaddingTop - compoundPaddingBottom
        var ny = scrollY
        if (top < scrollY || bottom > scrollY + visible) ny = (top - visible / 3).coerceIn(0, maxScrollY())
        var nx = scrollX
        if (!wordWrap) {
            val x = l.getPrimaryHorizontal(off.coerceIn(0, buffer.length)).toInt()
            val avail = width - compoundPaddingLeft - compoundPaddingRight
            if (x < scrollX || x > scrollX + avail - 20) nx = max(0, x - avail / 3)
        }
        if (nx != scrollX || ny != scrollY) scrollTo(nx, ny)
    }
}
