package com.andrerinas.openheadunit.decoder

import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog

/**
 * DiAuto Wire ships without the bundled FFmpeg HEVC decoder, so this is never available and the
 * head unit's own hardware decoder is always used. The API is kept so the decoder pipeline stays
 * unchanged.
 */
@Suppress("UNUSED_PARAMETER")
class FfmpegHevcDecoder(
    surface: Surface?,
    yuvFrameSink: SoftwareYuvFrameSink?,
    width: Int,
    height: Int
) {
    val isStarted: Boolean get() = false

    fun start(): Boolean {
        AppLog.w("DiAuto Wire has no software HEVC decoder; using the hardware decoder")
        return false
    }

    fun decode(buffer: ByteArray, offset: Int, size: Int): Int = 0

    fun stop() = Unit

    companion object {
        fun isAvailable(): Boolean = false
    }
}
