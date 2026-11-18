package com.jarvis.smarthomelauncher.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TelemetryDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val DATABASE_NAME = "clara_telemetry.db"
        private const val DATABASE_VERSION = 9

        const val TABLE_DISCOVERY = "discovery_events"
        const val TABLE_DEVICE_LIFECYCLE = "device_lifecycle_events"
        const val TABLE_DEVICE_BLACKBOX = "device_blackbox_events"
        const val TABLE_CAPABILITY_SNAPSHOTS = "device_capability_snapshots"
        const val TABLE_TELEMETRY = "telemetry_points"
        const val TABLE_SECURITY_AUDIT = "security_audit_logs"
        const val TABLE_SELF_HEALING = "self_healing_logs"
        const val TABLE_DVR_SNAPSHOT = "dvr_snapshots"
        const val TABLE_AUTOMATION_LOG = "automation_logs"
        const val TABLE_HABIT_EVENT = "habit_events"

        @Volatile
        private var instance: TelemetryDatabaseHelper? = null

        @Synchronized
        fun getInstance(context: Context): TelemetryDatabaseHelper {
            if (instance == null) {
                instance = TelemetryDatabaseHelper(context.applicationContext)
            }
            return instance!!
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createDiscoveryTable(db)
        createLifecycleTable(db)
        createBlackBoxTable(db)
        createCapabilitySnapshotsTable(db)
        createTelemetryTable(db)
        createSecurityAuditTable(db)
        createSelfHealingTable(db)
        createDvrSnapshotTable(db)
        createAutomationLogTable(db)
        createHabitEventTable(db)
    }

    private fun createDiscoveryTable(db: SQLiteDatabase) {
        // Change 1.2 + Onboarding Section 8: End-to-End Discovery Event Recorder + RF & Session Context
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DISCOVERY (
                id TEXT PRIMARY KEY NOT NULL,
                sessionId TEXT NOT NULL,
                pairingSessionId TEXT NOT NULL DEFAULT '',
                timestampMs INTEGER NOT NULL,
                monotonicTimestampNs INTEGER NOT NULL DEFAULT 0,
                elapsedMs INTEGER NOT NULL,
                eventType TEXT NOT NULL,
                transportSource TEXT NOT NULL,
                hardwareId TEXT NOT NULL DEFAULT '',
                cloudId TEXT NOT NULL DEFAULT '',
                macAddress TEXT NOT NULL DEFAULT '',
                ipAddress TEXT NOT NULL DEFAULT '',
                port INTEGER NOT NULL DEFAULT 0,
                vendor TEXT NOT NULL DEFAULT '',
                confidenceScore REAL NOT NULL DEFAULT 0.0,
                qualificationStage TEXT NOT NULL DEFAULT '',
                outcomeReason TEXT NOT NULL DEFAULT '',
                rawPayload TEXT NOT NULL DEFAULT '',
                wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0,
                rssiDbm INTEGER NOT NULL DEFAULT -127,
                permissionState TEXT NOT NULL DEFAULT '',
                durationMs INTEGER NOT NULL DEFAULT 0,
                protocolResultCode TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_session ON $TABLE_DISCOVERY(sessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_pairing_session ON $TABLE_DISCOVERY(pairingSessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_timestamp ON $TABLE_DISCOVERY(timestampMs DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_mac ON $TABLE_DISCOVERY(macAddress)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_hardware ON $TABLE_DISCOVERY(hardwareId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_cloud ON $TABLE_DISCOVERY(cloudId)")
    }

    private fun createLifecycleTable(db: SQLiteDatabase) {
        // Change 1.3 & 1.5 + Onboarding Section 8: Monotonic Device Flight Recorder + RF & Session Context
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DEVICE_LIFECYCLE (
                id TEXT PRIMARY KEY NOT NULL,
                deviceId TEXT NOT NULL,
                sequenceNumber INTEGER NOT NULL,
                pairingSessionId TEXT NOT NULL DEFAULT '',
                timestampMs INTEGER NOT NULL,
                monotonicTimestampNs INTEGER NOT NULL DEFAULT 0,
                event TEXT NOT NULL,
                previousState TEXT NOT NULL DEFAULT '',
                newState TEXT NOT NULL DEFAULT '',
                activeTransport TEXT NOT NULL DEFAULT '',
                vendorDriver TEXT NOT NULL DEFAULT '',
                latencyMs INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                protocolResultCode TEXT NOT NULL DEFAULT '',
                failureCategory TEXT NOT NULL DEFAULT '',
                wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0,
                rssiDbm INTEGER NOT NULL DEFAULT -127,
                permissionState TEXT NOT NULL DEFAULT '',
                capabilitySchemaVersion INTEGER NOT NULL DEFAULT 0,
                capabilitySchemaHash TEXT NOT NULL DEFAULT '',
                capabilitySnapshotJson TEXT NOT NULL DEFAULT '',
                UNIQUE(deviceId, sequenceNumber)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifecycle_device_seq ON $TABLE_DEVICE_LIFECYCLE(deviceId, sequenceNumber)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifecycle_pairing_session ON $TABLE_DEVICE_LIFECYCLE(pairingSessionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lifecycle_timestamp ON $TABLE_DEVICE_LIFECYCLE(timestampMs DESC)")
    }

    private fun createCapabilitySnapshotsTable(db: SQLiteDatabase) {
        // Change 1.5: Versioned Hardware Capability Snapshots for Deterministic Timeline Replay
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CAPABILITY_SNAPSHOTS (
                id TEXT PRIMARY KEY NOT NULL,
                deviceId TEXT NOT NULL,
                schemaVersion INTEGER NOT NULL,
                lifecycleSequenceNumber INTEGER NOT NULL,
                timestampMs INTEGER NOT NULL,
                monotonicTimestampNs INTEGER NOT NULL DEFAULT 0,
                schemaHash TEXT NOT NULL,
                capabilityCount INTEGER NOT NULL DEFAULT 0,
                capabilitiesJson TEXT NOT NULL,
                UNIQUE(deviceId, schemaVersion)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_cap_snap_device_seq ON $TABLE_CAPABILITY_SNAPSHOTS(deviceId, lifecycleSequenceNumber ASC)"
        )
    }

    private fun createBlackBoxTable(db: SQLiteDatabase) {
        // Change 1.4 + Onboarding Section 8: Partitioned Per-Device Black Box Ring Buffer + RF Context
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DEVICE_BLACKBOX (
                id TEXT PRIMARY KEY NOT NULL,
                pairingSessionId TEXT NOT NULL DEFAULT '',
                timestampMs INTEGER NOT NULL,
                monotonicTimestampNs INTEGER NOT NULL DEFAULT 0,
                deviceId TEXT NOT NULL,
                deviceName TEXT NOT NULL,
                provider TEXT NOT NULL,
                endpointIp TEXT NOT NULL,
                domain TEXT NOT NULL DEFAULT 'COMMAND',
                category TEXT NOT NULL,
                "action" TEXT NOT NULL,
                outcome TEXT NOT NULL,
                latencyMs INTEGER NOT NULL,
                expectedState TEXT NOT NULL,
                actualState TEXT NOT NULL,
                errorSummary TEXT NOT NULL,
                rawPayload TEXT NOT NULL,
                wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0,
                rssiDbm INTEGER NOT NULL DEFAULT -127,
                permissionState TEXT NOT NULL DEFAULT '',
                protocolResultCode TEXT NOT NULL DEFAULT '',
                isPinned INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blackbox_device ON $TABLE_DEVICE_BLACKBOX(deviceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blackbox_pairing_session ON $TABLE_DEVICE_BLACKBOX(pairingSessionId)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_blackbox_device_pin_ts ON $TABLE_DEVICE_BLACKBOX(deviceId, isPinned, timestampMs DESC, monotonicTimestampNs DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_blackbox_device_domain ON $TABLE_DEVICE_BLACKBOX(deviceId, domain)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DISCOVERY")
            createDiscoveryTable(db)
        }
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICE_LIFECYCLE")
            createLifecycleTable(db)
        }
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICE_BLACKBOX")
            createBlackBoxTable(db)
        }
        if (oldVersion < 5) {
            if (oldVersion >= 3) {
                runCatching {
                    db.execSQL("ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN capabilitySchemaVersion INTEGER NOT NULL DEFAULT 0")
                }
                runCatching {
                    db.execSQL("ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN capabilitySchemaHash TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    db.execSQL("ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN capabilitySnapshotJson TEXT NOT NULL DEFAULT ''")
                }
            }
            createCapabilitySnapshotsTable(db)
        }
        if (oldVersion < 6) {
            if (oldVersion >= 2) {
                listOf(
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN pairingSessionId TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN rssiDbm INTEGER NOT NULL DEFAULT -127",
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN permissionState TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE $TABLE_DISCOVERY ADD COLUMN protocolResultCode TEXT NOT NULL DEFAULT ''",
                    "CREATE INDEX IF NOT EXISTS idx_discovery_pairing_session ON $TABLE_DISCOVERY(pairingSessionId)"
                ).forEach { sql -> runCatching { db.execSQL(sql) } }
            }
            if (oldVersion >= 3) {
                listOf(
                    "ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN pairingSessionId TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN rssiDbm INTEGER NOT NULL DEFAULT -127",
                    "ALTER TABLE $TABLE_DEVICE_LIFECYCLE ADD COLUMN permissionState TEXT NOT NULL DEFAULT ''",
                    "CREATE INDEX IF NOT EXISTS idx_lifecycle_pairing_session ON $TABLE_DEVICE_LIFECYCLE(pairingSessionId)"
                ).forEach { sql -> runCatching { db.execSQL(sql) } }
            }
            if (oldVersion >= 4) {
                listOf(
                    "ALTER TABLE $TABLE_DEVICE_BLACKBOX ADD COLUMN pairingSessionId TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE $TABLE_DEVICE_BLACKBOX ADD COLUMN wifiFrequencyMhz INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE $TABLE_DEVICE_BLACKBOX ADD COLUMN rssiDbm INTEGER NOT NULL DEFAULT -127",
                    "ALTER TABLE $TABLE_DEVICE_BLACKBOX ADD COLUMN permissionState TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE $TABLE_DEVICE_BLACKBOX ADD COLUMN protocolResultCode TEXT NOT NULL DEFAULT ''",
                    "CREATE INDEX IF NOT EXISTS idx_blackbox_pairing_session ON $TABLE_DEVICE_BLACKBOX(pairingSessionId)"
                ).forEach { sql -> runCatching { db.execSQL(sql) } }
            }
        }
        if (oldVersion < 7) {
            runCatching {
                db.execSQL("UPDATE $TABLE_DEVICE_LIFECYCLE SET deviceId = UPPER(deviceId) WHERE deviceId LIKE 'guid::mac::%'")
                db.execSQL("UPDATE $TABLE_CAPABILITY_SNAPSHOTS SET deviceId = UPPER(deviceId) WHERE deviceId LIKE 'guid::mac::%'")
                db.execSQL("UPDATE $TABLE_DEVICE_BLACKBOX SET deviceId = UPPER(deviceId) WHERE deviceId LIKE 'guid::mac::%'")
            }
        }
        if (oldVersion < 8) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TELEMETRY")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SECURITY_AUDIT")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SELF_HEALING")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DVR_SNAPSHOT")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_AUTOMATION_LOG")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_HABIT_EVENT")
            createTelemetryTable(db)
            createSecurityAuditTable(db)
            createSelfHealingTable(db)
            createDvrSnapshotTable(db)
            createAutomationLogTable(db)
            createHabitEventTable(db)
        }
        if (oldVersion < 9) {
            runCatching {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_mac ON $TABLE_DISCOVERY(macAddress)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_hardware ON $TABLE_DISCOVERY(hardwareId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_discovery_cloud ON $TABLE_DISCOVERY(cloudId)")
            }
        }
        onCreate(db)
    }

    private fun createTelemetryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TELEMETRY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestampEpochMs INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                deviceTitle TEXT NOT NULL,
                metricKey TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_telemetry_device ON $TABLE_TELEMETRY(deviceId, metricKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_telemetry_time ON $TABLE_TELEMETRY(timestampEpochMs DESC)")
    }

    private fun createSecurityAuditTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SECURITY_AUDIT (
                id TEXT PRIMARY KEY NOT NULL,
                timestampEpochMs INTEGER NOT NULL,
                actorRole TEXT NOT NULL,
                actionType TEXT NOT NULL,
                targetName TEXT NOT NULL,
                detail TEXT NOT NULL,
                succeeded INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sec_audit_time ON $TABLE_SECURITY_AUDIT(timestampEpochMs DESC)")
    }

    private fun createSelfHealingTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SELF_HEALING (
                id TEXT PRIMARY KEY NOT NULL,
                timestampEpochMs INTEGER NOT NULL,
                deviceId TEXT NOT NULL,
                deviceTitle TEXT NOT NULL,
                integrationName TEXT NOT NULL,
                stageReached INTEGER NOT NULL,
                stageLabel TEXT NOT NULL,
                succeeded INTEGER NOT NULL,
                diagnosticDetail TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_healing_time ON $TABLE_SELF_HEALING(timestampEpochMs DESC)")
    }

    private fun createDvrSnapshotTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DVR_SNAPSHOT (
                id TEXT PRIMARY KEY NOT NULL,
                timestampEpochMs INTEGER NOT NULL,
                cameraTileId TEXT NOT NULL,
                cameraTitle TEXT NOT NULL,
                roomName TEXT NOT NULL,
                aiEventType TEXT NOT NULL,
                localFilePathOrUri TEXT NOT NULL,
                summaryText TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dvr_time ON $TABLE_DVR_SNAPSHOT(timestampEpochMs DESC)")
    }

    private fun createAutomationLogTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATION_LOG (
                id TEXT PRIMARY KEY NOT NULL,
                timestampEpochMs INTEGER NOT NULL,
                ruleId TEXT NOT NULL,
                ruleName TEXT NOT NULL,
                triggerSource TEXT NOT NULL,
                status TEXT NOT NULL,
                userFriendlySummary TEXT NOT NULL,
                matchedBranchLabel TEXT NOT NULL,
                stepLogsJson TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_auto_log_time ON $TABLE_AUTOMATION_LOG(timestampEpochMs DESC)")
    }

    private fun createHabitEventTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HABIT_EVENT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestampEpochMs INTEGER NOT NULL,
                tileId TEXT NOT NULL,
                tileTitle TEXT NOT NULL,
                actionType TEXT NOT NULL,
                numericValue REAL NOT NULL,
                textValue TEXT NOT NULL,
                hourOfDay INTEGER NOT NULL,
                minuteOfHour INTEGER NOT NULL,
                isoDayOfWeek INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_habit_time ON $TABLE_HABIT_EVENT(timestampEpochMs DESC)")
    }
}
