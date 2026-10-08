/*
 * termdeb-wrapd - TermDeb continuous Box64 wrap watcher daemon.
 *
 * termdeb-wrap64 performs a one-shot scan at session start; termdeb-wrapd is
 * the persistent half of the transparent Box64 layer. It watches the places
 * where x86_64 toolchain binaries appear at runtime - the Gradle artifact
 * transform cache (AGP re-extracts aapt2/aidl/zipalign into a fresh
 * transforms/<hash> directory whenever the cache entry is (re)created),
 * ~/.android, the SDK build-tools, and the guest's /usr/local, /usr/bin and
 * /usr/sbin - and wraps any x86_64 ELF executable the instant it is written
 * or moved in, BEFORE a native ARM64 parent (the Gradle JVM via
 * jspawnhelper/posix_spawn) can exec it. A periodic full sweep over the same
 * roots (plus $HOME) catches anything inotify missed.
 *
 * Wrapping is idempotent: the launcher is an ARM64 binary, so an already
 * wrapped file is never re-wrapped, and native ARM64 binaries are never
 * touched. Only true executables are wrapped (ET_EXEC, or ET_DYN/PIE with a
 * PT_INTERP header) - x86_64 shared libraries (.so) are never touched. A
 * single instance is enforced with flock on a pid file; a second instance
 * (another terminal session) simply exits.
 *
 * The inotify watch set is deliberately surgical so it can never exhaust
 * the kernel watch limit: Gradle transform roots are watched root-only
 * (fresh extractions arrive as small new directories that are then watched
 * recursively), SDK roots passed as CLI args are watched only in the spots
 * executables appear (build-tools/<ver>, cmdline-tools/<ver>/bin, platform-tools),
 * and only small trees (.android, /usr/local) get full recursion. The
 * periodic full sweep over all roots is the always-on backstop; inotify
 * only narrows the latency. If the kernel refuses watches (watch limit),
 * the daemon degrades to periodic scanning only.
 *
 * Usage: termdeb-wrapd [dir...]
 *   Optional directories (typically the discovered Android SDK roots) are
 *   ADDED to the default watch/sweep roots.
 *   TERMDEB_WRAPD_INTERVAL overrides the sweep interval (seconds, default 15).
 *
 * Build (Android NDK, static):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
 *       -O2 -static -s -o termdeb-wrapd termdeb-wrapd.c
 *
 * License: MIT (same as TermDeb)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <poll.h>
#include <dirent.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <time.h>

#define ELF_MAGIC_64 "\x7f\x45\x4c\x46\x02"  /* \x7f E L F + ELFCLASS64 */
#define EM_X86_64    62                      /* e_machine value for x86-64 */
#define ET_EXEC      2
#define ET_DYN       3
#define PT_INTERP    3
#define X86_64_HI     (EM_X86_64 >> 8)       /* 0 */
#define ARCHIVE_DIR  ".termdeb-x86_64"
#define WATCH_MASK   (IN_CREATE | IN_MOVED_TO | IN_CLOSE_WRITE | \
                      IN_DELETE_SELF | IN_MOVE_SELF)
#define EVENT_BUF_LEN (64 * 1024)

static const char *launcher_path = NULL;
static const char *scanner_path = NULL;
static const char *state_path = NULL;
static volatile sig_atomic_t g_stop = 0;
static volatile sig_atomic_t g_scanner_running = 0;

static void on_signal(int sig) { (void)sig; g_stop = 1; }

static void on_child(int sig) {
    (void)sig;
    while (waitpid(-1, NULL, WNOHANG) > 0) {}
    g_scanner_running = 0;
}

/* ---------------- small helpers ---------------- */

/* True if <path> is an x86_64 ELF *executable*: ET_EXEC, or ET_DYN (PIE)
 * with a PT_INTERP program header. Shared libraries are never wrapped. */
static int is_x86_64_executable(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    unsigned char hdr[64];
    ssize_t n;
    uint16_t etype, phentsize, phnum;
    uint64_t phoff;
    if (fd < 0) return 0;
    n = read(fd, hdr, sizeof(hdr));
    if (n < 64) { close(fd); return 0; }
    if (memcmp(hdr, ELF_MAGIC_64, 5) != 0) { close(fd); return 0; }
    if (hdr[18] != EM_X86_64 || hdr[19] != X86_64_HI) { close(fd); return 0; }
    etype = (uint16_t)(hdr[16] | (hdr[17] << 8));
    if (etype == ET_EXEC) { close(fd); return 1; }
    if (etype != ET_DYN) { close(fd); return 0; }
    phoff = (uint64_t)hdr[32] | ((uint64_t)hdr[33] << 8) | ((uint64_t)hdr[34] << 16) |
            ((uint64_t)hdr[35] << 24) | ((uint64_t)hdr[36] << 32) | ((uint64_t)hdr[37] << 40) |
            ((uint64_t)hdr[38] << 48) | ((uint64_t)hdr[39] << 56);
    phentsize = (uint16_t)(hdr[54] | (hdr[55] << 8));
    phnum = (uint16_t)(hdr[56] | (hdr[57] << 8));
    if (phentsize < 8 || phnum == 0 || phnum > 4096) { close(fd); return 0; }
    {
        unsigned char ph[8];
        unsigned int i;
        for (i = 0; i < phnum; i++) {
            if (pread(fd, ph, sizeof(ph), (off_t)(phoff + (uint64_t)i * phentsize)) != 8) break;
            if ((uint32_t)(ph[0] | (ph[1] << 8) | (ph[2] << 16) | (ph[3] << 24)) == PT_INTERP) {
                close(fd);
                return 1;
            }
        }
    }
    close(fd);
    return 0;
}

static int copy_file(const char *src, const char *dest) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int out = open(dest, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0755);
    if (out < 0) { close(in); return -1; }
    char buf[65536];
    ssize_t r;
    while ((r = read(in, buf, sizeof(buf))) > 0) {
        ssize_t w = write(out, buf, (size_t)r);
        if (w != r) { close(in); close(out); unlink(dest); return -1; }
    }
    close(in);
    if (close(out) != 0) return -1;
    chmod(dest, 0755);
    return 0;
}

/* Wrap one file if it is an unwrapped x86_64 ELF executable. Returns 1 if
 * wrapped, 0 if skipped, -1 on failure. Hidden names (.termdeb-x86_64) are
 * never touched.
 *
 * The check-then-act is serialized with an exclusive flock on the archive
 * directory, shared with the scanner: the watcher and the periodic scanner
 * can otherwise race the same freshly extracted binary - each sees it as an
 * unwrapped x86_64 executable and the second clobbers the first's archive
 * with a launcher, destroying the real binary (the launcher then points at
 * another launcher and box64 dies instantly). The launcher itself is placed
 * via a temp file + rename so no reader ever sees a partially copied one. */
static int try_wrap(const char *dir, const char *name) {
    char path[4096], arch[4096], archdir[4096], tmp[4096];
    struct stat st;
    int lockfd = -1;
    if (name[0] == '.') return 0;
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    if ((st.st_mode & 0111) == 0) return 0;        /* not executable */
    snprintf(archdir, sizeof(archdir), "%s/%s", dir, ARCHIVE_DIR);
    mkdir(archdir, 0700);                          /* ignore if exists */
    lockfd = open(archdir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (lockfd >= 0) flock(lockfd, LOCK_EX);
    /* Re-check under the lock: another wrapper may have got here first. */
    if (!is_x86_64_executable(path)) {             /* not an x86_64 executable */
        if (lockfd >= 0) flock(lockfd, LOCK_UN);
        if (lockfd >= 0) close(lockfd);
        return 0;
    }
    snprintf(arch, sizeof(arch), "%s/%s", archdir, name);
    if (access(arch, F_OK) == 0) {                 /* already wrapped */
        if (lockfd >= 0) flock(lockfd, LOCK_UN);
        if (lockfd >= 0) close(lockfd);
        return 0;
    }
    if (rename(path, arch) != 0) {
        if (lockfd >= 0) flock(lockfd, LOCK_UN);
        if (lockfd >= 0) close(lockfd);
        return 0;
    }
    snprintf(tmp, sizeof(tmp), "%s/.launcher.tmp.%d", archdir, (int)getpid());
    if (copy_file(launcher_path, tmp) != 0 || rename(tmp, path) != 0) {
        unlink(tmp);
        rename(arch, path);                        /* roll back */
        if (lockfd >= 0) flock(lockfd, LOCK_UN);
        if (lockfd >= 0) close(lockfd);
        return -1;
    }
    {
        char ts[32];
        time_t now = time(NULL);
        struct tm tmv;
        localtime_r(&now, &tmv);
        strftime(ts, sizeof(ts), "%H:%M:%S", &tmv);
        printf("termdeb-wrapd: [%s] wrapped %s (x86_64 -> box64)\n", ts, path);
        fflush(stdout);
    }
    if (lockfd >= 0) flock(lockfd, LOCK_UN);
    if (lockfd >= 0) close(lockfd);
    return 1;
}

/* ---------------- inotify watch management ---------------- */

static int inotify_fd = -1;

enum watch_mode { W_RECURSE = 0, W_SDK = 1, W_TRANSFORMS = 2, W_TRANSFORMS_ROOT = 3, W_TRANSFORMS_PARENT = 4 };

struct watch_entry {
    int wd;
    char *dir;
    int mode;
};
static struct watch_entry *g_watches = NULL;
static size_t g_nwatches = 0, g_capwatches = 0;

static void remember_wd(int wd, const char *dir, int mode) {
    if (g_nwatches == g_capwatches) {
        size_t nc = g_capwatches ? g_capwatches * 2 : 64;
        struct watch_entry *nw = realloc(g_watches, nc * sizeof(*nw));
        if (!nw) return;
        g_watches = nw;
        g_capwatches = nc;
    }
    g_watches[g_nwatches].wd = wd;
    g_watches[g_nwatches].dir = strdup(dir);
    g_watches[g_nwatches].mode = mode;
    g_nwatches++;
}

static const char *dir_for_wd(int wd) {
    size_t i;
    for (i = 0; i < g_nwatches; i++)
        if (g_watches[i].wd == wd) return g_watches[i].dir;
    return NULL;
}

static int mode_for_wd(int wd) {
    size_t i;
    for (i = 0; i < g_nwatches; i++)
        if (g_watches[i].wd == wd) return g_watches[i].mode;
    return W_RECURSE;
}

static void forget_wd(int wd) {
    size_t i;
    for (i = 0; i < g_nwatches; i++) {
        if (g_watches[i].wd == wd) {
            free(g_watches[i].dir);
            g_watches[i] = g_watches[g_nwatches - 1];
            g_nwatches--;
            return;
        }
    }
}

static void add_watch_for_dir_mode(const char *dir, int mode) {
    int wd = inotify_add_watch(inotify_fd, dir, WATCH_MASK);
    if (wd >= 0)
        remember_wd(wd, dir, mode);
    /* ENOSPC (watch limit) or ENOENT: ignored; the periodic sweep is the
     * backstop. */
}

static int is_watched(const char *dir) {
    size_t i;
    for (i = 0; i < g_nwatches; i++)
        if (strcmp(g_watches[i].dir, dir) == 0) return 1;
    return 0;
}

static void add_watch_for_dir(const char *dir) {
    add_watch_for_dir_mode(dir, W_RECURSE);
}

static int g_watch_budget = 0;   /* remaining watches for bounded recursion */

/* Walk a directory: wrap x86_64 files now, add watches, recurse. */
static void watch_dir_recursive(const char *dir) {
    DIR *d;
    struct dirent *de;
    char path[4096];
    add_watch_for_dir(dir);
    d = opendir(dir);
    if (!d) return;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        snprintf(path, sizeof(path), "%s/%s", dir, de->d_name);
        if (de->d_type == DT_DIR) {
            watch_dir_recursive(path);
        } else if (de->d_type == DT_REG || de->d_type == DT_UNKNOWN) {
            try_wrap(dir, de->d_name);
        }
    }
    closedir(d);
}

/* Budgeted recursion for freshly re-created transform trees. When a wiped
 * transforms directory is re-created, the tree is often fully populated
 * within milliseconds - before the watcher could add a root watch - so the
 * deeper IN_ISDIR/IN_CLOSE_WRITE events are enqueued against watches that
 * did not exist yet and are lost. Budgeted recursion covers that burst:
 * wrap x86_64 files now and add watches down the tree, stopping after
 * g_watch_budget watches so a huge (mature) tree can never exhaust the
 * kernel watch limit. The periodic sweep remains the backstop beyond the
 * budget. */
static void watch_dir_bounded(const char *dir) {
    DIR *d;
    struct dirent *de;
    char path[4096];
    if (g_watch_budget <= 0) return;
    if (!is_watched(dir)) {
        add_watch_for_dir(dir);
        g_watch_budget--;
    }
    d = opendir(dir);
    if (!d) return;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        snprintf(path, sizeof(path), "%s/%s", dir, de->d_name);
        if (de->d_type == DT_DIR) {
            watch_dir_bounded(path);
        } else if (de->d_type == DT_REG || de->d_type == DT_UNKNOWN) {
            try_wrap(dir, de->d_name);
        }
        if (g_watch_budget <= 0) break;
    }
    closedir(d);
}

/* Targeted SDK watching. The Android SDK can be named anything and live
 * anywhere, so we cannot guess its layout: watch the root plus the handful
 * of directories where toolchain executables actually appear - build-tools
 * (one root-only watch per installed version, wrapping its direct
 * executables now), cmdline-tools/<ver>/bin and platform-tools. Recursive
 * inotify of a whole SDK (or the ~34k-dir Gradle transform tree) would
 * exhaust the kernel watch limit and silently degrade to the periodic
 * sweep - so SDK trees are watched SHALLOWLY (root-only watches, direct
 * children scanned for executables), never fully recursed. SDK tools are
 * stable once installed, so the shallow watches plus the periodic sweep
 * (and the session-start one-shot scan) are ample coverage. */
static void watch_sdk_dir(const char *root) {
    DIR *d;
    struct dirent *de;
    char path[4096];
    struct stat st;
    if (access(root, F_OK) != 0) return;
    add_watch_for_dir_mode(root, W_SDK);
    /* build-tools/<version> dirs: root-only watch, direct executables
     * wrapped now. Never recurse (lib64, lib etc. are libraries and the
     * sweep covers the rest). */
    snprintf(path, sizeof(path), "%s/build-tools", root);
    if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
        add_watch_for_dir_mode(path, W_SDK);
        d = opendir(path);
        if (d) {
            while ((de = readdir(d)) != NULL) {
                if (de->d_name[0] == '.') continue;
                if (de->d_type != DT_DIR && de->d_type != DT_UNKNOWN) continue;
                char v[4096];
                snprintf(v, sizeof(v), "%s/%s", path, de->d_name);
                if (stat(v, &st) == 0 && S_ISDIR(st.st_mode)) {
                    if (!is_watched(v)) add_watch_for_dir_mode(v, W_SDK);
                    /* Wrap any direct x86_64 executables right now. */
                    {
                        DIR *vd = opendir(v);
                        struct dirent *ve;
                        if (vd) {
                            while ((ve = readdir(vd)) != NULL)
                                if (ve->d_name[0] != '.')
                                    try_wrap(v, ve->d_name);
                            closedir(vd);
                        }
                    }
                }
            }
            closedir(d);
        }
    }
    /* cmdline-tools/<version>/bin */
    snprintf(path, sizeof(path), "%s/cmdline-tools", root);
    if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
        add_watch_for_dir_mode(path, W_SDK);
        d = opendir(path);
        if (d) {
            while ((de = readdir(d)) != NULL) {
                if (de->d_name[0] == '.') continue;
                if (de->d_type != DT_DIR && de->d_type != DT_UNKNOWN) continue;
                char b[4096];
                snprintf(b, sizeof(b), "%s/%s/bin", path, de->d_name);
                if (stat(b, &st) == 0 && S_ISDIR(st.st_mode)) {
                    if (!is_watched(b)) add_watch_for_dir_mode(b, W_SDK);
                    {
                        DIR *bd = opendir(b);
                        struct dirent *be;
                        if (bd) {
                            while ((be = readdir(bd)) != NULL)
                                if (be->d_name[0] != '.')
                                    try_wrap(b, be->d_name);
                            closedir(bd);
                        }
                    }
                }
            }
            closedir(d);
        }
    }
    /* platform-tools (flat bin dir) */
    snprintf(path, sizeof(path), "%s/platform-tools", root);
    if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
        if (!is_watched(path)) add_watch_for_dir_mode(path, W_SDK);
        {
            DIR *pd = opendir(path);
            struct dirent *pe;
            if (pd) {
                while ((pe = readdir(pd)) != NULL)
                    if (pe->d_name[0] != '.')
                        try_wrap(path, pe->d_name);
                closedir(pd);
            }
        }
    }
}

/* Gradle transform caches hold tens of thousands of directories; watching
 * them all exhausts the kernel watch limit. Watch only the transforms
 * root: a fresh extraction arrives as a new (small) directory, which the
 * IN_ISDIR handler then watches recursively and wraps immediately.
 *
 * The transforms root may not exist at startup (a cache clean deletes it)
 * and Gradle re-creates it mid-build - so the parent chain (.gradle/caches
 * and .gradle/caches/<version>) is also watched root-only. When a parent
 * watch sees a new "transforms" directory appear, it re-arms the full
 * transforms watch so a fresh aapt2 extraction is wrapped before AGP can
 * spawn its daemon. Other new dirs under the parent chain just get a
 * root-only watch of their own (they contain the chain one level down). */
static void watch_transforms_root(const char *dir) {
    if (access(dir, F_OK) == 0 && !is_watched(dir))
        add_watch_for_dir_mode(dir, W_TRANSFORMS_ROOT);
    /* Re-arm chain: watch the parent (and grandparent) root-only so a
     * re-created transforms directory is caught. */
    {
        char parent[4096];
        const char *slash = strrchr(dir, '/');
        if (slash && slash != dir) {
            size_t n = (size_t)(slash - dir);
            memcpy(parent, dir, n);
            parent[n] = '\0';
            if (access(parent, F_OK) == 0 && !is_watched(parent))
                add_watch_for_dir_mode(parent, W_TRANSFORMS_PARENT);
        }
    }
}

/* Watch .gradle/caches root-only so a wiped/absent caches tree (or version
 * dir) is re-armed when Gradle recreates it. */
static void watch_caches_root(const char *dir) {
    if (access(dir, F_OK) == 0 && !is_watched(dir))
        add_watch_for_dir_mode(dir, W_TRANSFORMS_PARENT);
}

/* Arm the whole Gradle cache -> transforms chain for HOME_D. Idempotent
 * (is_watched guards), so it can be called at startup and re-armed on every
 * sweep. The chain is what catches a transforms directory that is deleted
 * (cache clean) and re-created mid-build, and it must be armed BEFORE the
 * SDK watches so the watch limit can never starve it. */
static void arm_transforms_chain(const char *home_d) {
    char buf[4096], t[4096];
    DIR *d;
    struct dirent *de;
    snprintf(buf, sizeof(buf), "%s/.gradle/caches", home_d);
    watch_caches_root(buf);
    d = opendir(buf);
    if (d) {
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            if (de->d_type != DT_DIR && de->d_type != DT_UNKNOWN) continue;
            snprintf(t, sizeof(t), "%s/%s", buf, de->d_name);
            watch_caches_root(t);
            snprintf(t, sizeof(t), "%s/%s/transforms", buf, de->d_name);
            watch_transforms_root(t);
            snprintf(t, sizeof(t), "%s/%s/transforms-3", buf, de->d_name);
            watch_transforms_root(t);
        }
        closedir(d);
    }
}

static void handle_events(void) {
    char buf[EVENT_BUF_LEN] __attribute__((aligned(8)));
    ssize_t n = read(inotify_fd, buf, sizeof(buf));
    struct inotify_event *ev;
    if (n <= 0) return;
    for (ssize_t off = 0; off < n; ) {
        ev = (struct inotify_event *)(buf + off);
        off += (ssize_t)sizeof(*ev) + ev->len;
        if (ev->mask & (IN_DELETE_SELF | IN_MOVE_SELF)) {
            forget_wd(ev->wd);
            continue;
        }
        if (ev->len == 0 || ev->name[0] == '\0' || ev->name[0] == '.') continue;
        /* Hidden names (.termdeb-x86_64 archive dirs and their contents) are
         * managed by the wrap itself and must NEVER be watched, recursed into
         * or re-wrapped: recursing into an archive dir re-wraps the archived
         * original, which creates a deeper archive dir, which fires another
         * event - an unbounded nesting loop that leaves the launcher pointing
         * at a nested launcher instead of the real x86_64 binary. */
        {
            const char *dir = dir_for_wd(ev->wd);
            if (!dir) continue;
            if (ev->mask & IN_ISDIR) {
                /* New (or moved-in) directory: watch it and wrap its contents
                 * immediately - covers Gradle renaming a transform output
                 * directory into place and a freshly placed SDK. The mode
                 * decides how to treat the new tree (SDK roots are watched
                 * surgically, fresh transform trees are small enough for full
                 * recursion). */
                char path[4096];
                int mode = mode_for_wd(ev->wd);
                snprintf(path, sizeof(path), "%s/%s", dir, ev->name);
                if (mode == W_SDK) {
                    watch_sdk_dir(path);
                } else if (mode == W_TRANSFORMS_ROOT) {
                    /* Fresh extraction under an existing transforms root:
                     * small tree, recurse now and wrap immediately. */
                    g_watch_budget = 2048;
                    watch_dir_bounded(path);
                } else if (mode == W_TRANSFORMS_PARENT) {
                    /* Chain level: a re-created transforms (or a version dir
                     * holding one) appeared under a parent watch. Re-arm the
                     * transforms watch for it; otherwise keep chaining. */
                    if (strcmp(ev->name, "transforms") == 0 ||
                        strcmp(ev->name, "transforms-3") == 0) {
                        watch_transforms_root(path);
                        /* The fresh tree is usually already fully populated
                         * by the time this event is processed (the mkdir
                         * burst happens in the same instant), so recurse now
                         * with a budget instead of waiting for deeper events
                         * that were enqueued against watches that did not
                         * exist yet. */
                        g_watch_budget = 2048;
                        watch_dir_bounded(path);
                    } else {
                        add_watch_for_dir_mode(path, W_TRANSFORMS_PARENT);
                    }
                } else {
                    watch_dir_recursive(path);
                }
            } else if (ev->mask & (IN_CLOSE_WRITE | IN_MOVED_TO)) {
                /* Wrap only complete files: IN_CLOSE_WRITE (writer closed) or
                 * IN_MOVED_TO (rename into place) mean the content is done.
                 * IN_CREATE alone is deliberately excluded - it fires when a
                 * file is first created, possibly empty or mid-write, and
                 * wrapping that would archive a truncated binary. */
                try_wrap(dir, ev->name);
            }
        }
    }
}

/* ---------------- periodic full sweep ---------------- */

/* The sweep scanner must NEVER block the inotify event loop. A full home
 * scan is slow (tens of thousands of directories walked through proot's
 * ptrace), and if the watcher sat in waitpid() while Gradle extracted aapt2
 * into a re-created transforms dir, the daemon would spawn against the raw
 * binary before the queued events were processed. The scanner therefore
 * runs detached (one at a time), and the event loop keeps draining inotify
 * concurrently - the per-directory flock in try_wrap() serializes any wrap
 * the scanner and the event handler attempt on the same binary. */
static void run_scanner(char **roots, int nroots) {
    pid_t pid;
    if (g_scanner_running) return;   /* previous sweep still running */
    pid = fork();
    if (pid == 0) {
        char **argv = malloc((size_t)(nroots + 4) * sizeof(char *));
        int i;
        if (!argv) _exit(127);
        argv[0] = (char *)scanner_path;
        argv[1] = (char *)launcher_path;
        argv[2] = (char *)state_path;
        for (i = 0; i < nroots; i++) argv[i + 3] = roots[i];
        argv[nroots + 3] = NULL;
        execv(scanner_path, argv);
        _exit(127);
    }
    if (pid > 0)
        g_scanner_running = 1;
}

/* ---------------- roots ---------------- */

static char **g_sweep_roots = NULL;
static int g_nsweep = 0, g_capsweep = 0;

static void add_root(const char *dir) {
    struct stat st;
    if (!dir || !dir[0]) return;
    if (stat(dir, &st) != 0 || !S_ISDIR(st.st_mode)) return;
    if (g_nsweep == g_capsweep) {
        size_t nc = g_capsweep ? g_capsweep * 2 : 16;
        char **nr = realloc(g_sweep_roots, nc * sizeof(char *));
        if (!nr) return;
        g_sweep_roots = nr;
        g_capsweep = nc;
    }
    g_sweep_roots[g_nsweep++] = strdup(dir);
}

static void add_glob_dirs(const char *base, const char *rel) {
    /* <base>/.gradle/caches/<version>/transforms and the legacy
     * caches/transforms-3 - the Gradle artifact transform output dirs where
     * AGP's aapt2/aidl/zipalign are extracted. */
    char buf[4096], pat[4096];
    struct stat st;
    DIR *d;
    struct dirent *de;
    snprintf(buf, sizeof(buf), "%s/.gradle/caches", base);
    if (stat(buf, &st) != 0 || !S_ISDIR(st.st_mode)) return;
    d = opendir(buf);
    if (!d) return;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.') continue;
        if (de->d_type != DT_DIR && de->d_type != DT_UNKNOWN) continue;
        snprintf(pat, sizeof(pat), "%s/%s/%s", buf, de->d_name, rel);
        add_root(pat);
    }
    closedir(d);
    /* Legacy flat dir. */
    snprintf(pat, sizeof(pat), "%s/transforms-3", buf);
    add_root(pat);
}

/* ---------------- main ---------------- */

static const char *PREFIX = NULL;       /* host Termux prefix */
static const char *APP_DIR = NULL;      /* host app data dir */
static const char *UROOT = NULL;        /* host debian rootfs */
static const char *HOME_D = NULL;       /* host home (files/home) */

static void resolve_paths(void) {
    if (access("/etc/os-release", F_OK) == 0 && getuid() == 0) {
        /* Inside the Debian guest. */
        launcher_path = "/usr/local/libexec/termdeb-x86_64-launcher";
        scanner_path = "/usr/local/libexec/termdeb-scan64";
        state_path = "/var/lib/termdeb/scan-state";
        PREFIX = getenv("PREFIX");
        HOME_D = getenv("HOME");
        UROOT = NULL;
        APP_DIR = NULL;
    } else {
        /* Host (Android): derive from PREFIX like termdeb-wrap64. */
        const char *pfx = getenv("PREFIX");
        if (!pfx) pfx = "/data/data/com.qali.termdeb/files/usr";
        PREFIX = pfx;
        APP_DIR = NULL;
        {
            size_t len = strlen(pfx);
            const char *suffix = "/files/usr";
            size_t slen = strlen(suffix);
            if (len > slen && strcmp(pfx + len - slen, suffix) == 0) {
                char *app = malloc(len - slen + 1);
                memcpy(app, pfx, len - slen);
                app[len - slen] = '\0';
                APP_DIR = app;
            } else {
                APP_DIR = strdup(pfx);
            }
        }
        {
            /* Generous buffers: APP_DIR is /data/data/<pkg> (~26 chars) and
             * the libexec paths are ~100 chars - a tight buffer silently
             * truncates them via snprintf and every exec/copy then fails. */
            size_t n = strlen(APP_DIR) + 160;
            char *home = malloc(n);
            char *uroot = malloc(n);
            char *lp = malloc(n), *sp = malloc(n), *st = malloc(n);
            snprintf(home, n, "%s/files/home", APP_DIR);
            snprintf(uroot, n, "%s/files/debian-root", APP_DIR);
            snprintf(lp, n, "%s/files/debian-root/usr/local/libexec/termdeb-x86_64-launcher", APP_DIR);
            snprintf(sp, n, "%s/files/debian-root/usr/local/libexec/termdeb-scan64", APP_DIR);
            snprintf(st, n, "%s/files/termdeb-scan-state", APP_DIR);
            HOME_D = home;
            UROOT = uroot;
            launcher_path = lp;
            scanner_path = sp;
            state_path = st;
        }
    }
}

int main(int argc, char **argv) {
    int interval = 15;
    const char *envint = getenv("TERMDEB_WRAPD_INTERVAL");
    int i;
    struct sigaction sa;

    resolve_paths();

    /* Optional positional args add watch+sweep roots. */
    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) break;
        add_root(argv[i]);
    }
    if (envint) interval = atoi(envint);
    if (interval < 5) interval = 5;

    /* Single instance: flock a pid file. A second session must not start a
     * second watcher (double wraps are harmless but wasteful). */
    {
        const char *pidfile = UROOT ? "/tmp/termdeb-wrapd.pid" : NULL;
        char pbuf[4096];
        int lockfd;
        if (!pidfile && PREFIX) {
            snprintf(pbuf, sizeof(pbuf), "%s/tmp/termdeb-wrapd.pid", PREFIX);
            pidfile = pbuf;
        }
        if (pidfile) {
            lockfd = open(pidfile, O_CREAT | O_RDWR | O_CLOEXEC, 0644);
            if (lockfd >= 0) {
                if (flock(lockfd, LOCK_EX | LOCK_NB) != 0) {
                    /* Another instance holds the lock - done. */
                    return 0;
                }
                ftruncate(lockfd, 0);
                dprintf(lockfd, "%d\n", (int)getpid());
            }
        }
    }

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sa.sa_handler = on_child;
    sigaction(SIGCHLD, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    printf("termdeb-wrapd: starting (interval %ds) launcher=%s scanner=%s\n",
        interval, launcher_path ? launcher_path : "(none)",
        scanner_path ? scanner_path : "(none)");
    fflush(stdout);

    /* Build the full sweep root set: CLI dirs + defaults. */
    if (HOME_D) {
        add_root(HOME_D);
        add_glob_dirs(HOME_D, "transforms");
        {
            char buf[4096];
            snprintf(buf, sizeof(buf), "%s/.android", HOME_D);
            add_root(buf);
        }
    }
    if (UROOT) {
        char buf[4096];
        snprintf(buf, sizeof(buf), "%s/usr/local", UROOT); add_root(buf);
        snprintf(buf, sizeof(buf), "%s/usr/bin", UROOT);    add_root(buf);
        snprintf(buf, sizeof(buf), "%s/usr/sbin", UROOT);   add_root(buf);
        snprintf(buf, sizeof(buf), "%s/opt", UROOT);        add_root(buf);
    }

    /* Initial sweep before watching: the environment is wrapped from the
     * moment the daemon exists. */
    run_scanner(g_sweep_roots, g_nsweep);

    inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotify_fd >= 0) {
        /* The inotify watch set is deliberately surgical - the runtime
         * hotspots where x86_64 toolchain binaries appear (Gradle transform
         * extractions, .android, the guest /usr/local, and the discovered
         * SDK roots passed as CLI args). The full home tree is covered by
         * the periodic sweep instead, so a huge project tree can never
         * exhaust the kernel watch limit. The Gradle cache -> transforms
         * chain is armed FIRST: it is the critical race (AGP re-extracts
         * aapt2 into a wiped/re-created transforms dir mid-build), so it
         * must never be starved by the SDK watches. SDK roots are watched
         * shallowly (root-only watches, direct executables wrapped now). */
        if (HOME_D)
            arm_transforms_chain(HOME_D);
        for (i = 1; i < argc; i++) {
            if (strcmp(argv[i], "--") == 0) break;
            watch_sdk_dir(argv[i]);
        }
        if (HOME_D) {
            char buf[4096];
            snprintf(buf, sizeof(buf), "%s/.android", HOME_D);
            if (access(buf, F_OK) == 0) watch_dir_recursive(buf);
        }
        if (UROOT) {
            char buf[4096];
            snprintf(buf, sizeof(buf), "%s/usr/local", UROOT);
            if (access(buf, F_OK) == 0) watch_dir_recursive(buf);
        }
    } else {
        printf("termdeb-wrapd: inotify unavailable (%s) - periodic sweep only\n",
            strerror(errno));
        fflush(stdout);
    }

    /* Event loop with periodic sweep. */
    {
        time_t last_sweep = time(NULL);
        struct pollfd pfd;
        pfd.fd = inotify_fd;
        pfd.events = POLLIN;
        while (!g_stop) {
            int rv = poll(&pfd, 1, 1000);
            if (rv > 0 && (pfd.revents & POLLIN))
                handle_events();
            if (time(NULL) - last_sweep >= interval) {
                /* Self-heal the watch chain before each sweep: if the caches
                 * or transforms directories were re-created (cache clean) or
                 * a watch was lost, re-arm them so the next extraction is
                 * caught by inotify, not just by this sweep. */
                if (HOME_D)
                    arm_transforms_chain(HOME_D);
                run_scanner(g_sweep_roots, g_nsweep);
                last_sweep = time(NULL);
            }
        }
    }

    printf("termdeb-wrapd: stopping\n");
    fflush(stdout);
    return 0;
}
