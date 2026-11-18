![img.png](img.png)This consolidated Master Architecture and Remediation Blueprint merges every P0 defect fix, security hardening measure, concurrency repair, persistence migration, and technical-debt cleanup across all eleven audit categories into six dependency-ordered phases. 
Master Phase & Coverage Matrix
Phase	Primary Scope	Work Items	Debt Categories Resolved	Core Outcome
Phase 1	Vault, Boot and Backup	1.1 to 1.6	P0 Bugs, Security, Legacy, Performance	Zero-loss secrets and V2 restore
Phase 2	Identity and SQLite	2.1 to 2.7	Persistence, Duplication, Parallel Systems	Single GUID, hash, and DB truth
Phase 3	Onboarding and LAN	3.1 to 3.6	Protocol Drift, Parallel Flows, Perf	Locked pairing and v3.4/v3.5 LAN
Phase 4	Audio, Security and Perf	4.1 to 4.4	Concurrency, Security, Bloat, Perf	Safe audio, LAN auth, fast logs
Phase 5	Decoupling and Cleanup	5.1 to 5.6	Drift, God Classes, Sprawl, Dead Code	Pure core layer and clean UI
Phase 6	Rebuild Verification	Gates 1 to 6	End-to-End Regression Prevention	Deterministic wall-tablet stability
Execute the phases sequentially so duplicated stores and competing identity builders are retired before refactoring higher-level engines. Enforce five permanent architectural invariants across every change: 
•	Single Credential Authority: Store all keys, tokens, PINs, and passwords exclusively in ClaraSecretVault with zero secondary wrappers. 
•	Single Registry Truth: Persist all device identities, capabilities, and tiles solely in clara_device_registry.db without XML mirrors. 
•	Single Identity Spec: Compute MAC normalization, combo offsets, GUIDs, and schema hashes in one canonical core utility. 
•	One-Way Layering: Enforce strict ui → engines and services → integrations → core dependencies with zero UI or HTTP in core. 
•	Bounded SQLite Telemetry: Keep all time-series points and forensic traces inside pruned clara_telemetry.db ring buffers. 
Phase 1: Vault Integrity, Boot Isolation & Portable V2 Backup
1.1 Multi-Process Boot Guard, SDK Overrides & Off-Thread Startup
Target files: ClaraApplication.kt, ClaraSecretVault.kt. 
What fails: Cold boots risk multi-process file-lock collisions on the encrypted vault, stall the main thread with synchronous decryption and JSON parsing, and ignore user-saved Tuya SDK credential overrides. 
Why it happens: ClaraApplication.onCreate() calls ensureTuyaSdkInitialized(this) before checking if (!isMainProcess) return, allowing Tuya's background :monitor process to open ClaraSecretVault and DeviceRepository concurrently with the main process. Inside ensureTuyaSdkInitialized(), DeviceRepository.loadBridge() runs on the main thread—decrypting bridge secrets and loading five JSON log stores—while never reading ClaraSecretVault.getTuyaSdkAppKeyOverride() or getTuyaSdkAppSecretOverride(). 
How to fix: Move all ClaraSecretVault and DeviceRepository access strictly behind isMainProcess. Allow :monitor to read only PackageManager.GET_META_DATA manifest keys, wire getTuyaSdkAppKeyOverride() and getTuyaSdkAppSecretOverride() into main-process initialization, and remove synchronous loadBridge() calls from onCreate().
1.2 Non-Destructive Keyset Recovery in ClaraSecretVault
Target files: ClaraSecretVault.kt. 
What fails: Any transient I/O contention or KeyStore hiccup during startup permanently deletes the master encryption key and wipes all paired device credentials. 
Why it happens: createEncryptedPrefsWithRecovery() catches broad Exception on the first buildEncryptedPrefs() call and immediately executes purgeCorruptedKeyset(), deleting MasterKey.DEFAULT_MASTER_KEY_ALIAS from AndroidKeyStore alongside clara_secret_vault_v1 and its Tink keyset preference files. 
How to fix: Retry buildEncryptedPrefs() up to three times with short backoff on transient errors, and invoke purgeCorruptedKeyset() only when the exception is KeyPermanentlyInvalidatedException or AEADBadTagException after archiving a timestamped copy of the raw XML file for forensic inspection.
Kotlin
private fun createEncryptedPrefsWithRecovery(context: Context): SharedPreferences {
    for (attempt in 1..3) {
        try {
            return buildEncryptedPrefs(context, VAULT_FILE_NAME)
        } catch (e: Exception) {
            val unrecoverable = e is android.security.keystore.KeyPermanentlyInvalidatedException ||
                e.cause is javax.crypto.AEADBadTagException
            if (!unrecoverable && attempt < 3) {
                Thread.sleep(60L * attempt)
                continue
            }
            if (!unrecoverable) throw e
            break
        }
    }
    purgeCorruptedKeyset(context)
    return buildEncryptedPrefs(context, VAULT_FILE_NAME)
}
1.3 Enforcing Migration Gate & Scrubbing Mixed Legacy XML Stores
Target files: ClaraSecretVault.kt. 
What fails: Every app launch re-scans thirteen legacy SharedPreferences XML files on disk, while sensitive tokens inside google_home_jarvis_prefs_v10 and clara_native_music_prefs_v7 remain in plaintext XML. 
Why it happens: migrateAllLegacyStores() sets KEY_MIGRATION_V2_COMPLETE to true at the end of execution but never checks that flag on entry. Furthermore, legacyEditor.remove(rawKey) is gated behind if (isDedicatedSecretStore), which explicitly excludes google_home_jarvis_prefs_v10 and clara_native_music_prefs_v7, copying matched secrets into the vault without deleting the plaintext originals. 
How to fix: Remove the if (isDedicatedSecretStore) guard around legacyEditor.remove(rawKey) so every key matching isSensitiveKey is scrubbed from its source XML, bump the completion flag to clara_vault_13_store_migration_v3_complete, and return immediately at line 1 of migrateAllLegacyStores() once that flag is true.
1.4 Retiring Secondary KeyStore Encryption & Scrubbing SQLite Secrets
Target files: DeviceRepository.kt, ClaraDeviceRegistryStore.kt, Models.kt. 
What fails: Secrets are encrypted twice under two separate hardware keys (MasterKey plus clara_jarvis_master_aes_key_v1), while SmartTile.secretCode is serialized into SQLite tile payloads, snapshots, and quarantine records. 
Why it happens: DeviceRepository.encryptSecret() prepends ENC_GCM_V1: using its own AndroidKeyStore key before writing values into ClaraSecretVault or serializeSingleTileToJson(). Removing ENC_GCM_V1: without removing secretCode from tile JSON would spill plaintext device keys into clara_device_registry.db. 
How to fix: Run a one-time migration unwrapping all ENC_GCM_V1: strings into ClaraSecretVault under canonical keys (device::secret::<canonicalGuid>, iptv::xtream_password, wifi::guest_password), remove secretCode from serializeSingleTileToJson() so SQLite never stores keys, leave salted PIN_SHA256_V1: hashes unwrapped, and delete encryptSecret() and decryptSecret().
1.5 Vaulting Plaintext API Keys, Fixing EZVIZ OAuth & Stopping Legacy Dual-Writes
Target files: ClaraEntertainmentStore.kt, DeviceRepository.kt, EzvizCloudApiClient.kt. 
What fails: tmdb_api_key_v3 is stored in plaintext XML, EZVIZ OAuth sessions disappear immediately after login, and saveBridge() re-populates legacy plaintext credential files. 
Why it happens: ClaraEntertainmentStore.setTmdbApiKey() writes directly to clara_entertainment_prefs_v1; EzvizCloudApiClient.kt deletes its OAuth token from SharedPreferences after vaulting it in saveEzvizOAuthTokens but still reads the deleted SharedPreferences key in getSavedOAuthSession; and DeviceRepository.saveBridge() calls TpLinkTapoKasaClient.saveTpLinkCredentials(), EWeLinkSonoffClient.saveEWeLinkCredentials(), and MqttAndDiyClient.saveMqttBrokerConfig(). 
How to fix: Migrate tmdb_api_key_v3 into ClaraSecretVault, update EzvizCloudApiClient.getSavedOAuthSession to read ClaraSecretVault.getEzvizAccessToken(context) and getEzvizApiDomain(context), and delete the three legacy credential-helper calls from DeviceRepository.saveBridge().
1.6 Portable PBKDF2 Backup V2 & Complete SQLite Restore
Target files: DeviceRepository.kt, ClaraDeviceRegistryStore.kt, ClaraEntertainmentStore.kt. 
What fails: Restoring a household backup after a factory reset or tablet swap loses every hardware localKey, LG client-key, Matter NOC, camera PIN, newer bridge integration, entertainment item, and SQLite tile. 
Why it happens: exportEncryptedBackupJson() embeds hardware-bound ENC_GCM_V1: strings inside the backup JSON (which decryptSecret() silently turns into "" on any new KeyStore), derives its outer cipher key with a single unsalted SHA-256 hash defaulting to the installation secret, omits 20+ BridgeSettings fields (Hubitat, Homey, Octopus, Powerwall, GivEnergy, SolarEdge, UniFi, security temperatures), omits clara_entertainment_store_v1.json, and omits per-device ClaraSecretVault keys. Worse, importEncryptedBackupJson() writes tiles only to KEY_TILES_JSON in SharedPreferences, which ClaraDeviceRegistryStore.loadHealthyTileJsonObjects() ignores whenever SQLite already has starter tiles. 
How to fix: Introduce CLARA_BACKUP_V2: encrypted with AES-256-GCM using a key derived via PBKDF2WithHmacSHA256 (600,000 iterations, 16-byte random salt, 12-byte random IV) from a mandatory user passphrase. Export all ClaraSecretVault key-value pairs, all BridgeSettings properties, and ClaraEntertainmentStore items in plaintext inside the AEAD payload. On import, write vaultSecrets to ClaraSecretVault, restore clara_entertainment_store_v1.json, and call clearAllForCleanSlate(emptyList()) followed by persistTileJsonObjectsTransactional() on ClaraDeviceRegistryStore.
Phase 2: Identity Unification, SQLite Persistence & State Deduplication
2.1 Single Canonical GUID & 48-Bit Combo MAC Engine
Target files: CrossEcosystemIdentityCorrelator.kt, ClaraDeviceRegistryStore.kt, ThemeAndModels_remaining.kt, UniversalOnboardingEngine.kt, DiagnosticsRecorder.kt. 
What fails: Identical physical hardware is assigned conflicting guid:: identifiers across discovery, tile persistence, and Black Box partitions. 
Why it happens: Four separate GUID builders disagree on casing and precedence: CanonicalIdentityResolver.buildCanonicalGuid() outputs lowercase guid::mac:: and ranks serialNumber ahead of MAC, whereas LegacyIdUntangler.classify() and DeviceIdentityResolver output uppercase guid::mac:: and rank MAC ahead of serial, while DeviceLifecycleRecorder.resolveCanonicalGuid() adds guid::device:: fallbacks. Combo BLE/Wi-Fi MAC offset math (+1/-1/+2/-2) is likewise duplicated four times. 
How to fix: Delete CanonicalIdentityResolver and all duplicate MAC helpers, consolidating 100% of MAC normalization, 48-bit integer ±2 carry plus locally-administered bit-flip variant math, and GUID resolution inside CrossEcosystemIdentityCorrelator. Enforce one strict priority order: correlated uppercase guid::mac::AABBCCDDEEFF, ecosystem hardware ID (guid::tuya::, guid::matter::, guid::nest::, guid::vin::), hardware serial (guid::sn::), and bridge entity (guid::ha::). Add a SQLite upgrade step in both databases normalizing existing guid::mac:: keys to uppercase.
2.2 Single Capability Schema Hasher, Shared Codec & Immutable SmartTile
Target files: DeviceRepository.kt, ThemeAndModels_remaining.kt, DiagnosticsRecorder.kt, Models.kt. 
What fails: Background state polls fire false CAPABILITY_EVOLVED transitions and snapshot writes, while Group Card icon changes fail to save to disk. 
Why it happens: computeCapabilitySchemaHash() in ThemeAndModels_remaining.kt joins fields with ; and includes rendererType and presets, whereas DeviceRepository.computeCapabilitySchemaHash() joins fields with pipe characters and includes safetyTier. Every SmartTile constructed with the default semicolon hash mismatches the repository's pipe hash on first poll. Meanwhile, SmartTile declares var groupIconName and var groupCategoryIcon as mutable properties on a data class, so mutating them in place mutates the cached instance inside cachedTileJsonById, causing cachedPair.first == t to skip re-serialization. 
How to fix: Delete DeviceRepository.computeCapabilitySchemaHash() and standardize on one CapabilitySchemaHasher over structural hardware fields (key, dpId, kind, isWritable, minValue, maxValue, stepValue, unit, sorted enumOptions). Share CapabilitySnapshotCodec for both SQLite and flight-recorder capability JSON, and replace both var icon properties on SmartTile with a single val groupCategoryIcon: String = GroupCardIconOption.LIGHTING.key.
2.3 Fixing SQLite Foreign-Key Cascade Wipes & Orphaned Rows
Target files: ClaraDeviceRegistryStore.kt. 
What fails: Every saveTiles() transaction silently deletes device_discovery_fingerprints rows, deleting the final dashboard tile fails to clear SQLite, and removed devices linger forever in canonical_devices. 
Why it happens: ClaraDeviceRegistryStore enables ON DELETE CASCADE foreign keys and then writes parent rows using SQLiteDatabase.CONFLICT_REPLACE, which SQLite executes as DELETE followed by INSERT—wiping child tables on every save. Additionally, DELETE FROM dashboard_tiles only runs inside if (activeTileIds.isNotEmpty()), and canonical_devices is never pruned when tiles are removed. 
How to fix: Replace CONFLICT_REPLACE on canonical_devices and dashboard_tiles with an update-first helper (db.update, falling back to CONFLICT_IGNORE insert when zero rows match). Execute db.delete("dashboard_tiles", null, null) when activeTileIds is empty, and delete canonical_devices rows not referenced by dashboard_tiles unless their lifecycle_state is in active onboarding (AUTHENTICATED, ACTIVATED, CAPABILITY_LEARNING).
2.4 Eliminating Tile XML Mirroring, Snapshot Churn & Detached Sidecar Prefs
Target files: DeviceRepository.kt, ClaraDeviceRegistryStore.kt, Theme.kt, ThemeAndModels_remaining.kt. 
What fails: Every 80ms tile save writes four redundant full-state copies across SQLite and XML, while six small feature preferences fragment configuration across separate XML files. 
Why it happens: saveTiles() updates SQLite tables, appends a full JSON array to registry_snapshots on every single call, and writes two full JSON copies (KEY_TILES_JSON and __verified_backup_snapshot) to SharedPreferences. Meanwhile, clara_capability_classification_v2, clara_registry_meta_v2, clara_entertainment_prefs_v1, clara_hero_clock_split_prefs, clara_unified_grid_cards_v2, and clara_room_photo_uris_v1 each open independent preference files. 
How to fix: Delete KEY_TILES_JSON and writeVerifiedJsonWithSnapshot tile writes in DeviceRepository so clara_device_registry.db is the sole tile store. Throttle registry_snapshots inserts to structural membership changes or 15-minute intervals. Persist SemanticClassifier resolutions and registry metadata inside clara_device_registry.db, and merge clock-split, unified-grid, and room-photo settings into one DashboardLayoutStore.
2.5 Moving Time-Series & Operational Logs to SQLite + Reactive ViewModel Feeds
Target files: DeviceRepository.kt, TelemetryDatabaseHelper.kt, DashboardViewModel.kt. 
What fails: High-frequency wattage telemetry rewrites a massive XML file on every sample, and UI polling causes main-thread frame drops.
Why it happens: DeviceRepository stores up to 2,400 TelemetryDataPoint samples plus SecurityAuditLogEntry, SelfHealingActionLog, DvrEventSnapshot, AutomationLogEntry, and HabitActuationEvent lists as JSON strings inside google_home_jarvis_prefs_v10, while DashboardViewModel.kt reloads JSON stores on the main thread every two seconds. 
How to fix: Migrate telemetry_points and the five operational log tables into clara_telemetry.db using batched background inserts on Dispatchers.IO with indexed ring-buffer pruning, delete the XML JSON keys, and replace the two-second polling loop in DashboardViewModel.kt with reactive StateFlow collectors.
2.6 Bounded Lifecycle/Snapshot Pruning & Indexed Discovery Queries
Target files: DiagnosticsRecorder.kt, TelemetryDatabaseHelper.kt. 
What fails: clara_telemetry.db grows without bound over months of wall-tablet operation, and opening device diagnostics triggers an unindexed full-table scan that drops RF metadata. 
Why it happens: DeviceLifecycleRecorder never deletes old rows from device_lifecycle_events or device_capability_snapshots, while DiscoveryFlightRecorder.getCorrelatedDiscoveryEvents() runs SELECT * FROM discovery_events with selection = null and omits reading pairingSessionId, wifiFrequencyMhz, rssiDbm, permissionState, durationMs, and protocolResultCode from the cursor. 
How to fix: Prune device_lifecycle_events to retain the first 15 onboarding rows (sequenceNumber 1 to 15) plus the newest 500 operational rows per deviceId, and prune device_capability_snapshots to the newest 10 versions per deviceId. Add indexes on macAddress, hardwareId, and cloudId in discovery_events, push filtering into a parameterized SQL WHERE clause, and populate all six missing RF columns.
2.7 Retiring Parallel Watchlists, Duplicate Hold Maps & Six-Way Endpoint Caches
Target files: ClaraEntertainmentStore.kt, DeviceRepository.kt, ClaraDeviceRegistryStore.kt. 
What fails: Watchlist edits race with uninitialized disk state, date-only releases show fake "Today 20:00" timestamps, legacy BridgeSettings re-saves purged show lists, and device IPs/holds drift across six caches. 
Why it happens: ClaraEntertainmentStore coexists with BridgeSettings.trackedMediaShows; its mutateItem() and deleteItemPermanently() skip checking isInitialized before writing _items.value to disk; and parseIsoToLocalNoonMs() sets Calendar.HOUR_OF_DAY to 20, triggering hasExactTime = true in formatCountdownLabel(). Concurrently, DeviceStateRepository duplicates SmartTile.manualHoldUntilEpochMs, and device IPs/keys are cached across six separate maps. 
How to fix: Remove trackedMediaShows from BridgeSettings, call an internal ensureLoadedLocked(appContext) inside storeMutex.withLock at the start of every ClaraEntertainmentStore mutation, set HOUR_OF_DAY and MINUTE to 0 in parseIsoToLocalNoonMs(), delete DeviceStateRepository, and route all IP/MAC/key lookups through ClaraDeviceRegistryStore (write-through memory index) and ClaraSecretVault.
Phase 3: Onboarding Engine, Protocol Routing & LAN Hardware Clients
3.1 Fixing Stage History Corruption & Suspending Pairing Mutex
Target files: UniversalOnboardingEngine.kt. 
What fails: Unified onboarding logs identical previousState and newState values on every step, and BLE/SoftAP Wi-Fi provisioning releases the single-device pairing lock immediately while hardware provisioning is still running. 
Why it happens: emitUnifiedStageRecord() assigns activeUnifiedStageFlow.value = stage on its first line and then reads activeUnifiedStageFlow.value.name as previousState. In onboardResolvedIdentity(), the AutoProvisioningRequired branch launches callback-based TuyaDirectPairingManager.startDirectPairing() on Dispatchers.Main and immediately returns Result.failure(Exception("AUTO_PROVISIONING_STARTED")), exiting pairingSessionMutex.withLock. 
How to fix: Capture val previous = activeUnifiedStageFlow.value.name before updating activeUnifiedStageFlow.value, and wrap TuyaDirectPairingManager.startDirectPairing() inside withTimeout(90_000L) { suspendCancellableCoroutine { ... } } inside pairingSessionMutex.withLock so the mutex holds until tile commit or failure.
3.2 Preserving Explicit ProviderId & Fixing Cloud/Bridge Misrouting
Target files: UniversalOnboardingEngine.kt, Enums.kt. 
What fails: Cloud-discovered Nest, Imou, EZVIZ, SmartThings, and Tuya devices store "CLOUD_ACCOUNT_API" (which maps to ProviderId.UNKNOWN), while Hubitat, Homey, Tesla Wall Connector, and Energy Storage tiles have their provider overwritten with SHELLY_HTTP. 
Why it happens: TransportRadioType.toProviderId() is dead code that is never called; DeviceIdentityResolver sets primaryProviderId = fp.transport.name. Worse, extraBridgesJob wraps Hubitat, Homey, Tesla Gen3, and storage tiles in TransportRadioType.SHELLY_TASMOTA_HTTP, which ProviderId.fromRawKey() maps to ProviderId.SHELLY_HTTP. 
How to fix: Add val explicitProviderId: ProviderId? = null to DeviceFingerprint, populate it on every bridge, energy, camera, and cloud scanner job, and resolve primaryProviderId via (fp.explicitProviderId ?: fp.transport.toProviderId(fp.suggestedType)).id.
3.3 Sub-Addressing Split Two-Gang Sockets
Target files: UniversalOnboardingEngine.kt, DeviceRepository.kt, ClaraDeviceRegistryStore.kt. 
What fails: Splitting a dual-gang smart plug into two dashboard tiles causes SQLite row overwrites and prevents DHCP IP healing from updating both sockets. 
Why it happens: splitOrMergeTwoGangTile() clones identical canonicalGuid, serialNumber, and entityId onto both tiles, and ConnectedIdentityIndex.knownGuidToTileId is a 1:1 ConcurrentHashMap that overwrites the first socket's tileId with the second. 
How to fix: Keep one hardware canonical_devices row for the physical plug (guid::mac::...), change ConnectedIdentityIndex maps to store Set of String tile IDs per canonicalGuid so healExistingTileIpIfMatched() updates every child socket tile simultaneously, and key dashboard_tiles by tile_id with an explicit sub_endpoint_index (1 or 2).
3.4 Consolidating Onboarding Pipelines, Progress Flows & Legacy Modals
Target files: UniversalOnboardingEngine.kt, Models.kt, SetupWizardTabs.kt, ManageAndPairModals.kt, SetupWizardScreen.kt. 
What fails: Three parallel onboarding pipelines, four pairing-state flows, triple flight loggers, and legacy token-clobbering modals compete across the UI and engine layers. 
Why it happens: scanCandidatesForEntryMode() (OnboardingCandidateDevice), runUnifiedDiscoveryPass() (ResolvedDeviceIdentity), and onboardCompatibilityTile() coexist alongside DiscoveryLifecycleLogger, unrendered wireless state in SetupWizardScreen.kt, and legacy modals in ManageAndPairModals.kt that overwrite Nest OAuth refresh tokens with "nest_web_session_active". 
How to fix: Unify wizard scans onto ResolvedDeviceIdentity and a single OnboardingSessionStateFlow, route legacy callers exclusively through onboardResolvedIdentity(), delete DiscoveryLifecycleLogger calls in favor of the SQLite recorders, strip dead wizard UI state, and remove the "nest_web_session_active" overwrite in ManageAndPairModals.kt.
3.5 Restoring Tuya v3.4/v3.5 LAN Sockets, Nest Web JS & Parallel Recovery
Target files: TuyaNativeClient.kt, TuyaCloudClient.kt, NestSdmApiClient.kt, RecoveryEngine.kt. 
What fails: Newer Tuya/Lytmi v3.4 and v3.5 devices fail local TCP 6668 actuation, Nest Web login crashes with a JS syntax error, and Stage 1 LAN recovery stalls for 13 seconds.
Why it happens: sendLocalLanDpCommand in TuyaNativeClient.kt gates raw socket dispatch to v3.3 despite containing v3.4/v3.5 framing helpers; an automated refactor injected Kotlin catch (e: Throwable) inside the JS string in NestSdmApiClient.kt; RecoveryEngine.kt awaits Tuya UDP and mDNS scans sequentially; and integrations spawn unpooled HttpURLConnection instances. 
How to fix: Read ClaraDeviceRegistryStore.getInstance(ctx).getPersistedProtocolVersion(deviceId) in TuyaNativeClient.kt to dispatch v3.3, v3.4 (HMAC-SHA256 session negotiation), and v3.5 (AES-GCM 0x00006699) frames locally; delete dead proxy/QR stubs in TuyaCloudClient.kt; fix JS catch (err) in NestSdmApiClient.kt; run UDP and mDNS recovery concurrently via coroutineScope { async ... } in RecoveryEngine.kt; and share one pooled OkHttpClient singleton across all HTTP integrations.
3.6 Lease-Based MulticastLock, API 34 Radio Modernization & Permission Classification
Target files: DiscoveryRadioCoordinator.kt, ErrorClassifier.kt, DiagnosticsRecorder.kt. 
What fails: Overlapping mDNS scans prematurely drop the Wi-Fi MulticastLock, deprecated Android Wi-Fi/NSD APIs return "unknown ssid" or serialize slowly, and missing OS radio permissions are misreported as AUTHENTICATION_FAILURE. 
Why it happens: When the 20-second watchdog in DiscoveryRadioCoordinator resets activeLockCount to zero, an older scan's finally block decrements and releases a newer scan's lock. Meanwhile, RF/permission probes are triplicated across DiscoveryRadioCoordinator, OnboardingRfContextProbe, and ErrorClassifier, using deprecated WifiManager.connectionInfo and NsdManager.resolveService(), while auditRadioPermissionsAndServices() tags missing permissions as AUTHENTICATION_FAILURE. 
How to fix: Replace activeLockCount with ConcurrentHashMap UUID leases so release() only fires when zero active leases remain. Consolidate all three RF helpers into RadioEnvironmentProbe using ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) on Android 12+ for WifiInfo and NsdManager.registerServiceInfoCallback on Android 14+ (API 34+) for parallel mDNS resolution, and change auditRadioPermissionsAndServices() to emit FailureCategory.TRANSPORT_FAILURE.
Phase 4: Audio Concurrency, Network Security & Recorder Performance
4.1 Main-Thread Audio Queue Confinement, Managed Scopes & CDN Stream TTL
Target files: ClaraInAppMusicPlayer.kt. 
What fails: Concurrent track skips and background prefetchers race on currentQueue, ducking/volume calls spawn unmanaged coroutines, and cached audio URLs fail with HTTP 403 after CDN expiry. 
Why it happens: currentQueue (mutableListOf) and currentIndex are mutated across both Dispatchers.IO and Dispatchers.Main without locks; initContextIfNeeded(), setVoiceAssistantDucking(), and setVolumePercent() instantiate orphan CoroutineScope(Dispatchers.Main) objects; streamCache has no TTL; and playback state is duplicated across ClaraInAppMusicPlayer and JarvisLiveBridge. 
How to fix: Confine all currentQueue and currentIndex mutations to a single persistent playerMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), add a 45-minute expiresAtEpochMs check to ResolvedAudioStream in streamCache, re-resolve fresh stream URLs on ExoPlayer HTTP 403/410 errors, and expose canonical StateFlow properties from ClaraInAppMusicPlayer referenced directly by UI consumers.
4.2 RFC1918 Cleartext Scoping, WebView Lockdown & Local Hub Bearer Auth
Target files: AndroidManifest.xml, ClaraApplication.kt. 
What fails: Global cleartext traffic allows plaintext internet calls, permissive WebViews expose JS bridges, and the wall-tablet Local Hub Server lacks inbound request authentication. 
Why it happens: AndroidManifest.xml enables global cleartext traffic for LAN devices without blocking public HTTP, WebViews enable mixed content and broad @JavascriptInterface objects, and KEY_LOCAL_HUB_API_TOKEN provisioned in ClaraApplication must guard inbound LAN HTTP endpoints. 
How to fix: Configure network_security_config.xml (cleartextTrafficPermitted="false" for base-config, allowing only .local domains) paired with an OkHttpClient interceptor that permits http:// strictly to RFC1918/link-local IP literals (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) while rejecting public cleartext hosts. Replace @JavascriptInterface with origin-restricted WebViewCompat.addWebMessageListener, disable mixed content, and require constant-time Authorization: Bearer validation (MessageDigest.isEqual) against ClaraApplication.getOrCreateLocalHubToken() on all Local Hub Server routes.
4.3 Multi-Secret DiagnosticsSanitizer & Sensitive Export Hardening
Target files: DiagnosticsRecorder.kt, ClaraSecretVault.kt. 
What fails: Checking an unconfigured SSID overwrites and disables Wi-Fi password redaction in logs, while diagnostic exports copy unflagged logs to the global clipboard and world-readable external storage. 
Why it happens: DiagnosticsSanitizer.registerTransientWifiSecretForRedaction() stores a single transientSensitiveWifiLiteral string and resets it to "" whenever getWifiPasswordForSsid() passes a string shorter than four characters. Meanwhile, exportDiagnosticReport() writes directly to ClipboardManager without EXTRA_IS_SENSITIVE and writes plaintext to getExternalFilesDir(). 
How to fix: Replace transientSensitiveWifiLiteral with ConcurrentHashMap.newKeySet() so registered Wi-Fi secrets are never cleared by empty lookups, attach PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } to ClipData.description.extras, and write exported reports to File(context.cacheDir, "diagnostics") shared via FileProvider.
4.4 Debounced Per-Device Black Box Flows, Precompiled Formatters & Test Extraction
Target files: DiagnosticsRecorder.kt, ClaraEntertainmentStore.kt, ThemeAndModels_remaining.kt. 
What fails: Every device poll copies and sorts thousands of trace records synchronously inside a mutex, allocates SimpleDateFormat and Regex instances in hot loops, and ships a synthetic 2,090-event DB flood test in production UI. 
Why it happens: DeviceBlackBoxRecorder.appendPartitionedInternal() calls publishAllPartitionsLocked() on every event—merging and sorting up to 1,025 events per device while holding partitionLock—and ensureHydratedLocked() runs synchronous SQLite reads if called before async init completes. DiagnosticsSanitizer.formatTime(), ClaraEntertainmentStore.buildDashboardSnapshot(), and SmartTile.multiGangSocketCapabilities() allocate formatters/regexes per call, while verifyPartitionIsolationGate() writes guid::selftest:: rows to live SQLite. 
How to fix: Emit updates on per-device MutableStateFlow partitions and merge/debounce (250ms on Dispatchers.Default) only while FlightRecorderHubModal is actively subscribed (SharingStarted.WhileSubscribed(5000)). Replace SimpleDateFormat with immutable java.time.format.DateTimeFormatter, hoist SOCKET_KEY_REGEX and SWITCH_KEY_REGEX to top-level constants, and move verifyPartitionIsolationGate() plus its "Verify 2k Poll Gate" button to src/androidTest.
Phase 5: Layer Decoupling, Model Decomposition & Bloat/Sprawl Purge
5.1 Inverting Upward Core Dependencies & Stripping Wildcard Imports
Target files: ClaraApplication.kt, DeviceRepository.kt, ClaraInAppMusicPlayer.kt, ErrorClassifier.kt, UniversalOnboardingEngine.kt. 
What fails: core cannot compile without integrations, engines, services, and ui, while loadBridge() triggers UI/network side effects and activeDashboardTilesRef mutates UI state lists directly from backend repos. 
Why it happens: Core files paste import com.jarvis.smarthomelauncher.ui.* wildcards and directly call TuyaApiClient, LgThinQWebOsClient, JarvisLiveBridge, SmartHomeApiClient, SmartHomeConnectivityService, SpotifyHiddenWebPlayer, and CircuitBreakerOpenException. 
How to fix: Define pure interfaces in core (NetworkTopologyObserver, DeviceUnbindHandler, AudioPlaybackTransport, and TransientServiceUnavailableException), inject implementations from application startup, strip all upward and wildcard imports, remove activeDashboardTilesRef in favor of DeviceRepository StateFlow updates, and make loadBridge() a pure read without theme or flow side effects.
5.2 Moving Compose UI & HTTP Clients Out of Core + Restoring Corrupted Icons
Target files: ModularPopups.kt, DiagnosticsRecorder.kt, Theme.kt, ThemeAndModels_remaining.kt. 
What fails: core hosts full Material3 Compose modals where UTF-8 encoding corruption turned close buttons, thermostat minus buttons, degree symbols, header badges, and TV D-pad arrows into blank " " strings, alongside three HttpURLConnection directory clients. 
Why it happens: UI dialogs (ModularDeviceControlDialog, WidgetHeaderPopupDialog, FlightRecorderHubModal), theme state (Theme.kt), HTTP scrapers (FootballFixturesDirectoryClient, NextEpisodeDirectoryClient, LiveRadioDirectoryClient), and TuyaApiClient provisioning locks were dumped into core during AI refactors. 
How to fix: Move ModularPopups.kt to ui.popups, FlightRecorderHubModal to ui.modals, and Theme.kt to ui.theme. Replace every blank " " glyph string with vector Icons.Default (Close, Remove, Add, KeyboardArrowUp, KeyboardArrowDown, KeyboardArrowLeft, KeyboardArrowRight, Whatshot, Tv, Speaker, Power), drive ModularDeviceControlDialog from CapabilityControlSpec rather than title substring guessing, and move the three directory HTTP clients and Tuya provisioning mutexes into integrations.
5.3 Isolating Non-IoT Lifestyle Features
Target files: Models.kt, Enums.kt, ClaraEntertainmentStore.kt, ThemeAndModels_remaining.kt. 
What fails: Core smart-home schemas are bloated with UK council bin schedules, ESPN football fixtures, Xtream/M3U IPTV channel mappers, rail commute boards, and TV/movie watchlists. 
Why it happens: Lifestyle dashboard widgets were embedded directly into BridgeSettings, Enums.kt, and Models.kt instead of an isolated feature boundary. 
How to fix: Extract Council Bins, Football/IPTV, Commute/Guest Wi-Fi, and Entertainment models, stores, and clients into a self-contained feature.lifestyle package with its own persistence slice.
5.4 Decomposing SmartTile & BridgeSettings God Classes
Target files: Models.kt, DeviceRepository.kt. 
What fails: SmartTile (~95 properties) and BridgeSettings (~115 properties) duplicate live state between top-level scalars (isActive, targetTemperature, volume, fanSpeedLevel, lytmiHdmiInput) and capabilities currentValue strings, causing constant serialization drift. 
Why it happens: Every new device type or setting appended flat fields onto SmartTile and BridgeSettings rather than composing domain sub-models. 
How to fix: Slim SmartTile down to ~22 core identity, grid, lifecycle, and authority fields plus capabilities: List and a sealed DomainState (SwitchEnergyState, ClimateState, MediaSyncState, VehicleTelemetryState, CameraSecurityState), computing scalar convenience properties directly from capabilities / DomainState. Partition BridgeSettings into DashboardLayoutConfig, HouseholdSecurityConfig, and EcosystemEndpointsConfig.
5.5 Collapsing Widget/Renderer Taxonomies & Lazy Home Graph Evaluation
Target files: Enums.kt, DeviceRepository.kt, ThemeAndModels_remaining.kt. 
What fails: Four overlapping widget enums and three capability-renderer enums fragment UI routing, an 8-word appliance filter is copy-pasted four times, and a 9-primitive LLM Knowledge Graph rebuilds on every tile save. 
Why it happens: Brand-specific TileType entries (SMARTKNIGHT_PLUG, PROBREEZE_FAN, AIRROBO_VACUUM) overlap with DeviceClass, DeviceSubtype, ServiceClass, and DashboardWidgetType, while SemanticControlRole, CapabilityRendererType, and CapabilityWidgetRenderer map the same controls three times, and saveTiles() triggers scheduleAsyncGraphRebuild() on every mutation. 
How to fix: Separate DashboardWidgetType services from generic hardware archetypes (DeviceClass + DeviceSubtype + ProviderId), collapse the three control enums into one CapabilityControlSpec, centralize the 8-word non-heater appliance check (plug, socket, pond, pump, kettle, fryer, coffee, washer) inside SemanticClassifier, rename DashboardCardSlot.ROOM_PHOTO to PRIMARY_UTILITY_SLOT, and compute rebuildDigitalHomeGraph() lazily only when queried by the Voice Assistant or Knowledge Graph UI.
5.6 Complete Dead Code, Legacy Shim & AI Residue Purge
Target files: UniversalOnboardingEngine.kt, DeviceRepository.kt, Models.kt, Enums.kt, ThemeAndModels_remaining.kt, ModularPopups.kt, MainActivity.kt, ClockWidgets.kt. 
What fails: Unreachable branches, shadowed extensions, empty repository shells, prototype VIN scrubbers, fake reliability defaults, and AI prompt tags clutter the codebase and mislead debugging. 
Why it happens: Iterative AI passes deprecated or bypassed old code paths without deleting them. 
How to fix: Delete hardcoded val isBle = false / val isSoftAp = false / val isNestWeaveBle = false dead branches, unread enableSilentEzBroadcast, uncalled determineOnboardingRequirement(), and @Deprecated no-ops (triggerSilentBackgroundEzBroadcastIfConfigured, runHandsFreeAutoOnboardingBatchIfEnabled) in UniversalOnboardingEngine.kt. In core, delete the shadowed val SmartTile.macAddress extension, unused MediaLifestyleRepository, HouseholdUtilityRepository, and CredentialVaultRepository, duplicate SmartTile getters (isActivelyHeating, supportsEnergyTelemetry, subtype, hasDualSockets), non-hardware DeviceSubtype entries (RADIO, TV_TRACKER, CALENDAR_HUB, FOOTBALL_TRACKER, GUEST_WIFI, MUSIC_PLAYER), magic sensorUnit strings ("VOLUME_HERO", "RADIO_HERO"), legacy isTall, unimplemented vehicle enums, runtime SYNTHETIC_DUMMY_VINS scrubbers (after running once in SQLite migration), placeholder debug text in WidgetHeaderPopupDialog, hardcoded 98.5f/99.4f defaults in ClaraReliabilityKpis, and prompt-tag comments ("PASS 05", "CRIT-04"). Finally, remove if (true) in MainActivity.kt and @Suppress variable shadowing in ClockWidgets.kt. 
Phase 6: Deterministic Rebuild & Soak Verification Gates
1.	Cold-Boot Gate: Force-stop and relaunch 10 times with :monitor active; verify zero KeyStore resets, KEY_MIGRATION_V2_COMPLETE short-circuiting, and onCreate() under 80ms. 
2.	Backup V2 Gate: Export a passphrase V2 snapshot, clear app data to destroy KeyStore keys, restore V2, and verify SQLite tiles, watchlists, and LAN keys work immediately. 
3.	Zero-Churn SQLite Gate: Run 50 poll cycles; confirm zero false CAPABILITY_EVOLVED rows, preserved device_discovery_fingerprints child rows, and 100% uppercase MAC GUIDs. 
4.	Offline LAN Hardware Gate: Unplug router WAN internet; verify sub-second local control on LG webOS TV, Lytmi Sync Box, Tuya v3.3/v3.4/v3.5 plugs, Nest Mini Cast, and RTSP 554 cameras. 
5.	Pairing & Split-Socket Gate: Provision a BLE/AP plug verifying pairingSessionMutex holds until tile commit, split a 2-gang plug, change its DHCP IP, and confirm both tiles heal. 
6.	24/7 Wall-Tablet Soak Gate: Inject 5,000 state polls and telemetry samples; verify static XML size, capped SQLite ring buffers, zero leaked MulticastLock leases, and redacted Wi-Fi secrets. 

