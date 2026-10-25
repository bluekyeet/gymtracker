package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.Split
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SplitsScreen(nav: NavController, splitRepo: SplitRepository, exerciseRepo: ExerciseRepository) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newSplitName by remember { mutableStateOf("") }
    var splitToDelete by remember { mutableStateOf<Split?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Workout Splits", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newSplitName,
                onValueChange = { newSplitName = it },
                label = { Text("New split name") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (newSplitName.isNotBlank()) {
                    scope.launch {
                        val isFirst = splits.isEmpty()
                        val split = Split(id = UUID.randomUUID(), userId = UUID.randomUUID(), name = newSplitName, isActive = isFirst)
                        splitRepo.save(split)
                        if (isFirst) {
                            splitRepo.setActive(split.id)
                        }
                        newSplitName = ""
                    }
                }
            }) { Text("Add") }
        }
        Spacer(Modifier.height(16.dp))
        splits.forEach { split ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = if (split.isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = split.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (split.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        if (split.isActive) {
                            Text(
                                "Main Split",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (!split.isActive) {
                        OutlinedButton(onClick = {
                            scope.launch { splitRepo.setActive(split.id) }
                        }) { Text("Set Main") }
                        Spacer(Modifier.width(4.dp))
                    }
                    Button(onClick = { nav.navigate("split/${split.id}") }) { Text("Edit") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { splitToDelete = split },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                }
            }
        }

        if (splitToDelete != null) {
            AlertDialog(
                onDismissRequest = { splitToDelete = null },
                title = { Text("Delete split?") },
                text = { Text("Are you sure you want to delete \"${splitToDelete?.name}\"? This action cannot be undone.") },
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
                dismissButton = {
                    OutlinedButton(onClick = { splitToDelete = null }) { Text("Cancel") }
                }
            )
        }
    }
}
