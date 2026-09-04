Applicable XR devices This guidance helps you build experiences for these types of XR devices. [Learn about XR device types →](https://developer.android.com/develop/xr/devices) ![](https://developer.android.com/static/images/develop/xr/ai-glasses-icon.svg) Audio \&  
Display Glasses [](https://developer.android.com/develop/xr/devices#audio-display) [Learn about XR device types →](https://developer.android.com/develop/xr/devices)

<br />

Just like on a phone, accessing sensitive hardware like the camera and
microphone on audio glasses and display glasses requires explicit user consent.
These are considered
**glasses-specific permissions**, and your app must request them at runtime,
even if it already has the corresponding permissions on the phone.

## Declare the permissions in your app's manifest

Before requesting permissions, you must [declare them in your app's manifest](https://developer.android.com/training/permissions/declaring)
file using the [`<uses-permission>`](https://developer.android.com/guide/topics/manifest/uses-permission-element) element. This declaration remains the
same whether the permission is for a phone or a glasses-specific feature, but
you must still explicitly request it for glasses-specific hardware or
functionality.

    <manifest ...>
        <!-- Only declare permissions that your app actually needs. In this example,
        we declare permissions for the camera. -->
        <uses-permission android:name="android.permission.CAMERA"/>
        <application ...>
            ...
        </application>
    </manifest>

## Before requesting glasses permissions

Review the [permission principles and workflows](https://developer.android.com/training/permissions/requesting#principles) to make sure you provide the
best experience for your users, like [checking whether the user has already
granted the runtime permissions](https://developer.android.com/training/permissions/requesting#already-granted) your app requires and [whether your app
should show a rationale](https://developer.android.com/training/permissions/requesting#explain) to the user before requesting glasses-specific
permissions.

## Permission scenarios

There are different scenarios you might encounter when requesting [dangerous
runtime permissions](https://developer.android.com/guide/topics/permissions/requesting#normal-dangerous) on audio and display glasses:

- Request runtime permissions from a projected activity
- Request runtime permissions from a phone activity

See the following sections in this guide for details about each scenario.

### Request and handle runtime permissions from a projected activity

To request permissions for audio glasses and display glasses, first use the
[`ActivityResultLauncher`](https://developer.android.com/reference/kotlin/androidx/activity/result/ActivityResultLauncher) with the
[`ProjectedPermissionsResultContract`](https://developer.android.com/reference/kotlin/androidx/xr/projected/permissions/ProjectedPermissionsResultContract#ProjectedPermissionsResultContract()) method to register the permissions
launcher. When the user has acted on the permission request, the callback
receives a map of permission names to their granted status.


```kotlin
// Register the permissions launcher using the ProjectedPermissionsResultContract.
private val requestPermissionLauncher: ActivityResultLauncher<List<ProjectedPermissionsRequestParams>> =
    registerForActivityResult(ProjectedPermissionsResultContract()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            isPermissionDenied = false
            initializeGlassesFeatures()
        } else {
            // Handle permission denial.
            isPermissionDenied = true
        }
    }
```

<br />

#### Key points about the code

- You need to specify which permissions your app requires, such as [`Manifest.permission.CAMERA`](https://developer.android.com/reference/kotlin/android/Manifest.permission#camera) or [`Manifest.permission.RECORD_AUDIO`](https://developer.android.com/reference/kotlin/android/Manifest.permission#record_audio).
- Your app should handle both granted and denied results gracefully in the launcher's callback.

To trigger the permission request flow, pass a list of
[`ProjectedPermissionsRequestParams`](https://developer.android.com/reference/kotlin/androidx/xr/projected/permissions/ProjectedPermissionsRequestParams) to your registered permission
launcher's `launch` method. The `ProjectedPermissionsRequestParams` object
bundles the requested manifest permissions together with a custom `rationale`
string. The `rationale` string must clearly and concisely explain why the app
requires access to the glasses' hardware features (such as the camera or
microphone).


```kotlin
private fun requestHardwarePermissions() {
    val params = ProjectedPermissionsRequestParams(
        permissions = listOf(Manifest.permission.CAMERA),
        rationale = "Camera access is required to overlay digital content on your physical environment."
    )
    requestPermissionLauncher.launch(listOf(params))
}
```

<br />

#### Key points about the code

- Calling `launch` on the launcher triggers the [permission request user
  flow](https://developer.android.com/develop/xr/jetpack-xr-sdk/request-hardware-permissions#permissions-user-flow).

#### Understand the permission request user flow

When you launch a permission request using the
[`ProjectedPermissionsResultContract`](https://developer.android.com/reference/kotlin/androidx/xr/projected/permissions/ProjectedPermissionsResultContract#ProjectedPermissionsResultContract()) method, the system initiates a
coordinated user flow across both the glasses and the phone.

During the permissions user flow, here is what your app and the user can expect:

1. **On the glasses** : An activity appears on the **projected device
   (glasses)**, instructing the user to look at their phone to continue.

   <br />

   > [!WARNING]
   > **Preview:** Currently, the instructions and rationale provided by the system are not audible to the user. To provide an audible rationale to the user, we recommend using [Text to Speech
   > (TTS)](https://developer.android.com/develop/xr/jetpack-xr-sdk/tts). For example:
   >
   > <br />
   >
   >     tts?.speak("Please review the permission request on your host device",
   >     TextToSpeech.QUEUE_ADD,
   >     null,
   >     "permission_request")
   >
   > <br />
   >
   <br />

2. **On the phone** : Concurrently, an activity launches on the **host device
   (phone)**. This screen displays the rationale string you provided and gives
   the user the option to proceed or cancel.

3. **On the phone** : If the user accepts the rationale, a modified Android
   system permission dialog appears on the phone telling the user that they are
   granting the permission **for the glasses** (not the phone), and the user
   can formally grant or deny the permission.

4. **Receiving the result** : After the user makes their final choice, the
   activities on both the phone and glasses are dismissed. Your
   [`ActivityResultLauncher`](https://developer.android.com/reference/kotlin/androidx/activity/result/ActivityResultLauncher) callback is then invoked with a map
   containing the granted status for each requested permission.

### Request runtime permissions in a phone activity

If your app is running in a phone activity but requires permissions for audio or
display glasses---for example, to let a user switch a video stream to the glasses'
camera for a first-person point of view---request the permissions using the
device-specific [`Activity#requestPermissions(permissions, requestCode,
deviceId)`](https://developer.android.com/reference/kotlin/android/app/Activity#requestpermissions_1) method.

To target audio and display glasses, obtain the appropriate device ID by calling
[`getDeviceId`](https://developer.android.com/reference/kotlin/android/content/Context#getdeviceid) on a projected device context. Pass this ID when requesting
permissions from your phone activity, as shown in the following example:


```kotlin
// Request the projected permission from phone activity
requestPermissions(
    arrayOf(Manifest.permission.CAMERA),
    // REQUEST_CODE_GLASSES_CAMERA is a developer-defined constant
    REQUEST_CODE_GLASSES_CAMERA,
    projectedDeviceId
)
```

<br />

#### Handle the permission results

Once the user responds to the permission dialog on the phone, their decision is
delivered to your app by invoking the onRequestPermissionsResult callback.

To handle the response, override [`onRequestPermissionsResult`](https://developer.android.com/reference/kotlin/androidx/core/app/ActivityCompat.OnRequestPermissionsResultCallback#onRequestPermissionsResult(int,java.lang.String%5B%5D,int%5B%5D)) within the
`Activity` instance that initiated the permission request.

The following code snippet shows how to handle the callback and verify whether
the user granted the requested permissions:


```kotlin
private companion object {
    // REQUEST_CODE_GLASSES_CAMERA is a developer-defined constant.
    const val REQUEST_CODE_GLASSES_CAMERA = 1001
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
    deviceId: Int
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)

    // Handle the result of the permission request
    if (requestCode == REQUEST_CODE_GLASSES_CAMERA && deviceId == projectedDeviceId) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Proceed with glasses camera features
        } else {
            // Handle glasses permission denied
        }
    }
}
```

<br />

##### Key points about the code

- Use the `deviceId` overload with the `onRequestPermissionsResult` callback to ensure the permission status is correctly mapped to the specific context, such as the audio or display glasses rather than the host phone.
- Your app should handle both granted and denied results gracefully.