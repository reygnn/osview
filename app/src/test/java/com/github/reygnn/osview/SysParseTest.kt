package com.github.reygnn.osview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SysParseTest {

    // Parsers take the raw bytes ByteReader delivers; formatters append to a StringBuilder.
    private fun bytes(text: String) = text.toByteArray()
    private fun out(block: (StringBuilder) -> Unit) = StringBuilder().also(block).toString()
    private fun bar(f: Double) = out { appendBar(it, f) }
    private fun deg(c: Double) = out { appendDeg(it, c) }
    private fun pct(f: Double) = out { appendPct(it, f) }
    private fun kb(k: Long) = out { appendKb(it, k) }
    private fun long(text: String) = bytes(text).let { parseLong(it, it.size) }

    private fun stat(text: String, cores: Int = 16) =
        StatSample(cores).also { s -> bytes(text).let { parseStat(it, it.size, s) } }

    private fun meminfo(text: String) =
        MemInfo().also { m -> bytes(text).let { parseMeminfo(it, it.size, m) } }

    // ---------- Integers ----------

    @Test
    fun `parseLong reads the whole content like trim toLongOrNull`() {
        assertEquals(42500L, long("42500\n"))
        assertEquals(-273000L, long("  -273000 \n"))
        assertEquals(7L, long("+7"))
        assertEquals(0L, long("0"))
    }

    @Test
    fun `parseLong rejects junk, empty content and overflow`() {
        for (t in listOf("", "  \n", "abc", "12 34", "1x", "-", "99999999999999999999")) {
            assertEquals(t, NO_VALUE, long(t))
        }
    }

    // ---------- /proc/stat ----------

    private val statText = """
        cpu  100 10 50 800 40 5 5 0 0 0
        cpu0 50 5 25 400 20 0 0 0 0 0
        cpu1 50 5 25 400 20 5 5 0 0 0
        intr 12345 0 0
        cpu9 1 1 1 1 1 1 1 1
    """.trimIndent()

    @Test
    fun `parseStat takes idle plus iowait and the sum of the first eight fields`() {
        val s = stat(statText)
        assertTrue(s.present)
        assertEquals(840L, s.idle[0]); assertEquals(1010L, s.total[0])
        assertEquals(420L, s.idle[1]); assertEquals(500L, s.total[1])
        assertEquals(420L, s.idle[2]); assertEquals(510L, s.total[2])
    }

    @Test
    fun `parseStat stops at the first non-cpu line`() {
        val s = stat(statText)
        assertEquals(listOf(0, 1, 2), s.valid.indices.filter { s.valid[it] })
    }

    @Test
    fun `parseStat skips lines with too few fields and maps garbage to zero`() {
        val s = stat("cpu 1 2 3\ncpu0 1 x 1 10 0")
        assertFalse(s.valid[0])
        assertTrue(s.valid[1])
        assertEquals(10L, s.idle[1]); assertEquals(12L, s.total[1])
    }

    @Test
    fun `parseStat of empty text is present but has no cores`() {
        val s = stat("")
        assertTrue(s.present)
        assertFalse(s.valid.any { it })
    }

    @Test
    fun `parseStat ignores cores beyond the capacity and unknown cpu-prefixed lines`() {
        val s = stat("cpu  1 1 1 10 0\ncpufoo 9 9 9 9 9\ncpu7 1 1 1 1 1", cores = 2)
        assertEquals(10L, s.idle[0]) // not overwritten by "cpufoo"
        assertEquals(3, s.valid.size)
        assertFalse(s.valid[1] || s.valid[2])
    }

    @Test
    fun `parseStat clears the previous content of the sample`() {
        val s = stat(statText)
        bytes("cpu 1 1 1 1 1").let { parseStat(it, it.size, s) }
        assertTrue(s.valid[0])
        assertFalse(s.valid[1])
    }

    // ---------- CPU load and mode ----------

    private fun sample(vararg slots: Pair<Long, Long>) = StatSample(4).apply {
        present = true
        slots.forEachIndexed { i, (idle, total) ->
            this.idle[i] = idle; this.total[i] = total; valid[i] = true
        }
    }

    private val prevSample = sample(100L to 200L, 50L to 100L)
    private val nowSample = sample(125L to 300L, 75L to 150L)
    private val absent = StatSample(4)

    @Test
    fun `cpuLoad is the busy share of the total delta`() {
        assertEquals(0.75, cpuLoad(nowSample, prevSample, 0), 1e-9) // 25 idle of 100
        assertEquals(0.5, cpuLoad(nowSample, prevSample, 1), 1e-9)
    }

    @Test
    fun `cpuLoad is NaN without a previous sample, a missing slot or no progress`() {
        assertTrue(cpuLoad(nowSample, absent, 0).isNaN())
        assertTrue(cpuLoad(absent, nowSample, 0).isNaN())
        assertTrue(cpuLoad(nowSample, prevSample, 2).isNaN())
        assertTrue(cpuLoad(nowSample, prevSample, 99).isNaN())
        assertTrue(cpuLoad(nowSample, nowSample, 0).isNaN())
        // A counter that went backwards (core hot-plugged) is not a load either.
        assertTrue(cpuLoad(sample(0L to 100L), nowSample, 0).isNaN())
    }

    @Test
    fun `cpuMode is LOCKED without a sample, WARMUP without a delta, else LOAD`() {
        assertEquals(CpuMode.LOCKED, cpuMode(absent, prevSample))
        assertEquals(CpuMode.LOCKED, cpuMode(absent, absent))
        assertEquals(CpuMode.WARMUP, cpuMode(nowSample, absent))
        assertEquals(CpuMode.WARMUP, cpuMode(nowSample, nowSample))
        assertEquals(CpuMode.LOAD, cpuMode(nowSample, prevSample))
    }

    @Test
    fun `coreFraction uses the clock ratio only when LOCKED`() {
        val nan = Double.NaN
        assertEquals(0.5, coreFraction(CpuMode.LOCKED, nan, 1000, 2000), 1e-9)
        assertEquals(0.0, coreFraction(CpuMode.LOCKED, nan, 1000, 0), 1e-9)
        assertEquals(0.0, coreFraction(CpuMode.LOCKED, nan, NO_VALUE, 2000), 1e-9)
        assertEquals(0.0, coreFraction(CpuMode.LOCKED, nan, 1000, NO_VALUE), 1e-9)
        assertEquals(0.3, coreFraction(CpuMode.LOAD, 0.3, 1000, 2000), 1e-9)
        // No load figure while /proc/stat is readable: empty bar, never the clock.
        assertEquals(0.0, coreFraction(CpuMode.LOAD, nan, 1000, 2000), 1e-9)
        assertEquals(0.0, coreFraction(CpuMode.WARMUP, nan, 1000, 2000), 1e-9)
    }

    // ---------- CPU lines ----------

    @Test
    fun `cpuHeader shows the locked text, a placeholder while warming up, else the load`() {
        assertEquals("locked!\n", out { cpuHeader(it, CpuMode.LOCKED, Double.NaN, "locked!") })
        assertEquals("CPU  " + bar(0.0) + PCT_NONE + "\n", out { cpuHeader(it, CpuMode.WARMUP, Double.NaN, "x") })
        assertEquals("CPU  " + bar(0.75) + "  75%\n", out { cpuHeader(it, CpuMode.LOAD, 0.75, "x") })
    }

    @Test
    fun `coreLine shows the clock bar without a load column when LOCKED`() {
        assertEquals(
            "cpu0 " + bar(0.5) + " 1000MHz\n",
            out { coreLine(it, 0, CpuMode.LOCKED, Double.NaN, 1_000_000, 2_000_000) },
        )
    }

    @Test
    fun `coreLine shows an empty bar and placeholder while warming up`() {
        assertEquals(
            "cpu0 " + bar(0.0) + PCT_NONE + " 1000MHz\n",
            out { coreLine(it, 0, CpuMode.WARMUP, Double.NaN, 1_000_000, 2_000_000) },
        )
    }

    @Test
    fun `coreLine shows the load, and a placeholder for a core without a stat line`() {
        assertEquals(
            "cpu1 " + bar(0.5) + "  50%" + " 1800MHz\n",
            out { coreLine(it, 1, CpuMode.LOAD, 0.5, 1_800_000, 2_000_000) },
        )
        assertEquals(
            "cpu2 " + bar(0.0) + PCT_NONE + " 1800MHz\n",
            out { coreLine(it, 2, CpuMode.LOAD, Double.NaN, 1_800_000, 2_000_000) },
        )
    }

    @Test
    fun `coreLine omits the clock when it is unreadable`() {
        assertEquals("cpu10" + bar(0.25) + "  25%\n", out { coreLine(it, 10, CpuMode.LOAD, 0.25, NO_VALUE, NO_VALUE) })
    }

    @Test
    fun `offlineLine pads the core name like the other lines`() {
        assertEquals("cpu3 offline\n", out { offlineLine(it, 3) })
    }

    @Test
    fun `PCT_NONE is as wide as the percentage column`() {
        assertEquals(pct(0.5).length, PCT_NONE.length)
    }

    // ---------- /proc/meminfo, /proc/loadavg ----------

    @Test
    fun `parseMeminfo reads the shown fields in kB and skips junk lines`() {
        val m = meminfo(
            """
            MemTotal:        7864320 kB
            MemFree:          123456 kB
            MemAvailable:    3932160 kB
            Buffers:            1024 kB
            Cached:          2097152 kB
            SwapCached:         4096 kB
            SwapTotal:       4194304 kB
            SwapFree:        4000000 kB
            HugePages_Total:       0
            no colon here
            """.trimIndent(),
        )
        assertEquals(7864320L, m.total)
        assertEquals(123456L, m.free)
        assertEquals(3932160L, m.available)
        assertEquals(1024L, m.buffers)
        assertEquals(2097152L, m.cached) // not confused with SwapCached
        assertEquals(4194304L, m.swapTotal)
        assertEquals(4000000L, m.swapFree)
    }

    @Test
    fun `parseMeminfo leaves missing or unparsable fields at NO_VALUE`() {
        val m = meminfo("MemTotal: abc kB\nMemFree: 5 kB")
        assertEquals(NO_VALUE, m.total)
        assertEquals(5L, m.free)
        assertEquals(NO_VALUE, m.available)
        assertEquals(NO_VALUE, m.swapTotal)
    }

    @Test
    fun `parseMeminfo clears the previous content`() {
        val m = meminfo("MemTotal: 10 kB")
        bytes("MemFree: 5 kB").let { parseMeminfo(it, it.size, m) }
        assertEquals(NO_VALUE, m.total)
    }

    @Test
    fun `orElse falls back only for NO_VALUE`() {
        assertEquals(0L, orElse(0L, 9L))
        assertEquals(9L, orElse(NO_VALUE, 9L))
        assertEquals(9L, orElse(NO_VALUE, orElse(NO_VALUE, 9L)))
    }

    @Test
    fun `appendLoadavg keeps the three load figures`() {
        assertEquals("1.23 0.98 0.75", out { s -> bytes("1.23 0.98 0.75 2/1234 5678\n").let { appendLoadavg(s, it, it.size) } })
        assertEquals("1.23 0.98 0.75", out { s -> bytes("1.23 0.98 0.75\n").let { appendLoadavg(s, it, it.size) } })
    }

    // ---------- Temperature ----------

    private fun temp(text: String) = parseTemp(long(text))

    @Test
    fun `parseTemp converts millidegrees and keeps whole degrees`() {
        assertEquals(42.5, temp("42500"), 1e-9)
        assertEquals(42.0, temp("42"), 1e-9)
    }

    @Test
    fun `parseTemp reads decidegrees when whole degrees are implausible`() {
        assertEquals(45.0, temp("450"), 1e-9)
        assertEquals(15.1, temp("151"), 1e-9)
        assertEquals(99.9, temp("999"), 1e-9)
        // Plausible as whole degrees: stays whole degrees.
        assertEquals(100.0, temp("100"), 1e-9)
        assertEquals(149.0, temp("149"), 1e-9)
    }

    @Test
    fun `parseTemp rejects implausible or unreadable values`() {
        for (t in listOf("0", "-5", "-273000", "150000", "abc", "")) {
            assertTrue(t, temp(t).isNaN())
        }
        assertTrue(parseTemp(NO_VALUE).isNaN())
    }

    @Test
    fun `isCpuZone matches vendor naming schemes`() {
        for (t in listOf("cpu-0-0-usr", "cpuss-1", "cpu_little0", "big", "mid", "little", "cluster0")) {
            assertTrue(t, isCpuZone(t))
        }
        for (t in listOf("battery", "skin-therm", "gpu", "bigger")) {
            assertFalse(t, isCpuZone(t))
        }
    }

    private val cpuZone = listOf("cpu0" to "/sys/class/thermal/thermal_zone0/temp")

    @Test
    fun `tempState is OK as soon as one temperature was read`() {
        assertEquals(TempState.OK, tempState(ZoneScan(cpuZone, total = 1, unreadable = 0), 1))
    }

    @Test
    fun `tempState is LOCKED when zone types exist but can't be read`() {
        // Samsung A17: seven zones, every type file denied by SELinux.
        assertEquals(TempState.LOCKED, tempState(ZoneScan(emptyList(), total = 7, unreadable = 7), 0))
    }

    @Test
    fun `tempState is LOCKED when CPU zones were found but no temperature reads`() {
        assertEquals(TempState.LOCKED, tempState(ZoneScan(cpuZone, total = 1, unreadable = 0), 0))
    }

    @Test
    fun `tempState is LOCKED when no zone is visible at all`() {
        // Samsung A17 as seen by the app: SELinux denies even the stat on thermal_zone0.
        assertEquals(TempState.LOCKED, tempState(ZoneScan(emptyList(), total = 0, unreadable = 0), 0))
    }

    @Test
    fun `tempState is NO_CPU_ZONES when readable zones contain no CPU zone`() {
        assertEquals(TempState.NO_CPU_ZONES, tempState(ZoneScan(emptyList(), total = 5, unreadable = 0), 0))
    }

    @Test
    fun `cpuTempLines lists readable zones with the label on the first row only`() {
        val names = listOf("cpu-0-0-usr", "locked-zone", "a-very-long-zone-name")
        val temps = doubleArrayOf(41.5, Double.NaN, 42.0)
        assertEquals(
            "Temp cpu-0-0-usr    " + deg(41.5) + "\n" +
                "     a-very-long-zo " + deg(42.0) + "\n",
            out { cpuTempLines(it, names, temps) },
        )
    }

    @Test
    fun `cpuTempLines summarises more than six readable zones in the shared value column`() {
        val names = (1..9).map { "cpu$it" }
        val temps = DoubleArray(9) { 41.0 + it }.also { it[8] = Double.NaN } // 41..48 readable -> Ø 44.5
        assertEquals(
            "Temp cpu Ø (8)      " + deg(44.5) + "  max " + deg(48.0) + "\n",
            out { cpuTempLines(it, names, temps) },
        )
    }

    @Test
    fun `cpuTempLines with exactly six readable zones still lists them`() {
        val names = (1..8).map { "z$it" }
        val temps = DoubleArray(8) { 40.0 }.also { it[0] = Double.NaN; it[7] = Double.NaN }
        assertEquals(MAX_TEMP_LINES, out { cpuTempLines(it, names, temps) }.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `tempRow puts the value in the same column as the summary`() {
        val summary = out { cpuTempLines(it, (1..7).map { n -> "z$n" }, DoubleArray(7) { 44.0 }) }
        val battery = out { tempRow(it, "", "battery", 31.0) }
        assertEquals(summary.indexOf(deg(44.0)), battery.indexOf(deg(31.0)))
        assertEquals("     battery        " + deg(31.0) + "\n", battery)
    }

    @Test
    fun `thermalStatusName maps known codes and marks unknown ones`() {
        assertEquals("none", thermalStatusName(0))
        assertEquals("shutdown", thermalStatusName(6))
        assertEquals("?", thermalStatusName(7))
        assertEquals("?", thermalStatusName(-1))
    }

    // ---------- Formatting ----------

    @Test
    fun `bar rounds to the nearest cell and clamps`() {
        assertEquals("[" + " ".repeat(BAR_W) + "]", bar(0.0))
        assertEquals("[" + "|".repeat(BAR_W) + "]", bar(1.0))
        assertEquals("[" + "|".repeat(8) + " ".repeat(8) + "]", bar(0.5))
        assertEquals(bar(0.0), bar(-1.0))
        assertEquals(bar(1.0), bar(2.5))
        assertEquals(BAR_W + 2, bar(0.37).length)
    }

    @Test
    fun `pct rounds and pads to three digits`() {
        assertEquals("   5%", pct(0.049))
        assertEquals("  50%", pct(0.5))
        assertEquals(" 100%", pct(1.0))
    }

    @Test
    fun `kb switches units at 1M and 1G`() {
        assertEquals("1023K", kb(1023))
        assertEquals("1M", kb(1024))
        assertEquals("2M", kb(1536)) // half up, like %.0f
        assertEquals("1024M", kb((1L shl 20) - 1))
        assertEquals("1.0G", kb(1L shl 20))
        assertEquals("7.5G", kb(7864320))
    }

    @Test
    fun `kb matches String format over the whole range`() {
        var k = 1L
        while (k < 1L shl 40) {
            val expected = when {
                k >= 1L shl 20 -> "%.1fG".format(Locale.ROOT, k / 1048576.0)
                k >= 1L shl 10 -> "%.0fM".format(Locale.ROOT, k / 1024.0)
                else -> "${k}K"
            }
            assertEquals("k=$k", expected, kb(k))
            k = k * 9 / 8 + 1
        }
    }

    @Test
    fun `deg pads to a fixed width`() {
        assertEquals(" 42.5°C", deg(42.5))
        assertEquals("100.0°C", deg(100.0))
        assertEquals("  1.0°C", deg(1.0))
    }

    @Test
    fun `deg matches String format for every millidegree reading`() {
        for (raw in 1..149_999) {
            val c = raw / 1000.0
            assertEquals("raw=$raw", "%5.1f°C".format(Locale.ROOT, c), deg(c))
        }
    }

    @Test
    fun `appendFixed pads, zero-fills the fraction and keeps the sign`() {
        assertEquals("0.05", out { appendFixed(it, 0.05, 2) })
        assertEquals("0.85", out { appendFixed(it, 0.85f.toDouble(), 2) })
        assertEquals("1.00", out { appendFixed(it, 0.999, 2) })
        assertEquals(" -1.5", out { appendFixed(it, -1.5, 1, 5) })
        assertEquals("0.0", out { appendFixed(it, -0.01, 1) })
        assertEquals("3", out { appendFixed(it, 2.5, 0) })
    }

    @Test
    fun `appendPadded cuts and pads`() {
        assertEquals("ab   ", out { appendPadded(it, "ab", 5) })
        assertEquals("abcd ", out { appendPadded(it, "abcdefg", 5, 4) })
    }

    @Test
    fun `formatUptime splits days, hours and minutes`() {
        assertEquals("0d 00:00", out { appendUptime(it, 59) })
        assertEquals("1d 01:01", out { appendUptime(it, 86400 + 3600 + 60 + 59) })
        assertEquals("12d 23:59", out { appendUptime(it, 12 * 86400 + 86399) })
    }

    @Test
    fun `formatters emit ASCII digits under a non-Latin default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("  50%", pct(0.5))
            assertEquals("1d 01:01", out { appendUptime(it, 86400 + 3660) })
            assertEquals(" 42.5°C", deg(42.5))
            assertEquals("7.5G", kb(7864320))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `usageLine renders label, bar and used over total`() {
        assertEquals("Mem  " + bar(0.5) + " 1.0G/2.0G\n", out { usageLine(it, "Mem", 1L shl 20, 2L shl 20) })
    }

    @Test
    fun `usageLine with zero total shows an empty bar`() {
        assertEquals("Swap " + bar(0.0) + " 0K/0K\n", out { usageLine(it, "Swap", 0, 0) })
    }
}
