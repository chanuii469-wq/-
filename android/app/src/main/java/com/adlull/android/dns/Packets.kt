package com.adlull.android.dns

/** IPv4 + UDP 패킷 파싱/생성 (DNS 처리에 필요한 최소 기능) */
class UdpPacket(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object IpPackets {
    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 28) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || buf[9].toInt() != 17) return null // UDP만
        val total = minOf(u16(buf, 2), len)
        if (total < ihl + 8) return null
        val udpLen = u16(buf, ihl + 4)
        val end = minOf(ihl + udpLen, total)
        if (end <= ihl + 8) return null
        return UdpPacket(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payload = buf.copyOfRange(ihl + 8, end),
        )
    }

    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40 // Don't Fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val cs = checksum(p, 0, 20)
        p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        p[20] = (srcPort shr 8).toByte(); p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP 체크섬 0 = 사용 안 함 (IPv4에서 허용)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}

class DnsQuery(val name: String, val qtype: Int, val questionEnd: Int)

object DnsPackets {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun parse(msg: ByteArray): DnsQuery? {
        if (msg.size < 12) return null
        if (msg[2].toInt() and 0x80 != 0) return null // 응답 패킷
        val qd = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var pos = 12
        while (true) {
            if (pos >= msg.size) return null
            val l = msg[pos].toInt() and 0xFF
            if (l == 0) { pos++; break }
            if (l and 0xC0 != 0 || pos + 1 + l > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in pos + 1..pos + l) sb.append((msg[i].toInt() and 0xFF).toChar())
            pos += l + 1
        }
        if (pos + 4 > msg.size) return null
        val qtype = ((msg[pos].toInt() and 0xFF) shl 8) or (msg[pos + 1].toInt() and 0xFF)
        return DnsQuery(sb.toString().lowercase(), qtype, pos + 4)
    }

    /** 차단 응답: A → 0.0.0.0, AAAA → ::, 그 외 → 빈 응답. nxdomain=true면 NXDOMAIN */
    fun blockedResponse(query: ByteArray, q: DnsQuery, nxdomain: Boolean): ByteArray {
        val question = query.copyOfRange(12, q.questionEnd)
        val answer: ByteArray? = if (nxdomain) null else when (q.qtype) {
            TYPE_A -> rr(TYPE_A, ByteArray(4))
            TYPE_AAAA -> rr(TYPE_AAAA, ByteArray(16))
            else -> null
        }
        val out = ByteArray(12 + question.size + (answer?.size ?: 0))
        out[0] = query[0]; out[1] = query[1]
        out[2] = (0x80 or (query[2].toInt() and 0x01)).toByte() // QR + RD 유지
        out[3] = (0x80 or if (nxdomain) 3 else 0).toByte() // RA + RCODE
        out[5] = 1
        out[7] = if (answer != null) 1 else 0
        System.arraycopy(question, 0, out, 12, question.size)
        answer?.let { System.arraycopy(it, 0, out, 12 + question.size, it.size) }
        return out
    }

    private fun rr(type: Int, data: ByteArray): ByteArray {
        val r = ByteArray(12 + data.size)
        r[0] = 0xC0.toByte(); r[1] = 0x0C // 질문 이름 포인터
        r[2] = (type shr 8).toByte(); r[3] = type.toByte()
        r[5] = 1 // IN
        r[9] = 60 // TTL 60초
        r[11] = data.size.toByte()
        System.arraycopy(data, 0, r, 12, data.size)
        return r
    }
}
