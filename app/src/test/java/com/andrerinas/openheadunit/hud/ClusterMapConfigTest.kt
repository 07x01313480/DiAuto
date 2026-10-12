package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.hud.ClusterMapConfig.Content
import com.andrerinas.openheadunit.hud.ClusterMapConfig.Detail
import com.andrerinas.openheadunit.hud.ClusterMapConfig.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMapConfigTest {

    private fun profile(
        content: Content = Content.MAP, size: Size = Size.NORMAL, detail: Detail = Detail.NORMAL,
        x: Int = 0, y: Int = 0,
    ) = ClusterMapConfig.profile(content, size, detail, x, y)

    @Test
    fun `normal size draws the 8 to 3 panel inside a 1080p frame`() {
        val p = profile()
        assertEquals(ClusterMapConfig.RES_1920x1080, p.codecResolution)
        assertEquals(1920, p.contentWidth)
        assertEquals(720, p.contentHeight)
        assertEquals(0, p.widthMargin)
        assertEquals(360, p.heightMargin)
        assertEquals(ClusterMapConfig.Insets(180, 180, 0, 0), p.margins)
    }

    @Test
    fun `large size uses a 720p frame for a 1280x480 picture`() {
        val p = profile(size = Size.LARGE)
        assertEquals(ClusterMapConfig.RES_1280x720, p.codecResolution)
        assertEquals(1280, p.contentWidth)
        assertEquals(480, p.contentHeight)
        assertEquals(240, p.heightMargin)
        assertEquals(ClusterMapConfig.Insets(120, 120, 0, 0), p.margins)
    }

    @Test
    fun `map is a cluster display and the turn card an auxiliary one`() {
        assertEquals(ClusterMapConfig.DISPLAY_TYPE_CLUSTER, profile().displayType)
        assertNull(profile().initialContentKeycode)
        val card = profile(content = Content.TURN_CARD)
        assertEquals(ClusterMapConfig.DISPLAY_TYPE_AUXILIARY, card.displayType)
        assertEquals(ClusterMapConfig.KEYCODE_TURN_CARD, card.initialContentKeycode)
    }

    @Test
    fun `detail sets the density`() {
        assertEquals(120, profile(detail = Detail.SMALL).density)
        assertEquals(160, profile().density)
        assertEquals(240, profile(detail = Detail.EXTRA_LARGE).density)
        assertEquals(213, profile(size = Size.LARGE, detail = Detail.LARGE).density)
    }

    @Test
    fun `default safe area matches the small navi window`() {
        val i = profile().contentInsets
        assertEquals(1920 * 35 / 100, i.left)
        assertEquals(1920 * 36 / 100, i.right)
        assertEquals(720 * 16 / 100, i.top)
        assertEquals(720 * 25 / 100, i.bottom)
    }

    @Test
    fun `marker steps move the safe area without resizing it`() {
        val centre = ClusterMapConfig.insets(1920, 720, 0, 0)
        val right = ClusterMapConfig.insets(1920, 720, 1, 0)
        assertEquals(centre.left + 192, right.left)
        assertEquals(centre.right - 192, right.right)
        val up = ClusterMapConfig.insets(1920, 720, 0, -1)
        assertEquals(centre.top - 72, up.top)
        assertEquals(centre.bottom + 72, up.bottom)
    }

    @Test
    fun `marker steps are clamped and never leave the panel`() {
        for (x in -10..10) for (y in -10..10) {
            val i = ClusterMapConfig.insets(1920, 720, x, y)
            assertTrue("x=$x y=$y $i", i.left >= 0 && i.right >= 0 && i.top >= 0 && i.bottom >= 0)
            assertTrue("x=$x y=$y $i", i.left + i.right < 1920 && i.top + i.bottom < 720)
        }
        assertEquals(ClusterMapConfig.insets(1920, 720, 3, 2), ClusterMapConfig.insets(1920, 720, 9, 9))
    }

    @Test
    fun `crop shows only the drawn content`() {
        // 1920x1080 buffer on a 1920x720 view: the 1080 rows must stretch so the middle 720 fill it.
        val m = ClusterMapConfig.cropMatrix(profile(), 1920, 720)
        assertArrayEquals(floatArrayOf(1f, 1.5f, 0f, -180f), m, 0.001f)
        // Content row 180 (top of the picture) lands on view row 0, row 900 on view row 720.
        val default = 720f / 1080f // view pixels per buffer row before the transform
        assertEquals(0f, 180 * default * m[1] + m[3], 0.01f)
        assertEquals(720f, 900 * default * m[1] + m[3], 0.01f)
    }

    @Test
    fun `crop of the large stream on a 1280x480 panel`() {
        val m = ClusterMapConfig.cropMatrix(profile(size = Size.LARGE), 1280, 480)
        assertArrayEquals(floatArrayOf(1f, 1.5f, 0f, -120f), m, 0.001f)
    }

    @Test
    fun `cards follow the marker by default`() {
        val p = profile(x = 1, y = -1)
        assertEquals(p.stableInsets, p.contentInsets)
        assertEquals(ClusterMapConfig.insets(1920, 720, 1, -1), p.stableInsets)
    }

    @Test
    fun `cards at a side move only the content insets`() {
        val left = ClusterMapConfig.profile(Content.MAP, Size.NORMAL, Detail.NORMAL, 0, 1, ClusterMapConfig.Cards.LEFT)
        assertEquals(ClusterMapConfig.insets(1920, 720, 0, 1), left.stableInsets)
        assertEquals(1920 * 2 / 100, left.contentInsets.left)
        assertEquals(1920 * 68 / 100, left.contentInsets.right)
        // The cards keep the marker box's height, so the vertical slider still moves them.
        assertEquals(left.stableInsets.top, left.contentInsets.top)
        assertEquals(left.stableInsets.bottom, left.contentInsets.bottom)
        val right = ClusterMapConfig.profile(Content.MAP, Size.LARGE, Detail.NORMAL, 0, 0, ClusterMapConfig.Cards.RIGHT)
        assertEquals(1280 * 68 / 100, right.contentInsets.left)
        assertEquals(1280 * 2 / 100, right.contentInsets.right)
    }

    @Test
    fun `unknown stored values fall back to defaults`() {
        assertEquals(ClusterMapConfig.Cards.WITH_MARKER, ClusterMapConfig.Cards.of(5))
        assertEquals(Content.MAP, Content.of(9))
        assertEquals(Size.NORMAL, Size.of(-1))
        assertEquals(Detail.NORMAL, Detail.of(7))
    }
}
