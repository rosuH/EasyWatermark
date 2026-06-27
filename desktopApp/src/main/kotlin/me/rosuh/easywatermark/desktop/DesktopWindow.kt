package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.domain.OutputPrefsEditor
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import java.awt.FileDialog
import java.io.File

/** Best-effort Open-dialog filename filter (honored on macOS; ignored on some platforms — harmless). */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

/** Short label for the current output preference, e.g. "JPEG / 80". */
private fun describePref(p: UserPreferences): String = "${p.outputFormat} / ${p.compressLevel}"

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
 * the shared `OutputPrefsEditor`, so the save flow encodes in the chosen format. Still no icon / drag-drop /
 * preview-image / templates / share substitute in this slice.
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
    // S4d-130: the current/effective output preference, loaded on launch + refreshed after each preset save.
    var outputPref by remember { mutableStateOf("loading…") }
    LaunchedEffect(Unit) { outputPref = describePref(userConfigRepo.userPreferences.first()) }

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
                        "(No icon yet.)",
                    style = MaterialTheme.typography.body2,
                )
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
                            val next = withContext(Dispatchers.IO) {
                                try {
                                    val o = DesktopWatermarkFlow.runSaveFlow(repo, editor, userConfigRepo)
                                    "Saved: ${o.outputPath}\n  ${o.format}, ${o.width}x${o.height}, ${o.outputByteCount} B\n" +
                                        "  config: ${o.configAfterEdit}"
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
                                val next = withContext(Dispatchers.IO) {
                                    try {
                                        val bytes = selected.readBytes()
                                        val o = DesktopWatermarkFlow.runSaveFlow(
                                            repo, editor, userConfigRepo, inputBytes = bytes, inputLabel = selected.path,
                                        )
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
                        // Cancelled (null file/directory) → leave the status unchanged and do no work.
                    },
                ) {
                    Text("Open image…")
                }
                Text(status, style = MaterialTheme.typography.body2)
            }
        }
    }
}
