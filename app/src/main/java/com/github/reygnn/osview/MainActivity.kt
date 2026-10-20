package com.github.reygnn.osview

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowInsets
import android.widget.TextView
import java.io.File
import java.util.Locale

private const val INTERVAL_MS = 1000L
private const val BAR_W = 16
private const val MAX_TEMP_LINES = 6

private val THERMAL_STATUS =
    arrayOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var power: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var prevStat: Map<String, LongArray>? = null

    /** CPU-Thermalzonen (Name -> temp-Pfad), einmalig gesucht. */
    private var cpuZones: List<Pair<String, String>>? = null
    private var lastHeadroom = Float.NaN

    private val tick = object : Runnable {
        override fun run() {
            out.text = render()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        power = getSystemService(PowerManager::class.java)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFFD0D0D0.toInt())
            setBackgroundColor(Color.BLACK)
            // targetSdk 36 = edge-to-edge erzwungen -> Insets selbst anwenden
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val p = (12 * resources.displayMetrics.density).toInt()
                v.setPadding(i.left + p, i.top + p, i.right + p, i.bottom + p)
                insets
            }
        }
        setContentView(out)
    }

    // Polling nur solange sichtbar
    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun render(): String {
        val sb = StringBuilder(1024)
        sb.append(Build.MODEL).append("  Android ").append(Build.VERSION.RELEASE)
            .append("  up ").append(uptime()).append('\n')
        read("/proc/loadavg")?.let {
            sb.append("load ").append(it.split(' ').take(3).joinToString(" ")).append('\n')
        }
        sb.append('\n')
        cpu(sb)
        sb.append('\n')
        thermal(sb)
        sb.append('\n')
        mem(sb)
        sb.append('\n')
        disk(sb)
        return sb.toString()
    }

    // ---------- CPU ----------

    private fun cpu(sb: StringBuilder) {
        val now = readStat()
        val prev = prevStat
        prevStat = now

        fun load(key: String): Double? {
            val a = now?.get(key) ?: return null
            val b = prev?.get(key) ?: return null
            val dTotal = a[1] - b[1]
            if (dTotal <= 0) return null
            return 1.0 - (a[0] - b[0]).toDouble() / dTotal
        }

        if (now == null) {
            sb.append("CPU-Last gesperrt, Balken = Takt\n")
        } else {
            load("cpu")?.let { sb.append("CPU  ").append(bar(it)).append(pct(it)).append('\n') }
        }

        var i = 0
        while (true) {
            val base = "/sys/devices/system/cpu/cpu$i"
            if (!File(base).exists()) break
            val name = "cpu$i".padEnd(5)
            if (read("$base/online") == "0") {
                sb.append(name).append("offline\n")
                i++
                continue
            }
            val cur = read("$base/cpufreq/scaling_cur_freq")?.toLongOrNull()
            val max = read("$base/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
            val l = load("cpu$i")
            val frac = l ?: if (cur != null && max != null && max > 0) cur.toDouble() / max else 0.0
            sb.append(name).append(bar(frac))
            if (l != null) sb.append(pct(l))
            if (cur != null) sb.append(' ').append(cur / 1000).append("MHz")
            sb.append('\n')
            i++
        }
    }

    /** /proc/stat -> name -> [idle, total]; null wenn von SELinux gesperrt. */
    private fun readStat(): Map<String, LongArray>? {
        val text = read("/proc/stat") ?: return null
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

    // ---------- Temperatur ----------

    private fun thermal(sb: StringBuilder) {
        val zones = cpuZones ?: findCpuZones().also { cpuZones = it }
        val temps = zones.mapNotNull { (name, path) -> temp(path)?.let { name to it } }

        when {
            zones.isEmpty() -> sb.append("Temp cpu: keine Zonen\n")
            temps.isEmpty() -> sb.append("Temp cpu: gesperrt\n")
            temps.size <= MAX_TEMP_LINES -> {
                var label = "Temp "
                for ((name, t) in temps) {
                    sb.append(label).append(name.take(14).padEnd(15)).append(deg(t)).append('\n')
                    label = "     "
                }
            }
            else -> {
                var max = temps[0].second
                var sum = 0.0
                for ((_, t) in temps) {
                    sum += t
                    if (t > max) max = t
                }
                sb.append("Temp cpu Ø ").append(deg(sum / temps.size))
                    .append("  max ").append(deg(max))
                    .append(" (").append(temps.size).append(")\n")
            }
        }

        // Akku: immer verfügbar über den Sticky-Broadcast (Zehntel °C)
        val bt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (bt != Int.MIN_VALUE) {
            sb.append("     ").append("akku".padEnd(15)).append(deg(bt / 10.0)).append('\n')
        }

        // Headroom: 1.0 = Schwelle für starkes Throttling. NaN wenn zu oft abgefragt.
        val h = power.getThermalHeadroom(0)
        if (!h.isNaN()) lastHeadroom = h
        val status = THERMAL_STATUS.getOrNull(power.currentThermalStatus) ?: "?"
        sb.append("Thrm ")
        if (!lastHeadroom.isNaN()) {
            sb.append(bar(lastHeadroom.toDouble()))
                .append(" %.2f ".format(Locale.ROOT, lastHeadroom))
        }
        sb.append(status).append('\n')
    }

    /** Sucht einmalig alle Thermalzonen, deren Typ nach CPU aussieht. */
    private fun findCpuZones(): List<Pair<String, String>> {
        val zones = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < 256) {
            val base = "/sys/class/thermal/thermal_zone$i"
            if (!File(base).exists()) break
            val type = read("$base/type")
            if (type != null && isCpuZone(type.lowercase(Locale.ROOT))) {
                zones += type to "$base/temp"
            }
            i++
        }
        return zones
    }

    // Qualcomm: cpu-*, cpuss-*; MediaTek: cpu_*; Tensor/Exynos: big/mid/little
    private fun isCpuZone(t: String): Boolean =
        "cpu" in t || t == "big" || t == "mid" || t == "little" || t.startsWith("cluster")

    /** Milligrad oder Grad -> °C; null bei unlesbar oder unplausibel (inaktive Zonen melden oft 0 oder -273). */
    private fun temp(path: String): Double? {
        val v = read(path)?.toLongOrNull() ?: return null
        val c = if (v > 1000 || v < -1000) v / 1000.0 else v.toDouble()
        return if (c > 0.0 && c < 150.0) c else null
    }

    private fun deg(c: Double): String = "%5.1f°C".format(Locale.ROOT, c)

    // ---------- RAM / Swap ----------

    private fun mem(sb: StringBuilder) {
        val mi = readMeminfo()
        val mt = mi?.get("MemTotal")
        val total: Long
        val avail: Long
        if (mi != null && mt != null) {
            total = mt
            avail = mi["MemAvailable"] ?: mi["MemFree"] ?: 0L
        } else {
            val info = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            total = info.totalMem / 1024
            avail = info.availMem / 1024
        }
        line(sb, "Mem", total - avail, total)

        if (mi == null) {
            sb.append("Swap n/a\n")
            return
        }
        val st = mi["SwapTotal"] ?: 0L
        if (st > 0) line(sb, "Swap", st - (mi["SwapFree"] ?: 0L), st) else sb.append("Swap keiner\n")
        sb.append("     cache ").append(kb(mi["Cached"] ?: 0L))
            .append("  buf ").append(kb(mi["Buffers"] ?: 0L)).append('\n')
    }

    /** /proc/meminfo -> Key -> kB */
    private fun readMeminfo(): Map<String, Long>? {
        val text = read("/proc/meminfo") ?: return null
        val m = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val c = line.indexOf(':')
            if (c < 0) continue
            m[line.substring(0, c)] =
                line.substring(c + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
        }
        return m
    }

    // ---------- Disk ----------

    private fun disk(sb: StringBuilder) {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes / 1024
        line(sb, "Disk", total - fs.availableBytes / 1024, total)
    }

    // ---------- Helfer ----------

    private fun read(path: String): String? =
        try { File(path).readText().trim() } catch (e: Exception) { null }

    private fun line(sb: StringBuilder, label: String, usedKb: Long, totalKb: Long) {
        val f = if (totalKb > 0) usedKb.toDouble() / totalKb else 0.0
        sb.append(label.padEnd(5)).append(bar(f)).append(' ')
            .append(kb(usedKb)).append('/').append(kb(totalKb)).append('\n')
    }

    private fun bar(frac: Double): String {
        val n = (frac.coerceIn(0.0, 1.0) * BAR_W + 0.5).toInt()
        return "[" + "|".repeat(n) + " ".repeat(BAR_W - n) + "]"
    }

    private fun pct(f: Double): String = " %3d%%".format((f * 100 + 0.5).toInt())

    private fun kb(k: Long): String = when {
        k >= 1L shl 20 -> "%.1fG".format(Locale.ROOT, k / 1048576.0)
        k >= 1L shl 10 -> "%.0fM".format(Locale.ROOT, k / 1024.0)
        else -> "${k}K"
    }

    private fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        return "%dd %02d:%02d".format(s / 86400, s % 86400 / 3600, s % 3600 / 60)
    }
}
