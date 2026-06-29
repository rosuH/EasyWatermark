package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.data.db.unpackDefaultTemplateSeed
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.DesktopIconPersistence
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.TemplateEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopImageDecoder
import me.rosuh.easywatermark.render.DesktopSaveDecision
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File

/** Best-effort Open-dialog filename filter (honored on macOS; ignored on some platforms — harmless). */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

/** Short label for the current output preference, e.g. "JPEG / 80". */
private fun describePref(p: UserPreferences): String = "${p.outputFormat} / ${p.compressLevel}"

/**
 * S4d-158 / S4d-228: drop-target file extraction. [hasFileList] is the cheap drag-over predicate (flavor
 * only); [supportedImageFiles] does the real extraction on drop — reads the dropped file list and returns
 * ALL files whose extension is in [IMAGE_EXTENSIONS] (order preserved), via the pure, unit-tested
 * [DesktopSaveDecision.supportedImageFiles]. Both swallow AWT failures → false/empty (soft-fail, never
 * crash). The AWT file-list flavor is the desktop drag-drop interop; commonMain is untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun hasFileList(event: DragAndDropEvent): Boolean = try {
    event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
} catch (t: Throwable) {
    false
}

@OptIn(ExperimentalComposeUiApi::class)
private fun supportedImageFiles(event: DragAndDropEvent): List<File> = try {
    val transferable = event.awtTransferable
    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
        DesktopSaveDecision.supportedImageFiles(files, IMAGE_EXTENSIONS)
    } else {
        emptyList()
    }
} catch (t: Throwable) {
    emptyList()
}

/** Stable UI label for a [TextTypeface] — explicit map (no reflection / object-name dependency). */
private fun typefaceLabelOf(t: TextTypeface): String = when (t) {
    TextTypeface.Normal -> "Normal"
    TextTypeface.Italic -> "Italic"
    TextTypeface.Bold -> "Bold"
    TextTypeface.BoldItalic -> "BoldItalic"
}

/** Stable UI label for a [TextPaintStyle] — explicit map (no reflection / object-name dependency). */
private fun styleLabelOf(s: TextPaintStyle): String = when (s) {
    TextPaintStyle.Fill -> "Fill"
    TextPaintStyle.Stroke -> "Stroke"
}

private class LastImage(val bytes: ByteArray, val label: String)

/**
 * S4d-215: the stable per-user app-data dir the interactive Desktop window persists into —
 * `~/.easywatermark` (matching the `CreateDataStore.desktop.kt` store-creation convention), NOT the
 * repo-local `build/` dev paths the headless/demo witness (`Main.kt`) deliberately uses. If
 * `user.home` is unavailable, fall back to a repo-local `build/` dir and warn instead of crashing.
 * The dir is created if missing.
 */
private fun resolveDesktopAppDataDir(): File {
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
    return if (home != null) {
        File(home, ".easywatermark")
    } else {
        System.err.println(
            "S4d-215: user.home unavailable; persisting window state under repo-local build/desktop-app-data"
        )
        File("build/desktop-app-data")
    }.apply { mkdirs() }
}

/**
 * S4d-217: the user-facing output dir for the interactive window's REAL saves (drop / Render & Save /
 * Open image) — `~/Pictures` when it exists, else `~/.easywatermark/output` (reusing the S4d-215
 * app-data dir). Saved watermarked images must not land in the repo-local `build/` dir. The
 * headless/demo witness (`Main.kt`), the `DesktopWatermarkFlow` default `outputDir`, and the preview
 * temp deliberately stay build-local — NOT routed here. (Full per-OS XDG/AppData handling is a
 * separate deferred refinement.) The dir is created if missing.
 */
private fun resolveDesktopOutputDir(): File {
    val pictures = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it, "Pictures") }
    return (pictures?.takeIf { it.isDirectory } ?: File(resolveDesktopAppDataDir(), "output"))
        .apply { mkdirs() }
}

/**
 * S4d-121: the smallest useful **Compose Desktop window** over the S4d-120 save spine. A no-arg
 * `:desktopApp` launch opens this window (`Main.kt` dispatches); the `--headless` flag keeps a bounded
 * console automation path that exits.
 *
 * Honest, not faked: the "Render & Save sample" button runs the SAME shared spine the headless path uses
 * ([DesktopWatermarkFlow.runSaveFlow] → common `WaterMarkRepository` + `WatermarkConfigEditor` persist a
 * config edit, then `DesktopWatermarkComposer.composeOverRealImage` renders the deterministic fixture and
 * writes an image) and shows the persisted config + output path/dims/size. It honors text color, typeface,
 * and paint style. S4d-125: an "Open image…" button picks a real file via a native AWT [FileDialog] and runs
 * the SAME spine over those bytes. S4d-130: two output-preference presets (JPEG/80, PNG/100) persist through
 * the shared `OutputPrefsEditor`, so the save flow encodes in the chosen format. S4d-135: an "Open icon…"
 * button persists a picked icon path via `WatermarkConfigEditor.updateIcon` (flipping persisted mode to
 * Image), so the existing Render/Open-image saves then render through the S4d-134 Image branch. S4d-136: a
 * "Use text watermark" button flips persisted mode back to Text (via `WatermarkConfigEditor.updateText`,
 * preserving the current text), so Image mode is not one-way. S4d-137: the window remembers the last
 * "Open image…" selection (bytes + label), so "Render & Save sample" composites over that real photo
 * instead of the fixture (null → fixture, as before). S4d-140: a "Save as…" button opens a native AWT
 * SAVE dialog and passes the chosen path as `runSaveFlow(outputFile = …)`, so saves can land outside the
 * fixed `build/` default (same render decision; destination-only). S4d-145: a "Watermark text" field +
 * "Apply text" button persist user text via `WatermarkConfigEditor.updateText`, and `runSaveFlow` no longer
 * forces a demo string — so the window finally renders user-chosen text (the first real edit control).
 * S4d-147: a "Preview" button renders the current config through `runSaveFlow` to a repo-local temp file,
 * decodes the bytes (`DesktopImageDecoder`, generic JPEG/PNG), and shows the result on-screen — ending the
 * blind-edit loop. S4d-148: an "Apply degree" field edits rotation (`updateDegree`, 0..360). S4d-149: an
 * "Apply color" field edits the text color (hex, via `WatermarkConfigEditor.updateTextColor`). S4d-150: an
 * "Apply opacity" field edits the alpha as a 0..100 percent (via `WatermarkConfigEditor.updateAlpha`). S4d-151:
 * "Apply gaps" fields edit the horizontal/vertical gaps (0..500) atomically (via `updateHorizon`/`updateVertical`).
 * S4d-152: an "Apply text size" field edits the text size (1..100, via `updateTextSize`). S4d-153: two buttons
 * persist the tile mode REPEAT (grid tile) / CLAMP (single decal) via `updateTileMode` (MIRROR/DECAL not exposed).
 * S4d-154: per-value buttons persist the typeface (Normal/Italic/Bold/BoldItalic) and text style (Fill/Stroke) via
 * `updateTextTypeface`/`updateTextStyle`. S4d-155: the main content `Column` is `verticalScroll`-able so the
 * growing control surface + preview stay reachable on constrained window heights (control order/behavior
 * unchanged). S4d-157: a "share substitute" — "Show in folder" (guarded `java.awt.Desktop.open(parentFile)`)
 * + "Copy output path" (Compose `LocalClipboardManager`) acting on the last REAL saved output file (set by
 * the Render & Save / Save as… / Open image… success paths, NOT Preview). S4d-158: dropping an image file
 * onto the window loads it through the same Open-image save spine (`Modifier.dragAndDropTarget` + the AWT
 * file-list flavor; updates `lastImage` + `lastSavedFile`; unsupported/empty/while-busy drops fail softly).
 * S4d-160: a minimal "Templates" section over the shared Desktop Room path saves the current watermark
 * text, lists saved templates, and applies (Use → `WatermarkConfigEditor.updateText`), updates in place
 * (Update → `TemplateEditor.update`), or deletes them.
 * S4d-198: REACTIVE preview — every successful explicit editor action (Apply text/degree/color/opacity/
 * gaps/text-size, the tile/typeface/style buttons, "Use text watermark", and template "Use") now
 * auto-refreshes the on-screen preview through the SAME `refreshPreview()` → `runSaveFlow` temp-file spine
 * the manual "Preview" button uses (bounded to explicit clicks, NOT per keystroke). Preview stays a
 * temp render: it never sets `lastSavedFile`, so the share-substitute buttons remain bound to real saves;
 * a preview-refresh failure keeps the last good preview and reports it in the status. S4d-198-r1: the
 * source-change actions "Open image…" and image **drop** ALSO auto-refresh the preview over the just-loaded
 * image (after their real save sets `lastImage`/`lastSavedFile`; the extra refresh still writes only the temp
 * file). "Render & Save sample" / "Save as…" remain real-save-only (they don't change the source/config).
 */
fun launchDesktopWindow() = application {
    // S4d-215: persist the window's user state (watermark config, output prefs, templates DB) under the
    // stable per-user app-data dir ~/.easywatermark (the CreateDataStore.desktop.kt convention), NOT the
    // repo-local build/ dev paths — those stay the intentional headless/demo witness layout (Main.kt +
    // DesktopWatermarkFlow defaults). All three persistence files (the two DataStores named by their
    // SP_NAME + the Room "ewm-db") share this dir; distinct filenames, no collision.
    val appDataDir = remember { resolveDesktopAppDataDir() }
    // S4d-217: where the window's REAL saves (drop / Render & Save / Open image) write — a user dir, not
    // the repo-local build/ default. "Save as…" still uses its chosen path; the preview temp + headless
    // witness stay build-local.
    val outputDir = remember { resolveDesktopOutputDir() }
    // ONE repository + editor for the window's lifetime (DataStore forbids a second active store per file).
    val repo = remember { DesktopWatermarkFlow.buildRepository(dir = appDataDir) }
    val editor = remember { WatermarkConfigEditor(repo) }
    // S4d-128: the output-prefs repo the save flow reads (empty store → the shared (JPEG, 80) default).
    val userConfigRepo = remember { DesktopWatermarkFlow.buildUserConfigRepository(dir = appDataDir) }
    // S4d-130: the shared output-prefs write use-case over the SAME store the save flow reads.
    val outputEditor = remember { OutputPrefsEditor(userConfigRepo) }
    // S4d-160/S4d-215/S4d-224/S4d-225: the Desktop templates Room DB (commonMain Room via the desktopMain
    // BundledSQLiteDriver builder), now under the stable app-data dir and seeded from the shared desktopMain
    // seed resource on first creation (Chinese for `zh` locales, English otherwise). Room is
    // single-instance-per-file; the process exits on window close, releasing the DB.
    val templateDb = remember {
        val seedFile = File(appDataDir, "seed-ewm-db-default.db").also { unpackDefaultTemplateSeed(it) }
        buildTemplateDatabase(appDataDir, seedFile)
    }
    val templateRepo = remember { TemplateRepository(templateDb.templateDao(), Dispatchers.IO) }
    val templateEditor = remember { TemplateEditor(templateRepo) }
    // Collect the saved templates into state (remember the Flow so collection is stable across recompositions).
    val templates by remember { templateRepo.getAllTemplate() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf("Ready. Click “Render & Save sample” to run the shared save flow.")
    }
    var busy by remember { mutableStateOf(false) }
    var lastImage by remember { mutableStateOf<LastImage?>(null) }
    // S4d-157: the last REAL saved output file (set only by the Render & Save / Save as… / Open image…
    // success paths — NOT Preview, which writes a temp file). Drives the share-substitute buttons.
    var lastSavedFile by remember { mutableStateOf<File?>(null) }
    // S4d-130: the current/effective output preference, loaded on launch + refreshed after each preset save.
    var outputPref by remember { mutableStateOf("loading…") }
    // S4d-145: the watermark text being edited; loaded from the persisted config on launch, persisted on Apply.
    var watermarkText by remember { mutableStateOf("") }
    // S4d-147: the rendered preview image (null until the first "Preview" click).
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    // S4d-148: the watermark rotation degree being edited (string; parsed on "Apply degree"). Loaded on launch.
    var degreeText by remember { mutableStateOf("") }
    // S4d-149: the watermark text COLOR being edited (hex string; parsed on "Apply color"). Loaded on launch.
    var colorText by remember { mutableStateOf("") }
    // S4d-150: the watermark opacity (alpha) being edited as a 0..100 percent (parsed on "Apply opacity").
    var alphaText by remember { mutableStateOf("") }
    // S4d-151: the horizontal/vertical watermark GAPS being edited (parsed together on "Apply gaps").
    var hGapText by remember { mutableStateOf("") }
    var vGapText by remember { mutableStateOf("") }
    // S4d-152: the watermark TEXT SIZE being edited (string; parsed on "Apply text size"). Loaded on launch.
    var textSizeText by remember { mutableStateOf("") }
    // S4d-153: the current persisted tile mode label (REPEAT grid-tile vs CLAMP single-decal). Loaded on launch.
    var tileModeLabel by remember { mutableStateOf("loading…") }
    // S4d-154: the current persisted typeface + text-style labels (explicit label maps). Loaded on launch.
    var typefaceLabel by remember { mutableStateOf("loading…") }
    var styleLabel by remember { mutableStateOf("loading…") }
    LaunchedEffect(Unit) {
        outputPref = describePref(userConfigRepo.userPreferences.first())
        watermarkText = repo.waterMark.first().text
        degreeText = repo.waterMark.first().degree.toString()
        colorText = "#%08X".format(repo.waterMark.first().textColor)
        // Persisted alpha is a 0..255 byte; display as a percent (Android editor semantics).
        // S4d-179: shared WatermarkConfigRules.alphaByteToPercent (Android baseline order); the displayed
        // value may differ from the old `alpha * 100f / 255f` by a final float ULP (display only).
        alphaText = WatermarkConfigRules.alphaByteToPercent(repo.waterMark.first().alpha).toString()
        // S4d-151: load the persisted horizontal/vertical gaps into the editable fields.
        hGapText = repo.waterMark.first().hGap.toString()
        vGapText = repo.waterMark.first().vGap.toString()
        // S4d-152: load the persisted text size into the editable field.
        textSizeText = repo.waterMark.first().textSize.toString()
        // S4d-153: load the persisted tile mode (only REPEAT/CLAMP are exposed in the UI).
        tileModeLabel = repo.waterMark.first().tileMode.name
        // S4d-154: load the persisted typeface + text style (explicit label maps — no reflection).
        typefaceLabel = typefaceLabelOf(repo.waterMark.first().textTypeface)
        styleLabel = styleLabelOf(repo.waterMark.first().textStyle)
    }

    // S4d-198: reactive preview. Render the CURRENT persisted config over the remembered image (or the
    // deterministic fixture) through the SAME DesktopWatermarkFlow.runSaveFlow spine the manual "Preview"
    // button uses, decode the bytes (DesktopImageDecoder, generic JPEG/PNG), and update the on-screen
    // `preview`. Writes ONLY the repo-local temp preview path and never sets `lastSavedFile` — a preview is
    // NOT a real save (the share-substitute buttons stay bound to real saves). Keeps the last good preview on
    // failure (only replaces it on a successful decode). Heavy render+decode runs off the EDT inside
    // withContext(IO); the Compose `preview` state is set after, on the caller's UI dispatcher. Returns a
    // short status line. Callers invoke this inside their own `busy = true … busy = false` span (after a
    // successful explicit edit/mode/source change, or from the manual Preview button), so renders stay
    // serialized. Defined before the drop target so the S4d-198-r1 drop refresh can call it.
    suspend fun refreshPreview(): String {
        val current = lastImage
        val previewFile = File("build/s4d147-desktop-preview/preview.img").apply { parentFile?.mkdirs() }
        val (img, msg) = withContext(Dispatchers.IO) {
            try {
                val o = if (current != null) {
                    DesktopWatermarkFlow.runSaveFlow(
                        repo, editor, userConfigRepo,
                        inputBytes = current.bytes, inputLabel = current.label, outputFile = previewFile,
                    )
                } else {
                    DesktopWatermarkFlow.runSaveFlow(repo, editor, userConfigRepo, outputFile = previewFile)
                }
                DesktopImageDecoder.decode(previewFile.readBytes()) to
                    "Preview: ${o.format}, ${o.width}x${o.height} (${o.outputByteCount} B)"
            } catch (t: Throwable) {
                null to "Preview refresh failed (kept last preview): ${t.message}"
            }
        }
        // Only replace the visible preview on a successful decode (keep the last good one on failure).
        img?.let { preview = it }
        return msg
    }

    // S4d-158: drop image file(s) onto the window to load them through the SAME save spine as "Open image…".
    // S4d-228: a multi-file drop now watermarks and saves EVERY supported dropped image (was first-only),
    // sequentially, to the user output dir with collision-free names. onDrop runs on the Compose UI thread,
    // so it reads/sets state directly and launches the heavy render loop on `scope`. A drop while busy or a
    // drop with no supported image fails softly with a status (no crash). Remembered so the target identity
    // is stable.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (busy) {
                    status = "Busy — wait for the current render before dropping another image."
                    return false
                }
                // S4d-228: take ALL supported dropped images (pure DesktopSaveDecision.supportedImageFiles).
                val files = supportedImageFiles(event)
                if (files.isEmpty()) {
                    status = "Unsupported drop — no supported image files in drop (${IMAGE_EXTENSIONS.joinToString(", ")})."
                    return false
                }
                scope.launch {
                    busy = true
                    status = "Rendering ${files.size} image(s)…"
                    // Remember the LAST successful image/output (for reuse + the share-substitute buttons).
                    var lastPicked: LastImage? = null
                    var lastSaved: File? = null
                    // S4d-228-r1: the whole batch span is wrapped in try/finally so `busy` is ALWAYS reset —
                    // even if setup (notably reading the output prefs) throws BEFORE the per-file loop. This
                    // restores the old single-file drop's recovery: a setup failure must not leave the UI stuck.
                    try {
                        val next = withContext(Dispatchers.IO) {
                            // S4d-228-r1: a setup-level failure (e.g. userConfigRepo.userPreferences.first())
                            // surfaces as a "Failed: …" status instead of an uncaught throw. The per-file loop
                            // keeps its OWN try/catch so one bad image still does not abort the batch.
                            try {
                                // S4d-228 / S4d-217: read the output format ONCE per batch; saves go to the user dir.
                                val fmt = userConfigRepo.userPreferences.first().outputFormat
                                var successCount = 0
                                var failCount = 0
                                var firstFailure: String? = null
                                // S4d-228: STRICTLY SEQUENTIAL. resolveUniqueOutputFile is existence-check-only and does
                                // NOT create the file, and runSaveFlow writes its output synchronously before returning,
                                // so resolving the NEXT path only after the previous save has written yields collision-
                                // free names (watermarked.<ext>, watermarked_1.<ext>, …). Read one file's bytes per
                                // iteration (never all up front). A per-file failure never aborts the batch.
                                for (file in files) {
                                    try {
                                        val bytes = file.readBytes()
                                        val out = DesktopSaveDecision.resolveUniqueOutputFile(outputDir, fmt)
                                        val o = DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, inputBytes = bytes, inputLabel = file.path,
                                            outputFile = out,
                                        )
                                        lastPicked = LastImage(bytes, file.path)
                                        lastSaved = File(o.outputPath)
                                        successCount++
                                    } catch (t: Throwable) {
                                        failCount++
                                        if (firstFailure == null) firstFailure = "${file.name}: ${t.message}"
                                    }
                                }
                                buildString {
                                    append("Saved $successCount/${files.size} images to ${outputDir.path}")
                                    if (failCount > 0) append(" · $failCount failed: $firstFailure")
                                }
                            } catch (t: Throwable) {
                                "Failed: ${t.message}"
                            }
                        }
                        lastPicked?.let { lastImage = it }
                        lastSaved?.let { lastSavedFile = it }
                        // S4d-228: refresh the preview AT MOST ONCE after the batch, only when ≥1 save succeeded
                        // (over the last successful image). refreshPreview writes ONLY the temp preview file
                        // (never lastSavedFile, so the share-substitute buttons stay bound to real saves).
                        status = if (lastSaved != null) "$next · ${refreshPreview()}" else next
                    } finally {
                        busy = false
                    }
                }
                return true
            }
        }
    }

    Window(onCloseRequest = ::exitApplication, title = "EasyWatermark — Desktop") {
        MaterialTheme {
            Column(
                // S4d-155: vertical scroll so the growing single-column control surface stays reachable
                // on constrained window heights. verticalScroll after fillMaxSize makes the column fill the
                // viewport and scroll when its content is taller; padding stays inside (scrolls with content).
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    // S4d-158: accept a dropped image file anywhere on the window content.
                    .dragAndDropTarget(shouldStartDragAndDrop = { hasFileList(it) }, target = dropTarget)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("EasyWatermark — Desktop", style = MaterialTheme.typography.h6)
                Text(
                    "Renders the deterministic sample through the shared engine and saves an image. " +
                        "Honors text / color / typeface / textStyle / tileMode / textSize / degree / gaps / alpha. " +
                        "Pick an icon to switch to Image mode.",
                    style = MaterialTheme.typography.body2,
                )
                // S4d-145: the watermark TEXT input — the first real Desktop edit control. Persisted via
                // WatermarkConfigEditor.updateText on an explicit "Apply text" click (NOT per keystroke);
                // updateText flips persisted mode to Text, so the next Render/Open-image save renders this
                // text (runSaveFlow no longer forces a demo string). Initialized from the persisted config.
                OutlinedTextField(
                    value = watermarkText,
                    onValueChange = { watermarkText = it },
                    enabled = !busy,
                    label = { Text("Watermark text") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val applied = watermarkText
                            val (msg, ok) = withContext(Dispatchers.IO) {
                                try {
                                    editor.updateText(applied)
                                    "Watermark text applied (Text mode): \"$applied\"" to true
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}" to false
                                }
                            }
                            // S4d-198: auto-refresh the preview on a successful apply (no manual click).
                            status = if (ok) "$msg · ${refreshPreview()}" else msg
                            busy = false
                        }
                    },
                ) {
                    Text("Apply text")
                }
                // S4d-148: the watermark ROTATION DEGREE input. Parsed on an explicit "Apply degree" click
                // (toFloatOrNull; invalid → status only, no persist); coerced to 0..360 at the edge (the repo
                // also clamps via WatermarkConfigRules.clampDegree). S4d-198: a successful apply auto-refreshes the preview (manual "Preview" still available).
                OutlinedTextField(
                    value = degreeText,
                    onValueChange = { degreeText = it },
                    enabled = !busy,
                    label = { Text("Degree (0–360)") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        // r1: reject blank/non-numeric AND non-finite (NaN/Infinity parse as Floats) before persisting.
                        val parsed = degreeText.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                        if (parsed == null) {
                            // Invalid number → short failure status; do NOT call the editor.
                            status = "Invalid degree: \"$degreeText\" — enter a number (0–360)."
                        } else {
                            scope.launch {
                                busy = true
                                val applied = parsed.coerceIn(0f, 360f)
                                val (next, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateDegree(applied)
                                        "Degree applied: $applied" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // r1: on a successful apply, snap the field to the clamped value actually
                                // persisted (a typed 400 now shows 360.0, not the rejected 400).
                                if (ok) degreeText = applied.toString()
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply degree")
                }
                // S4d-149: the watermark TEXT COLOR input (hex). Accepts #RRGGBB / RRGGBB / #AARRGGBB /
                // AARRGGBB; RGB-only uses opaque alpha 0xFF. Parsed on an explicit "Apply color" click
                // (invalid → status only, no persist); persisted via WatermarkConfigEditor.updateTextColor;
                // the field is normalized to #AARRGGBB on success. S4d-198: a successful apply auto-refreshes the preview (manual "Preview" still available).
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { colorText = it },
                    enabled = !busy,
                    label = { Text("Text color (#AARRGGBB)") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val raw = colorText.trim().removePrefix("#")
                        // r1: reject any non-hex char (incl. a leading +/-, which toLongOrNull(16) WOULD
                        // accept — e.g. "-FFFFF"/"+FFFFF" parse to valid Longs) BEFORE the length/parse, so
                        // signed/garbage input never reaches updateTextColor.
                        val isHex = raw.isNotEmpty() && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                        val parsedColor: Int? = when {
                            !isHex -> null
                            raw.length == 6 -> raw.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } // RRGGBB → opaque
                            raw.length == 8 -> raw.toLongOrNull(16)?.toInt()                              // AARRGGBB
                            else -> null
                        }
                        if (parsedColor == null) {
                            // Invalid hex → short failure status; do NOT call the editor.
                            status = "Invalid color: \"$colorText\" — use #RRGGBB or #AARRGGBB hex."
                        } else {
                            val normalized = "#%08X".format(parsedColor)
                            scope.launch {
                                busy = true
                                val (next, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateTextColor(parsedColor)
                                        "Color applied: $normalized" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // Normalize the field to the stable #AARRGGBB display on a successful apply.
                                if (ok) colorText = normalized
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply color")
                }
                // S4d-150: the watermark OPACITY (alpha) input as a 0..100 percent (Android editor semantics).
                // Parsed on an explicit "Apply opacity" click (toFloatOrNull + isFinite; invalid → status only,
                // no persist); coerced to 0..100; persisted via WatermarkConfigEditor.updateAlpha(percent),
                // which converts to the 0..255 byte. The field snaps to the applied value. S4d-198: auto-preview.
                OutlinedTextField(
                    value = alphaText,
                    onValueChange = { alphaText = it },
                    enabled = !busy,
                    label = { Text("Opacity (0–100%)") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val parsed = alphaText.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                        if (parsed == null) {
                            // Invalid/non-finite → short failure status; do NOT call the editor.
                            status = "Invalid opacity: \"$alphaText\" — enter a number (0–100)."
                        } else {
                            scope.launch {
                                busy = true
                                val applied = parsed.coerceIn(0f, 100f)
                                val (next, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateAlpha(applied)
                                        "Opacity applied: $applied%" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // Snap the field to the applied/coerced value on a successful apply.
                                if (ok) alphaText = applied.toString()
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply opacity")
                }
                // S4d-151: the horizontal/vertical GAP inputs. Both are parsed together on an explicit
                // "Apply gaps" click (toIntOrNull); if EITHER is invalid the apply is rejected atomically
                // (status only, NEITHER value persisted). Valid values are coerced to 0..500 at the edge
                // (the repo also clamps via WatermarkConfigRules) and persisted through updateHorizon /
                // updateVertical; both fields snap to the coerced values. S4d-198: a successful apply auto-previews.
                OutlinedTextField(
                    value = hGapText,
                    onValueChange = { hGapText = it },
                    enabled = !busy,
                    label = { Text("Horizontal gap (0–500%)") },
                )
                OutlinedTextField(
                    value = vGapText,
                    onValueChange = { vGapText = it },
                    enabled = !busy,
                    label = { Text("Vertical gap (0–500%)") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val h = hGapText.trim().toIntOrNull()
                        val v = vGapText.trim().toIntOrNull()
                        if (h == null || v == null) {
                            // Either field invalid → reject BOTH; persist nothing (atomic apply).
                            status = "Invalid gaps: H=\"$hGapText\" V=\"$vGapText\" — enter whole numbers (0–500)."
                        } else {
                            scope.launch {
                                busy = true
                                val appliedH = h.coerceIn(0, 500)
                                val appliedV = v.coerceIn(0, 500)
                                val (next, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateHorizon(appliedH)
                                        editor.updateVertical(appliedV)
                                        "Gaps applied: H=$appliedH V=$appliedV" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // Snap both fields to the coerced values on a successful apply.
                                if (ok) {
                                    hGapText = appliedH.toString()
                                    vGapText = appliedV.toString()
                                }
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply gaps")
                }
                // S4d-152: the watermark TEXT SIZE input. Parsed on an explicit "Apply text size" click
                // (toFloatOrNull + isFinite; invalid → status only, no persist); coerced to 1f..100f at the
                // edge (the repo also clamps); persisted via WatermarkConfigEditor.updateTextSize. The field
                // snaps to the applied value. S4d-198: a successful apply auto-refreshes the preview (manual "Preview" still available).
                OutlinedTextField(
                    value = textSizeText,
                    onValueChange = { textSizeText = it },
                    enabled = !busy,
                    label = { Text("Text size (1–100)") },
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        val parsed = textSizeText.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                        if (parsed == null) {
                            // Invalid/non-finite → short failure status; do NOT call the editor.
                            status = "Invalid text size: \"$textSizeText\" — enter a number (1–100)."
                        } else {
                            scope.launch {
                                busy = true
                                val applied = parsed.coerceIn(1f, 100f)
                                val (next, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateTextSize(applied)
                                        "Text size applied: $applied" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // Snap the field to the applied/coerced value on a successful apply.
                                if (ok) textSizeText = applied.toString()
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply text size")
                }
                // S4d-153: the watermark TILE MODE control. Two buttons persist REPEAT (grid-tile across the
                // photo) or CLAMP (a single decal at a fractional offset) via WatermarkConfigEditor.updateTileMode.
                // Only these two product values are exposed — MIRROR/DECAL are legacy read-only storage ids, not
                // UI choices. After each apply the label is re-read from the persisted config (so it reflects what
                // actually persisted, even on a write failure). S4d-198: a successful apply auto-refreshes the preview (manual "Preview" still available).
                Text("Tile mode: $tileModeLabel", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val (msg, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateTileMode(WatermarkTileMode.REPEAT)
                                        "Tile mode → REPEAT (grid tile)" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                tileModeLabel = repo.waterMark.first().tileMode.name
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$msg · ${refreshPreview()}" else msg
                                busy = false
                            }
                        },
                    ) { Text("Tile / REPEAT") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val (msg, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateTileMode(WatermarkTileMode.CLAMP)
                                        "Tile mode → CLAMP (single decal)" to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                tileModeLabel = repo.waterMark.first().tileMode.name
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$msg · ${refreshPreview()}" else msg
                                busy = false
                            }
                        },
                    ) { Text("Decal / CLAMP") }
                }
                // S4d-154: the watermark TYPEFACE control. One button per TextTypeface (Normal/Italic/Bold/
                // BoldItalic) persists via WatermarkConfigEditor.updateTextTypeface; the current persisted value
                // shows in the label (re-read after each apply, truthful on a write failure). These four are the
                // only typeface choices. Both enums are render-honored on Desktop Skiko (S4d-122/123). S4d-198: auto-preview.
                Text("Typeface: $typefaceLabel", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TextTypeface.Normal,
                        TextTypeface.Italic,
                        TextTypeface.Bold,
                        TextTypeface.BoldItalic,
                    ).forEach { tf ->
                        val name = typefaceLabelOf(tf)
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val (msg, ok) = withContext(Dispatchers.IO) {
                                        try {
                                            editor.updateTextTypeface(tf)
                                            "Typeface → $name" to true
                                        } catch (t: Throwable) {
                                            "Failed: ${t.message}" to false
                                        }
                                    }
                                    typefaceLabel = typefaceLabelOf(repo.waterMark.first().textTypeface)
                                    // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                    status = if (ok) "$msg · ${refreshPreview()}" else msg
                                    busy = false
                                }
                            },
                        ) { Text(name) }
                    }
                }
                // S4d-154: the watermark TEXT STYLE control. One button per TextPaintStyle (Fill/Stroke) persists
                // via WatermarkConfigEditor.updateTextStyle; the current persisted value shows in the label (re-read
                // after each apply). These two are the only style choices. S4d-198: a successful apply auto-previews.
                Text("Text style: $styleLabel", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TextPaintStyle.Fill,
                        TextPaintStyle.Stroke,
                    ).forEach { st ->
                        val name = styleLabelOf(st)
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val (msg, ok) = withContext(Dispatchers.IO) {
                                        try {
                                            editor.updateTextStyle(st)
                                            "Text style → $name" to true
                                        } catch (t: Throwable) {
                                            "Failed: ${t.message}" to false
                                        }
                                    }
                                    styleLabel = styleLabelOf(repo.waterMark.first().textStyle)
                                    // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                    status = if (ok) "$msg · ${refreshPreview()}" else msg
                                    busy = false
                                }
                            },
                        ) { Text(name) }
                    }
                }
                // S4d-130: choose the output preference (two presets) through the shared OutputPrefsEditor,
                // persisted to the SAME store runSaveFlow reads — so the next sample/Open render uses it.
                Text("Output preference: $outputPref", style = MaterialTheme.typography.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                outputEditor.save(ImageFormat.JPEG, 80)
                                outputPref = describePref(userConfigRepo.userPreferences.first())
                            }
                        },
                    ) { Text("JPEG / 80") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                outputEditor.save(ImageFormat.PNG, 100)
                                outputPref = describePref(userConfigRepo.userPreferences.first())
                            }
                        },
                    ) { Text("PNG / 100") }
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // Launch on the UI-bound scope; do the heavy render off the UI thread, then write
                        // Compose state back on the UI dispatcher.
                        scope.launch {
                            busy = true
                            status = "Rendering…"
                            val current = lastImage
                            var savedFile: File? = null
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    // S4d-217: write the real save to the user output dir (not the build/ default).
                                    val fmt = userConfigRepo.userPreferences.first().outputFormat
                                    val out = DesktopSaveDecision.resolveUniqueOutputFile(outputDir, fmt)
                                    val o = if (current != null) {
                                        DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo,
                                            inputBytes = current.bytes, inputLabel = current.label,
                                            outputFile = out,
                                        )
                                    } else {
                                        DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, outputFile = out,
                                        )
                                    }
                                    // S4d-157: remember the real saved output for the share-substitute buttons.
                                    savedFile = File(o.outputPath)
                                    "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                        "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}"
                                }
                            }
                            savedFile?.let { lastSavedFile = it }
                            status = next
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "Working…" else "Render & Save sample")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // S4d-140: native AWT SAVE dialog to choose the OUTPUT path (modal on the EDT). The
                        // flow already accepts outputFile; the window simply supplies it here. Same render
                        // path/decision as "Render & Save sample" — destination-only.
                        val dialog = FileDialog(window, "Save image", FileDialog.SAVE).apply {
                            isVisible = true
                        }
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            val target = File(dir, name)
                            // Snapshot the remembered image on the UI thread; render off it (or the fixture).
                            val current = lastImage
                            scope.launch {
                                busy = true
                                status = "Saving to ${target.name}…"
                                var savedFile: File? = null
                                val next = withContext(Dispatchers.IO) {
                                    try {
                                        val o = if (current != null) {
                                            DesktopWatermarkFlow.runSaveFlow(
                                                repo, editor, userConfigRepo,
                                                inputBytes = current.bytes, inputLabel = current.label,
                                                outputFile = target,
                                            )
                                        } else {
                                            DesktopWatermarkFlow.runSaveFlow(
                                                repo, editor, userConfigRepo, outputFile = target,
                                            )
                                        }
                                        // S4d-157: remember the real saved output for the share-substitute buttons.
                                        savedFile = File(o.outputPath)
                                        "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                            "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                savedFile?.let { lastSavedFile = it }
                                status = next
                                busy = false
                            }
                        }
                        // Cancelled (null dir/file) → no save, no status change, no remembered-image change (no-op).
                    },
                ) {
                    Text("Save as…")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // Native AWT Open dialog on the EDT (the Compose Desktop UI thread); it is modal, so
                        // it returns the selection synchronously. `window` is the FrameWindowScope's AWT frame.
                        val dialog = FileDialog(window, "Open image", FileDialog.LOAD).apply {
                            setFilenameFilter { _, fileName ->
                                fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                            }
                            isVisible = true
                        }
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            val selected = File(dir, name)
                            // Read + render off the UI thread, then write Compose state back on the UI dispatcher.
                            scope.launch {
                                busy = true
                                status = "Rendering ${selected.name}…"
                                var picked: LastImage? = null
                                var savedFile: File? = null
                                val next = withContext(Dispatchers.IO) {
                                    try {
                                        val bytes = selected.readBytes()
                                        picked = LastImage(bytes, selected.path)
                                        // S4d-217: write the real save to the user output dir (not the build/ default).
                                        val fmt = userConfigRepo.userPreferences.first().outputFormat
                                        val out = DesktopSaveDecision.resolveUniqueOutputFile(outputDir, fmt)
                                        val o = DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, inputBytes = bytes, inputLabel = selected.path,
                                            outputFile = out,
                                        )
                                        // S4d-157: remember the real saved output for the share-substitute buttons.
                                        savedFile = File(o.outputPath)
                                        "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                            "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                picked?.let { lastImage = it }
                                savedFile?.let { lastSavedFile = it }
                                // S4d-198-r1: a successful "Open image…" is an explicit source change →
                                // auto-refresh the preview over the just-loaded image (lastImage is set above).
                                // savedFile != null ⟺ the real save succeeded; refreshPreview writes ONLY the
                                // temp preview file (never lastSavedFile, so share-substitute stays real-save-bound).
                                status = if (savedFile != null) "$next · ${refreshPreview()}" else next
                                busy = false
                            }
                        }
                        // Cancelled (null file/directory) → leave the status unchanged and do no work.
                    },
                ) {
                    Text("Open image…")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // S4d-135: native AWT Open dialog for the watermark ICON (same modal pattern + filter
                        // as "Open image…"). `window` is the FrameWindowScope's AWT frame.
                        val dialog = FileDialog(window, "Open icon", FileDialog.LOAD).apply {
                            setFilenameFilter { _, fileName ->
                                fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                            }
                            isVisible = true
                        }
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            val selected = File(dir, name)
                            // Persist ONLY the icon PATH (off the UI thread). editor.updateIcon flips persisted
                            // markMode to Image (S4d-134), so the next "Render & Save sample" / "Open image…"
                            // save renders through the Image branch (composeIconOverRealImage) over that path.
                            // S4d-198: this is a mode+input change (→ Image), so it auto-refreshes the preview
                            // (the icon-over-fixture/last-image render) — symmetric with "Use text watermark".
                            scope.launch {
                                busy = true
                                status = "Setting icon ${selected.name}…"
                                // Mirror the render buttons: try/catch INSIDE withContext returns the status
                                // string, so a DataStore/updateIcon failure becomes "Failed: …" and the
                                // `status = …; busy = false` below ALWAYS run (window never stuck busy).
                                val (msg, ok) = withContext(Dispatchers.IO) {
                                    try {
                                        // S4d-219/S4d-221: copy the picked icon into app-private storage and persist
                                        // THAT path (not the user's original), so Image-mode survives the source icon
                                        // moving/renaming/deleting and is machine-portable — parity with iOS
                                        // `IosIconPersistence` (S4d-116). The copy-then-prune logic lives in the tested
                                        // shared helper `DesktopIconPersistence` (`:shared:desktopTest`).
                                        val copied = DesktopIconPersistence.persistIcon(
                                            selected, File(appDataDir, "watermark_icons"),
                                        )
                                        editor.updateIcon(MediaRef(copied.absolutePath))
                                        ("Icon set: ${selected.name}\n  Copied to ${copied.path}\n  Watermark mode → Image.") to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                status = if (ok) "$msg · ${refreshPreview()}" else msg
                                busy = false
                            }
                        }
                        // Cancelled (null file/directory) → leave the status + stored icon unchanged (no-op).
                    },
                ) {
                    Text("Open icon…")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Switching to Text mode…"
                            val (msg, ok) = withContext(Dispatchers.IO) {
                                try {
                                    // updateText flips persisted mode to Text, so preserve the existing text value.
                                    val currentText = repo.waterMark.first().text
                                    editor.updateText(currentText)
                                    ("Watermark mode → Text. " +
                                        "Next “Render & Save sample” / “Open image…” renders text.") to true
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}" to false
                                }
                            }
                            // S4d-198: auto-refresh the preview on success (no manual Preview click).
                            status = if (ok) "$msg · ${refreshPreview()}" else msg
                            busy = false
                        }
                    },
                ) {
                    Text("Use text watermark")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // S4d-147/S4d-198: manual preview — render the CURRENT persisted config through the
                        // shared refreshPreview() spine (the SAME path the post-edit auto-refresh uses) and show
                        // it on-screen. Still available as an explicit user command even though edits now
                        // auto-refresh.
                        scope.launch {
                            busy = true
                            status = "Rendering preview…"
                            status = refreshPreview()
                            busy = false
                        }
                    },
                ) {
                    Text("Preview")
                }
                // S4d-157: Desktop "share substitute" over the last REAL saved output file (set only by the
                // Render & Save / Save as… / Open image… success paths — Preview writes a temp file and does NOT
                // set it). "Show in folder" reveals the saved file's folder via guarded java.awt.Desktop (AWT IO
                // off the UI thread); "Copy output path" puts the path on the Compose clipboard. Both are enabled
                // only when a real save exists and the window isn't busy.
                val clipboard = LocalClipboardManager.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && lastSavedFile != null,
                        onClick = {
                            val file = lastSavedFile
                            if (file != null) {
                                scope.launch {
                                    busy = true
                                    // AWT Desktop IO off the Compose UI thread; result reported via status.
                                    val next = withContext(Dispatchers.IO) {
                                        try {
                                            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                                            val folder = file.parentFile
                                            if (desktop != null && folder != null && desktop.isSupported(Desktop.Action.OPEN)) {
                                                desktop.open(folder)
                                                "Opened folder: ${folder.path}"
                                            } else {
                                                "Show in folder isn’t supported on this platform."
                                            }
                                        } catch (t: Throwable) {
                                            "Couldn’t open folder: ${t.message}"
                                        }
                                    }
                                    status = next
                                    busy = false
                                }
                            }
                        },
                    ) { Text("Show in folder") }
                    Button(
                        enabled = !busy && lastSavedFile != null,
                        onClick = {
                            val file = lastSavedFile
                            if (file != null) {
                                clipboard.setText(AnnotatedString(file.path))
                                status = "Copied path: ${file.path}"
                            }
                        },
                    ) { Text("Copy output path") }
                }
                // S4d-160/S4d-226: minimal Templates section over the shared Desktop Room template path. "Save
                // current text" stores the edited watermark text (templateEditor.add); each saved row applies it
                // (Use → WatermarkConfigEditor.updateText + sync the text field), updates in place from the
                // current watermark text (Update → TemplateEditor.update, preserving id/creationDate), or deletes
                // it. S4d-198: Use auto-refreshes the preview after a successful apply. `content` is the watermark
                // TEXT (S4d-159).
                Text("Templates", style = MaterialTheme.typography.subtitle1)
                Button(
                    // S4d-162: require nonblank text so an empty template can't be saved.
                    enabled = !busy && watermarkText.isNotBlank(),
                    onClick = {
                        val text = watermarkText
                        scope.launch {
                            busy = true
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    templateEditor.add(text)
                                    "Saved template: \"$text\""
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}"
                                }
                            }
                            status = next
                            busy = false
                        }
                    },
                ) { Text("Save current text as template") }
                if (templates.isEmpty()) {
                    Text("No templates yet — save one above.", style = MaterialTheme.typography.body2)
                } else {
                    templates.forEach { template ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                template.content ?: "",
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = !busy && template.content != null,
                                onClick = {
                                    val content = template.content
                                    if (content != null) {
                                        scope.launch {
                                            busy = true
                                            val (msg, ok) = withContext(Dispatchers.IO) {
                                                try {
                                                    editor.updateText(content)
                                                    "Template applied: \"$content\"" to true
                                                } catch (t: Throwable) {
                                                    "Failed: ${t.message}" to false
                                                }
                                            }
                                            // Sync the editable text field to the applied template on success.
                                            if (ok) watermarkText = content
                                            // S4d-198: auto-refresh the preview on success (no manual Preview click).
                                            status = if (ok) "$msg · ${refreshPreview()}" else msg
                                            busy = false
                                        }
                                    }
                                },
                            ) { Text("Use") }
                            Button(
                                // S4d-226: update the existing row from the current watermark text.
                                enabled = !busy && watermarkText.isNotBlank(),
                                onClick = {
                                    val text = watermarkText
                                    scope.launch {
                                        busy = true
                                        val next = withContext(Dispatchers.IO) {
                                            try {
                                                templateEditor.update(
                                                    template.copy(
                                                        content = text,
                                                        lastModifiedDate = Clock.System.now(),
                                                    )
                                                )
                                                "Updated template to: \"$text\""
                                            } catch (t: Throwable) {
                                                "Failed: ${t.message}"
                                            }
                                        }
                                        status = next
                                        busy = false
                                    }
                                },
                            ) { Text("Update") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val next = withContext(Dispatchers.IO) {
                                            try {
                                                templateEditor.delete(template)
                                                "Template deleted."
                                            } catch (t: Throwable) {
                                                "Failed: ${t.message}"
                                            }
                                        }
                                        status = next
                                        busy = false
                                    }
                                },
                            ) { Text("Delete") }
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.body2)
                preview?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Watermark preview",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    )
                }
            }
        }
    }
}
