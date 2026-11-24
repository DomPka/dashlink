# DashLink

A small Android app that downloads clips from a Mio MiVue dashcam over Wi-Fi **without taking over the
phone's main Wi-Fi** — so wireless Android Auto can stay connected while it works.

Status: v0.4, written from the protocol used by MiVue Pro 1.1.233.1 and, for the live view, from what is
published about cameras with the same firmware family. **Not yet tested against a camera or in a
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
- On a phone that can hold only one Wi-Fi connection (the Diagnostics report says "Two Wi-Fi links at once
  (local-only): false"), connecting to the camera replaces the phone's other Wi-Fi, wireless Android Auto
  included, and DashLink says so before it connects. If Android then gives the Wi-Fi back to something else,
  DashLink does not take it again by itself (that would cut Android Auto off once more, and it would take the
  Wi-Fi back in turn): tap Connect when you want the camera, and downloads go on from where they stopped. The
  Diagnostics report lists the link's events with the time of day, so a drop can be matched to what happened.
- Saves clips straight into `Movies/DashLink` (photos into `Pictures/DashLink`). An unfinished clip is hidden
  from the Gallery until it is complete; there is never a second copy.
- Remembers the queue: clips that were queued when Android stopped the app are still queued, and continue from
  where they were, the next time the camera is connected.
- Shows what the camera sees, live: a small picture on the main screen and a full-screen viewer. The app has
  its own small RTSP client (H.264 or Motion-JPEG over RTP, TCP first, UDP if the camera wants it), because
  Android's own player cannot be told to use the camera's Wi-Fi link instead of the phone's main one.
- Plays clips straight from the camera's SD card, with seeking, and shows a small picture of each clip in the
  list. Both read the clip in pieces (HTTP `Range`), so nothing is saved until Download is tapped.
- Shares the camera fairly: one request at a time, downloads and file lists first. The live picture gives
  the camera up the moment anything else asks for it and comes back by itself; it never touches the camera
  while a download runs, and neither do the clip previews.
- Read-only: it never changes camera settings, deletes files or formats the card. The only HTTP requests it
  can send are `action=get`, `action=dir`, `action=reardir` and plain file downloads. The live view adds only
  the RTSP requests that read a stream (`OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `GET_PARAMETER`, `TEARDOWN`),
  and only for the camera's stream paths `/liveRTSP/av1`-`av9` and `/liveRTSP/v1`-`v9`. Anything else is
  refused inside the app before it reaches the network.

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
- the download queue: link drop, 404, phone full, Stop, saved and restored queue;
- the live view against a fake RTSP server (`FakeRtsp`): H.264 split every way RFC 6184 allows (single
  packets, STAP-A, FU-A), Motion-JPEG rebuilt per RFC 2435 and checked pixel by pixel, lost, repeated and
  late packets, TCP and the UDP fallback, keep-alive, stream paths that do not exist, a camera that wants a
  password, hostile stream descriptions, giving the camera up to a download within a fraction of a second
  and coming back, Stop from every state, and that nothing but the six reading requests is ever sent;
- reading a clip in pieces (`RemoteFile`): random reads anywhere, the way Android's player reads a clip
  (start, index at the end, then straight through) in three requests, read-ahead, a camera that ignores
  the end of a range or `Range` altogether, a camera that caps its replies, replies that are not what was
  asked for, cuts, stalls, close, and giving the camera up to whoever waits for it.

`./gradlew connectedDebugAndroidTest` — on an emulator or phone. CI runs them on two emulators: API 34
(Android 14) and API 36 (Android 16). The real app, service and MediaStore, with `FakeCam` as the camera and a
pretend Wi-Fi link: permission dialogs granted and denied, Android's Wi-Fi prompt declined, left alone and
timed out, Wi-Fi off, downloads through the screen into shared storage, a queue left behind by a killed
process, the app being rebuilt, rotated and resized, the battery-optimisation question, the Diagnostics report.
The media tests make their own video on the emulator (Android's H.264 encoder) and serve it through the fakes:
the live tile and the full-screen viewer play H.264 and Motion-JPEG streams, give the camera up to a download or
a file list and come back by themselves; the player plays and seeks a clip with its index at the end (as a
dashcam writes it) by reading only ranges, downloads it, and lets go of the camera when closed; thumbnails come
from the clip, then from the cache, and are never fetched while a download runs.

`ci/smoke.sh app.apk` — installs the release APK on the emulator and pokes it over plain adb, with all
permissions granted and with all denied. Any crash or ANR of the app in logcat fails the build.

Every run also takes pictures of the screen in its main states (`ScreenshotTest`: not connected, permissions
missing, a list of clips, a download, dark mode, an unfolded and a sideways screen, the live tile playing, not
available, dark and wide; `MediaShotsTest`: the viewer sideways and upright, busy and not available, the player
light, dark and unable to play). They are in the
`screenshots` folder of the emulator jobs' artifacts - the quickest way to see what a change did to the look.

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
- Wi-Fi power saving with the screen off: Android 14 and later treat the Wi-Fi lock the app takes as a
  "low latency" lock, which only counts while the app is on screen, so a download in the background may be slower;
- Android refusing to start the download service: the refusal is caught and shown on the screen, but nothing
  on an emulator provokes it reliably;
- transfer speed, and how the camera copes with being asked for many large files in a row;
- everything about the camera's live stream: which of `av1`-`av4` / `v1`-`v3` exist and which is front or
  rear, whether it streams over TCP or only UDP, what its stream description looks like, how long it waits
  between key frames (the app waits 6 s for the first picture), whether it keeps recording and answering file
  requests while it streams, and whether `Camera.Preview.RTSP.av` and `.keepalive` mean what the app assumes;
- whether the camera honours a range with an end (`bytes=a-b`), and where its clips keep their index (at the
  start or the end of the file): that decides how quickly a clip starts playing and a thumbnail appears;
- how long a thumbnail takes over the camera's Wi-Fi, and whether the camera has a faster way to give one
  (the app makes them on the phone from the clip itself).

The Diagnostics report is built for exactly this: it says what the phone supports, what the link and the queue
did, why Android last stopped the app, and what the camera answered — without the Wi-Fi password.

## Code map

- `Cam.kt` — the camera's HTTP interface: info, file list, resumable file download, the one-request-at-a-time
  gate. No Android classes.
- `Live.kt`, `LiveRun.kt`, `Rtsp.kt`, `Rtp.kt`, `Jpeg.kt` — the live view: RTSP client, RTP depacketizers
  (H.264, Motion-JPEG), the loop that finds a stream, keeps it alive and gives the camera up. No Android classes.
- `RemoteFile.kt` — a clip on the camera read in pieces over HTTP `Range`, for playback and thumbnails.
- `LinkCore.kt` — the rules of the second Wi-Fi link (ask, retry, give up, camera address). No Android classes.
- `Link.kt` — the Android side of the link: `WifiNetworkSpecifier` requests and callbacks.
- `Jobs.kt`, `Runner.kt` — the download queue and what happens to one clip (wait, resume, give up).
- `MediaFiles.kt` — files in shared storage (pending MediaStore items).
- `DownloadService.kt` — the foreground service around the queue.
- `Browser.kt`, `MainActivity.kt` — the file list and the main screen with the live tile and thumbnails (built
  in code from platform widgets). On a wide screen it shows two columns.
- `LiveSurface.kt`, `LiveActivity.kt` — the live picture (MediaCodec for H.264, drawn frames for Motion-JPEG)
  and the full-screen viewer.
- `PlayerActivity.kt`, `ClipSource.kt`, `MediaChrome.kt` — the clip player (Android's MediaPlayer reading the
  clip through `RemoteFile`) and its shared bits.
- `Thumbs.kt` — clip thumbnails, made on the phone from the clip, kept in memory and on disk.
- `Power.kt`, `Report.kt`, `Diag.kt`, `App.kt` — battery optimisation, the Diagnostics report, last-crash note.
- `Tokens.kt`, `Glass.kt`, `Type.kt`, `Drawables.kt` — the look ("Lens Glass"): colours from the wallpaper
  (Material You) with fixed signal colours, frosted-glass surfaces, type styles and the drawn details.
- `app/src/sharedTest` — `FakeCam` and `FakeRtsp`, used by both kinds of test; `app/src/androidTest/TestVideo.kt`
  makes the test video on the emulator.
