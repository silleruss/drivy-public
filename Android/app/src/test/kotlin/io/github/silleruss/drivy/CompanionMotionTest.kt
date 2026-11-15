package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class CompanionMotionTest {
    @Test fun idleBlinksAndGreetingTakesPriorityForEverySpecies() {
        for (companion in Companion.entries) {
            assertEquals(0, companionPose(companion, 0f, false).frame)
            assertEquals(1, companionPose(companion, .85f, false).frame)
            assertEquals(0, companionPose(companion, .99f, false).frame)
            assertEquals(2, companionPose(companion, .85f, true).frame)
        }
        assertEquals(1, companionPose(Companion.NERU, .7f, false).frame)
        assertEquals(0, companionPose(Companion.MOKA, .7f, false).frame)
    }

    @Test fun eachSpeciesHasDistinctMotionAndTheLoopIsContinuousAndBounded() {
        assertEquals(6, Companion.entries.map { companionPose(it, .125f, false) }.distinct().size)
        for (companion in Companion.entries) for (greeting in listOf(false, true)) {
            val start = companionPose(companion, 0f, greeting)
            val end = companionPose(companion, 1f, greeting)
            assertEquals(start.frame, end.frame)
            assertEquals(start.lift, end.lift, .001f)
            assertEquals(start.tilt, end.tilt, .001f)
            assertEquals(start.stretch, end.stretch, .001f)
            for (step in 0..100) {
                val pose = companionPose(companion, step / 100f, greeting)
                assertTrue(pose.frame in 0..2)
                assertTrue(pose.lift in -11f..3f)
                assertTrue(pose.tilt in -7f..7f)
                assertTrue(pose.stretch in -.04f.. .04f)
            }
        }
    }
}
