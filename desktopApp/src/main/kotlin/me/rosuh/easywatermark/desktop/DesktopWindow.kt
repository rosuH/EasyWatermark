package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.domain.WatermarkConfigEditor

/**
 * S4d-121: the smallest useful **Compose Desktop window** over the S4d-120 save spine. A no-arg
 * `:desktopApp` launch opens this window (`Main.kt` dispatches); the `--headless` flag keeps a bounded
 * console automation path that exits.
 *
 * Honest, not faked: the "Render & Save sample" button runs the SAME shared spine the headless path uses
 * ([DesktopWatermarkFlow.runSaveFlow] → common `WaterMarkRepository` + `WatermarkConfigEditor` persist a
 * config edit, then `DesktopWatermarkComposer.composeOverRealImage` renders the deterministic fixture and
 * writes a PNG) and shows the persisted config + output path/dims/size. S4d-122: it honors text color and
 * typeface too (the paint-style mapping is wired but currently inert at the raster — see
 * `DesktopTextParityTest`); no icon / output-format yet. There is deliberately no file picker / drag-drop /
 * templates / share substitute / preview-image in this slice.
 */
fun launchDesktopWindow() = application {
    // ONE repository + editor for the window's lifetime (DataStore forbids a second active store per file).
    val repo = remember { DesktopWatermarkFlow.buildRepository() }
    val editor = remember { WatermarkConfigEditor(repo) }
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf("Ready. Click “Render & Save sample” to run the shared save flow.")
    }
    var busy by remember { mutableStateOf(false) }

    Window(onCloseRequest = ::exitApplication, title = "EasyWatermark — Desktop (S4d-121)") {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("EasyWatermark — Desktop", style = MaterialTheme.typography.h6)
                Text(
                    "Renders the deterministic sample through the shared engine and saves a PNG. " +
                        "Honors text / color / typeface / tileMode / textSize / degree / gaps / alpha. " +
                        "(textStyle is wired but currently inert; no icon or output-format yet.)",
                    style = MaterialTheme.typography.body2,
                )
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
                                    val o = DesktopWatermarkFlow.runSaveFlow(repo, editor)
                                    "Saved: ${o.outputPath}\n  ${o.width}x${o.height}, ${o.pngByteCount} B\n" +
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
                Text(status, style = MaterialTheme.typography.body2)
            }
        }
    }
}
