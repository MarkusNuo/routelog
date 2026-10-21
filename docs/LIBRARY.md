# routelog as a library

routelog is a standalone app and also an Android library, `routelog-library`, that other apps include, for
example an app built with Capacitor. This guide is for the developers of such a host app.

**Status:** verified in the emulator only (Android 16 and 17), with two hosts: the routelog app and a minimal
debug test host that includes the prebuilt package (AGP 9.3.0, `http` to 127.0.0.1). In the test host these
worked: recording, ending the process, the notice "Recording interrupted", resuming and uploading. The way
through the notification is tested in the emulator on Android 17 (a tap on the notification in the
notification shade, app in the background), not on a real phone.

**Not tested:** a release host (`https`, R8 minification), a Capacitor host, real phones, Android 8 to 15.

## What the library does, and what it does not

The library (`routelog-library`) takes care of:

- recording with the phone's GPS, once per second, as a foreground service of type `location`. It keeps
  running with the screen locked.
- the mandatory notification with "Stop", also on the lock screen without unlocking.
- storing every point in a local database at once.
- the notice "Recording interrupted" after a restart or when Android ends the app, with resume within
  12 hours.
- the GPX export.
- the upload after the stop to the host's address, retried until the grant expires.

The library has **no UI**: no list, no dialogs, no permission requests. The host does that (see
[Host duties](#host-duties)).

Unlike the `intent://` link of the routelog app, the library shows **no confirmation dialog** for the target
server. The tap on "Record" in the host counts as the confirmation. The host handles the consent of the
people in the car. Because no dialog stands in between, the rules under
[Security without a dialog](#security-without-a-dialog) apply.

The server side (upload, responses, timing) is described in
[EXTERNAL_RECORDING.md, "Upload"](EXTERNAL_RECORDING.md#upload). It applies to the library in the same way.

## API

Everything goes through the object `io.github.markusnuo.routelog.Routelog` (Kotlin):

| Call | Purpose |
|---|---|
| `configure(RoutelogConfig)` | Optional. **Once**, in `Application.onCreate`, before the library is used for the first time (see [Configuration](#configuration)). |
| `startProblem(context): StartBlocker?` | What is still missing: GPS receiver, location permission (precise), notifications (permission and channel), location turned on. `null` = ready. What the host does for each value: [table below](#startblocker-and-what-the-host-does). |
| `startWithUpload(context, UploadRequest): StartOutcome` (suspend) | A recording that is uploaded after the stop. |
| `start(context): StartOutcome` | A recording without upload. |
| `resume(context, recordingId): StartOutcome` | Resume an interrupted recording. |
| `stop(context)` | Stop; with an upload, the upload follows. Does nothing when nothing is recording. If Android refuses the way to the service (app in the background), this is logged, not thrown; the Stop button of the notification still works then. |
| `finishAndUpload(context, recordingId) { finished -> }` | Finish an interrupted recording and upload it ("Finish & upload"). The callback runs on the main thread; `false` if the recording was not (or no longer) interrupted. A recording that a dead process left as running counts as interrupted. |
| `state(context): StateFlow<RecorderState>` | `Idle`, `Starting`, `Recording` (with distance, points, GPS status). |
| `events(context): SharedFlow<RecorderEvent>` | Why a start did not work (e.g. `StartFailed`, `ResumeTooLate`, `LinkUsed`), for a message to the user. Only what happens while someone collects is delivered. |
| `recordings(context): Flow<List<RecordingListItem>>` | All recordings with their upload status, newest first. **Call it when the host's UI opens** (see [Host duties](#host-duties)): it marks recordings that a dead process left as running as interrupted and removes the notice "Recording interrupted". |
| `delete(context, recordingId): Boolean` (suspend) | Delete, including the route and a pending upload; not while recording. `false` if it did not work (running, not found, database error). A recording that a dead process left as running can be deleted. |
| `exportGpx(context, recordingId): Uri?` (suspend) | GPX file as a `content://` URI for sharing. `null` if it did not work (recording not found, write error). The share intent needs `FLAG_GRANT_READ_URI_PERMISSION`. |
| `uploadRetention(context)` / `setUploadRetention(context, retention)` | What happens to the route after the upload (default: delete right away). Setting it writes a small file on the calling thread and applies **at once to routes already uploaded** too: a shorter period deletes their points in the background. |
| `EXTRA_RESUME_FAILED` | Extra of the intent that opens the host after a tap on "Recording interrupted" when resuming did not start (see [Notifications](#notifications)). Value: the ID of the recording (`Long`). |

`UploadRequest(uploadUrl, token, label, expiresAtMillis)`:

- `uploadUrl`: `https://` only. `http://` works only when the host app itself is a debug build (see
  [Debug builds: http](#debug-builds-http)).
- `token`: the one-time grant. It is valid for exactly one recording, as with the link. If the same token
  comes again:
  - its recording is running or starting → `AlreadyRecording`
  - it is interrupted → it is resumed
  - it is stopped or uploaded → `AlreadyUsed`

  Get a new grant for every recording.
- `label`: shown with the recording and used as the track name (`<name>`) of the uploaded GPX file; an
  export is named after the start time instead. Control and invisible formatting characters are removed,
  and a label longer than 80 characters is cut to 79 and "…". It goes to the server: **do not put names of
  people in it**; use something like "Lesson 14:00".
- `expiresAtMillis`: the expiry of the grant, exclusive. The upload is tried until then. Choose a time
  several hours after the end of the drive (see
  [EXTERNAL_RECORDING.md, "Timing"](EXTERNAL_RECORDING.md#timing)).

`StartOutcome`:

| Value | Meaning |
|---|---|
| `Started` | The service was **requested**. It can still fail afterwards: `RecorderEvent.StartFailed` (e.g. Android does not allow the foreground service, the GPS cannot be started), `LinkUsed`, `LinkExpired`, `NotResumable`, `ResumeTooLate` on `events`. `state` then goes back to `Idle`. The host shows the error from `events` and lets the user start again. |
| `AlreadyRecording` | The recording of this token is running or starting already. |
| `Busy` | Another recording is running or starting. |
| `AlreadyUsed` | This token has already started a recording. |
| `TooLateToResume` | The recording of this token has been interrupted for more than 12 hours; it can only be finished (`finishAndUpload`). |
| `Blocked(blocker)` | A permission, notifications or location is missing. |
| `Invalid(reason)` | Address, token or expiry invalid. |
| `NotVisible` | Android threw an exception when the service was requested, because the app was not visible. There is no reliable check the other way round: shortly after the app left the foreground, a start can still return `Started` and then fail with `StartFailed`. So start only from a visible activity, right after the user's tap. |
| `Failed` | Nothing was started: the local database could not be read (only `startWithUpload`; a later try can work), or the recording service was not found (a broken manifest merge). |

**Order in `startWithUpload`:** what routelog already knows about the token (`AlreadyRecording`,
`AlreadyUsed`, `TooLateToResume`) is answered first, without checking the conditions. Only a start or a
resume checks them (`Blocked`). So a user who sends an already used token again does not get a needless
location request.

**Double tap:** a start is claimed atomically before the service is called. Of two quick calls, one gets
`Started`, the other `AlreadyRecording` (same token) or `Busy` (another token). This holds only if all starts
go through `Routelog`.

The token is never logged and never backed up (`noBackupFilesDir`). The library deletes it after the upload
or the expiry.

### Supported API

Meant and supported for hosts are only:

- `Routelog` (all calls of the table above, including `EXTRA_RESUME_FAILED`),
- `RoutelogConfig` (and `RoutelogConfig.legacy(...)`, which the routelog app uses),
- `UploadRequest`,
- `StartOutcome` and `StartBlocker`,
- `RecorderState` (with `GpsStatus`) and `RecorderEvent`,
- `RecordingListItem` and the classes it exposes: `RecordingEntity`, `RecordingState`, `UploadStatus`,
  `UploadState`,
- `UploadRetention`,
- `RoutelogResumeActivity.intent(...)`,
- the names of the string resources (see [Notifications](#notifications)).

All other public classes of the package (e.g. `Recorder`, `RecordingService`, `UploadManager`, `Uploader`,
`TokenStore`, `GpxExporter`, the DAOs, `RoutelogCore`, `RecordingLinkParser`) belong to routelog itself. They
are public only because the routelog app uses them (two modules). A host does not use them; there is no
compatibility promise for them, and they can change with any version.

## Flow in the host

1. **Ask for the permissions** before the start:
   - `ACCESS_FINE_LOCATION` **together with** `ACCESS_COARSE_LOCATION`;
   - from Android 13, `POST_NOTIFICATIONS`.

   `startProblem` tells what is missing. Without allowed notifications (app and channel "Recording"),
   nothing starts.
2. **Start only from a visible activity, right after a user action.** From Android 14, only a visible app
   may start a location foreground service. Background location (`ACCESS_BACKGROUND_LOCATION`) is never
   needed. The service keeps its access also with the screen off.
3. Show the status from `state` and the messages from `events`.
4. Stop with `stop` or in the notification. The upload then runs by itself.
5. The library does not ask for the exemption from battery optimisation. The host can ask for it. It needs
   the permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for that and a justification under the Play
   policies. Otherwise, battery managers on some phones end a recording without notice.

Example (Kotlin, in an activity):

```kotlin
lifecycleScope.launch {
    val outcome = Routelog.startWithUpload(
        this@MainActivity,
        UploadRequest(uploadUrl = url, token = token, label = "Lesson 14:00", expiresAtMillis = expires),
    )
    when (outcome) {
        StartOutcome.Started, StartOutcome.AlreadyRecording -> Unit
        is StartOutcome.Blocked -> askFor(outcome.blocker)
        else -> showMessage(outcome)
    }
}
```

**Capacitor:**

- The host's plugin calls these functions from its plugin methods. By default, Capacitor does not call
  plugin methods on the main thread, so they do not run "in the `BridgeActivity`". The condition for the
  start is not the thread but that the app is **visible** (the `BridgeActivity` is in the foreground,
  RESUMED).
- The plugin asks for the permissions through Capacitor's permissions API.
- It passes `state` and `events` on to the web app with `notifyListeners`.
- The UI stays in the web app.

### StartBlocker and what the host does

| `StartBlocker` | What the host does |
|---|---|
| `NO_GPS` | Explain: this device has no GPS receiver, so recording is not possible. |
| `LOCATION_PERMISSION` | The system dialog for `ACCESS_FINE_LOCATION` **together with** `ACCESS_COARSE_LOCATION`. If it was denied and Android no longer shows the dialog (`shouldShowRequestPermissionRationale` is `false`): the app settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). |
| `PRECISE_LOCATION` | The user allowed only "approximate". Show the dialog again (from Android 12, Android asks once for "precise"); after that, the app settings. |
| `NOTIFICATION_PERMISSION` | From Android 13: the system dialog for `POST_NOTIFICATIONS`; if it was denied, the notification settings. |
| `NOTIFICATIONS_BLOCKED` | The app's notifications or the channel "Recording" are turned off. Open the channel settings: `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` with `Settings.EXTRA_APP_PACKAGE` and `Settings.EXTRA_CHANNEL_ID` = `routelog_recording` (with another `channelIdPrefix`: the prefix + `recording`). Otherwise `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. |
| `GPS_OFF` | Location is turned off: `Settings.ACTION_LOCATION_SOURCE_SETTINGS`. |
| `LOCAL_NETWORK_PERMISSION` | The library's `startProblem` does not report it. See [Debug builds: http](#debug-builds-http): the host has to catch this itself. |

After every change in the settings, ask `startProblem` again; start only on `null`.

## Host duties

The library has no UI. For the library's notifications and messages to be right, the host has to do four
things.

1. **Show a list of the recordings** (`recordings()`), with:
   - interrupted recordings with "Resume" and, if they have an upload, "Finish & upload";
   - uploads that expired, were refused or failed, with "Export" (GPX) and "Delete";
   - "Delete" for **every** recording, also for those without an upload.

   `openAppIntent` of the configuration should lead to this list: it is the target of a tap on the
   notifications. Without this list the library's texts are wrong: they say "you can export it as GPX" and
   "Tap to continue the recording". The host then has to override these texts (see
   [Notifications](#notifications)).
2. **Call `recordings()` when its UI opens** (not only when the list is shown). This marks recordings that a
   dead process left as running as interrupted (otherwise they can be neither resumed nor deleted) and
   removes the outdated notice "Recording interrupted".
3. **Handle `Routelog.EXTRA_RESUME_FAILED`.** If the user taps "Recording interrupted" and resuming does not
   start (a permission, notifications or location is missing, another recording is running), the library
   opens the host with this extra (value: the ID of the recording). The host explains why and offers
   "Resume" in the list. With Capacitor the intent arrives in `onNewIntent` of the `BridgeActivity` (it is
   `singleTask` or `singleTop`, otherwise a new instance starts); the plugin reads the extra there and passes
   it on to the web app.
4. **Choose the retention:** a fixed setting (`setUploadRetention` once at start; default: delete right
   away), or let the user choose. Setting it also applies to routes already uploaded.

## Same process: a crash of the host ends the recording

The recording runs **in the host's process**. Every crash of the host ends it. Every point is already
stored; the user sees "Recording interrupted" and can resume it with a tap (within 12 hours).

**Capacitor:** if the WebView renderer crashes or Android ends it for lack of memory, Android by default
ends the whole app, and with it the recording. To prevent that:

- Register a `WebViewListener` with the bridge in the plugin whose `onRenderProcessGone` returns `true` (the
  crash then counts as handled).
- Rebuild the WebView on the next `onResume` (otherwise the app shows an empty page).

Open: the names and the behaviour of this Capacitor API in the host's Capacitor version have to be checked;
they are not tested here.

To find out why a process was ended: `ActivityManager.getHistoricalProcessExitReasons`
(`ApplicationExitInfo`, from Android 11).

Not implemented: running the recording service in a process of its own (`android:process`). The recording
would then survive a crash of the host. That is an architecture change (database, state and flows across the
process boundary).

## Security without a dialog

For a link, the routelog app asks which server the route goes to. The library does not ask: the host passes
address and token directly. So that no foreign page can send a route to a foreign server:

- The host's plugin accepts only the host's **own upload addresses** (an allowlist in native code, not taken
  from the web app or from the network).
- `startWithUpload` is **never** wired to deep links or external intents. The start comes only from a tap of
  the user in the host's own UI.
- Capacitor logs the options of plugin calls in debug builds. Set `loggingBehavior: 'none'` in
  `capacitor.config` (or keep the token out of the logs in another way). The library itself never logs a
  token.

## Debug builds: http

`http://` is allowed only when the host app itself is a debug build (then to any address); release builds
accept only `https://`.

- For apps with `targetSdk` 28 or higher, Android blocks cleartext unless a network security config that is
  **only valid in the debug build** allows it (`src/debug/res/xml/network_security_config.xml`,
  `android:networkSecurityConfig` in the debug manifest). Otherwise the upload fails with "CLEARTEXT
  communication not permitted".
- From Android 17, an upload to an address in the local network (a development computer) needs the
  permission `ACCESS_LOCAL_NETWORK`: declare it in the **debug** manifest and request it at runtime before a
  start with such an address. The routelog app does it this way (`LocalNetwork`); the library's
  `startProblem` does not know this condition. `127.0.0.1` is not affected.
- In the debug build, show the user a clear "unencrypted".

## What the library adds to the manifest

On build, these end up in the host automatically:

- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`,
  `ACCESS_NETWORK_STATE`.
  - For the Play Store, the host needs the declaration of the location foreground service **and** the "Data
    safety" form: location is collected and sent to the host's server.
- **The library's components, none of them exported:**
  - the recording service (`foregroundServiceType="location"`),
  - the upload job service,
  - a receiver for restart and app update,
  - the resume activity (see below),
  - a FileProvider of its own (`RoutelogFileProvider`, authority `<applicationId>.routelog.fileprovider`).
    It does not clash with Capacitor's FileProvider.
- **Components that the dependencies bring** (not from routelog, but in the host's manifest; seen in the test
  host with version 1.0.0):
  - `androidx.startup.InitializationProvider` (not exported) with the entries of OkHttp
    (`okhttp3.internal.platform.PlatformInitializer`) and `ProfileInstallerInitializer`,
  - `androidx.room.MultiInstanceInvalidationService` (Room, not exported),
  - `androidx.profileinstaller.ProfileInstallReceiver`: **exported**, but protected by the permission
    `android.permission.DUMP`, so only for the system and `adb`,
  - the signature permission `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX Core).
- **Background location:** the library does not request `ACCESS_BACKGROUND_LOCATION`. So that no other
  library slips it in either, the host adds this to its manifest (the `<manifest>` element needs
  `xmlns:tools="http://schemas.android.com/tools"` for it):
  ```xml
  <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:tools="http://schemas.android.com/tools">
      <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" tools:node="remove" />
      ...
  </manifest>
  ```

## Backup rules (required for the host)

- **Tokens:** the library keeps them in `noBackupFilesDir`. Android never backs up this folder. The file name
  follows `tokenFileName` of the configuration (default: `routelog_upload_tokens.properties`); because of
  `noBackupFilesDir`, it needs no rule.
- **Location data:** the recordings are in `databases/routelog.db` (with `-wal` and `-shm`). Without rules of
  its own, Android Auto Backup backs up this file to the cloud and transfers it on a device change, without
  anyone having agreed. The routelog app asks for this explicitly.
- **A host has to decide and handle this itself.** The recommendation is to exclude the library's database
  and settings. The path of the settings follows `settingsName` of the configuration (default:
  `routelog_settings.xml`).
- **The exclusions for device transfer (`<device-transfer>`) are needed regardless of `allowBackup`.** Do not
  rely on `allowBackup="false"` to prevent the device transfer: always add the exclusions.

`res/xml/backup_rules.xml` (Android 11 and older; `android:fullBackupContent` in the manifest):

```xml
<full-backup-content>
    <exclude domain="database" path="routelog.db" />
    <exclude domain="database" path="routelog.db-wal" />
    <exclude domain="database" path="routelog.db-shm" />
    <exclude domain="sharedpref" path="routelog_settings.xml" />
</full-backup-content>
```

`res/xml/data_extraction_rules.xml` (Android 12 and newer; `android:dataExtractionRules` in the manifest):
the same `exclude` lines in both `<cloud-backup>` and `<device-transfer>`.

Without these rules and with `allowBackup="true"` (the default of the Capacitor template), recorded routes end
up in the Google backup of the phone's user.

## Notifications

| Channel | Importance | Purpose |
|---|---|---|
| `routelog_recording` | Default, silent | The running recording, with Stop. |
| `routelog_interruptions` | High, with sound | "Recording interrupted". |
| `routelog_uploads` | Default | Upload expired, refused or failed. |

(With another `channelIdPrefix` in the configuration, accordingly.)

The texts are in English. A host can override any text with a string of the same name in its own resources,
e.g. `routelog_notification_recording_title`. All names are in
`routelog-library/src/main/res/values/strings.xml`. This is also how the texts can be translated. The upload
notices say "you can export it as GPX": that is only true if the host offers a list with an export (see
[Host duties](#host-duties)); otherwise override the texts `routelog_notification_upload_*_text`.

- **Tap on the recording or on an upload notice:** opens the host's launch activity (configurable).
- **Tap on "Recording interrupted":** opens the invisible `RoutelogResumeActivity`. It resumes the recording
  as soon as it is visible (RESUMED), then opens the host (in a task of its own) and closes itself.
  - **If resuming does not start** (a permission, notifications or location is missing, another recording is
    running), the notice "Recording interrupted" comes back as long as the recording can still be resumed
    (12 hours), and the host is opened with the extra `Routelog.EXTRA_RESUME_FAILED` (the ID of the
    recording). The notice comes **before** the host opens; when the host calls `recordings()`, it removes
    the notice again, and the list shows "Resume".
  - If the host has no matching activity, this is logged (only the class name of the exception).
- **A target of its own:** a host can set its own target. It must be an activity that no other app can start
  (not exported). The host then also handles the start and `EXTRA_RESUME_FAILED` itself.

## Configuration

`Routelog.configure(RoutelogConfig(...))`:

- **Once**, in `Application.onCreate`, before anything of the library runs. A restart of the device or of the
  service can start the process without an activity.
- A call with other values after the library's first use throws an `IllegalStateException` (the lambdas
  `openAppIntent` and `resumeIntent` count as other values).
- Without a call, the defaults apply, which are meant for hosts.

| Field | Default | Meaning |
|---|---|---|
| `channelIdPrefix` | `routelog_` | Put in front of the channel IDs. |
| `notificationIdBase` | `0x524C0000` | Notification IDs start here. |
| `jobIdBase` | `0x524C0000` | JobScheduler IDs start here. A host that uses WorkManager keeps its range below (`Configuration.Builder.setJobSchedulerJobIdRange`). |
| `settingsName` | `routelog_settings` | File of the settings (retention). |
| `tokenFileName` | `routelog_upload_tokens` | File of the upload tokens in `noBackupFilesDir` (`<name>.properties`). Use a name that only the library uses: the token store moves, once, the tokens of a SharedPreferences file of the same name and then deletes that file. |
| `openAppIntent` | The host's launch activity | Target of a tap on the notifications. |
| `resumeIntent` | `RoutelogResumeActivity` | Target of a tap on "Recording interrupted". |

The routelog app itself uses `RoutelogConfig.legacy(...)` with its older values (empty prefix, base 0,
`settings`, `upload_tokens`). This keeps the channel settings of its users and their scheduled uploads.

## Including the library

The routelog repository contains the built library as a local Maven directory, `maven-repo/`, one version per
Git tag (`routelog-library-<version>`). The host does **not** build the sources. The reason: with
`includeBuild` it would have to use exactly the same Android Gradle plugin version as routelog; in a test,
the build failed with AGP 9.3.0 and Gradle 8.

1. **Add the submodule**, in the host's Android project (e.g. `android/` of a Capacitor app):
   ```
   git submodule add https://github.com/MarkusNuo/routelog.git routelog
   git -C routelog checkout routelog-library-1.0.0
   git add routelog
   git commit -m "Add routelog-library 1.0.0"
   ```
   `git add routelog` and the commit pin the checked state: the host repository then records exactly this
   commit of the submodule (the tag alone is not in the host repository).

   Before you push the host's commit, check that the submodule commit is the commit of the tag:
   ```
   git -C routelog rev-parse HEAD
   git ls-remote https://github.com/MarkusNuo/routelog.git 'refs/tags/routelog-library-1.0.0^{}'
   ```
   Both must print the same commit. The address in `.gitmodules` alone does not prove where a commit comes
   from: GitHub also serves commits of forks through it. Do this also when reviewing a change of the submodule
   pointer (e.g. in a pull request).
   - **New clones** of the host repository: `git clone --recurse-submodules <address>`, or
     `git submodule update --init` in a repository that is already cloned. Without it, `routelog/` is empty
     and the build does not find the library.
   - **CI** has to check out the submodule too, e.g. `actions/checkout` with `submodules: true` on GitHub
     Actions.
2. **Add the Maven repository**, in the host's `settings.gradle(.kts)`:
   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           exclusiveContent {
               forRepository { maven { url = uri("routelog/maven-repo") } }
               filter { includeGroup("io.github.markusnuo") }
           }
       }
   }
   ```
   With Capacitor (Groovy, `repositories` in `build.gradle`):
   ```groovy
   exclusiveContent {
       forRepository {
           maven { url = uri("$rootDir/routelog/maven-repo") }
       }
       filter { includeGroup("io.github.markusnuo") }
   }
   ```
   `exclusiveContent` binds the group `io.github.markusnuo` to this directory: an artifact with the same
   coordinates from another repository (Maven Central, for example) can never take precedence.
3. **Add the dependency** in the app module:
   ```kotlin
   implementation("io.github.markusnuo:routelog-library:1.0.0")
   ```
   The host does not have to add the other dependencies. Gradle reads the package's module file (`.module`).
   It defines what goes on the host's compile classpath (`api`: Room, kotlinx-coroutines-android, the Kotlin
   standard library) and what only comes along at runtime (AndroidX Core, OkHttp).
4. **Check the integrity.** The tag `routelog-library-<version>` is annotated; its message names the SHA-256
   of the AAR file and that of the module file (`.module`; it defines which dependencies Gradle pulls into the
   host). The host compares both:
   ```
   git -C routelog tag -n99 -l routelog-library-1.0.0
   sha256sum routelog/maven-repo/io/github/markusnuo/routelog-library/1.0.0/routelog-library-1.0.0.aar
   sha256sum routelog/maven-repo/io/github/markusnuo/routelog-library/1.0.0/routelog-library-1.0.0.module
   ```
   (PowerShell: `Get-FileHash -Algorithm SHA256 <file>`.) The values must match the tag message. This
   protects against a damaged or wrong copy, not against a commit from a fork or against someone with write
   access to the routelog repository who changes the tag and the files at the same time. Against that, these
   help:
   - the submodule pointer from step 1: the host builds only the commit it has checked;
   - the check from step 1 that the submodule commit is the commit of the tag (`git ls-remote`), also when a
     change of the pointer is reviewed;
   - **Gradle dependency verification** in the host (`gradle/verification-metadata.xml`, created with, for
     example, `./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease`; the
     host adapts the tasks so that they resolve the configurations its CI builds): Gradle then checks the
     checksums of all dependencies on every build, including those of the library from `maven-repo/`.
     After generating the file, compare the sha256 values recorded for `routelog-library-<version>.aar` and
     `.module` with the tag message. On every update, review the diff of `verification-metadata.xml`
     together with the change of the submodule pointer. Recommended;
   - tag protection in the routelog repository: a GitHub ruleset for `routelog-library-*` blocks moving and
     deleting tags. A repository admin can still change a ruleset, so it guards against mistakes, not against
     a compromised owner account. This is a setting of the routelog repository, not of the host.
5. **Before the first build**, read and apply these sections:
   - [Backup rules (required for the host)](#backup-rules-required-for-the-host), including the exclusions
     for device transfer (`<device-transfer>`): otherwise routes end up in the backup.
   - The rule against `ACCESS_BACKGROUND_LOCATION` in the host's manifest, under
     [What the library adds to the manifest](#what-the-library-adds-to-the-manifest) ("Background
     location").
   - [Security without a dialog](#security-without-a-dialog): an allowlist of upload addresses, no start
     through deep links, no tokens in the logs.

**Updating to a new version:**

1. `git -C routelog fetch --tags`
2. `git -C routelog checkout routelog-library-<new version>`
3. Check that the commit is the commit of the tag, as in step 1 above:
   ```
   git -C routelog rev-parse HEAD
   git ls-remote https://github.com/MarkusNuo/routelog.git 'refs/tags/routelog-library-<new version>^{}'
   ```
   Both must print the same commit.
4. Check the integrity, as in step 4 above.
5. Change the version number from step 3 above and commit it together with `git add routelog` in **one**
   commit: the pointer and the version belong together. If the host uses dependency verification, include
   the updated `gradle/verification-metadata.xml` and review its diff together with the pointer (step 4
   above).

Tested in the emulator with a minimal test host (debug build, AGP 9.3.0, Kotlin 2.2, `http` to 127.0.0.1,
Android 16 and 17):

- Recording with upload.
- Process ended → "Recording interrupted" (channel `routelog_interruptions`) → tap →
  `RoutelogResumeActivity` resumes the same recording.
- Stop → upload (two segments, token, 204).

Not tested: a release host (`https`, R8 minification), Capacitor, real phones, Android 8 to 15.

## Requirements

- `minSdk` 26 (Android 8.0), `compileSdk` 37.
- **Android Gradle plugin 9.1 or newer** (and with it Gradle 9). The library's AndroidX Core 1.19 requires
  it; the library does not lower its dependencies for AGP 8. AGP 8 (Gradle 8) does not work.
  - As a host, only **AGP 9.3.0** has used the prebuilt package so far (the test host). AGP 9.4.1 is the
    version routelog itself is built with, not a separate host. 9.1 and 9.2 have not been tried.
  - **Capacitor:** the whole Android project of the Capacitor app (`capacitor-android` and all plugins) then
    has to build with AGP 9.1+ and Gradle 9. Whether the host's Capacitor version supports that is open and
    not tested.
- **Kotlin 2.2 or newer** in the host. The library is built with language and API version 2.2.
- `android.useAndroidX=true`.
- The library depends on:
  - Room 2.8 (`api`: the entries of `RecordingListItem` are Room entities),
  - kotlinx-coroutines-android (`api`: `Routelog` returns `StateFlow`, `SharedFlow` and `Flow` and has
    `suspend` functions),
  - AndroidX Core (runtime only),
  - OkHttp 5 (runtime only).
- No Google Play services. Location only through the device's GPS (`LocationManager`).

## Releasing a version

For maintainers of routelog. A version counts as released once its tag `routelog-library-<version>` exists;
a released version is never changed again.

1. Raise `routelogLibraryVersion` in `gradle.properties`. Publish as the last step, when the library's code
   for this version is final.
2. Fetch the tags first (`git fetch --tags`): the build's check only sees local tags. Then
   ```
   ./gradlew :routelog-library:publishReleasePublicationToRepoRepository
   ```
   writes the package to `maven-repo/`. It stops if the tag of that version exists ("version <v> is
   published (tagged routelog-library-<v>); raise routelogLibraryVersion"); `-PallowRepublish=true` overrides
   that and must never be used for a tagged version. It also stops if a file in `routelog-library/src` has CRLF line endings
   (a checkout from before `.gitattributes` pinned LF): delete `routelog-library/src` and run
   `git checkout -- routelog-library/src`.
3. Commit `maven-repo/` together with the version change.
4. On the commit that gets tagged, run
   ```
   ./gradlew :routelog-library:verifyLibraryPackage
   ```
   It builds the package again and compares it with `maven-repo/`: the `.aar`, the sources jar and the
   `.module` with their checksum files byte for byte, the `.pom` apart from line endings. If it fails
   ("maven-repo/ is stale: publish again or raise the version"), publish again or raise the version.
5. Tag that commit with an annotated tag that names both hashes, and push it:
   ```
   git tag -a routelog-library-<version> -m "routelog-library <version>, sha256 of the aar: <hash>, sha256 of the module: <hash>"
   git push origin routelog-library-<version>
   ```
   The hashes are in `maven-repo/io/github/markusnuo/routelog-library/<version>/routelog-library-<version>.aar.sha256`
   and `….module.sha256`.

Byte equality of a rebuilt package has been shown on Windows only (also from a fresh clone with
`core.autocrlf=true`). Other operating systems and JDKs are untested: the deflate implementation, file
permissions and the line separator Gradle writes into the `.pom` and `maven-metadata.xml` may differ. Publish
and verify on Windows, where byte equality has been shown.

## License

The library is licensed under the [GNU Affero General Public License v3.0 only](../LICENSE)
(`AGPL-3.0-only`), like the rest of the repository. An app that includes it must publish its complete source
code under the same license. Other terms, e.g. a commercial license, are available from the author
(GitHub: @MarkusNuo).
