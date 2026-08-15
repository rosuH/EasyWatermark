package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue 26 / C4.4R.S1 — fail-closed **source contracts** for the iOS PhotosPicker identity edge.
 *
 * Structural evidence only (no Simulator gestures). Runtime PHPicker acceptance remains a separate
 * Coordinator-owned R1/R2 rerun after this code contract is reviewed.
 */
class IosPhotosPickerIdentityContractTest {

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

    private fun stripSwiftComments(source: String): String {
        val noBlock = source.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    @Test
    fun s1_both_picker_edges_request_current_encoding() {
        val contentView = resolveRepoFile("iosApp/iosApp/ContentView.swift").readText()
        val pickerHost = resolveRepoFile("iosApp/iosApp/PhotoLibraryPHPicker.swift").readText()
        val code = stripSwiftComments(contentView)
        val pickerCode = stripSwiftComments(pickerHost)

        assertTrue(
            "PHPickerViewController" in pickerCode &&
                "PHPickerConfiguration(photoLibrary: .shared())" in pickerCode,
            "Main-photo picker must be UIKit PHPickerViewController with photoLibrary: .shared()",
        )
        assertTrue(
            Regex("""preferredAssetRepresentationMode\s*=\s*\.current""").containsMatchIn(pickerCode),
            "Main-photo PHPicker must set preferredAssetRepresentationMode = .current",
        )
        assertTrue(
            "preselectedAssetIdentifiers" in pickerCode,
            "Main-photo PHPicker must bind PHPickerConfiguration.preselectedAssetIdentifiers",
        )
        assertTrue(
            Regex("""selectionLimit\s*=\s*50""").containsMatchIn(pickerCode),
            "Main-photo PHPicker selectionLimit must stay 50",
        )
        assertFalse(
            Regex("""\.photosPicker\s*\([\s\S]*maxSelectionCount\s*:\s*50""").containsMatchIn(code),
            "Main-photo SwiftUI .photosPicker must not remain; owner A replaced it with UIKit PHPicker",
        )

        val currentEncodingHits = Regex("""preferredItemEncoding\s*:\s*\.current""")
            .findAll(code)
            .count()
        assertEquals(
            1,
            currentEncodingHits,
            "Icon .photosPicker must set preferredItemEncoding: .current " +
                "(found $currentEncodingHits). Silent return to automatic encoding is a fail-closed " +
                "regression for issue 26 H1.",
        )

        // Fail if a photosPicker block still uses only the automatic default on the iOS 17 path:
        // require the iOS 17 branch to exist and carry .current (icon picker).
        assertTrue(
            Regex("""#available\s*\(\s*iOS\s+17\.0""").containsMatchIn(code),
            "ContentView must gate icon preferredItemEncoding behind iOS 17 availability " +
                "(deployment target remains 16).",
        )
        assertTrue(
            Regex("""isIconPickerPresented""").containsMatchIn(code) &&
                Regex("""\.photosPicker\s*\(""").containsMatchIn(code),
            "Icon picker must remain SwiftUI .photosPicker",
        )
    }

    @Test
    fun s2_contentView_uses_serial_commit_gate_for_photos_and_icon() {
        val contentView = resolveRepoFile("iosApp/iosApp/ContentView.swift").readText()
        val gate = resolveRepoFile("iosApp/iosApp/PhotosPickerBatchGate.swift").readText()
        val contentCode = stripSwiftComments(contentView)
        val gateCode = stripSwiftComments(gate)

        assertTrue(
            "enum PhotosPickerBatchGate" in gateCode ||
                Regex("""enum\s+PhotosPickerBatchGate""").containsMatchIn(gateCode),
            "PhotosPickerBatchGate must exist as a pure Swift helper",
        )
        assertTrue(
            Regex("""func\s+beginGeneration\s*\(""").containsMatchIn(gateCode),
            "gate must expose beginGeneration",
        )
        assertTrue(
            Regex("""func\s+shouldDeliver\s*\(""").containsMatchIn(gateCode),
            "gate must expose shouldDeliver",
        )
        assertTrue(
            Regex("""func\s+shouldBeginCommit\s*\(""").containsMatchIn(gateCode),
            "gate must expose shouldBeginCommit (review F1)",
        )
        assertTrue(
            Regex("""@MainActor[\s\S]*class\s+PhotosPickerCommitSerial""").containsMatchIn(gateCode) ||
                Regex("""final\s+class\s+PhotosPickerCommitSerial""").containsMatchIn(gateCode),
            "PhotosPickerCommitSerial must be MainActor class (not reentrant actor-only body)",
        )
        assertTrue(
            "commitTail" in gateCode,
            "commit serial must keep a FIFO commitTail so bodies do not reenter",
        )
        assertTrue(
            Regex("""func\s+commitIfNewest\s*\(""").containsMatchIn(gateCode),
            "commit serial must expose commitIfNewest",
        )
        assertTrue(
            "candidate == latest && candidate > highestPublished" in gateCode ||
                Regex(
                    """candidate\s*==\s*latest\s*&&\s*candidate\s*>\s*highestPublished""",
                ).containsMatchIn(gateCode),
            "shouldBeginCommit must require newest + not yet published",
        )
        assertTrue(
            "inFlight?.cancel()" in gateCode || "inFlight?.cancel" in gateCode,
            "F9: beginGeneration must cancel in-flight older commit",
        )
        assertTrue(
            "isCurrent" in gateCode,
            "F9: serial must expose isCurrent for pre-publish checks",
        )
        assertTrue(
            "previous?.result" in gateCode || "await previous" in gateCode,
            "commitIfNewest must await previous commit fully before revalidate/body",
        )

        assertTrue(
            "photoCommitSerial" in contentCode && "iconCommitSerial" in contentCode,
            "ContentView must own photo and icon commit serial lanes",
        )
        assertTrue(
            "commitIfNewest" in contentCode,
            "ContentView must stage via commitIfNewest (not check-then-await alone)",
        )
        // F6: generation frozen at selection onChange, not only inside loadPhotos Task.
        assertTrue(
            "handleMainPhotoPickerFinish" in contentCode,
            "main-photo PHPicker finish handler must exist",
        )
        val finishBlock = contentCode.substringAfter("private func handleMainPhotoPickerFinish")
            .substringBefore("private func applyMainPhotoPickerDiff")
        assertTrue(
            "photoCommitSerial.beginGeneration" in finishBlock,
            "F6: beginGeneration must run synchronously when the main PHPicker finishes",
        )
        assertTrue(
            "oldSet == newSet" in finishBlock ||
                Regex("""oldSet\s*==\s*newSet""").containsMatchIn(finishBlock),
            "unchanged PHPicker identifier set (cancel / same selection) must be a no-op",
        )
        assertTrue(
            "nextPhotoGeneration" in gateCode || "IosPickGenerationGate" in gateCode,
            "F14: beginGeneration must issue tokens from Kotlin IosPickGenerationGate",
        )
        assertTrue(
            "generation:" in finishBlock || "generation =" in finishBlock ||
                "generation: generation" in contentCode,
            "F6: frozen generation must be passed into loadPhotos",
        )
        assertTrue(
            "onChange(of: pickedIconItem)" in contentCode &&
                "iconCommitSerial.beginGeneration" in contentCode,
            "F6: icon generation frozen at icon selection onChange",
        )

        val commitGuards = Regex("""commitIfNewest""")
            .findAll(contentCode)
            .count()
        assertTrue(
            commitGuards >= 2,
            "expected commitIfNewest on both photo and icon load paths; found $commitGuards",
        )

        // Progressive path-first photo deliver sits inside serial commit (ordering smoke).
        val photoDeliverIdx = contentCode.indexOf("photoImportCoordinator.importBatch")
        val photoCommitIdx = contentCode.indexOf("photoCommitSerial.commitIfNewest")
        assertTrue(
            photoCommitIdx >= 0 && photoDeliverIdx > photoCommitIdx,
            "photoImportCoordinator.importBatch must run only inside photoCommitSerial.commitIfNewest",
        )
        assertTrue(
            "PhotoImportCoordinator" in contentCode || "photoImportCoordinator" in contentCode,
            "ContentView must own a PhotoImportCoordinator for path-first progressive import",
        )
        assertTrue(
            "ImageFileTransfer" in resolveRepoFile("iosApp/iosApp/PhotoImportCoordinator.swift").readText() ||
                "FileRepresentation" in resolveRepoFile("iosApp/iosApp/PhotoImportCoordinator.swift").readText(),
            "PhotoImportCoordinator must use FileRepresentation path transfer",
        )
        assertFalse(
            Regex("""print\s*\(\s*".*(hash|SHA|filename|localIdentifier)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(contentCode),
            "no release telemetry of user media identity in ContentView",
        )
    }

    @Test
    fun pbxproj_includes_photos_picker_batch_gate() {
        val pbx = resolveRepoFile("iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(
            "PhotosPickerBatchGate.swift in Sources" in pbx,
            "Xcode target must compile PhotosPickerBatchGate.swift",
        )
        assertTrue(
            "PhotoImportCoordinator.swift in Sources" in pbx,
            "Xcode target must compile PhotoImportCoordinator.swift",
        )
        assertTrue(
            "ProgressiveImportNotifications.swift in Sources" in pbx,
            "Xcode target must compile ProgressiveImportNotifications.swift",
        )
        assertTrue(
            "PhotoLibraryPHPicker.swift in Sources" in pbx,
            "Xcode target must compile PhotoLibraryPHPicker.swift",
        )
        assertTrue(
            "path = PhotosPickerBatchGate.swift" in pbx,
            "pbxproj must reference PhotosPickerBatchGate.swift",
        )
        assertTrue(
            "path = PhotoLibraryPHPicker.swift" in pbx,
            "pbxproj must reference PhotoLibraryPHPicker.swift",
        )
    }

    @Test
    fun k3_staging_writes_uuid_paths_via_ios_source_stager() {
        val stager = resolveRepoFile(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/session/IosSourceStager.kt",
        ).readText()
        val services = resolveRepoFile(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/session/IosAppServices.kt",
        ).readText()
        val stagerCode = stripSwiftComments(stager)
        val servicesCode = stripSwiftComments(services)
        assertTrue("ewm_src_" in stagerCode && "NSUUID()" in stagerCode,
            "IosSourceStager must mint ewm_src_ + UUID paths")
        assertTrue("writeToFile" in stagerCode && "atomically" in stagerCode,
            "staging must write atomically to the minted path")
        assertTrue("IosSourceStager.stageBytes" in servicesCode,
            "IosAppServices.stagePickedImagesBytes must delegate path identity to IosSourceStager")
        assertTrue("focusAfterPick" in servicesCode,
            "IosAppServices must use ProductShellNav.focusAfterPick for append focus")
    }
}
