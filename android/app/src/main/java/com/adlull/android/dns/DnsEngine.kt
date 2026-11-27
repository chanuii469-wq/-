package com.adlull.android.dns

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** DNS를 요청한 앱 */
data class AppOwner(val pkg: String?, val label: String)

/**
 * DNS 처리 엔진. 안드로이드에 의존하지 않아 PC에서도 그대로 테스트할 수 있다.
 *
 * 요청마다 스레드나 소켓을 새로 만들지 않고, 정해진 몇 개의 스레드로 모든 요청을 처리한다.
 *  - 터널 읽기 스레드(호출자): 패킷 해석 → 차단·캐시 응답은 그 자리에서 바로 돌려줌
 *  - I/O 스레드 1개: 미리 열어 둔 논블로킹 소켓 4개로 업스트림 응답을 받고,
 *    응답이 늦으면 다른 서버로 다시 보내고(헤징), 시간이 지나면 실패 처리
 *  - 앱 조회 스레드 1개: '어느 앱이 요청했는지' 시스템 조회 (초당 횟수 제한)
 *  - DoH·TCP 처리 스레드: 켜져 있거나 필요할 때만, 최대 8개
 *
 * 업스트림이 응답하지 않으면 조용히 버리지 않고 정해진 시간 안에 SERVFAIL로 답한다.
 * (버리면 안드로이드가 5초를 기다린 뒤에야 다시 물어봐서 페이지가 크게 느려진다)
 */
class DnsEngine(
    private val host: Host,
    private val deadlineMs: Long = 3000,
    private val minHedgeMs: Long = 100,
    private val maxHedgeMs: Long = 800,
    ownerLookupsPerSecond: Int = 20,
    val cache: DnsCache = DnsCache(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Host {
        fun writeToTun(packet: ByteArray)
        /** 차단 분류 플래그 (0이면 차단 대상 아님) */
        fun match(name: String): Int
        /** 앱별 규칙이 있어서 차단 여부를 정하려면 요청한 앱을 알아야 하는가 */
        fun appRulesActive(): Boolean
        fun isBlocked(mask: Int, owner: AppOwner?): Boolean
        fun nxdomain(): Boolean
        fun logEnabled(): Boolean
        /** 시스템에 요청한 앱을 물어봄 (시스템 호출이라 느릴 수 있음) */
        fun lookupOwner(packet: UdpPacket): AppOwner?
        fun record(name: String, qtype: Int, blocked: Boolean, owner: AppOwner?)
        fun servers(): List<InetSocketAddress>
        fun cacheScope(): String
        /** DoH 주소. 꺼져 있으면 null */
        fun dohUrl(): String?
        fun onResolved(ms: Long, fromCache: Boolean) {}
        fun onFailure() {}
    }

    private class Waiter(val pkt: UdpPacket, val q: DnsQuery) {
        @Volatile var owner: AppOwner? = null
    }

    private class Pending(
        val id: Int,
        val key: String,
        val q: DnsQuery,
        val query: ByteArray,
        val scope: String,
        val created: Long,
        val servers: List<InetSocketAddress>,
    ) {
        val waiters = ArrayList<Waiter>(2)
        val sentAt = HashMap<InetSocketAddress, Long>(4)
        var attempts = 0
        var nextSendAt = Long.MAX_VALUE
        var deadline = Long.MAX_VALUE
        var viaDoh = false
        var tcp = false
        var truncated: ByteArray? = null
    }

    private val lock = Any()
    private val byId = HashMap<Int, Pending>()
    private val byKey = HashMap<String, Pending>()
    private val random = SecureRandom()
    private val rttMs = ConcurrentHashMap<InetSocketAddress, Double>()
    private val failures = ConcurrentHashMap<InetSocketAddress, Int>()

    @Volatile private var selector: Selector? = null
    private val channels = ArrayList<DatagramChannel>()
    private var ioThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var rebindRequested = false
    private var lastRebind = 0L
    /** I/O 스레드가 다음에 깨어날 예정 시각 (lock 안에서만 바꿈) */
    private var plannedWake = Long.MAX_VALUE

    private val ownerExec = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16), daemon("dns-owner"),
    )
    private val slowExec = ThreadPoolExecutor(
        0, 8, 30L, TimeUnit.SECONDS, SynchronousQueue(), daemon("dns-slow"),
    )
    private val dohSlots = Semaphore(DOH_CONNECTIONS)
    @Volatile private var dohDownUntil = 0L

    private val tokenLock = Any()
    private val tokenRate = ownerLookupsPerSecond.toDouble()
    private var tokens = tokenRate
    private var tokensAt = 0L

    // 진단용
    @Volatile var ownerLookups = 0L; private set
    @Volatile var upstreamSends = 0L; private set
    @Volatile var servfails = 0L; private set
    fun pendingCount(): Int = synchronized(lock) { byId.size }

    // ------------------------------------------------------------ 수명

    fun start() {
        if (running) return
        val sel = Selector.open()
        openChannels(sel)
        selector = sel
        running = true
        ioThread = Thread(::ioLoop, "dns-io").apply { isDaemon = true; start() }
    }

    /** 와이파이↔모바일 전환 등 기본 네트워크가 바뀌면 업스트림 소켓을 새로 열도록 요청 */
    fun requestRebind() {
        rebindRequested = true
        selector?.wakeup()
    }

    private fun openChannels(sel: Selector) {
        repeat(CHANNELS) {
            val ch = DatagramChannel.open()
            ch.configureBlocking(false)
            ch.bind(null) // 임의 포트
            ch.register(sel, SelectionKey.OP_READ)
            channels.add(ch)
        }
    }

    /** I/O 스레드에서만 호출. 진행 중인 질의는 다음 재전송 때 새 소켓으로 나감 */
    private fun rebindChannels(sel: Selector) {
        synchronized(lock) {
            channels.forEach { runCatching { it.close() } }
            channels.clear()
            runCatching { openChannels(sel) }
            for (p in byId.values) if (!p.viaDoh && !p.tcp) p.nextSendAt = minOf(p.nextSendAt, clock())
        }
    }

    fun stop() {
        running = false
        selector?.wakeup()
        ioThread?.join(1000)
        channels.forEach { runCatching { it.close() } }
        channels.clear()
        runCatching { selector?.close() }
        ownerExec.shutdownNow()
        slowExec.shutdownNow()
        synchronized(lock) { byId.clear(); byKey.clear() }
    }

    // ------------------------------------------------------------ 들어온 패킷

    /** 터널에서 읽은 IP 패킷 하나를 처리 (buf는 호출 뒤 재사용해도 됨) */
    fun onPacket(buf: ByteArray, len: Int) {
        val pkt = IpPackets.parseUdp(buf, len)
        if (pkt == null) {
            IpPackets.tcpResetFor(buf, len)?.let(host::writeToTun)
            return
        }
        if (pkt.dstPort != 53) return
        val q = DnsPackets.parse(pkt.payload) ?: return
        val now = clock()
        val mask = host.match(q.name)
        if (mask != 0) {
            // 앱별 규칙이 있을 때만 앱을 알아낸 뒤 판단. 그 외에는 기다리지 않고 바로 응답
            if (host.appRulesActive() && submitOwner { decide(pkt, q, mask, lookup(pkt), clock(), true) }) return
            decide(pkt, q, mask, null, now, false)
            return
        }
        resolve(pkt, q, null, now)
    }

    private fun decide(pkt: UdpPacket, q: DnsQuery, mask: Int, owner: AppOwner?, now: Long, ownerKnown: Boolean) {
        if (host.isBlocked(mask, owner)) {
            if (ownerKnown) {
                reply(pkt, DnsPackets.blockedResponse(pkt.payload, q, host.nxdomain()))
                host.record(q.name, q.qtype, true, owner)
            } else {
                replyNowRecordLater(pkt, q, DnsPackets.blockedResponse(pkt.payload, q, host.nxdomain()), true, now)
            }
        } else {
            resolve(pkt, q, owner, now)
        }
    }

    /**
     * 응답은 즉시 보내고, 통계용 앱 조회는 동시에 따로 진행 (응답을 절대 기다리게 하지 않음).
     * 조회를 응답보다 먼저 시작해 두어 대부분 앱을 찾을 수 있음.
     */
    private fun replyNowRecordLater(pkt: UdpPacket, q: DnsQuery, response: ByteArray, blocked: Boolean, now: Long) {
        val submitted = host.logEnabled() && takeToken(now) &&
            submitOwner { host.record(q.name, q.qtype, blocked, lookup(pkt)) }
        reply(pkt, response)
        if (!submitted) host.record(q.name, q.qtype, blocked, null)
    }

    private fun resolve(pkt: UdpPacket, q: DnsQuery, knownOwner: AppOwner?, now: Long) {
        val scope = host.cacheScope()
        val hit = cache.lookup(pkt.payload, q, scope, now)
        if (hit != null) {
            if (hit.stale) enqueue(null, q, pkt.payload, scope, now) // 뒤에서 새로 받아 둠
            host.onResolved(0, true)
            if (knownOwner != null) {
                reply(pkt, hit.response)
                host.record(q.name, q.qtype, false, knownOwner)
            } else {
                replyNowRecordLater(pkt, q, hit.response, false, now)
            }
            return
        }
        val w = Waiter(pkt, q).also { it.owner = knownOwner }
        if (!enqueue(w, q, pkt.payload, scope, now)) {
            servfails++
            reply(pkt, DnsPackets.errorResponse(pkt.payload, q, DnsPackets.RCODE_SERVFAIL))
            host.onFailure()
            return
        }
        // 업스트림 응답을 기다리는 동안 어느 앱인지 조회 (응답 시간에 더해지지 않음)
        if (knownOwner == null && host.logEnabled() && takeToken(now)) {
            submitOwner { w.owner = lookup(pkt) }
        }
    }

    /**
     * 업스트림 질의를 등록. 같은 질문이 이미 진행 중이면 그 응답을 함께 기다림(합치기).
     * w == null이면 캐시 갱신용(기다리는 앱 없음).
     */
    private fun enqueue(w: Waiter?, q: DnsQuery, query: ByteArray, scope: String, now: Long): Boolean {
        val key = DnsCache.key(q, scope)
        synchronized(lock) {
            val existing = byKey[key]
            if (existing != null) {
                if (w == null) return true
                if (existing.waiters.size >= MAX_WAITERS) return false
                existing.waiters.add(w)
                return true
            }
        }
        // 서버 목록은 시스템 호출이 있을 수 있어 lock 밖에서 가져옴
        val servers = orderServers(host.servers())
        if (servers.isEmpty()) return false
        val p: Pending
        synchronized(lock) {
            val existing = byKey[key]
            if (existing != null) { // 그 사이 누가 등록함
                if (w == null) return true
                if (existing.waiters.size >= MAX_WAITERS) return false
                existing.waiters.add(w)
                return true
            }
            if (byId.size >= MAX_PENDING) return false
            val id = newIdLocked()
            val upstreamQuery = query.copyOf().also { it[0] = (id shr 8).toByte(); it[1] = id.toByte() }
            p = Pending(id, key, q, upstreamQuery, scope, now, servers)
            if (w != null) p.waiters.add(w)
            p.deadline = now + deadlineMs
            byId[id] = p
            byKey[key] = p
        }
        begin(p, now)
        return true
    }

    private fun begin(p: Pending, now: Long) {
        val url = host.dohUrl()
        if (url != null && now >= dohDownUntil) {
            synchronized(lock) { p.viaDoh = true; p.deadline = now + DOH_DEADLINE_MS }
            try {
                slowExec.execute { dohTask(p, url) }
                wakeIfNeeded(p)
                return
            } catch (e: RejectedExecutionException) {
                synchronized(lock) { p.viaDoh = false; p.deadline = now + deadlineMs }
            }
        }
        synchronized(lock) {
            if (byId[p.id] !== p) return
            sendNextLocked(p, now)
        }
        wakeIfNeeded(p)
    }

    private fun wakeIfNeeded(p: Pending) {
        val wake = synchronized(lock) { eventOf(p) < plannedWake }
        if (wake) selector?.wakeup()
    }

    // ------------------------------------------------------------ 업스트림 송수신

    private fun sendNextLocked(p: Pending, now: Long) {
        if (channels.isEmpty()) { // 소켓을 못 연 상태: 1ms마다 헛돌지 않도록 다음 시도를 미룸
            p.nextSendAt = now + 500
            rebindRequested = true
            return
        }
        val server = p.servers[p.attempts % p.servers.size]
        val round = p.attempts / p.servers.size
        p.attempts++
        p.sentAt.putIfAbsent(server, now)
        try {
            channels[p.id % channels.size].send(ByteBuffer.wrap(p.query), server)
            upstreamSends++
        } catch (e: Exception) {
            failures.merge(server, 1, Int::plus)
        }
        p.nextSendAt = now + hedgeDelay(server) * (1 + round)
    }

    /** 서버 응답이 이 시간 안에 안 오면 다음 서버에도 보냄 */
    private fun hedgeDelay(s: InetSocketAddress): Long {
        val r = rttMs[s] ?: DEFAULT_RTT_MS
        return (r * 2.5).toLong().coerceIn(minHedgeMs, maxHedgeMs)
    }

    /** 빠르고 실패가 적은 서버부터 */
    private fun orderServers(list: List<InetSocketAddress>): List<InetSocketAddress> =
        list.map(::normalize).distinct().sortedBy { (rttMs[it] ?: DEFAULT_RTT_MS) + (failures[it] ?: 0) * 300.0 }

    private fun ioLoop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocate(65535)
        while (running) {
            try {
                if (rebindRequested && clock() - lastRebind >= 500) {
                    rebindRequested = false
                    lastRebind = clock()
                    rebindChannels(sel)
                }
                val now = clock()
                val wake = synchronized(lock) {
                    val w = nextEventLocked()
                    plannedWake = w
                    w
                }
                var timeout = if (wake == Long.MAX_VALUE) IDLE_TIMEOUT_MS else (wake - now).coerceIn(1L, 1000L)
                if (rebindRequested) timeout = minOf(timeout, 500L)
                sel.select(timeout)
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    val ch = k.channel() as DatagramChannel
                    while (true) {
                        buf.clear()
                        val from = ch.receive(buf) as? InetSocketAddress ?: break
                        buf.flip()
                        val data = ByteArray(buf.remaining())
                        buf.get(data)
                        onUpstream(normalize(from), data)
                    }
                }
                onTimers(clock())
            } catch (e: ClosedSelectorException) {
                return
            } catch (e: Exception) {
                if (!running) return
                // 예상치 못한 오류가 반복돼도 CPU를 태우며 헛돌지 않도록 잠깐 쉼
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun onUpstream(from: InetSocketAddress, data: ByteArray) {
        if (data.size < 12 || data[2].toInt() and 0x80 == 0) return
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val now = clock()
        val p: Pending
        var tcpServer: InetSocketAddress? = null
        synchronized(lock) {
            p = byId[id] ?: return // 이미 끝난 질의의 늦은 응답 등
            val sent = p.sentAt[from] ?: return // 보낸 적 없는 곳에서 온 응답은 무시 (위조 방지)
            if (!sameQuestion(data, p.q)) return
            rttMs.merge(from, (now - sent).toDouble()) { old, v -> old * 0.7 + v * 0.3 }
            failures.remove(from)
            if (data[2].toInt() and 0x02 != 0) { // 잘린 응답 → 같은 서버에 TCP로 다시
                if (p.tcp) return
                p.tcp = true
                p.truncated = data
                p.nextSendAt = Long.MAX_VALUE
                p.deadline = now + TCP_DEADLINE_MS
                tcpServer = from
            } else {
                removeLocked(p)
            }
        }
        val s = tcpServer
        if (s != null) {
            try {
                slowExec.execute { tcpTask(p, s) }
            } catch (e: RejectedExecutionException) {
                synchronized(lock) { removeLocked(p) }
                complete(p, data)
            }
            return
        }
        complete(p, data)
    }

    private fun onTimers(now: Long) {
        var expired: ArrayList<Pending>? = null
        synchronized(lock) {
            for (p in byId.values) {
                if (now >= p.deadline) {
                    (expired ?: ArrayList<Pending>().also { expired = it }).add(p)
                } else if (!p.viaDoh && !p.tcp && now >= p.nextSendAt) {
                    sendNextLocked(p, now)
                }
            }
            expired?.forEach { removeLocked(it) }
        }
        expired?.forEach { fail(it) }
    }

    private fun nextEventLocked(): Long {
        var t = Long.MAX_VALUE
        for (p in byId.values) t = minOf(t, eventOf(p))
        return t
    }

    private fun eventOf(p: Pending): Long =
        if (p.viaDoh || p.tcp) p.deadline else minOf(p.nextSendAt, p.deadline)

    // ------------------------------------------------------------ 완료·실패

    private fun complete(p: Pending, resp: ByteArray) {
        val now = clock()
        cache.put(p.q, resp, p.scope, now)
        if (p.waiters.isEmpty()) return
        host.onResolved(now - p.created, false)
        for (w in p.waiters) {
            reply(w.pkt, DnsCache.withId(resp, w.pkt.payload))
            host.record(w.q.name, w.q.qtype, false, w.owner)
        }
    }

    private fun fail(p: Pending) {
        p.sentAt.keys.forEach { failures.merge(it, 1, Int::plus) }
        if (p.waiters.isEmpty()) return
        servfails++
        host.onFailure()
        for (w in p.waiters) {
            reply(w.pkt, DnsPackets.errorResponse(w.pkt.payload, w.q, DnsPackets.RCODE_SERVFAIL))
        }
    }

    private fun removeLocked(p: Pending) {
        byId.remove(p.id)
        if (byKey[p.key] === p) byKey.remove(p.key)
    }

    private fun newIdLocked(): Int {
        while (true) {
            val id = random.nextInt(65536)
            if (!byId.containsKey(id)) return id
        }
    }

    private fun reply(pkt: UdpPacket, response: ByteArray) =
        host.writeToTun(IpPackets.buildUdp(pkt.dstIp, pkt.srcIp, pkt.dstPort, pkt.srcPort, response))

    // ------------------------------------------------------------ DoH / TCP

    private fun dohTask(p: Pending, url: String) {
        val r = dohQuery(url, p.query)
        if (r != null && r.size >= 12 && r[2].toInt() and 0x80 != 0 && sameQuestion(r, p.q)) {
            synchronized(lock) {
                if (byId[p.id] !== p) return
                removeLocked(p)
            }
            complete(p, r)
            return
        }
        // DoH 실패: 1분간 쉬고 이 질의는 일반 DNS로 이어서 처리
        dohDownUntil = clock() + DOH_BACKOFF_MS
        val now = clock()
        synchronized(lock) {
            if (byId[p.id] !== p) return
            p.viaDoh = false
            p.deadline = now + deadlineMs
            sendNextLocked(p, now)
        }
        wakeIfNeeded(p)
    }

    private fun dohQuery(url: String, query: ByteArray): ByteArray? {
        if (!dohSlots.tryAcquire(1500, TimeUnit.MILLISECONDS)) return null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = 2000
                readTimeout = 2500
                setFixedLengthStreamingMode(query.size)
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode != 200) {
                conn.errorStream?.close()
                return null
            }
            // 끝까지 읽고 닫아야 HTTPS 연결이 재사용됨
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(512)
                input.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            return null
        } finally {
            dohSlots.release()
        }
    }

    private fun tcpTask(p: Pending, server: InetSocketAddress) {
        val r = try {
            Socket().use { s ->
                s.connect(server, 2000)
                s.soTimeout = 3000
                val out = DataOutputStream(s.getOutputStream())
                out.writeShort(p.query.size)
                out.write(p.query)
                out.flush()
                val input = DataInputStream(s.getInputStream())
                ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            }
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            if (byId[p.id] !== p) return
            removeLocked(p)
        }
        val ok = r != null && r.size >= 12 && sameQuestion(r, p.q)
        complete(p, if (ok) r!! else p.truncated!!)
    }

    // ------------------------------------------------------------ 앱 조회

    private fun lookup(pkt: UdpPacket): AppOwner? {
        ownerLookups++
        return try { host.lookupOwner(pkt) } catch (e: Exception) { null }
    }

    private fun submitOwner(task: () -> Unit): Boolean = try {
        ownerExec.execute(task)
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /** 앱 조회는 시스템에 부담이 있어 초당 횟수를 제한 */
    private fun takeToken(now: Long): Boolean = synchronized(tokenLock) {
        if (tokensAt == 0L) tokensAt = now
        if (now > tokensAt) {
            tokens = minOf(tokenRate, tokens + (now - tokensAt) * tokenRate / 1000.0)
            tokensAt = now
        }
        if (tokens >= 1.0) { tokens -= 1.0; true } else false
    }

    companion object {
        private const val CHANNELS = 4
        private const val MAX_PENDING = 4096
        private const val MAX_WAITERS = 256
        private const val DEFAULT_RTT_MS = 120.0
        private const val DOH_CONNECTIONS = 4
        private const val DOH_DEADLINE_MS = 5000L
        private const val DOH_BACKOFF_MS = 60_000L
        private const val TCP_DEADLINE_MS = 6000L
        /** 할 일이 없을 때도 이 간격으로만 깨어남 (안전장치) */
        private const val IDLE_TIMEOUT_MS = 30_000L

        private fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        /**
         * ::ffff:1.2.3.4 형태(IPv4 매핑 주소)만 1.2.3.4로 바꿔 비교가 정확하도록 함.
         * 다른 IPv6 주소는 그대로 둠 (fe80::1%wlan0 같은 링크 로컬 주소의 범위 정보 유지)
         */
        fun normalize(a: InetSocketAddress): InetSocketAddress {
            val addr = a.address as? Inet6Address ?: return a
            val b = addr.address
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
            return if (mapped) InetSocketAddress(InetAddress.getByAddress(b.copyOfRange(12, 16)), a.port) else a
        }

        private fun sameQuestion(resp: ByteArray, q: DnsQuery): Boolean {
            val rq = DnsPackets.question(resp) ?: return false
            return rq.qtype == q.qtype && rq.name == q.name
        }
    }
}
