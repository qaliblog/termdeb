/*
 * termdeb-scan64 - TermDeb transparent Box64 layer scanner.
 *
 * Walks directories and replaces every x86_64 (Intel/AMD) ELF *executable*
 * with the ARM64 termdeb-x86_64-launcher (the original moves to
 * <dir>/.termdeb-x86_64/<name>). Native ARM64 binaries, scripts and shared
 * libraries are never touched.
 *
 * Freshness tracking lives in a sidecar state file (argv[2]) OUTSIDE the
 * scanned trees, never inside them: an early version dropped a
 * `.termdeb-wrap64.stamp` file into every visited directory, which broke
 * tools that consume whole directories (AGP hands the merged_res output
 * directory to `aapt2 link`, which choked on the stamp). The layer must
 * never write anything into a user or project directory except the wrap
 * itself, so the stamp approach was replaced by a sidecar mapping of
 * dir -> mtime that skips unchanged directories on later scans.
 *
 * Only true executables are wrapped: e_type must be ET_EXEC, or ET_DYN
 * (PIE) with a PT_INTERP program header. x86_64 shared objects (.so) have
 * no interpreter and are loadable libraries, not programs - wrapping one
 * would corrupt it, so they are skipped.
 *
 * Implemented as a compiled binary instead of shell because a bash scanner
 * spawns dd/od per file, and process creation under proot's ptrace is two
 * orders of magnitude too slow. This uses plain syscalls only, so a full
 * scan of the home tree takes a second or two even inside proot.
 *
 * Usage: termdeb-scan64 <launcher-path> <state-file> <dir>...
 *
 * Build (Android NDK, static):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
 *       -O2 -static -s -o termdeb-scan64 termdeb-scan64.c
 *
 * License: MIT (same as TermDeb)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <time.h>
#include <sys/file.h>

#define ELF_MAGIC_64 "\x7f\x45\x4c\x46\x02"  /* \x7f E L F + ELFCLASS64 */
#define EM_X86_64    62                       /* e_machine value for x86-64 */
#define ET_EXEC      2
#define ET_DYN       3
#define PT_INTERP    3
#define ARCHIVE_DIR  ".termdeb-x86_64"
#define X86_64_HI    (EM_X86_64 >> 8)         /* 0 */

static const char *launcher_path = NULL;

/* ---------------- ELF inspection ---------------- */

/* True if <path> is an x86_64 ELF *executable* (ET_EXEC, or ET_DYN/PIE with
 * a PT_INTERP header). Shared libraries (.so) are never wrapped. */
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
    /* e_machine is a little-endian u16 at offset 18. */
    if (hdr[18] != EM_X86_64 || hdr[19] != X86_64_HI) { close(fd); return 0; }
    etype = (uint16_t)(hdr[16] | (hdr[17] << 8));
    if (etype == ET_EXEC) { close(fd); return 1; }      /* static executable */
    if (etype != ET_DYN) { close(fd); return 0; }        /* not an executable */
    /* PIE: require PT_INTERP. */
    phoff   = (uint64_t)hdr[32] | ((uint64_t)hdr[33] << 8) | ((uint64_t)hdr[34] << 16) |
              ((uint64_t)hdr[35] << 24) | ((uint64_t)hdr[36] << 32) | ((uint64_t)hdr[37] << 40) |
              ((uint64_t)hdr[38] << 48) | ((uint64_t)hdr[39] << 56);
    phentsize = (uint16_t)(hdr[54] | (hdr[55] << 8));
    phnum     = (uint16_t)(hdr[56] | (hdr[57] << 8));
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

/* ---------------- sidecar freshness state ---------------- */

struct dent {
    char *dir;
    long long mtime;   /* st_mtim in nanoseconds */
};

static struct dent *g_old = NULL;   /* loaded previous state (sorted) */
static size_t g_nold = 0;
static struct dent *g_new = NULL;   /* collected current state */
static size_t g_nnew = 0, g_capnew = 0;
static const char *g_state_path = NULL;

static int cmp_dent(const void *a, const void *b) {
    return strcmp(((const struct dent *)a)->dir, ((const struct dent *)b)->dir);
}

static void load_state(void) {
    FILE *f = fopen(g_state_path, "r");
    char line[8192];
    if (!f) return;
    while (fgets(line, sizeof(line), f) != NULL) {
        char *sp = strchr(line, ' ');
        if (!sp) continue;
        *sp = '\0';
        long long mt = atoll(sp + 1);
        struct dent *nd = realloc(g_old, (g_nold + 1) * sizeof(*nd));
        if (!nd) break;
        g_old = nd;
        g_old[g_nold].dir = strdup(line);
        g_old[g_nold].mtime = mt;
        g_nold++;
    }
    fclose(f);
    if (g_nold > 1)
        qsort(g_old, g_nold, sizeof(*g_old), cmp_dent);
}

static int state_dir_mtime(const char *dir, long long *out) {
    size_t lo = 0, hi = g_nold;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        int c = strcmp(g_old[mid].dir, dir);
        if (c == 0) { *out = g_old[mid].mtime; return 1; }
        if (c < 0) lo = mid + 1; else hi = mid;
    }
    return 0;
}

static void record_dir(const char *dir, long long mtime) {
    struct dent *nd;
    if (g_nnew == g_capnew) {
        size_t nc = g_capnew ? g_capnew * 2 : 1024;
        struct dent *nn = realloc(g_new, nc * sizeof(*nn));
        if (!nn) return;
        g_new = nn;
        g_capnew = nc;
    }
    nd = &g_new[g_nnew];
    nd->dir = strdup(dir);
    nd->mtime = mtime;
    if (nd->dir) g_nnew++;
}

static void save_state(void) {
    FILE *f;
    size_t i;
    if (!g_state_path || g_nnew == 0) return;
    qsort(g_new, g_nnew, sizeof(*g_new), cmp_dent);
    f = fopen(g_state_path, "w");
    if (!f) return;
    for (i = 0; i < g_nnew; i++) {
        /* Unique-ify: keep the first (sorted) entry per dir. */
        if (i > 0 && strcmp(g_new[i].dir, g_new[i - 1].dir) == 0) continue;
        fprintf(f, "%s %lld\n", g_new[i].dir, g_new[i].mtime);
    }
    fclose(f);
}

/* ---------------- wrapping ---------------- */

static int copy_launcher(const char *dest) {
    int in = open(launcher_path, O_RDONLY | O_CLOEXEC);
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

/* ---------------- directory walk ---------------- */

static void scan_dir(const char *dir) {
    DIR *d;
    struct dirent *de;
    struct stat ds;
    long long recorded = -1;
    int fresh = 0;
    char archdir[4096], path[4096];

    if (stat(dir, &ds) != 0) return;
    if (state_dir_mtime(dir, &recorded))
        fresh = (recorded == (long long)ds.st_mtim.tv_sec * 1000000000LL + ds.st_mtim.tv_nsec);
    record_dir(dir, (long long)ds.st_mtim.tv_sec * 1000000000LL + ds.st_mtim.tv_nsec);

    d = opendir(dir);
    if (!d) return;

    /* Single readdir pass: wrap x86_64 executables, recurse into subdirectories.
     * The archive dir is created lazily only where a binary is actually
     * wrapped - never in every scanned directory - so user/project trees
     * stay byte-for-byte untouched except for the wrap itself. */
    while ((de = readdir(d)) != NULL) {
        struct stat st;
        if (de->d_name[0] == '.') continue;          /* hidden incl. archives */
        snprintf(path, sizeof(path), "%s/%s", dir, de->d_name);
        if (de->d_type == DT_DIR) {
            scan_dir(path);
            continue;
        }
        if (de->d_type != DT_REG && de->d_type != DT_UNKNOWN) continue;
        if (!fresh) {
            if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) continue;
            if ((st.st_mode & 0111) == 0) continue;  /* not executable */
            /* Never wrap a file modified within the last 2 seconds: the
             * periodic walk may catch a binary MID-extraction (Gradle writes
             * transform outputs in place), and wrapping a partial file leaves
             * the archive truncated so box64 dies on it. The watcher's
             * IN_CLOSE_WRITE handles complete files immediately; anything
             * missed is picked up by the next sweep. */
            if ((long long)st.st_mtim.tv_sec > time(NULL) - 2) continue;
            {
                char arch[4096], tmp[4096];
                int lockfd;
                /* Serialize with the watcher: both can race the same freshly
                 * extracted binary; without a lock the second wrapper would
                 * clobber the first's archive with a launcher and destroy
                 * the real binary. Re-check under the lock. */
                snprintf(archdir, sizeof(archdir), "%s/%s", dir, ARCHIVE_DIR);
                mkdir(archdir, 0700); /* ignore errors - the rename below will fail too */
                lockfd = open(archdir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
                if (lockfd >= 0) flock(lockfd, LOCK_EX);
                if (!is_x86_64_executable(path)) {
                    if (lockfd >= 0) { flock(lockfd, LOCK_UN); close(lockfd); }
                    continue;
                }
                snprintf(arch, sizeof(arch), "%s/%s", archdir, de->d_name);
                if (access(arch, F_OK) == 0) {      /* already wrapped */
                    if (lockfd >= 0) { flock(lockfd, LOCK_UN); close(lockfd); }
                    continue;
                }
                /* rename original -> archive, launcher (via temp) -> original */
                if (rename(path, arch) == 0) {
                    snprintf(tmp, sizeof(tmp), "%s/.launcher.tmp.%d", archdir, (int)getpid());
                    if (copy_launcher(tmp) != 0 || rename(tmp, path) != 0) {
                        unlink(tmp);
                        rename(arch, path); /* roll back */
                    } else {
                        char ts[32];
                        time_t now = time(NULL);
                        struct tm tmv;
                        localtime_r(&now, &tmv);
                        strftime(ts, sizeof(ts), "%H:%M:%S", &tmv);
                        printf("termdeb-wrap64: [%s] wrapped %s (x86_64 -> box64)\n", ts, path);
                        fflush(stdout);
                    }
                }
                if (lockfd >= 0) { flock(lockfd, LOCK_UN); close(lockfd); }
            }
        }
    }
    closedir(d);
}

int main(int argc, char **argv) {
    int i;
    if (argc < 4) {
        fprintf(stderr, "usage: %s <launcher-path> <state-file> <dir>...\n", argv[0]);
        return 2;
    }
    launcher_path = argv[1];
    g_state_path = argv[2];
    if (access(launcher_path, X_OK) != 0) {
        fprintf(stderr, "termdeb-scan64: launcher missing at %s\n", launcher_path);
        return 1;
    }
    load_state();
    for (i = 3; i < argc; i++)
        scan_dir(argv[i]);
    save_state();
    return 0;
}
