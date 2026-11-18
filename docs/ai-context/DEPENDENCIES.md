# Dependencies

*Note: Sourced from `build.gradle.kts`. Versions are inferred from catalog or direct definition.*

| Dependency Name | Version | Purpose | Risk Assessment | Replacement Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| **Jetpack Compose BOM** | *(Platform)* | Core UI Framework | Low | N/A |
| **androidx.documentfile** | 1.0.1 | Storage Access Framework (Photo trees) | Low | N/A |
| **androidx.security:security-crypto** | 1.1.0-alpha06 | Encrypted SharedPreferences | Medium (Alpha) | Update to Stable |
| **com.thingclips.smart:thingsmart** | 6.11.0 | Tuya IoT Cloud/Native SDK | High (Vendor Lock-in, NDK conflicts) | Abstract behind interfaces |
| **com.alibaba:fastjson** | 1.1.71.android | Required by Tuya SDK | Medium | N/A (Tuya dependency) |
| **com.facebook.soloader** | 0.10.5 | Native library loading (Fixes Tuya bug) | Low | N/A |
| **org.videolan.android:libvlc-all** | 3.6.3 | RTSP / Hardware Video Decoding | Medium (Large binary) | ExoPlayer preferred where possible |
| **androidx.media3:media3-exoplayer** | 1.3.1 | HLS/DASH/RTSP Video Streaming | Low | N/A |
| **com.squareup.okhttp3:okhttp** | 4.12.0 | HTTP/2, WebSockets, SSE | Low | N/A |
| **com.squareup.retrofit2:retrofit** | 3.0.0 | REST API Abstraction (CWAS Widgets) | Low | N/A |
| **com.google.android.gms:play-services-home** | 16.0.0 | Google Matter Commissioning | Medium | N/A |
| **org.jetbrains.kotlinx:kotlinx-coroutines-android** | 1.8.1 | Concurrency | Low | N/A |
