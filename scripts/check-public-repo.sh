#!/usr/bin/env bash
set -euo pipefail

repository_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_dir"

failures=0

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  failures=$((failures + 1))
}

for required in \
  README.md \
  README.zh-CN.md \
  .public-source.json \
  LICENSE \
  CONTRIBUTING.md \
  CODE_OF_CONDUCT.md \
  SECURITY.md \
  SUPPORT.md \
  CHANGELOG.md \
  RELEASING.md \
  THIRD_PARTY_NOTICES.md \
  OPEN_SOURCE_SCOPE.md \
  docs/ARCHITECTURE.md \
  docs/SECURITY_MODEL.md \
  licenses/MIT.txt \
  licenses/Apache-2.0.txt \
  licenses/GPL-2.0-only.txt \
  licenses/LGPL-3.0-or-later.txt \
  licenses/SQLite-Public-Domain.txt \
  android-app/PHOSPHOR-NOTICE.md \
  android-app/gradle/verification-metadata.xml \
  wire/kotlin-contract/gradle/verification-metadata.xml \
  .github/workflows/ci.yml \
  .github/dependabot.yml \
  .github/pull_request_template.md \
  .github/ISSUE_TEMPLATE/config.yml \
  .github/ISSUE_TEMPLATE/bug_report.yml \
  .github/ISSUE_TEMPLATE/feature_request.yml \
  scripts/check-markdown-links.mjs \
  scripts/check-public-sync.sh \
  scripts/export-public-repo.sh \
  scripts/public-export-paths.txt \
  scripts/verify-release-apk.mjs \
  third_party/patches/proot-5.1.107.86-android-ndk.patch; do
  if [[ ! -f "$required" ]]; then
    fail "required public file is missing: $required"
  fi
done

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  fail "the public check must run inside a Git worktree"
fi
if ! git rev-parse --verify HEAD >/dev/null 2>&1; then
  fail "the public snapshot must have a Git commit before it can pass"
fi

if [[ -f .public-source.json ]]; then
  metadata_values="$(
    node -e '
      const fs = require("node:fs");
      const value = JSON.parse(fs.readFileSync(".public-source.json", "utf8"));
      process.stdout.write(`${value.sourceRevision ?? ""}\t${value.exportRevision ?? ""}`);
    ' 2>/dev/null || true
  )"
  IFS=$'\t' read -r source_revision export_revision <<< "$metadata_values"
  if [[ ! "$source_revision" =~ ^[0-9a-f]{40}$ ]]; then
    fail ".public-source.json sourceRevision must be a full Git SHA"
  fi
  if [[ ! "$export_revision" =~ ^[0-9a-f]{40}$ ]]; then
    fail ".public-source.json exportRevision must be a full Git SHA"
  fi
  runtime_revision="$(
    node -e '
      const fs = require("node:fs");
      const value = JSON.parse(
        fs.readFileSync("android-app/app/src/main/assets/pi-runtime/manifest.json", "utf8"),
      ).buildRevision;
      if (typeof value !== "string") process.exit(1);
      process.stdout.write(value);
    ' 2>/dev/null || true
  )"
  if [[ "$runtime_revision" != "$source_revision" ]]; then
    fail "runtime buildRevision must match .public-source.json sourceRevision"
  fi
fi

while IFS= read -r path; do
  case "$path" in
    */build/*|*/node_modules/*|*/.gradle/*|.idea/*|artifacts/*|captures/*|reports/*|\
    docs/validation/*|local.properties|*.iml|*.apk|*.aab|*.ipa|*.pem|*.key|*.jks|\
    *.keystore|*.p12|*.pfx|*.mobileprovision|*.tgz|*.tar|*.tar.gz|*.log)
      fail "forbidden tracked path: $path"
      ;;
  esac
done < <(git ls-files)

if git ls-files -s | awk '$1 == "120000" { print $4 }' | grep -q .; then
  fail "tracked symbolic links are not allowed in the public snapshot"
fi

while IFS= read -r action_line; do
  action_ref="${action_line#*@}"
  action_ref="${action_ref%% *}"
  if [[ ! "$action_ref" =~ ^[0-9a-f]{40}$ ]]; then
    fail "GitHub Actions must be pinned to an immutable 40-character commit SHA"
    printf '%s\n' "$action_line" >&2
  fi
done < <(grep -R -h -E '^[[:space:]]*-[[:space:]]+uses:' .github/workflows)

scan_forbidden() {
  local label="$1"
  local pattern="$2"
  local matches
  matches="$(
    git grep -nI -E "$pattern" -- \
      . \
      ':(exclude)android-app/app/src/main/assets/pi-runtime/pi-mobile.js' \
      ':(exclude)mobile-runtime-js/scripts/verify-bundle.mjs' \
      ':(exclude)scripts/check-public-repo.sh' \
      2>/dev/null || true
  )"
  if [[ -n "$matches" ]]; then
    fail "$label"
    printf '%s\n' "$matches" >&2
  fi
}

scan_forbidden \
  "private brand, identity, host fixture, or local development trace found" \
  'dev\.zyyai|codexmobile|codex-mobile|codex_mobile|CodexMobile|Codex Mobile|nuomiji|7224cc47|localhost-test-key|Maxgent|MoClaw|StoryLens'
scan_forbidden \
  "private-key material found" \
  'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY'
scan_forbidden \
  "credential-shaped token found" \
  'sk-or-v1-[A-Za-z0-9_-]{10,}|(^|[^A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}'
milestone_matches="$(
  git grep -nI -E 'P2-[0-9]|P3A|E5B|E6-[0-9]|E7-[0-9]|CAP-[0-9]' -- \
    . \
    ':(exclude)wire/**' \
    ':(exclude)docs/design/fixtures/r0-contract-fixtures.json' \
    ':(exclude)scripts/lib/p2-fixture-projection.mjs' \
    ':(exclude)android-app/app/src/main/assets/pi-runtime/pi-mobile.js' \
    ':(exclude)scripts/check-public-repo.sh' \
    2>/dev/null || true
)"
if [[ -n "$milestone_matches" ]]; then
  fail "internal milestone identifier found outside the public wire protocol"
  printf '%s\n' "$milestone_matches" >&2
fi
source_naming_matches="$(
  git grep -nI -E \
    'P2Dao|p2Dao|e5b-runtime|prepareE5b|task-e[567]-|session-e[67]-|goal-e6-|entry-e7-' -- \
      android-app/app/build.gradle.kts \
      android-app/app/src/main \
      android-app/app/src/test \
      mobile-runtime-js/test \
      scripts/build-phone-local-linux-runtime.sh \
      2>/dev/null || true
)"
if [[ -n "$source_naming_matches" ]]; then
  fail "internal phase name found in a public source or build identifier"
  printf '%s\n' "$source_naming_matches" >&2
fi

if ! grep -q 'namespace = "app.momoding"' android-app/app/build.gradle.kts; then
  fail "Android namespace must be app.momoding"
fi
if ! grep -q 'applicationId = "app.momoding"' android-app/app/build.gradle.kts; then
  fail "Android applicationId must be app.momoding"
fi
for metadata in \
  android-app/gradle/verification-metadata.xml \
  wire/kotlin-contract/gradle/verification-metadata.xml; do
  if ! grep -q '<verify-metadata>true</verify-metadata>' "$metadata"; then
    fail "Gradle dependency metadata verification must remain enabled: $metadata"
  fi
done

actual_permissions="$(
  node -e '
    const fs = require("node:fs");
    const manifest = fs.readFileSync("android-app/app/src/main/AndroidManifest.xml", "utf8");
    const permissions = [...manifest.matchAll(/<uses-permission\b[\s\S]*?\/>/g)]
      .filter(match => !/tools:node="remove"/.test(match[0]))
      .map(match => /android:name="([^"]+)"/.exec(match[0])?.[1])
      .filter(Boolean)
      .sort();
    process.stdout.write(permissions.join("\n"));
  '
)"
expected_permissions="$(
  printf '%s\n' \
    android.permission.ACCESS_NETWORK_STATE \
    android.permission.FOREGROUND_SERVICE \
    android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION \
    android.permission.INTERNET \
    android.permission.MANAGE_EXTERNAL_STORAGE \
    android.permission.READ_EXTERNAL_STORAGE \
    android.permission.READ_MEDIA_IMAGES \
    android.permission.READ_MEDIA_VISUAL_USER_SELECTED \
    moe.shizuku.manager.permission.API_V23 |
    sort -u
)"
if [[ "$actual_permissions" != "$expected_permissions" ]]; then
  fail "Android permission set differs from the reviewed public allowlist"
  printf 'Expected:\n%s\nActual:\n%s\n' "$expected_permissions" "$actual_permissions" >&2
fi

if ((failures > 0)); then
  printf '\nPublic repository check failed with %d issue(s).\n' "$failures" >&2
  exit 1
fi

node ./scripts/check-markdown-links.mjs

printf 'Public repository boundary check passed.\n'
