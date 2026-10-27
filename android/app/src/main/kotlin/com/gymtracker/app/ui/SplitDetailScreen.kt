package com.gymtracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
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

    var splitName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var dayToDelete by remember { mutableStateOf<SplitDay?>(null) }

    LaunchedEffect(splitId) { splitName = splitRepo.getSplit(splitId)?.name ?: "Split" }

    // Day creation lives behind the + in the header, not inline on the screen.
    var newDayName by remember { mutableStateOf("") }
    var selectedWeekdays by remember { mutableStateOf<Set<Int>>(emptySet()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubScreenHeader(
            title = splitName,
            onBack = { nav.popBackStack() },
            actions = {
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New day")
                }
            }
        )

        Column(Modifier.padding(top = 4.dp)) {
            Text("DAYS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Each day is its own workout. Tap one to set its weekdays, order and targets.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            if (days.isEmpty()) {
                Text(
                    "No days yet. Tap + at the top to add the first one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            days.forEach { day ->
                var menuOpen by remember { mutableStateOf(false) }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { nav.navigate("day/${day.id}") }
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                day.weekdays.map { WEEKDAYS[it - 1] }.joinToString(", ").ifEmpty { "No assigned days" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${day.name}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        menuOpen = false
                                        nav.navigate("day/${day.id}")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            splitRepo.saveDay(
                                                day.copy(
                                                    id = UUID.randomUUID(),
                                                    sortKey = days.size.toDouble()
                                                )
                                            )
                                        }
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        dayToDelete = day
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New day") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newDayName,
                        onValueChange = { newDayName = it },
                        label = { Text("Day name (e.g. Leg Day)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Assigned weekdays", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WEEKDAYS.forEachIndexed { idx, label ->
                            FilterChip(
                                selected = selectedWeekdays.contains(idx + 1),
                                onClick = {
                                    selectedWeekdays = if (selectedWeekdays.contains(idx + 1)) {
                                        selectedWeekdays - (idx + 1)
                                    } else {
                                        selectedWeekdays + (idx + 1)
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newDayName.isNotBlank(),
                    onClick = {
                        val name = newDayName.trim()
                        val weekdays = selectedWeekdays.toList().sorted()
                        scope.launch {
                            splitRepo.saveDay(
                                SplitDay(
                                    id = UUID.randomUUID(),
                                    splitId = splitId,
                                    name = name,
                                    weekdays = weekdays,
                                    sortKey = days.size.toDouble()
                                )
                            )
                        }
                        newDayName = ""
                        selectedWeekdays = emptySet()
                        creating = false
                    }
                ) { Text("Add day") }
            },
            dismissButton = { OutlinedButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }

    if (dayToDelete != null) {
        AlertDialog(
            onDismissRequest = { dayToDelete = null },
            title = { Text("Delete split day?") },
            text = { Text("Are you sure you want to delete \"${dayToDelete?.name}\"? Past workouts keep their records.") },
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
}
