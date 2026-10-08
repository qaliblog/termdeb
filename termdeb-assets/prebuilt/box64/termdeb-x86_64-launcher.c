/*
 * termdeb-x86_64-launcher - ARM64 launcher that transparently runs an x86_64
 * ELF binary through box64.
 *
 * Purpose
 * -------
 * TermDeb's transparent Box64 layer replaces every x86_64 ELF executable with
 * this small ARM64 binary (the original moves to <dir>/.termdeb-x86_64/). Any
 * exec mechanism - execve, execv, execvp, posix_spawn, JDK's jspawnhelper, a
 * direct kernel exec - then sees an ARM64 binary and works natively. This
 * launcher simply re-execs box64 with the real binary, preserving argv and the
 * environment. Native ARM64 binaries are never touched by the layer.
 *
 * This is the fix for the previous LD_PRELOAD execve-interposer
 * (libbox64auto.so): an interposer only sees execve/execv made through glibc's
 * public symbols, so Java 17's ProcessBuilder (which execs its own
 * jspawnhelper, which then calls execvp internally) bypassed it and the ARM64
 * kernel rejected the x86_64 binary. On-disk wrapping cannot be bypassed: the
 * kernel never even sees the x86_64 ELF.
 *
 * Build (Android NDK, static - no libc dependency at runtime):
 *
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
 *       -O2 -static -s -o termdeb-x86_64-launcher termdeb-x86_64-launcher.c
 *
 * License: MIT (same as TermDeb)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <errno.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* box64 bound into the guest by termdeb-debian / termdeb-native. */
static const char BOX64_PATH[] = "/usr/local/bin/box64";
/* directory (next to this launcher) holding the original x86_64 binaries. */
static const char BOX64_LIB_DIR[] = ".termdeb-x86_64";
/* default x86_64 library search used by the guest's provisioned rootfs. */
static const char BOX64_LD_LIBRARY_PATH_DEFAULT[] =
    "/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/lib64:/usr/lib64";

int main(int argc, char** argv) {
    char real[PATH_MAX];
    char cmd[PATH_MAX];
    char **newargv;
    int i;

    if (argc < 1 || argv[0] == NULL || argv[0][0] == '\0') {
        fprintf(stderr, "termdeb-x86_64-launcher: cannot determine argv[0]\n");
        return 126;
    }

    /* Resolve argv[0] through symlinks: exec can arrive via a symlink whose
     * directory has no .termdeb-x86_64/ (e.g. cmdline-tools/latest/bin/aapt2
     * -> ../../build-tools/34.0.0/aapt2). */
    if (realpath(argv[0], real) == NULL) {
        if (strchr(argv[0], '/') == NULL) {
            /* Bare command name: search PATH like execvp. */
            const char* path = getenv("PATH");
            if (path != NULL) {
                char* p = strdup(path);
                char* tok;
                int found = 0;
                for (tok = strtok(p, ":"); tok != NULL; tok = strtok(NULL, ":")) {
                    char try[PATH_MAX];
                    snprintf(try, sizeof(try), "%s/%s", tok, argv[0]);
                    if (realpath(try, real) != NULL) { found = 1; break; }
                }
                free(p);
                if (found) goto resolved;
            }
        }
        fprintf(stderr, "termdeb-x86_64-launcher: cannot resolve '%s': %s\n",
            argv[0], strerror(errno));
        return 126;
    }
resolved:;

    /* real = "<dir>/<base>"; the real binary lives at "<dir>/.termdeb-x86_64/<base>". */
    char* base = strrchr(real, '/');
    if (base == NULL || base[1] == '\0') {
        fprintf(stderr, "termdeb-x86_64-launcher: bad path '%s'\n", real);
        return 126;
    }
    *base = '\0';
    base++;
    if (snprintf(cmd, sizeof(cmd), "%s/%s/%s", real, BOX64_LIB_DIR, base) >= (int)sizeof(cmd)) {
        fprintf(stderr, "termdeb-x86_64-launcher: path too long\n");
        return 126;
    }

    /* Default the x86_64 library path when the caller didn't set it, so the
     * emulated loader finds glibc no matter how this binary was launched. */
    if (getenv("BOX64_LD_LIBRARY_PATH") == NULL)
        setenv("BOX64_LD_LIBRARY_PATH", BOX64_LD_LIBRARY_PATH_DEFAULT, 1);

    newargv = (char**)malloc((size_t)(argc + 2) * sizeof(char*));
    if (newargv == NULL) {
        fprintf(stderr, "termdeb-x86_64-launcher: out of memory\n");
        return 126;
    }
    newargv[0] = (char*)BOX64_PATH;
    newargv[1] = cmd;
    for (i = 1; i < argc; i++)
        newargv[i + 1] = argv[i];
    newargv[argc + 1] = NULL;

    execv(BOX64_PATH, newargv);

    /* Only reached on failure. */
    fprintf(stderr, "termdeb-x86_64-launcher: cannot exec %s (%s): %s\n",
        BOX64_PATH, cmd, strerror(errno));
    return 126;
}
