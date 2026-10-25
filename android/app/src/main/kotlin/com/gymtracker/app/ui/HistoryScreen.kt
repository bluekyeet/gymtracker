package com.gymtracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(nav: NavController, workoutRepo: WorkoutRepository) {
    val sessions by workoutRepo.observeSessions().collectAsState(initial = emptyList())

    val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy").withZone(ZoneId.systemDefault())
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "${sessions.size} workout${if (sessions.size == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Text(
                "No workouts yet. Start one from the Today tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    val inProgress = s.endedAt == null
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate("workout/${s.id}") }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.dayName ?: "Workout",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (inProgress) {
                                    Text(
                                        "In progress",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${dateFormatter.format(s.startedAt)} · ${timeFormatter.format(s.startedAt)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!inProgress && s.endedAt != null) {
                                val minutes = java.time.Duration.between(s.startedAt, s.endedAt).toMinutes()
                                Text(
                                    "Completed · ${minutes} min",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
