#!/bin/bash
# TermDeb Environment Configuration
# Sets up the runtime environment variables for TermDeb
#
# Copyright (c) TermDeb Contributors
# SPDX-License-Identifier: MIT

# TermDeb paths
export TERMDEB_APP_DATA_DIR="/data/data/com.qali.termdeb"
export TERMDEB_PREFIX="${TERMDEB_APP_DATA_DIR}/files/usr"
export TERMDEB_HOME="${TERMDEB_APP_DATA_DIR}/files/home"
export TERMDEB_UROOT="${TERMDEB_APP_DATA_DIR}/files/debian-root"

# TermDeb version info
export TERMDEB_VERSION="0.1.0"
export TERMDEB_ENV_VERSION="1"

# Termux compatibility - many tools expect these
export PREFIX="${TERMDEB_PREFIX}"
export HOME="${TERMDEB_HOME}"
export TMPDIR="${TERMDEB_PREFIX}/tmp"
export TEMP="${TMPDIR}"
export TMP="${TMPDIR}"

# TLS certificates: the bootstrap binaries were compiled with the official
# "com.termux" prefix hardcoded, so their compiled-in CA bundle path
# (/data/data/com.termux/files/usr/etc/tls/cert.pem) is not readable by this
# fork. Point curl/openssl at this fork's own CA bundle, otherwise HTTPS fails
# with "error adding trust anchors" (curl exit 77).
export CURL_CA_BUNDLE="${TERMDEB_PREFIX}/etc/tls/cert.pem"
export SSL_CERT_FILE="${TERMDEB_PREFIX}/etc/tls/cert.pem"

# Box64 configuration
export BOX64_PATH="${TERMDEB_PREFIX}/bin/box64"
export BOX64_DYNAREC=1
export BOX64_DYNAREC_BIGBLOCK=1
export BOX64_DYNAREC_SAFEFLAGS=1
export BOX64_ENV=0
export BOX64_LOG=0

# Box86 configuration (optional)
export BOX86_PATH="${TERMDEB_PREFIX}/bin/box86"
export BOX86_DYNAREC=1
export BOX86_DYNAREC_STRONGMEM=1
export BOX86_DYNAREC_BIGBLOCK=1

# Android-specific environment
export ANDROID_DATA="${TERMDEB_APP_DATA_DIR}"
export ANDROID_ROOT="/system"
export ANDROID_DNS="1"

# Locale
export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

# Standard paths
export PATH="${TERMDEB_PREFIX}/bin:${TERMDEB_PREFIX}/bin/applets:${PATH}"
export LD_LIBRARY_PATH="${TERMDEB_PREFIX}/lib:${LD_LIBRARY_PATH:-}"

# Man pages
export MANPATH="${TERMDEB_PREFIX}/share/man:${MANPATH:-}"

# Build tools (if Android SDK is bundled)
if [[ -d "${TERMDEB_PREFIX}/share/android-sdk" ]]; then
    export ANDROID_HOME="${TERMDEB_PREFIX}/share/android-sdk"
    export ANDROID_SDK_ROOT="${ANDROID_HOME}"
    export PATH="${ANDROID_HOME}/build-tools/$(ls "${ANDROID_HOME}/build-tools" 2>/dev/null | sort -V | tail -1):${PATH}"
fi
