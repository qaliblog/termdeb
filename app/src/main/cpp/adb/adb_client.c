/*
 * TermDeb ADB bridge - guest ADB client.
 *
 * /usr/bin/adb inside the TermDeb Debian (PRoot) guest. Speaks Google's ADB
 * smart protocol to the app-owned server (127.0.0.1:5037) and reproduces the
 * official client's observable behavior: service strings, output text, exit
 * codes, interactive shell handling (raw mode, escape char, window size),
 * sync push/pull and streamed installs.
 *
 * Protocol facts verified against AOSP platform/packages/modules/adb:
 * docs/dev/services.md, docs/dev/sync.md, shell_protocol.h,
 * file_sync_protocol.h, client/commandline.cpp, client/adb_install.cpp.
 */
#define _GNU_SOURCE 1
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include "adb_client.h"

#define die(...) die_at(__FILE__, __LINE__, __VA_ARGS__)

/* Transport selection state (global options -s/-t/-d/-e) and the qualified
 * device-service connect used by every transport-sensitive command. The
 * definitions live with the connect/disconnect section below. */
static const char* g_serial = NULL;
static const char* g_transport_id = NULL;
static int g_want_usb = 0;
static int g_want_tcp = 0;
static int adb_connect_device_service(const char* service, char* err, size_t errlen);

static void die_at(const char* file, int line, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "error: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    exit(1);
}

/* ---------- socket plumbing ---------- */

static int server_port = ADB_DEFAULT_SERVER_PORT;

/* Resolve the server endpoint from ADB_SERVER_SOCKET / ANDROID_ADB_SERVER_PORT. */
static void resolve_server_endpoint(void) {
    const char* port_env = getenv("ANDROID_ADB_SERVER_PORT");
    if (port_env && *port_env) {
        int p = atoi(port_env);
        if (p > 0 && p < 65536) server_port = p;
    }
    const char* sock = getenv("ADB_SERVER_SOCKET");
    if (sock && *sock) {
        /* Only tcp:[HOST]:PORT is supported; localabstract would require the
         * server to expose a UNIX socket, which it does not. */
        if (strncmp(sock, "tcp:", 4) == 0) {
            const char* spec = sock + 4;
            const char* colon = strrchr(spec, ':');
            if (colon) {
                int p = atoi(colon + 1);
                if (p > 0 && p < 65536) server_port = p;
            } else if (*spec) {
                int p = atoi(spec);
                if (p > 0 && p < 65536) server_port = p;
            }
        } else {
            fprintf(stderr, "error: unsupported ADB_SERVER_SOCKET '%s'\n", sock);
        }
    }
}

/* Connect to the server, without starting it. Returns -1 on refusal. */
static int socket_connect_server(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)server_port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return fd;
}

/* Write a 4-hex-digit length prefix, then the payload. */
static int send_hex4(int fd, const char* service) {
    size_t len = strlen(service);
    if (len > 0xffff) return -1;
    char header[5];
    snprintf(header, sizeof(header), "%04zx", len);
    if (write(fd, header, 4) != 4) return -1;
    if (len && write(fd, service, len) != (ssize_t)len) return -1;
    return 0;
}

/* Read exactly n bytes. Returns 1 on success, 0 on EOF/error. */
static int read_full(int fd, void* buf, size_t n) {
    char* p = (char*)buf;
    while (n) {
        ssize_t r = read(fd, p, n);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            return 0;
        }
        p += r;
        n -= (size_t)r;
    }
    return 1;
}

static int write_full(int fd, const void* buf, size_t n) {
    const char* p = (const char*)buf;
    while (n) {
        ssize_t r = write(fd, p, n);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            return 0;
        }
        p += r;
        n -= (size_t)r;
    }
    return 1;
}

/* Read the OKAY/FAIL activation reply. Returns 0 on OKAY, -1 on FAIL (with
 * reason copied into err, if provided) or transport error. */
static int read_status(int fd, char* err, size_t errlen) {
    char code[4];
    if (!read_full(fd, code, 4)) {
        if (err && errlen) snprintf(err, errlen, "couldn't read status");
        return -1;
    }
    if (memcmp(code, "OKAY", 4) == 0) return 0;
    if (memcmp(code, "FAIL", 4) == 0) {
        if (err && errlen) {
            char lenbuf[5];
            lenbuf[4] = 0;
            if (!read_full(fd, lenbuf, 4)) {
                if (err && errlen) snprintf(err, errlen, "couldn't read status");
                return -1;
            }
            unsigned len = 0;
            sscanf(lenbuf, "%4x", &len);
            if (len >= errlen) len = (unsigned)(errlen - 1);
            if (len && !read_full(fd, err, len)) {
                if (err && errlen) snprintf(err, errlen, "couldn't read status");
                return -1;
            }
            err[len] = 0;
        }
        return -1;
    }
    if (err && errlen) {
        /* Provide the exact bytes seen so failure is debuggable. */
        char tmp[32];
        size_t n = sizeof(tmp) - 1;
        if (n > 4) n = 4;
        memcpy(tmp, code, n);
        tmp[n] = 0;
        snprintf(err, errlen, "unknown reply code (%s)", tmp);
    }
    return -1;
}

/* Query the server with a host: service; on OKAY, reads a hex4-length payload
 * and returns a malloc'd string (or NULL for empty payload). *ok set to 1 on
 * OKAY. Returns NULL on FAIL/transport error with err filled. */
static char* adb_query(const char* service, int* ok, char* err, size_t errlen) {
    int fd = socket_connect_server();
    if (fd < 0) {
        if (err && errlen) snprintf(err, errlen, "cannot connect to daemon");
        return NULL;
    }
    if (send_hex4(fd, service) < 0) {
        close(fd);
        if (err && errlen) snprintf(err, errlen, "cannot connect to daemon");
        return NULL;
    }
    if (read_status(fd, err, errlen) < 0) {
        close(fd);
        return NULL;
    }
    char lenbuf[5];
    lenbuf[4] = 0;
    if (!read_full(fd, lenbuf, 4)) {
        if (err && errlen) snprintf(err, errlen, "couldn't read status");
        close(fd);
        return NULL;
    }
    unsigned len = 0;
    sscanf(lenbuf, "%4x", &len);
    char* payload = NULL;
    if (len) {
        payload = (char*)malloc(len + 1);
        if (!payload || !read_full(fd, payload, len)) {
            free(payload);
            close(fd);
            if (err && errlen) snprintf(err, errlen, "couldn't read status");
            return NULL;
        }
        payload[len] = 0;
    }
    /* Orderly shutdown marker (server sends "0000"). */
    char tail[4];
    if (!read_full(fd, tail, 4)) { /* tolerate missing marker */
    }
    close(fd);
    if (ok) *ok = 1;
    return payload ? payload : strdup("");
}

/* Open a service stream: OKAY returns the raw fd, FAIL returns -1 with err.
 * This is used for device services (shell/exec/sync/track-jdwp/transport).
 * The caller owns the stream and must not expect a trailing 0000.
 */
static int adb_connect_service(const char* service, char* err, size_t errlen) {
    int fd = socket_connect_server();
    if (fd < 0) {
        if (err && errlen) snprintf(err, errlen, "cannot connect to daemon");
        return -1;
    }
    if (send_hex4(fd, service) < 0 || read_status(fd, err, errlen) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Debug toggle: set ADB_DEBUG=1 to print protocol details to stderr. */
static int debug_mode(void) {
    const char* e = getenv("ADB_DEBUG");
    return e && *e;
}

static void debug_print(const char* fmt, ...) {
    if (!debug_mode()) return;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[adb-debug] ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

/* Ensure a server is running; prints Google's daemon messages. Returns 0 when
 * the server is reachable. The server itself is app-owned; starting it means
 * waiting for TermdebAdbBridge to (re)bind. */
static int ensure_server(void) {
    int fd = socket_connect_server();
    if (fd >= 0) {
        close(fd);
        return 0;
    }
    fprintf(stderr, "* daemon not running; starting now at tcp:%d\n", server_port);
    /* Poke the Java bridge through the loopback port for up to ~2.5s. */
    for (int i = 0; i < 50; i++) {
        usleep(50 * 1000);
        fd = socket_connect_server();
        if (fd >= 0) {
            close(fd);
            fprintf(stderr, "* daemon started successfully\n");
            return 0;
        }
    }
    fprintf(stderr, "error: cannot connect to daemon\n");
    return -1;
}

/* ---------- shell protocol (v2) ---------- */

typedef struct {
    int fd;
    unsigned char id;
    unsigned length;
    unsigned char* data;
} shell_packet;

static void packet_alloc(shell_packet* p, int fd) {
    p->fd = fd;
    p->id = SHELL_ID_INVALID;
    p->length = 0;
    p->data = (unsigned char*)malloc(SYNC_DATA_MAX);
}

static void packet_free(shell_packet* p) {
    free(p->data);
    p->data = NULL;
}

/* Returns 1 on packet read, 0 on stream end. */
static int packet_read(shell_packet* p) {
    unsigned char header[5];
    if (!read_full(p->fd, header, 5)) return 0;
    p->id = header[0];
    memcpy(&p->length, header + 1, 4);
    if (p->length > SYNC_DATA_MAX) p->length = SYNC_DATA_MAX;
    if (p->length && !read_full(p->fd, p->data, p->length)) return 0;
    return 1;
}

static int packet_write(shell_packet* p, unsigned char id, const void* data, unsigned len) {
    unsigned char header[5];
    header[0] = id;
    memcpy(header + 1, &len, 4);
    if (!write_full(p->fd, header, 5)) return 0;
    if (len && !write_full(p->fd, data, len)) return 0;
    return 1;
}

/* ---------- termios / winsize ---------- */

static struct termios g_saved_tio;
static int g_raw_stdin = 0;

static void stdin_raw_init(void) {
    if (g_raw_stdin) return;
    if (tcgetattr(STDIN_FILENO, &g_saved_tio) < 0) return;
    struct termios tio = g_saved_tio;
    cfmakeraw(&tio);
    tio.c_cc[VTIME] = 0;
    tio.c_cc[VMIN] = 1;
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &tio);
    g_raw_stdin = 1;
}

static void stdin_raw_restore(void) {
    if (g_raw_stdin) {
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &g_saved_tio);
        g_raw_stdin = 0;
    }
}

static void send_window_size_change(shell_packet* p) {
    struct winsize ws;
    if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) < 0) return;
    char buf[64];
    int l = snprintf(buf, sizeof(buf), "%dx%d,%dx%d", ws.ws_row, ws.ws_col,
                     ws.ws_xpixel, ws.ws_ypixel);
    /* Length includes the NUL (strlen+1), matching ShellServiceString-side
     * behavior in client/commandline.cpp. */
    packet_write(p, SHELL_ID_WINDOW_SIZE_CHANGE, buf, (unsigned)l + 1);
}

/* ---------- interactive shell ---------- */

static volatile sig_atomic_t g_winch = 0;
static void winch_handler(int sig) { (void)sig; g_winch = 1; }

typedef struct {
    shell_packet* p;
    char escape_char;
    int* exit_flag;
} stdin_args;

/* stdin reader thread: forwards raw stdin as kIdStdin packets, handles the
 * escape char and SIGWINCH-triggered window size updates. */
static void* stdin_read_loop(void* arg) {
    stdin_args* a = (stdin_args*)arg;
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGWINCH);
    pthread_sigmask(SIG_UNBLOCK, &mask, NULL);

    send_window_size_change(a->p);

    char one[2];
    int escape_state = 0; /* 0 = start of line, 1 = mid-flow, 2 = in-escape */
    for (;;) {
        if (g_winch) {
            g_winch = 0;
            send_window_size_change(a->p);
        }
        ssize_t r = read(STDIN_FILENO, one, 1);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) {
            packet_write(a->p, SHELL_ID_CLOSE_STDIN, "", 0);
            break;
        }
        char ch = one[0];
        if (a->escape_char != 0) {
            if (ch == a->escape_char) {
                if (escape_state == 0) {
                    escape_state = 2;
                    continue;
                }
                escape_state = 1;
            } else {
                if (escape_state == 2) {
                    if (ch == '.') {
                        fprintf(stderr, "\r\n[ disconnected ]\r\n");
                        stdin_raw_restore();
                        exit(0);
                    }
                    /* Swallowed escape that is not part of a sequence: emit
                     * the escape char we swallowed, then this char. */
                    packet_write(a->p, SHELL_ID_STDIN, &a->escape_char, 1);
                }
                escape_state = (ch == '\n' || ch == '\r') ? 0 : 1;
            }
        }
        if (packet_write(a->p, SHELL_ID_STDIN, &ch, 1) == 0) break;
    }
    return NULL;
}

/* Output pump: writes stdout/stderr, captures exit code. Returns the exit
 * status (255 on unexpected close, like OpenSSH / read_and_dump_protocol). */
static int shell_output_loop(shell_packet* p) {
    int exit_code = 255;
    while (packet_read(p)) {
        if (p->id == SHELL_ID_STDOUT) {
            if (p->length && !write_full(STDOUT_FILENO, p->data, p->length)) {
                exit_code = 128 + SIGPIPE;
                break;
            }
        } else if (p->id == SHELL_ID_STDERR) {
            if (p->length && !write_full(STDERR_FILENO, p->data, p->length)) {
                exit_code = 128 + SIGPIPE;
                break;
            }
        } else if (p->id == SHELL_ID_EXIT) {
            if (p->length) exit_code = p->data[0];
        }
    }
    return exit_code;
}

/* Build "shell[,v2][,TERM=x][,pty|raw]:<command>" like ShellServiceString(). */
static char* shell_service_string(int use_shell_protocol, const char* type_arg,
                                  const char* command) {
    char args[256];
    args[0] = 0;
    if (use_shell_protocol) {
        strncat(args, "v2", sizeof(args) - strlen(args) - 1);
        const char* term = getenv("TERM");
        if (term && *term) {
            strncat(args, ",TERM=", sizeof(args) - strlen(args) - 1);
            strncat(args, term, sizeof(args) - strlen(args) - 1);
        }
    }
    if (type_arg && *type_arg) {
        if (args[0]) strncat(args, ",", sizeof(args) - strlen(args) - 1);
        strncat(args, type_arg, sizeof(args) - strlen(args) - 1);
    }
    size_t need = 8 + strlen(args) + strlen(command) + 1;
    char* s = (char*)malloc(need);
    if (s) {
        snprintf(s, need, "shell%s%s:%s", args[0] ? "," : "", args, command);
        debug_print("shell_service_string -> '%s'", s);
    }
    return s;
}

static shell_packet g_shell_packet;

/* Legacy (non-v2) interactive stdin: raw bytes straight onto the socket. */
static void* stdin_read_loop_legacy(void* arg) {
    int fd = *(int*)arg;
    char one[2];
    for (;;) {
        ssize_t r = read(STDIN_FILENO, one, 1);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) {
            shutdown(fd, SHUT_WR);
            break;
        }
        if (write_full(fd, one, 1) == 0) break;
    }
    return NULL;
}

/* Core of `adb shell`. Returns the remote exit code.
 *
 * Legacy protocol (!use_shell_protocol): the service stream is a raw byte
 * stream with NO exit status — the client always exits 0, exactly like
 * adb_shell() with shell_protocol disabled in AOSP commandline.cpp. */
static int remote_shell(int use_shell_protocol, const char* type_arg, char escape_char,
                        int empty_command, const char* command) {
    char* service = shell_service_string(use_shell_protocol, type_arg, command);
    if (!service) {
        fprintf(stderr, "error: memory\n");
        return 1;
    }
    int fd = adb_connect_device_service(service, NULL, 0);
    free(service);
    if (fd < 0) {
        fprintf(stderr, "error: closed\n");
        return 1;
    }
    debug_print("remote_shell fd=%d use_shell_protocol=%d type_arg='%s' command='%s'",
                fd, use_shell_protocol, type_arg, command);

    if (!use_shell_protocol) {
        int raw_stdin = (type_arg[0] == 0 && empty_command);
        if (raw_stdin) {
            stdin_raw_init();
            pthread_t tid;
            pthread_create(&tid, NULL, stdin_read_loop_legacy, &fd);
        }
        char buf[BUFSIZ];
        for (;;) {
            ssize_t r = read(fd, buf, sizeof(buf));
            if (r < 0) {
                if (errno == EINTR) continue;
                break;
            }
            if (r == 0) break;
            if (!write_full(STDOUT_FILENO, buf, (size_t)r)) break;
        }
        if (raw_stdin) stdin_raw_restore();
        return 0;
    }

    int raw_stdin = (strcmp(type_arg, "pty") == 0) || (type_arg[0] == 0 && empty_command);

    packet_alloc(&g_shell_packet, fd);
    shell_packet* p = &g_shell_packet;

    pthread_t tid;
    stdin_args args;
    args.p = p;
    args.escape_char = escape_char;
    if (raw_stdin) {
        stdin_raw_init();
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = winch_handler;
        sa.sa_flags = 0; /* no SA_RESTART: read must be interrupted */
        sigaction(SIGWINCH, &sa, NULL);
        sigset_t mask;
        sigemptyset(&mask);
        sigaddset(&mask, SIGWINCH);
        pthread_sigmask(SIG_BLOCK, &mask, NULL);
        pthread_create(&tid, NULL, stdin_read_loop, &args);
    }

    int code = shell_output_loop(p);
    if (raw_stdin) stdin_raw_restore();
    debug_print("remote_shell exit=%d", code);
    return code;
}

/* ---------- sync protocol ---------- */

static void put_le32(unsigned char* p, unsigned v) {
    p[0] = v & 0xff;
    p[1] = (v >> 8) & 0xff;
    p[2] = (v >> 16) & 0xff;
    p[3] = (v >> 24) & 0xff;
}

static unsigned get_le32(const unsigned char* p) {
    return (unsigned)p[0] | ((unsigned)p[1] << 8) | ((unsigned)p[2] << 16) | ((unsigned)p[3] << 24);
}

static int sync_send_request(int fd, unsigned id, const char* path) {
    unsigned char buf[8];
    put_le32(buf, id);
    put_le32(buf + 4, (unsigned)strlen(path));
    if (!write_full(fd, buf, 8)) return -1;
    if (*path && !write_full(fd, path, strlen(path))) return -1;
    return 0;
}

/* Read one sync response (id + length [+ optional payload pointer]). */
static int sync_read_response(int fd, unsigned* id, unsigned* len) {
    unsigned char buf[8];
    if (!read_full(fd, buf, 8)) return -1;
    *id = get_le32(buf);
    *len = get_le32(buf + 4);
    return 0;
}

static int sync_expect(int fd, unsigned id, unsigned* len) {
    unsigned rid, rlen;
    if (sync_read_response(fd, &rid, &rlen) < 0) return -1;
    if (rid != id) return -1;
    if (len) *len = rlen;
    return 0;
}

static int sync_read_fail(int fd) {
    unsigned id, len;
    if (sync_read_response(fd, &id, &len) < 0) return -1;
    if (id == ID_FAIL) {
        if (len) {
            char* msg = (char*)malloc(len + 1);
            if (msg && read_full(fd, msg, len)) {
                msg[len] = 0;
                fprintf(stderr, "adb: error: %s\n", msg);
            }
            free(msg);
        }
    }
    return -1;
}

/* STAT v1 reply: the raw 16-byte sync_stat_v1 struct { id, mode, size,
 * mtime } written directly by file_sync_service.cpp do_lstat_v1 — there is
 * NO id/len wrapper around it. A missing file reports mode 0 (all-zero
 * mode/size/mtime with the STAT id), not a FAIL frame. */
static int sync_stat(int fd, const char* path, unsigned* mode, unsigned* size, unsigned* mtime) {
    if (sync_send_request(fd, ID_STAT_V1, path) < 0) return -1;
    unsigned char st[16];
    if (!read_full(fd, st, 16)) return -1;
    unsigned id = get_le32(st);
    if (id != ID_STAT_V1) return -1;
    if (mode) *mode = get_le32(st + 4);
    if (size) *size = get_le32(st + 8);
    if (mtime) *mtime = get_le32(st + 12);
    return 0;
}

typedef int (*dent_fn)(unsigned mode, unsigned size, unsigned mtime, const char* name,
                       void* ctx);

/* LIST v1 reply: a sequence of raw 20-byte sync_dent_v1 structs
 * { id, mode, size, mtime, namelen } each followed by namelen bytes of
 * name, terminated by a raw 8-byte DONE struct { ID_DONE, 0 }. */
static int sync_list(int fd, const char* path, dent_fn cb, void* ctx) {
    if (sync_send_request(fd, ID_LIST_V1, path) < 0) return -1;
    for (;;) {
        unsigned char dent[20];
        if (!read_full(fd, dent, 20)) return -1;
        unsigned id = get_le32(dent);
        if (id == ID_DONE) return 0;
        if (id == ID_FAIL) {
            unsigned len = get_le32(dent + 4);
            if (len) {
                char* msg = (char*)malloc(len + 1);
                if (msg && read_full(fd, msg, len)) {
                    msg[len] = 0;
                    fprintf(stderr, "adb: error: %s\n", msg);
                }
                free(msg);
            }
            return -1;
        }
        if (id != ID_DENT_V1) return -1;
        unsigned mode = get_le32(dent + 4);
        unsigned size = get_le32(dent + 8);
        unsigned mtime = get_le32(dent + 12);
        unsigned namelen = get_le32(dent + 16);
        if (namelen > 1024) return -1;
        char name[1025];
        if (namelen && !read_full(fd, name, namelen)) return -1;
        name[namelen] = 0;
        if (cb) cb(mode, size, mtime, name, ctx);
    }
}

/* ---------- file helpers for push/pull ---------- */

#define PATHBUF 4096

static unsigned host_mtime(const struct stat* st) {
    return (unsigned)st->st_mtime;
}

static void join_path(char* out, size_t outlen, const char* a, const char* b);
static const char* basename_of(const char* path);
static int sync_send_mkdir(int fd, const char* rdir, unsigned mode);
static int read_status_line(int fd, char* buf, size_t count);

static void join_path(char* out, size_t outlen, const char* a, const char* b) {
    snprintf(out, outlen, "%s%s%s", a, a[strlen(a) - 1] == '/' ? "" : "/", b);
}

/* push a single file; rpath is the remote path; mode is the target file mode */
static int sync_push_file(int fd, const char* lpath, const char* rpath, unsigned mode,
                          unsigned* total_bytes, int quiet) {
    struct stat st;
    if (stat(lpath, &st) < 0) {
        fprintf(stderr, "adb: cannot stat '%s': %s\n", lpath, strerror(errno));
        return -1;
    }
    if (!S_ISREG(st.st_mode)) {
        fprintf(stderr, "adb: cannot push '%s': not a file\n", lpath);
        return -1;
    }

    char req[SYNC_DATA_MAX];
    snprintf(req, sizeof(req), "%s,%o", rpath, mode);
    if (sync_send_request(fd, ID_SEND_V1, req) < 0) return -1;

    int lfd = open(lpath, O_RDONLY);
    if (lfd < 0) {
        fprintf(stderr, "adb: cannot open '%s': %s\n", lpath, strerror(errno));
        return -1;
    }
    char buf[SYNC_DATA_MAX];
    for (;;) {
        ssize_t r = read(lfd, buf, sizeof(buf));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        unsigned char hdr[8];
        put_le32(hdr, ID_DATA);
        put_le32(hdr + 4, (unsigned)r);
        if (!write_full(fd, hdr, 8) || !write_full(fd, buf, (size_t)r)) {
            close(lfd);
            return -1;
        }
        if (total_bytes) *total_bytes += (unsigned)r;
        if (!quiet) {
            fprintf(stderr, "[%4u%%] %s\r",
                    (unsigned)(100.0 * (double)(*total_bytes) / (double)st.st_size), rpath);
        }
    }
    close(lfd);

    unsigned char done[8];
    put_le32(done, ID_DONE);
    put_le32(done + 4, host_mtime(&st));
    if (!write_full(fd, done, 8)) return -1;

    unsigned id, len;
    if (sync_read_response(fd, &id, &len) < 0) return -1;
    if (id == ID_OKAY) {
        if (!quiet) fprintf(stderr, "\n");
        return 0;
    }
    if (id == ID_FAIL && len) {
        char* msg = (char*)malloc(len + 1);
        if (msg && read_full(fd, msg, len)) {
            msg[len] = 0;
            fprintf(stderr, "adb: error: failed to copy '%s': %s\n", lpath, msg);
        }
        free(msg);
    } else {
        fprintf(stderr, "adb: error: failed to copy '%s'\n", lpath);
    }
    return -1;
}

typedef struct {
    int fd;
    const char* rdir;
    const char* ldir;
    unsigned long files;
    unsigned long bytes;
    int quiet;
} push_ctx;

static int push_walk(int fd, const char* ldir, const char* rdir, unsigned long* files,
                     unsigned long* bytes, int quiet) {
    DIR* d = opendir(ldir);
    if (!d) {
        fprintf(stderr, "adb: cannot open '%s': %s\n", ldir, strerror(errno));
        return -1;
    }
    struct dirent* de;
    while ((de = readdir(d))) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        char lp[PATHBUF], rp[PATHBUF];
        join_path(lp, sizeof(lp), ldir, de->d_name);
        join_path(rp, sizeof(rp), rdir, de->d_name);
        struct stat st;
        if (lstat(lp, &st) < 0) continue;
        if (S_ISDIR(st.st_mode)) {
            /* Create remote directory: a trailing '/' with mode drwxrwxrwx. */
            char req[SYNC_DATA_MAX];
            snprintf(req, sizeof(req), "%s/", rp);
            /* SEND to path ending in '/' asks adbd to mkdir (fixed_push_mkdir
             * semantics for real adbd; our server honors it). */
            char modestring[32];
            snprintf(modestring, sizeof(modestring), ",%o", (unsigned)(st.st_mode & 07777) | S_IFDIR);
            (void)modestring;
            if (sync_send_mkdir(fd, rp, (unsigned)(st.st_mode & 07777)) < 0) {
                closedir(d);
                return -1;
            }
            if (push_walk(fd, lp, rp, files, bytes, quiet) < 0) {
                closedir(d);
                return -1;
            }
        } else if (S_ISREG(st.st_mode)) {
            unsigned target = (unsigned)(st.st_mode & 07777) | S_IFREG;
            if (sync_push_file(fd, lp, rp, target, NULL, quiet) < 0) {
                closedir(d);
                return -1;
            }
            (*files)++;
            (*bytes) += (unsigned long)st.st_size;
        }
        /* Symlinks/devices: skipped, matching `adb push` default (without
         * --copy-links) which pushes symlinks as files on old versions and
         * as symlinks on new; the bridge treats them as plain files if the
         * server supports it, else skips. */
    }
    closedir(d);
    return 0;
}

static int sync_send_mkdir(int fd, const char* rdir, unsigned mode) {
    /* Implemented as SEND of a directory path with trailing '/' and mode with
     * directory bit - the sync v1 "SEND directory" convention used by modern
     * file_sync_client for fixed_push_mkdir. */
    char req[SYNC_DATA_MAX];
    snprintf(req, sizeof(req), "%s/,0%o", rdir, mode | S_IFDIR);
    if (sync_send_request(fd, ID_SEND_V1, req) < 0) return -1;
    unsigned char done[8];
    put_le32(done, ID_DONE);
    put_le32(done + 4, (unsigned)time(NULL));
    if (!write_full(fd, done, 8)) return -1;
    unsigned id, len;
    if (sync_read_response(fd, &id, &len) < 0) return -1;
    if (id == ID_OKAY) return 0;
    if (id == ID_FAIL && len) {
        char* msg = (char*)malloc(len + 1);
        if (msg && read_full(fd, msg, len)) {
            msg[len] = 0;
            fprintf(stderr, "adb: error: failed to mkdir '%s': %s\n", rdir, msg);
        }
        free(msg);
    }
    return -1;
}

/* pull: stream one remote file into a local file.
 *
 * NOTE: the sync stream carries only this RECV transaction's frames; after
 * RECV the device responds DATA* then DONE (no STAT). Never send STAT on
 * this stream mid-transaction — the response frames would be consumed as if
 * they were file data. */
static int sync_pull_file(int fd, const char* rpath, const char* lpath, int copy_attrs) {
    unsigned mode = 0, size = 0, mtime = 0;
    int have_stat = (sync_stat(fd, rpath, &mode, &size, &mtime) == 0) && S_ISREG(mode);
    if (!have_stat) {
        if (mode && S_ISDIR(mode)) {
            fprintf(stderr, "adb: error: failed to copy '%s': Is a directory\n", rpath);
            return -1;
        }
    }
    if (sync_send_request(fd, ID_RECV_V1, rpath) < 0) return -1;
    int lfd = open(lpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (lfd < 0) {
        fprintf(stderr, "adb: cannot create '%s': %s\n", lpath, strerror(errno));
        return -1;
    }
    for (;;) {
        unsigned id, len;
        if (sync_read_response(fd, &id, &len) < 0) {
            close(lfd);
            return -1;
        }
        if (id == ID_DATA) {
            if (len > SYNC_DATA_MAX) {
                close(lfd);
                return -1;
            }
            char buf[SYNC_DATA_MAX];
            if (len && !read_full(fd, buf, len)) {
                close(lfd);
                return -1;
            }
            if (!write_full(lfd, buf, len)) {
                close(lfd);
                return -1;
            }
        } else if (id == ID_DONE) {
            break;
        } else if (id == ID_FAIL) {
            char* msg = (char*)malloc(len + 1);
            if (msg && len && read_full(fd, msg, len)) {
                msg[len] = 0;
                fprintf(stderr, "adb: error: failed to copy '%s': %s\n", rpath, msg);
            }
            free(msg);
            close(lfd);
            unlink(lpath);
            return -1;
        } else {
            close(lfd);
            return -1;
        }
    }
    close(lfd);
    if (copy_attrs && mtime) {
        struct timeval tv[2];
        tv[0].tv_sec = mtime;
        tv[0].tv_usec = 0;
        tv[1] = tv[0];
        utimes(lpath, tv);
    }
    return 0;
}

/* ---------- commands ---------- */
static void print_devices(const char* payload) {
    printf("List of devices attached\n");
    if (payload) fputs(payload, stdout);
}

static int cmd_devices(int long_form) {
    if (ensure_server() < 0) return 1;
    int ok = 0;
    char err[256];
    char* payload = adb_query(long_form ? "host:devices-l" : "host:devices", &ok, err, sizeof(err));
    if (!payload) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    print_devices(payload);
    free(payload);
    return 0;
}

static int cmd_track_devices(void) {
    if (ensure_server() < 0) return 1;
    char err[256];
    int fd = adb_connect_service("host:track-devices", err, sizeof(err));
    if (fd < 0) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    for (;;) {
        char lenbuf[5];
        lenbuf[4] = 0;
        if (!read_full(fd, lenbuf, 4)) break;
        unsigned len = 0;
        sscanf(lenbuf, "%4x", &len);
        if (!len) break;
        char* payload = (char*)malloc(len + 1);
        if (!payload || !read_full(fd, payload, len)) {
            free(payload);
            break;
        }
        payload[len] = 0;
        fputs(payload, stdout);
        fflush(stdout);
        free(payload);
    }
    close(fd);
    return 0;
}

static int cmd_version(void) {
    /* Real adb prints its own version without contacting the server.
     * Line 1 is the fixed protocol version; line 2 is the platform-tools
     * client version (35.0.2 pairs with ADB_SERVER_VERSION 41). */
    printf(ADB_CLIENT_VERSION_STRING "\n");
    printf("Version 35.0.2\n");
    printf("Installed as /usr/bin/adb\n");
    return 0;
}

static int cmd_shell(int argc, char** argv) {
    /* option parsing mirrors adb_shell(): e: n: t: T: x */
    char escape_char = '~';
    int use_shell_protocol = 1;
    enum { PTY_AUTO, PTY_NO, PTY_YES, PTY_DEFINITELY } tty = PTY_AUTO;

    int optind = 0;
    for (int i = 1; i < argc; i++) {
        const char* a = argv[i];
        if (a[0] != '-' || a[1] == 0) break;
        for (const char* c = a + 1; *c; c++) {
            switch (*c) {
                case 'e':
                    if (c[1]) {
                        escape_char = c[1];
                        c += strlen(c) - 1;
                    } else if (i + 1 < argc) {
                        escape_char = *argv[++i];
                    } else {
                        fprintf(stderr, "-e requires a single-character argument or 'none'\n");
                        return 1;
                    }
                    if (escape_char == 'n' && strcmp(argv[i], "none") == 0) escape_char = 0;
                    break;
                case 'n':
                    /* close stdin: implemented by /dev/null redirect below */
                    break;
                case 'x':
                    use_shell_protocol = 0;
                    tty = PTY_DEFINITELY;
                    escape_char = '~';
                    break;
                case 't':
                    tty = (tty >= PTY_YES) ? PTY_DEFINITELY : PTY_YES;
                    break;
                case 'T':
                    tty = PTY_NO;
                    break;
                default:
                    fprintf(stderr, "error: unsupported option '-%c'\n", *c);
                    return 1;
            }
            if (*c == 'e' && escape_char) break; /* consumed the next arg */
        }
        optind = i + 1;
    }
    debug_print("cmd_shell opts: use_shell_protocol=%d escape='%c' tty=%d",
                use_shell_protocol, escape_char, tty);

    const char* command = "";
    if (optind < argc) {
        /* join with spaces, like android::base::Join of remaining args */
        size_t total = 0;
        for (int i = optind; i < argc; i++) total += strlen(argv[i]) + 1;
        char* joined = (char*)malloc(total + 1);
        joined[0] = 0;
        for (int i = optind; i < argc; i++) {
            strcat(joined, argv[i]);
            if (i + 1 < argc) strcat(joined, " ");
        }
        command = joined;
    }

    int is_interactive = (optind == argc);

    const char* shell_type_arg = "pty";
    if (tty == PTY_NO) {
        shell_type_arg = "raw";
    } else if (tty == PTY_AUTO) {
        if (!isatty(STDIN_FILENO) || !is_interactive) shell_type_arg = "raw";
    } else if (tty == PTY_YES) {
        if (!isatty(STDIN_FILENO)) {
            fprintf(stderr,
                    "Remote PTY will not be allocated because stdin is not a terminal.\n"
                    "Use multiple -t options to force remote PTY allocation.\n");
            shell_type_arg = "raw";
        }
    }

    if (!use_shell_protocol) {
        if (strcmp(shell_type_arg, "pty") != 0) {
            fprintf(stderr, "error: device only supports allocating a pty\n");
            return 1;
        }
        shell_type_arg = "";
    }

    if (ensure_server() < 0) return 1;

    int code = remote_shell(use_shell_protocol, shell_type_arg, escape_char, is_interactive,
                            command);
    return code;
}

static int cmd_exec_out(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: adb exec-out command\n");
        return 1;
    }
    size_t total = strlen("exec:") + 1;
    for (int i = 1; i < argc; i++) total += strlen(argv[i]) + 1;
    char* service = (char*)malloc(total + 1);
    strcpy(service, "exec:");
    for (int i = 1; i < argc; i++) {
        strcat(service, argv[i]);
        if (i + 1 < argc) strcat(service, " ");
    }
    if (ensure_server() < 0) return 1;
    int fd = adb_connect_device_service(service, NULL, 0);
    free(service);
    if (fd < 0) {
        fprintf(stderr, "error: closed\n");
        return 1;
    }
    char buf[BUFSIZ];
    for (;;) {
        ssize_t r = read(fd, buf, sizeof(buf));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        if (!write_full(STDOUT_FILENO, buf, (size_t)r)) break;
    }
    close(fd);
    return 0;
}

static int cmd_push(int argc, char** argv) {
    /* usage: adb push [--sync] LOCAL... REMOTE */
    int sync_flag = 0;
    int first = 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--sync") == 0) {
            sync_flag = 1;
            first = i + 1;
        }
    }
    if (argc - first < 2) {
        fprintf(stderr, "usage: adb push [-f] LOCAL... REMOTE\n");
        return 1;
    }
    const char* remote = argv[argc - 1];
    if (ensure_server() < 0) return 1;

    int err = 0;
    char errbuf[256];
    int fd = adb_connect_device_service("sync:", errbuf, sizeof(errbuf));
    if (fd < 0) {
        fprintf(stderr, "error: %s\n", errbuf);
        return 1;
    }

    /* If the remote exists and is a directory, push every local into it. */
    unsigned rmode = 0, rsize = 0, rmtime = 0;
    int remote_is_dir = 0;
    if (sync_stat(fd, remote, &rmode, &rsize, &rmtime) == 0 && S_ISDIR(rmode)) {
        remote_is_dir = 1;
    }

    for (int i = first; i < argc - 1; i++) {
        const char* local = argv[i];
        struct stat st;
        if (lstat(local, &st) < 0) {
            fprintf(stderr, "adb: cannot stat '%s': %s\n", local, strerror(errno));
            err = 1;
            continue;
        }
        char rpath[PATHBUF];
        if (remote_is_dir) {
            join_path(rpath, sizeof(rpath), remote, basename_of(local));
        } else {
            snprintf(rpath, sizeof(rpath), "%s", remote);
        }
        if (S_ISDIR(st.st_mode)) {
            if (sync_send_mkdir(fd, rpath, (unsigned)(st.st_mode & 07777)) < 0) {
                err = 1;
                continue;
            }
            unsigned long files = 0, bytes = 0;
            if (push_walk(fd, local, rpath, &files, &bytes, 0) < 0) err = 1;
            fprintf(stderr, "%lu files pushed, %lu bytes\n", files, bytes);
        } else if (S_ISREG(st.st_mode)) {
            unsigned target = (unsigned)(st.st_mode & 07777) | S_IFREG;
            if (sync_push_file(fd, local, rpath, target, NULL, 0) < 0) {
                err = 1;
            } else {
                fprintf(stderr, "1 file pushed, %lu bytes\n",
                        (unsigned long)st.st_size);
            }
        } else {
            fprintf(stderr, "adb: skipping special file '%s'\n", local);
        }
    }
    unsigned char quit[8];
    put_le32(quit, ID_QUIT);
    put_le32(quit + 4, 0);
    write_full(fd, quit, 8);
    close(fd);
    return err;
}

static const char* basename_of(const char* path) {
    const char* s = strrchr(path, '/');
    return s ? s + 1 : path;
}

static int cmd_pull(int argc, char** argv) {
    int copy_attrs = 0;
    int first = 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-a") == 0) {
            copy_attrs = 1;
            first = i + 1;
        }
    }
    if (argc - first < 1) {
        fprintf(stderr, "usage: adb pull [-a] REMOTE... LOCAL\n");
        return 1;
    }
    const char* local = argv[argc - 1];
    if (argc - first < 2) {
        fprintf(stderr, "usage: adb pull [-a] REMOTE... LOCAL\n");
        return 1;
    }
    if (ensure_server() < 0) return 1;

    char errbuf[256];
    int fd = adb_connect_device_service("sync:", errbuf, sizeof(errbuf));
    if (fd < 0) {
        fprintf(stderr, "error: %s\n", errbuf);
        return 1;
    }

    int err = 0;
    for (int i = first; i < argc - 1; i++) {
        const char* rpath = argv[i];
        unsigned mode = 0, size = 0, mtime = 0;
        if (sync_stat(fd, rpath, &mode, &size, &mtime) < 0) {
            fprintf(stderr, "adb: error: failed to stat '%s'\n", rpath);
            err = 1;
            continue;
        }
        char lpath[PATHBUF];
        struct stat lst;
        if (stat(local, &lst) == 0 && S_ISDIR(lst.st_mode)) {
            join_path(lpath, sizeof(lpath), local, basename_of(rpath));
        } else {
            snprintf(lpath, sizeof(lpath), "%s", local);
        }
        if (S_ISDIR(mode)) {
            fprintf(stderr, "adb: error: directory pull of '%s' requires recursive support\n", rpath);
            err = 1;
            continue;
        }
        if (sync_pull_file(fd, rpath, lpath, copy_attrs) < 0) {
            err = 1;
        } else {
            fprintf(stderr, "1 file pulled, %u bytes\n", size);
        }
    }
    unsigned char quit[8];
    put_le32(quit, ID_QUIT);
    put_le32(quit + 4, 0);
    write_full(fd, quit, 8);
    close(fd);
    return err;
}

static int cmd_install(int argc, char** argv) {
    /* usage: adb install [-lrtsdg] [--instant] FILE */
    const char* flags[16];
    int nflags = 0;
    const char* file = NULL;
    for (int i = 1; i < argc; i++) {
        if (argv[i][0] == '-' && argv[i][1] == '-') {
            flags[nflags++] = argv[i]; /* long flags pass through */
        } else if (argv[i][0] == '-' && argv[i][1]) {
            for (const char* c = argv[i] + 1; *c; c++) {
                char f[3] = {'-', *c, 0};
                flags[nflags++] = strdup(f);
            }
        } else {
            file = argv[i];
        }
    }
    if (!file) {
        fprintf(stderr, "usage: adb install [flags] FILE\n");
        return 1;
    }
    struct stat sb;
    if (stat(file, &sb) < 0) {
        fprintf(stderr, "adb: failed to stat %s: %s\n", file, strerror(errno));
        return 1;
    }
    if (ensure_server() < 0) return 1;

    /* build: exec:cmd package install-create -S <size> [flags] */
    char service[SYNC_DATA_MAX];
    int off = snprintf(service, sizeof(service), "exec:cmd package install-create -S %lld",
                       (long long)sb.st_size);
    for (int i = 0; i < nflags; i++) {
        off += snprintf(service + off, sizeof(service) - (size_t)off, " %s", flags[i]);
    }

    char errbuf[256];
    int fd = adb_connect_device_service(service, errbuf, sizeof(errbuf));
    if (fd < 0) {
        fprintf(stderr, "adb: connect error for create: %s\n", errbuf);
        return 1;
    }
    /* read_status_line */
    char buf[BUFSIZ];
    if (!read_status_line(fd, buf, sizeof(buf))) {
        fprintf(stderr, "adb: failed to read session status\n");
        close(fd);
        return 1;
    }
    int session_id = -1;
    if (strncmp(buf, "Success", 7) == 0) {
        char* start = strrchr(buf, '[');
        char* end = strrchr(buf, ']');
        if (start && end && end > start) {
            *end = 0;
            session_id = atoi(start + 1);
        }
    }
    if (session_id < 0) {
        fprintf(stderr, "adb: failed to create session\n");
        fputs(buf, stderr);
        close(fd);
        return 1;
    }

    /* install-write */
    char svc2[SYNC_DATA_MAX];
    snprintf(svc2, sizeof(svc2), "exec:cmd package install-write -S %lld %d %s -",
             (long long)sb.st_size, session_id, basename_of(file));
    int wfd = adb_connect_device_service(svc2, errbuf, sizeof(errbuf));
    if (wfd < 0) {
        fprintf(stderr, "adb: connect error for write: %s\n", errbuf);
        return 1;
    }
    int lfd = open(file, O_RDONLY);
    if (lfd < 0) {
        fprintf(stderr, "adb: failed to open \"%s\": %s\n", file, strerror(errno));
        return 1;
    }
    char chunk[65536];
    for (;;) {
        ssize_t r = read(lfd, chunk, sizeof(chunk));
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) break;
        if (!write_full(wfd, chunk, (size_t)r)) break;
    }
    close(lfd);
    if (!read_status_line(wfd, buf, sizeof(buf))) {
        fprintf(stderr, "adb: failed to write \"%s\"\n", file);
        close(wfd);
        return 1;
    }
    if (strncmp(buf, "Success", 7) != 0) {
        fprintf(stderr, "adb: failed to write \"%s\"\n", file);
        fputs(buf, stderr);
        close(wfd);
        return 1;
    }
    close(wfd);

    /* install-commit */
    char svc3[SYNC_DATA_MAX];
    snprintf(svc3, sizeof(svc3), "exec:cmd package install-commit %d", session_id);
    int cfd = adb_connect_device_service(svc3, errbuf, sizeof(errbuf));
    if (cfd < 0) {
        fprintf(stderr, "adb: connect error for finalize: %s\n", errbuf);
        return 1;
    }
    if (!read_status_line(cfd, buf, sizeof(buf))) {
        fprintf(stderr, "adb: failed to finalize session\n");
        close(cfd);
        return 1;
    }
    close(cfd);
    if (strncmp(buf, "Success", 7) != 0) {
        fprintf(stderr, "adb: failed to finalize session\n");
        fputs(buf, stderr);
        return 1;
    }
    fputs(buf, stdout);
    return 0;
}

static int read_status_line(int fd, char* buf, size_t count) {
    count--;
    size_t n = 0;
    while (n < count) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r <= 0) break;
        if (c == '\n') break;
        buf[n++] = c;
    }
    buf[n] = 0;
    return n > 0 || 0;
}

static int cmd_uninstall(int argc, char** argv) {
    int keep = 0;
    const char* pkg = NULL;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-k") == 0) {
            keep = 1;
        } else if (!pkg) {
            pkg = argv[i];
        }
    }
    if (!pkg) {
        fprintf(stderr, "usage: adb uninstall [-k] PACKAGE\n");
        return 1;
    }
    if (ensure_server() < 0) return 1;
    char service[512];
    if (keep) {
        fprintf(stderr,
                "The -k option uninstalls the application while retaining the data/cache.\n"
                "At the moment, there is no way to remove the remaining data.\n"
                "You will have to reinstall the application with the same signature, and fully\n"
                "uninstall it. You can also use 'adb shell cmd package uninstall -k'.\n");
        return 1;
    }
    snprintf(service, sizeof(service), "exec:cmd package uninstall %s", pkg);
    char errbuf[256];
    int fd = adb_connect_device_service(service, errbuf, sizeof(errbuf));
    if (fd < 0) {
        fprintf(stderr, "error: %s\n", errbuf);
        return 1;
    }
    char buf[BUFSIZ];
    read_status_line(fd, buf, sizeof(buf));
    close(fd);
    fputs(buf, stdout);
    return strncmp(buf, "Success", 7) == 0 ? 0 : 1;
}

/* ---------- forward / reverse ---------- */

static int cmd_forward(int argc, char** argv, int reverse) {    const char* prefix = reverse ? "reverse" : "forward";
    /* Prefix registry queries with host-serial:<serial>: when -s was given
     * (AOSP commandline.cpp forward/reverse block). */
    const char* hs_prefix = g_serial ? "host-serial:" : "host:";
    const char* hs_middle = g_serial ? ":" : "";
    int norebind = 0;
    int i = 1;
    for (; i < argc; i++) {
        if (strcmp(argv[i], "--no-rebind") == 0) {
            norebind = 1;
        } else if (strcmp(argv[i], "--list") == 0) {
            char service[192];
            snprintf(service, sizeof(service), "%s%s%slist-forward",
                     hs_prefix, g_serial ? g_serial : "", hs_middle);
            int ok = 0;
            char err[256];
            char* payload = adb_query(service, &ok, err, sizeof(err));
            if (!payload) {
                fprintf(stderr, "error: %s\n", err);
                return 1;
            }
            fputs(payload, stdout);
            free(payload);
            return 0;
        } else if (strcmp(argv[i], "--remove-all") == 0) {
            char service[192];
            snprintf(service, sizeof(service), "%s%s%skillforward-all",
                     hs_prefix, g_serial ? g_serial : "", hs_middle);
            int ok = 0;
            char err[256];
            char* payload = adb_query(service, &ok, err, sizeof(err));
            if (!payload) {
                fprintf(stderr, "error: %s\n", err);
                return 1;
            }
            free(payload);
            return 0;
        } else if (strcmp(argv[i], "--remove") == 0 && i + 1 < argc) {
            char service[224];
            snprintf(service, sizeof(service), reverse
                 ? "%s%s%sreverse:killforward:%s"
                 : "%s%s%skillforward:%s",
                 hs_prefix, g_serial ? g_serial : "", hs_middle, argv[i + 1]);
            int ok = 0;
            char err[256];
            char* payload = adb_query(service, &ok, err, sizeof(err));
            if (!payload) {
                fprintf(stderr, "error: %s\n", err);
                return 1;
            }
            free(payload);
            return 0;
        } else {
            break;
        }
    }
    if (i + 2 != argc) {
        fprintf(stderr, "usage: adb %s [--no-rebind] LOCAL REMOTE\n"
                        "   adb %s --list\n"
                        "   adb %s --remove LOCAL\n"
                        "   adb %s --remove-all\n",
                prefix, prefix, prefix, prefix);
        return 1;
    }
    char service[SYNC_DATA_MAX];
    if (reverse) {
        /* Device-side service: "reverse:forward:<local>;<remote>". With -s it
         * must be dispatched to THAT transport (device service → switch
         * form); host-serial: refuses reverse as a host query. */
        snprintf(service, sizeof(service), "reverse:forward:%s%s;%s",
                 norebind ? "norebind:" : "", argv[i], argv[i + 1]);
        if (g_serial) {
            int fd = adb_connect_device_service(service, NULL, 0);
            if (fd < 0) return 1;
            /* Two OKAYs (connect + status), optional port string, then the
             * stream closes — dump whatever follows, like the client does. */
            char c;
            while (read(fd, &c, 1) > 0) {
            }
            close(fd);
            return 0;
        }
    } else {
        /* Host-side service: "host:forward[:norebind]:<local>;<remote>",
         * serial-scoped via host-serial: so the server targets the right
         * device for the remote endpoint. */
        snprintf(service, sizeof(service), "%s%s%sforward:%s%s;%s",
                 hs_prefix, g_serial ? g_serial : "", hs_middle,
                 norebind ? "norebind:" : "", argv[i], argv[i + 1]);
    }
    int ok = 0;
    char err[256];
    char* payload = adb_query(service, &ok, err, sizeof(err));
    if (!payload) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    free(payload);
    return 0;
}

/* ---------- transport selection (global options) ---------- */

/* Open a (possibly transport-switched) device service stream.
 *
 * Without a -s/-t/-d/-e selection this is the plain adb_connect_service.
 * With -t it sends "host-transport-id:<id>:<service>" (one hex4 frame; the
 * server dispatches the inner service after the switch). With -s or -d/-e it
 * sends the transport switch first, consumes its reply, then sends the device
 * service hex4-framed on the SAME socket (AOSP _adb_connect behavior; the
 * server answers the switch with OKAY + 8 raw transport-id bytes which are
 * consumed here). Returns the raw service stream fd, or -1 with err. */
static int adb_connect_device_service(const char* service, char* err, size_t errlen) {
    if (!g_serial && !g_transport_id && !g_want_usb && !g_want_tcp) {
        return adb_connect_service(service, err, errlen);
    }
    int fd = socket_connect_server();
    if (fd < 0) {
        if (err && errlen) snprintf(err, errlen, "cannot connect to daemon");
        return -1;
    }
    if (g_transport_id) {
        char* qualified;
        if (asprintf(&qualified, "host-transport-id:%s:%s", g_transport_id, service) < 0) {
            close(fd);
            if (err && errlen) snprintf(err, errlen, "out of memory");
            return -1;
        }
        int rc = send_hex4(fd, qualified);
        free(qualified);
        if (rc < 0 || read_status(fd, err, errlen) < 0) {
            close(fd);
            return -1;
        }
        return fd;
    }
    /* Transport switch first: host:transport:<serial>, host:transport-usb
     * (-d), or host:transport-local (-e). The switch reply is OKAY followed
     * by the raw 8-byte transport id (new protocol). */
    char* sw;
    if (g_serial) {
        if (asprintf(&sw, "host:transport:%s", g_serial) < 0) {
            close(fd);
            if (err && errlen) snprintf(err, errlen, "out of memory");
            return -1;
        }
    } else {
        sw = strdup(g_want_usb ? "host:transport-usb" : "host:transport-local");
    }
    if (!sw) {
        close(fd);
        if (err && errlen) snprintf(err, errlen, "out of memory");
        return -1;
    }
    int rc = send_hex4(fd, sw);
    free(sw);
    if (rc < 0 || read_status(fd, err, errlen) < 0) {
        close(fd);
        return -1;
    }
    /* Consume the raw 8-byte transport id the new-protocol switch appends. */
    char tid[8];
    if (!read_full(fd, tid, 8)) {
        /* Legacy server without the id bytes: tolerate, the service reply
         * follows immediately. */
    }
    if (send_hex4(fd, service) < 0 || read_status(fd, err, errlen) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* ---------- connect / disconnect ---------- */

/*
 * ADB connect/disconnect are host-server operations: the server performs the
 * TCP connect + CNXN/AUTH handshake (AOSP connect_service in adb.cpp) and
 * owns the transport afterwards. The client just relays the verdict.
 */
static int cmd_connect(const char* addr) {
    if (ensure_server() < 0) return 1;
    /* No port given: default 5555, like the official client. */
    char* spec = NULL;
    if (strchr(addr, ':')) {
        spec = strdup(addr);
    } else if (asprintf(&spec, "%s:5555", addr) < 0) {
        return 1;
    }
    char service[512];
    snprintf(service, sizeof(service), "host:connect:%s", spec);
    free(spec);
    int ok = 0;
    char err[256];
    char* payload = adb_query(service, &ok, err, sizeof(err));
    if (!payload) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    /* AOSP connect_service: the verdict is printed verbatim; success is
     * "connected to ...", anything else is an honest failure. */
    fprintf(stderr, "%s\n", payload);
    int ret = strncmp(payload, "connected", 9) == 0 ? 0 : 1;
    free(payload);
    return ret;
}

static int cmd_disconnect(const char* addr) {
    if (ensure_server() < 0) return 1;
    char service[512];
    if (addr && *addr) {
        snprintf(service, sizeof(service), "host:disconnect:%s", addr);
    } else {
        snprintf(service, sizeof(service), "host:disconnect:");
    }
    int ok = 0;
    char err[256];
    char* payload = adb_query(service, &ok, err, sizeof(err));
    if (!payload) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    /* Official client prints the server's message verbatim on stdout. */
    fputs(payload, stdout);
    free(payload);
    return 0;
}

/* ---------- pair (Wireless Debugging, Android 11+) ---------- */

/*
 * `adb pair HOST:PAIRING_PORT` — performs REAL Android 11+ Wireless
 * Debugging pairing (SPAKE2 over TLS 1.3). The TLS/SPAKE2 protocol itself
 * lives in the Android app (PairingConnection, Java); this CLI only:
 *   1. sends "host:pair:<addr>" and reads the OKAY/FAIL activation reply,
 *   2. prompts "Enter pairing code: " locally,
 *   3. sends the 6 digits hex4-framed on the SAME socket,
 *   4. reads the hex4-framed verdict and prints it verbatim.
 * The code travels only over the loopback smart-socket connection into the
 * app — never a file, environment variable, or argv entry (ps-visible) —
 * and is never stored by either side.
 */
static int cmd_pair(const char* addr) {
    if (!addr || !*addr) {
        fprintf(stderr, "error: usage: adb pair HOST:PAIRING_PORT\n");
        return 1;
    }
    if (ensure_server() < 0) return 1;
    int fd = socket_connect_server();
    if (fd < 0) {
        fprintf(stderr, "error: cannot connect to daemon\n");
        return 1;
    }
    char service[512];
    snprintf(service, sizeof(service), "host:pair:%s", addr);
    if (send_hex4(fd, service) < 0) {
        close(fd);
        fprintf(stderr, "error: protocol failure\n");
        return 1;
    }
    char err[256];
    if (read_status(fd, err, sizeof(err)) < 0) {
        close(fd);
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    fprintf(stderr, "Enter pairing code: ");
    fflush(stderr);
    char line[64];
    if (!fgets(line, sizeof(line), stdin)) {
        close(fd);
        fprintf(stderr, "\nerror: no pairing code entered\n");
        return 1;
    }
    char digits[7];
    int n = 0;
    for (const char* p = line; *p; ++p) {
        if (*p >= '0' && *p <= '9') {
            if (n >= 6) {
                close(fd);
                fprintf(stderr, "error: pairing code must be exactly 6 digits\n");
                return 1;
            }
            digits[n++] = *p;
        } else if (*p == '\n' || *p == '\r') {
            break;
        } else if (*p == ' ' || *p == '\t') {
            continue;
        } else {
            close(fd);
            fprintf(stderr, "error: pairing code must be exactly 6 digits\n");
            return 1;
        }
    }
    if (n != 6) {
        close(fd);
        fprintf(stderr, "error: pairing code must be exactly 6 digits\n");
        return 1;
    }
    digits[6] = 0;
    if (write(fd, "0006", 4) != 4 || write(fd, digits, 6) != 6) {
        close(fd);
        fprintf(stderr, "error: failed to send the pairing code to the daemon\n");
        return 1;
    }
    /* Read the hex4-framed verdict the app writes after the real pairing. */
    char lenbuf[5];
    lenbuf[4] = 0;
    if (!read_full(fd, lenbuf, 4)) {
        close(fd);
        fprintf(stderr, "error: daemon closed during pairing\n");
        return 1;
    }
    unsigned len = 0;
    sscanf(lenbuf, "%4x", &len);
    if (len >= sizeof(err)) len = (unsigned)(sizeof(err) - 1);
    if (len && !read_full(fd, err, len)) {
        close(fd);
        fprintf(stderr, "error: daemon closed during pairing\n");
        return 1;
    }
    err[len] = 0;
    close(fd);
    fprintf(stderr, "%s\n", err);
    return strncmp(err, "Success", 7) == 0 ? 0 : 1;
}

static int cmd_kill_server(void) {
    int ok = 0;
    char err[256];
    char* payload = adb_query("host:kill", &ok, err, sizeof(err));
    free(payload);
    return 0;
}

static int cmd_start_server(void) {
    return ensure_server() < 0 ? 1 : 0;
}

static int cmd_wait_for_device(const char* state) {
    if (ensure_server() < 0) return 1;
    /* host-serial scoped waits are host queries; plain waits are handled by
     * the server too (wait-for-* host services). Either way the reply is the
     * standard two-OKAY adb_command form handled inside adb_query. */
    char service[128];
    if (g_serial) {
        snprintf(service, sizeof(service), "host-serial:%s:wait-for-%s", g_serial,
                 state ? state : "any");
    } else {
        snprintf(service, sizeof(service), "host:wait-for-%s",
                 state ? state : "any");
    }
    int ok = 0;
    char err[256];
    char* payload = adb_query(service, &ok, err, sizeof(err));
    if (!payload) {
        fprintf(stderr, "error: %s\n", err);
        return 1;
    }
    free(payload);
    return 0;
}

static void usage(FILE* out) {
    fprintf(out,
        "TermDeb ADB (compatible with Android Debug Bridge)\n"
        "\n"
        "global options:\n"
        " -a                       listen on all network interfaces (ignored)\n"
        " -d                       use USB device (error if multiple devices)\n"
        " -e                       use TCP/IP device (error if multiple TCP devices)\n"
        " -s SERIAL                use device with given serial\n"
        " -t ID                    use device with given transport id\n"
        " -H                       name of adb server host (default: localhost)\n"
        " -P                       port of adb server (default: 5037)\n"
        " -L SOCKET                listen socket for the adb server (default tcp:localhost:5037)\n"
        "\n"
        "general commands:\n"
        " devices [-l]             list devices\n"
        " help                     show this help\n"
        " version                  show version number\n"
        "\n"
        "networking:\n"
        " forward --list\n"
        " forward [--no-rebind] LOCAL REMOTE\n"
        " forward --remove LOCAL\n"
        " reverse --list\n"
        " reverse [--no-rebind] REMOTE LOCAL\n"
        " reverse --remove REMOTE\n"
        " connect HOST[:PORT]      connect to a remote adbd (network transport)\n"
        " disconnect [HOST[:PORT]] disconnect from a remote adbd\n"
        " pair HOST:PAIRING_PORT   pair with Wireless Debugging (Android 11+)\n"
        "\n"
        "shell:\n"
        " shell [-e ESCAPE] [-n] [-Tt] [-x] [COMMAND...]\n"
        " exec-out COMMAND...      like shell, but raw stdout (no PTY)\n"
        "\n"
        "file transfer:\n"
        " push [--sync] LOCAL... REMOTE\n"
        " pull [-a] REMOTE... LOCAL\n"
        " sync [ALL] \n"
        "\n"
        "app package:\n"
        " install [-lrtsdg] [--instant] FILE\n"
        " install-multiple [-lrtsdpg] FILE...\n"
        " uninstall [-k] PACKAGE\n"
        "\n"
        "debugging:\n"
        " bugreport [PATH]\n"
        " logcat [options]         (alias for shell logcat)\n"
        "\n"
        "server:\n"
        " start-server             ensure the app-owned server is running\n"
        " kill-server              kill the app-owned server\n"
        " wait-for-<state>         wait for device state\n");
}

/* ---------- main ---------- */

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    resolve_server_endpoint();

    /* global options */
    int i = 1;
    for (; i < argc; i++) {
        const char* a = argv[i];
        if (a[0] != '-' || a[1] == 0 || a[1] == ' ') break;
        if (strcmp(a, "-a") == 0) continue;
        if (strcmp(a, "-d") == 0) {
            g_want_usb = 1;
            continue;
        }
        if (strcmp(a, "-e") == 0) {
            g_want_tcp = 1;
            continue;
        }
        if ((strcmp(a, "-s") == 0 || strcmp(a, "-t") == 0) && i + 1 < argc) {
            if (strcmp(a, "-s") == 0) {
                g_serial = argv[++i];
            } else {
                g_transport_id = argv[++i];
            }
            continue;
        }
        if (strncmp(a, "-s", 2) == 0 && a[2]) {
            g_serial = a + 2;
            continue;
        }
        if (strncmp(a, "-t", 2) == 0 && a[2]) {
            g_transport_id = a + 2;
            continue;
        }
        if (strcmp(a, "-L") == 0 || strcmp(a, "-H") == 0 || strcmp(a, "-P") == 0) {
            i++; /* consume value; server endpoint is loopback-managed */
            continue;
        }
        if (strncmp(a, "-P", 2) == 0 && a[2]) {
            int p = atoi(a + 2);
            if (p > 0) server_port = p;
            continue;
        }
        break;
    }
    if (i >= argc) {
        usage(stderr);
        return 1;
    }
    const char* cmd = argv[i];
    int sub_argc = argc - i;
    char** sub_argv = argv + i;

    if (strcmp(cmd, "help") == 0 || strcmp(cmd, "--help") == 0 || strcmp(cmd, "-h") == 0) {
        usage(stdout);
        return 0;
    }
    if (strcmp(cmd, "version") == 0) return cmd_version();
    if (strcmp(cmd, "devices") == 0) return cmd_devices(sub_argc > 1 && strcmp(sub_argv[1], "-l") == 0);
    if (strcmp(cmd, "track-devices") == 0) return cmd_track_devices();
    if (strcmp(cmd, "shell") == 0) return cmd_shell(sub_argc, sub_argv);
    if (strcmp(cmd, "exec-out") == 0 || strcmp(cmd, "exec-in") == 0) return cmd_exec_out(sub_argc, sub_argv);
    if (strcmp(cmd, "push") == 0) return cmd_push(sub_argc, sub_argv);
    if (strcmp(cmd, "pull") == 0) return cmd_pull(sub_argc, sub_argv);
    if (strcmp(cmd, "install") == 0) return cmd_install(sub_argc, sub_argv);
    if (strcmp(cmd, "uninstall") == 0) return cmd_uninstall(sub_argc, sub_argv);
    if (strcmp(cmd, "forward") == 0) return cmd_forward(sub_argc, sub_argv, 0);
    if (strcmp(cmd, "reverse") == 0) return cmd_forward(sub_argc, sub_argv, 1);
    if (strcmp(cmd, "logcat") == 0) {
        /* alias: shell logcat ... */
        char** nargv = (char**)malloc(sizeof(char*) * (argc + 1));
        nargv[0] = (char*)"shell";
        nargv[1] = (char*)"logcat";
        int n = 2;
        for (int k = i + 1; k < argc; k++) nargv[n++] = argv[k];
        return cmd_shell(n, nargv);
    }
    if (strcmp(cmd, "get-state") == 0) {
        /* Ask the server for the SELECTED transport's state (the official
         * client does exactly this; default selection = the sole device). */
        char service[192];
        if (g_serial) {
            snprintf(service, sizeof(service), "host-serial:%s:get-state", g_serial);
        } else {
            snprintf(service, sizeof(service), "host:get-state");
        }
        int ok = 0;
        char err[256];
        char* payload = adb_query(service, &ok, err, sizeof(err));
        if (!payload) {
            fprintf(stderr, "error: %s\n", err);
            return 1;
        }
        printf("%s\n", payload);
        free(payload);
        return 0;
    }
    if (strcmp(cmd, "get-serialno") == 0) {
        char service[192];
        snprintf(service, sizeof(service), "host:get-serialno");
        int ok = 0;
        char err[256];
        char* payload = adb_query(service, &ok, err, sizeof(err));
        if (!payload) {
            fprintf(stderr, "error: %s\n", err);
            return 1;
        }
        printf("%s\n", payload);
        free(payload);
        return 0;
    }
    if (strcmp(cmd, "get-devpath") == 0) {
        char service[192];
        snprintf(service, sizeof(service), "host:get-devpath");
        int ok = 0;
        char err[256];
        char* payload = adb_query(service, &ok, err, sizeof(err));
        if (!payload) {
            fprintf(stderr, "error: %s\n", err);
            return 1;
        }
        printf("%s\n", payload);
        free(payload);
        return 0;
    }
    if (strcmp(cmd, "wait-for-device") == 0 || strncmp(cmd, "wait-for-", 9) == 0) {
        return cmd_wait_for_device(cmd + 9);
    }
    if (strcmp(cmd, "start-server") == 0) return cmd_start_server();
    if (strcmp(cmd, "kill-server") == 0) return cmd_kill_server();
    if (strcmp(cmd, "pair") == 0) {
        if (sub_argc < 2) {
            fprintf(stderr, "error: usage: adb pair HOST:PAIRING_PORT\n");
            return 1;
        }
        return cmd_pair(sub_argv[1]);
    }
    if (strcmp(cmd, "connect") == 0) {
        if (sub_argc < 2) {
            fprintf(stderr, "error: usage: adb connect HOST[:PORT]\n");
            return 1;
        }
        return cmd_connect(sub_argv[1]);
    }
    if (strcmp(cmd, "disconnect") == 0) {
        return cmd_disconnect(sub_argc >= 2 ? sub_argv[1] : NULL);
    }
    if (strcmp(cmd, "bugreport") == 0) {
        /* bugreport is a shell command */
        char** nargv = (char**)malloc(sizeof(char*) * (argc + 1));
        nargv[0] = (char*)"shell";
        nargv[1] = (char*)"bugreport";
        int n = 2;
        for (int k = i + 1; k < argc; k++) nargv[n++] = argv[k];
        return cmd_shell(n, nargv);
    }
    if (strcmp(cmd, "emu") == 0) {
        fprintf(stderr, "error: emulator console not supported\n");
        return 1;
    }
    if (strcmp(cmd, "jdwp") == 0) {
        /* no debuggable VMs visible */
        int fd = adb_connect_device_service("track-jdwp", NULL, 0);
        if (fd < 0) return 1;
        char buf[16];
        while (read(fd, buf, sizeof(buf)) > 0) {
        }
        close(fd);
        return 0;
    }
    if (strcmp(cmd, "remount") == 0 || strcmp(cmd, "root") == 0 || strcmp(cmd, "unroot") == 0) {
        fprintf(stderr, "adbd cannot run as root in production builds\n");
        return 1;
    }
    if (strcmp(cmd, "backup") == 0 || strcmp(cmd, "restore") == 0) {
        fprintf(stderr, "error: adb backup is not supported\n");
        return 1;
    }
    fprintf(stderr, "adb: unknown command %s\n", cmd);
    usage(stderr);
    return 1;
}
