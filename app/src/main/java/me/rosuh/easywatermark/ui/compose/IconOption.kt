package me.rosuh.easywatermark.ui.compose

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.graphics.drawable.toIcon
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.WaterMark


@Preview
@Composable
fun IconOptionPreview() {
    IconOption(
        item = FuncTitleModel(
            FuncTitleModel.FuncType.Icon,
            R.string.water_mark_mode_image,
            R.drawable.ic_func_sticker
        ),
        waterMark = WaterMark.default
    ) { _, _ -> }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun IconOption(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onIconSelected: (item: FuncTitleModel, Uri) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        if (waterMark.iconUri.toString().isNotBlank()) {
            AsyncImage(
                model = waterMark.iconUri,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                contentDescription = stringResource(id = R.string.water_mark_mode_image)
            )
        }
        val mediaPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionState(
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            rememberPermissionState(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        val singlePhotoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
            onResult = { uri -> uri?.let { onIconSelected(item, it) } })
        Button(onClick = {
            if (mediaPermissionState.status.isGranted) {
                singlePhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                mediaPermissionState.launchPermissionRequest()
            }
        }) {
            Text(text = stringResource(id = R.string.action_pick))
        }
    }
}