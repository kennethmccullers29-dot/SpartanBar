package com.example.spartanbar

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val selawik = FontFamily(Font(R.font.selawik_regular))
    private var accessibilityEnabled by mutableStateOf(false)
    private var barEnabled by mutableStateOf(true)
    private var signalAccessGranted by mutableStateOf(false)
    private val requestSignalAccess = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        signalAccessGranted = hasSignalAccess()
        sendBroadcast(Intent(MetroAccessibilityService.ACTION_UPDATE).setPackage(packageName))
    }

    private fun hasSignalAccess() = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val type = MaterialTheme.typography
            MaterialTheme(typography = type.copy(
                headlineLarge = type.headlineLarge.copy(fontFamily = selawik),
                bodyLarge = type.bodyLarge.copy(fontFamily = selawik),
                labelLarge = type.labelLarge.copy(fontFamily = selawik)
            )) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF101010)).padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("SPARTAN BAR", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("Windows 10 Mobile status bar for Android", color = Color.White)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Enable the SpartanBar accessibility service to place the bar over the Android status bar. Swipe down from near the top edge for Action Center. Spartan apps can share their status bar colors directly. Other apps use a black bar.",
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(24.dp))
                    if (!accessibilityEnabled) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Accessibility settings")
                        }
                    } else {
                        Button(
                            onClick = {
                                barEnabled = !barEnabled
                                getSharedPreferences("status_bar", MODE_PRIVATE)
                                    .edit().putBoolean("enabled", barEnabled).apply()
                                sendBroadcast(Intent(MetroAccessibilityService.ACTION_UPDATE)
                                    .setPackage(packageName))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (barEnabled) "Hide status bar" else "Show status bar")
                        }
                        Spacer(Modifier.height(12.dp))
                        if (!signalAccessGranted) {
                            Button(
                                onClick = {
                                    requestSignalAccess.launch(arrayOf(
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable live signal bars")
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Button(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enable Action Center notifications")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val component = ComponentName(this, MetroAccessibilityService::class.java)
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        accessibilityEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == component.packageName &&
                it.resolveInfo.serviceInfo.name == component.className }
        barEnabled = getSharedPreferences("status_bar", MODE_PRIVATE).getBoolean("enabled", true)
        signalAccessGranted = hasSignalAccess()
        sendBroadcast(Intent(StatusBarAppearanceReceiver.ACTION).setPackage(packageName)
            .putExtra("source_package", packageName)
            .putExtra("color", 0xff101010.toInt())
            .putExtra("dark_icons", false))
    }
}
