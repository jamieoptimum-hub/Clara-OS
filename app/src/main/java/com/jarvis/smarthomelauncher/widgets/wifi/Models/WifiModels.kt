package com.jarvis.smarthomelauncher.widgets.wifi.Models

enum class WifiSecurityType {
    WPA, WEP, NONE
}

data class WifiConfig(
    val ssid: String,
    val password: String,
    val security: WifiSecurityType,
    val isHidden: Boolean = false
) {
    fun toQrCodeString(): String {
        val escape = { str: String -> str.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:") }
        val sec = when(security) {
            WifiSecurityType.WPA -> "WPA"
            WifiSecurityType.WEP -> "WEP"
            WifiSecurityType.NONE -> "nopass"
        }
        val p = if (security == WifiSecurityType.NONE) "" else "P:${escape(password)};"
        val h = if (isHidden) "H:true;" else ""
        return "WIFI:T:$sec;S:${escape(ssid)};$p$h;"
    }
}
