package io.github.silleruss.drivy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal const val REPORT_DAY_MS = 86_400_000L
internal enum class DriveGoal(val label: String) {
    NONE("목표 보류"), START("부드러운 출발"), SLOW("여유 있는 감속"), OBSERVE("내 패턴 알아보기")
}
internal enum class DriveDriver(val label: String) { UNKNOWN("운전자 모름"), ME("내 기록"), OTHER("다른 운전자") }
internal enum class DriveContext(val label: String) {
    NONE("상황 입력 건너뛰기"), TRAFFIC("정체가 있었음"), AVOIDANCE("피해야 하는 상황"), UNUSUAL("평소와 다름"), UNKNOWN("잘 모르겠음")
}
internal enum class DriveReflection(val label: String) {
    NONE("회고 건너뛰기"), HELPFUL("도움 됨"), MIXED("애매함"), DIFFERENT("내 상황과 다름")
}
internal enum class DriveEnvironment(val label: String) {
    NONE("환경 선택 안 함"), CITY("시내 위주"), HIGHWAY("고속도로 위주"), MIXED("혼합 환경")
}

internal data class DriveRecord(
    val id: String, val startedAt: Long, val endedAt: Long, val distanceKm: Double,
    val hasDistance: Boolean, val coveredMs: Long, val hasGap: Boolean,
    val accelerations: Int, val decelerations: Int, val endReason: String,
    val goal: DriveGoal = DriveGoal.NONE, val driver: DriveDriver = DriveDriver.UNKNOWN,
    val context: DriveContext = DriveContext.NONE, val reflection: DriveReflection = DriveReflection.NONE,
    val environment: DriveEnvironment = DriveEnvironment.NONE
) {
    init {
        require(id.isNotBlank() && id.length <= 64 && startedAt > 0 && endedAt >= startedAt)
        require(distanceKm.isFinite() && distanceKm in 0.0..DistanceGrowth.MAXIMUM_TOTAL_KM)
        require(coveredMs in 0..30 * REPORT_DAY_MS && accelerations in 0..1_000_000 && decelerations in 0..1_000_000)
        require(endReason in listOf("collecting", "stopped", "disconnected", "interrupted", "paused"))
    }
    val includedInPersonalAnalysis get() = driver == DriveDriver.ME && context !in listOf(DriveContext.AVOIDANCE, DriveContext.UNUSUAL)
}

internal data class DriveJournal(val enabled: Boolean = false, val goal: DriveGoal = DriveGoal.NONE,
                                 val records: List<DriveRecord> = emptyList(), val environment: DriveEnvironment = DriveEnvironment.NONE) {
    fun retained(now: Long) = copy(records = records.filter { it.endedAt >= now - 30 * REPORT_DAY_MS }.takeLast(1000))
    fun upsert(record: DriveRecord, now: Long) = copy(records = records.filterNot { it.id == record.id } + record).retained(now)
    fun week(now: Long) = records.filter { it.endedAt in (now - 7 * REPORT_DAY_MS)..now }
}

/** One foreground collection interval; never reconstructs an unobserved journey. */
internal class DriveRecorder(val startedAt: Long, private val goal: DriveGoal, private val environment: DriveEnvironment = DriveEnvironment.NONE) {
    private val id = UUID.randomUUID().toString()
    private val speed = DriveReportAccumulator()
    private val distance = DistanceGrowth()
    private var hasDistance = false
    private var hasGap = false
    private var endedAt = startedAt
    var hasSamples = false
        private set

    fun speed(value: Double?, elapsedMs: Long, now: Long) {
        if (speed.observeSpeed(value, elapsedMs)) {
            hasSamples = true
            endedAt = maxOf(endedAt, now)
        }
    }

    fun distance(value: Double?, elapsedMs: Long, now: Long) {
        val result = distance.observe(value, elapsedMs)
        if (result in listOf("baseline", "unchanged", "grown")) {
            hasDistance = hasDistance || result != "baseline"
            hasSamples = true
            endedAt = maxOf(endedAt, now)
        } else if (result != "out_of_order") hasGap = true
    }

    fun invalidateSpeed() = speed.invalidate()
    fun invalidateDistance() { distance.invalidate(); hasGap = true }
    fun snapshot(reason: String) = DriveRecord(id, startedAt, endedAt, distance.totalKm, hasDistance,
        speed.coveredMs, hasGap || speed.hasGap, speed.accelerations, speed.decelerations, reason, goal, environment = environment)
}

/** Separate from pet saves: deleting a report can never reset the companion. Calls run on the save executor. */
internal class DriveJournalStore(directory: File) {
    private val path = File(directory, "drive-journal.json")
    private val temporary = File(directory, "drive-journal.json.tmp")
    private val deletion = File(directory, "drive-journal.delete-pending")
    var healthy = true
        private set
    var deletionPending = false
        private set

    fun load(now: Long): DriveJournal = try {
        deletionPending = deletion.exists()
        if (deletionPending || !path.exists()) DriveJournal() else {
            require(path.isFile && path.length() <= 2_000_000)
            val json = JSONObject(path.readText())
            val version = json.integer("version")
            require(version in 1L..2L)
            val records = json.getJSONArray("records")
            require(records.length() <= 1000)
            val loaded = (0 until records.length()).map { index ->
                val r = records.getJSONObject(index)
                DriveRecord(r.getString("id"), r.integer("startedAt"), r.integer("endedAt"), (r.get("distanceKm") as Number).toDouble(),
                    r.get("hasDistance") as Boolean, r.integer("coveredMs"), r.get("hasGap") as Boolean,
                    r.integer("accelerations").also { require(it in 0..1_000_000) }.toInt(),
                    r.integer("decelerations").also { require(it in 0..1_000_000) }.toInt(),
                    r.getString("endReason").let { if (it == "collecting") "interrupted" else it },
                    DriveGoal.valueOf(r.getString("goal")), DriveDriver.valueOf(r.getString("driver")),
                    DriveContext.valueOf(r.getString("context")), DriveReflection.valueOf(r.getString("reflection")),
                    if (version == 1L) DriveEnvironment.NONE else DriveEnvironment.valueOf(r.getString("environment")))
            }
            require(loaded.map { it.id }.distinct().size == loaded.size)
            DriveJournal(json.get("enabled") as Boolean, DriveGoal.valueOf(json.getString("goal")), loaded,
                if (version == 1L) DriveEnvironment.NONE else DriveEnvironment.valueOf(json.getString("environment"))).retained(now)
        }
    } catch (_: Exception) { healthy = false; DriveJournal() }

    fun save(journal: DriveJournal): Boolean {
        if (!healthy || deletionPending || deletion.exists()) return false
        return try {
            check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
            val records = JSONArray()
            for (r in journal.records) records.put(JSONObject().put("id", r.id).put("startedAt", r.startedAt)
                .put("endedAt", r.endedAt).put("distanceKm", r.distanceKm).put("hasDistance", r.hasDistance)
                .put("coveredMs", r.coveredMs).put("hasGap", r.hasGap).put("accelerations", r.accelerations)
                .put("decelerations", r.decelerations).put("endReason", r.endReason).put("goal", r.goal.name)
                .put("driver", r.driver.name).put("context", r.context.name).put("reflection", r.reflection.name)
                .put("environment", r.environment.name))
            val bytes = JSONObject().put("version", 2).put("enabled", journal.enabled).put("goal", journal.goal.name)
                .put("environment", journal.environment.name)
                .put("records", records).toString().toByteArray(Charsets.UTF_8)
            require(journal.records.size <= 1000 && bytes.size <= 2_000_000 && journal.records.map { it.id }.distinct().size == journal.records.size)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(path))
            true
        } catch (_: Exception) { false }
    }

    fun delete(): Boolean = try {
        check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
        // Persist intent before unlinking: a failed deletion must never restore old consent.
        FileOutputStream(deletion).use { it.write(1); it.fd.sync() }
        deletionPending = true
        check(!temporary.exists() || temporary.delete())
        check(!path.exists() || path.delete())
        check(deletion.delete())
        deletionPending = false
        healthy = true
        true
    } catch (_: Exception) { false }

    private fun JSONObject.integer(key: String) = (get(key) as Number).toString().toLong()
}
