package io.github.silleruss.drivy

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

internal data class RunnerCoin(val offset: Double, val height: Double, var collected: Boolean = false)

internal class RunnerGame(private val playerHalfWidth: Double = .075, private val random: Random = Random.Default) {
    init {
        require(playerHalfWidth.isFinite() && playerHalfWidth > 0 && playerHalfWidth <= .075)
    }

    val playerLeft get() = .225 - playerHalfWidth
    val playerRight get() = .225 + playerHalfWidth
    val playerHeight = .18
    var elapsed = 0.0; private set
    var runElapsed = 0.0; private set
    var height = 0.0; private set
    var verticalVelocity = 0.0; private set
    var obstacle = 1.1; private set
    var passed = 0; private set
    var coins = 0; private set
    var stage = 1; private set
    var stagePassed = 0; private set
    var theme = 0; private set
    var obstacleKind = random.nextInt(3); private set
    private var obstacleVariant = random.nextInt(3)
    private var wideObstacle = false
    var secondOffset: Double? = null; private set
    private var secondPassed = false
    var distance = 0.0; private set
    var over = false; private set
    var coinPattern: List<RunnerCoin> = listOf(RunnerCoin(0.0, .36)); private set
    val coinCollected get() = coinPattern.first().collected
    private var obstaclePassed = false
    private var pendingTime = 0.0
    private var jumps = 0
    val obstaclesPerStage = 5
    val speed get() = .35 + (stage - 1) * .05
    val obstacleWidth get() = if (wideObstacle) .08 else .04 + obstacleVariant * .01
    val obstacleHeight get() = if (wideObstacle) .07 else .07 + obstacleVariant * .02
    val coinX get() = obstacle + obstacleWidth / 2
    val coinHeight get() = .36
    val progress get() = (stagePassed.toDouble() / obstaclesPerStage).coerceAtMost(1.0)
    val score get() = elapsed.toInt() + passed * 5 + coins * 10

    fun jump() {
        if (!over && jumps < 2) { verticalVelocity = 1.5; jumps++ }
    }

    fun releaseJump() { if (verticalVelocity > .55) verticalVelocity = .55 }

    fun advance(seconds: Double) {
        if (over || !seconds.isFinite() || seconds <= 0) return
        // Bound catch-up work after a stalled frame; physics never skips collision steps.
        pendingTime += min(seconds, .25)
        val dt = 1.0 / 240
        while (pendingTime + 1e-12 >= dt && !over) {
            pendingTime -= dt
            elapsed += dt
            val wasAirborne = height > 0.0 || verticalVelocity > 0.0
            height = (height + verticalVelocity * dt).coerceAtLeast(0.0)
            verticalVelocity = if (height == 0.0 && verticalVelocity < 0) 0.0 else verticalVelocity - 3.0 * dt
            if (height == 0.0 && verticalVelocity == 0.0) jumps = 0
            runElapsed = if (height > 0.0 || wasAirborne) 0.0 else runElapsed + dt
            val travelled = speed * dt
            distance += travelled
            obstacle -= travelled
            if (obstacle < playerRight && obstacle + obstacleWidth > playerLeft && height < obstacleHeight && height + playerHeight > 0) {
                over = true
                break
            }
            val second = secondOffset?.let { obstacle + it }
            if (second != null && second < playerRight && second + .04 > playerLeft && height < .07) {
                over = true
                break
            }
            for (coin in coinPattern) if (!coin.collected && coinX + coin.offset in playerLeft..playerRight && abs(height + .05 - coin.height) < .09) {
                coins++
                coin.collected = true
            }
            if (!obstaclePassed && obstacle + obstacleWidth < playerLeft) {
                passed++
                obstaclePassed = true
            }
            if (second != null && !secondPassed && second + .04 < playerLeft) {
                passed++
                secondPassed = true
            }
            if (obstacle + obstacleWidth < 0 && (second == null || second + .04 < 0) &&
                coinPattern.all { it.collected || coinX + it.offset + .022 < 0 }) {
                stagePassed += if (second == null) 1 else 2
                if (stagePassed == obstaclesPerStage) {
                    stage++
                    stagePassed = 0
                    theme = (theme + 1 + random.nextInt(2)) % 3
                }
                resetObstacle()
            }
        }
    }

    private fun resetObstacle() {
        obstacle = 1.05 + random.nextDouble() * .30
        obstacleKind = (obstacleKind + 1 + random.nextInt(2)) % 3
        obstacleVariant = maxOf(random.nextInt(3), min((stage - 1) / 2, 2))
        wideObstacle = stage >= 2 && random.nextInt(4) == 0
        obstaclePassed = false
        secondPassed = false
        // Require the entire group to fit within a conservative airborne interval.
        val candidateOffset = obstacleWidth + .018
        val span = candidateOffset + .04 + 2 * playerHalfWidth
        secondOffset = if (stage >= 2 && stagePassed <= 3 && random.nextInt(6) < min(stage - 1, 4)) {
            if (span / speed <= .70 && random.nextBoolean()) candidateOffset
            else obstacleWidth + 2 * playerHalfWidth + speed * 1.15
        } else null
        val spacing = speed * .18
        coinPattern = when (random.nextInt(3)) {
            0 -> listOf(RunnerCoin(0.0, .36))
            1 -> listOf(RunnerCoin(-spacing, .24), RunnerCoin(0.0, .36), RunnerCoin(spacing, .24))
            else -> listOf(RunnerCoin(-.50 - spacing, .05), RunnerCoin(-.50, .05), RunnerCoin(-.50 + spacing, .05))
        }
        obstacle = maxOf(obstacle, 1.022 - obstacleWidth / 2 - coinPattern.minOf { it.offset })
    }
}
