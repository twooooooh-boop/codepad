package com.forai.codepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.Layout
import android.text.Selection
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatEditText
import java.util.concurrent.Executors
import java.util.regex.Pattern
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
        const val MAX_HIGHLIGHT = 2_000_000
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

    var fontSp: Float = 14f
        private set

    var undoManager: UndoManager = UndoManager()
    var findPattern: Pattern? = null

    var lang: Lang = Langs.plain
        private set

    var onCaretMoved: (() -> Unit)? = null
    var onEdited: (() -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null

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
            scheduleTokenize(if (programmatic) 0L else 250L)
            onEdited?.invoke()
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    private val indentFilter = InputFilter { source, start, end, dest, dstart, _ ->
        if (programmatic || !autoIndent || source == null || end - start != 1 || source[start] != '\n') {
            return@InputFilter null
        }
        var ls = dstart
        while (ls > 0 && dest[ls - 1] != '\n') ls--
        var i = ls
        while (i < dstart && (dest[i] == ' ' || dest[i] == '\t')) i++
        val indent = dest.subSequence(ls, i).toString()
        var j = dstart - 1
        while (j >= ls && (dest[j] == ' ' || dest[j] == '\t')) j--
        var extra = ""
        if (j >= ls) {
            val ch = dest[j]
            if (ch == '{' || ch == '(' || ch == '[' || (ch == ':' && blockColon)) extra = indentUnit()
        }
        "\n" + indent + extra
    }

    init {
        typeface = Typeface.MONOSPACE
        gutterPaint.typeface = Typeface.MONOSPACE
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = true
        setLineSpacing(0f, 1.1f)
        filters = arrayOf(indentFilter)
        addTextChangedListener(watcher)
        applyFontSize(14f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---- theme ----
    private fun applyTheme(t: EditorTheme) {
        setBackgroundColor(t.bg)
        setTextColor(t.fg)
        highlightColor = t.selection
        gutterBgPaint.color = t.gutterBg
        dividerPaint.color = (t.gutterFg and 0x00FFFFFF) or 0x40000000
        linePaint.color = t.currentLine
        matchPaint.color = t.findMatch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val d = GradientDrawable()
                d.setColor(t.cursor)
                d.setSize(max(2, dp(2f).toInt()), 0)
                setTextCursorDrawable(d)
            } catch (e: Throwable) {
                // keep default cursor
            }
        }
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
        t.getChars(0, n, charBuf, 0)
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
        if (showLineNumbers && l != null && th != null) drawGutter(canvas, l, th)
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
        invalidate()
        onCaretMoved?.invoke()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        scheduleSpans(40L)
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
        t.replace(r[1], r[1], "\n" + block)
        val d = block.length + 1
        setSelection(min(a + d, t.length), min(b + d, t.length))
    }

    fun deleteLines() {
        val t = buffer
        val r = selLineRange()
        var s = r[0]
        var e = r[1]
        if (e < t.length) e++ else if (s > 0) s--
        t.replace(s, e, "")
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
            t.replace(ps, e, block + "\n" + prev)
            val d = prev.length + 1
            setSelection((selA - d).coerceIn(0, t.length), (selB - d).coerceIn(0, t.length))
        } else {
            if (e >= t.length) return
            val ne = lineEnd(e + 1)
            val next = t.subSequence(e + 1, ne).toString()
            val block = t.subSequence(s, e).toString()
            t.replace(s, ne, next + "\n" + block)
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
        t.replace(r[0], r[1], out.toString())
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
        t.replace(r[0], r[1], out.toString())
        setSelection(r[0], min(r[0] + out.length, t.length))
    }

    fun replaceAllText(s: String) {
        val t = buffer
        val keep = min(max(0, selectionStart), s.length)
        t.replace(0, t.length, s)
        setSelection(keep)
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
