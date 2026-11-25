package com.forai.codepad

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import kotlin.math.min

/** One file or folder in the explorer tree. */
class FsNode(val docId: String, val name: String, val isDir: Boolean, val parent: FsNode?, val depth: Int) {
    var expanded = false
    var children: List<FsNode>? = null
    var loading = false
}

/**
 * Right-hand file explorer. Works on any folder the user grants through Android's folder picker
 * (Storage Access Framework), so it needs no storage permission.
 */
class Explorer(
    private val ctx: Context,
    private val panel: View,
    private val handle: TextView,
    private val title: TextView,
    private val list: ListView,
    private val emptyView: View,
    private val headerButtons: List<TextView>,
    private val prefs: SharedPreferences,
    private val openFile: (Uri) -> Unit,
    private val renamed: (Uri, Uri, String) -> Unit,
    private val deleted: (Uri) -> Unit,
    private val toast: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cr = ctx.contentResolver

    private var treeUri: Uri? = null
    private var root: FsNode? = null
    private var selected: FsNode? = null
    private val flat = ArrayList<FsNode>()

    var isOpen = false
        private set
    private var panelW = 0

    private var fg = Color.WHITE
    private var accent = Color.CYAN

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = flat.size
        override fun getItem(position: Int): Any = flat[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(ctx).also {
                it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                it.maxLines = 1
                it.ellipsize = android.text.TextUtils.TruncateAt.END
                it.minHeight = dp(38)
                it.gravity = Gravity.CENTER_VERTICAL
            }
            val n = flat[position]
            tv.setPadding(dp(10 + n.depth * 14), 0, dp(8), 0)
            tv.text = (if (n.isDir) (if (n.expanded) "▾ " else "▸ ") else "    ") + n.name
            tv.typeface = if (n.isDir) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.setTextColor(fg)
            tv.setBackgroundColor(if (n === selected) (accent and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            return tv
        }
    }

    init {
        list.adapter = adapter
        list.divider = null
        list.setOnItemClickListener { _, _, pos, _ -> if (pos in 0 until flat.size) clicked(flat[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in 0 until flat.size) menu(flat[pos])
            true
        }
        handle.setOnClickListener { toggle() }
        updateEmptyState()
    }

    // ------------------------------------------------------------------ appearance

    fun applyTheme(t: EditorTheme) {
        fg = t.fg
        accent = t.accent
        panel.setBackgroundColor(t.gutterBg)
        title.setTextColor(t.barFg)
        for (b in headerButtons) b.setTextColor(t.barFg)
        handle.setBackgroundColor(t.bar)
        handle.setTextColor(t.barFg)
        adapter.notifyDataSetChanged()
    }

    /** Call when the editor area changes size. */
    fun layout(areaWidth: Int) {
        val w = min(dp(300), (areaWidth * 0.88f).toInt())
        if (w == panelW) return
        panelW = w
        val lp = panel.layoutParams
        lp.width = w
        panel.layoutParams = lp
        if (!isOpen) panel.translationX = w.toFloat()
    }

    fun toggle() {
        if (isOpen) hide() else show()
    }

    fun show() {
        isOpen = true
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(180).start()
        handle.text = "›"
        val r = root
        if (r != null && r.children == null && !r.loading) load(r)
    }

    fun hide() {
        isOpen = false
        panel.animate().translationX(panelW.toFloat()).setDuration(180).withEndAction {
            if (!isOpen) panel.visibility = View.INVISIBLE
        }.start()
        handle.text = "‹"
    }

    // ------------------------------------------------------------------ folder

    val hasFolder: Boolean get() = root != null

    fun restore() {
        val s = prefs.getString("workspace_uri", null) ?: return
        try {
            setTree(Uri.parse(s), false)
        } catch (e: Exception) {
            prefs.edit().remove("workspace_uri").apply()
        }
    }

    fun setTree(uri: Uri, persist: Boolean) {
        treeUri = uri
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val name = FileIO.displayName(cr, DocumentsContract.buildDocumentUriUsingTree(uri, rootId))
        val r = FsNode(rootId, name, true, null, 0)
        r.expanded = true
        root = r
        selected = r
        if (persist) prefs.edit().putString("workspace_uri", uri.toString()).apply()
        title.text = name.uppercase()
        updateEmptyState()
        load(r)
    }

    fun closeFolder() {
        root = null
        treeUri = null
        selected = null
        flat.clear()
        prefs.edit().remove("workspace_uri").apply()
        title.text = "EXPLORER"
        updateEmptyState()
        adapter.notifyDataSetChanged()
    }

    private fun updateEmptyState() {
        val has = root != null
        emptyView.visibility = if (has) View.GONE else View.VISIBLE
        list.visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun docUri(n: FsNode): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri!!, n.docId)

    private fun rebuildFlat() {
        flat.clear()
        fun walk(n: FsNode) {
            flat.add(n)
            if (n.isDir && n.expanded) n.children?.forEach { walk(it) }
        }
        root?.let { walk(it) }
        adapter.notifyDataSetChanged()
    }

    fun refresh() {
        val r = root ?: return
        r.children = null
        load(r)
    }

    private fun queryChildren(parent: FsNode): List<FsNode> {
        val tu = treeUri ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tu, parent.docId)
        val out = ArrayList<FsNode>()
        cr.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2) ?: ""
                if (id != null && name != null) {
                    out.add(FsNode(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, parent, parent.depth + 1))
                }
            }
        }
        out.sortWith(compareBy<FsNode>({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    private fun load(node: FsNode) {
        if (node.loading) return
        node.loading = true
        io.execute {
            val kids = try {
                queryChildren(node)
            } catch (e: Exception) {
                null
            }
            ui.post {
                node.loading = false
                if (kids == null) {
                    toast("Could not read folder " + node.name)
                } else {
                    node.children = kids
                }
                rebuildFlat()
            }
        }
    }

    // ------------------------------------------------------------------ interaction

    private fun clicked(n: FsNode) {
        selected = n
        if (n.isDir) {
            n.expanded = !n.expanded
            if (n.expanded && n.children == null) load(n)
            rebuildFlat()
        } else {
            adapter.notifyDataSetChanged()
            openFile(docUri(n))
        }
    }

    private fun targetDir(): FsNode? {
        val s = selected ?: root
        return if (s == null) null else if (s.isDir) s else (s.parent ?: root)
    }

    fun newFile() {
        val d = targetDir() ?: return
        create(d, false)
    }

    fun newFolder() {
        val d = targetDir() ?: return
        create(d, true)
    }

    private fun prompt(titleText: String, hint: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(ctx)
        et.hint = hint
        et.setText(initial)
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.selectAll()
        val box = FrameLayout(ctx)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(titleText)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty() && !v.contains('/')) ok(v) else toast("Enter a plain name (no slashes)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun create(parent: FsNode, folder: Boolean) {
        prompt(
            if (folder) "New folder in " + parent.name else "New file in " + parent.name,
            if (folder) "folder name" else "file name, e.g. main.py",
            ""
        ) { name ->
            val pu = docUri(parent)
            io.execute {
                val created = try {
                    DocumentsContract.createDocument(
                        cr, pu,
                        if (folder) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                        name
                    )
                } catch (e: Exception) {
                    null
                }
                ui.post {
                    if (created == null) {
                        toast("Could not create $name")
                    } else {
                        parent.expanded = true
                        parent.children = null
                        load(parent)
                        if (!folder) {
                            val tu = treeUri
                            if (tu != null) {
                                openFile(DocumentsContract.buildDocumentUriUsingTree(tu, DocumentsContract.getDocumentId(created)))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rename(n: FsNode) {
        prompt("Rename", "new name", n.name) { name ->
            val old = docUri(n)
            io.execute {
                val res = try {
                    DocumentsContract.renameDocument(cr, old, name)
                } catch (e: Exception) {
                    null
                }
                ui.post {
                    if (res == null) {
                        toast("Could not rename")
                    } else {
                        val tu = treeUri
                        if (tu != null && !n.isDir) {
                            renamed(old, DocumentsContract.buildDocumentUriUsingTree(tu, DocumentsContract.getDocumentId(res)), name)
                        }
                        val p = n.parent
                        if (p != null) {
                            p.children = null
                            load(p)
                        }
                    }
                }
            }
        }
    }

    private fun delete(n: FsNode) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete " + n.name + "?")
            .setMessage(if (n.isDir) "The folder and everything inside it will be deleted." else "This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val u = docUri(n)
                io.execute {
                    val ok = try {
                        DocumentsContract.deleteDocument(cr, u)
                    } catch (e: Exception) {
                        false
                    }
                    ui.post {
                        if (!ok) {
                            toast("Could not delete")
                        } else {
                            if (!n.isDir) deleted(u)
                            val p = n.parent
                            if (p != null) {
                                p.children = null
                                load(p)
                            }
                            if (selected === n) selected = p
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun menu(n: FsNode) {
        selected = n
        adapter.notifyDataSetChanged()
        val isRoot = n.parent == null
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (n.isDir) {
            labels.add("New file here")
            actions.add { create(n, false) }
            labels.add("New folder here")
            actions.add { create(n, true) }
        }
        if (!isRoot) {
            labels.add("Rename")
            actions.add { rename(n) }
            labels.add("Delete")
            actions.add { delete(n) }
        } else {
            labels.add("Close folder")
            actions.add { closeFolder() }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(n.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }
}
