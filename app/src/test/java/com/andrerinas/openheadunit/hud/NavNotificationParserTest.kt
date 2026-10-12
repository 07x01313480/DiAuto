package com.andrerinas.openheadunit.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavNotificationParserTest {

    private fun parse(vararg texts: String) = NavNotificationParser.parse(texts.toList())

    @Test
    fun `google maps title distance and text instruction`() {
        val result = parse("300 m", "Turn right onto Al Rasheed St", "12 min · 5.2 km · 17:45 ETA")!!
        assertEquals(8, result.maneuverType)
        assertEquals(300, result.distanceMeters)
        assertEquals("Al Rasheed St", result.road)
    }

    @Test
    fun `the trip summary never supplies the turn distance`() {
        // Total trip distance and ETA must not be mistaken for the next turn.
        assertNull(parse("Turn left", "12 min · 5.2 km · 17:45"))
        assertEquals(150, parse("Turn left", "12 min · 5.2 km · 17:45", "150 m")!!.distanceMeters)
    }

    @Test
    fun `arrival in the summary is not a destination`() {
        assertNull(parse("400 m", "Arrival 17:45"))
    }

    @Test
    fun `single line instruction with distance`() {
        val result = parse("In 1.2 km, keep left toward Airport Road")!!
        assertEquals(3, result.maneuverType)
        assertEquals(1200, result.distanceMeters)
        assertEquals("Airport Road", result.road)
    }

    @Test
    fun `slight sharp merge fork and u-turn`() {
        assertEquals(5, parse("200 m", "Slight left")!!.maneuverType)
        assertEquals(10, parse("200 m", "Sharp right")!!.maneuverType)
        assertEquals(28, parse("200 m", "Merge right onto Highway 1")!!.maneuverType)
        assertEquals(25, parse("200 m", "Keep left at the fork")!!.maneuverType)
        assertEquals(11, parse("200 m", "Make a U-turn")!!.maneuverType)
        assertEquals(22, parse("500 m", "Take the exit on the right")!!.maneuverType)
    }

    @Test
    fun `roundabout uses counter-clockwise exit number`() {
        val result = parse("250 m", "At the roundabout, take the 2nd exit onto Palestine St")!!
        assertEquals(34, result.maneuverType)
        assertEquals(2, result.roundaboutExit)
        assertEquals(34, parse("250 m", "At the roundabout, take the third exit")!!.maneuverType)
        assertEquals(3, parse("250 m", "At the roundabout, take the third exit")!!.roundaboutExit)
        assertEquals(30, parse("250 m", "Enter the roundabout")!!.maneuverType)
        assertEquals(31, parse("50 m", "Exit the roundabout")!!.maneuverType)
    }

    @Test
    fun `destination with side`() {
        assertEquals(42, parse("100 m", "Destination will be on the right")!!.maneuverType)
        assertEquals(41, parse("100 m", "Your destination is on the left")!!.maneuverType)
        assertEquals(39, parse("0 m", "You have arrived")!!.maneuverType)
    }

    @Test
    fun `continue straight`() {
        assertEquals(36, parse("2 km", "Continue straight on Highway 1")!!.maneuverType)
        assertEquals(2000, parse("2 km", "Continue")!!.distanceMeters)
    }

    @Test
    fun `arabic instructions with arabic digits`() {
        val right = parse("٣٠٠ م", "انعطف يمينًا إلى شارع الرشيد")!!
        assertEquals(8, right.maneuverType)
        assertEquals(300, right.distanceMeters)
        assertEquals("شارع الرشيد", right.road)

        val left = parse("1٫5 كم", "انعطف يسارًا")!!
        assertEquals(7, left.maneuverType)
        assertEquals(1500, left.distanceMeters)

        assertEquals(3, parse("200 م", "الزم اليسار")!!.maneuverType)
        assertEquals(6, parse("200 م", "انعطف قليلاً إلى اليمين")!!.maneuverType)
        assertEquals(36, parse("2 كم", "تابع مباشرةً")!!.maneuverType)
    }

    @Test
    fun `arabic roundabout ordinal and destination`() {
        val roundabout = parse("400 م", "عند الدوّار، اسلك المخرج الثاني")!!
        assertEquals(34, roundabout.maneuverType)
        assertEquals(2, roundabout.roundaboutExit)
        assertEquals(42, parse("50 م", "ستكون وجهتك على اليمين")!!.maneuverType)
    }

    @Test
    fun `arabic time with pm marker is not a distance`() {
        // "م" also means PM; a clock time marks the line as a trip summary.
        assertNull(parse("انعطف يمينًا", "الوصول ٥:٤٥ م"))
    }

    @Test
    fun `imperial units convert to meters`() {
        assertEquals(805, parse("0.5 mi", "Turn left")!!.distanceMeters)
        assertEquals(152, parse("500 ft", "Turn left")!!.distanceMeters)
        assertEquals(1200, parse("1,200 m", "Turn left")!!.distanceMeters)
    }

    @Test
    fun `unknown wording or missing distance gives nothing`() {
        assertNull(parse("300 m", "Rerouting…"))
        assertNull(parse("Turn right onto Main St"))
        assertNull(parse("500 m", "Take the exit"))
        assertNull(parse("Waze is running"))
    }

    @Test
    fun `the earlier side wins`() {
        assertEquals(7, parse("100 m", "Turn left, then right")!!.maneuverType)
    }

    @Test
    fun `words containing left or right are not sides`() {
        assertNull(parse("100 m", "Bright Street"))
    }

    @Test
    fun `parsed types all map to byd codes`() {
        val samples = listOf(
            parse("300 m", "Turn right"), parse("300 m", "Keep left"), parse("300 m", "Make a U-turn"),
            parse("300 m", "At the roundabout, take the 4th exit"), parse("300 m", "Destination on the left"),
            parse("300 m", "Continue"), parse("300 m", "Take the exit on the left"),
        )
        for (s in samples) {
            requireNotNull(s)
            requireNotNull(BydNavigationMapper.codes(s.maneuverType, s.roundaboutExit)) { "no BYD code for $s" }
        }
    }
}
