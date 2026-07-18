package me.rosuh.easywatermark.data.model

import kotlin.jvm.JvmInline

/**
 * Platform-neutral media reference (URI or file path string).
 *
 * Android maps to/from `android.net.Uri` at edges (`MediaRefExt`). DataStore stores [value] as text.
 * Empty sentinel is [Empty].
 */
@JvmInline
value class MediaRef(val value: String) {

    fun isEmpty(): Boolean = value.isEmpty()

    override fun toString(): String = value

    companion object {
        /** No-icon / empty reference sentinel. */
        val Empty: MediaRef = MediaRef("")

        /** Wrap a serialized reference string (missing DataStore keys use `""` → [Empty]). */
        fun parse(s: String): MediaRef = MediaRef(s)
    }
}
