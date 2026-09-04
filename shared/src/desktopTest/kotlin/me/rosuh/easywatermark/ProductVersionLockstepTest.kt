package me.rosuh.easywatermark

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product version lives in three hand-synced places: `buildSrc` `Apps` (Android
 * `versionName`/`versionCode` + Desktop `packageVersion`), commonMain [ProductVersion] (the
 * About row on all three platforms), and the Xcode project (`MARKETING_VERSION` /
 * `CURRENT_PROJECT_VERSION`). Nothing generates one from another, so a partial bump ships an
 * About screen that lies about the build. These are source-text contracts, not device gates.
 */
class ProductVersionLockstepTest {

    private fun resolveRepoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found from user.dir=$cwd")
    }

    private fun read(path: String): String = resolveRepoFile(path).readText()

    private fun single(pattern: Regex, text: String, label: String): String {
        val values = pattern.findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(values.isNotEmpty(), "$label not found")
        assertEquals(1, values.distinct().size, "$label disagrees with itself: $values")
        return values.first()
    }

    @Test
    fun buildSrcAndProductVersionAgree() {
        val apps = read("buildSrc/src/main/kotlin/Dependencies.kt")
        val appsName = single(Regex("""versionName\s*=\s*"([^"]+)""""), apps, "Apps.versionName")
        val appsCode = single(Regex("""versionCode\s*=\s*(\d+)"""), apps, "Apps.versionCode")

        assertEquals(appsName, ProductVersion.NAME, "Apps.versionName vs ProductVersion.NAME")
        assertEquals(appsCode.toInt(), ProductVersion.CODE, "Apps.versionCode vs ProductVersion.CODE")
    }

    @Test
    fun iosProjectAgreesWithProductVersion() {
        val pbxproj = read("iosApp/iosApp.xcodeproj/project.pbxproj")
        val marketing = single(Regex("""MARKETING_VERSION\s*=\s*([0-9.]+);"""), pbxproj, "MARKETING_VERSION")
        val build = single(Regex("""CURRENT_PROJECT_VERSION\s*=\s*(\d+);"""), pbxproj, "CURRENT_PROJECT_VERSION")

        assertEquals(ProductVersion.NAME, marketing, "MARKETING_VERSION vs ProductVersion.NAME")
        assertEquals(ProductVersion.CODE, build.toInt(), "CURRENT_PROJECT_VERSION vs ProductVersion.CODE")
    }

    /**
     * `versionCode` is `major * 10000 + minor * 100 + patch`. Play refuses a lower or equal code
     * than the installed build, so the encoding has to stay monotonic with the marketing version.
     */
    @Test
    fun versionCodeEncodesVersionName() {
        val parts = ProductVersion.NAME.split(".").map { it.toInt() }
        assertEquals(3, parts.size, "versionName must be major.minor.patch: ${ProductVersion.NAME}")
        val (major, minor, patch) = parts
        assertTrue(minor < 100 && patch < 100, "minor/patch must stay below 100 to keep the encoding unique")
        assertEquals(major * 10000 + minor * 100 + patch, ProductVersion.CODE)
    }

    /** jpackage MSI rejects a major above 255; the same string feeds DMG and DEB. */
    @Test
    fun versionNameIsValidForDesktopPackaging() {
        val (major, minor, patch) = ProductVersion.NAME.split(".").map { it.toInt() }
        assertTrue(major in 1..255, "jpackage MSI needs 1..255 major, got $major")
        assertTrue(minor in 0..255, "jpackage MSI needs 0..255 minor, got $minor")
        assertTrue(patch in 0..65535, "jpackage MSI needs 0..65535 build, got $patch")
    }

    /** Every released build must be newer than the last shipped store version (2.10.0 / 21000). */
    @Test
    fun versionIsAheadOfLastShippedRelease() {
        assertTrue(
            ProductVersion.CODE > 21000,
            "versionCode ${ProductVersion.CODE} must exceed the shipped Play/F-Droid 2.10.0 code 21000",
        )
    }
}
