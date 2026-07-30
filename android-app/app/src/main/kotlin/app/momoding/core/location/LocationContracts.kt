package app.momoding.core.location

import app.momoding.core.capabilities.LocationCapabilityAccess
import java.time.Instant

enum class LocationToolAction(val wireValue: String) {
    GET_CURRENT("get_current"),
    ;

    companion object {
        fun fromWireValue(value: String): LocationToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class LocationToolRequest(
    val action: LocationToolAction,
    val precision: LocationCapabilityAccess,
    val purpose: String,
)

data class LocationReading(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAt: Instant,
    val providerCategory: LocationProviderCategory,
)

enum class LocationProviderCategory(val wireValue: String) {
    SATELLITE("satellite"),
    NETWORK("network"),
    PASSIVE("passive"),
    SYSTEM("system"),
}

sealed interface LocationAcquisition {
    data class Available(val reading: LocationReading) : LocationAcquisition
    data object ServicesDisabled : LocationAcquisition
    data object ProviderUnavailable : LocationAcquisition
    data object NoFix : LocationAcquisition
}

class LocationToolArgumentsException :
    IllegalArgumentException("Location arguments are invalid")
