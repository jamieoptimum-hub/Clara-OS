package com.jarvis.smarthomelauncher.core

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CrossEcosystemIdentityCorrelator {
    private val hexMacRegex = Regex("^[0-9A-F]{12}$")
    private val embeddedMacRegex = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])")
    private val dummyMacBlacklist = setOf("000000000000", "FFFFFFFFFFFF", "020000000000")

    private val guidByMacOrVariant = ConcurrentHashMap<String, String>()
    private val guidBySerial = ConcurrentHashMap<String, String>()
    private val guidByExternalId = ConcurrentHashMap<String, String>()
    private val macByGuid = ConcurrentHashMap<String, String>()
    private val observedMacByIp = ConcurrentHashMap<String, String>()

    fun clearAll() {
        guidByMacOrVariant.clear()
        guidBySerial.clear()
        guidByExternalId.clear()
        macByGuid.clear()
        observedMacByIp.clear()
    }

    fun normalizeMac(rawMac: String?): String {
        if (rawMac.isNullOrBlank()) return ""
        val stripped = rawMac.trim()
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .uppercase(Locale.US)
        if (!hexMacRegex.matches(stripped)) return ""
        if (stripped in dummyMacBlacklist) return ""
        return stripped
    }

    fun areMacsCorrelated(macA: String?, macB: String?): Boolean {
        val cleanA = normalizeMac(macA)
        val cleanB = normalizeMac(macB)
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false
        if (cleanA == cleanB) return true
        return cleanB in computeMacOffsetVariants(cleanA)
    }

    fun computeMacOffsetVariants(rawMac: String?): Set<String> {
        val base = normalizeMac(rawMac)
        if (base.isEmpty()) return emptySet()

        val variants = linkedSetOf(base)
        val oui = base.substring(0, 6)
        val nic = base.substring(6, 12).toLongOrNull(16) ?: return variants
        for (delta in -2L..2L) {
            val shifted = (nic + delta) and 0xFFFFFFL
            variants.add(oui + "%06X".format(shifted))
        }

        val full48 = base.toLongOrNull(16)
        if (full48 != null) {
            for (delta in -2L..2L) {
                val shifted48 = (full48 + delta) and 0xFFFFFFFFFFFFL
                variants.add("%012X".format(Locale.US, shifted48))
            }
        }
        val firstByte = base.substring(0, 2).toIntOrNull(16)
        if (firstByte != null) {
            val flippedFirstByte = "%02X".format(firstByte xor 0x02)
            val flippedOui = flippedFirstByte + base.substring(2, 6)
            for (delta in -1L..1L) {
                val shifted = (nic + delta) and 0xFFFFFFL
                variants.add(flippedOui + "%06X".format(shifted))
            }
        }
        return variants
    }

    fun indexPersistedRecord(
        canonicalGuid: String,
        normalizedMac: String,
        macVariantsCsv: String,
        hardwareSerial: String,
        tuyaGwId: String,
        haEntityId: String,
        nestEnterpriseId: String,
        matterNodeId: String,
        cloudDeviceId: String,
        hostIp: String,
        port: Int
    ) {
        if (canonicalGuid.isBlank()) return
        val cleanMac = normalizeMac(normalizedMac)
        if (cleanMac.isNotEmpty()) {
            macByGuid[canonicalGuid] = cleanMac
            guidByMacOrVariant[cleanMac] = canonicalGuid
            val variants = if (macVariantsCsv.isNotBlank()) {
                macVariantsCsv.split(',')
            } else computeMacOffsetVariants(cleanMac)
            for (v in variants) {
                val nv = normalizeMac(v)
                if (nv.isNotEmpty()) guidByMacOrVariant[nv] = canonicalGuid
            }
            if (hostIp.isNotBlank()) {
                observedMacByIp[hostIp] = cleanMac
            }
        }
        if (hardwareSerial.length >= 5) guidBySerial[hardwareSerial.uppercase(Locale.US)] = canonicalGuid
        if (tuyaGwId.isNotBlank()) guidByExternalId["tuya::${tuyaGwId.lowercase(Locale.US)}"] = canonicalGuid
        if (haEntityId.isNotBlank()) guidByExternalId["ha::${haEntityId.lowercase(Locale.US)}"] = canonicalGuid
        if (nestEnterpriseId.isNotBlank()) guidByExternalId["nest::$nestEnterpriseId"] = canonicalGuid
        if (matterNodeId.isNotBlank()) guidByExternalId["matter::${matterNodeId.lowercase(Locale.US)}"] = canonicalGuid
        if (cloudDeviceId.length >= 5) guidByExternalId["cloud::${cloudDeviceId.lowercase(Locale.US)}"] = canonicalGuid
    }

    fun recordApplicationLayerObservation(ipAddress: String?, rawMac: String?) {
        val endpoint = EndpointSplitter.splitHostAndPort(ipAddress)
        val cleanMac = normalizeMac(rawMac)
        if (endpoint.hostIp.isNotEmpty() && cleanMac.isNotEmpty()) {
            observedMacByIp[endpoint.hostIp] = cleanMac
        }
    }

    fun extractMacFromApplicationPayload(
        ipAddress: String? = null,
        mdnsTxtAttributes: Map<String, String> = emptyMap(),
        rawPayloadText: String? = null
    ): String {
        val candidateKeys = listOf("mac", "id", "bs", "deviceid", "macaddress", "bssid", "ethmac", "wifimac")
        for ((k, v) in mdnsTxtAttributes) {
            if (k.lowercase(Locale.US) in candidateKeys) {
                val direct = normalizeMac(v)
                if (direct.isNotEmpty()) {
                    recordApplicationLayerObservation(ipAddress, direct)
                    return direct
                }
                val embedded = embeddedMacRegex.find(v)?.value?.let { normalizeMac(it) }.orEmpty()
                if (embedded.isNotEmpty()) {
                    recordApplicationLayerObservation(ipAddress, embedded)
                    return embedded
                }
            }
        }

        val body = rawPayloadText?.trim().orEmpty()
        if (body.startsWith("{")) {
            try {
                val json = JSONObject(body)
                for (field in listOf("mac", "macAddress", "mic_mac", "bssid", "wifi_mac", "device_mac")) {
                    val found = normalizeMac(json.optString(field, ""))
                    if (found.isNotEmpty()) {
                        recordApplicationLayerObservation(ipAddress, found)
                        return found
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (body.contains("macAddress", ignoreCase = true) || body.contains("shelly", ignoreCase = true)) {
            val match = embeddedMacRegex.find(body)?.value?.let { normalizeMac(it) }.orEmpty()
            if (match.isNotEmpty()) {
                recordApplicationLayerObservation(ipAddress, match)
                return match
            }
        }

        return endpointMacLookup(ipAddress)
    }

    fun resolveCanonicalGuidForCandidate(
        context: Context? = null,
        rawMac: String? = null,
        hardwareSerial: String? = null,
        tuyaGwId: String? = null,
        haEntityId: String? = null,
        nestEnterpriseId: String? = null,
        matterNodeId: String? = null,
        cloudDeviceId: String? = null,
        observedEndpoint: String? = null
    ): String? {
        val cleanMac = normalizeMac(rawMac).ifEmpty { endpointMacLookup(observedEndpoint) }
        var matchedGuid: String? = null

        if (cleanMac.isNotEmpty()) {
            matchedGuid = guidByMacOrVariant[cleanMac]
            if (matchedGuid == null) {
                for (variant in computeMacOffsetVariants(cleanMac)) {
                    matchedGuid = guidByMacOrVariant[variant]
                    if (matchedGuid != null) break
                }
            }
        }
        if (matchedGuid == null && !hardwareSerial.isNullOrBlank() && hardwareSerial.length >= 5) {
            matchedGuid = guidBySerial[hardwareSerial.trim().uppercase(Locale.US)]
        }
        if (matchedGuid == null && !tuyaGwId.isNullOrBlank()) {
            matchedGuid = guidByExternalId["tuya::${tuyaGwId.trim().lowercase(Locale.US)}"]
        }
        if (matchedGuid == null && !haEntityId.isNullOrBlank()) {
            matchedGuid = guidByExternalId["ha::${haEntityId.trim().lowercase(Locale.US)}"]
        }
        if (matchedGuid == null && !nestEnterpriseId.isNullOrBlank()) {
            matchedGuid = guidByExternalId["nest::${nestEnterpriseId.trim()}"]
        }
        if (matchedGuid == null && !matterNodeId.isNullOrBlank()) {
            matchedGuid = guidByExternalId["matter::${matterNodeId.trim().lowercase(Locale.US)}"]
        }
        if (matchedGuid == null && !cloudDeviceId.isNullOrBlank() && cloudDeviceId.length >= 5) {
            matchedGuid = guidByExternalId["cloud::${cloudDeviceId.trim().lowercase(Locale.US)}"]
        }

        if (matchedGuid != null && context != null) {
            val endpoint = EndpointSplitter.splitHostAndPort(observedEndpoint)
            if (endpoint.hostIp.isNotEmpty() || cleanMac.isNotEmpty()) {
                ClaraDeviceRegistryStore.getInstance(context).updateDeviceEndpointAndMac(
                    canonicalGuid = matchedGuid,
                    newHostIp = endpoint.hostIp,
                    newPort = endpoint.port,
                    learnedMac = cleanMac
                )
            }
        }
        return matchedGuid
    }

    fun lookupMacForGuidOrIp(canonicalGuid: String, hostIp: String): String {
        return macByGuid[canonicalGuid] ?: observedMacByIp[hostIp].orEmpty()
    }

    fun getObservedIpToMacMap(): MutableMap<String, String> {
        return HashMap(observedMacByIp)
    }

    fun untangleIdentifiers(tileJson: JSONObject): UntangledIdentifiers {
        val rawEntity = tileJson.optString("entityId", "").trim()
        val rawSerial = tileJson.optString("serialNumber", "").trim()
        val explicitVin = tileJson.optString("vehicleVin", "").trim().uppercase(Locale.US)
        val provider = tileJson.optString("activeProviderId", "").lowercase(Locale.US)
        val rawMac = normalizeMac(
            tileJson.optString("macAddress", "").ifBlank { rawSerial }
        )
        val tileId = tileJson.optString("id", "").ifBlank { UUID.randomUUID().toString() }

        var haEntityId = ""
        var nestId = ""
        var tuyaGwId = ""
        var matterNodeId = ""
        var vehicleVin = if (Regex("^[A-HJ-NPR-Z0-9]{17}$").matches(explicitVin)) explicitVin else ""
        var hardwareSerial = ""
        var cloudDeviceId = ""

        if (rawEntity.startsWith("enterprises/") || rawEntity.contains("/devices/")) {
            nestId = rawEntity
        } else if (Regex("^[a-z0-9_]+\\.[a-z0-9_]+$").matches(rawEntity.lowercase(Locale.US))) {
            haEntityId = rawEntity
        } else if (provider.contains("tuya") || provider.contains("smartlife") || Regex("^[a-zA-Z0-9]{20,28}$").matches(rawEntity)) {
            tuyaGwId = rawEntity
        } else if (rawEntity.isNotEmpty()) {
            cloudDeviceId = rawEntity
        }

        if (rawSerial.startsWith("matter_") || provider.contains("matter")) {
            matterNodeId = rawSerial
        } else if (vehicleVin.isEmpty() && Regex("^[A-HJ-NPR-Z0-9]{17}$").matches(rawSerial.uppercase(Locale.US))) {
            vehicleVin = rawSerial.uppercase(Locale.US)
        } else if (rawSerial.isNotEmpty()) {
            hardwareSerial = rawSerial
        }

        val canonicalGuid = resolveCanonicalGuid(
            rawDeviceId = tileId,
            macAddress = rawMac,
            hardwareSerial = hardwareSerial,
            cloudDeviceId = cloudDeviceId,
            activeProviderId = provider,
            vehicleVin = vehicleVin,
            haEntityId = haEntityId,
            nestEnterpriseId = nestId,
            matterNodeId = matterNodeId
        )

        return UntangledIdentifiers(
            canonicalGuid = canonicalGuid,
            hardwareSerial = hardwareSerial,
            haEntityId = haEntityId,
            tuyaGwId = tuyaGwId,
            nestEnterpriseId = nestId,
            matterNodeId = matterNodeId,
            vehicleVin = vehicleVin,
            cloudDeviceId = cloudDeviceId
        )
    }

    fun resolveCanonicalGuid(
        rawDeviceId: String = "",
        macAddress: String = "",
        hardwareSerial: String = "",
        cloudDeviceId: String = "",
        fallbackIdentityKey: String = "",
        activeProviderId: String = "",
        vehicleVin: String = "",
        haEntityId: String = "",
        nestEnterpriseId: String = "",
        matterNodeId: String = ""
    ): String {
        val cleanMac = normalizeMac(macAddress)
        val cleanSn = hardwareSerial.trim().takeUnless { it.contains(".") && it.split(".").all { part -> part.all { c -> c.isDigit() } } }.orEmpty()
        val cleanCloud = cloudDeviceId.trim().takeUnless { it.contains(".") && it.split(".").all { part -> part.all { c -> c.isDigit() } } }.orEmpty()
        val cleanRaw = rawDeviceId.trim().takeUnless { it.contains(".") && it.split(".").all { part -> part.all { c -> c.isDigit() } } }.orEmpty()
        val cleanVin = vehicleVin.trim().uppercase(Locale.US)
        
        val resolvedHaEntity = haEntityId.trim().ifBlank { if (cleanCloud.contains(".")) cleanCloud else if (cleanSn.contains(".")) cleanSn else if (cleanRaw.contains(".")) cleanRaw else "" }.lowercase(Locale.US)
        val resolvedNestId = nestEnterpriseId.trim().ifBlank { if (cleanCloud.startsWith("enterprises/")) cleanCloud else if (cleanSn.startsWith("enterprises/")) cleanSn else if (cleanRaw.startsWith("enterprises/")) cleanRaw else "" }
        val resolvedMatterId = matterNodeId.trim().ifBlank { if (cleanSn.startsWith("matter_", ignoreCase = true)) cleanSn else if (cleanRaw.startsWith("matter_", ignoreCase = true)) cleanRaw else "" }.lowercase(Locale.US)
        val resolvedTuyaId = if (activeProviderId.uppercase(Locale.US).contains("TUYA") || cleanCloud.isNotBlank() || cleanSn.isNotBlank() || cleanRaw.isNotBlank()) cleanCloud.ifBlank { cleanSn }.ifBlank { cleanRaw }.lowercase(Locale.US) else ""

        val correlated = resolveCanonicalGuidForCandidate(
            rawMac = cleanMac,
            hardwareSerial = cleanSn,
            tuyaGwId = resolvedTuyaId,
            haEntityId = resolvedHaEntity,
            nestEnterpriseId = resolvedNestId,
            matterNodeId = resolvedMatterId,
            cloudDeviceId = cleanCloud.ifBlank { cleanRaw }
        )
        if (!correlated.isNullOrBlank() && !correlated.startsWith("guid::unresolved::")) return correlated

        val providerUpper = activeProviderId.uppercase(Locale.US)

        return when {
            cleanMac.isNotEmpty() -> "guid::mac::$cleanMac"
            providerUpper.contains("TUYA") && resolvedTuyaId.isNotBlank() -> "guid::tuya::$resolvedTuyaId"
            (resolvedMatterId.isNotBlank() || providerUpper.contains("MATTER")) && cleanSn.ifBlank { cleanRaw }.isNotBlank() -> "guid::matter::${cleanSn.ifBlank { cleanRaw }.lowercase(Locale.US)}"
            resolvedNestId.isNotBlank() -> "guid::nest::$resolvedNestId"
            cleanVin.length == 17 -> "guid::vin::$cleanVin"
            cleanSn.length >= 5 -> "guid::sn::${cleanSn.lowercase(Locale.US)}"
            cleanRaw.length >= 5 -> "guid::sn::${cleanRaw.lowercase(Locale.US)}"
            resolvedHaEntity.isNotBlank() -> "guid::ha::$resolvedHaEntity"
            cleanCloud.isNotBlank() -> "guid::cloud::${cleanCloud.lowercase(Locale.US)}"
            fallbackIdentityKey.startsWith("id_sn_") -> "guid::sn::${fallbackIdentityKey.removePrefix("id_sn_").lowercase(Locale.US)}"
            fallbackIdentityKey.startsWith("id_cloud_") -> "guid::cloud::${fallbackIdentityKey.removePrefix("id_cloud_").lowercase(Locale.US)}"
            fallbackIdentityKey.startsWith("id_mac_") -> {
                val idMac = normalizeMac(fallbackIdentityKey.removePrefix("id_mac_"))
                if (idMac.isNotEmpty()) "guid::mac::$idMac" else "guid::id::${fallbackIdentityKey.lowercase(Locale.US)}"
            }
            fallbackIdentityKey.isNotBlank() -> "guid::device::${fallbackIdentityKey.lowercase(Locale.US)}"
            else -> "guid::unknown"
        }
    }

    private fun endpointMacLookup(rawEndpoint: String?): String {
        val host = EndpointSplitter.splitHostAndPort(rawEndpoint).hostIp
        return if (host.isNotEmpty()) observedMacByIp[host].orEmpty() else ""
    }
}
