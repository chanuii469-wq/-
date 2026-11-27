package com.adshield.android.filter

import android.content.Context
import android.util.Log
import com.adshield.android.Prefs
import com.adshield.android.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class FilterSub(
    val id: String,
    val name: String,
    val url: String?,          // null 또는 asset:파일명 = 앱에 내장된 목록
    val description: String = "",
    val category: Int = Blocklist.ADS,
    val enabled: Boolean = true,
    val custom: Boolean = false,
    val lastUpdated: Long = 0,
    val ruleCount: Int = 0,
) {
    val remote: Boolean get() = url != null && !url.startsWith("asset:")
}

object FilterManager {
    private const val TAG = "FilterManager"
    private const val MAX_BYTES = 40L * 1024 * 1024

    private val DEFAULTS = listOf(
        FilterSub("builtin_ads", "AdShield 기본 광고 목록", "asset:default_ads.txt", "앱 광고 SDK·국내외 광고 네트워크 (내장)"),
        FilterSub(
            "builtin_trackers", "AdShield 기본 추적기 목록", "asset:default_trackers.txt",
            "분석·추적 도구 (내장)", category = Blocklist.TRACKERS,
        ),
        FilterSub(
            "adguard_dns", "AdGuard DNS 필터",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "모바일에 최적화된 광고·추적기 목록", enabled = true,
        ),
        FilterSub(
            "stevenblack", "StevenBlack Unified hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "광고·악성코드 도메인 hosts 목록", enabled = false,
        ),
        FilterSub(
            "oisd_small", "OISD small",
            "https://small.oisd.nl/",
            "오차단이 적은 범용 차단 목록", enabled = false,
        ),
        FilterSub(
            "pgl", "Peter Lowe's list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            "가벼운 광고 서버 목록", enabled = false,
        ),
    )

    private lateinit var appCtx: Context
    private val mutex = Mutex()

    private val _subs = MutableStateFlow<List<FilterSub>>(emptyList())
    val subs: StateFlow<List<FilterSub>> = _subs

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = _ruleCount

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        _subs.value = loadSubs()
    }

    private fun dir() = File(appCtx.filesDir, "filters").apply { mkdirs() }
    private fun fileFor(s: FilterSub) = File(dir(), "${s.id}.txt")

    fun needsInitialDownload() = _subs.value.any { it.enabled && it.remote && !fileFor(it).exists() }

    // ---------- 구독 목록 저장/불러오기 ----------
    private fun loadSubs(): List<FilterSub> {
        val json = Prefs.subscriptionsJson ?: return DEFAULTS
        return runCatching {
            val arr = JSONArray(json)
            val saved = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                FilterSub(
                    id = it.getString("id"),
                    name = it.getString("name"),
                    url = it.optString("url").ifEmpty { null },
                    description = it.optString("description"),
                    enabled = it.optBoolean("enabled", true),
                    custom = it.optBoolean("custom", false),
                    lastUpdated = it.optLong("lastUpdated"),
                    ruleCount = it.optInt("ruleCount"),
                    category = it.optInt("category", Blocklist.ADS),
                )
            }.filter { it.custom || DEFAULTS.any { d -> d.id == it.id } } // 없어진 기본 목록 제거
                .map { s -> DEFAULTS.firstOrNull { it.id == s.id }?.let { d -> s.copy(url = d.url, category = d.category) } ?: s }
            // 새 버전에서 추가된 기본 목록 합치기
            saved + DEFAULTS.filter { d -> saved.none { it.id == d.id } }
        }.getOrDefault(DEFAULTS)
    }

    private fun saveSubs(list: List<FilterSub>) {
        _subs.value = list
        Prefs.subscriptionsJson = JSONArray(list.map {
            JSONObject()
                .put("id", it.id).put("name", it.name).put("url", it.url ?: "")
                .put("description", it.description).put("enabled", it.enabled)
                .put("custom", it.custom).put("lastUpdated", it.lastUpdated).put("ruleCount", it.ruleCount)
                .put("category", it.category)
        }).toString()
    }

    private fun update(id: String, f: (FilterSub) -> FilterSub) =
        saveSubs(_subs.value.map { if (it.id == id) f(it) else it })

    // ---------- 사용자 조작 ----------
    suspend fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled) }
        val sub = _subs.value.first { it.id == id }
        busy {
            if (enabled && sub.remote && !fileFor(sub).exists()) download(sub)
            rebuild()
        }
    }

    suspend fun addCustom(name: String, url: String, category: Int = Blocklist.ADS) {
        val id = "custom_" + System.currentTimeMillis()
        saveSubs(_subs.value + FilterSub(id, name.ifBlank { url }, url, "사용자 추가 목록", category = category, custom = true))
        busy {
            download(_subs.value.first { it.id == id })
            rebuild()
        }
    }

    suspend fun remove(id: String) {
        _subs.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
        saveSubs(_subs.value.filterNot { it.id == id })
        rebuild()
    }

    suspend fun setUserRules(text: String) {
        Prefs.userRules = text
        rebuild()
    }

    /** 도메인 차단/허용 규칙 추가. 같은 도메인의 기존 규칙은 대체한다. */
    suspend fun addUserRule(domain: String, allow: Boolean) {
        val rule = UserRules.normalize(domain, allow) ?: return
        val d = Blocklist.parseRule(rule)!!.first
        val rules = UserRules.parse(Prefs.userRules).filterNot { it.domain == d }
        setUserRules(UserRules.format(rules + UserRule(rule, true)))
    }

    suspend fun setUserRuleList(rules: List<UserRule>) = setUserRules(UserRules.format(rules))

    /** 켜진 원격 목록 모두 다운로드. @return 실패한 목록 이름 */
    suspend fun updateAll(): List<String> {
        val failed = mutableListOf<String>()
        busy {
            for (s in _subs.value.filter { it.enabled && it.remote }) {
                if (!download(s)) failed += s.name
            }
            rebuild()
        }
        return failed
    }

    private suspend fun <T> busy(block: suspend () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
        }
    }

    private suspend fun download(s: FilterSub): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmp = File(dir(), "${s.id}.tmp")
            var url = URL(s.url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AdShield/1.0 (Android)")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 5) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                break
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()
            // 규칙 수 계산
            val b = HashMap<String, Int>(); val a = HashSet<String>()
            tmp.useLines { seq -> seq.forEach { Blocklist.parseLine(it, b, a) } }
            if (b.isEmpty() && a.isEmpty()) throw IllegalStateException("no rules")
            tmp.renameTo(fileFor(s))
            update(s.id) { it.copy(lastUpdated = System.currentTimeMillis(), ruleCount = b.size + a.size) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${s.url}", e)
            false
        }
    }

    /** 켜진 모든 목록 + 사용자 규칙으로 차단 목록을 다시 만듦 */
    suspend fun rebuild() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val block = HashMap<String, Int>(200_000)
            val allow = HashSet<String>()
            for (s in _subs.value.filter { it.enabled }) {
                try {
                    if (s.url?.startsWith("asset:") == true) {
                        var n = 0
                        appCtx.assets.open(s.url.removePrefix("asset:")).bufferedReader().useLines { seq ->
                            seq.forEach { n += Blocklist.parseLine(it, block, allow, s.category) }
                        }
                        if (s.ruleCount == 0) update(s.id) { it.copy(ruleCount = n) }
                    } else {
                        val f = fileFor(s)
                        if (f.exists()) f.useLines { seq -> seq.forEach { Blocklist.parseLine(it, block, allow, s.category) } }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "load failed: ${s.id}", e)
                }
            }
            UserRules.activeLines(Prefs.userRules).forEach { Blocklist.parseLine(it, block, allow, Blocklist.USER) }
            Stats.blocklist = Blocklist(block, allow)
            _ruleCount.value = block.size
        }
    }
}
