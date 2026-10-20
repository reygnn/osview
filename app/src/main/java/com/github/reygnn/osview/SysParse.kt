package com.github.reygnn.osview

import java.util.Locale

// Pure parsing / formatting logic, free of Android types so it runs in plain JVM tests.
// MainActivity does the file reads and system-service calls and hands the raw text here.

internal const val BAR_W = 16

/** Stands in the percentage column (same width as [pct]) while there is no load figure. */
internal const val PCT_NONE = "  --%"

/** Up to this many CPU zones are listed one per line; more are summarised in one line. */
internal const val MAX_TEMP_LINES = 6

/** Raw values above this magnitude are millidegrees, below it degrees or decidegrees. */
private const val MILLI_THRESHOLD = 1000L

/** Plausible range in °C; inactive zones often report 0 or -273. */
private const val TEMP_MIN_C = 0.0
private const val TEMP_MAX_C = 150.0

internal val THERMAL_STATUS =
    arrayOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** /proc/stat -> name -> [idle, total]. Only the leading cpu lines are read. */
internal fun parseStat(text: String): Map<String, LongArray> {
    val m = HashMap<String, LongArray>()
    for (line in text.lineSequence()) {
        if (!line.startsWith("cpu")) break
        val f = line.split(' ').filter { it.isNotEmpty() }
        val v = f.drop(1).take(8).map { it.toLongOrNull() ?: 0L }
        if (v.size < 5) continue
        m[f[0]] = longArrayOf(v[3] + v[4], v.sum()) // idle + iowait, total
    }
    return m
}

/** Busy fraction of [key] between two /proc/stat samples; null without a usable delta. */
internal fun cpuLoad(
    now: Map<String, LongArray>?,
    prev: Map<String, LongArray>?,
    key: String,
): Double? {
    val a = now?.get(key) ?: return null
    val b = prev?.get(key) ?: return null
    val dTotal = a[1] - b[1]
    if (dTotal <= 0) return null
    return 1.0 - (a[0] - b[0]).toDouble() / dTotal
}

/**
 * What the CPU bars mean in this frame. LOCKED: /proc/stat is unreadable, bars show the
 * clock (and the screen says so). WARMUP: readable, but there is no previous sample to
 * form a delta yet (first frame after start or resume). LOAD: bars show the measured load.
 */
internal enum class CpuMode { LOCKED, WARMUP, LOAD }

internal fun cpuMode(now: Map<String, LongArray>?, prev: Map<String, LongArray>?): CpuMode = when {
    now == null -> CpuMode.LOCKED
    cpuLoad(now, prev, "cpu") == null -> CpuMode.WARMUP
    else -> CpuMode.LOAD
}

/**
 * Bar fill for one core. Only when LOCKED is the bar the clock relative to its max;
 * otherwise it is the load, and empty without one — it never silently switches meaning.
 */
internal fun coreFraction(mode: CpuMode, load: Double?, curKhz: Long?, maxKhz: Long?): Double =
    if (mode == CpuMode.LOCKED) {
        if (curKhz != null && maxKhz != null && maxKhz > 0) curKhz.toDouble() / maxKhz else 0.0
    } else {
        load ?: 0.0
    }

/** The overall CPU line: [lockedText] when LOCKED, else bar and load (placeholder in WARMUP). */
internal fun cpuHeader(sb: StringBuilder, mode: CpuMode, load: Double?, lockedText: String) {
    if (mode == CpuMode.LOCKED) {
        sb.append(lockedText).append('\n')
        return
    }
    val l = if (mode == CpuMode.LOAD) load else null
    sb.append("CPU  ").append(bar(l ?: 0.0)).append(l?.let(::pct) ?: PCT_NONE).append('\n')
}

/** One per-core line: name, bar, load column (not when LOCKED), current clock. */
internal fun coreLine(sb: StringBuilder, name: String, mode: CpuMode, load: Double?, curKhz: Long?, maxKhz: Long?) {
    val l = if (mode == CpuMode.LOAD) load else null
    sb.append(name.padEnd(5)).append(bar(coreFraction(mode, l, curKhz, maxKhz)))
    if (mode != CpuMode.LOCKED) sb.append(l?.let(::pct) ?: PCT_NONE)
    if (curKhz != null) sb.append(' ').append(curKhz / 1000).append("MHz")
    sb.append('\n')
}

/** /proc/meminfo -> Key -> kB */
internal fun parseMeminfo(text: String): Map<String, Long> {
    val m = HashMap<String, Long>()
    for (line in text.lineSequence()) {
        val c = line.indexOf(':')
        if (c < 0) continue
        m[line.substring(0, c)] =
            line.substring(c + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
    }
    return m
}

/** First three fields of /proc/loadavg. */
internal fun parseLoadavg(text: String): String = text.split(' ').take(3).joinToString(" ")

/**
 * Millidegrees, degrees or decidegrees -> °C; null when unparsable or implausible.
 * Above ±1000 the value is millidegrees. Below, whole degrees win when plausible
 * (so 100 stays 100 °C); otherwise decidegrees are tried (450 -> 45.0 °C).
 */
internal fun parseTemp(raw: String?): Double? {
    val v = raw?.toLongOrNull() ?: return null
    if (v > MILLI_THRESHOLD || v < -MILLI_THRESHOLD) return plausibleTemp(v / 1000.0)
    return plausibleTemp(v.toDouble()) ?: plausibleTemp(v / 10.0)
}

private fun plausibleTemp(c: Double): Double? = if (c > TEMP_MIN_C && c < TEMP_MAX_C) c else null

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
 * One temperature row: 5-column label, 15-column name (at most 14 shown), value, optional
 * [suffix]. Every row in the Temp block goes through here, so the values share one column.
 */
internal fun tempRow(sb: StringBuilder, label: String, name: String, c: Double, suffix: String = "") {
    sb.append(label.padEnd(5)).append(name.take(14).padEnd(15)).append(deg(c)).append(suffix).append('\n')
}

/** CPU zone temperatures: one row each up to [MAX_TEMP_LINES], else average, max and count. */
internal fun cpuTempLines(sb: StringBuilder, temps: List<Pair<String, Double>>) {
    if (temps.size <= MAX_TEMP_LINES) {
        var label = "Temp"
        for ((name, t) in temps) {
            tempRow(sb, label, name, t)
            label = ""
        }
        return
    }
    val avg = temps.sumOf { it.second } / temps.size
    val max = temps.maxOf { it.second }
    tempRow(sb, "Temp", "cpu Ø (${temps.size})", avg, "  max " + deg(max))
}

internal fun bar(frac: Double): String {
    val n = (frac.coerceIn(0.0, 1.0) * BAR_W + 0.5).toInt()
    return "[" + "|".repeat(n) + " ".repeat(BAR_W - n) + "]"
}

internal fun pct(f: Double): String = " %3d%%".format(Locale.ROOT, (f * 100 + 0.5).toInt())

internal fun kb(k: Long): String = when {
    k >= 1L shl 20 -> "%.1fG".format(Locale.ROOT, k / 1048576.0)
    k >= 1L shl 10 -> "%.0fM".format(Locale.ROOT, k / 1024.0)
    else -> "${k}K"
}

internal fun deg(c: Double): String = "%5.1f°C".format(Locale.ROOT, c)

internal fun formatUptime(seconds: Long): String =
    "%dd %02d:%02d".format(Locale.ROOT, seconds / 86400, seconds % 86400 / 3600, seconds % 3600 / 60)

/** One usage line: label, bar, used/total. */
internal fun usageLine(sb: StringBuilder, label: String, usedKb: Long, totalKb: Long) {
    val f = if (totalKb > 0) usedKb.toDouble() / totalKb else 0.0
    sb.append(label.padEnd(5)).append(bar(f)).append(' ')
        .append(kb(usedKb)).append('/').append(kb(totalKb)).append('\n')
}
