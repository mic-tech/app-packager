# App Packager

An Android app that packages an installed app into an `.xapk` — a zip holding
the base APK, every split APK, the app icon and a `manifest.json` — so a
complete app can be reinstalled on another device.

## What it does

- Lists installed apps with icon, package name, version, on-disk size, and a
  badge showing how many split APKs an app has.
- Search, multi-select, and "select all shown".
- Writes to a folder you pick once through the Storage Access Framework, so no
  broad storage permission is needed. The choice is remembered.
- Backs up in a foreground service with a progress notification and a Cancel
  action, so a multi-gigabyte copy survives you leaving the app.
- Optionally pulls in OBB expansion files from `Android/obb/<package>`.

## Output format

| App has | Result |
| --- | --- |
| Split APKs, or an OBB | `Label_version_versionCode.xapk` |
| A single APK only | `Label_version_versionCode.apk` (tap to install) |

Turn on **Always package as XAPK** in Options to get a `.xapk` either way.

The `.xapk` is a zip containing:

```
base.apk
config.arm64_v8a.apk          # one per split, named after the split's own id
config.en.apk
config.xxhdpi.apk
Android/obb/<package>/*.obb   # only when included
icon.png
manifest.json                 # XAPK v2
```

`manifest.json` follows the XAPK v2 shape that APKPure-style installers read —
`package_name`, `version_code`, `version_name`, `min_sdk_version`,
`target_sdk_version`, `permissions`, `total_size`, a `split_apks` array mapping
each file to its split id (the base APK is id `base`), and `expansions` when
OBBs are present.

APK entries are stored uncompressed. They are already deflated internally, so
re-compressing them costs a lot of CPU for well under a percent of size.

## Limits

- **APKs only, not app data.** Saved games, logins and settings live in an app's
  private data directory, which Android does not let another app read without
  root. Restoring an XAPK gives you the app at that version, freshly installed.
- **OBB files need All-files access** on Android 11+. Without it they are
  skipped and the APKs are still backed up.
- **System apps** are hidden by default. They can be backed up but often refuse
  to install on another device. Apps that shipped with the device *and* have
  since been updated stay visible, because their update APK is a real one.
- Installing the result on another device needs a split-aware installer (SAI,
  MT Manager, APKPure) — a `.xapk` is a container, not something Android's
  package installer opens directly.

## Building

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleRelease
```

Release signing reads the keystore password from a file outside the repo; see
`app/build.gradle.kts`. Without the keystore the release APK is left unsigned
rather than falling back to the debug key.

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest lintRelease
```

`XapkWriterTest` builds real archives on the JVM and reads them back — the
Android-specific pieces (launcher icon, shared-storage location) are injected
into `XapkWriter` so the archive format itself is testable without a device.

## License

MIT — see [LICENSE](LICENSE).
