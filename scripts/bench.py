#!/usr/bin/env python3
"""Agent benchmark: the same messages, before and after a change, on the dev build (emulator).

Each case runs in a fresh chat through the debug RPC and reports, per case:
  first  seconds from the person's message to the first reply text they can see (the main measure)
  turn   seconds until the turn's last reply
  calls  model calls in the main chat for this turn (one per assistant message)
  in/out input and output tokens over those calls (output includes hidden reasoning)
  fixed  the fixed part of one request (instructions + tools), estimated tokens
For a case that hands work to a helper it also waits for the result to come back and reports
  back   seconds from the message until the main chat's reply after the result
  report characters of the result rows left in the main chat's history

Usage: scripts/bench.py LABEL [case ...]   (needs `adb forward tcp:5321 tcp:5321`)
Results go to ~/.momoding-dev/bench/LABEL.json; compare two runs with `scripts/bench.py --diff A B`.
"""
import json, os, sys, time, urllib.request

CASES = {
    "chat": "你好",
    "remind": "明天下午三点提醒我给妈妈打电话",
    "lookup": "查一下这周六北京到上海最早的一班高铁",
    "routine": "以后每天早上八点给我一份北京的天气简报",
    "helper": "派个帮手去查一下 iPhone 18 和 Pixel 11 哪个续航更好，给我结论",
}
OUT = os.path.expanduser("~/.momoding-dev/bench")


def rpc(method, params=None):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request("http://localhost:5321", body, {"content-type": "application/json"})
    reply = json.load(urllib.request.urlopen(req, timeout=180))
    if "error" in reply:
        raise RuntimeError(f"{method}: {reply['error']}")
    return reply["result"]


def messages(sid):
    return rpc("chat.messages.list", {"sessionId": sid, "limit": 500})["messages"]


def idle(sid, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if not rpc("chat.session.status", {"sessionId": sid}).get("isRunning"):
            return True
        time.sleep(0.5)
    return False


def text_of(m):
    return "".join(p.get("value", "") for p in m.get("parts", []) if p.get("type") == "text").strip()


def turn_stats(msgs, start):
    """Stats for the assistant messages of one turn, from the user row at index `start`."""
    t0 = msgs[start]["createdAt"]
    turn = []
    for m in msgs[start + 1:]:
        if m["role"] == "user" and text_of(m) and not text_of(m).startswith("<system-reminder>"):
            break  # tool results and the app's own notes are user rows too
        if m["role"] == "assistant":
            turn.append(m)
    said = [m for m in turn if text_of(m)]
    use = [m.get("tokenUsage") or {} for m in turn]
    return {
        "first": round((said[0]["createdAt"] - t0) / 1000, 1) if said else None,
        "turn": round((turn[-1]["createdAt"] - t0) / 1000, 1) if turn else None,
        "calls": len(turn),
        "in": sum(u.get("inputTokens", 0) for u in use),
        "out": sum(u.get("outputTokens", 0) for u in use),
        "fixed": use[0].get("estimatedFixedTokens") if use else None,
        "reply": " / ".join(text_of(m) for m in said)[:300],
    }


def run_case(name, prompt):
    sid = rpc("chat.prompt", {"prompt": prompt, "title": f"bench-{name}"})["sessionId"]
    time.sleep(1)
    idle(sid, 300)
    msgs = messages(sid)
    first_user = next(i for i, m in enumerate(msgs) if m["role"] == "user")
    stats = turn_stats(msgs, first_user)
    if name == "helper":
        # the result comes back as a hidden "[task …]" row and wakes the chat; wait for that turn
        end = time.time() + 600
        while time.time() < end:
            msgs = messages(sid)
            rows = [i for i, m in enumerate(msgs) if m["role"] == "user" and text_of(m).startswith("[task ")]
            done = [i for i in rows if any(w in text_of(msgs[i])[:80] for w in ("done", "failed", "ask"))]
            if done and idle(sid, 5):
                msgs = messages(sid)
                after = turn_stats(msgs, done[-1])
                t0 = msgs[first_user]["createdAt"]
                last = [m for m in msgs[done[-1] + 1:] if m["role"] == "assistant"]
                stats["back"] = round((last[-1]["createdAt"] - t0) / 1000, 1) if last else None
                stats["report"] = sum(len(text_of(msgs[i])) for i in rows)
                stats["back_calls"] = after["calls"]
                stats["back_reply"] = after["reply"]
                break
            time.sleep(2)
    stats["session"] = sid
    return stats


def show(label, results):
    print(f"\n== {label}")
    print(f"{'case':8} {'first':>6} {'turn':>6} {'calls':>5} {'in':>7} {'out':>6} {'fixed':>6} {'back':>6} {'report':>7}")
    for name, s in results.items():
        f = lambda k: "" if s.get(k) is None else str(s[k])
        print(f"{name:8} {f('first'):>6} {f('turn'):>6} {f('calls'):>5} {f('in'):>7} {f('out'):>6} {f('fixed'):>6} {f('back'):>6} {f('report'):>7}")


def main():
    if sys.argv[1:2] == ["--diff"]:
        for label in sys.argv[2:4]:
            show(label, json.load(open(f"{OUT}/{label}.json")))
        return
    label = sys.argv[1]
    names = sys.argv[2:] or list(CASES)
    results = {}
    for name in names:
        results[name] = run_case(name, CASES[name])
        print(name, json.dumps(results[name], ensure_ascii=False))
    os.makedirs(OUT, exist_ok=True)
    json.dump(results, open(f"{OUT}/{label}.json", "w"), ensure_ascii=False, indent=1)
    show(label, results)


if __name__ == "__main__":
    main()
