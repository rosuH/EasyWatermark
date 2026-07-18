package me.rosuh.easywatermark.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cmp_spike_hello
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceCaches
import org.jetbrains.compose.resources.clearBlocking
import org.jetbrains.compose.resources.getString

/**
 * S-i18n-0: real Compose Multiplatform resource path (generated [Res] + [getString]), not a bag
 * And not Android R.string. Proves default EN + zh-rCN qualifier selection by flipping JVM locale * (public API path — [ResourceEnvironment] ctor is library-internal).
 */
@OptIn(ExperimentalResourceApi::class)
class CmpSpikeResourcesTest {

    @Test
    fun spike_key_en_locale_matches_literal() {
        withJvmLocale(Locale.US) {
            val text = runBlocking { CmpSpikeResources.resolveSpikeHello() }
            assertEquals(CmpSpikeResources.EXPECTED_EN, text)
            val viaRes = runBlocking { getString(Res.string.cmp_spike_hello) }
            assertEquals(CmpSpikeResources.EXPECTED_EN, viaRes)
        }
    }

    @Test
    fun spike_key_zh_cn_locale_matches_literal() {
        withJvmLocale(Locale.SIMPLIFIED_CHINESE) {
            val text = runBlocking { CmpSpikeResources.resolveSpikeHello() }
            assertEquals(CmpSpikeResources.EXPECTED_ZH_CN, text)
            assertTrue(text.contains("探针"), "zh-CN spike should contain 探针, got: $text")
        }
    }

    @Test
    fun system_locale_resolve_is_non_empty_and_known() {
        val text = runBlocking { CmpSpikeResources.resolveSpikeHello() }
        assertTrue(text.isNotBlank(), "system-locale spike must not be empty (CMP-9547 class failure)")
        assertTrue(
            text == CmpSpikeResources.EXPECTED_EN || text == CmpSpikeResources.EXPECTED_ZH_CN,
            "unexpected system spike: $text",
        )
    }

    private fun withJvmLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            // Drop cached string loads so the next getString re-reads under the new locale.
            ResourceCaches.clearBlocking()
            block()
        } finally {
            Locale.setDefault(previous)
            ResourceCaches.clearBlocking()
        }
    }
}
