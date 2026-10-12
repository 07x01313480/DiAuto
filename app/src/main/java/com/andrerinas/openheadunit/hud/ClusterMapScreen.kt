package com.andrerinas.openheadunit.hud

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.protocol.messages.ClusterDisplayServices
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions

/**
 * Shows the Android Auto cluster stream on BYD's instrument-cluster projection display.
 *
 * BYD exposes that area as a public presentation display (owned by com.byd.containerservice on
 * DiLink 5, com.xdja.containerservice on DiLink 3/4); the dashboard shows it while its own navi
 * mode is Small or Full screen, which only the driver can switch. Display names and sizes are the
 * ones DiPlay measured on those firmwares; anything else is left alone rather than guessed.
 *
 * The decoded frame is the whole codec frame; [ClusterMapConfig.cropMatrix] shows only the part
 * the phone drew, so the 8:3 picture is not squashed by the frame's 16:9 shape.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class ClusterMapScreen(
    context: Context,
    display: Display,
    private val profile: ClusterMapConfig.Profile,
) : Presentation(context, display) {

    private var surface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val texture = TextureView(context)
        fun applyCrop(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val m = ClusterMapConfig.cropMatrix(profile, width, height)
            texture.setTransform(Matrix().apply { setScale(m[0], m[1]); postTranslate(m[2], m[3]) })
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(profile.codecWidth, profile.codecHeight)
                applyCrop(width, height)
                val s = Surface(st)
                surface = s
                AppLog.i("ClusterMap: surface ready ${width}x$height on display ${display.displayId}:${display.name}")
                decoder().setSurface(s)
                // A codec on a new surface needs a keyframe before it can draw anything.
                requestKeyframe(context)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCrop(width, height)
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                AppLog.i("ClusterMap: surface destroyed")
                surface?.let { decoder().stopIfCurrentSurface(it, "dashboard surface destroyed") }
                surface?.release()
                surface = null
                return true
            }
        }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun decoder() = App.provide(context).clusterVideoDecoder

    companion object {
        private const val DILINK5_BASE = "fission_bg_XDJAScreenProjection"
        private const val DILINK4_NAME = "fission_bg_xdjaVirtualSurface"

        private var current: ClusterMapScreen? = null
        private var currentOwner: Owner? = null

        /**
         * Who the dashboard window belongs to, tried in this order. A window owned by DiAuto's
         * screen is stopped with it, so the map would vanish whenever the camera or another app
         * comes to the front; the first two belong to the app and stay up until the session ends.
         */
        private enum class Owner { OVERLAY, APP, SCREEN }

        /** Shows the dashboard map while a session with it enabled is up. Safe to call repeatedly. */
        fun show(context: Context) {
            val settings = App.provide(context).settings
            if (!settings.clusterMapEnabled || current?.isShowing == true) return
            val display = findDisplay(context)
            if (display == null) {
                AppLog.w("ClusterMap: no BYD dashboard display among ${describeDisplays(context)}")
                return
            }
            val profile = ClusterDisplayServices.profile(settings)
            val app = context.applicationContext
            val owners = buildList {
                // "Display over other apps" lets the window outlive DiAuto's screen for certain.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppPermissions.isOverlayGranted(app)) add(Owner.OVERLAY)
                // Without it, a presentation of the app's own is accepted on many firmwares.
                add(Owner.APP)
                add(Owner.SCREEN)
            }
            for (owner in owners) {
                val screen = ClusterMapScreen(if (owner == Owner.SCREEN) context else app, display, profile)
                if (owner == Owner.OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    screen.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                val shown = runCatching { screen.show() }
                    .onFailure { AppLog.w("ClusterMap: $owner window refused on ${display.name}: ${it.message}") }
                    .isSuccess
                if (shown) {
                    current = screen
                    currentOwner = owner
                    AppLog.i("ClusterMap: shown as $owner window" +
                        if (owner == Owner.SCREEN) " (closes when DiAuto's screen is hidden)" else "")
                    return
                }
            }
        }

        /** DiAuto's screen is going away: closes the map only if that screen owns its window. */
        fun onScreenHidden() {
            if (currentOwner == Owner.SCREEN) dismiss()
        }

        /** The session ended. */
        fun dismiss() {
            current?.let { runCatching { it.dismiss() } }
            current = null
            currentOwner = null
        }

        fun requestKeyframe(context: Context) {
            App.provide(context).commManager.requestClusterKeyframe()
        }

        fun findDisplay(context: Context): Display? {
            val displays = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            // DiLink 5 / 5.1: the base projection display, else its full-map layer (_0).
            displays.firstOrNull { it.name == DILINK5_BASE }?.let { return it }
            displays.firstOrNull { it.name.contains(DILINK5_BASE) && it.name.endsWith("_0") }?.let { return it }
            displays.firstOrNull { it.name.contains(DILINK5_BASE) }?.let { return it }
            // DiLink 3 / 4: an exact name and one of the measured sizes, nothing looser.
            return displays.firstOrNull { d ->
                val size = sizeOf(d)
                d.name == DILINK4_NAME && ((size.x == 1920 && size.y == 720) || (size.x == 1280 && size.y == 480))
            }
        }

        fun describeDisplays(context: Context): String =
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.displays
                ?.joinToString { d -> val s = sizeOf(d); "${d.displayId}:${d.name} ${s.x}x${s.y}" }.orEmpty()

        private fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}
