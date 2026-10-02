package at.tellioglu.kamerad

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import at.tellioglu.kamerad.gopro.GoProController
import at.tellioglu.kamerad.screens.KarooBackButton
import at.tellioglu.kamerad.screens.MainScreen
import at.tellioglu.kamerad.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var hasPermission by mutableStateOf(false)

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = GoProController.hasBluetoothPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermission = GoProController.hasBluetoothPermission()
        if (!hasPermission) permissionRequest.launch(permissions)

        val content = ComposeView(this).apply {
            setContent {
                AppTheme {
                    // The button lies over the screen, like the Karoo's own back button
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(hasPermission = hasPermission, onRequestPermission = { permissionRequest.launch(permissions) })
                        KarooBackButton(
                            onClick = { onBackPressedDispatcher.onBackPressed() },
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
                }
            }
        }
        setContentView(KarooBackButtonLayout(this) { onBackPressedDispatcher.onBackPressed() }.apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        // Keep the camera connected while the app is open
        GoProController.acquire()
    }

    override fun onStop() {
        GoProController.release()
        super.onStop()
    }
}
