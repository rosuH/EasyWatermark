package me.rosuh.easywatermark.session

import platform.Foundation.NSLock

/**
 * In-memory Photos asset-id table for already-picked editor photos (ADR-0029 P1).
 *
 * Key is the final Session owned path (`ewm_src_*`), never a provisional import path.
 * Values are `PHPickerResult.assetIdentifier` / `PhotosPickerItem.itemIdentifier`.
 * Not persisted. Not a PhotoKit pixel source.
 */
internal object IosAssetIdentityRegistry {
    const val MAX_ENTRIES: Int = 50

    private val lock = NSLock()
    private val pathToId = LinkedHashMap<String, String>()
    private val idToPath = LinkedHashMap<String, String>()

    fun put(ownedPath: String, assetId: String) {
        if (ownedPath.isBlank() || assetId.isBlank()) return
        lock.lock()
        try {
            pathToId.remove(ownedPath)?.let { previousId ->
                if (idToPath[previousId] == ownedPath) idToPath.remove(previousId)
            }
            idToPath.remove(assetId)?.let { previousPath ->
                pathToId.remove(previousPath)
            }
            while (pathToId.size >= MAX_ENTRIES) {
                val oldest = pathToId.keys.first()
                removeLocked(oldest)
            }
            pathToId[ownedPath] = assetId
            idToPath[assetId] = ownedPath
        } finally {
            lock.unlock()
        }
    }

    fun get(ownedPath: String): String? {
        if (ownedPath.isBlank()) return null
        lock.lock()
        try {
            return pathToId[ownedPath]
        } finally {
            lock.unlock()
        }
    }

    fun pathFor(assetId: String): String? {
        if (assetId.isBlank()) return null
        lock.lock()
        try {
            return idToPath[assetId]
        } finally {
            lock.unlock()
        }
    }

    /** Session Ready path order; skips paths with no id. */
    fun idsForOwnedPaths(ownedPaths: List<String>): List<String> {
        lock.lock()
        try {
            return ownedPaths.mapNotNull { path ->
                pathToId[path]?.takeIf { it.isNotBlank() }
            }
        } finally {
            lock.unlock()
        }
    }

    fun remove(ownedPath: String) {
        if (ownedPath.isBlank()) return
        lock.lock()
        try {
            removeLocked(ownedPath)
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            pathToId.clear()
            idToPath.clear()
        } finally {
            lock.unlock()
        }
    }

    internal fun sizeForTests(): Int {
        lock.lock()
        try {
            return pathToId.size
        } finally {
            lock.unlock()
        }
    }

    internal fun resetForTests() = clear()

    private fun removeLocked(ownedPath: String) {
        val id = pathToId.remove(ownedPath) ?: return
        if (idToPath[id] == ownedPath) idToPath.remove(id)
    }
}
