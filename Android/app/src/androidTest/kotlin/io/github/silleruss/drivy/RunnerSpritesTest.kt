package io.github.silleruss.drivy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RunnerSpritesTest {
    @Test fun runningLoopsKeepTheSameImageAnchorAcrossTheSeam() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (character in Companion.entries) for (stage in 0..2) {
            val sprites = runBlocking { loadRunnerSprites(assets, character, stage) }
            fun anchor(frame: RunnerFrame): Pair<Float, Float> {
                val bitmap = frame.image.asAndroidBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val subject = pixels.indices.filter { pixels[it] ushr 24 >= 64 }
                val left = subject.minOf { it % bitmap.width }
                val right = subject.maxOf { it % bitmap.width }
                val top = subject.minOf { it / bitmap.width }
                return (left + right + 1) / 2f - frame.originX to top - frame.groundY
            }
            val first = anchor(sprites.running.first())
            val last = anchor(sprites.running.last())
            for (frames in listOf(sprites.running, sprites.jumping)) frames.forEach { it.image.asAndroidBitmap().recycle() }
            assertEquals("$character stage $stage horizontal camera must not snap at loop wrap", first.first, last.first, 1f)
            assertEquals("$character stage $stage vertical camera must not snap at loop wrap", first.second, last.second, 1f)
        }
    }

    @Test fun sceneryHasThreeBackgroundsAndTransparentObstaclesAndCoin() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val scenery = loadRunnerScenery(assets)
        assertEquals(3, scenery.backgrounds.size)
        assertEquals(3, scenery.obstacles.size)
        for (image in scenery.backgrounds) {
            assertTrue("landscape must be wide", image.width > image.height)
            assertTrue("background must support the large game view", image.width >= 1024)
            image.asAndroidBitmap().recycle()
        }
        for (image in scenery.obstacles + scenery.coin) {
            val bitmap = image.asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("object sprite must not be empty", pixels.count { it ushr 24 >= 128 } > pixels.size / 4)
            assertTrue("object corners must remain transparent", pixels.any { it ushr 24 == 0 })
            bitmap.recycle()
        }
    }

    @Test fun allCharactersAndStagesHaveThirtyUsableFramesPerMotion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets
        val preview = Bitmap.createBitmap(1800, 1800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview).apply { drawColor(Color.rgb(30, 62, 54)) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f }
        for (character in Companion.entries) for (stage in 0..2) {
            val sprites = runBlocking { loadRunnerSprites(assets, character, stage) }
            run {
                assertEquals(sprites.running.first().referenceHeight, sprites.jumping.first().referenceHeight, 1f)
                assertEquals(sprites.running.first().groundY, sprites.jumping.first().groundY, 1f)
            }
            val left = stage * 600f
            val top = character.ordinal * 300f
            canvas.drawText("${character.name} ${stage + 1}  RUN / JUMP", left + 10, top + 25, textPaint)
            for ((index, frame) in listOf(sprites.running[0], sprites.jumping[15]).withIndex()) {
                val b = frame.image.asAndroidBitmap()
                val scale = 170f / frame.referenceHeight
                val x = left + index * 300 + 150 - frame.originX * scale
                val y = top + 270 - frame.groundY * scale
                canvas.drawBitmap(b, null, RectF(x, y, x + b.width * scale, y + b.height * scale), paint)
            }
            for (frames in listOf(sprites.running, sprites.jumping)) {
                assertEquals(30, frames.size)
                assertEquals("one fixed scale for the entire motion", 1, frames.map { it.referenceHeight }.distinct().size)
                assertTrue("cell width must not follow each pose", frames.maxOf { it.image.width } - frames.minOf { it.image.width } <= 1)
                assertTrue("cell height must not follow each pose", frames.maxOf { it.image.height } - frames.minOf { it.image.height } <= 1)
                val hashes = mutableSetOf<Int>()
                for ((frameIndex, frame) in frames.withIndex()) {
                    val bitmap = frame.image.asAndroidBitmap()
                    assertTrue("$character stage $stage has an empty frame", bitmap.width > 10 && bitmap.height > 10)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val opaque = pixels.count { it ushr 24 > 0 }
                    assertTrue("$character stage $stage missing transparency", opaque in 100 until pixels.size * 95 / 100)
                    hashes.add(pixels.contentHashCode())
                    if (character in listOf(Companion.TORI, Companion.NERU) && stage == 0) {
                        val sample = Bitmap.createBitmap(420, 360, Bitmap.Config.ARGB_8888)
                        val sampleCanvas = Canvas(sample).apply { drawColor(Color.rgb(30, 62, 54)) }
                        val factor = 220f / frame.referenceHeight
                        val x = 210f - frame.originX * factor
                        val y = 315f - frame.groundY * factor
                        sampleCanvas.drawBitmap(bitmap, null, RectF(x, y, x + bitmap.width * factor, y + bitmap.height * factor), paint)
                        val action = if (frames === sprites.running) "run" else "jump"
                        context.getFileStreamPath("${character.name.lowercase(java.util.Locale.ROOT)}-$action-${frameIndex.toString().padStart(2, '0')}.png").outputStream().use {
                            sample.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        sample.recycle()
                    }
                    bitmap.recycle()
                }
                assertEquals("$character stage $stage must use distinct images", 30, hashes.size)
            }
        }
        context.getFileStreamPath("runner-sprite-audit.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        preview.recycle()
    }
}
