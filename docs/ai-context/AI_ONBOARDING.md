# AI Onboarding Guide

## Project Purpose
Clara Smart Home is a Jetpack Compose-based Android Launcher designed to be the ultimate Smart Home dashboard. It bridges local protocols (Matter, Tuya) and web services (Weather, TfL, API-Football) into a single, cohesive, glanceable interface.

## Architecture Summary
The project is currently transitioning from a **Legacy Monolithic Architecture** (where all smart devices share a massive `SmartTile` and `DeviceRepository`) to the **Clara Widget Architecture Standard (CWAS)** (where new data-driven widgets are strictly modularized and decoupled).

## Current State
- The App successfully compiles.
- 4 New CWAS Widgets (Football, Radio, Commute, Wi-Fi) have been successfully built, registered, and completely decoupled from legacy UI code.

## Critical Files
- `app/build.gradle.kts` (Dependencies)
- `ui/screens/MainActivity.kt` (Main Launcher Entry)
- `ui/widgets/DefaultWidgetsRegistrar.kt` (Where all widgets are mounted)
- `/widgets/<feature>/Manifest/<Feature>Manifest.kt` (CWAS Widget Entry Points)

## Development Workflow
1. **Analyze**: Read user intent and check the `docs/ai-context/` for architectural rules.
2. **Modify**: Use `write_file` or `replace_file_content` (Avoid Shell `sed` to prevent file corruption).
3. **Compile**: Use the `gradle_build` tool (`app:assembleDebug`) to verify syntax.

## Naming Conventions
- Compose files: `PascalCase.kt` (e.g., `FootballWidget.kt`).
- Compose functions: `PascalCase` (e.g., `fun FootballHubPopup()`).
- Data Classes: `PascalCase` inside `Models` folders.
- Repository: `<Feature>Repository`.

## Widget Framework
Always use the CWAS pattern for new widgets. See `WIDGET_ARCHITECTURE.md`.
**Rule:** A widget consists of a glanceable Card (`Widget.kt`) and a full-screen detailed Dialog (`Popup.kt`).

## Data Storage Strategy
- Legacy: In-memory `DeviceRepository` and `EncryptedSharedPreferences`.
- CWAS: Retrofit Network fetching (Currently lacks a local Room database cache).

## Recommended Reading Order
1. `AI_INDEX.md`
2. `ARCHITECTURE.md`
3. `WIDGET_ARCHITECTURE.md`
4. `GOLDEN_RULES.md`
