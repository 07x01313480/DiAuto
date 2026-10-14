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
 * and closing a voice session, so the Assistant can be given a stream of its own.
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
                val voice = if (voiceStream == AUTOMATIC) navi else voiceStream
                val usage = if (voice == navi && navi != USAGE_ONLY) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    else AudioAttributes.USAGE_ASSISTANT
                Route(usage, AudioAttributes.CONTENT_TYPE_SPEECH, voice.takeIf { it in STREAMS })
            } else {
                Route(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH,
                    navi.takeIf { it in STREAMS })
            }
        }
        Channel.ID_AU2 -> Route(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.CONTENT_TYPE_SONIFICATION, null)
        else -> Route(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, null)
    }

    /** The navigation stream for a setting value; automatic picks the DiLink default. */
    fun resolveNavi(naviStream: Int, dilink: Int): Int =
        if (naviStream == AUTOMATIC) defaultNaviStream(dilink) else naviStream

    fun defaultNaviStream(dilink: Int): Int = when (dilink) {
        3, 4 -> 14
        5 -> 15
        else -> USAGE_ONLY
    }

    /** Whether a voice-session change can move the speech channel to another slider. */
    fun speechRouteChanges(sdkInt: Int, naviStream: Int, voiceStream: Int, dilink: Int): Boolean =
        sdkInt >= ASSISTANT_MIN_SDK &&
            routeFor(Channel.ID_AU1, true, sdkInt, naviStream, voiceStream, dilink) !=
            routeFor(Channel.ID_AU1, false, sdkInt, naviStream, voiceStream, dilink)

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
