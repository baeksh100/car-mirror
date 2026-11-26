package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuidanceTest {
    private val ok = "nsd ok(2/2) CarMirror"

    @Test fun connectedShowsIphoneName() {
        val g = guidanceFor(ok, "wlan0:v6only", "내 차", session = SessionSnapshot(running = true, iphoneName = "홍길동의 iPhone"))
        assertTrue(g.ok); assertTrue(g.text.contains("홍길동의 iPhone"))
    }

    @Test fun recentlyEndedAsksToReselect() {
        val g = guidanceFor(ok, "wlan0:v6only", "내 차", session = SessionSnapshot(endedAgoMs = 5_000))
        assertFalse(g.ok); assertTrue(g.text.contains("끊겼어요")); assertTrue(g.text.contains("내 차"))
    }

    @Test fun longAgoEndBackToReady() {
        val g = guidanceFor(ok, "wlan0:v6only", "내 차", session = SessionSnapshot(endedAgoMs = RECONNECT_HINT_MS + 1))
        assertTrue(g.ok); assertTrue(g.text.startsWith("준비됐어요"))
    }

    @Test fun endedButNotAdvertisingShowsNetworkProblem() {
        val g = guidanceFor("jmdns:no-network(retrying)", "usb0:v6only", "X", session = SessionSnapshot(endedAgoMs = 1000))
        assertTrue(g.text.contains("Wi-Fi가 꺼져"))
    }
}

class ChecklistTest {
    @Test fun hiddenBeforeTimeout() =
        assertTrue(checklistFor("nsd ok(2/2) X", "wlan0:v6only", SessionSnapshot(waitingMs = 10_000)).isEmpty())

    @Test fun hiddenWhileRunning() =
        assertTrue(checklistFor("x", "", SessionSnapshot(running = true, waitingMs = 99_000)).isEmpty())

    // The real car network seen in car3.
    @Test fun ipv6OnlyCarCase() {
        val l = checklistFor("nsd ok(2/2) X", "dummy0:v6only* wlan0:v6only usb0:v6only v4-wlan0:192.0.0.4*",
            SessionSnapshot(waitingMs = 40_000))
        assertTrue(l[0].startsWith("✓ Wi-Fi"))
        assertTrue(l[1].contains("IPv6 전용"))
        assertTrue(l[2].startsWith("✓"))
        assertTrue(l.any { it.contains("카플레이") })
    }

    @Test fun noWifiMarked() {
        val l = checklistFor("jmdns:no-network(retrying)", "usb0:v6only", SessionSnapshot(waitingMs = 40_000))
        assertTrue(l[0].startsWith("✗ Wi-Fi"))
        assertTrue(l.any { it.startsWith("✗ 기기 알림") })
    }

    @Test fun ipv4Marked() {
        val l = checklistFor("nsd ok(2/2) X", "wlan0:172.20.10.3", SessionSnapshot(waitingMs = 40_000))
        assertEquals("✓ 주소: IPv4", l[1])
    }
}

class AdvertisedSizeTest {
    @Test fun wideCarScreenKeptAsIs() = assertEquals(1920 to 720, advertisedSize(1920, 720))
    @Test fun portraitReportedLandscape() = assertEquals(1920 to 720, advertisedSize(720, 1920))
    @Test fun largeScaledDownKeepingAspect() = assertEquals(1920 to 720, advertisedSize(2560, 960))
    @Test fun tallLimitedByHeight() = assertEquals(1440 to 1080, advertisedSize(2048, 1536))
    @Test fun evenRounding() {
        val (w, h) = advertisedSize(1280, 481)!!
        assertEquals(0, w % 2); assertEquals(0, h % 2)
    }
    @Test fun nonsenseIsNull() { assertNull(advertisedSize(0, 720)); assertNull(advertisedSize(100, 50)) }
}

class NightTest {
    private fun hm(h: Int, m: Int) = h * 60 + m

    // November: sunset ~17:30 -> night from ~17:50; the car photos were taken ~18:40.
    @Test fun novemberEveningIsNight() = assertTrue(isNight(11, hm(18, 42), null, false))
    @Test fun novemberAfternoonIsDay() = assertFalse(isNight(11, hm(16, 0), null, false))
    @Test fun juneEightPmStillDusk() = assertFalse(isNight(6, hm(20, 0), null, false))
    @Test fun juneTenPmNight() = assertTrue(isNight(6, hm(22, 0), null, false))
    @Test fun earlyMorningNight() = assertTrue(isNight(1, hm(6, 30), null, false))
    @Test fun sensorDarkWins() = assertTrue(isNight(6, hm(12, 0), 2f, false))
    @Test fun sensorBrightWins() = assertFalse(isNight(12, hm(23, 0), 200f, true))
    @Test fun sensorHysteresisKeepsState() {
        assertTrue(isNight(6, hm(12, 0), 20f, true))
        assertFalse(isNight(6, hm(12, 0), 20f, false))
    }
}
