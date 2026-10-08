/*
 * libbox64auto.so - LD_PRELOAD execve interposer for TermDeb's Debian proot guest.
 *
 * Purpose
 * -------
 * When an x86_64 (Intel/AMD) ELF binary is executed inside the ARM64 Debian
 * guest, this library transparently re-routes the exec through box64 so the
 * binary runs under emulation automatically - no `box64 ./program` prefix
 * needed. Native ARM64 binaries and scripts are passed through untouched.
 *
 * Why LD_PRELOAD instead of binfmt_misc or proot -q
 * --------------------------------------------------
 * - binfmt_misc requires mounting a kernel filesystem: impossible under proot
 *   (no real root).
 * - proot -q re-inserts the runner for every guest execve and splices -0/argv,
 *   which makes a wrapper script recurse forever (verified empirically and in
 *   proot's execve.c).
 * - LD_PRELOAD intercepts execve in the calling (guest, glibc) process, which
 *   covers `./binary`, PATH commands and most fork+exec chains. Static or
 *   non-glibc callers are not covered, which is an acceptable edge case.
 *
 * Build
 * -----
 * Built with the Android NDK clang for aarch64, -nostdlib: the library uses
 * raw Linux syscalls only, so it has no libc dependency and loads into both
 * the guest's glibc processes and box64 itself.
 *
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
 *       -O2 -fPIC -shared -nostdlib -fno-stack-protector \
 *       -o libbox64auto.so box64auto.c
 *
 * License: MIT (same as TermDeb)
 */

/* ---- Linux syscall numbers ---- */
#ifdef __aarch64__
#define __NR_openat   56
#define __NR_read     63
#define __NR_close    57
#define __NR_execve   221
#else
/* x86_64 (host-side testing of the routing logic only) */
#define __NR_openat   257
#define __NR_read     0
#define __NR_close    3
#define __NR_execve   59
#endif

#define AT_FDCWD     -100
#define O_RDONLY     0

#define EM_X86_64    62   /* ELF e_machine value for x86-64 */

/* Path of box64 inside the guest (bound by termdeb-debian). */
static const char BOX64_PATH[] = "/usr/local/bin/box64";

typedef long ssize_t;
typedef unsigned long size_t;

/* Raw syscall helper (no libc). */
static long syscall3(long n, long a, long b, long c) {
#ifdef __aarch64__
    register long x0 asm("x0") = a;
    register long x1 asm("x1") = b;
    register long x2 asm("x2") = c;
    register long x8 asm("x8") = n;
    asm volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return x0;
#else
    /* x86_64 (for host-side testing of the routing logic only) */
    register long rax asm("rax") = n;
    register long rdi asm("rdi") = a;
    register long rsi asm("rsi") = b;
    register long rdx asm("rdx") = c;
    asm volatile("syscall"
                 : "+a"(rax)
                 : "D"(rdi), "S"(rsi), "d"(rdx)
                 : "rcx", "r11", "memory");
    return rax;
#endif
}

static long sys_openat(const char *path) { return syscall3(__NR_openat, AT_FDCWD, (long)path, O_RDONLY); }
static long sys_read(long fd, void *buf, size_t count) { return syscall3(__NR_read, fd, (long)buf, (long)count); }
static long sys_close(long fd) { return syscall3(__NR_close, fd, 0, 0); }
static long sys_execve(const char *path, char *const argv[], char *const envp[]) {
    return syscall3(__NR_execve, (long)path, (long)argv, (long)envp);
}

/* glibc's exported errno accessor. We call it (rather than referencing the
 * errno data symbol) because errno is thread-local and a plain extern int
 * reference cannot be resolved safely from a -nostdlib object. Used to report
 * execve failures accurately (bash prints e.g. "No such file or directory"
 * instead of a bogus "Success"). */
extern int *__errno_location(void);

/* Convert a raw syscall return value into the libc execve convention:
 * 0 / positive -> success (never happens for execve, which only returns on error)
 * negative     -> -1 with errno set to -rc
 */
static int execve_result(long rc) {
    if (rc < 0) {
        *__errno_location() = (int)-rc;
        return -1;
    }
    return (int)rc;
}

/*
 * Return 1 if `path` is an ELF64 file with e_machine == EM_X86_64.
 * Returns 0 for anything else (missing file, script, ARM64 binary, ...).
 */
static int is_x86_64_elf(const char *path) {
    unsigned char hdr[20];
    long fd = sys_openat(path);
    if (fd < 0)
        return 0;
    long n = sys_read(fd, hdr, sizeof(hdr));
    sys_close(fd);
    if (n < 5)
        return 0;
    /* ELF magic */
    if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F')
        return 0;
    /* EI_CLASS == ELFCLASS64 */
    if (hdr[4] != 2)
        return 0;
    /* e_machine (little-endian u16 at offset 18) */
    unsigned short machine = (unsigned short)(hdr[18] | (hdr[19] << 8));
    return machine == EM_X86_64;
}

/* Compare the name part of an env string (up to '=') with `name`. */
static int env_name_is(const char *entry, const char *name) {
    const char *e = entry;
    const char *n = name;
    while (*n) {
        if (*e != *n)
            return 0;
        e++;
        n++;
    }
    return *e == '=';
}

/*
 * Re-exec `path` through box64, keeping the original argv[1..] arguments.
 * argv layout becomes: [box64, path, argv[1], ..., argv[n-1], NULL]
 * (stack-allocated, no malloc needed).
 *
 * LD_PRELOAD is stripped from the environment: box64 forwards it to the
 * emulated x86_64 process, whose loader cannot load an aarch64 library and
 * would print "cannot pre-load" warnings (and could fail). The interposer is
 * only needed in the ARM64 calling process.
 *
 * On success execve never returns; on failure this returns -1 with errno set.
 */
static int route_through_box64(const char *path, char *const argv[], char *const envp[]) {
    int argc = 0;
    if (argv) {
        while (argv[argc])
            argc++;
    }
    char **newargv = (char **)__builtin_alloca((size_t)(argc + 2) * sizeof(char *));
    int i;
    newargv[0] = (char *)BOX64_PATH;
    newargv[1] = (char *)path;
    for (i = 1; i < argc; i++)
        newargv[i + 1] = argv[i];
    newargv[argc + 1] = 0;

    int envc = 0;
    if (envp) {
        while (envp[envc])
            envc++;
    }
    char **newenvp = (char **)__builtin_alloca((size_t)(envc + 1) * sizeof(char *));
    int j = 0;
    for (i = 0; i < envc; i++) {
        if (env_name_is(envp[i], "LD_PRELOAD"))
            continue; /* strip */
        newenvp[j++] = envp[i];
    }
    newenvp[j] = 0;

    /* On success execve never returns; on failure this returns -1 with errno set. */
    return execve_result(sys_execve(BOX64_PATH, newargv, newenvp));
}

/*
 * Interposed execve(2). Used by bash and most programs for `./binary` and
 * PATH-resolved commands.
 */
int execve(const char *path, char *const argv[], char *const envp[]) {
    if (is_x86_64_elf(path))
        return route_through_box64(path, argv, envp);
    return execve_result(sys_execve(path, argv, envp));
}

/*
 * Interposed execv(3) - like execve with the current environment.
 */
int execv(const char *path, char *const argv[]) {
    extern char **environ;
    if (is_x86_64_elf(path))
        return route_through_box64(path, argv, environ);
    return execve_result(sys_execve(path, argv, environ));
}
