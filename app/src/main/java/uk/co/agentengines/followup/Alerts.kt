package uk.co.agentengines.followup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

object Alerts {
    private const val CHANNEL = "open-slots"
    private const val WORK = "reclaim-slot-check"

    private fun prefs(c: Context) = c.getSharedPreferences("slot-alerts-v1", Context.MODE_PRIVATE)

    fun on(c: Context) = prefs(c).getBoolean("enabled", false)

    fun permission(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun start(c: Context, bookings: List<Booking>) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Open appointment slots",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        val seen = bookings.filter { it.status == "released" }.map { it.id }.toSet()
        prefs(c).edit().putBoolean("enabled", true).putStringSet("seen", seen).commit()
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<SlotWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        WorkManager.getInstance(c)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @Synchronized
    fun stop(c: Context) {
        prefs(c).edit().clear().commit()
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        c.getSystemService(NotificationManager::class.java).cancel(48)
    }

    @Synchronized
    fun check(c: Context, login: Login, bookings: List<Booking>) {
        if (
            !on(c) ||
                !permission(c) ||
                !NotificationManagerCompat.from(c).areNotificationsEnabled() ||
                Access(c).load() != login
        )
            return
        val open =
            bookings
                .filter { it.status == "released" && it.startsAt > System.currentTimeMillis() }
                .map { it.id }
                .toSet()
        val fresh = open - prefs(c).getStringSet("seen", emptySet()).orEmpty()
        if (fresh.isNotEmpty()) {
            val pi =
                PendingIntent.getActivity(
                    c,
                    1,
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_alert)
                    .setContentTitle(
                        if (fresh.size == 1) "An appointment slot has opened"
                        else "Appointment slots have opened"
                    )
                    .setContentText("Open Reclaim to review availability.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build()
            c.getSystemService(NotificationManager::class.java).notify(48, notification)
        }
        prefs(c).edit().putStringSet("seen", open).commit()
    }
}

class SlotWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result =
        try {
            val c = applicationContext
            val login = Access(c).load()
            if (login != null && Alerts.on(c))
                Alerts.check(c, login, ReclaimApi(login).workspace().bookings)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
}
