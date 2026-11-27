package com.adlull.android.vpn

import com.adlull.android.dns.DnsCache
import com.adlull.android.dns.DnsQuery
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 캐시 + 같은 요청 합치기 + 미리 갱신을 담당.
 * - 캐시: 반복 조회는 네트워크 없이 바로 응답
 * - 합치기: 같은 도메인을 동시에 여러 앱이 물으면 업스트림에는 한 번만 질의
 * - 미리 갱신: 자주 쓰는 항목이 만료되기 직전에 뒤에서 새로 받아 둠
 */
class Resolver(
    private val resolveUpstream: (ByteArray) -> ByteArray?,
    private val scope: () -> String,
    private val executor: Executor,
    val cache: DnsCache = DnsCache(),
) {
    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var result: ByteArray? = null
    }

    private val inflight = ConcurrentHashMap<String, Pending>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    @Volatile var coalesced = 0L; private set
    @Volatile var prefetched = 0L; private set

    fun resolve(query: ByteArray, q: DnsQuery): ByteArray? {
        val sc = scope()
        cache.lookup(query, q, sc)?.let { hit ->
            if (hit.stale) prefetch(query, q, sc)
            return hit.response
        }
        val key = DnsCache.key(q, sc)
        val mine = Pending()
        val other = inflight.putIfAbsent(key, mine)
        if (other != null) {
            coalesced++
            other.latch.await(6, TimeUnit.SECONDS)
            return other.result?.let { DnsCache.withId(it, query) }
        }
        try {
            val r = resolveUpstream(query)
            if (r != null) cache.put(q, r, sc)
            mine.result = r
            return r
        } finally {
            inflight.remove(key)
            mine.latch.countDown()
        }
    }

    private fun prefetch(query: ByteArray, q: DnsQuery, sc: String) {
        val key = DnsCache.key(q, sc)
        if (!refreshing.add(key)) return
        val copy = query.copyOf()
        try {
            executor.execute {
                try {
                    resolveUpstream(copy)?.let { cache.put(q, it, sc); prefetched++ }
                } finally {
                    refreshing.remove(key)
                }
            }
        } catch (e: RejectedExecutionException) {
            refreshing.remove(key)
        }
    }

    fun clear() = cache.clear()
}
