# API Integrations

Clara Smart Home acts as an aggregation layer for multiple third-party services.

## Smart Home & Core Integrations
These are located in `/app/src/main/java/com/jarvis/smarthomelauncher/integrations/`.
- **Tuya Cloud / Native**: Uses the official Tuya Smart SDK (`com.thingclips.smart:thingsmart`) for native device control and MQTT streams.
- **Matter / Thread**: Integrates with Google Play Services Home (`play-services-home`, `play-services-threadnetwork`) for local Matter commissioning.
- **Google Cast**: LAN client for finding and casting to Google Nest speakers.
- **Nest SDM API**: Uses OkHttp for Smart Device Management to interface with Google Nest Thermostats.
- **EZVIZ / Imou**: Interacts with cloud APIs and uses LibVLC / ExoPlayer to render H.265/H.264 RTSP and HLS streams.
- **TP-Link Tapo/Kasa / LgThinQ / eWeLink**: Assorted REST and WebSocket clients for localized smart home control.

## Widget APIs (CWAS)
These are modern integrations utilizing Retrofit2 and Gson, located within their respective `/widgets/<feature>/Services/` folders.

- **Football (`api-football`)**: 
  - Provider: API-Sports (v3.football.api-sports.io)
  - Usage: Fetches live fixtures, scores, and team data.
- **Radio (`RadioBrowser`)**:
  - Provider: Radio Browser API (e.g., de1.api.radio-browser.info)
  - Usage: Searches and fetches top global radio stations.
- **Commute (`TfL`)**:
  - Provider: Transport for London API (api.tfl.gov.uk)
  - Usage: Real-time status of London Underground lines (Victoria, Jubilee, Northern). Designed to be pluggable with National Rail or Google Maps.
- **Guest Wi-Fi**:
  - Currently a placeholder payload generator for QR codes (`WIFI:T:WPA;S:SSID;P:PASS;;`), designed for future REST integration with Asus Router APIs (e.g., Be88u).

## Authentication
- Legacy IoT integrations heavily rely on OAuth 2.0 (e.g., Nest, EZVIZ) or API Keys/Secrets (Tuya) stored in local/gradle properties.
- Widget integrations rely on simple Header-based API Keys (e.g., `x-apisports-key`).

## Future Architecture
- **Caching Layer**: Currently missing for CWAS APIs.
- **Polling Architecture**: Mostly handled by `LaunchedEffect` or Coroutines. WebSockets or MQTT are used where real-time push (Tuya, Shelly) is available.
