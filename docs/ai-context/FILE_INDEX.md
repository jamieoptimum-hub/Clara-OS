# File Index

| Filename | Purpose | Dependencies | Used By | Risk Level |
| :--- | :--- | :--- | :--- | :--- |
| `app/build.gradle.kts` | Main build configuration | Gradle, Plugins | System | High |
| `WidgetRegistry.kt` | Registry for dashboard widgets | Compose | `DefaultWidgetsRegistrar` | High |
| `DefaultWidgetsRegistrar.kt` | Wires widgets into the dashboard | Compose, All Widgets | App Initialization | High |
| `WidgetContext.kt` | Legacy monolithic state object | Core Models | Legacy Widgets | High |
| `DeviceRepository.kt` | Central state store for smart devices | API Clients | UI Layer | Critical |
| `DashboardTiles.kt` | Legacy massive UI file for home tiles | Compose | Dashboard | Critical |
| `Enums.kt` | Core enumerations (e.g. DashboardWidgetType) | None | Project-wide | Low |
| `widgets/football/Manifest/FootballManifest.kt` | Entry point for Football CWAS widget | Compose | `DefaultWidgetsRegistrar` | Low |
| `widgets/radio/Manifest/RadioManifest.kt` | Entry point for Radio CWAS widget | Compose | `DefaultWidgetsRegistrar` | Low |
| `widgets/commute/Manifest/CommuteManifest.kt` | Entry point for Commute CWAS widget | Compose | `DefaultWidgetsRegistrar` | Low |
| `widgets/wifi/Manifest/WifiManifest.kt` | Entry point for Wi-Fi CWAS widget | Compose | `DefaultWidgetsRegistrar` | Low |
| `core/SmartTile.kt` | God Object for legacy devices | None | Legacy Repos, UI | Critical |
| `integrations/TuyaNativeClient.kt` | Tuya SDK implementation | Tuya SDK | `DeviceRepository` | High |
| `integrations/NestSdmApiClient.kt` | Google Nest API adapter | OkHttp | `DeviceRepository` | Medium |
| `ui/screens/MainActivity.kt` | Main entry point | Compose, Engines | System | High |
| `engines/AutomationEngine.kt` | Evaluates rules and triggers actions | `DeviceRepository` | Background Services | High |

*Note: This is a high-level catalog. The project contains many integration clients and legacy Compose files not listed here.*
