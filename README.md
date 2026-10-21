# routelog

routelog records a driven route with the phone's GPS, also with the screen locked, and keeps it on the
device. When another app starts the recording with a link, routelog uploads the route afterwards to the
server named in the link. It is an Android app written in Kotlin and needs no Google Play services.

## Features

- **Recording:** a foreground service with a notification records one GPS fix per second and stores every
  valid fix in a local database at once (implausible fixes, such as 0°/0° or coordinates out of range, are
  dropped). The recording keeps running with the screen off, and "Stop" in the notification works from the
  lock screen without unlocking.
- **Recordings list:** every recording with its date, duration and distance.
- **GPX export:** GPX 1.1 through the share sheet. To see a route, open the file in a map app; routelog has
  no map of its own.
- **Delete:** removes a recording from the phone, including its exported GPX file.
- **Resume after an interruption:** if a restart or Android ends the app during a recording, the notice
  "Recording interrupted" offers to continue. The same recording continues as a new segment, within 12 hours
  of the interruption. Not every way of ending the app gives this notice ("Force stop" in the system settings
  does not); the recording is then listed as interrupted the next time the app opens.
- **Start from a link:** another app opens routelog with a "record and upload" link (upload address, label
  and expiry in the link, the one-time grant as an intent extra). routelog shows the target server and asks
  before it records, unless the user chose to always allow that server (trusted servers can be removed in the
  menu). Release builds accept https links only. One link starts at most one recording.
- **Upload:** after the stop, the route is uploaded as GPX with Android's JobScheduler, over any network,
  retried until the grant expires. Problems are notified; the route stays on the phone and can be exported.
  After a successful upload only the route's points are removed, by default right away; a setting offers
  right away, 7 days, 30 days, or keep. The entry "Uploaded to …" stays until the user deletes it. Upload
  tokens are never part of a backup.
- **Backup only with consent:** on the first start, routelog asks whether recordings may go into the Android
  backup; until then, and on Android 8.x, nothing is backed up. With consent, recordings move along to a new
  phone, and go into a cloud backup only when it is end-to-end encrypted. The choice can be changed in the
  menu.
- **Battery optimisation:** before the first recording, routelog asks Android to exempt it from battery
  optimisation; while it is not exempt, the app shows a hint with a button to allow it.
- **No account:** routelog needs no account and no server of its own.

## Tech stack

| Area | Technology |
|---|---|
| Language and UI | Kotlin, Jetpack Compose, Material 3 |
| Location | `LocationManager` with the GPS provider, no Google Play services |
| Storage | Room 2.8 |
| Upload | JobScheduler, OkHttp 5 |
| Build | Gradle, Android Gradle plugin 9.4 |
| Tests | JUnit 4, AndroidX Test, Espresso, Compose UI tests |

## Getting started

### Prerequisites

- JDK 17 or newer
- Android SDK with the platforms 37.0 and 37.2: the app compiles against 37.2, the library against 37.0 (the
  Android Gradle plugin installs 37.0 automatically)
- An emulator or a phone with Android 8.0 or newer

### Build and install

```bash
git clone https://github.com/MarkusNuo/routelog.git
cd routelog

# debug build, installed on the connected emulator or phone
./gradlew :app:installDebug
```

On Windows use `gradlew.bat` instead of `./gradlew`.

Gradle finds the Android SDK through `ANDROID_HOME` or `sdk.dir` in `local.properties`. The debug build also
accepts `http` links, with a clear "unencrypted" warning; release builds accept `https` only.

## Running the tests

```bash
# build, unit tests, lint, and the check that maven-repo/ matches the library's sources
./gradlew :app:assembleDebug :routelog-library:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :routelog-library:lintDebug :routelog-library:verifyLibraryPackage
./gradlew :app:connectedDebugAndroidTest   # with an emulator or phone attached
```

The instrumented tests cover the library too. `UploadSchedulerTest` turns airplane mode on for a moment and
restores the previous state afterwards; it needs Android 11 or newer and a device on which airplane mode cuts
all networks.

## Library

`routelog-library` is the recording part without any UI: foreground service, notifications, storage, GPX and
upload. Other apps include it as the prebuilt package in `maven-repo/`, one version per Git tag
(`routelog-library-<version>`). How to include it, its API and the duties of the host app:
[`docs/LIBRARY.md`](docs/LIBRARY.md).

Apps that start a recording in the routelog app with a link:
[`docs/EXTERNAL_RECORDING.md`](docs/EXTERNAL_RECORDING.md).

## Project structure

| Folder | Contents |
|---|---|
| `app/` | The standalone app: UI, link dialog, trusted servers, backup question, battery hint |
| `routelog-library/` | The library without UI: recording, foreground service, storage, GPX, upload |
| `maven-repo/` | The prebuilt library package, one version per Git tag |
| `docs/` | Library guide, link interface, emulator acceptance test and device-test guide |
| `tools/` | `gpx_check.py`: checks a GPX file locally and prints numbers only |
| `gradle/` | Version catalog (`libs.versions.toml`) and Gradle wrapper |

## Status

- **Verified in the emulator only:** automated tests on Android 16 and 17; manual acceptance on Android 17
  with the debug and the release build ([`docs/EMULATOR_ACCEPTANCE.md`](docs/EMULATOR_ACCEPTANCE.md)). The
  library also ran in a minimal debug test host on Android 16 and 17.
- **Not tested:** Android 8 to 15, real phones, and release or Capacitor hosts of the library. The device-test
  guide is [`docs/DEVICE_TESTS.md`](docs/DEVICE_TESTS.md).
- Open device tests and known gaps are GitHub issues.

## License

Copyright (C) 2026 MarkusNuo

- **Code** — everything in this repository except the third-party parts below, including the prebuilt
  library package in `maven-repo/` — is licensed under the
  [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). You may use, change and
  distribute it, also commercially, as long as you publish the complete source code of your version under the
  same license — this includes apps that include the library.
- **Name and logo** are not covered by this license; your own version needs its own name.
- **Other terms**, e.g. a commercial license, are available from the author (GitHub: @MarkusNuo).
- **Third-party parts** keep their own licenses: the Gradle wrapper (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/`, Apache License 2.0), the Material icon `ic_more_vert` (Apache License 2.0), the GPX 1.1
  schema by TopoGrafix that the tests validate against (`routelog-library/src/test/resources/gpx/gpx-1.1.xsd`)
  and the libraries listed in `gradle/libs.versions.toml`.
