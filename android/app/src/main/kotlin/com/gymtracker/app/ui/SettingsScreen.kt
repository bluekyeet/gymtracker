package com.gymtracker.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.sync.ServerClient
import com.gymtracker.data.sync.SyncManager
import com.gymtracker.data.sync.SyncResult
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val serverClient = remember { ServerClient(context, db) }
    val syncManager = remember { SyncManager(context, db) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(serverClient.serverUrl.ifBlank { "http://10.0.2.2:8081" }) }
    var key by remember { mutableStateOf("k7m2-x9qd-4hrt-wc3f-p8ze-n6ay") }
    var keyVisible by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(!serverClient.accessToken.isNullOrBlank()) }
    var isLoading by remember { mutableStateOf(false) }

    val client = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                    prettyPrint = true
                    encodeDefaults = true
                })
            }
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
                Text(
                    "Connection Status: ${if (isConnected) "Configured & Enrolled" else "Not enrolled"}",
                    fontWeight = FontWeight.Medium
                )

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
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) "Hide enrollment key" else "Show enrollment key"
                            )
                        }
                    },
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
                                    statusMessage = "Connection failed: ${e.javaClass.simpleName}: ${e.message ?: e.localizedMessage}"
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
                                    when (val res = syncManager.performFullSync(serverClient, client)) {
                                        is SyncResult.Success -> {
                                            statusMessage = "Sync complete successfully! (Last: ${serverClient.lastSyncTime})"
                                        }
                                        is SyncResult.NetworkError -> {
                                            statusMessage = "Server unavailable. Device pairing preserved."
                                        }
                                        is SyncResult.AuthError -> {
                                            statusMessage = "Authentication failed: ${res.message}. Tap Reconnect / Update to refresh credentials."
                                        }
                                        is SyncResult.OtherError -> {
                                            statusMessage = "Sync failed: ${res.message}"
                                        }
                                    }
                                    isLoading = false
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

        // Add Device (Watch QR Scanner)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Companion Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val pairedWatch = serverClient.pairedWatchDeviceId
                if (pairedWatch != null) {
                    Text(
                        "Paired Watch ID: ${pairedWatch.take(8).uppercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "Scan the QR code displayed on your smartwatch Settings to connect it with PowerTrack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { nav.navigate("scan_device") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Device", fontWeight = FontWeight.Bold)
                }
            }
        }

        // QR Code Pairing Card (like WhatsApp/Telegram)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Device Pairing (QR Code)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Scan with watch or another device to link credentials automatically:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val pairPayload = remember(url, key) {
                    "powertrack://pair?url=${url.trim()}&key=${key.trim()}"
                }

                val qrBitmap = remember(pairPayload) {
                    generateQrBitmap(pairPayload, 300)
                }

                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Device Pairing QR Code",
                            modifier = Modifier.size(180.dp)
                        )
                    }
                }

                Text(
                    "URL: $url",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
