package com.jarvis.smarthomelauncher.core



import android.content.ClipData

import android.content.ClipboardManager

import android.content.Context

import android.content.ContentValues

import android.content.Intent

import android.database.sqlite.SQLiteDatabase

import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontFamily

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import androidx.compose.ui.window.Dialog

import androidx.compose.ui.window.DialogProperties

import android.os.SystemClock

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.channels.BufferOverflow

import kotlinx.coroutines.channels.Channel

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.launch

import org.json.JSONObject

import java.io.File

import java.net.SocketTimeoutException

import java.security.MessageDigest

import java.text.SimpleDateFormat

import java.util.Date

import java.util.Locale

import java.util.UUID

import java.util.concurrent.ConcurrentHashMap

import java.util.concurrent.atomic.AtomicBoolean





data class DeviceCapabilitySnapshot(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val schemaVersion: Int,
    val lifecycleSequenceNumber: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val monotonicTimestampNs: Long = safeElapsedRealtimeNanos(),
    val schemaHash: String,
    val capabilityCount: Int,
    val capabilitiesJson: String,
    val capabilities: List<DeviceCapabilityDescriptor> = emptyList()
)

enum class DiscoveryEventType {

    STARTED,

    ADVERTISEMENT_RECEIVED,

    IDENTIFIED,

    FILTERED,

    PRESENTED,

    FAILED,

    TIMEOUT

}



data class DiscoveryFlightEvent(

    val id: String = UUID.randomUUID().toString(),

    val sessionId: String,

    val pairingSessionId: String = "",

    val timestampMs: Long = System.currentTimeMillis(),

    val monotonicTimestampNs: Long = safeElapsedRealtimeNanos(),

    val elapsedMs: Long,

    val eventType: DiscoveryEventType,

    val transportSource: String,

    val hardwareId: String,

    val cloudId: String = "",

    val macAddress: String,

    val ipAddress: String,

    val port: Int,

    val vendor: String,

    val confidenceScore: Float,

    val qualificationStage: String,

    val outcomeReason: String,

    val rawPayload: String = "",

    val wifiFrequencyMhz: Int = 0,

    val rssiDbm: Int = -127,

    val permissionState: String = "",

    val durationMs: Long = 0L,

    val protocolResultCode: String = ""

)



private fun safeElapsedRealtimeNanos(): Long =

    runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }



private fun safeElapsedRealtimeMs(): Long =

    runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }



data class DeviceLifecycleEvent(

    val id: String = UUID.randomUUID().toString(),

    val deviceId: String,

    val sequenceNumber: Long,

    val pairingSessionId: String = "",

    val timestampMs: Long = System.currentTimeMillis(),

    val monotonicTimestampNs: Long = safeElapsedRealtimeNanos(),

    val event: String,

    val previousState: String = "",

    val newState: String = "",

    val activeTransport: String = "",

    val vendorDriver: String = "",

    val latencyMs: Long = 0L,

    val durationMs: Long = 0L,

    val protocolResultCode: String = "",

    val failureCategory: String = "",

    val wifiFrequencyMhz: Int = 0,

    val rssiDbm: Int = -127,

    val permissionState: String = "",

    val capabilitySchemaVersion: Int = 0,

    val capabilitySchemaHash: String = "",

    val capabilitySnapshotJson: String = ""

)



/**

 * Change 1.4: Nine typed per-device Black Box domains.

 * Setup/forensic domains (Discovery, Pairing, Auth, Activation, Capability) are eligible

 * for permanent pinning (first 25 records per canonical GUID) until formal unbind.

 */

enum class BlackBoxDomain(

    val label: String,

    val isPinEligibleSetupDomain: Boolean

) {

    DISCOVERY("Discovery", true),

    PAIRING("Pairing", true),

    AUTH("Auth", true),

    ACTIVATION("Activation", true),

    CAPABILITY("Capability", true),

    COMMAND("Command", false),

    CONNECTIVITY("Connectivity", false),

    RECOVERY("Recovery", false),

    FAILURE("Failure", false);



    companion object {

        fun fromRawOrInfer(

            raw: String?,

            category: DeviceTraceCategory,

            outcome: TraceOutcome,

            action: String = ""

        ): BlackBoxDomain {

            if (!raw.isNullOrBlank()) {

                entries.firstOrNull {

                    it.name.equals(raw.trim(), ignoreCase = true) ||

                            it.label.equals(raw.trim(), ignoreCase = true)

                }?.let { return it }

            }

            val actUpper = action.uppercase(Locale.US)

            return when {

                category == DeviceTraceCategory.AUTH_KEY -> AUTH

                outcome in setOf(

                    TraceOutcome.FAILED,

                    TraceOutcome.TIMEOUT,

                    TraceOutcome.BOUNCED,

                    TraceOutcome.UNHANDLED

                ) -> FAILURE

                actUpper.startsWith("DISCOVER") ||

                        actUpper.startsWith("VALIDAT") ||

                        actUpper.startsWith("REDISCOVER") -> DISCOVERY

                actUpper.startsWith("PAIR") ||

                        actUpper.startsWith("UNPAIR") ||

                        actUpper.startsWith("UNBOUND") -> PAIRING

                actUpper.startsWith("AUTH") -> AUTH

                actUpper.startsWith("ACTIVAT") -> ACTIVATION

                category == DeviceTraceCategory.CAPABILITY_MISMATCH ||

                        actUpper.startsWith("CAPABILITY") -> CAPABILITY

                actUpper.startsWith("RECOVERY") || actUpper.contains("SELF_HEAL") -> RECOVERY

                category == DeviceTraceCategory.STATE_POLL ||

                        category == DeviceTraceCategory.REACHABILITY -> CONNECTIVITY

                else -> COMMAND

            }

        }

    }

}



enum class DeviceTraceCategory {

    UI_ACTION,

    COMMAND_TX,

    TRANSPORT_RX,

    STATE_POLL,

    STATE_BOUNCE_WARN,

    REACHABILITY,

    AUTH_KEY,

    CAPABILITY_MISMATCH,

    LIFECYCLE_STEP

}



enum class TraceOutcome {

    SUCCESS,

    FAILED,

    TIMEOUT,

    BOUNCED,

    UNHANDLED

}



data class DeviceTraceEvent(

    val id: String = UUID.randomUUID().toString(),

    val pairingSessionId: String = "",

    val timestampMs: Long = System.currentTimeMillis(),

    val monotonicTimestampNs: Long = safeElapsedRealtimeNanos(),

    val deviceId: String,

    val deviceName: String,

    val provider: String,

    val endpointIp: String = "",

    val category: DeviceTraceCategory,

    val action: String,

    val outcome: TraceOutcome,

    val latencyMs: Long = 0L,

    val expectedState: String = "",

    val actualState: String = "",

    val errorSummary: String = "",

    val rawPayload: String = "",

    val wifiFrequencyMhz: Int = 0,

    val rssiDbm: Int = -127,

    val permissionState: String = "",

    val protocolResultCode: String = "",

    val domain: BlackBoxDomain = BlackBoxDomain.fromRawOrInfer(null, category, outcome, action),

    val isPinned: Boolean = false

)



data class PartitionVerificationReport(

    val passed: Boolean,

    val deviceAPinnedCount: Int,

    val deviceARuntimeCount: Int,

    val deviceADiskTotalCount: Int,

    val deviceBPinnedCount: Int,

    val deviceBRuntimeCount: Int,

    val deviceBDiskTotalCount: Int,

    val summary: String

)



object DiagnosticsSanitizer {

    private val wifiPasswordRegex = Regex(

        """(?i)("?(?:wifi[_-]?password|wifi[_-]?pass|ssid[_-]?password|ssid[_-]?pass|passphrase|psk|password|passwd)"?)\s*([:=\s])\s*("([^"\\]|\\.)*"|'([^'\\]|\\.)*'|[^"',\s}\]]+)"""

    )



    private val hashedKeyOrTokenRegex = Regex(

        """(?i)("?(?:local[_-]?key|client[-_]?key|ssap[_-]?key|device[_-]?key|secret|token|access[_-]?token|refresh[_-]?token|api[_-]?key|code[_-]?verifier|bearer)"?)\s*([:=\s])\s*["']?([^"',\s}\]]+)["']?"""

    )



    @Volatile

    private var transientSensitiveWifiLiteral: String = ""



    fun registerTransientWifiSecretForRedaction(rawWifiPassword: String) {

        val trimmed = rawWifiPassword.trim()

        transientSensitiveWifiLiteral = if (trimmed.length >= 4) trimmed else ""

    }



    fun fingerprintSecret(rawSecret: String?): String {

        val clean = rawSecret?.trim().orEmpty()

        if (clean.isEmpty()) return "empty"

        return runCatching {

            val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))

            digest.take(4).joinToString("") { "%02x".format(it) }

        }.getOrDefault("redacted")

    }



    fun scrub(input: String): String {

        if (input.isBlank()) return ""

        var working = input

        val activeWifiSecret = transientSensitiveWifiLiteral

        if (activeWifiSecret.isNotEmpty() && working.contains(activeWifiSecret)) {

            working = working.replace(activeWifiSecret, "[REDACTED_WIFI_SECRET]")

        }

        working = wifiPasswordRegex.replace(working) { match ->

            val key = match.groupValues[1]

            val sep = match.groupValues[2].ifBlank { "=" }

            val wasJsonQuoted = key.startsWith("\"") && key.endsWith("\"")

            if (wasJsonQuoted) "$key$sep\"[REDACTED_WIFI_SECRET]\"" else "$key$sep[REDACTED_WIFI_SECRET]"

        }

        working = hashedKeyOrTokenRegex.replace(working) { match ->

            val key = match.groupValues[1]

            val sep = match.groupValues[2].ifBlank { "=" }

            val rawValue = match.groupValues[3]

            if (rawValue.startsWith("sha256:", ignoreCase = true) || rawValue == "[REDACTED_WIFI_SECRET]") {

                match.value

            } else {

                val fp = fingerprintSecret(rawValue)

                val wasJsonQuoted = key.startsWith("\"") && key.endsWith("\"")

                if (wasJsonQuoted) "$key$sep\"sha256:$fp\"" else "$key${sep}sha256:$fp"

            }

        }

        return working.take(1500)

    }



    fun formatTime(ms: Long): String =

        SimpleDateFormat("HH:mm:ss.SSS", Locale.UK).format(Date(ms))

}

val OnboardingRfContextProbe: RadioEnvironmentProbe
    get() = RadioEnvironmentProbe

object DiscoveryFlightRecorder {

    private const val MAX_EVENTS = 1500

    private const val MAX_DISK_EVENTS = 5000

    private const val WRITE_BATCH_LIMIT = 64

    private const val PRUNE_INTERVAL_WRITES = 128



    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeQueue = Channel<DiscoveryFlightEvent>(

        capacity = 4096,

        onBufferOverflow = BufferOverflow.DROP_OLDEST

    )

    private val writerStarted = AtomicBoolean(false)



    private val _events = MutableStateFlow<List<DiscoveryFlightEvent>>(emptyList())

    val events: StateFlow<List<DiscoveryFlightEvent>> = _events.asStateFlow()



    @Volatile private var activeSessionId: String = "boot"

    @Volatile private var activePairingSessionId: String = ""

    @Volatile private var sessionStartElapsedMs: Long = safeElapsedRealtimeMs()

    @Volatile private var dbHelper: TelemetryDatabaseHelper? = null



    fun currentSessionId(): String = activeSessionId



    fun currentPairingSessionId(): String = activePairingSessionId



    fun startPairingSession(entryModeLabel: String): String {

        val newPairingId = "pair-${UUID.randomUUID().toString().take(8)}"

        activePairingSessionId = newPairingId

        startSession("wizard_${entryModeLabel.lowercase(Locale.US).replace(' ', '_')}")

        return newPairingId

    }



    fun attachActivePairingSession(pairingSessionId: String) {

        activePairingSessionId = pairingSessionId.trim()

    }



    fun endPairingSession(expectedPairingSessionId: String = "") {

        if (expectedPairingSessionId.isBlank() || activePairingSessionId == expectedPairingSessionId) {

            activePairingSessionId = ""

        }

    }



    fun initialize(context: Context) {

        RadioEnvironmentProbe.attachContext(context)

        dbHelper = TelemetryDatabaseHelper.getInstance(context)

        ensureBackgroundWriterStarted()

        loadFromDiskAsync()

    }



    private fun ensureBackgroundWriterStarted() {

        if (!writerStarted.compareAndSet(false, true)) return

        ioScope.launch {

            var writesSincePrune = 0

            for (firstEvent in writeQueue) {

                val helper = dbHelper ?: continue

                val batch = ArrayList<DiscoveryFlightEvent>(WRITE_BATCH_LIMIT)

                batch.add(firstEvent)

                while (batch.size < WRITE_BATCH_LIMIT) {

                    val next = writeQueue.tryReceive().getOrNull() ?: break

                    batch.add(next)

                }

                runCatching {

                    val db = helper.writableDatabase

                    db.beginTransactionNonExclusive()

                    try {

                        for (ev in batch) {

                            val values = ContentValues().apply {

                                put("id", ev.id)

                                put("sessionId", ev.sessionId)

                                put("pairingSessionId", ev.pairingSessionId)

                                put("timestampMs", ev.timestampMs)

                                put("monotonicTimestampNs", ev.monotonicTimestampNs)

                                put("elapsedMs", ev.elapsedMs)

                                put("eventType", ev.eventType.name)

                                put("transportSource", ev.transportSource)

                                put("hardwareId", ev.hardwareId)

                                put("cloudId", ev.cloudId)

                                put("macAddress", ev.macAddress)

                                put("ipAddress", ev.ipAddress)

                                put("port", ev.port)

                                put("vendor", ev.vendor)

                                put("confidenceScore", ev.confidenceScore)

                                put("qualificationStage", ev.qualificationStage)

                                put("outcomeReason", ev.outcomeReason)

                                put("rawPayload", ev.rawPayload)

                                put("wifiFrequencyMhz", ev.wifiFrequencyMhz)

                                put("rssiDbm", ev.rssiDbm)

                                put("permissionState", ev.permissionState)

                                put("durationMs", ev.durationMs)

                                put("protocolResultCode", ev.protocolResultCode)

                            }

                            db.insertWithOnConflict(

                                TelemetryDatabaseHelper.TABLE_DISCOVERY,

                                null,

                                values,

                                SQLiteDatabase.CONFLICT_REPLACE

                            )

                        }

                        writesSincePrune += batch.size

                        if (writesSincePrune >= PRUNE_INTERVAL_WRITES) {

                            writesSincePrune = 0

                            db.execSQL(

                                """

                                DELETE FROM ${TelemetryDatabaseHelper.TABLE_DISCOVERY}

                                WHERE id NOT IN (

                                    SELECT id FROM ${TelemetryDatabaseHelper.TABLE_DISCOVERY}

                                    ORDER BY timestampMs DESC, monotonicTimestampNs DESC

                                    LIMIT $MAX_DISK_EVENTS

                                )

                                """.trimIndent()

                            )

                        }

                        db.setTransactionSuccessful()

                    } finally {

                        db.endTransaction()

                    }

                }

            }

        }

    }



    private fun loadFromDiskAsync() {

        ioScope.launch {

            runCatching {

                val db = dbHelper?.readableDatabase ?: return@launch

                val loaded = mutableListOf<DiscoveryFlightEvent>()

                val cursor = db.query(

                    TelemetryDatabaseHelper.TABLE_DISCOVERY,

                    null,

                    null,

                    null,

                    null,

                    null,

                    "timestampMs DESC, monotonicTimestampNs DESC",

                    MAX_EVENTS.toString()

                )

                cursor.use {

                    val monoIdx = it.getColumnIndex("monotonicTimestampNs")

                    val cloudIdx = it.getColumnIndex("cloudId")

                    val pairIdx = it.getColumnIndex("pairingSessionId")

                    val freqIdx = it.getColumnIndex("wifiFrequencyMhz")

                    val rssiIdx = it.getColumnIndex("rssiDbm")

                    val permIdx = it.getColumnIndex("permissionState")

                    val durIdx = it.getColumnIndex("durationMs")

                    val codeIdx = it.getColumnIndex("protocolResultCode")

                    while (it.moveToNext()) {

                        val rawType = it.getString(it.getColumnIndexOrThrow("eventType"))

                        val parsedType = runCatching { DiscoveryEventType.valueOf(rawType) }

                            .getOrDefault(DiscoveryEventType.IDENTIFIED)

                        loaded.add(

                            DiscoveryFlightEvent(

                                id = it.getString(it.getColumnIndexOrThrow("id")),

                                sessionId = it.getString(it.getColumnIndexOrThrow("sessionId")),

                                pairingSessionId = if (pairIdx >= 0) it.getString(pairIdx).orEmpty() else "",

                                timestampMs = it.getLong(it.getColumnIndexOrThrow("timestampMs")),

                                monotonicTimestampNs = if (monoIdx >= 0) it.getLong(monoIdx) else 0L,

                                elapsedMs = it.getLong(it.getColumnIndexOrThrow("elapsedMs")),

                                eventType = parsedType,

                                transportSource = it.getString(it.getColumnIndexOrThrow("transportSource")),

                                hardwareId = it.getString(it.getColumnIndexOrThrow("hardwareId")),

                                cloudId = if (cloudIdx >= 0) it.getString(cloudIdx).orEmpty() else "",

                                macAddress = it.getString(it.getColumnIndexOrThrow("macAddress")),

                                ipAddress = it.getString(it.getColumnIndexOrThrow("ipAddress")),

                                port = it.getInt(it.getColumnIndexOrThrow("port")),

                                vendor = it.getString(it.getColumnIndexOrThrow("vendor")),

                                confidenceScore = it.getFloat(it.getColumnIndexOrThrow("confidenceScore")),

                                qualificationStage = it.getString(it.getColumnIndexOrThrow("qualificationStage")),

                                outcomeReason = it.getString(it.getColumnIndexOrThrow("outcomeReason")),

                                rawPayload = it.getString(it.getColumnIndexOrThrow("rawPayload")),

                                wifiFrequencyMhz = if (freqIdx >= 0) it.getInt(freqIdx) else 0,

                                rssiDbm = if (rssiIdx >= 0) it.getInt(rssiIdx) else -127,

                                permissionState = if (permIdx >= 0) it.getString(permIdx).orEmpty() else "",

                                durationMs = if (durIdx >= 0) it.getLong(durIdx) else 0L,

                                protocolResultCode = if (codeIdx >= 0) it.getString(codeIdx).orEmpty() else ""

                            )

                        )

                    }

                }

                _events.update { current ->

                    (current + loaded).distinctBy { ev -> ev.id }.take(MAX_EVENTS)

                }

            }

        }

    }



    fun startSession(reason: String = "manual_scan"): String {

        val newId = "flt-${UUID.randomUUID().toString().take(8)}"

        activeSessionId = newId

        sessionStartElapsedMs = safeElapsedRealtimeMs()

        record(

            eventType = DiscoveryEventType.STARTED,

            transportSource = "SYSTEM",

            hardwareId = "-",

            macAddress = "-",

            ipAddress = "-",

            port = 0,

            vendor = "System",

            confidenceScore = 1.0f,

            qualificationStage = "Session Start",

            outcomeReason = "Started discovery flight session ($reason)",

            cloudId = "-",

            protocolResultCode = "SESSION_START"

        )

        return newId

    }



    fun record(

        eventType: DiscoveryEventType,

        transportSource: String,

        hardwareId: String = "",

        macAddress: String = "",

        ipAddress: String = "",

        port: Int = 0,

        vendor: String = "",

        confidenceScore: Float = 0.0f,

        qualificationStage: String = "",

        outcomeReason: String = "",

        rawPayload: String = "",

        cloudId: String = "",

        pairingSessionId: String = activePairingSessionId,

        wifiFrequencyMhz: Int = 0,

        rssiDbm: Int = -127,

        permissionState: String = "",

        durationMs: Long = 0L,

        protocolResultCode: String = ""

    ) {

        val wallNowMs = System.currentTimeMillis()

        val monoNowNs = safeElapsedRealtimeNanos()

        val elapsed = (safeElapsedRealtimeMs() - sessionStartElapsedMs).coerceAtLeast(0L)

        val effectiveFreq = if (wifiFrequencyMhz > 0) wifiFrequencyMhz else RadioEnvironmentProbe.currentWifiFrequencyMhz()

        val effectivePerms = permissionState.ifBlank {

            if (pairingSessionId.isNotBlank() || eventType == DiscoveryEventType.STARTED || eventType == DiscoveryEventType.FAILED) {

                RadioEnvironmentProbe.currentPermissionStateSummary()

            } else ""

        }

        val ev = DiscoveryFlightEvent(

            sessionId = activeSessionId,

            pairingSessionId = pairingSessionId.trim(),

            timestampMs = wallNowMs,

            monotonicTimestampNs = monoNowNs,

            elapsedMs = elapsed,

            eventType = eventType,

            transportSource = transportSource,

            hardwareId = hardwareId,

            cloudId = cloudId,

            macAddress = macAddress,

            ipAddress = ipAddress,

            port = port,

            vendor = vendor,

            confidenceScore = confidenceScore,

            qualificationStage = qualificationStage,

            outcomeReason = DiagnosticsSanitizer.scrub(outcomeReason),

            rawPayload = DiagnosticsSanitizer.scrub(rawPayload),

            wifiFrequencyMhz = effectiveFreq,

            rssiDbm = rssiDbm,

            permissionState = effectivePerms,

            durationMs = durationMs.coerceAtLeast(0L),

            protocolResultCode = DiagnosticsSanitizer.scrub(protocolResultCode)

        )

        _events.update { current -> (listOf(ev) + current).take(MAX_EVENTS) }

        ensureBackgroundWriterStarted()

        writeQueue.trySend(ev)

    }



    fun getCorrelatedDiscoveryEvents(

        canonicalGuid: String,

        macHints: Set<String> = emptySet(),

        idHints: Set<String> = emptySet()

    ): List<DiscoveryFlightEvent> {

        val cleanGuid = canonicalGuid.trim()

        val normalizedMacVariants = buildSet {

            for (m in macHints) {

                val norm = CrossEcosystemIdentityCorrelator.normalizeMac(m)

                if (norm.isNotEmpty()) {

                    add(norm.uppercase(Locale.US))

                    addAll(CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(norm).map { it.uppercase(Locale.US) })

                }

            }

            if (cleanGuid.startsWith("guid::mac::")) {

                val guidMac = CrossEcosystemIdentityCorrelator.normalizeMac(cleanGuid.removePrefix("guid::mac::"))

                if (guidMac.isNotEmpty()) {

                    add(guidMac.uppercase(Locale.US))

                    addAll(CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(guidMac).map { it.uppercase(Locale.US) })

                }

            }

        }

        val normalizedIdHints = buildSet {

            for (raw in idHints) {

                val trimmed = raw.trim().lowercase(Locale.US)

                if (trimmed.length >= 4 && trimmed != "none" && trimmed != "-") {

                    add(trimmed)

                }

            }

            val guidTail = cleanGuid.substringAfterLast("::").trim().lowercase(Locale.US)

            if (guidTail.length >= 4 && !cleanGuid.startsWith("guid::mac::")) {

                add(guidTail)

            }

        }



        fun matchesCandidate(ev: DiscoveryFlightEvent): Boolean {

            if (ev.eventType == DiscoveryEventType.STARTED) return false

            val evMac = CrossEcosystemIdentityCorrelator.normalizeMac(ev.macAddress).uppercase(Locale.US)

            if (evMac.isNotEmpty() && evMac in normalizedMacVariants) return true



            val hwLower = ev.hardwareId.trim().lowercase(Locale.US)

            val cloudLower = ev.cloudId.trim().lowercase(Locale.US)

            if (hwLower.isNotEmpty() && hwLower != "-" && hwLower in normalizedIdHints) return true

            if (cloudLower.isNotEmpty() && cloudLower != "-" && cloudLower in normalizedIdHints) return true



            val resolvedEvGuid = DeviceLifecycleRecorder.resolveCanonicalGuid(

                rawDeviceId = ev.hardwareId.ifBlank { ev.cloudId },

                macAddress = evMac,

                hardwareSerial = ev.hardwareId,

                cloudDeviceId = ev.cloudId

            )

            return resolvedEvGuid == cleanGuid

        }



        val helper = dbHelper

        if (helper != null) {

            runCatching {

                val matched = mutableListOf<DiscoveryFlightEvent>()

                val selection = StringBuilder("eventType != ?")
                val selectionArgs = mutableListOf(DiscoveryEventType.STARTED.name)

                val orClauses = mutableListOf<String>()

                if (normalizedMacVariants.isNotEmpty()) {
                    val placeholders = normalizedMacVariants.joinToString(", ") { "?" }
                    orClauses.add("UPPER(macAddress) IN ($placeholders)")
                    selectionArgs.addAll(normalizedMacVariants)
                }

                if (normalizedIdHints.isNotEmpty()) {
                    val placeholders = normalizedIdHints.joinToString(", ") { "?" }
                    orClauses.add("LOWER(hardwareId) IN ($placeholders)")
                    selectionArgs.addAll(normalizedIdHints)
                    orClauses.add("LOWER(cloudId) IN ($placeholders)")
                    selectionArgs.addAll(normalizedIdHints)
                }

                val cleanGuidLower = cleanGuid.lowercase(Locale.US)
                orClauses.add("LOWER(hardwareId) = ?")
                selectionArgs.add(cleanGuidLower)
                orClauses.add("LOWER(cloudId) = ?")
                selectionArgs.add(cleanGuidLower)
                
                if (cleanGuid.startsWith("guid::mac::")) {
                    val mac = cleanGuid.removePrefix("guid::mac::").replace(":", "").uppercase(Locale.US)
                    orClauses.add("UPPER(REPLACE(macAddress, ':', '')) = ?")
                    selectionArgs.add(mac)
                }

                selection.append(" AND (").append(orClauses.joinToString(" OR ")).append(")")

                helper.readableDatabase.query(
                    TelemetryDatabaseHelper.TABLE_DISCOVERY,
                    null,
                    selection.toString(),
                    selectionArgs.toTypedArray(),
                    null,
                    null,
                    "timestampMs ASC, monotonicTimestampNs ASC"
                ).use { cursor ->
                    val monoIdx = cursor.getColumnIndex("monotonicTimestampNs")
                    val cloudIdx = cursor.getColumnIndex("cloudId")
                    val pairIdx = cursor.getColumnIndex("pairingSessionId")
                    val freqIdx = cursor.getColumnIndex("wifiFrequencyMhz")
                    val rssiIdx = cursor.getColumnIndex("rssiDbm")
                    val permIdx = cursor.getColumnIndex("permissionState")
                    val durIdx = cursor.getColumnIndex("durationMs")
                    val protoIdx = cursor.getColumnIndex("protocolResultCode")
                    
                    while (cursor.moveToNext()) {
                        val rawType = cursor.getString(cursor.getColumnIndexOrThrow("eventType"))
                        val parsedType = runCatching { DiscoveryEventType.valueOf(rawType) }
                            .getOrDefault(DiscoveryEventType.IDENTIFIED)
                        val candidate = DiscoveryFlightEvent(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            sessionId = cursor.getString(cursor.getColumnIndexOrThrow("sessionId")),
                            pairingSessionId = if (pairIdx >= 0) cursor.getString(pairIdx).orEmpty() else "",
                            timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestampMs")),
                            monotonicTimestampNs = if (monoIdx >= 0) cursor.getLong(monoIdx) else 0L,
                            elapsedMs = cursor.getLong(cursor.getColumnIndexOrThrow("elapsedMs")),
                            eventType = parsedType,
                            transportSource = cursor.getString(cursor.getColumnIndexOrThrow("transportSource")),
                            hardwareId = cursor.getString(cursor.getColumnIndexOrThrow("hardwareId")),
                            cloudId = if (cloudIdx >= 0) cursor.getString(cloudIdx).orEmpty() else "",
                            macAddress = cursor.getString(cursor.getColumnIndexOrThrow("macAddress")),
                            ipAddress = cursor.getString(cursor.getColumnIndexOrThrow("ipAddress")),
                            port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
                            vendor = cursor.getString(cursor.getColumnIndexOrThrow("vendor")),
                            confidenceScore = cursor.getFloat(cursor.getColumnIndexOrThrow("confidenceScore")),
                            qualificationStage = cursor.getString(cursor.getColumnIndexOrThrow("qualificationStage")),
                            outcomeReason = cursor.getString(cursor.getColumnIndexOrThrow("outcomeReason")),
                            rawPayload = cursor.getString(cursor.getColumnIndexOrThrow("rawPayload")),
                            wifiFrequencyMhz = if (freqIdx >= 0) cursor.getInt(freqIdx) else 0,
                            rssiDbm = if (rssiIdx >= 0) cursor.getInt(rssiIdx) else -127,
                            permissionState = if (permIdx >= 0) cursor.getString(permIdx).orEmpty() else "",
                            durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L,
                            protocolResultCode = if (protoIdx >= 0) cursor.getString(protoIdx).orEmpty() else ""
                        )

                        if (matchesCandidate(candidate)) {

                            matched.add(candidate)

                        }

                    }

                }

                if (matched.isNotEmpty()) return matched

            }

        }



        return _events.value

            .filter { matchesCandidate(it) }

            .sortedWith(compareBy<DiscoveryFlightEvent> { it.timestampMs }.thenBy { it.monotonicTimestampNs })

    }



    fun clear() {

        while (writeQueue.tryReceive().isSuccess) {

            // Drain pending buffered events

        }

        _events.value = emptyList()

        ioScope.launch {

            runCatching {

                dbHelper?.writableDatabase?.delete(TelemetryDatabaseHelper.TABLE_DISCOVERY, null, null)

            }

        }

    }

}



object DeviceLifecycleRecorder {

    private const val MAX_IN_MEMORY_EVENTS = 5000

    private const val WRITE_BATCH_LIMIT = 64



    private sealed interface LifecycleDbOp {

        data class InsertTransition(

            val event: DeviceLifecycleEvent?,

            val snapshot: DeviceCapabilitySnapshot?

        ) : LifecycleDbOp

        data object ClearAll : LifecycleDbOp

    }



    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sequenceLock = Any()

    private val writeQueue = Channel<LifecycleDbOp>(capacity = Channel.UNLIMITED)

    private val writerStarted = AtomicBoolean(false)



    @Volatile

    private var hydratedFromDisk = false



    @Volatile

    private var dbHelper: TelemetryDatabaseHelper? = null



    private val sequenceCounters = ConcurrentHashMap<String, Long>()

    private val lastKnownStateByGuid = ConcurrentHashMap<String, String>()

    private val guidAliasIndex = ConcurrentHashMap<String, String>()

    private val capabilityVersionCounters = ConcurrentHashMap<String, Int>()

    private val latestCapabilityHashByGuid = ConcurrentHashMap<String, String>()

    private val capabilitySnapshotsByGuid = ConcurrentHashMap<String, MutableList<DeviceCapabilitySnapshot>>()



    private val _events = MutableStateFlow<List<DeviceLifecycleEvent>>(emptyList())

    val events: StateFlow<List<DeviceLifecycleEvent>> = _events.asStateFlow()



    private val _capabilitySnapshots = MutableStateFlow<List<DeviceCapabilitySnapshot>>(emptyList())

    val capabilitySnapshots: StateFlow<List<DeviceCapabilitySnapshot>> = _capabilitySnapshots.asStateFlow()



    fun initialize(context: Context) {

        RadioEnvironmentProbe.attachContext(context)

        dbHelper = TelemetryDatabaseHelper.getInstance(context)

        ensureBackgroundWriterStarted()

        ioScope.launch {

            synchronized(sequenceLock) {

                ensureHydratedLocked()

            }

        }

    }



    fun registerTileAliases(tile: SmartTile): String {

        val canonGuid = tile.canonicalGuid.trim()

            .takeIf { it.isNotBlank() && !it.startsWith("guid::unresolved::") }

            ?: tile.resolvedCanonicalGuid()

        if (canonGuid.isBlank()) return ""

        if (tile.id.isNotBlank()) {

            guidAliasIndex[tile.id.trim().lowercase(Locale.US)] = canonGuid

        }

        val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(tile.macAddress)

        if (cleanMac.isNotBlank()) {

            guidAliasIndex["mac::$cleanMac"] = canonGuid

            guidAliasIndex[cleanMac.lowercase(Locale.US)] = canonGuid

        }

        val sn = tile.serialNumber.trim()

        if (sn.length >= 4 && !sn.contains(".")) {

            guidAliasIndex[sn.lowercase(Locale.US)] = canonGuid

        }

        val ent = tile.entityId.trim()

        if (ent.length >= 4) {

            guidAliasIndex[ent.lowercase(Locale.US)] = canonGuid

        }

        val vin = tile.vehicleVin.trim().uppercase(Locale.US)

        if (vin.length == 17) {

            guidAliasIndex[vin.lowercase(Locale.US)] = canonGuid

        }

        if (tile.title.isNotBlank()) {

            DeviceBlackBoxRecorder.registerDeviceDisplayName(canonGuid, tile.title)

        }

        return canonGuid

    }



    fun aliasesForCanonicalGuid(canonicalGuid: String): Set<String> {

        val clean = canonicalGuid.trim()

        if (clean.isEmpty()) return emptySet()

        return guidAliasIndex.entries

            .filter { it.value == clean }

            .map { it.key.removePrefix("mac::") }

            .toSet()

    }



    fun seedKnownStateIfAbsent(deviceIdOrGuid: String, stateName: String) {

        if (deviceIdOrGuid.isBlank() || stateName.isBlank()) return

        val resolvedGuid = resolveCanonicalGuid(deviceIdOrGuid)

        if (resolvedGuid.isNotBlank()) {

            lastKnownStateByGuid.putIfAbsent(resolvedGuid, stateName.trim())

        }

    }



    fun seedBaselineCapabilitySnapshotIfMissing(tile: SmartTile) {

        if (tile.capabilities.isEmpty()) return

        val canonGuid = registerTileAliases(tile)

        if (canonGuid.isBlank() || canonGuid.startsWith("guid::unresolved::")) return

        val computedHash = tile.capabilitySchemaHash.ifBlank {

            CapabilitySchemaHasher.computeHash(tile.capabilities)

        }

        if (computedHash.isBlank()) return

        val createdSnapshot: DeviceCapabilitySnapshot?

        synchronized(sequenceLock) {

            ensureHydratedLocked()

            if (latestCapabilityHashByGuid[canonGuid] == computedHash) return

            val nextVer = (capabilityVersionCounters[canonGuid] ?: 0) + 1

            capabilityVersionCounters[canonGuid] = nextVer

            latestCapabilityHashByGuid[canonGuid] = computedHash

            val anchorSeq = sequenceCounters[canonGuid] ?: 0L

            val json = CapabilitySnapshotCodec.encodeDescriptors(tile.capabilities)

            val snap = DeviceCapabilitySnapshot(

                deviceId = canonGuid,

                schemaVersion = nextVer,

                lifecycleSequenceNumber = anchorSeq,

                schemaHash = computedHash,

                capabilityCount = tile.capabilities.size,

                capabilitiesJson = json,

                capabilities = CapabilitySnapshotCodec.decodeDescriptors(json)

            )

            capabilitySnapshotsByGuid.getOrPut(canonGuid) { mutableListOf() }.add(snap)

            createdSnapshot = snap

            ensureBackgroundWriterStarted()

            writeQueue.trySend(LifecycleDbOp.InsertTransition(event = null, snapshot = snap))

        }

        if (createdSnapshot != null) {

            _capabilitySnapshots.update { current -> listOf(createdSnapshot) + current }

        }

    }



    fun resolveCanonicalGuid(
        rawDeviceId: String,
        macAddress: String = "",
        hardwareSerial: String = "",
        cloudDeviceId: String = ""
    ): String {
        val trimmed = rawDeviceId.trim()
        if (trimmed.startsWith("guid::") && !trimmed.startsWith("guid::unresolved::")) {
            return trimmed
        }
        if (trimmed.isNotBlank()) {
            guidAliasIndex[trimmed.lowercase(Locale.US)]?.let { return it }
        }
        val cleanMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress)
        if (cleanMac.isNotBlank()) {
            guidAliasIndex["mac::$cleanMac"]?.let { return it }
        }
        val resolved = CrossEcosystemIdentityCorrelator.resolveCanonicalGuid(
            rawDeviceId = trimmed,
            macAddress = cleanMac,
            hardwareSerial = hardwareSerial,
            cloudDeviceId = cloudDeviceId,
            fallbackIdentityKey = trimmed
        )
        if (trimmed.isNotBlank() && resolved != "guid::unknown") {
            guidAliasIndex.putIfAbsent(trimmed.lowercase(Locale.US), resolved)
        }
        return resolved
    }



    private fun ensureHydratedLocked() {

        if (hydratedFromDisk) return

        val helper = dbHelper ?: return

        hydratedFromDisk = true

        runCatching {

            val db = helper.readableDatabase

            // 1. Hydrate exact max sequence number and latest state for EVERY canonical GUID on disk

            db.rawQuery(

                """

                SELECT deviceId, sequenceNumber, newState

                FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE}

                ORDER BY deviceId ASC, sequenceNumber ASC

                """.trimIndent(),

                null

            ).use { seqCursor ->

                while (seqCursor.moveToNext()) {

                    val devGuid = seqCursor.getString(0).orEmpty()

                    val seq = seqCursor.getLong(1)

                    val st = seqCursor.getString(2).orEmpty()

                    if (devGuid.isNotBlank()) {

                        val curMax = sequenceCounters[devGuid] ?: 0L

                        if (seq > curMax) {

                            sequenceCounters[devGuid] = seq

                        }

                        if (st.isNotBlank()) {

                            lastKnownStateByGuid[devGuid] = st

                        }

                    }

                }

            }



            // 2. Hydrate versioned capability snapshots ordered by (deviceId ASC, schemaVersion ASC)

            val hydratedSnaps = mutableListOf<DeviceCapabilitySnapshot>()

            runCatching {

                db.query(

                    TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS,

                    null,

                    null,

                    null,

                    null,

                    null,

                    "deviceId ASC, schemaVersion ASC"

                ).use { snapCursor ->

                    val monoIdx = snapCursor.getColumnIndex("monotonicTimestampNs")

                    while (snapCursor.moveToNext()) {

                        val guid = snapCursor.getString(snapCursor.getColumnIndexOrThrow("deviceId"))

                        val ver = snapCursor.getInt(snapCursor.getColumnIndexOrThrow("schemaVersion"))

                        val hash = snapCursor.getString(snapCursor.getColumnIndexOrThrow("schemaHash")).orEmpty()

                        val rawJson = snapCursor.getString(snapCursor.getColumnIndexOrThrow("capabilitiesJson")).orEmpty()

                        val snap = DeviceCapabilitySnapshot(

                            id = snapCursor.getString(snapCursor.getColumnIndexOrThrow("id")),

                            deviceId = guid,

                            schemaVersion = ver,

                            lifecycleSequenceNumber = snapCursor.getLong(snapCursor.getColumnIndexOrThrow("lifecycleSequenceNumber")),

                            timestampMs = snapCursor.getLong(snapCursor.getColumnIndexOrThrow("timestampMs")),

                            monotonicTimestampNs = if (monoIdx >= 0) snapCursor.getLong(monoIdx) else 0L,

                            schemaHash = hash,

                            capabilityCount = snapCursor.getInt(snapCursor.getColumnIndexOrThrow("capabilityCount")),

                            capabilitiesJson = rawJson,

                            capabilities = CapabilitySnapshotCodec.decodeDescriptors(rawJson)

                        )

                        hydratedSnaps.add(snap)

                        capabilitySnapshotsByGuid.getOrPut(guid) { mutableListOf() }.add(snap)

                        val curMaxVer = capabilityVersionCounters[guid] ?: 0

                        if (ver > curMaxVer) {

                            capabilityVersionCounters[guid] = ver

                            latestCapabilityHashByGuid[guid] = hash

                        }

                    }

                }

            }

            if (hydratedSnaps.isNotEmpty()) {

                _capabilitySnapshots.value = hydratedSnaps.sortedByDescending { it.timestampMs }

            }



            // 3. Load recent lifecycle events for reactive UI display (newest first)

            val loaded = mutableListOf<DeviceLifecycleEvent>()

            db.query(

                TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE,

                null,

                null,

                null,

                null,

                null,

                "timestampMs DESC, sequenceNumber DESC",

                MAX_IN_MEMORY_EVENTS.toString()

            ).use { cursor ->

                val monoIdx = cursor.getColumnIndex("monotonicTimestampNs")

                val pairIdx = cursor.getColumnIndex("pairingSessionId")

                val durIdx = cursor.getColumnIndex("durationMs")

                val freqIdx = cursor.getColumnIndex("wifiFrequencyMhz")

                val rssiIdx = cursor.getColumnIndex("rssiDbm")

                val permIdx = cursor.getColumnIndex("permissionState")

                val capVerIdx = cursor.getColumnIndex("capabilitySchemaVersion")

                val capHashIdx = cursor.getColumnIndex("capabilitySchemaHash")

                val capJsonIdx = cursor.getColumnIndex("capabilitySnapshotJson")

                while (cursor.moveToNext()) {

                    loaded.add(

                        DeviceLifecycleEvent(

                            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),

                            deviceId = cursor.getString(cursor.getColumnIndexOrThrow("deviceId")),

                            sequenceNumber = cursor.getLong(cursor.getColumnIndexOrThrow("sequenceNumber")),

                            pairingSessionId = if (pairIdx >= 0) cursor.getString(pairIdx).orEmpty() else "",

                            timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestampMs")),

                            monotonicTimestampNs = if (monoIdx >= 0) cursor.getLong(monoIdx) else 0L,

                            event = cursor.getString(cursor.getColumnIndexOrThrow("event")),

                            previousState = cursor.getString(cursor.getColumnIndexOrThrow("previousState")).orEmpty(),

                            newState = cursor.getString(cursor.getColumnIndexOrThrow("newState")).orEmpty(),

                            activeTransport = cursor.getString(cursor.getColumnIndexOrThrow("activeTransport")).orEmpty(),

                            vendorDriver = cursor.getString(cursor.getColumnIndexOrThrow("vendorDriver")).orEmpty(),

                            latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("latencyMs")),

                            durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L,

                            protocolResultCode = cursor.getString(cursor.getColumnIndexOrThrow("protocolResultCode")).orEmpty(),

                            failureCategory = cursor.getString(cursor.getColumnIndexOrThrow("failureCategory")).orEmpty(),

                            wifiFrequencyMhz = if (freqIdx >= 0) cursor.getInt(freqIdx) else 0,

                            rssiDbm = if (rssiIdx >= 0) cursor.getInt(rssiIdx) else -127,

                            permissionState = if (permIdx >= 0) cursor.getString(permIdx).orEmpty() else "",

                            capabilitySchemaVersion = if (capVerIdx >= 0) cursor.getInt(capVerIdx) else 0,

                            capabilitySchemaHash = if (capHashIdx >= 0) cursor.getString(capHashIdx).orEmpty() else "",

                            capabilitySnapshotJson = if (capJsonIdx >= 0) cursor.getString(capJsonIdx).orEmpty() else ""

                        )

                    )

                }

            }

            if (loaded.isNotEmpty()) {

                _events.update { current ->

                    (current + loaded).distinctBy { "${it.deviceId}#${it.sequenceNumber}" }.take(MAX_IN_MEMORY_EVENTS)

                }

            }

        }

    }



    private fun ensureBackgroundWriterStarted() {

        if (!writerStarted.compareAndSet(false, true)) return

        ioScope.launch {

            for (firstOp in writeQueue) {

                val helper = dbHelper ?: continue

                val batch = ArrayList<LifecycleDbOp>(WRITE_BATCH_LIMIT)

                batch.add(firstOp)

                while (batch.size < WRITE_BATCH_LIMIT) {

                    val next = writeQueue.tryReceive().getOrNull() ?: break

                    batch.add(next)

                }

                runCatching {

                    val db = helper.writableDatabase

                    db.beginTransactionNonExclusive()

                    try {

                        for (op in batch) {

                            when (op) {

                                is LifecycleDbOp.InsertTransition -> {

                                    op.event?.let { ev ->

                                        val values = ContentValues().apply {

                                            put("id", ev.id)

                                            put("deviceId", ev.deviceId)

                                            put("sequenceNumber", ev.sequenceNumber)

                                            put("pairingSessionId", ev.pairingSessionId)

                                            put("timestampMs", ev.timestampMs)

                                            put("monotonicTimestampNs", ev.monotonicTimestampNs)

                                            put("event", ev.event)

                                            put("previousState", ev.previousState)

                                            put("newState", ev.newState)

                                            put("activeTransport", ev.activeTransport)

                                            put("vendorDriver", ev.vendorDriver)

                                            put("latencyMs", ev.latencyMs)

                                            put("durationMs", ev.durationMs)

                                            put("protocolResultCode", ev.protocolResultCode)

                                            put("failureCategory", ev.failureCategory)

                                            put("wifiFrequencyMhz", ev.wifiFrequencyMhz)

                                            put("rssiDbm", ev.rssiDbm)

                                            put("permissionState", ev.permissionState)

                                            put("capabilitySchemaVersion", ev.capabilitySchemaVersion)

                                            put("capabilitySchemaHash", ev.capabilitySchemaHash)

                                            put("capabilitySnapshotJson", ev.capabilitySnapshotJson)

                                        }

                                        db.insertWithOnConflict(

                                            TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE,

                                            null,

                                            values,

                                            SQLiteDatabase.CONFLICT_REPLACE

                                        )

                                    }

                                    op.snapshot?.let { snap ->

                                        val snapValues = ContentValues().apply {

                                            put("id", snap.id)

                                            put("deviceId", snap.deviceId)

                                            put("schemaVersion", snap.schemaVersion)

                                            put("lifecycleSequenceNumber", snap.lifecycleSequenceNumber)

                                            put("timestampMs", snap.timestampMs)

                                            put("monotonicTimestampNs", snap.monotonicTimestampNs)

                                            put("schemaHash", snap.schemaHash)

                                            put("capabilityCount", snap.capabilityCount)

                                            put("capabilitiesJson", snap.capabilitiesJson)

                                        }

                                        db.insertWithOnConflict(
                                            TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS,
                                            null,
                                            snapValues,
                                            SQLiteDatabase.CONFLICT_REPLACE
                                        )
                                    }
                                }

                                is LifecycleDbOp.ClearAll -> {
                                    db.delete(TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE, null, null)
                                    db.delete(TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS, null, null)
                                }
                            }
                        }
                        
                        val modifiedDevices = batch.mapNotNull { 
                            when (it) {
                                is LifecycleDbOp.InsertTransition -> it.event?.deviceId ?: it.snapshot?.deviceId
                                else -> null
                            }
                        }.toSet()
                        
                        for (deviceId in modifiedDevices) {
                            db.execSQL("""
                                DELETE FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE}
                                WHERE deviceId = ?
                                AND sequenceNumber > 15
                                AND id NOT IN (
                                    SELECT id FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE}
                                    WHERE deviceId = ? AND sequenceNumber > 15
                                    ORDER BY timestampMs DESC
                                    LIMIT 500
                                )
                            """.trimIndent(), arrayOf(deviceId, deviceId))
                            
                            db.execSQL("""
                                DELETE FROM ${TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS}
                                WHERE deviceId = ?
                                AND id NOT IN (
                                    SELECT id FROM ${TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS}
                                    WHERE deviceId = ?
                                    ORDER BY timestampMs DESC
                                    LIMIT 10
                                )
                            """.trimIndent(), arrayOf(deviceId, deviceId))
                        }

                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }

                }

            }

        }

    }



    fun recordTileTransition(

        tile: SmartTile,

        transition: MandatoryLifecycleTransition,

        previousState: String = "",

        newState: String = "",

        activeTransport: String = "",

        vendorDriver: String = "",

        latencyMs: Long = 0L,

        protocolResultCode: String = "OK",

        failureCategory: FailureCategory? = null,

        pairingSessionId: String = DiscoveryFlightRecorder.currentPairingSessionId(),

        durationMs: Long = latencyMs,

        wifiFrequencyMhz: Int = 0,

        rssiDbm: Int = -127,

        permissionState: String = ""

    ): Long {

        val canonGuid = registerTileAliases(tile)

        val fallbackPrev = previousState.ifBlank { tile.effectiveLifecycleState.name }

        val resolvedTarget = newState.ifBlank {

            transition.defaultTargetState?.name ?: tile.effectiveLifecycleState.name

        }

        val computedCapHash = if (tile.capabilities.isNotEmpty()) {

            tile.capabilitySchemaHash.ifBlank {

                CapabilitySchemaHasher.computeHash(tile.capabilities)

            }

        } else ""

        return recordTransition(

            deviceId = canonGuid,

            event = transition.eventName,

            previousState = fallbackPrev,

            newState = resolvedTarget,

            activeTransport = activeTransport.ifBlank { tile.transportForRecorder },

            vendorDriver = vendorDriver.ifBlank { tile.vendorDriverForRecorder },

            latencyMs = latencyMs,

            protocolResultCode = protocolResultCode,

            failureCategory = failureCategory?.name.orEmpty(),

            capabilities = tile.capabilities,

            capabilitySchemaHash = computedCapHash,

            pairingSessionId = pairingSessionId,

            durationMs = durationMs,

            wifiFrequencyMhz = wifiFrequencyMhz,

            rssiDbm = rssiDbm,

            permissionState = permissionState

        )

    }



    fun recordTransition(

        deviceId: String,

        event: String,

        previousState: String = "",

        newState: String = "",

        activeTransport: String = "",

        vendorDriver: String = "",

        latencyMs: Long = 0L,

        protocolResultCode: String = "",

        failureCategory: String = "",

        macAddress: String = "",

        hardwareSerial: String = "",

        cloudDeviceId: String = "",

        capabilities: List<DeviceCapabilityDescriptor> = emptyList(),

        capabilitySchemaHash: String = "",

        pairingSessionId: String = DiscoveryFlightRecorder.currentPairingSessionId(),

        durationMs: Long = latencyMs,

        wifiFrequencyMhz: Int = 0,

        rssiDbm: Int = -127,

        permissionState: String = ""

    ): Long {

        val canonicalGuid = resolveCanonicalGuid(

            rawDeviceId = deviceId,

            macAddress = macAddress,

            hardwareSerial = hardwareSerial,

            cloudDeviceId = cloudDeviceId

        )

        val matchedMandatory = MandatoryLifecycleTransition.fromRawEvent(event)

        val standardizedEventName = matchedMandatory?.eventName ?: event.trim().ifBlank { "State Transition" }



        val resolvedTransport = activeTransport.trim().ifBlank { "LOCAL_LAN" }

        val resolvedDriver = vendorDriver.trim().ifBlank {

            ProviderId.fromRawKey(resolvedTransport).takeIf { it != ProviderId.UNKNOWN }?.displayName

                ?: resolvedTransport

        }

        val cleanFailureCat = failureCategory.trim().let { raw ->

            if (raw.isBlank()) {

                ""

            } else {

                FailureCategory.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.name

                    ?: ErrorClassifier.classifyException(Exception(raw)).name

            }

        }

        val resolvedResultCode = protocolResultCode.trim().ifBlank {

            if (cleanFailureCat.isNotBlank()) "ERROR" else "OK"

        }



        val effectiveHash = if (capabilities.isNotEmpty()) {

            capabilitySchemaHash.trim().ifBlank {

                CapabilitySchemaHasher.computeHash(capabilities)

            }

        } else ""



        val cleanPairingSession = pairingSessionId.trim()

        val effectiveFreq = if (wifiFrequencyMhz > 0) {

            wifiFrequencyMhz

        } else if (cleanPairingSession.isNotBlank()) {

            RadioEnvironmentProbe.currentWifiFrequencyMhz()

        } else 0

        val effectivePerms = permissionState.trim().ifBlank {

            if (cleanPairingSession.isNotBlank() || cleanFailureCat.isNotBlank()) {

                RadioEnvironmentProbe.currentPermissionStateSummary()

            } else ""

        }



        val ev: DeviceLifecycleEvent

        var newSnapshot: DeviceCapabilitySnapshot? = null

        synchronized(sequenceLock) {

            ensureHydratedLocked()

            val prevRecordedState = lastKnownStateByGuid[canonicalGuid].orEmpty()

            val effectivePrevState = previousState.trim()

                .ifBlank { prevRecordedState }

                .ifBlank { "NONE" }

            val effectiveNewState = newState.trim()

                .ifBlank { matchedMandatory?.defaultTargetState?.name.orEmpty() }

                .ifBlank { effectivePrevState }



            val nextSeq = (sequenceCounters[canonicalGuid] ?: 0L) + 1L

            sequenceCounters[canonicalGuid] = nextSeq

            lastKnownStateByGuid[canonicalGuid] = effectiveNewState



            val wallNowMs = System.currentTimeMillis()

            val monoNowNs = safeElapsedRealtimeNanos()



            if (matchedMandatory == MandatoryLifecycleTransition.UNBOUND) {

                latestCapabilityHashByGuid.remove(canonicalGuid)

            } else if (capabilities.isNotEmpty() && effectiveHash.isNotBlank()) {

                val prevHash = latestCapabilityHashByGuid[canonicalGuid].orEmpty()

                if (effectiveHash != prevHash) {

                    val nextSchemaVer = (capabilityVersionCounters[canonicalGuid] ?: 0) + 1

                    capabilityVersionCounters[canonicalGuid] = nextSchemaVer

                    latestCapabilityHashByGuid[canonicalGuid] = effectiveHash

                    val encodedJson = CapabilitySnapshotCodec.encodeDescriptors(capabilities)

                    val snap = DeviceCapabilitySnapshot(

                        deviceId = canonicalGuid,

                        schemaVersion = nextSchemaVer,

                        lifecycleSequenceNumber = nextSeq,

                        timestampMs = wallNowMs,

                        monotonicTimestampNs = monoNowNs,

                        schemaHash = effectiveHash,

                        capabilityCount = capabilities.size,

                        capabilitiesJson = encodedJson,

                        capabilities = CapabilitySnapshotCodec.decodeDescriptors(encodedJson)

                    )

                    capabilitySnapshotsByGuid.getOrPut(canonicalGuid) { mutableListOf() }.add(snap)

                    newSnapshot = snap

                }

            }



            val activeSchemaVer = newSnapshot?.schemaVersion ?: (capabilityVersionCounters[canonicalGuid] ?: 0)

            val activeSchemaHash = newSnapshot?.schemaHash ?: latestCapabilityHashByGuid[canonicalGuid].orEmpty()



            ev = DeviceLifecycleEvent(

                deviceId = canonicalGuid,

                sequenceNumber = nextSeq,

                pairingSessionId = cleanPairingSession,

                timestampMs = wallNowMs,

                monotonicTimestampNs = monoNowNs,

                event = standardizedEventName,

                previousState = effectivePrevState,

                newState = effectiveNewState,

                activeTransport = resolvedTransport,

                vendorDriver = DiagnosticsSanitizer.scrub(resolvedDriver),

                latencyMs = latencyMs.coerceAtLeast(0L),

                durationMs = durationMs.coerceAtLeast(0L),

                protocolResultCode = DiagnosticsSanitizer.scrub(resolvedResultCode),

                failureCategory = cleanFailureCat,

                wifiFrequencyMhz = effectiveFreq,

                rssiDbm = rssiDbm,

                permissionState = effectivePerms,

                capabilitySchemaVersion = activeSchemaVer,

                capabilitySchemaHash = activeSchemaHash,

                capabilitySnapshotJson = newSnapshot?.capabilitiesJson.orEmpty()

            )

            ensureBackgroundWriterStarted()

            writeQueue.trySend(LifecycleDbOp.InsertTransition(event = ev, snapshot = newSnapshot))

        }



        _events.update { current -> (listOf(ev) + current).take(MAX_IN_MEMORY_EVENTS) }

        newSnapshot?.let { snap ->

            _capabilitySnapshots.update { current -> listOf(snap) + current }

        }



        if (matchedMandatory == MandatoryLifecycleTransition.UNBOUND) {

            DeviceBlackBoxRecorder.onDeviceFormallyUnbound(canonicalGuid)

        } else if (matchedMandatory != null &&

            matchedMandatory !in setOf(

                MandatoryLifecycleTransition.COMMAND_SENT,

                MandatoryLifecycleTransition.COMMAND_ACK,

                MandatoryLifecycleTransition.COMMAND_FAILED

            )

        ) {

            DeviceBlackBoxRecorder.recordLifecycleForensicEvent(

                lifecycleEvent = ev,

                transition = matchedMandatory

            )

        } else if (matchedMandatory == null && cleanPairingSession.isNotBlank()) {

            DeviceBlackBoxRecorder.recordCustomWizardStageForensicEvent(ev)

        }

        return ev.sequenceNumber

    }



    fun getDeviceHistoryOrdered(deviceIdOrGuid: String): List<DeviceLifecycleEvent> {

        val canonicalGuid = resolveCanonicalGuid(deviceIdOrGuid)

        val helper = dbHelper

        if (helper != null) {

            runCatching {

                val list = mutableListOf<DeviceLifecycleEvent>()

                helper.readableDatabase.query(

                    TelemetryDatabaseHelper.TABLE_DEVICE_LIFECYCLE,

                    null,

                    "deviceId = ?",

                    arrayOf(canonicalGuid),

                    null,

                    null,

                    "sequenceNumber ASC"

                ).use { cursor ->

                    val monoIdx = cursor.getColumnIndex("monotonicTimestampNs")

                    val pairIdx = cursor.getColumnIndex("pairingSessionId")

                    val durIdx = cursor.getColumnIndex("durationMs")

                    val freqIdx = cursor.getColumnIndex("wifiFrequencyMhz")

                    val rssiIdx = cursor.getColumnIndex("rssiDbm")

                    val permIdx = cursor.getColumnIndex("permissionState")

                    val capVerIdx = cursor.getColumnIndex("capabilitySchemaVersion")

                    val capHashIdx = cursor.getColumnIndex("capabilitySchemaHash")

                    val capJsonIdx = cursor.getColumnIndex("capabilitySnapshotJson")

                    while (cursor.moveToNext()) {

                        list.add(

                            DeviceLifecycleEvent(

                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),

                                deviceId = cursor.getString(cursor.getColumnIndexOrThrow("deviceId")),

                                sequenceNumber = cursor.getLong(cursor.getColumnIndexOrThrow("sequenceNumber")),

                                pairingSessionId = if (pairIdx >= 0) cursor.getString(pairIdx).orEmpty() else "",

                                timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestampMs")),

                                monotonicTimestampNs = if (monoIdx >= 0) cursor.getLong(monoIdx) else 0L,

                                event = cursor.getString(cursor.getColumnIndexOrThrow("event")),

                                previousState = cursor.getString(cursor.getColumnIndexOrThrow("previousState")).orEmpty(),

                                newState = cursor.getString(cursor.getColumnIndexOrThrow("newState")).orEmpty(),

                                activeTransport = cursor.getString(cursor.getColumnIndexOrThrow("activeTransport")).orEmpty(),

                                vendorDriver = cursor.getString(cursor.getColumnIndexOrThrow("vendorDriver")).orEmpty(),

                                latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("latencyMs")),

                                durationMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L,

                                protocolResultCode = cursor.getString(cursor.getColumnIndexOrThrow("protocolResultCode")).orEmpty(),

                                failureCategory = cursor.getString(cursor.getColumnIndexOrThrow("failureCategory")).orEmpty(),

                                wifiFrequencyMhz = if (freqIdx >= 0) cursor.getInt(freqIdx) else 0,

                                rssiDbm = if (rssiIdx >= 0) cursor.getInt(rssiIdx) else -127,

                                permissionState = if (permIdx >= 0) cursor.getString(permIdx).orEmpty() else "",

                                capabilitySchemaVersion = if (capVerIdx >= 0) cursor.getInt(capVerIdx) else 0,

                                capabilitySchemaHash = if (capHashIdx >= 0) cursor.getString(capHashIdx).orEmpty() else "",

                                capabilitySnapshotJson = if (capJsonIdx >= 0) cursor.getString(capJsonIdx).orEmpty() else ""

                            )

                        )

                    }

                }

                if (list.isNotEmpty()) return list

            }

        }

        return _events.value

            .filter { it.deviceId == canonicalGuid }

            .sortedBy { it.sequenceNumber }

    }



    fun getCapabilitySnapshotsForDevice(deviceIdOrGuid: String): List<DeviceCapabilitySnapshot> {

        val canonicalGuid = resolveCanonicalGuid(deviceIdOrGuid)

        val helper = dbHelper

        if (helper != null) {

            runCatching {

                val list = mutableListOf<DeviceCapabilitySnapshot>()

                helper.readableDatabase.query(

                    TelemetryDatabaseHelper.TABLE_CAPABILITY_SNAPSHOTS,

                    null,

                    "deviceId = ?",

                    arrayOf(canonicalGuid),

                    null,

                    null,

                    "schemaVersion ASC"

                ).use { cursor ->

                    val monoIdx = cursor.getColumnIndex("monotonicTimestampNs")

                    while (cursor.moveToNext()) {

                        val rawJson = cursor.getString(cursor.getColumnIndexOrThrow("capabilitiesJson")).orEmpty()

                        list.add(

                            DeviceCapabilitySnapshot(

                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),

                                deviceId = cursor.getString(cursor.getColumnIndexOrThrow("deviceId")),

                                schemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("schemaVersion")),

                                lifecycleSequenceNumber = cursor.getLong(cursor.getColumnIndexOrThrow("lifecycleSequenceNumber")),

                                timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestampMs")),

                                monotonicTimestampNs = if (monoIdx >= 0) cursor.getLong(monoIdx) else 0L,

                                schemaHash = cursor.getString(cursor.getColumnIndexOrThrow("schemaHash")).orEmpty(),

                                capabilityCount = cursor.getInt(cursor.getColumnIndexOrThrow("capabilityCount")),

                                capabilitiesJson = rawJson,

                                capabilities = CapabilitySnapshotCodec.decodeDescriptors(rawJson)

                            )

                        )

                    }

                }

                if (list.isNotEmpty()) return list

            }

        }

        return synchronized(sequenceLock) {

            ensureHydratedLocked()

            capabilitySnapshotsByGuid[canonicalGuid]?.sortedBy { it.schemaVersion }.orEmpty()

        }

    }



    fun getAllRecordedDeviceGuids(): List<String> {

        synchronized(sequenceLock) {

            ensureHydratedLocked()

            return sequenceCounters.keys

                .filter { it.isNotBlank() && it != "guid::unknown" && !it.startsWith("guid::selftest::") }

                .sorted()

        }

    }



    fun hasContiguousSequenceFromOne(eventsForDevice: List<DeviceLifecycleEvent>): Boolean {

        if (eventsForDevice.isEmpty()) return true

        val sorted = eventsForDevice.sortedBy { it.sequenceNumber }

        for (idx in sorted.indices) {

            if (sorted[idx].sequenceNumber != (idx + 1).toLong()) return false

        }

        return true

    }



    fun clear() {

        synchronized(sequenceLock) {

            while (writeQueue.tryReceive().isSuccess) {

                // Drain pending writes

            }

            sequenceCounters.clear()

            lastKnownStateByGuid.clear()

            capabilityVersionCounters.clear()

            latestCapabilityHashByGuid.clear()

            capabilitySnapshotsByGuid.clear()

            _events.value = emptyList()

            _capabilitySnapshots.value = emptyList()

            ensureBackgroundWriterStarted()

            writeQueue.trySend(LifecycleDbOp.ClearAll)

        }

    }

}



/**

 * Recorder 2 (Change 1.4): Partitioned Per-Device Black Box Ring Buffer.

 * Isolates forensic retention by canonical device GUID across nine typed domains,

 * permanently pins the first 25 onboarding/auth/activation/capability-learning records

 * per device until formal unbind, and retains the newest 1,000 runtime events per device.

 */

object DeviceBlackBoxRecorder {

    const val MAX_RUNTIME_EVENTS_PER_DEVICE = 1000

    const val MAX_PINNED_EVENTS_PER_DEVICE = 25

    private const val WRITE_BATCH_LIMIT = 128

    private const val BOUNCE_WINDOW_MS = 4_500L



    private data class PendingExpectation(

        val action: String,

        val expectedState: String,

        val timestampMs: Long

    )



    private class DevicePartition(val canonicalGuid: String) {

        var displayName: String = canonicalGuid.removePrefix("guid::")

        val pinnedSetupEvents = ArrayList<DeviceTraceEvent>(MAX_PINNED_EVENTS_PER_DEVICE)

        val runtimeRingBuffer = ArrayDeque<DeviceTraceEvent>(MAX_RUNTIME_EVENTS_PER_DEVICE)



        fun snapshotNewestFirst(): List<DeviceTraceEvent> {

            val combined = ArrayList<DeviceTraceEvent>(pinnedSetupEvents.size + runtimeRingBuffer.size)

            combined.addAll(runtimeRingBuffer)

            combined.addAll(pinnedSetupEvents)

            combined.sortWith(

                compareByDescending<DeviceTraceEvent> { it.timestampMs }

                    .thenByDescending { it.monotonicTimestampNs }

            )

            return combined

        }

    }



    private sealed interface BlackBoxDbOp {

        data class InsertAndPrune(val event: DeviceTraceEvent) : BlackBoxDbOp

        data class DeletePartition(val canonicalGuid: String) : BlackBoxDbOp

        data object ClearAll : BlackBoxDbOp

        class FlushBarrier(val ack: kotlinx.coroutines.CompletableDeferred<Unit>) : BlackBoxDbOp

    }



    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val partitionLock = Any()

    private val writeQueue = Channel<BlackBoxDbOp>(capacity = Channel.UNLIMITED)

    private val writerStarted = AtomicBoolean(false)



    @Volatile

    private var hydratedFromDisk = false



    @Volatile

    private var dbHelper: TelemetryDatabaseHelper? = null



    private val partitions = ConcurrentHashMap<String, DevicePartition>()

    private val displayNamesByGuid = ConcurrentHashMap<String, String>()

    private val recentExpectations = ConcurrentHashMap<String, PendingExpectation>()

    private val lastKnownIps = ConcurrentHashMap<String, String>()



    private val _events = MutableStateFlow<List<DeviceTraceEvent>>(emptyList())

    val events: StateFlow<List<DeviceTraceEvent>> = _events.asStateFlow()



    fun initialize(context: Context) {

        RadioEnvironmentProbe.attachContext(context)

        dbHelper = TelemetryDatabaseHelper.getInstance(context)

        ensureBackgroundWriterStarted()

        ioScope.launch {

            synchronized(partitionLock) {

                ensureHydratedLocked()

                publishAllPartitionsLocked()

            }

        }

    }



    fun resolvePartitionGuid(rawDeviceId: String): String =

        DeviceLifecycleRecorder.resolveCanonicalGuid(rawDeviceId)



    fun registerDeviceDisplayName(deviceIdOrGuid: String, displayName: String) {

        val cleanName = displayName.trim()

        if (cleanName.isBlank() || cleanName.startsWith("guid::")) return

        val guid = resolvePartitionGuid(deviceIdOrGuid)

        displayNamesByGuid[guid] = cleanName

        synchronized(partitionLock) {

            partitions[guid]?.displayName = cleanName

        }

    }



    fun resolveDisplayName(canonicalGuid: String, fallbackName: String = ""): String {

        val cleanFallback = fallbackName.trim()

        if (cleanFallback.isNotBlank() &&

            !cleanFallback.startsWith("guid::") &&

            !cleanFallback.equals("Device", ignoreCase = true)

        ) {

            displayNamesByGuid[canonicalGuid] = cleanFallback

            return cleanFallback

        }

        return displayNamesByGuid[canonicalGuid]

            ?: cleanFallback.ifBlank { canonicalGuid.removePrefix("guid::") }

    }



    private fun ensureHydratedLocked() {

        if (hydratedFromDisk) return

        val helper = dbHelper ?: return

        hydratedFromDisk = true

        runCatching {

            val db = helper.readableDatabase

            // Hydrate oldest-to-newest so pinned setup slots (first 25) and runtime deques (newest 1,000) rebuild identically

            db.query(

                TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX,

                null,

                null,

                null,

                null,

                null,

                "timestampMs ASC, monotonicTimestampNs ASC"

            ).use { cursor ->

                val monoIdx = cursor.getColumnIndex("monotonicTimestampNs")

                val pairIdx = cursor.getColumnIndex("pairingSessionId")

                val freqIdx = cursor.getColumnIndex("wifiFrequencyMhz")

                val rssiIdx = cursor.getColumnIndex("rssiDbm")

                val permIdx = cursor.getColumnIndex("permissionState")

                val codeIdx = cursor.getColumnIndex("protocolResultCode")

                val domainIdx = cursor.getColumnIndex("domain")

                val pinnedIdx = cursor.getColumnIndex("isPinned")

                while (cursor.moveToNext()) {

                    val rawGuid = cursor.getString(cursor.getColumnIndexOrThrow("deviceId"))

                    val guid = resolvePartitionGuid(rawGuid)

                    val devName = cursor.getString(cursor.getColumnIndexOrThrow("deviceName")).orEmpty()

                    val catRaw = cursor.getString(cursor.getColumnIndexOrThrow("category")).orEmpty()

                    val cat = runCatching { DeviceTraceCategory.valueOf(catRaw) }

                        .getOrDefault(DeviceTraceCategory.COMMAND_TX)

                    val outRaw = cursor.getString(cursor.getColumnIndexOrThrow("outcome")).orEmpty()

                    val outcome = runCatching { TraceOutcome.valueOf(outRaw) }

                        .getOrDefault(TraceOutcome.SUCCESS)

                    val act = cursor.getString(cursor.getColumnIndexOrThrow("action")).orEmpty()

                    val domRaw = if (domainIdx >= 0) cursor.getString(domainIdx) else null

                    val domain = BlackBoxDomain.fromRawOrInfer(domRaw, cat, outcome, act)

                    val pinnedFlag = if (pinnedIdx >= 0) cursor.getInt(pinnedIdx) == 1 else false



                    val resolvedName = resolveDisplayName(guid, devName)

                    val ev = DeviceTraceEvent(

                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),

                        pairingSessionId = if (pairIdx >= 0) cursor.getString(pairIdx).orEmpty() else "",

                        timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestampMs")),

                        monotonicTimestampNs = if (monoIdx >= 0) cursor.getLong(monoIdx) else 0L,

                        deviceId = guid,

                        deviceName = resolvedName,

                        provider = cursor.getString(cursor.getColumnIndexOrThrow("provider")).orEmpty(),

                        endpointIp = cursor.getString(cursor.getColumnIndexOrThrow("endpointIp")).orEmpty(),

                        category = cat,

                        action = act,

                        outcome = outcome,

                        latencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("latencyMs")),

                        expectedState = cursor.getString(cursor.getColumnIndexOrThrow("expectedState")).orEmpty(),

                        actualState = cursor.getString(cursor.getColumnIndexOrThrow("actualState")).orEmpty(),

                        errorSummary = cursor.getString(cursor.getColumnIndexOrThrow("errorSummary")).orEmpty(),

                        rawPayload = cursor.getString(cursor.getColumnIndexOrThrow("rawPayload")).orEmpty(),

                        wifiFrequencyMhz = if (freqIdx >= 0) cursor.getInt(freqIdx) else 0,

                        rssiDbm = if (rssiIdx >= 0) cursor.getInt(rssiIdx) else -127,

                        permissionState = if (permIdx >= 0) cursor.getString(permIdx).orEmpty() else "",

                        protocolResultCode = if (codeIdx >= 0) cursor.getString(codeIdx).orEmpty() else "",

                        domain = domain,

                        isPinned = pinnedFlag

                    )



                    val part = partitions.getOrPut(guid) { DevicePartition(guid) }

                    part.displayName = resolvedName

                    if (ev.isPinned && part.pinnedSetupEvents.size < MAX_PINNED_EVENTS_PER_DEVICE) {

                        part.pinnedSetupEvents.add(ev)

                    } else {

                        val runtimeCopy = if (ev.isPinned) ev.copy(isPinned = false) else ev

                        part.runtimeRingBuffer.addFirst(runtimeCopy)

                        while (part.runtimeRingBuffer.size > MAX_RUNTIME_EVENTS_PER_DEVICE) {

                            part.runtimeRingBuffer.removeLast()

                        }

                    }

                }

            }

        }

    }



    private fun publishAllPartitionsLocked() {

        val totalSize = partitions.values.sumOf { it.pinnedSetupEvents.size + it.runtimeRingBuffer.size }

        if (totalSize == 0) {

            _events.value = emptyList()

            return

        }

        val merged = ArrayList<DeviceTraceEvent>(totalSize)

        for (part in partitions.values) {

            val friendlyName = displayNamesByGuid[part.canonicalGuid]

            if (!friendlyName.isNullOrBlank()) {

                part.displayName = friendlyName

            }

            merged.addAll(part.runtimeRingBuffer)

            merged.addAll(part.pinnedSetupEvents)

        }

        merged.sortWith(

            compareByDescending<DeviceTraceEvent> { it.timestampMs }

                .thenByDescending { it.monotonicTimestampNs }

        )

        _events.value = merged

    }



    private fun ensureBackgroundWriterStarted() {

        if (!writerStarted.compareAndSet(false, true)) return

        ioScope.launch {

            for (firstOp in writeQueue) {

                val batch = ArrayList<BlackBoxDbOp>(WRITE_BATCH_LIMIT)

                batch.add(firstOp)

                while (batch.size < WRITE_BATCH_LIMIT) {

                    val next = writeQueue.tryReceive().getOrNull() ?: break

                    batch.add(next)

                }



                val helper = dbHelper

                if (helper != null) {

                    runCatching {

                        val db = helper.writableDatabase

                        val touchedRuntimeGuids = LinkedHashSet<String>()

                        db.beginTransactionNonExclusive()

                        try {

                            for (op in batch) {

                                when (op) {

                                    is BlackBoxDbOp.InsertAndPrune -> {

                                        val ev = op.event

                                        val values = ContentValues().apply {

                                            put("id", ev.id)

                                            put("pairingSessionId", ev.pairingSessionId)

                                            put("timestampMs", ev.timestampMs)

                                            put("monotonicTimestampNs", ev.monotonicTimestampNs)

                                            put("deviceId", ev.deviceId)

                                            put("deviceName", ev.deviceName)

                                            put("provider", ev.provider)

                                            put("endpointIp", ev.endpointIp)

                                            put("domain", ev.domain.name)

                                            put("category", ev.category.name)

                                            put("action", ev.action)

                                            put("outcome", ev.outcome.name)

                                            put("latencyMs", ev.latencyMs)

                                            put("expectedState", ev.expectedState)

                                            put("actualState", ev.actualState)

                                            put("errorSummary", ev.errorSummary)

                                            put("rawPayload", ev.rawPayload)

                                            put("wifiFrequencyMhz", ev.wifiFrequencyMhz)

                                            put("rssiDbm", ev.rssiDbm)

                                            put("permissionState", ev.permissionState)

                                            put("protocolResultCode", ev.protocolResultCode)

                                            put("isPinned", if (ev.isPinned) 1 else 0)

                                        }

                                        db.insertWithOnConflict(

                                            TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX,

                                            null,

                                            values,

                                            SQLiteDatabase.CONFLICT_REPLACE

                                        )

                                        if (!ev.isPinned) {

                                            touchedRuntimeGuids.add(ev.deviceId)

                                        }

                                    }



                                    is BlackBoxDbOp.DeletePartition -> {

                                        touchedRuntimeGuids.remove(op.canonicalGuid)

                                        db.delete(

                                            TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX,

                                            "deviceId = ?",

                                            arrayOf(op.canonicalGuid)

                                        )

                                    }



                                    is BlackBoxDbOp.ClearAll -> {

                                        touchedRuntimeGuids.clear()

                                        db.delete(TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX, null, null)

                                    }



                                    is BlackBoxDbOp.FlushBarrier -> Unit

                                }

                            }



                            // Per-device pruning: retain newest 1,000 unpinned runtime events per canonical GUID

                            for (guid in touchedRuntimeGuids) {

                                db.execSQL(

                                    """

                                    DELETE FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX}

                                    WHERE deviceId = ?

                                      AND isPinned = 0

                                      AND id NOT IN (

                                          SELECT id FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX}

                                          WHERE deviceId = ? AND isPinned = 0

                                          ORDER BY timestampMs DESC, monotonicTimestampNs DESC

                                          LIMIT $MAX_RUNTIME_EVENTS_PER_DEVICE

                                      )

                                    """.trimIndent(),

                                    arrayOf(guid, guid)

                                )

                            }

                            db.setTransactionSuccessful()

                        } finally {

                            db.endTransaction()

                        }

                    }

                }



                for (op in batch) {

                    if (op is BlackBoxDbOp.FlushBarrier) {

                        op.ack.complete(Unit)

                    }

                }

            }

        }

    }



    suspend fun flushPendingWrites() {

        ensureBackgroundWriterStarted()

        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()

        writeQueue.trySend(BlackBoxDbOp.FlushBarrier(barrier))

        barrier.await()

    }



    /**

     * Releases all pinned onboarding forensics and runtime black-box records for [deviceIdOrGuid]

     * strictly when the device is formally unbound.

     */

    fun onDeviceFormallyUnbound(deviceIdOrGuid: String) {

        val guid = resolvePartitionGuid(deviceIdOrGuid)

        recentExpectations.remove(guid)

        lastKnownIps.remove(guid)

        synchronized(partitionLock) {

            ensureHydratedLocked()

            partitions.remove(guid)

            publishAllPartitionsLocked()

            ensureBackgroundWriterStarted()

            writeQueue.trySend(BlackBoxDbOp.DeletePartition(guid))

        }

    }



    /**

     * Records lifecycle onboarding, authentication, activation, capability-learning, connectivity,

     * and recovery transitions into the device's isolated Black Box partition.

     */

    internal fun recordLifecycleForensicEvent(

        lifecycleEvent: DeviceLifecycleEvent,

        transition: MandatoryLifecycleTransition

    ) {

        val mappedDomain = when (transition) {

            MandatoryLifecycleTransition.DISCOVERED,

            MandatoryLifecycleTransition.VALIDATED,

            MandatoryLifecycleTransition.REDISCOVERED -> BlackBoxDomain.DISCOVERY

            MandatoryLifecycleTransition.PAIRING_STARTED,

            MandatoryLifecycleTransition.PAIRING_COMPLETED,

            MandatoryLifecycleTransition.UNPAIRED,

            MandatoryLifecycleTransition.UNBOUND -> BlackBoxDomain.PAIRING

            MandatoryLifecycleTransition.AUTH_SUCCESS,

            MandatoryLifecycleTransition.AUTH_FAILED -> BlackBoxDomain.AUTH

            MandatoryLifecycleTransition.ACTIVATION_STARTED,

            MandatoryLifecycleTransition.ACTIVATION_COMPLETED -> BlackBoxDomain.ACTIVATION

            MandatoryLifecycleTransition.CAPABILITY_REQUESTED,

            MandatoryLifecycleTransition.CAPABILITY_RECEIVED -> BlackBoxDomain.CAPABILITY

            MandatoryLifecycleTransition.ONLINE,

            MandatoryLifecycleTransition.OFFLINE -> BlackBoxDomain.CONNECTIVITY

            MandatoryLifecycleTransition.RECOVERY_STARTED,

            MandatoryLifecycleTransition.RECOVERY_COMPLETED -> BlackBoxDomain.RECOVERY

            MandatoryLifecycleTransition.RECOVERY_FAILED,

            MandatoryLifecycleTransition.COMMAND_FAILED -> BlackBoxDomain.FAILURE

            MandatoryLifecycleTransition.COMMAND_SENT,

            MandatoryLifecycleTransition.COMMAND_ACK -> BlackBoxDomain.COMMAND

        }

        val mappedCategory = when (mappedDomain) {

            BlackBoxDomain.AUTH -> DeviceTraceCategory.AUTH_KEY

            BlackBoxDomain.CONNECTIVITY -> DeviceTraceCategory.REACHABILITY

            else -> DeviceTraceCategory.LIFECYCLE_STEP

        }

        val isFailed = lifecycleEvent.failureCategory.isNotBlank() ||

                transition in setOf(

            MandatoryLifecycleTransition.AUTH_FAILED,

            MandatoryLifecycleTransition.OFFLINE,

            MandatoryLifecycleTransition.RECOVERY_FAILED,

            MandatoryLifecycleTransition.COMMAND_FAILED

        )

        val friendlyName = resolveDisplayName(lifecycleEvent.deviceId, lifecycleEvent.vendorDriver)



        logEvent(

            DeviceTraceEvent(

                pairingSessionId = lifecycleEvent.pairingSessionId,

                timestampMs = lifecycleEvent.timestampMs,

                monotonicTimestampNs = lifecycleEvent.monotonicTimestampNs,

                deviceId = lifecycleEvent.deviceId,

                deviceName = friendlyName,

                provider = lifecycleEvent.activeTransport.ifBlank { lifecycleEvent.vendorDriver },

                category = mappedCategory,

                action = "${transition.eventName} (#${lifecycleEvent.sequenceNumber})",

                outcome = if (isFailed) TraceOutcome.FAILED else TraceOutcome.SUCCESS,

                latencyMs = lifecycleEvent.latencyMs,

                expectedState = lifecycleEvent.previousState,

                actualState = lifecycleEvent.newState,

                errorSummary = if (lifecycleEvent.failureCategory.isNotBlank()) {

                    "${lifecycleEvent.failureCategory} (${lifecycleEvent.protocolResultCode})"

                } else "",

                rawPayload = "code=${lifecycleEvent.protocolResultCode} dur=${lifecycleEvent.durationMs}ms driver=${lifecycleEvent.vendorDriver}",

                wifiFrequencyMhz = lifecycleEvent.wifiFrequencyMhz,

                rssiDbm = lifecycleEvent.rssiDbm,

                permissionState = lifecycleEvent.permissionState,

                protocolResultCode = lifecycleEvent.protocolResultCode,

                domain = mappedDomain,

                isPinned = mappedDomain.isPinEligibleSetupDomain

            )

        )

    }



    internal fun recordCustomWizardStageForensicEvent(lifecycleEvent: DeviceLifecycleEvent) {

        val upperEvent = lifecycleEvent.event.uppercase(Locale.US)

        val mappedDomain = when {

            upperEvent.contains("KEY") || upperEvent.contains("BIND") || upperEvent.contains("AUTH") ->

                BlackBoxDomain.AUTH

            upperEvent.contains("CLOUD") || upperEvent.contains("ACTIVAT") || upperEvent.contains("REGISTER") ->

                BlackBoxDomain.ACTIVATION

            upperEvent.contains("CAPABILITY") || upperEvent.contains("SYNC") ->

                BlackBoxDomain.CAPABILITY

            else -> BlackBoxDomain.PAIRING

        }

        val isFailed = lifecycleEvent.failureCategory.isNotBlank()

        val friendlyName = resolveDisplayName(lifecycleEvent.deviceId, lifecycleEvent.vendorDriver)

        logEvent(

            DeviceTraceEvent(

                pairingSessionId = lifecycleEvent.pairingSessionId,

                timestampMs = lifecycleEvent.timestampMs,

                monotonicTimestampNs = lifecycleEvent.monotonicTimestampNs,

                deviceId = lifecycleEvent.deviceId,

                deviceName = friendlyName,

                provider = lifecycleEvent.activeTransport.ifBlank { lifecycleEvent.vendorDriver },

                category = if (mappedDomain == BlackBoxDomain.AUTH) DeviceTraceCategory.AUTH_KEY else DeviceTraceCategory.LIFECYCLE_STEP,

                action = "${lifecycleEvent.event} (#${lifecycleEvent.sequenceNumber})",

                outcome = if (isFailed) TraceOutcome.FAILED else TraceOutcome.SUCCESS,

                latencyMs = lifecycleEvent.latencyMs,

                expectedState = lifecycleEvent.previousState,

                actualState = lifecycleEvent.newState,

                errorSummary = if (isFailed) "${lifecycleEvent.failureCategory} (${lifecycleEvent.protocolResultCode})" else "",

                rawPayload = "code=${lifecycleEvent.protocolResultCode} dur=${lifecycleEvent.durationMs}ms driver=${lifecycleEvent.vendorDriver}",

                wifiFrequencyMhz = lifecycleEvent.wifiFrequencyMhz,

                rssiDbm = lifecycleEvent.rssiDbm,

                permissionState = lifecycleEvent.permissionState,

                protocolResultCode = lifecycleEvent.protocolResultCode,

                domain = mappedDomain,

                isPinned = true

            )

        )

    }



    fun getDeviceEvents(

        deviceIdOrGuid: String,

        domainFilter: BlackBoxDomain? = null

    ): List<DeviceTraceEvent> {

        val guid = resolvePartitionGuid(deviceIdOrGuid)

        val snapshot = synchronized(partitionLock) {

            ensureHydratedLocked()

            partitions[guid]?.snapshotNewestFirst().orEmpty()

        }

        return if (domainFilter == null) snapshot else snapshot.filter { it.domain == domainFilter }

    }



    fun getPartitionSummary(deviceIdOrGuid: String): Triple<Int, Int, Map<BlackBoxDomain, Int>> {

        val guid = resolvePartitionGuid(deviceIdOrGuid)

        return synchronized(partitionLock) {

            ensureHydratedLocked()

            val part = partitions[guid]

            if (part == null) {

                Triple(0, 0, emptyMap())

            } else {

                val all = part.pinnedSetupEvents + part.runtimeRingBuffer

                val byDomain = all.groupingBy { it.domain }.eachCount()

                Triple(part.pinnedSetupEvents.size, part.runtimeRingBuffer.size, byDomain)

            }

        }

    }



    fun logEvent(event: DeviceTraceEvent) {

        val canonGuid = resolvePartitionGuid(event.deviceId)

        val resolvedName = resolveDisplayName(canonGuid, event.deviceName)

        val cleanEndpoint = event.endpointIp.trim()



        if (cleanEndpoint.isNotBlank()) {

            val prevIp = lastKnownIps.put(canonGuid, cleanEndpoint)

            if (prevIp != null && prevIp != cleanEndpoint) {

                appendPartitionedInternal(

                    DeviceTraceEvent(

                        deviceId = canonGuid,

                        deviceName = resolvedName,

                        provider = event.provider,

                        endpointIp = cleanEndpoint,

                        category = DeviceTraceCategory.REACHABILITY,

                        action = "IP_ADDRESS_CHANGED",

                        outcome = TraceOutcome.BOUNCED,

                        expectedState = prevIp,

                        actualState = cleanEndpoint,

                        errorSummary = "Device IP drifted from $prevIp to $cleanEndpoint",

                        domain = BlackBoxDomain.CONNECTIVITY,

                        isPinned = false

                    )

                )

            }

        }



        val resolvedPairingSession = event.pairingSessionId.ifBlank {

            DiscoveryFlightRecorder.currentPairingSessionId()

        }

        appendPartitionedInternal(

            event.copy(

                pairingSessionId = resolvedPairingSession,

                deviceId = canonGuid,

                deviceName = resolvedName,

                endpointIp = cleanEndpoint,

                rawPayload = DiagnosticsSanitizer.scrub(event.rawPayload),

                errorSummary = DiagnosticsSanitizer.scrub(event.errorSummary),

                protocolResultCode = DiagnosticsSanitizer.scrub(event.protocolResultCode)

            )

        )

    }



    private fun appendPartitionedInternal(ev: DeviceTraceEvent) {

        synchronized(partitionLock) {

            ensureHydratedLocked()

            val part = partitions.getOrPut(ev.deviceId) { DevicePartition(ev.deviceId) }

            part.displayName = resolveDisplayName(ev.deviceId, ev.deviceName)



            val shouldPin = part.pinnedSetupEvents.size < MAX_PINNED_EVENTS_PER_DEVICE &&

                    (ev.isPinned || ev.domain.isPinEligibleSetupDomain)

            val persistedEvent = ev.copy(

                deviceName = part.displayName,

                isPinned = shouldPin

            )



            if (shouldPin) {

                part.pinnedSetupEvents.add(persistedEvent)

            } else {

                part.runtimeRingBuffer.addFirst(persistedEvent)

                while (part.runtimeRingBuffer.size > MAX_RUNTIME_EVENTS_PER_DEVICE) {

                    part.runtimeRingBuffer.removeLast()

                }

            }



            publishAllPartitionsLocked()

            ensureBackgroundWriterStarted()

            writeQueue.trySend(BlackBoxDbOp.InsertAndPrune(persistedEvent))

        }

    }



    /**

     * Wrap any device command execution (HTTP, TCP 6668, WebSocket, Cast/ADB, SDK call)

     * so latency, non-throwing failure pairs, exceptions, timeouts, and payloads are recorded.

     */

    suspend fun <T> traceCommand(

        deviceId: String,

        deviceName: String,

        provider: String,

        endpointIp: String = "",

        action: String,

        expectedState: String = "",

        payloadPreview: String = "",

        block: suspend () -> T

    ): Result<T> {

        val canonGuid = resolvePartitionGuid(deviceId)

        registerDeviceDisplayName(canonGuid, deviceName)

        val start = System.currentTimeMillis()

        if (expectedState.isNotBlank()) {

            recentExpectations[canonGuid] = PendingExpectation(action, expectedState, start)

        }

        logEvent(

            DeviceTraceEvent(

                deviceId = canonGuid,

                deviceName = deviceName,

                provider = provider,

                endpointIp = endpointIp,

                category = DeviceTraceCategory.UI_ACTION,

                action = action,

                outcome = TraceOutcome.SUCCESS,

                expectedState = expectedState,

                rawPayload = payloadPreview,

                domain = BlackBoxDomain.COMMAND

            )

        )

        return try {

            val result = block()

            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)

            val pairFailed = result is Pair<*, *> && result.first == false

            val boolFailed = result is Boolean && !result

            if (pairFailed || boolFailed) {

                recentExpectations.remove(canonGuid)

                val failDetail = (result as? Pair<*, *>)?.second?.toString().orEmpty()

                    .ifBlank { "Hardware command returned unacknowledged false" }

                val isTimeout = failDetail.contains("timeout", ignoreCase = true) ||

                        failDetail.contains("did not respond", ignoreCase = true) ||

                        failDetail.contains("unreachable", ignoreCase = true)

                logEvent(

                    DeviceTraceEvent(

                        deviceId = canonGuid,

                        deviceName = deviceName,

                        provider = provider,

                        endpointIp = endpointIp,

                        category = DeviceTraceCategory.TRANSPORT_RX,

                        action = action,

                        outcome = if (isTimeout) TraceOutcome.TIMEOUT else TraceOutcome.FAILED,

                        latencyMs = elapsed,

                        expectedState = expectedState,

                        actualState = "UNCONFIRMED",

                        errorSummary = failDetail,

                        domain = BlackBoxDomain.FAILURE

                    )

                )

            } else {

                val actualSummary = when (result) {

                    is Pair<*, *> -> result.second?.toString()?.take(180) ?: "OK"

                    null -> "OK"

                    else -> result.toString().take(180)

                }

                logEvent(

                    DeviceTraceEvent(

                        deviceId = canonGuid,

                        deviceName = deviceName,

                        provider = provider,

                        endpointIp = endpointIp,

                        category = DeviceTraceCategory.COMMAND_TX,

                        action = action,

                        outcome = TraceOutcome.SUCCESS,

                        latencyMs = elapsed,

                        expectedState = expectedState,

                        actualState = actualSummary,

                        domain = BlackBoxDomain.COMMAND

                    )

                )

            }

            Result.success(result)

        } catch (ce: CancellationException) {

            throw ce

        } catch (t: Throwable) {

            recentExpectations.remove(canonGuid)

            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)

            val isTimeout = t is SocketTimeoutException ||

                    t.message?.contains("timeout", ignoreCase = true) == true

            logEvent(

                DeviceTraceEvent(

                    deviceId = canonGuid,

                    deviceName = deviceName,

                    provider = provider,

                    endpointIp = endpointIp,

                    category = DeviceTraceCategory.TRANSPORT_RX,

                    action = action,

                    outcome = if (isTimeout) TraceOutcome.TIMEOUT else TraceOutcome.FAILED,

                    latencyMs = elapsed,

                    expectedState = expectedState,

                    errorSummary = "${t.javaClass.simpleName}: ${t.message ?: "No error message"}",

                    rawPayload = t.stackTraceToString().lineSequence().take(6).joinToString("\n"),

                    domain = BlackBoxDomain.FAILURE

                )

            )

            Result.failure(t)

        }

    }



    /**

     * Call whenever a poll loop, WebSocket frame, or SDK listener updates a device's state.

     * Automatically catches "state bounce" when a poll overwrites a recent command.

     */

    fun recordStateSync(

        deviceId: String,

        deviceName: String,

        provider: String,

        endpointIp: String = "",

        observedState: String,

        rawPayload: String = ""

    ) {

        val canonGuid = resolvePartitionGuid(deviceId)

        registerDeviceDisplayName(canonGuid, deviceName)

        val now = System.currentTimeMillis()

        val pending = recentExpectations[canonGuid]

        if (pending != null && (now - pending.timestampMs) <= BOUNCE_WINDOW_MS) {

            if (!observedState.contains(pending.expectedState, ignoreCase = true)) {

                logEvent(

                    DeviceTraceEvent(

                        deviceId = canonGuid,

                        deviceName = deviceName,

                        provider = provider,

                        endpointIp = endpointIp,

                        category = DeviceTraceCategory.STATE_BOUNCE_WARN,

                        action = "POLL_OVERWROTE_${pending.action}",

                        outcome = TraceOutcome.BOUNCED,

                        latencyMs = now - pending.timestampMs,

                        expectedState = pending.expectedState,

                        actualState = observedState,

                        errorSummary = "State reverted ${now - pending.timestampMs}ms after command '${pending.action}'",

                        rawPayload = rawPayload,

                        domain = BlackBoxDomain.FAILURE

                    )

                )

                recentExpectations.remove(canonGuid)

                return

            } else {

                recentExpectations.remove(canonGuid)

            }

        }



        logEvent(

            DeviceTraceEvent(

                deviceId = canonGuid,

                deviceName = deviceName,

                provider = provider,

                endpointIp = endpointIp,

                category = DeviceTraceCategory.STATE_POLL,

                action = "STATE_SYNC",

                outcome = TraceOutcome.SUCCESS,

                actualState = observedState,

                rawPayload = rawPayload,

                domain = BlackBoxDomain.CONNECTIVITY

            )

        )

    }



    fun recordUnhandledCapability(

        deviceId: String,

        deviceName: String,

        provider: String,

        requestedCapability: String,

        detail: String

    ) {

        val canonGuid = resolvePartitionGuid(deviceId)

        logEvent(

            DeviceTraceEvent(

                deviceId = canonGuid,

                deviceName = deviceName,

                provider = provider,

                category = DeviceTraceCategory.CAPABILITY_MISMATCH,

                action = requestedCapability,

                outcome = TraceOutcome.UNHANDLED,

                errorSummary = detail,

                domain = BlackBoxDomain.FAILURE

            )

        )

    }



    /**

     * Executes the Change 1.4 Verification Gate on two isolated canonical GUID partitions:

     * Generates 2,000 synthetic poll events on Device A and verifies Device B loses 0 historical

     * events while Device A retains all 25 pinned initial onboarding records + its newest 1,000 runtime events.

     */

    suspend fun verifyPartitionIsolationGate(): PartitionVerificationReport =

        kotlinx.coroutines.withContext(Dispatchers.IO) {

            val guidA = "guid::selftest::device_a"

            val guidB = "guid::selftest::device_b"

            onDeviceFormallyUnbound(guidA)

            onDeviceFormallyUnbound(guidB)



            val setupDomains = listOf(

                BlackBoxDomain.DISCOVERY,

                BlackBoxDomain.PAIRING,

                BlackBoxDomain.AUTH,

                BlackBoxDomain.ACTIVATION,

                BlackBoxDomain.CAPABILITY

            )



            // 1. Seed 25 initial onboarding/auth/activation/capability-learning records on Device A

            for (i in 1..25) {

                val dom = setupDomains[(i - 1) % setupDomains.size]

                logEvent(

                    DeviceTraceEvent(

                        deviceId = guidA,

                        deviceName = "Self-Test Device A",

                        provider = "TUYA_LAN",

                        endpointIp = "192.168.1.210",

                        category = DeviceTraceCategory.LIFECYCLE_STEP,

                        action = "ONBOARDING_STEP_$i",

                        outcome = TraceOutcome.SUCCESS,

                        actualState = dom.label,

                        domain = dom,

                        isPinned = true

                    )

                )

            }



            // 2. Seed 15 pinned setup records + 50 runtime events on Device B

            for (i in 1..15) {

                val dom = setupDomains[(i - 1) % setupDomains.size]

                logEvent(

                    DeviceTraceEvent(

                        deviceId = guidB,

                        deviceName = "Self-Test Device B",

                        provider = "GOOGLE_CAST",

                        endpointIp = "192.168.1.211:8009",

                        category = DeviceTraceCategory.LIFECYCLE_STEP,

                        action = "ONBOARDING_STEP_$i",

                        outcome = TraceOutcome.SUCCESS,

                        actualState = dom.label,

                        domain = dom,

                        isPinned = true

                    )

                )

            }

            for (i in 1..50) {

                logEvent(

                    DeviceTraceEvent(

                        deviceId = guidB,

                        deviceName = "Self-Test Device B",

                        provider = "GOOGLE_CAST",

                        endpointIp = "192.168.1.211:8009",

                        category = DeviceTraceCategory.COMMAND_TX,

                        action = "VOLUME_STEP_$i",

                        outcome = TraceOutcome.SUCCESS,

                        actualState = "Volume ${i}%",

                        domain = BlackBoxDomain.COMMAND,

                        isPinned = false

                    )

                )

            }



            // 3. Flood Device A with 2,000 synthetic poll events

            for (pollIdx in 1..2000) {

                recordStateSync(

                    deviceId = guidA,

                    deviceName = "Self-Test Device A",

                    provider = "TUYA_LAN",

                    endpointIp = "192.168.1.210",

                    observedState = "SYNTHETIC_POLL_#$pollIdx",

                    rawPayload = "poll_seq=$pollIdx"

                )

            }



            flushPendingWrites()



            val (aPinnedMem, aRuntimeMem, _) = getPartitionSummary(guidA)

            val (bPinnedMem, bRuntimeMem, _) = getPartitionSummary(guidB)

            val aEvents = getDeviceEvents(guidA)

            val oldestRetainedRuntimePoll = aEvents.filter { !it.isPinned }.lastOrNull()?.actualState.orEmpty()

            val newestRetainedRuntimePoll = aEvents.filter { !it.isPinned }.firstOrNull()?.actualState.orEmpty()



            var aDiskCount = aPinnedMem + aRuntimeMem

            var bDiskCount = bPinnedMem + bRuntimeMem

            dbHelper?.readableDatabase?.let { db ->

                db.rawQuery(

                    "SELECT COUNT(*) FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX} WHERE deviceId = ?",

                    arrayOf(guidA)

                ).use { c -> if (c.moveToFirst()) aDiskCount = c.getInt(0) }

                db.rawQuery(

                    "SELECT COUNT(*) FROM ${TelemetryDatabaseHelper.TABLE_DEVICE_BLACKBOX} WHERE deviceId = ?",

                    arrayOf(guidB)

                ).use { c -> if (c.moveToFirst()) bDiskCount = c.getInt(0) }

            }



            val gatePassed = aPinnedMem == 25 &&

                    aRuntimeMem == 1000 &&

                    aDiskCount == 1025 &&

                    bPinnedMem == 15 &&

                    bRuntimeMem == 50 &&

                    bDiskCount == 65 &&

                    oldestRetainedRuntimePoll == "SYNTHETIC_POLL_#1001" &&

                    newestRetainedRuntimePoll == "SYNTHETIC_POLL_#2000"



            val summary = if (gatePassed) {

                "PASS Change 1.4 Gate: Dev A kept 25/25 pinned + 1,000 newest polls (#1001..#2000, disk=$aDiskCount) | Dev B lost 0 events (15 pinned + 50 runtime, disk=$bDiskCount)"

            } else {

                "CHECK Gate: Dev A pinned=$aPinnedMem runtime=$aRuntimeMem disk=$aDiskCount ($oldestRetainedRuntimePoll..$newestRetainedRuntimePoll) | Dev B pinned=$bPinnedMem runtime=$bRuntimeMem disk=$bDiskCount"

            }



            PartitionVerificationReport(

                passed = gatePassed,

                deviceAPinnedCount = aPinnedMem,

                deviceARuntimeCount = aRuntimeMem,

                deviceADiskTotalCount = aDiskCount,

                deviceBPinnedCount = bPinnedMem,

                deviceBRuntimeCount = bRuntimeMem,

                deviceBDiskTotalCount = bDiskCount,

                summary = summary

            )

        }



    /**

     * Plain-English root-cause diagnosis explaining WHY a specific device is failing.

     */

    fun diagnoseDevice(deviceId: String): List<String> {

        val guid = resolvePartitionGuid(deviceId)

        val history = getDeviceEvents(guid)

        if (history.isEmpty()) {

            return listOf("No telemetry recorded for this device partition yet. Trigger a control or poll to capture live execution.")

        }

        val (pinnedCnt, runtimeCnt, _) = getPartitionSummary(guid)

        val findings = mutableListOf<String>()

        findings += "PARTITION RETENTION: $pinnedCnt/$MAX_PINNED_EVENTS_PER_DEVICE pinned setup forensics • $runtimeCnt/$MAX_RUNTIME_EVENTS_PER_DEVICE runtime ring events."



        val timeouts = history.count { it.outcome == TraceOutcome.TIMEOUT }

        val failures = history.filter { it.outcome == TraceOutcome.FAILED }

        val bounces = history.filter { it.outcome == TraceOutcome.BOUNCED && it.category == DeviceTraceCategory.STATE_BOUNCE_WARN }

        val unhandled = history.filter { it.outcome == TraceOutcome.UNHANDLED }



        if (timeouts > 0) {

            val ip = history.firstOrNull { it.endpointIp.isNotBlank() }?.endpointIp ?: "unknown IP"

            findings += "LAN TIMEOUT ($timeouts events at $ip): Device is not answering local sockets. Check DHCP IP drift, deep Wi-Fi sleep, or closed control port."

        }

        if (failures.any {

                it.category == DeviceTraceCategory.AUTH_KEY ||

                        it.errorSummary.contains("401") ||

                        it.errorSummary.contains("403") ||

                        it.errorSummary.contains("key", ignoreCase = true) ||

                        it.errorSummary.contains("auth", ignoreCase = true)

            }

        ) {

            findings += "AUTH / KEY REJECTED: Handshake or localKey/client-key was rejected by hardware. Re-pair or refresh token."

        } else if (failures.isNotEmpty()) {

            findings += "FAILURE RECORDED (${failures.size}x): Latest -> ${failures.first().errorSummary.ifBlank { failures.first().action }}"

        }

        if (bounces.isNotEmpty()) {

            findings += "STATE BOUNCE DETECTED (${bounces.size}x): Hardware or polling loop reverted UI state right after dispatch (${bounces.first().errorSummary})."

        }

        if (unhandled.isNotEmpty()) {

            findings += "UNMAPPED EXECUTOR (${unhandled.first().action}): UI triggered an action with no bound backend transport handler."

        }

        if (timeouts == 0 && failures.isEmpty() && bounces.isEmpty() && unhandled.isEmpty()) {

            val avgLat = history.filter { it.latencyMs > 0 }.map { it.latencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L

            findings += "All ${history.size} recorded operations succeeded cleanly (avg latency ${avgLat} ms)."

        }

        return findings

    }



    fun exportDiagnosticReport(

        context: Context,

        filterDeviceId: String? = null,

        shareIntent: Boolean = false

    ): String {

        val sb = StringBuilder()

        sb.appendLine("=== CLARA SMART HOME DIAGNOSTIC REPORT ===")

        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())}")

        sb.appendLine()



        val resolvedFilterGuid = filterDeviceId?.takeIf { it.isNotBlank() }?.let { resolvePartitionGuid(it) }

        val targetPartitionGuids = synchronized(partitionLock) {

            ensureHydratedLocked()

            if (resolvedFilterGuid != null) {

                listOfNotNull(resolvedFilterGuid.takeIf { partitions.containsKey(it) })

            } else {

                partitions.keys.sorted()

            }

        }



        sb.appendLine("--- PARTITIONED PER-DEVICE BLACK BOX (${targetPartitionGuids.size} device partitions) ---")

        if (targetPartitionGuids.isEmpty()) {

            sb.appendLine("No per-device Black Box events recorded yet.")

            sb.appendLine()

        } else {

            for (guid in targetPartitionGuids) {

                val eventsForGuid = getDeviceEvents(guid)

                val (pinnedCnt, runtimeCnt, domainCounts) = getPartitionSummary(guid)

                val devName = resolveDisplayName(guid, eventsForGuid.firstOrNull()?.deviceName.orEmpty())

                sb.appendLine("==================================================================")

                sb.appendLine("Device: $devName [$guid]")

                sb.appendLine("Retention: Pinned Setup=$pinnedCnt/$MAX_PINNED_EVENTS_PER_DEVICE | Runtime Ring=$runtimeCnt/$MAX_RUNTIME_EVENTS_PER_DEVICE")

                val domainSummary = BlackBoxDomain.entries.joinToString(", ") { dom ->

                    "${dom.label}=${domainCounts[dom] ?: 0}"

                }

                sb.appendLine("Domains: $domainSummary")

                diagnoseDevice(guid).forEach { finding ->

                    sb.appendLine("  * $finding")

                }



                val pinnedChronological = eventsForGuid.filter { it.isPinned }

                    .sortedWith(compareBy<DeviceTraceEvent> { it.timestampMs }.thenBy { it.monotonicTimestampNs })

                if (pinnedChronological.isNotEmpty()) {

                    sb.appendLine("  -- Pinned Initial Setup, Auth, Activation & Capability Forensics (${pinnedChronological.size}) --")

                    pinnedChronological.forEach { ev ->

                        sb.appendLine(

                            "  [PINNED | ${DiagnosticsSanitizer.formatTime(ev.timestampMs)} | ${ev.domain.label.uppercase(Locale.US)}] " +

                                    "${ev.provider} @ ${ev.endpointIp.ifBlank { "no-ip" }} | ${ev.category} | ${ev.action} -> ${ev.outcome} (${ev.latencyMs}ms)"

                        )

                        if (ev.expectedState.isNotBlank() || ev.actualState.isNotBlank()) {

                            sb.appendLine("      expected='${ev.expectedState}' actual='${ev.actualState}'")

                        }

                        if (ev.errorSummary.isNotBlank()) {

                            sb.appendLine("      error: ${ev.errorSummary}")

                        }

                    }

                }



                val newestRuntime = eventsForGuid.filterNot { it.isPinned }.take(150)

                sb.appendLine("  -- Newest Runtime Ring Buffer Events (showing ${newestRuntime.size} of $runtimeCnt) --")

                newestRuntime.forEach { ev ->

                    sb.appendLine(

                        "  [${DiagnosticsSanitizer.formatTime(ev.timestampMs)} | ${ev.domain.label.uppercase(Locale.US)}] " +

                                "${ev.provider} @ ${ev.endpointIp.ifBlank { "no-ip" }} | ${ev.category} | ${ev.action} -> ${ev.outcome} (${ev.latencyMs}ms)"

                    )

                    if (ev.expectedState.isNotBlank() || ev.actualState.isNotBlank()) {

                        sb.appendLine("      expected='${ev.expectedState}' actual='${ev.actualState}'")

                    }

                    if (ev.errorSummary.isNotBlank()) {

                        sb.appendLine("      error: ${ev.errorSummary}")

                    }

                    if (ev.rawPayload.isNotBlank()) {

                        sb.appendLine("      payload: ${ev.rawPayload}")

                    }

                }

                sb.appendLine()

            }

        }



        val lifecycleList = if (resolvedFilterGuid != null) {

            DeviceLifecycleRecorder.getDeviceHistoryOrdered(resolvedFilterGuid)

        } else {

            DeviceLifecycleRecorder.events.value

        }

        sb.appendLine("--- MONOTONIC DEVICE LIFECYCLE RECORDER (${lifecycleList.size} events) ---")

        val byGuid = lifecycleList.groupBy { it.deviceId }

        for ((guid, guidEvents) in byGuid) {

            val ordered = guidEvents.sortedBy { it.sequenceNumber }

            val contiguous = DeviceLifecycleRecorder.hasContiguousSequenceFromOne(ordered)

            sb.appendLine("Device GUID: $guid | Seq 1..${ordered.lastOrNull()?.sequenceNumber ?: 0} | Contiguous=$contiguous")

            ordered.takeLast(120).forEach { lc ->

                val failSuffix = if (lc.failureCategory.isNotBlank()) " | fail=${lc.failureCategory}" else ""

                sb.appendLine(

                    "  [#${lc.sequenceNumber} @ ${DiagnosticsSanitizer.formatTime(lc.timestampMs)}] " +

                            "${lc.event} | ${lc.previousState} -> ${lc.newState} | " +

                            "tx=${lc.activeTransport} (${lc.vendorDriver}) | " +

                            "${lc.latencyMs}ms | code=${lc.protocolResultCode}$failSuffix"

                )

            }

        }



        if (resolvedFilterGuid == null) {

            val discovery = DiscoveryFlightRecorder.events.value

            sb.appendLine()

            sb.appendLine("--- DISCOVERY FLIGHT RECORDER (${discovery.size} events) ---")

            discovery.take(300).forEach { flt ->

                val confPct = (flt.confidenceScore * 100f).toInt()

                sb.appendLine(

                    "[+${flt.elapsedMs}ms | mono=${flt.monotonicTimestampNs}ns | ${flt.sessionId}] " +

                            "${flt.transportSource} | ${flt.eventType} | stage=${flt.qualificationStage.ifBlank { "-" }} " +

                            "(${confPct}%) | ${flt.vendor.ifBlank { "-" }} | " +

                            "hw=${flt.hardwareId.ifBlank { "-" }} cloud=${flt.cloudId.ifBlank { "-" }} " +

                            "mac=${flt.macAddress.ifBlank { "-" }} ep=${flt.ipAddress.ifBlank { "-" }}:${flt.port} -> ${flt.outcomeReason}"

                )

                if (flt.rawPayload.isNotBlank()) {

                    sb.appendLine("    payload: ${flt.rawPayload}")

                }

            }

        }



        val reportText = sb.toString()



        // 1. Copy to clipboard

        runCatching {

            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            cm.setPrimaryClip(ClipData.newPlainText("Clara Diagnostics", reportText))

        }



        // 2. Write to external app files for instant `adb pull`

        runCatching {

            val outDir = context.getExternalFilesDir(null) ?: context.filesDir

            File(outDir, "clara_device_diagnostics.txt").writeText(reportText)

        }



        // 3. Optional Android Share Sheet

        if (shareIntent) {

            runCatching {

                val send = Intent(Intent.ACTION_SEND).apply {

                    type = "text/plain"

                    putExtra(Intent.EXTRA_SUBJECT, "Clara Smart Home Device Black Box")

                    putExtra(Intent.EXTRA_TEXT, reportText)

                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                }

                context.startActivity(Intent.createChooser(send, "Export Flight Recorder").apply {

                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                })

            }

        }

        return reportText

    }



    fun clear() {

        recentExpectations.clear()

        lastKnownIps.clear()

        synchronized(partitionLock) {

            partitions.clear()

            _events.value = emptyList()

            ensureBackgroundWriterStarted()

            writeQueue.trySend(BlackBoxDbOp.ClearAll)

        }

    }

}



/**

 * Unified Diagnostics UI replacing Discovery Labs.

 * Tab 0 = Partitioned Per-Device Black Box | Tab 1 = Discovery Flight Recorder | Tab 2 = Monotonic Lifecycle Seq

 */

@Composable

fun FlightRecorderHubModal(

    initialDeviceIdFilter: String? = null,

    onDismiss: () -> Unit

) {

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val resolvedInitialGuid = remember(initialDeviceIdFilter) {

        initialDeviceIdFilter?.takeIf { it.isNotBlank() }?.let { DeviceBlackBoxRecorder.resolvePartitionGuid(it) }

    }

    var selectedTab by remember { mutableStateOf(0) }

    var selectedDeviceId by remember(resolvedInitialGuid) { mutableStateOf(resolvedInitialGuid) }

    var selectedDomainFilter by remember { mutableStateOf<BlackBoxDomain?>(null) }

    var statusBanner by remember { mutableStateOf<String?>(null) }

    var isRunningGateTest by remember { mutableStateOf(false) }



    val deviceEvents by DeviceBlackBoxRecorder.events.collectAsState()

    val discoveryEvents by DiscoveryFlightRecorder.events.collectAsState()

    val lifecycleEvents by DeviceLifecycleRecorder.events.collectAsState()

    var selectedLifecycleGuid by remember(resolvedInitialGuid) {

        mutableStateOf(resolvedInitialGuid)

    }



    val distinctDevices = remember(deviceEvents) {

        deviceEvents

            .groupBy { it.deviceId }

            .map { (guid, list) ->

                guid to DeviceBlackBoxRecorder.resolveDisplayName(guid, list.firstOrNull()?.deviceName.orEmpty())

            }

    }



    val distinctLifecycleGuids = remember(lifecycleEvents) {

        lifecycleEvents.map { it.deviceId }.distinct()

    }



    val deviceScopedEvents = remember(deviceEvents, selectedDeviceId) {

        if (selectedDeviceId == null) deviceEvents

        else deviceEvents.filter { it.deviceId == selectedDeviceId }

    }



    val domainCountsForScope = remember(deviceScopedEvents) {

        deviceScopedEvents.groupingBy { it.domain }.eachCount()

    }



    val filteredDeviceEvents = remember(deviceScopedEvents, selectedDomainFilter) {

        if (selectedDomainFilter == null) deviceScopedEvents

        else deviceScopedEvents.filter { it.domain == selectedDomainFilter }

    }



    val filteredLifecycleEvents = remember(lifecycleEvents, selectedLifecycleGuid) {

        if (selectedLifecycleGuid == null) lifecycleEvents

        else lifecycleEvents.filter { it.deviceId == selectedLifecycleGuid }.sortedByDescending { it.sequenceNumber }

    }



    Dialog(

        onDismissRequest = onDismiss,

        properties = DialogProperties(usePlatformDefaultWidth = false)

    ) {

        Surface(

            modifier = Modifier

                .fillMaxWidth(0.92f)

                .fillMaxHeight(0.86f),

            shape = RoundedCornerShape(28.dp),

            color = Color(0xFF12141A),

            border = BorderStroke(1.dp, Color(0xFF2B303C))

        ) {

            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {

                // Header

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    verticalAlignment = Alignment.CenterVertically,

                    horizontalArrangement = Arrangement.SpaceBetween

                ) {

                    Column(modifier = Modifier.weight(1f)) {

                        Text(

                            text = "Black Box & Flight Recorder",

                            color = Color.White,

                            fontSize = 20.sp,

                            fontWeight = FontWeight.Bold

                        )

                        Text(

                            text = "Partitioned per-GUID ring buffer (25 pinned setup + 1,000 runtime/device) & flight traces",

                            color = Color(0xFF94A3B8),

                            fontSize = 13.sp,

                            maxLines = 1,

                            overflow = TextOverflow.Ellipsis

                        )

                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        OutlinedButton(

                            onClick = {

                                if (!isRunningGateTest) {

                                    isRunningGateTest = true

                                    scope.launch {

                                        val report = DeviceBlackBoxRecorder.verifyPartitionIsolationGate()

                                        statusBanner = report.summary

                                        selectedTab = 0

                                        selectedDeviceId = "guid::selftest::device_a"

                                        isRunningGateTest = false

                                    }

                                }

                            },

                            border = BorderStroke(1.dp, Color(0xFFFBBF24))

                        ) {

                            Text(

                                text = if (isRunningGateTest) "Testing..." else "Verify 2k Poll Gate",

                                color = Color(0xFFFBBF24),

                                fontSize = 12.sp

                            )

                        }

                        OutlinedButton(

                            onClick = {

                                DeviceBlackBoxRecorder.exportDiagnosticReport(

                                    context = context,

                                    filterDeviceId = selectedDeviceId,

                                    shareIntent = false

                                )

                                statusBanner = "Copied partitioned report to clipboard & clara_device_diagnostics.txt"

                            },

                            border = BorderStroke(1.dp, Color(0xFF38BDF8))

                        ) {

                            Text("Copy & Save", color = Color(0xFF38BDF8), fontSize = 12.sp)

                        }

                        OutlinedButton(

                            onClick = {

                                DeviceBlackBoxRecorder.exportDiagnosticReport(

                                    context = context,

                                    filterDeviceId = selectedDeviceId,

                                    shareIntent = true

                                )

                            },

                            border = BorderStroke(1.dp, Color(0xFF10B981))

                        ) {

                            Text("Share", color = Color(0xFF10B981), fontSize = 12.sp)

                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF262A34))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u2715", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                    }

                }



                statusBanner?.let { msg ->

                    Spacer(Modifier.height(8.dp))

                    Text(

                        text = msg,

                        color = Color(0xFF34D399),

                        fontSize = 12.sp,

                        modifier = Modifier

                            .fillMaxWidth()

                            .clip(RoundedCornerShape(10.dp))

                            .background(Color(0xFF064E3B).copy(alpha = 0.45f))

                            .padding(horizontal = 12.dp, vertical = 6.dp)

                    )

                }



                Spacer(Modifier.height(14.dp))



                // Mode Switcher

                Row(

                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.spacedBy(10.dp)

                ) {

                    TabPill(

                        title = "Device Black Box (${deviceEvents.size})",

                        selected = selectedTab == 0,

                        accent = Color(0xFFF97316),

                        onClick = { selectedTab = 0 }

                    )

                    TabPill(

                        title = "Discovery Flight (${discoveryEvents.size})",

                        selected = selectedTab == 1,

                        accent = Color(0xFF38BDF8),

                        onClick = { selectedTab = 1 }

                    )

                    TabPill(

                        title = "Lifecycle Seq (${lifecycleEvents.size})",

                        selected = selectedTab == 2,

                        accent = Color(0xFFA78BFA),

                        onClick = { selectedTab = 2 }

                    )

                    TabPill(

                        title = "Timeline Replay",

                        selected = selectedTab == 3,

                        accent = Color(0xFF34D399),

                        onClick = { selectedTab = 3 }

                    )

                    Spacer(Modifier.weight(1f))

                    if (selectedTab in 0..2) {

                        TextButton(onClick = {

                            when (selectedTab) {

                                0 -> {

                                    selectedDeviceId = null

                                    selectedDomainFilter = null

                                    DeviceBlackBoxRecorder.clear()

                                }

                                1 -> DiscoveryFlightRecorder.clear()

                                else -> DeviceLifecycleRecorder.clear()

                            }

                        }) {

                            Text("Clear Log", color = Color(0xFFF87171), fontSize = 12.sp)

                        }

                    }

                }



                Spacer(Modifier.height(12.dp))



                when (selectedTab) {

                    0 -> {

                        // 1. Canonical Device Partition Filter Chips

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                            item {

                                FilterChipPill(

                                    label = "All Partitions (${distinctDevices.size})",

                                    selected = selectedDeviceId == null,

                                    onClick = { selectedDeviceId = null }

                                )

                            }

                            items(distinctDevices, key = { it.first }) { (guid, name) ->

                                val shortGuid = guid.removePrefix("guid::").takeLast(12)

                                FilterChipPill(

                                    label = "$name [$shortGuid]",

                                    selected = selectedDeviceId == guid,

                                    onClick = {

                                        selectedDeviceId = guid

                                        selectedLifecycleGuid = guid

                                    }

                                )

                            }

                        }



                        Spacer(Modifier.height(8.dp))



                        // 2. Nine Typed Domain Filter Chips

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                            item {

                                FilterChipPill(

                                    label = "All 9 Domains (${deviceScopedEvents.size})",

                                    selected = selectedDomainFilter == null,

                                    onClick = { selectedDomainFilter = null }

                                )

                            }

                            items(BlackBoxDomain.entries) { dom ->

                                val cnt = domainCountsForScope[dom] ?: 0

                                FilterChipPill(

                                    label = "${dom.label} ($cnt)",

                                    selected = selectedDomainFilter == dom,

                                    onClick = {

                                        selectedDomainFilter = if (selectedDomainFilter == dom) null else dom

                                    }

                                )

                            }

                        }



                        // Root-cause diagnosis banner when a device partition is selected

                        selectedDeviceId?.let { devGuid ->

                            val findings = remember(deviceEvents, devGuid) {

                                DeviceBlackBoxRecorder.diagnoseDevice(devGuid)

                            }

                            Spacer(Modifier.height(10.dp))

                            Column(

                                modifier = Modifier

                                    .fillMaxWidth()

                                    .clip(RoundedCornerShape(14.dp))

                                    .background(Color(0xFF1E222D))

                                    .padding(12.dp),

                                verticalArrangement = Arrangement.spacedBy(4.dp)

                            ) {

                                Text(

                                    text = "PARTITION DIAGNOSIS • $devGuid",

                                    color = Color(0xFFFBBF24),

                                    fontSize = 11.sp,

                                    fontWeight = FontWeight.Bold

                                )

                                findings.forEach { line ->

                                    Text(text = "• $line", color = Color(0xFFE2E8F0), fontSize = 12.sp)

                                }

                            }

                        }



                        Spacer(Modifier.height(10.dp))



                        LazyColumn(

                            modifier = Modifier.fillMaxSize(),

                            verticalArrangement = Arrangement.spacedBy(8.dp)

                        ) {

                            items(filteredDeviceEvents, key = { it.id }) { ev ->

                                DeviceTraceRow(ev)

                            }

                        }

                    }



                    1 -> {

                        LazyColumn(

                            modifier = Modifier.fillMaxSize(),

                            verticalArrangement = Arrangement.spacedBy(8.dp)

                        ) {

                            items(discoveryEvents, key = { it.id }) { flt ->

                                DiscoveryFlightRow(flt)

                            }

                        }

                    }



                    2 -> {

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                            item {

                                FilterChipPill(

                                    label = "All Canonical GUIDs",

                                    selected = selectedLifecycleGuid == null,

                                    onClick = { selectedLifecycleGuid = null }

                                )

                            }

                            items(distinctLifecycleGuids) { guid ->

                                FilterChipPill(

                                    label = guid.removePrefix("guid::"),

                                    selected = selectedLifecycleGuid == guid,

                                    onClick = { selectedLifecycleGuid = guid }

                                )

                            }

                        }



                        Spacer(Modifier.height(10.dp))



                        LazyColumn(

                            modifier = Modifier.fillMaxSize(),

                            verticalArrangement = Arrangement.spacedBy(8.dp)

                        ) {

                            items(filteredLifecycleEvents, key = { "${it.deviceId}#${it.sequenceNumber}#${it.id}" }) { lc ->

                                DeviceLifecycleRow(lc)

                            }

                        }

                    }



                    else -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Replay panel unavailable", color = Color.Gray)
                        }
                    }

                }

            }

        }

    }

}



@Composable

private fun TabPill(title: String, selected: Boolean, accent: Color, onClick: () -> Unit) {

    Surface(

        shape = RoundedCornerShape(12.dp),

        color = if (selected) accent.copy(alpha = 0.2f) else Color(0xFF1B1F29),

        border = BorderStroke(1.dp, if (selected) accent else Color(0xFF2D3342)),

        modifier = Modifier.clickable(onClick = onClick)

    ) {

        Text(

            text = title,

            color = if (selected) accent else Color(0xFF94A3B8),

            fontSize = 13.sp,

            fontWeight = FontWeight.SemiBold,

            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)

        )

    }

}



@Composable

private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {

    Surface(

        shape = RoundedCornerShape(50),

        color = if (selected) Color(0xFF334155) else Color(0xFF191C24),

        border = BorderStroke(1.dp, if (selected) Color.White.copy(alpha = 0.6f) else Color(0xFF2A2F3D)),

        modifier = Modifier.clickable(onClick = onClick)

    ) {

        Text(

            text = label,

            color = Color.White,

            fontSize = 12.sp,

            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)

        )

    }

}



@Composable

private fun DeviceTraceRow(ev: DeviceTraceEvent) {

    val badgeColor = when (ev.outcome) {

        TraceOutcome.SUCCESS -> Color(0xFF10B981)

        TraceOutcome.TIMEOUT, TraceOutcome.FAILED -> Color(0xFFEF4444)

        TraceOutcome.BOUNCED, TraceOutcome.UNHANDLED -> Color(0xFFF59E0B)

    }

    Column(

        modifier = Modifier

            .fillMaxWidth()

            .clip(RoundedCornerShape(12.dp))

            .background(Color(0xFF181B23))

            .padding(12.dp)

    ) {

        Row(

            modifier = Modifier.fillMaxWidth(),

            horizontalArrangement = Arrangement.SpaceBetween,

            verticalAlignment = Alignment.CenterVertically

        ) {

            Row(

                verticalAlignment = Alignment.CenterVertically,

                horizontalArrangement = Arrangement.spacedBy(6.dp),

                modifier = Modifier.weight(1f)

            ) {

                if (ev.isPinned) {

                    Text(

                        text = "PINNED",

                        color = Color(0xFFFBBF24),

                        fontSize = 10.sp,

                        fontWeight = FontWeight.ExtraBold,

                        modifier = Modifier

                            .clip(RoundedCornerShape(6.dp))

                            .background(Color(0xFFFBBF24).copy(alpha = 0.18f))

                            .padding(horizontal = 6.dp, vertical = 2.dp)

                    )

                }

                Text(

                    text = ev.domain.label.uppercase(Locale.US),

                    color = Color(0xFF38BDF8),

                    fontSize = 10.sp,

                    fontWeight = FontWeight.Bold,

                    modifier = Modifier

                        .clip(RoundedCornerShape(6.dp))

                        .background(Color(0xFF38BDF8).copy(alpha = 0.14f))

                        .padding(horizontal = 6.dp, vertical = 2.dp)

                )

                Text(

                    text = "${DiagnosticsSanitizer.formatTime(ev.timestampMs)} • ${ev.deviceName} (${ev.provider})",

                    color = Color.White,

                    fontSize = 13.sp,

                    fontWeight = FontWeight.SemiBold,

                    maxLines = 1,

                    overflow = TextOverflow.Ellipsis

                )

            }

            Text(

                text = "${ev.outcome} ${if (ev.latencyMs > 0) "${ev.latencyMs}ms" else ""}".trim(),

                color = badgeColor,

                fontSize = 11.sp,

                fontWeight = FontWeight.Bold

            )

        }

        Text(

            text = "${ev.deviceId} • ${ev.category} -> ${ev.action} ${if (ev.endpointIp.isNotBlank()) "@ ${ev.endpointIp}" else ""}",

            color = Color(0xFF94A3B8),

            fontSize = 11.5.sp,

            fontFamily = FontFamily.Monospace

        )

        if (ev.expectedState.isNotBlank() || ev.actualState.isNotBlank()) {

            Text(

                text = "Expected: ${ev.expectedState.ifBlank { "-" }}  |  Actual: ${ev.actualState.ifBlank { "-" }}",

                color = Color(0xFFCBD5E1),

                fontSize = 11.sp,

                fontFamily = FontFamily.Monospace

            )

        }

        if (ev.errorSummary.isNotBlank()) {

            Text(

                text = ev.errorSummary,

                color = Color(0xFFFCA5A5),

                fontSize = 12.sp,

                fontFamily = FontFamily.Monospace

            )

        }

    }

}



@Composable

private fun DiscoveryFlightRow(flt: DiscoveryFlightEvent) {

    val stageColor = when (flt.eventType) {

        DiscoveryEventType.FAILED, DiscoveryEventType.TIMEOUT -> Color(0xFFEF4444)

        DiscoveryEventType.FILTERED -> Color(0xFFF59E0B)

        DiscoveryEventType.PRESENTED -> Color(0xFF10B981)

        else -> Color(0xFF38BDF8)

    }

    val confPct = (flt.confidenceScore * 100f).toInt()

    val endpointLabel = buildString {

        append(flt.ipAddress.ifBlank { "no-ip" })

        if (flt.port > 0) append(":${flt.port}")

        if (flt.macAddress.isNotBlank() && flt.macAddress != "-") append(" • MAC ${flt.macAddress}")

        if (flt.hardwareId.isNotBlank() && flt.hardwareId != "-") append(" • ID ${flt.hardwareId}")

        if (flt.cloudId.isNotBlank() && flt.cloudId != "-" && flt.cloudId != flt.hardwareId) {

            append(" • Cloud ${flt.cloudId}")

        }

    }

    Column(

        modifier = Modifier

            .fillMaxWidth()

            .clip(RoundedCornerShape(12.dp))

            .background(Color(0xFF181B23))

            .padding(12.dp)

    ) {

        Row(

            modifier = Modifier.fillMaxWidth(),

            horizontalArrangement = Arrangement.SpaceBetween

        ) {

            Text(

                text = "[${flt.sessionId} +${flt.elapsedMs}ms] ${flt.vendor.ifBlank { "Device" }} (${flt.transportSource})",

                color = Color.White,

                fontSize = 13.sp,

                fontWeight = FontWeight.SemiBold,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis

            )

            Text(

                text = "${flt.eventType.name} • ${confPct}%",

                color = stageColor,

                fontSize = 11.sp,

                fontWeight = FontWeight.Bold

            )

        }

        Text(

            text = "${flt.qualificationStage.ifBlank { "-" }} • $endpointLabel",

            color = Color(0xFF94A3B8),

            fontSize = 11.sp,

            fontFamily = FontFamily.Monospace

        )

        Text(

            text = flt.outcomeReason,

            color = Color(0xFFCBD5E1),

            fontSize = 12.sp

        )

        if (flt.rawPayload.isNotBlank()) {

            Text(

                text = flt.rawPayload,

                color = Color(0xFF94A3B8),

                fontSize = 11.sp,

                fontFamily = FontFamily.Monospace

            )

        }

    }

}



@Composable

private fun DeviceLifecycleRow(lc: DeviceLifecycleEvent) {

    val hasFailure = lc.failureCategory.isNotBlank()

    val accentColor = if (hasFailure) Color(0xFFEF4444) else Color(0xFFA78BFA)

    Column(

        modifier = Modifier

            .fillMaxWidth()

            .clip(RoundedCornerShape(12.dp))

            .background(Color(0xFF181B23))

            .padding(12.dp)

    ) {

        Row(

            modifier = Modifier.fillMaxWidth(),

            horizontalArrangement = Arrangement.SpaceBetween,

            verticalAlignment = Alignment.CenterVertically

        ) {

            Text(

                text = "[#${lc.sequenceNumber}] ${lc.event} • ${lc.deviceId}",

                color = Color.White,

                fontSize = 13.sp,

                fontWeight = FontWeight.SemiBold,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis

            )

            Text(

                text = "${lc.protocolResultCode} ${if (lc.latencyMs > 0) "${lc.latencyMs}ms" else ""}".trim(),

                color = accentColor,

                fontSize = 11.sp,

                fontWeight = FontWeight.Bold

            )

        }

        Text(

            text = "${DiagnosticsSanitizer.formatTime(lc.timestampMs)} • ${lc.previousState} -> ${lc.newState} • ${lc.activeTransport} (${lc.vendorDriver})",

            color = Color(0xFF94A3B8),

            fontSize = 11.sp,

            fontFamily = FontFamily.Monospace

        )

        if (hasFailure) {

            Text(

                text = "Failure Category: ${lc.failureCategory}",

                color = Color(0xFFFCA5A5),

                fontSize = 12.sp,

                fontFamily = FontFamily.Monospace

            )

        }

    }

}

