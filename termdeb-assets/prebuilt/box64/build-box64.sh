#!/bin/bash
# Build the patched box64 used by TermDeb.
#
# Why patched: stock box64 pre-reserves the ELF image of fixed-address
# (ET_EXEC) binaries with a non-fixed mmap hint. On Android kernels (and some
# others) low mmap hints are ignored, so huge non-PIE binaries like Bun-compiled
# apps (opencode, etc.) that demand a fixed low load address (e.g. 0x200000)
# get relocated by the kernel, every code pointer is wrong, and box64 aborts
# with "cannot create memory map (@0x200000 ...) got ...". The patch uses
# MAP_FIXED when the target region is free (verified free via
# IsAddrElfOrFileMapped), so the image lands exactly where the binary needs it.
# See box64-mapfixed.patch.
#
# Build the stock box64 for aarch64-glibc (runs inside the Debian proot guest):
#   git clone https://github.com/ptitSeb/box64
#   cd box64
#   git apply ../box64-mapfixed.patch   (after checkout of the pinned commit)
#   mkdir build && cd build
#   cmake .. -DBAD_SIGNAL=ON -DARM_DYNAREC=ON -DBOX32=1 -DCMAKE_BUILD_TYPE=RelWithDebInfo
#   make -j$(nproc)
#
# The resulting build/box64 binary is committed at
# app/src/main/assets/termdeb-runtime/box64/box64 (the asset pipeline reuses an
# existing binary, so the APK ships this patched build). Strip it with
# llvm-strip from the Android NDK before committing:
#   llvm-strip build/box64
#
# Recommended runtime env for maximum compatibility (set by the app):
#   BOX64_DYNAREC=1 BOX64_DYNAREC_STRONGMEM=1 BOX64_DYNAREC_BIGBLOCK=1
#   BOX64_DYNAREC_SAFEFLAGS=1 BOX64_DYNAREC_BLEEDING_EDGE=1
