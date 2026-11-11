# DashLink

A small Android app that downloads clips from a Mio MiVue dashcam over Wi-Fi **without taking over the
phone's main Wi-Fi** — so wireless Android Auto can stay connected while it works.

Status: v0.2, written from the protocol used by MiVue Pro 1.1.233.1. **Not yet tested against a camera or in a
car.** Everything that can be tested without one is tested on every build (see [Tests](#tests)); what cannot
is listed under [Not covered by any test](#not-covered-by-any-test).

## What it does

- Joins the camera's Wi-Fi as a second, local-only link (Android 12+ dual Wi-Fi).
- Lists the camera's folders (Normal / Event / Parking / Photo), front or rear camera, newest first.
- Downloads ticked clips in a foreground service. If the link drops, it waits and resumes from the same byte.
- Knows when to stop trying. A clip the camera no longer has (or keeps answering with nothing) is marked
  failed and the next one starts. A camera that is out of reach pauses the queue instead: after ten minutes
  connected without a new byte, or as soon as the link has given up. A full phone pauses it too. A paused
  queue keeps what it has and continues when the camera is back or Download is tapped.
- Saves clips straight into `Movies/DashLink` (photos into `Pictures/DashLink`). An unfinished clip is hidden
  from the Gallery until it is complete; there is never a second copy.
- Remembers the queue: clips that were queued when Android stopped the app are still queued, and continue from
  where they were, the next time the camera is connected.
- Read-only: it never changes camera settings, deletes files or formats the card. The only requests it can
  send are `action=get`, `action=dir`, `action=reardir` and plain file downloads; anything else is refused
  inside the app before it reaches the network.

## Build

- GitHub: push to `main`. The workflow runs the unit tests, builds the APK, tests that APK on an emulator and
  only then publishes it as the `latest` release (`DashLink.apk`). A failing test blocks the release.
- Android Studio: open this folder and run the `app` configuration.

`debug.keystore` is a throwaway debug key kept in the repo on purpose, so each build installs over the last.

## First test

1. Switch Wi-Fi on in the camera's menu and note the network name and password it shows.
2. With Android Auto running, open DashLink, enter the name (the start of it is enough) and password, tap Connect.
   The first time, Android shows its own prompt with the networks it found: pick the camera there. Android gives
   that prompt as long as it needs; DashLink only ends a search nobody answers after three minutes.
3. The status line should show the camera **and** still list the car under "Other Wi-Fi on this phone".
4. Tick a clip, tap Download selected. Tap Diagnostics to share a report if anything looks wrong.

Things the app will ask for, and why:

- **Location (precise)** — Android only tells an app the name of a Wi-Fi network with this permission. Without
  it the camera has to be picked from Android's prompt every time and the link cannot reconnect by itself.
  The same goes for a phone on which Location is switched off altogether.
- **Nearby devices**, **Notifications** — the link to the camera, and the progress notification.
- **Unrestricted battery use** — asked on the first download. Without it Android (Samsung in particular) may
  stop long downloads while the screen is off. If that happens, the app says so the next time it is opened.

Stop cancels everything that is queued and deletes what was half-downloaded.

## Tests

`./gradlew testDebugUnitTest` — on any computer, no phone needed. A small HTTP server (`FakeCam`) plays the camera:

- file list and settings parsing, including damaged and enormous replies;
- downloads: full, resumed, Range ignored, 416, cut mid-stream and resumed to a byte-identical file,
  unknown size, cancel, time-outs, non-200, empty body;
- 5000+ files, paging that repeats or never ends, no rear camera;
- one request to the camera at a time, and nothing but reads ever sent;
- the rules of the Wi-Fi link: refusals, time-out, drop and reconnect, no retry loop, no leaked request,
  the camera's address when the DHCP server or gateway is missing;
- the download queue: link drop, 404, phone full, Stop, saved and restored queue.

`./gradlew connectedDebugAndroidTest` — on an emulator or phone. CI runs them on two emulators: API 34
(Android 14) and API 36 (Android 16). The real app, service and MediaStore, with `FakeCam` as the camera and a
pretend Wi-Fi link: permission dialogs granted and denied, Android's Wi-Fi prompt declined, left alone and
timed out, Wi-Fi off, downloads through the screen into shared storage, a queue left behind by a killed
process, the app being rebuilt, rotated and resized, the battery-optimisation question, the Diagnostics report.

`ci/smoke.sh app.apk` — installs the release APK on the emulator and pokes it over plain adb, with all
permissions granted and with all denied. Any crash or ANR of the app in logcat fails the build.

An emulator is not a Galaxy Z Fold: it has stock Android's battery rules, one screen and a simulated Wi-Fi
chip. The tests show that the app handles what Android can answer; they cannot show what a Samsung phone,
the camera or the car will answer.

## Not covered by any test

No camera and no car were available. These need the real thing:

- whether the camera's replies really look the way `Cam.kt` expects (list XML, settings, `Range` support);
- joining the camera's Wi-Fi at all: password, Android's prompt, the prompt being skipped the second time;
- two Wi-Fi links at once on the phone, and wireless Android Auto staying up meanwhile;
- reconnecting after a real drop with the screen off;
- Samsung's battery management on a long download;
- transfer speed, and how the camera copes with being asked for many large files in a row.

The Diagnostics report is built for exactly this: it says what the phone supports, what the link and the queue
did, why Android last stopped the app, and what the camera answered — without the Wi-Fi password.

## Code map

- `Cam.kt` — the camera's HTTP interface: info, file list, resumable file download. No Android classes.
- `LinkCore.kt` — the rules of the second Wi-Fi link (ask, retry, give up, camera address). No Android classes.
- `Link.kt` — the Android side of the link: `WifiNetworkSpecifier` requests and callbacks.
- `Jobs.kt`, `Runner.kt` — the download queue and what happens to one clip (wait, resume, give up).
- `MediaFiles.kt` — files in shared storage (pending MediaStore items).
- `DownloadService.kt` — the foreground service around the queue.
- `Browser.kt`, `MainActivity.kt` — the file list and the single screen.
- `Power.kt`, `Report.kt`, `Diag.kt`, `App.kt` — battery optimisation, the Diagnostics report, last-crash note.
- `app/src/sharedTest` — `FakeCam`, used by both kinds of test.
