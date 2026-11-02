---
name: momoding-apps
description: How a Momoding App works — its folder, its own Momoding, window.momoding (phone commands, the model, the web, storage), checking it in your browser, reporting back. Read before you build or change a Momoding App.
---

# Momoding Apps

A Momoding App is a small app on the person's home screen with its own Momoding inside: you.
You build it the way you build any web project. This skill is only what is special here; for
everything else (how to structure it, design it, test it) use what you know, and when you are
not sure what is current, look up today's best practice with `momoding-web` before you decide.

## Principles

- Trust your judgment: decide what makes the app good, and build that. Ask the person only what
  only they can answer.
- It must work the first time they open it. Check it yourself before you hand it over, and
  after every change: everywhere the change shows, not only where you made it (a word changed
  on one screen is often on another).
- Build in small steps the person can see: each step ends with the app working on their phone and
  one short line saying what is new, then the next step. Never hold everything back for one big
  hand-over at the end.
- Something that works soon beats something polished late, and watching it grow is not waiting:
  get a first screen working in a minute or two and tell the chat that asked for it
  (`momoding-app progress "<what is there now>"`). From then on every change you make shows on
  their screen as you make it; keep going from there.
- Say what you made or changed in plain words, one or two lines, never code.
- Say only what is so. What waits on the person's yes (an icon on the home screen, a widget, a
  permission) is there once they have said it, which `momoding-app list` or the log shows; until
  then it is theirs to add, from the app's card.
- The first time you hand an app over, end with one concrete change they could ask for
  ("想加个每月预算提醒？说一声就行"), so they know a sentence changes it. Only that first time.
- Same rules as all of Momoding: what needs the person's OK still does.

## The folder

`/var/minis/apps/<name>/` is the app, a git repository (`git init` it first; if git is missing,
`apk add git`). Changes show in the app on the phone at once: there is nothing to publish.

- `index.html` is where it starts. Plain HTML, CSS and JavaScript; use libraries if they help
  (vendored into the folder, so the app works offline).
- `manifest.json` is a standard web app manifest: `short_name` is the name under the icon,
  `description` the one line under its name in the person's list of Bots (what it is for),
  `emoji` its icon: one emoji that says what it is for (`"📸"`), which Momoding draws in one
  style for every Bot, on the home screen and wherever the person sees it; `theme_color` its
  colour (its name, its chat's accent).
- `NOTES.md` is your memory of this app: what it is for, what the person asked for, what was
  decided and why, how they use it. Read it when you start; update it with every change.
- Commit each working change with a message saying what changed. Going back is `git revert`.
- `.momoding/` is the platform's and stays out of git: `data.json` (a read-only copy of what
  the person's app stored: read it to understand their use, it cannot be changed from here),
  `log` (its console and errors, newest last; lines from your own checks say `preview`).
  Read them when something is wrong. To change the person's data, change the app so it does.

## window.momoding

On the phone and in your browser alike, every page of the app has:

- `await momoding.run("<command>")` — a phone command, the same ones you run: `android-*`,
  `momoding-web`, `minis-scheduled`. Resolves to `{code, output, json}`. What needs the
  person's OK asks them, as always.
- `await momoding.ask(prompt, {system, json, images})` — the person's model as a function: the
  reply text, or a parsed value with `json: true`; `images` (data URLs) go to a model that can
  see them. It is a plain model call, not you.
- `await momoding.http(url, {method, headers, body})` — `{status, text}`; public addresses only.
  When the app needs a service (hearing speech, making pictures, any web API), write the code
  for it yourself, and borrow the key: put `{{NAME}}` where the key goes (a header or the URL),
  and Momoding fills it in on the way to that key's own site only; nothing of the app ever sees
  it. `await momoding.keys()` lists the keys Momoding holds (`name`, `about`, `site`): the model
  services it already uses are there. When none fits, `await momoding.key("NAME", {site, why})`
  asks the person once, in Momoding's own field over the app, in your plain words for `why`;
  every app can borrow it after. Make the app work without it first.
- `momoding.store.get(key)`, `.set(key, value)`, `.remove(key)`, `.all()` — what the app keeps,
  as JSON, synchronous. Keep the app's data here, not in localStorage: it survives every
  change you make, and you can read it.
- `momoding.chat(text)` — opens the app's own Momoding (you) over the app, with `text` in the
  person's box.
- `momoding.on('notification', async n => …)` — what arrives on the phone for the app: a text or
  another app's notification (`n.app`, `n.title`, `n.text`, `n.sms`, `n.at`). Say what the app
  listens to in `manifest.json`, the way routines match:
  `"momoding": {"listen": [{"app": "sms", "match": "消费|支付|收款"}]}` (`app`: part of an app's
  name, or `sms`; `match`: any of `a|b` in the title or text, written the way those messages
  really are). It wakes the app even when it is closed (run in the background, no screen), so
  handle it in code: parse it, store it, `momoding.ask` if parsing needs the model. Nothing
  missed: what is not handled is handed over again next time the app opens. The person is asked,
  on the app's screen, before it receives their texts or notifications. What the app keeps comes
  in through the app itself: when texts or notifications feed it, it listens for them, and
  nothing else writes them in for it.
- `momoding.on('share', s => …)` — what the person shares to the app from any other app, by its
  name in the share sheet (`s.text`, `s.subject`, `s.image` as a data URL). Offer it with
  `"momoding": {"share": true}` in `manifest.json` when sharing into the app makes sense (a
  screenshot of a bill into the ledger, a link into a reading list). The app opens to take it.
- `momoding.widget({title, value, lines, color})` — what the app's home-screen widget shows: a
  title, one big value, up to three short lines. Set it whenever what matters at a glance
  changes, including in an `on` handler (a text came in, today's total moved). Once it has
  something to show, the app's card in the chat offers the person its widget.
- `momoding.app` (`name`, `title`), `momoding.device` (`dark`, `language`), `momoding.preview`
  (true in your browser).
- `console.*` and uncaught errors go to `.momoding/log`.

## The phone

- The app is a web page, and whatever a page can do in a phone browser it can do here, through
  the web's own interfaces and any library built on them: take or pick a picture or a file, the
  camera and microphone, location, saving a file. `momoding.run` is for what the web has no
  interface for.
- Mobile first, one hand: big tap targets, the main action within thumb reach. The app runs
  full screen in its own window, below the status bar and above the navigation bar.
- A pen button sits in the bottom-right corner (it opens you): keep 64 px clear there.
- Follow the system's dark mode (`prefers-color-scheme`), and the person's language.

## Check it

Open `minis://apps/<name>/` (with the trailing slash) in your browser: look at a screenshot,
tap through the main flow, run JavaScript to read state, and read `.momoding/log` for errors.
Use a phone-sized viewport. Hand it over only when it works.

Then check it where it is used: on the phone. `momoding-app look` shows it on the screen and gives
a picture of it as the person sees it there (with what Momoding shows over it, like a question);
look at it with `read_image`. `momoding-app tap <x> <y>`, `swipe`, `type "<text>"` use it with a
finger, in that picture's pixels, on the page: what Momoding shows over it (a question, the pen)
is the person's, and your finger does not reach it. The camera, the real keyboard and screen size:
only the phone shows them. What the system itself shows (its permission prompt, a picker, the
camera app) is not in the picture; when the app stands waiting on the person, its `.momoding/log`
says what for, and what they answered. Never conclude something cannot work before you have seen
it on the phone and read the log.

In your browser the app's store is a copy of the person's data (`.momoding/preview.json`, made
the first time you open it there): fill it, empty it, test freely; theirs is untouched. Delete
`preview.json` to start the copy over from their current data. `momoding.run` is the same: a
command that only reads (`list`, `get`, `search`, `read`, `status`, `info`) really runs, so you
see real answers; one that would change something (an event, a reminder, a message) is not done —
it answers `{"code":0, "preview":true}` and the `log` says what it would have done. Check it there.

## Commands

- `momoding-app open <name>` — shows it on the screen.
- `momoding-app done "<what changed>"` — when a piece of work is done and checked: one line for
  the chat that asked for it (in the person's language), and under it the app's card, where the
  person opens it and puts it, and its widget, on the home screen.
- `momoding-app progress "<what is there now>"` — while building, once there is something to
  see: the same line, early. Your next line replaces it while nothing else has been said there,
  so the chat keeps one line for the work, the newest.
- In the app, `momoding.tell(text)` hands something to you (it arrives in your chat, marked as
  from the app): use it for what needs you, not the model alone — "看看这个月花得怎么样".
- What the app may use of the phone is the person's choice per app: the first time it runs a
  command that reads their things or acts for them, they are asked on its screen; a "no" makes
  `run` fail with that reason, and the app should say so plainly and carry on without it.
