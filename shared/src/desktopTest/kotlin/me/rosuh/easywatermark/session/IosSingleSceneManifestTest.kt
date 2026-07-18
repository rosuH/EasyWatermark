package me.rosuh.easywatermark.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Permanent source-contract guard for the **current release** iOS single-scene policy.
 *
 * [IosAppServices] / `defaultIosAppServices()` owns one process-wide Session. Advertising
 * multi-scene support without a scene-scoped Session design is false capability declaration.
 *
 * An authorized future multi-window slice must deliberately replace this guard with real
 * two-scene isolation tests (route/selection/export/temp isolation) — do not delete it
 * just to re-enable `UIApplicationSupportsMultipleScenes=true`.
 */
class IosSingleSceneManifestTest {

    @Test
    fun sourceManifest_disablesMultipleScenes_untilSceneScopedSessionIsAuthorized() {
        val plist = locateIosInfoPlist()
        val text = plist.readText()
        assertTrue(
            text.contains("<key>UIApplicationSceneManifest</key>"),
            "UIApplicationSceneManifest must be present in ${plist.path}",
        )
        val value = extractSupportsMultipleScenes(text)
            ?: fail(
                "UIApplicationSupportsMultipleScenes key missing under UIApplicationSceneManifest " +
                    "in ${plist.path}",
            )
        assertEquals(
            false,
            value,
            "Current release is single-scene (process-wide IosAppServices Session). " +
                "Found UIApplicationSupportsMultipleScenes=$value in ${plist.path}. " +
                "Re-enabling multi-scene requires a separately authorized scene-scoped Session design " +
                "and two-scene isolation tests — not a silent true flip.",
        )
    }

    private fun locateIosInfoPlist(): File {
        val relative = "iosApp/iosApp/Info.plist"
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: fail("iosApp/iosApp/Info.plist not found from user.dir=$cwd candidates=$candidates")
    }

    /**
     * Returns the boolean after `UIApplicationSupportsMultipleScenes` within the scene manifest
     * dict, or null if the key is absent. Does not guess safety from unrelated symbols.
     */
    private fun extractSupportsMultipleScenes(plistXml: String): Boolean? {
        val key = "<key>UIApplicationSupportsMultipleScenes</key>"
        val keyIdx = plistXml.indexOf(key)
        if (keyIdx < 0) return null
        val after = plistXml.substring(keyIdx + key.length)
        val trueIdx = after.indexOf("<true/>")
        val falseIdx = after.indexOf("<false/>")
        // Next boolean tag wins (plist is well-formed; value immediately follows the key).
        return when {
            trueIdx < 0 && falseIdx < 0 -> null
            trueIdx < 0 -> false
            falseIdx < 0 -> true
            trueIdx < falseIdx -> true
            else -> false
        }
    }
}
