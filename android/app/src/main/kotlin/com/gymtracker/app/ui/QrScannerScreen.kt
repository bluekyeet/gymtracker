@file:OptIn(ExperimentalMaterial3Api::class)

package com.gymtracker.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.sync.ServerClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

data class ScannedPairData(
    val serverUrl: String,
    val deviceId: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(nav: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val db = remember { DatabaseProvider.get(context) }
    val serverClient = remember { ServerClient(context, db) }
    val scope = rememberCoroutineScope()

    val httpClient = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Pairing workflow states
    var scannedData by remember { mutableStateOf<ScannedPairData?>(null) }
    var isPairingLoading by remember { mutableStateOf(false) }
    var pairingErrorMessage by remember { mutableStateOf<String?>(null) }
    var pairingSuccess by remember { mutableStateOf(false) }
    var manualInputMode by remember { mutableStateOf(false) }
    var manualPayload by remember { mutableStateOf("") }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Watch", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                // 1. Success confirmation
                pairingSuccess -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Watch Paired Successfully",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Your watch is now connected to PowerTrack.\nDevice ID: ${scannedData?.deviceId?.take(8)?.uppercase() ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { nav.popBackStack() },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        ) {
                            Text("Done")
                        }
                    }
                }

                // 2. Watch Detected -> Pairing confirmation
                scannedData != null -> {
                    val pair = scannedData!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Watch,
                            contentDescription = "Watch",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Watch Detected",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Server Address:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = pair.serverUrl,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Device Identifier:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = pair.deviceId.uppercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (pairingErrorMessage != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = pairingErrorMessage!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        if (isPairingLoading) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Connecting to server...", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isPairingLoading = true
                                        pairingErrorMessage = null
                                        try {
                                            // Test server health
                                            val health = serverClient.checkHealth(pair.serverUrl, httpClient)
                                            // Update server URL and associate watch
                                            serverClient.serverUrl = pair.serverUrl.trim().removeSuffix("/")
                                            serverClient.pairedWatchDeviceId = pair.deviceId
                                            pairingSuccess = true
                                        } catch (e: Exception) {
                                            pairingErrorMessage = "Unable to reach server at ${pair.serverUrl}:\n${e.localizedMessage ?: e.message}"
                                        } finally {
                                            isPairingLoading = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("Pair Device", fontWeight = FontWeight.Bold)
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    // Cancel back to scanning
                                    scannedData = null
                                    pairingErrorMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel & Scan Again")
                            }
                        }
                    }
                }

                // 3. Permission denied state
                !hasCameraPermission -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Permission Needed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "PowerTrack needs camera access to scan the device pairing QR code displayed on your smartwatch.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        ) {
                            Text("Grant Permission")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { manualInputMode = true }
                        ) {
                            Text("Enter Payload Manually")
                        }
                    }
                }

                // 4. Manual entry fallback (for testing/emulators without camera feed)
                manualInputMode -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Manual Pairing Entry",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Enter the 8-digit Code displayed under your watch QR code (e.g. 9543F346) or the full URI:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = manualPayload,
                            onValueChange = { manualPayload = it },
                            label = { Text("Watch Code (e.g. 9543F346)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val parsed = parseAndValidatePairPayload(manualPayload, serverClient.serverUrl)
                                if (parsed != null) {
                                    scannedData = parsed
                                    manualInputMode = false
                                } else {
                                    pairingErrorMessage = "Invalid code. Enter the code from your watch screen."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Confirm")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { manualInputMode = false }) {
                            Text("Return to Camera")
                        }
                    }
                }

                // 5. Live Camera Scanner Preview
                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setTargetResolution(Size(1280, 720))
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    val qrReader = MultiFormatReader().apply {
                                        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                                    }

                                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (scannedData != null || isPairingLoading) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        try {
                                            val plane = imageProxy.planes[0]
                                            val buffer = plane.buffer
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            val width = imageProxy.width
                                            val height = imageProxy.height

                                            val source = PlanarYUVLuminanceSource(
                                                bytes, width, height, 0, 0, width, height, false
                                            )
                                            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                                            val rawResult = qrReader.decodeWithState(binaryBitmap)
                                            val text = rawResult.text
                                            val parsed = parseAndValidatePairPayload(text)
                                            if (parsed != null) {
                                                scannedData = parsed
                                            }
                                        } catch (_: NotFoundException) {
                                            // No QR in frame
                                        } catch (_: Exception) {
                                            // Format mismatch
                                        } finally {
                                            qrReader.reset()
                                            imageProxy.close()
                                        }
                                    }

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (_: Exception) {}
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Scanner Overlay Target Box
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Point your phone at the QR code displayed on your watch",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )

                            // Target frame in center
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { nav.popBackStack() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text("Cancel")
                                }
                                TextButton(
                                    onClick = { manualInputMode = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                                ) {
                                    Text("Manual Code")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Validates and parses the pairing payload generated by PowerTrack watch or companion.
 * Accepts:
 *   powertrack://pair?url=http://...&deviceId=...
 *   or JSON { "url": "...", "deviceId": "..." }
 */
fun parseAndValidatePairPayload(raw: String, defaultServerUrl: String = "http://10.0.2.2:8081"): ScannedPairData? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    try {
        if (trimmed.startsWith("powertrack://") || trimmed.contains("://pair")) {
            val uri = Uri.parse(trimmed)
            val urlParam = uri.getQueryParameter("url")
            val devIdParam = uri.getQueryParameter("deviceId") ?: uri.getQueryParameter("devId") ?: uri.getQueryParameter("key")
            if (!urlParam.isNullOrBlank() && !devIdParam.isNullOrBlank()) {
                val cleanUrl = if (!urlParam.startsWith("http://") && !urlParam.startsWith("https://")) {
                    "http://$urlParam"
                } else urlParam
                return ScannedPairData(serverUrl = cleanUrl, deviceId = devIdParam)
            }
        }

        // Fallback 1: check query format url=...&deviceId=...
        if (trimmed.contains("url=") && trimmed.contains("deviceId=")) {
            val parts = trimmed.split("&").associate {
                val kv = it.substringAfter("?").split("=")
                if (kv.size == 2) kv[0] to kv[1] else "" to ""
            }
            val u = parts["url"]
            val d = parts["deviceId"]
            if (!u.isNullOrBlank() && !d.isNullOrBlank()) {
                return ScannedPairData(serverUrl = u, deviceId = d)
            }
        }

        // Fallback 2: Direct 6..36 char pairing code from watch screen (e.g. 9543F346)
        if (trimmed.length in 6..36 && !trimmed.contains(" ") && !trimmed.contains("/")) {
            val cleanDefault = defaultServerUrl.ifBlank { "http://10.0.2.2:8081" }
            return ScannedPairData(serverUrl = cleanDefault, deviceId = trimmed)
        }
    } catch (_: Exception) {}

    return null
}
