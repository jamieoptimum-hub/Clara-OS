# Repository Map

```text
Clara Smart Home
│
├── app/src/main/java/com/jarvis/smarthomelauncher
│   ├── core/                  # Legacy Monolithic Models (SmartTile, WidgetContext)
│   ├── engines/               # Background Automation, Voice, Presence Services
│   ├── integrations/          # 3rd-Party IoT APIs (Tuya, Matter, Nest, EZVIZ)
│   ├── services/              # Android Background Services
│   ├── ui/                    # Legacy Jetpack Compose UI Layer
│   │   ├── modals/            # Legacy popups and device controls
│   │   ├── popups/            # Legacy generic popup templates
│   │   ├── screens/           # Main application screens (MainActivity, SetupWizard)
│   │   ├── viewmodels/        # State holders for legacy screens
│   │   └── widgets/           # Legacy UI components (DashboardTiles, WidgetRegistry)
│   │
│   └── widgets/               # New CWAS (Modular) Widgets
│       ├── commute/           # Transport for London / Rail Integration
│       │   ├── Manifest/      # CommuteManifest (Registry Binder)
│       │   ├── Models/        # Domain Models
│       │   ├── Popup/         # Full-Screen Hub Dialog
│       │   ├── Repository/    # Data fetching logic
│       │   ├── Services/      # Retrofit Interfaces
│       │   ├── Settings/      # Configuration
│       │   └── Widget/        # Dashboard Card UI
│       │
│       ├── football/          # API-Football Integration
│       │   └── [CWAS Structure]
│       │
│       ├── radio/             # RadioBrowser Integration
│       │   └── [CWAS Structure]
│       │
│       └── wifi/              # Guest Wi-Fi QR Code Generator
│           └── [CWAS Structure]
│
├── gradle/                    # Build configuration and version catalogs
└── docs/
    └── ai-context/            # Artificial Intelligence Context Files (You are here)
```
