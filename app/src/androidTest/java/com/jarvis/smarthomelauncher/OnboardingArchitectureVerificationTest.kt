package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.ClaraSecretVault
import com.jarvis.smarthomelauncher.core.ConnectedIdentityIndex
import com.jarvis.smarthomelauncher.core.DeviceLifecycleState
import com.jarvis.smarthomelauncher.core.DiscoveryRadioCoordinator
import com.jarvis.smarthomelauncher.core.OnboardingEntryMode
import com.jarvis.smarthomelauncher.core.ProviderId
import com.jarvis.smarthomelauncher.core.SmartTile
import com.jarvis.smarthomelauncher.core.TileType
import com.jarvis.smarthomelauncher.core.WifiBandValidationStatus
import com.jarvis.smarthomelauncher.engines.DeviceFingerprint
import com.jarvis.smarthomelauncher.engines.DeviceIdentityResolver
import com.jarvis.smarthomelauncher.engines.OnboardingRequirement
import com.jarvis.smarthomelauncher.engines.TransportRadioType
import com.jarvis.smarthomelauncher.integrations.JarvisLiveBridge

class OnboardingArchitectureVerificationTest {

    @Test
    fun comboMacOffsetVariants_handlesPlusMinusTwoAndByteBoundaryCarry() {
        val variants = ConnectedIdentityIndex.computeComboMacOffsetVariants("D8:1F:12:AA:BB:FE")
        assertTrue("Normalized base MAC must be present", variants.contains("D81F12AABBFE"))
        assertTrue("+1 offset must be present", variants.contains("D81F12AABBFF"))
        assertTrue("-1 offset must be present", variants.contains("D81F12AABBFD"))
        assertTrue("-2 offset must be present", variants.contains("D81F12AABBFC"))
        // Full 48-bit +2 carry across ..:BB:FE -> ..:BC:00
        assertTrue("Full 48-bit +2 carry must be present", variants.contains("D81F12AABC00"))
    }

    @Test
    fun connectedIdentityIndex_suppressesActiveAndHiddenUnpairedComboMacs_butAllowsUnbound() {
        val activePlug = SmartTile(
            id = "tile_plug_1",
            title = "Kitchen Plug",
            type = TileType.SMARTKNIGHT_PLUG,
            serialNumber = "bf99112233aabbcc",
            macAddress = "D8:1F:12:33:44:10",
            lifecycleState = DeviceLifecycleState.OPERATIONAL
        )
        val hiddenUnpairedPlug = SmartTile(
            id = "tile_plug_hidden",
            title = "Hall Plug",
            type = TileType.SMARTKNIGHT_PLUG,
            serialNumber = "bf88776655ddeeff",
            macAddress = "10:D5:61:22:33:40",
            isVisible = false,
            lifecycleState = DeviceLifecycleState.UNPAIRED
        )
        val unboundPlug = SmartTile(
            id = "tile_plug_unbound",
            title = "Reset Plug",
            type = TileType.SMARTKNIGHT_PLUG,
            serialNumber = "bf00001111222233",
            macAddress = "50:8A:06:11:22:30",
            lifecycleState = DeviceLifecycleState.UNBOUND
        )

        ConnectedIdentityIndex.rebuildFromTiles(listOf(activePlug, hiddenUnpairedPlug, unboundPlug))

        // +1 BLE MAC offset from active Wi-Fi plug must be suppressed
        assertTrue(
            ConnectedIdentityIndex.isAlreadyOnboarded(
                providerId = ProviderId.TUYA_LAN.id,
                rawDeviceId = "",
                macAddress = "D8:1F:12:33:44:11"
            )
        )
        // +2 BLE MAC offset from hidden UNPAIRED plug must remain suppressed
        assertTrue(
            ConnectedIdentityIndex.isAlreadyOnboarded(
                providerId = ProviderId.TUYA_LAN.id,
                rawDeviceId = "",
                macAddress = "10:D5:61:22:33:42"
            )
        )
        // UNBOUND plug must become discoverable again
        assertFalse(
            ConnectedIdentityIndex.isAlreadyOnboarded(
                providerId = ProviderId.TUYA_LAN.id,
                rawDeviceId = "bf00001111222233",
                macAddress = "50:8A:06:11:22:30"
            )
        )
    }

    @Test
    fun lgWebOsPrecedence_neverPromotesUnkeyedTvFromSsdpToZeroTouchReady() {
        val ssdpUnauthenticatedFingerprint = DeviceFingerprint(
            transport = TransportRadioType.SHELLY_TASMOTA_HTTP,
            protocolBadge = "SSDP UPnP",
            confidenceScore = 0.95f,
            displayName = "LG OLED65C3",
            suggestedType = TileType.LG_TV,
            ipAddress = "192.168.1.55",
            port = 3001,
            macAddress = "64:B7:08:11:22:33",
            requiresPhysicalAccept = false
        )
        val webOsFingerprint = DeviceFingerprint(
            transport = TransportRadioType.LG_WEBOS_SSAP,
            protocolBadge = "LG webOS",
            confidenceScore = 0.95f,
            displayName = "LG OLED65C3",
            suggestedType = TileType.LG_TV,
            ipAddress = "192.168.1.55",
            port = 3001,
            macAddress = "64:B7:08:11:22:33",
            requiresPhysicalAccept = true
        )

        val requirement = DeviceIdentityResolver.resolveOnboardingRequirement(
            listOf(ssdpUnauthenticatedFingerprint, webOsFingerprint)
        )
        assertTrue(
            "Unkeyed LG webOS TV must require physical on-screen Accept even when SSDP is present",
            requirement is OnboardingRequirement.PhysicalAcceptRequired
        )
    }

    @Test
    fun unkeyedTuyaLanAndMultiAdminMatter_requireExplicitAuthInsteadOfZeroTouch() {
        val unkeyedTuyaLan = DeviceFingerprint(
            transport = TransportRadioType.TUYA_LAN_6668,
            protocolBadge = "LAN 6668",
            confidenceScore = 0.95f,
            displayName = "Smart Plug",
            suggestedType = TileType.SMARTKNIGHT_PLUG,
            ipAddress = "192.168.1.88",
            port = 6668,
            hardwareSerialOrNodeId = "bf1234567890abcd",
            localKeyOrPin = "" // Missing 16-byte localKey
        )
        val tuyaReq = DeviceIdentityResolver.resolveOnboardingRequirement(listOf(unkeyedTuyaLan))
        assertTrue(tuyaReq is OnboardingRequirement.InAppAccountWorkflowRequired)

        val foreignCommissionedMatter = DeviceFingerprint(
            transport = TransportRadioType.MATTER_FABRIC,
            protocolBadge = "Matter Fabric",
            confidenceScore = 0.96f,
            displayName = "Matter Plug",
            suggestedType = TileType.MATTER_DEVICE,
            ipAddress = "192.168.1.92",
            port = 5540,
            hardwareSerialOrNodeId = "matter_node_99",
            requiresShortCode = false // Operational on another fabric, but no Clara NOC in vault
        )
        val matterReq = DeviceIdentityResolver.resolveOnboardingRequirement(listOf(foreignCommissionedMatter))
        assertTrue(matterReq is OnboardingRequirement.ShortCodeRequired)
        assertTrue(
            (matterReq as OnboardingRequirement.ShortCodeRequired).hintLabel.contains("Multi-Admin", ignoreCase = true)
        )
    }

    @Test
    fun isolatedRadioPassesAndWifiBandValidation_enforceHardwareCompatibility() {
        assertNotEquals(
            OnboardingEntryMode.FAST_BLINKING.primaryRadioPass,
            OnboardingEntryMode.SLOW_BLINKING_AP.primaryRadioPass
        )
        assertNotEquals(
            OnboardingEntryMode.FAST_BLINKING.primaryRadioPass,
            OnboardingEntryMode.ALREADY_ON_NETWORK.primaryRadioPass
        )

        assertTrue(WifiBandValidationStatus.VALID_24GHZ_ONLY.isCompatibleWith24GhzHardware)
        assertTrue(WifiBandValidationStatus.VALID_DUAL_BAND_MESH.isCompatibleWith24GhzHardware)
        assertFalse(WifiBandValidationStatus.BLOCKED_5GHZ_OR_6GHZ_ONLY.isCompatibleWith24GhzHardware)

        assertTrue(DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid("SmartLife-4F9A"))
        assertTrue(DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid("SL-8821"))
        assertFalse(DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid("DIRECT-HP-OfficeJet"))
        assertFalse(DiscoveryRadioCoordinator.isDeviceSetupHotspotSsid("SkyBroadband-5G"))
    }

    @Test
    fun failureHeaders_neverMislabelAuthOrBandOrTvErrorsAsConnectionTimedOut() {
        assertEquals(
            "Confirmation Needed on TV",
            JarvisLiveBridge.inferFailureHeaderTitle("Please press 'Accept' on your LG TV screen.")
        )
        assertEquals(
            "TV Security Re-Verification Needed",
            JarvisLiveBridge.inferFailureHeaderTitle("TLS certificate pin mismatch on port 3001")
        )
        assertEquals(
            "2.4 GHz Wi-Fi Required",
            JarvisLiveBridge.inferFailureHeaderTitle("Tablet is connected on 5 GHz Wi-Fi")
        )
        assertEquals(
            "Authentication Rejected",
            JarvisLiveBridge.inferFailureHeaderTitle("Router Wi-Fi password rejected (401)")
        )
    }

    @Test
    fun secretFingerprint_neverLeaksPlaintextLocalKey() {
        val rawLocalKey = "a1b2c3d4e5f60718"
        val fp = ClaraSecretVault.fingerprintSecret(rawLocalKey)
        assertFalse("Fingerprint must never contain raw secret", fp.contains(rawLocalKey))
        assertTrue("Fingerprint must be non-empty short hash", fp.isNotBlank() && fp.length <= 16)
    }
}