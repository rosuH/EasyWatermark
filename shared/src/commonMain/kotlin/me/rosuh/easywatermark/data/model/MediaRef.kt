package me.rosuh.easywatermark.data.model

import kotlin.jvm.JvmInline

/**
 * Platform-neutral image identity (replaces `android.net.Uri` in models; ADR-0007). String-backed so
 * DataStore round-trips without a migration: the historical persisted value under `KEY_ICON_URI` is
 * already `uri.toString()`, and `MediaRef(value)` accepts that exact string.
 *
 * `android.net.Uri` survives only at the Android edge via the `utils/ktx/MediaRefExt.kt` mappers
 * (`MediaRef.toUri()` / `Uri.toMediaRef()`), mirroring the established `ImageFormat` / `WatermarkTileMode`
 * seam (neutral type in `:shared/commonMain`, Android-edge mapper in `:app`).
 *
 * Implemented as an `@JvmInline value class` wrapping a single `String` — the canonical inline-class
 * use case (no per-instance allocation on JVM/Android; identity is just the underlying string). The
 * `@JvmInline` annotation requires `import kotlin.jvm.JvmInline` in `commonMain`; with that import it
 * compiles unchanged for Android + JVM/desktop + iOS/Native (verified by the S4d-50 3-target compile gate).
 * Equality (`==`) is the default underlying-field string equality — `MediaRef(s) == MediaRef(s)` for
 * any `s`. `toString()` is overridden to return the underlying [value].
 */
@JvmInline
value class MediaRef(val value: String) {

    fun isEmpty(): Boolean = value.isEmpty()

    override fun toString(): String = value

    companion object {
        /**
         * The empty/no-icon sentinel. Maps to `Uri.EMPTY` / `Uri.parse("")` at the Android edge, i.e.
         * the same "no icon selected" state the legacy `WaterMark.default.iconUri = Uri.parse("")`
         * represented.
         */
        val Empty: MediaRef = MediaRef("")

        /**
         * Wraps a serialized reference string (typically read from DataStore). The production read path
         * is `it[KEY_ICON_URI] ?: ""`, so `parse` always receives a non-null `String`; an empty stored
         * value (or a missing key via the `?: ""` fallback) collapses to [Empty].
         */
        fun parse(s: String): MediaRef = MediaRef(s)
    }
}
