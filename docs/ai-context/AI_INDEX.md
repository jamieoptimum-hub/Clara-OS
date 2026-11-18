# Master Repository Index (Clara Smart Home)

## Project Overview
Clara Smart Home is an Android application designed as a smart home launcher. It features a dashboard with interchangeable widgets, integrates with various smart home protocols and platforms (Tuya, Matter, Nest, EZVIZ, etc.), and includes engines for discovery, voice assistant, and rules automation.

## Architecture Summary
The application currently features a mix of a monolithic, legacy architecture for core smart home controls and a newer modular architecture (Clara Widget Architecture Standard - CWAS) for specialized dashboard widgets (e.g., Football, Radio, Commute, Guest Wi-Fi).
The app is heavily UI-focused with Jetpack Compose, backed by Coroutines for background threading, and relies on an internal `WidgetRegistry` to plug components into the dashboard.

## Directory Tree
- `/app/src/main/java/com/jarvis/smarthomelauncher`
  - `/core` (Models, Application class, Device classes)
  - `/engines` (Automation, Discovery, Device Dispatch, Voice Assistant)
  - `/integrations` (Clients for Matter, Tuya, Nest, Spotify, LgThinQ, TP-Link, eWeLink, EZVIZ)
  - `/services` (Background services)
  - `/ui` (Compose screens, ViewModels, Popups, Modals, legacy widgets)
  - `/widgets` (New CWAS Widgets: commute, football, radio, wifi)

## Major Components
- **Dashboard Launcher**: The main UI surface rendering widgets.
- **SmartHome API Clients**: Adapters to control IoT devices.
- **Engines**: Background processes driving automation, presence, and discovery.
- **Widget Framework**: The registry that allows dynamically rendering widgets.

## Data Layer
- **Legacy**: `SmartTile` acts as a monolithic God Object. `DeviceRepository` caches state.
- **New CWAS**: Each widget has its own scoped `Repository`, `Models`, and `Services` layers via Retrofit.

## UI Layer
Built entirely with Jetpack Compose.
- **Screens**: Primary full-screen views (MainActivity, SetupWizard, Automations).
- **Modals & Popups**: Detailed control views.
- **Widgets**: Reusable, glanceable dashboard cards.

## Widget Layer
- **Old Widgets**: Tightly coupled with `DashboardTiles.kt` and `WidgetContext`.
- **New Widgets**: Fully modularized into separate domains (`Manifest`, `Widget`, `Popup`, `Repository`, `Services`, `Models`).

## Background Processing
- Managed by various "Engines" (`AutomationEngine`, `PresenceWakeManager`) using Kotlin Coroutines and background services.

## Third-Party Integrations
- Tuya Cloud & Native
- Google Cast & Play Services Home
- OkHttp/Retrofit for REST APIs (TfL, RadioBrowser, API-Football)
- LibVLC / ExoPlayer for camera streams

## Known Technical Debt
- Heavy reliance on the monolithic `SmartTile` and `DeviceRepository` for legacy devices.
- Very large Compose files (e.g., `DashboardTiles.kt`, `ManageAndPairModals.kt`).
- Hardcoded integrations that should ideally be abstracted behind standard interfaces.

## File Index
See `FILE_INDEX.md` for a breakdown of meaningful files.
