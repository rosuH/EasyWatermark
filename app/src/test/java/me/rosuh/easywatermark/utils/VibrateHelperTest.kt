package me.rosuh.easywatermark.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrateHelperTest {
    @Test
    fun performHapticFeedbackSafelyReturnsFalseWhenPermissionIsDenied() {
        val result = VibrateHelper.performHapticFeedbackSafely {
            throw SecurityException("missing android.permission.VIBRATE")
        }

        assertFalse(result)
    }

    @Test
    fun performHapticFeedbackSafelyReturnsDelegateResult() {
        assertTrue(VibrateHelper.performHapticFeedbackSafely { true })
        assertFalse(VibrateHelper.performHapticFeedbackSafely { false })
    }
}
