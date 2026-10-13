package com.andrerinas.openheadunit.aap

import android.media.AudioAttributes
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarVolumeGroupsTest {

    @Test
    fun `media plays on the media slider`() {
        val a = CarVolumeGroups.attributesFor(Channel.ID_AUD, assistantActive = false, sdkInt = 33)
        assertEquals(AudioAttributes.USAGE_MEDIA, a.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, a.contentType)
    }

    @Test
    fun `speech is navigation guidance outside a voice session`() {
        val a = CarVolumeGroups.attributesFor(Channel.ID_AU1, assistantActive = false, sdkInt = 33)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, a.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, a.contentType)
    }

    @Test
    fun `speech is the assistant during a voice session`() {
        val a = CarVolumeGroups.attributesFor(Channel.ID_AU1, assistantActive = true, sdkInt = 33)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, a.usage)
    }

    @Test
    fun `before API 26 the assistant stays on the navigation slider`() {
        val a = CarVolumeGroups.attributesFor(Channel.ID_AU1, assistantActive = true, sdkInt = 25)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, a.usage)
        assertFalse(CarVolumeGroups.speechLabelChanges(25))
        assertTrue(CarVolumeGroups.speechLabelChanges(26))
    }

    @Test
    fun `system sounds are sonification`() {
        val a = CarVolumeGroups.attributesFor(Channel.ID_AU2, assistantActive = false, sdkInt = 33)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, a.usage)
    }
}
