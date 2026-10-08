#!/bin/sh
# TermDeb Box64 transparent layer - helper build.
#
# Compiles the three ARM64 helpers that implement the on-disk Box64 wrapping
# layer FROM SOURCE with the Android NDK:
#
#   termdeb-x86_64-launcher  - re-execs box64 with the real x86_64 binary
#   termdeb-scan64           - scans trees and wraps x86_64 ELF executables
#   termdeb-wrapd            - continuous inotify watcher that keeps the wrap table fresh
#
# Building from source (rather than shipping a fixed prebuilt) is required
# because these helpers embed paths that depend on the app identity and the
# guest rootfs layout (com.qali.termdeb, files/debian-root, the termdeb-* tool
# names and the .termdeb-x86_64 backup dir). A stale prebuilt would point at
# the old package/rootfs names and silently break x86_64 execution.
#
# Usage:
#   sh ./termdeb-assets/prebuilt/box64/build-box64-layer.sh [output-dir]
#
# The binaries are written as <output-dir>/termdeb-{x86_64-launcher,scan64,wrapd}
# (default output dir: this script's directory). Exits non-zero if the NDK is
# unavailable or any binary fails to build - the caller may then fall back to
# the committed prebuilt helpers.
set -e

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${1:-$SRC_DIR}"
mkdir -p "$OUT_DIR"

# ---- Locate an NDK toolchain --------------------------------------------
# Mirrors termdeb-assets/prebuilt/adb/build-adb-client.sh so both guest-side
# helpers build the same way across local machines and CI runners.
NDK_ROOT=""
if [ -n "$ANDROID_NDK_HOME" ] && [ -x "$ANDROID_NDK_HOME/ndk-build" ]; then
    NDK_ROOT="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK" ] && [ -x "$ANDROID_NDK/ndk-build" ]; then
    NDK_ROOT="$ANDROID_NDK"
elif [ -x "$ANDROID_HOME/ndk-bundle/ndk-build" ]; then
    NDK_ROOT="$ANDROID_HOME/ndk-bundle"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    LATEST_NDK="$(ls -1d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -n "$LATEST_NDK" ] && [ -x "$LATEST_NDK/ndk-build" ]; then
        NDK_ROOT="$LATEST_NDK"
    fi
fi

if [ -z "$NDK_ROOT" ]; then
    echo "[box64-layer] No Android NDK found (ANDROID_NDK_HOME / ANDROID_NDK / ANDROID_HOME/ndk)." >&2
    exit 1
fi

TOOLCHAIN="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n 1 || true)"
CLANG=""
for api in 24 21 23 26 28 30; do
    if [ -n "$TOOLCHAIN" ] && [ -x "$TOOLCHAIN/aarch64-linux-android${api}-clang" ]; then
        CLANG="$TOOLCHAIN/aarch64-linux-android${api}-clang"
        break
    fi
done
if [ -z "$CLANG" ]; then
    echo "[box64-layer] NDK clang wrapper not found under $NDK_ROOT." >&2
    exit 1
fi
echo "[box64-layer] Building with NDK: $CLANG"

# Static, optimized, stripped: the helpers must run in both the Termux prefix
# and the Debian guest without pulling in a dynamic libc.
CFLAGS="-O2 -static -s -Wall"

for name in termdeb-x86_64-launcher termdeb-scan64 termdeb-wrapd; do
    src="$SRC_DIR/$name.c"
    out="$OUT_DIR/$name"
    if [ ! -f "$src" ]; then
        echo "[box64-layer] Source not found: $src" >&2
        exit 1
    fi
    echo "[box64-layer] Compiling $name ..."
    # shellcheck disable=SC2086  # intentional word-splitting of CFLAGS
    "$CLANG" $CFLAGS -o "$out" "$src"
    if [ ! -s "$out" ]; then
        echo "[box64-layer] Build produced no output for $name" >&2
        exit 1
    fi
    if command -v file >/dev/null 2>&1; then
        if ! file "$out" | grep -qi 'aarch64'; then
            echo "[box64-layer] $name is not an ARM64 binary:" >&2
            file "$out" >&2
            exit 1
        fi
    fi
    echo "[box64-layer] Built $out"
done

echo "[box64-layer] All helpers built from source."
