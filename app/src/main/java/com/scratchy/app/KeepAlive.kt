package com.scratchy.app

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps the edge bar alive when you clear recent apps. Some phones (Xiaomi, Honor, Huawei) kill the whole app
 * then, and only let it come back if "Autostart" is allowed; this brings the bar back and walks you through
 * the phone's own settings.
 */
object KeepAlive {
    private fun prefs(c: Context) = c.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    /** Whether you turned the bar on; a restart (reboot, update, cleared apps) only brings it back if so. */
    fun wanted(c: Context) = prefs(c).getBoolean("bar_on", false)
    fun setWanted(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bar_on", on).apply()

    fun start(c: Context) {
        if (!Settings.canDrawOverlays(c) || OverlayService.running) return
        runCatching { c.startForegroundService(Intent(c, OverlayService::class.java)) }
    }

    /** Swiped away from recents: ask Android to start the bar again in a second. */
    fun scheduleRestart(c: Context) {
        val pi = PendingIntent.getForegroundService(
            c, 7, Intent(c, OverlayService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        c.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi)
    }

    fun batteryFree(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    private val brand get() = Build.MANUFACTURER.lowercase()
    private val xiaomi get() = brand in setOf("xiaomi", "redmi", "poco")

    /** Xiaomi keeps Autostart as its own permission; null when the phone doesn't say. */
    fun autostartAllowed(c: Context): Boolean? {
        if (!xiaomi) return null
        return runCatching {
            val ops = c.getSystemService(AppOpsManager::class.java)
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            check.invoke(ops, 10008, Process.myUid(), c.packageName) as Int == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    /** The phone's own "Autostart" / "App launch" page, if it has one. */
    private fun autostartPage(c: Context): Intent? {
        val pages = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        return pages.map { (p, a) -> Intent().setComponent(ComponentName(p, a)) }
            .firstOrNull { c.packageManager.resolveActivity(it, 0) != null }
    }

    /** Something is still missing that we know of: shown as a line on the notes screen. */
    fun needsSetup(c: Context) = !batteryFree(c) || autostartAllowed(c) == false

    fun showSetup(a: Activity) {
        val auto = autostartAllowed(a)
        val page = autostartPage(a)
        val s = Sheet(a).title("Keep the edge bar running")
            .message(
                "Some phones close Scratchy completely when you clear recent apps, and then don't let it start again. " +
                    "Allow these and the bar comes right back." +
                    (if (xiaomi) "\n\nAlso lock Scratchy in recent apps: open recents, long-press Scratchy and tap the lock." else "")
            )
        if (page != null) s.item(if (auto == true) "Autostart is allowed" else "Allow Autostart", checked = auto == true, accent = auto != true) {
            runCatching { a.startActivity(page) }.onFailure { openAppInfo(a) }
        }
        val free = batteryFree(a)
        s.item(if (free) "No battery restrictions" else "Remove battery restrictions", checked = free, accent = !free) {
            if (free) openAppInfo(a)
            else runCatching {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
            }.onFailure { openAppInfo(a) }
        }
        s.cancel("Done").show()
    }

    private fun openAppInfo(a: Activity) = runCatching {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
    }
}

/** After a reboot or an update, bring the bar back if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (KeepAlive.wanted(c)) KeepAlive.start(c)
        }
    }
}
