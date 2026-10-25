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
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.shared.domain.Split
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SplitsScreen(nav: NavController, splitRepo: SplitRepository, exerciseRepo: ExerciseRepository) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var splitToDelete by remember { mutableStateOf<Split?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Workout Splits", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${splits.size} split${if (splits.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New split")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (splits.isEmpty()) {
            Text(
                "No splits yet. Tap + to create one, then add days to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        splits.forEach { split ->
            var menuOpen by remember { mutableStateOf(false) }
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { nav.navigate("split/${split.id}") },
                colors = if (split.isActive) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = split.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (split.isActive) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (split.isActive) "Main split" else "Open to edit its days",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (split.isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${split.name}")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Open") },
                                onClick = {
                                    menuOpen = false
                                    nav.navigate("split/${split.id}")
                                }
                            )
                            if (!split.isActive) {
                                DropdownMenuItem(
                                    text = { Text("Set as main") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { splitRepo.setActive(split.id) }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { splitRepo.duplicateSplit(split.id) }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    splitToDelete = split
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New split") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Split name (e.g. Push / Pull / Legs)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val splitName = name.trim()
                        scope.launch {
                            val isFirst = splits.isEmpty()
                            val split = Split(
                                id = UUID.randomUUID(),
                                userId = UUID.randomUUID(),
                                name = splitName,
                                isActive = isFirst
                            )
                            splitRepo.save(split)
                            if (isFirst) splitRepo.setActive(split.id)
                            creating = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = { OutlinedButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }

    if (splitToDelete != null) {
        AlertDialog(
            onDismissRequest = { splitToDelete = null },
            title = { Text("Delete split?") },
            text = { Text("Are you sure you want to delete \"${splitToDelete?.name}\"? Its days go with it. Past workouts keep their records.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = splitToDelete
                        splitToDelete = null
                        if (target != null) {
                            scope.launch { splitRepo.softDeleteSplit(target.id) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { splitToDelete = null }) { Text("Cancel") } }
        )
    }
}
