package com.adlull.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adlull.android.BuildConfig
import com.adlull.android.Diagnostics
import com.adlull.android.Prefs
import com.adlull.android.Stats
import kotlinx.coroutines.delay

/** 속도·발열 원인을 찾기 위한 진단 화면. 열려 있는 동안만 2초마다 갱신 */
@Composable
fun DiagnosticsScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(Unit) {
        var lastCpu = Process.getElapsedCpuTime()
        var lastWall = SystemClock.elapsedRealtime()
        var recentCpu = "측정 중…"
        while (true) {
            rows = collect(ctx, recentCpu)
            delay(2000)
            val cpu = Process.getElapsedCpuTime(); val wall = SystemClock.elapsedRealtime()
            recentCpu = "%.1f%%".format((cpu - lastCpu) * 100.0 / (wall - lastWall).coerceAtLeast(1))
            lastCpu = cpu; lastWall = wall
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("상태 진단", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "느리거나 뜨거울 때 이 화면을 열어 두고 잠시 쓴 뒤, 아래 '복사'를 눌러 보내 주세요. " +
                    "방문한 사이트 주소는 포함되지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider()
            }
            Button(
                onClick = {
                    val text = "Adlull 진단\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" }
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Adlull 진단", text))
                    Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) { Text("진단 정보 복사") }
        }
    }
}

private fun collect(ctx: Context, recentCpu: String): List<Pair<String, String>> {
    val p = Diagnostics.percentiles()
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val privateDns = if (Build.VERSION.SDK_INT >= 28) {
        cm.getLinkProperties(cm.activeNetwork)?.let { lp ->
            when {
                lp.privateDnsServerName != null -> "지정(${lp.privateDnsServerName})"
                lp.isPrivateDnsActive -> "자동(사용 중)"
                else -> "꺼짐/자동"
            }
        } ?: "-"
    } else "-"
    val hot = Prefs.hot
    return listOf(
        "버전" to BuildConfig.VERSION_NAME,
        "Android" to "${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})",
        "보호 상태" to if (Stats.running.value) "켜짐" else "꺼짐",
        "앱 CPU (최근 2초)" to recentCpu,
        "앱 CPU (시작 후 평균)" to "%.1f%%".format(Diagnostics.cpuSinceStart()),
        "DNS 응답 중앙값 / 느린 5%" to (p?.let { "${it.first}ms / ${it.second}ms" } ?: "-"),
        "느린 응답(0.3초+) / 실패" to "${Diagnostics.slow()} / ${Diagnostics.failures()}",
        "DNS 요약" to (Stats.dnsInfo.value ?: "-"),
        "DNS 서버 설정" to hot.upstream.name + if (hot.useDoh) " + DoH" else "",
        "사용 중인 DNS 서버" to Diagnostics.dnsServers.joinToString(", ").ifEmpty { "-" },
        "비공개 DNS" to privateDns,
        "VPN 시작 / 터널 끊김 / 자동 재연결" to "${Diagnostics.vpnStarts} / ${Diagnostics.tunnelEnds} / ${Diagnostics.autoReconnects}",
        "DNS 엔진" to Diagnostics.poolInfo(),
        "요청 기록" to if (hot.logQueries) "켜짐" else "꺼짐",
    )
}
