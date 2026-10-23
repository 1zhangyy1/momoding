#!/bin/zsh
# deploy-share-relay.sh <your domain, e.g. momoding.app>
# Puts the Momoding share relay on https://share.<domain>. Run it yourself:
#   1. a browser window opens to sign in to Cloudflare (the domain must be on that account);
#   2. you paste a GitHub fine-grained token — Repository access: only 1zhangyy1/momoding-skills;
#      Permissions: Contents and Pull requests, read and write. It is typed without echo and goes
#      straight into the Worker's secrets; it is never written to disk or shown.
set -e
DOMAIN=$1
[ -n "$DOMAIN" ] || { print "usage: scripts/deploy-share-relay.sh <your domain>"; exit 2; }
cd ${0:a:h:h}/services/share-relay
W="npx --yes wrangler@3"
$=W whoami >/dev/null 2>&1 || $=W login
if grep -q REPLACE_WITH_KV_ID wrangler.toml; then
  ID=$($=W kv namespace create LIMITS 2>/dev/null | sed -n 's/.*id = "\([^"]*\)".*/\1/p' | head -1)
  [ -n "$ID" ] || { print "could not create the KV namespace"; exit 1; }
  sed -i '' "s/REPLACE_WITH_KV_ID/$ID/" wrangler.toml
fi
sed -i '' "s|^# routes = .*|routes = [{ pattern = \"share.$DOMAIN\", custom_domain = true }]|" wrangler.toml
print -n "GitHub fine-grained token (input hidden): "; read -rs TOKEN; print
[ -n "$TOKEN" ] || { print "no token, stopped"; exit 1; }
print -rn -- "$TOKEN" | $=W secret put GITHUB_TOKEN >/dev/null
unset TOKEN
$=W deploy
sleep 5
curl -fsS "https://share.$DOMAIN/v1/health" && print "\nrelay is up at https://share.$DOMAIN"
