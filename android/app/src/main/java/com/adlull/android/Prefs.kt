package com.adlull.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UpstreamDns(val label: String, val ips: List<String>, val dohUrl: String?) {
    AUTO("자동 · 가장 빠른 응답 (추천)", emptyList(), null),
    SYSTEM("통신사·와이파이 기본 DNS만", emptyList(), null),
    CLOUDFLARE("Cloudflare (1.1.1.1)", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"), "https://1.1.1.1/dns-query"),
    GOOGLE("Google (8.8.8.8)", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"), "https://8.8.8.8/dns-query"),
    QUAD9("Quad9 (9.9.9.9)", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe"), "https://9.9.9.9/dns-query");

    /** IP 문자열을 미리 InetAddress로 변환해 둠 (요청마다 변환하지 않도록) */
    val addresses: List<java.net.InetAddress> by lazy { ips.map { java.net.InetAddress.getByName(it) } }

    /** DoH를 쓸 수 있는 서버인지 (특정 회사 서버만 가능) */
    val supportsDoh get() = dohUrl != null
}

/** 앱 설정. UI가 관찰할 수 있도록 변경 시 version을 올림 */
object Prefs {
    private lateinit var sp: SharedPreferences
    // 통계는 자주 저장되므로 설정과 다른 파일에 둠 (설정 화면이 저장 때마다 다시 그려지지 않도록)
    private lateinit var statsSp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        statsSp = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        if (sp.contains("total_queries")) { // 이전 버전에서 옮기기
            statsSp.edit().putLong("total_queries", sp.getLong("total_queries", 0))
                .putLong("total_blocked", sp.getLong("total_blocked", 0)).apply()
            sp.edit().remove("total_queries").remove("total_blocked").apply()
        }
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
    var hot = Hot(UpstreamDns.AUTO, false, false, true)
        private set

    private fun refreshHot() {
        hot = Hot(upstream, useDoh, nxdomain, logQueries)
    }

    var upstream: UpstreamDns
        get() = runCatching { UpstreamDns.valueOf(sp.getString("upstream", "AUTO")!!) }.getOrDefault(UpstreamDns.AUTO)
        set(v) = sp.edit().putString("upstream", v.name).apply()

    var useDoh: Boolean
        get() = sp.getBoolean("doh", false)
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

    val totalQueries: Long get() = statsSp.getLong("total_queries", 0)
    val totalBlocked: Long get() = statsSp.getLong("total_blocked", 0)

    fun saveTotals(queries: Long, blocked: Long) =
        statsSp.edit().putLong("total_queries", queries).putLong("total_blocked", blocked).apply()
}
