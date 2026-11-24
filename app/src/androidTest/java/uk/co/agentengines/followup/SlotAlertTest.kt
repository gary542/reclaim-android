package uk.co.agentengines.followup

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlotAlertTest {
    @Test
    fun optInProducesOnePrivateNoticeForANewlyOpenedSlot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        val access = Access(ctx)
        val login = Login("https://localhost:9", UUID.randomUUID().toString())
        val b =
            Booking(
                UUID.randomUUID().toString(),
                "Private customer",
                "Private service",
                WA,
                System.currentTimeMillis() + 40 * HOUR,
                "Europe/London",
                status = "released",
            )
        fun notices() = manager.activeNotifications.filter { it.id == 48 }
        fun waitForCount(expected: Int) {
            val until = android.os.SystemClock.elapsedRealtime() + 15_000
            while (notices().size != expected && android.os.SystemClock.elapsedRealtime() < until) {
                Thread.sleep(100)
            }
            assertEquals(expected, notices().size)
        }
        Alerts.stop(ctx)
        try {
            access.save(login)
            Alerts.check(ctx, login, listOf(b))
            assertTrue(notices().isEmpty())
            Alerts.start(ctx, emptyList())
            if (Build.VERSION.SDK_INT >= 33 && !Alerts.permission(ctx)) {
                Alerts.check(ctx, login, listOf(b))
                assertTrue(notices().isEmpty())
                instrumentation.uiAutomation.grantRuntimePermission(
                    ctx.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            assertTrue(Alerts.permission(ctx))
            Alerts.check(ctx, login, listOf(b))
            waitForCount(1)
            val extras = notices().single().notification.extras
            assertEquals(
                "An appointment slot has opened",
                extras.getCharSequence("android.title").toString(),
            )
            assertFalse(extras.getCharSequence("android.text").toString().contains(b.name))
            manager.cancel(48)
            waitForCount(0)
            Alerts.check(ctx, login, listOf(b))
            assertTrue(
                ctx.getSharedPreferences("slot-alerts-v1", Context.MODE_PRIVATE)
                    .getStringSet("seen", emptySet())
                    .orEmpty()
                    .contains(b.id)
            )
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            assertTrue(notices().isEmpty())
        } finally {
            Alerts.stop(ctx)
            access.clear()
        }
    }
}
