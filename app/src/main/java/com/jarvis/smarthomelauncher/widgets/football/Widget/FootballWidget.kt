package com.jarvis.smarthomelauncher.widgets.football.Widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.smarthomelauncher.widgets.football.Models.ApiFootballFixture
import com.jarvis.smarthomelauncher.widgets.football.Repository.FootballRepository
import kotlinx.coroutines.launch

@Composable
fun FootballWidget(
    modifier: Modifier = Modifier,
    apiKey: String,
    onOpenFootballHub: () -> Unit
) {
    val repository = remember(apiKey) { FootballRepository(apiKey) }
    var fixtures by remember { mutableStateOf<List<ApiFootballFixture>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(repository) {
        fixtures = repository.getFixturesForToday(leagueId = 39, season = 2023)
        isLoading = false
    }

    Card(
        modifier = modifier
            .padding(8.dp)
            .clickable { onOpenFootballHub() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Premier League Today", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Text(text = "Loading fixtures...")
            } else if (fixtures.isEmpty()) {
                Text(text = "No fixtures today")
            } else {
                for (fixture in fixtures.take(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = fixture.teams.home.name, modifier = Modifier.weight(1f))
                        Text(text = "vs", modifier = Modifier.padding(horizontal = 8.dp))
                        Text(text = fixture.teams.away.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
