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
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

private const val INTERVAL_MS = 1000L

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var power: PowerManager
    private val main = Handler(Looper.getMainLooper())

    // The /proc, /sys and binder reads run here; only the finished text goes to the main thread.
    // prevStat, maxKhz, zoneScan and lastHeadroom are touched on this thread only.
    private val worker = HandlerThread("osview-poll").apply { start() }
    private val bg = Handler(worker.looper)

    /** Last /proc/stat sample; cleared on stop so a resume never averages over the pause. */
    private var prevStat: Map<String, LongArray>? = null

    /** cpuinfo_max_freq per core index — fixed hardware value, cached once it was readable. */
    private val maxKhz = HashMap<Int, Long>()

    /** CPU-Thermalzonen (Name -> temp-Pfad), einmalig gesucht. */
    private var zoneScan: ZoneScan? = null
    private var lastHeadroom = Float.NaN

    private val tick = object : Runnable {
        override fun run() {
            val text = render()
            main.post { out.text = text }
            bg.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        power = getSystemService(PowerManager::class.java)
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(0xFFD0D0D0.toInt())
        }
        // Scrollable in both directions: with a large font scale or many cores the text no
        // longer fits. Horizontally the TextView gets unbounded width, so lines never wrap
        // and the columns stay aligned.
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(HorizontalScrollView(context).apply { addView(out) })
            // targetSdk 36 = edge-to-edge erzwungen -> Insets selbst anwenden.
            // Padding am ScrollView (clipToPadding): gescrollter Text bleibt aus den Systemleisten.
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val p = (12 * resources.displayMetrics.density).toInt()
                v.setPadding(i.left + p, i.top + p, i.right + p, i.bottom + p)
                insets
            }
        }
        setContentView(root)
    }

    // Polling nur solange sichtbar.
    // Start and stop are queued on the worker itself, so they serialize with a running tick:
    // a tick can never re-post itself after a stop, and a quick stop/start never runs two loops.
    override fun onStart() {
        super.onStart()
        bg.post {
            bg.removeCallbacks(tick)
            tick.run()
        }
    }

    override fun onStop() {
        bg.post {
            bg.removeCallbacks(tick)
            prevStat = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun render(): String {
        val sb = StringBuilder(1024)
        sb.append(Build.MODEL).append("  Android ").append(Build.VERSION.RELEASE)
            .append("  up ").append(formatUptime(SystemClock.elapsedRealtime() / 1000)).append('\n')
        read("/proc/loadavg")?.let {
            sb.append("load ").append(parseLoadavg(it)).append('\n')
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
        val now = read("/proc/stat")?.let(::parseStat)
        val prev = prevStat
        prevStat = now
        val mode = cpuMode(now, prev)

        cpuHeader(sb, mode, cpuLoad(now, prev, "cpu"), getString(R.string.cpu_locked))

        var i = 0
        while (true) {
            val base = "/sys/devices/system/cpu/cpu$i"
            if (!File(base).exists()) break
            val name = "cpu$i"
            if (read("$base/online") == "0") {
                sb.append(name.padEnd(5)).append("offline\n")
                i++
                continue
            }
            val cur = read("$base/cpufreq/scaling_cur_freq")?.toLongOrNull()
            val max = maxKhz[i]
                ?: read("$base/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.also { maxKhz[i] = it }
            coreLine(sb, name, mode, cpuLoad(now, prev, name), cur, max)
            i++
        }
    }

    // ---------- Temperatur ----------

    private fun thermal(sb: StringBuilder) {
        val scan = zoneScan ?: findCpuZones().also { zoneScan = it }
        val temps = scan.cpu.mapNotNull { (name, path) -> parseTemp(read(path))?.let { name to it } }

        when (tempState(scan, temps.size)) {
            TempState.NO_CPU_ZONES -> sb.append(getString(R.string.temp_no_cpu_zones)).append('\n')
            TempState.LOCKED -> sb.append(getString(R.string.temp_locked)).append('\n')
            TempState.OK -> cpuTempLines(sb, temps)
        }

        // Akku: immer verfügbar über den Sticky-Broadcast (Zehntel °C)
        val bt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (bt != Int.MIN_VALUE) {
            tempRow(sb, "", getString(R.string.battery), bt / 10.0)
        }

        // Headroom: 1.0 = Schwelle für starkes Throttling. NaN wenn zu oft abgefragt.
        val h = power.getThermalHeadroom(0)
        if (!h.isNaN()) lastHeadroom = h
        val status = thermalStatusName(power.currentThermalStatus)
        sb.append("Thrm ")
        if (!lastHeadroom.isNaN()) {
            sb.append(bar(lastHeadroom.toDouble()))
                .append(" %.2f ".format(Locale.ROOT, lastHeadroom))
        }
        sb.append(status).append('\n')
    }

    /** Sucht einmalig alle Thermalzonen, deren Typ nach CPU aussieht. */
    private fun findCpuZones(): ZoneScan {
        val zones = ArrayList<Pair<String, String>>()
        var unreadable = 0
        var i = 0
        while (i < 256) {
            val base = "/sys/class/thermal/thermal_zone$i"
            if (!File(base).exists()) break
            val type = read("$base/type")
            if (type == null) unreadable++
            if (type != null && isCpuZone(type.lowercase(Locale.ROOT))) {
                zones += type to "$base/temp"
            }
            i++
        }
        return ZoneScan(zones, total = i, unreadable = unreadable)
    }

    // ---------- RAM / Swap ----------

    private fun mem(sb: StringBuilder) {
        val mi = read("/proc/meminfo")?.let(::parseMeminfo)
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
        usageLine(sb, "Mem", total - avail, total)

        if (mi == null) {
            sb.append(getString(R.string.swap_na)).append('\n')
            return
        }
        val st = mi["SwapTotal"] ?: 0L
        if (st > 0) usageLine(sb, "Swap", st - (mi["SwapFree"] ?: 0L), st) else sb.append(getString(R.string.swap_none)).append('\n')
        sb.append("     cache ").append(kb(mi["Cached"] ?: 0L))
            .append("  buf ").append(kb(mi["Buffers"] ?: 0L)).append('\n')
    }

    // ---------- Disk ----------

    private fun disk(sb: StringBuilder) {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.totalBytes / 1024
        // freeBytes, not availableBytes: the reserved blocks are free, not used (as in df).
        usageLine(sb, "Disk", total - fs.freeBytes / 1024, total)
    }

    // ---------- Helfer ----------

    private fun read(path: String): String? =
        try { File(path).readText().trim() } catch (e: Exception) { null }
}
