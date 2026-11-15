package io.github.silleruss.drivy

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RunnerRecord(val score: Int, val companion: Companion)

private val runnerScoresLock = Any()

internal class RunnerScores(directory: File, name: String = "runner-scores.json") {
    private val file = AtomicFile(File(directory, name))
    fun load(): List<RunnerRecord> = synchronized(runnerScoresLock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val bytes = file.readFully()
        require(bytes.size <= 4096)
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        require(array.length() <= 5)
        (0 until array.length()).map {
            val item = array.getJSONObject(it)
            val score = item.getInt("score")
            require(score >= 0)
            RunnerRecord(score, Companion.valueOf(item.getString("companion")))
        }.sortedByDescending { it.score }
    }
    fun add(record: RunnerRecord): List<RunnerRecord> = synchronized(runnerScoresLock) {
        val records = (load() + record).sortedByDescending { it.score }.take(5)
        val array = JSONArray()
        records.forEach { array.put(JSONObject().put("score", it.score).put("companion", it.companion.name)) }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        records
    }
}
