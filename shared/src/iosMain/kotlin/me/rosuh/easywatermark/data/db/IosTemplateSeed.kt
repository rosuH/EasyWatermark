@file:OptIn(ExperimentalForeignApi::class)

package me.rosuh.easywatermark.data.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.languageCode
import platform.posix.memcpy

/**
 * iOS template **seed** loader — reads a bundled Android template seed DB (`ewm-db-ch.db` /
 * `ewm-db-eng.db`) from an [NSBundle] and returns its raw bytes, mirroring the [IosFontLoader] NSBundle
 * pattern (`pathForResource` → `NSData.dataWithContentsOfFile` → `ByteArray` via pinned `memcpy`).
 *
 * The seed DBs are the **same** authoritative Android assets (`app/src/main/assets/ewm-db-{ch,eng}.db`),
 * copied verbatim into `iosApp/iosApp/Resources/Seed/` and packaged into `iosApp.app` as Copy Bundle
 * Resources (`iosApp.xcodeproj`) — the proven font-bundling path. The production no-arg
 * `buildTemplateDatabase()` reads them from `NSBundle.mainBundle`; the `buildTemplateDatabase(dir, seedBytes)`
 * overload is the platform-agnostic seam (tests pass bytes directly, since a Kotlin/Native test executable's
 * bundle does not carry the app's Copy Bundle Resources — see `IosFontLoaderTest`).
 *
 * Locale-aware selection mirrors `TemplateDatabaseSeeds.defaultTemplateSeedLanguage()` (Desktop) and
 * `TemplateDatabaseBuilder.android.kt`: Chinese (`ch`) when the locale language contains `zh`, English
 * (`eng`) otherwise.
 *
 * Failure is loud (missing/unreadable/empty resource → [IllegalStateException]), matching [IosFontLoader].
 * No new dependency: Kotlin/Native bundled `platform.Foundation`/`platform.posix` interop only.
 */
object IosTemplateSeed {

    const val LANGUAGE_CH: String = "ch"
    const val LANGUAGE_ENG: String = "eng"

    /** Bundled seed resource base names (extension [SEED_RESOURCE_TYPE]); match the Android asset names. */
    private const val SEED_RESOURCE_PREFIX: String = "ewm-db-"
    private const val SEED_RESOURCE_TYPE: String = "db"

    /** `"ch"` if [locale]'s language contains `"zh"`, `"eng"` otherwise (matches the Android/Desktop rule). */
    fun defaultSeedLanguage(locale: NSLocale = NSLocale.currentLocale): String {
        // `NSLocale.languageCode` is non-null in the Kotlin/Native Foundation binding.
        val language = locale.languageCode
        return if (language.contains("zh")) LANGUAGE_CH else LANGUAGE_ENG
    }

    /**
 * Read the bundled seed DB bytes for [language] (`"ch"`/`"eng"`) from [bundle] (default: the main app
 * Bundle). Throws [IllegalStateException] if the resource is missing, unreadable, or empty.     */
    fun loadSeedBytes(language: String, bundle: NSBundle = NSBundle.mainBundle): ByteArray {
        val name = "$SEED_RESOURCE_PREFIX$language"
        val path = bundle.pathForResource(name, SEED_RESOURCE_TYPE)
            ?: error("IosTemplateSeed: seed resource '$name.$SEED_RESOURCE_TYPE' not found in bundle '${bundle.bundlePath}'")
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("IosTemplateSeed: could not read seed data at '$path' (resource '$name.$SEED_RESOURCE_TYPE')")
        val bytes = data.toByteArray()
        check(bytes.isNotEmpty()) { "IosTemplateSeed: seed resource '$name.$SEED_RESOURCE_TYPE' at '$path' is empty" }
        return bytes
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
