# Acceptance test in the emulator

Every feature of the app, including recordings started from a link and their upload, played through by hand
on 2026-10-19. The app was operated with `adb`/`uiautomator`, and the GPS positions were injected. Uploads
went to a local test server with controllable responses, with dummy grants.

- **Device:** AVD `routelog37`, Android 17, `targetSdk` 37, with a PIN lock.
- **Builds:** debug, and for the release checks the release build, signed with the local debug key.
- **Limit:** emulator only. Android 8 to 15 are untested. Manufacturers' battery managers, a real drive, a
  real server and https: see [DEVICE_TESTS.md](DEVICE_TESTS.md).

| # | Flow | Result |
|---|---|---|
| 1 | First start: backup question | ok |
| 2 | First recording: location (approximate only at first), notifications (denied at first), battery exemption | ok, after fix F1 (below) |
| 3 | Recording with the screen off (40 points injected) | ok, all points there |
| 4 | "Stop" in the notification on the lock screen, without unlocking | ok; the device stays locked, service and notification are gone |
| 5 | List (date, duration, distance), "Export GPX" through the share sheet | ok; GPX 1.1 valid; Drive, Gmail, Chat, Quick Share offered |
| 6 | Delete with confirmation | ok; the export file is deleted too |
| 7 | Process ended (`kill -9`) during the recording → "Recording interrupted" → tap → resume | ok; notice after about 2 s, same recording, new segment |
| 8 | Device restart during the recording → notice → resume | ok; notice about 20 s after unlocking (Android delivers `BOOT_COMPLETED` slowly), GPX with 3 segments |
| 9 | Link: confirmation (server, label, expiry, warning "unencrypted"), local network dialog, upload 204 | ok |
| 10 | Same link: during the recording → ignored; after the upload → "already used" | ok |
| 11 | Expired link; link while another recording is running | ok; matching messages |
| 12 | Server answers 500, then 204 | ok; but the retry came only after 7 min 49 s (Android decides when, see F2) |
| 13 | Server answers 401 | ok; "refused (401)" in the list and the notification, the route stays |
| 14 | Interrupted link recording → "Finish & upload" | ok |
| 15 | Retention "Keep it for 7 days" | ok; route and export stay after the upload |
| 16 | The grant expires while the server answers 503 | ok; "Upload expired" about 2 min after the expiry |
| 17 | Doze forced (deep sleep, on battery), with and without the battery exemption | ok; recording without gaps |
| 18 | Release: `http` link → "Only https links are accepted"; permissions without local network and without background location | ok |
| 19 | Release: `https` link with "Always allow" → a subdomain starts without asking; removing the server | ok |
| 20 | Location off before the start (hint) and during the recording (card and notification) | ok; points continue once it is on again |
| 21 | Dark mode, font size 200 %, landscape during the recording | ok |
| 22 | Backup on/off in the menu | ok |

## Findings

- **F1 (fixed):** when "Approximate" was chosen, routelog only pointed to the settings. The code now assumes
  that Android 12 and newer shows its follow-up question once more after "Approximate", whether to switch to
  "precise", although Android reports no reason to explain (rationale); routelog then offers "Try again"
  once. After that, Android decides again (rationale). Observed on Android 16 and 17: after a refusal,
  Android did not ask again, and routelog showed "Open settings", also on the next start. Android 8 to 15 are
  untested.
- **F2 (platform, documented):** Android does not run jobs at once, but can batch and hold them back on every
  supported version. According to the Android documentation, that is typically up to about 30 minutes; newer
  versions also batch jobs that need a network. In Doze mode without the battery exemption and after several
  failed tries (the interval doubles from 30 s up to at most 5 hours), an upload can take hours. Only the
  value in the emulator is measured (Android 17): the first retry after a `500` came after 7 min 49 s; the
  rest is Android's documented behaviour. [EXTERNAL_RECORDING.md](EXTERNAL_RECORDING.md#timing) tells this to
  apps that start recordings (advice: set `expires` several hours after the drive).
- **Not a bug:** a second link with the **same** grant starts nothing ("already used"). This is intended.
