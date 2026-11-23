package com.forai.codepad

import android.os.SystemClock

/**
 * Per-document undo history. Edits are exact (start, removed text, inserted text) so they
 * can always be reversed. Consecutive typing / backspacing merges into one step.
 */
class UndoManager {
    class Edit(var start: Int, var before: String, var after: String, var time: Long)

    private val undo = ArrayList<Edit>()
    private val redo = ArrayList<Edit>()
    private var savedAt = 0
    private var savedValid = true
    private var noMerge = false

    var version = 0
        private set

    fun canUndo(): Boolean = undo.isNotEmpty()
    fun canRedo(): Boolean = redo.isNotEmpty()

    /** True when the document differs from what was last saved (or loaded). */
    fun isModified(): Boolean = !(savedValid && undo.size == savedAt)

    fun markSaved() {
        savedAt = undo.size
        savedValid = true
        noMerge = true
    }

    fun forceModified() {
        savedValid = false
    }

    fun record(start: Int, before: String, after: String) {
        if (before == after) return
        version++
        redo.clear()
        if (savedAt > undo.size) savedValid = false
        val now = SystemClock.uptimeMillis()
        val last = undo.lastOrNull()
        if (last != null && !noMerge && now - last.time < 1000 && merge(last, start, before, after)) {
            last.time = now
            if (last.before == last.after) undo.removeAt(undo.size - 1)
            return
        }
        noMerge = false
        undo.add(Edit(start, before, after, now))
        if (undo.size > MAX) {
            undo.removeAt(0)
            savedAt--
            if (savedAt < 0) savedValid = false
        }
    }

    private fun merge(last: Edit, start: Int, before: String, after: String): Boolean {
        if (before.indexOf('\n') >= 0 || after.indexOf('\n') >= 0) return false
        if (last.after.length > 400 || last.before.length > 400) return false
        val lastEnd = last.start + last.after.length
        // edit that happens inside text the previous edit inserted (typing, IME composing, backspacing fresh text)
        if (start >= last.start && start + before.length <= lastEnd) {
            val off = start - last.start
            last.after = last.after.substring(0, off) + after + last.after.substring(off + before.length)
            return true
        }
        // repeated backspace / forward-delete over original text
        if (last.after.isEmpty() && after.isEmpty()) {
            if (start + before.length == last.start) {
                last.start = start
                last.before = before + last.before
                return true
            }
            if (start == last.start) {
                last.before = last.before + before
                return true
            }
        }
        return false
    }

    fun popUndo(): Edit? {
        if (undo.isEmpty()) return null
        val e = undo.removeAt(undo.size - 1)
        redo.add(e)
        noMerge = true
        version++
        return e
    }

    fun popRedo(): Edit? {
        if (redo.isEmpty()) return null
        val e = redo.removeAt(redo.size - 1)
        undo.add(e)
        noMerge = true
        version++
        return e
    }

    private companion object {
        const val MAX = 1000
    }
}
