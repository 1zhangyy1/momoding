package app.momoding.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import app.momoding.core.capabilities.LocationCapabilityAccess
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidLocationGateway private constructor(
    private val locationManager: LocationManager,
    private val callbackExecutor: Executor,
) : LocationGateway {
    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(
        precision: LocationCapabilityAccess,
    ): LocationAcquisition {
        if (!locationManager.isLocationEnabled) return LocationAcquisition.ServicesDisabled
        val providers = selectProviders(precision)
        if (providers.isEmpty()) return LocationAcquisition.ProviderUnavailable
        freshestRecentLocation(providers)?.let {
            return LocationAcquisition.Available(it.toReading())
        }
        val signals = providers.map { CancellationSignal() }
        return suspendCancellableCoroutine { continuation ->
            val lock = Any()
            var completed = false
            var remaining = providers.size
            var sawNoFix = false
            var lastError: Throwable? = null

            fun cancelSignals() {
                signals.forEach(CancellationSignal::cancel)
            }

            fun completeOne(noFix: Boolean, error: Throwable? = null) {
                var terminal: LocationAcquisition? = null
                var terminalError: Throwable? = null
                synchronized(lock) {
                    if (completed) return
                    if (noFix) sawNoFix = true
                    if (error != null) lastError = error
                    remaining -= 1
                    if (remaining == 0) {
                        completed = true
                        if (sawNoFix) {
                            terminal = LocationAcquisition.NoFix
                        } else {
                            terminalError = lastError
                        }
                    }
                }
                terminal?.let { continuation.resume(it) }
                terminalError?.let { continuation.resumeWith(Result.failure(it)) }
            }

            continuation.invokeOnCancellation {
                synchronized(lock) { completed = true }
                cancelSignals()
            }
            providers.zip(signals).forEach { (provider, signal) ->
                try {
                    locationManager.getCurrentLocation(
                        provider,
                        signal,
                        callbackExecutor,
                    ) { location ->
                        if (location == null) {
                            completeOne(noFix = true)
                            return@getCurrentLocation
                        }
                        val shouldResume = synchronized(lock) {
                            if (completed) {
                                false
                            } else {
                                completed = true
                                true
                            }
                        }
                        if (shouldResume) {
                            cancelSignals()
                            continuation.resume(
                                LocationAcquisition.Available(location.toReading()),
                            )
                        }
                    }
                } catch (error: Throwable) {
                    completeOne(noFix = false, error = error)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun freshestRecentLocation(providers: List<String>): Location? {
        val nowMillis = System.currentTimeMillis()
        return providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { location ->
                (nowMillis - location.time) in 0..MAX_CACHED_LOCATION_AGE_MILLIS
            }
            .maxByOrNull(Location::getTime)
    }

    @SuppressLint("InlinedApi")
    private fun selectProviders(access: LocationCapabilityAccess): List<String> {
        val enabled = locationManager.getProviders(true).toSet()
        val preference = when (access) {
            LocationCapabilityAccess.APPROXIMATE -> listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            LocationCapabilityAccess.PRECISE -> listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        }
        return preference.filter(enabled::contains)
    }

    private fun Location.toReading() = LocationReading(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.coerceAtLeast(0f),
        capturedAt = Instant.ofEpochMilli(time),
        providerCategory = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationProviderCategory.SATELLITE
            LocationManager.NETWORK_PROVIDER -> LocationProviderCategory.NETWORK
            LocationManager.PASSIVE_PROVIDER -> LocationProviderCategory.PASSIVE
            else -> LocationProviderCategory.SYSTEM
        },
    )

    companion object {
        private const val MAX_CACHED_LOCATION_AGE_MILLIS = 30_000L

        fun create(context: Context): AndroidLocationGateway {
            val application = context.applicationContext
            return AndroidLocationGateway(
                locationManager = application.getSystemService(LocationManager::class.java),
                callbackExecutor = application.mainExecutor,
            )
        }
    }
}
