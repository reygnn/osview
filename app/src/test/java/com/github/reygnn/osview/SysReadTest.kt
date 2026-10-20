package com.github.reygnn.osview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// ByteReader against temp files — never the host's /proc.
class SysReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ByteReader.content() = String(buf, 0, len)

    @Test
    fun `read puts the file content into the buffer`() {
        val f = tmp.newFile("temp").apply { writeText("42500\n") }
        val r = ByteReader()
        assertTrue(r.read(r.source(f.path)))
        assertEquals("42500\n", r.content())
        assertEquals(42500L, parseLong(r.buf, r.len))
    }

    @Test
    fun `read grows the buffer for a file larger than its capacity`() {
        val text = "x".repeat(1000) + "end"
        val f = tmp.newFile("big").apply { writeText(text) }
        val r = ByteReader(initialCapacity = 16)
        assertTrue(r.read(r.source(f.path)))
        assertEquals(text, r.content())
    }

    @Test
    fun `read reuses the buffer and only the current length counts`() {
        val long = tmp.newFile("long").apply { writeText("1234567890") }
        val short = tmp.newFile("short").apply { writeText("7") }
        val r = ByteReader()
        r.read(r.source(long.path))
        val buf = r.buf
        assertTrue(r.read(r.source(short.path)))
        assertTrue(buf === r.buf)
        assertEquals(7L, parseLong(r.buf, r.len))
    }

    @Test
    fun `a file that was never readable is blocked after the first failure`() {
        val path = File(tmp.root, "missing").path
        val r = ByteReader()
        val src = r.source(path)
        assertFalse(r.read(src))
        assertEquals(0, r.len)
        assertTrue(src.blocked)
        File(path).writeText("1")
        assertFalse(r.read(src)) // blocked: not even tried
    }

    @Test
    fun `unblockAll gives blocked files another try`() {
        val path = File(tmp.root, "late").path
        val r = ByteReader()
        val src = r.source(path)
        assertFalse(r.read(src))
        File(path).writeText("5")
        r.unblockAll()
        assertTrue(r.read(src))
        assertEquals(5L, parseLong(r.buf, r.len))
    }

    @Test
    fun `a file that was readable before keeps being retried`() {
        val f = tmp.newFile("hotplug").apply { writeText("1") }
        val r = ByteReader()
        val src = r.source(f.path)
        assertTrue(r.read(src))
        f.delete()
        assertFalse(r.read(src))
        assertFalse(src.blocked)
        f.writeText("2")
        assertTrue(r.read(src))
        assertEquals(2L, parseLong(r.buf, r.len))
    }

    @Test
    fun `readPath and text serve the one-time scans`() {
        val f = tmp.newFile("type").apply { writeText("cpu-0-0-usr\n") }
        val r = ByteReader()
        assertTrue(r.readPath(f.path))
        assertEquals("cpu-0-0-usr", r.text())
        assertFalse(r.readPath(File(tmp.root, "nope").path))
    }
}
