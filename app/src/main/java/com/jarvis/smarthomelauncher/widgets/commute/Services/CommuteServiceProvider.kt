package com.jarvis.smarthomelauncher.widgets.commute.Services

import com.jarvis.smarthomelauncher.widgets.commute.Models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * CWAS Rule 15: Provider abstraction decoupling CommuteRepository & UI from upstream transport APIs.
 */
interface CommuteDataProvider {
    val providerName: String

    suspend fun searchStopsAndLocations(
        query: String,
        mode: SupportedTransportMode
    ): List<TransportStopEndpoint>

    suspend fun fetchRouteDepartures(
        route: SavedCommuteRoute,
        previousPlatformsByServiceId: Map<String, String> = emptyMap()
    ): List<CommuteDepartureService>

    suspend fun fetchStationFacilityDetails(
        station: TransportStopEndpoint,
        previousPlatformsByServiceId: Map<String, String> = emptyMap()
    ): StationFacilityDetails

    suspend fun fetchActiveDisruptions(
        savedRoutes: List<SavedCommuteRoute>,
        routeDepartures: List<CommuteDepartureService>
    ): List<CommuteDisruptionItem>

    suspend fun planMultiOptionJourneys(
        origin: TransportStopEndpoint,
        destination: TransportStopEndpoint,
        preferredMode: SupportedTransportMode
    ): List<PlannedJourneyOption>
}

/**
 * Production Live Multi-Modal UK & Urban Transport Provider:
 * - National Rail Darwin JSON Gateway (Huxley2 / configurable Darwin proxy) for real-time UK rail
 *   departures, arrivals, platforms, calling points, formations, and NRCC engineering alerts.
 * - TfL Unified API v1 for Underground, Overground, Elizabeth line, DLR, Bus, Tram, River-Bus (Ferry),
 *   station facilities, network line statuses, strategic road corridors, and national multi-modal Journey Planner.
 * - OSRM Live Routing Engine + Open-Meteo Geocoding for Driving, Walking, and Cycling routes.
 */
class LiveUkMultiModalCommuteProvider(
    private val tflApiKeyProvider: () -> String?,
    private val darwinGatewayUrlProvider: () -> String = { DEFAULT_DARWIN_GATEWAY_URL }
) : CommuteDataProvider {

    override val providerName: String
        get() = if (!tflApiKeyProvider().isNullOrBlank()) {
            "National Rail Darwin + TfL Unified API (Authenticated) + OSRM Routing"
        } else {
            "National Rail Darwin + TfL Live Transit + OSRM Multi-Modal Routing"
        }

    companion object {
        const val DEFAULT_DARWIN_GATEWAY_URL = "https://huxley2.azurewebsites.net"
        private const val TFL_BASE_URL = "https://api.tfl.gov.uk"
        private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    }

    // ========================================================================
    // 1. Live Station, Stop & Address Search Across All 8 Transport Modes
    // ========================================================================

    override suspend fun searchStopsAndLocations(
        query: String,
        mode: SupportedTransportMode
    ): List<TransportStopEndpoint> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val results = LinkedHashMap<String, TransportStopEndpoint>()

        if (mode == SupportedTransportMode.RAIL) {
            // Query National Rail CRS directory first for exact 3-letter UK station codes
            val darwinBase = darwinGatewayUrlProvider().trimEnd('/')
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val crsArray = getJsonArray("$darwinBase/crs/$encoded")
            if (crsArray != null) {
                for (i in 0 until crsArray.length()) {
                    val item = crsArray.optJSONObject(i) ?: continue
                    val crs = item.optString("crsCode").trim().uppercase(Locale.UK)
                    val stationName = item.optString("stationName").trim()
                    if (crs.length == 3 && stationName.isNotBlank()) {
                        results["crs_$crs"] = TransportStopEndpoint(
                            id = "crs_$crs",
                            name = stationName,
                            crsOrStopCode = crs,
                            primaryMode = SupportedTransportMode.RAIL,
                            zoneOrRegion = "National Rail ($crs)"
                        )
                    }
                }
            }
        }

        if (mode.isPublicTransit) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val tflUrl = appendTflKey(
                "$TFL_BASE_URL/StopPoint/Search/$encoded?modes=${mode.tflModeToken}&maxResults=15"
            )
            val root = getJsonObject(tflUrl)
            val matches = root?.optJSONArray("matches")
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val m = matches.optJSONObject(i) ?: continue
                    val stopId = m.optString("id").trim()
                    val name = m.optString("name").trim()
                    if (stopId.isBlank() || name.isBlank()) continue
                    val lat = m.optDouble("lat", Double.NaN).takeIf { !it.isNaN() }
                    val lon = m.optDouble("lon", Double.NaN).takeIf { !it.isNaN() }
                    val zone = m.optString("zone").trim()
                    val icsId = m.optString("icsId").trim()

                    // Enrich existing CRS entry with coordinates/StopPoint ID if names match
                    val existingCrsEntry = results.values.firstOrNull { existing ->
                        normalizeStopName(existing.name) == normalizeStopName(name)
                    }
                    if (existingCrsEntry != null) {
                        results[existingCrsEntry.id] = existingCrsEntry.copy(
                            latitude = lat ?: existingCrsEntry.latitude,
                            longitude = lon ?: existingCrsEntry.longitude,
                            zoneOrRegion = if (zone.isNotBlank()) {
                                "${existingCrsEntry.zoneOrRegion} • Zone $zone"
                            } else {
                                existingCrsEntry.zoneOrRegion
                            }
                        )
                    } else {
                        val inferredCrs = stopId.removePrefix("910G").takeIf {
                            stopId.startsWith("910G") && it.length == 3
                        }.orEmpty()
                        results[stopId] = TransportStopEndpoint(
                            id = stopId,
                            name = name,
                            crsOrStopCode = inferredCrs.ifBlank { icsId },
                            primaryMode = mode,
                            latitude = lat,
                            longitude = lon,
                            zoneOrRegion = if (zone.isNotBlank()) "Zone $zone" else mode.displayName
                        )
                    }
                }
            }
        }

        // Geocoding search for Driving, Walking, Cycling, or nationwide towns/postcodes
        if (!mode.isPublicTransit || results.size < 5) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val geoRoot = getJsonObject(
                "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=10&language=en&format=json"
            )
            val geoArr = geoRoot?.optJSONArray("results")
            if (geoArr != null) {
                for (i in 0 until geoArr.length()) {
                    val g = geoArr.optJSONObject(i) ?: continue
                    val placeName = g.optString("name").trim()
                    val admin1 = g.optString("admin1").trim()
                    val country = g.optString("country").trim()
                    val lat = g.optDouble("latitude", Double.NaN)
                    val lon = g.optDouble("longitude", Double.NaN)
                    if (placeName.isBlank() || lat.isNaN() || lon.isNaN()) continue

                    val id = "geo_${g.optLong("id", i.toLong())}"
                    val regionText = listOf(admin1, country).filter { it.isNotBlank() }.joinToString(", ")
                    results.putIfAbsent(
                        id,
                        TransportStopEndpoint(
                            id = id,
                            name = placeName,
                            crsOrStopCode = "",
                            primaryMode = mode,
                            latitude = lat,
                            longitude = lon,
                            zoneOrRegion = regionText
                        )
                    )
                }
            }
        }

        results.values.take(18).toList()
    }

    // ========================================================================
    // 2. Live Route Departures (Rail, Underground, Bus, Tram, Ferry & Road)
    // ========================================================================

    override suspend fun fetchRouteDepartures(
        route: SavedCommuteRoute,
        previousPlatformsByServiceId: Map<String, String>
    ): List<CommuteDepartureService> = withContext(Dispatchers.IO) {
        when {
            !route.mode.isPublicTransit -> fetchRoadOrActiveTravelDepartures(route)
            route.mode == SupportedTransportMode.RAIL && route.origin.crsOrStopCode.length == 3 -> {
                val darwinServices = fetchDarwinRailDepartures(
                    route = route,
                    previousPlatformsByServiceId = previousPlatformsByServiceId
                )
                if (darwinServices.isNotEmpty()) {
                    darwinServices
                } else {
                    fetchTflJourneyBackedDepartures(route, previousPlatformsByServiceId)
                }
            }
            else -> {
                val stopDepartures = fetchTflStopArrivalsAsDepartures(route, previousPlatformsByServiceId)
                if (stopDepartures.isNotEmpty()) {
                    stopDepartures
                } else {
                    fetchTflJourneyBackedDepartures(route, previousPlatformsByServiceId)
                }
            }
        }
    }

    private fun fetchDarwinRailDepartures(
        route: SavedCommuteRoute,
        previousPlatformsByServiceId: Map<String, String>
    ): List<CommuteDepartureService> {
        val darwinBase = darwinGatewayUrlProvider().trimEnd('/')
        val originCrs = route.origin.crsOrStopCode.trim().uppercase(Locale.UK)
        val destCrs = route.destination.crsOrStopCode.trim().uppercase(Locale.UK)

        val directUrl = if (destCrs.length == 3 && destCrs != originCrs) {
            "$darwinBase/departures/$originCrs/to/$destCrs/10?expand=true"
        } else {
            "$darwinBase/departures/$originCrs/10?expand=true"
        }

        var boardJson = getJsonObject(directUrl)
        var trainServices = boardJson?.optJSONArray("trainServices")

        // If an interchange is required and no direct train calls at destCrs, fall back to station board
        var requiresInterchangeNote = ""
        if ((trainServices == null || trainServices.length() == 0) && destCrs.length == 3) {
            boardJson = getJsonObject("$darwinBase/departures/$originCrs/10?expand=true")
            trainServices = boardJson?.optJSONArray("trainServices")
            if (trainServices != null && trainServices.length() > 0) {
                requiresInterchangeNote = "Connect en route for ${route.destination.name}"
            }
        }

        if (trainServices == null || trainServices.length() == 0) return emptyList()

        val nrccBanner = extractNrccPlainText(boardJson?.optJSONArray("nrccMessages"))
        val list = mutableListOf<CommuteDepartureService>()

        for (i in 0 until trainServices.length()) {
            val s = trainServices.optJSONObject(i) ?: continue
            val rawId = s.optString("serviceIdUrlSafe")
                .ifBlank { s.optString("serviceID") }
                .ifBlank { "${originCrs}_${s.optString("std")}_$i" }
            val serviceKey = "${route.routeId}_$rawId"

            val std = s.optString("std").trim()
            val etdRaw = s.optString("etd").trim()
            if (std.isBlank()) continue

            val operator = s.optString("operator").ifBlank { "National Rail" }
            val destArr = s.optJSONArray("destination")
            val finalDestName = destArr?.optJSONObject(0)?.optString("locationName")
                .orEmpty()
                .ifBlank { route.destination.name }

            val platform = s.optString("platform").trim()
            val prevPlat = previousPlatformsByServiceId[serviceKey].orEmpty()
            val platChanged = prevPlat.isNotBlank() && platform.isNotBlank() && prevPlat != platform

            val isCancelled = s.optBoolean("isCancelled", false) ||
                    etdRaw.equals("Cancelled", ignoreCase = true)

            val expectedTimeStr = when {
                isCancelled -> "Cancelled"
                etdRaw.equals("On time", ignoreCase = true) || etdRaw.isBlank() -> std
                etdRaw.equals("Delayed", ignoreCase = true) -> std
                else -> etdRaw
            }

            val schedMs = parseTodayOrTomorrowTimeEpochMs(std)
            val expMs = if (expectedTimeStr.contains(":")) {
                parseTodayOrTomorrowTimeEpochMs(expectedTimeStr, referenceMs = schedMs)
            } else {
                schedMs
            }

            val delayMins = when {
                isCancelled -> 0
                etdRaw.equals("Delayed", ignoreCase = true) -> 10
                expMs > schedMs -> ((expMs - schedMs) / 60_000L).toInt().coerceAtLeast(0)
                else -> 0
            }

            val cancelOrDelayReason = s.optString("cancelReason")
                .ifBlank { s.optString("delayReason") }
                .ifBlank { nrccBanner }

            val statusKind = when {
                isCancelled -> ServiceStatusKind.CANCELLED
                platChanged -> ServiceStatusKind.PLATFORM_CHANGED
                cancelOrDelayReason.contains("engineering", ignoreCase = true) ->
                    ServiceStatusKind.ENGINEERING_WORKS
                delayMins > 0 || etdRaw.equals("Delayed", ignoreCase = true) ->
                    ServiceStatusKind.DELAYED
                else -> ServiceStatusKind.ON_TIME
            }

            val callingPoints = parseDarwinCallingPoints(s.optJSONArray("subsequentCallingPoints"))
            val matchedDestStop = callingPoints.firstOrNull { cp ->
                (destCrs.length == 3 && cp.crsOrCode.equals(destCrs, ignoreCase = true)) ||
                        normalizeStopName(cp.stationName) == normalizeStopName(route.destination.name)
            } ?: callingPoints.lastOrNull()

            val arrivalStr = matchedDestStop?.expectedTime?.takeIf { it.contains(":") }
                ?: matchedDestStop?.scheduledTime.orEmpty()
            val durationMins = if (arrivalStr.contains(":")) {
                val arrMs = parseTodayOrTomorrowTimeEpochMs(arrivalStr, referenceMs = expMs)
                ((arrMs - expMs) / 60_000L).toInt().coerceAtLeast(1)
            } else {
                0
            }

            val coaches = s.optInt("length", 0)
            val occupancy = when {
                coaches >= 10 -> ServiceOccupancyLevel.LOW
                coaches in 6..9 -> ServiceOccupancyLevel.MODERATE
                coaches in 1..5 -> ServiceOccupancyLevel.BUSY
                else -> ServiceOccupancyLevel.UNKNOWN
            }

            val altAdvice = if (isCancelled || delayMins >= 15) {
                "Check Journey Planner tab for alternative ${route.destination.name} connections."
            } else {
                requiresInterchangeNote
            }

            list.add(
                CommuteDepartureService(
                    serviceId = serviceKey,
                    routeId = route.routeId,
                    mode = SupportedTransportMode.RAIL,
                    operatorOrLineName = operator,
                    originName = route.origin.name,
                    destinationName = if (destCrs.length == 3 && matchedDestStop != null) {
                        route.destination.name
                    } else {
                        finalDestName
                    },
                    platform = platform,
                    previousPlatform = prevPlat,
                    platformChanged = platChanged,
                    scheduledDepartureTime = std,
                    expectedDepartureTime = expectedTimeStr,
                    scheduledDepartureEpochMs = schedMs,
                    expectedDepartureEpochMs = expMs,
                    scheduledArrivalTime = arrivalStr,
                    journeyDurationMinutes = durationMins,
                    delayMinutes = delayMins,
                    statusKind = statusKind,
                    statusDetailReason = cancelOrDelayReason,
                    occupancy = occupancy,
                    callingPoints = callingPoints,
                    alternativeRouteSuggestion = altAdvice
                )
            )
        }

        return list.sortedBy { it.scheduledDepartureEpochMs }
    }

    private fun parseDarwinCallingPoints(subsequentArray: JSONArray?): List<ServiceCallingPoint> {
        if (subsequentArray == null || subsequentArray.length() == 0) return emptyList()
        val cpList = subsequentArray.optJSONObject(0)?.optJSONArray("callingPoint") ?: return emptyList()
        val points = mutableListOf<ServiceCallingPoint>()
        for (i in 0 until cpList.length()) {
            val cp = cpList.optJSONObject(i) ?: continue
            val locName = cp.optString("locationName").trim()
            val crs = cp.optString("crs").trim()
            val st = cp.optString("st").trim()
            val etRaw = cp.optString("et").trim()
            val cancelled = cp.optBoolean("isCancelled", false) || etRaw.equals("Cancelled", ignoreCase = true)
            val expected = if (etRaw.equals("On time", ignoreCase = true) || etRaw.isBlank()) st else etRaw
            if (locName.isNotBlank() && st.isNotBlank()) {
                points.add(
                    ServiceCallingPoint(
                        stationName = locName,
                        crsOrCode = crs,
                        scheduledTime = st,
                        expectedTime = expected,
                        isCancelled = cancelled
                    )
                )
            }
        }
        return points
    }

    private fun fetchTflStopArrivalsAsDepartures(
        route: SavedCommuteRoute,
        previousPlatformsByServiceId: Map<String, String>
    ): List<CommuteDepartureService> {
        val stopId = route.origin.id.removePrefix("crs_")
        if (stopId.startsWith("geo_") || stopId.length < 4) return emptyList()

        val arrJson = getJsonArray(appendTflKey("$TFL_BASE_URL/StopPoint/$stopId/Arrivals"))
            ?: return emptyList()
        if (arrJson.length() == 0) return emptyList()

        val nowMs = System.currentTimeMillis()
        val items = mutableListOf<CommuteDepartureService>()

        for (i in 0 until arrJson.length()) {
            val obj = arrJson.optJSONObject(i) ?: continue
            val vehicleId = obj.optString("vehicleId").ifBlank { obj.optString("id", i.toString()) }
            val lineName = obj.optString("lineName").ifBlank { route.mode.displayName }
            val destName = obj.optString("destinationName")
                .ifBlank { obj.optString("towards") }
                .ifBlank { route.destination.name }
            val rawPlatform = obj.optString("platformName").trim()
            val timeToStationSec = obj.optInt("timeToStation", 0).coerceAtLeast(0)
            val expectedIso = obj.optString("expectedArrival")

            val expMs = parseIsoEpochMs(expectedIso).takeIf { it > 0L }
                ?: (nowMs + timeToStationSec * 1000L)
            val expFormatted = Instant.ofEpochMilli(expMs)
                .atZone(ZoneId.systemDefault())
                .format(HH_MM_FORMATTER)

            val serviceKey = "${route.routeId}_${lineName}_$vehicleId"
            val prevPlat = previousPlatformsByServiceId[serviceKey].orEmpty()
            val platChanged = prevPlat.isNotBlank() && rawPlatform.isNotBlank() && prevPlat != rawPlatform

            items.add(
                CommuteDepartureService(
                    serviceId = serviceKey,
                    routeId = route.routeId,
                    mode = route.mode,
                    operatorOrLineName = lineName,
                    originName = route.origin.name,
                    destinationName = destName,
                    platform = rawPlatform,
                    previousPlatform = prevPlat,
                    platformChanged = platChanged,
                    scheduledDepartureTime = expFormatted,
                    expectedDepartureTime = expFormatted,
                    scheduledDepartureEpochMs = expMs,
                    expectedDepartureEpochMs = expMs,
                    statusKind = if (platChanged) ServiceStatusKind.PLATFORM_CHANGED else ServiceStatusKind.ON_TIME,
                    statusDetailReason = obj.optString("currentLocation")
                )
            )
        }

        return items.sortedBy { it.expectedDepartureEpochMs }.take(8)
    }

    private fun fetchTflJourneyBackedDepartures(
        route: SavedCommuteRoute,
        previousPlatformsByServiceId: Map<String, String>
    ): List<CommuteDepartureService> {
        val options = planTflJourneysInternal(
            origin = route.origin,
            destination = route.destination,
            preferredMode = route.mode
        )
        return options.mapIndexed { idx, opt ->
            val firstLeg = opt.legs.firstOrNull()
            val depMs = parseTodayOrTomorrowTimeEpochMs(opt.departureTime)
            val plat = firstLeg?.platformOrStop.orEmpty()
            val serviceKey = "${route.routeId}_jp_${opt.departureTime}_$idx"
            val prevPlat = previousPlatformsByServiceId[serviceKey].orEmpty()
            val platChanged = prevPlat.isNotBlank() && plat.isNotBlank() && prevPlat != plat
            val hasDisruption = opt.disruptionNote.isNotBlank()

            CommuteDepartureService(
                serviceId = serviceKey,
                routeId = route.routeId,
                mode = firstLeg?.mode ?: route.mode,
                operatorOrLineName = firstLeg?.lineOrInstruction ?: route.mode.displayName,
                originName = route.origin.name,
                destinationName = route.destination.name,
                platform = plat,
                previousPlatform = prevPlat,
                platformChanged = platChanged,
                scheduledDepartureTime = opt.departureTime,
                expectedDepartureTime = opt.departureTime,
                scheduledDepartureEpochMs = depMs,
                expectedDepartureEpochMs = depMs,
                scheduledArrivalTime = opt.arrivalTime,
                journeyDurationMinutes = opt.totalDurationMinutes,
                delayMinutes = 0,
                statusKind = when {
                    platChanged -> ServiceStatusKind.PLATFORM_CHANGED
                    hasDisruption -> ServiceStatusKind.DELAYED
                    else -> ServiceStatusKind.ON_TIME
                },
                statusDetailReason = opt.disruptionNote,
                callingPoints = opt.legs.map { leg ->
                    ServiceCallingPoint(
                        stationName = leg.toStopName,
                        scheduledTime = leg.arrivalTime,
                        expectedTime = leg.arrivalTime
                    )
                }
            )
        }
    }

    private fun fetchRoadOrActiveTravelDepartures(
        route: SavedCommuteRoute
    ): List<CommuteDepartureService> {
        val oLat = route.origin.latitude
        val oLon = route.origin.longitude
        val dLat = route.destination.latitude
        val dLon = route.destination.longitude
        if (oLat == null || oLon == null || dLat == null || dLon == null) return emptyList()

        val osrmProfile = when (route.mode) {
            SupportedTransportMode.WALKING -> "foot"
            SupportedTransportMode.CYCLING -> "bike"
            else -> "car"
        }

        val url = "https://router.project-osrm.org/route/v1/$osrmProfile/$oLon,$oLat;$dLon,$dLat?overview=false&alternatives=true&steps=true"
        val root = getJsonObject(url) ?: return emptyList()
        val routesArr = root.optJSONArray("routes") ?: return emptyList()
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val results = mutableListOf<CommuteDepartureService>()
        val altSummary = if (routesArr.length() > 1) {
            val altSec = routesArr.optJSONObject(1)?.optDouble("duration", 0.0) ?: 0.0
            val altMin = (altSec / 60.0).roundToInt().coerceAtLeast(1)
            "Alternative route available (~$altMin mins)"
        } else {
            ""
        }

        for (i in 0 until minOf(routesArr.length(), 3)) {
            val rObj = routesArr.optJSONObject(i) ?: continue
            val durationMins = ((rObj.optDouble("duration", 0.0)) / 60.0).roundToInt().coerceAtLeast(1)
            val distanceKm = rObj.optDouble("distance", 0.0) / 1000.0
            val depMs = nowMs + (i * 15 * 60_000L)
            val arrMs = depMs + durationMins * 60_000L
            val depStr = Instant.ofEpochMilli(depMs).atZone(zone).format(HH_MM_FORMATTER)
            val arrStr = Instant.ofEpochMilli(arrMs).atZone(zone).format(HH_MM_FORMATTER)

            val summaryRoad = rObj.optJSONArray("legs")
                ?.optJSONObject(0)
                ?.optString("summary")
                .orEmpty()
                .ifBlank { "${"%.1f".format(Locale.UK, distanceKm)} km ${route.mode.displayName}" }

            results.add(
                CommuteDepartureService(
                    serviceId = "${route.routeId}_osrm_$i",
                    routeId = route.routeId,
                    mode = route.mode,
                    operatorOrLineName = "via $summaryRoad",
                    originName = route.origin.name,
                    destinationName = route.destination.name,
                    platform = "${"%.1f".format(Locale.UK, distanceKm)} km",
                    scheduledDepartureTime = depStr,
                    expectedDepartureTime = depStr,
                    scheduledDepartureEpochMs = depMs,
                    expectedDepartureEpochMs = depMs,
                    scheduledArrivalTime = arrStr,
                    journeyDurationMinutes = durationMins,
                    statusKind = ServiceStatusKind.ON_TIME,
                    alternativeRouteSuggestion = altSummary
                )
            )
        }
        return results
    }

    // ========================================================================
    // 3. Station Facility Details (Departures, Arrivals, Parking, Taxi, Access)
    // ========================================================================

    override suspend fun fetchStationFacilityDetails(
        station: TransportStopEndpoint,
        previousPlatformsByServiceId: Map<String, String>
    ): StationFacilityDetails = withContext(Dispatchers.IO) {
        val darwinBase = darwinGatewayUrlProvider().trimEnd('/')
        val crs = station.crsOrStopCode.trim().uppercase(Locale.UK)

        val liveDepartures = mutableListOf<CommuteDepartureService>()
        val liveArrivals = mutableListOf<StationArrivalEntry>()

        if (crs.length == 3) {
            coroutineScope {
                val depDeferred = async { getJsonObject("$darwinBase/departures/$crs/10?expand=true") }
                val arrDeferred = async { getJsonObject("$darwinBase/arrivals/$crs/10?expand=false") }

                val depObj = depDeferred.await()
                val depArr = depObj?.optJSONArray("trainServices")
                if (depArr != null) {
                    for (i in 0 until depArr.length()) {
                        val s = depArr.optJSONObject(i) ?: continue
                        val std = s.optString("std").trim()
                        val etd = s.optString("etd").trim()
                        val dest = s.optJSONArray("destination")
                            ?.optJSONObject(0)
                            ?.optString("locationName")
                            .orEmpty()
                        val plat = s.optString("platform").trim()
                        val cancelled = s.optBoolean("isCancelled", false) || etd.equals("Cancelled", ignoreCase = true)
                        val expStr = if (etd.equals("On time", ignoreCase = true) || etd.isBlank()) std else etd
                        val schedMs = parseTodayOrTomorrowTimeEpochMs(std)
                        val expMs = if (expStr.contains(":")) parseTodayOrTomorrowTimeEpochMs(expStr, schedMs) else schedMs
                        val delay = if (!cancelled && expMs > schedMs) ((expMs - schedMs) / 60_000L).toInt() else 0

                        liveDepartures.add(
                            CommuteDepartureService(
                                serviceId = "st_dep_${crs}_${std}_$i",
                                mode = SupportedTransportMode.RAIL,
                                operatorOrLineName = s.optString("operator").ifBlank { "National Rail" },
                                originName = station.name,
                                destinationName = dest,
                                platform = plat,
                                scheduledDepartureTime = std,
                                expectedDepartureTime = expStr,
                                scheduledDepartureEpochMs = schedMs,
                                expectedDepartureEpochMs = expMs,
                                delayMinutes = delay,
                                statusKind = when {
                                    cancelled -> ServiceStatusKind.CANCELLED
                                    delay > 0 -> ServiceStatusKind.DELAYED
                                    else -> ServiceStatusKind.ON_TIME
                                },
                                callingPoints = parseDarwinCallingPoints(s.optJSONArray("subsequentCallingPoints"))
                            )
                        )
                    }
                }

                val arrObj = arrDeferred.await()
                val arrServices = arrObj?.optJSONArray("trainServices")
                if (arrServices != null) {
                    for (i in 0 until arrServices.length()) {
                        val s = arrServices.optJSONObject(i) ?: continue
                        val sta = s.optString("sta").trim()
                        val eta = s.optString("eta").trim()
                        val origin = s.optJSONArray("origin")
                            ?.optJSONObject(0)
                            ?.optString("locationName")
                            .orEmpty()
                        liveArrivals.add(
                            StationArrivalEntry(
                                serviceId = "st_arr_${crs}_${sta}_$i",
                                operatorOrLineName = s.optString("operator").ifBlank { "National Rail" },
                                originName = origin,
                                platform = s.optString("platform").trim().ifBlank { "TBC" },
                                scheduledArrivalTime = sta,
                                expectedArrivalTime = if (eta.equals("On time", ignoreCase = true) || eta.isBlank()) sta else eta,
                                statusLabel = if (s.optBoolean("isCancelled", false)) "Cancelled" else eta.ifBlank { "On time" }
                            )
                        )
                    }
                }
            }
        }

        // Query TfL StopPoint metadata for real facility properties (Car Park, Taxi Rank, WiFi, Step-Free)
        var parkingSummary = "Station car park metadata not published for this stop"
        var taxiSummary = "Local licensed taxi / minicab pick-up outside main entrance"
        val facilities = LinkedHashSet<String>()
        var accessibilitySummary = "Check operator assistance for step-free boarding ramps"

        val candidateStopId = when {
            !station.id.startsWith("crs_") && !station.id.startsWith("geo_") -> station.id
            crs.length == 3 -> "910G$crs"
            else -> ""
        }

        if (candidateStopId.isNotBlank()) {
            val stopJson = getJsonObject(appendTflKey("$TFL_BASE_URL/StopPoint/$candidateStopId"))
            val props = stopJson?.optJSONArray("additionalProperties")
            if (props != null) {
                for (i in 0 until props.length()) {
                    val p = props.optJSONObject(i) ?: continue
                    val key = p.optString("key").trim()
                    val value = p.optString("value").trim()
                    if (value.isBlank() || value.equals("no", ignoreCase = true) || value == "0") continue

                    when {
                        key.contains("CarPark", ignoreCase = true) || key.contains("Spaces", ignoreCase = true) -> {
                            parkingSummary = "$key: $value"
                        }
                        key.contains("Taxi", ignoreCase = true) -> {
                            taxiSummary = "Taxi Rank available ($value)"
                        }
                        key.contains("Lift", ignoreCase = true) ||
                                key.contains("Access", ignoreCase = true) ||
                                key.contains("StepFree", ignoreCase = true) -> {
                            accessibilitySummary = "$key: $value"
                        }
                        key.equals("WiFi", ignoreCase = true) ||
                                key.equals("Toilets", ignoreCase = true) ||
                                key.equals("Waiting Room", ignoreCase = true) ||
                                key.equals("Ticket Halls", ignoreCase = true) ||
                                key.equals("Escalators", ignoreCase = true) ||
                                key.equals("Payphones", ignoreCase = true) -> {
                            facilities.add("$key: $value")
                        }
                    }
                }
            }

            if (liveDepartures.isEmpty()) {
                val tflArr = getJsonArray(appendTflKey("$TFL_BASE_URL/StopPoint/$candidateStopId/Arrivals"))
                if (tflArr != null) {
                    val nowMs = System.currentTimeMillis()
                    for (i in 0 until minOf(tflArr.length(), 10)) {
                        val a = tflArr.optJSONObject(i) ?: continue
                        val sec = a.optInt("timeToStation", 0)
                        val expMs = nowMs + sec * 1000L
                        val tStr = Instant.ofEpochMilli(expMs).atZone(ZoneId.systemDefault()).format(HH_MM_FORMATTER)
                        liveDepartures.add(
                            CommuteDepartureService(
                                serviceId = "tfl_st_$i",
                                mode = station.primaryMode,
                                operatorOrLineName = a.optString("lineName"),
                                originName = station.name,
                                destinationName = a.optString("destinationName"),
                                platform = a.optString("platformName"),
                                scheduledDepartureTime = tStr,
                                expectedDepartureTime = tStr,
                                scheduledDepartureEpochMs = expMs,
                                expectedDepartureEpochMs = expMs
                            )
                        )
                    }
                }
            }
        }

        if (facilities.isEmpty()) {
            if (crs.length == 3) {
                facilities.add("Live Departure Boards ($crs)")
                facilities.add("Smartcard / Contactless / Ticket Machines")
            } else {
                facilities.add("${station.primaryMode.displayName} Stop")
            }
        }

        StationFacilityDetails(
            station = station,
            liveDepartures = liveDepartures.sortedBy { it.scheduledDepartureEpochMs },
            liveArrivals = liveArrivals,
            parkingSummary = parkingSummary,
            taxiAvailability = taxiSummary,
            facilities = facilities.toList(),
            accessibilitySummary = accessibilitySummary
        )
    }

    // ========================================================================
    // 4. Live Network & Route Disruptions
    // ========================================================================

    override suspend fun fetchActiveDisruptions(
        savedRoutes: List<SavedCommuteRoute>,
        routeDepartures: List<CommuteDepartureService>
    ): List<CommuteDisruptionItem> = withContext(Dispatchers.IO) {
        val disruptions = LinkedHashMap<String, CommuteDisruptionItem>()
        val routeKeywords = savedRoutes.flatMap { r ->
            listOf(r.origin.name, r.destination.name, r.routeName)
        }.map { it.lowercase(Locale.UK) }

        // 1. Derive immediate route-specific service disruptions from live departure boards
        for (dep in routeDepartures) {
            if (!dep.statusKind.isDisrupted) continue
            val category = when (dep.statusKind) {
                ServiceStatusKind.CANCELLED -> DisruptionCategory.CANCELLATIONS
                ServiceStatusKind.ENGINEERING_WORKS -> DisruptionCategory.ENGINEERING_WORKS
                ServiceStatusKind.SUSPENDED -> DisruptionCategory.SERVICE_SUSPENSIONS
                ServiceStatusKind.HEAVY_TRAFFIC -> DisruptionCategory.ROAD_TRAFFIC
                else -> DisruptionCategory.DELAYS
            }
            val id = "route_dis_${dep.serviceId}"
            val summary = buildString {
                append("${dep.scheduledDepartureTime} ${dep.originName} to ${dep.destinationName}: ")
                when {
                    dep.isCancelled -> append("Service cancelled.")
                    dep.platformChanged -> append("Platform changed to ${dep.platform} (was ${dep.previousPlatform}).")
                    dep.delayMinutes > 0 -> append("Delayed by ${dep.delayMinutes} mins (expected ${dep.expectedDepartureTime}).")
                    else -> append(dep.statusKind.label)
                }
                if (dep.statusDetailReason.isNotBlank()) {
                    append(" ${dep.statusDetailReason}")
                }
            }
            disruptions[id] = CommuteDisruptionItem(
                id = id,
                category = category,
                lineOrRouteName = dep.operatorOrLineName,
                mode = dep.mode,
                severityLabel = dep.statusKind.label,
                summary = summary,
                alternativeRouteAdvice = dep.alternativeRouteSuggestion.ifBlank {
                    "Open Journey Planner tab to compare fastest alternative connections."
                },
                affectsSavedRoute = true
            )
        }

        // 2. Query TfL Multi-Modal Line Statuses (Tube, Overground, Elizabeth Line, DLR, National Rail, Tram, Ferry)
        coroutineScope {
            val transitJob = async {
                getJsonArray(
                    appendTflKey("$TFL_BASE_URL/Line/Mode/tube,dlr,overground,elizabeth-line,national-rail,tram,river-bus/Status?detail=true")
                )
            }
            val roadJob = async {
                if (savedRoutes.any { it.mode == SupportedTransportMode.DRIVING }) {
                    getJsonArray(appendTflKey("$TFL_BASE_URL/Road/all/Status"))
                } else {
                    null
                }
            }

            val lineArr = transitJob.await()
            if (lineArr != null) {
                for (i in 0 until lineArr.length()) {
                    val lineObj = lineArr.optJSONObject(i) ?: continue
                    val lineName = lineObj.optString("name").trim()
                    val modeName = lineObj.optString("modeName").trim()
                    val statuses = lineObj.optJSONArray("lineStatuses") ?: continue

                    for (s in 0 until statuses.length()) {
                        val st = statuses.optJSONObject(s) ?: continue
                        val severity = st.optInt("statusSeverity", 10)
                        val severityDesc = st.optString("statusSeverityDescription").trim()
                        // Severity 10 = Good Service; skip healthy lines
                        if (severity == 10 || severityDesc.equals("Good Service", ignoreCase = true)) continue

                        val reason = st.optString("reason").trim().ifBlank { severityDesc }
                        val lowerReason = reason.lowercase(Locale.UK)
                        val cat = when {
                            lowerReason.contains("planned") || lowerReason.contains("engineering") || severity == 7 ->
                                DisruptionCategory.ENGINEERING_WORKS
                            lowerReason.contains("suspend") || lowerReason.contains("closed") ->
                                DisruptionCategory.SERVICE_SUSPENSIONS
                            lowerReason.contains("cancel") ->
                                DisruptionCategory.CANCELLATIONS
                            else -> DisruptionCategory.DELAYS
                        }
                        val matchesUserRoute = routeKeywords.any { kw ->
                            kw.length >= 4 && (lowerReason.contains(kw) || lineName.lowercase(Locale.UK).contains(kw))
                        } || routeDepartures.any { dep ->
                            dep.operatorOrLineName.equals(lineName, ignoreCase = true)
                        }

                        val id = "tfl_line_${lineObj.optString("id")}_$s"
                        disruptions[id] = CommuteDisruptionItem(
                            id = id,
                            category = cat,
                            lineOrRouteName = lineName,
                            mode = SupportedTransportMode.fromIdOrName(modeName),
                            severityLabel = severityDesc,
                            summary = reason,
                            alternativeRouteAdvice = if (matchesUserRoute) {
                                "Use Journey Planner for step-by-step re-routing around $lineName."
                            } else {
                                "Tickets accepted on reasonable alternative routes where applicable."
                            },
                            affectsSavedRoute = matchesUserRoute
                        )
                    }
                }
            }

            val roadArr = roadJob.await()
            if (roadArr != null) {
                for (i in 0 until roadArr.length()) {
                    val r = roadArr.optJSONObject(i) ?: continue
                    val roadName = r.optString("displayName").trim()
                    val statusSeverity = r.optString("statusSeverity").trim()
                    val statusDesc = r.optString("statusSeverityDescription").trim()
                    if (statusSeverity.equals("Good", ignoreCase = true)) continue

                    val id = "road_${r.optString("id", i.toString())}"
                    disruptions[id] = CommuteDisruptionItem(
                        id = id,
                        category = DisruptionCategory.ROAD_TRAFFIC,
                        lineOrRouteName = roadName,
                        mode = SupportedTransportMode.DRIVING,
                        severityLabel = statusSeverity,
                        summary = "$roadName: $statusDesc",
                        alternativeRouteAdvice = "OSRM Driving planner includes live alternative road corridors.",
                        affectsSavedRoute = routeKeywords.any { roadName.lowercase(Locale.UK).contains(it) }
                    )
                }
            }
        }

        disruptions.values.sortedWith(
            compareByDescending<CommuteDisruptionItem> { it.affectsSavedRoute }
                .thenBy { it.category.ordinal }
        )
    }

    // ========================================================================
    // 5. Multi-Modal Journey Planner (Fastest, Cheapest, Fewest Changes, Earliest)
    // ========================================================================

    override suspend fun planMultiOptionJourneys(
        origin: TransportStopEndpoint,
        destination: TransportStopEndpoint,
        preferredMode: SupportedTransportMode
    ): List<PlannedJourneyOption> = withContext(Dispatchers.IO) {
        val rawOptions = if (preferredMode.isPublicTransit) {
            planTflJourneysInternal(origin, destination, preferredMode)
        } else {
            planOsrmJourneysInternal(origin, destination, preferredMode)
        }

        if (rawOptions.isEmpty()) return@withContext emptyList()

        val fastestId = rawOptions.minByOrNull { it.totalDurationMinutes }?.id
        val cheapestId = rawOptions.filter { it.estimatedFareGbp != null }
            .minByOrNull { it.estimatedFareGbp ?: Double.MAX_VALUE }?.id
        val fewestChangesId = rawOptions.minByOrNull { it.changesCount }?.id
        val earliestId = rawOptions.minByOrNull { it.arrivalEpochMs }?.id

        rawOptions.map { opt ->
            val badges = buildList {
                if (opt.id == fastestId) add(JourneyOptionBadge.FASTEST_ROUTE)
                if (opt.id == earliestId) add(JourneyOptionBadge.EARLIEST_ARRIVAL)
                if (opt.id == fewestChangesId) add(JourneyOptionBadge.FEWEST_CHANGES)
                if (cheapestId != null && opt.id == cheapestId) add(JourneyOptionBadge.CHEAPEST_ROUTE)
            }
            opt.copy(badges = badges)
        }
    }

    private fun planTflJourneysInternal(
        origin: TransportStopEndpoint,
        destination: TransportStopEndpoint,
        preferredMode: SupportedTransportMode
    ): List<PlannedJourneyOption> {
        val fromToken = formatJourneyEndpointParam(origin)
        val toToken = formatJourneyEndpointParam(destination)
        if (fromToken.isBlank() || toToken.isBlank()) return emptyList()

        val encodedFrom = URLEncoder.encode(fromToken, "UTF-8")
        val encodedTo = URLEncoder.encode(toToken, "UTF-8")
        val url = appendTflKey(
            "$TFL_BASE_URL/Journey/JourneyResults/$encodedFrom/to/$encodedTo?nationalSearch=true"
        )

        val root = getJsonObject(url) ?: return emptyList()
        val journeysArr = root.optJSONArray("journeys") ?: return emptyList()
        val options = mutableListOf<PlannedJourneyOption>()

        for (i in 0 until journeysArr.length()) {
            val j = journeysArr.optJSONObject(i) ?: continue
            val startIso = j.optString("startDateTime")
            val arrIso = j.optString("arrivalDateTime")
            val duration = j.optInt("duration", 0).coerceAtLeast(1)
            val startMs = parseIsoEpochMs(startIso)
            val arrMs = parseIsoEpochMs(arrIso).takeIf { it > 0L } ?: (startMs + duration * 60_000L)

            val depStr = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).format(HH_MM_FORMATTER)
            val arrStr = Instant.ofEpochMilli(arrMs).atZone(ZoneId.systemDefault()).format(HH_MM_FORMATTER)

            val totalCostPence = j.optJSONObject("fare")?.optInt("totalCost", -1) ?: -1
            val fareGbp = if (totalCostPence > 0) totalCostPence / 100.0 else null

            val legsArr = j.optJSONArray("legs")
            val legs = mutableListOf<JourneyLegStep>()
            val disruptionMessages = mutableListOf<String>()

            if (legsArr != null) {
                for (l in 0 until legsArr.length()) {
                    val legObj = legsArr.optJSONObject(l) ?: continue
                    val legModeId = legObj.optJSONObject("mode")?.optString("id").orEmpty()
                    val legMode = SupportedTransportMode.fromIdOrName(legModeId)
                    val routeName = legObj.optJSONArray("routeOptions")
                        ?.optJSONObject(0)
                        ?.optString("name")
                        .orEmpty()
                        .ifBlank { legObj.optJSONObject("instruction")?.optString("summary").orEmpty() }
                        .ifBlank { legMode.displayName }

                    val depPoint = legObj.optJSONObject("departurePoint")
                    val arrPoint = legObj.optJSONObject("arrivalPoint")
                    val fromName = depPoint?.optString("commonName").orEmpty().ifBlank { origin.name }
                    val toName = arrPoint?.optString("commonName").orEmpty().ifBlank { destination.name }
                    val plat = depPoint?.optString("platformName").orEmpty()

                    val legDepMs = parseIsoEpochMs(legObj.optString("departureTime"))
                    val legArrMs = parseIsoEpochMs(legObj.optString("arrivalTime"))
                    val legDur = legObj.optInt("duration", 0).coerceAtLeast(1)

                    val legDepStr = if (legDepMs > 0L) {
                        Instant.ofEpochMilli(legDepMs).atZone(ZoneId.systemDefault()).format(HH_MM_FORMATTER)
                    } else depStr
                    val legArrStr = if (legArrMs > 0L) {
                        Instant.ofEpochMilli(legArrMs).atZone(ZoneId.systemDefault()).format(HH_MM_FORMATTER)
                    } else arrStr

                    val disArr = legObj.optJSONArray("disruptions")
                    if (disArr != null && disArr.length() > 0) {
                        val desc = disArr.optJSONObject(0)?.optString("description").orEmpty().trim()
                        if (desc.isNotBlank()) disruptionMessages.add(desc)
                    }

                    legs.add(
                        JourneyLegStep(
                            mode = legMode,
                            lineOrInstruction = routeName,
                            fromStopName = fromName,
                            toStopName = toName,
                            departureTime = legDepStr,
                            arrivalTime = legArrStr,
                            durationMinutes = legDur,
                            platformOrStop = plat
                        )
                    )
                }
            }

            val transitLegCount = legs.count { it.mode != SupportedTransportMode.WALKING }
            val changesCount = (transitLegCount - 1).coerceAtLeast(0)

            options.add(
                PlannedJourneyOption(
                    id = "jp_${i}_${depStr}_$arrStr",
                    badges = emptyList(),
                    departureTime = depStr,
                    arrivalTime = arrStr,
                    arrivalEpochMs = arrMs,
                    totalDurationMinutes = duration,
                    changesCount = changesCount,
                    estimatedFareGbp = fareGbp,
                    legs = legs,
                    disruptionNote = disruptionMessages.distinct().joinToString(" • ")
                )
            )
        }

        return options
    }

    private fun planOsrmJourneysInternal(
        origin: TransportStopEndpoint,
        destination: TransportStopEndpoint,
        mode: SupportedTransportMode
    ): List<PlannedJourneyOption> {
        val oLat = origin.latitude ?: return emptyList()
        val oLon = origin.longitude ?: return emptyList()
        val dLat = destination.latitude ?: return emptyList()
        val dLon = destination.longitude ?: return emptyList()

        val profile = when (mode) {
            SupportedTransportMode.WALKING -> "foot"
            SupportedTransportMode.CYCLING -> "bike"
            else -> "car"
        }

        val root = getJsonObject(
            "https://router.project-osrm.org/route/v1/$profile/$oLon,$oLat;$dLon,$dLat?overview=false&alternatives=true&steps=true"
        ) ?: return emptyList()

        val routes = root.optJSONArray("routes") ?: return emptyList()
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val options = mutableListOf<PlannedJourneyOption>()

        for (i in 0 until routes.length()) {
            val r = routes.optJSONObject(i) ?: continue
            val durMins = (r.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)
            val distKm = r.optDouble("distance", 0.0) / 1000.0
            val arrMs = nowMs + durMins * 60_000L
            val depStr = Instant.ofEpochMilli(nowMs).atZone(zone).format(HH_MM_FORMATTER)
            val arrStr = Instant.ofEpochMilli(arrMs).atZone(zone).format(HH_MM_FORMATTER)
            val summary = r.optJSONArray("legs")?.optJSONObject(0)?.optString("summary")
                .orEmpty()
                .ifBlank { mode.displayName }

            // Approximate fuel cost for Driving so Cheapest Route comparison works meaningfully
            val fuelEst = if (mode == SupportedTransportMode.DRIVING) {
                ((distKm * 0.16) * 100.0).roundToInt() / 100.0
            } else {
                0.0
            }

            options.add(
                PlannedJourneyOption(
                    id = "osrm_opt_$i",
                    badges = emptyList(),
                    departureTime = depStr,
                    arrivalTime = arrStr,
                    arrivalEpochMs = arrMs,
                    totalDurationMinutes = durMins,
                    changesCount = 0,
                    estimatedFareGbp = fuelEst,
                    legs = listOf(
                        JourneyLegStep(
                            mode = mode,
                            lineOrInstruction = "${mode.displayName} via $summary (${"%.1f".format(Locale.UK, distKm)} km)",
                            fromStopName = origin.name,
                            toStopName = destination.name,
                            departureTime = depStr,
                            arrivalTime = arrStr,
                            durationMinutes = durMins
                        )
                    )
                )
            )
        }
        return options
    }

    // ========================================================================
    // Internal HTTP, Time & Formatting Helpers
    // ========================================================================

    private fun formatJourneyEndpointParam(endpoint: TransportStopEndpoint): String {
        return when {
            endpoint.latitude != null && endpoint.longitude != null ->
                "${endpoint.latitude},${endpoint.longitude}"
            !endpoint.id.startsWith("crs_") && !endpoint.id.startsWith("geo_") && endpoint.id.isNotBlank() ->
                endpoint.id
            endpoint.crsOrStopCode.length == 3 ->
                "910G${endpoint.crsOrStopCode.uppercase(Locale.UK)}"
            else -> endpoint.name
        }
    }

    private fun appendTflKey(baseUrl: String): String {
        val key = tflApiKeyProvider()?.trim().orEmpty()
        if (key.isBlank()) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "${baseUrl}${sep}app_key=${URLEncoder.encode(key, "UTF-8")}"
    }

    private fun extractNrccPlainText(nrccArr: JSONArray?): String {
        if (nrccArr == null || nrccArr.length() == 0) return ""
        val first = nrccArr.optJSONObject(0)?.optString("Value")
            ?: nrccArr.optString(0)
            ?: ""
        return first
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeStopName(raw: String): String {
        return raw.lowercase(Locale.UK)
            .replace("rail station", "")
            .replace("underground station", "")
            .replace("station", "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun parseTodayOrTomorrowTimeEpochMs(
        hhMm: String,
        referenceMs: Long = System.currentTimeMillis()
    ): Long {
        val parts = hhMm.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return referenceMs
        val minute = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0
        val zone = ZoneId.systemDefault()
        val refZdt = Instant.ofEpochMilli(referenceMs).atZone(zone)
        var candidate = ZonedDateTime.of(refZdt.toLocalDate(), LocalTime.of(hour % 24, minute % 60), zone)
        if (candidate.toInstant().toEpochMilli() < referenceMs - 6 * 3_600_000L) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    private fun parseIsoEpochMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
            runCatching {
                ZonedDateTime.parse(
                    if (raw.endsWith("Z") || raw.contains("+")) raw else "${raw}Z",
                    DateTimeFormatter.ISO_DATE_TIME
                ).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }

    private fun getJsonObject(urlString: String): JSONObject? {
        val body = httpGetText(urlString) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getJsonArray(urlString: String): JSONArray? {
        val body = httpGetText(urlString) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun httpGetText(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "ClaraSmartHome/2.0 (Android; CommuteHub)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * RulesEngine implementation per Commute Hub v2.0 JSON specification ("provider": "RulesEngine").
 * Evaluates all 6 supported rules: Delay Alert, Platform Change, Engineering Works,
 * Heavy Traffic, Alternative Route Available, and Leave Home Reminder.
 */
object CommuteInsightRulesEngine {

    fun evaluateInsights(
        primaryCard: RouteLiveSummaryCard?,
        allCards: List<RouteLiveSummaryCard>,
        disruptions: List<CommuteDisruptionItem>,
        preferences: CommuteHubPreferences
    ): List<CommuteInsight> {
        val insights = mutableListOf<CommuteInsight>()

        // Rule 1: Platform Change (Highest immediate urgency before boarding)
        allCards.forEach { card ->
            val changedSvc = card.upcomingServices.firstOrNull { it.platformChanged }
                ?: card.nextService?.takeIf { it.platformChanged }
            if (changedSvc != null) {
                insights.add(
                    CommuteInsight(
                        id = "rule_plat_${changedSvc.serviceId}",
                        ruleType = CommuteInsightRuleType.PLATFORM_CHANGE,
                        headline = "Platform changed to ${changedSvc.platform} for ${changedSvc.scheduledDepartureTime} to ${changedSvc.destinationName}",
                        supportingDetail = "Previously Platform ${changedSvc.previousPlatform}",
                        priority = 100,
                        routeId = card.route.routeId
                    )
                )
            }
        }

        // Rule 2: Delay Alert / Cancellation
        allCards.forEach { card ->
            val next = card.nextService ?: return@forEach
            if (next.isCancelled) {
                insights.add(
                    CommuteInsight(
                        id = "rule_cancel_${next.serviceId}",
                        ruleType = CommuteInsightRuleType.DELAY_ALERT,
                        headline = "${next.scheduledDepartureTime} ${next.originName} to ${next.destinationName} is CANCELLED",
                        supportingDetail = next.statusDetailReason.ifBlank { card.route.routeName },
                        priority = 98,
                        routeId = card.route.routeId
                    )
                )
            } else if (next.delayMinutes >= preferences.delayAlertThresholdMinutes || next.delayMinutes >= 5) {
                insights.add(
                    CommuteInsight(
                        id = "rule_delay_${next.serviceId}",
                        ruleType = CommuteInsightRuleType.DELAY_ALERT,
                        headline = "${card.route.routeName} delayed by ${next.delayMinutes} mins (expected ${next.expectedDepartureTime})",
                        supportingDetail = next.statusDetailReason.ifBlank { next.operatorOrLineName },
                        priority = 94,
                        routeId = card.route.routeId
                    )
                )
            }
        }

        // Rule 3: Engineering Works
        disruptions.firstOrNull {
            it.category == DisruptionCategory.ENGINEERING_WORKS && it.affectsSavedRoute
        }?.let { eng ->
            insights.add(
                CommuteInsight(
                    id = "rule_eng_${eng.id}",
                    ruleType = CommuteInsightRuleType.ENGINEERING_WORKS,
                    headline = "Engineering works affecting ${eng.lineOrRouteName}",
                    supportingDetail = eng.summary.take(120),
                    priority = 90
                )
            )
        }

        // Rule 4: Heavy Traffic
        disruptions.firstOrNull {
            it.category == DisruptionCategory.ROAD_TRAFFIC
        }?.let { traffic ->
            insights.add(
                CommuteInsight(
                    id = "rule_traffic_${traffic.id}",
                    ruleType = CommuteInsightRuleType.HEAVY_TRAFFIC,
                    headline = "Heavy traffic reported on ${traffic.lineOrRouteName}",
                    supportingDetail = traffic.summary.take(110),
                    priority = 84
                )
            )
        }

        // Rule 5: Alternative Route Available
        val disruptedPrimary = primaryCard?.takeIf { it.overallStatus.isDisrupted }
        if (disruptedPrimary != null && disruptedPrimary.alternativeRouteSummary.isNotBlank()) {
            insights.add(
                CommuteInsight(
                    id = "rule_alt_${disruptedPrimary.route.routeId}",
                    ruleType = CommuteInsightRuleType.ALTERNATIVE_ROUTE_AVAILABLE,
                    headline = disruptedPrimary.alternativeRouteSummary,
                    supportingDetail = "Tap Commute Hub -> Journey Planner for step-by-step transfer times",
                    priority = 88,
                    routeId = disruptedPrimary.route.routeId
                )
            )
        }

        // Rule 6: Leave Home Reminder
        primaryCard?.let { card ->
            val leaveMins = card.leaveHomeInMinutes
            val next = card.nextService
            if (leaveMins != null && next != null && !next.isCancelled && leaveMins in 0..45) {
                val headline = if (leaveMins <= 2) {
                    "Leave home now to catch the ${next.expectedDepartureTime} to ${next.destinationName}"
                } else {
                    "Leave home in $leaveMins mins (${card.leaveHomeAtFormatted}) for the ${next.expectedDepartureTime} service"
                }
                insights.add(
                    CommuteInsight(
                        id = "rule_leave_${card.route.routeId}",
                        ruleType = CommuteInsightRuleType.LEAVE_HOME_REMINDER,
                        headline = headline,
                        supportingDetail = "${card.route.walkToStationMinutes}m walk to ${card.route.origin.name} • Platform ${next.displayPlatform}",
                        priority = if (leaveMins <= preferences.leaveHomeBufferMinutes) 96 else 78,
                        routeId = card.route.routeId
                    )
                )
            }
        }

        return insights.sortedByDescending { it.priority }
    }
}