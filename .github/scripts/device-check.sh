#!/usr/bin/env bash
# android-emulator-runner executes each script line in a separate shell.
# Keep result handling in this script, and retain the app to collect screenshots.
set -uo pipefail

gradle connectedDebugAndroidTest --stacktrace -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
test_status=$?

if ! adb pull /sdcard/Android/data/com.moyer.roleplay.next/files/screenshots app/build/ui-screenshots; then
    if [ "$test_status" -eq 0 ]; then test_status=1; fi
fi
adb logcat -d > app/build/device-logcat.txt
exit "$test_status"
