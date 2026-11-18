# AI Context Summary

## Architecture
Clara Smart Home is an Android Launcher (Kotlin + Compose) bridging local IoT protocols and web APIs.
The app is split:
- **Legacy Architecture**: Massive files (e.g., `DashboardTiles.kt`), single `SmartTile` God object, global `DeviceRepository`, and `WidgetContext`. Highly coupled.
- **CWAS (Clara Widget Architecture Standard)**: New widgets (Football, Radio, Commute, Wi-Fi) are fully isolated in `/widgets/<feature>/`. They have their own UI, Models, Services (Retrofit), and Repository. They are 100% decoupled from legacy UI code.

## Data Model Summary
- **SmartTile (Legacy)**: Represents *everything* from a lightbulb to a camera. Pushed to UI via `WidgetContext`.
- **CWAS Models**: Standard Kotlin Data Classes tied specifically to their API (e.g., `ApiFootballFixture`, `RadioStation`).

## Widget Framework Summary
- `WidgetRegistry` mounts all widgets.
- CWAS widgets register via `<Feature>Manifest.kt`.
- UI consists of a `Widget` (Dashboard Card) and a `Popup` (Full-Screen Dialog overlay triggered by tapping the card).

## Critical Dependencies
- **UI**: Jetpack Compose (`androidx.compose.*`)
- **Network (CWAS)**: Retrofit 3.0.0, OkHttp 4.12.0
- **IoT / Media**: Tuya Smart SDK 6.11.0, Google Play Services Home (Matter), ExoPlayer / LibVLC (Cameras).

## Known Technical Debt
- **God Classes**: `SmartTile` and `DeviceRepository`.
- **Missing Database**: App relies on in-memory caching and SharedPreferences; Room database is urgently needed for offline resilience and analytics.

## Recommended Refactor Priorities
1. **Database Integration**: Add Room to CWAS repositories for offline caching.
2. **Migrate Legacy Widgets**: Move Weather, Volume, Security, and Calendar to the new CWAS folder structure to decouple them from `DashboardTiles.kt`.
3. **DI Framework**: Implement Hilt/Dagger to inject Retrofit services instead of manually building them inside Repositories.
