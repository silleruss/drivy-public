package io.github.silleruss.drivy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class PetSaveStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldExperienceRetainsStageAndProgressAfterMigration() {
        for ((oldXp, newXp) in listOf(500.0 to 25_000.0, 1000.0 to 50_000.0,
            3000.0 to 175_000.0, 5000.0 to 300_000.0, 6000.0 to 301_000.0)) {
            val directory = temporary.newFolder()
            File(directory, "pet-progress.json").writeText(signedProgress(60.0, oldXp, "TORI"))
            val store = PetSaveStore(directory)
            val migrated = store.load()
            assertEquals(newXp, migrated.experience, 0.0)
            assertTrue(store.save(migrated))
            assertEquals(migrated, PetSaveStore(directory).load())
        }
    }

    @Test fun saveReloadAndBackupRecoveryPreserveTheGoodBackup() {
        val directory = temporary.newFolder()
        val store = PetSaveStore(directory)
        assertEquals(0.0, store.load().totalKm, 0.0)
        assertTrue(store.save(PetProgress(12.25, companion = Companion.PIKO)))
        assertTrue(store.save(PetProgress(13.5, companion = Companion.PIKO)))
        assertEquals(13.5, PetSaveStore(directory).load().totalKm, 0.0)
        val primary = File(directory, "pet-progress.json")
        primary.writeText("{broken")
        val recovery = PetSaveStore(directory)
        assertEquals(12.25, recovery.load().totalKm, 0.0)
        assertEquals("recovered_backup", recovery.status)
        assertTrue(recovery.save(PetProgress(12.5, companion = Companion.PIKO)))
        assertEquals(12.5, PetSaveStore(directory).load().totalKm, 0.0)
        primary.writeText("{broken-again")
        assertEquals(12.25, PetSaveStore(directory).load().totalKm, 0.0)
    }

    @Test fun bothCorruptArePreservedAndFutureSavesBlocked() {
        val directory = temporary.newFolder()
        val primary = File(directory, "pet-progress.json").apply { writeText("{broken") }
        File(directory, "pet-progress.json.bak").writeText("{}")
        val store = PetSaveStore(directory)
        assertEquals(0.0, store.load().totalKm, 0.0)
        assertFalse(store.canSave)
        assertEquals("corrupt", store.status)
        assertFalse(store.save(PetProgress(1.0, companion = Companion.PIKO)))
        assertEquals("{broken", primary.readText())
    }

    @Test fun interruptedWriteAndIoFailure() {
        val directory = temporary.newFolder()
        File(directory, "pet-progress.json.tmp").writeText("partial")
        assertEquals(0.0, PetSaveStore(directory).load().totalKm, 0.0)
        val blocked = temporary.newFile()
        val store = PetSaveStore(blocked)
        assertFalse(store.save(PetProgress(1.0, companion = Companion.PIKO)))
        assertEquals("save_failed", store.status)
        assertEquals(0L, blocked.length())
    }

    @Test fun importsUnityWithoutTouchingItsOriginals() {
        for (representation in listOf("0", "0.6875", "12", "9.9999999999999982", "1E-05")) {
            val local = temporary.newFolder()
            val legacy = temporary.newFolder()
            val original = File(legacy, "pet-progress.json")
            original.writeText(legacyJson(representation))
            val content = original.readText()
            val store = PetSaveStore(local, legacy)
            assertEquals(representation.toDouble(), store.load().totalKm, 0.0)
            assertEquals("imported_unity", store.status)
            assertTrue(store.save(PetProgress(representation.toDouble() + .01, companion = Companion.PIKO)))
            assertEquals(content, original.readText())
            assertEquals(representation.toDouble() + .01, PetSaveStore(local, legacy).load().totalKm, 0.0)
        }
    }

    @Test fun corruptLegacyAndNewerLocalNeverFallBackToOldProgress() {
        val local = temporary.newFolder()
        val legacy = temporary.newFolder()
        File(legacy, "pet-progress.json").writeText("{broken")
        val corrupt = PetSaveStore(local, legacy)
        assertEquals(0.0, corrupt.load().totalKm, 0.0)
        assertFalse(corrupt.canSave)
        File(legacy, "pet-progress.json").writeText(legacyJson("12"))
        File(local, "pet-progress.json").writeText("{newer-broken")
        val newer = PetSaveStore(local, legacy)
        assertEquals(0.0, newer.load().totalKm, 0.0)
        assertFalse(newer.canSave)
    }

    @Test fun rejectsTamperedWrongVersionStringAndOversizeSaves() {
        val directory = temporary.newFolder()
        val file = File(directory, "pet-progress.json")
        val valid = legacyJson("2")
        for (bad in listOf(valid.replace("\"version\":1", "\"version\":9"),
            valid.replace("\"totalKm\":2", "\"totalKm\":3"),
            JSONObject(valid).put("totalKm", "2").toString(), " ".repeat(4097))) {
            file.writeText(bad)
            val store = PetSaveStore(directory)
            assertEquals(0.0, store.load().totalKm, 0.0)
            assertFalse(store.canSave)
        }
    }

    @Test fun assignedCompanionAndReducedExperienceSurviveReloadAndBackupRecovery() {
        val directory = temporary.newFolder()
        val store = PetSaveStore(directory)
        val initial = store.load()
        assertTrue(initial.companion in Companion.entries)
        assertTrue(store.save(initial))
        assertEquals(initial, PetSaveStore(directory).load(Companion.NERU))
        val grown = initial.copy(totalKm = 12.0, experience = 1500.0)
        assertTrue(store.save(grown))
        val reduced = grown.copy(experience = 850.0, drivingPenalty = 45.5)
        assertTrue(store.save(reduced))
        assertEquals(reduced, PetSaveStore(directory).load())
        File(directory, "pet-progress.json").writeText("{broken")
        assertEquals(grown, PetSaveStore(directory).load())
    }

    @Test fun oldSavesMigrateDistanceToExperienceAndKeepExistingCompanion() {
        for (version in listOf(1, 2)) {
            val directory = temporary.newFolder()
            File(directory, "pet-progress.json").writeText(legacyJson("12.25", version))
            val store = PetSaveStore(directory)
            val progress = store.load(Companion.NERU)
            assertEquals(PetProgress(12.25, 64062.5, Companion.NERU), progress)
            assertTrue(store.save(progress))
            assertEquals(4, JSONObject(File(directory, "pet-progress.json").readText()).getInt("version"))
            assertEquals(progress, PetSaveStore(directory).load(Companion.PIKO))
        }
    }

    @Test fun invalidExperienceOrCompanionCannotReplaceGoodProgress() {
        val directory = temporary.newFolder()
        val store = PetSaveStore(directory)
        assertTrue(store.save(PetProgress(12.0, 250.0, Companion.PIKO)))
        val file = File(directory, "pet-progress.json")
        val valid = file.readText()
        val invalid = listOf(
            JSONObject(valid).put("experience", 300.0).toString(),
            JSONObject(valid).put("experience", "250").toString(),
            JSONObject(valid).put("drivingPenalty", 30.0).toString(),
            JSONObject(valid).put("drivingPenalty", "0").toString(),
            signedProgress(12.0, -1.0, "BORI"),
            signedProgress(12.0, 2_000_000_001.0, "BORI"),
            signedProgress(12.0, 250.0, "removed_companion"),
            signedProgress(12.0, 250.0, "BORI", -1.0),
            signedProgress(12.0, 250.0, "BORI", 101.0)
        )
        for (bad in invalid) {
            file.writeText(bad)
            val corrupted = PetSaveStore(directory)
            assertEquals(0.0, corrupted.load().totalKm, 0.0)
            assertFalse(corrupted.canSave)
            assertFalse(corrupted.save(PetProgress(companion = Companion.TORI)))
            assertEquals(bad, file.readText())
        }
        for (experience in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 2_000_000_001.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                PetProgress(experience = experience, companion = Companion.TORI)
            }
        }
        for (penalty in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 101.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                PetProgress(companion = Companion.TORI, drivingPenalty = penalty)
            }
        }
    }

    @Test fun referenceCharactersAndLegacyNamesKeepProgressAndValidateOriginalChecksum() {
        for ((saved, companion) in Companion.entries.map { it.name to it } +
            listOf("BORI" to Companion.PIKO, "NABI" to Companion.NERU)) {
            val directory = temporary.newFolder()
            val file = File(directory, "pet-progress.json")
            file.writeText(signedProgress(12.25, 850.0, saved, 45.5))
            val store = PetSaveStore(directory)
            val expected = PetProgress(12.25, 42500.0, companion, 45.5)
            assertEquals(expected, store.load())
            assertTrue(store.canSave)
            assertTrue(store.save(expected))
            assertEquals(expected, PetSaveStore(directory).load())
            assertEquals(companion.name, JSONObject(file.readText()).getString("companion"))
            file.writeText(signedProgress(12.25, 850.0, saved, 45.5).replace("850", "851"))
            File(directory, "pet-progress.json.bak").delete()
            val corrupt = PetSaveStore(directory)
            corrupt.load()
            assertFalse("Legacy names must not bypass checksum checks", corrupt.canSave)
        }
    }

    private fun signedProgress(total: Double, experience: Double, companion: String, penalty: Double = 0.0): String {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest("3|$total|$experience|$companion|$penalty".toByteArray()))
        return JSONObject().put("version", 3).put("totalKm", total).put("experience", experience)
            .put("companion", companion).put("drivingPenalty", penalty).put("checksum", hash).toString()
    }

    private fun legacyJson(representation: String, version: Int = 1): String {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest("$version|$representation".toByteArray()))
        return "{\"version\":$version,\"totalKm\":$representation,\"checksum\":\"$hash\"}"
    }
}
