package me.rosuh.easywatermark.utils.ktx

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.ui.MainActivity

fun Activity.isStoragePermissionGrated(): Boolean {
    val readGranted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    val writeGranted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    return readGranted && writeGranted
}

fun Activity.preCheckStoragePermission(block: () -> Unit) {
    if (isStoragePermissionGrated()) {
        block.invoke()
    } else {
        requestPermission()
    }
}

/**
 * 申请权限
 */
fun Activity.requestPermission() {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ),
        MainActivity.REQ_CODE_REQ_WRITE_PERMISSION
    )
}


inline fun ViewModel.launch(crossinline action: suspend CoroutineScope.() -> Unit): Job {
    return viewModelScope.launch {
        action()
    }
}