#!/bin/bash
# provision-rootfs.sh - Pre-install systemd, dbus, ca-certificates, locales
# into a Debian ARM64 rootfs at BUILD TIME so the APK ships a complete
# userspace ready for offline use.
#
# Uses Docker with QEMU user-mode emulation (binfmt_misc) to run the ARM64
# apt-get inside the rootfs. The result is a fully provisioned rootfs that
# needs no online initialization at first launch.
#
# Usage: ./provision-rootfs.sh <rootfs-dir>
#   <rootfs-dir>  Path to an extracted Debian trixie ARM64 rootfs
#
# Requirements:
#   - Docker with QEMU user-mode emulation for ARM64
#     (Docker Desktop; or on Linux: docker run --privileged --rm
#      tonistiigi/binfmt --install all)
#   - Internet access (to download packages inside Docker)
#
# Copyright (c) TermDeb Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

ROOTFS_DIR="${1:?Usage: $0 <rootfs-dir>}"

if [ ! -d "${ROOTFS_DIR}/etc" ] || [ ! -f "${ROOTFS_DIR}/etc/os-release" ]; then
  echo "Error: ${ROOTFS_DIR} does not look like a Debian rootfs" >&2
  exit 1
fi

echo "============================================"
echo "  Pre-provisioning Debian ARM64 rootfs"
echo "============================================"
echo "  Rootfs: ${ROOTFS_DIR}"

# ---- Ensure Docker is available ----
if ! command -v docker &>/dev/null; then
  echo "Error: docker not found. Docker is required for rootfs provisioning." >&2
  echo "  Install Docker: https://docs.docker.com/get-docker/" >&2
  echo "  For ARM64 emulation on Linux: docker run --privileged --rm tonistiigi/binfmt --install all" >&2
  exit 1
fi

# ---- Resolve docker command (handle group membership) ----
# After adding user to docker group, the current shell may not have the
# new group. Use 'sg docker' to run docker in the docker group context.
run_docker() {
  docker "$@"
}
if ! docker info >/dev/null 2>&1; then
  if command -v sg &>/dev/null && sg docker -c "docker info" >/dev/null 2>&1; then
    run_docker() {
      sg docker -c "docker $*"
    }
    echo "  Using 'sg docker' for docker group context"
  else
    echo "Error: docker is installed but not accessible. Add user to docker group:" >&2
    echo "  sudo usermod -aG docker \$USER" >&2
    echo "  Then log out and back in, or run: newgrp docker" >&2
    exit 1
  fi
fi

# ---- Ensure QEMU ARM64 emulation is registered ----
# Check if binfmt_misc handles ARM64. If not, register it.
if [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ] 2>/dev/null; then
  echo "  QEMU ARM64 emulation: already registered"
else
  echo "  QEMU ARM64 emulation: registering..."
  if run_docker run --privileged --rm --pull=always tonistiigi/binfmt --install arm64 2>&1; then
    echo "  QEMU ARM64 emulation: registered"
  else
    echo "Error: Failed to register QEMU ARM64 emulation." >&2
    echo "  Run manually: docker run --privileged --rm tonistiigi/binfmt --install all" >&2
    exit 1
  fi
fi

# ---- Run provisioning inside Docker ----
echo ""
echo "  Running apt-get install inside ARM64 container..."

# Write the inner provisioning script to a temp file and mount it into
# the Docker container. This avoids nesting -c arguments which break when
# docker is invoked via 'sg docker -c "docker ..."'.
PROVISION_INNER=$(mktemp /tmp/termdeb-provision-inner-XXXXXX.sh)
cat > "${PROVISION_INNER}" << 'INNER_EOF'
#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

echo "  [docker] Setting up rootfs environment..."
cp /etc/resolv.conf /rootfs/etc/resolv.conf

# Ensure /dev, /proc, /tmp exist in the rootfs for chroot
mkdir -p /rootfs/dev /rootfs/proc /rootfs/tmp /rootfs/sys
chmod 1777 /rootfs/tmp
# Bind-mount host /dev into rootfs so /dev/null works inside chroot
mount --bind /dev /rootfs/dev 2>/dev/null || true

# Run the guest provisioning from a real file rather than a single-quoted `bash -c`
# argument. The guest script contains single quotes (grep -qE '^en_US.UTF-8...',
# printf '...'), which terminated the -c string early and silently truncated the
# whole provisioning: the shell then died with
# "-c: line N: syntax error: unexpected end of file" and the rootfs shipped
# un-provisioned.
cat > /rootfs/tmp/termdeb-base-provision.sh << 'BASE_GUEST_EOF'
  export DEBIAN_FRONTEND=noninteractive
  export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
  export TMPDIR=/tmp

  # Debian 13 (trixie) includes deb822 sources in debian.sources. Do not
  # keep a second legacy sources.list: APT then downloads every index twice and
  # reports each target as configured multiple times. Preserve any legacy file
  # for diagnostics, but make it inactive rather than deleting user data.
  if [ -s /etc/apt/sources.list.d/debian.sources ] && [ -f /etc/apt/sources.list ]; then
    mv /etc/apt/sources.list /etc/apt/sources.list.termdeb-disabled
  fi
  mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial
  # A minimal Debian trixie can omit empty man directories; update-alternatives
  # used by OpenJDK expects these paths to exist during a first install.
  mkdir -p /usr/share/man/man1 /usr/share/man/man5 /usr/share/man/man8

  # Preserve the rootfs package database while making maintainer scripts
  # executable. This matters for rootfs assembled from extracted .debs and is
  # harmless for a normal Debian trixie.
  if [ -f /usr/share/debconf/frontend ]; then chmod 755 /usr/share/debconf/frontend; fi
  find /var/lib/dpkg/info -type f \( -name "*.preinst" -o -name "*.postinst" -o -name "*.prerm" -o -name "*.postrm" \) -exec chmod 755 {} + 2>/dev/null || true
  if [ ! -e /usr/sbin/policy-rc.d ]; then
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod 755 /usr/sbin/policy-rc.d
  fi

  # Android devices commonly return IPv6 records for deb.debian.org while
  # the app network path only permits IPv4; prefer IPv4 to avoid long connect
  # timeouts and misleading partial-update errors. Do not replace an existing
  # administrator setting.
  if ! grep -Rqs '^Acquire::ForceIPv4' /etc/apt/apt.conf.d 2>/dev/null; then
    cat > /etc/apt/apt.conf.d/99termdeb-network << 'APTNETWORK_EOF'
Acquire::ForceIPv4 "true";
Acquire::Retries "2";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
APTNETWORK_EOF
  fi

  # Step 1: Install gnupg+gpgv first (needed for GPG key verification).
  # Debian trixie minimal rootfs lacks gnupg/apt-key, so use insecure
  # mode for this one package. This is safe at build time.
  echo "  [apt] Bootstrapping gnupg+gpgv (insecure, build-time only)..."
  apt-get update -qq --allow-insecure-repositories
  apt-get install -y --no-install-recommends --allow-unauthenticated \
    gnupg gpgv

  # Step 2: Now that gnupg is available, do a proper apt-get update
  # with GPG verification enabled.
  echo "  [apt] Updating package lists (with GPG verification)..."
  apt-get update -qq

  # Step 3: Install essential system packages. Keep apt and libapt-pkg in the
  # same transaction so the gpgv method and its APT library cannot get out of
  # sync (an older rootfs can otherwise leave apt 2.8 with libapt 2.7).
  echo "  [apt] Installing essential system packages..."
  apt-get install -y --no-install-recommends \
    apt apt-utils \
    systemd dbus ca-certificates locales \
    curl wget git unzip \
    build-essential \
    software-properties-common \
    apt-transport-https

  # Confirm package configuration is complete before continuing. Some Debian
  # maintainer scripts cannot perform service operations under Docker chroot;
  # repair what can be repaired without making provisioning fail on those hooks.
  dpkg --configure -a || true
  apt-get --fix-broken install -y || true

  # Step 4: Install JDK dependencies so openjdk-17-jdk installs quickly at runtime.
  # We do NOT install openjdk itself (user wants it on-demand), but we install
  # its dependency chain minus the JDK itself.
  echo "  [apt] Pre-installing JDK runtime dependencies..."
  apt-get install -y --no-install-recommends \
    fontconfig libfreetype6 libharfbuzz0b libfontconfig1 \
    libasound2t64 libx11-6 libxext6 libxi6 libxrender1 libxtst6 \
    zlib1g libjpeg62-turbo libpng16-16t64 \
    libcups2t64 libdrm2 libgbm1 libnss3 libnspr4 \
    libatk1.0-0t64 libatk-bridge2.0-0t64 libgtk-3-0t64 || true

  # Step 5: Generate locale. locale-gen reads /etc/locale.gen; passing a
  # locale argument alone does not enable it on all Debian base images.
  if ! locale -a 2>/dev/null | grep -q "en_US.UTF-8\|en_US.utf8"; then
    echo "  [apt] Generating en_US.UTF-8 locale..."
    if [ -f /etc/locale.gen ] && ! grep -qE '^en_US.UTF-8[[:space:]]+UTF-8' /etc/locale.gen; then
      printf 'en_US.UTF-8 UTF-8\n' >> /etc/locale.gen
    fi
    locale-gen 2>/dev/null || true
    update-locale LANG=en_US.UTF-8 2>/dev/null || true
  fi

  # Step 6: Configure default locale in /etc/environment
  if ! grep -q "LANG=en_US.UTF-8" /etc/environment 2>/dev/null; then
    echo "LANG=en_US.UTF-8" >> /etc/environment
  fi

  # Step 7: Create JAVA_HOME auto-detection profile.
  # When the user installs openjdk-17-jdk at runtime, this script
  # automatically configures JAVA_HOME and PATH on next shell login.
  echo "  [config] Creating JAVA_HOME auto-detection profile..."
  mkdir -p /etc/profile.d
  cat > /etc/profile.d/java.sh << 'JAVAEOF'
#!/bin/bash
# Auto-detect JAVA_HOME when OpenJDK is installed.
if [ -z "${JAVA_HOME}" ]; then
  # Try the standard Debian openjdk path first
  _java=$(command -v java 2>/dev/null)
  if [ -n "${_java}" ]; then
    _jdir=$(dirname "$(dirname "$(readlink -f "${_java}")")")
    if [ -f "${_jdir}/include/jni.h" ] || [ -d "${_jdir}/lib" ]; then
      export JAVA_HOME="${_jdir}"
    fi
  fi
  # Fallback: scan /usr/lib/jvm for installed JDKs
  if [ -z "${JAVA_HOME}" ] && [ -d /usr/lib/jvm ]; then
    for _d in /usr/lib/jvm/java-17-openjdk-* /usr/lib/jvm/java-*-openjdk-*; do
      if [ -d "${_d}/bin" ] && [ -f "${_d}/bin/javac" ]; then
        export JAVA_HOME="${_d}"
        break
      fi
    done
  fi
fi
if [ -n "${JAVA_HOME}" ] && [ -d "${JAVA_HOME}/bin" ]; then
  case ":${PATH}:" in
    *":${JAVA_HOME}/bin:"*) ;;
    *) export PATH="${JAVA_HOME}/bin:${PATH}" ;;
  esac
fi
unset _java _jdir _d
JAVAEOF
  chmod +x /etc/profile.d/java.sh

  # Step 8: Create a dpkg-fixer script for proot environments.
  # Under proot, systemd-related postinst scripts fail silently.
  # This wrapper lets dpkg skip broken postinst hooks.
  echo "  [config] Configuring dpkg for proot compatibility..."
  cat > /usr/local/bin/termdeb-dpkg-fixer << 'DPKGEOF'
#!/bin/bash
# termdeb-dpkg-fixer - Fix interrupted package configuration.
# Run after package install failures in proot.
dpkg --configure -a 2>/dev/null || true
apt --fix-broken install -y 2>/dev/null || true
echo "dpkg configuration fixed."
DPKGEOF
  chmod +x /usr/local/bin/termdeb-dpkg-fixer

  # Write provisioned marker
  touch /etc/termdeb-provisioned
  echo "  [apt] Provisioned marker written."

  # Keep apt lists so users can install additional packages at runtime.
  echo "  [apt] Cleaning downloaded .debs (keeping lists for runtime)..."
  rm -rf /var/cache/apt/archives/*.deb
  apt-get clean

  # Show what we installed
  echo "  [apt] Installed packages summary:"
  dpkg-query -W -f="  ✓ %{Package} (%{Version})\n" \
    systemd dbus ca-certificates locales \
    curl wget git unzip \
    build-essential gnupg gpgv 2>/dev/null | head -30
BASE_GUEST_EOF
chmod 755 /rootfs/tmp/termdeb-base-provision.sh
chroot /rootfs /bin/bash /tmp/termdeb-base-provision.sh
rm -f /rootfs/tmp/termdeb-base-provision.sh

# Unmount /dev from rootfs
umount /rootfs/dev 2>/dev/null || true

rm -f /rootfs/etc/resolv.conf
echo "  [docker] Provisioning complete."
INNER_EOF
chmod +x "${PROVISION_INNER}"

# Run the container with the inner script mounted.
# --privileged is needed for bind-mounting /dev into the chroot
# so /dev/null works inside the rootfs.
run_docker run --rm --privileged --platform linux/arm64 \
  -v "${ROOTFS_DIR}:/rootfs" \
  -v /etc/resolv.conf:/etc/resolv.conf:ro \
  -v "${PROVISION_INNER}:/provision.sh:ro" \
  debian:trixie \
  /bin/bash /provision.sh

rm -f "${PROVISION_INNER}"

# ---- Fix permissions (Docker creates root-owned files) ----
# After Docker runs as root inside the container, many files end up owned
# by root:root with restricted permissions. Fix them so the build can
# tar the rootfs and so the app can read/write everything.
echo "  Fixing rootfs permissions..."
chmod -R a+r "${ROOTFS_DIR}" 2>/dev/null || true
find "${ROOTFS_DIR}" -type d -exec chmod a+rx {} + 2>/dev/null || true
chmod 1777 "${ROOTFS_DIR}/tmp" 2>/dev/null || true

# ---- Verify provisioning ----
echo ""
echo "  Verifying provisioning..."
if [ -f "${ROOTFS_DIR}/etc/termdeb-provisioned" ] && [ -x "${ROOTFS_DIR}/usr/lib/systemd/systemd" ]; then
  echo "  ✓ systemd binary: present"
  echo "  ✓ Provisioned marker: present"
else
  echo "  ✗ Provisioning verification failed!" >&2
  [ -f "${ROOTFS_DIR}/etc/termdeb-provisioned" ] || echo "    Missing: /etc/termdeb-provisioned" >&2
  [ -x "${ROOTFS_DIR}/usr/lib/systemd/systemd" ] || echo "    Missing: /usr/lib/systemd/systemd" >&2
  exit 1
fi

# Check dbus
if [ -x "${ROOTFS_DIR}/usr/bin/dbus-daemon" ]; then
  echo "  ✓ dbus-daemon: present"
else
  echo "  ⚠ dbus-daemon: not found (non-fatal, may be at different path)"
fi

# Check ca-certificates
if [ -d "${ROOTFS_DIR}/etc/ssl/certs" ] && ls "${ROOTFS_DIR}/etc/ssl/certs/"*.pem >/dev/null 2>&1; then
  CERT_COUNT=$(ls "${ROOTFS_DIR}/etc/ssl/certs/"*.pem 2>/dev/null | wc -l)
  echo "  ✓ ca-certificates: ${CERT_COUNT} certificates"
else
  echo "  ⚠ ca-certificates: certs directory may be incomplete"
fi

# Check essential tools
for tool in curl wget git unzip make gcc; do
  if [ -x "${ROOTFS_DIR}/usr/bin/${tool}" ] || [ -x "${ROOTFS_DIR}/usr/bin/${tool}" ]; then
    echo "  ✓ ${tool}: present"
  else
    echo "  ⚠ ${tool}: not found"
  fi
done

# Check JDK runtime dependencies
for lib in libasound2t64 libx11-6 libxext6 libxi6 libxrender1 libxtst6 zlib1g; do
  if ls "${ROOTFS_DIR}/usr/lib/aarch64-linux-gnu/${lib}"* >/dev/null 2>&1; then
    echo "  ✓ ${lib}: present (JDK dependency)"
  fi
done

# Check apt lists (needed for runtime package installation)
LIST_COUNT=$(ls "${ROOTFS_DIR}/var/lib/apt/lists/"*Release 2>/dev/null | wc -l)
if [ "$LIST_COUNT" -gt 0 ]; then
  echo "  ✓ apt lists: ${LIST_COUNT} release files (users can apt-get install at runtime)"
else
  echo "  ⚠ apt lists: empty — users will need apt-get update first"
fi

# Check locales
if "${ROOTFS_DIR}/usr/bin/locale" -a 2>/dev/null | grep -q 'en_US.UTF-8'; then
  echo "  ✓ en_US.UTF-8 locale: available"
else
  echo "  ⚠ en_US.UTF-8 locale: not in locale -a (may still work)"
fi

# Check /etc/environment for JAVA_HOME hints
if grep -q "LANG=en_US.UTF-8" "${ROOTFS_DIR}/etc/environment" 2>/dev/null; then
  echo "  ✓ /etc/environment: LANG configured"
fi

echo ""
echo "============================================"
echo "  Rootfs pre-provisioned successfully"
echo "============================================"
echo ""
