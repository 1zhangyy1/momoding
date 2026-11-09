#!/bin/bash
# Puts your OpenRouter key into the Momoding dev build on the emulator (app.momoding.dev), through
# its debug RPC, so walkthroughs can use a real model. The first run asks for the key (read without
# echo) and keeps it on this Mac in ~/.momoding-dev/openrouter.key (mode 600, outside the repo);
# later runs, e.g. after a reinstall, reuse it without asking. Safe to re-run; --new asks again.
set -e
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
adb forward tcp:5321 tcp:5321 >/dev/null
SAVED=~/.momoding-dev/openrouter.key
for _ in $(seq 30); do curl -s -m2 localhost:5321 >/dev/null && break; adb shell monkey -p app.momoding.dev -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2; done
if [ "$1" != "--new" ] && [ -s "$SAVED" ]; then KEY=$(cat "$SAVED"); else
  read -rsp "OpenRouter API key (Enter = take it from the clipboard): " KEY; echo
  [ -n "$KEY" ] || KEY=$(pbpaste | tr -d '[:space:]')
  [ -n "$KEY" ] || { echo "no key given"; exit 1; }
  (umask 077; mkdir -p ~/.momoding-dev; printf %s "$KEY" > "$SAVED")
fi
rpc() { curl -s -m20 localhost:5321 -d @-; }
ID=$(echo '{"jsonrpc":"2.0","id":1,"method":"provider.instances.list"}' | rpc | python3 -c '
import json, sys
print(next((i["id"] for i in json.load(sys.stdin)["result"]["instances"] if i["providerType"] == "openRouter"), ""))
')
KEY="$KEY" ID="$ID" python3 -c '
import json, os
p = {"apiKey": os.environ["KEY"]}
if os.environ["ID"]: m, p["instanceId"] = "provider.instances.update", os.environ["ID"]
else: m, p = "provider.instances.create", dict(p, providerType="openRouter", label="OpenRouter")
print(json.dumps({"jsonrpc": "2.0", "id": 1, "method": m, "params": p}))
' | rpc | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("OpenRouter key set in app.momoding.dev" if d.get("result") else "failed: %s" % d.get("error", {}).get("message"))
'
