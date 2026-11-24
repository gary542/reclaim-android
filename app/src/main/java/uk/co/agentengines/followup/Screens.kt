package uk.co.agentengines.followup

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
}

@Composable
private fun Field(
    value: String,
    label: String,
    kind: KeyboardType = KeyboardType.Text,
    update: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = update,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kind),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BookingForm(m: WorkspaceModel, done: (String) -> Unit) {
    val ctx = LocalContext.current
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("") }
    var recipient by rememberSaveable { mutableStateOf("") }
    var channel by rememberSaveable { mutableStateOf(if (m.data.whatsapp) WA else FB) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(3).toString()) }
    var minutes by rememberSaveable { mutableIntStateOf(10 * 60) }
    var zoneName by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var lateOffset by rememberSaveable { mutableStateOf(false) }
    var issue by rememberSaveable { mutableStateOf("") }
    val localDate = LocalDate.parse(date)
    val t = LocalTime.of(minutes / 60, minutes % 60)
    val zone = ZoneId.of(zoneName)
    val ldt = LocalDateTime.of(localDate, t)
    val offsets = zone.rules.getValidOffsets(ldt)
    val at =
        offsets.let {
            if (it.isEmpty()) null
            else ldt.toInstant(if (lateOffset) it.last() else it.first()).toEpochMilli()
        }
    Page {
        Title("Add appointment")
        if (!m.paired) Copy("Use made-up details in the demo. These bookings stay on this phone.")
        Field(name, "Customer name") { name = it.take(60) }
        Field(service, "Appointment or service") { service = it.take(80) }
        Label("Appointment time")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                            ctx,
                            { _, y, mo, d ->
                                date = LocalDate.of(y, mo + 1, d).toString()
                                lateOffset = false
                            },
                            localDate.year,
                            localDate.monthValue - 1,
                            localDate.dayOfMonth,
                        )
                        .also { it.datePicker.minDate = System.currentTimeMillis() - 86_400_000 }
                        .show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(localDate.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)))
            }
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                            ctx,
                            { _, h, min ->
                                minutes = h * 60 + min
                                lateOffset = false
                            },
                            t.hour,
                            t.minute,
                            true,
                        )
                        .show()
                },
                modifier = Modifier.weight(.65f),
            ) {
                Text(t.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK)))
            }
        }
        Copy("Time zone · " + zoneName)
        if (offsets.size > 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(lateOffset, { lateOffset = it })
                Text("Use the later occurrence (" + offsets.last() + ")")
            }
        Label("Reminder channel")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(WA to m.data.whatsapp, FB to m.data.messenger).forEach { (c, available) ->
                FilterChip(
                    selected = channel == c,
                    enabled = available,
                    onClick = {
                        channel = c
                        consent = false
                    },
                    label = { Text(channelName(c)) },
                )
            }
        }
        if (channel == WA)
            Field(recipient, "WhatsApp number, e.g. +44…", KeyboardType.Phone) {
                recipient = it.take(35)
            }
        else
            Copy(
                "After saving, give the customer their link code to send to your Facebook Page in Messenger."
            )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { consent = !consent },
        ) {
            Checkbox(checked = consent, onCheckedChange = null, modifier = Modifier.padding(10.dp))
            Text(
                "The customer agreed to appointment reminders on " + channelName(channel) + ".",
                color = Ink,
            )
        }
        if (issue.isNotEmpty()) Text(issue, color = Signal)
        StrongButton(if (m.busy) "Saving…" else "Save appointment", !m.busy) {
            val n = recipient.replace(Regex("[\\s()-]"), "")
            issue =
                when {
                    name.isBlank() || service.isBlank() ->
                        "Enter the customer name and appointment."
                    at == null -> "Choose another time. The clocks move forward during this time."
                    at <= System.currentTimeMillis() ||
                        at > System.currentTimeMillis() + 730 * 24 * HOUR ->
                        "Choose a future appointment within two years."
                    channel == WA && !Regex("\\+[1-9]\\d{7,14}").matches(n) ->
                        "Enter the number with its country code, for example +447700900123."
                    !consent -> "Confirm permission to send before saving."
                    else -> ""
                }
            if (issue.isEmpty() && at != null)
                m.add(
                    Draft(
                        draftId,
                        name.trim(),
                        service.trim(),
                        channel,
                        if (channel == WA) n else "",
                        at,
                        zoneName,
                        true,
                    ),
                    done,
                )
        }
    }
}

@Composable
fun Detail(m: WorkspaceModel, b: Booking) {
    var confirm by rememberSaveable(b.id) { mutableStateOf("") }
    val demo = !m.paired
    val current =
        b.startsAt > System.currentTimeMillis() && b.status !in setOf("refilled", "cancelled")
    val pending = b.status == "scheduled" || b.status == "awaiting"
    val active = !m.busy && (!m.paired || !m.stale)
    Page {
        Status(b)
        Title(b.name)
        Label(b.service)
        Copy(
            appointmentTime(b.startsAt, b.zone, "EEEE d MMMM yyyy · HH:mm z") +
                "\n" +
                channelName(b.channel)
        )
        if (b.note.isNotBlank())
            Panel {
                Label("Owner review")
                Copy(b.note)
            }
        if (!b.linked && b.linkCode.isNotBlank() && current)
            Panel {
                Label("Link Messenger")
                Copy(
                    "Ask the customer to send this exact message to your business’s Facebook Page before the reminder is due."
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("LINK " + b.linkCode, color = Ink, fontWeight = FontWeight.Bold)
                }
                if (demo) Outline("Simulate customer linking", active) { m.action(b, "link") }
            }
        if (b.status == "released")
            Panel {
                Label("Available to refill")
                Copy(
                    "The original booking has been released. Once someone else takes the time, record it here."
                )
                if (current) StrongButton("Mark slot refilled", active) { confirm = "refill" }
                if (current && b.review)
                    Outline("Restore original appointment", active) { confirm = "confirm" }
            }
        if (b.status == "refilled")
            Panel {
                Label("Slot refilled")
                Copy("You recorded that someone else took this appointment time.")
            }
        if (b.status == "confirmed")
            Panel {
                Label("Appointment confirmed")
                Copy("This time is reserved for " + b.name + ".")
            }
        if (pending) {
            Panel {
                Label("Reminder schedule")
                Copy(
                    "48-hour check · " +
                        appointmentTime(b.startsAt - 48 * HOUR, b.zone, "EEE d MMM, HH:mm z")
                )
                Copy(
                    "24-hour follow-up · " +
                        appointmentTime(b.startsAt - 24 * HOUR, b.zone, "EEE d MMM, HH:mm z")
                )
                if (b.stage > 0)
                    Text(
                        if (demo)
                            (if (b.stage == 1) "First reminder simulated"
                            else "Follow-up simulated")
                        else
                            (if (b.stage == 1) "First reminder accepted by channel"
                            else "Follow-up accepted by channel"),
                        fontWeight = FontWeight.SemiBold,
                        color = Pine,
                    )
                if (b.stage == 0 && b.linked)
                    Copy(
                        if (demo) "Preview the reminder below."
                        else "The service checks due reminders every minute."
                    )
            }
            if (demo && current && b.linked) {
                Panel {
                    Label("Customer preview · demo")
                    Copy(
                        "Hi " +
                            b.name +
                            ", can you still make your appointment on " +
                            appointmentTime(b.startsAt, b.zone, "EEE d MMM 'at' HH:mm z") +
                            "? Reply YES to confirm or NO to release your slot."
                    )
                    if (b.stage == 0)
                        StrongButton("Simulate 48-hour reminder", active) { m.action(b, "send") }
                    else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { m.action(b, "confirm") },
                                enabled = active,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Reply YES")
                            }
                            OutlinedButton(
                                onClick = { m.action(b, "release") },
                                enabled = active,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Reply NO")
                            }
                        }
                        if (!b.review)
                            TextButton(
                                onClick = {
                                    m.action(b, if (b.stage == 1) "followup" else "quiet")
                                },
                                enabled = active,
                            ) {
                                Text(
                                    if (b.stage == 1) "Simulate 24-hour follow-up"
                                    else "Simulate no reply"
                                )
                            }
                    }
                }
            }
        }
        if (current && b.status != "released") {
            if (!demo || b.review) {
                if (pending) Outline("Record confirmation", active) { confirm = "confirm" }
                Outline("Release this slot", active) { confirm = "release" }
            }
            TextButton(onClick = { confirm = "cancel" }, enabled = active) {
                Text("Cancel appointment")
            }
        }
    }
    if (confirm.isNotEmpty()) {
        val labels =
            mapOf(
                "refill" to
                    ("Mark this slot refilled?" to
                        "The time will be removed from your open slots."),
                "release" to
                    ("Release this slot?" to
                        "This records a customer cancellation and makes the time available."),
                "confirm" to
                    ("Confirm this appointment?" to
                        "Record the confirmation you received and stop further reminders."),
                "cancel" to
                    ("Cancel this appointment?" to
                        "Future reminders will stop. Contact the customer separately about this change."),
            )
        val text = labels.getValue(confirm)
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text(text.first) },
            text = { Text(text.second) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val a = confirm
                        confirm = ""
                        m.action(b, a)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Go back") } },
        )
    }
}

@Composable
fun ConnectionScreen(m: WorkspaceModel, done: () -> Unit) {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf("") }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) m.changeAlerts(true)
            else permissionNote = "Slot alerts are off. You can still check Open slots in the app."
        }
    Page {
        Title("Connection")
        if (!m.paired) {
            Panel {
                Label("Demo workspace")
                Copy(
                    "Try the booking flow offline. Demo appointments stay separate from the connected workspace."
                )
            }
            Label("Connect your business")
            Copy(
                "Use the service address and device access key supplied by your Reclaim administrator."
            )
            Field(url, "Reclaim service URL", KeyboardType.Uri) { url = it.take(320) }
            OutlinedTextField(
                key,
                { key = it.take(160) },
                label = { Text("Device access key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            StrongButton(if (m.busy) "Connecting…" else "Connect workspace", !m.busy) {
                m.connect(url, key) {
                    key = ""
                    done()
                }
            }
        } else {
            Panel {
                Label(m.data.business)
                Copy(
                    when (m.data.mode) {
                        "test" -> "Test workspace · only approved testers can receive reminders."
                        "live" -> "Live workspace · customer reminders are enabled."
                        "off" -> "Connected · sending switched off."
                        else -> "Waiting to load the service."
                    }
                )
                Copy(
                    "WhatsApp · " +
                        (if (m.data.whatsapp) "Configured" else "Unavailable") +
                        "\nMessenger · " +
                        (if (m.data.messenger) "Configured" else "Unavailable")
                )
            }
            Outline("Refresh workspace", !m.busy) { m.refresh() }
            Panel {
                Label("Open-slot alerts")
                Copy(
                    "Reclaim checks while the app is open. Optional Android background checks can take 15 minutes or longer."
                )
                if (permissionNote.isNotBlank()) Copy(permissionNote)
                Outline(
                    if (m.alerts && Alerts.permission(ctx)) "Turn alerts off"
                    else "Enable slot alerts",
                    !m.busy && !m.stale && m.lastSync > 0,
                ) {
                    if (m.alerts && Alerts.permission(ctx)) m.changeAlerts(false)
                    else if (Build.VERSION.SDK_INT >= 33 && !Alerts.permission(ctx))
                        request.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else m.changeAlerts(true)
                }
            }
            Outline("Disconnect this phone", !m.busy) { disconnect = true }
        }
    }
    if (disconnect)
        AlertDialog(
            onDismissRequest = { disconnect = false },
            title = { Text("Disconnect this phone?") },
            text = {
                Text(
                    "The device key will be removed from this phone. Bookings and reminders continue on the server."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.disconnect()
                        disconnect = false
                        done()
                    }
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Go back") } },
        )
}
