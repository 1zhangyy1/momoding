#!/usr/bin/env bash
set -euo pipefail

repository_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repository_dir/scripts/public-export-paths.txt"

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
fi

output_dir="$1"
if [[ -e "$output_dir" ]]; then
  if [[ ! -d "$output_dir" || -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    printf 'Output directory must not exist or must be empty: %s\n' "$output_dir" >&2
    exit 2
  fi
else
  mkdir -p "$output_dir"
fi
output_dir="$(cd "$output_dir" && pwd)"

cd "$repository_dir"
git rev-parse --is-inside-work-tree >/dev/null
revision="$(git rev-parse HEAD)"
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'A full source commit is required.\n' >&2
  exit 1
fi
if [[ -n "$(git status --porcelain=v1 --untracked-files=normal)" ]]; then
  printf 'Public export requires a clean private source worktree.\n' >&2
  exit 1
fi
if [[ ! -f "$manifest" ]]; then
  printf 'Missing public export manifest: %s\n' "$manifest" >&2
  exit 1
fi

runtime_manifest="android-app/app/src/main/assets/pi-runtime/manifest.json"
runtime_revision="$(
  git show "$revision:$runtime_manifest" |
    node -e '
      let input = "";
      process.stdin.setEncoding("utf8");
      process.stdin.on("data", chunk => { input += chunk; });
      process.stdin.on("end", () => {
        const value = JSON.parse(input).buildRevision;
        if (typeof value !== "string") process.exit(1);
        process.stdout.write(value);
      });
    '
)"
if [[ ! "$runtime_revision" =~ ^[0-9a-f]{40}$ ]]; then
  printf 'The generated runtime must declare a full source revision.\n' >&2
  exit 1
fi
if ! git merge-base --is-ancestor "$runtime_revision" "$revision"; then
  printf 'Runtime source revision is not an ancestor of the export revision.\n' >&2
  exit 1
fi

matches_prefix() {
  local path="$1"
  local prefix="$2"
  [[ "$path" == "$prefix" || "$path" == "$prefix/"* ]]
}

should_export() {
  local path="$1"
  local decision=false
  local raw rule operation
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    [[ -z "$raw" || "$raw" == \#* ]] && continue
    operation="${raw:0:1}"
    rule="${raw:1}"
    if [[ "$operation" != "+" && "$operation" != "-" ]]; then
      printf 'Invalid public export rule: %s\n' "$raw" >&2
      exit 1
    fi
    if matches_prefix "$path" "$rule"; then
      [[ "$operation" == "+" ]] && decision=true || decision=false
    fi
  done < "$manifest"
  [[ "$decision" == true ]]
}

exported=0
while IFS=$'\t' read -r metadata path; do
  should_export "$path" || continue
  mode="${metadata%% *}"
  case "$mode" in
    100644|100755) ;;
    *)
      printf 'Unsupported public tree mode %s for %s\n' "$mode" "$path" >&2
      exit 1
      ;;
  esac
  destination="$output_dir/$path"
  mkdir -p "$(dirname "$destination")"
  git show "$revision:$path" > "$destination"
  [[ "$mode" == "100755" ]] && chmod 755 "$destination" || chmod 644 "$destination"
  exported=$((exported + 1))
done < <(git ls-tree -r "$revision")

if ((exported == 0)); then
  printf 'Public export produced no files.\n' >&2
  exit 1
fi

cat > "$output_dir/.public-source.json" <<EOF
{
  "sourceRepository": "private-momoding",
  "sourceRevision": "$runtime_revision",
  "exportRevision": "$revision",
  "exportManifest": "scripts/public-export-paths.txt"
}
EOF

printf 'Exported %d files from %s (runtime source %s) to %s\n' \
  "$exported" "$revision" "$runtime_revision" "$output_dir"
