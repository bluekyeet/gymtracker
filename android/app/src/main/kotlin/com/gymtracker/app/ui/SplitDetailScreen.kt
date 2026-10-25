package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.shared.domain.SplitDay
import kotlinx.coroutines.launch
import java.util.UUID

val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitDetailScreen(nav: NavController, splitRepo: SplitRepository, splitId: UUID) {
    val days by splitRepo.observeDays(splitId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newDayName by remember { mutableStateOf("") }
    var selectedWeekdays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var dayToDelete by remember { mutableStateOf<SplitDay?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Split Days", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newDayName,
            onValueChange = { newDayName = it },
            label = { Text("Day name (e.g. Leg Day)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("Assigned Weekdays", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WEEKDAYS.forEachIndexed { idx, label ->
                FilterChip(
                    selected = selectedWeekdays.contains(idx + 1),
                    onClick = {
                        selectedWeekdays = if (selectedWeekdays.contains(idx + 1)) selectedWeekdays - (idx + 1) else selectedWeekdays + (idx + 1)
                    },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (newDayName.isNotBlank()) {
                    scope.launch {
                        splitRepo.saveDay(
                            SplitDay(
                                id = UUID.randomUUID(),
                                splitId = splitId,
                                name = newDayName,
                                weekdays = selectedWeekdays.toList().sorted(),
                                sortKey = days.size.toDouble()
                            )
                        )
                        newDayName = ""
                        selectedWeekdays = emptySet()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Day") }
        Spacer(Modifier.height(16.dp))

        days.forEach { day ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        day.weekdays.map { WEEKDAYS[it - 1] }.joinToString(", ").ifEmpty { "No assigned days" },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.navigate("day/${day.id}") }, modifier = Modifier.weight(1f)) { Text("Edit") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                splitRepo.saveDay(day.copy(id = UUID.randomUUID(), sortKey = days.size.toDouble()))
                            }
                        }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
                        Button(
                            onClick = { dayToDelete = day },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    }
                }
            }
        }

        if (dayToDelete != null) {
            AlertDialog(
                onDismissRequest = { dayToDelete = null },
                title = { Text("Delete split day?") },
                text = { Text("Are you sure you want to delete \"${dayToDelete?.name}\"?") },
                confirmButton = {
                    Button(
                        onClick = {
                            val target = dayToDelete
                            dayToDelete = null
                            if (target != null) {
                                scope.launch { splitRepo.softDeleteDay(target.id) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = { OutlinedButton(onClick = { dayToDelete = null }) { Text("Cancel") } }
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
