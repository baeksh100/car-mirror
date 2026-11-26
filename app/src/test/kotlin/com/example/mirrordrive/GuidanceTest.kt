package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {
    @Test fun readyWhenNsdRegistered() {
        val g = guidanceFor("nsd ok(2/2) CarMirror", "wlan0:v6only", "내 차")
        assertTrue(g.ok)
        assertTrue(g.text.contains("내 차"))
    }

    @Test fun readyWhenJmdnsOnIpv6() {
        assertTrue(guidanceFor("jmdns on fe80::1%wlan0", "wlan0:v6only", "CarMirror").ok)
    }

    // The real car case before car4: Wi-Fi joined, no IPv4.
    @Test fun waitingForAddressWhenWifiPresent() {
        val g = guidanceFor("jmdns:no-network(retrying)", "dummy0:v6only* wlan0:v6only usb0:v6only v4-wlan0:192.0.0.4*", "CarMirror")
        assertFalse(g.ok)
        assertTrue(g.text.contains("기다리는"))
    }

    @Test fun wifiOffWhenNoWlan() {
        val g = guidanceFor("jmdns:no-network(retrying)", "usb0:v6only", "CarMirror")
        assertTrue(g.text.contains("Wi-Fi가 꺼져"))
    }

    @Test fun nativeFailureWins() {
        assertFalse(guidanceFor("nsd ok(2/2) X", "wlan0:1.2.3.4", "X", nativeOk = false).ok)
    }
}

class SanitizeNameTest {
    @Test fun trimsAndCollapses() = assertEquals("백 차", sanitizeName("  백   차  "))
    @Test fun replacesDotAndAt() = assertEquals("a b c", sanitizeName("a.b@c"))
    @Test fun blankIsNull() = assertNull(sanitizeName("   "))
    @Test fun limitsTo63Utf8Bytes() {
        val s = sanitizeName("가".repeat(40))!!   // 3 bytes each
        assertTrue(s.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals(21, s.length)
    }
}
