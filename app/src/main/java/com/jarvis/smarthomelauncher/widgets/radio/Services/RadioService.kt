package com.jarvis.smarthomelauncher.widgets.radio.Services

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jarvis.smarthomelauncher.core.LiveRadioDirectoryClient
import com.jarvis.smarthomelauncher.widgets.radio.Models.NowPlayingMetadata
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioCategory
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioProgrammeSlot
import com.jarvis.smarthomelauncher.widgets.radio.Models.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

interface RadioDataProvider {
    suspend fun getBuiltInAndTopStations(countryCode: String = "GB", limit: Int = 36): List<RadioStation>
    suspend fun searchStations(query: String, countryCode: String? = null, limit: Int = 40): List<RadioStation>
    suspend fun getTrendingStations(limit: Int = 24): List<RadioStation>
    suspend fun getPopularInCountry(countryCode: String, limit: Int = 24): List<RadioStation>
    suspend fun getNewStations(limit: Int = 20): List<RadioStation>
    suspend fun getStationsByCategory(category: RadioCategory, countryCode: String = "GB", limit: Int = 32): List<RadioStation>
    suspend fun fetchNowPlaying(station: RadioStation, allowStreamProbe: Boolean = true): NowPlayingMetadata
    suspend fun fetchStationSchedule(station: RadioStation): List<RadioProgrammeSlot>
}

object RadioImageMemoryCache {
    private val cache = object : LruCache<String, ImageBitmap>(72) {}

    private val imageClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun loadImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val clean = url.trim().replace("http://", "https://")
        if (!clean.startsWith("https://", ignoreCase = true)) return@withContext null
        synchronized(cache) {
            cache.get(clean)?.let { return@withContext it }
        }
        runCatching {
            val req = Request.Builder()
                .url(clean)
                .header("User-Agent", "ClaraSmartHome/2.0")
                .build()
            imageClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.isEmpty() || bytes.size > 2_500_000) return@use null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                if (bmp != null) {
                    synchronized(cache) {
                        cache.put(clean, bmp)
                    }
                }
                bmp
            }
        }.getOrNull()
    }
}

class LiveRadioService : RadioDataProvider {

    private val apiMirrors = listOf(
        "https://de1.api.radio-browser.info",
        "https://fr1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    private val accentPalette = longArrayOf(
        0xFF00E5FFL,
        0xFFFF4081L,
        0xFF00E676L,
        0xFFFFB300L,
        0xFFE040FBL,
        0xFF58A6FFL,
        0xFFFF8A00L,
        0xFF2DD4BFL
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4500, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val icyProbeClient = OkHttpClient.Builder()
        .connectTimeout(3200, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun builtInStations(): List<RadioStation> =
        LiveRadioDirectoryClient.builtInCatalog.map {
            RadioStation.fromRadioStationStream(it, isFavourite = false)
        }

    override suspend fun getBuiltInAndTopStations(
        countryCode: String,
        limit: Int
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        val local = builtInStations()
        val remotePopular = getPopularInCountry(countryCode, limit = limit)
        mergeDistinctStations(local + remotePopular)
    }

    override suspend fun searchStations(
        query: String,
        countryCode: String?,
        limit: Int
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext getBuiltInAndTopStations(countryCode ?: "GB", limit)
        }
        val localMatches = builtInStations().filter { st ->
            st.name.contains(clean, ignoreCase = true) ||
                    st.genre.contains(clean, ignoreCase = true) ||
                    st.badgeCode.contains(clean, ignoreCase = true) ||
                    st.tags.any { it.contains(clean, ignoreCase = true) }
        }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val countryParam = if (!countryCode.isNullOrBlank()) "&countrycode=${URLEncoder.encode(countryCode, "UTF-8")}" else ""
        val path = "/json/stations/search?name=$encoded$countryParam&limit=$limit&hidebroken=true&order=clickcount&reverse=true"
        val remote = queryRadioBrowserMirrors(path)
        mergeDistinctStations(localMatches + remote)
    }

    override suspend fun getTrendingStations(limit: Int): List<RadioStation> = withContext(Dispatchers.IO) {
        val path = "/json/stations/topclick?limit=$limit&hidebroken=true"
        val remote = queryRadioBrowserMirrors(path)
        if (remote.isNotEmpty()) remote else builtInStations().take(limit)
    }

    override suspend fun getPopularInCountry(countryCode: String, limit: Int): List<RadioStation> =
        withContext(Dispatchers.IO) {
            val cleanCode = countryCode.trim().uppercase(Locale.US).ifBlank { "GB" }
            val encoded = URLEncoder.encode(cleanCode, "UTF-8")
            val path = "/json/stations/search?countrycode=$encoded&limit=$limit&hidebroken=true&order=clickcount&reverse=true"
            val remote = queryRadioBrowserMirrors(path)
            val localForCountry = if (cleanCode == "GB") builtInStations() else emptyList()
            mergeDistinctStations(localForCountry + remote).take(limit.coerceAtLeast(16))
        }

    override suspend fun getNewStations(limit: Int): List<RadioStation> = withContext(Dispatchers.IO) {
        val path = "/json/stations/lastchange?limit=$limit&hidebroken=true"
        queryRadioBrowserMirrors(path)
    }

    override suspend fun getStationsByCategory(
        category: RadioCategory,
        countryCode: String,
        limit: Int
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        val tagLower = category.searchTag.lowercase(Locale.UK)
        val labelLower = category.label.lowercase(Locale.UK)
        val localMatches = builtInStations().filter { st ->
            st.genre.lowercase(Locale.UK).contains(tagLower) ||
                    st.genre.lowercase(Locale.UK).contains(labelLower) ||
                    st.name.lowercase(Locale.UK).contains(labelLower) ||
                    st.tags.any { it.contains(tagLower) || it.contains(labelLower) }
        }
        val encTag = URLEncoder.encode(tagLower, "UTF-8")
        val ukPath = "/json/stations/search?tag=$encTag&countrycode=${countryCode.ifBlank { "GB" }}&limit=${limit / 2}&hidebroken=true&order=clickcount&reverse=true"
        val globalPath = "/json/stations/search?tag=$encTag&limit=$limit&hidebroken=true&order=votes&reverse=true"
        val ukRemote = queryRadioBrowserMirrors(ukPath)
        val globalRemote = queryRadioBrowserMirrors(globalPath)
        mergeDistinctStations(localMatches + ukRemote + globalRemote).take(limit)
    }

    override suspend fun fetchNowPlaying(
        station: RadioStation,
        allowStreamProbe: Boolean
    ): NowPlayingMetadata = withContext(Dispatchers.IO) {
        val bbcServiceId = resolveBbcServiceId(station)
        if (bbcServiceId != null) {
            fetchBbcLiveNowPlaying(station, bbcServiceId)?.let { return@withContext it }
        }
        if (allowStreamProbe) {
            probeIcyStreamMetadata(station)?.let { return@withContext it }
        }
        NowPlayingMetadata(
            stationId = station.id,
            stationName = station.name,
            showOrPresenter = station.genre.ifBlank { "Live Broadcast" },
            isLiveBroadcast = true
        )
    }

    override suspend fun fetchStationSchedule(station: RadioStation): List<RadioProgrammeSlot> =
        withContext(Dispatchers.IO) {
            val bbcServiceId = resolveBbcServiceId(station) ?: return@withContext emptyList()
            fetchBbcScheduleSlots(bbcServiceId)
        }

    private fun resolveBbcServiceId(station: RadioStation): String? {
        val combined = "${station.id} ${station.name} ${station.streamUrl}".lowercase(Locale.UK)
        return when {
            combined.contains("bbc_radio_one") || combined.contains("bbc radio 1") && !combined.contains("1xtra") ->
                "bbc_radio_one"
            combined.contains("bbc_1xtra") || combined.contains("1xtra") ->
                "bbc_1xtra"
            combined.contains("bbc_radio_two") || combined.contains("bbc radio 2") ->
                "bbc_radio_two"
            combined.contains("bbc_radio_three") || combined.contains("bbc radio 3") ->
                "bbc_radio_three"
            combined.contains("bbc_radio_four") || combined.contains("bbc radio 4") ->
                "bbc_radio_fourfm"
            combined.contains("bbc_radio_five") || combined.contains("5 live") ->
                "bbc_radio_five_live"
            combined.contains("bbc_6music") || combined.contains("6 music") ->
                "bbc_6music"
            combined.contains("bbc_world_service") || combined.contains("bbc world service") ->
                "bbc_world_service"
            else -> null
        }
    }

    private fun fetchBbcLiveNowPlaying(
        station: RadioStation,
        serviceId: String
    ): NowPlayingMetadata? {
        var artist = ""
        var track = ""
        var albumArt = ""
        var progressPct: Int? = null
        var durationMs = 0L

        runCatching {
            val segReq = Request.Builder()
                .url("https://rms.api.bbc.co.uk/v2/services/$serviceId/segments/latest?limit=1")
                .header("User-Agent", "ClaraSmartHome/2.0")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(segReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val item = root.optJSONArray("data")?.optJSONObject(0)
                    if (item != null) {
                        val titles = item.optJSONObject("titles")
                        artist = titles?.optString("primary", "").orEmpty().trim()
                        track = titles?.optString("secondary", "").orEmpty().trim()
                        val rawImg = item.optString("image_url", "")
                        if (rawImg.isNotBlank()) {
                            albumArt = rawImg.replace("{recipe}", "256x256")
                        }
                        val offset = item.optJSONObject("offset")
                        val nowPlayingFlag = offset?.optBoolean("now_playing", true) ?: true
                        val durSec = item.optInt("duration", offset?.optInt("end", 0)?.minus(offset.optInt("start", 0)) ?: 0)
                        if (durSec > 0) {
                            durationMs = durSec * 1000L
                        }
                        if (!nowPlayingFlag) {
                            // Segment already ended; clear stale track if older
                            progressPct = null
                        }
                    }
                }
            }
        }

        var showTitle = ""
        runCatching {
            val pollReq = Request.Builder()
                .url("https://rms.api.bbc.co.uk/v2/services/$serviceId/poll")
                .header("User-Agent", "ClaraSmartHome/2.0")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(pollReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val broadcast = root.optJSONObject("data")
                        ?.optJSONArray("broadcasts")
                        ?.optJSONObject(0)
                    if (broadcast != null) {
                        val titles = broadcast.optJSONObject("titles")
                        val primary = titles?.optString("primary", "").orEmpty().trim()
                        val secondary = titles?.optString("secondary", "").orEmpty().trim()
                        showTitle = listOf(primary, secondary).filter { it.isNotBlank() }.joinToString(" • ")
                        val startIso = broadcast.optString("start", "")
                        val endIso = broadcast.optString("end", "")
                        val startMs = parseIso8601EpochMs(startIso)
                        val endMs = parseIso8601EpochMs(endIso)
                        val nowMs = System.currentTimeMillis()
                        if (startMs > 0L && endMs > startMs && nowMs in startMs..endMs) {
                            progressPct = (((nowMs - startMs).toDouble() / (endMs - startMs).toDouble()) * 100.0)
                                .toInt()
                                .coerceIn(1, 99)
                        }
                        if (albumArt.isBlank()) {
                            val showImg = broadcast.optString("image_url", "")
                            if (showImg.isNotBlank()) {
                                albumArt = showImg.replace("{recipe}", "256x256")
                            }
                        }
                    }
                }
            }
        }

        if (artist.isBlank() && track.isBlank() && showTitle.isBlank()) return null
        return NowPlayingMetadata(
            stationId = station.id,
            stationName = station.name,
            track = track.ifBlank { showTitle },
            artist = artist.ifBlank { if (track.isNotBlank()) showTitle else station.name },
            showOrPresenter = showTitle,
            albumArtUrl = albumArt,
            progressPercent = progressPct,
            durationMs = durationMs,
            isLiveBroadcast = true
        )
    }

    private fun fetchBbcScheduleSlots(serviceId: String): List<RadioProgrammeSlot> {
        return runCatching {
            val req = Request.Builder()
                .url("https://rms.api.bbc.co.uk/v2/services/$serviceId/poll")
                .header("User-Agent", "ClaraSmartHome/2.0")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val root = JSONObject(resp.body?.string().orEmpty())
                val broadcasts = root.optJSONObject("data")?.optJSONArray("broadcasts") ?: return@use emptyList()
                val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
                val nowMs = System.currentTimeMillis()
                buildList {
                    for (i in 0 until broadcasts.length()) {
                        val b = broadcasts.optJSONObject(i) ?: continue
                        val titles = b.optJSONObject("titles")
                        val primary = titles?.optString("primary", "").orEmpty().trim()
                        if (primary.isBlank()) continue
                        val secondary = titles?.optString("secondary", "").orEmpty().trim()
                        val startMs = parseIso8601EpochMs(b.optString("start", ""))
                        val endMs = parseIso8601EpochMs(b.optString("end", ""))
                        add(
                            RadioProgrammeSlot(
                                id = b.optString("id", "slot_$i"),
                                title = primary,
                                presenterOrSubtitle = secondary,
                                startTimeLabel = if (startMs > 0L) timeFmt.format(Date(startMs)) else "",
                                endTimeLabel = if (endMs > 0L) timeFmt.format(Date(endMs)) else "",
                                isLiveNow = startMs > 0L && endMs > startMs && nowMs in startMs..endMs
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun probeIcyStreamMetadata(station: RadioStation): NowPlayingMetadata? {
        val url = station.streamUrl.trim()
        if (!url.startsWith("http", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)) {
            return null
        }
        val rawStreamTitle = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Icy-MetaData", "1")
                .header("User-Agent", "ClaraSmartHome/2.0")
                .build()
            icyProbeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val metaInt = resp.header("icy-metaint")?.toIntOrNull() ?: return@use null
                if (metaInt !in 256..65536) return@use null
                val stream = resp.body?.byteStream() ?: return@use null
                skipExactBytes(stream, metaInt)
                val lenByte = stream.read()
                if (lenByte <= 0) return@use null
                val metaByteLen = lenByte * 16
                if (metaByteLen > 4096) return@use null
                val metaBuf = ByteArray(metaByteLen)
                var totalRead = 0
                while (totalRead < metaByteLen) {
                    val r = stream.read(metaBuf, totalRead, metaByteLen - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                val metaStr = String(metaBuf, 0, totalRead, Charsets.UTF_8)
                    .trim { it <= ' ' || it == '\u0000' }
                Regex("StreamTitle='([^']*)'")
                    .find(metaStr)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
            }
        }.getOrNull().orEmpty()

        if (rawStreamTitle.isBlank() ||
            rawStreamTitle.equals(station.name, ignoreCase = true) ||
            rawStreamTitle.equals("Unknown", ignoreCase = true)
        ) {
            return null
        }

        val parts = rawStreamTitle.split(" - ", " – ", limit = 2)
        val artist = if (parts.size == 2) parts[0].trim() else ""
        val track = if (parts.size == 2) parts[1].trim() else rawStreamTitle.trim()

        val (artUrl, trackDurMs) = lookupItunesTrackArtworkAndDuration(artist, track)
        return NowPlayingMetadata(
            stationId = station.id,
            stationName = station.name,
            track = track,
            artist = artist,
            showOrPresenter = station.genre,
            albumArtUrl = artUrl,
            durationMs = trackDurMs,
            isLiveBroadcast = true
        )
    }

    private fun lookupItunesTrackArtworkAndDuration(artist: String, track: String): Pair<String, Long> {
        val cleanQuery = "$artist $track".trim()
        if (cleanQuery.length < 4) return "" to 0L
        return runCatching {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&country=GB&media=music&entity=song&limit=1")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use "" to 0L
                val first = JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("results")
                    ?.optJSONObject(0) ?: return@use "" to 0L
                val art = first.optString("artworkUrl100", "").replace("100x100bb", "300x300bb")
                val durMs = first.optLong("trackTimeMillis", 0L)
                art to durMs
            }
        }.getOrDefault("" to 0L)
    }

    private fun skipExactBytes(input: InputStream, targetBytes: Int) {
        val scratch = ByteArray(4096)
        var remaining = targetBytes
        while (remaining > 0) {
            val toRead = minOf(scratch.size, remaining)
            val n = input.read(scratch, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun parseIso8601EpochMs(iso: String): Long {
        if (iso.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pat in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                sdf.parse(iso)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun queryRadioBrowserMirrors(pathAndQuery: String): List<RadioStation> {
        for (mirror in apiMirrors) {
            try {
                val req = Request.Builder()
                    .url("$mirror$pathAndQuery")
                    .header("User-Agent", "ClaraSmartHome/2.0")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        if (bodyStr.isNotBlank()) {
                            return parseRadioBrowserArray(JSONArray(bodyStr))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun parseRadioBrowserArray(arr: JSONArray): List<RadioStation> {
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val streamUrl = o.optString("url_resolved", o.optString("url", "")).trim()
                if (name.isBlank() || !streamUrl.startsWith("http", ignoreCase = true)) continue
                val uuid = o.optString("stationuuid", UUID.randomUUID().toString())
                val rawTags = o.optString("tags", "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val country = o.optString("country", "").trim()
                val countryCode = o.optString("countrycode", "").trim().uppercase(Locale.UK)
                val bitrate = o.optInt("bitrate", 128)
                val codec = o.optString("codec", "MP3").trim()
                val votes = o.optInt("votes", 0)
                val favicon = o.optString("favicon", "").trim()
                val homepage = o.optString("homepage", "").trim()
                val primaryGenre = rawTags.take(2).joinToString(", ") { tag ->
                    tag.replaceFirstChar { c -> c.uppercase(Locale.UK) }
                }.ifBlank { "Live Radio" }
                val badge = name.replace(Regex("[^A-Za-z0-9]"), "")
                    .take(3)
                    .uppercase(Locale.UK)
                    .ifBlank { "FM" }
                add(
                    RadioStation(
                        id = "rb_$uuid",
                        name = name,
                        streamUrl = streamUrl,
                        genre = primaryGenre,
                        tags = rawTags.take(6),
                        country = country.ifBlank { countryCode.ifBlank { "Global" } },
                        countryCode = countryCode,
                        faviconUrl = favicon,
                        homepageUrl = homepage,
                        bitrateKbps = bitrate,
                        codec = codec,
                        votes = votes,
                        accentColorHex = accentPalette[(name.hashCode() and 0x7FFFFFFF) % accentPalette.size],
                        badgeCode = badge,
                        isFavourite = false,
                        isCustom = false
                    )
                )
            }
        }
    }

    private fun mergeDistinctStations(stations: List<RadioStation>): List<RadioStation> {
        val seenUrls = HashSet<String>()
        val seenNames = HashSet<String>()
        return buildList {
            for (st in stations) {
                val urlKey = st.streamUrl.trim().lowercase(Locale.UK)
                val nameKey = st.name.trim().lowercase(Locale.UK)
                if (seenUrls.add(urlKey) && seenNames.add(nameKey)) {
                    add(st)
                }
            }
        }
    }
}