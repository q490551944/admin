#!/usr/bin/env bash
set -euo pipefail

# Test artifacts only. Fixed official release assets are checked before execution or image assembly.
task_repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_artifacts="$task_repo_root/target/minio-test-image"
task_mode="${1:-image}"
case "$task_mode" in
  image|binary) ;;
  *) printf 'Usage: %s [image|binary]\n' "$0" >&2; exit 2 ;;
esac
mkdir -p "$task_artifacts"

download_checked() {
  local task_url="$1" task_sha="$2" task_destination="$3"
  if [[ -f "$task_destination" ]] && printf '%s  %s\n' "$task_sha" "$task_destination" | sha256sum --check --status; then
    chmod +x "$task_destination"
    return
  fi
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 \
    --output "$task_destination.part" "$task_url"
  printf '%s  %s\n' "$task_sha" "$task_destination.part" | sha256sum --check --status
  mv "$task_destination.part" "$task_destination"
  chmod +x "$task_destination"
}

download_checked \
  'https://github.com/minio/minio/releases/download/RELEASE.2025-09-07T16-13-09Z/minio.linux-amd64.RELEASE.2025-09-07T16-13-09Z' \
  '7c5bd8512c6e966455b1d198209358b2d191c77a83ab377c4073281065fb855f' \
  "$task_artifacts/minio"

if [[ "$task_mode" == binary ]]; then
  if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    printf 'The standalone test binary requires Linux amd64. Use image mode for Linux containers.\n' >&2
    exit 2
  fi
  "$task_artifacts/minio" --version
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'MINIO_BIN=%s\n' "$task_artifacts/minio" >> "$GITHUB_ENV"
  fi
  exit
fi

download_checked \
  'https://github.com/minio/mc/releases/download/RELEASE.2025-08-13T08-35-41Z/mc.linux-amd64.RELEASE.2025-08-13T08-35-41Z' \
  '01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891' \
  "$task_artifacts/mc"

task_image='admin-monitor-minio:RELEASE.2025-09-07T16-13-09Z'
docker build --platform linux/amd64 --file "$task_repo_root/scripts/minio-test.Dockerfile" \
  --tag "$task_image" "$task_artifacts"
docker run --rm --platform linux/amd64 "$task_image" --version
