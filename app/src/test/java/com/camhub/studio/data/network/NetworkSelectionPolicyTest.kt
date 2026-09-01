package com.camhub.studio.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSelectionPolicyTest {
    @Test
    fun `auto prefers ethernet and keeps wifi as fallback`() {
        assertEquals(
            listOf(NetworkTransport.ETHERNET, NetworkTransport.WIFI),
            NetworkSelectionPolicy.orderedTransports(
                NetworkSelectionMode.AUTO,
                setOf(NetworkTransport.WIFI, NetworkTransport.ETHERNET)
            )
        )
    }

    @Test
    fun `auto uses wifi when ethernet is unavailable`() {
        assertEquals(
            listOf(NetworkTransport.WIFI),
            NetworkSelectionPolicy.orderedTransports(
                NetworkSelectionMode.AUTO,
                setOf(NetworkTransport.WIFI)
            )
        )
    }

    @Test
    fun `fixed mode never falls through to another transport`() {
        assertEquals(
            emptyList<NetworkTransport>(),
            NetworkSelectionPolicy.orderedTransports(
                NetworkSelectionMode.ETHERNET,
                setOf(NetworkTransport.WIFI)
            )
        )
        assertEquals(
            listOf(NetworkTransport.WIFI),
            NetworkSelectionPolicy.orderedTransports(
                NetworkSelectionMode.WIFI,
                setOf(NetworkTransport.WIFI, NetworkTransport.ETHERNET)
            )
        )
    }
}
