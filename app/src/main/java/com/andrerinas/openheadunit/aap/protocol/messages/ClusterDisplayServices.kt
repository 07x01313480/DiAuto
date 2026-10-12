package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.hud.ClusterMapConfig
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The extra services that declare the BYD dashboard to the phone as a second display.
 *
 * Android Auto renders a navigation app's cluster map on a video sink whose display_type is
 * CLUSTER (Waze and Google Maps both support it). The turn card is an AUXILIARY display opened on
 * KEYCODE_TURN_CARD. Each display is declared with an input service of its own.
 */
internal object ClusterDisplayServices {

    // Android key codes a cluster/auxiliary display may receive (DPAD, media, navigation).
    private val AUXILIARY_KEYCODES = listOf(3, 4, 5, 6, 84, 85, 87, 88, 126, 127, 65537, 65538, 65540)

    fun profile(settings: Settings): ClusterMapConfig.Profile = ClusterMapConfig.profile(
        ClusterMapConfig.Content.of(settings.clusterMapContent),
        ClusterMapConfig.Size.of(settings.clusterMapSize),
        ClusterMapConfig.Detail.of(settings.clusterMapDetail),
        settings.clusterMarkerX,
        settings.clusterMarkerY,
        ClusterMapConfig.Width.of(settings.clusterMapWidth),
    )

    fun build(settings: Settings): List<Control.Service> {
        if (!settings.clusterMapEnabled) return emptyList()
        val p = profile(settings)
        AppLog.i("[ServiceDiscovery] Announcing dashboard display: type=${p.displayType} " +
            "codec=${p.codecWidth}x${p.codecHeight} content=${p.contentWidth}x${p.contentHeight} " +
            "density=${p.density} insets=${p.contentInsets}")

        fun insets(i: ClusterMapConfig.Insets) = Media.Insets.newBuilder()
            .setTop(i.top).setBottom(i.bottom).setLeft(i.left).setRight(i.right).build()

        val video = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType.forNumber(p.codecResolution)
            )
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(p.widthMargin)
            .setMarginHeight(p.heightMargin)
            .setDensity(p.density)
            .setRealDensity(p.density)
            .setDecoderAdditionalDepth(0)
            .setViewingDistance(VIEWING_DISTANCE_MM)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setUiConfig(
                Media.UiConfig.newBuilder()
                    .setMargins(insets(p.margins))
                    .setContentInsets(insets(p.contentInsets))
                    .setStableContentInsets(insets(p.contentInsets))
                    .setUiTheme(0) // automatic: follow the phone's day/night
                    .build()
            )
            .build()

        val sink = Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setAudioType(Media.AudioStreamType.NONE)
            .setAvailableWhileInCall(true)
            .addVideoConfigs(video)
            .setDisplayId(ClusterMapConfig.DISPLAY_ID)
            .setDisplayType(Control.Service.MediaSinkService.DisplayType.forNumber(p.displayType))
        p.initialContentKeycode?.let { sink.setInitialContentKeycode(it) }

        val services = mutableListOf(
            Control.Service.newBuilder().setId(Channel.ID_CLUSTER_VID).setMediaSinkService(sink).build()
        )
        // Every extra display gets an input service bound to it, as LIVI declares its cluster; only
        // the turn card takes keys.
        val input = Control.Service.InputSourceService.newBuilder().setDisplayId(ClusterMapConfig.DISPLAY_ID)
        if (p.displayType == ClusterMapConfig.DISPLAY_TYPE_AUXILIARY) input.addAllKeycodesSupported(AUXILIARY_KEYCODES)
        services += Control.Service.newBuilder().setId(Channel.ID_CLUSTER_INP).setInputSourceService(input).build()
        return services
    }

    /** Roughly the driver's distance to an instrument cluster. */
    private const val VIEWING_DISTANCE_MM = 750
}
