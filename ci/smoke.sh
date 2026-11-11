#!/usr/bin/env bash
# Smoke test for the very APK that is about to be published, on a running emulator, over plain adb.
# It does what a first-time user would do - once with every runtime permission granted, once with
# every one denied - and fails if the app crashes or stops responding at any point.
#
#   ci/smoke.sh path/to/DashLink.apk
#
# The camera is not involved: there is none. This only shows that the app survives without one.
set -uo pipefail

APK="${1:?usage: smoke.sh path/to/app.apk}"
PKG=com.dompka.dashlink
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="${SMOKE_LOG:-smoke-logcat.txt}"

ui() { python3 "$HERE/ui.py" "$@"; }
step() { echo; echo "== $*"; }

finish() {
    sleep 2
    adb logcat -d -v threadtime > "$LOG" 2>/dev/null
    bash "$HERE/crashes.sh" "$LOG"
}

die() {
    echo "SMOKE TEST FAILED: $*" >&2
    ui texts >&2
    finish
    exit 1
}

alive() {
    adb shell pidof "$PKG" > /dev/null || die "the app is no longer running ($1)"
}

launch() {
    adb shell am start -n "$PKG/.MainActivity" > /dev/null || die "could not start the app"
}

# Tap Connect with nothing filled in, then with a Wi-Fi name that does not exist, and decline Android's prompt.
connect_twice() {
    step "Connect with empty fields"
    ui type id=ssid "" || die "could not empty the name field"
    ui type id=pass "" || die "could not empty the password field"
    ui tap id=connect || die "no Connect button"
    sleep 2
    ui expect id=status "Not connected to the camera" 10 || die "tapping Connect with empty fields changed the state"
    ui expect id=connect "^connect$" 5 || die "the button no longer says Connect"
    alive "after Connect with empty fields"

    step "Connect to a Wi-Fi name that does not exist"
    ui type id=ssid DashLinkNoSuchCam || die "could not type the name"
    ui type id=pass 12345678 || die "could not type the password"
    ui tap id=connect || die "no Connect button"
    # A phone that cannot hold two Wi-Fi links (the emulator) is warned first.
    ui tap-if "text=connect anyway" 6
    # Android's own "choose a device" prompt. Decline it; if its button cannot be found, Back does the same.
    if [ "$(ui tap-if 'text=cancel' 45)" != "tapped" ]; then
        adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q settings \
            || die "Android's Wi-Fi prompt did not appear"
        adb shell input keyevent KEYCODE_BACK
    fi
    ui expect id=status "declined" 45 || die "the refusal was not reported"
    ui expect id=connect "^connect$" 10 || die "the button did not go back to Connect"
    sleep 6
    ui expect id=status "declined" 5 || die "something happened after the prompt was declined"
    ui absent "text=cancel" || die "Android's Wi-Fi prompt came back by itself"
    alive "after a declined Wi-Fi prompt"
}

# The same start command the app sends itself. With nothing queued the service must come up, find
# nothing to do and leave again; stopping it from outside afterwards must not upset anything either.
service_start_stop() {
    step "Start and stop DownloadService"
    adb shell am start-foreground-service -n "$PKG/.DownloadService" || die "could not start the service"
    sleep 4
    adb shell am stopservice -n "$PKG/.DownloadService"
    sleep 2
    adb shell am start-foreground-service -n "$PKG/.DownloadService" || die "could not start the service again"
    sleep 4
    alive "after starting and stopping the service"
    ui expect id=status "." 15 || die "the screen is gone after starting and stopping the service"
}

# Root is needed to start a service that is not exported. Emulator images allow it.
adb root > /dev/null 2>&1
adb wait-for-device
adb logcat -G 16M
adb logcat -c
adb shell settings put secure show_ime_with_hard_keyboard 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

step "1. Fresh install with every runtime permission granted"
adb uninstall "$PKG" > /dev/null 2>&1
adb install -g "$APK" || die "the APK does not install"
launch
ui expect id=status "Not connected to the camera" 90 || die "the main screen did not appear"
ui absent id=permission_deny_button || die "asked for a permission although all are granted"
ui absent id=notice || die "a notice is shown although nothing is wrong"
alive "after the first start"
connect_twice
service_start_stop

step "2. Fresh install with every runtime permission denied"
adb uninstall "$PKG" > /dev/null || die "could not uninstall"
adb install "$APK" || die "the APK does not install"
launch
denied=0
for i in 1 2 3 4 5 6; do
    if [ "$(ui tap-if id=permission_deny_button $([ $denied -eq 0 ] && echo 60 || echo 8))" = "tapped" ]; then
        denied=$((denied + 1))
        sleep 1
    else
        break
    fi
done
echo "permission questions answered with no: $denied"
[ "$denied" -eq 3 ] || die "expected 3 permission questions (location, nearby devices, notifications), got $denied"
ui expect id=status "Not connected to the camera" 30 || die "the main screen did not come back after the permission questions"
ui expect id=notice "Location.*Nearby devices.*Notifications" 20 || die "the missing permissions are not explained"
alive "after denying every permission"
connect_twice
service_start_stop

step "3. Start again: no questions the second time"
adb shell am force-stop "$PKG"
launch
ui expect id=status "Not connected to the camera" 60 || die "the main screen did not appear on the second start"
sleep 3
ui absent id=permission_deny_button || die "the permission question came back on the second start"
ui expect id=notice "Location" 10 || die "the notice about missing permissions is gone"
alive "after the second start"

step "4. A download queue left behind by a killed process is back after a restart"
adb shell am force-stop "$PKG"
# Written the way the app writes it: two clips still queued, and the note that work was under way.
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/dashlink.xml'" <<'XML' || die "could not plant the saved queue"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="queue">/SD/Normal/F/SMOKE1.MP4&#9;1000&#9;2026-01-01 00:00:00&#9;1920x1080&#9;60&#9;&#10;/SD/Normal/F/SMOKE2.MP4&#9;2000&#9;2026-01-01 00:01:00&#9;1920x1080&#9;60&#9;</string>
    <boolean name="working" value="true" />
    <boolean name="askedPermissions" value="true" />
</map>
XML
launch
ui expect id=notice "2 download\(s\) were cut off" 60 || die "the interrupted downloads are not reported after the restart"
ui expect id=progress "2 queued, 0 saved" 15 || die "the queue did not come back"
alive "after restoring a queue"
ui tap id=stop || die "no Stop button"
ui expect id=progress "0 queued, 0 saved" 15 || die "Stop did not empty the restored queue"
ui expect id=notice "Location" 15 || die "the notice did not return to the missing permissions"
alive "after stopping a restored queue"

adb shell am force-stop "$PKG"
finish || exit 1
adb uninstall "$PKG" > /dev/null 2>&1
echo
echo "Smoke test passed."
