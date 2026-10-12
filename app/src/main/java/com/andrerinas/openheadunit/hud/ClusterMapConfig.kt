package com.andrerinas.openheadunit.hud

/**
 * What DiAuto declares to the phone for the BYD instrument-cluster ("dashboard") screen, and how
 * the decoded picture is cropped back onto it. Pure, so the arithmetic is unit-tested.
 *
 * Android Auto only offers fixed codec sizes, so the 8:3 dashboard is a codec frame with equal
 * top/bottom margins: the phone draws only inside the margins, and [cropMatrix] shows just that
 * part. Content insets are the phone's safe area: navigation apps keep the car marker inside it,
 * which is how the marker is moved without touching the map itself.
 */
internal object ClusterMapConfig {

    /** What the dashboard shows. */
    enum class Content(val value: Int) {
        /** DISPLAY_TYPE_CLUSTER: the navigation app's own cluster map (Waze, Google Maps). */
        MAP(0),
        /** DISPLAY_TYPE_AUXILIARY opened on the turn card. Experimental. */
        TURN_CARD(1);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: MAP }
    }

    /** How much of the map fits: a smaller stream is enlarged onto the panel. */
    enum class Size(val value: Int, val codecResolution: Int, val codecWidth: Int, val codecHeight: Int) {
        /** 1920x720 drawn at native size. */
        NORMAL(0, RES_1920x1080, 1920, 1080),
        /** 1280x480 enlarged 1.5x: a closer, larger map. */
        LARGE(1, RES_1280x720, 1280, 720);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: NORMAL }
    }

    /** Size of map labels, icons and the car marker, as the display density the phone draws for. */
    enum class Detail(val value: Int, val dpi: Int) {
        SMALL(0, 120), NORMAL(1, 160), LARGE(2, 213), EXTRA_LARGE(3, 240);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: NORMAL }
    }

    // VideoCodecResolutionType values.
    const val RES_1280x720 = 2
    const val RES_1920x1080 = 3

    const val DISPLAY_TYPE_CLUSTER = 1
    const val DISPLAY_TYPE_AUXILIARY = 2
    const val KEYCODE_TURN_CARD = 65544

    /** The cluster is the second display; the main screen is display 0. */
    const val DISPLAY_ID = 1

    /** The dashboard panel these presets are made for (8:3). */
    const val PANEL_WIDTH = 1920
    const val PANEL_HEIGHT = 720

    /**
     * BYD's "Small screen navi" window is about x 31–68 %, y 16–89 % of the panel (measured by
     * DiPlay on a DiLink 5 cluster); this safe area keeps the marker inside it in both modes.
     */
    const val BASE_LEFT = 35
    const val BASE_RIGHT = 36
    const val BASE_TOP = 16
    const val BASE_BOTTOM = 25

    /** Marker steps move the safe area by this many percent of the panel. */
    const val STEP_PERCENT = 10
    val HORIZONTAL_STEPS = -3..3 // negative = left
    val VERTICAL_STEPS = -2..2 // negative = up

    /** Never let the safe area shrink below this share of the panel in either direction. */
    private const val MIN_SPAN_PERCENT = 10

    data class Insets(val top: Int, val bottom: Int, val left: Int, val right: Int)

    data class Profile(
        val displayType: Int,
        val initialContentKeycode: Int?,
        val codecResolution: Int,
        val codecWidth: Int,
        val codecHeight: Int,
        val widthMargin: Int,
        val heightMargin: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val density: Int,
        val contentInsets: Insets,
    ) {
        /** Half of each margin, the UiConfig margins the phone leaves blank on each side. */
        val margins: Insets get() = Insets(heightMargin / 2, heightMargin - heightMargin / 2,
            widthMargin / 2, widthMargin - widthMargin / 2)
    }

    fun profile(content: Content, size: Size, detail: Detail, markerX: Int, markerY: Int): Profile {
        // Keep the panel's 8:3 shape inside the codec frame; the rest is margin.
        val contentWidth = size.codecWidth
        val contentHeight = contentWidth * PANEL_HEIGHT / PANEL_WIDTH
        val heightMargin = size.codecHeight - contentHeight
        return Profile(
            displayType = if (content == Content.TURN_CARD) DISPLAY_TYPE_AUXILIARY else DISPLAY_TYPE_CLUSTER,
            initialContentKeycode = if (content == Content.TURN_CARD) KEYCODE_TURN_CARD else null,
            codecResolution = size.codecResolution,
            codecWidth = size.codecWidth,
            codecHeight = size.codecHeight,
            widthMargin = 0,
            heightMargin = heightMargin,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            // Apps size labels, icons and the marker in dp, so a higher density draws them larger
            // and shows less area around the car. Left unscaled on the LARGE stream on purpose:
            // its 1.5x enlargement then makes everything 1.5x larger, which is what LARGE is for.
            density = detail.dpi,
            contentInsets = insets(contentWidth, contentHeight, markerX, markerY),
        )
    }

    /** The safe area in content pixels for marker steps [markerX] and [markerY]. */
    fun insets(contentWidth: Int, contentHeight: Int, markerX: Int, markerY: Int): Insets {
        val dx = markerX.coerceIn(HORIZONTAL_STEPS) * STEP_PERCENT
        val dy = markerY.coerceIn(VERTICAL_STEPS) * STEP_PERCENT
        val (left, right) = shift(BASE_LEFT, BASE_RIGHT, dx)
        val (top, bottom) = shift(BASE_TOP, BASE_BOTTOM, dy)
        return Insets(
            top = contentHeight * top / 100,
            bottom = contentHeight * bottom / 100,
            left = contentWidth * left / 100,
            right = contentWidth * right / 100,
        )
    }

    /** Moves a (start, end) inset pair by [delta] percent, keeping both edges on the panel. */
    private fun shift(start: Int, end: Int, delta: Int): Pair<Int, Int> {
        val span = 100 - start - end
        val newStart = (start + delta).coerceIn(0, 100 - maxOf(span, MIN_SPAN_PERCENT))
        return newStart to (100 - span - newStart).coerceAtLeast(0)
    }

    /**
     * Scale and translation for a TextureView of [viewWidth]x[viewHeight] whose buffer is the
     * whole codec frame, so that only the drawn content (inside the margins) fills the view.
     * Returned as (scaleX, scaleY, translateX, translateY) for Matrix.setScale + postTranslate.
     */
    fun cropMatrix(profile: Profile, viewWidth: Int, viewHeight: Int): FloatArray {
        val contentLeft = profile.margins.left
        val contentTop = profile.margins.top
        val scaleX = profile.codecWidth.toFloat() / profile.contentWidth
        val scaleY = profile.codecHeight.toFloat() / profile.contentHeight
        val translateX = -contentLeft.toFloat() * viewWidth / profile.contentWidth
        val translateY = -contentTop.toFloat() * viewHeight / profile.contentHeight
        return floatArrayOf(scaleX, scaleY, translateX, translateY)
    }
}
