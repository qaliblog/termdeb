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
│        ├── OUTER Mir server: mir:virtual output + mir:egl-generic software rendering        │
│        ├── Lomiri shell = its own Mir server (qtmir "mirserver" QPA), nested on the outer   │
│        │     server through mir:wayland (MIR_SERVER_WAYLAND_HOST)                            │
│        └── termdeb-mir-bridge                                                               │
│               ├── wlr-screencopy  → pixels → /run/termdeb/fb.buf (shared framebuffer)       │
│               └── abstract AF_UNIX socket ← input records ← Android app                     │
│                     └── Wayland virtual keyboard/pointer → injected into the outer server   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Why an outer Mir server, and why does Lomiri nest on it?

Inside proot there is no DRM/KMS device and no GPU, so Mir cannot drive a real
output. Mir's `mir:virtual` display platform provides an offscreen output and
`mir:egl-generic` renders it in software (llvmpipe).

Lomiri cannot render on that platform directly. `lomiri --mode=full-shell` always
selects qtmir's `mirserver` QPA - the shell *is* a Mir server, and it ignores the
Mir 1.x mechanisms (`QT_QPA_PLATFORM=wayland`, `MIR_SERVER_HOST_SOCKET`). Pointed
at `mir:virtual` it dies, because that platform reports **no display sync groups**:

```
void mgv::Display::for_each_display_sync_group(std::function<void(DisplaySyncGroup &)> const&)
{
}
```

(src/platforms/virtual/display.cpp in Mir 2.20). qtmir therefore never binds a
render target to the screen and crashes at the first frame:

```
qtmir.screens: ScreenPlatformWindow[...]::updateExpose(exposed=true, primary=true)
!!! Fatal signal received. Attempting cleanup, but deadlock may occur
Mir fatal error: Unsupported attempt to continue after a fatal signal: SIGSEGV
  PlatformScreen::makeCurrent()   <- Screen::makeCurrent() on a null render target
```

So the guest runs an outer Mir server on the virtual output, and Lomiri's Mir
server gets a *real* display by nesting on it with Mir's nested-Wayland platform
(`MIR_SERVER_PLATFORM_DISPLAY_LIBS=mir:wayland`,
`MIR_SERVER_WAYLAND_HOST=wayland-0`). It then has a display sink to render into,
and its content is composited by the outer server - which is what the bridge
captures.

Two quirks of that nesting are handled explicitly:

* **EINTR.** Mir's nested-Wayland event loop treats a `poll(2)` interrupted by a
  signal as fatal (`src/platforms/wayland/display.cpp`, still the case on main).
  The shell spawns short-lived helpers, so a single `SIGCHLD` aborted the desktop
  with `Failed to wait for event: Interrupted system call`.
  `prebuilt/mir-eintr-shim/` builds a small `LD_PRELOAD` library, loaded only for
  the shell, that retries those `poll()` calls - and only those: the caller's
  shared object is checked before the retry.
* **Socket names.** The nested server creates its own Wayland socket (`wayland-1`
  while the outer server holds `wayland-0`). The session discovers both and
  publishes the nested one for the shell's own clients.

### The display path is not VNC

- Frames are copied into a **memory-mapped file** that both the guest bridge and
  the Android app map. There is no network transport, no frame encoding, and no
  remote-desktop protocol.
- Input travels over a **local abstract `AF_UNIX` socket** owned by the app.
- The Android app presents the frames directly on its surface and forwards touch
  and mini-keyboard events back to the compositor.

### Framebuffer format

`LomiriDesktopDisplay.java` and `termdeb-mir-bridge.c` share this header
(little-endian, 32 bytes) at the start of `fb.buf`. Pixels are `Bitmap`-order
RGBA (what `Bitmap.copyPixelsFromBuffer` expects); the bridge converts from the
Wayland `ARGB8888` layout (`B,G,R,A`) and flips the frame vertically, because Mir
reports `ZWLR_SCREENCOPY_FRAME_V1_FLAGS_Y_INVERT` and captures bottom-up. The
bridge also has to send the protocol's `copy` request after the `buffer_done`
event (screencopy v3): without it no frame is ever rendered and the app only sees
the empty framebuffer.

```
uint32 magic   = 0x42464454 ("TDFB")
uint32 version = 1
uint32 width
uint32 height
uint32 stride  (bytes per row)
uint32 format  = 1 (ARGB_8888, Android Bitmap word order)
uint32 seq     (incremented by the producer on every completed frame)
uint32 flags   (bit 0 = producer ready, bit 1 = producer stalled, bit 2 = producer failed)
```

The app preallocates the file and publishes the desktop geometry in the header;
the guest bridge reads it, configures Mir's virtual output to match, and streams
frames.

`seq` and `flags` are what make a black screen readable rather than mysterious:

* the producer writes `seq = 0` while the framebuffer is still untouched, and the
  app ignores that (`seq <= 0`) so it never paints a zeroed buffer as a black
  desktop over a good frame;
* the producer publishes `seq` only after the pixels are in place (a release
  barrier separates the two), so a new `seq` always means a complete frame;
* `flags` bit 1 is set when the compositor has not answered several capture
  requests in a row, bit 2 when the producer gave up. The view reads both and
  replaces the black surface with the state and the producer's own last log
  lines, so a failed desktop says why it failed.

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
| `files/termdeb-desktop/runtime/bridge.log` | Producer log: capture progress/errors, read by the app for its status notice. |
| guest `.../mir/server-platform/input-termdeb-stub.so` | Mir input platform (`termdeb:input-stub`). |
| guest `/usr/local/lib/termdeb-mir-eintr-shim.so` | EINTR shim preloaded into the Lomiri shell (nested-Wayland `poll()`). |
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
script, otherwise Mir loads the module but never detects it. The nested server
also gets input from the outer server's seat through its own `mir:wayland` input
platform, which is where the app's injected events arrive.

Other session-level requirements:

* **Mir's Wayland socket name is not configurable** in Mir 2.20 - `--host-socket`
is a Mir 1.x option and is rejected. Mir creates `$XDG_RUNTIME_DIR/wayland-0`, and
the session detects the socket instead of assuming a name.
* **A session D-Bus** must exist before the shell starts; the session script
starts one at `$XDG_RUNTIME_DIR/bus` when the app has not provided one.
* **Do not override `--wayland-extensions`** on the outer server. Mir refuses to
start at all if one name in the list is unknown, and its built-in default set
already contains everything the session needs (`xdg_wm_base`, `wl_shell`,
`zwlr_screencopy_manager_v1`, `zwlr_virtual_pointer_manager_v1`,
`zwp_virtual_keyboard_manager_v1`, `zxdg_output_manager_v1`, ...).

## Known limitations / validation status

Verified against the ARM64 rootfs binaries (no device), by running the shipped
guest session script end to end in a chroot on an aarch64 host:

* `mir_demo_server` starts on `mir:virtual` + `mir:egl-generic` +
  `termdeb:input-stub` and creates `wayland-0`;
* the Lomiri shell starts, selects `mir:wayland` as its display platform, gets a
  display sink for its screen, and creates its own socket (`wayland-1`);
* `termdeb-mir-bridge` attaches to the outer server, captures with
  wlr-screencopy and writes real frames into `fb.buf` - a capture of the running
  desktop is ~96% non-black with the Lomiri wallpaper/status-bar/launcher
  structure visible. A known-colour test client was captured correctly, i.e. the
  frame is neither vertically flipped nor colour-channel swapped.
* `provision-desktop.sh` runs the *same* session script as its smoke test and
  fails the build unless non-black desktop pixels reach the shared framebuffer,
  the bridge reports no capture error, and the session reports frames reaching
  the framebuffer;
* the capture watchdog was exercised by forcing the timeout down
  (`TERMDEB_DESKTOP_CAPTURE_TIMEOUT_MS=1` and `=20`): the bridge abandoned
  unanswered requests, re-attached to the compositor over a thousand times
  without a crash or a leaked frame mapping, marked the stalled state, and still
  captured real frames whenever the timeout allowed a request to complete.
  The same session reported `Desktop is rendering (frame N)` in both runs.

## When the desktop is black

The desktop cannot be silently black any more; every layer reports what it is
doing:

* the session script logs `Waiting for the first frame in /run/termdeb/fb.buf...`
  and then either `Desktop is rendering (frame N); the Android view is being fed.`
  or, after 30 s without a frame, a `WARNING: no desktop frame reached ...` line
  followed by the tail of `bridge.log`. That is the first thing to read, and the
  app shows the same status on the surface (see below);
* `bridge.log` records `frame N captured (WxH, 4096 sampled pixels, M non-black)`
  every 150 frames, so a black *capture* (nothing composited yet) can be told
  apart from a desktop that never captured at all, and any fatal condition
  (`ERROR: ...`) names itself;
* `mir.log` records Mir startup, the selected platform drivers, and whether the
  virtual output was configured;
* `lomiri.log` records whether the shell nested on the outer server
  (`Selected display driver: mir:wayland`), whether it crashed (`SIGSEGV`/`Fatal
  signal`) or whether the nested-Wayland event loop aborted (`Failed to wait for
  event`);
* the app side reads `flags`/`seq` from the shared header and, when no frame has
  arrived, replaces the black surface with the producer state (starting / stalled
  / failed) plus the producer's last log lines.

Failure modes that have been seen on a device, and what they meant:

* **`Desktop view is a blank screen` with everything "up" (Mir, Lomiri, bridge).**
  The bridge had attached but the framebuffer stayed at `seq = 0`, i.e. no frame
  was ever copied. Two known causes: the bridge never sent the screencopy `copy`
  request (fixed: version-aware, after `buffer_done` on v3 and immediately after
  `buffer` on v1/v2), or the compositor never answered a request. The latter is
  now bounded: a capture request that is not answered within 4 s
  (`TERMDEB_DESKTOP_CAPTURE_TIMEOUT_MS`) is abandoned, the connection is
  re-established and the request is re-issued, and after three consecutive stalls
  the header is marked stalled. A request Mir never answers - e.g. because it
  never composed a frame - used to leave the bridge idle forever with the app on a
  black screen.
* **A black screen before the shell paints.** The frame is genuinely black until
  Lomiri paints; with software rendering (llvmpipe) on a phone-sized geometry this
  is tens of seconds. Logcat/`bridge.log` now shows those frames as
  `0 non-black` sampled pixels.

Still not yet validated on a real device:

* Frame latency, touch calibration, proot's syscall filtering, and the app-side
  surface path (first-frame pacing, rotation). The app-side status notice is
  compile-verified only: it needs a device to see it drawn.
* What the shell shows beyond the desktop surface: no app has been launched from
  the desktop and the greeter's user/AccountsService lookups fail in the guest
  (`Failed to get 'RealName' property`, `Cannot read property 'username' of
  undefined`), so the session may stop at the greeter rather than a logged-in
  desktop.
* The app launch path: apps launched by the shell talk to the nested server through
  `WAYLAND_DISPLAY`; the session publishes that variable, but the launch path
  (`lomiri-app-launch`, which in the guest has no systemd user session) has not
  been exercised.
