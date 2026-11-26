package com.aayoutube.app

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SabrUmpReaderTest {
    private fun reader(vararg bytes: Int) = SabrUmpReader(ByteArrayInputStream(bytes.map { it.toByte() }.toByteArray()))

    @Test fun cleanBoundaryAndMultipleParts() {
        val r = reader(20, 3, 7, 8, 9, 21, 1, 7)
        assertEquals(20L, r.next()!!.type)
        val p = r.next()!!
        assertEquals(21L, p.type); assertArrayEquals(byteArrayOf(7), p.data)
        assertNull(r.next())
    }

    @Test fun allFiveIntegerWidths() {
        val inputs = listOf(
            intArrayOf(0x7f, 0), intArrayOf(0x80, 2, 0),
            intArrayOf(0xc0, 0, 2, 0), intArrayOf(0xe0, 0, 0, 2, 0),
            intArrayOf(0xf0, 0xff, 0xff, 0xff, 0xff, 0),
        )
        val expected = listOf(127L, 128L, 16384L, 2097152L, 4294967295L)
        inputs.zip(expected).forEach { (bytes, n) -> assertEquals(n, reader(*bytes).next()!!.type) }
    }

    @Test fun truncatedAndOversizedFailClosed() {
        assertThrows(EOFException::class.java) { reader(0x80).next() }
        assertThrows(EOFException::class.java) { reader(20, 4, 1).next() }
        assertThrows(IOException::class.java) { reader(20, 0xf0, 0xff, 0xff, 0xff, 0xff).next() }
        val limited = SabrUmpReader(ByteArrayInputStream(byteArrayOf(20, 2, 1, 2)), maxPartBytes = 1)
        assertThrows(IOException::class.java) { limited.next() }
        val many = SabrUmpReader(ByteArrayInputStream(byteArrayOf(20, 0, 21, 0)), maxParts = 1)
        many.next(); assertThrows(IOException::class.java) { many.next() }
    }
}
