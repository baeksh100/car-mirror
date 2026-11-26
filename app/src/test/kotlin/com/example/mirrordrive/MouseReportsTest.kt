package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseReportsTest {
    @Test fun reportClampsAndMasks() {
        val r = MouseReports.report(0xFF, 500, -500, 3)
        assertEquals(4, r.size)
        assertEquals(7, r[0].toInt())
        assertEquals(127, r[1].toInt())
        assertEquals(-127, r[2].toInt())
        assertEquals(3, r[3].toInt())
    }

    @Test fun splitMoveSumsExactlyAndRespectsStep() {
        val steps = MouseReports.splitMove(300, -45, 20)
        assertEquals(300, steps.sumOf { it.first })
        assertEquals(-45, steps.sumOf { it.second })
        assertTrue(steps.all { kotlin.math.abs(it.first) <= 20 && kotlin.math.abs(it.second) <= 20 })
        assertEquals(15, steps.size)
    }

    @Test fun splitMoveZeroIsEmpty() {
        assertTrue(MouseReports.splitMove(0, 0).isEmpty())
    }

    @Test fun descriptorIsBalanced() {
        val d = MouseReports.DESCRIPTOR.map { it.toInt() and 0xFF }
        assertEquals(2, d.count { it == 0xC0 })
        assertEquals(0x05, d[0])
    }

    @Test fun accumulatorCarriesFractions() {
        val a = MotionAccumulator(0.5f)
        assertEquals(0 to 0, a.add(1f, 1f))
        assertEquals(1 to 1, a.add(1f, 1f))
        assertEquals(-1 to 0, a.add(-2f, 0f))
    }
}
