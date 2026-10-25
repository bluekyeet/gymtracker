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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Splits", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = newSplitName, onValueChange = { newSplitName = it }, label = { Text("Split name") }, modifier = Modifier.weight(1f))
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
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(split.name + if (split.isActive) " (active)" else "", modifier = Modifier.weight(1f))
                    Button(onClick = { nav.navigate("split/${split.id}") }) { Text("Edit") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { scope.launch { splitRepo.softDeleteSplit(split.id) } }) { Text("X") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
