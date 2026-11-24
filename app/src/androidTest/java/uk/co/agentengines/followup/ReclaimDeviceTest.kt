package uk.co.agentengines.followup

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReclaimDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    val clean =
        object : ExternalResource() {
            override fun before() {
                listOf("demo-bookings-v1", "connection-v1", "slot-alerts-v1").forEach {
                    context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    private fun hit(text: String) {
        val node = ui.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        ui.waitUntil(30_000) {
            ui.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun appears(text: String) {
        ui.waitUntil(35_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sampleDeclineRefillSurvivesRelaunch() {
        hit("Try a sample booking")
        appears("Reply NO")
        hit("Reply NO")
        appears("Mark slot refilled")
        hit("Mark slot refilled")
        ui.onNodeWithText("Confirm").performClick()
        appears("Slot refilled")
        ui.activityRule.scenario.recreate()
        appears("Slot refilled")
        assertEquals("refilled", DemoStore(context).read().bookings.single().status)
    }

    @Test
    fun silenceNeverFreesTheSlotAndYesConfirms() {
        hit("Try a sample booking")
        appears("Simulate 24-hour follow-up")
        hit("Simulate 24-hour follow-up")
        appears("Simulate no reply")
        hit("Simulate no reply")
        appears("Owner review")
        assertEquals("awaiting", DemoStore(context).read().bookings.single().status)
        hit("Reply YES")
        appears("Appointment confirmed")
        assertEquals("confirmed", DemoStore(context).read().bookings.single().status)
    }

    @Test
    fun messengerBookingValidatesPermissionAndLinks() {
        hit("Add appointment")
        ui.onNodeWithText("Customer name").performTextInput("Taylor Example")
        ui.onNodeWithText("Appointment or service").performTextInput("Consultation")
        hit("Messenger")
        hit("Save appointment")
        appears("Confirm permission to send before saving.")
        hit("The customer agreed to appointment reminders on Messenger.")
        hit("Save appointment")
        appears("Simulate customer linking")
        hit("Simulate customer linking")
        appears("Simulate 48-hour reminder")
        hit("Simulate 48-hour reminder")
        appears("Reply YES")
        hit("Reply YES")
        appears("Appointment confirmed")
    }

    @Test
    fun secureClientPairsAndUsesTheDocumentedContract() {
        val ca =
            HeldCertificate.Builder().commonName("Reclaim test CA").certificateAuthority(1).build()
        val leaf =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .signedBy(ca)
                .build()
        val serverKeys =
            HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val old = HttpsURLConnection.getDefaultSSLSocketFactory()
        val server = MockWebServer()
        try {
            server.useHttps(serverKeys.sslSocketFactory(), false)
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(trust.sslSocketFactory())
            val token = UUID.randomUUID().toString()
            val login = Access.validate("https://localhost:" + server.port, token)
            assertTrue(runCatching { Access.validate("http://localhost", token) }.isFailure)
            assertTrue(runCatching { Access.validate("https://user@localhost", token) }.isFailure)
            val sample = DemoStore(context).sample()
            fun body(j: JSONObject) =
                MockResponse().setHeader("Content-Type", "application/json").setBody(j.toString())
            val snapshot =
                JSONObject()
                    .put("protocol", 1)
                    .put("mode", "test")
                    .put("business", "Fixture Studio")
                    .put("channels", JSONObject().put(WA, true).put(FB, true))
                    .put("appointments", JSONArray())
            val b =
                Booking(
                    UUID.randomUUID().toString(),
                    "Alex Example",
                    "Haircut",
                    WA,
                    System.currentTimeMillis() + 50 * HOUR,
                    ZoneId.systemDefault().id,
                )

            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.getHeader("Authorization") != "Bearer " + token ->
                                MockResponse().setResponseCode(401)
                            request.method == "GET" && request.path == "/v1/workspace" ->
                                body(snapshot)
                            request.method == "PUT" && request.path == "/v1/appointments/" + b.id ->
                                body(JSONObject().put("appointment", b.json()))
                            request.method == "POST" &&
                                request.path == "/v1/appointments/" + b.id + "/actions" ->
                                body(
                                    JSONObject()
                                        .put("appointment", b.copy(status = "confirmed").json())
                                )
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            fun requestFor(method: String): RecordedRequest {
                repeat(8) {
                    val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    if (request.method == method) return request
                }
                error("Expected request did not arrive")
            }
            hit("Demo workspace")
            ui.onNodeWithText("Reclaim service URL").performTextInput(login.origin)
            ui.onNodeWithText("Device access key").performTextInput(token)
            hit("Connect workspace")
            appears("Test connection")
            val req = requestFor("GET")
            assertEquals("/v1/workspace", req.path)
            assertEquals("Bearer " + token, req.getHeader("Authorization"))
            assertEquals(login, Access(context).load())
            assertFalse(
                context
                    .getSharedPreferences("connection-v1", Context.MODE_PRIVATE)
                    .getString("login", "")!!
                    .contains(token)
            )
            assertTrue(ui.onAllNodesWithText(sample.name).fetchSemanticsNodes().isEmpty())
            val d =
                Draft(b.id, b.name, b.service, b.channel, "+447700900123", b.startsAt, b.zone, true)
            assertEquals(b.id, ReclaimApi(login).create(d).id)
            val create = requestFor("PUT")
            assertEquals("PUT", create.method)
            assertEquals("/v1/appointments/" + b.id, create.path)
            assertTrue(JSONObject(create.body.readUtf8()).getBoolean("consent"))
            assertEquals("confirmed", ReclaimApi(login).action(b, "confirm").status)
            val action = requestFor("POST")
            assertEquals("POST", action.method)
            assertEquals("/v1/appointments/" + b.id + "/actions", action.path)
            hit("Test connection")
            appears("Disconnect this phone")
            hit("Disconnect this phone")
            appears("Disconnect")
            hit("Disconnect")
            appears("Demo workspace")
            assertNull(Access(context).load())
            assertEquals(sample.id, DemoStore(context).read().bookings.single().id)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(old)
            server.shutdown()
        }
    }
}
