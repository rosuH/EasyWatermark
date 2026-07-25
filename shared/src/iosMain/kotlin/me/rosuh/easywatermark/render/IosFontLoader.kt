@file:OptIn(ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.text.font.FontFamily
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * The **iOS font-resource loader boundary** — the platform-edge piece that reads bundled font * files from an `NSBundle` (via `NSData`) and hands the raw **bytes** to the already-accepted
 * [IosTextRasterEnv.bundledFontFamily] rendering boundary.
 *
 * ## Why this exists (the packaging gap, now closed for the local/link half)
 * Kotlin/Native has **no JVM classpath** (`getResourceAsStream`), and `compose-resources` is **forbidden**
 * (CMP-9547 stays out of scope). therefore left iOS font-byte acquisition as the *caller's*
 * responsibility — [IosTextRasterEnv.bundledFontFamily] takes `ByteArray`s. This object adds the missing
 * convenience: read the bytes from the app bundle so a future iOS app target (C5) can build the watermark
 * [FontFamily] with one call, while the **byte-array API stays the core boundary** (so tests / non-bundle
 * callers keep working unchanged).
 *
 * Decode/orientation are unaffected (: Skia bakes EXIF at decode; no manual transform). This is a
 * pure resource-IO boundary in `iosMain`; commonMain stays decode-free and font-bytes-agnostic.
 *
 * ## Failure mode: loud, never silent
 * A missing resource (`pathForResource` → null), an unreadable file (`dataWithContentsOfFile` → null), or
 * empty bytes throw [IllegalStateException] with the resource name + bundle path — callers fail fast at the
 * decode/packaging edge instead of rendering with a wrong/blank font.
 *
 * ## Dependencies: none new
 * Uses only Kotlin/Native's bundled platform interop — `platform.Foundation` (`NSBundle`/`NSData`) and
 * `platform.posix.memcpy` via `kotlinx.cinterop` (the `NSData` → `ByteArray` copy). No third-party library,
 * no `compose-resources`, no binary font asset committed to `iosMain`.
 *
 * ## RUN proof is deferred (no iOS runtime here)
 * `pathForResource`/`dataWithContentsOfFile` only do real work on an iOS runtime; this slice proves the
 * loader **compiles and links** on both iOS targets and documents the contract via [IosFontLoaderTest].
 * The RUN (and real font packaging into an `.app` bundle) lands with the C5 iOS app target.
 */
object IosFontLoader {

    /** Default bundled face names/types (match the desktop fonts in `desktopMain/resources/fonts/`). */
    const val DEFAULT_LATIN_NAME: String = "NotoSans-Regular"
    const val DEFAULT_LATIN_TYPE: String = "ttf"
    const val DEFAULT_CJK_NAME: String = "NotoSansSC-Regular"
    const val DEFAULT_CJK_TYPE: String = "otf"

    /**
 * Read one font resource [name].[type] from [bundle] (default: the main app bundle) and return its raw
 * Bytes. Throws [IllegalStateException] if the resource is missing, unreadable, or empty.     */
    fun loadFontBytes(
        name: String,
        type: String,
        bundle: NSBundle = NSBundle.mainBundle,
    ): ByteArray {
        val path = bundle.pathForResource(name, type)
            ?: error("IosFontLoader: font resource '$name.$type' not found in bundle '${bundle.bundlePath}'")
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("IosFontLoader: could not read font data at '$path' (resource '$name.$type')")
        val bytes = data.toByteArray()
        check(bytes.isNotEmpty()) { "IosFontLoader: font resource '$name.$type' at '$path' is empty" }
        return bytes
    }

    /**
     * H2: process-wide cache of successfully loaded default-bundle families (latinFirst true/false).
     * Avoids re-reading multi-MB font files and rebuilding [FontFamily] on every preview/export.
     * Only caches the default name/type + main bundle path; custom names bypass the cache.
     */
    private val processFamilyLatinFirst = lazy {
        loadDefaultFamily(latinFirst = true, bundle = NSBundle.mainBundle)
    }
    private val processFamilyCjkFirst = lazy {
        loadDefaultFamily(latinFirst = false, bundle = NSBundle.mainBundle)
    }

    private fun loadDefaultFamily(latinFirst: Boolean, bundle: NSBundle): FontFamily {
        val latinBytes = loadFontBytes(DEFAULT_LATIN_NAME, DEFAULT_LATIN_TYPE, bundle)
        val cjkBytes = loadFontBytes(DEFAULT_CJK_NAME, DEFAULT_CJK_TYPE, bundle)
        return IosTextRasterEnv.bundledFontFamily(latinBytes, cjkBytes, latinFirst)
    }

    /**
     * Convenience: load the Latin + CJK faces from [bundle] and build the watermark [FontFamily] via the
     * core [IosTextRasterEnv.bundledFontFamily] boundary. [latinFirst] keeps the owner's Latin+CJK order.
     * Any missing/unreadable face throws (see [loadFontBytes]).
     *
     * H2: default names + main bundle are process-wide cached after first success.
     */
    fun bundledFontFamily(
        latinName: String = DEFAULT_LATIN_NAME,
        latinType: String = DEFAULT_LATIN_TYPE,
        cjkName: String = DEFAULT_CJK_NAME,
        cjkType: String = DEFAULT_CJK_TYPE,
        latinFirst: Boolean = true,
        bundle: NSBundle = NSBundle.mainBundle,
    ): FontFamily {
        val usesDefaultFaces =
            latinName == DEFAULT_LATIN_NAME &&
                latinType == DEFAULT_LATIN_TYPE &&
                cjkName == DEFAULT_CJK_NAME &&
                cjkType == DEFAULT_CJK_TYPE &&
                bundle == NSBundle.mainBundle
        if (usesDefaultFaces) {
            return if (latinFirst) processFamilyLatinFirst.value else processFamilyCjkFirst.value
        }
        val latinBytes = loadFontBytes(latinName, latinType, bundle)
        val cjkBytes = loadFontBytes(cjkName, cjkType, bundle)
        return IosTextRasterEnv.bundledFontFamily(latinBytes, cjkBytes, latinFirst)
    }

    /** Copy an [NSData] into a Kotlin [ByteArray] (pin the target, `memcpy` from the NSData bytes). */
    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size <= 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, length.convert())
        }
        return out
    }
}
