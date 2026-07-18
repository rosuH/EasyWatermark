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
 *
 * Content identity is [MediaStore.Images.Media._ID] → content URI. Do **not** require the
 * Deprecated [MediaStore.Images.Media.DATA] filesystem path: on API 29+ it is often null even * when the row is readable, which would empty the in-app gallery after the user grants access.
 */
class AndroidMediaLibraryPort(
    private val contentResolver: ContentResolver,
) : MediaLibraryPort {

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
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
            val imageIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketNameColumn =
                cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndex(
                if (Build.VERSION.SDK_INT > 28) {
                    MediaStore.Images.Media.DATE_MODIFIED
                } else {
                    MediaStore.Images.Media.DATE_TAKEN
                },
            )
            val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(imageIdColumn)
                if (imageId <= 0L) continue
                val bucketName =
                    if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn) ?: "" else ""
                val dateTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                val contentUri: Uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageId,
                )
                list += Image(
                    id = imageId.toInt(),
                    uri = contentUri.toMediaRef(),
                    name = bucketName,
                    size = size,
                    date = dateTaken,
                )
            }
        }
        list
    }

    override suspend fun enrichPickerRefs(refs: List<MediaRef>): List<Image> =
        withContext(Dispatchers.IO) {
            if (refs.isEmpty()) return@withContext emptyList()
            val uriList = refs.map { it.toUri() }
            // Photo Picker / share URIs are not always MediaStore content://…/_ID — parse fails
            // must not abort the whole pick path (caller still builds ImageInfo from raw refs).
            val idArgs = uriList.mapNotNull { uri ->
                runCatching { ContentUris.parseId(uri).takeIf { it > 0L }?.toString() }.getOrNull()
            }
            if (idArgs.isEmpty()) {
                return@withContext refs.mapIndexed { index, ref ->
                    Image(
                        id = index,
                        uri = ref,
                        name = "",
                        size = 0L,
                        date = 0L,
                        check = true,
                    )
                }
            }
            val imageList = ArrayList<Image>()
            val selection =
                "${MediaStore.Images.Media._ID} IN (${idArgs.joinToString(",") { "?" }})"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                idArgs.toTypedArray(),
                sortOrder,
            )?.use { cursor ->
                val imageIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketNameColumn =
                    cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndex(
                    if (Build.VERSION.SDK_INT > 28) {
                        MediaStore.Images.Media.DATE_MODIFIED
                    } else {
                        MediaStore.Images.Media.DATE_TAKEN
                    },
                )
                val sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val imageId = cursor.getLong(imageIdColumn)
                    if (imageId <= 0L) continue
                    val bucketName =
                        if (bucketNameColumn >= 0) cursor.getString(bucketNameColumn) ?: "" else ""
                    val dateTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                    val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId,
                    )
                    imageList += Image(
                        id = imageId.toInt(),
                        uri = contentUri.toMediaRef(),
                        name = bucketName,
                        size = size,
                        date = dateTaken,
                        check = true,
                    )
                }
            }
            // Prefer MediaStore rows when found; otherwise keep original picker refs.
            if (imageList.isNotEmpty()) imageList else {
                refs.mapIndexed { index, ref ->
                    Image(
                        id = index,
                        uri = ref,
                        name = "",
                        size = 0L,
                        date = 0L,
                        check = true,
                    )
                }
            }
        }
}
