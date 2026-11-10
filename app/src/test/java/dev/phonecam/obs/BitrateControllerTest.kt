package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test

class BitrateControllerTest {
    private val mb = 1_000_000

    @Test fun steadyLinkKeepsTheChoice() {
        val c = BitrateController(60 * mb)
        repeat(30) { assertNull(c.update(0)) }
        assertEquals(60 * mb, c.current); assertFalse(c.limited)
    }
    @Test fun dropsLowerStepByStepWithCooldown() {
        val c = BitrateController(60 * mb)
        c.update(0)
        assertEquals(42 * mb, c.update(10))
        assertNull(c.update(20))           // cooldown: at most one step every 2 seconds
        assertEquals(29 * mb, c.update(30))
        assertTrue(c.limited)
    }
    @Test fun neverBelowMinimumAndReportsStarved() {
        val c = BitrateController(12 * mb)  // minimum 2 Mb/s
        var drops = 0L
        repeat(40) { drops += 5; c.update(drops) }
        assertEquals(2 * mb, c.current)
        assertTrue(c.starved)
        c.update(drops)                     // a calm second clears it
        assertFalse(c.starved)
    }
    @Test fun recoversTowardsButNeverAboveTheChoice() {
        val c = BitrateController(20 * mb)
        c.update(0); c.update(5)            // 14 Mb/s
        var raised = 0
        repeat(200) { if (c.update(5) != null) raised++ }
        assertEquals(20 * mb, c.current)
        assertTrue(raised >= 2)
    }
    @Test fun dropsRightAfterRaisingDoubleTheWait() {
        val c = BitrateController(20 * mb)
        c.update(0); c.update(5)            // 14
        repeat(10) { c.update(5) }          // 10 calm seconds -> raised to 16
        assertEquals(16 * mb, c.current)
        repeat(2) { c.update(5) }
        c.update(9)                         // drops soon after the raise
        assertTrue(c.current < 16 * mb)
        val lowered = c.current
        repeat(19) { c.update(9) }          // 10 is no longer enough
        assertEquals(lowered, c.current)
        c.update(9)                         // the 20th calm second raises it
        assertTrue(c.current > lowered)
    }
    @Test fun minimumIsASixthButAtLeastTwo() {
        assertEquals(10 * mb, BitrateController.defaultMinimum(60 * mb))
        assertEquals(2 * mb, BitrateController.defaultMinimum(5 * mb))
        assertEquals(1 * mb, BitrateController.defaultMinimum(1 * mb))
    }
}
