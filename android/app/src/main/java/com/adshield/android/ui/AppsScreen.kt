package com.adshield.android.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.adshield.android.Prefs
import com.adshield.android.vpn.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class AppItem(val pkg: String, val label: String, val system: Boolean)

@Composable
fun AppsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var excluded by remember { mutableStateOf(Prefs.excludedApps) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val apps by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != ctx.packageName && hasInternet(pm, it.packageName) }
                .map {
                    AppItem(it.packageName, pm.getApplicationLabel(it).toString(), it.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    // 바꾼 뒤 잠시 기다렸다가 VPN 재시작 (연속 변경을 한 번에 반영)
    LaunchedEffect(excluded) {
        if (excluded == Prefs.excludedApps) return@LaunchedEffect
        Prefs.excludedApps = excluded
        delay(1200)
        AdBlockVpnService.restartIfRunning(ctx)
    }

    Column(modifier.fillMaxSize()) {
        Text(
            "차단에서 제외할 앱",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        )
        Text(
            "광고 차단 때문에 문제가 생기는 앱(은행, 결제 앱 등)은 켜서 제외하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("앱 검색") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
        )
        FilterChip(
            selected = showSystem, onClick = { showSystem = !showSystem },
            label = { Text("시스템 앱 표시") }, modifier = Modifier.padding(horizontal = 16.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val shown = list.filter {
            (showSystem || !it.system || it.pkg in excluded) &&
                (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
        }.sortedByDescending { it.pkg in excluded }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { app ->
                val on = app.pkg in excluded
                val toggle = { excluded = if (on) excluded - app.pkg else excluded + app.pkg }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.pkg)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(checked = on, onCheckedChange = { toggle() })
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.size(40.dp)) } }
}

private fun hasInternet(pm: PackageManager, pkg: String): Boolean =
    pm.checkPermission(Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED
