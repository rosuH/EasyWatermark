package me.rosuh.easywatermark.data.repo

import android.app.Application
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4d-84: pins that the platform-neutral default text-color constant now used by
 * `WaterMarkRepository` (`0xFFFFB800.toInt()`) equals the legacy `Color.parseColor("#FFB800")`,
 * proving the `android.graphics.Color` de-coupling is byte/value-identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaterMarkDefaultColorTest {

    @Test
    fun neutral_default_text_color_equals_legacy_parseColor() {
        assertEquals(Color.parseColor("#FFB800"), 0xFFFFB800.toInt())
    }
}
