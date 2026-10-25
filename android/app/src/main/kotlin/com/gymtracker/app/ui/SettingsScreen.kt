package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.sync.ServerClient
import com.gymtracker.data.sync.SyncManager
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val serverClient = remember { ServerClient(context, db) }
    val syncManager = remember { SyncManager(context, db) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(serverClient.serverUrl) }
    var key by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(!serverClient.accessToken.isNullOrBlank()) }
    var isLoading by remember { mutableStateOf(false) }

    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Server Settings & Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connection Status: ${if (isConnected) "Connected" else "Not connected"}", fontWeight = FontWeight.Medium)

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL (e.g. http://10.0.2.2:8081)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Enrollment Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (statusMessage != null) {
                    Text(statusMessage!!, color = MaterialTheme.colorScheme.primary)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                statusMessage = "Testing & Connecting..."
                                try {
                                    val health = serverClient.checkHealth(url, client)
                                    statusMessage = "Health OK (Server ID: ${health.serverId.take(8)}...). Enrolling..."
                                    serverClient.enroll(url, key, android.os.Build.MODEL, client)
                                    isConnected = true
                                    statusMessage = "Successfully connected and enrolled!"
                                } catch (e: Exception) {
                                    statusMessage = "Connection failed: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Text(if (isConnected) "Reconnect / Update" else "Connect & Test")
                    }

                    if (isConnected) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    statusMessage = "Syncing..."
                                    try {
                                        val devId = serverClient.deviceId?.let { UUID.fromString(it) } ?: UUID.randomUUID()
                                        syncManager.pushPending(serverClient.serverUrl, serverClient.accessToken!!, devId, 1, client)
                                        syncManager.pull(serverClient.serverUrl, serverClient.accessToken!!, client)
                                        statusMessage = "Sync complete successfully!"
                                    } catch (e: Exception) {
                                        statusMessage = "Sync failed: ${e.localizedMessage}"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading
                        ) {
                            Text("Sync Now")
                        }
                    }
                }

                if (isConnected) {
                    TextButton(onClick = {
                        serverClient.accessToken = null
                        serverClient.refreshToken = null
                        serverClient.deviceId = null
                        isConnected = false
                        statusMessage = "Disconnected (local data preserved)."
                    }) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
