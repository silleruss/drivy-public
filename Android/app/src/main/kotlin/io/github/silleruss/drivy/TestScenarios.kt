package io.github.silleruss.drivy

import java.io.File

/** Isolated fixtures never overwrite the player's files. */
internal fun testStorageScenario(cache: File): String {
    val directory = java.nio.file.Files.createTempDirectory(cache.toPath(), "drivy-test-").toFile()
    try {
        val store = PetSaveStore(directory)
        val progress = PetProgress(12.5, 50000.0, Companion.NERU, 25.0)
        check(store.save(progress))
        check(store.save(progress.copy(experience = 300000.0)))
        File(directory, "pet-progress.json").writeText("broken")
        val recovered = PetSaveStore(directory)
        check(recovered.load() == progress && recovered.status == "recovered_backup")
        File(directory, "pet-progress.json.bak").writeText("broken")
        val corrupt = PetSaveStore(directory)
        corrupt.load()
        check(!corrupt.canSave && !corrupt.save(progress))
        return "통과: 별도 임시 저장의 백업 복구·손상 저장 차단. 현재 동행 기록은 유지됩니다."
    } finally { directory.deleteRecursively() }
}

internal fun testDrivingScenario(): String {
    val growth = DistanceGrowth()
    check(growth.observe(100.0, 0) == "baseline")
    check(growth.observe(100.05, 1000, 100) == "grown")
    val xp = growth.experience
    check(growth.observe(200.0, 2000) == "implausible_jump")
    growth.invalidate()
    check(growth.observe(200.1, 3000) == "baseline")
    check(growth.experience == xp)
    val score = DrivingScore()
    for (ms in 0L..12000L step 1000) score.observe(60.0, ms)
    check(score.current(12000) == 100)
    check(score.current(15000) == null)
    return "통과: 합성 모델의 정상 성장·급증 거부·재연결 기준점·운전 점수 만료. 실제 SDK 검사가 아닙니다."
}

internal fun syntheticDrive(distanceKm: Double, seconds: Double): DistanceGrowth {
    require(distanceKm.isFinite() && distanceKm in 0.0..100.0)
    require(seconds.isFinite() && seconds in 1.0..1800.0)
    return DistanceGrowth().also {
        it.observe(1000.0, 0)
        it.observe(1000.0 + distanceKm, (seconds * 1000).toLong())
    }
}
