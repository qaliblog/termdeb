# TermDeb Lomiri Desktop (Mir display path)

This document describes the Lomiri desktop build of TermDeb: how the desktop is
packaged into the APK, how Lomiri runs inside the Debian trixie guest, and how
Mir's output reaches the Android app surface **without** using VNC or any other
remote-desktop protocol.

## What is new

| Area | Change |
| --- | --- |
| CI | New `.github/workflows/lomiri_desktop_build.yml`. The existing `debug_build.yml` is untouched. |
| Assets | New `prepareTermDebDesktopAssets` Gradle task; new `lomiri-desktop` manifest section; new `provision-desktop.sh`. |
| Guest runtime | New `termdeb-desktop` (host), `termdeb-desktop-session` (guest) and `termdeb-mir-bridge`. |
| Android app | New `LomiriDesktopActivity` (+ `LomiriDesktopView`, `LomiriDesktopDisplay`, `LomiriDesktopExtraKeys`) and `activity_lomiri_desktop.xml`. |
| Mini-keyboard | Unchanged. `view_terminal_toolbar_extra_keys.xml` and `ExtraKeysView` are reused verbatim, pinned to the bottom. |

The original terminal build, the Debian trixie userspace and the Box64 x86_64
layer are untouched. The desktop overlay lives under its own asset directory
(`app/src/main/assets/termdeb-desktop`) and is only produced by the desktop
workflow, so the base APK is unchanged.

## Architecture

```
┌──────────────────────────── Android app (com.qali.termdeb) ─────────────────────────────┐
│  LomiriDesktopActivity                                                                   │
│    ├── LomiriDesktopView (SurfaceView)   ← presents Mir frames                            │
│    │      └── LomiriDesktopDisplay       ← mmaps shared framebuffer, owns input socket    │
│    └── ExtraKeysView (existing mini-keyboard, UNCHANGED, pinned at the bottom)             │
└───────────────────────────────────────────┬───────────────────────────────────────────────┘
                                             │  files/termdeb-desktop/  (bind-mounted guest)
┌──────────────── Debian trixie rootfs, proot (no root required) ────────────────────────────┐
│  termdeb-desktop (host)  ──proot──▶  termdeb-desktop-session (guest)                        │
│        ├── Mir compositor: mir:virtual output + mir:egl-generic software rendering          │
│        ├── Lomiri shell (Wayland client of Mir)                                             │
│        └── termdeb-mir-bridge                                                               │
│               ├── wlr-screencopy  → pixels → /run/termdeb/fb.buf (shared framebuffer)       │
│               └── abstract AF_UNIX socket ← input records ← Android app                     │
│                     └── Wayland virtual keyboard/pointer → injected into Mir                │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Why Mir on a virtual output?

Inside proot there is no DRM/KMS device and no GPU, so Mir cannot drive a real
output. Mir's `mir:virtual` display platform provides an offscreen output and
`mir:egl-generic` renders it in software (llvmpipe). Lomiri is then a normal
Wayland client of that compositor.

### The display path is not VNC

- Frames are copied into a **memory-mapped file** that both the guest bridge and
  the Android app map. There is no network transport, no frame encoding, and no
  remote-desktop protocol.
- Input travels over a **local abstract `AF_UNIX` socket** owned by the app.
- The Android app presents the frames directly on its surface and forwards touch
  and mini-keyboard events back to the compositor.

### Framebuffer format

`LomiriDesktopDisplay.java` and `termdeb-mir-bridge.c` share this header
(little-endian, 32 bytes) at the start of `fb.buf`:

```
uint32 magic   = 0x42464454 ("TDFB")
uint32 version = 1
uint32 width
uint32 height
uint32 stride  (bytes per row)
uint32 format  = 1 (ARGB_8888, Android Bitmap word order)
uint32 seq     (incremented by the producer on every completed frame)
uint32 flags   (bit 0 = producer ready)
```

The app preallocates the file and publishes the desktop geometry in the header;
the guest bridge reads it, configures Mir's virtual output to match, and streams
frames.

### Input records

24-byte little-endian records written to the abstract socket:

```
u8  type      1=touch, 2=key, 3=text
u8  action    touch: 0=down,1=move,2=up ; key: 0=press,1=release
u16 reserved
i32 x         touch x (frame pixels)
i32 y         touch y (frame pixels)
u32 code      key: evdev key code ; text: Unicode code point
u32 modifiers bit0=ctrl, bit1=alt, bit2=shift
```

## Building

```bash
# Desktop APK (needs Docker + QEMU ARM64; heavy)
# Handled by .github/workflows/lomiri_desktop_build.yml, or locally:

# 1. base assets (provisioned Debian trixie ARM64 rootfs)
./gradlew prepareTermDebAssets -PprovisionRootfs=true

# 2. desktop-provision the rootfs (installs Lomiri + Mir, builds the bridge)
STAGE=$(mktemp -d)
DEBIAN_TAR=$(ls app/src/main/assets/termdeb-runtime/debian/debian-trixie-arm64.tar.* | head -1)
tar -xzf "$DEBIAN_TAR" -C "$STAGE"
bash termdeb-assets/prebuilt/provision-desktop.sh "$STAGE" termdeb-assets/prebuilt/mir-bridge
rm -f "$DEBIAN_TAR"; tar -czf "$DEBIAN_TAR" -C "$STAGE" .

# 3. stage the overlay and build
./gradlew prepareTermDebDesktopAssets
./gradlew assembleRelease
```

## Runtime layout

| Path | Purpose |
| --- | --- |
| `$PREFIX/bin/termdeb-desktop` | Host entry point; enters Debian and starts the session. |
| guest `/usr/local/bin/termdeb-desktop-session` | Brings up Mir, Lomiri and the bridge. |
| guest `/usr/local/bin/termdeb-mir-bridge` | Captures Mir frames and injects input. |
| guest `.../mir/server-platform/input-termdeb-stub.so` | Mir input platform (`termdeb:input-stub`). |
| `files/termdeb-desktop/fb.buf` | Shared framebuffer (bind-mounted to `/run/termdeb`). |
| abstract `termdeb-desktop-input` | Input socket owned by the app. |

## Guest requirements the session depends on

Mir 2.20 in Debian trixie splits its platforms into per-platform packages and
`mir-demos`/`mir_demo_server` depends on none of them, so provisioning installs
them explicitly (see `provision-desktop.sh`):

| Mir name | Package | Module file |
| --- | --- | --- |
| `mir:virtual` (offscreen output) | `mir-platform-graphics-virtual` | `server-virtual.so.*` |
| `mir:egl-generic` (software rendering) | `mir-platform-rendering-egl-generic` | `renderer-egl-generic.so.*` |
| `mir:wayland` (nested server) | `mir-platform-graphics-wayland` | `graphics-wayland.so.*` |
| `termdeb:input-stub` | built at provisioning time | `input-termdeb-stub.so` |

The input platform is ours because the guest has no usable stock one: Debian's
only input platform (`mir-platform-input-evdev10`) needs udev and `/dev/input`,
and the bridge injects all input over Wayland anyway. Mir resolves platform entry
points with `dlvsym()` under an ABI version symbol (e.g.
`MIR_INPUT_PLATFORM_0.27`); the stub gets it through a generated linker version
script, otherwise Mir loads the module but never detects it.

Other session-level requirements:

* **Mir's Wayland socket name is not configurable** in Mir 2.20 - `--host-socket`
is a Mir 1.x option and is rejected. Mir creates `$XDG_RUNTIME_DIR/wayland-0`, and
the session detects the socket instead of assuming a name.
* **A session D-Bus** must exist before the shell starts; the session script
starts one at `$XDG_RUNTIME_DIR/bus` when the app has not provided one.

## Known limitations / validation status

Verified against the ARM64 rootfs binaries (no device): Mir starts on
`mir:virtual` + `mir:egl-generic` + `termdeb:input-stub`, creates
`wayland-0`, serves clients, and `termdeb-mir-bridge` binds wlr-screencopy and
receives frame buffers. `provision-desktop.sh` runs this same sequence as a
smoke test and fails the build if the socket never appears.

Open item - the Lomiri shell itself: Debian's `lomiri` binary always uses qtmir's
`mirserver` QPA plugin and probes *device* display platforms, so it exits with
`Failed to find any platforms for current system` and ignores both
`QT_QPA_PLATFORM=wayland` and `MIR_SERVER_HOST_SOCKET`. Until that is solved the
Lomiri shell does not attach to the TermDeb Mir server (the bridge then captures
an empty virtual output). Candidate approaches: run the shell nested on the
guest's Wayland socket via a qtmir build/patch that honours the nested Wayland
display platform, or ship a `lomiri-system-compositor`-based session.

Device bring-up (first-frame latency, input calibration, and which Lomiri shell
entry point the packaged rootfs actually supports) still needs validation on a
real ARM64 device.
