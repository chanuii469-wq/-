package com.adlull.android

import com.adlull.android.filter.Blocklist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LogEntry(
    val time: Long,
    val domain: String,
    val qtype: Int,
    val blocked: Boolean,
    val app: String?,
    val pkg: String? = null,
)

/**
 * 서비스와 UI가 공유하는 실행 상태/통계.
 * DNS 요청마다 UI 상태를 갱신하면 화면이 계속 다시 그려지므로,
 * 값은 내부에만 쌓고 0.5초마다 바뀐 것만 내보낸다.
 */
object Stats {
    const val LOG_SIZE = 1000
    private const val PUBLISH_MS = 500L

    val running = MutableStateFlow(false)

    @Volatile
    var blocklist: Blocklist = Blocklist.EMPTY

    private val _totalQueries = MutableStateFlow(0L)
    private val _totalBlocked = MutableStateFlow(0L)
    val totalQueries: StateFlow<Long> = _totalQueries
    val totalBlocked: StateFlow<Long> = _totalBlocked

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    private val _topDomains = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    private val _topApps = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val topDomains: StateFlow<List<Pair<String, Int>>> = _topDomains
    val topApps: StateFlow<List<Pair<String, Int>>> = _topApps

    // ---- 내부 누적값 (lock 보호) ----
    private var queries = 0L
    private var blocked = 0L
    private val logBuf = ArrayDeque<LogEntry>()
    private val domainCounts = HashMap<String, Int>()
    private val appCounts = HashMap<String, Int>()
    private val pkgCounts = HashMap<String, LongArray>()
    private var logDirty = false
    private var topDirty = false
    private var unsaved = 0

    // DNS 응답 시간 (업스트림까지 다녀온 요청만) / 캐시 적중
    private var avgUpstreamMs = -1.0
    private var cacheHits = 0L
    private var resolved = 0L
    private val _dnsInfo = MutableStateFlow<String?>(null)
    /** 홈 화면 표시용: "DNS 평균 23ms · 캐시 41%" */
    val dnsInfo: StateFlow<String?> = _dnsInfo

    @Synchronized
    fun recordResolve(ms: Long, fromCache: Boolean) {
        resolved++
        if (fromCache) cacheHits++
        else avgUpstreamMs = if (avgUpstreamMs < 0) ms.toDouble() else avgUpstreamMs * 0.9 + ms * 0.1
    }

    fun load() {
        queries = Prefs.totalQueries
        blocked = Prefs.totalBlocked
        _totalQueries.value = queries
        _totalBlocked.value = blocked
    }

    // 기록이 생겼을 때만 깨어나 0.5초 뒤 한 번에 내보냄. 아무 요청이 없으면 전혀 깨어나지 않음
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** UI 상태 갱신 작업 시작 (앱 시작 시 한 번 호출) */
    fun startPublisher(scope: CoroutineScope) = scope.launch {
        for (u in wake) {
            delay(PUBLISH_MS)
            publish()
        }
    }

    @Synchronized
    private fun publish() {
        _totalQueries.value = queries
        _totalBlocked.value = blocked
        if (logDirty) {
            _log.value = logBuf.toList()
            logDirty = false
        }
        if (resolved > 0) {
            val avg = if (avgUpstreamMs < 0) "–" else "${avgUpstreamMs.toInt()}ms"
            _dnsInfo.value = "DNS 평균 응답 $avg · 캐시 적중 ${cacheHits * 100 / resolved}%"
        }
        if (topDirty) {
            _topDomains.value = top(domainCounts)
            _topApps.value = top(appCounts)
            topDirty = false
        }
    }

    private const val MAX_COUNTED = 5000

    /** 오래 켜 두어도 메모리가 늘지 않도록 많이 차단된 1,000개만 남김 */
    private fun trim(m: HashMap<String, Int>) {
        val keep = m.entries.sortedByDescending { it.value }.take(1000).associate { it.key to it.value }
        m.clear()
        m.putAll(keep)
    }

    private fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }

    @Synchronized
    fun record(domain: String, qtype: Int, isBlocked: Boolean, app: String?, pkg: String? = null, log: Boolean = true) {
        queries++
        if (isBlocked) {
            blocked++
            domainCounts.merge(domain, 1, Int::plus)
            if (domainCounts.size > MAX_COUNTED) trim(domainCounts)
            if (app != null) appCounts.merge(app, 1, Int::plus)
            topDirty = true
        }
        if (pkg != null) {
            val c = pkgCounts.getOrPut(pkg) { LongArray(2) }
            c[0]++
            if (isBlocked) c[1]++
        }
        if (log && Prefs.hot.logQueries) {
            logBuf.addFirst(LogEntry(System.currentTimeMillis(), domain, qtype, isBlocked, app, pkg))
            if (logBuf.size > LOG_SIZE) logBuf.removeLast()
            logDirty = true
        }
        if (++unsaved >= 500) save()
        wake.trySend(Unit)
    }

    /** 앱별 (전체 요청, 차단) 수 — 이번 실행 기준 */
    @Synchronized
    fun countsFor(pkg: String): Pair<Long, Long> = pkgCounts[pkg]?.let { it[0] to it[1] } ?: (0L to 0L)

    @Synchronized
    fun save() {
        unsaved = 0
        Prefs.saveTotals(queries, blocked)
    }

    @Synchronized
    fun reset() {
        queries = 0; blocked = 0
        domainCounts.clear(); appCounts.clear(); pkgCounts.clear()
        logBuf.clear()
        logDirty = true; topDirty = true
        save()
        publish()
    }

    @Synchronized
    fun clearLog() {
        logBuf.clear()
        logDirty = true
        publish()
    }
}
