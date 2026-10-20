package com.github.reygnn.osview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SysParseTest {

    // ---------- /proc/stat ----------

    private val stat = """
        cpu  100 10 50 800 40 5 5 0 0 0
        cpu0 50 5 25 400 20 0 0 0 0 0
        cpu1 50 5 25 400 20 5 5 0 0 0
        intr 12345 0 0
        cpu9 1 1 1 1 1 1 1 1
    """.trimIndent()

    @Test
    fun `parseStat takes idle plus iowait and the sum of the first eight fields`() {
        val m = parseStat(stat)
        assertArrayEquals(longArrayOf(840, 1010), m["cpu"])
        assertArrayEquals(longArrayOf(420, 500), m["cpu0"])
        assertArrayEquals(longArrayOf(420, 510), m["cpu1"])
    }

    @Test
    fun `parseStat stops at the first non-cpu line`() {
        assertEquals(setOf("cpu", "cpu0", "cpu1"), parseStat(stat).keys)
    }

    @Test
    fun `parseStat skips lines with too few fields and maps garbage to zero`() {
        val m = parseStat("cpu 1 2 3\ncpu0 1 x 1 10 0")
        assertNull(m["cpu"])
        assertArrayEquals(longArrayOf(10, 12), m["cpu0"])
    }

    @Test
    fun `parseStat of empty text is empty`() {
        assertTrue(parseStat("").isEmpty())
    }

    // ---------- CPU load ----------

    @Test
    fun `cpuLoad is the busy share of the total delta`() {
        val prev = mapOf("cpu" to longArrayOf(100, 200))
        val now = mapOf("cpu" to longArrayOf(125, 300)) // 25 idle of 100 -> 75 % busy
        assertEquals(0.75, cpuLoad(now, prev, "cpu")!!, 1e-9)
    }

    @Test
    fun `cpuLoad is null without a previous sample, a missing key or no progress`() {
        val now = mapOf("cpu" to longArrayOf(125, 300))
        assertNull(cpuLoad(now, null, "cpu"))
        assertNull(cpuLoad(null, now, "cpu"))
        assertNull(cpuLoad(now, now, "cpu0"))
        assertNull(cpuLoad(now, now, "cpu"))
        // A counter that went backwards (core hot-plugged) is not a load either.
        assertNull(cpuLoad(mapOf("cpu" to longArrayOf(0, 100)), now, "cpu"))
    }

    @Test
    fun `coreFraction prefers the load, falls back to clock ratio, else zero`() {
        assertEquals(0.3, coreFraction(0.3, 1000, 2000), 1e-9)
        assertEquals(0.5, coreFraction(null, 1000, 2000), 1e-9)
        assertEquals(0.0, coreFraction(null, 1000, 0), 1e-9)
        assertEquals(0.0, coreFraction(null, null, 2000), 1e-9)
        assertEquals(0.0, coreFraction(null, 1000, null), 1e-9)
    }

    // ---------- /proc/meminfo, /proc/loadavg ----------

    @Test
    fun `parseMeminfo reads kB values and skips junk lines`() {
        val m = parseMeminfo(
            """
            MemTotal:        7864320 kB
            MemFree:          123456 kB
            MemAvailable:    3932160 kB
            HugePages_Total:       0
            no colon here
            Broken:              abc kB
            """.trimIndent(),
        )
        assertEquals(7864320L, m["MemTotal"])
        assertEquals(3932160L, m["MemAvailable"])
        assertEquals(0L, m["HugePages_Total"])
        assertNull(m["Broken"])
        assertEquals(4, m.size)
    }

    @Test
    fun `parseLoadavg keeps the three load figures`() {
        assertEquals("1.23 0.98 0.75", parseLoadavg("1.23 0.98 0.75 2/1234 5678"))
    }

    // ---------- Temperature ----------

    @Test
    fun `parseTemp converts millidegrees and keeps whole degrees`() {
        assertEquals(42.5, parseTemp("42500")!!, 1e-9)
        assertEquals(42.0, parseTemp("42")!!, 1e-9)
    }

    @Test
    fun `parseTemp rejects implausible or unreadable values`() {
        assertNull(parseTemp("0"))
        assertNull(parseTemp("-273000"))
        assertNull(parseTemp("150000"))
        assertNull(parseTemp("abc"))
        assertNull(parseTemp(null))
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
        assertEquals("1024M", kb((1L shl 20) - 1))
        assertEquals("1.0G", kb(1L shl 20))
        assertEquals("7.5G", kb(7864320))
    }

    @Test
    fun `deg pads to a fixed width`() {
        assertEquals(" 42.5°C", deg(42.5))
        assertEquals("100.0°C", deg(100.0))
    }

    @Test
    fun `formatUptime splits days, hours and minutes`() {
        assertEquals("0d 00:00", formatUptime(59))
        assertEquals("1d 01:01", formatUptime(86400 + 3600 + 60 + 59))
    }

    @Test
    fun `formatters emit ASCII digits under a non-Latin default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("  50%", pct(0.5))
            assertEquals("1d 01:01", formatUptime(86400 + 3660))
            assertEquals(" 42.5°C", deg(42.5))
            assertEquals("7.5G", kb(7864320))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `usageLine renders label, bar and used over total`() {
        val sb = StringBuilder()
        usageLine(sb, "Mem", 1L shl 20, 2L shl 20)
        assertEquals("Mem  " + bar(0.5) + " 1.0G/2.0G\n", sb.toString())
    }

    @Test
    fun `usageLine with zero total shows an empty bar`() {
        val sb = StringBuilder()
        usageLine(sb, "Swap", 0, 0)
        assertEquals("Swap " + bar(0.0) + " 0K/0K\n", sb.toString())
    }
}
