package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CarSliderFinderTest {
    @Test
    fun `only moved values are listed, in key order`() {
        val before = mapOf("stream 3" to "10/30", "stream 15" to "5/30", "system/volume_voice" to "4")
        val after = mapOf("stream 3" to "10/30", "stream 15" to "8/30", "system/volume_voice" to "6")
        assertEquals(
            listOf("stream 15: 5/30 → 8/30", "system/volume_voice: 4 → 6"),
            CarSliderFinder.changes(before, after),
        )
    }

    @Test
    fun `a value that appears or disappears is listed`() {
        assertEquals(listOf("global/x_vol: – → 2"), CarSliderFinder.changes(emptyMap(), mapOf("global/x_vol" to "2")))
        assertEquals(listOf("global/x_vol: 2 → –"), CarSliderFinder.changes(mapOf("global/x_vol" to "2"), emptyMap()))
    }

    @Test
    fun `nothing moved means no lines`() {
        assertEquals(emptyList<String>(), CarSliderFinder.changes(mapOf("stream 1" to "1/7"), mapOf("stream 1" to "1/7")))
    }
}
