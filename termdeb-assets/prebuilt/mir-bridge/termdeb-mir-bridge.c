/*
 * termdeb-mir-bridge - TermDeb Mir -> Android surface display bridge
 *
 * Runs INSIDE the packaged Debian trixie guest (under proot) as a Wayland client
 * of the Mir compositor. It:
 *
 *   1. captures the compositor output with the wlr-screencopy protocol and writes
 *      the pixels into a shared, memory-mapped framebuffer file that the TermDeb
 *      Android app also maps (via the file the host binds to /run/termdeb);
 *   2. reads input records from a local abstract AF_UNIX socket owned by the app
 *      and injects them into Mir with the Wayland virtual keyboard/pointer
 *      protocols.
 *
 * This is deliberately NOT VNC / a remote desktop: there is no network transport
 * and no frame encoding. Frames travel through local shared memory and input
 * through a local socket. See docs/lomiri-desktop-architecture.md.
 *
 * Shared framebuffer header (little-endian, 32 bytes), matching
 * LomiriDesktopDisplay.java:
 *   uint32 magic (0x42464454), version, width, height, stride, format,
 *          seq, flags
 *
 * Input record (little-endian, 24 bytes), matching LomiriDesktopDisplay.java:
 *   u8 type, u8 action, u16 reserved, i32 x, i32 y, u32 code, u32 modifiers
 *
 * Copyright (c) TermDeb Contributors
 * SPDX-License-Identifier: MIT
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include <wayland-client.h>
#include <xkbcommon/xkbcommon.h>

#include "wlr-screencopy.h"
#include "wlr-virtual-pointer.h"
#include "virtual-keyboard.h"

#define FB_MAGIC 0x42464454u
#define FB_VERSION 1u
#define FB_FORMAT_ARGB8888 1u
#define FB_HEADER_SIZE 32u

/* Header flags (offset 28). The Android side only presents a frame once the
   producer is ready and has written at least one, so an untouched (all zero)
   framebuffer is never mistaken for a rendered desktop. */
#define FB_FLAG_READY 0x1u    /* producer mapped the framebuffer and is running */
#define FB_FLAG_STALLED 0x2u  /* the compositor is not answering capture requests */
#define FB_FLAG_ERROR 0x4u    /* the producer gave up; see its log for the reason */

/* A screencopy request is only answered once the compositor has composed a
   frame. A request that is never answered would leave the bridge idle forever
   (and the app on a black screen), so the wait is bounded and the connection is
   re-established when it expires. */
#define CAPTURE_TIMEOUT_MS_DEFAULT 4000u
/* Consecutive unanswered requests before the shared header is marked stalled. */
#define CAPTURE_STALL_LIMIT 3u
/* Frames between progress log lines (the log is a diagnostic aid, not a trace). */
#define FRAME_LOG_INTERVAL 150u

#define INPUT_RECORD_SIZE 24u
#define INPUT_TYPE_TOUCH 1
#define INPUT_TYPE_KEY 2
#define INPUT_TYPE_TEXT 3

#define TOUCH_DOWN 0
#define TOUCH_MOVE 1
#define TOUCH_UP 2

#define MOD_CTRL 1
#define MOD_ALT 2
#define MOD_SHIFT 4

/* Linux input (evdev) codes used for modifier key injection. */
#define KEY_LEFTCTRL 29
#define KEY_LEFTALT 56
#define KEY_LEFTSHIFT 42
#define BTN_LEFT 0x110

struct bridge {
    struct wl_display *display;
    struct wl_registry *registry;
    struct wl_shm *shm;
    struct wl_output *output;
    struct wl_seat *seat;
    struct zwlr_screencopy_manager_v1 *screencopy;
    struct zwp_virtual_keyboard_manager_v1 *vk_manager;
    struct zwlr_virtual_pointer_manager_v1 *vp_manager;
    struct zwp_virtual_keyboard_v1 *keyboard;
    struct zwlr_virtual_pointer_v1 *pointer;

    struct wl_shm_pool *pool;
    struct wl_buffer *buffer;
    void *shm_data;
    size_t shm_size;
    uint32_t shm_format;
    uint32_t buf_width;
    uint32_t buf_height;
    uint32_t buf_stride;
    bool have_buffer;

    bool capture_pending;
    bool frame_ready;

    /* Watchdog state for unanswered capture requests. */
    uint64_t capture_started_ms;
    unsigned capture_timeout_ms;
    unsigned stalls;
    uint64_t frames_written;

    /* Current screencopy frame (an object per capture request). The manager is
       bound at version 3 when the compositor offers it, and then the copy
       request has to wait for the buffer_done event; on versions 1/2 it goes
       out as soon as the buffer event arrives. */
    struct zwlr_screencopy_frame_v1 *frame;
    bool copy_sent;
    bool y_invert;
    uint32_t sc_version;

    uint32_t serial;

    /* Target framebuffer */
    int fb_fd;
    uint8_t *fb_map;
    size_t fb_size;
    uint32_t width;
    uint32_t height;
    uint32_t seq;

    /* Input */
    int input_fd;
    char input_socket[108];
};

static void log_msg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("termdeb-mir-bridge: ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    fflush(stderr);
}

/* Set by setup_framebuffer; the header helpers write through it. */
static uint8_t *g_fb = NULL;

static uint64_t now_ms(void);

/* ------------------------------------------------------------------ framebuffer */

static void fb_write_u32(uint32_t off, uint32_t value) {
    /* Host is little-endian (ARM64); match the Android ByteOrder.LITTLE_ENDIAN reader. */
    if (g_fb) memcpy(g_fb + off, &value, sizeof(value));
}

static void fb_set_flag(uint32_t bit, bool on) {
    uint32_t flags;
    if (!g_fb) return;
    memcpy(&flags, g_fb + 28, sizeof(flags));
    fb_write_u32(28, on ? (flags | bit) : (flags & ~bit));
}

static int setup_framebuffer(struct bridge *b, const char *path, uint32_t width, uint32_t height) {
    size_t wanted = FB_HEADER_SIZE + (size_t)width * height * 4;
    b->fb_fd = open(path, O_RDWR);
    if (b->fb_fd < 0) {
        log_msg("cannot open framebuffer %s: %s", path, strerror(errno));
        return -1;
    }
    struct stat st;
    if (fstat(b->fb_fd, &st) != 0) {
        log_msg("fstat(%s) failed: %s", path, strerror(errno));
        return -1;
    }
    if ((size_t)st.st_size < wanted) {
        if (ftruncate(b->fb_fd, (off_t)wanted) != 0) {
            log_msg("ftruncate(%s) failed: %s", path, strerror(errno));
            return -1;
        }
    }
    b->fb_size = wanted;
    b->fb_map = mmap(NULL, wanted, PROT_READ | PROT_WRITE, MAP_SHARED, b->fb_fd, 0);
    if (b->fb_map == MAP_FAILED) {
        log_msg("mmap(%s) failed: %s", path, strerror(errno));
        return -1;
    }
    g_fb = b->fb_map;

    b->width = width;
    b->height = height;
    b->seq = 0;
    b->frame_ready = false;

    fb_write_u32(0, FB_MAGIC);
    fb_write_u32(4, FB_VERSION);
    fb_write_u32(8, width);
    fb_write_u32(12, height);
    fb_write_u32(16, width * 4);
    fb_write_u32(20, FB_FORMAT_ARGB8888);
    fb_write_u32(24, 0);
    fb_write_u32(28, FB_FLAG_READY);
    return 0;
}

/* ------------------------------------------------------------------ input socket */

static int connect_input_socket(struct bridge *b, const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace: the Android app owns a LocalServerSocket with this name. */
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, name, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));

    if (connect(fd, (struct sockaddr *)&addr, len) != 0) {
        close(fd);
        return -1;
    }
    b->input_fd = fd;
    return 0;
}

/* ------------------------------------------------------------------ virtual input */

static void inject_key(struct bridge *b, uint32_t code, uint32_t state) {
    if (!b->keyboard) return;
    zwp_virtual_keyboard_v1_key(b->keyboard, b->serial, code, state);
    wl_display_flush(b->display);
}

static void inject_modifiers(struct bridge *b, uint32_t mods, bool down) {
    if (down) {
        if (mods & MOD_CTRL) inject_key(b, KEY_LEFTCTRL, 1);
        if (mods & MOD_ALT) inject_key(b, KEY_LEFTALT, 1);
        if (mods & MOD_SHIFT) inject_key(b, KEY_LEFTSHIFT, 1);
    } else {
        if (mods & MOD_SHIFT) inject_key(b, KEY_LEFTSHIFT, 0);
        if (mods & MOD_ALT) inject_key(b, KEY_LEFTALT, 0);
        if (mods & MOD_CTRL) inject_key(b, KEY_LEFTCTRL, 0);
    }
}

static void inject_touch(struct bridge *b, int action, int32_t x, int32_t y) {
    if (!b->pointer) return;
    uint32_t t = (uint32_t)(now_ms());
    switch (action) {
        case TOUCH_DOWN:
            zwlr_virtual_pointer_v1_motion_absolute(b->pointer, t, (uint32_t)x, (uint32_t)y,
                                                    b->width, b->height);
            zwlr_virtual_pointer_v1_button(b->pointer, t, BTN_LEFT, 1);
            break;
        case TOUCH_MOVE:
            zwlr_virtual_pointer_v1_motion_absolute(b->pointer, t, (uint32_t)x, (uint32_t)y,
                                                    b->width, b->height);
            break;
        case TOUCH_UP:
            zwlr_virtual_pointer_v1_button(b->pointer, t, BTN_LEFT, 0);
            break;
        default:
            return;
    }
    zwlr_virtual_pointer_v1_frame(b->pointer);
    wl_display_flush(b->display);
}

static void inject_text(struct bridge *b, uint32_t codepoint) {
    /* Text injection uses the virtual keyboard: map the Unicode code point to a
     * keysym and press/release it. For ASCII this is the code point itself. */
    if (!b->keyboard || codepoint == 0) return;
    uint32_t keysym = codepoint;
    uint32_t code = keysym + 8; /* xkb: keycode = keysym + 8 offset for ASCII */
    inject_key(b, code, 1);
    inject_key(b, code, 0);
}

static void handle_input_record(struct bridge *b, const uint8_t *rec) {
    uint8_t type = rec[0];
    uint8_t action = rec[1];
    int32_t x, y;
    uint32_t code, mods;
    memcpy(&x, rec + 4, 4);
    memcpy(&y, rec + 8, 4);
    memcpy(&code, rec + 12, 4);
    memcpy(&mods, rec + 16, 4);

    switch (type) {
        case INPUT_TYPE_TOUCH:
            inject_touch(b, action, x, y);
            break;
        case INPUT_TYPE_KEY:
            inject_modifiers(b, mods, true);
            inject_key(b, code, action == 0 ? 1 : 0);
            inject_modifiers(b, mods, false);
            break;
        case INPUT_TYPE_TEXT:
            inject_text(b, code);
            break;
        default:
            break;
    }
}

/* ------------------------------------------------------------------ shm buffers */

static void create_shm_buffer(struct bridge *b, uint32_t width, uint32_t height, uint32_t stride,
                              uint32_t format) {
    size_t size = (size_t)stride * height;
    int fd = memfd_create("termdeb-bridge-shm", MFD_CLOEXEC);
    if (fd < 0) {
        /* memfd_create is not universally available (some proot/seccomp
           combinations refuse it). /dev/shm always is: the host script binds the
           app's tmp/shm directory onto it. Without this fallback a refused
           memfd turns into a silently black desktop: no buffer, no copy
           request, and the app never sees a frame. */
        char name[64];
        snprintf(name, sizeof(name), "/termdeb-bridge-%d", (int)getpid());
        fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) {
            shm_unlink(name);
            log_msg("memfd_create unavailable (%s); using /dev/shm instead", strerror(errno));
        }
    }
    if (fd < 0) {
        log_msg("ERROR: cannot create a shared memory buffer for the capture: %s",
                strerror(errno));
        fb_set_flag(FB_FLAG_ERROR, true);
        return;
    }
    if (ftruncate(fd, (off_t)size) != 0) {
        log_msg("ERROR: cannot size the capture buffer (%zu bytes): %s", size, strerror(errno));
        fb_set_flag(FB_FLAG_ERROR, true);
        close(fd);
        return;
    }
    void *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        log_msg("ERROR: cannot map the capture buffer (%zu bytes): %s", size, strerror(errno));
        fb_set_flag(FB_FLAG_ERROR, true);
        close(fd);
        return;
    }

    if (b->buffer) {
        wl_buffer_destroy(b->buffer);
        b->buffer = NULL;
    }
    if (b->shm_data) {
        munmap(b->shm_data, b->shm_size);
    }

    b->shm_data = data;
    b->shm_size = size;
    b->shm_format = format;
    b->buf_width = width;
    b->buf_height = height;
    b->buf_stride = stride;

    struct wl_shm_pool *pool = wl_shm_create_pool(b->shm, fd, (int32_t)size);
    b->buffer = wl_shm_pool_create_buffer(pool, 0, (int32_t)width, (int32_t)height,
                                          (int32_t)stride, (int)format);
    wl_shm_pool_destroy(pool);
    close(fd);
    b->have_buffer = true;
}

static void copy_frame_to_fb(struct bridge *b) {
    if (!b->have_buffer || !b->fb_map) return;

    uint8_t *src = (uint8_t *)b->shm_data;
    uint8_t *dst = b->fb_map + FB_HEADER_SIZE;
    uint32_t copy_w = b->buf_width < b->width ? b->buf_width : b->width;
    uint32_t copy_h = b->buf_height < b->height ? b->buf_height : b->height;
    uint32_t dst_stride = b->width * 4;

    for (uint32_t row = 0; row < copy_h; row++) {
        /* Mir always reports ZWLR_SCREENCOPY_FRAME_V1_FLAGS_Y_INVERT and writes
           the capture bottom-up, so the rows have to be flipped back. */
        uint32_t src_row = b->y_invert ? (b->buf_height - 1u - row) : row;
        const uint8_t *s = src + (size_t)src_row * b->buf_stride;
        uint8_t *d = dst + (size_t)row * dst_stride;

        for (uint32_t col = 0; col < copy_w; col++) {
            /* wl_shm ARGB8888 is B,G,R,A in memory; Bitmap.Config.ARGB_8888 (and
               copyPixelsFromBuffer) wants R,G,B,A. Alpha is forced opaque because
               this is composited display content presented on an opaque surface. */
            d[col * 4 + 0] = s[col * 4 + 2];
            d[col * 4 + 1] = s[col * 4 + 1];
            d[col * 4 + 2] = s[col * 4 + 0];
            d[col * 4 + 3] = 0xFF;
        }
    }

    b->seq++;
    /* Publish the pixels before the sequence number: the Android side treats a
       changed seq as "the pixels are complete" and must never read a frame that
       was only half written. */
    __sync_synchronize();
    fb_write_u32(24, b->seq);
    fb_set_flag(FB_FLAG_STALLED, false);
    if (b->stalls) b->stalls = 0;
}

/* Sampled "is there anything on screen" check, used only for the progress log
   lines so a black desktop can be told apart from a desktop that never
   captured. */
static uint32_t sampled_nonblack_pixels(struct bridge *b) {
    const uint8_t *p;
    size_t total, step, off;
    uint32_t nonblack = 0;

    if (!b->fb_map) return 0;
    p = b->fb_map + FB_HEADER_SIZE;
    total = (size_t)b->width * b->height * 4;
    step = total / 4096;
    if (step < 4) step = 4;
    for (off = 0; off + 3 < total; off += step) {
        if (p[off] || p[off + 1] || p[off + 2]) nonblack++;
    }
    return nonblack;
}

/* ------------------------------------------------------------------ screencopy */

/* Ask the compositor to render the captured output into our wl_shm buffer. This
   is what actually makes the compositor produce a frame: without it the
   protocol never advances past the "buffer" event and no "ready" ever arrives,
   so the Android side would only ever see the empty framebuffer (a black
   screen). Version 3 clients have to wait for "buffer_done"; version 1/2 clients
   must send the request as soon as they have created the buffer. */
static void sc_send_copy(struct bridge *b) {
    if (!b->frame || b->copy_sent || !b->buffer) return;
    zwlr_screencopy_frame_v1_copy(b->frame, b->buffer);
    b->copy_sent = true;
}

static void sc_finish(struct bridge *b) {
    if (b->frame) {
        zwlr_screencopy_frame_v1_destroy(b->frame);
        b->frame = NULL;
    }
    b->copy_sent = false;
    b->capture_pending = false;
}

static void sc_buffer(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t format,
                      uint32_t width, uint32_t height, uint32_t stride) {
    struct bridge *b = data;
    b->frame = frame;
    b->copy_sent = false;
    create_shm_buffer(b, width, height, stride, format);
    if (b->sc_version < 3) sc_send_copy(b);
}

static void sc_buffer_done(void *data, struct zwlr_screencopy_frame_v1 *frame) {
    (void)frame;
    struct bridge *b = data;
    sc_send_copy(b);
}

static void sc_flags(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t flags) {
    (void)frame;
    struct bridge *b = data;
    b->y_invert = (flags & ZWLR_SCREENCOPY_FRAME_V1_FLAGS_Y_INVERT) != 0;
}

static void sc_ready(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t tv_sec_hi,
                     uint32_t tv_sec_lo, uint32_t tv_nsec) {
    (void)frame;
    (void)tv_sec_hi;
    (void)tv_sec_lo;
    (void)tv_nsec;
    struct bridge *b = data;
    copy_frame_to_fb(b);
    b->frame_ready = true;
    b->frames_written++;
    if (b->frames_written == 1 || (b->frames_written % FRAME_LOG_INTERVAL) == 0) {
        uint32_t nonblack = sampled_nonblack_pixels(b);
        log_msg("frame %u captured (%ux%u, %u sampled pixels, %u non-black)",
                (unsigned)b->frames_written, b->buf_width, b->buf_height, 4096u, nonblack);
        if (nonblack == 0)
            log_msg("WARNING: the captured frame is entirely black; the compositor "
                    "has no desktop content to show yet");
    }
    sc_finish(b);
}

static void sc_failed(void *data, struct zwlr_screencopy_frame_v1 *frame) {
    (void)frame;
    struct bridge *b = data;
    log_msg("screencopy: the compositor could not capture the output");
    sc_finish(b);
}

static void sc_damage(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t x, uint32_t y,
                      uint32_t width, uint32_t height) {
    (void)data;
    (void)frame;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
}

static void sc_linux_dmabuf(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t format,
                            uint32_t width, uint32_t height) {
    (void)data;
    (void)frame;
    (void)format;
    (void)width;
    (void)height;
}


static const struct zwlr_screencopy_frame_v1_listener sc_frame_listener = {
    .buffer = sc_buffer,
    .flags = sc_flags,
    .ready = sc_ready,
    .failed = sc_failed,
    .damage = sc_damage,
    .linux_dmabuf = sc_linux_dmabuf,
    .buffer_done = sc_buffer_done,
};


static void request_capture(struct bridge *b) {
    if (b->capture_pending || b->frame || !b->screencopy || !b->output) return;
    struct zwlr_screencopy_frame_v1 *frame =
        zwlr_screencopy_manager_v1_capture_output(b->screencopy, 0, b->output);
    if (!frame) return;
    zwlr_screencopy_frame_v1_add_listener(frame, &sc_frame_listener, b);
    b->capture_pending = true;
    b->capture_started_ms = now_ms();
}

/* ------------------------------------------------------------------ registry */

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
                            const char *interface, uint32_t version) {
    struct bridge *b = data;

    if (strcmp(interface, wl_shm_interface.name) == 0) {
        b->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, wl_output_interface.name) == 0) {
        if (!b->output)
            b->output = wl_registry_bind(registry, name, &wl_output_interface, version < 3 ? version : 3);
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        if (!b->seat) b->seat = wl_registry_bind(registry, name, &wl_seat_interface, 5);
    } else if (strcmp(interface, zwlr_screencopy_manager_v1_interface.name) == 0) {
        /* Bind the highest version we understand, but no higher than the
           compositor offers: the copy request is sent after buffer_done from
           version 3 on, and immediately after buffer below that. */
        b->sc_version = version < 3 ? version : 3;
        b->screencopy = wl_registry_bind(registry, name, &zwlr_screencopy_manager_v1_interface,
                                         b->sc_version);
    } else if (strcmp(interface, zwp_virtual_keyboard_manager_v1_interface.name) == 0) {
        b->vk_manager = wl_registry_bind(registry, name, &zwp_virtual_keyboard_manager_v1_interface, 1);
    } else if (strcmp(interface, zwlr_virtual_pointer_manager_v1_interface.name) == 0) {
        b->vp_manager = wl_registry_bind(registry, name, &zwlr_virtual_pointer_manager_v1_interface, 2);
    }
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

/* ------------------------------------------------------------------ virtual keyboard keymap */

static void install_keymap(struct bridge *b) {
    if (!b->vk_manager || !b->seat) return;

    struct xkb_context *ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *keymap =
        ctx ? xkb_keymap_new_from_names(ctx, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS) : NULL;
    if (!keymap) {
        log_msg("could not build xkb keymap; keyboard injection unavailable");
        if (ctx) xkb_context_unref(ctx);
        return;
    }
    char *str = xkb_keymap_get_as_string(keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
    size_t len = str ? strlen(str) + 1 : 0;
    int fd = memfd_create("termdeb-keymap", MFD_CLOEXEC);
    if (str && fd >= 0 && ftruncate(fd, (off_t)len) == 0) {
        void *m = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (m != MAP_FAILED) {
            memcpy(m, str, len);
            munmap(m, len);
            b->keyboard = zwp_virtual_keyboard_manager_v1_create_virtual_keyboard(b->vk_manager, b->seat);
            zwp_virtual_keyboard_v1_keymap(b->keyboard, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)len);
        }
    }
    if (fd >= 0) close(fd);
    free(str);
    xkb_keymap_unref(keymap);
    xkb_context_unref(ctx);
}

/* ------------------------------------------------------------------ main */

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

static void usage(const char *argv0) {
    fprintf(stderr,
        "Usage: %s --fb <path> [--input-socket <name>] [--width N] [--height N]\n", argv0);
}

/* Attach to the compositor named by WAYLAND_DISPLAY and bind everything the
   bridge needs from it. Called once at startup and again by the watchdog below
   when a capture request is never answered. */
static int connect_display(struct bridge *b) {
    b->display = wl_display_connect(NULL);
    if (!b->display) {
        log_msg("cannot connect to the compositor (is WAYLAND_DISPLAY set?)");
        return -1;
    }

    b->registry = wl_display_get_registry(b->display);
    wl_registry_add_listener(b->registry, &registry_listener, b);
    wl_display_roundtrip(b->display);
    wl_display_roundtrip(b->display);

    if (!b->screencopy) {
        log_msg("ERROR: compositor does not expose wlr-screencopy; cannot capture the desktop");
        return -1;
    }
    if (!b->output) {
        log_msg("ERROR: no wl_output found");
        return -1;
    }

    install_keymap(b);
    if (b->vp_manager && b->seat) {
        b->pointer = zwlr_virtual_pointer_manager_v1_create_virtual_pointer(b->vp_manager, b->seat);
    }
    return 0;
}

/* Drop every proxy that belonged to the connection that was just disconnected. */
static void forget_display(struct bridge *b) {
    /* The capture mapping belonged to the old connection's buffers: release it so
       repeated reconnects cannot leak a full frame each. */
    if (b->shm_data) {
        munmap(b->shm_data, b->shm_size);
        b->shm_data = NULL;
        b->shm_size = 0;
    }

    b->display = NULL;
    b->registry = NULL;
    b->shm = NULL;
    b->output = NULL;
    b->seat = NULL;
    b->screencopy = NULL;
    b->vk_manager = NULL;
    b->vp_manager = NULL;
    b->keyboard = NULL;
    b->pointer = NULL;
    b->frame = NULL;
    b->buffer = NULL;
    b->have_buffer = false;
    b->copy_sent = false;
    b->capture_pending = false;
    b->capture_started_ms = 0;
}

int main(int argc, char **argv) {
    struct bridge b;
    memset(&b, 0, sizeof(b));
    b.fb_fd = -1;
    b.input_fd = -1;

    const char *fb_path = NULL;
    const char *input_socket = "termdeb-desktop-input";
    uint32_t width = 1280, height = 800;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--fb") == 0 && i + 1 < argc) fb_path = argv[++i];
        else if (strcmp(argv[i], "--input-socket") == 0 && i + 1 < argc) input_socket = argv[++i];
        else if (strcmp(argv[i], "--width") == 0 && i + 1 < argc) width = (uint32_t)atoi(argv[++i]);
        else if (strcmp(argv[i], "--height") == 0 && i + 1 < argc) height = (uint32_t)atoi(argv[++i]);
        else { usage(argv[0]); return 2; }
    }
    if (!fb_path) { usage(argv[0]); return 2; }
    strncpy(b.input_socket, input_socket, sizeof(b.input_socket) - 1);

    if (setup_framebuffer(&b, fb_path, width, height) != 0) return 1;

    /* The wait for an answered capture request is bounded; see CAPTURE_TIMEOUT_MS_DEFAULT. */
    b.capture_timeout_ms = CAPTURE_TIMEOUT_MS_DEFAULT;
    const char *timeout_env = getenv("TERMDEB_DESKTOP_CAPTURE_TIMEOUT_MS");
    if (timeout_env && *timeout_env) {
        long value = strtol(timeout_env, NULL, 10);
        if (value > 0 && value < 3600000) b.capture_timeout_ms = (unsigned)value;
    }

    if (connect_display(&b) != 0) {
        fb_set_flag(FB_FLAG_ERROR, true);
        return 1;
    }

    if (connect_input_socket(&b, input_socket) != 0) {
        log_msg("WARNING: input socket '%s' not available yet; input disabled until it connects",
                input_socket);
    }

    log_msg("bridge running: %ux%u -> %s", width, height, fb_path);

    /* Frame pacing: capture at most ~30 fps to bound CPU on software rendering. */
    const uint64_t frame_interval_ms = 33;
    uint64_t last_capture = 0;

    while (true) {
        uint64_t now = now_ms();

        /* Watchdog: the compositor only answers a capture request once it has
           composed a frame. A request that is never answered leaves this loop
           idle forever, which the app cannot tell apart from a crashed desktop:
           it just stays black. Abandon the connection and start over instead. */
        if (b.capture_pending && b.capture_started_ms &&
            now - b.capture_started_ms > b.capture_timeout_ms) {
            b.stalls++;
            if (b.stalls == 1 || (b.stalls % 10) == 0)
                log_msg("WARNING: the compositor did not answer a capture request within %u ms "
                        "(%u in a row); re-establishing the connection",
                        b.capture_timeout_ms, b.stalls);
            if (b.stalls == CAPTURE_STALL_LIMIT) {
                fb_set_flag(FB_FLAG_STALLED, true);
                log_msg("WARNING: still no frame after %u unanswered capture requests; the "
                        "compositor is not producing one", b.stalls);
            }
            wl_display_disconnect(b.display);
            forget_display(&b);
            if (connect_display(&b) != 0) {
                log_msg("ERROR: could not re-attach to the compositor");
                fb_set_flag(FB_FLAG_ERROR, true);
                break;
            }
            log_msg("re-attached to the compositor");
            last_capture = 0;
            continue;
        }

        while (wl_display_prepare_read(b.display) != 0) {
            wl_display_dispatch_pending(b.display);
        }

        struct pollfd pfds[2];
        int nfds = 1;
        pfds[0].fd = wl_display_get_fd(b.display);
        pfds[0].events = POLLIN;
        if (b.input_fd >= 0) {
            pfds[1].fd = b.input_fd;
            pfds[1].events = POLLIN;
            nfds = 2;
        }

        if (wl_display_flush(b.display) < 0 && errno != EAGAIN) {
            log_msg("ERROR: display connection lost");
            fb_set_flag(FB_FLAG_ERROR, true);
            break;
        }

        int ret = poll(pfds, nfds, 10);
        if (ret > 0 && (pfds[0].revents & POLLIN)) {
            wl_display_read_events(b.display);
            wl_display_dispatch_pending(b.display);
        } else if (ret > 0) {
            wl_display_cancel_read(b.display);
        } else {
            wl_display_cancel_read(b.display);
        }

        if (b.input_fd < 0) {
            if (connect_input_socket(&b, input_socket) == 0)
                log_msg("input socket connected");
        } else if (ret > 0 && nfds == 2 && (pfds[1].revents & POLLIN)) {
            uint8_t buf[INPUT_RECORD_SIZE * 32];
            /* Drain whatever is available, then process whole records. */
            for (int i = 0; i < 64; i++) {
                ssize_t n = read(b.input_fd, buf, sizeof(buf));
                if (n <= 0) {
                    if (n == 0) {
                        log_msg("input socket closed by the app");
                        close(b.input_fd);
                        b.input_fd = -1;
                    }
                    break;
                }
                for (ssize_t off = 0; off + (ssize_t)INPUT_RECORD_SIZE <= n; off += INPUT_RECORD_SIZE)
                    handle_input_record(&b, buf + off);
            }
        }

        if (!b.capture_pending && now - last_capture >= frame_interval_ms) {
            if (b.serial < UINT32_MAX) b.serial++;
            request_capture(&b);
            last_capture = now;
        }

        if (b.frame_ready) {
            b.frame_ready = false;
        }
    }

    if (b.shm_data) munmap(b.shm_data, b.shm_size);
    munmap(b.fb_map, b.fb_size);
    if (b.fb_fd >= 0) close(b.fb_fd);
    wl_display_disconnect(b.display);
    return 0;
}
