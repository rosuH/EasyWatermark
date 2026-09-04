@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAssetIdentityRegistryTest {

    @BeforeTest
    fun setUp() {
        IosAssetIdentityRegistry.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        IosAssetIdentityRegistry.resetForTests()
    }

    @Test
    fun putGet_usesOwnedPath_notProvisional() {
        val owned = "/tmp/ewm_src_owned-a"
        IosAssetIdentityRegistry.put("/tmp/ewm_import_provisional_x", "asset-ignored-as-prod-key")
        IosAssetIdentityRegistry.put(owned, "asset-1")
        assertEquals("asset-1", IosAssetIdentityRegistry.get(owned))
        assertNull(IosAssetIdentityRegistry.get("/tmp/missing"))
    }

    @Test
    fun put_sameAssetId_onRenamedOwnedPath_movesKey() {
        val provisionalOrOld = "/tmp/ewm_src_old"
        val owned = "/tmp/ewm_src_final"
        IosAssetIdentityRegistry.put(provisionalOrOld, "asset-1")
        IosAssetIdentityRegistry.put(owned, "asset-1")
        assertNull(IosAssetIdentityRegistry.get(provisionalOrOld))
        assertEquals("asset-1", IosAssetIdentityRegistry.get(owned))
        assertEquals(owned, IosAssetIdentityRegistry.pathFor("asset-1"))
        assertEquals(listOf("asset-1"), IosAssetIdentityRegistry.idsForOwnedPaths(listOf(owned)))
    }

    @Test
    fun clear_dropsAllEntries() {
        IosAssetIdentityRegistry.put("/tmp/ewm_src_a", "a")
        IosAssetIdentityRegistry.put("/tmp/ewm_src_b", "b")
        IosAssetIdentityRegistry.clear()
        assertEquals(0, IosAssetIdentityRegistry.sizeForTests())
        assertNull(IosAssetIdentityRegistry.get("/tmp/ewm_src_a"))
        assertNull(IosAssetIdentityRegistry.pathFor("a"))
    }

    @Test
    fun cap_evictsOldest_whenOverFifty() {
        repeat(IosAssetIdentityRegistry.MAX_ENTRIES + 5) { index ->
            IosAssetIdentityRegistry.put("/tmp/ewm_src_$index", "asset-$index")
        }
        assertEquals(IosAssetIdentityRegistry.MAX_ENTRIES, IosAssetIdentityRegistry.sizeForTests())
        assertNull(IosAssetIdentityRegistry.get("/tmp/ewm_src_0"))
        assertNull(IosAssetIdentityRegistry.get("/tmp/ewm_src_4"))
        assertEquals("asset-5", IosAssetIdentityRegistry.get("/tmp/ewm_src_5"))
        assertEquals(
            "asset-${IosAssetIdentityRegistry.MAX_ENTRIES + 4}",
            IosAssetIdentityRegistry.get("/tmp/ewm_src_${IosAssetIdentityRegistry.MAX_ENTRIES + 4}"),
        )
    }

    @Test
    fun concurrentPuts_doNotCrash_andHonorCap() = runBlocking {
        coroutineScope {
            repeat(8) { worker ->
                launch(Dispatchers.Default) {
                    repeat(20) { item ->
                        val n = worker * 20 + item
                        IosAssetIdentityRegistry.put("/tmp/ewm_src_c_$n", "asset-c-$n")
                    }
                }
            }
        }
        assertTrue(IosAssetIdentityRegistry.sizeForTests() <= IosAssetIdentityRegistry.MAX_ENTRIES)
        assertTrue(IosAssetIdentityRegistry.sizeForTests() > 0)
    }
}
