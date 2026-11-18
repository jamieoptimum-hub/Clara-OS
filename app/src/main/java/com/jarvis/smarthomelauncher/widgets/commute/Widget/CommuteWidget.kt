package com.jarvis.smarthomelauncher.widgets.commute.Widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.smarthomelauncher.widgets.commute.Models.CommuteRoute
import com.jarvis.smarthomelauncher.widgets.commute.Repository.CommuteRepository

@Composable
fun CommuteWidget(
    modifier: Modifier = Modifier,
    onOpenCommuteHub: () -> Unit
) {
    val repository = remember { CommuteRepository() }
    var routes by remember { mutableStateOf<List<CommuteRoute>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        routes = repository.getAllRoutes()
        isLoading = false
    }

    Card(
        modifier = modifier
            .padding(8.dp)
            .clickable { onOpenCommuteHub() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Commute Status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Text(text = "Loading routes...")
            } else if (routes.isEmpty()) {
                Text(text = "No commute info")
            } else {
                for (route in routes.take(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = route.name, modifier = Modifier.weight(1f))
                        Text(text = route.status)
                    }
                }
            }
        }
    }
}
