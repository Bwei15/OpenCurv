package com.motoroute

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import com.motoroute.ui.OpenCurvRoot
import com.motoroute.ui.map.MapViewModel
import com.motoroute.ui.theme.OpenCurvTheme

/**
 * The single activity.
 *
 * Three things here exist purely because this app is used on a motorcycle:
 * the screen is pinned on, the volume rocker zooms the map so the rider never
 * has to take a hand off the bar, and the activity survives rotation without
 * tearing down navigation (which lives in a process-scoped controller).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MapViewModel by viewModels { MapViewModel.Factory }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModel.recenter()
        }
    }

    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.importFile(uri) { message -> viewModel.showMessage(message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The display must never sleep or dim while the app is up: a rider
        // cannot tap to wake a phone at 100 km/h.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissionsIfNeeded()

        setContent {
            val settings by viewModel.settings.collectAsState()
            OpenCurvTheme(mapTheme = settings.mapTheme) {
                OpenCurvRoot(
                    viewModel = viewModel,
                    onImportRequested = {
                        importFile.launch(arrayOf("*/*"))
                    },
                    onRequestPermission = ::requestPermissionsIfNeeded,
                )
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermission.launch(permissions.toTypedArray())
    }

    /**
     * Volume keys zoom the map.
     *
     * With gloves on and a bumpy road, a physical button beats a touch target
     * every time. The keys are only intercepted while the rider has this
     * enabled, so media volume still works when they want it.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!viewModel.settings.value.volumeKeyZoom) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.zoomIn()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.zoomOut()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Swallow the matching key-up so the system volume UI never appears. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!viewModel.settings.value.volumeKeyZoom) return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
