package com.ethran.notable.data

import com.ethran.notable.data.db.Folder

/**
 * Order of the folder bar on the home screen: `Heute`, `Gestern`, day folders `dd.mm.yyyy` newest
 * first, then everything else alphabetically. The server side names its seven day folders exactly
 * like this; a person's own folders ("New Folder", "Rezepte") follow. Room returns folders in
 * insertion order, which is meaningless once folders get renamed in place every night.
 */
private val DAY_TITLE = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""")

/** Sort key: (group, within-group key). Pure, unit-tested. */
internal fun folderSortKey(title: String): Pair<Int, String> {
    val t = title.trim()
    if (t.equals("Heute", ignoreCase = true)) return 0 to ""
    if (t.equals("Gestern", ignoreCase = true)) return 1 to ""
    DAY_TITLE.matchEntire(t)?.let { m ->
        val (d, mo, y) = m.destructured
        // Descending by date: invert the ISO string by comparing against a fixed "9999-99-99".
        val iso = "$y-$mo-$d"
        val inverted = iso.map { c -> if (c.isDigit()) ('9' - (c - '0')) else c }.joinToString("")
        return 2 to inverted
    }
    return 3 to t.lowercase()
}

fun sortFoldersForBar(folders: List<Folder>): List<Folder> =
    folders.sortedWith(compareBy({ folderSortKey(it.title).first }, { folderSortKey(it.title).second }, { it.id }))

/**
 * The folders the bar shows for [currentId]: every root folder (sorted), then — only if the current
 * folder is nested (legacy data; the app no longer creates nested folders) — the path down to it,
 * then the current folder's own subfolders (sorted) so existing nesting stays reachable.
 */
fun folderBar(all: List<Folder>, currentId: String?): List<Folder> {
    val byId = all.associateBy { it.id }
    val result = LinkedHashMap<String, Folder>()
    sortFoldersForBar(all.filter { it.parentFolderId == null }).forEach { result[it.id] = it }
    if (currentId != null) {
        val chain = ArrayDeque<Folder>()
        var cursor = byId[currentId]
        var guard = 0
        while (cursor != null && guard++ < 64) {
            chain.addFirst(cursor)
            cursor = cursor.parentFolderId?.let { byId[it] }
        }
        chain.forEach { result.putIfAbsent(it.id, it) }
        sortFoldersForBar(all.filter { it.parentFolderId == currentId }).forEach { result.putIfAbsent(it.id, it) }
    }
    return result.values.toList()
}
