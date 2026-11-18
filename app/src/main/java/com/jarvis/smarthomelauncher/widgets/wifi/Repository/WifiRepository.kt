package com.jarvis.smarthomelauncher.widgets.wifi.Repository

import com.jarvis.smarthomelauncher.widgets.wifi.Models.WifiConfig
import com.jarvis.smarthomelauncher.widgets.wifi.Models.WifiSecurityType
import com.jarvis.smarthomelauncher.widgets.wifi.Services.WifiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiRepository : WifiService {
    // Extensible for Asus router API integration later
    override suspend fun getGuestWifiConfig(): WifiConfig = withContext(Dispatchers.IO) {
        // Return realistic fallback data until Asus API is fully integrated
        WifiConfig(
            ssid = "ClaraGuest",
            password = "secureGuestPassword123",
            security = WifiSecurityType.WPA
        )
    }
}
