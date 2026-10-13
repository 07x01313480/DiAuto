package com.andrerinas.openheadunit.main

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * DiAuto Wire has no setup wizard. What the wizard applied for a fresh install — the display
 * setup the panel warrants and a fitting DPI — is applied once on first start instead, and can be
 * applied again from Settings ("Auto-optimize").
 */
object LiteSetup {

    /** Same value the removed wizard stored, so other screens see setup as done. */
    const val ONBOARDING_VERSION = 2

    /** Runs once: applies the recommended display setup on a fresh install and marks setup done. */
    fun runFirstStart(activity: Activity, settings: Settings) {
        if (settings.onboardingVersion >= ONBOARDING_VERSION) return
        // Upgraders already have a working config; only a fresh install gets the recommendation.
        val fresh = !settings.hasCompletedSetupWizard && settings.onboardingVersion == 0
        if (fresh) applyRecommendedDisplay(activity, settings)
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = ONBOARDING_VERSION
        settings.commit()
    }

    /** Writes the resolution, codec, view mode, orientation and DPI recommended for this panel. */
    fun applyRecommendedDisplay(activity: Activity, settings: Settings) {
        val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val panel = realMetrics(activity)
        val preset = estimateSizePreset(panel)
        val result = SystemOptimizer(activity).calculateOptimalSettings(preset, portrait)
        val previousViewMode = settings.viewMode
        settings.resolutionId = SystemOptimizer.recommendedResolution(panel.widthPixels, panel.heightPixels).id
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.screenOrientation = result.suggestedOrientation
        // A changed renderer is confirmed on the first projection, as after the wizard.
        if (settings.viewMode != previousViewMode) settings.pendingRendererConfirm = true
        settings.dpiPixelDensity = result.recommendedDpi
        settings.commit()
        AppLog.i("LiteSetup: applied ${panel.widthPixels}x${panel.heightPixels} preset=$preset " +
            "codec=${result.recommendedVideoCodec} dpi=${result.recommendedDpi}")
    }

    private fun realMetrics(activity: Activity): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> activity.display?.getRealMetrics(metrics)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            else ->
                (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    /** Estimates the physical diagonal from real pixels and physical DPI and picks the closest preset. */
    private fun estimateSizePreset(m: DisplayMetrics): SystemOptimizer.DisplaySizePreset {
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull { abs(it.diagonalInch - diagonal) }
            ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }
}
