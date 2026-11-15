package io.github.silleruss.drivy

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil

internal data class RunnerScenery(val backgrounds: List<ImageBitmap>, val obstacles: List<ImageBitmap>, val coin: ImageBitmap)

@Composable
internal fun rememberRunnerScenery(): RunnerScenery? {
    val assets = LocalContext.current.assets
    val scenery by produceState<RunnerScenery?>(null, assets) {
        try { value = withContext(Dispatchers.Default) { loadRunnerScenery(assets) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The caller keeps its existing procedural scenery on load failure. */ }
    }
    return scenery
}

internal fun loadRunnerScenery(assets: AssetManager): RunnerScenery {
    fun bitmap(name: String) = assets.open("runner/scenery/$name.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
    val backgrounds = listOf("forest", "desert", "night").map { name ->
        val original = bitmap(name)
        val width = original.width
        val height = original.height
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        val blend = width / 8
        for (y in 0 until height) for (x in 0 until blend) {
            val left = y * width + x
            val right = y * width + width - 1 - x
            val weight = .5f * (1f - x.toFloat() / blend)
            fun mix(a: Int, b: Int): Int {
                var result = 0
                for (shift in 0..24 step 8) {
                    val channel = (((a ushr shift and 255) * (1 - weight)) + ((b ushr shift and 255) * weight)).toInt()
                    result = result or (channel shl shift)
                }
                return result
            }
            val a = pixels[left]; val b = pixels[right]
            pixels[left] = mix(a, b); pixels[right] = mix(b, a)
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also { original.recycle() }.asImageBitmap()
    }
    val sheet = bitmap("objects")
    val objects = try {
        List(4) { index ->
            val left = sheet.width * (index % 2) / 2
            val top = sheet.height * (index / 2) / 2
            val width = sheet.width / 2
            val height = sheet.height / 2
            val pixels = IntArray(width * height)
            sheet.getPixels(pixels, 0, width, left, top, width, height)
            var minX = width; var minY = height; var maxX = -1; var maxY = -1
            for (p in pixels.indices) {
                if (pixels[p] ushr 24 < 128) pixels[p] = 0
                else {
                    minX = minOf(minX, p % width); maxX = maxOf(maxX, p % width)
                    minY = minOf(minY, p / width); maxY = maxOf(maxY, p / width)
                }
            }
            require(maxX > minX && maxY > minY) { "Empty scenery sprite $index" }
            require(pixels.count { it == 0 } > pixels.size / 10) { "Scenery sprite must have transparency" }
            val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            val cropped = Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
            if (cropped !== full) full.recycle()
            cropped.asImageBitmap()
        }
    } finally { sheet.recycle() }
    return RunnerScenery(backgrounds, objects.take(3), objects.last())
}

internal fun DrawScope.drawRunnerBackground(scenery: RunnerScenery, level: Int, distance: Float, ground: Float) {
    val index = (level - 1).mod(3)
    val picture = scenery.backgrounds[index]
    val tileWidth = ceil(maxOf(size.width, ground * picture.width / picture.height)).toInt()
    val travel = distance * size.width * .035f
    val offset = (travel % tileWidth).toInt()
    clipRect(0f, 0f, size.width, ground) {
        repeat(3) { i ->
            val x = i * tileWidth - offset
            drawImage(picture, dstOffset = IntOffset(x - 1, 0), dstSize = IntSize(tileWidth + 2, ground.toInt() + 1))
        }
    }
    val soil = listOf(Color(0xFF294E3A), Color(0xFF775245), Color(0xFF332F54))[index]
    val edge = listOf(Color(0xFF91BE6F), Color(0xFFE4B885), Color(0xFFAC9BE0))[index]
    drawRect(soil, Offset(0f, ground), Size(size.width, size.height - ground))
    drawRect(edge, Offset(0f, ground), Size(size.width, 4f * density))
    repeat(32) { i ->
        val x = ((i / 27f - distance) % 1.2f + 1.2f) % 1.2f * size.width
        val y = ground + (12 + i % 3 * 7) * density
        drawOval(edge.copy(alpha = .22f), Offset(x, y), Size((3 + i % 4 * 2) * density, 2f * density))
    }
}

internal fun DrawScope.drawRunnerObstacle(scenery: RunnerScenery, level: Int, topLeft: Offset, size: Size) {
    drawImage(scenery.obstacles[(level - 1).mod(3)], dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
}

internal fun DrawScope.drawRunnerCoin(scenery: RunnerScenery, center: Offset, radius: Float, elapsed: Float) {
    val width = radius * 2 * (.15f + .85f * abs(cos(elapsed * 7)))
    drawImage(scenery.coin, dstOffset = IntOffset((center.x - width / 2).toInt(), (center.y - radius).toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), (radius * 2).toInt().coerceAtLeast(1)))
}
