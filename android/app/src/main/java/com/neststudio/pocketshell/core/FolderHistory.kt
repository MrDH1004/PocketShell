package com.neststudio.pocketshell.core

/** Back / forward through visited folders, as in Explorer: going somewhere new clears forward. */
class FolderHistory {
    private val back = ArrayDeque<String>()
    private val forward = ArrayDeque<String>()

    val canBack get() = back.isNotEmpty()
    val canForward get() = forward.isNotEmpty()

    /** Leaving [from] for another folder (not by back/forward). */
    fun visit(from: String, to: String) {
        if (from == to) return
        back.addLast(from)
        if (back.size > MAX) back.removeFirst()
        forward.clear()
    }

    /** Folder to show for back from [current], or null. */
    fun back(current: String): String? {
        val to = back.removeLastOrNull() ?: return null
        forward.addLast(current)
        return to
    }

    fun forward(current: String): String? {
        val to = forward.removeLastOrNull() ?: return null
        back.addLast(current)
        return to
    }

    fun save(): Pair<ArrayList<String>, ArrayList<String>> = ArrayList(back) to ArrayList(forward)

    fun restore(b: List<String>?, f: List<String>?) {
        back.clear(); forward.clear()
        b?.let { back.addAll(it) }
        f?.let { forward.addAll(it) }
    }

    companion object {
        const val MAX = 100
    }
}
