package com.github.reygnn.osview

/*
 * Testing conventions for osview — technical reference.
 *
 * 1. Where logic goes
 *    Anything worth pinning (parsing, arithmetic, formatting, fallback decisions) lives
 *    in SysParse.kt as internal top-level functions taking plain strings/numbers.
 *    MainActivity only does the I/O: File reads, StatFs, PowerManager, the battery
 *    broadcast. A new readout follows the same split: read in the Activity, parse here.
 *
 * 2. Test style
 *    JUnit 4, JVM-only, backtick test names that state the behaviour. Feed captured
 *    /proc or /sys samples as trimIndent() strings — never read the host's /proc.
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
