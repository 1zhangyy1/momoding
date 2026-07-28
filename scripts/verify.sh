#!/usr/bin/env bash
set -euo pipefail

repository_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_dir"

if [[ -z "${JAVA_HOME:-}" ]]; then
  printf 'JAVA_HOME must point to JDK 17.\n' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  printf 'ANDROID_HOME must point to an Android SDK containing Platform 37.\n' >&2
  exit 1
fi

unset OPENROUTER_API_KEY
unset OPENAI_API_KEY
unset ANTHROPIC_API_KEY

if [[ -n "${SOURCE_REVISION:-}" ]]; then
  source_revision="$SOURCE_REVISION"
elif [[ -f .public-source.json ]]; then
  source_revision="$(
    node -e '
      const fs = require("node:fs");
      const value = JSON.parse(fs.readFileSync(".public-source.json", "utf8")).sourceRevision;
      if (typeof value !== "string") process.exit(1);
      process.stdout.write(value);
    '
  )"
else
  source_revision="$(git rev-parse HEAD)"
fi
if [[ ! "$source_revision" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'SOURCE_REVISION must be a full 40-character Git SHA.\n' >&2
  exit 1
fi
export SOURCE_REVISION="$source_revision"

./scripts/check-public-repo.sh

npm ci --prefix mobile-runtime-js
npm run check --prefix mobile-runtime-js

./wire/kotlin-contract/gradlew \
  -p wire/kotlin-contract \
  --no-daemon \
  test

./android-app/gradlew \
  -p android-app \
  --no-daemon \
  testDebugUnitTest \
  compileDebugAndroidTestKotlin \
  lintDebug \
  assembleDebug \
  assembleRelease

node ./scripts/verify-release-apk.mjs

if ! git diff --quiet -- .; then
  printf 'Verification changed tracked files:\n' >&2
  git diff --stat >&2
  exit 1
fi

printf 'Momoding verification passed for %s.\n' "$SOURCE_REVISION"
