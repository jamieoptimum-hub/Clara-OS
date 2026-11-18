# Widget Architecture

Clara Smart Home relies heavily on dynamically loaded dashboard widgets.

## Widget Framework Overview

There are two primary widget architectures in use:
1. **Legacy Architecture**: Highly coupled to a monolithic state object.
2. **CWAS (Clara Widget Architecture Standard)**: Highly modular, decoupled domain structure.

### Widget Registration
All widgets must register with the `WidgetRegistry` via `WidgetRegistry.register(DashboardWidgetType, Renderer)`.
- Legacy widgets are manually registered inside `DefaultWidgetsRegistrar.kt`.
- CWAS widgets provide a `<Feature>Manifest.kt` (e.g., `FootballManifest.kt`), which exposes a `register()` method. `DefaultWidgetsRegistrar` calls these manifests.

### CWAS Structure (The New Standard)
Every new widget MUST be fully contained within its own folder (`/widgets/<feature>/`) and follow this strict structure:
- `Manifest/`: Contains the `register()` method bridging the widget to the global `WidgetRegistry`.
- `Widget/`: The glanceable Jetpack Compose card shown on the dashboard.
- `Popup/`: The detailed, full-screen Jetpack Compose Dialog (Hub).
- `Repository/`: Handles data orchestration and caching.
- `Services/`: Retrofit network definitions.
- `Models/`: Data classes for network and domain layers.
- `Settings/`: (Optional) User configuration for the widget.

### Widget Lifecycle
- Widgets are recomposed based on State changes.
- CWAS widgets use `LaunchedEffect(Unit)` or `LaunchedEffect(repository)` to initiate data fetching upon entering the composition.
- The `Widget` (card) handles its own loading and empty states, independent of the rest of the dashboard.

### Widget Communication Patterns
- Tapping a CWAS widget triggers an `onOpen<Feature>Hub` callback (usually a simple state toggle `showPopup = true` in the Manifest), which opens the associated `Popup` Dialog.
- Data flows uni-directionally from Repository -> State -> UI.

### Performance Considerations
- CWAS widgets are significantly more performant than legacy widgets because they only re-render when their specific scoped data changes, rather than recomposing whenever any `SmartTile` in the global `WidgetContext` changes.

### Legacy Technical Debt
- Legacy widgets (e.g., Weather, Volume, Security) are tightly coupled inside massive files like `DashboardTiles.kt` and `DashboardWeatherAndClockHero.kt`. They all share the same monolithic `WidgetContext`, leading to potential over-recomposition and memory risks.
