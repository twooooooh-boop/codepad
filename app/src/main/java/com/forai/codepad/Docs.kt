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
    val undo = UndoManager()

    val lang: Lang get() = langOverride ?: Langs.forFileName(name)
    val isModified: Boolean get() = undo.isModified()
}

object FileIO {
    const val MAX_BYTES = 20 * 1024 * 1024

    class Loaded(val text: String, val crlf: Boolean, val charset: String, val bom: Boolean)

    fun read(cr: ContentResolver, uri: Uri): Loaded {
        val ins = cr.openInputStream(uri) ?: throw IOException("Cannot open file")
        val bytes = ins.use { readLimited(it) }
        return decode(bytes)
    }

    private fun readLimited(ins: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("File is larger than ${MAX_BYTES / 1024 / 1024} MB")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
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
        val out = cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot open file for writing")
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
