package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.shared.domain.SplitDay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SplitDetailScreen(nav: NavController, splitRepo: SplitRepository, splitId: UUID) {
    val days by splitRepo.observeDays(splitId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newDayName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Days", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = newDayName, onValueChange = { newDayName = it }, label = { Text("Day name (e.g. Monday Upper)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (newDayName.isNotBlank()) {
                    scope.launch {
                        splitRepo.saveDay(SplitDay(id = UUID.randomUUID(), splitId = splitId, name = newDayName, sortKey = days.size.toDouble()))
                        newDayName = ""
                    }
                }
            }) { Text("Add") }
        }
        Spacer(Modifier.height(8.dp))
        days.forEach { day ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(day.name, modifier = Modifier.weight(1f))
                    Button(onClick = { nav.navigate("day/${day.id}") }) { Text("Edit") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { scope.launch { splitRepo.softDeleteDay(day.id) } }) { Text("X") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
