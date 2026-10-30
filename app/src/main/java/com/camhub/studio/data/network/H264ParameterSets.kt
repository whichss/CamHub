package com.camhub.studio.data.network

internal object H264ParameterSets {
    fun equivalent(first: ByteArray, second: ByteArray): Boolean {
        fun normalize(data: ByteArray): ByteArray? {
            val output = java.io.ByteArrayOutputStream(data.size)
            var i = 0
            var found = false
            while (i < data.size) {
                val prefix = when {
                    i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                        data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                    i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                        data[i + 2] == 1.toByte() -> 3
                    else -> 0
                }
                if (prefix > 0) {
                    output.write(byteArrayOf(0, 0, 0, 1))
                    i += prefix
                    found = true
                } else {
                    if (!found) return null
                    output.write(data[i].toInt())
                    i++
                }
            }
            return if (found) output.toByteArray() else null
        }
        val a = normalize(first) ?: return first.contentEquals(second)
        val b = normalize(second) ?: return false
        return a.contentEquals(b)
    }

    fun extract(data: ByteArray, offset: Int): ByteArray? {
        if (offset < 0 || offset >= data.size) return null
        var i = offset
        var nalStart = -1
        var prefixSize = 0
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        fun capture(end: Int) {
            if (nalStart < 0 || nalStart + prefixSize >= end) return
            when (data[nalStart + prefixSize].toInt() and 0x1f) {
                7 -> sps = data.copyOfRange(nalStart, end)
                8 -> pps = data.copyOfRange(nalStart, end)
            }
        }
        while (i + 3 < data.size) {
            val size = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (size > 0) {
                capture(i)
                if (sps != null && pps != null) return sps!! + pps!!
                nalStart = i
                prefixSize = size
                i += size
            } else i++
        }
        capture(data.size)
        if (sps != null && pps != null) return sps!! + pps!!
        return null
    }
}
