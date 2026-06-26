package me.rosuh.easywatermark.utils.ktx

import android.net.Uri
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Android render/edge bridge for the platform-neutral [MediaRef] (CMP plan D7 / ADR-0007). The
 * neutral model is API-agnostic; the Android framework [Uri] appears only HERE at the Android edge,
 * exactly as `ImageFormat.toCompressFormat()` / `WatermarkTileMode.toShaderTileMode()` do.
 *
 * Behavior-preservation guarantee (storage-identical, no migration): for any string `s` a prior app
 * version could have written to `KEY_ICON_URI`,
 *
 *   `MediaRef(s).toUri() == Uri.parse(s)`   (and `Uri.parse(s).toMediaRef() == MediaRef(s)`)
 *
 * i.e. the `Uri` handed to the icon decode path is byte-for-byte the same as the legacy read path
 * produced, so icon rendering/export pixels do not change. The empty sentinel maps to `Uri.EMPTY`,
 * matching the legacy `Uri.parse("")` default.
 */
fun MediaRef.toUri(): Uri = if (value.isEmpty()) Uri.EMPTY else Uri.parse(value)

fun Uri.toMediaRef(): MediaRef = MediaRef(toString())
