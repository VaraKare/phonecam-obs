package dev.phonecam.obs

import org.junit.Assert.*
import org.junit.Test

class FrameStatsTest {
    @Test fun constantRateAndBoundedHistory() {
        val stats = FrameStats(32)
        repeat(100_000) { stats.add(it * 33_333L) }
        assertEquals(100_000L, stats.frames)
        assertEquals(32, stats.retainedGaps)
        assertEquals(30.0, stats.fps, 0.001)
        assertEquals(33_333L, stats.recentP95GapUs())
    }
    @Test fun lateGapSurvivesRollingWindow() {
        val stats = FrameStats(4)
        stats.add(0); stats.add(200_000)
        repeat(20) { stats.add(233_333L + it * 33_333L) }
        assertEquals(200_000L, stats.maxGapUs)
        assertEquals(33_333L, stats.recentP95GapUs())
    }
    @Test fun nonMonotonicPtsAreVisible() {
        val stats = FrameStats()
        stats.add(100); stats.add(100); stats.add(99)
        assertEquals(2L, stats.nonMonotonic)
    }
    @Test fun emptyAndSingleFrameHaveNoInventedRate() {
        val stats = FrameStats()
        assertEquals(0.0, stats.fps, 0.0)
        assertEquals(0L, stats.recentP95GapUs())
        stats.add(123)
        assertEquals(0.0, stats.fps, 0.0)
    }
}
