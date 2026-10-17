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

    fun profile(context: android.content.Context, settings: Settings): ClusterMapConfig.Profile = ClusterMapConfig.profile(
        ClusterMapConfig.Content.of(settings.clusterMapContent),
        ClusterMapConfig.Size.of(settings.clusterMapSize),
        ClusterMapConfig.Detail.of(settings.clusterMapDetail),
        settings.clusterMarkerX,
        settings.clusterMarkerY,
        ClusterMapConfig.Width.of(settings.clusterMapWidth),
        window(context, settings),
    )

    /**
     * The car's cluster theme: as the cluster itself reports it when DiAuto has Usage access (as
     * DiPlay follows it), otherwise the theme picked in Settings. A wrong guess puts the map on a
     * layer the cluster shows over everything, so the reported theme always wins.
     */
    fun theme(context: android.content.Context, settings: Settings): ClusterMapConfig.Theme {
        val reported = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            ClusterMapConfig.measured(android.os.Build.FINGERPRINT)
        ) com.andrerinas.openheadunit.hud.DiLink51ClusterMonitor.snapshotNow(context)?.theme else null
        return reported ?: ClusterMapConfig.Theme.of(settings.clusterTheme)
    }

    /** The map window of the car's cluster theme, on measured DiLink 5.1 firmware only. */
    fun window(context: android.content.Context, settings: Settings): ClusterMapConfig.Window? =
        if (ClusterMapConfig.measured(android.os.Build.FINGERPRINT)) ClusterMapConfig.window(theme(context, settings))
        else null

    fun build(context: android.content.Context, settings: Settings): List<Control.Service> {
        if (!settings.clusterMapEnabled) return emptyList()
        // DiAuto draws the turn card itself from the guidance the HUD uses, so the phone gets no
        // second display, and Google Maps keeps its own view on the main screen.
        if (ClusterMapConfig.Content.of(settings.clusterMapContent) == ClusterMapConfig.Content.TURN_CARD) return emptyList()
        val p = profile(context, settings)
        AppLog.i("[ServiceDiscovery] Announcing dashboard display: theme=${theme(context, settings)} type=${p.displayType} " +
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

    /**
     * The dashboard's UI config again, on its own channel: sent when the cluster theme changes
     * mid-session, so the phone moves the car marker into the new theme's window.
     */
    fun uiConfigUpdate(p: ClusterMapConfig.Profile): com.andrerinas.openheadunit.aap.AapMessage {
        fun insets(i: ClusterMapConfig.Insets) = Media.Insets.newBuilder()
            .setTop(i.top).setBottom(i.bottom).setLeft(i.left).setRight(i.right).build()
        val request = Media.UpdateUiConfigRequest.newBuilder().setUiConfig(
            Media.UiConfig.newBuilder()
                .setMargins(insets(p.margins))
                .setContentInsets(insets(p.contentInsets))
                .setStableContentInsets(insets(p.contentInsets))
                .setUiTheme(0)
                .build()
        ).build()
        AppLog.i("[ClusterMap] TX dashboard UpdateUiConfigRequest insets=${p.contentInsets}")
        return com.andrerinas.openheadunit.aap.AapMessage(
            Channel.ID_CLUSTER_VID, Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE, request)
    }

    /** Roughly the driver's distance to an instrument cluster. */
    private const val VIEWING_DISTANCE_MM = 750
}
