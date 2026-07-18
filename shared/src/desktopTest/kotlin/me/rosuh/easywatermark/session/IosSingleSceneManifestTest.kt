package me.rosuh.easywatermark.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Permanent source-contract guard for the **current release** iOS single-scene policy.
 *
 * [IosAppServices] / `defaultIosAppServices()` owns one process-wide Session. Advertising
 * multi-scene support without a scene-scoped Session design is false capability declaration.
 *
 * See ADR-0020. An authorized future multi-window slice must deliberately replace this guard
 * with real two-scene isolation tests — do not delete it just to re-enable
 * `UIApplicationSupportsMultipleScenes=true`.
 */
class IosSingleSceneManifestTest {

    @Test
    fun sourceManifest_disablesMultipleScenes_untilSceneScopedSessionIsAuthorized() {
        val plist = locateIosInfoPlist()
        val text = plist.readText()
        val sceneManifestBody = extractDictBodyAfterKey(text, "UIApplicationSceneManifest")
            ?: fail("UIApplicationSceneManifest dict missing in ${plist.path}")
        val value = extractDirectChildBoolean(
            sceneManifestBody,
            "UIApplicationSupportsMultipleScenes",
        ) ?: fail(
            "UIApplicationSupportsMultipleScenes missing as a **direct child** of " +
                "UIApplicationSceneManifest in ${plist.path} " +
                "(a nested impostor under UISceneConfigurations does not count)",
        )
        assertEquals(
            false,
            value,
            "Current release is single-scene (process-wide IosAppServices Session). " +
                "Found UIApplicationSupportsMultipleScenes=$value as direct child of " +
                "UIApplicationSceneManifest in ${plist.path}. Re-enabling multi-scene requires a " +
                "separately authorized scene-scoped Session design and two-scene isolation " +
                "tests — not a silent true flip.",
        )
    }

    @Test
    fun nestedImpostorKey_underSceneConfigurations_doesNotSatisfyGuard() {
        // Direct child missing; impostor false nested under UISceneConfigurations.
        val impostorBody = """
            <key>UISceneConfigurations</key>
            <dict>
                <key>UIApplicationSupportsMultipleScenes</key>
                <false/>
            </dict>
        """.trimIndent()
        assertNull(
            extractDirectChildBoolean(impostorBody, "UIApplicationSupportsMultipleScenes"),
            "Nested impostor under UISceneConfigurations must not satisfy the single-scene guard",
        )
    }

    @Test
    fun directChildFalse_isAcceptedEvenWhenNestedTrueAlsoPresent() {
        val body = """
            <key>UIApplicationSupportsMultipleScenes</key>
            <false/>
            <key>UISceneConfigurations</key>
            <dict>
                <key>UIApplicationSupportsMultipleScenes</key>
                <true/>
            </dict>
        """.trimIndent()
        assertEquals(
            false,
            extractDirectChildBoolean(body, "UIApplicationSupportsMultipleScenes"),
            "Direct-child false is authoritative; nested true must be ignored",
        )
    }

    private fun locateIosInfoPlist(): File {
        val relative = "iosApp/iosApp/Info.plist"
        val cwd = File(System.getProperty("user.dir")!!)
        // When :shared:desktopTest runs, user.dir is the :shared module root; parent is the repo root.
        val candidates = linkedSetOf(
            File(cwd, relative),
            File(cwd.parentFile ?: cwd, relative),
        )
        return candidates.firstOrNull { it.isFile }
            ?: fail("iosApp/iosApp/Info.plist not found from user.dir=$cwd candidates=$candidates")
    }

    /**
     * Returns the inner XML of the plist dict that immediately follows `<key>$key</key>`,
     * or null if the key or dict is absent. Nested dicts are balanced.
     */
    private fun extractDictBodyAfterKey(plistXml: String, key: String): String? {
        val keyTag = "<key>$key</key>"
        val keyIdx = plistXml.indexOf(keyTag)
        if (keyIdx < 0) return null
        val afterKey = plistXml.substring(keyIdx + keyTag.length)
        val dictOpen = afterKey.indexOf("<dict>")
        if (dictOpen < 0) return null
        val between = afterKey.substring(0, dictOpen).trim()
        if (between.isNotEmpty()) return null
        var depth = 0
        var i = dictOpen
        while (i < afterKey.length) {
            when {
                afterKey.startsWith("<dict>", i) -> {
                    depth++
                    i += "<dict>".length
                }
                afterKey.startsWith("</dict>", i) -> {
                    depth--
                    i += "</dict>".length
                    if (depth == 0) {
                        val openEnd = dictOpen + "<dict>".length
                        val closeStart = i - "</dict>".length
                        return afterKey.substring(openEnd, closeStart)
                    }
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Returns the boolean for a **direct child** key of a dict body (content between matching
     * `<dict>…</dict>`). Keys nested inside other dicts are ignored (depth > 0).
     */
    private fun extractDirectChildBoolean(dictBody: String, key: String): Boolean? {
        val keyTag = "<key>$key</key>"
        var depth = 0
        var i = 0
        while (i < dictBody.length) {
            when {
                dictBody.startsWith("<dict>", i) -> {
                    depth++
                    i += "<dict>".length
                }
                dictBody.startsWith("</dict>", i) -> {
                    depth--
                    i += "</dict>".length
                }
                depth == 0 && dictBody.startsWith(keyTag, i) -> {
                    val after = dictBody.substring(i + keyTag.length).trimStart()
                    return when {
                        after.startsWith("<true/>") || after.startsWith("<true></true>") -> true
                        after.startsWith("<false/>") || after.startsWith("<false></false>") -> false
                        else -> null
                    }
                }
                else -> i++
            }
        }
        return null
    }
}
