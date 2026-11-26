package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerTrackerTest {
    @Test fun firstMoveHomesThenRelative() {
        val t = PointerTracker()
        assertEquals(PointerTracker.Plan(true, 100, 200), t.plan(100, 200, 400, 800, 0))
        assertEquals(PointerTracker.Plan(false, 50, -150), t.plan(150, 50, 400, 800, 1000))
    }

    @Test fun rehomesAfterCountAgeOrSizeChange() {
        val t = PointerTracker(rehomeEvery = 2, maxAgeMs = 10_000)
        t.plan(10, 10, 400, 800, 0)
        assertFalse(t.plan(20, 20, 400, 800, 1).home)
        assertTrue(t.plan(30, 30, 400, 800, 2).home)          // count
        assertTrue(t.plan(30, 30, 400, 800, 20_000).home)     // age
        assertTrue(t.plan(30, 30, 800, 400, 20_001).home)     // rotated
    }

    @Test fun invalidateForcesHome() {
        val t = PointerTracker()
        t.plan(10, 10, 400, 800, 0)
        t.invalidate()
        assertTrue(t.plan(10, 10, 400, 800, 1).home)
    }
}
