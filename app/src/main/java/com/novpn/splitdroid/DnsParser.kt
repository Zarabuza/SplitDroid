package com.novpn.splitdroid

object DnsParser {
    /**
     * Extracts the query name from a raw IPv4 UDP DNS packet (including IP/UDP headers).
     * Supports DNS name compression pointers (0xC0).
     * Returns lowercase domain without trailing dot, or null if not a DNS query.
     */
    fun parseDnsQueryName(packet: ByteArray): String? {
        if (packet.size < 28) return null

        // IPv4 header
        val versionIhl = packet[0].toInt() and 0xFF
        if (versionIhl shr 4 != 4) return null
        val ihl = (versionIhl and 0x0F) * 4
        if (packet.size < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (srcPort != 53 && dstPort != 53) return null

        val dnsOffset = ihl + 8
        if (packet.size < dnsOffset + 12) return null

        // Only parse queries (QR bit = 0)
        val flags = ((packet[dnsOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[dnsOffset + 3].toInt() and 0xFF)
        if ((flags and 0x8000) != 0) return null

        val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or
            (packet[dnsOffset + 5].toInt() and 0xFF)
        if (qdCount < 1) return null

        return readDnsName(packet, dnsOffset + 12, dnsOffset)?.lowercase()?.trimEnd('.')
    }

    fun isDnsPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        val versionIhl = packet[0].toInt() and 0xFF
        if (versionIhl shr 4 != 4) return false
        val ihl = (versionIhl and 0x0F) * 4
        if (packet.size < ihl + 8) return false
        if ((packet[9].toInt() and 0xFF) != 17) return false
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        return srcPort == 53 || dstPort == 53
    }

    fun isDnsResponse(packet: ByteArray): Boolean {
        if (!isDnsPacket(packet)) return false
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val dnsOffset = ihl + 8
        if (packet.size < dnsOffset + 4) return false
        val flags = ((packet[dnsOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[dnsOffset + 3].toInt() and 0xFF)
        return (flags and 0x8000) != 0
    }

    private fun readDnsName(packet: ByteArray, start: Int, dnsOffset: Int): String? {
        val parts = mutableListOf<String>()
        var pos = start
        var jumps = 0

        while (pos < packet.size && jumps < 16) {
            val label = packet[pos].toInt() and 0xFF
            when {
                label == 0 -> break
                (label and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= packet.size) return null
                    val pointer = ((label and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                    pos = dnsOffset + pointer
                    jumps++
                }
                else -> {
                    pos++
                    if (pos + label > packet.size) return null
                    parts += String(packet, pos, label, Charsets.US_ASCII)
                    pos += label
                }
            }
        }

        if (parts.isEmpty()) return null
        return parts.joinToString(".")
    }

    /**
     * Builds a minimal IPv4 UDP DNS A-record response for [queryPacket] with [ipv4].
     */
    fun buildDnsResponse(queryPacket: ByteArray, ipv4: ByteArray): ByteArray? {
        if (ipv4.size != 4) return null
        if (queryPacket.size < 28) return null

        val versionIhl = queryPacket[0].toInt() and 0xFF
        val ihl = (versionIhl and 0x0F) * 4
        val dnsOffset = ihl + 8
        if (queryPacket.size < dnsOffset + 12) return null

        val questionEnd = findQuestionEnd(queryPacket, dnsOffset + 12) ?: return null
        val question = queryPacket.copyOfRange(dnsOffset + 12, questionEnd)

        val answer = ByteArray(2 + 2 + 2 + 4 + 2 + 4) // pointer + type + class + ttl + rdlen + rdata
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C.toByte()
        answer[2] = 0x00
        answer[3] = 0x01 // A
        answer[4] = 0x00
        answer[5] = 0x01 // IN
        answer[6] = 0x00
        answer[7] = 0x00
        answer[8] = 0x00
        answer[9] = 0x3C // TTL 60
        answer[10] = 0x00
        answer[11] = 0x04
        answer[12] = ipv4[0]
        answer[13] = ipv4[1]
        answer[14] = ipv4[2]
        answer[15] = ipv4[3]

        val dnsPayload = ByteArray(12 + question.size + answer.size)
        // copy transaction id
        dnsPayload[0] = queryPacket[dnsOffset]
        dnsPayload[1] = queryPacket[dnsOffset + 1]
        // flags: response, recursion available
        dnsPayload[2] = 0x81.toByte()
        dnsPayload[3] = 0x80.toByte()
        dnsPayload[4] = 0x00
        dnsPayload[5] = 0x01 // QDCOUNT
        dnsPayload[6] = 0x00
        dnsPayload[7] = 0x01 // ANCOUNT
        System.arraycopy(question, 0, dnsPayload, 12, question.size)
        System.arraycopy(answer, 0, dnsPayload, 12 + question.size, answer.size)

        val udpLen = 8 + dnsPayload.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)

        // IP header (swap src/dst)
        out[0] = queryPacket[0]
        out[1] = 0
        out[2] = (totalLen shr 8).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        // src IP = original dst
        System.arraycopy(queryPacket, 16, out, 12, 4)
        // dst IP = original src
        System.arraycopy(queryPacket, 12, out, 16, 4)
        // copy rest of IP header options if any
        if (ihl > 20) {
            System.arraycopy(queryPacket, 20, out, 20, ihl - 20)
        }
        fillIpChecksum(out, ihl)

        // UDP header (swap ports)
        out[ihl] = queryPacket[ihl + 2]
        out[ihl + 1] = queryPacket[ihl + 3]
        out[ihl + 2] = queryPacket[ihl]
        out[ihl + 3] = queryPacket[ihl + 1]
        out[ihl + 4] = (udpLen shr 8).toByte()
        out[ihl + 5] = (udpLen and 0xFF).toByte()
        out[ihl + 6] = 0
        out[ihl + 7] = 0
        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)

        return out
    }

    private fun findQuestionEnd(packet: ByteArray, start: Int): Int? {
        var pos = start
        var safety = 0
        while (pos < packet.size && safety++ < 64) {
            val label = packet[pos].toInt() and 0xFF
            when {
                label == 0 -> {
                    pos++
                    break
                }
                (label and 0xC0) == 0xC0 -> {
                    pos += 2
                    break
                }
                else -> {
                    pos += 1 + label
                }
            }
        }
        if (pos + 4 > packet.size) return null
        return pos + 4 // type + class
    }

    private fun fillIpChecksum(packet: ByteArray, ihl: Int) {
        packet[10] = 0
        packet[11] = 0
        var sum = 0
        var i = 0
        while (i < ihl) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
}
