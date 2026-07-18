package me.rosuh.easywatermark.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * behavior-preservation gate for the `WaterMark.iconUri: Uri → MediaRef` flip.
 *
 * Pins the **storage-identical** guarantee: `KEY_ICON_URI` is a `stringPreferencesKey`, so the
 * persisted bytes must be the exact same string the legacy `iconUri.toString()` write produced, and
 * the new `MediaRef.parse(...)` read must reconstruct an equal `MediaRef`. This holds for any string
 * an older app version could have written — the empty default ("") and a representative content Uri.
 *
 * The test exercises the storage layer directly (`PreferenceDataStoreFactory` + the same
 * `stringPreferencesKey(SP_KEY_ICON_URI)` the production repo uses) rather than the full
 * `WaterMarkRepository`, so it needs no Koin/Android context and isolates the contract.
 *
 * Equivalent to the S1 `WatermarkTileModeMappingTest` for `MediaRef`.
 */
class MediaRefIconUriRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { tempFolder.newFile("test.preferences_pb") }
    )

    private val key = stringPreferencesKey(WaterMarkRepository.SP_KEY_ICON_URI)

    @Test
    fun empty_default_round_trips_as_empty_mediaref() = runBlocking {
        val store = newStore()
        // The legacy default writes Uri.parse("").toString() == "" to KEY_ICON_URI.
        val empty = MediaRef.Empty
        store.edit { it[key] = empty.value }

        val read = store.data.first()[key] ?: ""
        val reconstructed = MediaRef.parse(read)

        assertTrue("empty default must read back as isEmpty()", reconstructed.isEmpty())
        assertTrue("empty MediaRef must round-trip equal", empty == reconstructed)
        assertEquals("legacy-compatible empty string", "", read)
    }

    @Test
    fun content_uri_string_round_trips_unchanged() = runBlocking {
        val store = newStore()
        val stored = "content://media/external/images/media/42"
        val ref = MediaRef(stored)
        store.edit { it[key] = ref.value }

        val read = store.data.first()[key] ?: ""
        val reconstructed = MediaRef.parse(read)

        assertEquals("persisted string must equal what legacy uri.toString() wrote", stored, read)
        assertTrue("non-empty MediaRef must round-trip equal", ref == reconstructed)
    }

    @Test
    fun missing_key_reads_as_empty_default() = runBlocking {
        // An older install or a fresh install has NO KEY_ICON_URI value; the production read path
        // is `it[KEY_ICON_URI] ?: ""` → must collapse to the empty MediaRef default, never null.
        val store = newStore()
        val read = store.data.first()[key] ?: ""
        val reconstructed = MediaRef.parse(read)

        assertTrue("missing key must read as the empty default", reconstructed.isEmpty())
        assertTrue("missing key must reconstruct to the empty sentinel", MediaRef.Empty == reconstructed)
    }
}
