package com.gymtracker.shared.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FuzzyAndGroupTests {
    @Test
    fun blankQueryMatchesEverything() {
        assertNotNull(fuzzyScore("", "Bench Press"))
        assertEquals(0, fuzzyScore("   ", "Bench Press")!!)
        val list = listOf("Bench Press", "Squat")
        assertEquals(list, list.fuzzyFilter("") { it })
    }

    @Test
    fun substringAndWordStartsMatch() {
        assertNotNull(fuzzyScore("bench", "Bench Press"))
        assertNotNull(fuzzyScore("press", "Bench Press"))
        assertNull(fuzzyScore("dnif", "Bench Press"))
        // Starting at a word boundary outranks a match in the middle.
        val atStart = fuzzyScore("press", "Press") ?: 0
        val inMiddle = fuzzyScore("press", "Bench Press") ?: 0
        assertTrue(atStart > inMiddle, "expected $atStart > $inMiddle")
    }

    @Test
    fun subsequenceMatchesOutOfOrderWords() {
        assertNotNull(fuzzyScore("bcp", "Bench Press"))
        assertNotNull(fuzzyScore("barw", "Barbell Row"))
        assertNull(fuzzyScore("zzz", "Barbell Row"))
    }

    @Test
    fun everyQueryWordMustMatch() {
        assertNotNull(fuzzyScore("bench pr", "Bench Press (PR)"))
        assertNull(fuzzyScore("bench squat", "Bench Press"))
    }

    @Test
    fun filterSortsBestMatchFirstAndKeepsOrderWhenBlank() {
        val exercises = listOf("Incline Bench Press", "Bench Press", "Barbell Row")
        val filtered = exercises.fuzzyFilter("bench") { it }
        assertEquals("Bench Press", filtered.first())
        assertEquals(2, filtered.size)
    }

    @Test
    fun builtInGroupLabelAndCustomGroup() {
        assertEquals("Chest", MuscleGroup.CHEST.label)
        assertEquals("Full body", MuscleGroup.FULL_BODY.label)
        assertEquals("Chest", Exercise(id = java.util.UUID.randomUUID(), name = "Bench", muscleGroup = MuscleGroup.CHEST).groupName)
        assertEquals("Calves", Exercise(id = java.util.UUID.randomUUID(), name = "Calf Raise", customGroup = "Calves").groupName)
        assertNull(Exercise(id = java.util.UUID.randomUUID(), name = "Mystery").groupName)
    }

    @Test
    fun resolvingAGroupPicksBuiltInOrCustom() {
        assertEquals(MuscleGroup.CHEST to null, resolveGroup("chest"))
        assertEquals(MuscleGroup.FULL_BODY to null, resolveGroup("Full body"))
        assertEquals(null to "Calves", resolveGroup("Calves"))
        assertEquals(null to null, resolveGroup("  "))
    }
}
