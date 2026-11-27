package com.adshield.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 앱별 세부 설정. 기본값은 모두 켜짐. */
data class AppRule(
    val ads: Boolean = true,
    val trackers: Boolean = true,
    val log: Boolean = true,
) {
    val isDefault get() = ads && trackers && log
}

object AppRules {
    private val _rules = MutableStateFlow<Map<String, AppRule>>(emptyMap())
    val rules: StateFlow<Map<String, AppRule>> = _rules

    fun load() {
        _rules.value = runCatching {
            val o = JSONObject(Prefs.appRulesJson ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val r = o.getJSONObject(k)
                AppRule(r.optBoolean("ads", true), r.optBoolean("trackers", true), r.optBoolean("log", true))
            }
        }.getOrDefault(emptyMap())
    }

    operator fun get(pkg: String): AppRule = _rules.value[pkg] ?: AppRule()

    fun set(pkg: String, rule: AppRule) {
        val m = _rules.value.toMutableMap()
        if (rule.isDefault) m.remove(pkg) else m[pkg] = rule
        _rules.value = m
        val o = JSONObject()
        m.forEach { (k, r) ->
            o.put(k, JSONObject().put("ads", r.ads).put("trackers", r.trackers).put("log", r.log))
        }
        Prefs.appRulesJson = o.toString()
    }
}
