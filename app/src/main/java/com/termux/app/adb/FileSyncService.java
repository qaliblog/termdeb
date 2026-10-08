package com.termux.app.adb;

import java.io.EOFException;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AOSP file sync protocol service (docs/dev/sync.md).
 *
 * Implements the v1 protocol fully (STAT, LIST, SEND, RECV, QUIT with
 * OKAY/FAIL/DONE/DATA frames) plus the v2 stat/ls request ids (STA2/LST2 with
 * DNT2 directory entries) that are advertised via stat_v2/ls_v2. SEND/RECV
 * remain v1 (sendrecv_v2 is not advertised), which is the path the official
 * client exercises for years of releases.
 *
 * Paths are resolved through {@link TermdebAdbBridge.FileSyncPaths#resolve}
 * against the same filesystem the app can access: /sdcard is real shared
 * storage (subject to the app's own storage permissions), /data/local/tmp is
 * an app-owned scratch directory. No permission escalation: unmapped paths and
 * access failures surface as sync FAIL frames with genuine strerror-style
 * messages, exactly like adbd.
 */
final class FileSyncService {

    static final int ID_STAT_V1 = mkid('S', 'T', 'A', 'T');
    static final int ID_STAT_V2 = mkid('S', 'T', 'A', '2');
    static final int ID_LSTAT_V2 = mkid('L', 'S', 'T', '2');
    static final int ID_LIST_V1 = mkid('L', 'I', 'S', 'T');
    static final int ID_LIST_V2 = mkid('L', 'I', 'S', '2');
    static final int ID_DENT_V1 = mkid('D', 'E', 'N', 'T');
    static final int ID_DENT_V2 = mkid('D', 'N', 'T', '2');
    static final int ID_SEND_V1 = mkid('S', 'E', 'N', 'D');
    static final int ID_RECV_V1 = mkid('R', 'E', 'C', 'V');
    static final int ID_DONE = mkid('D', 'O', 'N', 'E');
    static final int ID_DATA = mkid('D', 'A', 'T', 'A');
    static final int ID_OKAY = mkid('O', 'K', 'A', 'Y');
    static final int ID_FAIL = mkid('F', 'A', 'I', 'L');
    static final int ID_QUIT = mkid('Q', 'U', 'I', 'T');

    static final int SYNC_DATA_MAX = 64 * 1024;

    private static int mkid(char a, char b, char c, char d) {
        return (a) | ((b) << 8) | ((c) << 16) | ((d) << 24);
    }

    private static final String LOG_TAG = TermdebAdbBridge.LOG_TAG;

    private FileSyncService() {
    }

    /** Four-char wire id rendered as text ('STAT', 'LST2', ...) for logs. */
    private static String idName(int id) {
        return new String(new char[] {
            (char) (id & 0xff), (char) ((id >> 8) & 0xff),
            (char) ((id >> 16) & 0xff), (char) ((id >> 24) & 0xff)});
    }

    private static int myUid() {
        try {
            return android.os.Process.myUid();
        } catch (Throwable t) {
            // Stubbed fallback for stub bootstrap/CI compile.
            try {
                Class<?> cls = Class.forName("android.os.Process");
                Method m = cls.getMethod("myUid");
                return (int) m.invoke(null);
            } catch (Throwable e) {
                return 10000;
            }
        }
    }

    /** Serve one sync connection until QUIT or stream end. */
    static void serve(InputStream in, OutputStream out) throws IOException {
        byte[] header = new byte[8];
        while (true) {
            readFully(in, header, 0, 8);
            int id = le32(header, 0);
            int len = le32(header, 4);
            if (len < 0 || len > 0x10000) {
                writeFail(out, "invalid sync payload length");
                return;
            }
            byte[] payload = new byte[len];
            if (len > 0) readFully(in, payload, 0, len);
            String path = new String(payload, StandardCharsets.UTF_8);

            TermdebAdbBridge.logDebug(LOG_TAG, "sync request id=" + idName(id) + " path='" + path
                + "' len=" + len);

            if (id == ID_QUIT) return;
            if (id == ID_STAT_V1 || id == ID_STAT_V2 || id == ID_LSTAT_V2) { handleStat(out, path.trim(), id); continue; }
            if (id == ID_LIST_V1 || id == ID_LIST_V2) { handleList(out, path.trim(), id); continue; }
            if (id == ID_SEND_V1) { handleSend(out, path, in); continue; }
            if (id == ID_RECV_V1) { handleRecv(out, path.trim()); continue; }
            writeFail(out, "invalid sync id"); return;
        }
    }

    // ---------- stat ----------

    /** STAT: v1 16-byte struct (id,mode,size,mtime) or v2 65-byte struct. */
    private static void handleStat(OutputStream out, String path, int reqId) throws IOException {
        boolean v2 = (reqId == ID_STAT_V2 || reqId == ID_LSTAT_V2);
        TermdebAdbBridge.FileSyncPaths paths = TermdebAdbBridge.syncPaths();
        File f = paths != null ? paths.resolve(path) : null;

        int mode = 0;
        long size = 0;
        long mtime = 0;
        int errno = 0;
        if (f == null) {
            errno = 13; // EACCES — path outside the app's accessible roots
        } else if (!f.exists()) {
            errno = 2; // ENOENT
        } else {
            java.nio.file.Path p = f.toPath();
            try {
                java.nio.file.attribute.BasicFileAttributes attrs =
                    java.nio.file.Files.readAttributes(p,
                        java.nio.file.attribute.BasicFileAttributes.class);
                mode = unixMode(attrs);
                size = attrs.size();
                mtime = attrs.lastModifiedTime().toMillis() / 1000;
            } catch (IOException e) {
                errno = 13;
            }
        }

        if (!v2) {
            // v1: raw 16-byte sync_stat_v1 { id, mode, size, mtime }. A missing
            // file is reported as the all-zero struct (adbd compatibility), not
            // a FAIL frame.
            byte[] st = new byte[16];
            putLe32(st, 0, ID_STAT_V1);
            putLe32(st, 4, errno != 0 ? 0 : mode);
            putLe32(st, 8, (int) (errno != 0 ? 0 : size));
            putLe32(st, 12, (int) (errno != 0 ? 0 : mtime));
            out.write(st);
            out.flush();
            return;
        }
        // v2: sync_stat_v2 { id, error, dev, ino, mode, nlink, uid, gid, size,
        // atime, mtime, ctime } — 72 packed bytes (file_sync_protocol.h). Capabilities
        // the app cannot see (dev/ino/uid/gid of other uids' files) are reported
        // honestly as 0.
        byte[] st = new byte[72];
        putLe32(st, 0, reqId);
        if (errno == 0) {
            // st[4..7] = error = 0
            putLe64(st, 8, 0);                                   // dev
            putLe64(st, 16, f.hashCode() & 0xffffffffL);         // ino (opaque, non-zero)
            putLe32(st, 24, mode);
            putLe32(st, 28, 1);                                  // nlink
            putLe32(st, 32, myUid());
            putLe32(st, 36, myUid());
            putLe64(st, 40, size);
            putLe64(st, 48, mtime);                              // atime
            putLe64(st, 56, mtime);                              // mtime
            putLe64(st, 64, mtime);                              // ctime
        } else {
            putLe32(st, 4, errno);
        }
        out.write(st);
        out.flush();
    }

    private static int unixMode(java.nio.file.attribute.BasicFileAttributes attrs) {
        int mode;
        if (attrs.isDirectory()) {
            mode = 0040755;
        } else if (attrs.isSymbolicLink()) {
            mode = 0120777;
        } else {
            mode = 0100644;
        }
        return mode;
    }

    // ---------- list ----------

    /**
     * LIST: DENT/DNT2 entries then the ID_DONE terminator, which AOSP writes as
     * a whole dent struct of the matching version (20 or 76 bytes).
     */
    private static void handleList(OutputStream out, String path, int reqId) throws IOException {
        boolean v2 = (reqId == ID_LIST_V2);
        TermdebAdbBridge.FileSyncPaths paths = TermdebAdbBridge.syncPaths();
        File dir = paths != null ? paths.resolve(path) : null;
        File[] entries = dir == null ? null : dir.listFiles();
        if (entries == null) {
            // AOSP do_list falls through to the DONE terminator when opendir or
            // a per-entry lstat fails; it never sends a FAIL frame here. That
            // matters because the client reads sizeof(sync_dent_vN) bytes and
            // only then looks at the id, so a short reply would block it.
            TermdebAdbBridge.logWarn(LOG_TAG,
                "sync ls: cannot list '" + path + "' (treating as empty)");
            entries = new File[0];
        }
        java.util.Arrays.sort(entries);
        for (File entry : entries) {
            boolean isDir = entry.isDirectory();
            int mode = isDir ? 0040755 : 0100644;
            long size = entry.length();
            long mtime = entry.lastModified() / 1000;
            String name = entry.getName();
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] frame;
            if (!v2) {
                // sync_dent_v1 { id, mode, size, mtime, namelen, name }
                frame = new byte[20 + nameBytes.length];
                putLe32(frame, 0, ID_DENT_V1);
                putLe32(frame, 4, mode);
                putLe32(frame, 8, (int) size);
                putLe32(frame, 12, (int) mtime);
                putLe32(frame, 16, nameBytes.length);
                System.arraycopy(nameBytes, 0, frame, 20, nameBytes.length);
            } else {
                // sync_dent_v2 { id, error, dev, ino, mode, nlink, uid, gid,
                // size, atime, mtime, ctime, namelen, name } — 76 packed bytes
                // + name (file_sync_protocol.h).
                frame = new byte[76 + nameBytes.length];
                putLe32(frame, 0, ID_DENT_V2);
                // frame[4..7] = error = 0
                putLe64(frame, 8, 0);                            // dev
                putLe64(frame, 16, entry.hashCode() & 0xffffffffL); // ino
                putLe32(frame, 24, mode);
                putLe32(frame, 28, 1);                           // nlink
                putLe32(frame, 32, myUid());
                putLe32(frame, 36, myUid());
                putLe64(frame, 40, size);
                putLe64(frame, 48, mtime);                       // atime
                putLe64(frame, 56, mtime);                       // mtime
                putLe64(frame, 64, mtime);                       // ctime
                putLe32(frame, 72, nameBytes.length);
                System.arraycopy(nameBytes, 0, frame, 76, nameBytes.length);
            }
            out.write(frame);
        }
        // AOSP do_list terminates the listing with an ID_DONE frame the full
        // size of the version's dent struct (sizeof(sync_dent_v1) == 20,
        // sizeof(sync_dent_v2) == 76), all remaining fields zero.
        byte[] done = new byte[v2 ? 76 : 20];
        putLe32(done, 0, ID_DONE);
        out.write(done);
        out.flush();
    }

    // ---------- send (push) ----------

    /**
     * SEND: "path,mode" then DATA chunks then DONE -> OKAY. A path ending in
     * '/' requests a directory create (fixed_push_mkdir convention).
     */
    private static void handleSend(OutputStream out, String pathAndMode, InputStream in)
        throws IOException {
        String path = pathAndMode;
        int mode = 0100644;
        int comma = path.lastIndexOf(',');
        if (comma > 0) {
            try {
                mode = Integer.parseInt(path.substring(comma + 1), 8);
            } catch (NumberFormatException e) {
                mode = 0100644;
            }
            path = path.substring(0, comma);
        }
        path = path.trim();
        boolean wantsDir = path.endsWith("/");
        if (wantsDir) path = path.substring(0, path.length() - 1);

        TermdebAdbBridge.FileSyncPaths paths = TermdebAdbBridge.syncPaths();
        File f = paths != null ? paths.resolve(path) : null;
        if (f == null) {
            drainSend(in);
            writeFail(out, "Permission denied");
            return;
        }
        if (wantsDir) {
            if (!f.exists() && !f.mkdirs() && !f.isDirectory()) {
                drainSend(in);
                writeFail(out, "failed to create directory '" + path + "'");
                return;
            }
            // Consume the (empty) DATA/DONE sequence for a directory create.
            drainSend(in);
            writeOkay(out);
            return;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            drainSend(in);
            writeFail(out, "failed to create directory '" + parent + "'");
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            byte[] header = new byte[8];
            while (true) {
                readFully(in, header, 0, 8);
                int id = le32(header, 0);
                int len = le32(header, 4);
                if (id == ID_DONE) {
                    break;
                }
                if (id != ID_DATA || len < 0 || len > SYNC_DATA_MAX) {
                    writeFail(out, "invalid data frame");
                    return;
                }
                byte[] buf = new byte[len];
                readFully(in, buf, 0, len);
                fos.write(buf, 0, len);
            }
            fos.getFD().sync();
        } catch (IOException e) {
            writeFail(out, e.getMessage() != null ? e.getMessage() : "write failed");
            return;
        }
        writeOkay(out);
    }

    /** Read DATA/DONE frames after a failure so the client is not desynced. */
    private static void drainSend(InputStream in) throws IOException {
        byte[] header = new byte[8];
        while (true) {
            readFully(in, header, 0, 8);
            int id = le32(header, 0);
            int len = le32(header, 4);
            if (id == ID_DONE) return;
            if (id != ID_DATA || len < 0 || len > SYNC_DATA_MAX) return;
            skipFully(in, len);
        }
    }

    // ---------- recv (pull) ----------

    /** RECV: stream DATA chunks then DONE. */
    private static void handleRecv(OutputStream out, String path) throws IOException {
        TermdebAdbBridge.FileSyncPaths paths = TermdebAdbBridge.syncPaths();
        File f = paths != null ? paths.resolve(path) : null;
        if (f == null) {
            writeFail(out, "Permission denied");
            return;
        }
        if (!f.exists()) {
            writeFail(out, "No such file or directory");
            return;
        }
        if (f.isDirectory()) {
            writeFail(out, "Is a directory");
            return;
        }
        try (InputStream fin = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[SYNC_DATA_MAX];
            int n;
            while ((n = fin.read(buf)) > 0) {
                byte[] frame = new byte[8 + n];
                putLe32(frame, 0, ID_DATA);
                putLe32(frame, 4, n);
                System.arraycopy(buf, 0, frame, 8, n);
                out.write(frame);
            }
        } catch (IOException e) {
            writeFail(out, e.getMessage() != null ? e.getMessage() : "read failed");
            return;
        }
        byte[] done = new byte[8];
        putLe32(done, 0, ID_DONE);
        putLe32(done, 4, 0);
        out.write(done);
        out.flush();
    }

    // ---------- framing helpers ----------

    private static void writeFail(OutputStream out, String msg) throws IOException {
        byte[] m = msg.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + m.length];
        putLe32(frame, 0, ID_FAIL);
        putLe32(frame, 4, m.length);
        System.arraycopy(m, 0, frame, 8, m.length);
        out.write(frame);
        out.flush();
    }

    private static void writeOkay(OutputStream out) throws IOException {
        byte[] frame = new byte[8];
        putLe32(frame, 0, ID_OKAY);
        putLe32(frame, 4, 0);
        out.write(frame);
        out.flush();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
            | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putLe64(byte[] b, int off, long v) {
        putLe32(b, off, (int) (v & 0xffffffffL));
        putLe32(b, off + 4, (int) ((v >>> 32) & 0xffffffffL));
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len)
        throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("EOF in sync stream");
            got += n;
        }
    }

    private static void skipFully(InputStream in, int len) throws IOException {
        long remaining = len;
        byte[] buf = new byte[Math.min(len, 8192)];
        while (remaining > 0) {
            long n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new EOFException("EOF in sync stream");
            remaining -= n;
        }
    }
}
