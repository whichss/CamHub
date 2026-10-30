package com.camhub.studio.data.network

import java.util.Properties

internal object WireGoldenVectors {
    private val values: Properties by lazy {
        Properties().apply {
            WireGoldenVectors::class.java.classLoader
                .getResourceAsStream("camhub-wire-golden.properties")
                .use { stream ->
                    requireNotNull(stream) { "Missing shared CamHub wire vectors" }
                        .let { load(it) }
                }
        }
    }

    fun bytes(name: String): ByteArray {
        val hex = string(name)
        require(hex.length % 2 == 0) { "Odd-length hex vector: $name" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun string(name: String): String =
        requireNotNull(values.getProperty(name)) { "Missing CamHub vector: $name" }
}
