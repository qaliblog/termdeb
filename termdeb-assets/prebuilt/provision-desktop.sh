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

# Optional: Mir tooling/binaries. Install each name independently because
# apt-get installs nothing at all when any requested name is unknown.
for extra_pkg in mir-test-tools mir-demos libmirserver2 libmirplatform2; do
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

curl -fsSL --retry 3 --retry-delay 2 "${SCREENCCOPY_XML}" -o wlr-screencopy-unstable-v1.xml
curl -fsSL --retry 3 --retry-delay 2 "${VIRTUAL_POINTER_XML}" -o wlr-virtual-pointer-unstable-v1.xml

# virtual-keyboard-unstable-v1 is not shipped by Debian's wayland-protocols
# package; prefer an on-disk copy if one ever appears and otherwise download
# the wlroots-maintained definition.
VK_XML="$(find /usr/share/wayland-protocols -name 'virtual-keyboard-unstable-v1.xml' 2>/dev/null | head -1)"
if [ -n "${VK_XML}" ] && [ -f "${VK_XML}" ]; then
  echo "  [build] Using packaged virtual-keyboard protocol: ${VK_XML}"
  cp "${VK_XML}" virtual-keyboard-unstable-v1.xml
else
  curl -fsSL --retry 3 --retry-delay 2 "${VIRTUAL_KEYBOARD_XML}" -o virtual-keyboard-unstable-v1.xml
fi

for xml in wlr-screencopy-unstable-v1.xml wlr-virtual-pointer-unstable-v1.xml virtual-keyboard-unstable-v1.xml; do
  if [ ! -s "${xml}" ] || ! grep -q '<interface' "${xml}"; then
    echo "  [build] ERROR: protocol definition ${xml} is missing or invalid" >&2
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

if [ ! -s wlr-screencopy.h ] || [ ! -s wlr-screencopy.c ] || \
   [ ! -s wlr-virtual-pointer.h ] || [ ! -s wlr-virtual-pointer.c ] || \
   [ ! -s virtual-keyboard.h ] || [ ! -s virtual-keyboard.c ]; then
  echo "  [build] ERROR: wayland-scanner did not generate all protocol files" >&2
  ls -l *.xml *.h *.c 2>&1
  exit 1
fi

echo '  [build] Compiling termdeb-mir-bridge...'
cc -O2 -pipe -o termdeb-mir-bridge \
   /tmp/termdeb-mir-bridge.c \
   wlr-screencopy.c wlr-virtual-pointer.c virtual-keyboard.c \
   $(pkg-config --cflags --libs wayland-client xkbcommon)
strip termdeb-mir-bridge 2>/dev/null || true

install -D -m 0755 termdeb-mir-bridge /usr/local/bin/termdeb-mir-bridge
echo '  [build] Installed /usr/local/bin/termdeb-mir-bridge'

# Marker so the runtime can detect a desktop-provisioned rootfs.
touch /etc/termdeb-desktop-provisioned

echo '  [apt] Installed desktop packages:'
dpkg-query -W -f='    - %{Package} (%{Version})\n' lomiri mir-demos mir-test-tools 2>/dev/null || true

rm -f /tmp/termdeb-mir-bridge.c
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
cp /bridge/guest-provision.sh /rootfs/tmp/termdeb-guest-provision.sh
chmod 755 /rootfs/tmp/termdeb-guest-provision.sh

chroot /rootfs /bin/bash /tmp/termdeb-guest-provision.sh

umount /rootfs/dev 2>/dev/null || true
rm -f /rootfs/etc/resolv.conf /rootfs/tmp/termdeb-guest-provision.sh
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
chmod -R a+r "${ROOTFS_DIR}" 2>/dev/null || true
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

if [ "${STATUS}" -ne 0 ]; then
  echo ""
  echo "  Desktop provisioning FAILED." >&2
  exit 1
fi

echo ""
echo "============================================"
echo "  Lomiri desktop provisioning succeeded"
echo "============================================"
