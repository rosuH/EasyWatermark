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
 * Same race as `iosApp-2026-08-29-015756.ips` / [IosTextCellConcurrencyTest]: two
 * `Dispatchers.Default` workers in [WatermarkCellComposer.composeTextCell] → Skiko
 * `WeakKeysCache` SIGSEGV. Desktop production uses the same Skiko text cell path.
 */
class DesktopTextCellConcurrencyTest {

    @Test
    fun concurrent_composeTextCell_does_not_crash() = runBlocking {
        coroutineScope {
            val jobs = List(8) { worker ->
                async(Dispatchers.Default) {
                    repeat(16) { tick ->
                        val cell = DesktopWatermarkTextRenderer.renderTextCell(
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
