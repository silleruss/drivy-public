package io.github.silleruss.drivy

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest
import java.util.Base64

data class PetProgress(val totalKm: Double = 0.0, val experience: Double = totalKm * 100,
                       val companion: Companion = Companion.entries.random(), val drivingPenalty: Double = 0.0) {
    init {
        require(totalKm.isFinite() && totalKm in 0.0..DistanceGrowth.MAXIMUM_TOTAL_KM)
        require(experience.isFinite() && experience in 0.0..2_000_000_000.0)
        require(drivingPenalty.isFinite() && drivingPenalty in 0.0..100.0)
    }
}

/** Internal storage; imports Unity's external-files save without changing its originals. */
class PetSaveStore(private val directory: File, private val legacyDirectory: File? = null) {
    var canSave = true
        private set
    var status = "new"
        private set

    fun load(legacyCompanion: Companion? = null): PetProgress {
        val assigned = legacyCompanion ?: Companion.entries.random()
        val local = files(directory)
        val candidates = if (local.any { it.exists() }) local
            else local + (legacyDirectory?.let(::files) ?: emptyList())
        for ((index, file) in candidates.withIndex()) {
            read(file, assigned)?.let {
                status = if (index >= 2) "imported_unity" else if (index == 1) "recovered_backup" else "loaded"
                return it
            }
        }
        if (candidates.any { it.exists() }) {
            canSave = false
            status = "corrupt"
        }
        return PetProgress(companion = assigned)
    }

    fun save(progress: PetProgress): Boolean {
        if (!canSave) return false
        val (path, backup) = files(directory)
        val temporary = File(directory, "pet-progress.json.tmp")
        val backupTemporary = File(directory, "pet-progress.json.bak.tmp")
        return try {
            check(directory.isDirectory || directory.mkdirs())
            val data = JSONObject().put("version", 4).put("totalKm", progress.totalKm)
                .put("experience", progress.experience).put("companion", progress.companion.name)
                .put("drivingPenalty", progress.drivingPenalty)
                .put("checksum", checksum("4|${progress.totalKm}|${progress.experience}|${progress.companion.name}|${progress.drivingPenalty}"))
                .toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
            // Never replace a good backup with the damaged primary recovered during load.
            if (read(path) != null) {
                FileOutputStream(backupTemporary).use { it.write(path.readBytes()); it.fd.sync() }
                check(backupTemporary.renameTo(backup))
            }
            check(temporary.renameTo(path))
            status = "saved"
            true
        } catch (_: Exception) {
            status = "save_failed"
            false
        }
    }

    private fun files(base: File) = listOf(File(base, "pet-progress.json"), File(base, "pet-progress.json.bak"))

    private fun read(file: File, legacyCompanion: Companion = Companion.TORI): PetProgress? = try {
        if (!file.isFile || file.length() > 4096) null
        else {
            val json = JSONObject(file.readText())
            val value = json.get("totalKm") as? Number
            val total = value?.toDouble()
            val versionNumber = (json.get("version") as? Number)?.toDouble()
            val version = when (versionNumber) { 1.0 -> 1; 2.0 -> 2; 3.0 -> 3; 4.0 -> 4; else -> -1 }
            if (total == null || !total.isFinite() || total !in 0.0..DistanceGrowth.MAXIMUM_TOTAL_KM) null
            else if (version == 3 || version == 4) {
                val experience = (json.get("experience") as? Number)?.toDouble()
                val savedCompanion = json.getString("companion")
                val companion = Companion.fromSavedName(savedCompanion)
                val penalty = (json.get("drivingPenalty") as? Number)?.toDouble()
                if (experience == null || !experience.isFinite() || experience !in 0.0..2_000_000_000.0 || companion == null || penalty == null ||
                    checksum("$version|$total|$experience|$savedCompanion|$penalty") != json.getString("checksum")) null
                else PetProgress(total, if (version == 3) migrateXp(experience) else experience, companion, penalty)
            }
            else {
                val representations = when (version) {
                    2 -> listOf(total.toString())
                    1 -> legacyRepresentations(total)
                    else -> emptyList()
                }
                if (representations.any { checksum("$version|$it") == json.getString("checksum") })
                    PetProgress(total, migrateXp(total * 100), legacyCompanion) else null
            }
        }
    } catch (_: Exception) { null }

    private fun migrateXp(xp: Double): Double = when {
        xp < 1000 -> xp * 50
        xp < 5000 -> 50_000 + (xp - 1000) * 62.5
        else -> (300_000 + xp - 5000).coerceAtMost(2_000_000_000.0)
    }

    private fun legacyRepresentations(value: Double): List<String> {
        // Unity/Mono's invariant R formatter uses G15 or G17; newer runtimes use shortest round-trip.
        fun general(text: String): String {
            val number = BigDecimal(text).stripTrailingZeros()
            val exponent = number.precision() - number.scale() - 1
            return if (exponent >= -4 && exponent < 15) number.toPlainString()
            else number.movePointLeft(exponent).toPlainString() + "E" +
                (if (exponent >= 0) "+" else "-") + kotlin.math.abs(exponent).toString().padStart(2, '0')
        }
        return listOf(value.toString(), BigDecimal(value).round(MathContext(15)).toString(),
            BigDecimal(value).round(MathContext(17)).toString()).flatMap {
            listOf(general(it), BigDecimal(it).stripTrailingZeros().toPlainString())
        }.filter { it.toDouble() == value }
    }

    private fun checksum(value: String) = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
