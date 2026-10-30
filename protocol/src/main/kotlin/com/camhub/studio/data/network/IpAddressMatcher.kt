package com.camhub.studio.data.network

import java.net.Inet6Address
import java.net.InetAddress

/** Compares packet source addresses across IPv4 and IPv4-mapped IPv6 socket representations. */
object IpAddressMatcher {
    fun matches(expected: InetAddress, actual: InetAddress): Boolean {
        val expectedV4 = ipv4Bytes(expected)
        val actualV4 = ipv4Bytes(actual)
        if (expectedV4 != null || actualV4 != null) {
            return expectedV4 != null && actualV4 != null && expectedV4.contentEquals(actualV4)
        }

        if (!expected.address.contentEquals(actual.address)) return false
        if (expected is Inet6Address && actual is Inet6Address && expected.isLinkLocalAddress) {
            val expectedScope = expected.scopeId
            val actualScope = actual.scopeId
            return expectedScope == 0 || actualScope == 0 || expectedScope == actualScope
        }
        return true
    }

    private fun ipv4Bytes(address: InetAddress): ByteArray? {
        val bytes = address.address
        if (bytes.size == 4) return bytes
        if (bytes.size != 16) return null
        if ((0 until 10).any { bytes[it] != 0.toByte() } ||
            bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()
        ) {
            return null
        }
        return bytes.copyOfRange(12, 16)
    }
}
