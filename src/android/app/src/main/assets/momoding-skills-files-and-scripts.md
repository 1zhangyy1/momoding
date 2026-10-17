---
name: files-and-scripts
description: How to work in the Linux sandbox — shared folders under /var/minis, minis:// links and inline media, writing files, BusyBox ash quirks, Python packages on musl, background servers, ripgrep, the interactive terminal, environment variables. Read before writing scripts, handling files, installing packages or running programs.
---

# Files and scripts in the sandbox

Read this when a task needs scripts, files, packages or programs in the Linux sandbox. The person never needs to hear about any of this; tell them the result.

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is BusyBox ash: heredoc syntax (cat << EOF, python3 << 'EOF') may mis-parse braces, quotes, or special characters and execute abnormally — avoid it whenever possible, and prefer file_write over echo/printf for writing file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Also (BusyBox ash, NOT bash): brace expansion ({a,b,c}) and bash arrays (arr=(...), ${arr[@]}) are unsupported — use space-separated strings with a for loop or multiple arguments instead.
- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. Only fall back to `pip install` for pure-Python packages not available via apk. For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search & text scanning: use ripgrep (`rg`) for EVERY scan of files — finding names, keyword/regex search, field extraction, log analysis — on one file or a whole tree. Never use grep/awk/sed/find here, rg is far faster. Contents: `rg -n 'pattern' <dir>` (`-i` ignore case, `-w` whole word, `-F` literal, `-l` file names only, `-t py` or `-g '*.md'` to filter files). File names: `rg --files <dir> -g '*.pdf'`, or `rg --files <dir> | rg 'name'`. rg skips hidden, .gitignored and binary files by default; add `-uuu` to search everything. If `which rg` finds nothing, install it once with `apk add ripgrep`. `**` recursive glob (globstar) is not supported by the shell — use rg instead. When looking for user files, do NOT scan the whole filesystem: search /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders), and widen the scope only if the file is clearly not there.

Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. $API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "$VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo $VAR, printenv VAR, or any command that would output the actual value into the conversation context.
