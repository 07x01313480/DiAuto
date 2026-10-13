package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import com.andrerinas.openheadunit.aap.protocol.Channel

/**
 * Labels each Android Auto audio channel with the audio usage a car's own volume groups key on,
 * so the head unit's separate Media / Navi / Voice sliders (as on BYD DiLink) each move one kind
 * of sound. Without this the speech channel was played as a phone call and moved with "Call".
 *
 * The speech channel carries both navigation prompts and the Assistant's voice; Android Auto says
 * which by opening and closing a voice session, so it is labelled Assistant only during one.
 */
internal object CarVolumeGroups {

    data class Attributes(val usage: Int, val contentType: Int)

    /** USAGE_ASSISTANT exists from API 26; older systems keep the Assistant on the Navi slider. */
    private const val ASSISTANT_MIN_SDK = 26

    fun attributesFor(channel: Int, assistantActive: Boolean, sdkInt: Int): Attributes = when (channel) {
        Channel.ID_AU1 ->
            if (assistantActive && sdkInt >= ASSISTANT_MIN_SDK)
                Attributes(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH)
            else
                Attributes(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.CONTENT_TYPE_SPEECH)
        Channel.ID_AU2 ->
            Attributes(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.CONTENT_TYPE_SONIFICATION)
        else ->
            Attributes(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
    }

    /** Whether a voice-session change gives the speech channel a different label. */
    fun speechLabelChanges(sdkInt: Int): Boolean = sdkInt >= ASSISTANT_MIN_SDK
}
