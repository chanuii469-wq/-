package com.adlull.android

import com.adlull.android.dns.AppOwner
import com.adlull.android.dns.DnsEngine
import com.adlull.android.dns.DnsPackets
import com.adlull.android.dns.IpPackets
import com.adlull.android.dns.UdpPacket
import com.adlull.android.filter.Blocklist
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DnsEngine을 실제 소켓으로 끝까지 돌려 보는 테스트.
 * 터널 대신 큐를 쓰고, 업스트림 DNS는 localhost에 띄운 가짜 서버를 쓴다.
 */
class DnsEngineTest {
    private val clientIp = byteArrayOf(10, 111, 222.toByte(), 2)
    private val dnsIp = byteArrayOf(10, 111, 222.toByte(), 1)
    private val closeables = ArrayList<AutoCloseable>()
    private val engines = ArrayList<DnsEngine>()

    @After fun tearDown() {
        engines.forEach { it.stop() }
        closeables.forEach { runCatching { it.close() } }
    }

    // ---------------------------------------------------------------- 도구

    /** 가짜 업스트림 DNS 서버 */
    private class FakeDns(
        val delayMs: Long = 0,
        val silent: Boolean = false,
        val wrongQuestion: Boolean = false,
        val nxdomain: Boolean = false,
    ) : AutoCloseable {
        val sock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = AtomicInteger()
        private val sched = Executors.newSingleThreadScheduledExecutor()
        init {
            Thread {
                val buf = ByteArray(4096)
                while (!sock.isClosed) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        received.incrementAndGet()
                        if (silent) continue
                        val q = buf.copyOf(p.length)
                        val resp = if (nxdomain) nxdomainFor(q) else answerFor(q, if (wrongQuestion) "evil.example" else null)
                        val to = p.socketAddress
                        if (delayMs <= 0) sock.send(DatagramPacket(resp, resp.size, to))
                        else sched.schedule({ runCatching { sock.send(DatagramPacket(resp, resp.size, to)) } }, delayMs, TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        val address get() = InetSocketAddress(InetAddress.getLoopbackAddress(), sock.localPort)
        override fun close() { sock.close(); sched.shutdownNow() }
    }

    private class FakeHost(
        val upstreams: List<InetSocketAddress>,
        val blocked: Set<String> = emptySet(),
        val appRules: Boolean = false,
        val lookupDelayMs: Long = 2,
        val race: List<InetSocketAddress> = emptyList(),
    ) : DnsEngine.Host {
        val out = LinkedBlockingQueue<ByteArray>()
        val ownerCalls = AtomicInteger()
        val failures = AtomicInteger()
        override fun writeToTun(packet: ByteArray) { out.add(packet) }
        override fun match(name: String) = if (name in blocked) Blocklist.ADS else 0
        override fun appRulesActive() = appRules
        override fun isBlocked(mask: Int, owner: AppOwner?) = mask != 0
        override fun nxdomain() = false
        override fun logEnabled() = true
        override fun lookupOwner(packet: UdpPacket): AppOwner? {
            ownerCalls.incrementAndGet()
            Thread.sleep(lookupDelayMs) // 시스템 호출 비용 흉내
            return AppOwner("some.app", "앱")
        }
        override fun record(name: String, qtype: Int, blocked: Boolean, owner: AppOwner?) {}
        override fun servers() = upstreams
        override fun raceServers() = race
        override fun cacheScope() = "test"
        override fun dohUrl(): String? = null
        override fun onFailure() { failures.incrementAndGet() }
    }

    private fun fakeDns(delayMs: Long = 0, silent: Boolean = false, wrongQuestion: Boolean = false, nxdomain: Boolean = false) =
        FakeDns(delayMs, silent, wrongQuestion, nxdomain).also { closeables.add(it) }

    private fun engine(host: FakeHost, deadlineMs: Long = 3000) =
        DnsEngine(host, deadlineMs = deadlineMs).also { it.start(); engines.add(it) }

    private fun send(e: DnsEngine, id: Int, name: String, port: Int, type: Int = 1) {
        val pkt = IpPackets.buildUdp(clientIp, dnsIp, port, 53, query(id, name, type))
        e.onPacket(pkt, pkt.size)
    }

    private fun take(h: FakeHost, timeoutMs: Long = 2000): UdpPacket? =
        h.out.poll(timeoutMs, TimeUnit.MILLISECONDS)?.let { IpPackets.parseUdp(it, it.size) }

    private fun idOf(m: ByteArray) = ((m[0].toInt() and 0xFF) shl 8) or (m[1].toInt() and 0xFF)
    private fun rcodeOf(m: ByteArray) = m[3].toInt() and 0x0F

    // ---------------------------------------------------------------- 기본 동작

    @Test fun forwardsQueryAndRestoresIdAndPorts() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address))
        val e = engine(h)
        send(e, 0x1234, "example.com", 40000)
        val r = take(h)
        assertNotNull(r)
        assertEquals(40000, r!!.dstPort)
        assertEquals(53, r.srcPort)
        assertArrayEquals(clientIp, r.dstIp)
        assertArrayEquals(dnsIp, r.srcIp)
        assertEquals(0x1234, idOf(r.payload))
        assertEquals(0, rcodeOf(r.payload))
        assertEquals(1, up.received.get())
    }

    @Test fun secondQueryIsServedFromCache() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address))
        val e = engine(h)
        send(e, 1, "cached.example", 40001)
        assertNotNull(take(h))
        val t0 = System.nanoTime()
        send(e, 2, "cached.example", 40002)
        val r = take(h)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(2, idOf(r.payload))
        assertEquals(1, up.received.get()) // 업스트림에 다시 묻지 않음
        assertTrue("cache took ${ms}ms", ms < 50)
    }

    @Test fun coalescesIdenticalConcurrentQueries() {
        val up = fakeDns(delayMs = 200)
        val h = FakeHost(listOf(up.address))
        val e = engine(h)
        val sent = (0 until 50).associate { (41000 + it) to (100 + it) }
        sent.forEach { (port, id) -> send(e, id, "same.example", port) }
        val got = HashMap<Int, Int>()
        repeat(50) { val r = take(h)!!; got[r.dstPort] = idOf(r.payload) }
        assertEquals(sent, got) // 모두 자기 ID로 응답받음
        assertEquals(1, up.received.get()) // 업스트림에는 한 번만
    }

    @Test fun hedgesToNextServerWhenFirstIsSilent() {
        val silent = fakeDns(silent = true)
        val good = fakeDns()
        val h = FakeHost(listOf(silent.address, good.address))
        val e = engine(h)
        val t0 = System.nanoTime()
        send(e, 7, "hedge.example", 42000)
        val r = take(h)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(0, rcodeOf(r.payload))
        assertEquals(1, good.received.get())
        assertTrue("hedge took ${ms}ms", ms < 700)
    }

    @Test fun answersServfailInsteadOfDropping() {
        val silent = fakeDns(silent = true)
        val h = FakeHost(listOf(silent.address))
        val e = engine(h, deadlineMs = 800)
        val t0 = System.nanoTime()
        send(e, 9, "dead.example", 43000)
        val r = take(h, 3000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("must answer instead of silently dropping", r)
        assertEquals(DnsPackets.RCODE_SERVFAIL, rcodeOf(r!!.payload))
        assertEquals(9, idOf(r.payload))
        assertTrue("servfail after ${ms}ms", ms in 700..2000)
        assertTrue("retried the silent server", silent.received.get() >= 2)
    }

    @Test fun ignoresResponseForDifferentQuestion() {
        val liar = fakeDns(wrongQuestion = true)
        val h = FakeHost(listOf(liar.address))
        val e = engine(h, deadlineMs = 600)
        send(e, 11, "real.example", 44000)
        val r = take(h, 3000)!!
        assertEquals(DnsPackets.RCODE_SERVFAIL, rcodeOf(r.payload)) // 엉뚱한 응답은 받아들이지 않음
    }

    @Test fun blockedNameIsAnsweredWithoutUpstream() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address), blocked = setOf("ads.example"))
        val e = engine(h)
        send(e, 5, "ads.example", 45000)
        val r = take(h)!!
        assertEquals(5, idOf(r.payload))
        assertArrayEquals(ByteArray(4), r.payload.copyOfRange(r.payload.size - 4, r.payload.size)) // 0.0.0.0
        assertEquals(0, up.received.get())
    }

    @Test fun ownerLookupsAreRateLimitedButEveryQueryIsAnswered() {
        val up = fakeDns()
        val names = (0 until 300).map { "ad$it.example" }.toSet()
        val h = FakeHost(listOf(up.address), blocked = names)
        val e = engine(h)
        names.forEachIndexed { i, n -> send(e, i, n, 46000 + i) }
        repeat(300) { assertNotNull("answer #$it", take(h)) }
        assertTrue("owner lookups ${h.ownerCalls.get()}", h.ownerCalls.get() <= 45)
    }

    @Test fun appRulesForceOwnerLookupForBlockedNames() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address), blocked = setOf("ads.example"), appRules = true)
        val e = engine(h)
        send(e, 1, "ads.example", 47000)
        assertNotNull(take(h))
        assertEquals(1, h.ownerCalls.get())
    }

    @Test fun keepsWorkingAfterNetworkChangeRebind() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address))
        val e = engine(h)
        send(e, 1, "before.example", 48000)
        assertEquals(0, rcodeOf(take(h)!!.payload))
        e.requestRebind() // 와이파이↔모바일 전환 흉내
        Thread.sleep(100)
        send(e, 2, "after.example", 48001)
        val r = take(h)!!
        assertEquals(2, idOf(r.payload))
        assertEquals(0, rcodeOf(r.payload))
    }

    /** 앱 조회가 느려도 차단 응답과 캐시 응답은 기다리지 않고 바로 나가야 함 */
    @Test fun slowAppLookupNeverDelaysBlockedOrCachedAnswers() {
        val up = fakeDns()
        val h = FakeHost(listOf(up.address), blocked = setOf("ads.example"), lookupDelayMs = 300)
        val e = engine(h)
        var t0 = System.nanoTime()
        send(e, 1, "ads.example", 49000)
        assertNotNull(take(h))
        val blockedMs = (System.nanoTime() - t0) / 1_000_000
        send(e, 2, "site.example", 49001)
        assertNotNull(take(h)) // 업스트림으로 한 번 받아 캐시에 저장
        Thread.sleep(400)
        t0 = System.nanoTime()
        send(e, 3, "site.example", 49002)
        assertNotNull(take(h))
        val cachedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("blocked ${blockedMs}ms", blockedMs < 50)
        assertTrue("cached ${cachedMs}ms", cachedMs < 50)
    }

    // ---------------------------------------------------------------- 자동 모드(경쟁)

    @Test fun raceUsesFasterPublicServer() {
        val slowPrimary = fakeDns(delayMs = 300)
        val fastPublic = fakeDns()
        val h = FakeHost(listOf(slowPrimary.address), race = listOf(fastPublic.address))
        val e = engine(h)
        val t0 = System.nanoTime()
        send(e, 1, "race.example", 50000)
        val r = take(h)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(0, rcodeOf(r.payload))
        assertEquals(1, fastPublic.received.get()) // 처음부터 동시에 보냄
        assertEquals(1, slowPrimary.received.get())
        assertTrue("race took ${ms}ms", ms < 150)
    }

    @Test fun publicNxdomainWaitsForPrimaryAnswer() {
        val primary = fakeDns(delayMs = 100) // 공유기 DNS만 아는 내부 이름이라고 가정
        val public = fakeDns(nxdomain = true)
        val h = FakeHost(listOf(primary.address), race = listOf(public.address))
        val e = engine(h)
        send(e, 2, "router.local.example", 50001)
        val r = take(h)!!
        assertEquals(0, rcodeOf(r.payload)) // 공용 DNS의 '없음'보다 기본 DNS의 답을 씀
    }

    @Test fun publicNxdomainIsUsedWhenPrimaryNeverAnswers() {
        val primary = fakeDns(silent = true)
        val public = fakeDns(nxdomain = true)
        val h = FakeHost(listOf(primary.address), race = listOf(public.address))
        val e = engine(h)
        val t0 = System.nanoTime()
        send(e, 3, "nothing.example", 50002)
        val r = take(h, 3000)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(3, rcodeOf(r.payload)) // NXDOMAIN
        assertTrue("held ${ms}ms", ms in 300..1000)
    }

    @Test fun cacheSurvivesSaveAndLoad() {
        val c1 = com.adlull.android.dns.DnsCache()
        val q = query(1, "saved.example")
        val parsed = DnsPackets.parse(q)!!
        c1.put(parsed, answerFor(q), "s")
        val bytes = java.io.ByteArrayOutputStream().also { c1.save(java.io.DataOutputStream(it)) }.toByteArray()
        val c2 = com.adlull.android.dns.DnsCache()
        assertEquals(1, c2.load(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))))
        val hit = c2.get(query(7, "saved.example"), parsed, "s")
        assertNotNull(hit)
        assertEquals(7, idOf(hit!!))
    }

    // ---------------------------------------------------------------- 부하

    /** 2만 개 서로 다른 도메인을 업스트림 10ms 지연으로 처리할 때의 CPU·스레드 */
    @Test fun loadTwentyThousandQueries() {
        val up = fakeDns(delayMs = 10)
        val h = FakeHost(listOf(up.address))
        val e = engine(h)
        val cpu0 = processCpuNanos()
        val t0 = System.nanoTime()
        val total = 20_000
        var answered = 0
        var maxThreads = 0
        var i = 0
        while (i < total) {
            val batch = minOf(200, total - i)
            repeat(batch) { send(e, i and 0xFFFF, "host$i.load.example", 10000 + (i % 50000)); i++ }
            repeat(batch) {
                val r = take(h, 3000)
                assertNotNull("answer missing at $answered", r)
                assertEquals(0, rcodeOf(r!!.payload))
                answered++
            }
            maxThreads = maxOf(maxThreads, Thread.getAllStackTraces().keys.count { it.name.startsWith("dns-") })
        }
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        val cpuUsPerQuery = (processCpuNanos() - cpu0) / 1000.0 / total
        println("BENCH engine: $total queries in ${wallMs}ms, cpu ${"%.1f".format(cpuUsPerQuery)}us/query " +
            "(incl. fake server+test), engine threads max $maxThreads, upstream sends ${e.upstreamSends}")
        assertEquals(total, answered)
        assertTrue("engine threads $maxThreads", maxThreads <= 4)
        assertEquals(0, h.failures.get())
    }

    /** 테스트 JVM 전체의 CPU 사용 시간(ns). 안드로이드 API로 컴파일되므로 리플렉션으로 읽음 */
    private fun processCpuNanos(): Long {
        val mf = Class.forName("java.lang.management.ManagementFactory")
        val bean = mf.getMethod("getOperatingSystemMXBean").invoke(null)
        val iface = Class.forName("com.sun.management.OperatingSystemMXBean")
        return iface.getMethod("getProcessCpuTime").invoke(bean) as Long
    }

    companion object {
        fun query(id: Int, name: String, type: Int = 1): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
            name.split('.').forEach { l -> out.write(l.length); out.write(l.toByteArray()) }
            out.write(0)
            out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
            return out.toByteArray()
        }

        fun nxdomainFor(q: ByteArray): ByteArray {
            val parsed = DnsPackets.question(q)!!
            val r = q.copyOf(parsed.questionEnd)
            r[2] = 0x81.toByte(); r[3] = 0x83.toByte() // NXDOMAIN
            for (i in 6 until 12) r[i] = 0
            return r
        }

        /** 질의에 A 레코드(TTL 300) 하나로 답함. overrideName이면 다른 질문으로 답함(위조 흉내) */
        fun answerFor(q: ByteArray, overrideName: String? = null): ByteArray {
            val id = ((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF)
            val parsed = DnsPackets.question(q)!!
            val base = if (overrideName != null) query(id, overrideName, parsed.qtype) else q.copyOf(parsed.questionEnd)
            val r = base.copyOf(base.size + 16)
            r[2] = 0x81.toByte(); r[3] = 0x80.toByte(); r[7] = 1
            val p = base.size
            r[p] = 0xC0.toByte(); r[p + 1] = 0x0C; r[p + 3] = 1; r[p + 5] = 1
            r[p + 8] = 0x01; r[p + 9] = 0x2C // TTL 300
            r[p + 11] = 4
            r[p + 12] = 93; r[p + 13] = 184.toByte(); r[p + 14] = 216.toByte(); r[p + 15] = 34
            return r
        }
    }
}
