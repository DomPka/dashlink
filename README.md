# DashLink

A small Android app that downloads clips from a Mio MiVue dashcam over Wi-Fi **without taking over the
phone's main Wi-Fi** — so wireless Android Auto can stay connected while it works.

Status: v0.1, written from the protocol used by MiVue Pro 1.1.233.1. Not yet tested against a camera.

## What it does

- Joins the camera's Wi-Fi as a second, local-only link (Android 12+ dual Wi-Fi).
- Lists the camera's folders (Normal / Event / Parking / Photo), front or rear camera, newest first.
- Downloads ticked clips in a foreground service. If the link drops, it waits and resumes from the same byte.
- Saves finished clips to `Movies/DashLink` (photos to `Pictures/DashLink`).
- Read-only: it never changes camera settings, deletes files or formats the card.

## Build

- GitHub: push to `main`; the workflow builds the APK and publishes it as the `latest` release.
- Android Studio: open this folder and run the `app` configuration.

`debug.keystore` is a throwaway debug key kept in the repo on purpose, so each build installs over the last.

## First test

1. Switch Wi-Fi on in the camera's menu and note the network name and password it shows.
2. With Android Auto running, open DashLink, enter the name (the start of it is enough) and password, tap Connect.
3. The status line should show the camera **and** still list the car under "Other Wi-Fi on this phone".
4. Tick a clip, tap Download selected. Tap Diagnostics to share a report if anything looks wrong.

## Code map

- `Link.kt` — the second Wi-Fi link (request, reconnect, camera address).
- `Cam.kt` — the camera's HTTP interface: info, file list, resumable file download.
- `DownloadService.kt` — download queue, retry/resume, saving to shared storage.
- `MainActivity.kt` — the single screen.
