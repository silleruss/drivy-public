package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class RunnerMotionTest {
    @Test fun runningAdvancesEvenlyOnSixtyHertzIncludingTheLoopBoundary() {
        assertEquals((0..29).toList() + (0..29).toList(),
            (0..59).map { runnerFrame(it / 60.0, false, 0.0) })
    }
    @Test fun landingRestartsTheRunInsteadOfResumingAnUnrelatedFrame() {
        val game = RunnerGame()
        game.advance(.2)
        assertTrue(game.runElapsed > 0)
        game.jump()
        repeat(4) { game.advance(.25) }
        assertEquals(0.0, game.runElapsed, 1e-9)
        game.advance(1.0 / 240)
        assertEquals(0.0, game.height, 1e-9)
        assertEquals(0, runnerFrame(game.runElapsed, false, 0.0))
    }

    @Test fun runningUsesAllThirtyFramesAndLoops() {
        assertEquals((0..29).toList(), (0..29).map { runnerFrame((it + .01) / 60, false, 0.0) })
        assertEquals(0, runnerFrame(.5, false, 0.0))
    }
    @Test fun actualJumpVisitsEveryFrameAtThirtyHertz() {
        val game = RunnerGame()
        game.jump()
        val frames = mutableSetOf(runnerFrame(game.elapsed, true, game.verticalVelocity))
        repeat(29) {
            game.advance(1.0 / 30)
            frames.add(runnerFrame(game.elapsed, true, game.verticalVelocity))
        }
        assertEquals((0..29).toSet(), frames)
    }
    @Test fun jumpingTracksTheWholeOneSecondArc() {
        assertEquals((0..29).toList(), (0..29).map { runnerFrame(0.0, true, 1.5 - 3.0 * (it + .01) / 30) })
        assertEquals(0, runnerFrame(0.0, true, 1.6))
        assertEquals(29, runnerFrame(0.0, true, -1.6))
    }
}
