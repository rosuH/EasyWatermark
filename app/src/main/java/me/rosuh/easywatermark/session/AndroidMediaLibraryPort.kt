package me.rosuh.easywatermark.session

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri

/**
 * Android [MediaLibraryPort]: MediaStore listing + system-picker URI enrichment.
 * Behavior matches the former [me.rosuh.easywatermark.ui.MainViewModel] query paths.
 */
class AndroidMediaLibraryPort(
    private val contentResolver: ContentResolver,
) : MediaLibraryPort {

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATA,
        if (Build.VERSION.SDK_INT > 28) {
            MediaStore.Images.Media.DATE_MODIFIED
        } else {
            MediaStore.Images.Media.DATE_TAKEN
        },
        MediaStore.Images.Media.ORIENTATION,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
    )

    private val sortOrder: String
        get() = (if (Build.VERSION.SDK_INT > 28) {
            MediaStore.Images.Media.DATE_MODIFIED
        } else {
            MediaStore.Images.Media.DATE_TAKEN
        }) + " DESC"

    override suspend fun listImages(): List<Image> = withContext(Dispatchers.IO) {
        val list = ArrayList<Image>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val bucketNameColumn =
                cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val dateColumn = cursor.getColumnIndex(
                if (Build.VERSION.SDK_INT > 28) {
                    MediaStore.Images.Media.DATE_MODIFIED
                } else {
                    MediaStore.Images.Media.DATE_TAKEN
                },
            )
            val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataColumn)
                if (path.isNullOrBlank()) continue
                val imageId = cursor.getInt(imageIdColumn)
                val bucketName = cursor.getString(bucketNameColumn) ?: ""
                val dateTaken = cursor.getLong(dateColumn)
                val size = cursor.getLong(sizeColumn)
                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageId.toLong(),
                )
                list += Image(imageId, contentUri.toMediaRef(), bucketName, size, dateTaken)
            }
        }
        list
    }

    override suspend fun enrichPickerRefs(refs: List<MediaRef>): List<Image> =
        withContext(Dispatchers.IO) {
            if (refs.isEmpty()) return@withContext emptyList()
            val uriList = refs.map { it.toUri() }
            val imageList = ArrayList<Image>()
            val selection =
                "${MediaStore.Images.Media._ID} IN (${uriList.joinToString(",") { "?" }})"
            val selectionArgs = uriList.map { ContentUris.parseId(it).toString() }.toTypedArray()
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val bucketNameColumn =
                    cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val dateColumn = cursor.getColumnIndex(
                    if (Build.VERSION.SDK_INT > 28) {
                        MediaStore.Images.Media.DATE_MODIFIED
                    } else {
                        MediaStore.Images.Media.DATE_TAKEN
                    },
                )
                val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    if (path.isNullOrBlank()) continue
                    val imageId = cursor.getInt(imageIdColumn)
                    val bucketName = cursor.getString(bucketNameColumn) ?: ""
                    val dateTaken = cursor.getLong(dateColumn)
                    val size = cursor.getLong(sizeColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId.toLong(),
                    )
                    imageList += Image(
                        imageId,
                        contentUri.toMediaRef(),
                        bucketName,
                        size,
                        dateTaken,
                        check = true,
                    )
                }
            }
            imageList
        }
}
