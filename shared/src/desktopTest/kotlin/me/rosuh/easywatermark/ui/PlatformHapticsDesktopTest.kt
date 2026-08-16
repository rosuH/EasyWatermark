package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformHapticsDesktopTest {

    @Test
    fun platformHaptics_isAConcreteJvmClass_andSelectionTickIsSafe() {
        val cls = Class.forName("me.rosuh.easywatermark.ui.PlatformHaptics")
        assertNotNull(cls.getField("INSTANCE").get(null))
        PlatformHaptics.selectionTick()
    }

    @Test
    fun commonSource_isNotExpectObject() {
        val cwd = File(System.getProperty("user.dir")!!)
        val src = listOf(
            File(cwd, "src/commonMain/kotlin/me/rosuh/easywatermark/ui/PlatformHaptics.kt"),
            File(cwd, "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/PlatformHaptics.kt"),
        ).first { it.isFile }.readText()
        assertFalse("expect object PlatformHaptics" in src)
        assertTrue("object PlatformHaptics" in src)
        assertTrue("internal expect fun platformSelectionTick" in src)
    }
}
