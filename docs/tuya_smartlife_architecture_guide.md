# Clara Smart Home — Tuya & SmartLife Pairing and Control Architecture

This document is the single source of truth for how Clara initializes `ThingHomeSdk`, maintains its silent behind-the-scenes household account, pairs both Bluetooth-combo and Wi-Fi-only plugs (such as Panel Heaters, SmartKnight, and Jisusu), and dispatches instant local commands without port conflicts.

---

## 1. The Two Separate Tuya Key Pairs (Never Mix These Up)

Tuya's Developer Portal (`iot.tuya.com`) issues **two completely different types of credentials**. Mixing them up causes `Smart Home SDK Not Ready` or silent authentication failures.

### A. Android App SDK Keys (Required for `ThingHomeSdk`, BLE & SoftAP Pairing)
* **Where to find in Tuya Portal:** `App` -> `App SDK` -> `Clara Smart Home` -> `Get Key` -> **Android**.
* **Security Coupling:** Bound strictly to `applicationId = "com.jarvis.smarthomelauncher"`, the custom `app/libs/security-algorithm-1.0.0-beta.aar`, and your signing keystore's SHA-256 fingerprint.
* **Where stored in Clara:** `C:\projects\ClaraSmartHome\local.properties` (never committed to Git):
  ```properties
  tuyaAppKey=YOUR_ANDROID_APP_SDK_KEY
  tuyaAppSecret=YOUR_ANDROID_APP_SDK_SECRET
  ```
* **How wired into the APK:**
  1. `app/build.gradle.kts` imports `java.util.Properties` at line 1 (critical: writing `java.util.Properties()` inline inside `android {}` fails because Gradle's `java` plugin extension shadows the `java` package!).
  2. `build.gradle.kts` injects `manifestPlaceholders["TUYA_APP_KEY"]` and `manifestPlaceholders["TUYA_APP_SECRET"]`.
  3. `AndroidManifest.xml` exposes `<meta-data android:name="THING_SMART_APPKEY" ... />` and `<meta-data android:name="THING_SMART_SECRET" ... />`.
  4. `ClaraApplication.ensureTuyaSdkInitialized()` reads those Manifest metadata keys **first** so UI/Cloud settings can never overwrite the native SDK keys.

### B. Cloud Project OpenAPI Keys (Optional REST Schema & Key Sync)
* **Where to find in Tuya Portal:** `Cloud` -> `Development` -> `Clara Smart Home` -> `Overview` -> **Authorization Key** (`Access ID/Client ID` and `Access Secret/Client Secret`, Data Center: **Central Europe**, `https://openapi.tuyaeu.com`).
* **Mandatory Portal Link:** Under `Cloud` -> `Clara Smart Home` -> `Devices` -> **Link My App**, the `Clara Smart Home` App SDK must be linked with **Automatic Link (Recommended)** enabled. This allows OpenAPI queries to see devices paired by Clara's silent SDK session.
* **Where stored in Clara:** Entered in `Control Center -> Application Settings` (`BridgeSettings.tuyaAccessId` / `tuyaAccessSecret`, encrypted in `ClaraSecretVault`). Used by `TuyaCloudClient.kt` (`executeSignedRequest`).

---

## 2. Startup Order & Zero-Login Silent Account ("Clara Home")

Clara does **not** require the user to sign in with a personal SmartLife email or password to pair or control hardware.

### Startup Initialization Order (`ClaraApplication.onCreate`)
1. `SoLoader.init(this, false)` loads native C++ libraries first.
2. On the main process, `ClaraDeviceRegistryStore.initializeAndMigrate()` and `ClaraSecretVault.migrateAllLegacyStores(this)` **must run before** `ensureTuyaSdkInitialized(this)` so encrypted installation credentials and keys are available immediately.
3. `ClaraApplication.ensureInstallationIdentityProvisioned(this)` generates (once per tablet install) a unique cryptographic UID (`clara_home_<16-hex>`) and password (`Clara#<20-hex>!`) and stores them in `ClaraSecretVault`.

### Behind-the-Scenes Session (`TuyaDirectPairingManager.ensureSilentSessionAndHome`)
Whenever `ThingHomeSdk.getUserInstance().isLogin` is `false`:
1. Clara calls `ThingHomeSdk.getUserInstance().loginOrRegisterWithUid("44", silentUid, silentSecret, callback)`.
2. Tuya's EU cloud silently creates or restores the anonymous household account in the background.
3. Clara calls `queryHomeList()`, selects the saved `homeId` (or creates `"Clara Home"` via `createHome(...)` if empty), and caches `homeId` in `ClaraSecretVault`.
4. **Do Not Break:** Never re-add a preflight gate in `validateProvisioningPreflight()` that blocks pairing when `!ThingHomeSdk.getUserInstance().isLogin`—doing so breaks zero-login onboarding on fresh installs.

*(Note: If a user chooses `Matter / Cloud Account -> Link Official SmartLife / Tuya Account`, `TuyaApiClient.loginWithEmailAndPassword` signs into their personal SmartLife account across EU/US/CN/IN regions and `ensureSilentSessionAndHome` reuses `userInstance.isLogin == true` seamlessly.)*

---

## 3. Why Different Devices Use Different Pairing Modes

All onboarding runs through `OnboardingWizardViewModel` (`DashboardViewModel.kt`) and `UniversalOnboardingEngine.kt` under a single-device `pairingSessionMutex`. Radios are **never** scanned in the background on wizard open; only the radio pass for the user's chosen Step 1 card is activated.

### Mode A: Fast Blinking — Bluetooth LE Combo (`BLE_DIRECT` / `0xFD50`)
* **Hardware:** Modern dual-radio Tuya devices (e.g., Panel Heater, newer SmartKnight plugs).
* **How Discovery Works:**
  * `SmartHomeApiClient.discoverBleSetupPass()` scans for Tuya UUID `0xFD50` and manufacturer ID `0x07D0` for 8 seconds (inside a 20-second wizard timeout).
  * **Bound-Bit Filter:** Checks `(serviceData[0].toInt() and 0x80) != 0`. Bit 7 (`0x80`) means the device is *already paired* and broadcasting idle local-control beacons—Clara drops these so paired devices never reappear as new candidates.
  * **Combo MAC Offset Filter:** Combo chips advertise a BLE MAC that differs from their Wi-Fi MAC by $+1$, $-1$, $+2$, or $-2$ (including 48-bit byte-boundary carries). `ConnectedIdentityIndex.computeComboMacOffsetVariants()` suppresses all offset variants of onboarded tiles.
* **How GATT Pairing Works:**
  * Stops BLE scanning *before* opening the GATT link (`ThingHomeSdk.getBleOperator().stopLeScan()`) and calls `multiActivator.stopActivator(scanBean.uuid)` on failure before exponential backoff retry to prevent Android `GATT_ERROR 133`.

### Mode B: Slow Blinking / AP Mode (`AP_HOTSPOT` — `SmartLife-XXXX`, `SL-XXXX`, `Jisusu`)
* **Hardware:** 2.4 GHz Wi-Fi-only plugs without Bluetooth (e.g., **Jisusu** `BK7231N` plugs) or any device when your dual-band router (`Asus-Home`) steers the tablet onto 5 GHz.
* **Why Fast Blinking Misses Wi-Fi-Only Plugs:** A Wi-Fi-only plug in fast-blink mode has no Bluetooth radio—it sits in passive Wi-Fi monitor mode waiting for UDP length-encoded frames. If the tablet is associated with `Asus-Home` on 5 GHz ($\ge 4900\text{ MHz}$), 2.4 GHz EZ broadcast packets cannot cross bands.
* **How Clara's 5-Step SoftAP Flow Works:**
  1. **Put Plug in AP Mode:** Hold the plug button for 5–6 seconds until fast-blinking, then hold again for 5–6 seconds until **slow-blinking** (once every 2–3 seconds).
  2. **Fresh Hotspot Scan (`discoverSoftApPass`):** Triggers a Wi-Fi scan and filters `ScanResult.timestamp` (< 15 seconds old) so stale cached `SmartLife-XXXX` ghosts are ignored.
  3. **Online Token Pre-Fetch (`prefetchCloudPairingTokenSuspend`):** Fetches the 10-minute cloud pairing token *while the tablet still has internet on Home Wi-Fi* (Step 3).
  4. **Hotspot Bind & Credential Delivery (`findAndConnectToOpenPlugHotspot`):** Binds the app process via Android 10+ `WifiNetworkSpecifier` to the plug's open hotspot and runs `ActivatorModelEnum.THING_AP`. Holds the binding open until `device_cfg_send` / `device_bind_success` or until the plug reboots and drops its AP (`onLost`).
  5. **Home Wi-Fi Reconnect & Key Persistence:** Unbinds the SoftAP, awaits `awaitHomeWifiReconnect` (up to 15s), immediately persists `deviceId`, `localKey`, `ipAddress`, and `macAddress` into `ClaraSecretVault` + `ClaraDeviceRegistryStore` (`persistBoundTuyaBeanImmediately`), and runs a 45-second UDP 6666/6667 listener (`startPostOnboardingDiscoveryAcceleration`) to lock onto the plug's new LAN IP.

### Mode C: Pure 2.4 GHz Wi-Fi Broadcast (`PURE_EZ_BROADCAST`)
* **When Used:** Optional fallback button on the Fast Blinking timeout screen for older Wi-Fi-only plugs *only* when the tablet itself is connected on 2.4 GHz ($2400\text{--}2499\text{ MHz}$).
* **5 GHz Guard:** `TuyaDirectPairingManager.isTabletOn5GhzBand()` blocks pure EZ broadcast when tablet frequency is $\ge 4900\text{ MHz}$ and offers a 1-tap switch to **Slow Blinking / AP Mode**.

---

## 4. Local LAN Port 6668 Control & Why Plugs Get "Stuck ON" If Broken

This is the **most critical runtime invariant** in `TuyaNativeClient.kt`:

### The Single-Socket Protocol Rule (`v3.3` vs `v3.4` / `v3.5`)
1. Tuya smart plugs (especially Jisusu / `BK7231N` firmware) run Tuya LAN protocol **v3.4 or v3.5** and accept **only ONE TCP client connection on port 6668 at a time**.
2. `ThingHomeSdk` (`publishNativeDpsMap`) includes Tuya's official C++ LAN state machine supporting full `v3.3`, `v3.4` (HMAC-SHA256 session-key handshake), and `v3.5` (`0x6699` AES-GCM framing), with automatic fallback to MQTT/HTTP and real hardware ACK callbacks (`IResultCallback`).
3. Clara also contains a lightweight raw socket helper (`PersistentTuyaLanSocket` in `TuyaNativeClient.kt`).
4. **What Happened When It Broke:** Previously, `sendLocalLanDpCommand` tried `PersistentTuyaLanSocket` *before* `ThingHomeSdk`. On a `v3.4`/`v3.5` Jisusu plug:
   * Opening the raw Java socket kicked `ThingHomeSdk`'s C++ LAN client off port 6668.
   * `session.out.write(packet)` wrote bytes into Android's local kernel buffer in `3ms–8ms` and returned `true` (a false ACK!) while the plug firmware silently rejected the frame and stayed stuck ON.
5. **The Mandatory Rule in `TuyaApiClient.sendLocalLanDpCommand`:**
   * **Always route through `ThingHomeSdk` (`publishNativeDpsMap`) FIRST** whenever `ClaraApplication.isTuyaSdkReady()` is `true`, and close any competing `lanSocketPoolByIp` socket for that IP (`lanSocketPoolByIp.remove(resolvedIp)?.closeQuietly()`).
   * Only use `PersistentTuyaLanSocket` as a pure-offline fallback for legacy `protocolVersion == "3.3"` devices when `ThingHomeSdk` is unavailable.

---

## 5. Threading, Service & UI Guardrails (Do Not Regress)

1. **No Duplicate Dispatch in Modals (`SmartPlugTelemetryModal.kt`):**
   * Inside `SmartKnightLiveDpsModal`, `dispatchCapabilityCommand(...)` already dispatches the hardware command and records Black Box telemetry.
   * Its `onTileUpdated` callback **must** pass `persistCapabilityStateOnly` (which updates the modal and saves `liveTiles`), **never** `onUpdateTile` / `commitTileMutation`—otherwise `MainActivity.handleUpdateAnyTile` fires a second, third, and fourth recursive command storm within 50ms.
2. **No TCP Probes on `Dispatchers.Main` (`TuyaCloudClient.kt` & `SmartHomeApiClient.kt`):**
   * `TuyaApiClient.isDeviceReachableOrOnline()` probes TCP ports (`6668, 80, 9999, ...`). In `syncLiveTuyaStates()`, always compute reachability inside `withContext(Dispatchers.IO)` *before* switching to `withContext(Dispatchers.Main)`.
   * `SmartHomeApiClient.isPortOpenInternal()` keeps a defensive `Looper.myLooper() == Looper.getMainLooper()` guard returning `false` so UI threads can never throw `NetworkOnMainThreadException`.
3. **Pause Background Polling During Pairing (`SmartHomeConnectivityService.kt`):**
   * `SmartHomeConnectivityService.isPairingOrSoftApHandoffActive()` checks `JarvisLiveBridge.isPairingTuyaFlow.value`, `UniversalOnboardingEngine.isSingleSessionLocked()`, and `TuyaDirectPairingManager.isConnectedToDeviceHotspot()`.
   * When `true`, background 15s sync loops and `onNetworkTransition()` cache clears are paused so SoftAP Wi-Fi switching never wipes pre-fetched activator tokens or newly bound keys.
4. **Android 14+ Foreground Service Permission Order (`MainActivity.kt`):**
   * `SmartHomeConnectivityService` uses `foregroundServiceType="connectedDevice"`. Only call `startForegroundService()` inside `startConnectivityServiceIfPermitted()` after `BLUETOOTH_CONNECT` or `BLUETOOTH_SCAN` is granted to avoid `AppOps: op=START_FOREGROUND` rejections.

---

## 6. Fast Symptom-to-Fix Troubleshooting Table

| Symptom | Root Cause | Immediate Check / Fix |
| :--- | :--- | :--- |
| **`Smart Home SDK Not Ready`** on Step 3 of Wizard | `tuyaAppKey` / `tuyaAppSecret` missing from `local.properties` or Manifest placeholders empty | Check `C:\projects\ClaraSmartHome\local.properties` has the **Android App SDK** keys (`k7j8...`), sync Gradle, and check Logcat for `ThingHomeSdk initialized successfully`. |
| **Silent Tuya login fails with signature / security error** | Different PC `debug.keystore` SHA-256 than the SHA-256 registered in Tuya Portal (`Get SDK`) | Run `.\gradlew signingReport`, copy the `debug` SHA-256, add it under `Tuya Portal -> App -> Get SDK -> Android`, and update `app/libs/security-algorithm-*.aar` if regenerated. |
| **Fast-blinking plug (e.g., Jisusu) is not found in Step 2** | Plug is 2.4 GHz Wi-Fi-only (no Bluetooth chip) and tablet is on 5 GHz Wi-Fi | Hold plug button 5s again until **slow-blinking** (once every 2–3s) and pair via **Slow Blinking / AP Mode**. |
| **Plug pairs via SoftAP, works for 1 minute, then gets stuck ON/OFF** | Competing raw socket on port 6668 or `v3.4`/`v3.5` framing mismatch | Verify `TuyaApiClient.sendLocalLanDpCommand` calls `publishNativeDpsMap` **before** `PersistentTuyaLanSocket`, and check **Child Lock** is OFF in the plug's capability modal. |
| **Single button tap logs 3–4 `COMMAND_TX` entries in Black Box** | Modal `dispatchCapabilityCommand` passed `onUpdateTile` instead of `persistCapabilityStateOnly` | Ensure `SmartPlugTelemetryModal.kt` passes `onTileUpdated = persistCapabilityStateOnly` to every `dispatchCapabilityCommand` call. |
| **2.4 GHz Wi-Fi network blocked as 5 GHz** | Router has 2.4 GHz radio disabled or scan cache is empty | Tap **Rescan Wi-Fi** on Step 3; `validateSsidSupports24GhzByBssid` checks actual BSSID frequencies ($2400\text{--}2499\text{ MHz}$), never SSID names. |

---

## 7. Key Files Map

* `app/build.gradle.kts` & `local.properties` — Injects `TUYA_APP_KEY` and `TUYA_APP_SECRET` into Manifest placeholders.
* `core/ClaraApplication.kt` — Runs Vault migration first, provisions silent `clara_home_<hex>` identity, and initializes `ThingHomeSdk`.
* `core/ClaraSecretVault.kt` & `core/ClaraDeviceRegistryStore.kt` — Hardware-backed encrypted storage for Wi-Fi passwords, `homeId`, and 16-byte `localKey`s.
* `integrations/TuyaSupportClients.kt` (`TuyaDirectPairingManager`) — Silent UID session, BSSID 2.4 GHz validator, BLE `0xFD50` scanner, SoftAP `WifiNetworkSpecifier` handoff, and 45s post-onboarding UDP 6666/6667 accelerator.
* `integrations/TuyaNativeClient.kt` (`TuyaApiClient`) — `DeviceBean` DP schema introspection and `ThingHomeSdk`-first local LAN/MQTT command publisher.
* `integrations/TuyaCloudClient.kt` — OpenAPI EU/US/CN/IN signed REST fallback, official SmartLife login, and IO-safe background state polling.
* `ui/viewmodels/DashboardViewModel.kt` (`OnboardingWizardViewModel`) — Drives the deterministic Step 1–5 single-device onboarding UI.