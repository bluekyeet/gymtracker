package com.gymtracker.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SplitsScreen(nav: NavController, splitRepo: SplitRepository, exerciseRepo: ExerciseRepository) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var creating by remember { mutableStateOf(false) }
    var splitToDelete by remember { mutableStateOf<Split?>(null) }

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Delete ${selected.size} split${if (selected.size == 1) "" else "s"}?") },
            text = { Text("Their days go with them. Past workouts keep their records.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selected
                        showBulkDeleteDialog = false
                        selectMode = false
                        selected = emptySet()
                        scope.launch { toDelete.forEach { splitRepo.softDeleteSplit(it) } }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showBulkDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Workout Splits", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${splits.size} split${if (splits.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (selectMode) {
                TextButton(onClick = {
                    selected = if (selected.size == splits.size) emptySet() else splits.map { it.id }.toSet()
                }) {
                    Text(if (selected.size == splits.size) "Deselect all" else "Select all")
                }
                IconButton(onClick = { showBulkDeleteDialog = true }, enabled = selected.isNotEmpty()) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
            } else {
                if (splits.isNotEmpty()) {
                    TextButton(onClick = { selectMode = true }) { Text("Select") }
                }
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New split")
                }
            }
        }
        if (selectMode) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap splits to select them",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
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
            var menuOpen by remember(split.id) { mutableStateOf(false) }
            val isSelected = split.id in selected
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = {
                            if (selectMode) {
                                selected = if (isSelected) selected - split.id else selected + split.id
                            } else {
                                nav.navigate("split/${split.id}")
                            }
                        },
                        onLongClick = {
                            if (!selectMode) {
                                selectMode = true
                                selected = setOf(split.id)
                            }
                        }
                    ),
                colors = when {
                    isSelected -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    split.isActive -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else -> CardDefaults.cardColors()
                }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selectMode) {
                        Icon(
                            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
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
                    if (!selectMode) {
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
                                DropdownMenuItem(
                                    text = { Text("Select") },
                                    onClick = {
                                        menuOpen = false
                                        selectMode = true
                                        selected = setOf(split.id)
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
