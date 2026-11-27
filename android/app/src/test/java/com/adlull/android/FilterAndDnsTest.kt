package com.adlull.android

import com.adlull.android.dns.DnsPackets
import com.adlull.android.dns.IpPackets
import com.adlull.android.filter.Blocklist
import com.adlull.android.filter.UserRule
import com.adlull.android.filter.UserRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.adlull.android.dns.DnsCache
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class FilterAndDnsTest {

    private fun list(vararg lines: String): Blocklist {
        val b = Blocklist.Builder()
        lines.forEach { Blocklist.parseLine(it, b) }
        return b.build()
    }

    @Test fun parsesCommonFormats() {
        val bl = list(
            "0.0.0.0 ads.example.com",
            "127.0.0.1 tracker.net # comment",
            "||adnet.io^",
            "||important.io^\$important",
            "||client.io^\$client=192.168.0.1",
            "plain-domain.org",
            "*.wild.com",
            "! comment",
            "# comment",
            "example.com##.banner",
            "0.0.0.0 localhost",
            "@@||ok.adnet.io^",
        )
        assertTrue(bl.isBlocked("ads.example.com"))
        assertTrue(bl.isBlocked("sub.ads.example.com"))
        assertFalse(bl.isBlocked("example.com"))
        assertTrue(bl.isBlocked("tracker.net"))
        assertTrue(bl.isBlocked("x.adnet.io"))
        assertFalse(bl.isBlocked("ok.adnet.io"))
        assertFalse(bl.isBlocked("deep.ok.adnet.io"))
        assertTrue(bl.isBlocked("important.io"))
        assertFalse(bl.isBlocked("client.io"))
        assertTrue(bl.isBlocked("plain-domain.org"))
        assertTrue(bl.isBlocked("a.wild.com"))
        assertFalse(bl.isBlocked("localhost"))
        assertTrue(bl.isBlocked("ADS.EXAMPLE.COM."))
    }

    private fun dnsQuery(name: String, type: Int): ByteArray {
        val q = ArrayList<Byte>()
        listOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0).forEach { q += it.toByte() }
        name.split('.').forEach { l -> q += l.length.toByte(); l.forEach { q += it.code.toByte() } }
        q += 0; q += (type shr 8).toByte(); q += type.toByte(); q += 0; q += 1
        return q.toByteArray()
    }

    @Test fun dnsParseAndBlockA() {
        val msg = dnsQuery("ads.example.com", DnsPackets.TYPE_A)
        val q = DnsPackets.parse(msg)!!
        assertEquals("ads.example.com", q.name)
        assertEquals(1, q.qtype)
        val r = DnsPackets.blockedResponse(msg, q, nxdomain = false)
        assertEquals(0x12.toByte(), r[0]); assertEquals(0x34.toByte(), r[1])
        assertEquals(0x81.toByte(), r[2]); assertEquals(0x80.toByte(), r[3])
        assertEquals(1, r[7].toInt()) // ANCOUNT
        assertEquals(msg.size + 16, r.size)
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
    }

    @Test fun dnsBlockAaaaAndNx() {
        val msg = dnsQuery("x.io", DnsPackets.TYPE_AAAA)
        val q = DnsPackets.parse(msg)!!
        assertEquals(msg.size + 28, DnsPackets.blockedResponse(msg, q, false).size)
        val nx = DnsPackets.blockedResponse(msg, q, true)
        assertEquals(0x83.toByte(), nx[3])
        assertEquals(0, nx[7].toInt())
        assertNull(DnsPackets.parse(nx)) // 응답은 질의로 해석하지 않음
    }

    @Test fun ipUdpRoundTrip() {
        val payload = dnsQuery("a.b", 1)
        val src = byteArrayOf(10, 111, 222.toByte(), 2)
        val dst = byteArrayOf(10, 111, 222.toByte(), 1)
        val pkt = IpPackets.buildUdp(src, dst, 40000, 53, payload)
        val p = IpPackets.parseUdp(pkt, pkt.size)
        assertNotNull(p)
        assertArrayEquals(src, p!!.srcIp); assertArrayEquals(dst, p.dstIp)
        assertEquals(40000, p.srcPort); assertEquals(53, p.dstPort)
        assertArrayEquals(payload, p.payload)
        // IP 헤더 체크섬 검증: 전체 합이 0xFFFF
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test fun categoriesCombine() {
        val b = Blocklist.Builder()
        Blocklist.parseLine("ads.com", b, Blocklist.ADS)
        Blocklist.parseLine("t.ads.com", b, Blocklist.TRACKERS)
        Blocklist.parseLine("tracker.io", b, Blocklist.TRACKERS)
        val bl = b.build()
        assertEquals(Blocklist.ADS, bl.match("x.ads.com"))
        assertEquals(Blocklist.ADS or Blocklist.TRACKERS, bl.match("t.ads.com"))
        assertEquals(Blocklist.TRACKERS, bl.match("tracker.io"))
        assertEquals(0, bl.match("example.org"))
    }

    @Test fun userRulesToggleRoundTrip() {
        val text = "||ads.com^\n!- @@||ok.com^\n! plain comment\n\n"
        val rules = UserRules.parse(text)
        assertEquals(listOf(UserRule("||ads.com^", true), UserRule("@@||ok.com^", false)), rules)
        assertEquals(listOf("||ads.com^"), UserRules.activeLines(text))
        val toggled = rules.map { it.copy(enabled = !it.enabled) }
        assertEquals("!- ||ads.com^\n@@||ok.com^\n", UserRules.format(toggled))
        assertEquals("ok.com", toggled[1].domain)
        assertTrue(toggled[1].allow)
    }

    @Test fun normalizeInput() {
        assertEquals("||ads.com^", UserRules.normalize("https://Ads.com/path", false))
        assertEquals("@@||ok.com^", UserRules.normalize("ok.com", true))
        assertEquals("@@||ok.com^", UserRules.normalize("@@||ok.com^", false))
        assertNull(UserRules.normalize("not a domain", false))
    }

    @Test fun sizeCountsUniqueAcrossCategories() {
        val b = Blocklist.Builder()
        Blocklist.parseLine("a.com", b, Blocklist.ADS)
        Blocklist.parseLine("a.com", b, Blocklist.TRACKERS)
        Blocklist.parseLine("a.com", b, Blocklist.ADS)
        Blocklist.parseLine("b.com", b, Blocklist.USER)
        assertEquals(2, b.build().size)
    }

    @Test fun serializeRoundTrip() {
        val b = Blocklist.Builder()
        listOf("ads.com", "x.net").forEach { Blocklist.parseLine(it, b) }
        Blocklist.parseLine("tr.io", b, Blocklist.TRACKERS)
        Blocklist.parseLine("@@||ok.ads.com^", b)
        val bl = b.build()
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { o -> bl.writeTo(o) } }.toByteArray()
        val back = Blocklist.readFrom(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(bl.size, back.size)
        assertEquals(Blocklist.ADS, back.match("m.ads.com"))
        assertEquals(Blocklist.TRACKERS, back.match("tr.io"))
        assertEquals(0, back.match("ok.ads.com"))
    }

    private fun answer(query: ByteArray, ttl: Int, rcode: Int = 0, an: Int = 1): ByteArray {
        val r = query.copyOf(query.size + if (an > 0) 16 else 0)
        r[2] = 0x81.toByte(); r[3] = (0x80 or rcode).toByte(); r[7] = an.toByte()
        if (an > 0) {
            val p = query.size
            r[p] = 0xC0.toByte(); r[p + 1] = 0x0C; r[p + 3] = 1; r[p + 5] = 1
            r[p + 6] = (ttl shr 24).toByte(); r[p + 7] = (ttl shr 16).toByte(); r[p + 8] = (ttl shr 8).toByte(); r[p + 9] = ttl.toByte()
            r[p + 11] = 4; r[p + 12] = 93
        }
        return r
    }

    @Test fun dnsCacheTtl() {
        val q = dnsQuery("a.com", 1)
        assertEquals(300L, DnsCache.cacheTtl(answer(q, 300)))
        assertEquals(3600L, DnsCache.cacheTtl(answer(q, 999_999)))
        assertEquals(60L, DnsCache.cacheTtl(answer(q, 0, rcode = 3, an = 0)))
        assertNull(DnsCache.cacheTtl(answer(q, 300, rcode = 2)))
        val tc = answer(q, 300); tc[2] = (tc[2].toInt() or 0x02).toByte()
        assertNull(DnsCache.cacheTtl(tc))
    }

    @Test fun dnsCacheHitRewritesIdAndExpires() {
        val cache = DnsCache()
        val q1 = dnsQuery("a.com", 1)
        val parsed = DnsPackets.parse(q1)!!
        cache.put(parsed, answer(q1, 10), "s", now = 0)
        val q2 = q1.copyOf(); q2[0] = 0x55; q2[1] = 0x66
        val hit = cache.get(q2, parsed, "s", now = 5_000)!!
        assertEquals(0x55.toByte(), hit[0]); assertEquals(0x66.toByte(), hit[1])
        assertNull(cache.get(q2, parsed, "other", now = 5_000))
        // TTL을 남은 시간(5초)으로 고쳐 씀
        assertEquals(5, hit[hit.size - 7].toInt())
        // 만료 후에도 하루 동안은 TTL 10초로 바로 응답하고 갱신 필요로 표시 (낙관적 캐시)
        val stale = cache.lookup(q2, parsed, "s", now = 11_000)!!
        assertTrue(stale.stale)
        assertEquals(10, stale.response[stale.response.size - 7].toInt())
        assertNull(cache.get(q2, parsed, "s", now = 11_000 + 2 * 3600 * 1000L))
    }

    /** 30만 도메인 컴파일 시간·조회 속도 확인 (실패 기준은 넉넉하게) */
    @Test fun benchmarkLargeList() {
        val lines = (0 until 300_000).map { "0.0.0.0 host$it.ads-network${it % 977}.com" }
        val t0 = System.nanoTime()
        val b = Blocklist.Builder()
        lines.forEach { Blocklist.parseLine(it, b) }
        val bl = b.build()
        val compileMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        var hits = 0
        repeat(100_000) { if (bl.isBlocked("cdn.host$it.ads-network${it % 977}.com")) hits++ }
        val lookupUs = (System.nanoTime() - t1) / 1000.0 / 100_000
        println("BENCH compile=${compileMs}ms lookup=${"%.2f".format(lookupUs)}us size=${bl.size}")
        assertEquals(300_000, bl.size)
        assertEquals(100_000, hits)
        assertTrue(compileMs < 20_000)
    }

    @Test fun tcpSynGetsReset() {
        val syn = ByteArray(40)
        syn[0] = 0x45; syn[3] = 40; syn[9] = 6
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(syn, 12)
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(syn, 16)
        syn[20] = 0x9C.toByte(); syn[21] = 0x40; syn[23] = 53 // 40000 -> 53
        syn[24] = 0; syn[25] = 0; syn[26] = 0; syn[27] = 9 // seq=9
        syn[32] = 0x50; syn[33] = 0x02 // SYN
        val rst = IpPackets.tcpResetFor(syn, 40)!!
        assertArrayEquals(syn.copyOfRange(16, 20), rst.copyOfRange(12, 16))
        assertEquals(53, ((rst[20].toInt() and 0xFF) shl 8) or (rst[21].toInt() and 0xFF))
        assertEquals(10, rst[31].toInt()) // ack = seq + 1
        assertEquals(0x14, rst[33].toInt()) // RST|ACK
        assertNull(IpPackets.tcpResetFor(rst, 40)) // RST에는 응답 안 함
    }
}
