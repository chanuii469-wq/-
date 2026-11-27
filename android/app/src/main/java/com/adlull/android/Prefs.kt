package com.adlull.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UpstreamDns(val label: String, val ips: List<String>, val dohUrl: String?) {
    SYSTEM("시스템 기본 DNS", emptyList(), null),
    CLOUDFLARE("Cloudflare (1.1.1.1)", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"), "https://1.1.1.1/dns-query"),
    GOOGLE("Google (8.8.8.8)", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"), "https://8.8.8.8/dns-query"),
    QUAD9("Quad9 (9.9.9.9)", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe"), "https://9.9.9.9/dns-query"),
}

/** 앱 설정. UI가 관찰할 수 있도록 변경 시 version을 올림 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(listener)
        refreshHot()
    }

    // 리스너는 강한 참조로 보관해야 함
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshHot()
        _version.value++
    }

    /** DNS 요청마다 읽는 값은 메모리에 보관 (SharedPreferences 잠금 회피) */
    class Hot(val upstream: UpstreamDns, val useDoh: Boolean, val nxdomain: Boolean, val logQueries: Boolean)

    @Volatile
    var hot = Hot(UpstreamDns.CLOUDFLARE, true, false, true)
        private set

    private fun refreshHot() {
        hot = Hot(upstream, useDoh, nxdomain, logQueries)
    }

    var upstream: UpstreamDns
        get() = runCatching { UpstreamDns.valueOf(sp.getString("upstream", "CLOUDFLARE")!!) }.getOrDefault(UpstreamDns.CLOUDFLARE)
        set(v) = sp.edit().putString("upstream", v.name).apply()

    var useDoh: Boolean
        get() = sp.getBoolean("doh", true)
        set(v) = sp.edit().putBoolean("doh", v).apply()

    var nxdomain: Boolean
        get() = sp.getBoolean("nxdomain", false)
        set(v) = sp.edit().putBoolean("nxdomain", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var wasRunning: Boolean
        get() = sp.getBoolean("was_running", false)
        set(v) = sp.edit().putBoolean("was_running", v).apply()

    var autoUpdateFilters: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var logQueries: Boolean
        get() = sp.getBoolean("log_queries", true)
        set(v) = sp.edit().putBoolean("log_queries", v).apply()

    var excludedApps: Set<String>
        get() = sp.getStringSet("excluded_apps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("excluded_apps", v).apply()

    var userRules: String
        get() = sp.getString("user_rules", "")!!
        set(v) = sp.edit().putString("user_rules", v).apply()

    var appRulesJson: String?
        get() = sp.getString("app_rules", null)
        set(v) = sp.edit().putString("app_rules", v).apply()

    var subscriptionsJson: String?
        get() = sp.getString("subscriptions", null)
        set(v) = sp.edit().putString("subscriptions", v).apply()

    var totalQueries: Long
        get() = sp.getLong("total_queries", 0)
        set(v) = sp.edit().putLong("total_queries", v).apply()

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0)
        set(v) = sp.edit().putLong("total_blocked", v).apply()
}
