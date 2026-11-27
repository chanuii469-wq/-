package com.adshield.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.adshield.android.vpn.AdBlockVpnService

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START = "start"
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) AdBlockVpnService.start(this)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** VPN 권한을 확인하고 시작 */
    fun startProtection() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else AdBlockVpnService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (intent?.getBooleanExtra(EXTRA_START, false) == true) startProtection()

        setContent { AdShieldTheme { AppRoot(onStart = ::startProtection) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) startProtection()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("보호", Icons.Filled.Shield),
    LOG("기록", Icons.Filled.History),
    FILTERS("필터", Icons.Filled.FilterList),
    APPS("앱", Icons.Filled.Apps),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(onStart: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.HOME -> HomeScreen(m, onStart)
            Tab.LOG -> LogScreen(m)
            Tab.FILTERS -> FiltersScreen(m)
            Tab.APPS -> AppsScreen(m)
            Tab.SETTINGS -> SettingsScreen(m)
        }
    }
}

val Brand = Color(0xFF2E7D32)

@Composable
fun AdShieldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Brand, secondary = Brand)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
