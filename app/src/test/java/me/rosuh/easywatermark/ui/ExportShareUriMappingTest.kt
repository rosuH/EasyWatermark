package me.rosuh.easywatermark.ui

import android.app.Application
import android.net.Uri
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.utils.ktx.uriFromExportResultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers production edge helper [uriFromExportResultData] (MainActivity share/open list
 * maps `image.result?.data` through this function). Does not instrument Activity source text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportShareUriMappingTest {

    @Test
    fun mediaRefExportData_mapsToNonEmptyUri() {
        val result: Result<*> = Result.success(MediaRef("content://media/external/images/media/42"))
        assertEquals(
            Uri.parse("content://media/external/images/media/42"),
            uriFromExportResultData(result.data),
        )
    }

    @Test
    fun emptyMediaRef_mapsToNullForShareList() {
        assertNull(uriFromExportResultData(Result.success(MediaRef("")).data))
    }

    @Test
    fun wrongPayloadType_mapsToNull() {
        assertNull(uriFromExportResultData(Result.success("not-a-media-ref").data))
    }

    @Test
    fun multiImageList_preservesOrderAndDropsEmpty() {
        val list = listOf(
            Result.success(MediaRef("content://a/1")),
            Result.success(MediaRef("")),
            Result.success(MediaRef("content://a/2")),
        )
        val uris = list.mapNotNull { uriFromExportResultData(it.data) }
        assertEquals(2, uris.size)
        assertEquals("content://a/1", uris[0].toString())
        assertEquals("content://a/2", uris[1].toString())
    }
}
