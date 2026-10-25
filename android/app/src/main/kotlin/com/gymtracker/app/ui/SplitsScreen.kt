package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SplitsScreen(nav: NavController, splitRepo: SplitRepository, exerciseRepo: ExerciseRepository) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newSplitName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Splits", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = newSplitName, onValueChange = { newSplitName = it }, label = { Text("Split name") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (newSplitName.isNotBlank()) {
                    scope.launch {
                        val split = Split(id = UUID.randomUUID(), userId = UUID.randomUUID(), name = newSplitName, isActive = splits.isEmpty())
                        splitRepo.save(split)
                        newSplitName = ""
                    }
                }
            }) { Text("Add") }
        }
        Spacer(Modifier.height(16.dp))
        splits.forEach { split ->
            val days by splitRepo.observeDays(split.id).collectAsState(initial = emptyList())
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(split.name + if (split.isActive) " (active)" else "")
                    days.forEach { day ->
                        Text("  - ${day.name}")
                    }
                    Row {
                        Button(onClick = {
                            scope.launch {
                                val day = SplitDay(id = UUID.randomUUID(), splitId = split.id, name = "New Day", sortKey = days.size.toDouble())
                                splitRepo.saveDay(day)
                            }
                        }) { Text("Add day") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
