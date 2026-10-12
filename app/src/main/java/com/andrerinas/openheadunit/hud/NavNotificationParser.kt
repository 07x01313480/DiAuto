package com.andrerinas.openheadunit.hud

/**
 * The next maneuver read from a navigation app's notification on the head unit itself.
 *
 * [maneuverType] uses the Android Auto NavigationType numbers, so [BydNavigationMapper.codes]
 * maps it exactly as it maps a projected Android Auto session.
 */
internal data class NavInstruction(
    val maneuverType: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
)

/**
 * Turns the text of a Waze or Google Maps navigation notification into a [NavInstruction].
 *
 * Neither app documents its notification, so this reads text, in English or Arabic, and refuses
 * to guess: no distance or no recognised maneuver gives null, never a straight arrow or "0 m".
 * Strings that look like the trip summary (a clock time, minutes, ETA) are not used for the
 * distance, so the remaining trip distance is never shown as the distance to the next turn.
 */
internal object NavNotificationParser {
    const val WAZE = "com.waze"
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    val PACKAGES = setOf(WAZE, GOOGLE_MAPS)

    private const val MAX_ROAD_LENGTH = 64

    // Android Auto NavigationType numbers (see navigation.proto).
    private const val KEEP_LEFT = 3
    private const val KEEP_RIGHT = 4
    private const val SLIGHT_LEFT = 5
    private const val SLIGHT_RIGHT = 6
    private const val TURN_LEFT = 7
    private const val TURN_RIGHT = 8
    private const val SHARP_LEFT = 9
    private const val SHARP_RIGHT = 10
    private const val U_TURN_LEFT = 11
    private const val OFF_RAMP_LEFT = 21
    private const val OFF_RAMP_RIGHT = 22
    private const val FORK_LEFT = 25
    private const val FORK_RIGHT = 26
    private const val MERGE_LEFT = 27
    private const val MERGE_RIGHT = 28
    private const val ROUNDABOUT_ENTER = 30
    private const val ROUNDABOUT_EXIT = 31
    // Right-hand traffic circulates counter-clockwise.
    private const val ROUNDABOUT_CCW = 34
    private const val STRAIGHT = 36
    private const val DESTINATION = 39
    private const val DESTINATION_LEFT = 41
    private const val DESTINATION_RIGHT = 42

    /** [texts] in priority order: title, text, big text, sub text, then any other visible text. */
    fun parse(texts: List<String>): NavInstruction? {
        // The trip summary (duration, total distance, ETA) is skipped for both the distance and
        // the maneuver: its "arrival" must not turn into a destination flag either.
        val candidates = texts.map { it to normalize(it) }
            .filter { (_, matchable) -> matchable.isNotBlank() && !looksLikeTripSummary(matchable) }
            .distinctBy { it.second }
        val distance = candidates.firstNotNullOfOrNull { distanceMeters(it.second) } ?: return null
        for ((original, matchable) in candidates) {
            val (type, exit) = maneuver(matchable) ?: continue
            return NavInstruction(type, exit, distance, road(original))
        }
        return null
    }

    /**
     * Lower-cased with ASCII digits; Arabic diacritics, tatweel and bidi marks removed, alef
     * variants folded to ا, ى to ي and ة to ه. Keywords below are written in this folded form.
     */
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (c in raw) {
            when (c) {
                in '٠'..'٩' -> out.append('0' + (c - '٠'))
                in '۰'..'۹' -> out.append('0' + (c - '۰'))
                '٫' -> out.append('.') // Arabic decimal separator
                '٬' -> Unit // Arabic thousands separator
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> Unit // diacritics, tatweel
                '‎', '‏', '؜', in '‪'..'‮', in '⁦'..'⁩' -> Unit
                ' ', ' ', ' ' -> out.append(' ')
                'أ', 'إ', 'آ' -> out.append('ا') // أ إ آ -> ا
                'ى' -> out.append('ي') // ى -> ي
                'ة' -> out.append('ه') // ة -> ه
                else -> out.append(c.lowercaseChar())
            }
        }
        return out.toString().replace(WHITESPACE, " ").trim()
    }

    private val WHITESPACE = Regex("\\s+")
    private val CLOCK = Regex("\\d{1,2}:\\d{2}")
    private val SUMMARY_WORDS = Regex("\\b(min|mins|minutes|hr|hrs|hours|eta|arrival)\\b|دقيقه|دقائق|ساعه|الوصول")

    private fun looksLikeTripSummary(text: String): Boolean =
        CLOCK.containsMatchIn(text) || SUMMARY_WORDS.containsMatchIn(text)

    private val DISTANCE = Regex(
        "(?<![\\d.,:])(\\d{1,3}(?:,\\d{3})+|\\d+(?:[.,]\\d+)?)\\s*" +
            "(kilometers|kilometres|kilometer|kilometre|km|meters|metres|meter|metre|m|miles|mile|mi|" +
            "feet|foot|ft|yards|yard|yd|كيلومترات|كيلومتر|كم|امتار|متر|م|اميال|ميل|اقدام|قدم)" +
            "(?![a-z\\u0621-\\u064A])"
    )

    fun distanceMeters(text: String): Int? {
        val match = DISTANCE.find(text) ?: return null
        val number = match.groupValues[1]
        val unit = match.groupValues[2]
        val value = if (number.matches(Regex("\\d{1,3}(,\\d{3})+"))) {
            number.replace(",", "").toDoubleOrNull()
        } else {
            number.replace(',', '.').toDoubleOrNull()
        } ?: return null
        val meters = when (unit) {
            "kilometers", "kilometres", "kilometer", "kilometre", "km", "كيلومترات", "كيلومتر", "كم" -> value * 1000
            "miles", "mile", "mi", "اميال", "ميل" -> value * 1609.344
            "feet", "foot", "ft", "اقدام", "قدم" -> value * 0.3048
            "yards", "yard", "yd" -> value * 0.9144
            else -> value
        }
        return meters.takeIf { it >= 0 && it < 1_000_000 }?.let { Math.round(it).toInt() }
    }

    private fun has(text: String, vararg patterns: Regex) = patterns.any { it.containsMatchIn(text) }
    private fun word(w: String) = Regex("\\b$w\\b")
    private fun arabic(w: String) = Regex(w)

    private val LEFT = Regex("\\bleft\\b|يسار")
    private val RIGHT = Regex("\\bright\\b|يمين")

    /** -1 for left, +1 for right, 0 when neither appears; the earlier mention wins. */
    private fun side(text: String): Int {
        val left = LEFT.find(text)?.range?.first
        val right = RIGHT.find(text)?.range?.first
        return when {
            left == null && right == null -> 0
            right == null -> -1
            left == null -> 1
            left < right -> -1
            else -> 1
        }
    }

    private val ROUNDABOUT = listOf(word("roundabout"), word("rotary"), Regex("traffic circle"), arabic("دوار"))
    private val ROUNDABOUT_LEAVE = listOf(Regex("\\bexit the (roundabout|rotary|traffic circle)"), Regex("(اخرج|غادر) من (ال)?دوار"))
    private val U_TURN = listOf(Regex("\\bu-?\\s?turn\\b"), Regex("\\bmake a u\\b"), arabic("للخلف"), arabic("دوران كامل"))
    private val ARRIVE = listOf(word("destination"), Regex("\\barriv"), arabic("الوجهه"), arabic("وجهتك"), arabic("وصلت"), arabic("ستصل"))
    private val KEEP = listOf(word("keep"), word("stay"), arabic("الزم"), arabic("ابق"), arabic("حافظ"))
    private val SLIGHT = listOf(word("slight"), word("slightly"), word("bear"), arabic("قليلا"), arabic("طفيف"))
    private val SHARP = listOf(word("sharp"), word("sharply"), arabic("بحده"), arabic("حاد"))
    private val MERGE = listOf(word("merge"), arabic("اندمج"))
    private val FORK = listOf(word("fork"), arabic("تفرع"), arabic("مفترق"))
    private val RAMP = listOf(word("exit"), word("ramp"), arabic("المخرج"), arabic("مخرج"), arabic("اخرج"), arabic("منحدر"))
    private val TURN = listOf(word("turn"), arabic("انعطف"), arabic("استدر"), arabic("اتجه"), arabic("انحرف"))
    private val STRAIGHT_ON = listOf(word("continue"), word("straight"), word("head"), word("proceed"),
        arabic("تابع"), arabic("استمر"), arabic("واصل"), arabic("مباشره"), arabic("للامام"), arabic("اتجه"))

    /** (Android Auto maneuver type, roundabout exit) or null when the text names no maneuver. */
    fun maneuver(text: String): Pair<Int, Int>? {
        if (has(text, *ROUNDABOUT.toTypedArray())) {
            if (has(text, *ROUNDABOUT_LEAVE.toTypedArray())) return ROUNDABOUT_EXIT to 0
            val exit = ordinal(text)
            return if (exit > 0) ROUNDABOUT_CCW to exit else ROUNDABOUT_ENTER to 0
        }
        if (has(text, *U_TURN.toTypedArray())) return U_TURN_LEFT to 0
        val side = side(text)
        if (has(text, *ARRIVE.toTypedArray())) {
            return when (side) { -1 -> DESTINATION_LEFT; 1 -> DESTINATION_RIGHT; else -> DESTINATION } to 0
        }
        if (side != 0) {
            val left = side < 0
            val type = when {
                has(text, *MERGE.toTypedArray()) -> if (left) MERGE_LEFT else MERGE_RIGHT
                has(text, *FORK.toTypedArray()) -> if (left) FORK_LEFT else FORK_RIGHT
                has(text, *KEEP.toTypedArray()) -> if (left) KEEP_LEFT else KEEP_RIGHT
                has(text, *SHARP.toTypedArray()) -> if (left) SHARP_LEFT else SHARP_RIGHT
                has(text, *SLIGHT.toTypedArray()) -> if (left) SLIGHT_LEFT else SLIGHT_RIGHT
                has(text, *RAMP.toTypedArray()) -> if (left) OFF_RAMP_LEFT else OFF_RAMP_RIGHT
                else -> if (left) TURN_LEFT else TURN_RIGHT
            }
            return type to 0
        }
        if (has(text, *STRAIGHT_ON.toTypedArray())) return STRAIGHT to 0
        return null // A ramp or turn without a side is not guessed.
    }

    private val ORDINAL_DIGIT = Regex("\\b(\\d{1,2})(st|nd|rd|th)?\\s+exit\\b|\\bexit\\s+(\\d{1,2})\\b|(?:المخرج|مخرج)\\s*(\\d{1,2})")
    private val ORDINAL_WORDS = listOf(
        listOf("first", "الاول") to 1, listOf("second", "الثاني") to 2, listOf("third", "الثالث") to 3,
        listOf("fourth", "الرابع") to 4, listOf("fifth", "الخامس") to 5, listOf("sixth", "السادس") to 6,
        listOf("seventh", "السابع") to 7, listOf("eighth", "الثامن") to 8,
    )

    fun ordinal(text: String): Int {
        ORDINAL_DIGIT.find(text)?.let { m ->
            val n = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
            if (n != null && n in 1..10) return n
        }
        for ((words, n) in ORDINAL_WORDS) {
            if (words.any { w -> if (w[0] in 'a'..'z') word(w).containsMatchIn(text) else text.contains(w) }) return n
        }
        return 0
    }

    private val ROAD_MARKER = Regex("\\b(onto|toward|towards)\\s+|(?:^|\\s)(إلى|الى|نحو|باتجاه)\\s+", RegexOption.IGNORE_CASE)
    private val ROAD_END = Regex("\\s+[-–—·•|]\\s+|[,،]")
    private val INVISIBLE = Regex("[\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]")

    /** The road name from the original text, so its capitalisation and spelling are kept. */
    fun road(original: String): String {
        val text = original.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim()
        val marker = ROAD_MARKER.find(text) ?: return ""
        val rest = text.substring(marker.range.last + 1)
        val end = ROAD_END.find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end).trim().take(MAX_ROAD_LENGTH)
    }
}
