package me.rosuh.easywatermark.platform

import android.content.Context
import android.content.SharedPreferences
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.LaunchScreenUiState

/**
 * E2: minimal process-death restore identifiers for Android product Session.
 *
 * Stores **only** route + source MediaRef strings — never bitmap payloads or offsets.
 * Restoration re-opens files from app-owned cache ([AndroidShareStaging]) when present.
 */
class AndroidSessionRestoreStore(
    private val prefs: SharedPreferences,
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    data class Snapshot(
        val route: LaunchScreenUiState,
        val sourceRefs: List<MediaRef>,
    )

    fun saveEditorSources(refs: List<MediaRef>) {
        prefs.edit()
            .putString(KEY_ROUTE, ROUTE_EDITOR)
            .putString(KEY_SOURCES, refs.joinToString(SEPARATOR) { it.value })
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun read(): Snapshot? {
        val routeRaw = prefs.getString(KEY_ROUTE, null) ?: return null
        val route = when (routeRaw) {
            ROUTE_EDITOR -> LaunchScreenUiState.Editor
            ROUTE_LAUNCH -> LaunchScreenUiState.Launch
            else -> return null
        }
        val sources = prefs.getString(KEY_SOURCES, null)
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { MediaRef(it) }
            .orEmpty()
        if (route == LaunchScreenUiState.Editor && sources.isEmpty()) return null
        return Snapshot(route = route, sourceRefs = sources)
    }

    companion object {
        const val PREFS_NAME = "ewm_session_restore"
        private const val KEY_ROUTE = "route"
        private const val KEY_SOURCES = "sources"
        private const val ROUTE_EDITOR = "editor"
        private const val ROUTE_LAUNCH = "launch"
        private const val SEPARATOR = "\u001f"
    }
}
