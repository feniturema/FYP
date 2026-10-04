#!/usr/bin/env bash
# Install k6 v2.3.0 into $TOOLS_DIR (default: <repo>/.tools) with a pinned sha256 check.
# Prints the absolute path of the k6 binary on stdout; the caller does `export K6=...`.
# Exit codes: 3 unsupported platform, 4 download failed, 5 checksum mismatch, 6 wrong version.
# There is deliberately no switch to skip verification (docs/phases/P0.md §6.2).
set -Eeuo pipefail

K6_VERSION=v2.3.0
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS_DIR=${TOOLS_DIR:-$REPO/.tools}

if [[ $(uname -s) != Linux ]]; then
  echo "install_k6: only Linux is supported; use the Docker image grafana/k6:2.3.0 instead" >&2
  exit 3
fi
case $(uname -m) in
  x86_64) arch=amd64; expected=39c3117b6af817592dcd0ce4242105c0a7af10948c2a425306f0be8f7a8a8ab1 ;;
  aarch64 | arm64) arch=arm64; expected=5ca3433e8201da72a284aaa241a1bb5fb47f4abb4e384d39410ddd8062f49b90 ;;
  *)
    echo "install_k6: unsupported arch $(uname -m); use the Docker image grafana/k6:2.3.0 instead" >&2
    exit 3 ;;
esac

target=$TOOLS_DIR/k6-$K6_VERSION/k6
name=k6-$K6_VERSION-linux-$arch

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
url=https://github.com/grafana/k6/releases/download/$K6_VERSION/$name.tar.gz
if ! curl -fsSL --retry 3 -o "$tmp/k6.tgz" "$url"; then
  echo "install_k6: download failed: $url" >&2
  exit 4
fi
if ! echo "$expected  $tmp/k6.tgz" | sha256sum -c - >&2; then
  echo "install_k6: checksum mismatch for $name.tar.gz (expected $expected)" >&2
  exit 5
fi
tar -xzf "$tmp/k6.tgz" -C "$tmp"
install -D -m 0755 "$tmp/$name/k6" "$target"
if ! "$target" version | grep -q "k6 $K6_VERSION"; then
  echo "install_k6: $target does not report k6 $K6_VERSION" >&2
  exit 6
fi
echo "$target"
