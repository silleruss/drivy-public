package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class CompanionTest {
    @Test fun referenceHasSixCompanionsAndEighteenNamedForms() {
        assertEquals(listOf("모카", "누리", "피코", "네루", "토리", "라피"), Companion.entries.map { it.displayName })
        Companion.entries.forEach { companion ->
            assertEquals(3, companion.forms.size)
            assertTrue(companion.forms.all { it.name.contains(companion.displayName) && it.sprite != 0 })
        }
        assertEquals(18, Companion.entries.flatMap { it.forms }.map { it.name }.distinct().size)
        assertEquals(6, Companion.entries.map { it.forms.first().sprite }.distinct().size)
    }

    @Test fun savedSelectionRestoresAndUnknownSelectionIsAbsent() {
        Companion.entries.forEach { assertEquals(it, Companion.fromSavedName(it.name)) }
        assertEquals(Companion.PIKO, Companion.fromSavedName("BORI"))
        assertEquals(Companion.NERU, Companion.fromSavedName("NABI"))
        assertNull(Companion.fromSavedName(null))
        assertNull(Companion.fromSavedName("removed_companion"))
    }
}
