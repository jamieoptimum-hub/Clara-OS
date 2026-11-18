package com.jarvis.smarthomelauncher.widgets.wifi.Services

import com.jarvis.smarthomelauncher.widgets.wifi.Models.WifiConfig

interface WifiService {
    suspend fun getGuestWifiConfig(): WifiConfig
}
