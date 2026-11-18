package com.jarvis.smarthomelauncher.widgets.wifi.Widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.smarthomelauncher.widgets.wifi.Models.WifiConfig
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository

@Composable
fun WifiWidget(
    modifier: Modifier = Modifier,
    onOpenWifiHub: () -> Unit
) {
    val repository = remember { WifiRepository() }
    var config by remember { mutableStateOf<WifiConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        config = repository.getGuestWifiConfig()
        isLoading = false
    }

    Card(
        modifier = modifier
            .padding(8.dp)
            .clickable { onOpenWifiHub() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Guest Wi-Fi", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Text(text = "Loading Wi-Fi details...")
            } else if (config == null) {
                Text(text = "No guest Wi-Fi configured")
            } else {
                Text(text = "SSID: ${config?.ssid}", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Tap for QR Code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
