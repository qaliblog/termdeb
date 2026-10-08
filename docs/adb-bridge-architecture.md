# TermDeb Transparent ADB Bridge — Architecture Report

Status: reference design for the implemented bridge (`docs/`), version 1.0.
Scope: TermDeb `com.qali.termdeb`, Android 15 / API 35 target, no root, no Magisk,
no USB, no cloud. Version 1.1 adds real network transports (`adb connect host:port`)
to remote adbds: legacy TCP/IP ADB is fully supported. Version 1.2 adds Android 11+
Wireless Debugging: REAL pairing (`adb pair HOST:PAIRING_PORT`, SPAKE2 over TLS 1.3
with RFC 8446 keying-material export) and REAL secure connections (`adb connect
HOST:ADB_PORT` over the TLS transport, no A_AUTH — client cert is the identity).
See §4.9.

---

## 1. Existing TermDeb architecture (inspection results)

### 1.1 Modules and build

| Module | Role |
|---|---|
| `:app` | Application (`com.qali.termdeb`), namespace `com.termux`. AGP **8.13.2**, Gradle JVM args 4 GB, `compileSdkVersion=36`, `targetSdkVersion=28` (property; request targets API 35 — build does not depend on targetSdk for the bridge), `minSdk=21`, NDK `29.0.14206865`, `ndkBuild` external native build for `libtermux-bootstrap`. |
| `:termux-shared` | Shared constants (`TermuxConstants`), shell env, am socket server, logging. Includes its own NDK code (`local-socket.cpp` for `termux-am`). |
| `:terminal-emulator` | `TerminalSession`, `JNI` (package-private) → `libtermux.so` built from `terminal-emulator/src/main/jni/termux.c`: `open("/dev/ptmx")`, `grantpt/unlockpt/ptsname_r`, `fork` + child-side PTY attach, `execv`. |
| `:terminal-view` | Terminal rendering (`TerminalView`, text selection, gestures). |
| `:termdeb-assets` | Gradle task `prepareTermDebAssets`: downloads/provisions Debian 24.04 arm64 rootfs, proot 5.1.107.91, proot-distro 5.7.0, Box64 0.4.5 (local patched build), x86_64 glibc libs; stages everything into `app/src/main/assets/termdeb-runtime/` (gitignored, generated at build time). |

Key facts:

- Package name / prefix paths derive from `TermuxConstants` at runtime, with
  `/data/data/com.qali.termdeb/files/usr` as the effective `$PREFIX`.
- `AndroidManifest.xml`: `sharedUserId="${TERMUX_PACKAGE_NAME}"`, permissions include
  `INTERNET`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `MANAGE_EXTERNAL_STORAGE`,
  `READ_LOGS`, `DUMP`, `REQUEST_INSTALL_PACKAGES`, `WRITE_SECURE_SETTINGS`
  (runtime-grantable ones are requested by existing flows; the bridge adds no new
  permission requests). Services: `TermuxService` (foreground, sessions/tasks),
  `RunCommandService` (exported, signature/dangerous permission).
- App entry: `TermuxApplication.onCreate()` → files dir checks →
  `TermuxAmSocketServer.setupTermuxAmSocketServer(context)` (existing UNIX socket IPC
  pattern: termux-am clients connect to `$PREFIX/var/run/termux-am.sock`… actually
  `$PREFIX/var/run/termux-am/am.sock`; the bridge reuses the same "app-owned socket in
  $PREFIX" pattern).

### 1.2 How Debian is launched (host side)

`TermuxInstaller.installTermDebShellScripts()` writes `$PREFIX/bin/termdeb-debian`
(shebang = fork's own bash):

1. Guest re-entry guard (`/etc/os-release` + uid 0 → exec login shell).
2. Rootfs existence check → fallback to native shell.
3. First-start provisioning via `termdeb-provision` through proot (systemd, dbus,
   ca-certificates, locales; marker `/etc/termdeb-provisioned`).
4. Box64 env exports; optional native-mode attempt (`termdeb-native`, needs real root —
   unavailable normally); SDK discovery (`termdeb-wrap64 --print-sdk-roots`); transparent
   Box64 wrap refresh + `termdeb-wrapd` inotify watcher (host side).
5. Creates `$HOME/.profile` sourcing `~/.bashrc` (the "sourcing at shell startup"
   feature of commit `822c2cd`).
6. `exec proot --link2symlink --kill-on-exit --root-id --cwd=/root
   -b /dev -b /proc -b /sys -b $HOME:/root -b $HOME/storage:/mnt/storage
   -b /storage/emulated/0:/sdcard -b $PREFIX/tmp:/tmp -b $PREFIX/tmp/shm:/dev/shm
   -b $BOX64:/usr/local/bin/box64 -b $PREFIX/etc/resolv.conf:/etc/resolv.conf
   -r $TERMDEB_UROOT /usr/bin/env -i … /bin/bash --login`

So the guest root is `$APP_DIR/files/debian-root`, the guest sees `/root` = host `$HOME`,
`/tmp` = host `$PREFIX/tmp` (shared IPC location), and the host bash/PATH are
`$PREFIX/bin/*`. `TERMDEB_PATHS` in the installer encodes these path rules for scripts.

### 1.3 Process spawning / terminal

- Interactive sessions: `TermuxService.createTermuxSession()` → `TermuxSession.execute()`
  → `terminal-emulator` `TerminalSession` → `JNI.createSubprocess` (libtermux.so) which
  opens `/dev/ptmx`, forks, sets the slave as stdio of the child, `execv`s
  `$PREFIX/bin/login -p -l $PREFIX/bin/termdeb-shell-wrapper` (login chain ends in the
  proot'd Debian bash).
- Background tasks: `AppShell` with `TermuxShellUtils.setupProcessArgs`
  (`$PREFIX/bin/login -p ...`) via `ProcessBuilder`.
- Environment: `TermuxShellEnvironment` builds `TERMUX_*`, `PATH`,
  `PROOT_TMP_DIR`, etc.; `TermuxShellUtils.writeEnvironmentToFile` snapshots it.

### 1.4 Existing sockets / IPC

- `termux-am` socket: `$PREFIX/var/run/termux-am/am.sock` — app-side `LocalServerSocket`
  (`TermuxAmSocketServer`), clients are guest/host shell scripts. **Precedent for the ADB
  bridge host side.**
- `$PREFIX/tmp` is bound into the guest as `/tmp` — shared filesystem area usable by
  guest processes to talk to host-side servers through UNIX sockets.
- Storage mapping: `/sdcard` → host `/storage/emulated/0`; `$HOME/storage` →
  `:/mnt/storage` in guest.

### 1.5 JNI/native components

- `app/src/main/cpp`: `libtermux-bootstrap` (embedded bootstrap zip).
- `terminal-emulator/src/main/jni`: `libtermux` (PTY creation) — package-private JNI
  class, not reusable from app code; the bridge implements its own tiny PTY helper
  rather than widening the existing one (fewer changes to working code paths).

### 1.6 Conclusion of inspection

Nothing in the existing architecture runs an ADB server or exposes `adb`; the guest has
no `adb` binary at all. The bridge can be added entirely additively: one new native
binary + one new asset dir + a Java service + installer additions, without touching
proot launch, bootstrap extraction, or session management.

---

## 2. Threat and security model (hard requirements honored)

The bridge operates **only** within a normal app's capabilities:

- No root, no Magisk, no adbd patching, no system partition modification, no SELinux
  manipulation, no UID spoofing, no package security bypass, no APK-debugging bypass,
  no privileged shell. `adb root`-style requests are answered with the genuine
  `adbd cannot run as root in production builds` failure.
- Every "device" operation is implemented with the same OS facilities the host app
  itself has (its own UID): package listing/install/uninstall via Android Java APIs,
  logcat via `READ_LOGS` + `logcat -d`/`logcat` binary, file transfer bounded by the
  app's storage permissions.
- Android restrictions are preserved, not hidden: operations Android rejects fail with
  honest errors (exit status ≠ 0, realistic `pm`/`dumpsys` error text). The bridge never
  fabricates success.
- Guest → host security: the ADB server binds `127.0.0.1:5037` only (loopback), so a
  device-local `adb` cannot be reached remotely unless a forward explicitly maps it.
  No ADB authentication is required for localhost connections — same trust boundary as
  Google's ADB on the device itself (adbd trusts an authorized host; here the "host" is
  the same app sandbox).
- The agent inside Debian must never learn it is inside TermDeb through `adb`'s
  behavior: no invented command names, normal exit codes, normal error strings.

---

## 3. Real ADB reference behavior (verified against AOSP source)

Reference: AOSP `platform/packages/modules/adb` (cloned locally during design;
docs/dev/services.md, docs/dev/sync.md, shell_protocol.h, file_sync_protocol.h,
daemon/services.cpp, client/commandline.cpp, client/adb_install.cpp, adb.h).

### 3.1 Client ↔ server (what `/usr/bin/adb` in Debian must speak)

- Server listens on `127.0.0.1:5037` (configurable via `ANDROID_ADB_SERVER_PORT`).
- Request: 4 hex length prefix + service string (`%04x` format).
- Replies: `OKAY` (no payload) or `FAIL` + reason for service activation errors; query
  services (`host:version`, `host:devices`, …) return a 4-hex-length-prefixed payload.
- `host:version` payload: 4-hex-length-prefixed 4-byte value — version string is the
  **hex ADB version** (`000a0018`-style); client `adb version` prints
  `Android Debug Bridge version 1.0.41` + `Version 41` semantics. Server version
  constant is `ADB_SERVER_VERSION 41`.
- `host:devices` payload: `List of devices attached` header is printed by the *client*;
  server payload lines are `<serial>\t<state>\n` (`devices`, `offline`, `bootloader`, …).
  `host:devices-l` adds longer lines; `host:track-devices` streams updates on change.
- `host:features` / transport-scoped features: comma-joined feature strings. Device
  features the bridge advertises: `shell_v2`, `stat_v2`, `ls_v2`, `cmd`, `fixed_push_mkdir`,
  `push_sync`, `abb_exec`-free set chosen so the official client picks streamed install
  (`exec:cmd package install-create/-S …`) and shell v2.
- Transport selection: `host:transport:<serial>`, `host:transport-any`, `host-serial:...`.
  One device `host:transport-any` must succeed.
- Service activation for device services returns plain `OKAY`/`FAIL<reason>`, then the
  service stream is raw (shell/sync semantics below).
- Client binary itself: same binary is client and server (`fork`+`exec` `adb fork-server
  server`); first client invocation starts the server, prints
  `* daemon not running; starting now at tcp:5037` /
  `* daemon started successfully` on stderr, honors `ADB_SERVER_SOCKET`,
  `ANDROID_ADB_SERVER_PORT`, `-L tcp:…` flags, `-s <serial>`, `-t <transport-id>`.

### 3.2 shell service

- Service string: `shell[,v2][,TERM=<term>][,pty|raw]:<command>`; empty command = login
  shell (`shell,v2,TERM=xterm-256color,pty:`).
- Legacy (no `v2`): raw PTY/raw pipe byte stream, **no exit code** — client exits 0.
- Shell v2 protocol (both directions, `kHeaderSize = 1 + 4`):
  - Packet: 1 byte id + 4-byte LE length + data.
  - Ids: `0` stdin, `1` stdout, `2` stderr, `3` exit, `4` close-stdin,
    `5` window-size-change (ASCII `%dx%d,%dx%d` rows,cols,xpix,ypix — length includes
    NUL, i.e. `strlen+1`), `255` invalid.
  - Exit packet: 1 byte. Signal death: `0x80 | sig`. Normal: `WEXITSTATUS`.
- PTY vs raw: interactive (`shell:` with pty arg or legacy) → child on PTY, cooked
  terminal, `TERM` from service string; non-interactive/raw → pipes, no PTY,
  stderr separable only with v2.
- `exec:` service: raw byte stream, no v2, no exit status (client exit 0),
  no PTY mangling — `adb exec-out` uses it.
- `adb shell -t/-T/-x` semantics come from the *client* selecting the service string;
  the device side must simply support pty/raw × v2/legacy combinations.

### 3.3 File sync (push/pull)

- Activation: `sync:` service → `OKAY`, then binary protocol, all 32-bit LE.
- v1 (must implement; official client uses v1 unless feature negotiated):
  - Requests: `STAT`(v1 `LSTAT` id `STAT`), `LIST`, `SEND`, `RECV`, `QUIT`;
    request = 4-byte id + 4-byte path length + path (no NUL).
  - `STAT` response: 16-byte `sync_stat_v1 { id, mode, size, mtime }`; "does not exist"
    = all-zero struct.
  - `LIST` response: sequence of `DENT { id, mode, size, mtime, namelen, name }`,
    terminated by `DONE`, which AOSP writes as a **whole dent struct** of the version's
    size (20 bytes v1, 76 bytes v2, remaining fields zero) — the client reads
    `sizeof(sync_dent_vN)` bytes before it looks at the id, so a short `DONE` (e.g. the
    bare 8-byte `sync_status` form) leaves it blocked forever. A listing that fails
    (`opendir`, non-directory) also ends in `DONE` with no entries, never a `FAIL`
    frame, for the same reason.
  - `SEND`: path `,mode` (decimal octal after last comma, e.g. `/data/x,0700`) in the
    request; then `DATA <len> <bytes>` chunks (≤ 64 KiB), terminated by
    `DONE <mtime>` → response `OKAY` (sync `OKAY`, 4-byte id + zero length) or
    `FAIL <len> <msg>` (sync failure string).
  - `RECV`: path only; server streams `DATA` chunks then `DONE`.
- v2 (`stat_v2`, `ls_v2`, `sendrecv_v2` features; client uses when advertised):
  `STA2/LST2/DNT2/SND2/RCV2` messages with error codes, `dev/ino/nlink/uid/gid`,
  64-bit sizes, 64-bit seconds timestamps; `SEND`/`RECV` v2 have a setup message
  (id + mode + flags) following the path request. The bridge advertises and implements
  `stat_v2`+`ls_v2` and leaves `sendrecv_v2` unadvertised → official client falls back
  to v1 SEND/RECV (widely exercised path) while getting v2 stat/ls.
- Compression flags (`brotli/lz4/zstd`) not advertised → never negotiated.

### 3.4 Install / uninstall

- `adb install` (official client, `cmd` feature present): opens `exec:cmd package
  install-create -S <total> [flags]`, reads first status line (`Success [<id>]` or
  `Error: ...` + `Exception: ...`), then `exec:cmd package install-write -S <size>
  <session> <name> -` streaming the APK bytes, finally
  `exec:cmd package install-commit <session>` (or `install-abandon`). Each step: the
  service stream is the raw stdout of the emulated `cmd package` process; a line
  starting with `Success` ends with exit 0.
- Single-APK fast path (`install_app_streamed`): one `exec:cmd package install-create
  -S <size> [flags]` … actually one `exec:cmd package install-create` … it is
  `cmd package install-create -S <size>` → wait `Success` → stream whole APK via
  `install-write` … (the client code above is authoritative).
- `adb uninstall [-k] <pkg>` → `cmd package uninstall [-k] <pkg>`.
- Legacy fallback (`pm` push install) not needed since `cmd` feature is advertised.

### 3.5 Property / service commands (agent-visible surface)

`adb shell getprop`, `pm list packages`, `am start`, `dumpsys`, `settings`,
`cmd package …`, `logcat`, `run-as`, `monkey` are all *shell commands executed on the
device* — the bridge's device side implements them as command handlers with genuine
Android-backed output (PackageManager, Settings, logd). Non-implemented-but-real
commands must fail with realistic error text/exit status, never silently.

### 3.6 Forwards / reverse

- `host:<prefix>:forward:<local>;<remote>`, `...:forward:norebind:...`,
  `killforward[:all]`, `list-forward` (payload: lines `<serial> <local> <remote>\n`).
- Rebind matches AOSP `install_listener`: an existing listener for the same local
  endpoint is **repurposed in place** — the bound socket is kept and only its
  `connect_to` target is swapped (closing and re-binding would race the accept thread
  and fail with `EADDRINUSE`). `--no-rebind` is what refuses it (FAIL text
  `cannot rebind existing socket`); a repurpose resolves no port, so nothing is echoed
  back, exactly as upstream leaves `resolved_tcp_port` 0. A freed name is reported as
  `listener '<name>' not found`, an unlistable spec as
  `cannot bind listener: unknown socket specification:<spec>`, and a real bind failure
  as `cannot bind listener: <strerror>` (e.g. `Address already in use`; Android's
  `BindException` text is normalised to the bare strerror form).
- `tcp:0` asks the kernel for a free port; the resolved port is returned to the client
  *and* kept as the listener's canonical local name (`tcp:<port>`), so `list-forward`
  and later `killforward`/rebind requests address it by the real port.
- `forward` (host-side) and `reverse` (device-side) keep separate registries, as they
  do on a real host/device pair; `list-forward` prints only its own direction, with
  `(reverse)` in place of the serial for reverse entries.
- Transport selection accepts only the id the bridge advertises (1, also reported by
  `devices -l` and `get-transport-id`): `adb -t 99 shell …` fails with AOSP's
  `no device with transport id '99'` instead of silently retargeting the sole
  transport, and a non-numeric id fails with `invalid transport id`.
  Caveat: because both sides share one loopback here, a `forward tcp:N` and a
  `reverse tcp:N` for the *same* port number still collide at the socket level.
- Local endpoints: `tcp:<port>`, `local:<path>` (filesystem sockets;
  `localabstract:`/`localreserved:` map to abstract-namespace sockets).
- Remote endpoints on the device: `tcp:<port>`, `local:<path>` — the bridge implements
  `tcp:` and filesystem `local:`; `jdwp:`/`vsock:` remote targets fail with honest errors.
- `reverse:` service: same command grammar, device-side listener ← host endpoint.
- `smart socket` behavior for forward listeners is server-internal; the official client
  only needs the service contract.

### 3.7 Exit codes / stream discipline the agent will notice

- `adb shell <cmd>` (v2): client exits with the device-reported exit status (8-bit;
  `0x80|sig` for signals).
- `adb exec-out <cmd>`: exit 0 always (no status channel); stdout bytes are pure.
- `adb push/pull`: nonzero client exit on sync `FAIL` with the failure string printed as
  `adb: error: failed to ...` style messages.
- `adb devices` prints `List of devices attached` + `emulator-5554\tdevice` (client
  pads/prints; the server payload only carries the lines).
- `adb shell` on a closed connection: `error: closed`-style messages come from the
  client; the server side just closes.

---

## 4. Bridge design

### 4.1 Topology

```
┌─────────────────────────────── Android app process (TermDeb) ───────────────────────────┐
│  TermuxApplication.onCreate()                                                           │
│      └─ TermdebAdbBridge.start(context)  (foreground-hosted, app-owned thread)          │
│           └─ AdbServer (Java, package com.termux.app.adb)                               │
│                ├─ listens 127.0.0.1:5037                                                │
│                ├─ host services: version/devices/features/track/transport/forward/reverse│
│                └─ device services:                                                      │
│                     ├─ shell[,v2][,pty|raw][,TERM=]:  → PtyRunner / PipeRunner          │
│                     ├─ exec: / exec-out client usage                  → PipeRunner      │
│                     ├─ sync: (STAT/LSTAT v1+v2, LIST v1+v2, SEND v1, RECV v1)           │
│                     ├─ cmd package install-* / uninstall  → session manager             │
│                     ├─ host-services (logcat dump) forwarders                           │
│                     └─ forward listeners (tcp:/local:) + reverse listeners              │
└─────────────▲───────────────────────────────────────────────────────────────────────────┘
              │ TCP 127.0.0.1:5037 (loopback only)
┌─────────────┴──────────────── Debian/PRoot guest ───────────────────────────────────────┐
│  /usr/bin/adb  (C99, static, Box64-free)                                                │
│    • speaks the client↔server smart protocol to the bridge server                       │
│    • full client surface: devices[-l], shell (v2, raw, pty, escape, winsize),           │
│      exec-out, push, pull, install, uninstall, forward, reverse, logcat passthrough,    │
│      getprop/… passthrough to shell service, kill-server, version, start-server,        │
│      wait-for-device, -s/-t selection, ADB_SERVER_SOCKET / ANDROID_ADB_SERVER_PORT      │
│    • identical stdout/stderr/error text as Google's client (verified format strings)    │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- The **server** runs inside the app process (Java). It needs Android APIs for the
  device-side emulation (PackageManager/ActivityManager/Settings/logd), and it is
  lifecycle-correct: started by `TermuxApplication`, stopped on `onTerminate`, restarts
  if the port was stolen, never survives app death (guest clients then see normal
  connection-refused behavior).
- The **client** is a small static C binary placed in the guest rootfs as
  `/usr/bin/adb` (guest PATH). It does not link anything beyond libc. It runs under
  proot transparently (no ptrace tricks needed: plain sockets and syscalls).
- No change to `termdeb-debian` launch flags is required; the guest reaches the host
  server over 127.0.0.1 (same network namespace as the host app; proot does not
  namespace the network).

### 4.2 Why client-in-C + server-in-Java (and not the reverse)

- The device side needs Android Java APIs (packages, logcat filtering, settings,
  install sessions). A Java server can use `PackageManager`, `PackageInstaller`
  (API ≥ 21), `ActivityManager`, `Settings.*` via `ContentResolver`, and
  `Runtime.exec("logcat")`.
- The client side must feel like Google's `adb` to an agent: same flags, same output
  formatting, same interactive behavior (escape char, winsize forwarding, raw stdin).
  A tiny C client speaking the smart protocol directly (no need for v2 shell protocol
  re-implementation on the *client* — the *server* sends v2 packets; the client parses
  them) is ~2k LOC, static, portable, and adds zero startup latency.
- ADB authentication is unnecessary and omitted (loopback only, same-sandbox trust),
  matching `adbd` behavior for `adb connect localhost` flows where the server is
  explicitly trusted.

### 4.3 Server (Java) component map — `com.termux.app.adb`

| Class | Responsibility |
|---|---|
| `TermdebAdbBridge` | App-lifecycle glue: start/stop, port acquisition, log tagging, restart on failure. |
| `AdbServer` | Accept loop on `127.0.0.1:5037`; per-connection smart protocol state machine (hex4 requests, OKAY/FAIL, query payloads, service handoff). |
| `HostServices` | `host:version` (41), `host:devices[-l]` (one device `emulator-5554\tdevice` — serial configurable), `host:track-devices` (streaming), `host:features`, transport selection, `host:kill`, `host:get-serialno/state/devpath`, `server-status` minimal. |
| `ForwardRegistry` | `forward`/`reverse`/`list-forward`/`killforward` bookkeeping + listener threads for `tcp:` and `local:` endpoints; `norebind`; connect-through to device-side endpoints or shell services (`tcp:<port>` remote = connect to that port in the *device/netns* = same loopback; `local:<path>` = UNIX socket connect, with `localabstract:` prefix mapping). |
| `DeviceServices` | Service-string parser and dispatcher: `shell[,v2][,TERM=][,pty|raw]:`, `exec:`, `sync:`, `reverse:`, `tcp:`, `local:…`, `cmd package …` (install), `logcat` special, `shell:` fallback. |
| `ShellRunner` | Subprocess engine. PTY mode: JNI-free PTY helper (`AdbPty` — own `/dev/ptmx` handling, mirrors `terminal-emulator` `termux.c` semantics); raw mode: `ProcessBuilder` pipes. Executes `/system/bin/sh -c <cmd>` in the **device (host) context**; interactive `shell:` runs the same login chain a real device would (`/system/bin/sh`), while `TERM` comes from the service string. v2 packetizer: stdin/stdout/stderr/exit/winchange framing incl. `0x80|sig` exit semantics. |
| `DeviceCommandHandlers` | Emulators for `getprop`, `pm list packages|path|…`, `pm install/uninstall` (legacy text path), `am` (start/broadcast/force-stop via `am` binary — real Android behavior), `dumpsys` passthrough to `/system/bin/dumpsys` (needs no privilege for many services; failures honest), `settings` passthrough (fails without WRITE_SECURE_SETTINGS exactly as a real device would), `run-as` (honest failure unless debuggable app owned), `cmd` passthrough. Handlers only intercept what needs Java-backed emulation (`pm list packages`, and the small `getprop` key set that an app uid cannot read — `ro.secure`, `ro.debuggable`, `service.adb.root`, `ro.serialno`); `getprop <key>` defers to the real `/system/bin/getprop` for every other key and the list form merges the real property service (≈2000 entries) under the virtualized overlay. Interception applies only to bare invocations: anything the shell must compose (`getprop | grep`, `pm list packages > f`) falls through to the real `sh -c`, so pipes and redirection behave genuinely. Everything else runs the real binary so behavior is genuine. |
| `PackageManagerService` | `cmd package install-create/-write/-commit/-abandon` session emulation plus the one-shot `install` form (what `adb install <apk>` sends: `cmd package 'install' [flags] -S <size>` with the APK body on stdin, run as create+write+commit), backed by `android.content.pm.PackageInstaller` (API 21+): sessions, streaming APK bytes, `Success [<id>]` line protocol, verdicts in AOSP's wording (`Failure [<status message>]` from `doCommitSession`), honest `Error:`/`Exception:` lines for user builds (e.g. `INSTALL_FAILED_USER_RESTRICTED` passthrough), `uninstall` via `PackageInstaller.uninstall`. Argument strings are split like a shell would: the adb client single-quotes the args it passes through (`escape_arg`), so they reach the emulation as `'install' '-r'` and must be unquoted before dispatch. |
| `FileSyncService` | sync protocol v1 (`STAT`/`LSTAT`, `LIST`, `SEND`, `RECV`, `QUIT`) + v2 stat/ls (`STA2`/`LST2`/`DNT2`), `FAIL` failure frames, 64 KiB chunk cap, mode/mtime preservation, path resolution bound to the app's storage access (same as `adb push` to `/sdcard` on a real device), `/data/local/tmp` mapped to the app's own scratch dir (readable/writable by shell UID on real devices). |
| `LogcatService` | `logcat` shell command interception: streams real logd output (`Runtime.exec("logcat ...")` with `READ_LOGS`); `-d`, `-c`, `-s`, `-b` flag passthrough; honest failure without permission. |

### 4.4 Client (C) component map — guest `/usr/bin/adb`

Single file `adb_client.c` (+ header) built by the `:termdeb-assets` gradle task
(host arm64 gcc cross-compile via NDK when available, else native gcc for local
testing), statically linked, installed to
`debian-root/usr/bin/adb` with mode 0755 (installer re-installs it from assets on
every app start, like the other termdeb runtime binaries).

- argv parser mirroring `client/commandline.cpp` ordering: global options
  `-a -d -e -s -t -H -P -L -N`, then subcommands.
- `devices [-l]`, `connect`, `disconnect` (honest failures), `shell [-eEscTtx]`,
  `exec-out`, `push [--sync]`, `pull [-a]`, `install [-lrtdg] [--fastdeploy]`,
  `uninstall [-k]`, `forward [--no-rebind] [--remove] [--list]`, `reverse ...`,
  `logcat [opts]` (= `shell logcat ...`), `get-state/get-serialno/get-devpath`,
  `wait-for-<state>`, `start-server`, `kill-server`, `version`, `bugreport` (exec-out),
  `-x` legacy shell.
- Server bootstrap: env `ADB_SERVER_SOCKET` (`tcp:...` / `localabstract:`/`local:`),
  `ANDROID_ADB_SERVER_PORT`; try-connect → on refusal `fork`+`execl` self
  `adb fork-server server` **only if** the binary is running as the "server capable"
  mode… — this repo's client instead prints the standard
  `* daemon not running; starting now at tcp:5037` and starts the server **in the
  background via a dedicated `-L`-aware spawn of itself**, then retries (matches
  Google's client behavior; the "server" it spawns is the Java bridge's own
  `adb fork-server` bootstrap stub that pokes `TermdebAdbBridge.start()` through the
  app's am-socket protocol, then exits).

  Simplification (documented deviation): the client's `start-server` detects the
  bridge is managed by the app; if the server is not reachable it prints the standard
  daemon lines and waits until the app-owned server appears (or fails with Google's
  error text after a 2 s deadline). This keeps a single server implementation (Java)
  and still shows the exact same user-visible messages.
- Interactive shell: raw stdin (termios `-icanon -echo -isig`), escape char `~` +
  letter handling (`.` quit, `^Z` …), `TIOCGWINSZ` → v2 `kIdWindowSizeChange`
  `%dx%d,%dx%d` + NUL, `kIdStdin` packets; parse `kIdStdout/Stderr/Exit` from server;
  `-T/-t/-x` map to service-string args exactly like `adb_shell()`.
- Sync client: v1 LIST/STAT/SEND/RECV (+v2 stat/ls when feature advertised), 64 KiB
  chunks, `,mode` suffix handling, `--sync` (mirror deletions) for push, `-a` (mtime)
  for pull, symlink/dir recursion identical to file_sync_client.cpp ordering.
- Install client: streamed-install sequence exactly as `adb_install.cpp`
  (`install-create -S total` → `install-write -S size <id> <name> -` per APK →
  `install-commit`/`install-abandon`), `Success`/`Error:`/`Exception:` line handling,
  `pm install` legacy path when `cmd` feature absent.
- Exit codes: shell v2 exit byte; signals `0x80|sig`; sync/install failures 1;
  usage errors 1 (Google's client uses `error_exit` = exit 1, `EXIT_FAILURE`).

### 4.5 Data flow examples

- `adb devices` → client connects 5037 → `000e` `host:devices` → OKAY → payload
  `emulator-5554\tdevice\n` (hex4) → client prints header + line.
- `adb shell pm list packages | grep termdeb` → `shell,v2,raw:pm list packages` (no
  TERM var, stdin not a tty) → server: `pm` handler builds the list from
  `PackageManager.getInstalledPackages`, v2 stdout packet(s), exit packet `0`.
- `adb shell` (interactive, from the Debian bash) → `shell,v2,TERM=xterm-256color,pty:`
  → server allocates PTY (AdbPty), forks `/system/bin/sh`; winsize packets flow from
  the client's terminal; the agent gets a real device-like shell (uid = app's own,
  honest).
- `adb push app.apk /sdcard/` → `sync:` + `SEND /sdcard/app.apk,0100644` + DATA chunks →
  server writes through its MANAGE_EXTERNAL_STORAGE-backed file access, sets mtime,
  `OKAY`.
- `adb install demo.apk` → `exec:cmd package install-create -S 12345` … session …
  `install-commit` → `Success [1234567]` via PackageInstaller; if the build is
  non-debuggable/user-restricted, the real `Error:` text is returned (honest).
- `adb logcat -d` → `shell,raw:logcat -d` → real logcat output.

### 4.6 What is intentionally NOT provided (honest limitations)

| Operation | Real-device behavior | Bridge behavior |
|---|---|---|
| `adb root` | restarts adbd as root (userbuild: fails) | prints the userbuild failure text |
| `adb unroot` | restarts adbd non-root | same |
| `adb remount` | needs root | honest failure |
| `adb backup` | broken in modern Android | honest failure |
| `jdwp:`/`track-jdwp` | lists debuggable VMs | `track-jdwp` streams empty list (no debuggable VMs visible) |
| `run-as <non-debuggable>` | fails | same honest failure |
| Wireless Debugging pairing (Android 11+) | mDNS discovery + SPAKE2+ pairing over TLS (`libadb_pairing_connection`) | IMPLEMENTED (v1.2, see §4.9): `adb pair HOST:PAIRING_PORT` runs the real AOSP pairing protocol (TLS 1.3 → keying export `adb-label\0` → SPAKE2 → AES-128-GCM PeerInfo); `adb connect HOST:ADB_PORT` uses the TLS transport with the unchanged CNXN engine and no A_AUTH (daemon/adb_wifi.cpp semantics). mDNS discovery is a Settings-side convenience (NsdManager), never required. |
| `adb connect host:port` (legacy TCP/IP ADB) | server-side `connect_service`: TCP connect + CNXN/AUTH + transport registration | IMPLEMENTED (v1.1): `AdbTransportManager` performs a real CNXN/AUTH handshake (`RemoteDevice`) and the transport participates in device listing, `-s` selection, shell/sync/forward/reverse. |
| `adb disconnect [host:port|all]` | removes the transport | IMPLEMENTED (v1.1) |
| `abb:`/`abb_exec:` | binder | not advertised in features → official client never uses them |
| `bugreportz`, `incremental install` | feature-gated | features not advertised → client falls back |

### 4.7 Files added by the implementation

```
termdeb-assets/prebuilt/adb/                  (new; gitignored build output + committed build script)
  build-adb-client.sh                         cross/native build entry for the C client
app/src/main/cpp/adb/adb_client.c             the ADB client (single translation unit)
app/src/main/cpp/adb/adb_client.h             shared protocol constants
app/src/main/cpp/adb/Android.mk               ndkBuild module `libadb_client` → executable `adb`
app/src/main/java/com/termux/app/adb/
  TermdebAdbBridge.java                       app lifecycle glue
  AdbServer.java                              smart-protocol server
  HostServices.java                           host: services
  DeviceServices.java                         device service dispatch
  TransportSelection.java                     -s/-t/-d/-e acquire_one_transport semantics
  ForwardRegistry.java                        forward/reverse
  ShellRunner.java + AdbPty.java              shell/exec engine (+ PTY)
  DeviceCommandHandlers.java                  getprop/pm/am/… emulation & passthrough
  PackageManagerService.java                  cmd package install/uninstall emulation
  FileSyncService.java                        sync v1 + v2 stat/ls
  LogcatService.java                          logcat passthrough
  SmartSocket.java                            hex4 framing helpers
app/src/main/java/com/termux/app/adb/remote/  (v1.1: real network transports)
  AdbPacket.java                              AOSP transport-local packet framing
  AdbKeyPair.java                             Android pubkey encode + RSA-2048 SHA-1 token signing
  AdbKeyStore.java                            app-private key storage (never in the guest rootfs)
  RemoteDevice.java                           CNXN/AUTH handshake + OPEN/OKAY/WRTE/CLSE multiplexer
  RemoteStreamService.java                    device-service relay over a remote transport
  AdbTransportManager.java                    connect/disconnect registry, track-devices integration
  AdbSettingsStore.java                       non-sensitive settings (SharedPreferences)
app/src/main/java/com/termux/app/adb/wireless/ (v1.2: Android 11+ Wireless Debugging)
  Spake2.java                                 BoringSSL spake25519.c port (Ed25519, adb roles/names)
  PairingCipher.java                          pairing_auth AES-128-GCM (HKDF-SHA256, LE nonce counter)
  PairingConnection.java                      AOSP pairing client (TLS 1.3, 6-byte frames, PeerInfo)
  WirelessTls.java                            Conscrypt TLS 1.3 context + "adb-label\0" exporter
  MiniCert.java                               self-signed X.509 v3 DER builder (ADB RSA key identity)
  WirelessDeviceStore.java                    paired-device registry (guid/host/port; no secrets)
  WirelessTransportManager.java               pair/connect/reconnect lifecycle + honest states
  WirelessDiscovery.java                      NsdManager mDNS (_adb-tls-pairing/_adb-tls-connect), optional
app/src/main/java/com/termux/app/fragments/settings/AdbPreferencesFragment.java  Settings → ADB
app/src/main/res/xml/adb_preferences.xml
app/src/test/java/com/termux/app/adb/remote/  JVM tests incl. fake-adbd integration
app/src/main/java/com/termux/app/TermuxInstaller.java   (+ extraction/install of adb client & server class check)
app/src/main/java/com/termux/app/TermuxApplication.java (+ TermdebAdbBridge.start(this))
docs/adb-bridge-architecture.md               this document
```

### 4.8 Testing strategy

- Unit (JVM, in-repo tests where possible): smart-protocol framing round-trips, sync
  encode/decode golden vectors, install session state machine, service-string parser.
  v1.1 adds `AdbPacketTest` (wire framing), `AdbKeyPairTest` (Android pubkey struct +
  RSA signing) and `RemoteDeviceTest` — a fake in-process adbd exercising the full
  CNXN/AUTH/OPEN/OKAY/WRTE/CLSE surface, maxdata clamping, pre-0x01000001 lockstep
  writes, zero-checksum tolerance and honest failure modes.
  v1.2 adds the wireless suite: `Spake2Test` (role agreement, determinism, on-curve
  rejection scan), `PairingCipherTest` (RFC 5869 Test Case 3 vector, GCM counters,
  tag-mismatch), `MiniCertTest` (JVM X.509 parse + verify + tamper detection),
  `PairCliGrammarTest`, and — most importantly — `WirelessDebuggingTest` running the
  REAL protocol end-to-end over loopback TLS: production `PairingConnection` vs
  `FakePairingServer` (device-side pairing role over Conscrypt TLS 1.3), wrong-code
  rejection, `FakeSecureAdbd` (no-AUTH CNXN inside TLS) connect/disconnect, store
  persistence, and `DeviceListWirelessTest` for `adb devices`/`-s` integration.
- On-device manual verification matrix: every command listed in the request §"Primary
  objective" from inside Debian with the official semantics checked (exit codes, stream
  separation, `-t/-T/-x`, winsize resize in `adb shell` under `vi`-like programs,
  `push/pull` directory recursion + mtime, streamed install output lines, forward
  round-trip with a guest TCP listener, reverse with host listener, `logcat -d`,
  `run-as` honest failure, `getprop` real values).
- Regression guard: `adb devices` twice (server restart path), `adb kill-server` then
  any command (restarts), app restart (server rebinds), offline airplane-mode `push`
  (storage path unaffected).

---

### 4.9 Wireless Debugging (v1.2): pairing + secure transport

Protocol sources verified line-by-line from AOSP
`packages/modules/adb` (android.googlesource.com, main branch):

| Component | AOSP reference | What it pins |
|---|---|---|
| Pairing wire format | `pairing_connection/pairing_connection.cpp` | 6-byte frame header `ver(u8)=1, type(u8), payload_size(u32 BE)`; client sends SPAKE2 msg first on the pairing socket; bounds `0 < len <= 2*8192` |
| Packet types | `proto/pairing.proto` | `SPAKE2_MSG=0`, `PEER_INFO=1`; PeerInfo types `ADB_RSA_PUB_KEY=0`, `ADB_DEVICE_GUID=1`; PeerInfo struct = exactly 8192 bytes |
| Password | `pairing_connection.cpp` `kExportedKeyLabel` | TLS 1.3 exporter, label `"adb-label\0"` (NUL included), 64 bytes; password = 6 ASCII digits ‖ exported material |
| SPAKE2 | `pairing_auth/pairing_auth.cpp` → BoringSSL `crypto/curve25519/spake25519.c` | Ed25519 group; roles alice=`"adb pair client\0"` / bob=`"adb pair server\0"`; password-scalar low-3-bits adjustment; `x = sc_reduce(rnd) << 3`; SHA-512 length-prefixed transcript; 64-byte key |
| Pairing cipher | `pairing_auth/aes_128_gcm.cpp` | HKDF-SHA256 (zero salt, info `"adb pairing_auth aes-128-gcm key"`, 16 B); nonce = 64-bit LE counter, top 4 bytes zero; no AAD; 16-byte tag appended; independent enc/dec counters |
| Secure connect | `daemon/adb_wifi.cpp` `adbd_wifi_secure_connect` | TLS 1.3 with client certs, then `handle_online` + `send_connect` directly — NO A_AUTH token exchange; the client certificate matched against the pairing record IS the authentication |
| Identity | `client/adb_wifi.cpp` | Certificate self-signs the ADB RSA key; our PeerInfo carries the same Android pubkey line as legacy AUTH, so adbd whitelists one key for both transports |
| mDNS | `adb_mdns.h`, `daemon/mdns.cpp` | `_adb-tls-pairing._tcp.` (while a pairing dialog is open) and `_adb-tls-connect._tcp.`; convenience only — explicit `HOST:PORT` never requires mDNS |

Implementation (all in `com.termux.app.adb.wireless`): `Spake2.java` is an
independent pure-Java port of the BoringSSL algorithm (Apache-2.0 reference
implementation cross-checked; no GPL code); TLS uses Conscrypt
(`org.conscrypt:conscrypt-android`) because stock JSSE has no exporter API —
`Conscrypt.exportKeyingMaterial` is the exact stand-in for BoringSSL's
`SSL_export_keying_material`; `MiniCert.java` hand-encodes the DER for the
self-signed v3 certificate (no public Android API builds X.509); the unchanged
`RemoteDevice` CNXN engine runs on top of the TLS socket via
`RemoteDevice.connectOverSocket`.

Security properties:

- The pairing code travels only over the loopback smart socket into the app
  (`host:pair:<addr>` service + hex4-framed second message); it is never a
  file, argv entry, env var, or log line on either side.
- Nothing pairing-related is written to the Debian rootfs. The RSA private key
  stays in app-private storage (`AdbKeyStore`); the TLS cert derives from it
  (persisted in `files/adb/`, owner-only); `WirelessDeviceStore` persists only
  GUID/host/port/result — never the code, never keys.
- No security bypasses: standard TLS 1.3, standard SPAKE2, standard adbd
  authorization. If the pairing was revoked on the device, connect keeps
  failing and the UI reports PAIRED-not-connected honestly.

Known limitations (honest):

- Pairing requires the user to read the 6-digit code from the device's
  "Pair device with pairing code" dialog; TermDeb cannot enable Wireless
  Debugging or open that dialog itself (no public API).
- The pairing exchange never reveals the Wireless Debugging ADB port (the
  pairing socket is a separate, short-lived endpoint), so a fresh pairing is
  stored without one. The Settings Connect flow resolves the port in order
  from the pairing dialog's optional ADB-port field, the last successful
  connect stored with the pairing, or a bounded mDNS sweep of
  _adb-tls-connect._tcp — and reports "port unknown" honestly when all
  three fail, never guessing. With no pairing records at all, Connect also
  probes this device's own adbd TLS port (`service.adb.tls.port`) on
  loopback, so the no-Wi-Fi enabler below feeds straight into Connect.
- Wireless Debugging can be enabled without Wi-Fi
  (`WirelessDebuggingEnabler`, Settings → ADB → "Enable Wireless Debugging
  (no Wi-Fi)"): some ROMs grey the Developer-options toggle out until a
  Wi-Fi network is connected, but the transport itself is gated only on
  Settings.Global.ADB_WIFI_ENABLED (AdbService's settings observer) — the
  "Wi-Fi required" rule is Settings-UI policy, and adbd's TLS server binds
  any interface. With WRITE_SECURE_SETTINGS (declared in the manifest,
  granted via `adb shell pm grant`), TermDeb sets the setting directly,
  keeps mobile data untouched, reads the resulting port from
  `service.adb.tls.port`, and never fabricates success: without the grant
  the action returns the exact grant command instead.
- SPAKE2 here is not constant-time (BigInteger math). Acceptable: the secret
  is a short-lived pairing code plus fresh TLS-exported material, and the
  code is single-use.

---

## 5. Implementation plan (as executed)

1. C client (`adb_client.c`) — complete smart-protocol client + sync + install logic,
   verifiable on the host with the reference Java server in JVM tests.
2. Java server (`com.termux.app.adb.*`) — smart protocol server, shell v2 engine,
   sync v1/v2-stat, install sessions, forwards, command handlers.
3. Wiring — `TermuxApplication` starts the bridge; installer extracts the client binary
   into the guest rootfs (`/usr/bin/adb`) and the native ABI directory; gradle wires the
   NDK executable into assets; `termdeb-version.json` documents the component.
4. Verification — compile the C client natively; JVM-level protocol tests; on-device
   matrix left to the user (documented above).
