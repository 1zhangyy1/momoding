#!/bin/zsh
# review-shares.sh list              shares waiting for review (each saved under ./pending-shares/)
# review-shares.sh accept <id>       open a pull request for it on 1zhangyy1/momoding-skills, with your
#                                    signed-in GitHub (gh), and take it off the list
# review-shares.sh reject <id>       take it off the list
# Needs ~/.momoding-release/share-relay.properties (written by deploy-share-relay.sh).
set -e
set -a; . $HOME/.momoding-release/share-relay.properties; set +a
H=(-H "Authorization: Bearer $REVIEW_KEY")
OUT=${PENDING_DIR:-./pending-shares}
case "$1" in
  list)
    mkdir -p $OUT
    curl -fsS $H "$RELAY/v1/pending" | python3 -I -c "
import json,sys,pathlib
out=pathlib.Path(sys.argv[1]); items=json.load(sys.stdin)['items']
for i in items:
    (out/f\"{i['id']}.md\").write_text(i['skill'],encoding='utf-8')
    print(i['id'], i['name'], i.get('app',''), i.get('at',''))
print(len(items),'pending')" $OUT ;;
  accept)
    ID=$2; F=$OUT/$ID.md; [ -f $F ] || { print "run list first"; exit 1; }
    NAME=$(sed -n 's/^name:[[:space:]]*//p' $F | head -1)
    REPO=1zhangyy1/momoding-skills; BR="share/$NAME-$ID"
    SHA=$(gh api repos/$REPO/git/ref/heads/main --jq .object.sha)
    gh api repos/$REPO/git/refs -f ref="refs/heads/$BR" -f sha=$SHA >/dev/null
    OLD=$(gh api "repos/$REPO/contents/skills/$NAME/SKILL.md?ref=$BR" --jq .sha 2>/dev/null || true)
    VERB=Add; EXTRA=(); if [ -n "$OLD" ]; then VERB=Update; EXTRA=(-f sha=$OLD); fi
    gh api -X PUT repos/$REPO/contents/skills/$NAME/SKILL.md -f message="$VERB $NAME (shared from the app)" \
      -f content="$(base64 < $F | tr -d '\n')" -f branch=$BR $EXTRA >/dev/null
    gh pr create -R $REPO --head $BR --base main --title "$VERB $NAME" --body "Shared by a Momoding app (share $ID); reviewed before this PR was opened."
    curl -fsS -X DELETE $H "$RELAY/v1/pending/$ID" >/dev/null ;;
  reject)
    curl -fsS -X DELETE $H "$RELAY/v1/pending/$2" >/dev/null && rm -f $OUT/$2.md && print "rejected $2" ;;
  *) sed -n '2,6p' $0 ;;
esac
