# Commute Hub Widget (`commute-hub`) — v2.0

**Application:** Clara Smart Home  
**Architecture Standard:** CWAS v1.0  
**Status:** `PRODUCTION READY`

---

## 1. Purpose

Commute Hub v2.0 provides a two-tier multi-modal transport intelligence module for Clara Smart Home across all 8 supported transport modes (`Rail`, `Underground`, `Bus`, `Tram`, `Ferry`, `Driving`, `Walking`, `Cycling`):

1. **Dashboard Experience (2–4m Passive Awareness):** Renders inside an `SfHouseSunkenTileContainer` designed for sub-3-second room readability with zero interaction required:
    - **Normal Mode:** Displays the primary route's origin and destination, next departure time, live countdown, highlighted platform badge, journey duration, Leave-By walk reminder, passenger occupancy pill, compact upcoming departures strip, and top `RulesEngine` insight.
    - **Disruption Mode (Automatic Override):** Automatically overrides Normal Mode whenever active delays, cancellations, platform changes, service suspensions, or engineering works affect a saved route—keeping the next departure visibly anchored while surfacing delay deltas, flashing platform changes, engineering alerts, and actionable alternative-route guidance.
2. **Hub Experience (`90% × 92%` Popup Overlay):** Clicking the widget header opens `CommuteHubPopup` over the live Clara dashboard across 6 tabs:
    - **Overview:** `RulesEngine` insight cards, side-by-side Morning Commute & Evening Commute route cards, active route disruptions, and primary upcoming departures.
    - **Routes:** Full saved-route management with primary-route starring, deletion, and live Leave-By status.
    - **Live Departures:** Expandable calling-point timelines, real-time platform-change badges, occupancy indicators, and one-tap station inspection.
    - **Stations:** Live UK Rail & TfL station lookup showing side-by-side live departures & live arrivals plus parking, taxi rank availability, concourse facilities, and step-free accessibility metadata.
    - **Disruptions:** Categorised disruption centre (`Delays`, `Cancellations`, `Engineering Works`, `Service Suspensions`, `Heavy Traffic`) with direct re-routing into the Journey Planner.
    - **Journey Planner:** Multi-modal route comparison badged by `Fastest Route`, `Cheapest Route`, `Fewest Changes`, and `Earliest Arrival`.
3. **Ecosystem Cooperation:** Exports real-time Leave-By calculations to Clara's household ecosystem via `CommuteManifest.leaveByCalculationsFlow(context)` (CWAS Rule 19).

---

## 2. Folder Structure

```text
widgets/commute/
├── Manifest/
│   └── CommuteManifest.kt
├── Models/
│   └── CommuteModels.kt
├── Popup/
│   └── CommuteHubPopup.kt
├── Repository/
│   └── CommuteRepository.kt
├── Services/
│   └── CommuteServiceProvider.kt
├── Settings/
│   └── CommuteSettingsSheet.kt
├── Widget/
│   └── CommuteWidget.kt
└── README.md