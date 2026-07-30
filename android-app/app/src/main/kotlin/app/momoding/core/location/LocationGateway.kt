package app.momoding.core.location

import app.momoding.core.capabilities.LocationCapabilityAccess

fun interface LocationForegroundGate {
    fun isForeground(): Boolean
}

fun interface LocationGateway {
    suspend fun getCurrentLocation(
        precision: LocationCapabilityAccess,
    ): LocationAcquisition
}
