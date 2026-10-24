---
name: about-momoding
description: Momoding's own source code, the exact version installed on this phone, and a map of it. Read when the person asks about Momoding itself — what it can or cannot do, how a feature works, why something did not work, where a setting is, what changed — or when you need to know how one of your own cards, permissions or settings behaves.
---

# About Momoding — your own source

Momoding is open source. The code of the version on this phone is in `source/` next to this file
(`/var/minis/skills/about-momoding/source/`), read-only and refreshed on every update. Search it
with `rg` and read the files that answer the question; do not read it all.

How to use it:
- Answer from what the code says, in plain words, as you would about anything else: what you can
  do, what you cannot and why, what the person can change and where (give the settings link).
  Never paste code, file paths or class names to the person.
- If the code shows you cannot do something, say so; do not promise what it does not support.
- Look, never change: this copy is for reading. Your behaviour does not change by editing it.

## Map

Momoding is built on OpenMinis (an open-source Android agent). Momoding's own code is under
`source/java/app/momoding/`; the OpenMinis files Momoding changed are under
`source/java/com/openminis/`, with each change marked `// momoding`.

| Folder | What it is |
| - | - |
| `prompt/` | What you are told every turn: `MomodingPrompt.kt` (how you work, the phone, tools), `MomodingRules.kt`, `MomodingHelper.kt` |
| `ui/chat/` | The chat cards: ask / next steps, drafts, sign-in handoff with the QR code (`MomodingBlock.kt`, `QrWatch.kt`), permission cards, reactions |
| `hands/` | Operating apps on the screen: the loop (`HandsOperator.kt`), how the screen is read (`ScreenTree.kt`), the hidden background screen (`HandsDisplay.kt`), Shizuku install, the capsule with Stop |
| `web/` | Looking things up without the browser: `momoding-web search` / `read` (`WebLookup.kt`, `WebText.kt`) |
| `guard/` | Safety: which actions need the person's OK (`RiskPolicy.kt`, `RiskGate.kt`), what the shell may run |
| `chat/` | Routines and reminders, notifications, SMS, drafts, error wording |
| `ui/settings/`, `ui/hands/` | The Settings pages: models, phone permissions, operating the screen |
| `providers/` | Model setup: which models are recommended, provider templates |
| `memory/` | `USER.md`, what you keep about the person |
| `home/`, `onboarding/` | Opening the main chat, the first run, the recovery page after crashes |
| `update/`, `status/` | Self-update, keeping the phone awake while working |

Settings links you can give: `minis://settings/<page>`; the pages are listed in
`parseSettingsPath` in `source/java/com/openminis/app/deeplink/DeepLinkHandler.kt`.
UI wording in the person's language: `source/res/values-zh/momoding_*.xml` (English: `values/`).
