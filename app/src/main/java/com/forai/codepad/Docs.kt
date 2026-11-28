package com.forai.codepad

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** One open tab. [content] is only current for documents that are not shown in the editor. */
class Doc(var uri: Uri?, var name: String, val id: String = UUID.randomUUID().toString()) {
    var content: String = ""
    var loaded: Boolean = false
    var selStart: Int = 0
    var selEnd: Int = 0
    var crlf: Boolean = false
    var charset: String = "UTF-8"
    var bom: Boolean = false
    var langOverride: Lang? = null
    var diskTime: Long = 0L
    var indentChecked: Boolean = false
    var hugeChecked: Boolean = false
    var viewer: Boolean = false
    var vStart: Long = 0L
    var vHex: Boolean = false
    var forceWrap: Boolean = false
    var indentSpaces: Boolean? = null
    var indentSize: Int = 4
    val undo = UndoManager()

    val lang: Lang get() = langOverride ?: Langs.forFileName(name)
    val isModified: Boolean get() = undo.isModified()
}

object FileIO {
    /** Hard limit for opening a file as one piece of text. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Above this size we ask before opening, because the editor gets slow. */
    const val WARN_BYTES = 1024 * 1024

    private const val HEAD_BYTES = 1024 * 1024
    private const val PREVIEW_BYTES = 64 * 1024

    const val KIND_TEXT = 0
    const val KIND_BINARY = 1
    const val KIND_TOO_LARGE = 2

    const val MODE_AUTO = 0     // normal open: refuse binary and very large files
    const val MODE_HEAD = 1     // first 1 MB only, as a copy
    const val MODE_FORCE = 2    // open anyway (binary files: first 1 MB only, as a copy)

    class Loaded(
        val text: String,
        val crlf: Boolean,
        val charset: String,
        val bom: Boolean,
        val kind: Int = KIND_TEXT,
        val size: Long = -1L,
        val preview: ByteArray = ByteArray(0),
        /** True when only part of the file was read; such a copy must never overwrite the original. */
        val partial: Boolean = false
    )

    /** File size in bytes, or -1 if the provider doesn't say. */
    fun size(cr: ContentResolver, uri: Uri): Long {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun fill(ins: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Heuristic: NUL bytes, many control characters, or a well-known binary header. */
    fun looksBinary(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        fun at(i: Int): Int = if (i < head.size) head[i].toInt() and 0xFF else -1
        // UTF-16 text legitimately contains NUL bytes
        if ((at(0) == 0xFF && at(1) == 0xFE) || (at(0) == 0xFE && at(1) == 0xFF)) return false
        if (at(0) == 0x50 && at(1) == 0x4B && (at(2) == 3 || at(2) == 5) ) return true     // zip / apk / jar / docx
        if (at(0) == 0x7F && at(1) == 0x45 && at(2) == 0x4C && at(3) == 0x46) return true     // ELF
        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return true     // PNG
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return true                      // JPEG
        if (at(0) == 0x1F && at(1) == 0x8B) return true                                        // gzip
        if (at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46) return true     // PDF
        var control = 0
        for (b in head) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            if (v < 9 || (v in 14..31 && v != 27)) control++
        }
        return control * 10 > head.size
    }

    /** Classic hex dump: offset, 16 bytes in hex, printable characters. */
    fun hexDump(title: String, bytes: ByteArray, totalSize: Long): String {
        val sb = StringBuilder(bytes.size * 5 + 200)
        sb.append(title).append('\n')
        sb.append("Size: ").append(if (totalSize >= 0) totalSize.toString() + " bytes" else "unknown")
        if (totalSize > bytes.size || totalSize < 0) sb.append("   (showing the first ").append(bytes.size).append(" bytes)")
        sb.append("\n\n")
        var off = 0
        while (off < bytes.size) {
            sb.append(String.format("%08X  ", off))
            for (i in 0 until 16) {
                if (off + i < bytes.size) sb.append(String.format("%02X ", bytes[off + i].toInt() and 0xFF)) else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in 0 until 16) {
                if (off + i >= bytes.size) break
                val v = bytes[off + i].toInt() and 0xFF
                sb.append(if (v in 32..126) v.toChar() else '.')
            }
            sb.append("|\n")
            off += 16
        }
        return sb.toString()
    }

    private fun trimUtf8Tail(b: ByteArray): ByteArray {
        var end = b.size
        var k = 0
        while (end > 0 && k < 4 && (b[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            k++
        }
        if (end > 0 && (b[end - 1].toInt() and 0x80) != 0) end--
        return b.copyOf(end)
    }

    fun read(cr: ContentResolver, uri: Uri, mode: Int = MODE_AUTO): Loaded {
        val size = size(cr, uri)
        val ins = cr.openInputStream(uri) ?: throw IOException("Cannot open file")
        return ins.use { stream ->
            val first = ByteArray(8192)
            val n0 = fill(stream, first)
            val head = first.copyOf(n0)
            val binary = looksBinary(head)

            if (mode == MODE_AUTO) {
                if (binary) {
                    val prev = ByteArrayOutputStream()
                    prev.write(head)
                    val more = ByteArray(PREVIEW_BYTES - n0)
                    val got = if (n0 == first.size) fill(stream, more) else 0
                    prev.write(more, 0, got)
                    return@use Loaded("", false, "", false, KIND_BINARY, size, prev.toByteArray())
                }
                if (size > WARN_BYTES) return@use Loaded("", false, "", false, KIND_TOO_LARGE, size)
            }

            val limit = when {
                mode == MODE_HEAD -> HEAD_BYTES
                mode == MODE_FORCE && binary -> HEAD_BYTES
                else -> MAX_BYTES
            }
            val bos = ByteArrayOutputStream()
            bos.write(head)
            var total = n0
            var truncated = false
            if (n0 == first.size) {
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (mode == MODE_AUTO && total + n > WARN_BYTES) {
                        return@use Loaded("", false, "", false, KIND_TOO_LARGE, size)
                    }
                    if (total + n > limit) {
                        if (mode == MODE_AUTO || (mode == MODE_FORCE && !binary)) {
                            throw IOException("File is larger than " + MAX_BYTES / 1024 / 1024 + " MB")
                        }
                        bos.write(buf, 0, limit - total)
                        total = limit
                        truncated = true
                        break
                    }
                    bos.write(buf, 0, n)
                    total += n
                }
            }
            var bytes = bos.toByteArray()
            if (truncated) bytes = trimUtf8Tail(bytes)
            val d = decode(bytes)
            Loaded(d.text, d.crlf, d.charset, d.bom, KIND_TEXT, size, ByteArray(0), truncated || (mode == MODE_FORCE && binary))
        }
    }

    fun decode(b: ByteArray): Loaded {
        var csName = "UTF-8"
        var bom = false
        var s: String
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            csName = "UTF-16LE"; bom = true
            s = String(b, 2, b.size - 2, Charsets.UTF_16LE)
        } else if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            csName = "UTF-16BE"; bom = true
            s = String(b, 2, b.size - 2, Charsets.UTF_16BE)
        } else {
            var off = 0
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
                off = 3; bom = true
            }
            try {
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                s = dec.decode(ByteBuffer.wrap(b, off, b.size - off)).toString()
            } catch (e: CharacterCodingException) {
                s = String(b, off, b.size - off, Charsets.ISO_8859_1)
                csName = "ISO-8859-1"
                bom = false
            }
        }
        val crlf = s.contains("\r\n")
        if (s.indexOf('\r') >= 0) s = s.replace("\r\n", "\n").replace('\r', '\n')
        return Loaded(s, crlf, csName, bom)
    }

    /** If the document's charset can't represent its text, fall back to UTF-8 so nothing is lost. */
    fun fixCharset(d: Doc) {
        try {
            val enc = Charset.forName(d.charset).newEncoder()
            if (!enc.canEncode(d.content)) {
                d.charset = "UTF-8"
                d.bom = false
            }
        } catch (e: Exception) {
            d.charset = "UTF-8"
            d.bom = false
        }
    }

    fun encode(text: String, crlf: Boolean, charset: String, bom: Boolean): ByteArray {
        val t = if (crlf) text.replace("\n", "\r\n") else text
        val body = t.toByteArray(Charset.forName(charset))
        if (!bom) return body
        val prefix = when (charset) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        return prefix + body
    }

    fun write(cr: ContentResolver, uri: Uri, bytes: ByteArray) {
        val first = try {
            cr.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        }
        val out = first ?: cr.openOutputStream(uri, "rwt") ?: throw IOException("Cannot open file for writing")
        out.use {
            it.write(bytes)
            it.flush()
        }
    }

    /** Last-modified time of a document URI, or 0 if the provider doesn't say. */
    fun lastModified(cr: ContentResolver, uri: Uri): Long {
        return try {
            cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun displayName(cr: ContentResolver, uri: Uri): String {
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (!n.isNullOrEmpty()) return n
                    }
                }
            }
        } catch (e: Exception) {
            // fall through
        }
        val seg = uri.lastPathSegment ?: return "untitled"
        return seg.substringAfterLast('/')
    }
}

/** Saves open tabs (and unsaved text) to private storage so nothing is lost if the app is killed. */
class SessionStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "drafts").also { it.mkdirs() }
    private val sessionFile = File(ctx.filesDir, "session.json")

    fun save(docs: List<Doc>, active: Int) {
        try {
            val arr = JSONArray()
            val keep = HashSet<String>()
            for (d in docs) {
                val o = JSONObject()
                o.put("id", d.id)
                o.put("uri", d.uri?.toString() ?: "")
                o.put("name", d.name)
                o.put("sel", d.selStart)
                o.put("selEnd", d.selEnd)
                o.put("crlf", d.crlf)
                o.put("cs", d.charset)
                o.put("bom", d.bom)
                o.put("lang", d.langOverride?.name ?: "")
                o.put("viewer", d.viewer)
                o.put("vstart", d.vStart)
                o.put("vhex", d.vHex)
                val needDraft = d.loaded && (d.uri == null || d.isModified)
                o.put("draft", needDraft)
                if (needDraft) {
                    File(dir, d.id + ".txt").writeText(d.content, Charsets.UTF_8)
                    keep.add(d.id + ".txt")
                }
                arr.put(o)
            }
            dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            val root = JSONObject()
            root.put("active", active)
            root.put("docs", arr)
            sessionFile.writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // best effort
        }
    }

    fun load(): Pair<MutableList<Doc>, Int>? {
        if (!sessionFile.exists()) return null
        try {
            val root = JSONObject(sessionFile.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("docs")
            val list = ArrayList<Doc>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uriStr = o.optString("uri", "")
                val d = Doc(
                    if (uriStr.isEmpty()) null else Uri.parse(uriStr),
                    o.optString("name", "untitled"),
                    o.optString("id", UUID.randomUUID().toString())
                )
                d.selStart = o.optInt("sel", 0)
                d.selEnd = o.optInt("selEnd", 0)
                d.crlf = o.optBoolean("crlf", false)
                d.charset = o.optString("cs", "UTF-8")
                d.bom = o.optBoolean("bom", false)
                val ln = o.optString("lang", "")
                if (ln.isNotEmpty()) d.langOverride = Langs.all.firstOrNull { it.name == ln }
                d.viewer = o.optBoolean("viewer", false)
                d.vStart = o.optLong("vstart", 0L)
                d.vHex = o.optBoolean("vhex", false)
                if (d.viewer) d.loaded = true
                if (o.optBoolean("draft", false)) {
                    val f = File(dir, d.id + ".txt")
                    if (f.exists()) {
                        d.content = f.readText(Charsets.UTF_8)
                        d.loaded = true
                        d.undo.forceModified()
                    }
                } else if (d.uri == null) {
                    d.loaded = true
                }
                list.add(d)
            }
            return Pair(list, root.optInt("active", 0))
        } catch (e: Exception) {
            return null
        }
    }
}

/** ACTION_OPEN_DOCUMENT with read+write+persistable grants, multiple files allowed. */
class OpenDocs : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        return i
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val out = ArrayList<Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { out.add(it) }
        } else {
            intent.data?.let { out.add(it) }
        }
        return out
    }
}
