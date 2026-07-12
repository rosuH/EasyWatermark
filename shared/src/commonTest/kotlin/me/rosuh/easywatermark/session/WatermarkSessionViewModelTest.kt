package me.rosuh.easywatermark.session

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Phase 0: shared ViewModel type is constructible on host pure tests (androidHost / commonTest).
 */
class WatermarkSessionViewModelTest {
    @Test
    fun constructs_without_platform_ports_in_phase0() {
        val vm = WatermarkSessionViewModel()
        assertNotNull(vm)
    }
}
