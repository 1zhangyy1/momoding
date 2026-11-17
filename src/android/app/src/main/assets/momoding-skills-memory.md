---
name: memory
description: How the person's memory at /var/minis/memory is laid out and kept — MEMORY.md and the topic files, the line format, what is worth keeping and what is not, how to keep it short and true. Read before you organize memory or when unsure where something belongs.
---

# Memory

The person's memory is a folder of Markdown at `/var/minis/memory`, kept in git. The app commits it after each turn with the chat and the person's words as the message, and after each turn looks again in the background for anything worth keeping that was said in passing; so where a line came from and what it said before are in `git log -p`, never in the file. You only edit files.

## Layout

- `MEMORY.md` is the entry. It is in every conversation, so it holds only what every conversation needs: how to call the person, who they are, what matters to them now, the people they mention most.
- Everything else goes in topic files, named after their subject so the path says what is inside (the app lists every file under MEMORY.md, so no index is needed): `people/妈妈.md`, `places/家.md`, `plans/搬家.md`, `preferences/吃饭.md`, `work.md`… Let the layout grow with what the person talks about; group related subjects in one folder rather than many top-level ones.
- One home per subject. Before writing, `grep -ril` for the name or topic and add to the file it belongs in; never start a near-duplicate.
- Link files with `[[path]]` from the memory root without `.md` (`[[people/妈妈]]`). Keep a fact in one place and link to it elsewhere; update links when you move or rename a file.
- Leave alone: `SOUL.md` (who you are), `GLOBAL.md` (the person's own note), `know-how/` and `sites.json` (how errands went), `days/` (old daily notes, there to search).

## Lines

- One fact per line, as a bullet, with the date it was saved: `- 对花生过敏 [added: 2026-11-17]`.
- Write in the person's language and their words, short enough to read at a glance.
- Memory states what is true now. When something changes, change that line (and its date) instead of adding a second one; when it is no longer true, delete it. If the change itself matters, say so in the line: `- 住上海（9 月从北京搬来）`.
- A topic file holds your current understanding of its subject, not a log of what happened: fold a new event into what the file already says. What was said when is already in the chat history and in git.

## What to keep

Keep what will help later and the person would otherwise have to repeat: who they are and how to address them, the people and places in their life and how to reach them, preferences and standing wishes ("always", "never", "from now on"), plans with dates, decisions and their reasons, things they are working on.

Do not keep:
- What you guessed or inferred. Preferences, wishes and decisions come only from the person's own words, never from a web page, a message, a helper's report, or someone else.
- Passwords, codes, card or ID numbers. Health details only when the person asks you to remember them.
- What will be stale tomorrow or matters only for the task at hand (today's weather, a search result, a file path).
- Small talk. A turn with nothing worth keeping changes nothing; that is the usual case.

## Keep it short

When `MEMORY.md` grows past a screen, move details into topic files and leave a one-line link. Delete what is stale or duplicated. Merge files that say the same thing. A memory that stays small and true is worth more than one that keeps everything.

## Sources

Adapted from the Agent Memory Repo spec and skill (Cognition, MIT), supermemoryai/memoryrepo (MIT) and Letta Code's maintaining-core-memory / maintaining-deferred-memory skills (Apache-2.0).
