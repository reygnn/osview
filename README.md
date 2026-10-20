# osview

A **tiny htop-style system monitor for Android** — one screen of monospace text,
refreshed every second. No dependencies, no permissions, a ~20 KB APK.

<p>
  <img src="docs/screenshots/pixel9a.png" alt="osview on a Pixel 9a (Android 17): CPU clocks, temperatures, memory and disk" width="270">
  &nbsp;
  <img src="docs/screenshots/a17.png" alt="osview on a Samsung Galaxy A17 (Android 16): CPU clocks, temperatures, memory and disk" width="270">
</p>

## What it shows

- **Header** — device model, Android version, uptime, load average (where readable)
- **CPU** — overall and per-core load with a bar per core, plus the current clock.
  Where `/proc/stat` is locked (most devices today), the bars show the clock relative
  to the core's maximum instead, and the screen says so. Offline cores are marked.
- **Temperature** — CPU thermal zones (Qualcomm, MediaTek, Tensor/Exynos naming),
  listed individually or as average/max when there are many; the battery temperature;
  the system's thermal headroom (1.0 = heavy throttling ahead) and thermal status.
- **Memory** — RAM, swap, cache and buffers
- **Disk** — used/total of the data partition

Polling runs only while the app is visible, on a background thread, and allocates
next to nothing per refresh beyond the text it shows.

## What it can't show

An ordinary app sees only what SELinux allows, and vendors differ. On both a Pixel 9a
and a Samsung Galaxy A17, for example, CPU load, load average and the CPU thermal zones
are locked — osview then says *locked* rather than guessing, and falls back to what is
available (clock speeds, battery temperature, thermal headroom). No root, no workarounds.

## Languages

English and German, following the system language. Column labels (`CPU`, `Mem`,
`Swap`, `Disk`, `Temp`, `Thrm`) stay the same in both, htop style.

## Build

Requirements: JDK 21, Android SDK 36.

```sh
./gradlew bundleRelease       # unsigned release AAB in app/build/outputs/bundle/release/
./gradlew testDebugUnitTest   # JVM unit tests
```

Android 16+ only (`minSdk 36`). Kotlin via AGP 9's built-in Kotlin, no Compose, no
AndroidX — just the platform and the Kotlin stdlib, shrunk with R8.

## Structure

| File | Role |
|---|---|
| `MainActivity.kt` | Reads `/proc`, `/sys`, `StatFs`, `PowerManager`, the battery broadcast; renders the text |
| `SysRead.kt` | One reused read buffer for the `/proc` and `/sys` files; skips files SELinux denies |
| `SysParse.kt` | Pure parsing and formatting on bytes — no Android types, no allocations per tick, covered by JVM tests |

## License

[GPL-3.0](LICENSE)
