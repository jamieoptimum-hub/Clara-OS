# Guest WiFi Widget (`guest-wifi`) — v1.0

**Application:** Clara Smart Home  
**Architecture Standard:** CWAS v1.0  
**Status:** `PRODUCTION READY`

---

## 1. Purpose

Guest WiFi Widget v1.0 allows household visitors to connect to Wi-Fi immediately by scanning a dynamic QR code without verbal password sharing:

1. **Dashboard Experience (1–2m Zero-Interaction Scanning):** Renders inside an `SfHouseSunkenTileContainer` with a pixel-snapped, high-contrast ISO/IEC 18004 QR code (`Primary Element`), the network SSID visibly anchored directly beneath the QR symbol (`Secondary Element`), and live Wi-Fi link status plus active connected guest count (`Tertiary Element`). The Wi-Fi password is never exposed on the dashboard.
2. **Hub Experience (`60% × 70%` Popup Overlay):** Clicking the widget header opens `WifiHubPopup` over the live Clara dashboard across three sections:
    - **WiFi & QR:** Large 1–2m scannable QR code with SSID underneath, masked-by-default password with explicit reveal toggle, one-tap SSID/password clipboard copy buttons, quick password rotation, and live band/signal/speed telemetry.
    - **Guests:** Real-time local subnet client monitoring showing active guest count, device hostnames, IPv4/MAC addresses, reachability latency, and manual LAN rescanning.
    - **Security:** Guest Network Isolation toggle, timed Temporary Guest Access windows (`2h`, `4h`, `8h`, `24h`, `48h`), memorable or 16-character cryptographic password rotation, and a persisted rotation audit log.

---

## 2. Folder Structure

```text
widgets/wifi/
├── Manifest/
│   └── WifiManifest.kt
├── Models/
│   └── WifiModels.kt
├── Popup/
│   └── WifiHubPopup.kt
├── Repository/
│   └── WifiRepository.kt
├── Services/
│   └── WifiService.kt
├── Settings/
│   └── WifiSettingsSheet.kt
├── Widget/
│   └── WifiWidget.kt
└── README.md