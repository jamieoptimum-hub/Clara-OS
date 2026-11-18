# Widget Rebuild Implementation Plan

The objective is to fully transition to the Clara Smart Home Widget Architecture Standard (CWAS) for the 4 widgets defined in `widgets.txt`: **Radio**, **Football**, **Guest WiFi**, and **Commute Hub**.

In accordance with CWAS, we will remove existing placeholder implementations and build production-ready components inside dedicated, modular folder structures.

## Proposed Changes

We will execute this plan sequentially, starting with a complete cleanup.

### Phase 1: Total Cleanup (Eliminate Technical Debt)
We will eradicate all previous implementations of the 4 widgets.
#### [MODIFY] [DefaultWidgetsRegistrar.kt](file:///C:/projects/ClaraSmartHome/app/src/main/java/com/jarvis/smarthomelauncher/ui/widgets/DefaultWidgetsRegistrar.kt)
- Remove `DashboardWidgetType.RADIO`, `FOOTBALL`, and `COMMUTE_WIFI` registrations temporarily until their new CWAS components are ready.
#### [MODIFY] [MediaWidgets.kt](file:///C:/projects/ClaraSmartHome/app/src/main/java/com/jarvis/smarthomelauncher/ui/widgets/MediaWidgets.kt)
- Delete `SfHouseLiveRadioCard` and any associated dummy state logic.
#### [DELETE] [FootballWidgets.kt](file:///C:/projects/ClaraSmartHome/app/src/main/java/com/jarvis/smarthomelauncher/ui/widgets/FootballWidgets.kt)
- Fully delete the legacy file and all its mock data.
#### [MODIFY] [CalendarCommuteWidgets.kt](file:///C:/projects/ClaraSmartHome/app/src/main/java/com/jarvis/smarthomelauncher/ui/widgets/CalendarCommuteWidgets.kt)
- Delete `SfHouseCommuteAndWifiCard` and associated mocks.

### Phase 2: Build the Football Hub (CWAS Standard)
We will create the new folder structure: `com.jarvis.smarthomelauncher.widgets.football`.
- **Repository/Services**: Establish a real data connection (e.g. using a live API key for football fixtures if you have one, or preparing the API client layer for one).
- **Models**: Implement data classes reflecting the JSON specifications (Matches, Teams, League Tables).
- **Widget**: Create the UI for `FootballWidget` supporting Normal, Match Day, and Live Modes.
- **Popup**: Build the `FootballHubPopup` with Tabs (Overview, Fixtures, Live, Tables, etc).
- **Manifest**: Define `FootballManifest` to self-register to Clara's `WidgetRegistry`.

### Phase 3: Build the Radio Hub (CWAS Standard)
We will create the new folder structure: `com.jarvis.smarthomelauncher.widgets.radio`.
- **Repository/Services**: Connect to a live streaming source (e.g., RadioBrowser API / ExoPlayer integration).
- **Widget**: Dashboard widget showing current playback, artwork, and one-touch favourite chips.
- **Popup**: The full-screen Radio Hub for Discover, History, and Categories.
- **Settings**: Database-backed favourite stations list.

### Phase 4: Build Commute Hub & Guest WiFi
Follow the exact same CWAS architecture for:
- `com.jarvis.smarthomelauncher.widgets.commute` (Live transit data integration)
- `com.jarvis.smarthomelauncher.widgets.wifi` (Live UniFi/Router integration & dynamic QR generation)

## Open Questions

> [!IMPORTANT]
> **API Integrations:** Rule 2 of CWAS prohibits dummy data. To build these correctly, I need to know which actual backend APIs or SDKs we are connecting to:
> - **Football**: Are we using *API-Football*, *Sportmonks*, or another provider?
> - **Radio**: Are we using *RadioBrowser API* or *TuneIn*?
> - **Commute**: *National Rail Enquiries* (UK), *TfL*, or *Google Maps*?
> - **WiFi**: Do you have a specific router integration (e.g., *UniFi Controller API*, *Omada*)?

## Verification Plan
### Manual Verification
- We will deploy one widget at a time.
- Verify the widget automatically self-registers into the dashboard.
- Verify real data populates the dashboard (e.g. live football scores).
- Ensure the layout remains readable from 2-4 metres away.
- Verify the Popup launches correctly when tapping the widget header.