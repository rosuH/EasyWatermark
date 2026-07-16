package me.rosuh.easywatermark.ui

import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.ResourceCaches
import org.jetbrains.compose.resources.clearBlocking

/**
 * S-i18n-0 Desktop host witness: resolve the spike string under US + zh-CN JVM locales via the
 * real [CmpSpikeResources.resolveSpikeHello] path. Called from `:desktopApp --headless`.
 */
@OptIn(ExperimentalResourceApi::class)
object CmpSpikeDesktopWitness {
    data class Result(val sys: String, val en: String, val zhCn: String)

    fun resolveEnAndZhCn(): Result = runBlocking {
        val sys = CmpSpikeResources.resolveSpikeHello()
        val en = underLocale(Locale.US) { CmpSpikeResources.resolveSpikeHello() }
        val zh = underLocale(Locale.SIMPLIFIED_CHINESE) { CmpSpikeResources.resolveSpikeHello() }
        Result(sys = sys, en = en, zhCn = zh)
    }

    fun printAndCheck() {
        val r = resolveEnAndZhCn()
        println("  S-i18n-0 spike SYS=[${r.sys}] EN=[${r.en}] ZH_CN=[${r.zhCn}]")
        check(r.en == CmpSpikeResources.EXPECTED_EN) { "EN spike mismatch: ${r.en}" }
        check(r.zhCn == CmpSpikeResources.EXPECTED_ZH_CN) { "zh-CN spike mismatch: ${r.zhCn}" }
        check(r.sys.isNotBlank()) { "system spike empty" }
    }

    private suspend fun underLocale(locale: Locale, block: suspend () -> String): String {
        val prev = Locale.getDefault()
        Locale.setDefault(locale)
        ResourceCaches.clearBlocking()
        return try {
            block()
        } finally {
            Locale.setDefault(prev)
            ResourceCaches.clearBlocking()
        }
    }
}
