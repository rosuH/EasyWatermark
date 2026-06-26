package me.rosuh.easywatermark.data.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-78: end-to-end proof on a real (non-Android) runtime that the commonMain `UserConfigRepository`
 * works over a desktop-created preferences DataStore. Exercises the okio-path store creation +
 * write/read roundtrip through the same common repository the Android app uses.
 */
class UserConfigDataStoreRoundtripTest {

    @Test
    fun desktop_store_userconfig_roundtrip() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "s4d78-ds-${System.nanoTime()}")
        try {
            // Uses the default name (UserConfigRepository.SP_NAME) so the default is covered.
            val repo = UserConfigRepository(createUserConfigDataStore(dir))

            // Defaults before any write (empty store).
            val initial = repo.userPreferences.first()
            assertEquals(ImageFormat.JPEG, initial.outputFormat)
            assertEquals(UserConfigRepository.DEFAULT_COMPRESS_LEVEL, initial.compressLevel)

            repo.updateFormat(ImageFormat.PNG)
            repo.updateCompressLevel(60) // 60 % 20 == 0, so it is kept (not snapped to default)

            val updated = repo.userPreferences.first()
            assertEquals(ImageFormat.PNG, updated.outputFormat)
            assertEquals(60, updated.compressLevel)

            // saveVersionCode write path must not throw on the desktop store.
            repo.saveVersionCode(123)
        } finally {
            dir.deleteRecursively()
        }
    }
}
