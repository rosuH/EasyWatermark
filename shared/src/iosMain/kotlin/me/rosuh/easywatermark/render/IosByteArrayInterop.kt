@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package me.rosuh.easywatermark.render

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * S4d-32: the **iOS Swift↔Kotlin byte-array bulk-copy boundary**.
 *
 * The Kotlin/Native ObjC bridge exposes `KotlinByteArray` to Swift with element accessors only
 * (`get(index:)`/`set(index:value:)`), so the previous Swift helpers (`KotlinInterop.swift`) copied
 * image-sized buffers **one byte at a time** — O(n) Swift calls per picked photo and per encoded PNG.
 *
 * This object moves the copy to a single native `memcpy`, bridging through Foundation `NSData` (which
 * Swift `Data` bridges to/from for free). It is the bulk analogue of the private `NSData → ByteArray`
 * copy already used by [IosFontLoader] (same `usePinned` + `memcpy` pattern, **no new dependency** —
 * `platform.Foundation`/`platform.posix` are Kotlin/Native bundled interop).
 *
 * **Byte-exact, no reinterpretation:** `memcpy` copies raw bytes, so every value round-trips including
 * the signed-`Byte` edge cases `0x00`/`0x7F`/`0x80`/`0xFF`. This is a pure copy mechanic — it does NOT
 * touch render semantics, fonts, tile/gap/degree, or the [IosWatermarkRenderBridge] error boundary.
 * iosMain-only: it is not compiled for the Android/`:app` or desktop targets.
 */
object IosByteArrayInterop {

    /** Copy an [NSData]'s bytes into a Kotlin [ByteArray] via one `memcpy` (pin the destination). */
    fun fromNSData(data: NSData): ByteArray {
        val size = data.length.toInt()
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length.convert())
        }
        return out
    }

    /**
     * Copy a Kotlin [ByteArray] into a new [NSData] via one `memcpy`. Uses `+[NSData dataWithBytes:length:]`
     * (the `create(bytes:length:)` binding), which **copies** the buffer, so the pinned address is only
     * needed for the duration of the call.
     */
    fun toNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        }
    }
}
