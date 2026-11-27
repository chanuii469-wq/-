package com.adlull.android.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adlull.android.AppRule
import com.adlull.android.AppRules
import com.adlull.android.Prefs
import com.adlull.android.Stats
import com.adlull.android.vpn.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class AppItem(val pkg: String, val label: String, val system: Boolean)

@Composable
fun AppsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var excluded by remember { mutableStateOf(Prefs.excludedApps) }
    val rules by AppRules.rules.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var onlyChanged by remember { mutableStateOf(false) }
    var openPkg by rememberSaveable { mutableStateOf<String?>(null) }

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

    // 적용 여부(VPN 경유)는 VPN을 다시 연결해야 반영됨. 연속 변경을 모아서 한 번에 재시작
    LaunchedEffect(excluded) {
        if (excluded == Prefs.excludedApps) return@LaunchedEffect
        Prefs.excludedApps = excluded
        delay(1200)
        AdBlockVpnService.restartIfRunning(ctx)
    }

    val setApplied = { pkg: String, on: Boolean -> excluded = if (on) excluded - pkg else excluded + pkg }

    val open = openPkg?.let { p -> apps?.firstOrNull { it.pkg == p } }
    if (open != null) {
        AppDetail(
            modifier, open,
            applied = open.pkg !in excluded,
            rule = rules[open.pkg] ?: AppRule(),
            onApplied = { setApplied(open.pkg, it) },
            onRule = { AppRules.set(open.pkg, it) },
            onBack = { openPkg = null },
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Text(
            "앱별 설정",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        )
        Text(
            "스위치로 앱마다 Adlull 적용 여부를 정하고, 앱을 누르면 광고·추적기 차단을 따로 설정할 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("앱 검색") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(onlyChanged, { onlyChanged = !onlyChanged }, label = { Text("변경한 앱만") })
            FilterChip(showSystem, { showSystem = !showSystem }, label = { Text("시스템 앱 표시") })
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val isChanged = { a: AppItem -> a.pkg in excluded || rules[a.pkg]?.isDefault == false }
        val shown = list.filter {
            (showSystem || !it.system || isChanged(it)) &&
                (!onlyChanged || isChanged(it)) &&
                (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
        }.sortedByDescending(isChanged)
        if (shown.isEmpty()) {
            Text("표시할 앱이 없어요", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { app ->
                val applied = app.pkg !in excluded
                Row(
                    Modifier.fillMaxWidth().clickable { openPkg = app.pkg }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.pkg, 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            summary(applied, rules[app.pkg] ?: AppRule()),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isChanged(app)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = applied, onCheckedChange = { setApplied(app.pkg, it) })
                }
            }
        }
    }
}

private fun summary(applied: Boolean, r: AppRule): String = when {
    !applied -> "적용 안 함 · 모든 트래픽 우회"
    r.isDefault -> "기본 설정"
    else -> buildList {
        add(if (r.ads) "광고 차단" else "광고 허용")
        add(if (r.trackers) "추적기 차단" else "추적기 허용")
        if (!r.log) add("기록 안 함")
    }.joinToString(" · ")
}

@Composable
private fun AppDetail(
    modifier: Modifier,
    app: AppItem,
    applied: Boolean,
    rule: AppRule,
    onApplied: (Boolean) -> Unit,
    onRule: (AppRule) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val log by Stats.log.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val tick by Stats.totalQueries.collectAsStateWithLifecycle()
    val (queries, blocked) = Stats.countsFor(app.pkg)
    val recent = log.filter { it.pkg == app.pkg }.take(30)
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }
    val perAppSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.pkg, 56.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(app.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("요청", "%,d".format(queries), Modifier.weight(1f))
                MiniStat("차단", "%,d".format(blocked), Modifier.weight(1f))
            }

            DetailSwitch(
                "Adlull 적용", "끄면 이 앱의 트래픽이 Adlull를 전혀 거치지 않아요 (은행·결제 앱 문제 해결용)",
                applied, true, onApplied,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            DetailSwitch("광고 차단", "광고 목록과 사용자 차단 규칙을 이 앱에 적용", rule.ads, applied && perAppSupported) {
                onRule(rule.copy(ads = it))
            }
            DetailSwitch("추적기 차단", "분석·추적 도구 목록을 이 앱에 적용", rule.trackers, applied && perAppSupported) {
                onRule(rule.copy(trackers = it))
            }
            DetailSwitch("요청 기록", "이 앱의 DNS 요청을 기록 탭에 남김", rule.log, applied && perAppSupported) {
                onRule(rule.copy(log = it))
            }
            if (!perAppSupported) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        "Android 10 미만에서는 요청한 앱을 알 수 없어 광고/추적기/기록 개별 설정을 쓸 수 없어요. 'Adlull 적용'은 동작해요.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!rule.isDefault) {
                OutlinedButton(onClick = { onRule(AppRule()) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("기본값으로 되돌리기")
                }
            }
            Text(
                "최근 요청", fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
            )
            if (recent.isEmpty()) {
                Text(
                    "아직 기록된 요청이 없어요", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(recent, key = { it.time.toString() + it.domain + it.hashCode() }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (e.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle, null,
                    tint = if (e.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(e.domain, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailSwitch(title: String, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: Dp) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(size)) { icon?.let { Image(it, null, Modifier.size(size)) } }
}

private fun hasInternet(pm: PackageManager, pkg: String): Boolean =
    pm.checkPermission(Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED
