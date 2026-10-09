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

# ---- Stage the bridge source for the container ------------------------------
STAGE_DIR="$(mktemp -d /tmp/termdeb-desktop-provision-XXXXXX)"
cp "${BRIDGE_SRC_DIR}/termdeb-mir-bridge.c" "${STAGE_DIR}/"

INNER_SCRIPT="${STAGE_DIR}/inner.sh"
cat > "${INNER_SCRIPT}" << 'INNER_EOF'
#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Resolver: the CI host's /etc/resolv.conf frequently points at a systemd-resolved
# stub (127.0.0.53) that is unreachable inside the container, which makes apt and
# curl fail with "Temporary failure resolving <host>". docker run --dns provides a
# working container resolver; fall back to a public resolver if it still looks wrong.
cp /etc/resolv.conf /rootfs/etc/resolv.conf
if ! grep -qE '^nameserver ' /rootfs/etc/resolv.conf 2>/dev/null \
   || grep -q '127.0.0.53' /rootfs/etc/resolv.conf 2>/dev/null; then
  printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /rootfs/etc/resolv.conf
fi
echo "  [docker] resolver: $(tr '\n' ' ' < /rootfs/etc/resolv.conf)"
mkdir -p /rootfs/dev /rootfs/proc /rootfs/tmp /rootfs/sys
chmod 1777 /rootfs/tmp
mount --bind /dev /rootfs/dev 2>/dev/null || true

# Protocol XML sources for the bridge (screencopy + virtual input).
SCREENCCOPY_XML="https://gitlab.freedesktop.org/wayland/wlr-protocols/-/raw/master/unstable/wlr-screencopy-unstable-v1.xml"
VIRTUAL_POINTER_XML="https://gitlab.freedesktop.org/wayland/wlr-protocols/-/raw/master/unstable/wlr-virtual-pointer-unstable-v1.xml"
VIRTUAL_KEYBOARD_XML="https://gitlab.freedesktop.org/wayland/wayland-protocols/-/raw/main/unstable/virtual-keyboard/virtual-keyboard-unstable-v1.xml"
# NOTE: wayland-protocols ships virtual-keyboard-unstable-v1.xml; the build prefers
# that copy and only falls back to this URL if the package layout changes.

cp /bridge/termdeb-mir-bridge.c /rootfs/tmp/termdeb-mir-bridge.c

chroot /rootfs /bin/bash -c "
  set -euo pipefail
  export DEBIAN_FRONTEND=noninteractive
  export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

  if [ ! -e /usr/sbin/policy-rc.d ]; then
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod 755 /usr/sbin/policy-rc.d
  fi

  if ! grep -Rqs '^Acquire::ForceIPv4' /etc/apt/apt.conf.d 2>/dev/null; then
    printf 'Acquire::ForceIPv4 \"true\";\nAcquire::Retries \"2\";\n' > /etc/apt/apt.conf.d/99termdeb-network
  fi

  echo '  [apt] Updating package lists...'
  if ! apt-get update -qq; then
    echo '  [apt] ERROR: apt-get update failed (DNS/network); cannot provision Lomiri.' >&2
    cat /etc/resolv.conf >&2 || true
    exit 1
  fi

  echo '  [apt] Installing the Lomiri desktop (ARM64)...'
  # Required: the Lomiri shell and its session integration, which pull in the Mir
  # server libraries the compositor is built on.
  apt-get install -y --no-install-recommends \
    lomiri lomiri-desktop-session

  # Optional: the Mir demo/tooling binaries used to host the virtual output.
  # Package names differ across mirrors, so install each one independently; a
  # missing optional package must not abort the whole transaction (apt-get
  # installs nothing at all if any requested name is unknown).
  for pkg in mir-test-tools mir-demos libmirserver2 libmirplatform2; do
    if ! apt-get install -y --no-install-recommends "$pkg"; then
      echo "  [apt] note: optional package '$pkg' unavailable; continuing"
    fi
  done

  echo '  [apt] Installing build tooling for the display bridge...'
  apt-get install -y --no-install-recommends \
    build-essential pkg-config \
    libwayland-dev libwayland-client0 wayland-protocols \
    libxkbcommon-dev libxkbcommon0 \
    curl ca-certificates

  # Optional enhancements (Qt Wayland platform integration, fonts, session bus).
  for pkg in qtwayland5 fonts-dejavu-core fontconfig dbus-x11; do
    apt-get install -y --no-install-recommends "$pkg" || true
  done

  # ---- Build the TermDeb Mir display bridge ----
  echo '  [build] Fetching Wayland protocol definitions...'
  mkdir -p /tmp/bridge
  cd /tmp/bridge
  curl -fsSL '${SCREENCCOPY_XML}' -o wlr-screencopy-unstable-v1.xml
  curl -fsSL '${VIRTUAL_POINTER_XML}' -o wlr-virtual-pointer-unstable-v1.xml

  # virtual-keyboard-unstable-v1 is shipped by the Debian wayland-protocols
  # package; prefer the on-disk copy and only download if it is absent.
  VK_XML=$(find /usr/share/wayland-protocols -name 'virtual-keyboard-unstable-v1.xml' 2>/dev/null | head -1)
  if [ -n "${VK_XML}" ] && [ -f "${VK_XML}" ]; then
    echo "  [build] Using packaged virtual-keyboard protocol: ${VK_XML}"
    cp "${VK_XML}" virtual-keyboard-unstable-v1.xml
  else
    curl -fsSL '${VIRTUAL_KEYBOARD_XML}' -o virtual-keyboard-unstable-v1.xml
  fi

  # Every protocol file must be present and actually contain an interface.
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

  echo '  [build] Compiling termdeb-mir-bridge...'
  cc -O2 -pipe -Wall -Wextra -o termdeb-mir-bridge \
     /tmp/termdeb-mir-bridge.c \
     wlr-screencopy.c wlr-virtual-pointer.c virtual-keyboard.c \
     \$(pkg-config --cflags --libs wayland-client xkbcommon)
  strip termdeb-mir-bridge 2>/dev/null || true

  install -D -m 0755 termdeb-mir-bridge /usr/local/bin/termdeb-mir-bridge
  echo '  [build] Installed /usr/local/bin/termdeb-mir-bridge'

  # Marker so the runtime can detect a desktop-provisioned rootfs.
  touch /etc/termdeb-desktop-provisioned

  echo '  [apt] Installed desktop packages:'
  dpkg-query -W -f='    ✓ %{Package} (%{Version})\n' lomiri mir-demos mir-test-tools 2>/dev/null || true

  rm -f /tmp/termdeb-mir-bridge.c
  rm -rf /tmp/bridge
  apt-get clean
"

umount /rootfs/dev 2>/dev/null || true
rm -f /rootfs/etc/resolv.conf
echo "  [docker] Desktop provisioning complete."
INNER_EOF
chmod +x "${INNER_SCRIPT}"

# --dns gives the container a working resolver (see the inner script); do NOT
# bind-mount the host resolv.conf, which on CI points at a systemd-resolved stub.
docker run --rm --privileged --platform linux/arm64 \
  --dns 8.8.8.8 --dns 1.1.1.1 \
  -v "${ROOTFS_DIR}:/rootfs" \
  -v "${STAGE_DIR}:/bridge:ro" \
  -v "${INNER_SCRIPT}:/inner.sh:ro" \
  debian:trixie \
  /bin/bash /inner.sh

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
  echo "  ✓ termdeb-mir-bridge: ${FILE_TYPE}"
  case "${FILE_TYPE}" in
    *aarch64*|*"ARM aarch64"*) ;;
    *) echo "  ✗ termdeb-mir-bridge is not ARM64" >&2; STATUS=1 ;;
  esac
else
  echo "  ✗ termdeb-mir-bridge missing" >&2; STATUS=1
fi

if [ -f "${ROOTFS_DIR}/etc/termdeb-desktop-provisioned" ]; then
  echo "  ✓ desktop provisioned marker present"
else
  echo "  ✗ desktop provisioned marker missing" >&2; STATUS=1
fi

# Report whether the Lomiri/Mir payload landed.
LOMIRI_FOUND=0
for p in usr/bin/lomiri usr/bin/lomiri-system-compositor usr/bin/mir_demo_server usr/bin/miral-shell; do
  if [ -e "${ROOTFS_DIR}/${p}" ]; then echo "  ✓ ${p}"; LOMIRI_FOUND=1; fi
done
if [ "${LOMIRI_FOUND}" -eq 0 ]; then
  echo "  ✗ no Lomiri/Mir binaries found in the rootfs" >&2
  STATUS=1
fi

if [ ! -d "${ROOTFS_DIR}/usr/lib/aarch64-linux-gnu/mir" ]; then
  echo "  ⚠ Mir server platform libraries not found under /usr/lib/aarch64-linux-gnu/mir"
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
