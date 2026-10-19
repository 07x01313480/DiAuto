package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import android.os.Build
import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Puts each Android Auto audio channel on the car's own volume slider.
 *
 * BYD DiLink does not sort sound by Android's audio usages: its Navi slider follows a vendor
 * stream type that only the legacy AudioTrack constructor accepts - 14 on DiLink 3/4 and 15 on
 * DiLink 5, the values DiPlay ships for the same cars. Media keeps the media usage. The speech
 * channel carries both navigation prompts and the Assistant; Android Auto says which by opening
 * and closing a voice session, so the Assistant can be given a stream of its own: the Voice
 * slider follows stream 17 on DiLink 5 (a Qin Plus 2025 moved stream 17 and
 * `volume_btts_speaker` together with that slider, and nothing else).
 *
 * Stream setting values: -1 automatic, 0 Android usage only, 1..20 that stream type.
 */
internal object CarVolumeGroups {

    const val AUTOMATIC = -1
    const val USAGE_ONLY = 0
    val STREAMS = 1..20

    /** How a track is built: a vendor stream type when [legacyStream] is set, else by usage. */
    data class Route(val usage: Int, val contentType: Int, val legacyStream: Int?)

    /** USAGE_ASSISTANT exists from API 26; older systems keep the Assistant with navigation. */
    private const val ASSISTANT_MIN_SDK = 26

    fun routeFor(
        channel: Int,
        assistantActive: Boolean,
        sdkInt: Int,
        naviStream: Int,
        voiceStream: Int,
        dilink: Int,
    ): Route = when (channel) {
        Channel.ID_AU1 -> {
            val navi = resolveNavi(naviStream, dilink)
            if (assistantActive && sdkInt >= ASSISTANT_MIN_SDK) {
                val voice = resolveVoice(voiceStream, naviStream, dilink)
                val usage = if (voice == navi && navi != USAGE_ONLY) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_ASSISTANT
                Route(usage, AudioAttributes.CONTENT_TYPE_SPEECH, voice.takeIf { it in STREAMS })
            } else {
                Route(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH,
                    navi.takeIf { it in STREAMS })
            }
        }
        Channel.ID_AU2 -> Route(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.CONTENT_TYPE_SONIFICATION, null)
        else -> {
            // Gemini answers on the media channel, not the speech one (Qin Plus 2025: only the
            // Media slider moved it). The phone pauses music while it talks, so during a voice
            // session the media channel carries the assistant and goes to the Voice slider.
            val voice = resolveVoice(voiceStream, naviStream, dilink)
            if (assistantActive && sdkInt >= ASSISTANT_MIN_SDK && voice in STREAMS && voice != resolveNavi(naviStream, dilink)) {
                Route(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH, voice)
            } else {
                Route(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, null)
            }
        }
    }

    /** The navigation stream for a setting value; automatic picks the DiLink default. */
    fun resolveNavi(naviStream: Int, dilink: Int): Int =
        if (naviStream == AUTOMATIC) defaultNaviStream(dilink) else naviStream

    fun defaultNaviStream(dilink: Int): Int = when (dilink) {
        3, 4 -> 14
        5 -> 15
        else -> USAGE_ONLY
    }

    /** The Assistant's stream for a setting value; automatic is DiLink 5's Voice slider, else navigation's. */
    fun resolveVoice(voiceStream: Int, naviStream: Int, dilink: Int): Int = when {
        voiceStream != AUTOMATIC -> voiceStream
        defaultVoiceStream(dilink) != AUTOMATIC -> defaultVoiceStream(dilink)
        else -> resolveNavi(naviStream, dilink)
    }

    /** The measured Voice slider stream, or [AUTOMATIC] where the Assistant shares navigation's. */
    fun defaultVoiceStream(dilink: Int): Int = if (dilink == 5) 17 else AUTOMATIC

    /**
     * Usages tried when a stream number does not reach its slider. Android maps a stream number
     * to sound attributes through the head unit's audio configuration; where that has no entry
     * for it the track plays as media, so the attributes whose volume the slider does control are
     * used instead.
     */
    val FALLBACK_USAGES = listOf(
        AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_MEDIA,
    )

    /**
     * Keeps [route]'s stream number when Android maps it to that slider ([legacyStreamOf] returns
     * the stream whose volume applies), else switches to the first usage that does reach it. With
     * neither, the route is unchanged, as before.
     */
    fun reach(route: Route, legacyStreamOf: (Int) -> Int?, usageStreamOf: (Int) -> Int?): Route {
        val target = route.legacyStream ?: return route
        if (legacyStreamOf(target) == target) return route
        val usage = (listOf(route.usage) + FALLBACK_USAGES).distinct().firstOrNull { usageStreamOf(it) == target }
            ?: return route
        return route.copy(usage = usage, legacyStream = null)
    }

    /** [reach] with this head unit's own mapping. */
    fun reachOnThisUnit(route: Route): Route = reach(route, ::volumeStreamOfLegacy, ::volumeStreamOfUsage)

    fun volumeStreamOfLegacy(stream: Int): Int? = runCatching {
        AudioAttributes.Builder().setLegacyStreamType(stream).build().volumeControlStream
    }.getOrNull()

    fun volumeStreamOfUsage(usage: Int): Int? = runCatching {
        AudioAttributes.Builder().setUsage(usage).build().volumeControlStream
    }.getOrNull()

    /** Whether a voice-session change moves [channel] to another slider. */
    fun routeChanges(channel: Int, sdkInt: Int, naviStream: Int, voiceStream: Int, dilink: Int): Boolean =
        sdkInt >= ASSISTANT_MIN_SDK &&
            routeFor(channel, true, sdkInt, naviStream, voiceStream, dilink) !=
            routeFor(channel, false, sdkInt, naviStream, voiceStream, dilink)

    /** Whether a voice-session change can move the speech channel to another slider. */
    fun speechRouteChanges(sdkInt: Int, naviStream: Int, voiceStream: Int, dilink: Int): Boolean =
        routeChanges(Channel.ID_AU1, sdkInt, naviStream, voiceStream, dilink)

    private val VERSION = Regex("""DiLink\s*([345])(?:\.\d+)?""", RegexOption.IGNORE_CASE)

    /** The DiLink generation (3, 4 or 5), or 0 when this is not a recognised BYD head unit. */
    fun detectDiLink(product: String?, fingerprint: String?, display: String?, sdkInt: Int, controllerVersion: String?): Int {
        for (value in listOf(product, fingerprint, display)) {
            val match = value?.let(VERSION::find) ?: continue
            return match.groupValues[1].toInt()
        }
        when (controllerVersion?.trim()?.substringBefore('.')?.toIntOrNull()) {
            13, 15, 18 -> return 3
            16, 17, 21 -> return 4
            23, 34, 57, 59 -> return 5
        }
        if (fingerprint?.startsWith("BYD-AUTO/IVI/") == true && sdkInt >= 33) return 5
        return 0
    }

    fun detectDiLink(): Int = detectDiLink(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY, Build.VERSION.SDK_INT,
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java, String::class.java)
                .invoke(null, "apps.setting.product.outswver", "")?.toString()
        }.getOrNull())
}
