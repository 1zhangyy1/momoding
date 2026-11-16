#!/usr/bin/env python3
"""Behavior eval: does the main chat judge and speak right? Auto-checked, on the dev build (emulator).

bench.py measures speed and tokens; this checks behavior. Each case is a fresh chat. It sends one
or more messages, waits until everything has settled (results from helpers included), then checks
what the person would see:

  answered  the person's last message got a visible reply (not only the quiet block)
  lang      every visible line is in the language of the person's message
  leak      no command names, paths or task ids in what the person sees
  handoff   the work was handed on (True) or done right here (False), as the case expects
  calls     at most this many model calls for the first turn
  first     the first visible words came within this many seconds
  has/not   a visible reply matches / does not match a pattern (dates, numbers)
  cmd       a command the main chat ran matches a pattern

Usage: scripts/eval.py LABEL [case ...]   (needs `adb forward tcp:5321 tcp:5321`)
Results go to ~/.momoding-dev/eval/LABEL.json; compare two runs with `scripts/eval.py --diff A B`.
The dates assume the device clock: cases are written for the week of Monday 2026-11-16.
"""
import json, os, re, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

# today is Monday 2026-11-16; "this Saturday" is the 21st, "tomorrow" the 17th
CASES = {
    "greet":     {"say": ["你好"], "handoff": False, "calls": 1},
    "math":      {"say": ["37 乘以 48 等于多少"], "handoff": False, "calls": 1, "has": r"1,?776"},
    "today":     {"say": ["今天几号，星期几"], "handoff": False, "calls": 1, "has": r"16", "not": r"星期[二三四五六日天]|周[二三四五六日天]"},
    "saturday":  {"say": ["这周六是几号"], "handoff": False, "calls": 1, "has": r"21", "not": r"2[02378]\s*[日号]"},
    "abilities": {"say": ["你都能帮我做什么"], "handoff": False, "calls": 1},
    "vague":     {"say": ["帮我看看"], "handoff": False, "calls": 1},
    "fact":      {"say": ["珠穆朗玛峰有多高"], "handoff": False, "calls": 2, "has": r"8,?84[89]"},
    "allergy":   {"say": ["我对花生过敏，记一下"], "handoff": False, "calls": 3},
    "battery":   {"say": ["我手机现在还有多少电"], "handoff": False, "calls": 3, "has": r"\d+\s*%"},
    "remind":    {"say": ["明天下午三点提醒我给妈妈打电话"], "handoff": False, "calls": 3,
                  "has": r"(明天|17).*(三点|3\s*点|15[:：]00)|(三点|3\s*点|15[:：]00).*(明天|17)"},
    "routine":   {"say": ["以后每天早上八点给我一份北京的天气简报"], "handoff": False, "calls": 6,
                  "has": r"(八点|8\s*点|8[:：]00)"},
    "train":     {"say": ["查一下这周六北京到上海最早的一班高铁"], "handoff": True, "first": 5,
                  "not": r"2[02378]\s*[日号]|11月2[02378]"},
    "duck":      {"say": ["帮我比较一下北京评分最高的三家烤鸭店，人均多少、要不要排队"], "handoff": True, "first": 5},
    "phones":    {"say": ["派个帮手去查一下 iPhone 18 和 Pixel 11 哪个续航更好，给我结论"], "handoff": True, "first": 5},
    "trip":      {"say": ["这周六我要去上海玩两天，帮我把高铁和酒店都查好，做个行程"], "handoff": True, "first": 5,
                  "not": r"2[02378]\s*[日号]|11月2[02378]"},
    "english":   {"say": ["What's the weather like in Beijing tomorrow?"]},
    "japanese":  {"say": ["明日の東京の天気はどうですか？"]},
    # a second message while the first is still being worked on
    "aside":     {"say": ["查一下这周六北京到上海最早的一班高铁", (4, "对了，12 乘 12 是多少")], "handoff": True,
                  "has": r"144", "aside_first": 10},
    "cancel":    {"say": ["帮我比较一下北京评分最高的三家烤鸭店，人均多少、要不要排队", (6, "算了，别查了")], "handoff": True, "tasks": False},
    "thanks":    {"say": ["明天下午三点提醒我给妈妈打电话", (0, "谢谢")], "handoff": False},
}

# what the person must never see: command names, paths, task ids, tool plumbing
LEAK = re.compile(r"momoding-[a-z]+|/var/minis|shell_execute|browser_use|handed_on|[A-Z]+\.md\b|\bcurl\b|"
                  r"\btask [0-9a-f]{6}\b|toolUse|tool_use|```(?!momoding-)")
# no headings or tables in a chat (bold is fine: the chat draws Markdown)
MARKUP = re.compile(r"^#{1,6} |^\|.*\|$", re.M)
CARD = re.compile(r"```momoding-[a-z]+\s[\s\S]*?```")  # the app's cards: drawn as cards, not as text
QUIET = re.compile(r"```momoding-quiet[\s\S]*?```")
OUT = os.path.expanduser("~/.momoding-dev/eval")


def relaunch():
    """The debug RPC stops answering when the app falls behind another (or its process dies)."""
    import subprocess
    adb = ["adb", "-s", "emulator-5554"]
    subprocess.run(adb + ["shell", "monkey", "-p", "app.momoding.dev", "-c", "android.intent.category.LAUNCHER", "1"],
                   capture_output=True, timeout=30)
    time.sleep(4)
    subprocess.run(adb + ["forward", "tcp:5321", "tcp:5321"], capture_output=True, timeout=30)


def rpc(method, params=None, tries=3):
    for k in range(tries):
        try:
            return _rpc(method, params)
        except (OSError, TimeoutError) as e:  # urllib's URLError and socket timeouts are OSErrors
            if k == tries - 1:
                raise
            relaunch()


def _rpc(method, params=None):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request("http://localhost:5321", body, {"content-type": "application/json"})
    reply = json.load(urllib.request.urlopen(req, timeout=60))
    if "error" in reply:
        raise RuntimeError(f"{method}: {reply['error']}")
    return reply["result"]


def messages(sid):
    return rpc("chat.messages.list", {"sessionId": sid, "limit": 500})["messages"]


def running(sid):
    return rpc("chat.session.status", {"sessionId": sid}).get("isRunning")


def texts(m):
    return [p["value"] for p in m.get("parts", []) if p.get("type") == "text" and p.get("value", "").strip()]


def is_person(m):
    t = "".join(texts(m))
    return m["role"] == "user" and t and not t.startswith("<system-reminder>") and not t.startswith("[task ")


def has_han(s):
    return any('一' <= c <= '鿿' for c in s)


def language(s):
    s = QUIET.sub("", s)
    if re.search(r"[぀-ヿ]", s):
        return "ja"
    if has_han(s):
        return "zh"
    return "en" if re.search(r"[A-Za-z]{3}", s) else None


def visible(msgs, person):
    """(index, createdAt, text) of what the person sees from the assistant, as the chat draws it:
    quiet-only replies are hidden, and so is Latin self-talk ahead of a later tool call (ChatFlatItems)."""
    out = []
    for i, m in enumerate(msgs):
        if m["role"] != "assistant":
            continue
        parts = m.get("parts", [])
        for k, p in enumerate(parts):
            if p.get("type") != "text" or not p.get("value", "").strip():
                continue
            t = p["value"]
            if not QUIET.sub("", t).strip():
                continue
            later_tool = any(q.get("type") == "toolUse" for q in parts[k + 1:]) or (
                i + 1 < len(msgs) and msgs[i + 1]["role"] == "user" and not is_person(msgs[i + 1])
                and any(q.get("type") == "toolResult" for q in msgs[i + 1].get("parts", [])))
            if later_tool and has_han(person) and not has_han(t) and len(re.findall(r"[A-Za-z]", t)) >= 3:
                continue
            out.append((i, m["createdAt"], t))
    return out


def commands(msgs, start, end):
    cmds, handed = [], 0
    for m in msgs[start:end]:
        for p in m.get("parts", []):
            v = p.get("value") if isinstance(p.get("value"), dict) else {}
            if p.get("type") == "toolUse":
                try:
                    cmds.append(json.loads(v.get("input") or "{}").get("command") or v.get("name"))
                except ValueError:
                    cmds.append(v.get("name"))
            if p.get("type") == "toolResult" and re.search(r'"handed_on"\s*:\s*true', str(v.get("output", ""))):
                handed += 1
    return cmds, handed


def settle(sid, timeout, tasks=True):
    """Wait until the chat is idle and stays idle; with `tasks`, also until every piece of work it
    handed on has reported back (the result wakes the chat again)."""
    end, quiet_since = time.time() + timeout, None
    while time.time() < end:
        busy = running(sid)
        if not busy and tasks:
            msgs = messages(sid)
            _, handed = commands(msgs, 0, len(msgs))
            back = sum(1 for m in msgs if m["role"] == "user" and "".join(texts(m)).startswith("[task "))
            busy = handed > back
        if busy:
            quiet_since = None
        elif quiet_since is None:
            quiet_since = time.time()
        elif time.time() - quiet_since > 6:
            return True
        time.sleep(1.5)
    return False


def run_case(name, case):
    say = [s if isinstance(s, tuple) else (0, s) for s in case["say"]]
    sid = rpc("chat.prompt", {"prompt": say[0][1], "title": f"eval-{name}"})["sessionId"]
    for delay, text in say[1:]:
        if delay:
            time.sleep(delay)
        else:
            settle(sid, 300, tasks=False)
        rpc("chat.prompt", {"prompt": text, "sessionId": sid})
    time.sleep(2)
    timed_out = not settle(sid, 600, tasks=case.get("tasks", True))
    msgs = messages(sid)
    persons = [i for i, m in enumerate(msgs) if is_person(m)]
    first_p, last_p = persons[0], persons[-1]
    asked = "".join(texts(msgs[first_p]))
    seen = visible(msgs, asked)
    # the first turn: up to the next message from the person
    end1 = persons[1] if len(persons) > 1 else len(msgs)
    turn1 = []
    for m in msgs[first_p + 1:end1]:
        if m["role"] == "user" and "".join(texts(m)).startswith("[task "):
            break  # a result woke the chat: a turn of its own
        if m["role"] == "assistant":
            turn1.append(m)
    cmds, handed = commands(msgs, first_p, end1)
    first_seen = next((t for i, t, _ in seen if i > first_p), None)
    words = [t for _, _, t in seen]

    checks = {}
    after_last = [t for i, _, t in seen if i > last_p]
    checks["answered"] = bool(after_last)
    want = language(asked)
    slips = [t[:60] for t in words if language(t) not in (want, None)]
    checks["lang"] = not slips
    leaks = sorted({m.group(0) for t in words for m in [LEAK.search(CARD.sub("", t))] if m})
    checks["leak"] = not leaks
    markup = [t[:60] for t in words if MARKUP.search(CARD.sub("", t))]
    checks["plain"] = not markup
    if "handoff" in case:
        checks["handoff"] = (handed > 0) == case["handoff"]
    if "calls" in case:
        checks["calls"] = len(turn1) <= case["calls"]
    if "first" in case:
        checks["first"] = first_seen is not None and (first_seen - msgs[first_p]["createdAt"]) / 1000 <= case["first"]
    if "aside_first" in case:
        ans = next((t for i, t, _ in seen if i > last_p), None)
        checks["aside_first"] = ans is not None and (ans - msgs[last_p]["createdAt"]) / 1000 <= case["aside_first"]
    joined = "\n".join(words)
    if "has" in case:
        checks["has"] = bool(re.search(case["has"], joined))
    if "not" in case:
        checks["not"] = not re.search(case["not"], joined)
    if "cmd" in case:
        checks["cmd"] = any(c and re.search(case["cmd"], c) for c in cmds)

    return {
        "pass": all(checks.values()) and not timed_out,
        "checks": checks,
        "timed_out": timed_out,
        "first": round((first_seen - msgs[first_p]["createdAt"]) / 1000, 1) if first_seen else None,
        "calls": len(turn1),
        "handed": handed,
        "slips": slips,
        "leaks": leaks,
        "markup": markup,
        "cmds": [str(c)[:120] for c in cmds],
        "said": words,
        "session": sid,
    }


def show(label, results):
    ok = sum(r["pass"] for r in results.values())
    print(f"\n== {label}: {ok}/{len(results)} pass")
    for name, r in results.items():
        bad = [k for k, v in r["checks"].items() if not v] + (["timeout"] if r["timed_out"] else [])
        mark = "✓" if r["pass"] else "✗"
        print(f"{mark} {name:10} first={r['first']!s:>5} calls={r['calls']:<2} {'FAIL ' + ','.join(bad) if bad else ''}")
        if bad:
            print(f"    said: {' / '.join(r['said'])[:240]}")


def main():
    if sys.argv[1:2] == ["--diff"]:
        a, b = (json.load(open(f"{OUT}/{l}.json")) for l in sys.argv[2:4])
        for name in a.keys() | b.keys():
            pa, pb = a.get(name, {}).get("pass"), b.get(name, {}).get("pass")
            if pa != pb:
                print(f"{name:10} {pa} -> {pb}")
        show(sys.argv[2], a)
        show(sys.argv[3], b)
        return
    label = sys.argv[1]
    names = sys.argv[2:] or list(CASES)
    relaunch()
    # a clean slate: earlier runs leave notes, USER.md lines and routines that leak into later cases
    for s in rpc("chat.sessions.list", {"limit": 500})["sessions"]:
        if s["title"].startswith(("eval-", "bench-")) or s["title"].startswith("Agent ·"):
            try:
                rpc("chat.session.delete", {"sessionId": s["id"], "confirm": True})
            except RuntimeError:
                pass  # a helper's chat goes with the chat that started it
    rpc("momoding.reset", {"confirm": True})
    rpc("debug.shellExecute", {"command": "rm -f /var/minis/memory/USER.md"})
    # a few chats at a time: each case has its own chat, and most of the wait is the network
    with ThreadPoolExecutor(2) as pool:
        futures = {n: pool.submit(run_case, n, CASES[n]) for n in names}
        results = {}
        for n in names:
            try:
                results[n] = futures[n].result()
            except Exception as e:
                results[n] = {"pass": False, "checks": {"error": False}, "timed_out": False, "first": None,
                              "calls": 0, "said": [str(e)[:200]], "session": None}
            print(n, "✓" if results[n]["pass"] else "✗", json.dumps(results[n]["checks"]), flush=True)
    os.makedirs(OUT, exist_ok=True)
    json.dump(results, open(f"{OUT}/{label}.json", "w"), ensure_ascii=False, indent=1)
    show(label, results)


if __name__ == "__main__":
    main()
