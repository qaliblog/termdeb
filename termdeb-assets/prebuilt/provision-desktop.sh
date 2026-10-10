#!/bin/bash
# provision-desktop.sh - Install Lomiri + Mir into the Debian ARM64 rootfs at build time
#
# Installs the Lomiri desktop (shell + desktop session), the Mir compositor and
# its demo tools, and builds the TermDeb Mir display bridge for ARM64, all inside
# the Debian trixie rootfs that ships in the APK. Uses Docker with QEMU user-mode
# emulation (binfmt_misc) to run the ARM64 toolchain and apt on the build host.
#
# Usage: ./provision-desktop.sh <rootfs-dir> [bridge-source-dir]
#   <rootfs-dir>        Extracted Debian trixie ARM64 rootfs (already base-provisioned)
#   <bridge-source-dir> Directory holding termdeb-mir-bridge.c (default: script dir/mir-bridge)
#
# Besides the Lomiri/Mir payload this installs:
#   * the Mir display/rendering platform modules (mir-platform-graphics-virtual,
#     mir-platform-rendering-egl-generic) - mir-demos/mir_demo_server does not
#     depend on them, and without them Mir exits before creating a socket;
#   * a stub Mir input platform (prebuilt/mir-input-stub), because Debian's only
#     input platform (evdev) needs udev and /dev/input, which the Android guest
#     has neither of - input arrives over Wayland instead;
#   * it then starts Mir with the same options the guest session uses and fails
#     the build when no Wayland socket appears, so a rootfs that cannot bring the
#     desktop up is never packaged.
#
# Requirements:
#   - Docker with ARM64 QEMU emulation (see provision-rootfs.sh)
#   - Internet access inside the container
#
# Copyright (c) TermDeb Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

ROOTFS_DIR="${1:?Usage: $0 <rootfs-dir> [bridge-source-dir]}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRIDGE_SRC_DIR="${2:-${SCRIPT_DIR}/mir-bridge}"

if [ ! -f "${ROOTFS_DIR}/etc/os-release" ]; then
  echo "Error: ${ROOTFS_DIR} does not look like a Debian rootfs" >&2
  exit 1
fi
if [ ! -f "${BRIDGE_SRC_DIR}/termdeb-mir-bridge.c" ]; then
  echo "Error: bridge source not found at ${BRIDGE_SRC_DIR}/termdeb-mir-bridge.c" >&2
  exit 1
fi

echo "============================================"
echo "  Provisioning Lomiri desktop (ARM64)"
echo "============================================"
echo "  Rootfs:      ${ROOTFS_DIR}"
echo "  Bridge src:  ${BRIDGE_SRC_DIR}"

# ---- Docker + QEMU ----------------------------------------------------------
if ! command -v docker &>/dev/null; then
  echo "Error: docker not found (required for ARM64 provisioning)." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Error: docker is installed but not accessible." >&2
  exit 1
fi
if [ ! -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ] 2>/dev/null; then
  echo "  Registering QEMU ARM64 emulation..."
  docker run --privileged --rm --pull=always tonistiigi/binfmt --install arm64
fi

# ---- Stage the bridge source + guest provisioning script --------------------
# The guest script runs inside `chroot` as a real file (not a double-quoted -c
# argument) so that its own loops and variables are never expanded by the outer
# shell.
STAGE_DIR="$(mktemp -d /tmp/termdeb-desktop-provision-XXXXXX)"
cp "${BRIDGE_SRC_DIR}/termdeb-mir-bridge.c" "${STAGE_DIR}/"

# Mir input platform stub (built inside the guest) and the linker version-script
# template it needs; see prebuilt/mir-input-stub/.
INPUT_STUB_SRC_DIR="${SCRIPT_DIR}/mir-input-stub"
if [ ! -f "${INPUT_STUB_SRC_DIR}/termdeb-mir-input-stub.cpp" ] || \
   [ ! -f "${INPUT_STUB_SRC_DIR}/version-script.map.in" ]; then
  echo "Error: Mir input platform stub sources not found in ${INPUT_STUB_SRC_DIR}" >&2
  exit 1
fi
cp "${INPUT_STUB_SRC_DIR}/termdeb-mir-input-stub.cpp" "${STAGE_DIR}/"
cp "${INPUT_STUB_SRC_DIR}/version-script.map.in" "${STAGE_DIR}/"

cat > "${STAGE_DIR}/guest-provision.sh" << 'GUEST_EOF'
#!/bin/bash
# Runs INSIDE the Debian ARM64 rootfs (chroot) under QEMU emulation.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Protocol sources are deliberately on raw.githubusercontent.com. The
# gitlab.freedesktop.org /-/raw/ endpoint is NOT usable from CI: for anonymous
# automated fetches it answers with an HTML sign-in page and HTTP 200, so
# `curl -f` succeeds and leaves a non-XML file behind.
SCREENCCOPY_XML="https://raw.githubusercontent.com/swaywm/wlr-protocols/master/unstable/wlr-screencopy-unstable-v1.xml"
VIRTUAL_POINTER_XML="https://raw.githubusercontent.com/swaywm/wlr-protocols/master/unstable/wlr-virtual-pointer-unstable-v1.xml"
# virtual-keyboard-unstable-v1 is NOT shipped by wayland-protocols (verified
# across tags 1.31..1.49 and main); it is maintained by wlroots.
VIRTUAL_KEYBOARD_XML="https://raw.githubusercontent.com/swaywm/wlroots/master/protocol/virtual-keyboard-unstable-v1.xml"

# Do not start services during package installation under this chroot.
if [ ! -e /usr/sbin/policy-rc.d ]; then
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 755 /usr/sbin/policy-rc.d
fi

# Android/CI networks commonly return IPv6 records while only IPv4 works; prefer
# IPv4 and retry to avoid long timeouts.
if ! grep -Rqs '^Acquire::ForceIPv4' /etc/apt/apt.conf.d 2>/dev/null; then
  printf 'Acquire::ForceIPv4 "true";\nAcquire::Retries "2";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\n' \
    > /etc/apt/apt.conf.d/99termdeb-network
fi

mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /usr/share/man/man1

echo '  [apt] Updating package lists...'
if ! apt-get update -qq; then
  echo '  [apt] ERROR: apt-get update failed (DNS/network); cannot provision Lomiri.' >&2
  cat /etc/resolv.conf >&2 || true
  exit 1
fi

echo '  [apt] Installing the Lomiri desktop (ARM64)...'
# Required: the Lomiri shell and its session integration, which pull in the Mir
# server libraries the compositor is built on.
if ! apt-get install -y --no-install-recommends lomiri lomiri-desktop-session; then
  echo '  [apt] WARNING: the Lomiri shell package set could not be installed.'
fi

# Required: the Mir platform modules. Debian trixie ships each Mir platform in its
# own versioned package (mir-platform-*23) behind a metapackage, and neither
# lomiri nor mir-demos/mir_demo_server depends on any of them. Without them Mir
# finds no display platform and dies before it ever creates the Wayland socket:
#
#   mir:virtual      -> mir-platform-graphics-virtual
#   mir:egl-generic  -> mir-platform-rendering-egl-generic
#   mir:wayland      -> mir-platform-graphics-wayland (nested server on the
#                       guest's own Wayland socket; used by the shell when it
#                       runs nested)
for required_pkg in mir-platform-graphics-virtual mir-platform-rendering-egl-generic mir-platform-graphics-wayland; do
  if ! apt-get install -y --no-install-recommends "${required_pkg}"; then
    echo "  [apt] ERROR: required Mir platform package '${required_pkg}' could not be installed" >&2
    exit 1
  fi
done

# Optional: Mir tooling/binaries. Install each name independently because
# apt-get installs nothing at all when any requested name is unknown.
# The old libmirserver2/libmirplatform2 names do not exist in trixie (they are
# libmirserver63/libmirplatform30 and are pulled in as dependencies anyway).
for extra_pkg in mir-test-tools mir-demos; do
  if ! apt-get install -y --no-install-recommends "${extra_pkg}"; then
    echo "  [apt] note: optional package '${extra_pkg}' unavailable; continuing"
  fi
done

echo '  [apt] Installing build tooling for the display bridge...'
apt-get install -y --no-install-recommends \
  build-essential pkg-config \
  libwayland-dev libwayland-client0 wayland-protocols \
  libxkbcommon-dev libxkbcommon0 \
  curl ca-certificates

# Mir development headers for the TermDeb input platform stub. Mir resolves the
# platform entry points with dlvsym(), so the stub must be compiled against the
# same headers/ABI as the installed server; the headers are build-time only.
echo '  [apt] Installing Mir development headers for the input platform stub...'
if ! apt-get install -y --no-install-recommends \
  g++ libmirserver-dev libmirplatform-dev libmircommon-dev; then
  echo '  [apt] ERROR: Mir development headers are required to build the input platform stub' >&2
  exit 1
fi

for extra_pkg in qtwayland5 fonts-dejavu-core fontconfig dbus-x11; do
  apt-get install -y --no-install-recommends "${extra_pkg}" || true
done

# wayland-scanner is NOT shipped as a binary by libwayland-dev or any separate Debian
# package in trixie (or any currently-releasing suite). Build it from source inside the
# chroot: the scanner is a small C program (wayland.git/src/scanner.c) that only needs
# build-essential + libxml2-dev; wayland.dtd comes from libwayland-dev which is already
# installed above.
echo '  [build] Building wayland-scanner from source...'
# wayland-scanner (wayland.git/src/scanner.c) needs: build-essential + libxml2-dev
# (for libexpat). xml2-config gives cflags/libs for libxml2; we also link -lexpat
# directly because scanner.c #include <expat.h> and uses the expat API.
WS_SRC_DIR="/tmp/wayland-scanner-src"
mkdir -p "${WS_SRC_DIR}"
curl -fsSL --retry 3 --retry-delay 2 \
  "https://gitlab.freedesktop.org/wayland/wayland/-/raw/master/src/scanner.c" \
  -o "${WS_SRC_DIR}/scanner.c"
if [ ! -s "${WS_SRC_DIR}/scanner.c" ]; then
  echo "  [build] ERROR: failed to download wayland-scanner source" >&2
  exit 1
fi
if ! apt-get install -y --no-install-recommends libxml2-dev >/dev/null 2>&1; then
  echo "  [build] ERROR: failed to install libxml2-dev" >&2
  exit 1
fi
# scanner.c uses the expat API directly (#include <expat.h>); install libexpat-dev so
# the compiler can find expat.h and link against libexpat.
if ! apt-get install -y --no-install-recommends libexpat-dev >/dev/null 2>&1; then
  echo "  [build] ERROR: failed to install libexpat-dev" >&2
  exit 1
fi
SCAN_CD="$(cd "${WS_SRC_DIR}" && pwd)"
cc -O2 -pipe -o /usr/local/bin/wayland-scanner \
  "${SCAN_CD}/scanner.c" \
  $(xml2-config --cflags --libs) \
  -D_GNU_SOURCE -lexpat -lwayland-client -lwayland-server -lwayland-cursor -lwayland-egl
if [ ! -x /usr/local/bin/wayland-scanner ]; then
  echo "  [build] ERROR: wayland-scanner build failed" >&2
  exit 1
fi
strip /usr/local/bin/wayland-scanner 2>/dev/null || true
rm -rf "${WS_SRC_DIR}"
echo "  [build] wayland-scanner built: $(/usr/local/bin/wayland-scanner --version 2>&1 || true)"

# ---- Build the TermDeb Mir display bridge ----
echo '  [build] Fetching Wayland protocol definitions...'
mkdir -p /tmp/bridge
cd /tmp/bridge

# Prefer a checked-in copy of each protocol XML so the build stays reproducible
# even when upstream raw.githubusercontent.com / gitlab endpoints are flaky from
# the CI network. Use the downloaded URL as a fallback only when the local copy
# is absent or empty.
fetch_xml() {
  local url="$1" local_file="$2" label="$3"
  if [ -s "${local_file}" ] && grep -q '<interface' "${local_file}" 2>/dev/null; then
    cp "${local_file}" "${url##*/}"
    echo "  [build] Using local ${label} protocol: ${local_file}"
  else
    curl -fsSL --retry 3 --retry-delay 2 "${url}" -o "${url##*/}" || true
    if [ ! -s "${url##*/}" ] || ! grep -q '<interface' "${url##*/}" 2>/dev/null; then
      echo "  [build] ERROR: ${label} protocol XML is missing or invalid" >&2
      exit 1
    fi
    echo "  [build] Downloaded ${label} protocol from ${url}"
  fi
}

# Local fallback copies of the three protocol XML files so this build stays
# reproducible even when the upstream raw.githubusercontent.com endpoints are
# unavailable from the CI network. These are the exact XML documents this build
# has already used successfully; keep them in sync with the URL variables above
# if the upstream protocol versions change.
SCREENCCOPY_XML_LOCAL="termdeb-assets/prebuilt/wlr-screencopy-unstable-v1.xml"
VIRTUAL_POINTER_XML_LOCAL="termdeb-assets/prebuilt/wlr-virtual-pointer-unstable-v1.xml"
VIRTUAL_KEYBOARD_XML_LOCAL="termdeb-assets/prebuilt/virtual-keyboard-unstable-v1.xml"

fetch_xml "${SCREENCCOPY_XML}" "${SCREENCCOPY_XML_LOCAL}" "wlr-screencopy"
fetch_xml "${VIRTUAL_POINTER_XML}" "${VIRTUAL_POINTER_XML_LOCAL}" "wlr-virtual-pointer"
fetch_xml "${VIRTUAL_KEYBOARD_XML}" "${VIRTUAL_KEYBOARD_XML_LOCAL}" "virtual-keyboard"

for xml in wlr-screencopy-unstable-v1.xml wlr-virtual-pointer-unstable-v1.xml virtual-keyboard-unstable-v1.xml; do
  if [ ! -s "${xml}" ] || ! grep -q '<interface' "${xml}"; then
    echo "  [build] ERROR: protocol definition ${xml} is missing or invalid" >&2
    ls -l "${xml}" 2>&1 || true
    exit 1
  fi
done

echo '  [build] Generating protocol code...'
wayland-scanner client-header wlr-screencopy-unstable-v1.xml wlr-screencopy.h
wayland-scanner private-code  wlr-screencopy-unstable-v1.xml wlr-screencopy.c
wayland-scanner client-header wlr-virtual-pointer-unstable-v1.xml wlr-virtual-pointer.h
wayland-scanner private-code  wlr-virtual-pointer-unstable-v1.xml wlr-virtual-pointer.c
wayland-scanner client-header virtual-keyboard-unstable-v1.xml virtual-keyboard.h
wayland-scanner private-code  virtual-keyboard-unstable-v1.xml virtual-keyboard.c

echo '  [build] Generated protocol files:' $(ls -1 *.h *.c 2>/dev/null | tr '
' ' ')

if [ ! -s wlr-screencopy.h ] || [ ! -s wlr-screencopy.c ] ||    [ ! -s wlr-virtual-pointer.h ] || [ ! -s wlr-virtual-pointer.c ] ||    [ ! -s virtual-keyboard.h ] || [ ! -s virtual-keyboard.c ]; then
  echo "  [build] ERROR: wayland-scanner did not generate all protocol files" >&2
  ls -l *.xml *.h *.c 2>&1
  exit 1
fi

# Build the bridge from a self-contained directory: the generated headers and
# sources live beside termdeb-mir-bridge.c so the compiler does not need to rely
# on the current working directory being correct.
BRIDGE_DIR=$(mktemp -d /tmp/termdeb-mir-bridge-build-XXXXXX)
cp -f wlr-screencopy.h wlr-screencopy.c    wlr-virtual-pointer.h wlr-virtual-pointer.c    virtual-keyboard.h virtual-keyboard.c    "${BRIDGE_DIR}/"
cp -f /tmp/termdeb-mir-bridge.c "${BRIDGE_DIR}/termdeb-mir-bridge.c"

echo "  [build] bridge build dir: ${BRIDGE_DIR}"
echo "  [build] protocol files next to bridge source:"
ls -1 "${BRIDGE_DIR}/"wlr-screencopy.h "${BRIDGE_DIR}/"wlr-screencopy.c       "${BRIDGE_DIR}/"wlr-virtual-pointer.h "${BRIDGE_DIR}/"wlr-virtual-pointer.c       "${BRIDGE_DIR}/"virtual-keyboard.h "${BRIDGE_DIR}/"virtual-keyboard.c 2>&1

echo '  [build] Compiling termdeb-mir-bridge...'
cc -O2 -pipe -o "${BRIDGE_DIR}/termdeb-mir-bridge"    "${BRIDGE_DIR}/termdeb-mir-bridge.c"    "${BRIDGE_DIR}/"wlr-screencopy.c    "${BRIDGE_DIR}/"wlr-virtual-pointer.c    "${BRIDGE_DIR}/"virtual-keyboard.c    -I"${BRIDGE_DIR}"    $(pkg-config --cflags --libs wayland-client xkbcommon)
strip "${BRIDGE_DIR}/termdeb-mir-bridge" 2>/dev/null || true

install -D -m 0755 "${BRIDGE_DIR}/termdeb-mir-bridge" /usr/local/bin/termdeb-mir-bridge
echo '  [build] Installed /usr/local/bin/termdeb-mir-bridge'

rm -rf "${BRIDGE_DIR}"

# ---- Mir input platform stub -------------------------------------------------
# Mir aborts at startup when no input platform is usable:
#
#   ERROR: input_probe.cpp(183): No appropriate input platform module found
#
# Debian's only input platform (mir-platform-input-evdev10) needs udev and
# /dev/input, neither of which exists in the Android guest, and the TermDeb bridge
# injects all input over Wayland (virtual keyboard/pointer). So build a stub
# platform and install it next to the stock platform modules.
echo '  [build] Building the TermDeb Mir input platform stub...'
MIR_PLATFORM_DIR=/usr/lib/aarch64-linux-gnu/mir/server-platform
mkdir -p "${MIR_PLATFORM_DIR}"

# Mir resolves platform entry points with dlvsym(handle, name, version), where
# version is Mir's input-platform ABI symbol (e.g. MIR_INPUT_PLATFORM_0.27). That
# name is not exposed by any installed header, so read it out of the server
# library that is already installed and stamp it with a linker version script.
MIR_SERVER_LIB="$(ls /usr/lib/aarch64-linux-gnu/libmirserver.so.* 2>/dev/null | head -1)"
MIR_INPUT_VERSION_SYMBOL="$(strings -a "${MIR_SERVER_LIB}" 2>/dev/null | grep -xE 'MIR_INPUT_PLATFORM_[0-9.]+' | head -1)"
if [ -z "${MIR_SERVER_LIB}" ] || [ -z "${MIR_INPUT_VERSION_SYMBOL}" ]; then
  echo "  [build] ERROR: could not determine Mir's input platform ABI symbol from '${MIR_SERVER_LIB}'" >&2
  exit 1
fi
echo "  [build] Mir input platform ABI symbol: ${MIR_INPUT_VERSION_SYMBOL}"

INPUT_STUB_DIR="$(mktemp -d /tmp/termdeb-mir-input-stub-XXXXXX)"
cp -f /tmp/termdeb-mir-input-stub.cpp "${INPUT_STUB_DIR}/termdeb-mir-input-stub.cpp"
sed "s/@PLATFORM_VERSION_SYMBOL@/${MIR_INPUT_VERSION_SYMBOL}/" /tmp/version-script.map.in \
  > "${INPUT_STUB_DIR}/version-script.map"
if ! grep -q "^${MIR_INPUT_VERSION_SYMBOL} {" "${INPUT_STUB_DIR}/version-script.map"; then
  echo '  [build] ERROR: the generated version script is malformed:' >&2
  cat "${INPUT_STUB_DIR}/version-script.map" >&2
  exit 1
fi

g++ -O2 -fPIC -shared -std=c++20 -Wall \
  -o "${INPUT_STUB_DIR}/input-termdeb-stub.so" \
  "${INPUT_STUB_DIR}/termdeb-mir-input-stub.cpp" \
  -I/usr/include/mirplatform -I/usr/include/mircommon -I/usr/include/mirserver -I/usr/include/mircore \
  -Wl,--version-script="${INPUT_STUB_DIR}/version-script.map"

# The server only detects a module that exports its entry points under the ABI
# version symbol; a module without them loads but is never listed.
if ! nm -D --defined-only "${INPUT_STUB_DIR}/input-termdeb-stub.so" \
     | grep -q "create_input_platform@@${MIR_INPUT_VERSION_SYMBOL}"; then
  echo '  [build] ERROR: the input platform stub is missing its versioned entry points:' >&2
  nm -D --defined-only "${INPUT_STUB_DIR}/input-termdeb-stub.so" >&2
  exit 1
fi

install -D -m 0755 "${INPUT_STUB_DIR}/input-termdeb-stub.so" "${MIR_PLATFORM_DIR}/input-termdeb-stub.so"
rm -rf "${INPUT_STUB_DIR}"
echo '  [build] Installed the Mir input platform stub (termdeb:input-stub)'

# Marker so the runtime can detect a desktop-provisioned rootfs.
touch /etc/termdeb-desktop-provisioned

# ---- Mir smoke test ----------------------------------------------------------
# Start Mir exactly the way termdeb-assets/config/termdeb-desktop-session does and
# require the Wayland socket to appear. Keep these options in sync with the
# session script: the desktop previously shipped a rootfs whose Mir died during
# startup ("Unknown command line options: --host-socket", missing platform
# modules, no input platform) and the guest session then aborted with
# "Mir did not create the Wayland socket".
echo ''
echo '  [smoke] Starting Mir with the desktop session options...'
SMOKE_DIR="$(mktemp -d /tmp/termdeb-mir-smoke-XXXXXX)"
export XDG_RUNTIME_DIR="${SMOKE_DIR}/runtime"
mkdir -p "${XDG_RUNTIME_DIR}"
SMOKE_WIDTH=1280
SMOKE_HEIGHT=800
SMOKE_STATUS=0

mir_demo_server \
  --platform-display-libs mir:virtual \
  --platform-rendering-libs mir:egl-generic \
  --platform-input-lib termdeb:input-stub \
  --virtual-output "${SMOKE_WIDTH}x${SMOKE_HEIGHT}" \
  --console-provider none \
  --wayland-extensions "wl_shell:xdg_wm_base:zwlr_layer_shell_v1:zxdg_output_manager_v1:zwp_virtual_keyboard_manager_v1:zwlr_virtual_pointer_manager_v1:zwlr_screencopy_manager_v1" \
  >"${SMOKE_DIR}/mir.log" 2>&1 &
SMOKE_MIR=$!

SMOKE_SOCKET=""
for _ in $(seq 1 60); do
  for _sock in "${XDG_RUNTIME_DIR}"/wayland-*; do
    if [ -S "${_sock}" ]; then SMOKE_SOCKET="${_sock}"; break; fi
  done
  [ -n "${SMOKE_SOCKET}" ] && break
  if ! kill -0 "${SMOKE_MIR}" 2>/dev/null; then break; fi
  sleep 0.5
done

if [ -n "${SMOKE_SOCKET}" ] && [ -S "${SMOKE_SOCKET}" ]; then
  echo "  [smoke] OK  Wayland socket created: ${SMOKE_SOCKET}"
else
  echo '  [smoke] FAIL Mir did not create a Wayland socket' >&2
  SMOKE_STATUS=1
fi

if grep -q 'Selected input driver: termdeb:input-stub' "${SMOKE_DIR}/mir.log"; then
  echo '  [smoke] OK  Mir selected the TermDeb input platform stub'
else
  echo '  [smoke] FAIL Mir did not use termdeb:input-stub' >&2
  SMOKE_STATUS=1
fi

if grep -q 'Initial display configuration' "${SMOKE_DIR}/mir.log"; then
  echo '  [smoke] OK  Mir configured the virtual display'
else
  echo '  [smoke] FAIL Mir did not configure the virtual display' >&2
  SMOKE_STATUS=1
fi

# Bridge: prove the display path end to end. The bridge must connect to Mir and
# Mir must hand it a screencopy buffer; frame content needs a drawing client
# (Lomiri), so "no frames yet" is reported, not failed.
SMOKE_FB="${SMOKE_DIR}/fb.buf"
: > "${SMOKE_FB}"
if [ -n "${SMOKE_SOCKET}" ]; then
  WAYLAND_DISPLAY="$(basename "${SMOKE_SOCKET}")" WAYLAND_DEBUG=1 \
    timeout 8 /usr/local/bin/termdeb-mir-bridge \
      --fb "${SMOKE_FB}" \
      --input-socket termdeb-desktop-input \
      --width "${SMOKE_WIDTH}" --height "${SMOKE_HEIGHT}" \
      >"${SMOKE_DIR}/bridge.log" 2>&1 || true
  if grep -q 'bridge running' "${SMOKE_DIR}/bridge.log"; then
    echo '  [smoke] OK  termdeb-mir-bridge connected to Mir'
  else
    echo '  [smoke] FAIL termdeb-mir-bridge could not run against Mir' >&2
    tail -n 10 "${SMOKE_DIR}/bridge.log" >&2
    SMOKE_STATUS=1
  fi
  if grep -q 'zwlr_screencopy_frame_v1.*buffer(' "${SMOKE_DIR}/bridge.log"; then
    echo '  [smoke] OK  Mir answered the screencopy capture request'
  else
    echo '  [smoke] FAIL Mir did not answer the screencopy capture request' >&2
    SMOKE_STATUS=1
  fi
  # Frame counter at byte offset 24 of the shared-framebuffer header.
  SMOKE_SEQ="$(dd if="${SMOKE_FB}" bs=4 skip=6 count=1 2>/dev/null | od -An -tu4 2>/dev/null | tr -d ' \n')"
  echo "  [smoke] note: frames copied to the shared framebuffer: ${SMOKE_SEQ:-unknown} (no drawing client in the container)"
fi

kill "${SMOKE_MIR}" 2>/dev/null || true
sleep 1

if [ "${SMOKE_STATUS}" -ne 0 ]; then
  echo ''
  echo '  [smoke] --- mir.log ---' >&2
  tail -n 40 "${SMOKE_DIR}/mir.log" >&2 || true
  rm -rf "${SMOKE_DIR}"
  echo '  [smoke] FAILED: the provisioned rootfs cannot bring the Mir desktop up.' >&2
  exit 1
fi
rm -rf "${SMOKE_DIR}"
echo '  [smoke] Mir desktop smoke test passed'

echo '  [apt] Installed desktop packages:'
dpkg-query -W -f='    - %{Package} (%{Version})\n' lomiri mir-demos mir-test-tools mir-platform-graphics-virtual mir-platform-rendering-egl-generic 2>/dev/null || true

rm -f /tmp/termdeb-mir-bridge.c /tmp/termdeb-mir-input-stub.cpp /tmp/version-script.map.in
rm -rf /tmp/bridge
GUEST_EOF

cat > "${STAGE_DIR}/outer.sh" << 'OUTER_EOF'
#!/bin/bash
# Runs in the container; sets up the rootfs then chroots into it.
set -euo pipefail

# Resolver: the CI host's /etc/resolv.conf often points at a systemd-resolved
# stub (127.0.0.53) unreachable inside the container. docker run --dns supplies a
# working container resolver; fall back to a public resolver if it still looks
# wrong.
cp /etc/resolv.conf /rootfs/etc/resolv.conf
if ! grep -qE '^nameserver ' /rootfs/etc/resolv.conf 2>/dev/null \
   || grep -q '127.0.0.53' /rootfs/etc/resolv.conf 2>/dev/null; then
  printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /rootfs/etc/resolv.conf
fi
echo "  [docker] resolver: $(tr '\n' ' ' < /rootfs/etc/resolv.conf)"

mkdir -p /rootfs/dev /rootfs/proc /rootfs/tmp /rootfs/sys
chmod 1777 /rootfs/tmp
mount --bind /dev /rootfs/dev 2>/dev/null || true

cp /bridge/termdeb-mir-bridge.c /rootfs/tmp/termdeb-mir-bridge.c
cp /bridge/termdeb-mir-input-stub.cpp /rootfs/tmp/termdeb-mir-input-stub.cpp
cp /bridge/version-script.map.in /rootfs/tmp/version-script.map.in
cp /bridge/guest-provision.sh /rootfs/tmp/termdeb-guest-provision.sh
chmod 755 /rootfs/tmp/termdeb-guest-provision.sh

chroot /rootfs /bin/bash /tmp/termdeb-guest-provision.sh

umount /rootfs/dev 2>/dev/null || true
rm -f /rootfs/etc/resolv.conf /rootfs/tmp/termdeb-guest-provision.sh \
  /rootfs/tmp/termdeb-mir-input-stub.cpp /rootfs/tmp/version-script.map.in \
  /rootfs/tmp/termdeb-mir-bridge.c
echo "  [docker] Desktop provisioning complete."
OUTER_EOF

chmod +x "${STAGE_DIR}/outer.sh" "${STAGE_DIR}/guest-provision.sh"

# --dns gives the container a working resolver; do NOT bind-mount the host
# resolv.conf, which on CI points at a systemd-resolved stub.
docker run --rm --privileged --platform linux/arm64 \
  --dns 8.8.8.8 --dns 1.1.1.1 \
  -v "${ROOTFS_DIR}:/rootfs" \
  -v "${STAGE_DIR}:/bridge:ro" \
  debian:trixie \
  /bin/bash /bridge/outer.sh

rm -rf "${STAGE_DIR}"

# ---- Fix permissions --------------------------------------------------------
echo "  Fixing rootfs permissions..."
# Many paths are still root-owned and/or mode 000 after provisioning from the
# Docker container. The workflow host runs unprivileged and must be able to read
# and later remove the staged tree, so chmod recursively first. Ignore failures
# here; a later step will force-walk the tree if needed.
chmod -R u+rwX "${ROOTFS_DIR}" 2>/dev/null || true
find "${ROOTFS_DIR}" -type d -exec chmod a+rx {} + 2>/dev/null || true
chmod 1777 "${ROOTFS_DIR}/tmp" 2>/dev/null || true
# Make the whole tree deletable by the unprivileged workflow user: force mode
# changes and (if they exist) any directory-owner mismatches.
chmod -R u+rwX "${ROOTFS_DIR}" 2>/dev/null || true
find "${ROOTFS_DIR}" -type d -exec chmod a+rx {} + 2>/dev/null || true
chmod 1777 "${ROOTFS_DIR}/tmp" 2>/dev/null || true

# ---- Verify -----------------------------------------------------------------
echo ""
echo "  Verifying desktop provisioning..."
STATUS=0
if [ -x "${ROOTFS_DIR}/usr/local/bin/termdeb-mir-bridge" ]; then
  FILE_TYPE=$(file "${ROOTFS_DIR}/usr/local/bin/termdeb-mir-bridge" 2>/dev/null || echo unknown)
  echo "  OK  termdeb-mir-bridge: ${FILE_TYPE}"
  case "${FILE_TYPE}" in
    *aarch64*|*"ARM aarch64"*) ;;
    *) echo "  FAIL termdeb-mir-bridge is not ARM64" >&2; STATUS=1 ;;
  esac
else
  echo "  FAIL termdeb-mir-bridge missing" >&2; STATUS=1
fi

if [ -f "${ROOTFS_DIR}/etc/termdeb-desktop-provisioned" ]; then
  echo "  OK  desktop provisioned marker present"
else
  echo "  FAIL desktop provisioned marker missing" >&2; STATUS=1
fi

LOMIRI_FOUND=0
for p in usr/bin/lomiri usr/bin/lomiri-system-compositor usr/bin/mir_demo_server usr/bin/miral-shell; do
  if [ -e "${ROOTFS_DIR}/${p}" ]; then echo "  OK  ${p}"; LOMIRI_FOUND=1; fi
done
if [ "${LOMIRI_FOUND}" -eq 0 ]; then
  echo "  FAIL no Lomiri/Mir binaries found in the rootfs" >&2
  STATUS=1
fi

# Mir platform modules and the TermDeb input platform stub. Mir resolves the
# module names below from these files at startup; a rootfs without them starts
# no compositor ("Failed to load platform" / "No appropriate input platform
# module found") and the guest session aborts before the Wayland socket exists.
MIR_PLATFORM_DIR="${ROOTFS_DIR}/usr/lib/aarch64-linux-gnu/mir/server-platform"
check_glob() {
  local pattern="$1" label="$2"
  # shellcheck disable=SC2086
  if compgen -G "${pattern}" >/dev/null 2>&1; then
    echo "  OK  ${label}: $(basename "$(compgen -G "${pattern}" | head -1)")"
  else
    echo "  FAIL ${label} missing (expected ${pattern})" >&2
    STATUS=1
  fi
}
check_glob "${MIR_PLATFORM_DIR}/server-virtual.so.*" "Mir virtual display platform (mir:virtual)"
check_glob "${MIR_PLATFORM_DIR}/renderer-egl-generic.so.*" "Mir egl-generic rendering platform (mir:egl-generic)"
check_glob "${MIR_PLATFORM_DIR}/input-termdeb-stub.so" "TermDeb Mir input platform stub (termdeb:input-stub)"

if [ -e "${MIR_PLATFORM_DIR}/input-termdeb-stub.so" ]; then
  if command -v nm >/dev/null 2>&1; then
    if nm -D --defined-only "${MIR_PLATFORM_DIR}/input-termdeb-stub.so" 2>/dev/null \
       | grep -q 'create_input_platform@@MIR_INPUT_PLATFORM_'; then
      echo '  OK  input stub exports its ABI-versioned entry points'
    else
      echo '  FAIL input stub does not export versioned entry points (Mir would ignore it)' >&2
      STATUS=1
    fi
  else
    echo '  note: nm unavailable; skipping the input stub symbol check'
  fi
fi

if [ "${STATUS}" -ne 0 ]; then
  echo ""
  echo "  Desktop provisioning FAILED." >&2
  exit 1
fi

echo ""
echo "============================================"
echo "  Lomiri desktop provisioning succeeded"
echo "============================================"
