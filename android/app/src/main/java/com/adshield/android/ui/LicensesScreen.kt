package com.adshield.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class Notice(val name: String, val owner: String, val license: String, val note: String = "")

private const val APACHE = "Apache License 2.0"

// 앱에 포함된 라이브러리 (releaseRuntimeClasspath 기준)
private val LIBRARIES = listOf(
    Notice("Jetpack Compose (UI, Foundation, Material 3, Material Icons, Animation, Runtime)", "The Android Open Source Project", APACHE),
    Notice("AndroidX Activity, Core, Lifecycle, SavedState, Startup", "The Android Open Source Project", APACHE),
    Notice("AndroidX WorkManager, Room, SQLite", "The Android Open Source Project", APACHE),
    Notice("AndroidX Annotation, Collection, Arch Core, Concurrent, Tracing, Profile Installer", "The Android Open Source Project", APACHE),
    Notice("AndroidX Autofill, CustomView, Emoji2, Graphics, Interpolator, VersionedParcelable", "The Android Open Source Project", APACHE),
    Notice("Kotlin Standard Library", "JetBrains s.r.o. and Kotlin Programming Language contributors", APACHE),
    Notice("kotlinx.coroutines", "JetBrains s.r.o.", APACHE),
    Notice("JetBrains Java Annotations", "JetBrains s.r.o.", APACHE),
    Notice("Guava ListenableFuture", "The Guava Authors", APACHE),
)

// 앱에 포함되지 않고, 사용자가 켜면 각 배포처에서 직접 내려받는 필터 목록
private val FILTER_LISTS = listOf(
    Notice(
        "AdGuard DNS filter", "AdGuard Software Ltd.", "GNU GPL v3.0",
        "github.com/AdguardTeam/AdGuardSDNSFilter · 앱에 포함되지 않으며 켜면 배포처에서 직접 내려받음",
    ),
    Notice(
        "StevenBlack Unified hosts", "Steven Black", "MIT License",
        "github.com/StevenBlack/hosts · 앱에 포함되지 않으며 켜면 배포처에서 직접 내려받음",
    ),
)

@Composable
fun LicensesScreen(modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var showText by remember { mutableStateOf(false) }
    val apacheText = remember {
        runCatching { ctx.assets.open("licenses/apache-2.0.txt").bufferedReader().readText() }.getOrDefault("")
    }
    BackHandler { if (showText) showText = false else onBack() }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showText) showText = false else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
            }
            Text(if (showText) APACHE else "오픈소스 라이선스", style = MaterialTheme.typography.titleLarge)
        }
        if (showText) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item { Text(apacheText, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { Section("앱에 포함된 라이브러리") }
            items(LIBRARIES) { NoticeRow(it) { showText = true } }
            item { Section("필터 목록 출처") }
            items(FILTER_LISTS) { NoticeRow(it, null) }
            item {
                Text(
                    "AdShield의 내장 차단 목록은 광고·추적 서버의 도메인 이름을 직접 정리한 것입니다.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(text: String) = Text(
    text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun NoticeRow(n: Notice, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(n.name)
        Text(
            "© ${n.owner} · ${n.license}" + if (onClick != null) " (눌러서 전문 보기)" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (n.note.isNotEmpty()) Text(n.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
