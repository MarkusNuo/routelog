# External recording

How another app starts a recording in the routelog app with a link, and how routelog uploads the route to
that app's server afterwards. Apps that include routelog as a library use the same server side; see
[LIBRARY.md](LIBRARY.md).

## Flow

1. Another app (for example a web app in the phone's browser) opens routelog with a link.
2. routelog checks the link as untrusted input. Then it shows what is recorded (the label), **which server**
   the route goes to afterwards and until when the upload is allowed. The user confirms and starts. If the
   user trusted the server before ("Always allow …"), the recording starts without asking.
3. After the stop, routelog uploads the route as GPX to the target address, with the grant from the link.
   Without a network it tries again later, at growing intervals, until it works or the grant has expired.

## Link

From the browser **only** as an Android `intent://` URL with the package name (required):

```
intent://record?upload=<URL>&label=<label>&expires=<time>#Intent;scheme=routelog;package=io.github.markusnuo.routelog;S.token=<grant>;S.browser_fallback_url=<URL>;end
```

routelog receives the address `routelog://record?upload=…&label=…&expires=…` from it and the token as the
string extra `token`. The token is **never in the address**: Android 8–12 writes the address of every opened
link to the system log, and "Recents" stores it; extras do not appear there. A link with `token=` in the
address is refused. The app that starts the recording must **never** create a bare `routelog://` link: any
app can register the same scheme and would get the target address and the grant. The token never travels in
an `https://` URL.

Values are percent-encoded (RFC 3986). `+` is **not** read as a space; write spaces as `%20`. Each parameter
may appear only once.

| Parameter | Required | Rule |
|---|---|---|
| `upload` | yes | Absolute URL. `https://`; `http://` only in a debug build of routelog. No user name or password in the URL, no `#` part, at most 2048 characters. |
| `token` (extra `S.token`) | yes | An opaque value, 1–4096 visible ASCII characters (no spaces). routelog never logs it, never shows it, does not put it into a backup, uses it only for this one upload and deletes it as soon as the upload is done, refused or expired. |
| `label` | no | Free text for display, e.g. "Lesson 14:00". Control characters are removed; at most 80 characters are shown. A person's name is not needed. |
| `expires` | yes | The expiry of the grant as an ISO 8601 time with `Z` or an offset, e.g. `2026-10-18T22:00:00Z`. **Exclusive:** from this time on, the grant is no longer valid. The app that starts the recording sets the value its server returns when it issues the grant (for example the end of the following day). No upper limit. |

A link is refused (with a message, without a recording) if a required parameter is missing or invalid,
`expires` has been reached, or another recording is already running.

**One link = one recording.** If the same grant comes again (a double tap, another tap on "record"): if its
recording is still running → nothing happens, it keeps running; if it is interrupted → routelog resumes
exactly that one; if it is stopped or uploaded → no new recording, the notice "already used". A new recording
needs a new grant.

## Upload

- `PUT <upload>` with `Content-Type: application/gpx+xml`, the body is GPX 1.1, header
  `Authorization: Bearer <token>`.
- GPX: every `trkpt` with `lat`, `lon` and `time` (UTC with `Z`); `ele` only if the device provides the
  altitude above sea level. After an interruption, a new `trkseg` starts.
- A recording without a single point is not uploaded; routelog tells the user.
- Redirects are **never** followed (otherwise the token would go to another address).
- Over any network, including mobile data.

| Response | routelog |
|---|---|
| `2xx` (e.g. `204`) | done |
| `409` (already uploaded) | done |
| `401` / `403` / `410` (grant invalid, the recording's target deleted on the server, expired) | no retry, the user is informed; the route stays on the phone and can be exported |
| other `4xx` (e.g. `400` gpx_invalid, `404`, `413` too large) and all `3xx` | final error: no retry, the user is informed; the route stays on the phone |
| `408`, `429`, `5xx`, network errors | retried later, at growing intervals, until `expires` |

After a completed upload, routelog keeps or deletes the location points according to the user's setting in
routelog (default: delete right away); the entry "Uploaded" without the route stays until the user deletes
it. This is not part of the link.

### Timing

Android runs the uploads through the JobScheduler and decides itself when they run.

- The first try usually runs right after the stop, as soon as a network is available.
- A retry after an error starts after 30 seconds at the earliest; after that, the interval doubles, up to at
  most 5 hours.
- Android can batch and hold back jobs on every supported version, according to the Android documentation
  typically for up to about 30 minutes; newer versions also batch jobs that need a network. In Doze mode
  without a battery exemption and after several failed tries, an upload can take hours. This is Android's
  documented behaviour, not measured.
- Only this value is measured (emulator, Android 17): the first retry after a `500` came after 7 minutes
  49 seconds.
- So do not set `expires` shortly after the end of the drive; several hours make sense.
