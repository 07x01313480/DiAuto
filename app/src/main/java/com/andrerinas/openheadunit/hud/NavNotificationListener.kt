package com.andrerinas.openheadunit.hud

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Turn guidance on the BYD HUD and instrument cluster from Waze or Google Maps running on the
 * head unit itself, read from their navigation notification.
 *
 * Opt-in ([Settings.navNotificationGuidance]) and only while no Android Auto session is up: a
 * projected session already feeds [BydNavigationOutputs] with exact data and keeps priority.
 * Every changed notification is logged with what was read from it, so a diagnostic export shows
 * the wording a navigation app uses when it is not recognised yet.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class NavNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var trackedKey: String? = null
    private var last: BydGuidance? = null
    private var lastLoggedTexts: List<String>? = null

    /** Republishes the last instruction so a long, unchanged stretch does not expire on the HUD. */
    private val keepAlive = object : Runnable {
        override fun run() {
            last?.let { publish(it.copy(updatedNs = System.nanoTime())) }
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    override fun onListenerConnected() {
        AppLog.i("NavNotify: listener connected")
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName in NavNotificationParser.PACKAGES }
            ?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        clear("listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NavNotificationParser.PACKAGES) return
        if (!Settings(this).navNotificationGuidance) {
            if (trackedKey != null) clear("setting turned off")
            return
        }
        // Only the ongoing navigation notification, not traffic alerts or messages.
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) == 0) return

        val texts = texts(sbn)
        var instruction = NavNotificationParser.parse(texts)
        var source = "extras"
        if (instruction == null) {
            val viewTexts = remoteViewTexts(sbn)
            if (viewTexts.isNotEmpty()) {
                instruction = NavNotificationParser.parse(texts + viewTexts)
                source = "views"
                logSample(sbn.packageName, texts + viewTexts, instruction, source)
            } else {
                logSample(sbn.packageName, texts, null, source)
            }
        } else {
            logSample(sbn.packageName, texts, instruction, source)
        }

        val guidance = instruction?.let { toGuidance(it) }
        if (guidance == null) {
            // Another ongoing notification of the same app must not wipe the live instruction.
            if (trackedKey != null && sbn.key != trackedKey) return
            // Rerouting, an unknown wording or no distance: show nothing rather than a guess.
            trackedKey = sbn.key
            handler.removeCallbacks(keepAlive)
            last = null
            publish(null)
            return
        }
        trackedKey = sbn.key
        last = guidance
        publish(guidance)
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == trackedKey) clear("navigation notification removed")
    }

    override fun onDestroy() {
        clear("listener destroyed")
        super.onDestroy()
    }

    private fun clear(reason: String) {
        if (trackedKey == null && last == null) return
        AppLog.i("NavNotify: cleared ($reason)")
        handler.removeCallbacks(keepAlive)
        trackedKey = null
        last = null
        publish(null)
    }

    private fun publish(guidance: BydGuidance?) {
        // A projected Android Auto session owns the outputs and has exact data.
        if (runCatching { App.provide(this).commManager.isConnected }.getOrDefault(false)) return
        val app = applicationContext
        if (!Settings(app).bydNavigationEnabled) return
        BydNavigationOutputs.start(app)
        BydNavigationOutputs.update(guidance)
    }

    private fun toGuidance(instruction: NavInstruction): BydGuidance? {
        val codes = BydNavigationMapper.codes(instruction.maneuverType, instruction.roundaboutExit) ?: return null
        return BydGuidance(codes.cluster, codes.gaode, codes.arrow, codes.exit, instruction.distanceMeters, instruction.road)
    }

    private fun texts(sbn: StatusBarNotification): List<String> {
        val n = sbn.notification
        val extras = n.extras ?: return listOfNotNull(n.tickerText?.toString())
        val keys = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        )
        val values = keys.mapNotNull { extras.getCharSequence(it)?.toString() } +
            (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()) +
            listOfNotNull(n.tickerText?.toString())
        return values.filter { it.isNotBlank() }.distinct()
    }

    /** Text drawn by a custom notification layout, which some navigation apps use instead of extras. */
    private fun remoteViewTexts(sbn: StatusBarNotification): List<String> = runCatching {
        val n = sbn.notification
        val packageContext = createPackageContext(sbn.packageName, Context.CONTEXT_IGNORE_SECURITY)
        @Suppress("DEPRECATION")
        val views = listOfNotNull(n.bigContentView, n.contentView) + if (Build.VERSION.SDK_INT >= 24) {
            runCatching {
                val builder = Notification.Builder.recoverBuilder(packageContext, n)
                listOfNotNull(builder.createBigContentView(), builder.createContentView())
            }.getOrDefault(emptyList())
        } else emptyList()
        views.flatMap { textsOf(packageContext, it) }.filter { it.isNotBlank() }.distinct()
    }.getOrElse {
        AppLog.d("NavNotify: custom layout unreadable for ${sbn.packageName}: ${it.message}")
        emptyList()
    }

    private fun textsOf(packageContext: Context, views: RemoteViews): List<String> = runCatching {
        val inflater = LayoutInflater.from(packageContext)
        val root = inflater.inflate(views.layoutId, null)
        views.reapply(packageContext, root)
        val out = mutableListOf<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) view.text?.toString()?.let { out.add(it) }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        out
    }.getOrDefault(emptyList())

    /** Logs a notification once per wording; a distance counting down does not repeat it. */
    private fun logSample(pkg: String, texts: List<String>, result: NavInstruction?, source: String) {
        val signature = texts.map { it.replace(DIGITS, "#") }
        if (signature == lastLoggedTexts) return
        lastLoggedTexts = signature
        AppLog.i("NavNotify: $pkg [$source] ${texts.joinToString(" | ")} -> ${result ?: "not recognised"}")
    }

    companion object {
        private const val KEEP_ALIVE_MS = 10_000L
        private val DIGITS = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]+")

        /** Whether the user has granted DiAuto notification access in Android settings. */
        fun hasAccess(context: Context): Boolean {
            val enabled = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            val me = ComponentName(context, NavNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** The adb command that grants access on head units without a notification-access screen. */
        fun adbGrantCommand(context: Context): String =
            "adb shell cmd notification allow_listener " +
                ComponentName(context, NavNotificationListener::class.java).flattenToString()
    }
}
