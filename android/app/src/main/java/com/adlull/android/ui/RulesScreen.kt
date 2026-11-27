package com.adlull.android.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adlull.android.Prefs
import com.adlull.android.filter.FilterManager
import com.adlull.android.filter.UserRule
import com.adlull.android.filter.UserRules
import kotlinx.coroutines.launch

/** 사용자 규칙을 한 줄씩 켜고 끄는 화면 */
@Composable
fun RulesScreen(modifier: Modifier, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Prefs.version.collectAsStateWithLifecycle()
    val rules = remember(version) { UserRules.parse(Prefs.userRules) }

    var input by remember { mutableStateOf("") }
    var addAllow by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) } // 0 전체, 1 차단, 2 허용
    var textEdit by remember { mutableStateOf(false) }

    fun save(list: List<UserRule>) = scope.launch { FilterManager.setUserRuleList(list) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("사용자 규칙", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { textEdit = true }) { Icon(Icons.Filled.Edit, "텍스트로 편집") }
        }

        // 추가
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("도메인 (예: ads.example.com)") }, singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(!addAllow, { addAllow = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("차단") }
                    SegmentedButton(addAllow, { addAllow = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("허용") }
                }
                Button(onClick = {
                    val r = UserRules.normalize(input, addAllow)
                    if (r == null) {
                        Toast.makeText(ctx, "올바른 도메인이 아니에요", Toast.LENGTH_SHORT).show()
                    } else {
                        scope.launch { FilterManager.addUserRule(r, addAllow) }
                        input = ""
                    }
                }, Modifier.padding(start = 8.dp)) { Text("추가") }
            }
        }

        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("전체", "차단", "허용").forEachIndexed { i, s ->
                FilterChip(mode == i, { mode = i }, label = { Text(s) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "적용 중 ${rules.count { it.enabled }} / 전체 ${rules.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = rules.isNotEmpty(), onClick = { save(rules.map { it.copy(enabled = true) }) }) { Text("모두 켜기") }
            TextButton(enabled = rules.isNotEmpty(), onClick = { save(rules.map { it.copy(enabled = false) }) }) { Text("모두 끄기") }
        }
        HorizontalDivider()

        val shown = rules.withIndex().filter { mode == 0 || (mode == 2) == it.value.allow }
        if (shown.isEmpty()) {
            Text(
                "규칙이 없어요. 위에서 도메인을 추가하거나 기록 탭에서 항목을 눌러 추가하세요.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, it -> it.index.toString() + it.value.rule }) { _, (idx, rule) ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(rule.enabled, { on -> save(rules.toMutableList().also { it[idx] = rule.copy(enabled = on) }) })
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            rule.domain, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            if (rule.allow) "허용 (예외)" else "차단",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (rule.allow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = { save(rules.filterIndexed { i, _ -> i != idx }) }) {
                        Icon(Icons.Filled.Delete, "삭제")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (textEdit) {
        var text by remember { mutableStateOf(Prefs.userRules) }
        AlertDialog(
            onDismissRequest = { textEdit = false },
            title = { Text("텍스트로 편집") },
            text = {
                Column {
                    Text(
                        "한 줄에 하나씩:\n||ads.example.com^  차단\n@@||example.com^  허용(예외)\n!- 로 시작하면 꺼진 규칙",
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
                    textEdit = false
                    scope.launch { FilterManager.setUserRules(text) }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { textEdit = false }) { Text("취소") } },
        )
    }
}
