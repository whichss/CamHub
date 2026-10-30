package com.camhub.studio.data.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpAddressMatcherTest {
    @Test
    fun `matches an IPv4 endpoint against its IPv4 mapped IPv6 representation`() {
        val ipv4 = InetAddress.getByName("192.0.2.45")
        val mappedBytes = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            this[12] = 192.toByte()
            this[13] = 0.toByte()
            this[14] = 2.toByte()
            this[15] = 45.toByte()
        }
        val mapped = Inet6Address.getByAddress(null, mappedBytes, -1)

        assertTrue(IpAddressMatcher.matches(ipv4, mapped))
        assertTrue(IpAddressMatcher.matches(mapped, ipv4))
    }

    @Test
    fun `does not equate different IPv4 mapped addresses`() {
        val first = InetAddress.getByName("192.0.2.45")
        val secondBytes = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            this[12] = 192.toByte()
            this[13] = 0.toByte()
            this[14] = 2.toByte()
            this[15] = 46.toByte()
        }

        assertFalse(IpAddressMatcher.matches(first, Inet6Address.getByAddress(null, secondBytes, -1)))
    }

    @Test
    fun `matches native IPv6 addresses without treating a different host as equal`() {
        val expected = InetAddress.getByName("2001:db8::1")
        val same = InetAddress.getByName("2001:0db8:0:0:0:0:0:1")
        val different = InetAddress.getByName("2001:db8::2")

        assertTrue(IpAddressMatcher.matches(expected, same))
        assertFalse(IpAddressMatcher.matches(expected, different))
    }
}
