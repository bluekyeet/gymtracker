package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import com.gymtracker.shared.domain.fuzzyFilter
import com.gymtracker.shared.domain.fuzzyScore
import com.gymtracker.shared.domain.groupName
import com.gymtracker.shared.domain.label

/** Labels for every group in use: built-in groups first, then the user's own groups alphabetically. */
fun groupLabels(exercises: List<Exercise>): List<String> {
    val custom = exercises.mapNotNull { it.customGroup?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .distinct()
        .sortedBy { it.lowercase() }
    val builtIn = MuscleGroup.values()
        .filter { b -> custom.none { it.equals(b.label, ignoreCase = true) } }
        .map { it.label }
    return builtIn + custom
}

/** Exercises with no group still need a heading, so they are shown under "Other". */
fun groupLabelOrOther(label: String?): String = label ?: "Other"

/**
 * Picker for the muscle group of a new exercise: the built-in groups, the groups already in
 * use, and "Create new group", which swaps the dropdown for a text field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerField(
    selected: String,
    onSelected: (String) -> Unit,
    knownGroups: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf("") }

    if (creating) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newGroup,
                onValueChange = { newGroup = it },
                label = { Text("New muscle group") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    newGroup.trim().takeIf { it.isNotEmpty() }?.let(onSelected)
                    newGroup = ""
                    creating = false
                },
                enabled = newGroup.isNotBlank()
            ) { Text("Use") }
            TextButton(onClick = {
                creating = false
                newGroup = ""
            }) { Text("Cancel") }
        }
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.ifEmpty { "No muscle group" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Muscle group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            DropdownMenuItem(
                text = { Text("No muscle group") },
                onClick = {
                    onSelected("")
                    expanded = false
                }
            )
            knownGroups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group) },
                    onClick = {
                        onSelected(group)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Create new group…") },
                onClick = {
                    expanded = false
                    creating = true
                }
            )
        }
    }
}

/**
 * Dialog that adds one exercise: a searchable, group-sectioned list of the library, with a
 * create mode for an exercise that does not exist yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExerciseDialog(
    title: String,
    exercises: List<Exercise>,
    onPick: (Exercise) -> Unit,
    onCreate: (name: String, groupLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            if (creating) {
                Column(Modifier.padding(16.dp)) {
                    NewExerciseForm(
                        title = "New exercise",
                        prefillName = query,
                        knownGroups = groupLabels(exercises),
                        onConfirm = onCreate
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { creating = false }) { Text("Back") }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            } else {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // Best of "matches the name" and "matches the group", so searching a group
                    // name surfaces its exercises too.
                    val available = if (query.isBlank()) {
                        exercises
                    } else {
                        exercises
                            .mapNotNull { ex ->
                                val best = listOfNotNull(
                                    fuzzyScore(query, ex.name),
                                    ex.groupName?.let { fuzzyScore(query, it) }
                                ).maxOrNull()
                                best?.let { it to ex }
                            }
                            .sortedByDescending { it.first }
                            .map { it.second }
                    }

                    if (query.isBlank()) {
                        TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("+ New exercise")
                        }
                    }

                    if (available.isEmpty()) {
                        Text(
                            "No exercise matches \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { creating = true },
                            enabled = query.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Create \"$query\"") }
                    } else {
                        val sections = available
                            .groupBy { groupLabelOrOther(it.groupName) }
                            .entries
                            .sortedBy { it.key.lowercase() }

                        LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            sections.forEach { (group, list) ->
                                item(key = "header-$group") {
                                    Text(
                                        group.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                    )
                                }
                                items(list, key = { it.id }) { ex ->
                                    DropdownMenuItem(
                                        text = { Text(ex.name, fontWeight = FontWeight.SemiBold) },
                                        onClick = { onPick(ex) }
                                    )
                                }
                            }
                            if (query.isNotBlank()) {
                                item(key = "create") {
                                    TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Create \"$query\" instead")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

/** Standalone dialog for creating an exercise (name + muscle group, including a new group). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewExerciseDialog(
    prefillName: String = "",
    exercises: List<Exercise>,
    onConfirm: (name: String, groupLabel: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                NewExerciseForm(
                    title = "New exercise",
                    prefillName = prefillName,
                    knownGroups = groupLabels(exercises),
                    onConfirm = onConfirm
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** Name + muscle group fields. The caller owns the dialog buttons so it can also offer Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewExerciseForm(
    title: String,
    prefillName: String,
    knownGroups: List<String>,
    onConfirm: (name: String, groupLabel: String) -> Unit
) {
    var name by remember { mutableStateOf(prefillName) }
    var group by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Exercise name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        GroupPickerField(
            selected = group,
            onSelected = { group = it },
            knownGroups = knownGroups
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { name.trim().takeIf { it.isNotEmpty() }?.let { onConfirm(it, group) } },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create exercise") }
    }
}
