package com.jarvis.smarthomelauncher.ui.widgets

import java.util.Locale

internal fun formatHeroTrackTime(ms: Long, prefixMinus: Boolean = false): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val mins = totalSec / 60
    val secs = totalSec % 60
    val formatted = "%d:%02d".format(mins, secs)
    return if (prefixMinus) "-$formatted" else formatted
}

internal fun timeOfDayGreetingText(hourOfDay: Int): String = when (hourOfDay) {
    in 5..11 -> "Good Morning"
    in 12..17 -> "Good Afternoon"
    else -> "Good Evening"
}

// Curated high-resolution moody smart-home interior photos per room
internal fun defaultPhotoUrlForRoom(roomName: String): String {
    val lower = roomName.lowercase(Locale.getDefault())
    return when {
        lower.contains("bed") ->
            "https://images.unsplash.com/photo-1540518614846-7ede433c4576?auto=format&fit=crop&w=900&q=80"
        lower.contains("living") || lower.contains("lounge") ->
            "https://images.unsplash.com/photo-1586023492125-27b2c045efd7?auto=format&fit=crop&w=900&q=80"
        lower.contains("kitchen") || lower.contains("dining") ->
            "https://images.unsplash.com/photo-1556911220-bff31c812dba?auto=format&fit=crop&w=900&q=80"
        lower.contains("office") || lower.contains("study") ->
            "https://images.unsplash.com/photo-1593062096033-9a26b09da705?auto=format&fit=crop&w=900&q=80"
        lower.contains("garden") || lower.contains("outdoor") || lower.contains("patio") || lower.contains("driveway") ->
            "https://images.unsplash.com/photo-1558036117-15d82a90b9b1?auto=format&fit=crop&w=900&q=80"
        lower.contains("bath") ->
            "https://images.unsplash.com/photo-1584622650111-993a426fbf0a?auto=format&fit=crop&w=900&q=80"
        lower.contains("hall") || lower.contains("front") || lower.contains("entrance") ->
            "https://images.unsplash.com/photo-1600585154340-be6161a56a0c?auto=format&fit=crop&w=900&q=80"
        else ->
            "https://images.unsplash.com/photo-1600210492486-724fe5c67fb0?auto=format&fit=crop&w=900&q=80"
    }
}
