package com.qtekfun.ultimatemaps.emergency

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.location.AndroidLocationSource
import com.qtekfun.ultimatemaps.ui.theme.MapasTheme

/**
 * Emergency screen (launcher shortcut or the SOS chip of the search panel). It reads the location only while
 * visible, shows it on screen and never logs or stores it. The call button opens the dialer; it does not call.
 */
class EmergencyActivity : ComponentActivity() {
    private lateinit var controller: EmergencyController

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val location = AndroidLocationSource(this)
        controller = EmergencyController(location, hasPermission = ::hasLocationPermission, providerAvailable = { location.isAvailable })
        setContent {
            MapasTheme(darkTheme = isSystemInDarkTheme()) {
                EmergencyScreen(
                    state = controller.state,
                    onDial = ::dial,
                    onShare = ::share,
                    onGrantLocation = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    private fun dial() {
        try {
            startActivity(EmergencyIntents.dial())
            controller.state.dialFailed = false
        } catch (_: ActivityNotFoundException) {
            controller.state.dialFailed = true
        }
    }

    private fun share() {
        val position = controller.state.position ?: return
        startActivity(EmergencyIntents.share(EmergencyText.shareText(getString(R.string.emergency_share_header), position)))
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
