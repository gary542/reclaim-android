package uk.co.agentengines.followup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

data class Login(val origin: String, val key: String)

class Access(context: Context) {
    private val prefs = context.getSharedPreferences("connection-v1", Context.MODE_PRIVATE)
    val paired: Boolean
        get() = prefs.contains("login")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("reclaim-device", null) as? SecretKey)?.let {
            return it
        }
        val spec =
            KeyGenParameterSpec.Builder(
                    "reclaim-device",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    fun save(login: Login) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain =
            JSONObject().put("origin", login.origin).put("key", login.key).toString().toByteArray()
        val data = cipher.iv + cipher.doFinal(plain)
        check(prefs.edit().putString("login", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }

    fun load(): Login? {
        val stored = prefs.getString("login", null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
        val obj = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
        return Login(obj.getString("origin"), obj.getString("key"))
    }

    fun clear() {
        prefs.edit().remove("login").commit()
    }

    companion object {
        fun validate(url: String, token: String): Login {
            val u =
                try {
                    URI(url.trim())
                } catch (_: Exception) {
                    null
                }
            require(
                u != null &&
                    u.scheme == "https" &&
                    !u.host.isNullOrBlank() &&
                    u.userInfo == null &&
                    u.query == null &&
                    u.fragment == null &&
                    (u.path.isNullOrEmpty() || u.path == "/")
            ) {
                "Use the HTTPS address of your Reclaim service."
            }
            require(token.trim().matches(Regex("[A-Za-z0-9_-]{32,160}"))) {
                "Enter the device access key issued for Reclaim."
            }
            return Login(u.toASCIIString().trimEnd('/'), token.trim())
        }
    }
}

class ReclaimApi(private val login: Login) {
    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = URL(login.origin + path).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + login.key)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val message =
                    conn.errorStream?.bufferedReader()?.use {
                        runCatching { JSONObject(it.readText()).getString("error") }.getOrNull()
                    }
                throw IOException(message?.take(180) ?: "The service returned HTTP " + code + ".")
            }
            return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            conn.disconnect()
        }
    }

    fun workspace() = Workspace.parse(call("GET", "/v1/workspace"))

    fun create(d: Draft) =
        Booking.parse(
            call("PUT", "/v1/appointments/" + d.id, d.json()).getJSONObject("appointment")
        )

    fun action(b: Booking, action: String) =
        Booking.parse(
            call(
                    "POST",
                    "/v1/appointments/" + b.id + "/actions",
                    JSONObject().put("action", action).put("revision", b.revision),
                )
                .getJSONObject("appointment")
        )
}
