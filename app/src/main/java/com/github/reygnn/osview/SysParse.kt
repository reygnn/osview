package com.github.reygnn.osview

import kotlin.math.abs

// Pure parsing / formatting logic, free of Android types so it runs in plain JVM tests.
// MainActivity does the file reads (into ByteReader's buffer) and system-service calls
// and hands the raw bytes here.
//
// Allocation-free per tick: parsers work on the byte buffer and fill preallocated holders
// (StatSample, MemInfo); "no value" is a sentinel (NO_VALUE, NaN) instead of a nullable,
// which would box; formatters append to the caller's StringBuilder instead of building
// Strings with String.format. Output is ASCII digits regardless of the default locale.

internal const val BAR_W = 16

/** Stands in the percentage column (same width as [appendPct]) while there is no load figure. */
internal const val PCT_NONE = "  --%"

/** Up to this many CPU zones are listed one per line; more are summarised in one line. */
internal const val MAX_TEMP_LINES = 6

/** "No value" for Long readings — an unreadable or unparsable file. */
internal const val NO_VALUE = Long.MIN_VALUE

/** Raw values above this magnitude are millidegrees, below it degrees or decidegrees. */
private const val MILLI_THRESHOLD = 1000L

/** Plausible range in °C; inactive zones often report 0 or -273. */
private const val TEMP_MIN_C = 0.0
private const val TEMP_MAX_C = 150.0

internal val THERMAL_STATUS =
    arrayOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

// ---------- Bytes ----------

private const val B_SPACE: Byte = 0x20
private const val B_TAB: Byte = 0x09
private const val B_LF: Byte = 0x0A
private const val B_CR: Byte = 0x0D
private const val B_MINUS: Byte = 0x2D
private const val B_PLUS: Byte = 0x2B
private const val B_COLON: Byte = 0x3A
private const val B_0: Byte = 0x30
private const val B_9: Byte = 0x39

private fun isBlank(b: Byte) = b == B_SPACE || b == B_TAB
private fun isSpace(b: Byte) = b == B_SPACE || b == B_TAB || b == B_LF || b == B_CR
private fun isDigit(b: Byte) = b >= B_0 && b <= B_9

/** Exactly one optionally signed decimal integer in [s, e); [NO_VALUE] on anything else or overflow. */
internal fun parseLong(b: ByteArray, s: Int, e: Int): Long {
    var p = s
    if (p < e && (b[p] == B_MINUS || b[p] == B_PLUS)) p++
    if (p >= e) return NO_VALUE
    val neg = b[s] == B_MINUS
    var v = 0L
    while (p < e) {
        if (!isDigit(b[p])) return NO_VALUE
        val d = b[p] - B_0
        if (v > (Long.MAX_VALUE - d) / 10) return NO_VALUE
        v = v * 10 + d
        p++
    }
    return if (neg) -v else v
}

/** The whole content as one integer, surrounding whitespace allowed — like `trim().toLongOrNull()`. */
internal fun parseLong(b: ByteArray, len: Int): Long {
    var s = 0
    var e = len
    while (s < e && isSpace(b[s])) s++
    while (e > s && isSpace(b[e - 1])) e--
    return parseLong(b, s, e)
}

private fun lineEnd(b: ByteArray, from: Int, len: Int): Int {
    var p = from
    while (p < len && b[p] != B_LF) p++
    return p
}

// ---------- /proc/stat ----------

/**
 * One /proc/stat sample in preallocated arrays. Slot 0 is the aggregate "cpu" line,
 * slot i + 1 is "cpuI". [present] is false when the file could not be read.
 */
internal class StatSample(cores: Int) {
    val idle = LongArray(cores + 1)
    val total = LongArray(cores + 1)
    val valid = BooleanArray(cores + 1)
    var present = false

    fun clear() {
        valid.fill(false)
        present = false
    }
}

/**
 * Fills [out] from /proc/stat: idle + iowait and the sum of the first eight fields per
 * cpu line. Only the leading cpu lines are read; lines with fewer than five fields are
 * skipped, unparsable fields count as zero, cores beyond [out]'s capacity are ignored.
 */
internal fun parseStat(b: ByteArray, len: Int, out: StatSample) {
    out.clear()
    out.present = true
    var p = 0
    while (p < len) {
        val end = lineEnd(b, p, len)
        if (end - p < 3 || b[p] != 'c'.code.toByte() || b[p + 1] != 'p'.code.toByte() || b[p + 2] != 'u'.code.toByte()) break
        var q = p + 3
        var slot = 0
        if (q < end && isDigit(b[q])) {
            var n = 0
            while (q < end && isDigit(b[q]) && n < 100_000) {
                n = n * 10 + (b[q] - B_0)
                q++
            }
            slot = n + 1
        }
        if (q < end && !isBlank(b[q])) slot = -1 // "cpufoo": not a cpu line we know

        var fields = 0
        var idle = 0L
        var total = 0L
        while (fields < 8) {
            while (q < end && isBlank(b[q])) q++
            if (q >= end) break
            val s = q
            while (q < end && !isBlank(b[q])) q++
            val v = parseLong(b, s, q).let { if (it == NO_VALUE) 0L else it }
            if (fields == 3 || fields == 4) idle += v
            total += v
            fields++
        }
        if (fields >= 5 && slot >= 0 && slot < out.valid.size) {
            out.idle[slot] = idle
            out.total[slot] = total
            out.valid[slot] = true
        }
        p = end + 1
    }
}

/** Busy fraction of [slot] between two samples; NaN without a usable delta. */
internal fun cpuLoad(now: StatSample, prev: StatSample, slot: Int): Double {
    if (!now.present || !prev.present) return Double.NaN
    if (slot >= now.valid.size || !now.valid[slot] || !prev.valid[slot]) return Double.NaN
    val dTotal = now.total[slot] - prev.total[slot]
    if (dTotal <= 0) return Double.NaN
    return 1.0 - (now.idle[slot] - prev.idle[slot]).toDouble() / dTotal
}

/**
 * What the CPU bars mean in this frame. LOCKED: /proc/stat is unreadable, bars show the
 * clock (and the screen says so). WARMUP: readable, but there is no previous sample to
 * form a delta yet (first frame after start or resume). LOAD: bars show the measured load.
 */
internal enum class CpuMode { LOCKED, WARMUP, LOAD }

internal fun cpuMode(now: StatSample, prev: StatSample): CpuMode = when {
    !now.present -> CpuMode.LOCKED
    cpuLoad(now, prev, 0).isNaN() -> CpuMode.WARMUP
    else -> CpuMode.LOAD
}

/**
 * Bar fill for one core. Only when LOCKED is the bar the clock relative to its max;
 * otherwise it is the load, and empty without one — it never silently switches meaning.
 * [load] NaN and kHz [NO_VALUE] mean "not available".
 */
internal fun coreFraction(mode: CpuMode, load: Double, curKhz: Long, maxKhz: Long): Double =
    if (mode == CpuMode.LOCKED) {
        if (curKhz != NO_VALUE && maxKhz != NO_VALUE && maxKhz > 0) curKhz.toDouble() / maxKhz else 0.0
    } else {
        if (load.isNaN()) 0.0 else load
    }

/** The overall CPU line: [lockedText] when LOCKED, else bar and load (placeholder in WARMUP). */
internal fun cpuHeader(sb: StringBuilder, mode: CpuMode, load: Double, lockedText: String) {
    if (mode == CpuMode.LOCKED) {
        sb.append(lockedText).append('\n')
        return
    }
    val l = if (mode == CpuMode.LOAD) load else Double.NaN
    sb.append("CPU  ")
    appendBar(sb, if (l.isNaN()) 0.0 else l)
    appendPctOrNone(sb, l)
    sb.append('\n')
}

/** One per-core line for core [index]: name, bar, load column (not when LOCKED), current clock. */
internal fun coreLine(sb: StringBuilder, index: Int, mode: CpuMode, load: Double, curKhz: Long, maxKhz: Long) {
    val l = if (mode == CpuMode.LOAD) load else Double.NaN
    appendCoreName(sb, index)
    appendBar(sb, coreFraction(mode, l, curKhz, maxKhz))
    if (mode != CpuMode.LOCKED) appendPctOrNone(sb, l)
    if (curKhz != NO_VALUE) sb.append(' ').append(curKhz / 1000).append("MHz")
    sb.append('\n')
}

internal fun offlineLine(sb: StringBuilder, index: Int) {
    appendCoreName(sb, index)
    sb.append("offline\n")
}

/** "cpuN" padded to the 5-column label. */
private fun appendCoreName(sb: StringBuilder, index: Int) {
    val start = sb.length
    sb.append("cpu").append(index)
    padTo(sb, start, 5)
}

private fun appendPctOrNone(sb: StringBuilder, f: Double) {
    if (f.isNaN()) sb.append(PCT_NONE) else appendPct(sb, f)
}

// ---------- /proc/meminfo ----------

/** The /proc/meminfo fields osview shows, in kB; [NO_VALUE] when missing. */
internal class MemInfo {
    var total = NO_VALUE
    var free = NO_VALUE
    var available = NO_VALUE
    var swapTotal = NO_VALUE
    var swapFree = NO_VALUE
    var cached = NO_VALUE
    var buffers = NO_VALUE

    fun clear() {
        total = NO_VALUE; free = NO_VALUE; available = NO_VALUE
        swapTotal = NO_VALUE; swapFree = NO_VALUE; cached = NO_VALUE; buffers = NO_VALUE
    }
}

private val K_MEM_TOTAL = "MemTotal".toByteArray()
private val K_MEM_FREE = "MemFree".toByteArray()
private val K_MEM_AVAILABLE = "MemAvailable".toByteArray()
private val K_SWAP_TOTAL = "SwapTotal".toByteArray()
private val K_SWAP_FREE = "SwapFree".toByteArray()
private val K_CACHED = "Cached".toByteArray()
private val K_BUFFERS = "Buffers".toByteArray()

private fun keyIs(b: ByteArray, s: Int, e: Int, key: ByteArray): Boolean {
    if (e - s != key.size) return false
    for (i in key.indices) if (b[s + i] != key[i]) return false
    return true
}

/** Fills [out] from /proc/meminfo ("Key:   value kB"); junk lines and values are skipped. */
internal fun parseMeminfo(b: ByteArray, len: Int, out: MemInfo) {
    out.clear()
    var p = 0
    while (p < len) {
        val end = lineEnd(b, p, len)
        var c = p
        while (c < end && b[c] != B_COLON) c++
        if (c < end) {
            var s = c + 1
            while (s < end && isBlank(b[s])) s++
            var e = s
            while (e < end && !isSpace(b[e])) e++
            val v = parseLong(b, s, e)
            if (v != NO_VALUE) {
                when {
                    keyIs(b, p, c, K_MEM_TOTAL) -> out.total = v
                    keyIs(b, p, c, K_MEM_FREE) -> out.free = v
                    keyIs(b, p, c, K_MEM_AVAILABLE) -> out.available = v
                    keyIs(b, p, c, K_SWAP_TOTAL) -> out.swapTotal = v
                    keyIs(b, p, c, K_SWAP_FREE) -> out.swapFree = v
                    keyIs(b, p, c, K_CACHED) -> out.cached = v
                    keyIs(b, p, c, K_BUFFERS) -> out.buffers = v
                }
            }
        }
        p = end + 1
    }
}

/** [v], or [fallback] when it is [NO_VALUE]. */
internal fun orElse(v: Long, fallback: Long): Long = if (v == NO_VALUE) fallback else v

// ---------- /proc/loadavg ----------

/** Appends the first three fields of /proc/loadavg. */
internal fun appendLoadavg(sb: StringBuilder, b: ByteArray, len: Int) {
    var p = 0
    while (p < len && isSpace(b[p])) p++
    var spaces = 0
    while (p < len && b[p] != B_LF && b[p] != B_CR) {
        if (b[p] == B_SPACE && ++spaces == 3) break
        sb.append((b[p].toInt() and 0xFF).toChar())
        p++
    }
}

// ---------- Temperature ----------

/**
 * Raw zone value (millidegrees, degrees or decidegrees) -> °C; NaN when [NO_VALUE] or
 * implausible. Above ±1000 the value is millidegrees. Below, whole degrees win when
 * plausible (so 100 stays 100 °C); otherwise decidegrees are tried (450 -> 45.0 °C).
 */
internal fun parseTemp(raw: Long): Double {
    if (raw == NO_VALUE) return Double.NaN
    if (raw > MILLI_THRESHOLD || raw < -MILLI_THRESHOLD) return plausibleTemp(raw / 1000.0)
    val whole = plausibleTemp(raw.toDouble())
    return if (!whole.isNaN()) whole else plausibleTemp(raw / 10.0)
}

private fun plausibleTemp(c: Double): Double = if (c > TEMP_MIN_C && c < TEMP_MAX_C) c else Double.NaN

// Qualcomm: cpu-*, cpuss-*; MediaTek: cpu_*; Tensor/Exynos: big/mid/little
/** [type] is expected lowercase. */
internal fun isCpuZone(type: String): Boolean =
    "cpu" in type || type == "big" || type == "mid" || type == "little" || type.startsWith("cluster")

/** Result of one scan over /sys/class/thermal/thermal_zone*. */
internal class ZoneScan(
    /** CPU zones as (type, temp path). */
    val cpu: List<Pair<String, String>>,
    val total: Int,
    /** Zones whose type file could not be read (SELinux on e.g. Samsung). */
    val unreadable: Int,
)

internal enum class TempState { NO_CPU_ZONES, LOCKED, OK }

/**
 * Which CPU temperature line to show. Every Android device has thermal zones, so seeing
 * none means SELinux hides them (Samsung A17: the app can't even stat thermal_zone*, no
 * audit log). Only readable zones of which none looks like a CPU are "no CPU zones".
 */
internal fun tempState(scan: ZoneScan, readableTemps: Int): TempState = when {
    readableTemps > 0 -> TempState.OK
    scan.cpu.isNotEmpty() -> TempState.LOCKED
    scan.total == 0 || scan.unreadable > 0 -> TempState.LOCKED
    else -> TempState.NO_CPU_ZONES
}

internal fun thermalStatusName(status: Int): String = THERMAL_STATUS.getOrNull(status) ?: "?"

/**
 * One temperature row: 5-column label, 15-column name (at most 14 shown), value. Every
 * row in the Temp block uses this layout, so the values share one column.
 */
internal fun tempRow(sb: StringBuilder, label: String, name: String, c: Double) {
    appendPadded(sb, label, 5)
    appendPadded(sb, name, 15, 14)
    appendDeg(sb, c)
    sb.append('\n')
}

/**
 * CPU zone temperatures from [temps] (NaN = unreadable, skipped), named by [names]:
 * one row each up to [MAX_TEMP_LINES] readable zones, else average, max and count.
 */
internal fun cpuTempLines(sb: StringBuilder, names: List<String>, temps: DoubleArray) {
    var n = 0
    var sum = 0.0
    var max = Double.NEGATIVE_INFINITY
    for (t in temps) {
        if (t.isNaN()) continue
        n++
        sum += t
        if (t > max) max = t
    }
    if (n <= MAX_TEMP_LINES) {
        var label = "Temp"
        for (i in temps.indices) {
            if (temps[i].isNaN()) continue
            tempRow(sb, label, names[i], temps[i])
            label = ""
        }
        return
    }
    appendPadded(sb, "Temp", 5)
    val start = sb.length
    sb.append("cpu Ø (").append(n).append(')')
    padTo(sb, start, 15)
    appendDeg(sb, sum / n)
    sb.append("  max ")
    appendDeg(sb, max)
    sb.append('\n')
}

// ---------- Formatting ----------

/** Pads with spaces until the text since [start] is [width] long. */
private fun padTo(sb: StringBuilder, start: Int, width: Int) {
    while (sb.length - start < width) sb.append(' ')
}

/** [s] cut to [max] characters, padded to [width]. */
internal fun appendPadded(sb: StringBuilder, s: String, width: Int, max: Int = width) {
    val start = sb.length
    sb.append(s, 0, minOf(s.length, max))
    padTo(sb, start, width)
}

/**
 * [v] with [decimals] places, rounded half up, right-aligned to [width] — what
 * `"%{width}.{decimals}f".format(Locale.ROOT, v)` prints for the values osview shows
 * (tests pin it for every millidegree reading and the kB range). A negative value that
 * rounds to zero prints "0.0", not "-0.0".
 */
internal fun appendFixed(sb: StringBuilder, v: Double, decimals: Int, width: Int = 0) {
    val start = sb.length
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = Math.round(abs(v) * scale)
    if (v < 0 && scaled != 0L) sb.append('-')
    sb.append(scaled / scale)
    if (decimals > 0) {
        sb.append('.')
        val frac = scaled % scale
        var d = scale / 10
        while (d > 1 && frac < d) {
            sb.append('0')
            d /= 10
        }
        sb.append(frac)
    }
    while (sb.length - start < width) sb.insert(start, ' ')
}

/** [v] right-aligned to [width]. */
private fun appendInt(sb: StringBuilder, v: Long, width: Int) {
    val start = sb.length
    sb.append(v)
    while (sb.length - start < width) sb.insert(start, ' ')
}

internal fun appendBar(sb: StringBuilder, frac: Double) {
    val n = (frac.coerceIn(0.0, 1.0) * BAR_W + 0.5).toInt()
    sb.append('[')
    repeat(n) { sb.append('|') }
    repeat(BAR_W - n) { sb.append(' ') }
    sb.append(']')
}

/** " %3d%%" of [f] × 100, rounded. */
internal fun appendPct(sb: StringBuilder, f: Double) {
    sb.append(' ')
    appendInt(sb, (f * 100 + 0.5).toLong(), 3)
    sb.append('%')
}

/** kB with unit: K below 1M, whole M below 1G, else G with one decimal. */
internal fun appendKb(sb: StringBuilder, k: Long) {
    if (k >= 1L shl 20) {
        appendFixed(sb, k / 1048576.0, 1)
        sb.append('G')
    } else if (k >= 1L shl 10) {
        appendFixed(sb, k / 1024.0, 0)
        sb.append('M')
    } else {
        sb.append(k).append('K')
    }
}

/** "%5.1f°C" */
internal fun appendDeg(sb: StringBuilder, c: Double) {
    appendFixed(sb, c, 1, 5)
    sb.append("°C")
}

/** "%dd %02d:%02d" */
internal fun appendUptime(sb: StringBuilder, seconds: Long) {
    sb.append(seconds / 86400).append("d ")
    val h = seconds % 86400 / 3600
    val m = seconds % 3600 / 60
    if (h < 10) sb.append('0')
    sb.append(h).append(':')
    if (m < 10) sb.append('0')
    sb.append(m)
}

/** One usage line: label, bar, used/total. */
internal fun usageLine(sb: StringBuilder, label: String, usedKb: Long, totalKb: Long) {
    val f = if (totalKb > 0) usedKb.toDouble() / totalKb else 0.0
    appendPadded(sb, label, 5)
    appendBar(sb, f)
    sb.append(' ')
    appendKb(sb, usedKb)
    sb.append('/')
    appendKb(sb, totalKb)
    sb.append('\n')
}
