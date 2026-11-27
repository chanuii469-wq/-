package com.adshield.android

import com.adshield.android.dns.DnsPackets
import com.adshield.android.dns.IpPackets
import com.adshield.android.filter.Blocklist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterAndDnsTest {

    private fun list(vararg lines: String): Blocklist {
        val b = HashSet<String>(); val a = HashSet<String>()
        lines.forEach { Blocklist.parseLine(it, b, a) }
        return Blocklist(b, a)
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
}
