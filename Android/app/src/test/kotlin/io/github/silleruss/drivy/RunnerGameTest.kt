package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class RunnerGameTest {
    @Test fun newPatternsEnterFromOutsideAndOldCoinsLeaveBeforeReplacement() {
        var replacements = 0
        for (seed in 0 until 10) {
            val game = RunnerGame(random = kotlin.random.Random(seed))
            repeat(60 * 90) {
                if (game.over || game.stage > 4) return@repeat
                val oldPattern = game.coinPattern
                val previousX = game.coinX
                val speed = game.speed
                val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
                if (target < game.playerRight + game.speed * .22 && game.height == 0.0) game.jump()
                game.advance(1.0 / 60)
                if (game.coinPattern !== oldPattern) {
                    replacements++
                    assertTrue(game.obstacle >= 1.0)
                    assertTrue(game.coinPattern.all { game.coinX + it.offset - .022 >= 1.0 - speed / 60 - 1e-9 })
                    assertTrue(oldPattern.all { it.collected || previousX + it.offset + .022 <= speed / 60 + 1e-9 })
                }
            }
        }
        assertTrue(replacements > 10)
    }

    @Test fun separatedObstaclesAllowLandingAndAnotherJump() {
        var verified = false
        for (seed in 0 until 20) {
            val game = RunnerGame(random = kotlin.random.Random(seed))
            var pairStart = -1
            var jumps = 0
            for (step in 0 until 60 * 120) {
                if (game.over || game.stage > 5) break
                if ((game.secondOffset ?: 0.0) > .20 && pairStart < 0) pairStart = game.passed
                val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
                if (target < game.playerRight + game.speed * .22 && game.height == 0.0) { game.jump(); if (pairStart >= 0) jumps++ }
                game.advance(1.0 / 60)
                if (pairStart >= 0 && game.passed >= pairStart + 2) { verified = jumps >= 2; break }
            }
            if (verified) break
        }
        assertTrue("a seeded separated pair must allow landing, a second jump, and two passes", verified)
    }

    @Test fun pairedObstaclesCanBeClearedAndAwardTwoBonuses() {
        var verified = 0
        for (seed in 0 until 20) {
            val game = RunnerGame(random = kotlin.random.Random(seed))
            var pairStart = -1
            for (step in 0 until 60 * 120) {
                if (game.over || game.stage > 5) break
                if ((game.secondOffset ?: Double.MAX_VALUE) < .20 && pairStart < 0) pairStart = game.passed
                val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
                if (target < game.playerRight + game.speed * .22 && game.height == 0.0) game.jump()
                game.advance(1.0 / 60)
                if (pairStart >= 0 && game.passed >= pairStart + 2) { verified++; break }
            }
            if (verified > 0) break
        }
        assertTrue("a seeded paired pattern must be clearable and score twice", verified > 0)
    }

    @Test fun coinPatternsAreRepeatableAndAwardEachCoinOnlyOnce() {
        val game = RunnerGame(random = kotlin.random.Random(42))
        var sawMultiple = false
        var collected = 0
        repeat(60 * 90) {
            if (game.over || game.stage > 4) return@repeat
            sawMultiple = sawMultiple || game.coinPattern.size == 3
            val before = game.coins
            val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
            if (target < game.playerRight + game.speed * .22 && game.height == 0.0) game.jump()
            game.advance(1.0 / 60)
            collected += game.coins - before
            assertEquals(collected, game.coins)
            assertEquals(game.elapsed.toInt() + game.passed * 5 + collected * 10, game.score)
        }
        assertTrue(sawMultiple)
        assertTrue(collected > 0)
        assertFalse(game.over)
    }

    @Test fun seededRunsAreRepeatableAndProduceVariedPlayableObstacles() {
        fun sample(seed: Int): List<List<Number>> {
            val game = RunnerGame(random = kotlin.random.Random(seed))
            val encounters = mutableListOf<List<Number>>()
            var previous = -1
            repeat(60 * 120) {
                if (game.over || game.stage > 4) return@repeat
                if (game.passed != previous) {
                    previous = game.passed
                    encounters.add(listOf(game.stage, game.theme, game.obstacleKind, game.obstacleWidth, game.obstacleHeight))
                }
                val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
                if (target < game.playerRight + game.speed * .22 && game.height == 0.0) game.jump()
                game.advance(1.0 / 60)
            }
            assertFalse("seed $seed must remain playable through four stages", game.over)
            assertTrue(game.stage > 4)
            return encounters
        }
        val first = sample(42)
        assertEquals(first, sample(42))
        assertNotEquals(first, sample(43))
        assertTrue(first.map { it[2] }.distinct().size >= 2)
        assertTrue(first.map { it[3] }.distinct().size >= 2)
        assertTrue(first.map { it[1] }.distinct().size >= 2)
    }

    private fun playStage(game: RunnerGame, fps: Int) {
        val initialStage = game.stage
        repeat(fps * 30) {
            if (game.stage > initialStage || game.over) return
            val target = if (game.obstacle + game.obstacleWidth < game.playerLeft) game.obstacle + (game.secondOffset ?: 0.0) else game.obstacle
            if (target < game.playerRight + game.speed * .22 && game.height == 0.0) game.jump()
            game.advance(1.0 / fps)
        }
        fail("Stage did not end")
    }

    @Test fun exposedHitboxMatchesTheFirstCollision() {
        val game = RunnerGame(.055)
        repeat(720) {
            if (!game.over) {
                assertTrue(game.obstacle >= game.playerRight || game.obstacle + game.obstacleWidth <= game.playerLeft || game.height >= game.obstacleHeight)
                game.advance(1.0 / 240)
            }
        }
        assertTrue(game.over)
        assertTrue(game.obstacle < game.playerRight)
        assertTrue(game.obstacle + game.obstacleWidth > game.playerLeft)
        assertTrue(game.height < game.obstacleHeight && game.height + game.playerHeight > 0)
    }

    @Test fun delayedFrameIsBoundedAndInvalidTimeIsIgnored() {
        val game = RunnerGame()
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) game.advance(dt)
        assertEquals(0.0, game.elapsed, 0.0)
        game.advance(Double.MAX_VALUE)
        assertEquals(.25, game.elapsed, 1e-9)
        repeat(20) { game.advance(.25) }
        assertTrue(game.over)
        assertEquals(0, game.passed)
        assertEquals(0, game.coins)
        assertEquals(game.elapsed.toInt(), game.score)
    }

    @Test fun collisionFreezesScoresAndResetStartsFresh() {
        val game = RunnerGame()
        repeat(40) { game.advance(.1) }
        assertTrue(game.over)
        val score = game.score
        game.advance(1.0)
        game.jump()
        assertEquals(score, game.score)
        assertEquals(0.0, game.height, 0.0)
        assertEquals(0, RunnerGame().score)
        assertEquals(1, RunnerGame().stage)
    }

    @Test fun doubleJumpIsLimitedAndLandingAllowsAnotherJump() {
        val game = RunnerGame()
        game.jump()
        game.advance(.2)
        val velocity = game.verticalVelocity
        game.jump()
        assertTrue(game.verticalVelocity > velocity)
        val secondVelocity = game.verticalVelocity
        game.jump()
        assertEquals(secondVelocity, game.verticalVelocity, 0.0)
        repeat(7) { game.advance(.2) }
        assertEquals(0.0, game.height, 0.0)
        game.jump()
        assertTrue(game.verticalVelocity > 0)
    }

    @Test fun releasingJumpEarlyReducesHeight() {
        val short = RunnerGame()
        val long = RunnerGame()
        short.jump(); long.jump()
        short.advance(.1); long.advance(.1)
        short.releaseJump()
        short.advance(.2); long.advance(.2)
        assertTrue(short.height < long.height)
    }

    @Test fun stagesContinueAndSpeedIncreasesUntilCollision() {
        val game = RunnerGame()
        repeat(8) { stage ->
            val speed = game.speed
            playStage(game, 60)
            assertFalse("Collision in stage ${stage + 1}", game.over)
            assertEquals(stage + 2, game.stage)
            assertEquals((stage + 1) * 5, game.passed)
            assertTrue(game.speed > speed)
            assertEquals(game.elapsed.toInt() + game.passed * 5 + game.coins * 10, game.score)
        }
        repeat(1000) { game.advance(.05) }
        assertTrue(game.over)
    }

    @Test fun thirtySixtyAndOneTwentyFpsCanAdvanceBeyondThreeStages() {
        for (fps in listOf(30, 60, 120)) {
            val game = RunnerGame()
            repeat(4) { playStage(game, fps); assertFalse("Collision at $fps FPS", game.over) }
            assertEquals(5, game.stage)
            assertEquals(20, game.passed)
        }
    }

    @Test fun passingAwardsOneBonusAndObstacleContinuesOffscreen() {
        val game = RunnerGame()
        for (frame in 0..1200) {
            if (game.passed > 0 || game.over) break
            if (game.obstacle < .36 && game.obstacle > .30 && game.height == 0.0) game.jump()
            game.advance(1.0 / 240)
        }
        assertFalse(game.over)
        assertEquals(1, game.passed)
        assertTrue(game.obstacle < .15)
        val coins = game.coins
        game.advance(.1)
        assertEquals(1, game.passed)
        assertEquals(coins, game.coins)
        assertTrue(game.obstacle < .15)
    }

    @Test fun narrowBodiesCanAdvanceAtThirtyAndSixtyFps() {
        for (fps in listOf(30, 60)) for (halfWidth in listOf(.0226, .055)) {
            val game = RunnerGame(halfWidth)
            repeat(4) { playStage(game, fps); assertFalse(game.over) }
            assertEquals(5, game.stage)
        }
    }

    @Test fun rejectsInvalidPlayerCollisionWidths() {
        for (halfWidth in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -.01, .076)) {
            assertThrows(IllegalArgumentException::class.java) { RunnerGame(halfWidth) }
        }
    }

}
