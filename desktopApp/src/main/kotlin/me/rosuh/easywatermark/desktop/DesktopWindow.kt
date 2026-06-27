package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import me.rosuh.easywatermark.render.DesktopImageDecoder
import java.awt.FileDialog
import java.io.File

/** Best-effort Open-dialog filename filter (honored on macOS; ignored on some platforms — harmless). */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

/** Short label for the current output preference, e.g. "JPEG / 80". */
private fun describePref(p: UserPreferences): String = "${p.outputFormat} / ${p.compressLevel}"

private class LastImage(val bytes: ByteArray, val label: String)

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
 * "Apply color" field edits the text color (hex `#AARRGGBB`/`#RRGGBB`, via `WatermarkConfigEditor.updateTextColor`).
 * Still no REACTIVE/live preview, gaps/alpha/tile/typeface/style controls, templates UI, drag-drop, or share
 * substitute in this slice.
 */
fun launchDesktopWindow() = application {
    // ONE repository + editor for the window's lifetime (DataStore forbids a second active store per file).
    val repo = remember { DesktopWatermarkFlow.buildRepository() }
    val editor = remember { WatermarkConfigEditor(repo) }
    // S4d-128: the output-prefs repo the save flow reads (empty store → the shared (JPEG, 80) default).
    val userConfigRepo = remember { DesktopWatermarkFlow.buildUserConfigRepository() }
    // S4d-130: the shared output-prefs write use-case over the SAME store the save flow reads.
    val outputEditor = remember { OutputPrefsEditor(userConfigRepo) }
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf("Ready. Click “Render & Save sample” to run the shared save flow.")
    }
    var busy by remember { mutableStateOf(false) }
    var lastImage by remember { mutableStateOf<LastImage?>(null) }
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
    LaunchedEffect(Unit) {
        outputPref = describePref(userConfigRepo.userPreferences.first())
        watermarkText = repo.waterMark.first().text
        degreeText = repo.waterMark.first().degree.toString()
        colorText = "#%08X".format(repo.waterMark.first().textColor)
    }

    Window(onCloseRequest = ::exitApplication, title = "EasyWatermark — Desktop (S4d-121)") {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
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
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    editor.updateText(applied)
                                    "Watermark text applied (Text mode). Next render uses: \"$applied\""
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}"
                                }
                            }
                            status = next
                            busy = false
                        }
                    },
                ) {
                    Text("Apply text")
                }
                // S4d-148: the watermark ROTATION DEGREE input. Parsed on an explicit "Apply degree" click
                // (toFloatOrNull; invalid → status only, no persist); coerced to 0..360 at the edge (the repo
                // also clamps via WatermarkConfigRules.clampDegree). No auto-preview — click "Preview" to see it.
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
                                        "Degree applied: $applied. Click Preview to see it." to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // r1: on a successful apply, snap the field to the clamped value actually
                                // persisted (a typed 400 now shows 360.0, not the rejected 400).
                                if (ok) degreeText = applied.toString()
                                status = next
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
                // the field is normalized to #AARRGGBB on success. No auto-preview — click "Preview" to see it.
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
                                        "Color applied: $normalized. Click Preview to see it." to true
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}" to false
                                    }
                                }
                                // Normalize the field to the stable #AARRGGBB display on a successful apply.
                                if (ok) colorText = normalized
                                status = next
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Apply color")
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
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    val o = if (current != null) {
                                        DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo,
                                            inputBytes = current.bytes, inputLabel = current.label,
                                        )
                                    } else {
                                        DesktopWatermarkFlow.runSaveFlow(repo, editor, userConfigRepo)
                                    }
                                    "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                        "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}"
                                }
                            }
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
                                        "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                            "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
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
                                val next = withContext(Dispatchers.IO) {
                                    try {
                                        val bytes = selected.readBytes()
                                        picked = LastImage(bytes, selected.path)
                                        val o = DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, inputBytes = bytes, inputLabel = selected.path,
                                        )
                                        "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                            "  input: ${o.inputLabel} (${o.inputByteCount} B)\n  config: ${o.configAfterEdit}"
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                picked?.let { lastImage = it }
                                status = next
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
                            // No render here — this control just sets the icon; the existing save buttons render.
                            scope.launch {
                                busy = true
                                status = "Setting icon ${selected.name}…"
                                // Mirror the render buttons: try/catch INSIDE withContext returns the status
                                // string, so a DataStore/updateIcon failure becomes "Failed: …" and the
                                // `status = next; busy = false` below ALWAYS run (window never stuck busy).
                                val next = withContext(Dispatchers.IO) {
                                    try {
                                        editor.updateIcon(MediaRef(selected.absolutePath))
                                        "Icon set: ${selected.path}\n  Watermark mode → Image. " +
                                            "Next “Render & Save sample” / “Open image…” renders this icon."
                                    } catch (t: Throwable) {
                                        "Failed: ${t.message}"
                                    }
                                }
                                status = next
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
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    // updateText flips persisted mode to Text, so preserve the existing text value.
                                    val currentText = repo.waterMark.first().text
                                    editor.updateText(currentText)
                                    "Watermark mode → Text. " +
                                        "Next “Render & Save sample” / “Open image…” renders text."
                                } catch (t: Throwable) {
                                    "Failed: ${t.message}"
                                }
                            }
                            status = next
                            busy = false
                        }
                    },
                ) {
                    Text("Use text watermark")
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        // S4d-147: manual preview — render the CURRENT persisted config over the remembered
                        // image (or fixture) through the SAME runSaveFlow spine the save buttons use, to a
                        // repo-local temp file, then decode those bytes and show them on-screen. Heavy work
                        // (render + decode) runs off the EDT; the ImageBitmap is set back on the UI dispatcher.
                        scope.launch {
                            busy = true
                            status = "Rendering preview…"
                            val current = lastImage
                            val previewFile = File("build/s4d147-desktop-preview/preview.img")
                                .apply { parentFile?.mkdirs() }
                            val next: Pair<ImageBitmap?, String> = withContext(Dispatchers.IO) {
                                try {
                                    val o = if (current != null) {
                                        DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo,
                                            inputBytes = current.bytes, inputLabel = current.label,
                                            outputFile = previewFile,
                                        )
                                    } else {
                                        DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, outputFile = previewFile,
                                        )
                                    }
                                    // Decode the GENERIC encoded output (JPEG/80 by default, or PNG when prefs
                                    // select PNG) — DesktopImageDecoder (ImageIO) handles both, not PNG-only.
                                    DesktopImageDecoder.decode(previewFile.readBytes()) to
                                        "Preview: ${o.format}, ${o.width}x${o.height} (${o.outputByteCount} B)"
                                } catch (t: Throwable) {
                                    null to "Failed: ${t.message}"
                                }
                            }
                            // Keep the last good preview on failure (only replace on success).
                            next.first?.let { preview = it }
                            status = next.second
                            busy = false
                        }
                    },
                ) {
                    Text("Preview")
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
