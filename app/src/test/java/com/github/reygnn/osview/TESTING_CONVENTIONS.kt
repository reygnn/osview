package com.github.reygnn.osview

/*
 * Testing conventions for osview — technical reference.
 *
 * 1. Where logic goes
 *    Anything worth pinning (parsing, arithmetic, formatting, fallback decisions) lives
 *    in SysParse.kt as internal top-level functions taking bytes, numbers or a
 *    StringBuilder to append to. SysRead.kt holds the reused read buffer (plain java.io).
 *    MainActivity only does the I/O: file reads via ByteReader, StatFs, PowerManager,
 *    the battery broadcast. A new readout follows the same split: read in the Activity,
 *    parse here — allocation-free per tick (preallocated holders, NO_VALUE/NaN sentinels,
 *    append* formatters instead of String.format).
 *
 * 2. Test style
 *    JUnit 4, JVM-only, backtick test names that state the behaviour. Feed captured
 *    /proc or /sys samples as trimIndent() strings (toByteArray() for the parsers) —
 *    never read the host's /proc. ByteReader is tested against TemporaryFolder files.
 *    A hand-written formatter that replaces String.format gets a test pinning it to
 *    String.format over the value range osview shows.
 *
 * 3. Locale
 *    Output is a fixed-width monospace layout, so every formatter pins Locale.ROOT.
 *    A test that changes Locale.setDefault must restore it in finally.
 *
 * 4. Robolectric
 *    Opt-in per test via @RunWith(RobolectricTestRunner::class) and @Config(sdk = [36]),
 *    only when a test genuinely needs the Android runtime.
 */
@Suppress("unused")
private object TestingConventions
