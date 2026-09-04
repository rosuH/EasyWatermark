package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Device crash `iosApp-2026-08-29-015756.ips`: two Default workers in
 * [WatermarkCellComposer.composeTextCell] → WeakKeysCache SIGSEGV.
 * This is the agent-runnable loop for that race (simulator, not PHPicker).
 */
class IosTextCellConcurrencyTest {

    @Test
    fun concurrent_composeTextCell_does_not_sigsegv() = runBlocking {
        coroutineScope {
            val jobs = List(8) { worker ->
                async(Dispatchers.Default) {
                    repeat(16) { tick ->
                        val cell = IosWatermarkRenderer.renderTextCell(
                            text = "EasyWatermark 水印 $worker-$tick",
                            textSize = 14f,
                            degree = 15f,
                            color = Color(0xFFFFB800),
                        )
                        assertTrue(cell.width > 0 && cell.height > 0)
                    }
                }
            }
            jobs.awaitAll()
            Unit
        }
    }
}
