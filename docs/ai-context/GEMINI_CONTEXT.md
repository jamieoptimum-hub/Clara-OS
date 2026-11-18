# GEMINI CONTEXT PACK
**Purpose:** Highly compressed, AI-optimized context file summarizing the Clara Smart Home Android project. Load this file first to understand the system without parsing the entire repository.

## 1. Project Overview
* **Application Purpose:** An Android application serving as a unified smart home launcher and dashboard.
* **Primary Goals:** Bridge local IoT protocols (Tuya, Matter, Nest) and web API services (TfL, Radio, Football) into a single, cohesive, glanceable interface.
* **Target Users:** Smart home owners requiring a centralized control panel for device management, daily commute tracking, media, and routines.
* **Core Functionality:** Device control (lights, climate, cameras, media), voice assistant, automation engine, dynamic dashboard widgets, local network device discovery, and media casting.

## 2. Architecture Overview
* **High-Level Architecture:** The app uses a **Hybrid Architecture**:
  * **Legacy Monolith:** Tightly coupled UI (`DashboardTiles.kt`), single God object (`SmartTile`), and global state (`WidgetContext`).
  * **CWAS (Clara Widget Architecture Standard):** Modern, modular, self-contained architecture for new features. Each feature has isolated UI, Domain, and Network layers.
* **Major Systems:** 
  * **Dashboard Launcher:** Main UI rendering engine.
  * **Engines:** Background coroutine systems (AutomationEngine, DeviceDispatchEngine, PresenceWakeManager).
  * **Integrations:** API adapters for Matter, Tuya SDK, OkHttp/Retrofit REST, ExoPlayer/LibVLC for RTSP camera streams.
* **Data Flow:** 
  * **Legacy:** 3rd Party APIs -> Integration Clients -> `DeviceRepository` (In-Memory Cache) -> `WidgetContext` -> UI (Forces heavy recomposition).
  * **CWAS:** External REST APIs -> Retrofit `Services` -> Scoped `Repository` -> Compose `Widget`/`Popup` (via localized `MutableState`). Database caching (Room) is currently a known tech debt priority.
* **Screen Structure:** Built entirely in Jetpack Compose.
  * **Screens:** `MainActivity` (Dashboard), `SetupWizardScreen`, `AutomationsSection`.
  * **Overlays:** Granular control handled via `Dialog` popups and full-screen Modals (e.g., `DeviceControlPopupHost.kt`).
* **Navigation Model:** Custom Compose-based overlays and full-screen swaps (no strict Navigation Component graph used for legacy widgets, state-driven rendering).

## 3. Widget Framework
* **How Widgets Work:** The dashboard dynamically loads interactive components. A standard widget consists of a glanceable Card (`Widget.kt`) and an interactive full-screen Dialog overlay (`Popup.kt`).
* **Widget Lifecycle:** Instantiated dynamically via `DefaultWidgetsRegistrar`. CWAS widgets execute their own data fetching on composition via `LaunchedEffect`. They manage local loading and empty states independently.
* **Registration Process:**
  1. Define a type in `DashboardWidgetType` Enum.
  2. CWAS Widgets create a `Manifest/` file (e.g., `FootballManifest.kt`).
  3. The `Manifest` calls `WidgetRegistry.register(DashboardWidgetType.X) { renderer }`.
  4. The Manifest's `register()` method is invoked inside `DefaultWidgetsRegistrar.kt`.
* **Configuration Process:** 
  * Legacy widgets rely on the massive `WidgetContext` passed during registration.
  * CWAS widgets receive specific parameters and instantiate their own Repositories internally (decoupled from the legacy monolith).
* **Shared Widget Contracts (CWAS):**
  * **Directory Rule:** Must reside in `/widgets/<feature>/`.
  * **Structural Rule:** Must contain `Manifest`, `Widget`, `Popup`, `Repository`, `Services`, `Models`.
  * **Decoupling Rule:** MUST NOT import `SmartTile`, `DashboardTiles.kt`, `WidgetContext`, or legacy custom Compose wrappers.
  * **Action Rule:** Tapping the `Widget` must toggle state to display the `Popup` Hub overlay.