package io.github.silleruss.drivy

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RunnerFrame(
    val image: ImageBitmap,
    val foot: Int,
    val top: Int,
    val originX: Float = image.width / 2f,
    val groundY: Float = foot.toFloat(),
    val referenceHeight: Float = (foot - top + 1).toFloat(),
)
internal data class RunnerSprites(val running: List<RunnerFrame>, val jumping: List<RunnerFrame>)

/** Reads the two concept sheets for just the selected character and growth stage. */
internal suspend fun loadRunnerSprites(assets: AssetManager, companion: Companion, growth: Int): RunnerSprites {
    val name = companion.name.lowercase(java.util.Locale.ROOT)
    suspend fun sheet(action: String): List<RunnerFrame> {
        val path = "runner/$name-stage${growth + 1}-$action-v2.png"
        val source = assets.open(path).use { requireNotNull(BitmapFactory.decodeStream(it)) }
        try {
            val sheetPixels = IntArray(source.width * source.height)
            source.getPixels(sheetPixels, 0, source.width, 0, 0, source.width, source.height)
            require(sheetPixels.count { it ushr 24 < 64 } > sheetPixels.size / 10) { "Missing transparent background: $path" }
            val frames = List(30) { i ->
                currentCoroutineContext().ensureActive()
                val left = source.width * (i % 6) / 6
                val right = source.width * (i % 6 + 1) / 6
                val top = source.height * (i / 6) / 5
                val bottom = source.height * (i / 6 + 1) / 5
                val w = right - left
                val h = bottom - top
                require(w > 0 && h > 0)
                val pixels = IntArray(w * h)
                source.getPixels(pixels, 0, w, left, top, w, h)
                // shortcut: low-alpha cleanup can remove fine effects, replace with authored per-frame assets for production.
                for (index in pixels.indices) if (pixels[index] ushr 24 < 64) pixels[index] = 0
                val queue = IntArray(pixels.size)
                var start: Int
                var end: Int
                // Keep the largest connected subject; this discards detached labels and ground shadows.
                val seen = BooleanArray(pixels.size)
                var largest = IntArray(0)
                for (seed in pixels.indices) {
                    if (pixels[seed] == 0 || seen[seed]) continue
                    start = 0; end = 0; queue[end++] = seed; seen[seed] = true
                    while (start < end) {
                        val point = queue[start++]
                        val x = point % w
                        fun visit(n: Int) { if (!seen[n] && pixels[n] != 0) { seen[n] = true; queue[end++] = n } }
                        if (x > 0) visit(point - 1)
                        if (x + 1 < w) visit(point + 1)
                        if (point >= w) visit(point - w)
                        if (point + w < pixels.size) visit(point + w)
                    }
                    if (end > largest.size) largest = queue.copyOf(end)
                }
                require(largest.size > w * h / 100) { "No character in $path frame ${i + 1}" }
                var minX = w; var minY = h; var maxX = 0; var maxY = 0
                val mask = BooleanArray(pixels.size)
                for (point in largest) {
                    mask[point] = true
                    minX = minOf(minX, point % w); maxX = maxOf(maxX, point % w)
                    minY = minOf(minY, point / w); maxY = maxOf(maxY, point / w)
                }
                for (p in pixels.indices) if (!mask[p]) pixels[p] = 0
                val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                RunnerFrame(bitmap.asImageBitmap(), maxY, minY, originX = (minX + maxX + 1) / 2f)
            }
            // Remove the run sheet's end-to-start camera drift without resizing poses or blending images.
            val ground = frames.map { it.foot }.sorted()[15].toFloat()
            val referenceHeight = frames.map { it.foot - it.top + 1 }.sorted()[15].toFloat()
            val driftX = if (action == "run") frames.last().originX - frames.first().originX else 0f
            val driftY = if (action == "run") (frames.last().top - frames.first().top).toFloat() else 0f
            return frames.mapIndexed { index, frame ->
                val phase = index / frames.lastIndex.toFloat()
                frame.copy(originX = frames.first().image.width / 2f + driftX * phase,
                    groundY = ground + driftY * phase, referenceHeight = referenceHeight)
            }

        } finally { source.recycle() }
    }
    val running = sheet("run")
    val jumping = sheet("jump")
    // Both authored motions share a camera and scale; tucked legs must not enlarge the body.
    val ratio = jumping.first().image.width.toFloat() / running.first().image.width
    val alignedJump = jumping.map { it.copy(referenceHeight = running.first().referenceHeight * ratio,
        groundY = running.first().groundY * ratio) }
    return RunnerSprites(running, alignedJump)
}

internal fun runnerFrame(elapsed: Double, jumping: Boolean, velocity: Double): Int =
    if (jumping) ((((1.5 - velocity) / 3.0) * 30) + 1e-7).toInt().coerceIn(0, 29)
    else ((elapsed * 60 + 1e-7).toLong() % 30).toInt()
