package app.momoding.core.provider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Narrow, read-only Android network preflight. Unknown states always defer to OkHttp. */
internal class AndroidProviderNetworkAvailability(
    context: Context,
) : ProviderNetworkAvailability {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun currentState(): ProviderNetworkState = runCatching {
        val connectivity = connectivityManager ?: return@runCatching ProviderNetworkState.UNKNOWN
        val network = connectivity.activeNetwork ?: return@runCatching ProviderNetworkState.OFFLINE
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return@runCatching ProviderNetworkState.UNKNOWN
        when {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                ProviderNetworkState.OFFLINE
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                ProviderNetworkState.UNKNOWN
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ->
                ProviderNetworkState.UNKNOWN
            else -> ProviderNetworkState.AVAILABLE
        }
    }.getOrDefault(ProviderNetworkState.UNKNOWN)
}
