#!/bin/zsh
# One-time (per install) grants for the Momoding debug build on the EMULATOR, so the agent can
# run end-to-end walkthroughs unattended. Run it yourself; it changes the device's permission and
# accessibility settings for app.momoding.dev only. Safe to re-run.
set -e
P=app.momoding.dev
for perm in READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null || true
done
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
SVC="$P/com.openminis.app.accessibility.MinisAccessibilityService"
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; null|"") CUR="$SVC" ;; *) CUR="$CUR:$SVC" ;; esac
adb shell settings put secure enabled_accessibility_services "$CUR"
adb shell settings put secure accessibility_enabled 1
echo "granted for $P"
