package com.jarvis.smarthomelauncher.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

object ClaraSecretVault {
    private const val TAG = "ClaraSecretVault"
    private const val VAULT_FILE_NAME = "clara_secret_vault_v1"
    private const val KEY_KEYSET_PREFS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    private const val VALUE_KEYSET_PREFS = "__androidx_security_crypto_encrypted_prefs_value_keyset__"

    @Volatile
    private var vaultInstance: SharedPreferences? = null

    fun getVault(context: Context): SharedPreferences {
        vaultInstance?.let { return it }
        synchronized(this) {
            vaultInstance?.let { return it }
            val appContext = context.applicationContext
            val created = createEncryptedPrefsWithRecovery(appContext)
            vaultInstance = created
            return created
        }
    }

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

    private fun buildEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun purgeCorruptedKeyset(context: Context) {
        runCatching {
            val prefsFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$VAULT_FILE_NAME.xml")
            if (prefsFile.exists()) {
                val archiveFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/${VAULT_FILE_NAME}_corrupted_${System.currentTimeMillis()}.xml")
                prefsFile.copyTo(archiveFile, overwrite = true)
                Log.w(TAG, "Archived corrupted vault XML for forensic inspection: ${archiveFile.absolutePath}")
            }
        }
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
        runCatching { context.deleteSharedPreferences(VAULT_FILE_NAME) }
        runCatching { context.deleteSharedPreferences(KEY_KEYSET_PREFS) }
        runCatching { context.deleteSharedPreferences(VALUE_KEYSET_PREFS) }
    }

    fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        return getVault(context).getString(key, defaultValue)
    }

    fun putString(context: Context, key: String, value: String?) {
        val editor = getVault(context).edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
        editor.apply()
    }

    fun remove(context: Context, key: String) {
        getVault(context).edit().remove(key).apply()
    }

    /**
     * Atomically reads a secret from a legacy plaintext SharedPreferences file,
     * writes it into the encrypted vault, and removes it from the plaintext file.
     */
    fun migrateStringKey(
        context: Context,
        legacyPrefsName: String,
        legacyKey: String,
        vaultKey: String = "${legacyPrefsName}::$legacyKey"
    ): String? {
        val vault = getVault(context)
        val existingVaultValue = vault.getString(vaultKey, null)
        val legacyPrefs = context.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)

        if (!existingVaultValue.isNullOrEmpty()) {
            if (legacyPrefs.contains(legacyKey)) {
                legacyPrefs.edit().remove(legacyKey).apply()
            }
            return existingVaultValue
        }

        val legacyValue = legacyPrefs.getString(legacyKey, null)
        if (!legacyValue.isNullOrEmpty()) {
            vault.edit().putString(vaultKey, legacyValue).apply()
            legacyPrefs.edit().remove(legacyKey).apply()
            return legacyValue
        }
        return null
    }

    // =========================================================================
    // PASS 05: UNIFIED 13-STORE MIGRATION & TYPED HARDWARE / OAUTH ACCESSORS
    // (Findings F-01, F-08, F-09, F-23, F-28, F-29, F-30, F-31)
    // =========================================================================

    private const val KEY_MIGRATION_V3_COMPLETE = "clara_vault_13_store_migration_v3_complete"

    val LEGACY_PREF_STORES = listOf(
        "google_home_jarvis_prefs_v10",
        "clara_installation_identity_v1",
        "clara_direct_pairing_prefs",
        "clara_native_music_prefs_v7",
        "clara_ezviz_account_prefs",
        "clara_imou_camera_prefs_v1",
        "clara_tplink_tapo_kasa_prefs_v1",
        "clara_ewelink_sonoff_prefs_v1",
        "clara_mqtt_broker_prefs_v1",
        "clara_lg_webos_tv_prefs_v2",
        "clara_tuya_consumer_session_v1",
        "clara_vehicle_oauth_sessions_v1",
        "clara_nest_sdm_prefs_v1"
    )

    private val SENSITIVE_KEY_TOKENS = listOf(
        "token", "secret", "password", "passwd", "pass", "key", "pin",
        "cookie", "jwt", "session", "oauth", "auth", "klap", "noc", "cert"
    )

    /**
     * Migrates all 13 legacy SharedPreferences credential stores into ClaraSecretVault (F-09)
     * and scrubs plaintext secret entries from legacy XML files.
     */
    @Synchronized
    fun migrateAllLegacyStores(context: Context) {
        val appCtx = context.applicationContext
        val vault = getVault(appCtx)
        if (vault.getBoolean(KEY_MIGRATION_V3_COMPLETE, false)) return
        val vaultEditor = vault.edit()
        var migratedCount = 0

        for (prefsName in LEGACY_PREF_STORES) {
            runCatching {
                val legacyPrefs = appCtx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val allEntries = legacyPrefs.all
                if (allEntries.isEmpty()) return@runCatching

                val legacyEditor = legacyPrefs.edit()
                var modifiedLegacy = false

                for ((rawKey, rawVal) in allEntries) {
                    val strVal = (rawVal as? String)?.trim().orEmpty()
                    if (strVal.isEmpty()) continue

                    val lowerKey = rawKey.lowercase()
                    val isDedicatedSecretStore = prefsName != "google_home_jarvis_prefs_v10" &&
                            prefsName != "clara_native_music_prefs_v7"
                    val isSensitiveKey = isDedicatedSecretStore ||
                            SENSITIVE_KEY_TOKENS.any { token -> lowerKey.contains(token) }

                    if (isSensitiveKey) {
                        val namespacedKey = "${prefsName}::$rawKey"
                        if (vault.getString(namespacedKey, null).isNullOrEmpty()) {
                            vaultEditor.putString(namespacedKey, strVal)
                            migratedCount++
                        }
                        // Canonical promotion for frequently accessed keys
                        promoteCanonicalKeyIfMatched(vaultEditor, prefsName, rawKey, strVal)
                        legacyEditor.remove(rawKey)
                        modifiedLegacy = true
                    }
                }
                if (modifiedLegacy) {
                    legacyEditor.apply()
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed migrating legacy store $prefsName: ${e.message}")
            }
        }

        vaultEditor.putBoolean(KEY_MIGRATION_V3_COMPLETE, true).apply()
        if (migratedCount > 0) {
            Log.i(TAG, "Migrated $migratedCount credential entries into ClaraSecretVault.")
        }
    }

    private fun promoteCanonicalKeyIfMatched(
        editor: SharedPreferences.Editor,
        prefsName: String,
        rawKey: String,
        value: String
    ) {
        when {
            rawKey.startsWith("local_key_") -> {
                val devId = rawKey.removePrefix("local_key_").trim()
                if (devId.isNotEmpty()) editor.putString(tuyaLocalKeyVaultKey(devId), value)
            }
            prefsName == "clara_lg_webos_tv_prefs_v2" && rawKey.startsWith("client_key_") -> {
                val tvId = rawKey.removePrefix("client_key_").trim()
                if (tvId.isNotEmpty()) editor.putString(lgClientKeyVaultKey(tvId), value)
            }
            prefsName == "clara_ezviz_account_prefs" && rawKey == "access_token" -> {
                editor.putString(KEY_EZVIZ_ACCESS_TOKEN, value)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. TUYA / SMART LIFE 16-BYTE LOCAL KEY, SDK OVERRIDES & HOME SESSION
    // -------------------------------------------------------------------------
    private const val KEY_TUYA_SESSION_SID = "tuya::consumer_session_sid"
    private const val KEY_TUYA_SESSION_UID = "tuya::consumer_session_uid"
    private const val KEY_TUYA_USER_KEY = "tuya::consumer_user_key"
    private const val KEY_TUYA_REGION_ENDPOINT = "tuya::consumer_region_endpoint"
    private const val KEY_TUYA_SELECTED_HOME_ID = "tuya::selected_home_id"
    private const val KEY_TUYA_SELECTED_HOME_NAME = "tuya::selected_home_name"
    private const val KEY_TUYA_SDK_APP_KEY_OVERRIDE = "tuya::sdk_app_key_override"
    private const val KEY_TUYA_SDK_APP_SECRET_OVERRIDE = "tuya::sdk_app_secret_override"

    private fun tuyaLocalKeyVaultKey(deviceIdOrMac: String): String =
        "tuya::local_key::${deviceIdOrMac.trim().lowercase()}"

    fun getTuyaLocalKey(context: Context, deviceId: String, macAddress: String = ""): String? {
        val vault = getVault(context)
        val primary = deviceId.trim().lowercase()
        if (primary.isNotEmpty()) {
            vault.getString(tuyaLocalKeyVaultKey(primary), null)
                ?.takeIf { it.length == 16 }
                ?.let { return it }
            vault.getString("clara_tuya_consumer_session_v1::local_key_$deviceId", null)
                ?.takeIf { it.length == 16 }
                ?.let { return it }
        }
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        if (normMac.isNotEmpty()) {
            vault.getString(tuyaLocalKeyVaultKey(normMac), null)
                ?.takeIf { it.length == 16 }
                ?.let { return it }
            for (variant in CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normMac)) {
                vault.getString(tuyaLocalKeyVaultKey(variant.lowercase()), null)
                    ?.takeIf { it.length == 16 }
                    ?.let { return it }
            }
        }
        return null
    }

    fun putTuyaLocalKey(context: Context, deviceId: String, localKey: String, macAddress: String = "") {
        val cleanKey = localKey.trim()
        if (cleanKey.isEmpty()) return
        val editor = getVault(context).edit()
        val primary = deviceId.trim().lowercase()
        if (primary.isNotEmpty()) {
            editor.putString(tuyaLocalKeyVaultKey(primary), cleanKey)
        }
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        if (normMac.isNotEmpty()) {
            editor.putString(tuyaLocalKeyVaultKey(normMac), cleanKey)
            for (variant in CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normMac)) {
                editor.putString(tuyaLocalKeyVaultKey(variant.lowercase()), cleanKey)
            }
        }
        editor.apply()
    }

    /**
     * Onboarding Section 7: Synchronously commits deviceId, IP, MAC (+1/+2 combo variants),
     * and 16-byte localKey immediately after Binding before capability discovery starts.
     */
    fun persistBoundTuyaCredentialsImmediate(
        context: Context,
        deviceId: String,
        localKey: String,
        ipAddress: String = "",
        macAddress: String = ""
    ) {
        val cleanId = deviceId.trim().lowercase()
        val cleanKey = localKey.trim()
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        val editor = getVault(context).edit()
        if (cleanId.isNotEmpty() && cleanKey.isNotEmpty()) {
            editor.putString(tuyaLocalKeyVaultKey(cleanId), cleanKey)
        }

        if (normMac.isNotEmpty() && cleanKey.isNotEmpty()) {
            editor.putString(tuyaLocalKeyVaultKey(normMac), cleanKey)
            for (variant in CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normMac)) {
                editor.putString(tuyaLocalKeyVaultKey(variant.lowercase()), cleanKey)
            }
        }
        editor.commit()
    }

    fun saveTuyaSdkCredentialsOverride(context: Context, appKey: String, appSecret: String) {
        getVault(context).edit()
            .putString(KEY_TUYA_SDK_APP_KEY_OVERRIDE, appKey.trim())
            .putString(KEY_TUYA_SDK_APP_SECRET_OVERRIDE, appSecret.trim())
            .apply()
    }

    fun getTuyaSdkAppKeyOverride(context: Context): String? =
        getString(context, KEY_TUYA_SDK_APP_KEY_OVERRIDE)?.takeIf { it.isNotBlank() }

    fun getTuyaSdkAppSecretOverride(context: Context): String? =
        getString(context, KEY_TUYA_SDK_APP_SECRET_OVERRIDE)?.takeIf { it.isNotBlank() }

    fun saveTuyaSelectedHome(context: Context, homeId: Long, homeName: String = "") {
        if (homeId <= 0L) return
        getVault(context).edit()
            .putString(KEY_TUYA_SELECTED_HOME_ID, homeId.toString())
            .putString(KEY_TUYA_SELECTED_HOME_NAME, homeName.trim())
            .apply()
    }

    fun getTuyaSelectedHomeId(context: Context): Long? =
        getString(context, KEY_TUYA_SELECTED_HOME_ID)?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    fun getTuyaSelectedHomeName(context: Context): String? =
        getString(context, KEY_TUYA_SELECTED_HOME_NAME)?.takeIf { it.isNotBlank() }

    fun saveTuyaConsumerSession(
        context: Context,
        sid: String,
        uid: String,
        userKey: String = "",
        regionEndpoint: String = ""
    ) {
        getVault(context).edit()
            .putString(KEY_TUYA_SESSION_SID, sid.trim())
            .putString(KEY_TUYA_SESSION_UID, uid.trim())
            .putString(KEY_TUYA_USER_KEY, userKey.trim())
            .putString(KEY_TUYA_REGION_ENDPOINT, regionEndpoint.trim())
            .apply()
    }

    fun getTuyaConsumerSid(context: Context): String? =
        getString(context, KEY_TUYA_SESSION_SID)?.takeIf { it.isNotBlank() }

    fun getTuyaConsumerUid(context: Context): String? =
        getString(context, KEY_TUYA_SESSION_UID)?.takeIf { it.isNotBlank() }

    fun getTuyaConsumerUserKey(context: Context): String? =
        getString(context, KEY_TUYA_USER_KEY)?.takeIf { it.isNotBlank() }

    fun getTuyaConsumerRegionEndpoint(context: Context): String? =
        getString(context, KEY_TUYA_REGION_ENDPOINT)?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // 2. TP-LINK TAPO / KASA KLAP V1/V2 CREDENTIALS & HASHES (F-09, F-28)
    // -------------------------------------------------------------------------
    private const val KEY_TPLINK_USERNAME = "tplink::cloud_username"
    private const val KEY_TPLINK_PASSWORD = "tplink::cloud_password"

    private fun tpLinkDeviceAuthHashKey(deviceIdOrIp: String): String =
        "tplink::klap_auth_hash::${deviceIdOrIp.trim().lowercase()}"

    fun saveTpLinkCredentials(context: Context, username: String, password: String) {
        getVault(context).edit()
            .putString(KEY_TPLINK_USERNAME, username.trim())
            .putString(KEY_TPLINK_PASSWORD, password)
            .apply()
    }

    fun getTpLinkUsername(context: Context): String? =
        getString(context, KEY_TPLINK_USERNAME)
            ?: migrateStringKey(context, "clara_tplink_tapo_kasa_prefs_v1", "tapo_username", KEY_TPLINK_USERNAME)

    fun getTpLinkPassword(context: Context): String? =
        getString(context, KEY_TPLINK_PASSWORD)
            ?: migrateStringKey(context, "clara_tplink_tapo_kasa_prefs_v1", "tapo_password", KEY_TPLINK_PASSWORD)

    fun putTpLinkKlapHash(context: Context, deviceIdOrMac: String, authHashHex: String) {
        if (deviceIdOrMac.isBlank() || authHashHex.isBlank()) return
        putString(context, tpLinkDeviceAuthHashKey(deviceIdOrMac), authHashHex.trim())
    }

    fun getTpLinkKlapHash(context: Context, deviceIdOrMac: String): String? =
        getString(context, tpLinkDeviceAuthHashKey(deviceIdOrMac))?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // 3. EWELINK / SONOFF DIY & AES-128 DEVICEKEY VAULT (F-01, F-09, F-28)
    // -------------------------------------------------------------------------
    private const val KEY_EWELINK_ACCESS_TOKEN = "ewelink::access_token"
    private const val KEY_EWELINK_REFRESH_TOKEN = "ewelink::refresh_token"
    private const val KEY_EWELINK_REGION = "ewelink::region"

    private fun ewelinkDeviceKeyVaultKey(deviceId: String): String =
        "ewelink::device_key::${deviceId.trim().lowercase()}"

    fun putEWeLinkDeviceKey(context: Context, deviceId: String, deviceKey: String) {
        if (deviceId.isBlank() || deviceKey.isBlank()) return
        putString(context, ewelinkDeviceKeyVaultKey(deviceId), deviceKey.trim())
    }

    fun getEWeLinkDeviceKey(context: Context, deviceId: String): String? {
        if (deviceId.isBlank()) return null
        return getString(context, ewelinkDeviceKeyVaultKey(deviceId))
            ?.takeIf { it.isNotBlank() }
            ?: migrateStringKey(
                context,
                "clara_ewelink_sonoff_prefs_v1",
                "device_key_${deviceId.trim()}",
                ewelinkDeviceKeyVaultKey(deviceId)
            )
    }

    fun saveEWeLinkSession(context: Context, accessToken: String, refreshToken: String = "", region: String = "eu") {
        getVault(context).edit()
            .putString(KEY_EWELINK_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_EWELINK_REFRESH_TOKEN, refreshToken.trim())
            .putString(KEY_EWELINK_REGION, region.trim())
            .apply()
    }

    fun getEWeLinkAccessToken(context: Context): String? =
        getString(context, KEY_EWELINK_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // 4. NEST SDM OFFICIAL OAUTH2 PKCE VAULT (Change 9.1)
    // -------------------------------------------------------------------------
    private const val KEY_NEST_SDM_PROJECT_ID = "nest::sdm_project_id"
    private const val KEY_NEST_SDM_CLIENT_ID = "nest::sdm_client_id"
    private const val KEY_NEST_SDM_CLIENT_SECRET = "nest::sdm_client_secret"
    private const val KEY_NEST_SDM_ACCESS_TOKEN = "nest::sdm_access_token"
    private const val KEY_NEST_SDM_REFRESH_TOKEN = "nest::sdm_refresh_token"
    private const val KEY_NEST_SDM_TOKEN_EXPIRES_AT_MS = "nest::sdm_token_expires_at_ms"
    private const val KEY_NEST_PKCE_CODE_VERIFIER = "nest::pkce_code_verifier"
    private const val KEY_NEST_PKCE_STATE_NONCE = "nest::pkce_state_nonce"
    private const val KEY_NEST_WEB_ISSUE_TOKEN = "nest::web_issue_token"
    private const val KEY_NEST_WEB_COOKIES = "nest::web_cookies"
    private const val KEY_NEST_WEB_JWT = "nest::web_czfe_jwt"
    private const val KEY_NEST_WEB_TRANSPORT_URL = "nest::web_transport_url"
    private const val KEY_NEST_WEB_USER_ID = "nest::web_user_id"
    private const val KEY_NEST_PROVISIONED_API_KEY = "nest::provisioned_api_key"

    fun saveNestSdmOAuthTokens(
        context: Context,
        projectId: String,
        clientId: String,
        clientSecret: String,
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMs: Long = 0L
    ) {
        val editor = getVault(context).edit()
        if (projectId.isNotBlank()) editor.putString(KEY_NEST_SDM_PROJECT_ID, projectId.trim())
        if (clientId.isNotBlank()) editor.putString(KEY_NEST_SDM_CLIENT_ID, clientId.trim())
        if (clientSecret.isNotBlank()) editor.putString(KEY_NEST_SDM_CLIENT_SECRET, clientSecret.trim())
        if (accessToken.isNotBlank()) editor.putString(KEY_NEST_SDM_ACCESS_TOKEN, accessToken.trim())
        if (refreshToken.isNotBlank() && refreshToken != "nest_web_session_active") {
            editor.putString(KEY_NEST_SDM_REFRESH_TOKEN, refreshToken.trim())
        }
        if (expiresAtEpochMs > 0L) {
            editor.putString(KEY_NEST_SDM_TOKEN_EXPIRES_AT_MS, expiresAtEpochMs.toString())
        }
        editor.apply()
    }

    fun getNestSdmAccessToken(context: Context): String? =
        getString(context, KEY_NEST_SDM_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }

    fun getNestSdmRefreshToken(context: Context): String? =
        getString(context, KEY_NEST_SDM_REFRESH_TOKEN)
            ?.takeIf { it.isNotBlank() && it != "nest_web_session_active" }

    fun getNestSdmProjectId(context: Context): String? =
        getString(context, KEY_NEST_SDM_PROJECT_ID)?.takeIf { it.isNotBlank() }

    fun getNestSdmClientId(context: Context): String? =
        getString(context, KEY_NEST_SDM_CLIENT_ID)?.takeIf { it.isNotBlank() }

    fun getNestSdmClientSecret(context: Context): String? =
        getString(context, KEY_NEST_SDM_CLIENT_SECRET)?.takeIf { it.isNotBlank() }

    fun getNestSdmTokenExpiresAtMs(context: Context): Long =
        getString(context, KEY_NEST_SDM_TOKEN_EXPIRES_AT_MS)?.toLongOrNull() ?: 0L

    fun saveNestPkceSession(context: Context, codeVerifier: String, stateNonce: String) {
        getVault(context).edit()
            .putString(KEY_NEST_PKCE_CODE_VERIFIER, codeVerifier.trim())
            .putString(KEY_NEST_PKCE_STATE_NONCE, stateNonce.trim())
            .commit()
    }

    fun consumeNestPkceVerifier(context: Context, returnedState: String?): String? {
        val vault = getVault(context)
        val savedState = vault.getString(KEY_NEST_PKCE_STATE_NONCE, null)
        val verifier = vault.getString(KEY_NEST_PKCE_CODE_VERIFIER, null)
        if (!savedState.isNullOrBlank() && !returnedState.isNullOrBlank() && savedState != returnedState.trim()) {
            return null
        }
        vault.edit()
            .remove(KEY_NEST_PKCE_CODE_VERIFIER)
            .remove(KEY_NEST_PKCE_STATE_NONCE)
            .apply()
        return verifier?.takeIf { it.isNotBlank() }
    }

    fun clearLegacyNestWebSession(context: Context) {
        getVault(context).edit()
            .remove(KEY_NEST_WEB_ISSUE_TOKEN)
            .remove(KEY_NEST_WEB_COOKIES)
            .remove(KEY_NEST_WEB_JWT)
            .remove(KEY_NEST_WEB_TRANSPORT_URL)
            .remove(KEY_NEST_WEB_USER_ID)
            .remove(KEY_NEST_PROVISIONED_API_KEY)
            .apply()
    }

    fun saveNestWebSession(
        context: Context,
        issueTokenUrl: String,
        cookies: String,
        jwtToken: String = "",
        transportUrl: String = "",
        userId: String = "",
        apiKey: String = ""
    ) {
        val editor = getVault(context).edit()
        if (issueTokenUrl.isNotBlank()) editor.putString(KEY_NEST_WEB_ISSUE_TOKEN, issueTokenUrl.trim())
        if (cookies.isNotBlank()) editor.putString(KEY_NEST_WEB_COOKIES, cookies.trim())
        if (jwtToken.isNotBlank()) editor.putString(KEY_NEST_WEB_JWT, jwtToken.trim())
        if (transportUrl.isNotBlank()) editor.putString(KEY_NEST_WEB_TRANSPORT_URL, transportUrl.trim())
        if (userId.isNotBlank()) editor.putString(KEY_NEST_WEB_USER_ID, userId.trim())
        if (apiKey.isNotBlank()) editor.putString(KEY_NEST_PROVISIONED_API_KEY, apiKey.trim())
        editor.apply()
    }

    fun getNestWebIssueToken(context: Context): String? =
        getString(context, KEY_NEST_WEB_ISSUE_TOKEN)?.takeIf { it.isNotBlank() }

    fun getNestWebCookies(context: Context): String? =
        getString(context, KEY_NEST_WEB_COOKIES)?.takeIf { it.isNotBlank() }

    fun getNestWebJwt(context: Context): String? =
        getString(context, KEY_NEST_WEB_JWT)?.takeIf { it.isNotBlank() }

    fun getNestWebTransportUrl(context: Context): String? =
        getString(context, KEY_NEST_WEB_TRANSPORT_URL)?.takeIf { it.isNotBlank() }

    fun getNestWebUserId(context: Context): String? =
        getString(context, KEY_NEST_WEB_USER_ID)?.takeIf { it.isNotBlank() }

    fun getNestProvisionedApiKey(context: Context): String? =
        getString(context, KEY_NEST_PROVISIONED_API_KEY)?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // 5. LG WEBOS SSAP PERSISTENT CLIENT-KEY VAULT (IP, UUID & MAC INDEXED)
    // -------------------------------------------------------------------------
    private fun lgClientKeyVaultKey(ipUuidOrMac: String): String =
        "lg_webos::client_key::${ipUuidOrMac.trim().lowercase()}"

    fun putLgWebOsClientKey(
        context: Context,
        ipAddress: String,
        clientKey: String,
        deviceUuid: String = "",
        macAddress: String = ""
    ) {
        val cleanKey = clientKey.trim()
        if (cleanKey.isEmpty()) return
        val editor = getVault(context).edit()
        val cleanIp = EndpointSplitter.splitHostAndPort(ipAddress).hostIp
        if (cleanIp.isNotBlank()) editor.putString(lgClientKeyVaultKey(cleanIp), cleanKey)
        if (deviceUuid.isNotBlank()) editor.putString(lgClientKeyVaultKey(deviceUuid), cleanKey)
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        if (normMac.isNotEmpty()) editor.putString(lgClientKeyVaultKey(normMac), cleanKey)
        editor.commit()
    }

    fun getLgWebOsClientKey(
        context: Context,
        ipAddress: String,
        deviceUuid: String = "",
        macAddress: String = ""
    ): String? {
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        if (normMac.isNotEmpty()) {
            getString(context, lgClientKeyVaultKey(normMac))?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (deviceUuid.isNotBlank()) {
            getString(context, lgClientKeyVaultKey(deviceUuid))?.takeIf { it.isNotBlank() }?.let { return it }
            val uuidAsMac = CrossEcosystemIdentityCorrelator.normalizeMac(deviceUuid).lowercase()
            if (uuidAsMac.isNotEmpty()) {
                getString(context, lgClientKeyVaultKey(uuidAsMac))?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        val cleanIp = EndpointSplitter.splitHostAndPort(ipAddress).hostIp
        if (cleanIp.isNotBlank()) {
            getString(context, lgClientKeyVaultKey(cleanIp))?.takeIf { it.isNotBlank() }?.let { return it }
            return migrateStringKey(
                context,
                "clara_lg_webos_tv_prefs_v2",
                "client_key_$cleanIp",
                lgClientKeyVaultKey(cleanIp)
            )
        }
        return null
    }

    fun clearLgWebOsClientKey(
        context: Context,
        ipAddress: String = "",
        deviceUuid: String = "",
        macAddress: String = ""
    ) {
        val editor = getVault(context).edit()
        val cleanIp = EndpointSplitter.splitHostAndPort(ipAddress).hostIp
        if (cleanIp.isNotBlank()) editor.remove(lgClientKeyVaultKey(cleanIp))
        if (deviceUuid.isNotBlank()) editor.remove(lgClientKeyVaultKey(deviceUuid))
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()
        if (normMac.isNotEmpty()) editor.remove(lgClientKeyVaultKey(normMac))
        editor.apply()
    }

    // -------------------------------------------------------------------------
    // 6. EZVIZ & IMOU CLOUD TOKENS + CAMERA VERIFICATION PINS (F-01, F-09, F-31)
    // -------------------------------------------------------------------------
    private const val KEY_EZVIZ_ACCESS_TOKEN = "ezviz::access_token"
    private const val KEY_EZVIZ_REFRESH_TOKEN = "ezviz::refresh_token"
    private const val KEY_EZVIZ_API_DOMAIN = "ezviz::api_domain"

    private fun cameraVerificationPinKey(serialOrDeviceId: String): String =
        "camera::verify_pin::${serialOrDeviceId.trim().uppercase()}"

    fun saveEzvizSession(
        context: Context,
        accessToken: String,
        apiDomain: String = "",
        refreshToken: String = "",
        defaultVerifyPin: String = ""
    ) {
        val editor = getVault(context).edit()
        if (accessToken.isNotBlank()) editor.putString(KEY_EZVIZ_ACCESS_TOKEN, accessToken.trim())
        if (apiDomain.isNotBlank()) editor.putString(KEY_EZVIZ_API_DOMAIN, apiDomain.trim())
        if (refreshToken.isNotBlank()) editor.putString(KEY_EZVIZ_REFRESH_TOKEN, refreshToken.trim())
        if (defaultVerifyPin.isNotBlank()) {
            editor.putString(cameraVerificationPinKey("DEFAULT"), defaultVerifyPin.trim())
        }
        editor.apply()
    }

    fun getEzvizAccessToken(context: Context): String? =
        getString(context, KEY_EZVIZ_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
            ?: migrateStringKey(context, "clara_ezviz_account_prefs", "access_token", KEY_EZVIZ_ACCESS_TOKEN)

    fun getEzvizApiDomain(context: Context): String? =
        getString(context, KEY_EZVIZ_API_DOMAIN)?.takeIf { it.isNotBlank() }

    fun putCameraVerificationPin(context: Context, serialOrDeviceId: String, pin: String) {
        if (serialOrDeviceId.isBlank() || pin.isBlank()) return
        putString(context, cameraVerificationPinKey(serialOrDeviceId), pin.trim())
    }

    fun getCameraVerificationPin(context: Context, serialOrDeviceId: String): String? {
        return getString(context, cameraVerificationPinKey(serialOrDeviceId))?.takeIf { it.isNotBlank() }
            ?: getString(context, cameraVerificationPinKey("DEFAULT"))?.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------------------
    // 7. MATTER NOC FABRIC & PRE-FLIGHT KEY GATE CHECK (F-24, F-28)
    // -------------------------------------------------------------------------
    private fun matterNodeNocKey(nodeIdOrDiscriminator: String): String =
        "matter::noc_fabric::${nodeIdOrDiscriminator.trim().lowercase()}"

    fun putMatterNodeNoc(context: Context, nodeId: String, nocCredential: String) {
        if (nodeId.isBlank() || nocCredential.isBlank()) return
        putString(context, matterNodeNocKey(nodeId), nocCredential.trim())
    }

    fun getMatterNodeNoc(context: Context, nodeId: String): String? =
        getString(context, matterNodeNocKey(nodeId))?.takeIf { it.isNotBlank() }

    /**
     * Used by UniversalOnboardingEngine to verify whether a key-locked LAN
     * device already possesses its required local encryption credential in the vault.
     * Onboarding Section 2: LG webOS verifies against target IP, UUID, or normalized MAC.
     */
    fun hasVerifiedLocalControlKey(
        context: Context,
        providerHint: String,
        deviceId: String,
        ipAddress: String = "",
        macAddress: String = ""
    ): Boolean {
        val normProvider = providerHint.trim().uppercase()
        return when {
            normProvider.contains("TUYA") || normProvider.contains("SMART_LIFE") ->
                !getTuyaLocalKey(context, deviceId, macAddress).isNullOrBlank()
            normProvider.contains("TAPO") || normProvider.contains("KASA") || normProvider.contains("TPLINK") ->
                !getTpLinkKlapHash(context, deviceId.ifBlank { ipAddress }).isNullOrBlank() ||
                        (!getTpLinkUsername(context).isNullOrBlank() && !getTpLinkPassword(context).isNullOrBlank())
            normProvider.contains("EWELINK") || normProvider.contains("SONOFF") ->
                !getEWeLinkDeviceKey(context, deviceId).isNullOrBlank()
            normProvider.contains("LG") || normProvider.contains("WEBOS") ->
                !getLgWebOsClientKey(context, ipAddress = ipAddress, deviceUuid = deviceId, macAddress = macAddress).isNullOrBlank()
            normProvider.contains("MATTER") ->
                !getMatterNodeNoc(context, deviceId).isNullOrBlank()
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // 8. 2.4 GHZ HOME WI-FI CREDENTIAL PRE-FILL, UNBIND PURGE & FINGERPRINTING
    // -------------------------------------------------------------------------
    private const val KEY_HOME_WIFI_DEFAULT_SSID = "wifi::home_24ghz_default_ssid"
    private const val KEY_HOME_WIFI_DEFAULT_PASS = "wifi::home_24ghz_default_password"

    private fun wifiSsidPasswordKey(ssid: String): String =
        "wifi::ssid_password::${ssid.trim()}"

    fun saveHomeWifiCredentials(context: Context, ssid: String, password: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return
        DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(password)
        getVault(context).edit()
            .putString(KEY_HOME_WIFI_DEFAULT_SSID, cleanSsid)
            .putString(KEY_HOME_WIFI_DEFAULT_PASS, password)
            .putString(wifiSsidPasswordKey(cleanSsid), password)
            .commit()
    }

    fun getSavedHomeWifiSsid(context: Context): String =
        getString(context, KEY_HOME_WIFI_DEFAULT_SSID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(context, "clara_direct_pairing_prefs::saved_wifi_ssid")?.trim()
            ?: getString(context, "clara_direct_pairing_prefs::wifi_ssid")?.trim().orEmpty()

    fun getWifiPasswordForSsid(context: Context, ssid: String = ""): String {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isNotEmpty()) {
            getString(context, wifiSsidPasswordKey(cleanSsid))?.let {
                DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(it)
                return it
            }
            if (cleanSsid.equals(getSavedHomeWifiSsid(context), ignoreCase = true)) {
                getString(context, KEY_HOME_WIFI_DEFAULT_PASS)?.let {
                    DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(it)
                    return it
                }
            }
        }
        val fallback = getString(context, KEY_HOME_WIFI_DEFAULT_PASS)
            ?: migrateStringKey(context, "clara_direct_pairing_prefs", "wifi_password", KEY_HOME_WIFI_DEFAULT_PASS)
            ?: getString(context, "clara_direct_pairing_prefs::saved_wifi_password").orEmpty()
        DiagnosticsSanitizer.registerTransientWifiSecretForRedaction(fallback)
        return fallback
    }

    /**
     * Change 7.1: Purges all per-device local keys, client keys, NOCs, and camera PINs on formal Unbind.
     */
    fun purgeDeviceSecretsOnUnbind(
        context: Context,
        deviceId: String,
        ipAddress: String = "",
        macAddress: String = ""
    ) {
        val editor = getVault(context).edit()
        val cleanId = deviceId.trim().lowercase()
        val cleanIp = EndpointSplitter.splitHostAndPort(ipAddress).hostIp
        val normMac = CrossEcosystemIdentityCorrelator.normalizeMac(macAddress).lowercase()

        if (cleanId.isNotEmpty()) {
            editor.remove(tuyaLocalKeyVaultKey(cleanId))
            editor.remove("clara_tuya_consumer_session_v1::local_key_${deviceId.trim()}")
            editor.remove(tpLinkDeviceAuthHashKey(cleanId))
            editor.remove(ewelinkDeviceKeyVaultKey(cleanId))
            editor.remove(lgClientKeyVaultKey(cleanId))
            editor.remove(matterNodeNocKey(cleanId))
            editor.remove(cameraVerificationPinKey(deviceId))
        }
        if (cleanIp.isNotBlank()) {
            editor.remove(lgClientKeyVaultKey(cleanIp))
            editor.remove(tpLinkDeviceAuthHashKey(cleanIp))
        }
        if (normMac.isNotEmpty()) {
            editor.remove(lgClientKeyVaultKey(normMac))
            editor.remove(tpLinkDeviceAuthHashKey(normMac))
            editor.remove(tuyaLocalKeyVaultKey(normMac))
            for (variant in CrossEcosystemIdentityCorrelator.computeMacOffsetVariants(normMac)) {
                editor.remove(tuyaLocalKeyVaultKey(variant.lowercase()))
            }
        }
        editor.commit()
    }

    fun fingerprintSecret(rawSecret: String?): String =
        DiagnosticsSanitizer.fingerprintSecret(rawSecret)
}
