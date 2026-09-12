package com.ethran.notable.data

import com.ethran.notable.data.db.Folder

/**
 * Order of the folder bar on the home screen: `Today`, `Yesterday`, day folders `dd.mm.yyyy`
 * newest first, then everything else alphabetically. The server side names its day folders
 * exactly like this (the German `Heute` / `Gestern` are accepted as aliases for the transition);
 * a person's own folders ("New Folder", "Rezepte") follow. Room returns folders in insertion
 * order, which is meaningless once folders get renamed in place every night.
 */
private val DAY_TITLE = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""")
private val TODAY_TITLES = listOf("Today", "Heute")
private val YESTERDAY_TITLES = listOf("Yesterday", "Gestern")

/** Sort key: (group, within-group key). Pure, unit-tested. */
internal fun folderSortKey(title: String): Pair<Int, String> {
    val t = title.trim()
    if (TODAY_TITLES.any { t.equals(it, ignoreCase = true) }) return 0 to ""
    if (YESTERDAY_TITLES.any { t.equals(it, ignoreCase = true) }) return 1 to ""
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
 * The folders the bar shows for [currentId]: every root folder (sorted), then — when the current
 * folder is a root folder or below one — the subfolders along the path down to it: for each
 * folder on the path from its root ancestor to the current folder, that folder's own subfolders
 * (sorted). So inside `Today` the bar ends with `Notebooks`, `Scratch notes`; inside
 * `Today/Scratch notes` it still shows both siblings (the open one filled) plus whatever lies
 * below the open one. Subfolders are only ever *shown*; the app creates folders in the root only,
 * the server side creates the nested ones.
 */
fun folderBar(all: List<Folder>, currentId: String?): List<Folder> {
    val byId = all.associateBy { it.id }
    val childrenByParent = all.filter { it.parentFolderId != null }.groupBy { it.parentFolderId!! }
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
        chain.forEach { folder ->
            result.putIfAbsent(folder.id, folder)
            sortFoldersForBar(childrenByParent[folder.id].orEmpty()).forEach { result.putIfAbsent(it.id, it) }
        }
    }
    return result.values.toList()
}
