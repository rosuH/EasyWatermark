package me.rosuh.easywatermark.ui.compose

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.toMediaRef
import me.rosuh.easywatermark.utils.ktx.toUri


@Preview
@Composable
fun IconOptionPreview() {
    IconOption(
        item = FuncTitleModel(
            FuncType.Icon,
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
    // S4d-50: IconOption is the Android edge. The picker launcher still returns android.net.Uri;
    // it is converted to a platform-neutral MediaRef HERE at the picker-result boundary, so Uri
    // never escapes into the model/ViewModel layer.
    onIconSelected: (item: FuncTitleModel, MediaRef) -> Unit,
) {
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
        onResult = { uri -> uri?.let { onIconSelected(item, it.toMediaRef()) } })
    IconWatermarkOption(
        hasIcon = waterMark.iconUri.isEmpty().not(),
        pickLabel = stringResource(id = R.string.action_pick),
        modifier = modifier,
        onPick = {
            if (mediaPermissionState.status.isGranted) {
                singlePhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                mediaPermissionState.launchPermissionRequest()
            }
        },
        preview = {
            AsyncImage(
                model = waterMark.iconUri.toUri(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                contentDescription = stringResource(id = R.string.water_mark_mode_image)
            )
        },
    )
}
