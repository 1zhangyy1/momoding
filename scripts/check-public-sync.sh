#!/usr/bin/env bash
set -euo pipefail

repository_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s PUBLIC_WORKTREE\n' "$0" >&2
  exit 2
fi

public_dir="$(cd "$1" && pwd)"
if ! git -C "$public_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  printf 'Public path must be a Git worktree: %s\n' "$public_dir" >&2
  exit 2
fi
if [[ -n "$(git -C "$public_dir" status --porcelain=v1 --untracked-files=normal)" ]]; then
  printf 'Public drift check requires a clean public worktree.\n' >&2
  exit 1
fi

temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

"$repository_dir/scripts/export-public-repo.sh" "$temporary_root/export"

if ! diff -qr -x .git "$temporary_root/export" "$public_dir"; then
  printf 'Public worktree differs from the private allowlisted export.\n' >&2
  exit 1
fi

printf 'Public worktree matches the private allowlisted export.\n'
