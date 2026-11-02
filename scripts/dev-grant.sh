#!/bin/zsh
# One-time (per install) grants for the Momoding debug build on the EMULATOR, so the agent can
# run end-to-end walkthroughs unattended. Run it yourself; it changes the device's permission and
# accessibility settings for app.momoding.dev only. Safe to re-run.
set -e
# Two emulators may be attached; default to the walkthrough one unless told otherwise.
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
P=app.momoding.dev
for perm in READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS \
            READ_MEDIA_IMAGES READ_MEDIA_VIDEO ACCESS_MEDIA_LOCATION ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION SEND_SMS RECEIVE_SMS READ_SMS; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null || true
done
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
# Files (All files access) and notification access, for the storage and notification walkthroughs
adb shell appops set $P MANAGE_EXTERNAL_STORAGE allow
adb shell cmd notification allow_listener "$P/com.openminis.app.offload.MinisNotificationListenerService"
SVC="$P/com.openminis.app.accessibility.MinisAccessibilityService"
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in *"$SVC"*) ;; null|"") CUR="$SVC" ;; *) CUR="$CUR:$SVC" ;; esac
adb shell settings put secure enabled_accessibility_services "$CUR"
adb shell settings put secure accessibility_enabled 1
echo "granted for $P"
