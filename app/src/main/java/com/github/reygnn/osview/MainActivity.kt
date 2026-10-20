package com.github.reygnn.osview

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
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
private const val CPU_DIR = "/sys/devices/system/cpu"

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var power: PowerManager
    private lateinit var activityManager: ActivityManager
    private val main = Handler(Looper.getMainLooper())

    // Resolved once in onCreate; a language change recreates the activity.
    private lateinit var header: String
    private lateinit var txtCpuLocked: String
    private lateinit var txtTempLocked: String
    private lateinit var txtNoCpuZones: String
    private lateinit var txtBattery: String
    private lateinit var txtSwapNone: String
    private lateinit var txtSwapNa: String

    // The /proc, /sys and binder reads run here; only the finished text goes to the main thread.
    private val worker = HandlerThread("osview-poll").apply { start() }
    private val bg = Handler(worker.looper)

    // ---- Worker-thread state: touched on the worker only. ----
    // Everything a tick needs is allocated once and reused, so a tick allocates next to
    // nothing on this side — essentially the finished String for the TextView.
    private val sb = StringBuilder(2048)
    private val reader = ByteReader()
    private val loadavgSrc = reader.source("/proc/loadavg")
    private val statSrc = reader.source("/proc/stat")
    private val meminfoSrc = reader.source("/proc/meminfo")
    private val mem = MemInfo()
    private val memFallback = ActivityManager.MemoryInfo()
    private val dataPath: String = Environment.getDataDirectory().path
    private var statFs: StatFs? = null
    private var cpu: Cpu? = null
    private var zones: Zones? = null
    private var lastHeadroom = Float.NaN

    /** Battery temperature in tenths of °C, from [battery]; Int.MIN_VALUE until known. */
    private var batteryDeci = Int.MIN_VALUE

    /** Delivered on the worker, and only when the battery state changes — no poll per tick. */
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batteryDeci = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        }
    }

    /** Hand-over to the main thread: one fixed Runnable instead of a lambda per tick. */
    @Volatile
    private var pending = ""
    private val show = Runnable { out.text = pending }

    private val tick = object : Runnable {
        override fun run() {
            pending = render()
            main.post(show)
            bg.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Per-core files and the two /proc/stat samples, set up once the core count is known. */
    private class Cpu(reader: ByteReader, val count: Int) {
        val online = Array(count) { reader.source("$CPU_DIR/cpu$it/online") }
        val cur = Array(count) { reader.source("$CPU_DIR/cpu$it/cpufreq/scaling_cur_freq") }
        val max = Array(count) { reader.source("$CPU_DIR/cpu$it/cpufreq/cpuinfo_max_freq") }

        /** cpuinfo_max_freq is a fixed hardware value: read until it was readable once. */
        val maxKhz = LongArray(count) { NO_VALUE }
        var now = StatSample(count)
        var prev = StatSample(count)
    }

    /** CPU thermal zones (found once) with their files and this tick's readings. */
    private class Zones(reader: ByteReader, val scan: ZoneScan) {
        val names = scan.cpu.map { it.first }
        val src = Array(scan.cpu.size) { reader.source(scan.cpu[it].second) }
        val temps = DoubleArray(scan.cpu.size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        power = getSystemService(PowerManager::class.java)
        activityManager = getSystemService(ActivityManager::class.java)
        header = "${Build.MODEL}  Android ${Build.VERSION.RELEASE}  up "
        txtCpuLocked = getString(R.string.cpu_locked)
        txtTempLocked = getString(R.string.temp_locked)
        txtNoCpuZones = getString(R.string.temp_no_cpu_zones)
        txtBattery = getString(R.string.battery)
        txtSwapNone = getString(R.string.swap_none)
        txtSwapNa = getString(R.string.swap_na)

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
            // Padding on the ScrollView (clipToPadding): scrolled text stays clear of the system bars.
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
        // ACTION_BATTERY_CHANGED is a protected system broadcast: no export flag needed.
        // The return value is the current sticky state, so the first frame already has it.
        val sticky = registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, bg)
        val initialBattery = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        bg.post {
            bg.removeCallbacks(tick)
            if (initialBattery != Int.MIN_VALUE) batteryDeci = initialBattery
            reader.unblockAll()
            tick.run()
        }
    }

    override fun onStop() {
        unregisterReceiver(battery)
        bg.post {
            bg.removeCallbacks(tick)
            // Drop both samples so a resume never averages over the pause.
            cpu?.let {
                it.now.clear()
                it.prev.clear()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun render(): String {
        val sb = sb
        sb.setLength(0)
        sb.append(header)
        appendUptime(sb, SystemClock.elapsedRealtime() / 1000)
        sb.append('\n')
        if (reader.read(loadavgSrc)) {
            sb.append("load ")
            appendLoadavg(sb, reader.buf, reader.len)
            sb.append('\n')
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
        val c = cpu ?: scanCpu().also { cpu = it }
        val t = c.prev
        c.prev = c.now
        c.now = t
        if (reader.read(statSrc)) parseStat(reader.buf, reader.len, c.now) else c.now.clear()
        val mode = cpuMode(c.now, c.prev)

        cpuHeader(sb, mode, cpuLoad(c.now, c.prev, 0), txtCpuLocked)

        for (i in 0 until c.count) {
            if (reader.read(c.online[i]) && parseLong(reader.buf, reader.len) == 0L) {
                offlineLine(sb, i)
                continue
            }
            val cur = if (reader.read(c.cur[i])) parseLong(reader.buf, reader.len) else NO_VALUE
            if (c.maxKhz[i] == NO_VALUE && reader.read(c.max[i])) {
                c.maxKhz[i] = parseLong(reader.buf, reader.len)
            }
            coreLine(sb, i, mode, cpuLoad(c.now, c.prev, i + 1), cur, c.maxKhz[i])
        }
    }

    /** Counts the cpuN directories once; offline cores keep theirs. */
    private fun scanCpu(): Cpu {
        var n = 0
        while (File("$CPU_DIR/cpu$n").exists()) n++
        return Cpu(reader, n)
    }

    // ---------- Temperatur ----------

    private fun thermal(sb: StringBuilder) {
        val z = zones ?: Zones(reader, findCpuZones()).also { zones = it }
        var readable = 0
        for (i in z.src.indices) {
            val t = if (reader.read(z.src[i])) parseTemp(parseLong(reader.buf, reader.len)) else Double.NaN
            z.temps[i] = t
            if (!t.isNaN()) readable++
        }

        when (tempState(z.scan, readable)) {
            TempState.NO_CPU_ZONES -> sb.append(txtNoCpuZones).append('\n')
            TempState.LOCKED -> sb.append(txtTempLocked).append('\n')
            TempState.OK -> cpuTempLines(sb, z.names, z.temps)
        }

        if (batteryDeci != Int.MIN_VALUE) tempRow(sb, "", txtBattery, batteryDeci / 10.0)

        // Headroom: 1.0 = Schwelle für starkes Throttling. NaN wenn zu oft abgefragt.
        val h = power.getThermalHeadroom(0)
        if (!h.isNaN()) lastHeadroom = h
        sb.append("Thrm ")
        if (!lastHeadroom.isNaN()) {
            appendBar(sb, lastHeadroom.toDouble())
            sb.append(' ')
            appendFixed(sb, lastHeadroom.toDouble(), 2)
            sb.append(' ')
        }
        sb.append(thermalStatusName(power.currentThermalStatus)).append('\n')
    }

    /** Sucht einmalig alle Thermalzonen, deren Typ nach CPU aussieht. */
    private fun findCpuZones(): ZoneScan {
        val zones = ArrayList<Pair<String, String>>()
        var unreadable = 0
        var i = 0
        while (i < 256) {
            val base = "/sys/class/thermal/thermal_zone$i"
            if (!File(base).exists()) break
            val type = if (reader.readPath("$base/type")) reader.text() else null
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
        val ok = reader.read(meminfoSrc)
        if (ok) parseMeminfo(reader.buf, reader.len, mem)
        val total: Long
        val avail: Long
        if (ok && mem.total != NO_VALUE) {
            total = mem.total
            avail = orElse(mem.available, orElse(mem.free, 0L))
        } else {
            activityManager.getMemoryInfo(memFallback)
            total = memFallback.totalMem / 1024
            avail = memFallback.availMem / 1024
        }
        usageLine(sb, "Mem", total - avail, total)

        if (!ok) {
            sb.append(txtSwapNa).append('\n')
            return
        }
        val st = orElse(mem.swapTotal, 0L)
        if (st > 0) usageLine(sb, "Swap", st - orElse(mem.swapFree, 0L), st) else sb.append(txtSwapNone).append('\n')
        sb.append("     cache ")
        appendKb(sb, orElse(mem.cached, 0L))
        sb.append("  buf ")
        appendKb(sb, orElse(mem.buffers, 0L))
        sb.append('\n')
    }

    // ---------- Disk ----------

    private fun disk(sb: StringBuilder) {
        val fs = statFs?.also { it.restat(dataPath) } ?: StatFs(dataPath).also { statFs = it }
        val total = fs.totalBytes / 1024
        // freeBytes, not availableBytes: the reserved blocks are free, not used (as in df).
        usageLine(sb, "Disk", total - fs.freeBytes / 1024, total)
    }
}
