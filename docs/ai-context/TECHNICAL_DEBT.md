# Technical Debt

## Critical
- **God Classes & Tight Coupling**: `SmartTile` and `DeviceRepository` are massive monolithic structures that control the entire legacy application. Every legacy widget (Lights, Thermostats, Security) depends heavily on this single data structure.
- **Massive UI Files**: Files like `DashboardTiles.kt` (1.7k+ lines), `SetupWizardComponents.kt`, and `ManageAndPairModals.kt` contain far too much logic and too many nested Composable functions, leading to hard-to-maintain code and difficult debugging.
- **State Management Risks**: Passing `WidgetContext` (which contains massive lists of all tiles) to every single legacy widget forces excessive recompositions.

## High
- **Missing Database**: Caching relies purely on in-memory collections within Singletons (`DeviceRepository`). There is no Room database for persistent caching, analytics, or offline resilience.
- **Widget Duplication (Legacy)**: Legacy popups (e.g., `DeviceControlPopupHost.kt`, `ModularPopups.kt`) use massive `when` statements to determine which UI to render based on device classes, violating the Open/Closed Principle.

## Medium
- **Tuya SDK Coupling**: The `TuyaNativeClient` and related classes use the Tuya SDK directly throughout the codebase, making it difficult to swap out vendors or test without a real Tuya environment.
- **Missing Abstractions**: Many API integrations manually handle Retrofit building instead of using a Dependency Injection framework like Hilt/Dagger to inject pre-configured REST clients.

## Low
- **Unused Imports/Variables**: Some recently refactored CWAS widgets (e.g., Radio, Wifi) contain minor unused imports or variables that need cleanup.
