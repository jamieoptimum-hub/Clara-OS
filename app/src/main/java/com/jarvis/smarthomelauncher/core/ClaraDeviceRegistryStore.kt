package com.jarvis.smarthomelauncher.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val REGISTRY_TAG = "ClaraRegistryStore"
private const val DB_NAME = "clara_device_registry.db"
private const val DB_VERSION = 5
private const val MAX_ROLLING_SNAPSHOTS = 10
private const val META_PREFS = "clara_registry_meta_v2"
private const val KEY_MIGRATED_V10 = "migrated_from_v10_prefs_v2"
private const val KEY_HOUSEHOLD_INITIALIZED = "household_initialized_v2"

data class ParsedEndpoint(val hostIp: String, val port: Int)

data class PersistedDeviceEndpoint(
    val canonicalGuid: String,
    val hostIp: String,
    val port: Int,
    val normalizedMac: String
)

data class UntangledIdentifiers(
    val canonicalGuid: String,
    val hardwareSerial: String,
    val haEntityId: String,
    val tuyaGwId: String,
    val nestEnterpriseId: String,
    val matterNodeId: String,
    val vehicleVin: String,
    val cloudDeviceId: String
)

object EndpointSplitter {
    fun splitHostAndPort(rawEndpoint: String?): ParsedEndpoint {
        val trimmed = rawEndpoint?.trim().orEmpty()
        if (trimmed.isEmpty()) return ParsedEndpoint("", 0)

        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket > 1) {
                val ipv6Host = trimmed.substring(1, closeBracket)
                val portPart = if (closeBracket + 2 <= trimmed.length && trimmed[closeBracket + 1] == ':') {
                    trimmed.substring(closeBracket + 2).toIntOrNull() ?: 0
                } else 0
                return ParsedEndpoint(ipv6Host, portPart.takeIf { it in 1..65535 } ?: 0)
            }
        }

        if (trimmed.count { it == ':' } == 1) {
            val parts = trimmed.split(':')
            val candidatePort = parts[1].toIntOrNull()
            if (candidatePort != null && candidatePort in 1..65535) {
                return ParsedEndpoint(parts[0].trim(), candidatePort)
            }
        }
        return ParsedEndpoint(trimmed, 0)
    }
}



class ClaraDeviceRegistryStore private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS canonical_devices (
                canonical_guid TEXT PRIMARY KEY NOT NULL,
                vendor_id TEXT NOT NULL DEFAULT '',
                product_id TEXT NOT NULL DEFAULT '',
                hardware_serial TEXT NOT NULL DEFAULT '',
                normalized_mac TEXT NOT NULL DEFAULT '',
                mac_variants_csv TEXT NOT NULL DEFAULT '',
                ha_entity_id TEXT NOT NULL DEFAULT '',
                tuya_gw_id TEXT NOT NULL DEFAULT '',
                nest_enterprise_id TEXT NOT NULL DEFAULT '',
                matter_node_id TEXT NOT NULL DEFAULT '',
                vehicle_vin TEXT NOT NULL DEFAULT '',
                cloud_device_id TEXT NOT NULL DEFAULT '',
                host_ip TEXT NOT NULL DEFAULT '',
                port INTEGER NOT NULL DEFAULT 0,
                protocol_version TEXT NOT NULL DEFAULT '',
                active_provider_id TEXT NOT NULL DEFAULT '',
                fallback_providers_csv TEXT NOT NULL DEFAULT '',
                auth_state TEXT NOT NULL DEFAULT 'UNKNOWN',
                subscription_state TEXT NOT NULL DEFAULT 'NONE',
                lifecycle_state TEXT NOT NULL DEFAULT 'DISCOVERED',
                last_seen_ms INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cd_mac ON canonical_devices(normalized_mac)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cd_serial ON canonical_devices(hardware_serial)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cd_tuya ON canonical_devices(tuya_gw_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cd_ha ON canonical_devices(ha_entity_id)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_capabilities (
                canonical_guid TEXT NOT NULL,
                capability_key TEXT NOT NULL,
                provenance TEXT NOT NULL DEFAULT 'UNVERIFIED_LEGACY',
                schema_hash TEXT NOT NULL DEFAULT '',
                descriptor_json TEXT NOT NULL,
                PRIMARY KEY (canonical_guid, capability_key),
                FOREIGN KEY (canonical_guid) REFERENCES canonical_devices(canonical_guid) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_discovery_fingerprints (
                fingerprint_id TEXT PRIMARY KEY NOT NULL,
                canonical_guid TEXT NOT NULL,
                source_protocol TEXT NOT NULL,
                observed_ip TEXT NOT NULL DEFAULT '',
                observed_port INTEGER NOT NULL DEFAULT 0,
                normalized_mac TEXT NOT NULL DEFAULT '',
                vendor_id TEXT NOT NULL DEFAULT '',
                product_id TEXT NOT NULL DEFAULT '',
                attributes_json TEXT NOT NULL DEFAULT '{}',
                last_seen_ms INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (canonical_guid) REFERENCES canonical_devices(canonical_guid) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dashboard_tiles (
                tile_id TEXT PRIMARY KEY NOT NULL,
                canonical_guid TEXT NOT NULL,
                sub_endpoint_index INTEGER NOT NULL DEFAULT 0,
                display_title TEXT NOT NULL,
                tile_type TEXT NOT NULL,
                room_name TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                is_visible INTEGER NOT NULL DEFAULT 1,
                is_online INTEGER NOT NULL DEFAULT 0,
                tile_payload_json TEXT NOT NULL,
                FOREIGN KEY (canonical_guid) REFERENCES canonical_devices(canonical_guid) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dt_guid_sub ON dashboard_tiles(canonical_guid, sub_endpoint_index)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quarantined_records (
                quarantine_id TEXT PRIMARY KEY NOT NULL,
                source_store TEXT NOT NULL,
                raw_json TEXT NOT NULL,
                failure_category TEXT NOT NULL DEFAULT 'CAT_11',
                error_detail TEXT NOT NULL,
                quarantined_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS registry_snapshots (
                snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at_ms INTEGER NOT NULL,
                reason TEXT NOT NULL,
                device_count INTEGER NOT NULL,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_resolutions (
                device_key TEXT PRIMARY KEY,
                resolution_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            runCatching {
                db.execSQL("ALTER TABLE dashboard_tiles ADD COLUMN sub_endpoint_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE dashboard_tiles
                    SET sub_endpoint_index = CASE
                        WHEN tile_payload_json LIKE '%"SPLIT_SOCKET_1"%' THEN 1
                        WHEN tile_payload_json LIKE '%"SPLIT_SOCKET_2"%' THEN 2
                        ELSE 0
                    END
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_dt_guid_sub ON dashboard_tiles(canonical_guid, sub_endpoint_index)")
            }
        }
        if (oldVersion < 4) {
            runCatching {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS semantic_resolutions (
                        device_key TEXT PRIMARY KEY,
                        resolution_json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE canonical_devices ADD COLUMN protocol_version TEXT NOT NULL DEFAULT ''")
            }
        }
        if (oldVersion < 3) {
            runCatching {
                db.execSQL("UPDATE canonical_devices SET canonical_guid = UPPER(canonical_guid) WHERE canonical_guid LIKE 'guid::mac::%'")
                db.execSQL("UPDATE device_capabilities SET canonical_guid = UPPER(canonical_guid) WHERE canonical_guid LIKE 'guid::mac::%'")
                db.execSQL("UPDATE device_discovery_fingerprints SET canonical_guid = UPPER(canonical_guid) WHERE canonical_guid LIKE 'guid::mac::%'")
                db.execSQL("UPDATE dashboard_tiles SET canonical_guid = UPPER(canonical_guid) WHERE canonical_guid LIKE 'guid::mac::%'")
            }
        }
        onCreate(db)
    }

    @Synchronized
    fun initializeAndMigrate() {
        val meta = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val hasExistingRows = runCatching {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM dashboard_tiles", null).use { c ->
                c.moveToFirst() && c.getInt(0) > 0
            }
        }.getOrDefault(false)

        if (!meta.getBoolean(KEY_MIGRATED_V10, false) || !hasExistingRows) {
            meta.edit().putBoolean(KEY_MIGRATED_V10, true).apply()
        }
        warmCorrelatorCacheFromDisk()
    }



    fun isHouseholdInitialized(): Boolean {
        val meta = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        return meta.getBoolean(KEY_HOUSEHOLD_INITIALIZED, false)
    }

    fun warmCorrelatorCacheFromDisk() {
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT canonical_guid, normalized_mac, mac_variants_csv, hardware_serial,
                   tuya_gw_id, ha_entity_id, nest_enterprise_id, matter_node_id,
                   cloud_device_id, host_ip, port
            FROM canonical_devices
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                CrossEcosystemIdentityCorrelator.indexPersistedRecord(
                    canonicalGuid = cursor.getString(0).orEmpty(),
                    normalizedMac = cursor.getString(1).orEmpty(),
                    macVariantsCsv = cursor.getString(2).orEmpty(),
                    hardwareSerial = cursor.getString(3).orEmpty(),
                    tuyaGwId = cursor.getString(4).orEmpty(),
                    haEntityId = cursor.getString(5).orEmpty(),
                    nestEnterpriseId = cursor.getString(6).orEmpty(),
                    matterNodeId = cursor.getString(7).orEmpty(),
                    cloudDeviceId = cursor.getString(8).orEmpty(),
                    hostIp = cursor.getString(9).orEmpty(),
                    port = cursor.getInt(10)
                )
            }
        }
    }

    @Synchronized
    fun clearAllForCleanSlate(starterTileObjects: List<JSONObject>) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("dashboard_tiles", null, null)
            db.delete("device_capabilities", null, null)
            db.delete("device_discovery_fingerprints", null, null)
            db.delete("canonical_devices", null, null)
            db.delete("registry_snapshots", null, null)
            appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HOUSEHOLD_INITIALIZED, false)
                .apply()
            CrossEcosystemIdentityCorrelator.clearAll()
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(REGISTRY_TAG, "clearAllForCleanSlate failed: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
        if (starterTileObjects.isNotEmpty()) {
            persistTileJsonObjectsTransactional(starterTileObjects, "clean_slate_starter")
        }
    }

    @Synchronized
    fun persistTileJsonObjectsTransactional(tileObjects: List<JSONObject>, reason: String = "save_tiles") {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransactionNonExclusive()
        try {
            val activeTileIds = mutableListOf<String>()
            val snapshotArray = JSONArray()
            val writtenCanonicalGuids = HashSet<String>()

            tileObjects.forEachIndexed { index, rawObj ->
                val obj = JSONObject(rawObj.toString())
                val ids = CrossEcosystemIdentityCorrelator.untangleIdentifiers(obj)
                val endpoint = EndpointSplitter.splitHostAndPort(obj.optString("ipAddress", ""))
                val normalizedMac = CrossEcosystemIdentityCorrelator.normalizeMac(
                    obj.optString("macAddress", "").ifBlank { obj.optString("serialNumber", "") }
                ).ifEmpty {
                    CrossEcosystemIdentityCorrelator.lookupMacForGuidOrIp(ids.canonicalGuid, endpoint.hostIp)
                }

                val rawChannelId = obj.optString("channelId", "")
                val subEndpointIndex = obj.optInt("subEndpointIndex", -1).takeIf { it >= 0 } ?: when {
                    rawChannelId.equals("SPLIT_SOCKET_1", ignoreCase = true) -> 1
                    rawChannelId.equals("SPLIT_SOCKET_2", ignoreCase = true) -> 2
                    rawChannelId.startsWith("SPLIT_SOCKET_", ignoreCase = true) ->
                        rawChannelId.substringAfter("SPLIT_SOCKET_").toIntOrNull() ?: 0
                    else -> 0
                }

                if (normalizedMac.isNotEmpty()) {
                    obj.put("macAddress", normalizedMac)
                }
                obj.put("canonicalGuid", ids.canonicalGuid)
                obj.put("subEndpointIndex", subEndpointIndex)

                val variantsCsv = if (normalizedMac.isNotEmpty()) {
                    CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normalizedMac).joinToString(",")
                } else ""

                if (writtenCanonicalGuids.add(ids.canonicalGuid)) {
                    val existingProtoVer = getPersistedProtocolVersionInternal(db, ids.canonicalGuid)
                    val resolvedProtoVer = obj.optString("protocolVersion", "").trim().ifBlank { existingProtoVer }

                    val deviceValues = ContentValues().apply {
                        put("canonical_guid", ids.canonicalGuid)
                        put("vendor_id", obj.optString("vendorId", ""))
                        put("product_id", obj.optString("productId", ""))
                        put("hardware_serial", ids.hardwareSerial)
                        put("normalized_mac", normalizedMac)
                        put("mac_variants_csv", variantsCsv)
                        put("ha_entity_id", ids.haEntityId)
                        put("tuya_gw_id", ids.tuyaGwId)
                        put("nest_enterprise_id", ids.nestEnterpriseId)
                        put("matter_node_id", ids.matterNodeId)
                        put("vehicle_vin", ids.vehicleVin)
                        put("cloud_device_id", ids.cloudDeviceId)
                        put("host_ip", endpoint.hostIp)
                        put("port", endpoint.port)
                        put("protocol_version", resolvedProtoVer)
                        put("active_provider_id", obj.optString("activeProviderId", ""))
                        put("fallback_providers_csv", obj.optJSONArray("fallbackProviderIds")?.toString().orEmpty())
                        put("auth_state", obj.optString("stateAuthorityTier", "CACHED_UI_STATE"))
                        put("subscription_state", obj.optString("subscriptionState", "NONE"))
                        put("lifecycle_state", obj.optString("lifecycleState", DeviceLifecycleState.DISCOVERED.name))
                        put("last_seen_ms", obj.optLong("lastConfirmedEpochMs", now))
                        put("updated_at_ms", now)
                    }
                    val deviceRows = db.update("canonical_devices", deviceValues, "canonical_guid = ?", arrayOf(ids.canonicalGuid))
                    if (deviceRows == 0) {
                        db.insertWithOnConflict("canonical_devices", null, deviceValues, SQLiteDatabase.CONFLICT_IGNORE)
                    }

                    val capsArray = obj.optJSONArray("capabilities")
                    if (capsArray != null) {
                        db.delete("device_capabilities", "canonical_guid = ?", arrayOf(ids.canonicalGuid))
                        for (c in 0 until capsArray.length()) {
                            val capObj = capsArray.optJSONObject(c) ?: continue
                            val capKey = capObj.optString("key", "cap_$c")
                            val capStr = capObj.toString()
                            val capProvenance = capObj.optString("provenance", "")
                                .ifBlank { capObj.optString("origin", CapabilityProvenance.VERIFIED_HARDWARE_SCHEMA.name) }
                            val capValues = ContentValues().apply {
                                put("canonical_guid", ids.canonicalGuid)
                                put("capability_key", capKey)
                                put("provenance", capProvenance)
                                put("schema_hash", sha256Short(capStr))
                                put("descriptor_json", capStr)
                            }
                            db.insertWithOnConflict("device_capabilities", null, capValues, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }

                    CrossEcosystemIdentityCorrelator.indexPersistedRecord(
                        canonicalGuid = ids.canonicalGuid,
                        normalizedMac = normalizedMac,
                        macVariantsCsv = variantsCsv,
                        hardwareSerial = ids.hardwareSerial,
                        tuyaGwId = ids.tuyaGwId,
                        haEntityId = ids.haEntityId,
                        nestEnterpriseId = ids.nestEnterpriseId,
                        matterNodeId = ids.matterNodeId,
                        cloudDeviceId = ids.cloudDeviceId,
                        hostIp = endpoint.hostIp,
                        port = endpoint.port
                    )
                }

                val tileId = obj.optString("id", UUID.randomUUID().toString())
                activeTileIds.add(tileId)
                if (subEndpointIndex > 0 && !ids.canonicalGuid.startsWith("guid::unresolved::")) {
                    db.delete(
                        "dashboard_tiles",
                        "canonical_guid = ? AND sub_endpoint_index = ? AND tile_id != ?",
                        arrayOf(ids.canonicalGuid, subEndpointIndex.toString(), tileId)
                    )
                }
                val tileValues = ContentValues().apply {
                    put("tile_id", tileId)
                    put("canonical_guid", ids.canonicalGuid)
                    put("sub_endpoint_index", subEndpointIndex)
                    put("display_title", obj.optString("title", "Smart Device"))
                    put("tile_type", obj.optString("type", "SMARTLIFE_DEVICE"))
                    put("room_name", obj.optString("roomOrSubtitle", ""))
                    put("sort_order", index)
                    put("is_visible", if (obj.optBoolean("isVisible", true)) 1 else 0)
                    put("is_online", if (obj.optBoolean("isOnline", false)) 1 else 0)
                    put("tile_payload_json", obj.toString())
                }
                val tileRows = db.update("dashboard_tiles", tileValues, "tile_id = ?", arrayOf(tileId))
                if (tileRows == 0) {
                    db.insertWithOnConflict("dashboard_tiles", null, tileValues, SQLiteDatabase.CONFLICT_IGNORE)
                }
                snapshotArray.put(obj)
            }

            if (activeTileIds.isNotEmpty()) {
                val placeholders = activeTileIds.joinToString(",") { "?" }
                db.delete("dashboard_tiles", "tile_id NOT IN ($placeholders)", activeTileIds.toTypedArray())
            } else {
                db.delete("dashboard_tiles", null, null)
            }

            db.execSQL(
                """
                DELETE FROM canonical_devices 
                WHERE canonical_guid NOT IN (SELECT canonical_guid FROM dashboard_tiles) 
                AND lifecycle_state NOT IN ('AUTHENTICATED', 'ACTIVATED', 'CAPABILITY_LEARNING')
                """.trimIndent()
            )

            val snapshotValues = ContentValues().apply {
                put("created_at_ms", now)
                put("reason", reason)
                put("device_count", snapshotArray.length())
                put("payload_json", snapshotArray.toString())
            }
            
            val meta = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            val lastSnapshotTime = meta.getLong("last_snapshot_time_ms", 0L)
            val currentTileIds = activeTileIds.sorted().joinToString(",")
            val lastTileIds = meta.getString("last_snapshot_tile_ids", "") ?: ""
            val timeSinceLast = now - lastSnapshotTime
            
            if (reason == "clean_slate_starter" || timeSinceLast > 15 * 60 * 1000L || currentTileIds != lastTileIds) {
                db.insert("registry_snapshots", null, snapshotValues)
                db.execSQL(
                    """
                    DELETE FROM registry_snapshots
                    WHERE snapshot_id NOT IN (
                        SELECT snapshot_id FROM registry_snapshots
                        ORDER BY snapshot_id DESC LIMIT $MAX_ROLLING_SNAPSHOTS
                    )
                    """.trimIndent()
                )
                meta.edit()
                    .putLong("last_snapshot_time_ms", now)
                    .putString("last_snapshot_tile_ids", currentTileIds)
                    .apply()
            }
            
            if (reason != "clean_slate_starter") {
                appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_HOUSEHOLD_INITIALIZED, true)
                    .apply()
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(REGISTRY_TAG, "Transactional registry save failed ($reason):${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    fun saveSemanticResolution(deviceKey: String, resolutionJson: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("device_key", deviceKey)
            put("resolution_json", resolutionJson)
        }
        db.insertWithOnConflict("semantic_resolutions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadSemanticResolution(deviceKey: String): String? {
        val db = readableDatabase
        db.rawQuery("SELECT resolution_json FROM semantic_resolutions WHERE device_key = ?", arrayOf(deviceKey)).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    fun loadHealthyTileJsonObjects(): List<JSONObject> {
        initializeAndMigrate()
        val db = readableDatabase
        val results = mutableListOf<JSONObject>()

        db.rawQuery(
            """
            SELECT t.tile_id, t.tile_payload_json, d.normalized_mac, d.host_ip, d.port, d.canonical_guid, t.sub_endpoint_index
            FROM dashboard_tiles t
            LEFT JOIN canonical_devices d ON t.canonical_guid = d.canonical_guid
            ORDER BY t.sort_order ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tileId = cursor.getString(0).orEmpty()
                val payloadStr = cursor.getString(1).orEmpty()
                val persistedMac = cursor.getString(2).orEmpty()
                val hostIp = cursor.getString(3).orEmpty()
                val port = cursor.getInt(4)
                val canonicalGuid = cursor.getString(5).orEmpty()
                val subEndpointIndex = cursor.getInt(6)

                try {
                    val obj = JSONObject(payloadStr)
                    if (persistedMac.isNotEmpty()) {
                        obj.put("macAddress", persistedMac)
                    }
                    if (canonicalGuid.isNotEmpty()) {
                        obj.put("canonicalGuid", canonicalGuid)
                    }
                    if (subEndpointIndex > 0) {
                        obj.put("subEndpointIndex", subEndpointIndex)
                        if (!obj.optString("channelId", "").startsWith("SPLIT_SOCKET_", ignoreCase = true)) {
                            obj.put("channelId", "SPLIT_SOCKET_$subEndpointIndex")
                        }
                    }
                    if (hostIp.isNotEmpty()) {
                        val healedEndpoint = if (port > 0 && obj.optString("ipAddress", "").contains(":")) {
                            "$hostIp:$port"
                        } else hostIp
                        obj.put("ipAddress", healedEndpoint)
                    }
                    results.add(obj)
                } catch (e: Exception) {
                    quarantineCorruptRecord("dashboard_tiles:$tileId", payloadStr, e.message ?: "JSON parse error")
                }
            }
        }

        if (results.isNotEmpty()) return results

        val restoredFromSnapshot = restoreNewestSnapshot()
        if (restoredFromSnapshot.isNotEmpty()) {
            Log.w(REGISTRY_TAG, "Restored ${restoredFromSnapshot.size} tiles from rolling SQLite snapshot")
            JarvisLiveBridge.recordDeserializationCorruption(
                storeKey = DB_NAME,
                errorMessage = "Primary dashboard_tiles empty; restored from rolling SQLite snapshot",
                restoredFromSnapshot = true
            )
            persistTileJsonObjectsTransactional(restoredFromSnapshot, "auto_snapshot_recovery")
            return restoredFromSnapshot
        }

        return emptyList()
    }

    fun parseJsonArrayFaultTolerant(rawArrayJson: String, sourceStore: String): List<JSONObject> {
        val healthy = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(rawArrayJson)
            for (i in 0 until array.length()) {
                val rawElement = array.optString(i, "")
                try {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("id", "").isBlank() && obj.optString("title", "").isBlank()) {
                        quarantineCorruptRecord("$sourceStore[$i]", rawElement, "Missing mandatory id and title")
                    } else {
                        healthy.add(obj)
                    }
                } catch (elementErr: Exception) {
                    quarantineCorruptRecord("$sourceStore[$i]", rawElement, elementErr.message ?: "Element corrupt")
                }
            }
            return healthy
        } catch (outerErr: Exception) {
            quarantineCorruptRecord(sourceStore, rawArrayJson.take(2000), "Outer JSONArray corrupt: ${outerErr.message}")
            for (candidate in extractTopLevelJsonObjects(rawArrayJson)) {
                try {
                    val obj = JSONObject(candidate)
                    if (obj.optString("id", "").isNotBlank() || obj.optString("title", "").isNotBlank()) {
                        healthy.add(obj)
                    }
                } catch (salvageErr: Exception) {
                    quarantineCorruptRecord("$sourceStore:salvage", candidate, salvageErr.message ?: "Unrecoverable chunk")
                }
            }
            return healthy
        }
    }

    fun quarantineCorruptRecord(sourceStore: String, rawJson: String, errorDetail: String) {
        Log.e(REGISTRY_TAG, "CAT_11 Quarantined corrupt record from $sourceStore:$errorDetail")
        try {
            val values = ContentValues().apply {
                put("quarantine_id", UUID.randomUUID().toString())
                put("source_store", sourceStore)
                put("raw_json", rawJson)
                put("failure_category", "CAT_11")
                put("error_detail", errorDetail)
                put("quarantined_at_ms", System.currentTimeMillis())
            }
            writableDatabase.insert("quarantined_records", null, values)
        } catch (_: Exception) {
        }
    }

    fun updateDeviceEndpointAndMac(
        canonicalGuid: String,
        newHostIp: String,
        newPort: Int,
        learnedMac: String = "",
        protocolVersion: String = ""
    ) {
        val normalizedMac = CrossEcosystemIdentityCorrelator.normalizeMac(learnedMac)
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            if (newHostIp.isNotBlank()) put("host_ip", newHostIp)
            if (newPort in 1..65535) put("port", newPort)
            if (protocolVersion.isNotBlank()) put("protocol_version", protocolVersion.trim())
            if (normalizedMac.isNotEmpty()) {
                put("normalized_mac", normalizedMac)
                put(
                    "mac_variants_csv",
                    CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normalizedMac).joinToString(",")
                )
            }
            put("last_seen_ms", now)
            put("updated_at_ms", now)
        }
        val rows = db.update("canonical_devices", values, "canonical_guid = ?", arrayOf(canonicalGuid))
        if (rows == 0 && newHostIp.isNotBlank()) {
            db.update("canonical_devices", values, "host_ip = ?", arrayOf(newHostIp))
        }
        if (newHostIp.isNotBlank() && normalizedMac.isNotEmpty()) {
            CrossEcosystemIdentityCorrelator.recordApplicationLayerObservation(newHostIp, normalizedMac)
        }
    }

    fun resolveEndpointForDevice(deviceIdIpOrGuid: String): PersistedDeviceEndpoint? {
        val clean = deviceIdIpOrGuid.trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase(Locale.US)
        val hostOnly = EndpointSplitter.splitHostAndPort(clean).hostIp
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(clean)
        return runCatching {
            readableDatabase.rawQuery(
                """
                SELECT canonical_guid, host_ip, port, normalized_mac
                FROM canonical_devices
                WHERE canonical_guid = ?
                   OR LOWER(tuya_gw_id) = ?
                   OR LOWER(hardware_serial) = ?
                   OR LOWER(cloud_device_id) = ?
                   OR (? != '' AND host_ip = ?)
                   OR (? != '' AND normalized_mac = ?)
                ORDER BY updated_at_ms DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(clean, lower, lower, lower, hostOnly, hostOnly, normMac, normMac)
            ).use { c ->
                if (c.moveToFirst()) {
                    PersistedDeviceEndpoint(
                        canonicalGuid = c.getString(0).orEmpty(),
                        hostIp = c.getString(1).orEmpty(),
                        port = c.getInt(2),
                        normalizedMac = c.getString(3).orEmpty()
                    )
                } else null
            }
        }.getOrNull()
    }

    /**
     * Onboarding Section 7 + Changes 5.1/5.3/6.4:
     * Immediately persists deviceId, IP, MAC (+1/+2 BLE variants), protocol version, and vault key
     * in ACTIVATED / CAPABILITY_LEARNING state BEFORE capability discovery runs, protecting paired
     * hardware from unnecessary factory resets if capability sync times out.
     */
    @Synchronized
    fun persistBoundDeviceImmediately(
        deviceId: String,
        ipAddress: String,
        port: Int = 0,
        macAddress: String = "",
        localOrClientKey: String = "",
        providerId: ProviderId,
        suggestedTitle: String = "Smart Device",
        suggestedRoom: String = "",
        suggestedTileType: TileType = TileType.SMARTLIFE_DEVICE,
        protocolVersion: String = "",
        vendorId: String = "",
        productId: String = ""
    ): String {
        val cleanDeviceId = deviceId.trim()
        val parsedEp = EndpointSplitter.splitHostAndPort(ipAddress)
        val effectiveHostIp = parsedEp.hostIp
        val effectivePort = if (port in 1..65535) port else parsedEp.port
        val normalizedMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress)
        val variantsCsv = if (normalizedMac.isNotEmpty()) {
            CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normalizedMac).joinToString(",")
        } else ""

        // 1. Synchronously commit secret key into ClaraSecretVault first
        if (localOrClientKey.isNotBlank()) {
            when (providerId) {
                ProviderId.TUYA_LAN, ProviderId.TUYA_CLOUD ->
                    ClaraSecretVault.persistBoundTuyaCredentialsImmediate(
                        context = appContext,
                        deviceId = cleanDeviceId,
                        localKey = localOrClientKey,
                        ipAddress = effectiveHostIp,
                        macAddress = normalizedMac
                    )
                ProviderId.LG_WEBOS ->
                    ClaraSecretVault.putLgWebOsClientKey(
                        context = appContext,
                        ipAddress = effectiveHostIp,
                        clientKey = localOrClientKey,
                        deviceUuid = cleanDeviceId,
                        macAddress = normalizedMac
                    )
                ProviderId.MATTER_FABRIC ->
                    ClaraSecretVault.putMatterNodeNoc(appContext, cleanDeviceId, localOrClientKey)
                ProviderId.EWELINK_LAN ->
                    ClaraSecretVault.putEWeLinkDeviceKey(appContext, cleanDeviceId, localOrClientKey)
                ProviderId.TPLINK_KLAP ->
                    ClaraSecretVault.putTpLinkKlapHash(appContext, cleanDeviceId.ifBlank { effectiveHostIp }, localOrClientKey)
                else -> Unit
            }
        }

        // 2. Resolve deterministic canonical GUID
        val correlatedGuid = CrossEcosystemIdentityCorrelator.resolveCanonicalGuidForCandidate(
            rawMac = normalizedMac,
            hardwareSerial = cleanDeviceId,
            tuyaGwId = if (providerId == ProviderId.TUYA_LAN || providerId == ProviderId.TUYA_CLOUD) cleanDeviceId else "",
            matterNodeId = if (providerId == ProviderId.MATTER_FABRIC) cleanDeviceId else "",
            cloudDeviceId = cleanDeviceId
        )
        val canonicalGuid = when {
            !correlatedGuid.isNullOrBlank() -> correlatedGuid
            normalizedMac.isNotEmpty() -> "guid::mac::$normalizedMac"
            providerId == ProviderId.TUYA_LAN || providerId == ProviderId.TUYA_CLOUD ->
                "guid::tuya::${cleanDeviceId.lowercase(Locale.US)}"
            providerId == ProviderId.MATTER_FABRIC ->
                "guid::matter::${cleanDeviceId.lowercase(Locale.US)}"
            cleanDeviceId.isNotEmpty() ->
                "guid::sn::${cleanDeviceId.lowercase(Locale.US)}"
            else -> "guid::ep::$effectiveHostIp"
        }

        val isTuya = providerId == ProviderId.TUYA_LAN || providerId == ProviderId.TUYA_CLOUD
        val isMatter = providerId == ProviderId.MATTER_FABRIC
        val now = System.currentTimeMillis()
        val db = writableDatabase

        db.beginTransactionNonExclusive()
        try {
            val existingProto = getPersistedProtocolVersionInternal(db, canonicalGuid)
            val deviceValues = ContentValues().apply {
                put("canonical_guid", canonicalGuid)
                put("vendor_id", vendorId)
                put("product_id", productId)
                put("hardware_serial", if (!isTuya && !isMatter) cleanDeviceId else "")
                put("normalized_mac", normalizedMac)
                put("mac_variants_csv", variantsCsv)
                put("ha_entity_id", "")
                put("tuya_gw_id", if (isTuya) cleanDeviceId else "")
                put("nest_enterprise_id", "")
                put("matter_node_id", if (isMatter) cleanDeviceId else "")
                put("vehicle_vin", "")
                put("cloud_device_id", if (!isTuya && !isMatter) cleanDeviceId else "")
                put("host_ip", effectiveHostIp)
                put("port", effectivePort)
                put("protocol_version", protocolVersion.trim().ifBlank { existingProto })
                put("active_provider_id", providerId.id)
                put("fallback_providers_csv", "")
                put("auth_state", "AUTHENTICATED")
                put("subscription_state", "BOUND")
                put("lifecycle_state", DeviceLifecycleState.CAPABILITY_LEARNING.name)
                put("last_seen_ms", now)
                put("updated_at_ms", now)
            }
            val rows = db.update("canonical_devices", deviceValues, "canonical_guid = ?", arrayOf(canonicalGuid))
            if (rows == 0) {
                db.insertWithOnConflict("canonical_devices", null, deviceValues, SQLiteDatabase.CONFLICT_IGNORE)
            }

            CrossEcosystemIdentityCorrelator.indexPersistedRecord(
                canonicalGuid = canonicalGuid,
                normalizedMac = normalizedMac,
                macVariantsCsv = variantsCsv,
                hardwareSerial = if (!isTuya && !isMatter) cleanDeviceId else "",
                tuyaGwId = if (isTuya) cleanDeviceId else "",
                haEntityId = "",
                nestEnterpriseId = "",
                matterNodeId = if (isMatter) cleanDeviceId else "",
                cloudDeviceId = cleanDeviceId,
                hostIp = effectiveHostIp,
                port = effectivePort
            )
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(REGISTRY_TAG, "persistBoundDeviceImmediately failed for $canonicalGuid: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
        return canonicalGuid
    }

    fun getPersistedProtocolVersion(canonicalGuidOrTuyaId: String): String {
        val clean = canonicalGuidOrTuyaId.trim()
        if (clean.isEmpty()) return ""
        return runCatching {
            readableDatabase.rawQuery(
                """
                SELECT protocol_version FROM canonical_devices
                WHERE canonical_guid = ? OR LOWER(tuya_gw_id) = ? OR LOWER(hardware_serial) = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(clean, clean.lowercase(Locale.US), clean.lowercase(Locale.US))
            ).use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
        }.getOrDefault("")
    }

    fun updatePersistedProtocolVersion(canonicalGuidOrTuyaId: String, protocolVersion: String) {
        val cleanId = canonicalGuidOrTuyaId.trim()
        val cleanVer = protocolVersion.trim()
        if (cleanId.isEmpty() || cleanVer.isEmpty()) return
        runCatching {
            val values = ContentValues().apply {
                put("protocol_version", cleanVer)
                put("updated_at_ms", System.currentTimeMillis())
            }
            writableDatabase.update(
                "canonical_devices",
                values,
                "canonical_guid = ? OR LOWER(tuya_gw_id) = ?",
                arrayOf(cleanId, cleanId.lowercase(Locale.US))
            )
        }
    }

    @Synchronized
    fun purgeUnboundDeviceRecord(canonicalGuid: String) {
        val clean = canonicalGuid.trim()
        if (clean.isEmpty()) return
        runCatching {
            writableDatabase.delete("canonical_devices", "canonical_guid = ?", arrayOf(clean))
        }
    }

    private fun getPersistedProtocolVersionInternal(db: SQLiteDatabase, canonicalGuid: String): String {
        return runCatching {
            db.rawQuery(
                "SELECT protocol_version FROM canonical_devices WHERE canonical_guid = ? LIMIT 1",
                arrayOf(canonicalGuid)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
        }.getOrDefault("")
    }

    private fun restoreNewestSnapshot(): List<JSONObject> {
        readableDatabase.rawQuery(
            "SELECT payload_json, snapshot_id FROM registry_snapshots ORDER BY snapshot_id DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getString(0).orEmpty()
                val snapId = cursor.getLong(1)
                val parsed = parseJsonArrayFaultTolerant(payload, "snapshot:$snapId")
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyList()
    }

    private fun extractTopLevelJsonObjects(raw: String): List<String> {
        val chunks = mutableListOf<String>()
        var depth = 0
        var inQuotes = false
        var escaped = false
        var startIdx = -1

        for (i in raw.indices) {
            val ch = raw[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inQuotes) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (ch == '{') {
                    if (depth == 0) startIdx = i
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0 && startIdx >= 0) {
                        chunks.add(raw.substring(startIdx, i + 1))
                        startIdx = -1
                    }
                }
            }
        }
        return chunks
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile
        private var instance: ClaraDeviceRegistryStore? = null

        fun getInstance(context: Context): ClaraDeviceRegistryStore {
            return instance ?: synchronized(this) {
                instance ?: ClaraDeviceRegistryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
