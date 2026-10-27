package app.hamstr.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone is online and on an unmetered network, kept current by the system. */
object Network {
    data class State(val online: Boolean = true, val unmetered: Boolean = true)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        _state.value = read(cm.getNetworkCapabilities(cm.activeNetwork))
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: AndroidNetwork, caps: NetworkCapabilities) { _state.value = read(caps) }
                override fun onLost(network: AndroidNetwork) { _state.value = State(online = false, unmetered = false) }
            })
        }
    }

    private fun read(caps: NetworkCapabilities?) = State(
        online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
    )

    /** Downloads may run: online, and on Wi-Fi (or another unmetered network) when asked to. */
    fun allows(wifiOnly: Boolean) = _state.value.let { it.online && (!wifiOnly || it.unmetered) }
}
