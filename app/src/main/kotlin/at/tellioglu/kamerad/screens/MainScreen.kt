package at.tellioglu.kamerad.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.tellioglu.kamerad.BuildConfig
import at.tellioglu.kamerad.gopro.CameraState
import at.tellioglu.kamerad.gopro.FoundCamera
import at.tellioglu.kamerad.gopro.GoProController
import at.tellioglu.kamerad.gopro.PairedCamera
import at.tellioglu.kamerad.gopro.bondWithCamera
import at.tellioglu.kamerad.gopro.scanForCameras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

@Composable
fun MainScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired by GoProController.pairedCamera.collectAsState()
    val state by GoProController.state.collectAsState()

    val found = remember { mutableStateListOf<FoundCamera>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Back from the About page returns to the main screen instead of leaving the app
    BackHandler(enabled = showAbout) { showAbout = false }

    // Messages belong to the pairing in progress; drop them when the paired camera changes (e.g. "Forget camera")
    LaunchedEffect(paired) { message = null }

    // Refresh the recording timer
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    fun startScan() {
        found.clear()
        message = "Searching…"
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(20.seconds) {
                    scanForCameras(context).collect { camera ->
                        if (found.none { it.address == camera.address }) found.add(camera)
                    }
                }
                message = if (found.isEmpty()) "No camera found. Is it in pairing mode?" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message
            } finally {
                scanJob = null
            }
        }
    }

    fun pair(camera: FoundCamera) {
        scanJob?.cancel()
        message = "Pairing with ${camera.name}…"
        scope.launch {
            try {
                bondWithCamera(context, camera.address)
                GoProController.setPairedCamera(PairedCamera(camera.address, camera.name))
                found.clear()
                showPairing = false
                // The screen now shows the camera and its status; no extra confirmation needed
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Pairing failed: ${e.message}"
            }
        }
    }

    if (showAbout) {
        AboutScreen(onClose = { showAbout = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BACK_BUTTON_SPACE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Kamerad", style = MaterialTheme.typography.headlineMedium)

        if (!hasPermission) {
            Text("Kamerad needs Bluetooth permission to talk to your camera.")
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permission")
            }
            AboutButton { showAbout = true }
            return@Column
        }

        val camera = paired
        if (camera != null) {
            val model by GoProController.cameraModel.collectAsState()
            Text("Camera: ${camera.name}" + (model?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium)
            Text(statusText(state, now))
            val connected = state as? CameraState.Connected
            Button(
                onClick = { GoProController.toggleRecording(announce = false) },
                // The camera reports itself busy while recording, so stopping must stay possible
                enabled = connected != null && (connected.recording || !connected.busy),
                colors = if (connected?.recording == true) {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFFD7261E))
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (connected?.recording == true) "Stop recording" else "Start recording")
            }
            OutlinedButton(onClick = { GoProController.togglePower(announce = false) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        connected != null -> "Switch camera off"
                        (state as? CameraState.Off)?.canWake == false -> "Reconnect"
                        else -> "Switch camera on"
                    },
                )
            }
            // Keep the rarely used camera management away from the everyday buttons
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            ForgetCameraButton()
        }

        // With a camera paired, pairing another one is rare: keep it folded away
        if (camera != null && !showPairing) {
            OutlinedButton(onClick = { showPairing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Pair a different camera")
            }
            AboutButton { showAbout = true }
            return@Column
        }

        Text(if (camera == null) "Pair a camera" else "Pair a different camera", style = MaterialTheme.typography.titleMedium)
        Text(
            "On the GoPro: swipe down, then Preferences → Wireless Connections → Connect Device → GoPro Quik App. " +
                "Then search here and tap your camera.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (scanJob == null) {
            Button(onClick = ::startScan, modifier = Modifier.fillMaxWidth()) { Text("Search for cameras") }
        } else {
            OutlinedButton(onClick = { scanJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stop searching") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        found.forEach { found ->
            key(found.address) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pair(found) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(found.name, style = MaterialTheme.typography.bodyLarge)
                    Text(found.address, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        AboutButton { showAbout = true }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AboutButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("About")
    }
}

@Composable
private fun AboutScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BACK_BUTTON_SPACE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("About Kamerad", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text("Control an action camera from your Karoo: start and stop recording, switch it on and off, and see its battery.")
        Text(
            "GoPro and HERO are trademarks of GoPro, Inc. Kamerad is not affiliated with or endorsed by GoPro or Hammerhead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("© 2026 Horst Tellioglu. MIT License. Built with Claude.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Built on the Karoo extension library karoo-ext by Hammerhead (Apache License 2.0).",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ForgetCameraButton() {
    OutlinedButton(onClick = { GoProController.setPairedCamera(null) }, modifier = Modifier.fillMaxWidth()) {
        Text("Forget camera")
    }
}

private fun statusText(state: CameraState, now: Long): String = when (state) {
    CameraState.NotPaired -> "Not paired"
    CameraState.NoPermission -> "Bluetooth permission missing"
    CameraState.Standby, CameraState.Searching -> "Waiting for camera…"
    CameraState.Starting -> "Camera responding, starting up…"
    is CameraState.Off -> if (state.canWake) "Switched off" else "Switched off – switch it on with its button, then tap Reconnect"
    is CameraState.Connected -> buildString {
        append(
            if (state.recording) {
                val seconds = ((now - (state.recordingSince ?: now)) / 1000).coerceAtLeast(0)
                "Recording %02d:%02d".format(seconds / 60, seconds % 60)
            } else {
                "Connected"
            },
        )
        state.batteryPercent?.let { append(" · Battery $it%") }
        if (state.busy) append(" · Busy")
    }
}

/** Space at the bottom of the scrolling screens, so the last items don't hide behind the back button. */
private val BACK_BUTTON_SPACE = 72.dp
