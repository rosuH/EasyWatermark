package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class DraftRenderConflatorSequentialTest {

    @Test
    fun sequentialDraftSamples_eachRender_whenWorkerIsFree() = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rendered = mutableListOf<Int>()
            val conflator = DraftRenderConflator<Int>(scope) { rendered += it }
            try {
                for (sample in 1..3) {
                    conflator.submit(sample)
                    withTimeout(10_000) {
                        while (rendered.size < sample) yield()
                    }
                }
                assertEquals(listOf(1, 2, 3), rendered.toList())
            } finally {
                conflator.close()
            }
        }
    }
}
