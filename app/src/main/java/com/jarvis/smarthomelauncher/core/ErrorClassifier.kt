package com.jarvis.smarthomelauncher.core

import android.content.Context
import android.util.Log
import com.jarvis.smarthomelauncher.core.DiscoveryFlightRecorder.record
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.cert.CertificateException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException

enum class FailureCategory(val displayName: String) {
    INVALID_PHANTOM_DEVICE("Invalid / Phantom Device"),
    ALREADY_ONBOARDED("Already Onboarded"),
    DUPLICATE_DISCOVERY("Duplicate Discovery"),
    STALE_REGISTRY_BLACKLIST("Stale Registry / Blacklist"),
    OWNERSHIP_CONFLICT("Ownership Conflict"),
    AUTHENTICATION_FAILURE("Authentication Failure"),
    ACTIVATION_FAILURE("Activation Failure"),
    CAPABILITY_FAILURE("Capability Failure"),
    TRANSPORT_FAILURE("Transport Failure"),
    CLOUD_FAILURE("Cloud Failure"),
    UNEXPECTED_DEFECT("Unexpected Defect")
}

data class ClassifiedFailure(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val category: FailureCategory,
    val transportSource: String,
    val operationStage: String,
    val targetDeviceId: String = "",
    val endpointIp: String = "",
    val httpStatusCode: Int = 0,
    val exceptionClass: String = "",
    val technicalDetail: String = "",
    val userRemediation: String,
    val missingPermissions: List<String> = emptyList()
)

object ErrorClassifier {
    private const val TAG = "ClaraErrorClassifier"
    private const val DEDUPE_WINDOW_MS = 3_000L
    private const val MAX_RECENT_FAILURES = 200

    private val cloudDomainMarkers = listOf(
        "googleapis.com",
        "nest.com",
        "tuyaeu.com",
        "tuyaus.com",
        "tuyacn.com",
        "tuyain.com",
        "ezvizlife.com",
        "imoulife.com",
        "smartthings.com",
        "developer-api.govee.com",
        "octopus.energy",
        "givenergy.cloud",
        "solaredge.com",
        "open-meteo.com",
        "wirelesscar",
        "mercedes-benz",
        "tesla.com"
    )

    private val recentDedupeBySignature = ConcurrentHashMap<String, Long>()

    private val _activeUiRemediationFlow = MutableStateFlow<ClassifiedFailure?>(null)
    val activeUiRemediationFlow: StateFlow<ClassifiedFailure?> = _activeUiRemediationFlow.asStateFlow()

    private val _recentFailuresFlow = MutableStateFlow<List<ClassifiedFailure>>(emptyList())
    val recentFailuresFlow: StateFlow<List<ClassifiedFailure>> = _recentFailuresFlow.asStateFlow()

    fun dismissActiveRemediation() {
        _activeUiRemediationFlow.value = null
    }

    fun classifyHttpStatus(
        httpStatusCode: Int,
        isCloudEndpoint: Boolean = true,
        detailHint: String = ""
    ): FailureCategory {
        val lower = detailHint.lowercase(Locale.US)
        return when {
            httpStatusCode == 401 || httpStatusCode == 403 -> FailureCategory.AUTHENTICATION_FAILURE
            httpStatusCode == 409 || lower.contains("already bound") || lower.contains("owner") ->
                FailureCategory.OWNERSHIP_CONFLICT
            httpStatusCode == 429 || httpStatusCode in 500..599 ->
                if (isCloudEndpoint) FailureCategory.CLOUD_FAILURE else FailureCategory.TRANSPORT_FAILURE
            httpStatusCode == 400 || httpStatusCode == 422 ->
                if (lower.contains("dp") || lower.contains("capability") || lower.contains("schema")) {
                    FailureCategory.CAPABILITY_FAILURE
                } else {
                    FailureCategory.ACTIVATION_FAILURE
                }
            httpStatusCode > 0 ->
                if (isCloudEndpoint) FailureCategory.CLOUD_FAILURE else FailureCategory.TRANSPORT_FAILURE
            else -> FailureCategory.UNEXPECTED_DEFECT
        }
    }

    fun classifyException(t: Throwable): FailureCategory =
        classifyException(t = t, httpStatusCode = 0, transportHint = "")

    fun classifyException(
        t: Throwable,
        httpStatusCode: Int = 0,
        transportHint: String = ""
    ): FailureCategory {
        if (t is CancellationException) throw t
        val msg = "${t.message.orEmpty()}${t.cause?.message.orEmpty()}".lowercase(Locale.US)
        val hintLower = transportHint.lowercase(Locale.US)
        val isCloudSource = hintLower.contains("cloud") ||
                hintLower.contains("openapi") ||
                hintLower.contains("sdm") ||
                cloudDomainMarkers.any { msg.contains(it) || hintLower.contains(it) }

        if (httpStatusCode > 0) {
            return classifyHttpStatus(httpStatusCode, isCloudSource, msg)
        }

        return when {
            // 1. OS Permission & Security Sandbox Violations
            t is SecurityException -> {
                if (msg.contains("keystore") || msg.contains("cipher") || msg.contains("token") || msg.contains("auth")) {
                    FailureCategory.AUTHENTICATION_FAILURE
                } else {
                    FailureCategory.TRANSPORT_FAILURE
                }
            }

            // 2. Explicit Exclusion / Deduplication / Registry Categories
            msg.contains("already_onboarded") || msg.contains("already onboarded") ->
                FailureCategory.ALREADY_ONBOARDED
            msg.contains("duplicate_discovery") || msg.contains("duplicate record") ->
                FailureCategory.DUPLICATE_DISCOVERY
            msg.contains("stale_registry") || msg.contains("blacklist") || msg.contains("user_dismissed") ->
                FailureCategory.STALE_REGISTRY_BLACKLIST
            msg.contains("phantom") || msg.contains("non-smart") || msg.contains("unsupported local") ||
                    msg.contains("missing gwid") || msg.contains("invalid vin") ->
                FailureCategory.INVALID_PHANTOM_DEVICE

            // 3. Ownership Conflict
            msg.contains("ownership_conflict") ||
                    msg.contains("already commissioned") ||
                    msg.contains("bound to another") ||
                    msg.contains("claimed by another") ||
                    msg.contains("multi-admin") ||
                    msg.contains("409") ->
                FailureCategory.OWNERSHIP_CONFLICT

            // 4. TLS Certificate Pinning, AES-GCM/ECB LocalKey & OAuth Authentication Failures
            t is CertificateException ||
                    t is SSLHandshakeException ||
                    t is SSLPeerUnverifiedException ||
                    t is AEADBadTagException ||
                    t is BadPaddingException ||
                    t is IllegalBlockSizeException ||
                    t is InvalidKeyException ->
                FailureCategory.AUTHENTICATION_FAILURE
            t is GeneralSecurityException ->
                FailureCategory.AUTHENTICATION_FAILURE
            msg.contains("401") ||
                    msg.contains("403") ||
                    msg.contains("unauthorized") ||
                    msg.contains("forbidden") ||
                    msg.contains("auth") ||
                    msg.contains("client-key") ||
                    msg.contains("localkey") ||
                    msg.contains("verifycode") ||
                    msg.contains("invalid token") ||
                    msg.contains("expired") ||
                    msg.contains("mfa") ||
                    msg.contains("otp") ->
                FailureCategory.AUTHENTICATION_FAILURE

            // 5. Provisioning, 5 GHz Wi-Fi Band & Commissioning Activation Failures
            msg.contains("5ghz") ||
                    msg.contains("5 ghz") ||
                    msg.contains("provisioning") ||
                    msg.contains("activator") ||
                    msg.contains("softap") ||
                    msg.contains("commission") ||
                    msg.contains("preflight_control_failed") ||
                    msg.contains("entry key") ->
                FailureCategory.ACTIVATION_FAILURE

            // 6. Hardware Schema, DP & Cluster Parsing Failures
            t is JSONException ||
                    t is NumberFormatException ||
                    msg.contains("capability") ||
                    msg.contains("schema") ||
                    msg.contains("unhandled") ||
                    msg.contains("cluster") ->
                FailureCategory.CAPABILITY_FAILURE

            // 7. Cloud Relay, Rate-Limit & Upstream 5xx Failures
            msg.contains("429") ||
                    msg.contains("rate limit") ||
                    msg.contains("500") ||
                    msg.contains("502") ||
                    msg.contains("503") ||
                    msg.contains("504") ||
                    (isCloudSource && (t is UnknownHostException || t is ConnectException || t is SocketTimeoutException || t is IOException)) ->
                FailureCategory.CLOUD_FAILURE

            // 8. Local Socket, BLE, Multicast, mDNS & Network Transport Failures
            t is SocketTimeoutException ||
                    t is ConnectException ||
                    t is NoRouteToHostException ||
                    t is BindException ||
                    t is PortUnreachableException ||
                    t is SocketException ||
                    t is UnknownHostException ||
                    t is EOFException ||
                    t is SSLException ||
                    t is IOException ||
                    msg.contains("permission") ||
                    msg.contains("location") ||
                    msg.contains("bluetooth") ||
                    msg.contains("timeout") ||
                    msg.contains("unreachable") ->
                FailureCategory.TRANSPORT_FAILURE

            // 9. Unexpected Runtime Defects
            else -> FailureCategory.UNEXPECTED_DEFECT
        }
    }

    fun getHumanReadableRemediation(category: FailureCategory, t: Throwable): String {
        if (t is com.jarvis.smarthomelauncher.integrations.CircuitBreakerOpenException) {
            return t.message ?: "Cloud Service Temporarily Unavailable"
        }
        val msg = "${t.message.orEmpty()}${t.cause?.message.orEmpty()}".trim()
        val lower = msg.lowercase(Locale.US)
        return when (category) {
            FailureCategory.TRANSPORT_FAILURE -> when {
                t is SecurityException && (lower.contains("bluetooth") || lower.contains("ble")) ->
                    "Bluetooth Nearby Devices permission is revoked. Open Android Settings -> Apps -> Clara Smart Home -> Permissions and allow Nearby Devices."
                t is SecurityException && (lower.contains("wifi") || lower.contains("nearby") || lower.contains("location") || lower.contains("peering")) ->
                    "Nearby Wi-Fi / Location permission is revoked. Open Android Settings -> Apps -> Clara Smart Home -> Permissions and allow Nearby Devices and Precise Location."
                t is SecurityException ->
                    "Android blocked radio access (${t.message ?: "permission denied"}). Enable Nearby Devices and Location permissions in Android Settings."
                lower.contains("location") && lower.contains("off") ->
                    "Location Services are turned OFF. Enable Location in Android Quick Settings so Wi-Fi SSID and Bluetooth LE scans can run."
                t is BindException ->
                    "Local discovery UDP/TCP port is busy (${t.message ?: "address in use"}). Wait a few seconds and rescan."
                t is NoRouteToHostException ->
                    "No route to device on local Wi-Fi. Check that router AP/Client Isolation is disabled and tablet is on your main home VLAN."
                else ->
                    "Local network transport unreachable (${t.javaClass.simpleName}). Ensure the device is powered on and connected to the same 2.4 GHz Wi-Fi subnet."
            }

            FailureCategory.AUTHENTICATION_FAILURE -> when {
                t is CertificateException || t is SSLHandshakeException || lower.contains("certificate") ->
                    "TLS certificate verification failed. If the TV or speaker was reset, tap 'Re-verify & Re-pair' to trust its new certificate."
                t is AEADBadTagException || t is BadPaddingException || lower.contains("localkey") ->
                    "Local AES encryption key mismatch. Re-sync your cloud account or re-pair the device to refresh its 16-character localKey."
                lower.contains("client-key") || lower.contains("accept") ->
                    "TV pairing key missing or rejected. Press 'Accept' on your TV screen when prompted."
                lower.contains("mfa") || lower.contains("otp") ->
                    "Multi-factor verification required. Sign in again inside Control Center to complete OTP verification."
                else ->
                    "Authentication rejected. Verify your account password, setup PIN, or pairing token and try again."
            }

            FailureCategory.ACTIVATION_FAILURE -> when {
                lower.contains("5ghz") || lower.contains("5 ghz") ->
                    "Your tablet or selected SSID is on 5 GHz Wi-Fi. Switch to a 2.4 GHz Wi-Fi band to provision smart plugs and sensors."
                else ->
                    "Device activation did not complete. Ensure the indicator light is blinking in pairing mode and on 2.4 GHz Wi-Fi."
            }

            FailureCategory.OWNERSHIP_CONFLICT ->
                "Ownership conflict: device is already bound to another account or Matter fabric. Factory-reset the device or share it via Multi-Admin."

            FailureCategory.CAPABILITY_FAILURE ->
                "Malformed hardware capability or JSON response (${t.javaClass.simpleName}). Trigger a manual telemetry refresh to re-introspect schema."

            FailureCategory.CLOUD_FAILURE -> when {
                lower.contains("429") || lower.contains("rate") ->
                    "Cloud provider rate-limited requests (HTTP 429). Cooling down automatically before retrying."
                else ->
                    "Cloud service is temporarily unreachable (${t.javaClass.simpleName}). Local LAN controls remain active."
            }

            FailureCategory.ALREADY_ONBOARDED ->
                "This device is already on your dashboard; duplicate onboarding suppressed."

            FailureCategory.DUPLICATE_DISCOVERY ->
                "Duplicate radio advertisement merged into existing discovery candidate."

            FailureCategory.STALE_REGISTRY_BLACKLIST ->
                "Device is currently hidden/ignored. Open Manage Ignored Devices to restore it."

            FailureCategory.INVALID_PHANTOM_DEVICE ->
                "Ignored non-smart peripheral or unverified network port."

            FailureCategory.UNEXPECTED_DEFECT ->
                "Unexpected internal error (${t.javaClass.simpleName}:${msg.ifBlank { "no message" }})."
        }
    }

    fun auditRadioPermissionsAndServices(
        context: Context?,
        transportSource: String,
        checkBle: Boolean = true,
        checkWifi: Boolean = true
    ): List<ClassifiedFailure> {
        val ctx = context?.applicationContext ?: return emptyList()
        val env = RadioEnvironmentProbe.snapshot(ctx)
        val missingPerms = env.missingPermissions(checkBle = checkBle, checkWifi = checkWifi)

        val detectedFailures = mutableListOf<ClassifiedFailure>()
        if (missingPerms.isNotEmpty()) {
            val permListStr = missingPerms.joinToString(", ")
            val remediation = "Missing Android radio permission ($permListStr). Grant Nearby Devices & Precise Location in Android Settings -> Apps -> Clara Smart Home -> Permissions."
            val failure = ClassifiedFailure(
                category = FailureCategory.TRANSPORT_FAILURE,
                transportSource = transportSource,
                operationStage = "auditRadioPermissions",
                exceptionClass = "SecurityException",
                technicalDetail = "Revoked runtime permissions: $permListStr",
                userRemediation = remediation,
                missingPermissions = missingPerms
            )
            publishClassifiedFailure(
                failure = failure,
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            detectedFailures.add(failure)
        }

        if ((checkWifi || checkBle) && !env.isLocationServiceEnabled) {
            val locFailure = ClassifiedFailure(
                category = FailureCategory.TRANSPORT_FAILURE,
                transportSource = transportSource,
                operationStage = "auditLocationServices",
                exceptionClass = "LocationServicesDisabled",
                technicalDetail = "Android LocationManager.isLocationEnabled == false",
                userRemediation = "Android Location Services are turned OFF. Turn ON Location so Wi-Fi SSID and Bluetooth LE discovery can detect nearby devices."
            )
            publishClassifiedFailure(
                failure = locFailure,
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            detectedFailures.add(locFailure)
        }

        if (checkWifi && !env.isWifiEnabled) {
            val wifiOffFailure = ClassifiedFailure(
                category = FailureCategory.TRANSPORT_FAILURE,
                transportSource = transportSource,
                operationStage = "auditWifiEnabled",
                exceptionClass = "WifiDisabled",
                technicalDetail = "WifiManager.isWifiEnabled == false",
                userRemediation = "Wi-Fi is turned OFF on this tablet. Enable Wi-Fi and connect to your 2.4 GHz home network."
            )
            publishClassifiedFailure(
                failure = wifiOffFailure,
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = true
            )
            detectedFailures.add(wifiOffFailure)
        }

        if (checkBle && !env.isBluetoothEnabled) {
            val btOffFailure = ClassifiedFailure(
                category = FailureCategory.TRANSPORT_FAILURE,
                transportSource = transportSource,
                operationStage = "auditBluetoothEnabled",
                exceptionClass = "BluetoothDisabled",
                technicalDetail = "BluetoothAdapter.isEnabled == false",
                userRemediation = "Bluetooth is turned OFF. Enable Bluetooth to discover blinking BLE setup beacons."
            )
            publishClassifiedFailure(
                failure = btOffFailure,
                logToDiscoveryRecorder = true,
                surfaceToUiBanner = false
            )
            detectedFailures.add(btOffFailure)
        }

        return detectedFailures
    }

    fun recordHandledException(
        t: Throwable,
        transportSource: String,
        operationStage: String,
        hardwareId: String = "",
        deviceName: String = "",
        ipAddress: String = "",
        port: Int = 0,
        httpStatusCode: Int = 0,
        logToDiscoveryRecorder: Boolean = true,
        logToDeviceBlackBox: Boolean = false,
        surfaceToUiBanner: Boolean = false
    ): ClassifiedFailure {
        if (t is CancellationException) throw t

        val category = classifyException(
            t = t,
            httpStatusCode = httpStatusCode,
            transportHint = transportSource
        )
        val remediation = getHumanReadableRemediation(category, t)
        val techSummary = "${t.javaClass.simpleName}:${t.message ?: "no detail"}"

        val failure = ClassifiedFailure(
            category = category,
            transportSource = transportSource,
            operationStage = operationStage,
            targetDeviceId = hardwareId,
            endpointIp = ipAddress,
            httpStatusCode = httpStatusCode,
            exceptionClass = t.javaClass.simpleName,
            technicalDetail = techSummary,
            userRemediation = remediation
        )

        val autoSurface = surfaceToUiBanner ||
                t is SecurityException ||
                t is CertificateException ||
                t is AEADBadTagException
        publishClassifiedFailure(
            failure = failure,
            deviceName = deviceName,
            port = port,
            isTimeout = t is SocketTimeoutException,
            rawStackSnippet = t.stackTraceToString().lineSequence().take(4).joinToString(" | "),
            logToDiscoveryRecorder = logToDiscoveryRecorder,
            logToDeviceBlackBox = logToDeviceBlackBox,
            surfaceToUiBanner = autoSurface
        )
        return failure
    }

    private fun publishClassifiedFailure(
        failure: ClassifiedFailure,
        deviceName: String = "",
        port: Int = 0,
        isTimeout: Boolean = false,
        rawStackSnippet: String = "",
        logToDiscoveryRecorder: Boolean,
        logToDeviceBlackBox: Boolean = false,
        surfaceToUiBanner: Boolean
    ) {
        val now = System.currentTimeMillis()
        val signature = "${failure.category.name}|${failure.transportSource}|${failure.operationStage}|${failure.targetDeviceId}|${failure.endpointIp}"
        val prevMs = recentDedupeBySignature.put(signature, now)
        val isDuplicateWithinWindow = prevMs != null && (now - prevMs) < DEDUPE_WINDOW_MS

        Log.w(
            TAG,
            "[${failure.category.name}] ${failure.transportSource}.${failure.operationStage}: ${failure.technicalDetail} ->${failure.userRemediation}"
        )

        if (!isDuplicateWithinWindow) {
            _recentFailuresFlow.update { current ->
                (listOf(failure) + current).take(MAX_RECENT_FAILURES)
            }
        }

        if (surfaceToUiBanner) {
            _activeUiRemediationFlow.value = failure
        }

        if (logToDiscoveryRecorder && !isDuplicateWithinWindow) {
            record(
                eventType = if (isTimeout) DiscoveryEventType.TIMEOUT else DiscoveryEventType.FAILED,
                transportSource = failure.transportSource,
                hardwareId = failure.targetDeviceId,
                ipAddress = failure.endpointIp,
                port = port,
                vendor = deviceName.ifBlank { failure.transportSource },
                qualificationStage = failure.operationStage,
                outcomeReason = "[${failure.category.name}]${failure.userRemediation}",
                rawPayload = "${failure.technicalDetail}${if (rawStackSnippet.isNotBlank()) " | $rawStackSnippet" else ""}"
            )
        }

        if (logToDeviceBlackBox && failure.targetDeviceId.isNotBlank() && !isDuplicateWithinWindow) {
            val canonGuid = DeviceBlackBoxRecorder.resolvePartitionGuid(failure.targetDeviceId)
            val isAuth = failure.category == FailureCategory.AUTHENTICATION_FAILURE
            DeviceBlackBoxRecorder.logEvent(
                DeviceTraceEvent(
                    deviceId = canonGuid,
                    deviceName = deviceName.ifBlank { canonGuid.removePrefix("guid::") },
                    provider = failure.transportSource,
                    endpointIp = if (port > 0 && failure.endpointIp.isNotBlank() && !failure.endpointIp.contains(":")) {
                        "${failure.endpointIp}:$port"
                    } else {
                        failure.endpointIp
                    },
                    category = if (isAuth) DeviceTraceCategory.AUTH_KEY else DeviceTraceCategory.TRANSPORT_RX,
                    action = failure.operationStage,
                    outcome = if (isTimeout) TraceOutcome.TIMEOUT else TraceOutcome.FAILED,
                    errorSummary = "[${failure.category.name}]${failure.userRemediation}",
                    rawPayload = failure.technicalDetail,
                    domain = if (isAuth) BlackBoxDomain.AUTH else BlackBoxDomain.FAILURE,
                    isPinned = false
                )
            )
        }
    }

    fun closeQuietly(
        resource: AutoCloseable?,
        transportSource: String,
        operationStage: String = "closeResource"
    ) {
        if (resource == null) return
        try {
            resource.close()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t !is IOException) {
                recordHandledException(
                    t = t,
                    transportSource = transportSource,
                    operationStage = operationStage,
                    logToDiscoveryRecorder = false
                )
            }
        }
    }
}
