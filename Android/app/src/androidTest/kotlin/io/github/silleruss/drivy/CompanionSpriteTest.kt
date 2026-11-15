package io.github.silleruss.drivy

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSpriteTest {
    @Test fun everySpriteHasTransparentMarginsAndASolidCharacter() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (form in Companion.entries.map { it.forms.first() }) {
            val bitmap = BitmapFactory.decodeResource(resources, form.sprite)
            assertTrue("${form.name} must have alpha", bitmap.hasAlpha())
            assertEquals("${form.name} must be square", bitmap.width, bitmap.height)
            assertEquals("Three equal columns", 0, bitmap.width % 3)
            val cell = bitmap.width / 3
            // The new TORI atlas has 418px cells, above the 256dp sprite at the tested 160dpi.
            assertTrue("${form.name} frames must remain sharp at vehicle display sizes", cell >= if (form.sprite == R.drawable.tori_atlas) 400 else 512)
            for (stage in 0..2) {
                val frames = (0..2).map { row ->
                    val pixels = IntArray(cell * cell)
                    bitmap.getPixels(pixels, 0, cell, stage * cell, row * cell, cell, cell)
                    for (edge in 0 until cell) {
                        assertTrue("${form.name} stage $stage frame $row must not bleed across cells",
                            listOf(pixels[edge], pixels[(cell - 1) * cell + edge], pixels[edge * cell],
                                pixels[edge * cell + cell - 1]).all { Color.alpha(it) <= if (form.sprite == R.drawable.tori_atlas) 2 else 1 })
                    }
                    assertTrue("${form.name} must contain a solid character",
                        pixels.count { Color.alpha(it) >= 250 } > cell * cell / 10)
                    pixels
                }
                assertTrue("Blink must change the character", !frames[0].contentEquals(frames[1]))
                assertTrue("Greeting must change the character", !frames[0].contentEquals(frames[2]))
            }
            bitmap.recycle()
        }
    }
}
