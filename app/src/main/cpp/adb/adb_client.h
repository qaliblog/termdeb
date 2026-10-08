/*
 * TermDeb ADB bridge - shared client constants.
 *
 * Wire formats verified against AOSP platform/packages/modules/adb:
 *   - docs/dev/services.md  (smart protocol, host/device services)
 *   - docs/dev/sync.md      (file sync v1)
 *   - shell_protocol.h      (shell v2 packet ids)
 *   - file_sync_protocol.h  (sync ids and structs)
 */
#ifndef TERMDEB_ADB_CLIENT_H
#define TERMDEB_ADB_CLIENT_H

#include <stdint.h>

/* Smart-protocol version reported by host:version (ADB_SERVER_VERSION). */
#define ADB_SERVER_VERSION 41
/* Version string printed by `adb version`. */
#define ADB_CLIENT_VERSION_STRING "Android Debug Bridge version 1.0.41"

/* Default server endpoint. */
#define ADB_DEFAULT_SERVER_PORT 5037

/* Shell protocol packet ids (shell_protocol.h). */
#define SHELL_ID_STDIN 0
#define SHELL_ID_STDOUT 1
#define SHELL_ID_STDERR 2
#define SHELL_ID_EXIT 3
#define SHELL_ID_CLOSE_STDIN 4
#define SHELL_ID_WINDOW_SIZE_CHANGE 5
#define SHELL_ID_INVALID 255

/* Sync protocol ids (file_sync_protocol.h). Little-endian on the wire. */
/* MKID must be defined before the ID_ macros below. */
#define MKID4(a, b, c, d) ((unsigned)(a) | ((unsigned)(b) << 8) | ((unsigned)(c) << 16) | ((unsigned)(d) << 24))

#define ID_STAT_V1 MKID4('S', 'T', 'A', 'T')
#define ID_STAT_V2 MKID4('S', 'T', 'A', '2')
#define ID_LSTAT_V2 MKID4('L', 'S', 'T', '2')
#define ID_LIST_V1 MKID4('L', 'I', 'S', 'T')
#define ID_LIST_V2 MKID4('L', 'I', 'S', '2')
#define ID_DENT_V1 MKID4('D', 'E', 'N', 'T')
#define ID_DENT_V2 MKID4('D', 'N', 'T', '2')
#define ID_SEND_V1 MKID4('S', 'E', 'N', 'D')
#define ID_RECV_V1 MKID4('R', 'E', 'C', 'V')
#define ID_DONE MKID4('D', 'O', 'N', 'E')
#define ID_DATA MKID4('D', 'A', 'T', 'A')
#define ID_OKAY MKID4('O', 'K', 'A', 'Y')
#define ID_FAIL MKID4('F', 'A', 'I', 'L')
#define ID_QUIT MKID4('Q', 'U', 'I', 'T')

/* Sync chunk cap (docs/dev/sync.md: "Each chunk must not be larger than 64k"). */
#define SYNC_DATA_MAX (64 * 1024)

/* MAX_PAYLOAD_V1 - legacy service strings must not exceed this. */
#define MAX_PAYLOAD_V1 (4 * 1024)

/* Device serial reported by the bridge server. */
#define ADB_BRIDGE_SERIAL "emulator-5554"

#endif /* TERMDEB_ADB_CLIENT_H */
