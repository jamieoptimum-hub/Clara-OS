# Golden Rules for Clara Smart Home

## 1. Widget Contract Compliance (CWAS)
**ALL new dashboard widgets must follow the Clara Widget Architecture Standard (CWAS).**
- Must exist in their own domain folder (`/widgets/<feature>/`).
- Must include a `Manifest`, `Widget` (Card), `Popup` (Hub), `Repository`, `Services`, and `Models`.
- **Never** import or use legacy monolithic components like `DashboardTiles.kt`, `GoogleHomeTileCard`, or `WidgetContext` inside a new CWAS widget.

## 2. Single Source of Truth & Repository Pattern
- UI Components (Composables) **must never** make direct network calls.
- All network or database operations must occur within a `Repository`.
- Repositories must expose clean Domain Models (Data Classes), **never** raw Retrofit Response objects.

## 3. Database Ownership Rules
- (When implemented) The Database is the single source of truth. Repositories fetch from the network, write to the database, and the UI observes the database (via Flow).

## 4. State Management Rules
- Use `remember { mutableStateOf() }` for localized UI state (like `showPopup`).
- Use `StateFlow` from ViewModels/Repositories for global or data-driven state.
- **Do not** pass massive State objects down deeply nested Compose trees; hoist state or use scoped ViewModels.

## 5. Dependency Management Rules
- All new external dependencies must be justified and recorded in `build.gradle.kts` (or version catalogs).
- Prefer built-in Jetpack/AndroidX libraries (e.g., Media3, Compose) over massive 3rd party SDKs when possible.

## 6. Documentation Standards
- Write KDocs for all public Repository functions and Interfaces.
- Document any complex Smart Home protocol logic (Matter, Tuya).

## 7. AI-Assisted Development Standards
- **Plan First**: Before executing a multi-file refactor, write a plan.
- **Surgical Edits**: Use targeted `replace_file_content` over `run_shell_command("sed")` to prevent file corruption.
- **Verify**: Always run `gradlew assembleDebug` after modifying files.
- **Read AI Context**: AI assistants must read this `/docs/ai-context/` folder before making architectural decisions.
