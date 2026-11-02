#!/bin/bash
# Puts your OpenRouter key into the Momoding dev build on the emulator (app.momoding.dev), through
# its debug RPC, so walkthroughs can use a real model. Run it yourself: the key is read without
# echo, sent once to the app (which keeps it encrypted), and written nowhere else. Safe to re-run:
# an OpenRouter already there gets the new key.
set -e
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
adb forward tcp:5321 tcp:5321 >/dev/null
read -rsp "OpenRouter API key (Enter = take it from the clipboard): " KEY; echo
[ -n "$KEY" ] || KEY=$(pbpaste | tr -d '[:space:]')
[ -n "$KEY" ] || { echo "no key given"; exit 1; }
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
