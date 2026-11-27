package com.adlull.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adlull.android.Prefs
import com.adlull.android.Stats
import com.adlull.android.UpstreamDns
import com.adlull.android.filter.FilterUpdateWorker

@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    // Prefs 변경 시 다시 그리기
    @Suppress("UNUSED_VARIABLE")
    val v by Prefs.version.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesScreen(modifier) { showLicenses = false }
        return
    }
    if (showPrivacy) {
        PrivacyScreen(modifier) { showPrivacy = false }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("DNS 서버")
        UpstreamDns.entries.forEach { u ->
            Row(
                Modifier.fillMaxWidth().clickable { Prefs.upstream = u }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = Prefs.upstream == u, onClick = { Prefs.upstream = u })
                Text(u.label)
            }
        }
        SwitchRow(
            "암호화 DNS (DNS-over-HTTPS)",
            "통신사·공유기가 방문 기록을 볼 수 없게 DNS를 암호화해요",
            Prefs.useDoh, enabled = Prefs.upstream != UpstreamDns.SYSTEM,
        ) { Prefs.useDoh = it }

        HorizontalDivider()
        Header("차단")
        SwitchRow(
            "NXDOMAIN으로 응답",
            "끄면 0.0.0.0으로 응답해요. 일부 앱이 재시도를 반복하면 켜 보세요",
            Prefs.nxdomain,
        ) { Prefs.nxdomain = it }
        SwitchRow("DNS 요청 기록", "기록 탭에 최근 ${Stats.LOG_SIZE}개 요청을 보여줘요", Prefs.logQueries) { Prefs.logQueries = it }
        SwitchRow("필터 자동 업데이트", "하루에 한 번 필터 목록을 새로 받아요", Prefs.autoUpdateFilters) {
            Prefs.autoUpdateFilters = it
            FilterUpdateWorker.schedule(ctx)
        }

        HorizontalDivider()
        Header("시작")
        SwitchRow("부팅 시 자동 시작", "휴대폰을 켜면 마지막 상태대로 보호를 다시 켜요", Prefs.autoStart) { Prefs.autoStart = it }
        ClickRow("상시 VPN 설정", "시스템 VPN 설정에서 Adlull를 '상시 사용'으로 지정하면 꺼지지 않아요") {
            ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        HorizontalDivider()
        Header("통계")
        ClickRow("통계 초기화", "차단 수와 기록을 모두 지워요") { confirmReset = true }

        HorizontalDivider()
        Header("정보")
        ClickRow("개인정보 처리방침", "Adlull은 개인정보를 수집하지 않아요") { showPrivacy = true }
        ClickRow("오픈소스 라이선스", "앱에 쓰인 라이브러리와 필터 목록의 저작권·라이선스") { showLicenses = true }

        Text(
            "Adlull ${com.adlull.android.BuildConfig.VERSION_NAME} · DNS 기반 광고 차단\n유튜브 영상 광고처럼 콘텐츠와 같은 서버에서 나오는 광고는 DNS로 막을 수 없어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("통계를 초기화할까요?") },
            confirmButton = { TextButton(onClick = { Stats.reset(); confirmReset = false }) { Text("초기화") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Header(text: String) = Text(
    text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
)

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ClickRow(title: String, desc: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
