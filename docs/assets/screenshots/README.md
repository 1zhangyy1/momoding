# Screenshot provenance

These screenshots were captured on 2026-08-07 from the signed public
`momoding-0.1.0-alpha.4.apk` after verifying its published SHA-256 checksum.

## Main showcase screenshots

- Environment: Android API 35 ARM64 emulator, 1080 x 2400 pixels, clean Momoding app data at the
  start of the session.
- Status bar: Android System UI demo mode, fixed at 10:00 with standard Wi-Fi and battery icons.
- `alpha4-share-draft.png`: synthetic alpha-launch notes sent through Android `ACTION_SEND`; the
  screenshot was taken only after Momoding reported that the content was ready to review and
  nothing had been sent.
- `alpha4-checklist-result.png`: a live OpenRouter model response to that synthetic task, followed
  by a real formatting follow-up. The task explicitly stopped before publishing, inviting, or
  uploading anything.
- `alpha4-device-tool-activity.png`: a separate task that really invoked
  `device_capabilities_get`, displayed the completed tool activity, and summarized the emulator's
  live permission state without requesting a permission or changing device state.

The temporary provider credential used for the live requests is not present in the repository,
screenshots, or capture scripts. Momoding app data was cleared immediately after capture.

## Additional setup screenshots

`alpha4-codex-setup.png` and `alpha4-device-capabilities.png` came from an earlier 412 x 915 capture
of the same verified APK. They use clean app data and do not claim a completed provider sign-in or
physical-device validation.

No real credentials, account details, private files, device identifiers, or user content appear in
these images. This evidence verifies live model connectivity and one local capability-tool call for
this emulator session. It does not prove ongoing provider authorization, physical-device behavior,
or production readiness.
