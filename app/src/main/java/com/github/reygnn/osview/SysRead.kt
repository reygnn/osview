package com.github.reygnn.osview

import java.io.FileInputStream

// Allocation-light reads of small /proc and /sys files. Plain java.io, no Android types,
// so it runs in JVM tests against temp files.
//
// File.readText() costs ~26 KB per call however small the file (reader and copy buffers
// are created anew each time). Here every file lands in one reused byte buffer instead;
// the parsers in SysParse.kt work on those bytes directly.

/**
 * One file that is read every tick. A file that has never been readable is [blocked]
 * after its first failure — SELinux denials don't change at runtime, and retrying would
 * throw an exception per tick. A file that was readable before keeps being retried
 * (a core's cpufreq files can vanish briefly during hotplug).
 */
internal class Source(val path: String) {
    internal var everRead = false
    internal var blocked = false
}

/** Not thread-safe: one instance per worker thread. */
internal class ByteReader(initialCapacity: Int = 8192) {

    /** Content of the last successful [read]; valid up to [len]. */
    var buf = ByteArray(initialCapacity)
        private set
    var len = 0
        private set

    private val sources = ArrayList<Source>()

    /** Creates a [Source] that [unblockAll] can reset. Call once per path, not per tick. */
    fun source(path: String): Source = Source(path).also { sources += it }

    /** Gives blocked files one more try — called on resume, so nothing stays blocked forever. */
    fun unblockAll() {
        for (s in sources) s.blocked = false
    }

    /** Reads [src] into [buf]; false (and [len] 0) when blocked or unreadable. */
    fun read(src: Source): Boolean {
        if (src.blocked) {
            len = 0
            return false
        }
        val ok = readPath(src.path)
        if (ok) src.everRead = true else if (!src.everRead) src.blocked = true
        return ok
    }

    /** One-off read without blocking bookkeeping, e.g. during the one-time scans. */
    fun readPath(path: String): Boolean = try {
        FileInputStream(path).use { s ->
            len = 0
            while (true) {
                if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                val n = s.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
        }
        true
    } catch (e: Exception) {
        len = 0
        false
    }

    /** Current content as a trimmed String — allocates, so only for the one-time scans. */
    fun text(): String = String(buf, 0, len, Charsets.UTF_8).trim()
}
