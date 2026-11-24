package uk.co.agentengines.followup

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

const val HOUR = 3_600_000L
const val WA = "whatsapp"
const val FB = "messenger"

fun channelName(channel: String) = if (channel == WA) "WhatsApp" else "Messenger"

fun appointmentTime(ms: Long, zone: String, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.UK)
        .withZone(ZoneId.of(zone))
        .format(Instant.ofEpochMilli(ms))

data class Booking(
    val id: String,
    val name: String,
    val service: String,
    val channel: String,
    val startsAt: Long,
    val zone: String,
    val status: String = "scheduled",
    val stage: Int = 0,
    val review: Boolean = false,
    val note: String = "",
    val revision: Int = 1,
    val linked: Boolean = true,
    val linkCode: String = "",
) {
    val label: String
        get() =
            when (status) {
                "released" -> "Open slot"
                "refilled" -> "Refilled"
                "confirmed" -> "Confirmed"
                "cancelled" -> "Cancelled"
                "awaiting" -> if (review) "Needs review" else "Awaiting reply"
                else ->
                    if (!linked) "Awaiting link" else if (review) "Needs review" else "Scheduled"
            }

    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("service", service)
            .put("channel", channel)
            .put("startsAt", startsAt)
            .put("zone", zone)
            .put("status", status)
            .put("stage", stage)
            .put("review", review)
            .put("note", note)
            .put("revision", revision)
            .put("linked", linked)
            .put("linkCode", linkCode)

    companion object {
        fun parse(j: JSONObject) =
            Booking(
                j.getString("id"),
                j.getString("name"),
                j.getString("service"),
                j.getString("channel"),
                j.getLong("startsAt"),
                j.getString("zone"),
                j.getString("status"),
                j.getInt("stage"),
                j.getBoolean("review"),
                j.getString("note"),
                j.getInt("revision"),
                j.getBoolean("linked"),
                j.optString("linkCode"),
            )
    }
}

data class Draft(
    val id: String,
    val name: String,
    val service: String,
    val channel: String,
    val recipient: String,
    val startsAt: Long,
    val zone: String,
    val consent: Boolean,
) {
    fun json() =
        JSONObject()
            .put("name", name)
            .put("service", service)
            .put("channel", channel)
            .put("recipient", recipient)
            .put("startsAt", startsAt)
            .put("zone", zone)
            .put("consent", consent)
}

data class Workspace(
    val business: String = "AgentEngines",
    val mode: String = "demo",
    val whatsapp: Boolean = true,
    val messenger: Boolean = true,
    val bookings: List<Booking> = emptyList(),
) {
    companion object {
        fun parse(j: JSONObject): Workspace {
            require(
                j.getInt("protocol") == 1 && j.getString("mode") in setOf("test", "live", "off")
            ) {
                "This service uses a different Reclaim protocol."
            }
            val channels = j.getJSONObject("channels")
            val items = j.getJSONArray("appointments")
            return Workspace(
                j.getString("business"),
                j.getString("mode"),
                channels.getBoolean(WA),
                channels.getBoolean(FB),
                List(items.length()) { Booking.parse(items.getJSONObject(it)) },
            )
        }
    }
}

class DemoStore(context: Context) {
    private val prefs = context.getSharedPreferences("demo-bookings-v1", Context.MODE_PRIVATE)

    fun read(): Workspace {
        val array = JSONArray(prefs.getString("items", "[]"))
        return Workspace(
            bookings =
                List(array.length()) { Booking.parse(array.getJSONObject(it)) }
                    .sortedBy { it.startsAt }
        )
    }

    private fun save(b: Booking): Booking {
        val updated = read().bookings.filter { it.id != b.id } + b
        check(
            prefs
                .edit()
                .putString("items", JSONArray(updated.map { it.json() }).toString())
                .commit()
        ) {
            "Could not save the demo booking."
        }
        return b
    }

    fun create(d: Draft) =
        save(
            Booking(
                d.id,
                d.name,
                d.service,
                d.channel,
                d.startsAt,
                d.zone,
                linked = d.channel == WA,
                linkCode = if (d.channel == FB) d.id.replace("-", "").take(12).uppercase() else "",
            )
        )

    fun sample(): Booking {
        val zone = ZoneId.systemDefault()
        val start = Instant.now().atZone(zone).plusHours(48).withSecond(0).withNano(0)
        return save(
            Booking(
                UUID.randomUUID().toString(),
                "Jamie Park",
                "Cut and finish",
                WA,
                start.toInstant().toEpochMilli(),
                zone.id,
                "awaiting",
                1,
            )
        )
    }

    fun change(id: String, action: String): Booking {
        val b = read().bookings.first { it.id == id }
        val next =
            when (action) {
                "link" -> b.copy(linked = true, linkCode = "")
                "send" -> b.copy(status = "awaiting", stage = 1)
                "followup" -> b.copy(stage = 2)
                "quiet" ->
                    b.copy(
                        review = true,
                        note = "No reply yet. Check with the customer before releasing this slot.",
                    )
                "confirm" -> b.copy(status = "confirmed", review = false, note = "")
                "release" -> b.copy(status = "released", review = false, note = "")
                "refill" -> b.copy(status = "refilled", review = false, note = "")
                "cancel" -> b.copy(status = "cancelled", review = false, note = "")
                else -> error("Unknown booking action.")
            }
        return save(next.copy(revision = b.revision + 1))
    }
}
