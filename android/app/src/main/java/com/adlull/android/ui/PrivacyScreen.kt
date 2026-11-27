package com.adlull.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SECTIONS = listOf(
    "요약" to "Adlull은 개인정보를 수집하지 않아요. 개발자는 서버를 운영하지 않고, 앱은 데이터를 개발자나 제3자에게 보내지 않아요.",
    "기기 안에서만 처리하는 정보" to
        "• DNS 요청(도메인 이름): 광고·추적 도메인인지 판별. 기록을 켜면 최근 1,000건을 메모리에만 보관하고 앱이 종료되면 삭제돼요.\n" +
        "• 요청한 앱 정보(Android 10 이상): 앱별 설정과 통계에 사용, 메모리에만 보관해요.\n" +
        "• 설치된 앱 목록: 앱별 설정 화면 표시용이며 저장하지 않아요.\n" +
        "• 차단 통계·설정·사용자 규칙: 기기 내부 저장소에 보관해요.\n" +
        "• 최근 DNS 응답(도메인과 IP 주소): 다시 열 때 빠르도록 최대 8,192개를 기기 안에 보관해요.",
    "앱이 접속하는 외부 서버" to
        "• DNS 서버: 차단되지 않은 도메인 조회를 고른 DNS 서버로 보내요. 기본 '자동' 모드는 통신사·와이파이 DNS와 Cloudflare(1.1.1.1)에 동시에 보내요. 조회에는 도메인 이름과 IP 주소가 포함되고, 각 운영자의 개인정보 처리방침이 적용돼요.\n" +
        "• 필터 목록 배포처: 켠 필터 목록을 각 배포처에서 내려받아요.\n" +
        "분석 도구, 광고, 오류 수집 기능은 없어요.",
    "권한" to
        "• VPN: DNS 요청을 기기 안에서 거르기 위해 사용해요. 일반 트래픽은 VPN을 거치지 않고 외부 VPN 서버도 쓰지 않아요.\n" +
        "• 설치된 앱 조회: 앱별 설정 화면용\n• 알림: 보호 상태 표시\n• 부팅 완료 수신: 재부팅 후 자동 시작",
    "삭제" to "설정 › 통계 초기화로 통계와 기록을 지울 수 있고, 앱을 삭제하면 모든 데이터가 함께 삭제돼요.",
)

@Composable
fun PrivacyScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("개인정보 처리방침", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SECTIONS.forEach { (title, body) ->
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "시행일 2026년 11월 27일",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}
