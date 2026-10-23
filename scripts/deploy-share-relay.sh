#!/bin/zsh
# deploy-share-relay.sh <your domain, e.g. momoding.com>
# Puts the Momoding share relay on https://share.<domain>. Run it yourself: a browser window opens
# to sign in to Cloudflare (the domain must be on that account) — click Allow. Nothing else to type.
# It makes a random review key for scripts/review-shares.sh and keeps it in
# ~/.momoding-release/share-relay.properties (readable only by you); no GitHub token is involved.
set -e
DOMAIN=$1
[ -n "$DOMAIN" ] || { print "usage: scripts/deploy-share-relay.sh <your domain>"; exit 2; }
cd ${0:a:h:h}/services/share-relay
W=(npx --yes wrangler@3)
$W whoami >/dev/null 2>&1 || $W login
if grep -q REPLACE_WITH_KV_ID wrangler.toml; then
  ID=$($W kv namespace create LIMITS 2>/dev/null | sed -n 's/.*id = "\([^"]*\)".*/\1/p' | head -1)
  [ -n "$ID" ] || { print "could not create the KV namespace"; exit 1; }
  sed -i '' "s/REPLACE_WITH_KV_ID/$ID/" wrangler.toml
fi
sed -i '' "s|^# routes = .*|routes = [{ pattern = \"share.$DOMAIN\", custom_domain = true }]|" wrangler.toml
P=$HOME/.momoding-release/share-relay.properties
mkdir -p ${P:h} && chmod 700 ${P:h}
if [ ! -f $P ]; then
  KEY=$(openssl rand -hex 24)
  printf "RELAY=https://share.%s\nREVIEW_KEY=%s\n" "$DOMAIN" "$KEY" > $P && chmod 600 $P
  print -rn -- "$KEY" | $W secret put REVIEW_KEY >/dev/null
  unset KEY
fi
$W deploy
sleep 5
curl -fsS "https://share.$DOMAIN/v1/health" && print "\nrelay is up at https://share.$DOMAIN"
