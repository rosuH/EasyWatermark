package me.rosuh.easywatermark.session

import platform.Photos.PHAuthorizationStatusAuthorized
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IosPhotoLibraryAccessTest {

    @BeforeTest
    fun setUp() {
        IosPhotoLibraryAccess.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        IosPhotoLibraryAccess.resetForTests()
    }

    @Test
    fun simulatorWithoutAllowAll_isNotUsable() {
        val status = IosPhotoLibraryAccess.status()
        assertNotEquals(IosPhotoLibraryAccess.Status.Authorized, status)
        assertFalse(IosPhotoLibraryAccess.isUsable())
        assertTrue(IosPhotoLibraryAccess.logLabel() in setOf(
            "notDetermined",
            "denied",
            "restricted",
            "limited",
        ))
        assertFalse(IosPhotoLibraryAccess.requestedThisProcessForTests())
    }

    @Test
    fun authorizedConstant_isDistinctFromLimited() {
        assertTrue(PHAuthorizationStatusAuthorized != 4L)
        assertFalse(
            IosPhotoLibraryAccess.Status.Limited == IosPhotoLibraryAccess.Status.Authorized,
        )
    }

    @Test
    fun installStatusForTests_overrides_native_and_reset_clears() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Authorized)
        assertTrue(IosPhotoLibraryAccess.isUsable())
        IosPhotoLibraryAccess.resetForTests()
        assertFalse(IosPhotoLibraryAccess.isUsable())
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        assertFalse(IosPhotoLibraryAccess.isUsable())
        assertEquals(IosPhotoLibraryAccess.Status.Denied, IosPhotoLibraryAccess.status())
    }
}
