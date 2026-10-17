package me.rosuh.easywatermark.font

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosWatermarkFontAccessSampleTest {

    @Test
    fun two_system_fonts_resolve_to_distinct_families() = runBlocking {
        val access = IosWatermarkFontAccess()
        val listed = access.listSystemFonts()
        assertTrue(listed.size >= 2, "need two system families")
        val a = listed[0]
        val b = listed.first { it.ref != a.ref && it.displayName != a.displayName }
        val ra = access.resolve(a.ref)
        val rb = access.resolve(b.ref)
        assertIs<FontResolution.Success>(ra)
        assertIs<FontResolution.Success>(rb)
        assertNotEquals(a.ref.fingerprint(), b.ref.fingerprint())
        assertTrue(ra.family !== rb.family || ra.displayName != rb.displayName)
        assertTrue(a.ref is WatermarkFontRef.System)
    }
}
