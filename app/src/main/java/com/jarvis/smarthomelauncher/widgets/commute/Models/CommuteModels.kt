package com.jarvis.smarthomelauncher.widgets.commute.Models

enum class TransportProvider {
    TFL, NATIONAL_RAIL, GOOGLE_MAPS
}

data class CommuteRoute(
    val id: String,
    val provider: TransportProvider,
    val name: String,
    val status: String,
    val estimatedArrival: String?,
    val durationMins: Int?
)
