package io.github.silleruss.drivy

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RunnerScoresTest {
    @Test fun failedWritePreservesExistingRanking() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "runner-write-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            val scores = RunnerScores(dir)
            scores.add(RunnerRecord(42, Companion.NURI))
            val primary = File(dir, "runner-scores.json")
            val before = primary.readBytes()
            val blocked = File(dir, "runner-scores.json.new")
            assertTrue(blocked.mkdir())
            File(blocked, "keep").writeText("block")
            try { scores.add(RunnerRecord(100, Companion.MOKA)); fail("expected write failure") } catch (_: java.io.IOException) { }
            assertArrayEquals(before, primary.readBytes())
            assertEquals(listOf(RunnerRecord(42, Companion.NURI)), scores.load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun rankingPersistsAndPreservesCorruptData() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "runner-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        try {
            val scores = RunnerScores(dir)
            listOf(5, 9, 9, 2, 8, 7).forEach { scores.add(RunnerRecord(it, Companion.TORI)) }
            assertEquals(listOf(9, 9, 8, 7, 5), RunnerScores(dir).load().map { it.score })
            val file = File(dir, "runner-scores.json")
            file.writeText("broken")
            try { scores.add(RunnerRecord(20, Companion.MOKA)); fail("expected corrupt data") } catch (_: org.json.JSONException) { }
            assertEquals("broken", file.readText())
        } finally { dir.deleteRecursively() }
    }
}
