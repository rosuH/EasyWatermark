package me.rosuh.easywatermark.platform

import android.app.Application
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E2 L1 — minimal restore identifiers only (route + MediaRef strings).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidSessionRestoreStoreTest {

    @Test
    fun saveEditorSources_roundTripsMinimalIds_withoutPayloads() {
        val context = RuntimeEnvironment.getApplication()
        val store = AndroidSessionRestoreStore(context)
        store.clear()

        val refs = listOf(
            MediaRef("content://me.rosuh.easywatermark.debug.fileprovider/share_sources/share-a.png"),
            MediaRef("content://me.rosuh.easywatermark.debug.fileprovider/share_sources/share-b.png"),
        )
        store.saveEditorSources(refs)

        val snap = store.read()
        assertEquals(LaunchScreenUiState.Editor, snap!!.route)
        assertEquals(refs, snap.sourceRefs)
        // Contract: prefs hold only strings — no binary image keys.
        val raw = context.getSharedPreferences(AndroidSessionRestoreStore.PREFS_NAME, 0)
        assertTrue(raw.all.values.all { it is String })
    }

    @Test
    fun clear_removesSnapshot() {
        val store = AndroidSessionRestoreStore(RuntimeEnvironment.getApplication())
        store.saveEditorSources(listOf(MediaRef("content://x/share_sources/share-1.png")))
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun emptyEditorSources_isRejected() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(AndroidSessionRestoreStore.PREFS_NAME, 0)
        prefs.edit().putString("route", "editor").putString("sources", "").apply()
        val store = AndroidSessionRestoreStore(prefs)
        assertNull(store.read())
    }
}
