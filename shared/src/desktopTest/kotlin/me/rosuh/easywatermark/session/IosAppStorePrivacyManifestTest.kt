package me.rosuh.easywatermark.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * App Store Connect required-reason + export-compliance source contract.
 *
 * Each dylib that touches UserDefaults / file timestamps / disk space / boot time needs its own
 * `PrivacyInfo.xcprivacy`. The app resource is not inherited by `Shared.framework`.
 *
 * Reason codes follow Apple's NSPrivacyAccessedAPITypeReasons (not the inverted 2024 blog mapping):
 * FileTimestamp `C617.1` = app-container metadata; `3B52.1` = user-granted files;
 * `DDA9.1` = display timestamps. DiskSpace `E174.1` = write-space check; `7D9E.1` = bug report.
 */
class IosAppStorePrivacyManifestTest {

    @Test
    fun appManifest_declaresNoTracking_andFileTimestampsForContainerAndUserGranted() {
        val text = read("iosApp/iosApp/PrivacyInfo.xcprivacy")
        assertFalse(trackingEnabled(text), "app PrivacyInfo must set NSPrivacyTracking=false")
        assertTrue("NSPrivacyAccessedAPICategoryFileTimestamp" in text)
        assertTrue("C617.1" in text, "temp copies live in the app container")
        assertTrue("3B52.1" in text, "PHPicker / incoming files are user-granted")
        assertFalse("DDA9.1" in text, "we do not display file timestamps")
        assertFalse("NSPrivacyAccessedAPICategoryUserDefaults" in text)
        assertFalse("NSPrivacyAccessedAPICategoryDiskSpace" in text)
    }

    @Test
    fun sharedManifest_coversDefaults_containerTimestamps_writeSpace_andCmpTimers() {
        val text = read("shared/PrivacyInfo.xcprivacy")
        assertFalse(trackingEnabled(text), "Shared.framework PrivacyInfo must set NSPrivacyTracking=false")
        assertTrue("NSPrivacyAccessedAPICategoryUserDefaults" in text)
        assertTrue("CA92.1" in text)
        assertTrue("NSPrivacyAccessedAPICategoryFileTimestamp" in text)
        assertTrue("C617.1" in text, "Ready paths / fileExists are app-container metadata")
        assertFalse("0A2A.1" in text, "0A2A.1 is third-party SDK wrapper only; Shared is first-party")
        assertTrue("NSPrivacyAccessedAPICategoryDiskSpace" in text)
        assertTrue("E174.1" in text, "write-space check is E174.1, not 7D9E.1 bug-report")
        assertFalse("7D9E.1" in text)
        assertTrue("NSPrivacyAccessedAPICategorySystemBootTime" in text)
        assertTrue("35F9.1" in text, "CMP / in-app elapsed time is 35F9.1")
    }

    @Test
    fun infoPlist_declaresNonExemptEncryptionFalse_andKeepsFullscreen() {
        val plist = read("iosApp/iosApp/Info.plist")
        assertTrue("<key>ITSAppUsesNonExemptEncryption</key>" in plist)
        val after = plist.substringAfter("<key>ITSAppUsesNonExemptEncryption</key>")
        assertTrue(
            after.trimStart().startsWith("<false/>") || after.trimStart().startsWith("<false></false>"),
            "ITSAppUsesNonExemptEncryption must be false (offline, no custom crypto)",
        )
        assertTrue("<key>UIRequiresFullScreen</key>" in plist)
        assertTrue("easywatermark" !in plist, "do not register a production store-seed URL scheme")
    }

    @Test
    fun xcodeProject_shipsAppPrivacyResource_andCopiesSharedManifestIntoFramework() {
        val pbx = read("iosApp/iosApp.xcodeproj/project.pbxproj")
        assertTrue("PrivacyInfo.xcprivacy in Resources" in pbx)
        assertTrue("INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO" in pbx)
        assertTrue("INFOPLIST_KEY_UIRequiresFullScreen = YES" in pbx)
        assertTrue("embed_shared_privacy_info.sh" in pbx)
        assertFalse(
            Regex("""EW_SKIP_KOTLIN_FRAMEWORK.*\n\s*exit 0""").containsMatchIn(pbx.replace("\\n", "\n")),
            "skipping embedAndSign must still copy Shared.framework PrivacyInfo",
        )
        val embed = read("iosApp/ci_scripts/embed_shared_privacy_info.sh")
        assertTrue("shared/PrivacyInfo.xcprivacy" in embed)
        assertTrue("Shared.framework" in embed)
        assertTrue("copied" in embed && "exit 1" in embed, "must fail if Shared.framework is missing")
    }

    @Test
    fun chineseAddUsageStrings_arePresent() {
        val hans = read("iosApp/iosApp/zh-Hans.lproj/InfoPlist.strings")
        val hant = read("iosApp/iosApp/zh-Hant.lproj/InfoPlist.strings")
        assertTrue("NSPhotoLibraryAddUsageDescription" in hans)
        assertTrue("把加好水印的照片保存到你的相册。" in hans)
        assertTrue("NSPhotoLibraryAddUsageDescription" in hant)
        assertTrue("把加好浮水印的照片儲存到你的相簿。" in hant)
    }

    private fun trackingEnabled(plist: String): Boolean {
        val after = plist.substringAfter("<key>NSPrivacyTracking</key>", missingDelimiterValue = "")
        return after.trimStart().startsWith("<true/>") || after.trimStart().startsWith("<true></true>")
    }

    private fun read(relative: String): String {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("$relative not found from user.dir=$cwd")
        return file.readText()
    }
}
