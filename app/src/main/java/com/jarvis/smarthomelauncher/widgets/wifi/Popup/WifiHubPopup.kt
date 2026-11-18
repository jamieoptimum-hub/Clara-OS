package com.jarvis.smarthomelauncher.widgets.wifi.Popup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.smarthomelauncher.core.GhTextPrimary
import com.jarvis.smarthomelauncher.widgets.wifi.Models.WifiConfig
import com.jarvis.smarthomelauncher.widgets.wifi.Repository.WifiRepository
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close

// QrCode rendering normally needs a library or manual canvas. We'll simulate a QR code placeholder here.

@Composable
fun WifiHubPopup(onDismiss: () -> Unit) {
    val repository = remember { WifiRepository() }
    var config by remember { mutableStateOf<WifiConfig?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        config = repository.getGuestWifiConfig()
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Guest Wi-Fi Hub", color = GhTextPrimary, style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = GhTextPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            
            if (isLoading) {
                CircularProgressIndicator()
            } else if (config == null) {
                Text("Guest Wi-Fi is not available.", color = GhTextPrimary)
            } else {
                Card(
                    modifier = Modifier.padding(16.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "SSID: ${config?.ssid}", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Password: ${config?.password}", style = MaterialTheme.typography.bodyLarge)
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Placeholder for QR code
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "[ QR Code Placeholder ]\n${config?.toQrCodeString()}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
}
