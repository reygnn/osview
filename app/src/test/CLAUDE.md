# CLAUDE.md (test source set)

Orientation for sessions touching the tests. The concrete patterns live in
`java/com/github/reygnn/osview/TESTING_CONVENTIONS.kt`; the baseline philosophy
(JVM-only by default, pure logic outside Android-runtime classes) comes from
`~/.claude/CLAUDE.md`.

## What exists

JVM unit tests (`app/src/test`, `./gradlew testDebugUnitTest`):

```
SysParseTest   /proc/stat + CPU-load delta, per-core fallback, /proc/meminfo,
               /proc/loadavg, temperature units + plausibility, CPU zone names,
               thermal status, locked-vs-no-zones decision, bar/pct/kb/deg/uptime
               formatting, Locale.ROOT
```

## Deliberately not

- **No Robolectric test yet.** It is on the classpath (mirrors Verso), but nothing
  here needs an Android runtime: `MainActivity` only reads files and system
  services and hands the text to `SysParse.kt`.
- **No MockK, no coroutines.** The app has neither; the parsers take strings.
- **No test of `MainActivity` itself.** Its /proc and /sys reads are device
  behaviour (SELinux decides what an app may read) — verify on the device.
