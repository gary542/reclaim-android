package uk.co.agentengines.followup

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkspaceModel(app: Application) : AndroidViewModel(app) {
    private val access = Access(app)
    private val demo = DemoStore(app)
    val ctx
        get() = getApplication<Application>()

    var paired by mutableStateOf(access.paired)
        private set

    var data by
        mutableStateOf(
            if (paired) Workspace("Reclaim service", "pending", false, false) else demo.read()
        )
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
        private set

    var lastSync by mutableLongStateOf(0)
        private set

    var alerts by mutableStateOf(Alerts.on(app))
        private set

    var stale by mutableStateOf(false)
        private set

    private suspend fun <T> io(block: () -> T) = withContext(Dispatchers.IO) { block() }

    private fun runTask(sync: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (sync) stale = true
                error =
                    when (e) {
                        is java.net.UnknownHostException,
                        is java.net.SocketTimeoutException,
                        is java.net.ConnectException ->
                            "Couldn’t reach the service. Check your internet connection and try again."
                        is javax.net.ssl.SSLException ->
                            "Couldn’t verify the secure connection to this service."
                        is IOException,
                        is IllegalArgumentException ->
                            e.message ?: "The request could not be completed."
                        else ->
                            "Couldn’t open this workspace. Check the connection or reconnect your device."
                    }
            } finally {
                busy = false
            }
        }
    }

    fun clearError() {
        error = ""
    }

    fun refresh() {
        if (!paired) {
            data = demo.read()
            return
        }
        runTask(true) {
            data = io {
                val login = requireNotNull(access.load())
                val snapshot = ReclaimApi(login).workspace()
                Alerts.check(ctx, login, snapshot.bookings)
                snapshot
            }
            stale = false
            lastSync = System.currentTimeMillis()
        }
    }

    fun connect(url: String, token: String, done: () -> Unit) = runTask {
        val login = Access.validate(url, token)
        val snapshot = io {
            val s = ReclaimApi(login).workspace()
            access.save(login)
            s
        }
        data = snapshot
        paired = true
        stale = false
        lastSync = System.currentTimeMillis()
        done()
    }

    fun disconnect() {
        if (busy) return
        Alerts.stop(ctx)
        alerts = false
        access.clear()
        paired = false
        data = demo.read()
        stale = false
        error = ""
        lastSync = 0
    }

    private fun updated(b: Booking) {
        data =
            data.copy(
                bookings = (data.bookings.filter { it.id != b.id } + b).sortedBy { it.startsAt }
            )
    }

    fun sample(done: (String) -> Unit) = runTask {
        if (!paired) {
            val b = io { demo.sample() }
            updated(b)
            done(b.id)
        }
    }

    fun add(d: Draft, done: (String) -> Unit) = runTask {
        val live = paired
        val b = io {
            if (live) ReclaimApi(requireNotNull(access.load())).create(d) else demo.create(d)
        }
        updated(b)
        done(b.id)
    }

    fun action(b: Booking, action: String) = runTask {
        val live = paired
        val next = io {
            if (live) ReclaimApi(requireNotNull(access.load())).action(b, action)
            else demo.change(b.id, action)
        }
        updated(next)
    }

    fun changeAlerts(value: Boolean) {
        if (value && paired && !stale && lastSync > 0 && Alerts.permission(ctx))
            Alerts.start(ctx, data.bookings)
        else Alerts.stop(ctx)
        alerts = Alerts.on(ctx)
    }
}
