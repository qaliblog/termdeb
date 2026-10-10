/*
 * termdeb-mir-eintr-shim - keep Mir's nested-Wayland platform alive across EINTR
 *
 * Why this exists
 * ---------------
 * Lomiri's own Mir server (qtmir's "mirserver" QPA) has to nest on a real
 * compositor, because Mir 2.20's offscreen "virtual" display platform exposes no
 * display sync groups at all (src/platforms/virtual/display.cpp has an empty
 * for_each_display_sync_group()), so qtmir never gets a render target and dies
 * with a null dereference in Screen::makeCurrent(). The guest therefore runs a
 * Mir server on mir:virtual and nests the Lomiri Mir server on it with
 * mir:wayland.
 *
 * Mir's nested-Wayland display platform then polls its host connection with
 * poll(2) and treats *every* failure as fatal (src/platforms/wayland/display.cpp):
 *
 *     if (poll(fds, indices, -1) == -1)
 *     {
 *         wl_display_cancel_read(display);
 *         BOOST_THROW_EXCEPTION((std::system_error{errno, ...,
 *             "Failed to wait for event"}));
 *     }
 *
 * EINTR is not an error: it just means a signal was delivered to a thread with a
 * handler installed. In this guest the Lomiri process has such handlers (GLib's
 * SIGCHLD handler, for instance) and it does spawn short-lived helper children,
 * so a child exiting aborts the whole desktop:
 *
 *   *** got signal 17 (Child exited)
 *   *** poll() interrupted (EINTR)
 *   Mir fatal error: Critical error in Wayland platform:
 *       Failed to wait for event: Interrupted system call
 *
 * This is still present in Mir's main branch, so it cannot be fixed by upgrading.
 * Rather than patch Mir, this shim retries poll() on EINTR - the behaviour Mir
 * intended, and what the rest of the process (Qt, GLib, D-Bus) already expects.
 *
 * Scope
 * -----
 * Only calls made from Mir's nested-Wayland platform module are touched: the
 * return address is checked against the shared object the caller lives in, so no
 * other poll() user in the process changes behaviour. The timeout is recomputed
 * after each retry so a wait never lasts longer than the caller asked for.
 *
 * Built at provisioning time by termdeb-assets/prebuilt/provision-desktop.sh and
 * preloaded only for the Lomiri shell by termdeb-desktop-session.
 *
 * Copyright (c) TermDeb Contributors
 * SPDX-License-Identifier: MIT
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <time.h>

#define MIR_WAYLAND_MODULE "graphics-wayland"

static int (*next_poll)(struct pollfd *, nfds_t, int);

static long elapsed_ms(struct timespec const *from, struct timespec const *to)
{
    return (long)(to->tv_sec - from->tv_sec) * 1000L +
           (long)(to->tv_nsec - from->tv_nsec) / 1000000L;
}

/* True when the caller of poll() lives in Mir's nested-Wayland platform. */
static int caller_is_mir_wayland_platform(void)
{
    Dl_info info;
    void *caller = __builtin_extract_return_addr(__builtin_return_address(0));

    if (!caller || !dladdr(caller, &info) || !info.dli_fname)
        return 0;
    return strstr(info.dli_fname, MIR_WAYLAND_MODULE) != NULL;
}

int poll(struct pollfd *fds, nfds_t nfds, int timeout)
{
    struct timespec start;
    int remaining = timeout;

    if (!next_poll)
        next_poll = (int (*)(struct pollfd *, nfds_t, int))dlsym(RTLD_NEXT, "poll");
    if (!next_poll || !caller_is_mir_wayland_platform())
        return next_poll(fds, nfds, timeout);

    clock_gettime(CLOCK_MONOTONIC, &start);

    for (;;) {
        int result = next_poll(fds, nfds, remaining);
        if (result >= 0 || errno != EINTR)
            return result;

        /* A zero timeout must not be retried: the caller wants a poll. */
        if (timeout == 0)
            return -1;

        if (timeout > 0) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            remaining = timeout - (int)elapsed_ms(&start, &now);
            if (remaining <= 0)
                return 0; /* the wait is over, report a timeout */
        }
        /* timeout < 0: wait again, indefinitely, as asked. */
    }
}
