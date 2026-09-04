import Foundation
import Shared

// C5.4 (S4d-27, bulk path S4d-32): Swift <-> Kotlin `ByteArray` bridging for the iOS watermark workflow.
//
// The Kotlin/Native ObjC bridge exposes `KotlinByteArray` to Swift with element accessors only
// (`get(index:)`/`set(index:value:)`), so a naive Swift bridge copies image-sized buffers one byte at a
// time (O(n) Swift calls). S4d-32 moves the copy into a single native `memcpy` in `:shared`
// (`IosByteArrayInterop`, iosMain), bridged through Foundation `NSData`. The Kotlin/Native Swift
// importer bridges the `NSData *` param/return to Swift `Data`, so no explicit `as NSData`/`as Data`
// cast is needed here. The conversion is byte-exact (so signed-`Byte` edge values 0x00/0x7F/0x80/0xFF
// round-trip) and changes no render semantics. These helpers keep the same names/signatures, so the
// call sites (`WatermarkWorkflow`) are unchanged.

extension Data {
    /// Copy these bytes into a Kotlin `ByteArray` for `IosWatermarkRenderBridge.renderWatermarkedPng(...)`.
    /// One native `memcpy` via `IosByteArrayInterop.fromNSData` (no per-byte Swift loop).
    func toKotlinByteArray() -> KotlinByteArray {
        IosByteArrayInterop.shared.fromNSData(data: self)
    }
}

extension KotlinByteArray {
    /// Copy a Kotlin `ByteArray` (e.g. the encoded PNG from the render bridge) into Swift `Data`.
    /// One native `memcpy` via `IosByteArrayInterop.toNSData` (no per-byte Swift loop).
    func toData() -> Data {
        IosByteArrayInterop.shared.toNSData(bytes: self)
    }
}
