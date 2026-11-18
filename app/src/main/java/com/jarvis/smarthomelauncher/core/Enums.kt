package com.jarvis.smarthomelauncher.core

import java.util.Locale

enum class DashboardWidgetType(val label: String, val shortLabel: String) {
    WEATHER("7-Day Weather Forecast", "7-Day Weather"),
    WEATHER_TODAY("Today's Weather (Compact)", "Today's Weather"),
    CLOCK_HERO("Room, Clock & Hero Controls", "Clock / Hero"),
    VOLUME_SLIDERS("Speaker & Media Volume Sliders", "Volume"),
    DEVICE_TILES("Smart Device Tiles Grid", "Devices"),
    VEHICLES("Jaguar & Mercedes-Benz Garage", "Vehicles"),
    ENERGY("Electricity & Power Analytics", "Energy"),
    SECURITY_CENTER("Security Center & Camera AI", "Security"),
    CALENDAR("Unified Calendar, Bins & Birthdays", "Calendar"),
    TV_TRACKER("Entertainment: TV & Movie Releases", "Entertainment"),
    FOOTBALL("Football Fixtures & UK TV Channels", "Football"),
    COMMUTE("Commute & Rail", "Commute"),
    WIFI("Guest Wi-Fi QR", "Wi-Fi"),
    MEDIA("In-App Music & Cast Player", "Media"),
    RADIO("Live UK & Global Radio Player", "Live Radio"),
    HOME_HEALTH("Home Health & Self-Healing", "Health")
}

enum class DpiScalePreset(val label: String, val scaleFactor: Float) {
    SMALL("Small (85%)", 0.85f),
    COMPACT("Compact (92%)", 0.92f),
    DEFAULT("Standard (100%)", 1.00f),
    LARGE("Large (115%)", 1.15f),
    EXTRA_LARGE("Extra Large (130%)", 1.30f),
    CUSTOM("Custom Scale (%)", 1.00f)
}

enum class DashboardGridSizePreset(
    val label: String,
    val subtitle: String,
    val tabletCols: Int,
    val mediumCols: Int,
    val compactCols: Int,
    val mobileCols: Int,
    val tabletVisibleRows: Int,
    val spacingDp: Int
) {
    COMFORTABLE("Comfortable (6 \u00D7 3 Grid)", "Larger tiles and wider spacing", 6, 6, 4, 2, 3, 12),
    STANDARD("Standard (8 \u00D7 4 Grid)", "Balanced default tablet grid", 8, 6, 4, 2, 4, 10),
    COMPACT("Compact (10 \u00D7 5 Grid)", "Smaller tiles \u2022 More devices on screen", 10, 8, 6, 4, 5, 8),
    ULTRA_DENSE("Ultra Dense (12 \u00D7 6 Grid)", "Maximum tile density for large dashboards", 12, 10, 8, 4, 6, 6)
}

enum class SemanticControlRole {
    PRIMARY_POWER_TOGGLE,
    SECONDARY_SOCKET_TOGGLE,
    THERMOSTAT_TEMPERATURE_DIAL,
    APPLIANCE_TEMPERATURE_DIAL,
    FAN_SPEED_STEPPER,
    BRIGHTNESS_SLIDER,
    COVER_POSITION_SLIDER,
    VOLUME_SLIDER,
    COLOR_WHEEL,
    HDMI_INPUT_SELECTOR,
    COUNTDOWN_TIMER,
    MODE_SELECTOR,
    ENERGY_TELEMETRY_GRAPH,
    READ_ONLY_METRIC
}

enum class CapabilityWidgetRenderer {
    TOGGLE_CARD,
    INPUT_SELECTOR,
    CHIP_GROUP,
    CONTINUOUS_SLIDER,
    TEMPERATURE_DIAL,
    FAN_SPEED_STEPPER,
    BRIGHTNESS_SLIDER,
    VOLUME_SLIDER,
    COVER_POSITION_SLIDER,
    COLOR_WHEEL_360,
    ENERGY_GRAPH,
    METRIC_BADGE
}

enum class ClassificationSource {
    MANUAL_OVERRIDE,
    CAPABILITY_CLASSIFIER,
    FINGERPRINT_ANALYZER,
    CLOUD_METADATA,
    HYBRID
}

enum class NavDockPosition(val label: String) {
    LEFT("Left Rail (Default)"),
    RIGHT("Right Rail"),
    TOP("Top Floating Bar"),
    BOTTOM("Bottom Dock Bar")
}

enum class UserRole(
    val label: String,
    val subtitle: String,
    val canEditLayout: Boolean,
    val canManageDevices: Boolean,
    val canControlVehiclesAndLocks: Boolean,
    val canAccessSettings: Boolean
) {
    OWNER("Owner", "Full administrative, vehicle, lock & layout access", true, true, true, true),
    ADMIN("Admin", "Full device, automation & layout management", true, true, true, true),
    FAMILY("Family", "Standard home, media, climate & lighting controls", false, false, true, false),
    CHILD("Child", "Parental Lock active: blocks layout edits, settings & vehicle/door unlock", false, false, false, false),
    GUEST("Guest", "Restricted access: blocks layout edits, settings & vehicle/door unlock", false, false, false, false)
}

enum class TrackedMediaKind(val label: String) {
    TV_SHOW("TV Series"),
    MOVIE("Movie")
}

enum class CalendarEventCategory(val label: String, val colorHex: Long) {
    BIRTHDAY("Birthday / Anniversary", 0xFFFF006EL),
    COUNCIL_BIN("Council Bin Collection", 0xFF00E676L),
    TV_EPISODE("TV Episode Airing", 0xFF00E5FFL),
    FOOTBALL_MATCH("Football Match", 0xFFFFB300L),
    VEHICLE_MOT_TAX("Vehicle MOT & Tax", 0xFFC084FCL),
    REMINDER("Family Event", 0xFFA3F0C1L)
}

enum class UkCouncilBinType(val label: String, val badgeColorHex: Long) {
    REFUSE_BLACK("Black / General Waste Bin", 0xFF78909CL),
    RECYCLING_BLUE("Blue / Recycling Bin", 0xFF00C6FFL),
    GARDEN_GREEN("Green / Garden Waste Bin", 0xFF00E676L),
    FOOD_CADDY("Food Waste Caddy", 0xFFFFB300L)
}

enum class FootballTvProviderOption(val label: String, val appId: String) {
    IBO_PRO_PLAYER("IBO Pro Player (IPTV Channel #)", "com.ibo.pro"),
    IPTV_SMARTERS("IPTV Smarters Pro", "com.nst.iptvsmarterstvbox"),
    TIVIMATE("TiviMate IPTV", "ar.tvplayer.tv"),
    UK_BROADCASTER_APPS("Official UK Apps (Sky / TNT / BBC / ITVX)", "bbc")
}

enum class DashboardCardSlot(
    val key: String,
    val label: String,
    val defaultWidthWeight: Float,
    val defaultHeightWeight: Float,
    val defaultWidgetType: DashboardWidgetType
) {
    ROOM_PHOTO("ROOM_PHOTO", "Weather Card", 1.0f, 1.0f, DashboardWidgetType.WEATHER),
    HERO_CLOCK("HERO_CLOCK", "Room, Clock & Hero Card", 1.62f, 1.0f, DashboardWidgetType.CLOCK_HERO),
    VOLUME_SLIDERS("VOLUME_SLIDERS", "Volume Sliders Card", 1.0f, 1.15f, DashboardWidgetType.VOLUME_SLIDERS),
    DEVICE_TILES("DEVICE_TILES", "Device Tiles Grid Card", 1.62f, 1.15f, DashboardWidgetType.DEVICE_TILES);

    companion object {
        val defaultOrderKeys: List<String> = entries.map { it.key }
        val defaultWidthMap: Map<String, Float> = entries.associate { it.key to it.defaultWidthWeight }
        val defaultHeightMap: Map<String, Float> = entries.associate { it.key to it.defaultHeightWeight }
        val defaultZoomMap: Map<String, Float> = entries.associate { it.key to 1.0f }
        val defaultWidgetTypeMap: Map<String, DashboardWidgetType> =
            entries.associate { it.key to it.defaultWidgetType }

        fun fromKey(key: String): DashboardCardSlot? =
            entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

enum class DashboardTemplate(
    val label: String,
    val subtitle: String,
    val slotWidgets: Map<String, DashboardWidgetType>,
    val widthWeights: Map<String, Float>,
    val heightWeights: Map<String, Float>
) {
    STANDARD_QUAD(
        label = "Standard 4-Card Home (Default)",
        subtitle = "Weather, Room/Clock/Hero, Volume Sliders & Device Grid",
        slotWidgets = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to DashboardWidgetType.WEATHER,
            DashboardCardSlot.HERO_CLOCK.key to DashboardWidgetType.CLOCK_HERO,
            DashboardCardSlot.VOLUME_SLIDERS.key to DashboardWidgetType.VOLUME_SLIDERS,
            DashboardCardSlot.DEVICE_TILES.key to DashboardWidgetType.DEVICE_TILES
        ),
        widthWeights = DashboardCardSlot.defaultWidthMap,
        heightWeights = DashboardCardSlot.defaultHeightMap
    ),
    AUTOMOTIVE_GARAGE(
        label = "Automotive & Executive Hub",
        subtitle = "Jaguar & Mercedes Garage, Room/Clock/Hero, Volume & Device Grid",
        slotWidgets = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to DashboardWidgetType.VEHICLES,
            DashboardCardSlot.HERO_CLOCK.key to DashboardWidgetType.CLOCK_HERO,
            DashboardCardSlot.VOLUME_SLIDERS.key to DashboardWidgetType.VOLUME_SLIDERS,
            DashboardCardSlot.DEVICE_TILES.key to DashboardWidgetType.DEVICE_TILES
        ),
        widthWeights = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to 1.12f,
            DashboardCardSlot.HERO_CLOCK.key to 1.50f,
            DashboardCardSlot.VOLUME_SLIDERS.key to 1.0f,
            DashboardCardSlot.DEVICE_TILES.key to 1.62f
        ),
        heightWeights = DashboardCardSlot.defaultHeightMap
    ),
    SECURITY_COMMAND(
        label = "Security & Perimeter Command",
        subtitle = "Security Center, Room/Clock/Hero, Vehicles & Device Grid",
        slotWidgets = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to DashboardWidgetType.SECURITY_CENTER,
            DashboardCardSlot.HERO_CLOCK.key to DashboardWidgetType.CLOCK_HERO,
            DashboardCardSlot.VOLUME_SLIDERS.key to DashboardWidgetType.VEHICLES,
            DashboardCardSlot.DEVICE_TILES.key to DashboardWidgetType.DEVICE_TILES
        ),
        widthWeights = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to 1.15f,
            DashboardCardSlot.HERO_CLOCK.key to 1.47f,
            DashboardCardSlot.VOLUME_SLIDERS.key to 1.15f,
            DashboardCardSlot.DEVICE_TILES.key to 1.47f
        ),
        heightWeights = DashboardCardSlot.defaultHeightMap
    ),
    ENERGY_ANALYTICS(
        label = "Eco & Energy Telemetry Focus",
        subtitle = "Weather, Room/Clock/Hero, Electricity Analytics & Device Grid",
        slotWidgets = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to DashboardWidgetType.WEATHER,
            DashboardCardSlot.HERO_CLOCK.key to DashboardWidgetType.CLOCK_HERO,
            DashboardCardSlot.VOLUME_SLIDERS.key to DashboardWidgetType.ENERGY,
            DashboardCardSlot.DEVICE_TILES.key to DashboardWidgetType.DEVICE_TILES
        ),
        widthWeights = DashboardCardSlot.defaultWidthMap,
        heightWeights = DashboardCardSlot.defaultHeightMap
    ),
    ADMIN_DIAGNOSTICS(
        label = "System Health & Diagnostics",
        subtitle = "Home Health & Self-Healing, Clock/Hero, Energy & Device Grid",
        slotWidgets = mapOf(
            DashboardCardSlot.ROOM_PHOTO.key to DashboardWidgetType.HOME_HEALTH,
            DashboardCardSlot.HERO_CLOCK.key to DashboardWidgetType.CLOCK_HERO,
            DashboardCardSlot.VOLUME_SLIDERS.key to DashboardWidgetType.ENERGY,
            DashboardCardSlot.DEVICE_TILES.key to DashboardWidgetType.DEVICE_TILES
        ),
        widthWeights = DashboardCardSlot.defaultWidthMap,
        heightWeights = DashboardCardSlot.defaultHeightMap
    )
}


enum class DashboardNavDestination(val label: String) {
    HOME("Home"),
    CAMERAS("Cameras"),
    PLAYER("Music"),
    ROUTINES("Routines"),
    ELECTRICITY("Energy"),
    SETTINGS("Settings")
}

enum class GroupCardIconOption(val key: String, val label: String) {
    LIGHTING("LIGHTING", "Lighting & Bulbs"),
    SOCKETS("SOCKETS", "Plugs & Sockets"),
    HEATING("HEATING", "Heating & Climate"),
    CAMERAS("CAMERAS", "Cameras & Doorbells"),
    MEDIA("MEDIA", "TV & Speakers"),
    SECURITY("SECURITY", "Locks & Security"),
    GARDEN("GARDEN", "Garden & Outdoor"),
    GENERAL("GENERAL", "Smart Group")
}

enum class SlideshowSourceType(val label: String, val subtitle: String) {
    LOCAL_DEVICE(
        "Local Tablet Photos & Subfolders",
        "Select folders, subfolders, or individual photos from this device"
    ),
    GOOGLE_PHOTOS(
        "Google Photos Albums",
        "Sign in with your Google account to tick the albums you want"
    ),
    ONEDRIVE(
        "Microsoft OneDrive Folders & Subfolders",
        "Sign in with your Microsoft account to browse folders, subfolders & albums"
    ),
    CUSTOM_URLS(
        "Custom Image URLs",
        "Optional direct image links (comma or line separated)"
    )
}

enum class LgTvAppOption(val label: String, val appId: String, val contentId: String = "") {
    BBC_IPLAYER("BBC iPlayer", "bbc"),
    NETFLIX("Netflix", "netflix"),
    YOUTUBE("YouTube", "youtube.leanback.v4"),
    ITV_X("ITVX", "itv.hub"),
    DISNEY_PLUS("Disney+", "com.disney.disneyplus-prod"),
    PRIME_VIDEO("Prime Video", "lovefilm.uk"),
    CHANNEL_4("Channel 4", "all4"),
    APPLE_TV("Apple TV", "com.apple.appletv"),
    SPOTIFY("Spotify", "spotify-beehive"),
    LIVE_TV("Live TV", "com.webos.app.livetv"),
    HDMI_1("HDMI 1", "com.webos.app.hdmi1"),
    HDMI_2("HDMI 2", "com.webos.app.hdmi2"),
    HDMI_3("HDMI 3", "com.webos.app.hdmi3")
}

enum class DeviceClass(val label: String, val roleDescription: String) {
    CLIMATE("Climate", "Heating, cooling, and ventilation"),
    LIGHT("Lighting", "Illumination and ambient RGB"),
    SWITCH("Switch", "Binary and multi-gang power control"),
    ENERGY("Energy", "Power, voltage, and consumption metering"),
    MEDIA("Media", "Audio playback and television displays"),
    CAMERA("Camera", "Video surveillance and entry intercoms"),
    SECURITY("Security", "Access control and intrusion protection"),
    VEHICLE("Vehicle", "Connected automotive telemetry and climate"),
    SENSOR("Sensor", "Read-only environmental telemetry"),
    APPLIANCE("Appliance", "Specialized household machines"),
    NETWORK("Network", "Infrastructure bridges, hubs, and groups")
}

enum class DeviceSubtype(
    val label: String,
    val defaultDeviceClass: DeviceClass = DeviceClass.SWITCH,
    val isHardwareSubtype: Boolean = true
) {
    GENERIC("Generic Device", DeviceClass.SWITCH, true),
    THERMOSTAT("Thermostat", DeviceClass.CLIMATE, true),
    TRV("Radiator Valve (TRV)", DeviceClass.CLIMATE, true),
    RADIATOR("Heater / Radiator", DeviceClass.CLIMATE, true),
    TOWER_FAN("Tower Fan", DeviceClass.CLIMATE, true),
    BULB("Smart Bulb", DeviceClass.LIGHT, true),
    LED_STRIP("LED Strip", DeviceClass.LIGHT, true),
    AMBIENT_SYNC("Ambient Sync Box", DeviceClass.MEDIA, true),
    SMART_PLUG("Smart Plug", DeviceClass.SWITCH, true),
    WALL_SOCKET("Wall Socket", DeviceClass.SWITCH, true),
    RELAY("Inline Relay", DeviceClass.SWITCH, true),
    TV("Smart TV", DeviceClass.MEDIA, true),
    SPEAKER("Smart Speaker", DeviceClass.MEDIA, true),
    CAST_GROUP("Multi-Room Speaker Group", DeviceClass.MEDIA, true),
    DOORBELL("Video Doorbell", DeviceClass.CAMERA, true),
    CAMERA("Security Camera", DeviceClass.CAMERA, true),
    LOCK("Smart Lock", DeviceClass.SECURITY, true),
    BLIND("Window Blind / Shade", DeviceClass.SECURITY, true),
    GARAGE_DOOR("Garage Door", DeviceClass.SECURITY, true),
    SECURITY_HUB("Security Center", DeviceClass.SECURITY, true),
    VEHICLE("Connected Vehicle", DeviceClass.VEHICLE, true),
    EV_CHARGER("EV Charger", DeviceClass.ENERGY, true),
    AIR_FRYER("Air Fryer", DeviceClass.APPLIANCE, true),
    KETTLE("Electric Kettle", DeviceClass.APPLIANCE, true),
    COFFEE_MACHINE("Coffee Machine", DeviceClass.APPLIANCE, true),
    WASHER("Washing Machine / Dryer", DeviceClass.APPLIANCE, true),
    VACUUM("Robot Vacuum", DeviceClass.APPLIANCE, true),
    POND_PUMP("Filter / Pond Pump", DeviceClass.APPLIANCE, true),
    SENSOR("Environmental Sensor", DeviceClass.SENSOR, true),
    MQTT_BROKER("MQTT Broker", DeviceClass.NETWORK, true),
    MATTER_BRIDGE("Matter Bridge", DeviceClass.NETWORK, true),
    THREAD_BORDER_ROUTER("Thread Border Router", DeviceClass.NETWORK, true),
    GROUP("Device Group", DeviceClass.NETWORK, false),// Legacy service widget aliases kept non-hardware so ServiceClass handles dashboard widgets cleanly
    RADIO("Live Radio", DeviceClass.MEDIA, false),
    TV_TRACKER("Entertainment Hub", DeviceClass.MEDIA, false),
    CALENDAR_HUB("Household Calendar & Bins", DeviceClass.NETWORK, false),
    FOOTBALL_TRACKER("Football Fixtures & TV", DeviceClass.MEDIA, false),
    GUEST_WIFI("Commute & Guest Wi-Fi", DeviceClass.NETWORK, false),
    MUSIC_PLAYER("Music Player", DeviceClass.MEDIA, false);

    companion object {
        val hardwareEntries: List<DeviceSubtype>
            get() = entries.filter { it.isHardwareSubtype }
    }
}

enum class ServiceClass(val label: String, val roleDescription: String) {
    RADIO("Live Radio", "Global & UK live radio streaming directory"),
    TV_TRACKER("Entertainment Hub", "Glanceable TV & movie release dashboard and full-screen Entertainment Hub"),
    CALENDAR_HUB("Household Calendar", "Unified family calendar, birthdays, council bins, TV & football"),
    FOOTBALL_TRACKER("Football Fixtures", "Live football fixtures, scores & UK TV channel auto-tuning"),
    GUEST_WIFI("Commute & Guest Wi-Fi", "Live UK rail/road commute board and scannable Guest Wi-Fi QR"),
    MUSIC("Music Service", "In-app music catalog and multi-room playback"),
    VOICE_ASSISTANT("Voice Assistant", "Hands-free wake word and spoken command engine"),
    WEATHER("Weather Service", "Local outdoor weather and 7-day forecast")
}

enum class CapabilityOrigin(val label: String, val isHardwareTrusted: Boolean) {
    HARDWARE_REPORTED("Hardware Telemetry", true),
    DISCOVERY_PROBED("Discovery Handshake", true),
    USER_CONFIGURED("User Configured", false),
    SYNTHETIC_FALLBACK("Unverified Fallback", false)
}

enum class DeviceLifecycleState(val label: String, val badgeColorHex: Long) {
    DISCOVERED("Discovered", 0xFF00E5FF),
    SEEN("Seen", 0xFF58A6FF),
    VALIDATED("Validated", 0xFF58A6FF),
    CONNECTABLE("Connectable", 0xFF58A6FF),
    AUTHENTICATED("Authenticated", 0xFFFFB300),
    ACTIVATED("Activated", 0xFF00C6FF),
    CAPABILITY_LEARNING("Capability Learning", 0xFF00C6FF),
    CAPABILITIES_LOADED("Capabilities Loaded", 0xFFA3F0C1),
    ONLINE("Online", 0xFFA3F0C1),
    OPERATIONAL("Operational", 0xFFA3F0C1),
    OFFLINE("Offline", 0xFFFF6B6B),
    RECOVERY("Recovery", 0xFFFF9E44),
    UNPAIRED("Unpaired", 0xFF8A909A),
    UNBOUND("Unbound", 0xFF8A909A),
    DISCOVERABLE_AGAIN("Discoverable Again", 0xFF00E5FF),
    QUARANTINED_ERROR("Quarantined", 0xFFFF5252)
}

/**
 * Change 1.3: All 21 mandatory per-device operational lifecycle transitions.
 */
enum class MandatoryLifecycleTransition(
    val eventName: String,
    val defaultTargetState: DeviceLifecycleState? = null
) {
    DISCOVERED("Discovered", DeviceLifecycleState.DISCOVERED),
    VALIDATED("Validated", DeviceLifecycleState.VALIDATED),
    PAIRING_STARTED("Pairing Started", DeviceLifecycleState.CONNECTABLE),
    PAIRING_COMPLETED("Pairing Completed", DeviceLifecycleState.CONNECTABLE),
    AUTH_SUCCESS("Auth Success", DeviceLifecycleState.AUTHENTICATED),
    AUTH_FAILED("Auth Failed", DeviceLifecycleState.QUARANTINED_ERROR),
    ACTIVATION_STARTED("Activation Started", DeviceLifecycleState.AUTHENTICATED),
    ACTIVATION_COMPLETED("Activation Completed", DeviceLifecycleState.ACTIVATED),
    CAPABILITY_REQUESTED("Capability Requested", DeviceLifecycleState.CAPABILITY_LEARNING),
    CAPABILITY_RECEIVED("Capability Received", DeviceLifecycleState.CAPABILITIES_LOADED),
    ONLINE("Online", DeviceLifecycleState.ONLINE),
    OFFLINE("Offline", DeviceLifecycleState.OFFLINE),
    COMMAND_SENT("Command Sent", DeviceLifecycleState.OPERATIONAL),
    COMMAND_ACK("Command Ack", DeviceLifecycleState.OPERATIONAL),
    COMMAND_FAILED("Command Failed", null),
    RECOVERY_STARTED("Recovery Started", DeviceLifecycleState.RECOVERY),
    RECOVERY_COMPLETED("Recovery Completed", DeviceLifecycleState.OPERATIONAL),
    RECOVERY_FAILED("Recovery Failed", DeviceLifecycleState.OFFLINE),
    UNPAIRED("Unpaired", DeviceLifecycleState.UNPAIRED),
    UNBOUND("Unbound", DeviceLifecycleState.UNBOUND),
    REDISCOVERED("Rediscovered", DeviceLifecycleState.DISCOVERABLE_AGAIN);

    companion object {
        fun fromRawEvent(raw: String): MandatoryLifecycleTransition? {
            val clean = raw.trim()
            if (clean.isEmpty()) return null
            return entries.firstOrNull {
                it.eventName.equals(clean, ignoreCase = true) ||
                        it.name.equals(clean.replace(' ', '_'), ignoreCase = true)
            }
        }
    }
}

enum class StateAuthorityTier(val priorityRank: Int, val label: String) {
    DEVICE_HARDWARE_TRUTH(1, "Hardware Truth"),
    PRIMARY_MESH_AUTHORITY(2, "Primary Wall Tablet"),
    CLOUD_SHADOW_STATE(3, "Provider Cloud State"),
    SECONDARY_MESH_NODE(4, "Secondary Mesh Tablet"),
    CACHED_UI_STATE(5, "Cached UI State")
}

enum class IntentAuthorityTier(
    val priorityRank: Int,
    val holdWindowMs: Long,
    val label: String
) {
    SAFETY_CRITICAL(1, 60 * 60_000L, "Safety Critical"),
    PHYSICAL_DEVICE(2, 30 * 60_000L, "Physical Device Control"),
    MANUAL_USER(3, 30 * 60_000L, "Manual User / Voice"),
    RECOVERY(4, 0L, "Autonomous Recovery"),
    AUTOMATION(5, 0L, "Sensor / State Automation"),
    SCHEDULED_TASK(6, 0L, "Scheduled Routine")
}

enum class CommandTransactionState(val label: String) {
    IDLE("Ready"),
    PENDING_ACK("Sending..."),
    CONFIRMED("Confirmed"),
    HEALING_IN_PROGRESS("Auto-Healing..."),
    SHADOW_QUEUED("Queued for Wake"),
    FAILED_ROLLED_BACK("Unreachable")
}

enum class SelfHealingStage(val stageNumber: Int, val label: String) {
    STAGE_0_NETWORK_INTEGRITY(0, "Stage 0: State & Network Validation"),
    STAGE_1_LAN_REPROBE(1, "Stage 1: ARP & Neighbor IP Refresh"),
    STAGE_2_TOKEN_ROTATION(2, "Stage 2: Sub-Second Service Rediscovery"),
    STAGE_3_TRANSPORT_RESTART(3, "Stage 3: Silent OAuth / Session Token Renewal"),
    STAGE_4_CROSS_PROTOCOL_FAILOVER(4, "Stage 4: Socket Eviction & Connection Rebuild"),
    STAGE_5_WAKE_SIGNAL(5, "Stage 5: Background Auto-Reprovision"),
    STAGE_6_SOFTAP_REPROVISION(6, "Stage 6: Cross-Provider Failover"),
    STAGE_7_DIGITAL_TWIN_SHADOW(7, "Stage 7: Shadow Queue & User Notification"),
    STAGE_8_ROOT_CAUSE_ALERT(8, "Stage 8: Actionable Root-Cause Alert")
}

enum class DeviceEventType(val label: String) {
    STATE_CHANGED("State Changed"),
    INTENT_QUEUED("Command Queued"),
    INTENT_CONFIRMED("Hardware ACK Confirmed"),
    INTENT_ROLLED_BACK("Unconfirmed Rollback"),
    CAPABILITY_EVOLVED("Capabilities Updated"),
    RECOVERY_STAGE("Autonomous Recovery"),
    PROVIDER_FAILOVER("Provider Failover"),
    MANUAL_HOLD_ACTIVATED("30m Automation Hold"),
    ONBOARDED("Device Onboarded")
}

enum class AccountAuthStatus(val label: String, val badgeColorHex: Long) {
    VALID("OAuth Valid", 0xFFA3F0C1),
    EXPIRING_SOON("Expiring Soon", 0xFFFFB300),
    EXPIRED("Token Expired", 0xFFFF9E44),
    MFA_REQUIRED("MFA / OTP Required", 0xFFFF5252),
    PERMISSIONS_REVOKED("Permissions Revoked", 0xFFFF6B6B),
    NOT_LINKED("Not Linked", 0xFF8A909A)
}

enum class ProviderId(val id: String, val displayName: String, val isLocalTransport: Boolean) {
    TUYA_LAN("TUYA_LAN", "Tuya Local LAN (6668)", true),
    TUYA_CLOUD("TUYA_CLOUD", "Tuya / Smart Life Cloud", false),
    GOOGLE_CAST("GOOGLE_CAST", "Google Cast TLS (8009)", true),
    GOOGLE_NEST("GOOGLE_NEST", "Google Nest SDM", false),
    LG_WEBOS("LG_WEBOS", "LG webOS SSAP (3000/3001)", true),
    GOVEE_LAN("GOVEE_LAN", "Govee Local UDP (4003)", true),
    GOVEE_CLOUD("GOVEE_CLOUD", "Govee Cloud API", false),
    TWINKLY_LAN("TWINKLY_LAN", "Twinkly Local XLED", true),
    TPLINK_KLAP("TPLINK_KLAP", "TP-Link Tapo / Kasa Local", true),
    EWELINK_LAN("EWELINK_LAN", "Sonoff / eWeLink Local", true),
    SHELLY_HTTP("SHELLY_HTTP", "Shelly Local HTTP", true),
    PHILIPS_HUE("PHILIPS_HUE", "Philips Hue Local Bridge", true),
    MATTER_FABRIC("MATTER_FABRIC", "Matter Local Fabric", true),
    MQTT_BROKER("MQTT_BROKER", "MQTT Local Broker (1883)", true),
    HOME_ASSISTANT("HOME_ASSISTANT", "Home Assistant Bridge", true),
    HUBITAT_LOCAL("HUBITAT_LOCAL", "Hubitat Elevation Maker API", true),
    HOMEY_BRIDGE("HOMEY_BRIDGE", "Athom Homey Bridge", true),
    ALEXA_BRIDGE("ALEXA_BRIDGE", "Amazon Alexa Routine Bridge", false),
    SMARTTHINGS_CLOUD("SMARTTHINGS_CLOUD", "Samsung SmartThings API", false),
    OCTOPUS_ENERGY("OCTOPUS_ENERGY", "Octopus Energy Agile / Intelligent", false),
    TESLA_ENERGY("TESLA_ENERGY", "Tesla Powerwall & Solar", true),
    GIVENERGY("GIVENERGY", "GivEnergy Inverter & Battery", true),
    SOLAREDGE("SOLAREDGE", "SolarEdge Solar & Storage", false),
    FRIGATE_NVR("FRIGATE_NVR", "Frigate Local AI NVR", true),
    RTSP_ONVIF_LAN("RTSP_ONVIF_LAN", "Local RTSP / ONVIF Video (554)", true),
    UNIFI_NETWORK("UNIFI_NETWORK", "UniFi Network Spatial Presence", true),
    IMOU_CLOUD("IMOU_CLOUD", "Imou Camera Cloud / RTSP", false),
    EZVIZ_CLOUD("EZVIZ_CLOUD", "EZVIZ Cloud / RTSP", false),
    JAGUAR_INCONTROL("JAGUAR_INCONTROL", "Jaguar InControl", false),
    MERCEDES_ME("MERCEDES_ME", "Mercedes me Connect", false),
    TESLA_FLEET("TESLA_FLEET", "Tesla Fleet API", false),
    BMW_CONNECTED("BMW_CONNECTED", "BMW ConnectedDrive", false),
    AUDI_CONNECT("AUDI_CONNECT", "myAudi Connect", false),
    VW_WECONNECT("VW_WECONNECT", "Volkswagen We Connect", false),
    VOLVO_CARS("VOLVO_CARS", "Volvo Cars API", false),
    FORD_PASS("FORD_PASS", "FordPass Connect", false),
    POLESTAR_API("POLESTAR_API", "Polestar Connected", false),
    HYUNDAI_BLUELINK("HYUNDAI_BLUELINK", "Hyundai Bluelink", false),
    KIA_CONNECT("KIA_CONNECT", "Kia Connect", false),
    SYSTEM_LOCAL("SYSTEM_LOCAL", "Built-in System Service", true),
    UNKNOWN("UNKNOWN", "Auto-Resolved Provider", true);

    companion object {
        fun fromRawKey(raw: String): ProviderId {
            val clean = raw.trim().uppercase(Locale.US)
            if (clean.isBlank()) return UNKNOWN
            return when {
                clean.contains("TUYA_LAN") || clean.contains("6668") -> TUYA_LAN
                clean.contains("TUYA") || clean.contains("SMARTLIFE") || clean.contains("SMART_LIFE") -> TUYA_CLOUD
                clean.contains("CAST") || clean.contains("8009") -> GOOGLE_CAST
                clean.contains("NEST") -> GOOGLE_NEST
                clean.contains("LG") || clean.contains("WEBOS") || clean.contains("SSAP") -> LG_WEBOS
                clean.contains("GOVEE_LAN") || clean.contains("4003") || clean == "GOVEE_TWINKLY_UDP" -> GOVEE_LAN
                clean.contains("GOVEE") -> GOVEE_CLOUD
                clean.contains("TWINKLY") || clean.contains("XLED") -> TWINKLY_LAN
                clean.contains("TAPO") || clean.contains("KASA") || clean.contains("TPLINK") || clean.contains("KLAP") -> TPLINK_KLAP
                clean.contains("EWELINK") || clean.contains("SONOFF") -> EWELINK_LAN
                clean.contains("SHELLY") -> SHELLY_HTTP
                clean.contains("HUE") -> PHILIPS_HUE
                clean.contains("MATTER") -> MATTER_FABRIC
                clean.contains("MQTT") || clean.contains("TASMOTA") || clean.contains("ESPHOME") -> MQTT_BROKER
                clean.contains("HOME_ASSISTANT") || clean == "HA" -> HOME_ASSISTANT
                clean.contains("HUBITAT") -> HUBITAT_LOCAL
                clean.contains("HOMEY") -> HOMEY_BRIDGE
                clean.contains("ALEXA") -> ALEXA_BRIDGE
                clean.contains("SMARTTHINGS") -> SMARTTHINGS_CLOUD
                clean.contains("OCTOPUS") -> OCTOPUS_ENERGY
                clean.contains("POWERWALL") || clean.contains("TESLA_ENERGY") || clean.contains("WALL_CONNECTOR") || clean.contains("TESLA_GEN3") -> TESLA_ENERGY
                clean.contains("GIVENERGY") -> GIVENERGY
                clean.contains("SOLAREDGE") -> SOLAREDGE
                clean.contains("FRIGATE") -> FRIGATE_NVR
                clean.contains("RTSP_ONVIF") || clean.contains("ONVIF") || clean == "RTSP_LAN" -> RTSP_ONVIF_LAN
                clean.contains("UNIFI") -> UNIFI_NETWORK
                clean.contains("IMOU") -> IMOU_CLOUD
                clean.contains("EZVIZ") -> EZVIZ_CLOUD
                clean.contains("JAGUAR") -> JAGUAR_INCONTROL
                clean.contains("MERCEDES") -> MERCEDES_ME
                clean.contains("TESLA") -> TESLA_FLEET
                clean.contains("BMW") -> BMW_CONNECTED
                clean.contains("AUDI") -> AUDI_CONNECT
                clean.contains("VW") || clean.contains("VOLKSWAGEN") -> VW_WECONNECT
                clean.contains("VOLVO") -> VOLVO_CARS
                clean.contains("FORD") -> FORD_PASS
                clean.contains("POLESTAR") -> POLESTAR_API
                clean.contains("HYUNDAI") || clean.contains("BLUELINK") -> HYUNDAI_BLUELINK
                clean.contains("KIA") -> KIA_CONNECT
                else -> entries.firstOrNull { it.name == clean || it.id == clean } ?: UNKNOWN
            }
        }
    }
}

enum class DeviceCapabilityKind(val label: String) {
    BOOLEAN_SWITCH("Switch / Toggle"),
    ENUM_MODE("Mode Selector"),
    NUMERIC_SLIDER("Level / Slider"),
    COUNTDOWN_TIMER("Countdown Timer"),
    READ_ONLY_SENSOR("Telemetry Sensor"),
    ACTION_TRIGGER("Instant Action")
}

enum class CapabilitySafetyTier {
    STANDARD,
    CLIMATE_HOLDABLE,
    HIGH_LOAD_APPLIANCE,
    SECURITY_CRITICAL
}

enum class CapabilityRendererType(val label: String) {
    TOGGLE("Toggle Button"),
    INPUT_SELECTOR("Input Source Selector"),
    CHIP_GROUP("Mode / Preset Chips"),
    DIAL("Radial Dial"),
    TEMPERATURE_DIAL("Temperature Dial"),
    FAN_SPEED_STEPPER("Fan Speed Stepper"),
    BRIGHTNESS_SLIDER("Brightness Slider"),
    VOLUME_SLIDER("Volume Slider"),
    COVER_POSITION_SLIDER("Cover Position Slider"),
    SLIDER("Linear Slider"),
    STEP_BAR("Discrete Step Bar"),
    COLOR_WHEEL("360\u00B0 RGB Wheel"),
    MEDIA_TRANSPORT("Media Transport"),
    D_PAD("Directional Pad"),
    ENERGY_GRAPH("Live Energy Graph"),
    SENSOR_BADGE("Telemetry Badge")
}

enum class SecurityCenterMode(val label: String, val subtitle: String) {
    HOME("Home", "Perimeter armed, indoor sensors disarmed"),
    AWAY("Away", "All cameras, motion sensors & vehicle locks armed"),
    NIGHT("Night", "Doors locked, vehicles locked & outdoor cameras armed"),
    VACATION("Vacation", "Maximum security, eco climate & presence simulation")
}

enum class CameraAiEventType(val label: String, val badgeColorHex: Long) {
    NONE("Clear", 0xFF8A909A),
    PERSON("Person Detected", 0xFFFF6B6B),
    VEHICLE("Vehicle Detected", 0xFF00E5FF),
    PACKAGE("Package Delivered", 0xFFFFB300),
    ANIMAL("Pet / Animal", 0xFFA3F0C1),
    MOTION("Motion Alert", 0xFFFF9E44),
    DOORBELL_RING("Doorbell Ring", 0xFFE040FB)
}

enum class ApplianceFingerprintCategory(
    val label: String,
    val suggestedSocketName: String,
    val typicalMinWatts: Float,
    val typicalMaxWatts: Float
) {
    UNKNOWN("Unclassified Load", "", 0f, 0f),
    PHONE_CHARGER("Charger / Standby Hub", "Device Charger", 2f, 24f),
    LIGHTING("LED Lamp / Ambient Light", "Lamp", 4f, 38f),
    POND_PUMP("Continuous Pump / Filter", "Filter Pump", 39f, 75f),
    TV_CONSOLES("TV / Media Console", "Entertainment System", 76f, 240f),
    WASHING_MACHINE("Washing Machine / Dryer", "Washing Machine", 245f, 1350f),
    SPACE_HEATER("Electric Heater / Radiator", "Electric Heater", 1351f, 2150f),
    KETTLE("Electric Kettle / Boiler", "Kettle", 2151f, 3100f),
    EV_CHARGER("EV Charger / Heavy Load", "EV Charger", 3101f, 7500f);

    fun toDeviceSubtype(): DeviceSubtype? = when (this) {
        UNKNOWN -> null
        PHONE_CHARGER -> DeviceSubtype.SMART_PLUG
        LIGHTING -> DeviceSubtype.BULB
        POND_PUMP -> DeviceSubtype.POND_PUMP
        TV_CONSOLES -> DeviceSubtype.TV
        WASHING_MACHINE -> DeviceSubtype.WASHER
        SPACE_HEATER -> DeviceSubtype.RADIATOR
        KETTLE -> DeviceSubtype.KETTLE
        EV_CHARGER -> DeviceSubtype.EV_CHARGER
    }
}

enum class DiscoveryProtocol(val label: String) {
    MATTER("Matter Fabric & mDNS"),
    TPLINK_KLAP_KASA("TP-Link KLAP & Kasa Local"),
    EWELINK_LAN("eWeLink Local LAN & Cloud"),
    MQTT_DISCOVERY("MQTT / Tasmota / ESPHome"),
    MDNS("mDNS / Bonjour"),
    SSDP("SSDP / UPnP"),
    BLE("Bluetooth LE"),
    WIFI_AP("Wi-Fi Setup Hotspot"),
    CLOUD_OAUTH("Cloud Account Sync"),
    LAN_PROBE("Local Wi-Fi Probe"),
    TUYA_UDP("Local UDP Broadcast"),
    GOVEE_LAN("Govee UDP 4001–4003 & API"),
    TWINKLY_XLED("Twinkly XLED REST & UDP"),
    HOME_ASSISTANT("Home Assistant Bridge"),
    SYSTEM_WIDGET("Built-In Widget")
}

enum class DiscoveredDeviceActionType(val buttonLabel: String) {
    ADD_DIRECT("Add"),
    START_PAIRING("Start Pairing"),
    OAUTH_SIGN_IN("Sign In / OAuth")
}

enum class TileType(val brandName: String) {
    CLOCK_WEATHER("Clock & Weather"),
    IMOU_CAMERA("Imou"),
    NEST_THERMOSTAT("Nest"),
    NEST_DOORBELL("Nest"),
    NEST_MINI("Nest"),
    EZVIZ_DOORBELL("Ezviz"),
    TUYA_RADIATOR("Tuya"),
    SMARTLIFE_DEVICE("Smart Life"),
    AIRROBO_VACUUM("Airrobo"),
    SMARTKNIGHT_PLUG("SmartKnight"),
    LG_TV("LG ThinQ"),
    TAPO_DEVICE("Tapo / Kasa"),
    HUE_LIGHT("Hue"),
    GOVEE_LIGHT("Govee"),
    TWINKLY_LIGHT("Twinkly"),
    SHELLY_RELAY("Shelly / Tasmota"),
    SONOFF_SWITCH("Sonoff / eWeLink"),
    MATTER_DEVICE("Matter"),
    MQTT_DEVICE("MQTT / ESPHome"),
    RING_CAMERA("Ring"),
    SMART_LOCK("Smart Lock"),
    SMART_COVER("Blinds & Garage"),
    SMART_SENSOR("Sensor"),
    MUSIC_PLAYER("Music"),
    JARVIS_SCENES("Routines"),
    PHOTO_SLIDESHOW("Photo Frame"),
    GROUP_CARD("Group Card"),
    ENERGY_MONITOR("Electricity"),
    MACRO_TILE("Macro"),
    JAGUAR_VEHICLE("Jaguar InControl"),
    MERCEDES_VEHICLE("Mercedes me"),
    LYTMI_SYNC_BOX("Lytmi Sync"),
    PROBREEZE_FAN("Pro Breeze"),
    SECURITY_CENTER("Security Center")
}

enum class BrandOption(
    val label: String,
    val defaultTileTitle: String,
    val defaultTileType: TileType,
    val companionPackage: String,
    val description: String,
    val defaultRoom: String,
    val discoveryProtocols: Set<DiscoveryProtocol>,
    val supportsCloudSync: Boolean = false,
    val supportsCustomRtsp: Boolean = false,
    val defaultChannelId: String = "0"
) {
    JAGUAR(
        label = "Jaguar InControl (EV / Fuel, Lock, Climate & Range)",
        defaultTileTitle = "Jaguar I-PACE",
        defaultTileType = TileType.JAGUAR_VEHICLE,
        companionPackage = "com.jlr.jaguar.incontrolremote.appstore",
        description = "Live Jaguar lock state, EV battery / fuel %, range, pre-conditioning climate & remote lock/flash",
        defaultRoom = "Driveway",
        discoveryProtocols = setOf(DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    MERCEDES(
        label = "Mercedes me Connect (Lock, EV / Fuel, Climate & Range)",
        defaultTileTitle = "Mercedes-Benz",
        defaultTileType = TileType.MERCEDES_VEHICLE,
        companionPackage = "com.daimler.ris.mercedesme.ece.android",
        description = "Live Mercedes-Benz lock state, battery / fuel %, range, auxiliary climate & remote lock",
        defaultRoom = "Driveway",
        discoveryProtocols = setOf(DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    LYTMI(
        label = "Lytmi HDMI 2.1 Sync Box & Ambient Backlight",
        defaultTileTitle = "Lytmi TV Sync Box",
        defaultTileType = TileType.LYTMI_SYNC_BOX,
        companionPackage = "com.tuya.smartlife",
        description = "Local LAN & Tuya BLE/Wi-Fi control for Lytmi HDMI 1–4 inputs, 360° RGB wheel, Video/Gaming/Music sync & scenes",
        defaultRoom = "Living Room",
        discoveryProtocols = setOf(DiscoveryProtocol.TUYA_UDP, DiscoveryProtocol.BLE, DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    GOVEE(
        label = "Govee RGBIC Ambient Lights, DreamView & Strips",
        defaultTileTitle = "Govee Ambient Light",
        defaultTileType = TileType.GOVEE_LIGHT,
        companionPackage = "com.govee.home",
        description = "Local UDP 4001–4003 LAN control, BLE discovery & Govee Cloud API with 360° RGB wheel, Video, Game, Music & Scene modes",
        defaultRoom = "Living Room",
        discoveryProtocols = setOf(DiscoveryProtocol.GOVEE_LAN, DiscoveryProtocol.BLE, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    TWINKLY(
        label = "Twinkly Smart LED Matrix, Strings & Squares",
        defaultTileTitle = "Twinkly LED Lights",
        defaultTileType = TileType.TWINKLY_LIGHT,
        companionPackage = "com.twinkly",
        description = "Local /xled/v1/ REST & UDP discovery for Twinkly lights with 360° RGB colour wheel, Movie, Music, Effect & Scene modes",
        defaultRoom = "Living Room",
        discoveryProtocols = setOf(DiscoveryProtocol.TWINKLY_XLED, DiscoveryProtocol.BLE, DiscoveryProtocol.LAN_PROBE),
        supportsCloudSync = true
    ),
    PLEX(
        label = "Plex Media Server (Local LAN Audio & Media)",
        defaultTileTitle = "Plex Media Player",
        defaultTileType = TileType.MUSIC_PLAYER,
        companionPackage = "com.plexapp.android",
        description = "Connect your local Plex Media Server for high-bitrate LAN music & multi-room Cast streaming",
        defaultRoom = "Living Room",
        discoveryProtocols = setOf(DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.MDNS),
        supportsCloudSync = true
    ),
    SECURITY_HUB(
        label = "Clara Security Center (Home / Away / Night / Vacation)",
        defaultTileTitle = "Security Center",
        defaultTileType = TileType.SECURITY_CENTER,
        companionPackage = "",
        description = "Unified perimeter alarm, camera AI event monitor, DVR snapshot history & vehicle auto-lock",
        defaultRoom = "Front Door",
        discoveryProtocols = setOf(DiscoveryProtocol.SYSTEM_WIDGET)
    ),
    MATTER(
        label = "Matter (Eve, Aqara, Meross, SwitchBot, Nanoleaf, Tapo)",
        defaultTileTitle = "Matter Smart Device",
        defaultTileType = TileType.MATTER_DEVICE,
        companionPackage = "com.google.android.apps.chromecast.app",
        description = "Automatic Matter mDNS & commissioning for Eve, Aqara, Meross, SwitchBot, Nanoleaf & Tuya Matter",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.MATTER, DiscoveryProtocol.MDNS, DiscoveryProtocol.BLE),
        supportsCloudSync = true
    ),
    TAPO(
        label = "TP-Link Tapo & Kasa (P100 / P110 / HS110 / KP115)",
        defaultTileTitle = "Tapo Smart Plug",
        defaultTileType = TileType.TAPO_DEVICE,
        companionPackage = "com.tplink.iot",
        description = "Native local KLAP & port 9999 discovery for Tapo & Kasa plugs, power strips, and lights",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.TPLINK_KLAP_KASA, DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    SONOFF(
        label = "eWeLink / Sonoff Smart Switch",
        defaultTileTitle = "Sonoff Switch",
        defaultTileType = TileType.SONOFF_SWITCH,
        companionPackage = "com.coolkit",
        description = "Native _ewelink._tcp mDNS, DIY Mode & eWeLink account discovery for Sonoff switches",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.EWELINK_LAN, DiscoveryProtocol.MDNS, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    MQTT_TASMOTA_ESPHOME(
        label = "MQTT Auto-Discovery, Tasmota & ESPHome",
        defaultTileTitle = "Tasmota / ESPHome Switch",
        defaultTileType = TileType.SHELLY_RELAY,
        companionPackage = "",
        description = "Direct MQTT Broker Auto-Discovery, Tasmota HTTP & ESPHome mDNS discovery without a hub",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.MQTT_DISCOVERY, DiscoveryProtocol.MDNS, DiscoveryProtocol.LAN_PROBE),
        supportsCloudSync = true
    ),
    NEST(
        label = "Google Nest",
        defaultTileTitle = "Nest Thermostat",
        defaultTileType = TileType.NEST_THERMOSTAT,
        companionPackage = "com.google.android.apps.chromecast.app",
        description = "Automatic Google OAuth & mDNS discovery for Nest Thermostats, Doorbells & Speakers",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.CLOUD_OAUTH, DiscoveryProtocol.MDNS),
        supportsCloudSync = true
    ),
    LG_THINQ(
        label = "LG ThinQ / LG webOS Smart TV",
        defaultTileTitle = "Smart TV",
        defaultTileType = TileType.LG_TV,
        companionPackage = "com.lgeha.nuts",
        description = "Automatic SSDP & Wi-Fi discovery for LG TVs (Power, Volume, Apps & Remote)",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.SSDP, DiscoveryProtocol.LAN_PROBE),
        supportsCloudSync = true
    ),
    IMOU(
        label = "Imou Camera",
        defaultTileTitle = "Imou Camera",
        defaultTileType = TileType.IMOU_CAMERA,
        companionPackage = "com.mm.android.direct.imou",
        description = "Automatic Cloud & Wi-Fi discovery for Imou cameras with PTZ",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.CLOUD_OAUTH, DiscoveryProtocol.LAN_PROBE),
        supportsCloudSync = true,
        supportsCustomRtsp = true,
        defaultChannelId = "0"
    ),
    EZVIZ(
        label = "Ezviz Doorbell / Camera",
        defaultTileTitle = "Ezviz Doorbell",
        defaultTileType = TileType.EZVIZ_DOORBELL,
        companionPackage = "com.videogo",
        description = "Automatic EZVIZ account & Wi-Fi discovery for doorbells and cameras",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.CLOUD_OAUTH, DiscoveryProtocol.LAN_PROBE),
        supportsCloudSync = true,
        supportsCustomRtsp = true,
        defaultChannelId = "1"
    ),
    SMARTKNIGHT(
        label = "SmartKnight / Tuya Smart Plug",
        defaultTileTitle = "Socket",
        defaultTileType = TileType.SMARTKNIGHT_PLUG,
        companionPackage = "com.tuya.smartlife",
        description = "Automatic Bluetooth LE, Wi-Fi & UDP discovery for single and dual-socket smart plugs",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.BLE, DiscoveryProtocol.TUYA_UDP, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    TUYA(
        label = "Tuya / Panel Heater / TRV",
        defaultTileTitle = "Heater",
        defaultTileType = TileType.TUYA_RADIATOR,
        companionPackage = "com.tuya.smart",
        description = "Automatic Bluetooth LE, Wi-Fi & Cloud discovery for smart heaters and thermostats",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.BLE, DiscoveryProtocol.TUYA_UDP, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    SMART_LIFE(
        label = "Smart Life Switch / Plug",
        defaultTileTitle = "Smart Life Switch",
        defaultTileType = TileType.SMARTLIFE_DEVICE,
        companionPackage = "com.tuya.smartlife",
        description = "Automatic Bluetooth LE, Wi-Fi & Smart Life discovery for switches, plugs & lights",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.BLE, DiscoveryProtocol.TUYA_UDP, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    AIRROBO(
        label = "Airrobo / Robot Vacuum",
        defaultTileTitle = "Airrobo Vacuum",
        defaultTileType = TileType.AIRROBO_VACUUM,
        companionPackage = "com.tuya.smartlife",
        description = "Automatic Bluetooth LE & Smart Life discovery for robot vacuums",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.BLE, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    PRO_BREEZE(
        label = "Pro Breeze Smart Tower Fan (Speed, Wind Mode, Oscillation & Timer)",
        defaultTileTitle = "Pro Breeze Tower Fan",
        defaultTileType = TileType.PROBREEZE_FAN,
        companionPackage = "com.tuya.smartlife",
        description = "Local LAN 6668, BLE & Smart Life control for Pro Breeze Tower Fans (6 speeds, Normal/Natural/Sleep/Auto, Oscillation, Ionizer &\n Timer)",
        defaultRoom = "Living Room",
        discoveryProtocols = setOf(DiscoveryProtocol.BLE, DiscoveryProtocol.TUYA_UDP, DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.CLOUD_OAUTH),
        supportsCloudSync = true
    ),
    HUE(
        label = "Philips Hue Light",
        defaultTileTitle = "Light 1",
        defaultTileType = TileType.HUE_LIGHT,
        companionPackage = "com.philips.lighting.hue2",
        description = "Automatic local Hue Bridge HTTP, mDNS & Home Assistant discovery for smart lights",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.MDNS, DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.HOME_ASSISTANT)
    ),
    SHELLY(
        label = "Shelly Local Relay",
        defaultTileTitle = "Shelly Relay",
        defaultTileType = TileType.SHELLY_RELAY,
        companionPackage = "allterco.bg.shelly",
        description = "Automatic mDNS & local HTTP discovery for Shelly Wi-Fi relays",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.MDNS, DiscoveryProtocol.LAN_PROBE, DiscoveryProtocol.HOME_ASSISTANT)
    ),
    RING(
        label = "Ring Camera / Doorbell",
        defaultTileTitle = "Ring Doorbell",
        defaultTileType = TileType.RING_CAMERA,
        companionPackage = "com.ringapp",
        description = "Automatic notification bridge & smart home hub discovery for Ring doorbells",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.HOME_ASSISTANT, DiscoveryProtocol.LAN_PROBE),
        supportsCustomRtsp = true
    ),
    LOCKS_COVERS(
        label = "Locks, Blinds & Sensors",
        defaultTileTitle = "Front Door Lock",
        defaultTileType = TileType.SMART_LOCK,
        companionPackage = "",
        description = "Automatic Matter, MQTT & smart home hub discovery for locks, blinds and sensors",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.MATTER, DiscoveryProtocol.MQTT_DISCOVERY, DiscoveryProtocol.HOME_ASSISTANT, DiscoveryProtocol.BLE)
    ),
    PHOTO_SLIDESHOW(
        label = "Photo Frame & Cloud Slideshow",
        defaultTileTitle = "Family Photos",
        defaultTileType = TileType.PHOTO_SLIDESHOW,
        companionPackage = "com.google.android.apps.photos",
        description = "Live Photo Slideshow Widget (Local Folders/Subfolders, Google Photos or OneDrive)",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.SYSTEM_WIDGET, DiscoveryProtocol.CLOUD_OAUTH)
    ),
    ENERGY_WIDGET(
        label = "Electricity & Energy Usage Chart",
        defaultTileTitle = "Electricity Consumed",
        defaultTileType = TileType.ENERGY_MONITOR,
        companionPackage = "",
        description = "Live Power (W) & Energy (kWh) consumption chart across all smart plugs",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.SYSTEM_WIDGET)
    ),
    SYSTEM(
        label = "Dashboard Widgets (Group Cards, Music, Energy & Routines)",
        defaultTileTitle = "Media Player",
        defaultTileType = TileType.MUSIC_PLAYER,
        companionPackage = "com.spotify.music",
        description = "Group Cards, Media Player, Electricity Chart, Photo Frame & Home Routines Widgets",
        defaultRoom = "",
        discoveryProtocols = setOf(DiscoveryProtocol.SYSTEM_WIDGET)
    );

    val discoverySummary: String
        get() = discoveryProtocols.joinToString(" \u2022 ") { it.label }
}

enum class MusicProvider(val label: String, val packageName: String) {
    SPOTIFY("Spotify", "com.spotify.music"),
    YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music")
}

enum class PtzDirection(val code: String, val imouCode: Int) {
    UP("Up", 0),
    DOWN("Down", 1),
    LEFT("Left", 2),
    RIGHT("Right", 3),
    ZOOM_IN("ZoomTele", 8),
    ZOOM_OUT("ZoomWide", 9)
}

enum class AutomationTriggerType(val label: String, val subtitle: String) {
    TIME_SCHEDULE(
        "Time Schedule",
        "Trigger at a specific time of day on selected days"
    ),
    SUNRISE_SUNSET(
        "Sunset / Sunrise (Day & Night)",
        "Trigger when outdoor daylight changes to Sunset or Sunrise"
    ),
    DEVICE_STATE_CHANGE(
        "Device Turns ON or OFF",
        "Trigger when a specific device, socket, light, or lock changes state"
    ),
    TEMPERATURE_THRESHOLD(
        "Room Temperature (\u00B0C)",
        "Trigger when a thermostat or heater temperature goes above or below X\u00B0C"
    ),
    POWER_WATTS_THRESHOLD(
        "Plug Power Draw (Watts)",
        "Trigger when a SmartKnight / smart plug power usage goes above or below X Watts"
    ),
    DOORBELL_RING(
        "Doorbell Rings",
        "Trigger immediately when the front doorbell rings"
    ),
    WEATHER_CONDITION(
        "Outdoor Weather / Temp",
        "Trigger when outdoor weather matches Rain/Snow/Clear or outdoor \u00B0C"
    ),
    SECURITY_MODE_IS(
        "Household Security Mode Is",
        "Match when Clara Security Center is in Home, Away, Night, or Vacation mode"
    ),
    TARIFF_WINDOW_IS(
        "Electricity Tariff Window Is",
        "Match when electricity is in Off-Peak Night Rate or Peak Day Rate window"
    ),
    DAYLIGHT_IS(
        "Outdoor Daylight State Is",
        "Match when it is currently Daytime or Nighttime outdoors"
    ),
    CAPABILITY_VALUE_MATCH(
        "Device Capability / Sensor Value",
        "Match any hardware capability or sensor (e.g. lux, humidity, battery %, mode)"
    )
}

enum class ComparisonOperator(val symbol: String, val label: String) {
    BELOW("<", "Drops Below (<)"),
    ABOVE(">", "Goes Above (>)"),
    EQUALS("==", "Equals (=)")
}

enum class DeviceTriggerTargetState(val label: String) {
    TURNED_ON("Turns ON"),
    TURNED_OFF("Turns OFF"),
    SOCKET_1_ON("Socket 1 Turns ON"),
    SOCKET_1_OFF("Socket 1 Turns OFF"),
    SOCKET_2_ON("Socket 2 Turns ON"),
    SOCKET_2_OFF("Socket 2 Turns OFF")
}

enum class ConditionCombinator(val label: String) {
    AND("AND (All Conditions Must Match)"),
    OR("OR (Any Condition Can Match)")
}

enum class AutomationTargetScope(val label: String) {
    SINGLE_TILE("Specific Device / Socket"),
    DYNAMIC_SELECTOR("Capability / Device Class Group")
}

enum class AutomationActionType(val label: String, val requiresTargetTile: Boolean = true) {
    TURN_ON("Turn Device ON", true),
    TURN_OFF("Turn Device OFF", true),
    TOGGLE("Toggle Device ON / OFF", true),
    SOCKET_1_ON("Dual Socket: Turn Socket 1 ON", true),
    SOCKET_1_OFF("Dual Socket: Turn Socket 1 OFF", true),
    SOCKET_2_ON("Dual Socket: Turn Socket 2 ON", true),
    SOCKET_2_OFF("Dual Socket: Turn Socket 2 OFF", true),
    BOTH_SOCKETS_ON("Dual Socket: Turn BOTH Sockets ON", true),
    BOTH_SOCKETS_OFF("Dual Socket: Turn BOTH Sockets OFF", true),
    SET_TEMPERATURE("Set Target Temperature (\u00B0C)", true),
    SET_HVAC_MODE("Set Heating Mode (HEAT / ECO / OFF)", true),
    SET_LEVEL_OR_VOLUME("Set Brightness / Volume / Blinds (%)", true),
    RING_NEST_MINI_CHIME("Nest Mini: Play Doorbell Chime & Announcement", true),
    OPEN_LIVE_CAMERA("Open Inline Live Camera Stream", true),
    CLOSE_LIVE_CAMERA("Close Inline Live Camera Stream", true),
    WAIT_DELAY("Wait / Delay Before Next Step", false),
    EXECUTE_SCENE("Run Home Routine Scene", false),
    SPEAK_ANNOUNCEMENT("Speak Voice Announcement (TTS)", false);

    companion object {
        val OPEN_CAMERA_FEED: AutomationActionType get() = OPEN_LIVE_CAMERA
        val CLOSE_CAMERA_FEED: AutomationActionType get() = CLOSE_LIVE_CAMERA
    }
}

enum class AutomationRunStatus(val label: String) {
    SUCCESS("Success"),
    PARTIAL_FAILURE("Partial Failure"),
    FAILED("Failed")
}

enum class JarvisEventCategory(val label: String) {
    MOTION("Motion"),
    DOORBELL("Doorbell"),
    CAMERA_AI("Camera AI"),
    LIGHT("Lighting"),
    CLIMATE("Climate"),
    PLUG_POWER("Plug & Power"),
    MEDIA("Media & Cast"),
    VEHICLE("Vehicle"),
    LYTMI_SYNC("Lytmi Ambient"),
    SECURITY_MODE("Security Center"),
    DEVICE_OFFLINE("Device Offline"),
    SELF_HEALING("Self-Healing"),
    SYSTEM("System")
}

enum class ConnectedVehicleBrand(
    val brandKey: String,
    val displayName: String,
    val providerId: ProviderId,
    val defaultIsEv: Boolean = true
) {
    JAGUAR("JAGUAR", "Jaguar InControl", ProviderId.JAGUAR_INCONTROL, true),
    MERCEDES("MERCEDES", "Mercedes me Connect", ProviderId.MERCEDES_ME, true),
    TESLA("TESLA", "Tesla Fleet API", ProviderId.TESLA_FLEET, true),
    BMW("BMW", "BMW ConnectedDrive", ProviderId.BMW_CONNECTED, true),
    AUDI("AUDI", "myAudi Connect", ProviderId.AUDI_CONNECT, true),
    VW("VW", "Volkswagen We Connect", ProviderId.VW_WECONNECT, true),
    VOLVO("VOLVO", "Volvo Cars", ProviderId.VOLVO_CARS, true),
    FORD("FORD", "FordPass Connect", ProviderId.FORD_PASS, true),
    POLESTAR("POLESTAR", "Polestar Connected", ProviderId.POLESTAR_API, true),
    HYUNDAI("HYUNDAI", "Hyundai Bluelink", ProviderId.HYUNDAI_BLUELINK, true),
    KIA("KIA", "Kia Connect", ProviderId.KIA_CONNECT, true);

    companion object {
        fun fromTileOrKey(tile: SmartTile): ConnectedVehicleBrand {
            val raw = "${tile.activeProviderId} ${tile.companionAppPackage} ${tile.title}".uppercase(Locale.US)
            return entries.firstOrNull { raw.contains(it.brandKey) || tile.resolvedProviderId == it.providerId }
                ?: if (tile.type == TileType.MERCEDES_VEHICLE) MERCEDES else JAGUAR
        }
    }
}

enum class KnowledgeGraphPrimitiveType(val label: String) {
    HOME("Home"),
    FLOOR("Floor / Zone"),
    PLACE("Room / Place"),
    PERSON("Household Member"),
    DEVICE("Canonical Device"),
    CAPABILITY("Hardware Capability"),
    ROUTINE("Automation Routine"),
    EVENT("Causal / Sensor Event"),
    TARIFF_SLOT("Energy Tariff Slot")
}

enum class KnowledgeGraphEdgeType(val label: String) {
    CONTAINS("CONTAINS"),
    LOCATED_IN("LOCATED_IN"),
    OCCUPIES("OCCUPIES"),
    EXPOSES("EXPOSES"),
    GOVERNS("GOVERNS"),
    TRIGGERS("TRIGGERS"),
    CAUSED_BY("CAUSED_BY"),
    OPTIMIZED_BY("OPTIMIZED_BY"),
    MULTI_HOMED_WITH("MULTI_HOMED_WITH")
}

enum class OnboardingEntryMode(
    val title: String,
    val subtitle: String,
    val primaryRadioPass: OnboardingScanRadioPass
) {
    FAST_BLINKING(
        title = "Fast Blinking",
        subtitle = "Smart plugs, lights & switches blinking rapidly (twice per second)",
        primaryRadioPass = OnboardingScanRadioPass.BLE_SETUP_ONLY
    ),
    SLOW_BLINKING_AP(
        title = "Slow Blinking / AP Mode",
        subtitle = "Devices blinking slowly (once every 2–3 seconds) with a SmartLife or device hotspot",
        primaryRadioPass = OnboardingScanRadioPass.SOFT_AP_ONLY
    ),
    ALREADY_ON_NETWORK(
        title = "Already on Wi-Fi / Ethernet",
        subtitle = "LG webOS TVs, Google Cast speakers, Shelly relays, Tapo plugs & RTSP cameras",
        primaryRadioPass = OnboardingScanRadioPass.LAN_ONLY
    ),
    MATTER_OR_CLOUD(
        title = "Matter QR / Cloud Account",
        subtitle = "Commission a Matter device or link SmartLife, Google Nest, Imou, EZVIZ or vehicles",
        primaryRadioPass = OnboardingScanRadioPass.MATTER_MDNS_BLE
    )
}

enum class OnboardingScanRadioPass(val label: String) {
    LAN_ONLY("Local Wi-Fi / Ethernet Scan"),
    BLE_SETUP_ONLY("Bluetooth LE Setup Beacon Scan"),
    SOFT_AP_ONLY("Device Wi-Fi Hotspot Scan"),
    MATTER_MDNS_BLE("Matter Commissioning Scan")
}

enum class WifiBandValidationStatus(val isCompatibleWith24GhzHardware: Boolean, val badgeLabel: String) {
    VALID_24GHZ_ONLY(true, "2.4 GHz Ready"),
    VALID_DUAL_BAND_MESH(true, "Dual-Band (2.4 GHz Verified)"),
    BLOCKED_5GHZ_OR_6GHZ_ONLY(false, "5 GHz / 6 GHz Only — Incompatible"),
    UNKNOWN_UNSCANNED(true, "Unverified Band")
}

enum class UnifiedOnboardingStage(
    val stepTitle: String,
    val consumerSubtitle: String,
    val isTerminal: Boolean = false
) {
    SELECT_MODE("Choose Setup Mode", "Select how your device is ready to connect"),
    SCANNING_CANDIDATES("Searching Nearby", "Looking for a single ready device"),
    SELECT_DEVICE("Select Your Device", "Choose one device to set up"),
    CONFIGURE_WIFI("2.4 GHz Wi-Fi Setup", "Confirm home Wi-Fi credentials"),
    AUTHENTICATING("Authenticating", "Verifying secure device handshake"),
    ACTIVATING("Activating Device", "Completing local or network activation"),
    CLOUD_REGISTERING("Registering Device", "Synchronizing device ownership"),
    BINDING_KEYS("Binding Security Keys", "Saving encrypted local control keys"),
    DISCOVERING_CAPABILITIES("Learning Capabilities", "Reading verified hardware controls"),
    INITIAL_SYNC("Initial State Sync", "Confirming live hardware response"),
    COMPLETED("Device Ready", "Verified and added to your home", true),
    TIMEOUT_NO_DEVICE("We couldn't find your device yet", "Check power or indicator light and try again", true),
    FAILED("Setup Did Not Complete", "Review the guidance below to retry", true)
}
