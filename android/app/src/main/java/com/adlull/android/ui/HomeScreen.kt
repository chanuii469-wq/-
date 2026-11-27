package com.adlull.android.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.adlull.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adlull.android.Stats
import com.adlull.android.filter.FilterManager
import com.adlull.android.vpn.AdBlockVpnService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(modifier: Modifier, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val running by Stats.running.collectAsStateWithLifecycle()
    val queries by Stats.totalQueries.collectAsStateWithLifecycle()
    val blocked by Stats.totalBlocked.collectAsStateWithLifecycle()
    val topDomains by Stats.topDomains.collectAsStateWithLifecycle()
    val topApps by Stats.topApps.collectAsStateWithLifecycle()
    val rules by FilterManager.ruleCount.collectAsStateWithLifecycle()
    val dnsInfo by Stats.dnsInfo.collectAsStateWithLifecycle()

    var privateDns by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(running) {
        while (true) {
            privateDns = strictPrivateDns(ctx)
            delay(5000)
        }
    }

    val color by animateColorAsState(
        if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "c"
    )

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.size(180.dp).clip(CircleShape).background(color.copy(alpha = 0.15f))
                .clickable { if (running) AdBlockVpnService.stop(ctx) else onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(130.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_moon), null, tint = Color.White, modifier = Modifier.size(72.dp))
            }
        }
        Text(
            if (running) "보호 중" else "보호 꺼짐",
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color,
        )
        Text(
            if (running) "모든 앱의 광고·추적기를 차단하고 있어요" else "가운데 버튼을 눌러 광고 차단을 시작하세요",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (privateDns != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.size(8.dp))
                        Text("비공개 DNS가 켜져 있어요", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "비공개 DNS($privateDns)가 켜져 있으면 광고 차단이 동작하지 않아요. " +
                            "설정 › 네트워크 › 비공개 DNS를 '사용 안함' 또는 '자동'으로 바꿔 주세요.",
                        Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("네트워크 설정 열기") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("차단", "%,d".format(blocked), Modifier.weight(1f))
            StatCard("전체 요청", "%,d".format(queries), Modifier.weight(1f))
            StatCard(
                "차단율",
                if (queries > 0) "%.1f%%".format(blocked * 100.0 / queries) else "–",
                Modifier.weight(1f),
            )
        }
        Text(
            "차단 규칙 %,d개 사용 중".format(rules) + (dnsInfo?.let { "\n$it" } ?: ""),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (running) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "💡 어떤 앱이 이상하게 동작하면 '앱' 탭에서 그 앱의 스위치를 꺼서 Adlull에서 빼고 다시 실행해 보세요.",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (topApps.isNotEmpty()) RankCard("광고를 가장 많이 요청한 앱", topApps)
        if (topDomains.isNotEmpty()) RankCard("가장 많이 차단된 도메인", topDomains)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RankCard(title: String, items: List<Pair<String, Int>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            items.take(5).forEach { (name, n) ->
                Row {
                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%,d".format(n), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** '비공개 DNS' 지정 모드(호스트 이름)가 켜져 있으면 그 이름을 반환 */
private fun strictPrivateDns(ctx: Context): String? {
    if (Build.VERSION.SDK_INT < 28) return null
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.privateDnsServerName
}
