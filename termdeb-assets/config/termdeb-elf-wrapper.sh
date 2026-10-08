#!/bin/bash
# TermDeb Transparent ELF Architecture Detection & Execution Wrapper
#
# This script intercepts process execution and detects ELF architecture:
#   - AArch64 (ARM64) → native execution
#   - x86_64 → Box64 transparent execution
#   - x86 → Box86 transparent execution (if available)
#   - Other → direct execution (best effort)
#
# Architecture detection occurs at the process execution level,
# not through shell aliases. This handles subprocess spawning transparently.
#
# Copyright (c) TermDeb Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

# Default paths
TERMDEB_PREFIX="${TERMDEB_PREFIX:-/data/data/com.qali.termdeb/files/usr}"
BOX64_BIN="${TERMDEB_PREFIX}/bin/box64"
BOX86_BIN="${TERMDEB_PREFIX}/bin/box86"
TERMDEB_EXEC_LOG="${TERMDEB_LOG_DIR:-/tmp}/termdeb-exec.log"

# ELF magic number: 0x7F 0x45 0x4C 0x46
ELF_MAGIC="7f454c46"

# ELF machine types
MACHINE_ARM=40        # 0x28 - EM_ARM
MACHINE_X86=3         # 0x03 - EM_386
MACHINE_X86_64=62     # 0x3E - EM_X86_64
MACHINE_AARCH64=183   # 0xB7 - EM_AARCH64

# Maximum retries for architecture-mismatch failures
MAX_ARCH_RETRY=1

log_debug() {
    if [[ "${TERMDEB_DEBUG:-0}" == "1" ]]; then
        echo "[termdeb-exec] $*" >> "${TERMDEB_EXEC_LOG}" 2>/dev/null || true
    fi
}

# Read bytes from a file at a given offset
# Uses /bin/busybox xxd if available, falls back to od
read_elf_field() {
    local file="$1"
    local offset="$2"
    local size="$3"

    if command -v xxd >/dev/null 2>&1; then
        xxd -s "${offset}" -l "${size}" -p "${file}" 2>/dev/null
    elif command -v od >/dev/null 2>&1; then
        od -A n -t x1 -j "${offset}" -N "${size}" "${file}" 2>/dev/null | tr -d ' \n'
    else
        # Fallback: read raw bytes with dd and convert
        dd if="${file}" bs=1 skip="${offset}" count="${size}" 2>/dev/null | od -A n -t x1 | tr -d ' \n'
    fi
}

# Detect ELF architecture of a binary
# Returns: "arm64", "x86_64", "arm32", "x86", or "unknown"
detect_elf_arch() {
    local binary="$1"

    if [[ ! -f "${binary}" ]]; then
        echo "unknown"
        return 1
    fi

    # Check ELF magic number (first 4 bytes)
    local magic
    magic=$(read_elf_field "${binary}" 0 4)
    if [[ "${magic}" != "${ELF_MAGIC}" ]]; then
        # Not an ELF file
        echo "not_elf"
        return 0
    fi

    # Read ELF class (byte at offset 4)
    # 1 = 32-bit (ELF32), 2 = 64-bit (ELF64)
    local elf_class
    elf_class=$(read_elf_field "${binary}" 4 1)

    # Read machine type (at offset 18 for both ELF32 and ELF64)
    local machine
    machine=$(read_elf_field "${binary}" 18 2)

    case "${elf_class}" in
        02)
            # 64-bit ELF
            case "${machine}" in
                00b7|b7)
                    echo "arm64"
                    ;;
                003e|3e)
                    echo "x86_64"
                    ;;
                *)
                    echo "unknown_64_${machine}"
                    return 1
                    ;;
            esac
            ;;
        01)
            # 32-bit ELF
            case "${machine}" in
                0028|28)
                    echo "arm32"
                    ;;
                0003|03)
                    echo "x86"
                    ;;
                *)
                    echo "unknown_32_${machine}"
                    return 1
                    ;;
            esac
            ;;
        *)
            echo "unknown_class_${elf_class}"
            return 1
            ;;
    esac
}

# Execute a binary with appropriate architecture routing
exec_with_arch_detection() {
    local program="$1"
    shift

    # Resolve the full path
    local full_path
    if [[ "${program}" = /* ]]; then
        full_path="${program}"
    else
        # Search PATH
        IFS=: read -ra path_dirs <<< "${PATH:-}"
        full_path=""
        for dir in "${path_dirs[@]}"; do
            if [[ -x "${dir}/${program}" ]]; then
                full_path="${dir}/${program}"
                break
            fi
        done
    fi

    # If not found, let the shell handle the error
    if [[ -z "${full_path}" ]] || [[ ! -f "${full_path}" ]]; then
        exec "${program}" "$@"
    fi

    # Detect architecture
    local arch
    arch=$(detect_elf_arch "${full_path}")

    log_debug "exec: ${full_path} -> arch=${arch}"

    case "${arch}" in
        arm64)
            # Native ARM64 execution
            exec "${full_path}" "$@"
            ;;
        x86_64)
            # Transparent Box64 execution
            if [[ -x "${BOX64_BIN}" ]]; then
                log_debug "routing through Box64: ${full_path}"
                exec "${BOX64_BIN}" "${full_path}" "$@"
            else
                echo "termdeb-exec: x86_64 binary requires Box64: ${full_path}" >&2
                return 126
            fi
            ;;
        arm32)
            # Native ARM32 execution (if ARM64 kernel supports it)
            exec "${full_path}" "$@"
            ;;
        x86)
            # Box86 execution for 32-bit x86
            if [[ -x "${BOX86_BIN}" ]]; then
                log_debug "routing through Box86: ${full_path}"
                exec "${BOX86_BIN}" "${full_path}" "$@"
            else
                echo "termdeb-exec: x86 binary requires Box86: ${full_path}" >&2
                return 126
            fi
            ;;
        not_elf)
            # Not an ELF binary - execute directly
            exec "${full_path}" "$@"
            ;;
        *)
            echo "termdeb-exec: unrecognized architecture ${arch} for: ${full_path}" >&2
            return 126
            ;;
    esac
}

# Error-fallback: handle ENOEXEC (Exec format error) by retrying with Box64
exec_with_error_fallback() {
    local program="$1"
    shift

    # First, try normal execution with architecture detection
    exec_with_arch_detection "${program}" "$@" 2>/dev/null
    local exit_code=$?

    if [[ ${exit_code} -eq 126 ]] || [[ ${exit_code} -eq 127 ]]; then
        # ENOEXEC or not found - might be wrong architecture
        local full_path
        if [[ "${program}" = /* ]]; then
            full_path="${program}"
        else
            full_path=$(which "${program}" 2>/dev/null || echo "${program}")
        fi

        if [[ -f "${full_path}" ]]; then
            local arch
            arch=$(detect_elf_arch "${full_path}")

            if [[ "${arch}" == "x86_64" ]] && [[ -x "${BOX64_BIN}" ]]; then
                log_debug "error-fallback: retrying with Box64: ${full_path}"
                exec "${BOX64_BIN}" "${full_path}" "$@"
            fi
        fi
    fi

    return ${exit_code}
}

# If called directly (not sourced), execute the given program
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    if [[ $# -eq 0 ]]; then
        echo "Usage: termdeb-elf-wrapper <program> [args...]" >&2
        echo "" >&2
        echo "Transparent ELF architecture detection and execution." >&2
        echo "Routes x86_64 binaries through Box64 automatically." >&2
        exit 1
    fi

    exec_with_error_fallback "$@"
fi
