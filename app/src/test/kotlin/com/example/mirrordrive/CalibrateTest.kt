package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrateTest {
    @Test fun scalesFromTap() { assertEquals(600, calibrateFull(300, 0.5f)) }
    @Test fun rejectsEdgeAndCorner() {
        assertNull(calibrateFull(300, 0.95f))
        assertNull(calibrateFull(300, 0.01f))
    }
}
