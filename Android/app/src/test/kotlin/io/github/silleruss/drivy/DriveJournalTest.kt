package io.github.silleruss.drivy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveJournalTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun record(at: Long = now) = DriveRecord("record-$at", at, at + 1000, 0.05, true,
        1000, false, 1, 0, "collecting", DriveGoal.START)

    @Test fun checkpointsReplaceOneRecordAndRestartsNeverResumeIt() {
        val store = DriveJournalStore(folder.root)
        val recorder = DriveRecorder(now, DriveGoal.START)
        recorder.distance(1000.0, 1000, now)
        recorder.distance(1000.05, 2000, now + 1000)
        recorder.speed(0.0, 1000, now)
        recorder.speed(18.0, 2000, now + 1000)
        val first = recorder.snapshot("collecting")
        val journal = DriveJournal(true).upsert(first, now).upsert(recorder.snapshot("stopped"), now)
        assertEquals(1, journal.records.size)
        assertEquals(first.id, journal.records.single().id)
        assertEquals(0.05, first.distanceKm, 0.00001)
        assertEquals(1000L, first.coveredMs)
        assertTrue(store.save(journal.copy(records = listOf(first))))
        val loaded = store.load(now)
        assertEquals("interrupted", loaded.records.single().endReason)
        val next = DriveRecorder(now + 5000, DriveGoal.SLOW)
        next.distance(2000.0, 6000, now + 5000)
        assertEquals(0.0, next.snapshot("stopped").distanceKm, 0.0)
        assertNotEquals(first.id, next.snapshot("stopped").id)
    }

    @Test fun missingSpeedAndDistanceNeverCountAsNormalDriving() {
        val recorder = DriveRecorder(now, DriveGoal.NONE)
        recorder.speed(null, 500, now)
        assertFalse(recorder.hasSamples)
        recorder.distance(1000.0, 1000, now)
        recorder.distance(null, 2000, now + 1000)
        recorder.distance(1001.0, 3000, now + 2000)
        recorder.speed(0.0, 1000, now)
        recorder.speed(18.0, 2000, now + 1000)
        recorder.invalidateSpeed()
        recorder.speed(72.0, 10_000, now + 9000)
        val result = recorder.snapshot("disconnected")
        assertEquals(0.0, result.distanceKm, 0.0)
        assertEquals(1000L, result.coveredMs)
        assertTrue(result.hasGap)
        assertEquals(1, result.accelerations)
    }

    @Test fun staleAndImplausibleDistanceCannotIncreaseReportTotals() {
        val recorder = DriveRecorder(now, DriveGoal.NONE)
        recorder.distance(1000.0, 1000, now)
        recorder.distance(1001.0, 2000, now + 1000)
        recorder.distance(1002.0, 1000, now + 2000)
        val result = recorder.snapshot("stopped")
        assertEquals(0.0, result.distanceKm, 0.0)
        assertTrue(result.hasGap)
    }

    @Test fun userInputsNeverRewriteFactsAndUnknownDriversAreExcluded() {
        val original = record()
        assertFalse(original.includedInPersonalAnalysis)
        val mine = original.copy(driver = DriveDriver.ME)
        assertTrue(mine.includedInPersonalAnalysis)
        for (context in listOf(DriveContext.AVOIDANCE, DriveContext.UNUSUAL)) {
            val excluded = mine.copy(context = context, reflection = DriveReflection.DIFFERENT)
            assertFalse(excluded.includedInPersonalAnalysis)
            assertEquals(original.distanceKm, excluded.distanceKm, 0.0)
            assertEquals(original.accelerations, excluded.accelerations)
        }
        assertFalse(mine.copy(driver = DriveDriver.OTHER).includedInPersonalAnalysis)
    }

    @Test fun consentStopKeepsRecordsAndOptionalInputsRoundTrip() {
        val store = DriveJournalStore(folder.root)
        val record = record().copy(endReason = "stopped", driver = DriveDriver.ME,
            context = DriveContext.TRAFFIC, reflection = DriveReflection.HELPFUL, environment = DriveEnvironment.CITY)
        val journal = DriveJournal(false, DriveGoal.OBSERVE, listOf(record), DriveEnvironment.MIXED)
        assertTrue(store.save(journal))
        assertEquals(journal, store.load(now))
    }

    @Test fun oldJournalMigratesWithoutInventingEnvironmentAndNewRecordsKeepTheirOwnInput() {
        val store = DriveJournalStore(folder.root)
        val oldRecord = record().copy(endReason = "stopped")
        assertTrue(store.save(DriveJournal(true, records = listOf(oldRecord))))
        val path = File(folder.root, "drive-journal.json")
        val old = JSONObject(path.readText()).put("version", 1)
        old.remove("environment")
        old.getJSONArray("records").getJSONObject(0).remove("environment")
        path.writeText(old.toString())
        val loaded = store.load(now)
        assertEquals(DriveEnvironment.NONE, loaded.environment)
        assertEquals(oldRecord, loaded.records.single())
        val recorder = DriveRecorder(now, DriveGoal.START, DriveEnvironment.HIGHWAY)
        recorder.speed(36.0, 1000, now)
        val updated = loaded.copy(environment = DriveEnvironment.CITY).upsert(recorder.snapshot("stopped"), now)
        assertTrue(store.save(updated))
        val migrated = DriveJournalStore(folder.root).load(now)
        assertEquals(DriveEnvironment.CITY, migrated.environment)
        assertEquals(DriveEnvironment.HIGHWAY, migrated.records.last().environment)
        assertEquals(2, JSONObject(path.readText()).getInt("version"))
    }

    @Test fun retentionAndWeekOnlyUseTheirObservedRange() {
        val journal = DriveJournal(records = listOf(record(now - 31 * REPORT_DAY_MS),
            record(now - 10 * REPORT_DAY_MS), record(now - REPORT_DAY_MS)))
        assertEquals(2, journal.retained(now).records.size)
        assertEquals(1, journal.week(now).size)
        val records = (0..1000).map { record(now - it * 2000) }.reversed()
        assertEquals(1000, DriveJournal(records = records).retained(now).records.size)
    }

    @Test fun corruptRecordsArePreservedUntilExplicitDeletion() {
        val file = File(folder.root, "drive-journal.json").apply { writeText("{damaged") }
        val pet = File(folder.root, "pet-progress.json").apply { writeText("pet-must-survive") }
        val store = DriveJournalStore(folder.root)
        assertFalse(store.load(now).enabled)
        assertFalse(store.healthy)
        assertFalse(store.save(DriveJournal(true)))
        assertEquals("{damaged", file.readText())
        assertTrue(store.delete())
        assertTrue(store.healthy)
        assertEquals(DriveJournal(), store.load(now))
        assertEquals("pet-must-survive", pet.readText())
    }

    @Test fun failedAtomicWriteKeepsPreviousJournalUntilRetry() {
        val store = DriveJournalStore(folder.root)
        val first = DriveJournal(true)
        val next = DriveJournal(false, records = listOf(record().copy(endReason = "stopped")))
        assertTrue(store.save(first))
        val temporary = File(folder.root, "drive-journal.json.tmp").apply { assertTrue(mkdir()) }
        assertFalse(store.save(next))
        assertEquals(first, DriveJournalStore(folder.root).load(now))
        assertTrue(temporary.delete())
        assertTrue(store.save(next))
        assertEquals(next, store.load(now))
    }

    @Test fun failedDeletionCannotRestoreConsentOrRecordsAfterRestart() {
        val store = DriveJournalStore(folder.root)
        assertTrue(store.save(DriveJournal(true, records = listOf(record()))))
        val blocked = File(folder.root, "drive-journal.json.tmp").apply { assertTrue(mkdir()) }
        File(blocked, "blocked").writeText("prevent deletion")
        assertFalse(store.delete())
        val restarted = DriveJournalStore(folder.root)
        assertEquals(DriveJournal(), restarted.load(now))
        assertFalse(restarted.save(DriveJournal(true)))
        assertTrue(blocked.deleteRecursively())
        assertTrue(restarted.delete())
        assertEquals(DriveJournal(), DriveJournalStore(folder.root).load(now))
    }

    @Test fun invalidFieldsAndDuplicateIdsBlockCollection() {
        for ((key, value) in listOf("distanceKm" to -1, "coveredMs" to -1, "driver" to "INVALID", "endReason" to "complete",
            "accelerations" to 4_294_967_296L, "coveredMs" to 1.5, "hasDistance" to "true")) {
            val directory = folder.newFolder()
            val store = DriveJournalStore(directory)
            assertTrue(store.save(DriveJournal(true, records = listOf(record()))))
            val file = File(directory, "drive-journal.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("records").getJSONObject(0).put(key, value)
            file.writeText(json.toString())
            assertFalse(store.load(now).enabled)
            assertFalse("Invalid $key must block writes", store.healthy)
        }
        val store = DriveJournalStore(folder.newFolder())
        assertFalse(store.save(DriveJournal(true, records = listOf(record(), record()))))
    }

    @Test fun feedbackIsExplicitAboutCoverageAndDoesNotClassifyHabits() {
        assertTrue(reportFeedback(record()).contains("일부 구간"))
        assertTrue(reportFeedback(record().copy(hasGap = true)).contains("공백"))
        assertTrue(reportFeedback(null).contains("동의"))
    }
}
