package com.example.minimalapk.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionTest {
    @Test
    fun grantedPermissionIsNeverPermanentlyDenied() {
        assertFalse(
            isCameraPermanentlyDenied(
                granted = true,
                requested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun beforeFirstRequestIsNotPermanentlyDenied() {
        assertFalse(
            isCameraPermanentlyDenied(
                granted = false,
                requested = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun denialWithRationaleIsNotPermanent() {
        assertFalse(
            isCameraPermanentlyDenied(
                granted = false,
                requested = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun denialWithoutRationaleAfterRequestIsPermanent() {
        assertTrue(
            isCameraPermanentlyDenied(
                granted = false,
                requested = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun unknownRationaleIsNotPermanent() {
        assertFalse(
            isCameraPermanentlyDenied(
                granted = false,
                requested = true,
                shouldShowRationale = null,
            ),
        )
    }
}
