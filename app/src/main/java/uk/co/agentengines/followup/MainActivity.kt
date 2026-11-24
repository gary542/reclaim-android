package uk.co.agentengines.followup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Pine = Color(0xFF163F37)
val Ink = Color(0xFF18352F)
val Soft = Color(0xFF4C625B)
val Paper = Color(0xFFF7F8F3)
val Lime = Color(0xFFD5F6A4)
val Edge = Color(0xFFCBD7CE)
val Warm = Color(0xFFFFE9CF)
val Signal = Color(0xFF7B4314)
val Shape = RoundedCornerShape(18.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[WorkspaceModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    model.refresh()
                    delay(30_000)
                }
            }
        }
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Pine,
                        onPrimary = Color.White,
                        surface = Paper,
                        background = Paper,
                        onBackground = Ink,
                        onSurface = Ink,
                        outline = Soft,
                        primaryContainer = Lime,
                        onPrimaryContainer = Ink,
                        surfaceVariant = Color(0xFFE7EEE8),
                        onSurfaceVariant = Soft,
                        secondary = Pine,
                    )
            ) {
                ReclaimApp(model)
            }
        }
    }
}

@Composable
fun Title(text: String) {
    Text(text, color = Ink, fontFamily = FontFamily.Serif, fontSize = 35.sp, lineHeight = 39.sp)
}

@Composable
fun Copy(text: String) {
    Text(text, color = Soft, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
fun StrongButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

@Composable
fun Outline(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(Shape)
            .background(Color.White)
            .border(1.dp, Edge, Shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

@Composable
fun Status(b: Booking) {
    val color =
        when {
            b.review || b.status == "released" -> Warm
            b.status == "confirmed" || b.status == "refilled" -> Lime
            else -> Color(0xFFE7EEE8)
        }
    Text(
        b.label,
        modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
                .background(color)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        color = if (color == Warm) Signal else Ink,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
    )
}

@Composable
fun ReclaimApp(m: WorkspaceModel) {
    var screen by rememberSaveable { mutableStateOf("home") }
    var id by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Upcoming") }
    val selected = m.data.bookings.find { it.id == id }
    fun home() {
        screen = "home"
        m.clearError()
    }
    fun detail(item: String) {
        id = item
        screen = "detail"
        m.clearError()
    }
    BackHandler(enabled = screen != "home") { if (!m.busy) home() }
    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (screen == "home") {
                Image(painterResource(R.drawable.ic_reclaim), null, Modifier.size(30.dp))
                Text("Reclaim", color = Pine, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            } else {
                TextButton(onClick = { home() }, enabled = !m.busy) {
                    Text("‹  Bookings", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (screen != "connection")
                TextButton(
                    onClick = {
                        m.clearError()
                        screen = "connection"
                    },
                    enabled = !m.busy,
                ) {
                    Text(
                        when (m.data.mode) {
                            "demo" -> "Demo workspace"
                            "test" -> "Test connection"
                            "live" -> "Connected"
                            else -> "Connection"
                        }
                    )
                }
        }
        if (m.error.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Warm)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.error, Modifier.weight(1f), color = Signal, fontSize = 14.sp)
                TextButton(onClick = { m.clearError() }) { Text("Dismiss") }
            }
        }
        when (screen) {
            "new" -> BookingForm(m) { detail(it) }
            "connection" ->
                ConnectionScreen(m) {
                    home()
                    filter = "Upcoming"
                }
            "detail" ->
                if (selected != null) Detail(m, selected)
                else
                    Column(Modifier.padding(20.dp)) {
                        Copy(
                            if (m.paired) "Load the workspace to view this booking."
                            else "This booking is unavailable."
                        )
                        Outline("Back to bookings") { home() }
                    }
            else -> Dashboard(m, filter, { filter = it }, { screen = "new" }, ::detail)
        }
    }
}

@Composable
fun Dashboard(
    m: WorkspaceModel,
    filter: String,
    onFilter: (String) -> Unit,
    onNew: () -> Unit,
    open: (String) -> Unit,
) {
    val now = System.currentTimeMillis()
    val future = m.data.bookings.filter { it.startsAt > now && it.status != "cancelled" }
    val active =
        when (filter) {
            "Open slots" -> future.filter { it.status == "released" }
            "Awaiting" -> future.filter { it.status == "awaiting" }
            "Review" ->
                future.filter { it.review || (!it.linked && it.startsAt - now <= 48 * HOUR) }
            "History" ->
                m.data.bookings
                    .filter { it.startsAt <= now || it.status == "cancelled" }
                    .sortedByDescending { it.startsAt }
            else -> future
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Pine, Shape).padding(19.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK)),
                    color = Lime,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Appointment book",
                    color = Color.White,
                    fontFamily = FontFamily.Serif,
                    fontSize = 33.sp,
                    lineHeight = 37.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf(
                            "Awaiting" to future.count { it.status == "awaiting" },
                            "Open slots" to future.count { it.status == "released" },
                            "Review" to
                                future.count {
                                    it.review || (!it.linked && it.startsAt - now <= 48 * HOUR)
                                },
                        )
                        .forEach { (label, count) ->
                            Column(
                                Modifier.clickable { onFilter(label) }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    count.toString(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 21.sp,
                                )
                                Text(label, color = Lime, fontSize = 13.sp)
                            }
                        }
                }
            }
        }
        item {
            StrongButton(
                "Add appointment",
                !m.busy && (!m.paired || m.data.whatsapp || m.data.messenger),
                onNew,
            )
        }
        if (!m.paired && m.data.bookings.isEmpty())
            item {
                Panel {
                    Text(
                        "Try a customer reply",
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Copy(
                        "Use a fictional appointment to see what happens when a customer replies YES or NO."
                    )
                    Outline("Try a sample booking", !m.busy) { m.sample(open) }
                }
            }
        if (m.paired && (m.stale || m.data.mode == "pending" || m.data.mode == "off"))
            item {
                Panel {
                    Copy(
                        if (m.stale)
                            "Refresh failed. The list shows the last information loaded on this phone."
                        else if (m.data.mode == "off")
                            "The service is connected. Sending is switched off."
                        else "Waiting for the first workspace update."
                    )
                    Outline("Refresh bookings", !m.busy) { m.refresh() }
                }
            }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf("Upcoming", "Awaiting", "Open slots", "Review", "History").forEach { name ->
                    FilterChip(
                        selected = filter == name,
                        onClick = { onFilter(name) },
                        label = { Text(name) },
                    )
                }
            }
        }
        if (active.isEmpty())
            item {
                Copy(
                    when (filter) {
                        "Upcoming" -> "Your upcoming appointments will appear here."
                        "Open slots" -> "Customers who reply NO free a slot here."
                        "Awaiting" -> "No appointments are waiting for a reply."
                        "Review" -> "No appointments need your attention."
                        else -> "Past and cancelled appointments appear here."
                    }
                )
            }
        items(active, key = { it.id }) { b ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(Shape)
                    .background(Color.White)
                    .border(1.dp, Edge, Shape)
                    .clickable { open(b.id) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    appointmentTime(b.startsAt, b.zone, "EEE d MMM · HH:mm z"),
                    color = Soft,
                    fontSize = 13.sp,
                )
                Text(b.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Copy(b.service + " · " + channelName(b.channel))
                Status(b)
            }
        }
        if (!m.paired && m.data.bookings.isNotEmpty())
            item {
                TextButton(onClick = { m.sample(open) }, enabled = !m.busy) {
                    Text("Try another sample booking")
                }
                Text(
                    "Demo bookings stay on this phone. Customer messaging is off.",
                    color = Soft,
                    fontSize = 13.sp,
                )
            }
    }
}
