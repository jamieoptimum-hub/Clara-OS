package com.jarvis.smarthomelauncher.widgets.radio.Models

import com.jarvis.smarthomelauncher.core.RadioStationStream
import java.util.Locale
import java.util.UUID

data class RadioWidgetMetadata(
    val id: String = "radio-hub",
    val name: String = "Radio",
    val version: String = "1.0",
    val description: String = "Large-screen live FM & internet radio dashboard and hub",
    val supportsPopup: Boolean = true,
    val supportsSettings: Boolean = true,
    val supportsNotifications: Boolean = true
)

enum class RadioHubTab(val label: String) {
    OVERVIEW("Overview"),
    LIVE("Live"),
    STATIONS("Stations"),
    CATEGORIES("Categories"),
    DISCOVER("Discover"),
    HISTORY("History")
}

enum class StationSortOption(val label: String) {
    FAVOURITES("Favourites"),
    RECENTLY_PLAYED("Recently Played"),
    MOST_POPULAR("Most Popular"),
    ALPHABETICAL("Alphabetical")
}

enum class HistoryTimeFilter(val label: String, val daysWindow: Int) {
    TODAY("Today", 1),
    THIS_WEEK("This Week", 7),
    THIS_MONTH("This Month", 30)
}

enum class StationDetailSection(val label: String) {
    NOW_PLAYING("Now Playing"),
    RECENTLY_PLAYED("Recently Played"),
    SCHEDULE("Schedule"),
    SIMILAR("Similar Stations")
}

enum class RadioCategory(
    val label: String,
    val searchTag: String,
    val accentHex: Long,
    val subtitle: String
) {
    POP("Pop", "pop", 0xFF00E5FFL, "Chart hits & mainstream radio"),
    ROCK("Rock", "rock", 0xFFFF6B6BL, "Classic, alternative & guitar anthems"),
    DANCE("Dance", "dance", 0xFFE040FBL, "Club, house & floorfillers"),
    CLASSICAL("Classical", "classical", 0xFFC084FCL, "Orchestral, piano & symphonies"),
    NEWS("News", "news", 0xFF58A6FFL, "24/7 headlines & world affairs"),
    TALK("Talk", "talk", 0xFFFFB300L, "Debate, phone-ins & culture"),
    SPORTS("Sports", "sport", 0xFF00E676L, "Live commentary & match talk"),
    JAZZ("Jazz", "jazz", 0xFFFF9E44L, "Smooth, bebop & contemporary jazz"),
    COUNTRY("Country", "country", 0xFFFBBF24L, "Nashville, acoustic & americana"),
    EIGHTIES("80s", "80s", 0xFFFF4081L, "Synth-pop & retro 80s classics"),
    NINETIES("90s", "90s", 0xFF2DD4BFL, "Britpop, R&B & 90s throwbacks"),
    TWO_THOUSANDS("2000s", "2000s", 0xFF38BDF8L, "Millennium pop, indie & club"),
    ELECTRONIC("Electronic", "electronic", 0xFFA855F7L, "Ambient, techno, electronica & bass"),
    INDIE("Indie", "indie", 0xFFA3F0C1L, "Alternative bands & new music"),
    CHILL("Chill", "chillout", 0xFF2DD4BFL, "Downtempo, lo-fi & relaxing mixes")
}

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val genre: String = "Live Radio",
    val tags: List<String> = emptyList(),
    val country: String = "UK",
    val countryCode: String = "GB",
    val faviconUrl: String = "",
    val homepageUrl: String = "",
    val bitrateKbps: Int = 128,
    val codec: String = "AAC",
    val votes: Int = 0,
    val accentColorHex: Long = 0xFF00E5FFL,
    val badgeCode: String = "FM",
    val isFavourite: Boolean = false,
    val isCustom: Boolean = false
) {
    fun toRadioStationStream(): RadioStationStream = RadioStationStream(
        id = id,
        name = name,
        genreOrTagline = genre.ifBlank { "Live Radio" },
        streamUrl = streamUrl,
        accentColorHex = accentColorHex,
        badgeCode = badgeCode.ifBlank {
            name.replace(Regex("[^A-Za-z0-9]"), "").take(3).uppercase(Locale.UK).ifBlank { "FM" }
        },
        isCustom = isCustom
    )

    companion object {
        fun fromRadioStationStream(
            stream: RadioStationStream,
            isFavourite: Boolean = false
        ): RadioStation {
            val inferredTags = stream.genreOrTagline
                .split(",", "•", "&", "/")
                .map { it.trim().lowercase(Locale.UK) }
                .filter { it.length >= 2 && it != "uk" }
            val inferredCountry = if (
                stream.genreOrTagline.contains("UK", ignoreCase = true) ||
                stream.name.contains("BBC", ignoreCase = true) ||
                stream.name.contains("UK", ignoreCase = true)
            ) "United Kingdom" else "Global"
            val inferredCode = if (inferredCountry == "United Kingdom") "GB" else ""
            return RadioStation(
                id = stream.id,
                name = stream.name,
                streamUrl = stream.streamUrl,
                genre = stream.genreOrTagline.ifBlank { "Live Radio" },
                tags = inferredTags,
                country = inferredCountry,
                countryCode = inferredCode,
                faviconUrl = inferWellKnownFavicon(stream.name),
                bitrateKbps = if (stream.streamUrl.contains("320")) 320 else 128,
                votes = 5000,
                accentColorHex = stream.accentColorHex,
                badgeCode = stream.badgeCode,
                isFavourite = isFavourite,
                isCustom = stream.isCustom
            )
        }

        private fun inferWellKnownFavicon(stationName: String): String {
            val lower = stationName.lowercase(Locale.UK)
            return when {
                lower.contains("radio paradise") -> "https://radioparadise.com/favicon-32x32.png"
                lower.contains("somafm") -> "https://somafm.com/img3/groovesalad-400.jpg"
                else -> ""
            }
        }
    }
}

data class NowPlayingMetadata(
    val stationId: String,
    val stationName: String,
    val track: String = "",
    val artist: String = "",
    val showOrPresenter: String = "",
    val albumArtUrl: String = "",
    val progressPercent: Int? = null,
    val startedAtEpochMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLiveBroadcast: Boolean = true,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val hasTrackInfo: Boolean
        get() = track.isNotBlank() || artist.isNotBlank()

    val primaryDisplayLine: String
        get() = when {
            track.isNotBlank() && artist.isNotBlank() -> "$track —$artist"
            track.isNotBlank() -> track
            showOrPresenter.isNotBlank() -> showOrPresenter
            else -> "Live Broadcast"
        }
}

data class RadioHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val stationId: String,
    val stationName: String,
    val artist: String,
    val track: String,
    val timestampEpochMs: Long,
    val durationMs: Long,
    val accentColorHex: Long = 0xFF00E5FFL,
    val badgeCode: String = "FM",
    val faviconUrl: String = ""
)

data class RadioProgrammeSlot(
    val id: String,
    val title: String,
    val presenterOrSubtitle: String,
    val startTimeLabel: String,
    val endTimeLabel: String,
    val isLiveNow: Boolean = false
)

enum class RadioScheduleTriggerType(val label: String, val description: String) {
    DAILY_ALARM("Scheduled Time / Alarm", "Start station at an exact time on selected days"),
    ARRIVING_HOME("Arriving Home Routine", "Play automatically when Security Mode changes to Home"),
    BEDTIME_ROUTINE("Bedtime / Night Routine", "Play automatically when Security Mode changes to Night"),
    AWAY_ROUTINE("Leaving Home Routine", "Play automatically when Security Mode changes to Away")
}

data class RadioScheduledRoutine(
    val id: String = UUID.randomUUID().toString(),
    val stationId: String,
    val stationName: String,
    val streamUrl: String,
    val label: String,
    val triggerType: RadioScheduleTriggerType = RadioScheduleTriggerType.DAILY_ALARM,
    val hour: Int = 7,
    val minute: Int = 0,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    val isEnabled: Boolean = true,
    val lastTriggeredEpochMs: Long = 0L
) {
    fun formattedSummary(): String = when (triggerType) {
        RadioScheduleTriggerType.DAILY_ALARM -> {
            val daysLabel = when {
                daysOfWeek.size >= 7 -> "Every Day"
                daysOfWeek == setOf(1, 2, 3, 4, 5) -> "Weekdays"
                daysOfWeek == setOf(6, 7) -> "Weekends"
                else -> {
                    val map = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
                    daysOfWeek.sorted().mapNotNull { map[it] }.joinToString(", ")
                }
            }
            String.format(Locale.UK, "%02d:%02d • %s", hour, minute, daysLabel)
        }
        else -> triggerType.label
    }
}

data class RadioActivityStats(
    val todayListeningMs: Long = 0L,
    val favouriteCount: Int = 0,
    val stationsPlayedToday: Int = 0,
    val stationsPlayedThisWeek: Int = 0,
    val mostPlayedStationThisWeek: String = ""
) {
    val todayListeningFormatted: String
        get() {
            val totalMinutes = (todayListeningMs / 60_000L).toInt()
            return when {
                totalMinutes <= 0 -> "0m"
                totalMinutes < 60 -> "${totalMinutes}m"
                else -> {
                    val hrs = totalMinutes / 60f
                    String.format(Locale.UK, "%.1fh", hrs)
                }
            }
        }
}

data class RadioUserSettings(
    val maxWidgetFavourites: Int = 6,
    val preferredCountryCode: String = "GB",
    val enableLiveTrackMetadata: Boolean = true,
    val showRecommendationsOnWidget: Boolean = true,
    val defaultSortOption: StationSortOption = StationSortOption.FAVOURITES
)

data class DiscoverFeed(
    val recommendedForYou: List<RadioStation> = emptyList(),
    val trendingStations: List<RadioStation> = emptyList(),
    val popularInArea: List<RadioStation> = emptyList(),
    val newStations: List<RadioStation> = emptyList(),
    val isLoading: Boolean = false
)

data class RadioDashboardSnapshot(
    val currentStation: RadioStation?,
    val isPlaying: Boolean,
    val activeOutputName: String,
    val nowPlaying: NowPlayingMetadata?,
    val sessionElapsedMs: Long,
    val favourites: List<RadioStation>,
    val allStations: List<RadioStation>,
    val liveStationsNowPlaying: Map<String, NowPlayingMetadata>,
    val recentlyPlayed: List<RadioHistoryItem>,
    val recommendedStations: List<RadioStation>,
    val activity: RadioActivityStats,
    val dashboardSummary: String,
    val settings: RadioUserSettings,
    val scheduledRoutines: List<RadioScheduledRoutine>
)

sealed interface RadioUiState {
    data object Loading : RadioUiState
    data class Ready(val snapshot: RadioDashboardSnapshot) : RadioUiState
    data class Empty(val message: String) : RadioUiState
    data class Error(val message: String) : RadioUiState
}