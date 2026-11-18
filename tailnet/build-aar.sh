#!/usr/bin/env bash
# Build the embedded Tailscale AAR with gomobile bind.
#
# Run from the repo root: bash tailnet/build-aar.sh
# (GitHub Actions runs it this way; it needs no executable bit.)
# Paths are resolved from this script's own location, so it also
# works when invoked from anywhere else.
#
# Needs: Go 1.27.3, JDK 17 (javac, not a JRE: gomobile compiles the
# generated Java bindings after the Go build), and the Android NDK
# r27d (ANDROID_NDK_HOME, or ANDROID_HOME/ndk/27.3.13750724).
# Pinned toolchain: tailscale.com v1.104.4, x/mobile 20260709.
#
# Output: app/libs/agentpilot-tailnet.aar (arm64-v8a only, the
# owner's phone ABI). The AAR is never committed; CI builds it on
# every run before Gradle, and the app build fails without it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
AAR_OUT="$REPO_ROOT/app/libs/agentpilot-tailnet.aar"

command -v go >/dev/null || { echo "go not found; install Go 1.27.3 first" >&2; exit 1; }
command -v javac >/dev/null || { echo "javac not found; gomobile bind needs a JDK, not a JRE" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip not found" >&2; exit 1; }

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/27.3.13750724" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
  else
    echo "ANDROID_NDK_HOME not set and no NDK r27d under ANDROID_HOME" >&2
    exit 1
  fi
fi
export ANDROID_NDK_HOME

# Go doc comments are copied verbatim into the generated Java and
# compiled with the platform charset: the bridge source is ASCII
# only, and javac is told UTF-8 anyway.
export JDK_JAVAC_OPTIONS="-encoding UTF-8"
export CGO_ENABLED=1

cd "$SCRIPT_DIR"

# gobind and gomobile from the SAME pinned x/mobile revision (the
# go.mod tool directives). A mismatch builds bindings for an ABI the
# runtime does not implement, and fails only at the first call.
go install golang.org/x/mobile/cmd/gobind
go install golang.org/x/mobile/cmd/gomobile

# This x/mobile's `gomobile init` takes no -ndk flag (its usage is
# `gomobile init [-openal dir]`); it reads the NDK from the
# environment instead. ANDROID_NDK_HOME is already exported above,
# and the workflow sets it too, so plain init finds the NDK.
gomobile init

mkdir -p "$REPO_ROOT/app/libs"
# The bind runs `go mod tidy` first, which fetches from the Go
# module proxy and the checksum database: a network blip there
# fails the whole build (seen once, run #221), while the same step
# passes on a re-run. Retry the bind up to three times so a blip
# does not waste a whole build round.
for attempt in 1 2 3; do
  if gomobile bind \
    -target=android/arm64 \
    -androidapi 26 \
    -javapkg=com.browsermcp \
    -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
    -o "$AAR_OUT" \
    ./tailnet; then
    break
  fi
  echo "gomobile bind failed (attempt $attempt), retrying..." >&2
  [ "$attempt" = 3 ] && exit 1
  sleep 10
done

# Verify the AAR: the facade class must exist by fully qualified
# name, and the native lib must be arm64-v8a only.
VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "$VERIFY_DIR"' EXIT
unzip -o -q "$AAR_OUT" classes.jar -d "$VERIFY_DIR"
unzip -l "$VERIFY_DIR/classes.jar" | grep -q "com/browsermcp/tailnet/Tailnet.class" \
  || { echo "facade class com.browsermcp.tailnet.Tailnet missing from AAR" >&2; exit 1; }
if unzip -l "$AAR_OUT" | grep "jni/" | grep -vq "arm64-v8a"; then
  echo "AAR ships a non-arm64 ABI; this spike ships arm64-v8a only" >&2
  exit 1
fi
unzip -l "$AAR_OUT" | grep "jni/arm64-v8a/libgojni.so"
ls -l "$AAR_OUT"
