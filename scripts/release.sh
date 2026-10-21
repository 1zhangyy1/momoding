#!/bin/zsh
# release.sh <version> <notes.md>   e.g.  scripts/release.sh 1.0.1 docs/releases/1.0.1.md
#
# One version, the same APK, on GitHub and on Gitee (the mirror people in mainland China can reach):
#   1. bump versionName / versionCode, build the release signed with Momoding's key
#   2. commit + tag v<version>, push to GitHub (origin) and Gitee
#   3. create the release on both with the notes, the APK and its .sha256
# Publishing is public: run it only when the person said to release.
# Needs: ~/.momoding-signing/keystore.properties (signing), ~/.momoding-release/gitee.properties
# (scripts/save-gitee-token.sh), gh logged in for GitHub.
set -e
V=$1; NOTES=$2
[ -n "$V" ] && [ -f "$NOTES" ] || { print "usage: scripts/release.sh <version> <notes.md>"; exit 2; }
ROOT=${0:a:h:h}; cd $ROOT
G=src/android/app/build.gradle.kts
[ -z "$(git status --porcelain -- src)" ] || { print "src/ has uncommitted changes; commit them first"; exit 1; }
[ -f ~/.momoding-signing/keystore.properties ] || { print "no signing key (~/.momoding-signing)"; exit 1; }
set -a; . ~/.momoding-release/gitee.properties; set +a
LOGIN=$(curl -s -m 20 -H "Authorization: token $GITEE_TOKEN" https://gitee.com/api/v5/user | python3 -c "import sys,json; print(json.load(sys.stdin)['login'])")

# 1. version + build
CODE=$(( $(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' $G | head -1) + 1 ))
sed -i '' "s/versionCode = [0-9]*/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$V\"/" $G
( cd src/android && export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  && ./gradlew :app:testDebugUnitTest --tests 'app.momoding.*' :app:assembleRelease --console=plain -q )
OUT=$(mktemp -d); APK=$OUT/Momoding-$V.apk
cp src/android/app/build/outputs/apk/release/app-release.apk $APK
( cd $OUT && shasum -a 256 Momoding-$V.apk > Momoding-$V.apk.sha256 )
print "built $APK ($(wc -c < $APK) bytes), versionCode $CODE"

# 2. commit, tag, push both
git commit -q -m "Momoding $V" -- $G
git tag -a v$V -m "Momoding $V"
git push -q origin HEAD:main v$V
git push -q "https://$LOGIN:$GITEE_TOKEN@gitee.com/$LOGIN/momoding.git" HEAD:main v$V 2>&1 | sed "s/$GITEE_TOKEN/***/g"

# 3. releases with the same files
gh release create v$V -R 1zhangyy1/momoding --title "Momoding $V" --notes-file $NOTES $APK $APK.sha256
RID=$(curl -s -m 60 -X POST "https://gitee.com/api/v5/repos/$LOGIN/momoding/releases" -H "Authorization: token $GITEE_TOKEN" \
  -F tag_name=v$V -F "name=Momoding $V" -F "body=<$NOTES" -F target_commitish=main -F prerelease=false \
  | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False)['id'])")
for f in $APK.sha256 $APK; do
  curl -s -m 900 -X POST "https://gitee.com/api/v5/repos/$LOGIN/momoding/releases/$RID/attach_files" -H "Authorization: token $GITEE_TOKEN" -F "file=@$f" >/dev/null
done
print "released v$V on GitHub and Gitee (Gitee release $RID)"
