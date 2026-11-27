package com.adlull.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adlull.android.LogEntry
import com.adlull.android.Stats
import com.adlull.android.filter.FilterManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by Stats.log.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) } // 0 전체, 1 차단, 2 허용
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }

    val shown = remember(log, query, mode) {
        log.filter {
            (mode == 0 || (mode == 1) == it.blocked) &&
                (query.isBlank() || it.domain.contains(query, true) || it.app?.contains(query, true) == true)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f),
                placeholder = { Text("도메인 또는 앱 검색") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            IconButton(onClick = { Stats.clearLog() }) { Icon(Icons.Filled.DeleteSweep, "기록 지우기") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("전체", "차단됨", "허용됨").forEachIndexed { i, s ->
                FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(s) })
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (Stats.running.value) "아직 기록이 없어요" else "보호를 켜면 DNS 요청 기록이 여기에 표시돼요",
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.time.toString() + it.domain + it.qtype + it.hashCode() }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = e }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (e.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle, null,
                        tint = if (e.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.domain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(e.app, typeName(e.qtype), fmt.format(Date(e.time))).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.domain) },
            text = { Text(if (e.blocked) "이 도메인은 차단되었어요." else "이 도메인은 허용되었어요.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { FilterManager.addUserRule(e.domain, allow = e.blocked) }
                    Toast.makeText(ctx, if (e.blocked) "예외로 추가했어요" else "차단 목록에 추가했어요", Toast.LENGTH_SHORT).show()
                    selected = null
                }) { Text(if (e.blocked) "차단 해제(예외 추가)" else "이 도메인 차단") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("domain", e.domain))
                    selected = null
                }) { Text("복사") }
            },
        )
    }
}

private fun typeName(t: Int) = when (t) {
    1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; 65 -> "HTTPS"; 64 -> "SVCB"; 16 -> "TXT"; 12 -> "PTR"; 15 -> "MX"
    else -> "TYPE$t"
}
