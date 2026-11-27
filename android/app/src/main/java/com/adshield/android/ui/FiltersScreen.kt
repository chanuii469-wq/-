package com.adshield.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.android.Prefs
import com.adshield.android.filter.FilterManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FiltersScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val subs by FilterManager.subs.collectAsStateWithLifecycle()
    val busy by FilterManager.busy.collectAsStateWithLifecycle()
    val rules by FilterManager.ruleCount.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editingRules by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }

    Column(modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("필터", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "차단 규칙 %,d개 사용 중".format(rules),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            val failed = FilterManager.updateAll()
                            Toast.makeText(
                                ctx,
                                if (failed.isEmpty()) "필터를 업데이트했어요" else "실패: ${failed.joinToString()}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, null); Spacer(Modifier.height(0.dp)); Text(" 모두 업데이트")
                    }
                    OutlinedButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Text(" 목록 추가") }
                }
            }
            items(subs, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            if (s.description.isNotEmpty()) Text(s.description, style = MaterialTheme.typography.bodySmall)
                            val info = buildList {
                                if (s.ruleCount > 0) add("규칙 %,d개".format(s.ruleCount))
                                if (s.url != null) add(if (s.lastUpdated > 0) "업데이트 ${fmt.format(Date(s.lastUpdated))}" else "아직 다운로드 안 됨")
                            }.joinToString(" · ")
                            if (info.isNotEmpty()) Text(
                                info, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.custom) IconButton(onClick = { scope.launch { FilterManager.remove(s.id) } }) {
                            Icon(Icons.Filled.Delete, "삭제")
                        }
                        Switch(checked = s.enabled, enabled = !busy, onCheckedChange = { v ->
                            scope.launch { FilterManager.setEnabled(s.id, v) }
                        })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("사용자 규칙", fontWeight = FontWeight.Bold)
                            val n = Prefs.userRules.lines().count { it.isNotBlank() && !it.trim().startsWith("!") }
                            Text("직접 추가한 차단/허용 규칙 ${n}개", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editingRules = true }) { Icon(Icons.Filled.Edit, "편집") }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("필터 목록 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("hosts 형식이나 AdGuard/uBlock 형식(||domain^) 목록 주소를 입력하세요.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http") && url.length > 10, onClick = {
                    adding = false
                    scope.launch { FilterManager.addCustom(name.trim(), url.trim()) }
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("취소") } },
        )
    }

    if (editingRules) {
        var text by remember { mutableStateOf(Prefs.userRules) }
        AlertDialog(
            onDismissRequest = { editingRules = false },
            title = { Text("사용자 규칙") },
            text = {
                Column {
                    Text(
                        "한 줄에 하나씩:\n||ads.example.com^  차단\n@@||example.com^  허용(예외)\n! 주석",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        text, { text = it },
                        Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingRules = false
                    scope.launch { FilterManager.setUserRules(text) }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { editingRules = false }) { Text("취소") } },
        )
    }
}
