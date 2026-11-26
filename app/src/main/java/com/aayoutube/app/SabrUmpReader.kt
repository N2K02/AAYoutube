package com.aayoutube.app

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * UMP framing layer for future authorized SABR sessions. A part consists of a custom
 * unsigned length-coded integer for type, another for size, then the payload.
 * No networking, client impersonation, credential handling or playback side effects.
 * Protocol reference: https://github.com/davidzeng0/innertube/blob/main/googlevideo/ump.md
 */
internal class SabrUmpReader(
    private val input: InputStream,
    private val maxPartBytes: Int = 2 * 1024 * 1024,
    private val maxResponseBytes: Long = 16L * 1024 * 1024,
    private val maxParts: Int = 4096,
) {
    data class Part(val type: Long, val data: ByteArray)
    private var total = 0L
    private var parts = 0

    init { require(maxPartBytes > 0 && maxResponseBytes > 0 && maxParts > 0) }

    private fun byte(): Int {
        val n = input.read()
        if (n < 0) throw EOFException("truncated UMP integer")
        return n
    }

    private fun integer(first: Int): Long {
        val following = when {
            first < 0x80 -> 0
            first < 0xc0 -> 1
            first < 0xe0 -> 2
            first < 0xf0 -> 3
            else -> 4
        }
        var shift = if (following == 4) 0 else 7 - following
        var value = if (following == 4) 0L else (first and ((1 shl shift) - 1)).toLong()
        repeat(following) {
            value = value or (byte().toLong() shl shift)
            shift += 8
        }
        total += following + 1
        if (total > maxResponseBytes) throw IOException("UMP response limit")
        return value
    }

    /** Null only at a clean part boundary. A malformed/truncated response fails closed. */
    fun next(): Part? {
        val first = input.read()
        if (first < 0) return null
        if (++parts > maxParts) throw IOException("UMP part count limit")
        val type = integer(first)
        val length = integer(byte())
        if (length > maxPartBytes || length > maxResponseBytes - total)
            throw IOException("UMP payload limit")
        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val n = input.read(payload, offset, payload.size - offset)
            if (n < 0) throw EOFException("truncated UMP payload")
            if (n == 0) {
                val single = input.read()
                if (single < 0) throw EOFException("truncated UMP payload")
                payload[offset++] = single.toByte()
            } else offset += n
        }
        total += length
        return Part(type, payload)
    }
}
