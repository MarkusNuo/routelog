# Device tests

What only a real phone shows. Tested so far: by hand in the emulator on Android 17
([EMULATOR_ACCEPTANCE.md](EMULATOR_ACCEPTANCE.md)) and with automated tests on Android 16 and 17. **Android 8
to 15 are not tested at all.** A phone with Android 12 to 15 is therefore especially useful. On every phone,
watch the start with its permission dialogs closely (order, texts, missing or extra dialogs) and note what
differs.

Record the results (numbers and observations, never coordinates) in the GitHub issue of the matching device
test.

## Privacy: location data of other people

The GPX of a drive with other people in the car contains their position every second, often from and to
their home address. Therefore:

- **Only GPX files of solo drives are sent or analysed** (nobody but you in the car).
- For **drives with other people**, record only the values from routelog's list (duration, points,
  distance) and your observations. The GPX of such a drive goes nowhere (no mail, no Drive, no chat) and is
  deleted from the phone after the check (delete the recording in routelog, and every exported file too).
- **Check a GPX locally:** copy the GPX of a solo drive (export: see test 2) to the computer and check it
  there:
  ```
  python tools/gpx_check.py <file.gpx>
  ```
  (Python 3.11 or newer, standard library only.) The script prints numbers only: segments, points per
  segment, total duration, gaps over 2 s (length in seconds), the largest gap, pauses between the segments,
  distance and jumps over 70 m/s. It shows no coordinates and no times of points. Afterwards, delete the
  file on the computer and on the phone.

## Preparation

1. **Phone:** note the model, the manufacturer and the Android version. Most telling is a device with strict
   battery management (Samsung, Xiaomi, Huawei, OnePlus; see dontkillmyapp.com).
2. **Install the app** (USB debugging on, phone connected to the computer by cable). Run all commands in
   **Git Bash**, in the repository folder. `adb` must be in the PATH (otherwise use
   `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`).
   - For tests 1, 2, 3 and 5, the **debug build**:
     ```
     ./gradlew :app:assembleDebug
     adb install -r app/build/outputs/apk/debug/app-debug.apk
     ```
   - For test 4, the **release build**. It accepts only `https` links and has no local network permission.
     Signed with the local debug key, it is for testing only, not for distribution:
     ```
     ./gradlew :app:assembleRelease
     BT="$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0"
     "$BT/zipalign.exe" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/release-aligned.apk
     "$BT/apksigner.bat" sign --ks "$USERPROFILE/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out app/build/release-test.apk app/build/release-aligned.apk
     adb install -r app/build/release-test.apk
     ```
     Both builds carry the same signature, so they can replace each other without losing data.
3. **On the first start:** answer the backup question. On the first recording:
   - Location: first choose **"Approximate"**. Expected: routelog shows "Precise location needed" with "Try
     again"; Android then asks whether to switch to precise location; agree, and the recording starts.
   - Another time (revoke the location permission in the app settings first), choose "Approximate" again and
     **decline** Android's follow-up question. Note what routelog shows. Expected: "Open settings", or "Try
     again" once more if Android offers the question again.
   - Allow notifications.
   - **Decline** the battery exemption once (for test 1a) and allow it later through the hint in the app (for
     test 1b).

**While driving:** only look at or use the phone when the car is stopped or someone else is driving.

## Test 1: drive with the phone locked

Two **solo drives** of about 90 minutes each. Drives with other people work too, but then without GPX
analysis (see [Privacy](#privacy-location-data-of-other-people)): only the values from the list.

- **1a without the battery exemption**, **1b with the battery exemption** (hint in the app → "Allow").
- Start, lock the phone, put it in your pocket or a holder. Do not unlock it until the drive is over.
- In between, look at the lock screen once (only when stopped or as a passenger): does it show "Recording
  route"?
- At the end, **tap "Stop" on the lock screen** without unlocking.

Record:

| Question | 1a | 1b |
|---|---|---|
| Did the recording run until the end? (list: duration, points) | | |
| Did "Recording interrupted" appear? When? | | |
| Notification visible on the lock screen? | | |
| Does Stop work without unlocking? | | |
| Distance plausible (compared with a map or the odometer)? | | |
| Gaps > 2 s, largest gap, jumps (`tools/gpx_check.py`, solo drive only) | | |

**Extra, battery saver:** a short solo drive (10 minutes) with the battery saver on, phone locked. Gaps, or no
points at all?

## Test 2: GPX export

With the GPX of a **solo test drive**, not of a drive with other people:

- "Export GPX" → which apps does the share sheet offer? Mail, messengers, Drive, map apps.
- Send it **only to yourself** (a mail to yourself, or save it on the phone and copy it to the computer with
  `adb pull`) and open it: does a map app show the route?
- Afterwards, delete the file everywhere (mail, Drive, downloads, computer, phone).
- In the emulator (Android 17 with Google apps), Quick Share, Drive, Gmail and Chat offered the file.

## Test 3: link from another app

With the **debug build** (see [Preparation](#preparation)). The normal flow (3.1 to 3.4) may run on the
production system with a real drive, under the normal consent process of the app that starts the recording.
**From 3.5 on, only on a staging server with test data and solo drives.**

1. In the app that starts the recording, tap its record button → Chrome opens routelog. Does the dialog show
   the right server and the label?
2. Tick "Always allow …" → Record → a short drive → Stop.
   - When does the upload arrive on the server (minutes after the stop)?
   - Does the list show "Uploaded to …"?
3. Start the next recording from that app: does routelog start without asking?
4. Open the same link again:
   - during the recording → nothing happens;
   - after the stop → "This link was already used".
5. **Dead zone** (staging, solo drive): airplane mode on, Stop, airplane mode off after 10 minutes. How long
   until the upload?
6. **Error case** (staging only, test data, solo drive): delete the recording's target on the server before
   the upload, if the server allows it. Expected: the notification "Upload refused (401/410)", and the route
   can still be exported.

## Test 4: HTTPS and network

With the **release build**. Steps 4.2 to 4.4 only on a staging server with test data and solo drives. What is
specific to Android 17 is said in the steps.

1. Upload to the server over https (public certificate): does it arrive?
2. **Self-signed or private certificate** (e.g. a staging server): the upload fails, because the release
   build accepts only the system's certificate authorities (on every Android version). On Android 17,
   Certificate Transparency can also refuse a public certificate without SCTs. Expected: "Waiting to upload"
   until `expires`, then the notification "Upload expired"; the route can still be exported.
3. **Server in the local Wi-Fi** (Android 17): a server that runs only at an internal address is never
   reachable from outside that Wi-Fi, not even over mobile data. In the Wi-Fi, Android 17 should block access
   to the local network, because the release build has no local network permission; the upload then stays at
   "Waiting to upload". Only with split DNS (a public host name that points to a private address in that
   Wi-Fi) does the upload go through over mobile data. Watch and note what happens; this is the expectation,
   it is not tested.
4. **Early refusal of a large upload:** this case needs an upload of several MB. One hour at one point per
   second is only about 0.4 MB; a few hours of recording are needed (about 10 hours give roughly 4 MB). A
   shorter drive does not prove the case. It also affects only servers that answer before they have read the
   body; whether the server does so is open. Then upload for a target that was deleted on the server.
   Expected is "Upload refused" at once, not "Waiting to upload" until the expiry. In the emulator the case
   is tested with several MB; on the phone this step is optional.

## Test 5: interruption and restart

1. Restart the phone during a recording. After unlocking, the notification "Recording interrupted" ("Tap to
   continue the recording …") should appear within about a minute. Tap it → it resumes the same recording (a
   new segment in the GPX).
2. Swipe the app away in Recents during a recording. Three outcomes are possible:
   - The recording keeps running.
   - The notice "Recording interrupted" appears.
   - Nothing appears: some manufacturers force-stop the app when it is swiped away, and then there is no
     notice by design (a platform limit). When the app is opened, the recording is listed as "Interrupted",
     with "Resume".
3. Do not test "Force stop" in the app settings. After it there is no notification by design (a platform
   limit).

## After the tests

- **Turn USB debugging on the phone off again.**
- Delete all GPX files (phone, computer, mail, Drive), and the recordings of drives with other people in
  routelog.
- Record the results in the GitHub issue of each device test.
