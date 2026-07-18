package me.rosuh.easywatermark.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.data.db.unpackDefaultTemplateSeed
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.domain.TemplateEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import me.rosuh.easywatermark.render.DesktopWatermarkTextRenderer
import me.rosuh.easywatermark.render.WatermarkGeometry
import me.rosuh.easywatermark.ui.CmpSpikeDesktopWitness
import java.io.File

/**
 * Desktop entry: default launch opens [launchDesktopWindow]; `--headless` runs witnesses and
 * [DesktopWatermarkFlow.runSaveFlow], then exits.
 */
fun main(args: Array<String>) {
    if (args.none { it == "--headless" }) {
        // Default (no-arg) launch -> the Compose Desktop window.
        launchDesktopWindow()
        return
    }
    // --headless -> the console witnesses + the save spine; runs and EXITS (automation path).
    runHeadless(args.filter { it != "--headless" }.toTypedArray())
}

/**
 * The bounded console path ( witnesses + the save spine). Reachable via
 * `--headless`; it runs to completion and returns so the JVM exits (no window).
 *
 * Any non-flag positional args are forwarded as `args[0]` = input image path, `args[1]` = output PNG path.
 */
private fun runHeadless(args: Array<String>) {
    println("EasyWatermark — Desktop (JVM) target, running :shared/commonMain engine core")

    // S-i18n-0: prove composeResources resolve on the real Desktop consumer (not unit-test only).
    CmpSpikeDesktopWitness.printAndCheck()

    // domain types from commonMain
    val format = ImageFormat.fromStorageId(1)
    check(format == ImageFormat.PNG) { "ImageFormat round-trip failed on desktop" }
    println("  output format (from storageId 1): $format")

    // engine geometry from commonMain — a sample 200x80 text cell rotated 315°, hGap/vGap 20%
    val contentW = 200f
    val contentH = 80f
    val degree = 315f
    val cellW = WatermarkGeometry.rotatedCellWidth(contentW, contentH, degree)
    val cellH = WatermarkGeometry.rotatedCellHeight(contentW, contentH, degree)
    val tileW = WatermarkGeometry.horizontalGap(cellW.toInt(), 20)
    val tileH = WatermarkGeometry.verticalGap(cellH.toInt(), 20)
    println("  cell ${contentW.toInt()}x${contentH.toInt()} @${degree.toInt()}° -> AABB ${cellW.toInt()}x${cellH.toInt()} -> tile ${tileW}x${tileH}")

    val r: Result<String> = Result.success("ok")
    check(r.isSuccess())
    println("  shared Result: success=${r.isSuccess()} data=${r.data}")

    // render real watermark text cells via the shared commonMain renderer + bundled font,
    // and write them as PNGs so the Desktop text renderer is demonstrably no longer a test-only helper.
    val outDir = File("build/s4d18-desktop-text").apply { mkdirs() }
    val samples = listOf(
        Triple("latin_0", "GOLDEN", 0f),
        Triple("cjk_0", "请勿转载", 0f),
        Triple("multiline_0", "DO NOT\nREDISTRIBUTE", 0f),
        Triple("ascii_315", "GOLDEN", 315f),
        Triple("cjk_315", "请勿转载", 315f),
    )
    println("Rendering Desktop watermark text samples via composeTextCell (bundled Latin+CJK):")
    for ((name, text, deg) in samples) {
        val cell = DesktopWatermarkTextRenderer.renderTextCellResult(text, degree = deg)
        val file = File(outDir, "$name.png").apply { writeBytes(cell.png) }
        println("  $name: ${cell.width}x${cell.height} -> ${file.path} (${cell.png.size} B)")
    }

    // compose FULL watermarked sample images over a generated background through the shared
    // commonMain WatermarkCellComposer.composeOverBackground — REPEAT (tiled) and CLAMP (single decal).
    val composeDir = File("build/s4d19-desktop-watermark").apply { mkdirs() }
    val watermarkText = "请勿转载\nDO NOT REDISTRIBUTE"
    println("Composing Desktop watermarked sample images (640x480) via composeOverBackground:")
    val composed = listOf(
        "repeat_watermark" to DesktopWatermarkComposer.composeSampleResult(
            text = watermarkText, bgWidth = 640, bgHeight = 480, tileMode = WatermarkTileMode.REPEAT,
        ),
        "clamp_watermark" to DesktopWatermarkComposer.composeSampleResult(
            text = watermarkText, bgWidth = 640, bgHeight = 480, tileMode = WatermarkTileMode.CLAMP,
            offsetX = 0.5f, offsetY = 0.5f,
        ),
    )
    for ((name, img) in composed) {
        val file = File(composeDir, "$name.png").apply { writeBytes(img.png) }
        println("  $name: ${img.width}x${img.height} -> ${file.path} (${img.png.size} B)")
    }

    // REAL-image decode path. Produce a deterministic PNG fixture, write it, then DECODE it back
    // through AWT/ImageIO (DesktopImageDecoder) and watermark the decoded image — the realistic Desktop
    // pipeline: decode (platform) -> render cell + compose (commonMain) -> encode (platform). No binary asset.
    val realDir = File("build/s4d20-desktop-real-image").apply { mkdirs() }
    val fixturePng = DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480)
    File(realDir, "source_fixture.png").writeBytes(fixturePng)
    println("Desktop real-image decode (ImageIO) + watermark:")
    println("  source_fixture.png: ${fixturePng.size} B -> ${File(realDir, "source_fixture.png").path}")
    val realWatermarked = DesktopWatermarkComposer.composeOverRealImage(
        imageBytes = fixturePng, text = watermarkText, tileMode = WatermarkTileMode.REPEAT,
    )
    val realFile = File(realDir, "real_image_watermark.png").apply { writeBytes(realWatermarked.png) }
    println("  real_image_watermark.png: ${realWatermarked.width}x${realWatermarked.height} -> ${realFile.path} (${realWatermarked.png.size} B)")

    // first APP-ENTRY construction of the common `UserConfigRepository` over the Desktop
    // DataStore factory — read -> update -> read against a real on-disk preferences store. This is an
    // app-level smoke/witness (the read/write roundtrip itself is already gated by `:shared:desktopTest`).
    // Uses a repo-local build dir (NOT ~/.easywatermark) so the smoke leaves no state in the user's home.
    val userConfigDir = File("build/s4d80-desktop-userconfig")
    val userRepo = UserConfigRepository(createUserConfigDataStore(dir = userConfigDir))
    println("Desktop UserConfigRepository (store dir: ${userConfigDir.path}):")
    runBlocking {
        println("  userPreferences (initial): ${userRepo.userPreferences.first()}")
        userRepo.updateFormat(ImageFormat.PNG)
        userRepo.updateCompressLevel(60)
        println("  userPreferences (after update): ${userRepo.userPreferences.first()}")
    }

    // save spine, extracted to DesktopWatermarkFlow so the Compose window reuses it.
    // A real common WaterMarkRepository persists the watermark config in a desktop DataStore; the shared
    // WatermarkConfigEditor edits it; the persisted WaterMark drives composeOverRealImage over a real
    // (ImageIO-decoded) input; the watermarked PNG is written to disk.
    val inputPath = args.getOrNull(0)
    val inputBytes = inputPath?.let { File(it).readBytes() }
    val inputLabel = inputPath ?: "<generated 640x480 fixture>"
    // an explicit CLI output path wins; otherwise null lets the flow pick the format-aware default.
    val outputFile = args.getOrNull(1)?.let { File(it) }
    val waterMarkRepo = DesktopWatermarkFlow.buildRepository()
    val configEditor = WatermarkConfigEditor(waterMarkRepo)
    // the output prefs the flow reads (empty store -> the shared (JPEG, 80) default).
    val saveFlowUserConfig = DesktopWatermarkFlow.buildUserConfigRepository()
    println("Desktop headless config-driven save flow (S4d-120):")
    val outcome = runBlocking {
        // the demo text/degree now live in the witness — runSaveFlow no longer forces them (so the
        // window can render user-set text), so set them here to keep --headless output deterministic.
        configEditor.updateText("请勿转载 DO NOT REDISTRIBUTE")
        configEditor.updateDegree(330f)
        DesktopWatermarkFlow.runSaveFlow(waterMarkRepo, configEditor, saveFlowUserConfig, inputBytes, inputLabel, outputFile)
    }
    println("  config (initial): ${outcome.configInitial}")
    println("  config (after edit, persisted): ${outcome.configAfterEdit}")
    println("  input:  ${outcome.inputLabel} (${outcome.inputByteCount} B)")
    println("  output: ${outcome.outputPath} (${outcome.format}, ${outcome.width}x${outcome.height}, ${outcome.outputByteCount} B)")

    // Image-mode headless witness (no picker). Persist a generated icon as a Desktop FILE PATH
    // (MediaRef) and render through the new icon branch -> composeIconOverRealImage. Uses a
    // SEPARATE watermark-config store dir so it can never flip the text witness's store to Image mode on a
    // later run (DataStore is single-instance-per-file; different files are independent).
    val iconFlowDir = File("build/s4d134-desktop-icon-flow").apply { mkdirs() }
    val iconFile = File(iconFlowDir, "icon.png").apply {
        writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = 64, height = 64))
    }
    val iconRepo = DesktopWatermarkFlow.buildRepository(dir = File(iconFlowDir, "config"))
    val iconEditor = WatermarkConfigEditor(iconRepo)
    // prefs default to (JPEG, 80) from the empty userconfig store, so a .jpg name matches; the printed
    // format is the source of truth either way.
    val iconOutputFile = File(iconFlowDir, "image_mode_watermarked.jpg")
    println("Desktop headless Image-mode save flow (S4d-134):")
    val iconOutcome = runBlocking {
        // updateIcon persists the icon path AND flips persisted markMode to Image.
        iconEditor.updateIcon(MediaRef(iconFile.absolutePath))
        DesktopWatermarkFlow.runSaveFlow(
            iconRepo, iconEditor, saveFlowUserConfig, inputBytes, inputLabel, iconOutputFile,
        )
    }
    println("  config (rendered): ${iconOutcome.configAfterEdit}")
    println("  icon:   ${iconFile.path} (${iconFile.length()} B)")
    println("  output: ${iconOutcome.outputPath} (${iconOutcome.format}, ${iconOutcome.width}x${iconOutcome.height}, ${iconOutcome.outputByteCount} B)")

    // prove the loud-fail contract — Image mode with a MISSING icon file must THROW, not silently
    // render text. Own store dir; the bad path is never created, so no output is written.
    val missingRepo = DesktopWatermarkFlow.buildRepository(dir = File(iconFlowDir, "config-missing"))
    val missingEditor = WatermarkConfigEditor(missingRepo)
    val missingIconPath = File(iconFlowDir, "does-not-exist.png").absolutePath
    print("Desktop headless Image-mode missing-icon guard (S4d-134): ")
    runBlocking {
        missingEditor.updateIcon(MediaRef(missingIconPath)) // mode=Image, icon points at a non-existent file
        try {
            DesktopWatermarkFlow.runSaveFlow(
                missingRepo, missingEditor, saveFlowUserConfig, inputBytes, inputLabel,
                File(iconFlowDir, "missing_icon_should_not_be_written.jpg"),
            )
            // Reaching here means Image mode did NOT throw on a missing icon → the guard regressed
            // (a silent fallback). HARD-fail the headless run so the gate catches it (error() throws an
            // IllegalStateException, which the IllegalArgumentException catch below does NOT swallow).
            error("S4d-134 guard regressed: Image-mode missing icon must throw, but render succeeded")
        } catch (e: IllegalArgumentException) {
            println("OK, threw as expected: ${e.message}")
        }
    }

    // / / : Desktop templates headless witness — the first :desktopApp consumer of
    // the Desktop template DB builder (bundled SQLite driver) + commonMain TemplateRepository/
    // TemplateEditor. Builds a fresh SEEDED repo-local DB via the shared desktopMain seed resource,
    // verifies the seeded templates, then add → list → delete roundtrips one more template and ends empty.
    // No UI. The witness directory is deleted before each run so the seeded-first-creation path is exercised
    // repeatedly. The seed language is selected by JVM locale (Chinese for `zh`, English otherwise).
    val templatesDir = File("build/s4d143a-desktop-templates").apply {
        deleteRecursively()
        mkdirs()
    }
    val seedFile = File(templatesDir, "seed-ewm-db-default.db")
    unpackDefaultTemplateSeed(seedFile)
    val templateDb = buildTemplateDatabase(templatesDir, seedFile)
    val templateRepo = TemplateRepository(templateDb.templateDao(), Dispatchers.IO)
    val templateEditor = TemplateEditor(templateRepo)
    println("Desktop headless templates witness (S4d-143a/S4d-224) [store dir: ${templatesDir.path}]:")
    runBlocking {
        val seeded = templateRepo.getAllTemplate().first()
        println("  daoNull=${templateRepo.checkIfIsDaoNull()} seeded count=${seeded.size} first='${seeded.firstOrNull()?.content}'")
        templateEditor.add("S4d-143a desktop template")
        val afterAdd = templateRepo.getAllTemplate().first()
        println("  after add: count=${afterAdd.size} content='${afterAdd.firstOrNull()?.content}' id=${afterAdd.firstOrNull()?.id}")
        afterAdd.forEach { templateEditor.delete(it) }
        println("  after delete: count=${templateRepo.getAllTemplate().first().size} (final empty)")
    }
    templateDb.close()

    println("OK — shared KMP engine core + Desktop text renderer + Desktop composition + real-image decode run on Desktop.")
}
