package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    private val body = "자동 빌드된 APK (커밋 abc1234).\n\nversionCode=142\nversionName=0.93-car8-142\n"

    @Test fun readsVersionFromReleaseBody() {
        assertEquals(142, parseVersionCode(body))
        assertEquals("0.93-car8-142", parseVersionName(body))
    }

    @Test fun missingVersionIsNull() {
        assertNull(parseVersionCode("옛 릴리스"))
        assertNull(parseVersionCode(null))
    }

    @Test fun onlyHigherCodeIsAnUpdate() {
        val r = RemoteBuild(142, "x", "u")
        assertTrue(isNewer(r, 141))
        assertFalse(isNewer(r, 142))
        assertFalse(isNewer(r, 150))
        assertFalse(isNewer(null, 1))
    }

    @Test fun autoCheckThrottle() {
        val h = 60 * 60 * 1000L
        assertTrue(shouldAutoCheck(10 * h, 0))
        assertFalse(shouldAutoCheck(10 * h, 9 * h))
        assertTrue(shouldAutoCheck(16 * h, 9 * h))
        assertTrue(shouldAutoCheck(1 * h, 9 * h)) // clock went backwards
    }
}
