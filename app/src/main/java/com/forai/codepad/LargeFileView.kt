package com.forai.codepad

import android.content.ContentResolver
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Opens a document as a seekable channel without loading it into memory. */
object LargeFile {
    class Opened(val channel: FileChannel, val pfd: ParcelFileDescriptor?, val size: Long, val temp: File?)

    private const val MAX_COPY = 2L * 1024 * 1024 * 1024

    fun release(o: Opened) {
        try {
            o.channel.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            o.pfd?.close()
        } catch (e: Exception) {
            // ignore
        }
        o.temp?.delete()
    }

    /** Blocking: call off the UI thread. Throws IOException with a readable message. */
    fun open(ctx: Context, cr: ContentResolver, uri: Uri): Opened {
        val pfd = try {
            cr.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd != null) {
            try {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val size = ch.size()
                if (size > 0) ch.read(ByteBuffer.allocate(1), size - 1)   // fails on non-seekable sources
                return Opened(ch, pfd, size, null)
            } catch (e: Exception) {
                try {
                    pfd.close()
                } catch (e2: Exception) {
                    // ignore
                }
            }
        }
        // The provider can't do random access: stream it into a private cache file (disk, not RAM).
        val tmp = File(ctx.cacheDir, "viewer.tmp")
        tmp.delete()
        val ins = cr.openInputStream(uri) ?: throw IOException("Cannot open file")
        var total = 0L
        ins.use { s ->
            FileOutputStream(tmp).use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_COPY) throw IOException("This file source needs a copy and the file is too large")
                    o.write(buf, 0, n)
                }
            }
        }
        return Opened(RandomAccessFile(tmp, "r").channel, null, total, tmp)
    }
}

/**
 * Read-only viewer for files of any size. Nothing but the visible rows is ever in memory:
 * text mode shows control characters and invalid bytes as symbols, hex mode shows offset / bytes / ASCII.
 */
class LargeFileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Row(val start: Long, val end: Long, val text: String)

    companion object {
        private const val ANCHOR = 65536L
        private const val MAX_ROW_BYTES = 2000
        private const val HEX = "0123456789ABCDEF"
    }

    var onStatus: ((String) -> Unit)? = null

    private var opened: LargeFile.Opened? = null
    private var channel: FileChannel? = null
    var fileSize = 0L
        private set

    var hexMode = false
        private set
    private var viewStart = 0L
    private var pxOffset = 0f
    private var hScroll = 0f
    private var maxRowChars = 0

    private val rows = ArrayList<Row>()
    private var windowDirty = true

    private var theme: EditorTheme? = null
    private var fontSp = 12f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var rowH = 0f
    private var baselineInRow = 0f
    private var charW = 1f
    private var visibleRows = 0
    private var bytesPerRow = 16
    private var gutterW = 0f

    private val ui = Handler(Looper.getMainLooper())
    private var thumbUntil = 0L
    private var draggingThumb = false
    private var dragOffset = 0f

    private val flinger = OverScroller(context)
    private var lastFlingY = 0

    private val searcher = Executors.newSingleThreadExecutor()
    @Volatile
    private var searchCancelled = false

    private val statusRunnable = Runnable { onStatus?.invoke(statusText()) }

    init {
        textPaint.typeface = Typeface.MONOSPACE
        gutterPaint.typeface = Typeface.MONOSPACE
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ------------------------------------------------------------------ lifecycle

    fun setTheme(t: EditorTheme) {
        theme = t
        textPaint.color = t.fg
        gutterPaint.color = t.gutterFg
        invalidate()
    }

    fun attach(o: LargeFile.Opened, startOffset: Long, hex: Boolean) {
        close()
        opened = o
        channel = o.channel
        fileSize = o.size
        hexMode = hex
        pxOffset = 0f
        hScroll = 0f
        windowDirty = true
        ensureMetrics()
        viewStart = align(startOffset.coerceIn(0L, max(0L, fileSize - 1)))
        postStatus()
        invalidate()
    }

    fun close() {
        searchCancelled = true
        val o = opened
        opened = null
        channel = null
        fileSize = 0
        rows.clear()
        if (o != null) LargeFile.release(o)
    }

    fun currentOffset(): Long = viewStart

    // ------------------------------------------------------------------ reading

    private fun readAt(pos: Long, len: Int): ByteArray {
        val ch = channel ?: return ByteArray(0)
        if (len <= 0 || pos < 0 || pos >= fileSize) return ByteArray(0)
        return try {
            val n = min(len.toLong(), fileSize - pos).toInt()
            val bb = ByteBuffer.allocate(n)
            var p = pos
            while (bb.hasRemaining()) {
                val r = ch.read(bb, p)
                if (r < 0) break
                p += r
            }
            bb.array().copyOf(bb.position())
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** End (exclusive) of the row that starts at [from] in [b]. Rows end at a newline or after MAX_ROW_BYTES. */
    private fun rowEnd(b: ByteArray, from: Int): Int {
        val limit = min(b.size, from + MAX_ROW_BYTES)
        var i = from
        while (i < limit) {
            if (b[i] == 10.toByte()) return i + 1
            i++
        }
        var e = limit
        // forced break: never cut inside a UTF-8 character
        if (limit - from == MAX_ROW_BYTES && e < b.size) {
            var back = 0
            while (e > from + 1 && back < 3 && (b[e].toInt() and 0xC0) == 0x80) {
                e--
                back++
            }
        }
        return max(e, from + 1)
    }

    private fun nextRowStart(pos: Long): Long {
        if (hexMode) return pos + bytesPerRow
        val b = readAt(pos, MAX_ROW_BYTES + 8)
        if (b.isEmpty()) return fileSize
        return pos + rowEnd(b, 0)
    }

    /** Rows restart at every 64 KB boundary, so finding the row that contains [pos] never scans more than that. */
    private fun rowStartAtOrBefore(pos: Long): Long {
        if (pos <= 0L || fileSize == 0L) return 0L
        val p = min(pos, fileSize - 1)
        val anchor = (p / ANCHOR) * ANCHOR
        val b = readAt(anchor, (p - anchor).toInt() + MAX_ROW_BYTES + 8)
        if (b.isEmpty()) return anchor
        var i = 0
        while (true) {
            val e = rowEnd(b, i)
            if (anchor + e > p || e >= b.size) return anchor + i
            i = e
        }
    }

    private fun prevRowStart(pos: Long): Long =
        if (hexMode) max(0L, pos - bytesPerRow) else rowStartAtOrBefore(pos - 1)

    private fun align(off: Long): Long =
        if (hexMode) (off / bytesPerRow) * bytesPerRow else rowStartAtOrBefore(off)

    // ------------------------------------------------------------------ decoding

    private fun decodeRow(b: ByteArray, from: Int, to: Int): String {
        var end = to
        if (end > from && b[end - 1] == 10.toByte()) end--
        if (end > from && b[end - 1] == 13.toByte()) end--
        val sb = StringBuilder(end - from)
        var i = from
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                when {
                    c == 9 -> sb.append("    ")
                    c < 32 || c == 127 -> sb.append('·')
                    else -> sb.append(c.toChar())
                }
                i++
                continue
            }
            val n = when {
                c in 0xC2..0xDF -> 2
                c in 0xE0..0xEF -> 3
                c in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (n == 0 || i + n > end) {
                sb.append('·')
                i++
                continue
            }
            var ok = true
            for (k in 1 until n) {
                if ((b[i + k].toInt() and 0xC0) != 0x80) {
                    ok = false
                    break
                }
            }
            if (!ok) {
                sb.append('·')
                i++
                continue
            }
            val cp = when (n) {
                2 -> ((c and 0x1F) shl 6) or (b[i + 1].toInt() and 0x3F)
                3 -> ((c and 0x0F) shl 12) or ((b[i + 1].toInt() and 0x3F) shl 6) or (b[i + 2].toInt() and 0x3F)
                else -> ((c and 0x07) shl 18) or ((b[i + 1].toInt() and 0x3F) shl 12) or
                        ((b[i + 2].toInt() and 0x3F) shl 6) or (b[i + 3].toInt() and 0x3F)
            }
            val bad = (n == 3 && (cp < 0x800 || cp in 0xD800..0xDFFF)) || (n == 4 && (cp < 0x10000 || cp > 0x10FFFF))
            if (bad) {
                sb.append('·')
                i++
            } else {
                sb.appendCodePoint(cp)
                i += n
            }
        }
        return sb.toString()
    }

    private fun hexLine(off: Long, b: ByteArray, from: Int, n: Int): String {
        val sb = StringBuilder(80)
        val digits = if (fileSize > 0xFFFFFFFFL) 12 else 8
        for (s in digits - 1 downTo 0) sb.append(HEX[((off shr (s * 4)) and 0xF).toInt()])
        sb.append("  ")
        for (k in 0 until bytesPerRow) {
            if (k < n) {
                val v = b[from + k].toInt() and 0xFF
                sb.append(HEX[v shr 4]).append(HEX[v and 0xF]).append(' ')
            } else {
                sb.append("   ")
            }
        }
        sb.append(' ')
        for (k in 0 until n) {
            val v = b[from + k].toInt() and 0xFF
            sb.append(if (v in 32..126) v.toChar() else '·')
        }
        return sb.toString()
    }

    private fun buildWindow() {
        rows.clear()
        maxRowChars = 0
        if (fileSize == 0L || channel == null) {
            windowDirty = false
            return
        }
        val need = visibleRows + 2
        if (hexMode) {
            val start = (viewStart / bytesPerRow) * bytesPerRow
            val b = readAt(start, need * bytesPerRow)
            var i = 0
            while (i < b.size) {
                val n = min(bytesPerRow, b.size - i)
                val line = hexLine(start + i, b, i, n)
                rows.add(Row(start + i, start + i + n, line))
                maxRowChars = max(maxRowChars, line.length)
                i += n
            }
        } else {
            val b = readAt(viewStart, min(need * MAX_ROW_BYTES, 262144))
            var i = 0
            while (i < b.size && rows.size < need) {
                val e = rowEnd(b, i)
                val text = decodeRow(b, i, e)
                rows.add(Row(viewStart + i, viewStart + e, text))
                maxRowChars = max(maxRowChars, text.length)
                i = e
            }
        }
        windowDirty = false
    }

    // ------------------------------------------------------------------ layout / drawing

    private fun ensureMetrics() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics)
        textPaint.textSize = px
        gutterPaint.textSize = px * 0.9f
        val fm = textPaint.fontMetrics
        val glyphH = fm.descent - fm.ascent
        rowH = glyphH * 1.15f
        baselineInRow = (rowH - glyphH) / 2f - fm.ascent
        charW = max(1f, textPaint.measureText("0"))
        visibleRows = (height / max(1f, rowH)).toInt() + 2
        val avail = ((width - dp(12f)) / charW).toInt()
        bytesPerRow = when {
            avail >= 12 + 4 * 16 -> 16
            avail >= 12 + 4 * 8 -> 8
            else -> 4
        }
        val digits = if (fileSize > 0xFFFFFFFFL) 12 else 8
        gutterW = if (hexMode) 0f else charW * digits + dp(14f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (channel != null) {
            ensureMetrics()
            if (hexMode) viewStart = align(viewStart)
            windowDirty = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        val th = theme ?: return
        canvas.drawColor(th.bg)
        if (channel == null) return
        if (fileSize == 0L) {
            canvas.drawText("Empty file", dp(16f), dp(32f), textPaint)
            return
        }
        ensureMetrics()
        if (windowDirty) buildWindow()

        val pad = dp(8f)
        val availChars = ((width - gutterW - pad) / charW).toInt() + 3
        val maxH = max(0f, maxRowChars * charW - (width - gutterW - pad))
        if (hScroll > maxH) hScroll = maxH
        val firstChar = (hScroll / charW).toInt()
        val x0 = gutterW + pad - hScroll + firstChar * charW

        canvas.save()
        canvas.clipRect(gutterW, 0f, width.toFloat(), height.toFloat())
        for ((i, r) in rows.withIndex()) {
            val y = -pxOffset + i * rowH + baselineInRow
            if (y - rowH > height) break
            val t = r.text
            if (firstChar < t.length) {
                canvas.drawText(t, firstChar, min(t.length, firstChar + availChars), x0, y, textPaint)
            }
        }
        canvas.restore()

        if (!hexMode) {
            fillPaint.color = th.gutterBg
            canvas.drawRect(0f, 0f, gutterW, height.toFloat(), fillPaint)
            val digits = if (fileSize > 0xFFFFFFFFL) 12 else 8
            for ((i, r) in rows.withIndex()) {
                val y = -pxOffset + i * rowH + baselineInRow
                if (y - rowH > height) break
                val sb = StringBuilder(digits)
                for (s in digits - 1 downTo 0) sb.append(HEX[((r.start shr (s * 4)) and 0xF).toInt()])
                canvas.drawText(sb.toString(), dp(6f), y, gutterPaint)
            }
        }
        drawThumb(canvas, th)
    }

    private fun thumbTop(h: Float): Float {
        val frac = if (fileSize > 0) viewStart.toDouble() / fileSize.toDouble() else 0.0
        val pad = dp(4f)
        return (pad + frac.coerceIn(0.0, 1.0) * (height - 2 * pad - h)).toFloat()
    }

    private fun drawThumb(canvas: Canvas, th: EditorTheme) {
        val now = SystemClock.uptimeMillis()
        val a = if (draggingThumb) 1f else ((thumbUntil - now) / 300f).coerceIn(0f, 1f)
        if (a <= 0f || fileSize <= 0) return
        val h = dp(56f)
        val w = if (draggingThumb) dp(8f) else dp(5f)
        val right = width - dp(3f)
        val top = thumbTop(h)
        thumbPaint.color = th.fg
        thumbPaint.alpha = ((if (draggingThumb) 190 else 110) * a).toInt()
        canvas.drawRoundRect(RectF(right - w, top, right, top + h), w / 2, w / 2, thumbPaint)
        if (!draggingThumb) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ scrolling

    private fun avgRowBytes(): Long {
        if (rows.isEmpty()) return 80L
        return max(1L, (rows.last().end - rows.first().start) / rows.size)
    }

    fun jumpTo(offset: Long) {
        if (fileSize == 0L) return
        viewStart = align(offset.coerceIn(0L, fileSize - 1))
        pxOffset = 0f
        windowDirty = true
        noteScrolled()
    }

    private fun scrollByPx(dy: Float) {
        if (fileSize == 0L || rowH <= 0f) return
        val rowsMove = dy / rowH
        if (abs(rowsMove) > 25f) {
            jumpTo(viewStart + (rowsMove * avgRowBytes()).toLong())
            return
        }
        pxOffset += dy
        var guard = 0
        while (pxOffset >= rowH && guard++ < 60) {
            val ns = nextRowStart(viewStart)
            if (ns >= fileSize) {
                pxOffset = 0f
                break
            }
            viewStart = ns
            pxOffset -= rowH
            windowDirty = true
        }
        while (pxOffset < 0f && guard++ < 120) {
            if (viewStart <= 0L) {
                pxOffset = 0f
                break
            }
            viewStart = prevRowStart(viewStart)
            pxOffset += rowH
            windowDirty = true
        }
        noteScrolled()
    }

    private fun noteScrolled() {
        thumbUntil = SystemClock.uptimeMillis() + 1200L
        postStatus()
        invalidate()
    }

    private fun postStatus() {
        ui.removeCallbacks(statusRunnable)
        ui.postDelayed(statusRunnable, 80L)
    }

    private fun sizeText(): String {
        val b = fileSize
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
            else -> String.format("%.2f GB", b / 1073741824.0)
        }
    }

    fun statusText(): String {
        val pct = if (fileSize > 0) (viewStart * 100 / fileSize).toInt() else 0
        return sizeText() + "  ·  0x" + java.lang.Long.toHexString(viewStart).uppercase() + "  ·  " + pct + "%"
    }

    fun toggleHex() {
        hexMode = !hexMode
        ensureMetrics()
        viewStart = align(viewStart)
        pxOffset = 0f
        hScroll = 0f
        windowDirty = true
        noteScrolled()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flinger.abortAnimation()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceX) > abs(distanceY) * 1.2f && maxRowChars > 0) {
                val maxH = max(0f, maxRowChars * charW - (width - gutterW - dp(8f)))
                hScroll = (hScroll + distanceX).coerceIn(0f, maxH)
                invalidate()
            } else {
                scrollByPx(distanceY)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) < abs(velocityX)) return false
            flinger.fling(0, 0, 0, (-velocityY).toInt(), 0, 0, -1_000_000_000, 1_000_000_000)
            lastFlingY = 0
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun computeScroll() {
        super.computeScroll()
        if (flinger.computeScrollOffset()) {
            val y = flinger.currY
            val d = y - lastFlingY
            lastFlingY = y
            scrollByPx(d.toFloat())
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                if (fileSize > 0 && event.x >= width - dp(30f) && now < thumbUntil) {
                    val top = thumbTop(dp(56f))
                    if (event.y >= top - dp(14f) && event.y <= top + dp(56f) + dp(14f)) {
                        draggingThumb = true
                        dragOffset = event.y - top
                        flinger.abortAnimation()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (draggingThumb) {
                val h = dp(56f)
                val range = height - 2 * dp(4f) - h
                if (range > 0f) {
                    val frac = ((event.y - dragOffset - dp(4f)) / range).coerceIn(0f, 1f)
                    jumpTo((frac.toDouble() * fileSize).toLong())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingThumb) {
                draggingThumb = false
                thumbUntil = SystemClock.uptimeMillis() + 1200L
                invalidate()
                return true
            }
        }
        gestures.onTouchEvent(event)
        return true
    }

    // ------------------------------------------------------------------ search

    fun cancelSearch() {
        searchCancelled = true
    }

    private fun matchAt(b: ByteArray, i: Int, needle: ByteArray, ci: Boolean): Boolean {
        for (k in needle.indices) {
            var x = b[i + k].toInt()
            if (ci && x in 65..90) x += 32
            if (x != needle[k].toInt()) return false
        }
        return true
    }

    private fun indexIn(b: ByteArray, len: Int, needle: ByteArray, ci: Boolean): Int {
        val m = needle.size
        val first = needle[0].toInt()
        var i = 0
        while (i <= len - m) {
            var x = b[i].toInt()
            if (ci && x in 65..90) x += 32
            if (x == first && matchAt(b, i, needle, ci)) return i
            i++
        }
        return -1
    }

    /**
     * Looks for [query] starting at [from] (wrapping around). Runs on a background thread; [progress] gets 0..100,
     * [done] gets the offset of the match or -1. Both are called on the UI thread.
     */
    fun search(query: String, from: Long, ignoreCase: Boolean, progress: (Int) -> Unit, done: (Long) -> Unit) {
        val orig = query.toByteArray(Charsets.UTF_8)
        val needle = if (ignoreCase) ByteArray(orig.size) { k ->
            val x = orig[k].toInt()
            (if (x in 65..90) x + 32 else x).toByte()
        } else orig
        if (needle.isEmpty() || channel == null || fileSize == 0L) {
            done(-1L)
            return
        }
        searchCancelled = false
        val total = fileSize
        val start = from.coerceIn(0L, total - 1)
        searcher.execute {
            var found = -1L
            try {
                val chunk = 1 shl 20
                var scanned = 0L
                fun scan(a: Long, b: Long): Long {
                    var pos = a
                    while (pos < b && !searchCancelled) {
                        val data = readAt(pos, chunk + needle.size - 1)
                        if (data.isEmpty()) break
                        val limit = min(data.size.toLong(), b - pos + needle.size - 1).toInt()
                        val idx = indexIn(data, limit, needle, ignoreCase)
                        if (idx >= 0) return pos + idx
                        pos += chunk
                        scanned += chunk
                        val pct = (min(scanned, total) * 100 / total).toInt()
                        ui.post { progress(pct) }
                    }
                    return -1L
                }
                found = scan(start, total)
                if (found < 0 && !searchCancelled && start > 0) found = scan(0L, min(total, start + needle.size))
            } catch (e: Exception) {
                found = -1L
            }
            val result = if (searchCancelled) -1L else found
            ui.post { done(result) }
        }
    }
}
