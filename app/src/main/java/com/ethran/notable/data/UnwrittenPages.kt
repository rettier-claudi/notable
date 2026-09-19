package com.ethran.notable.data

/*
 * Fork: a page added to a notebook and never written on is not part of the notebook yet.
 *
 * "Unwritten" is decided from the database alone, so it survives a restart and needs no bookkeeping:
 * no stroke, no image, and no page_sync_state row (never uploaded, never downloaded). Adding such a
 * page does not stamp the notebook, the upload leaves it out of the manifest, a download keeps it,
 * and closing the editor deletes it. The first stroke makes it an ordinary page: the write stamps
 * the notebook and the next round uploads it.
 */

/**
 * The pages of [pageIds] to hold back from the server: the [unwritten] ones, except that a notebook
 * never goes up without pages — if every page is unwritten, the first one stays (a brand-new
 * notebook is one empty page). Pure.
 */
internal fun unwrittenPagesToHold(pageIds: List<String>, unwritten: Set<String>): Set<String> {
    val held = pageIds.filter { it in unwritten }
    if (held.isEmpty()) return emptySet()
    if (held.size == pageIds.size) return held.drop(1).toSet()
    return held.toSet()
}

/**
 * The page order after a download replaces [localPageIds] with [remotePageIds]: the server's order,
 * with every [held] local page put back — at the end if it ended the local notebook (the page
 * turned to past the last one), otherwise after the local page it followed (or first, if nothing
 * before it survived). Pure.
 */
internal fun keepHeldPages(
    remotePageIds: List<String>,
    localPageIds: List<String>,
    held: Set<String>,
): List<String> {
    if (held.isEmpty()) return remotePageIds
    val result = remotePageIds.filterNot { it in held }.toMutableList()
    val trailingFrom = localPageIds.indexOfLast { it !in held } + 1
    for ((index, pageId) in localPageIds.withIndex()) {
        if (pageId !in held || pageId in result) continue
        if (index >= trailingFrom) {
            result.add(pageId)
            continue
        }
        val predecessor = localPageIds.subList(0, index).lastOrNull { it in result }
        val at = if (predecessor == null) 0 else result.indexOf(predecessor) + 1
        result.add(at, pageId)
    }
    return result
}

/**
 * Where to reopen a notebook after [removed] pages are dropped from [pageIds]: [openPageId] if it
 * stays, else the nearest surviving page before it, else the first surviving page. Pure.
 */
internal fun openPageAfterRemoval(pageIds: List<String>, openPageId: String?, removed: Set<String>): String? {
    if (openPageId == null || openPageId !in removed) return openPageId
    val index = pageIds.indexOf(openPageId)
    val before = if (index > 0) pageIds.subList(0, index).lastOrNull { it !in removed } else null
    return before ?: pageIds.firstOrNull { it !in removed }
}
