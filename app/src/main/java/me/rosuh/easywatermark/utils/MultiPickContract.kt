package me.rosuh.easywatermark.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.CallSuper

class MultiPickContract :
    ActivityResultContract<String, List<Uri?>>() {
    @CallSuper
    override fun createIntent(context: Context, input: String): Intent {
        return Intent(Intent.ACTION_PICK)
            .setType(input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    override fun getSynchronousResult(
        context: Context,
        input: String
    ): SynchronousResult<List<Uri?>>? {
        return null
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri?> {
        return if (intent == null || resultCode != Activity.RESULT_OK) {
            emptyList()
        } else getClipDataUris(intent)
    }

    companion object {
        fun getClipDataUris(intent: Intent): List<Uri> {
            // Use a LinkedHashSet to maintain any ordering that may be
            // present in the ClipData
            val resultSet = LinkedHashSet<Uri>()
            if (intent.data != null) {
                resultSet.add(intent.data ?: Uri.EMPTY)
            }
            val clipData = intent.clipData
            if (clipData == null && resultSet.isEmpty()) {
                return emptyList()
            } else if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    if (uri != null) {
                        resultSet.add(uri)
                    }
                }
            }
            return ArrayList(resultSet)
        }
    }
}
