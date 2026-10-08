package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();



    /*
     * TermDeb offline runtime installation.
     * These methods handle extraction of bundled Debian rootfs, proot-distro,
     * Box64, and other offline runtime components from the APK assets.
     */

    private static final String TERMDEB_ASSETS_DIR = "termdeb-runtime";
    private static final String TERMDEB_VERSION_FILE = TERMDEB_ASSETS_DIR + "/config/termdeb-version.json";
    private static final String TERMDEB_BOOTSTRAP_DIR = TERMDEB_ASSETS_DIR + "/bootstrap";
    private static final String TERMDEB_PROOT_DIR = TERMDEB_ASSETS_DIR + "/proot";
    private static final String TERMDEB_DEBIAN_DIR = TERMDEB_ASSETS_DIR + "/debian";
    private static final String TERMDEB_BOX64_DIR = TERMDEB_ASSETS_DIR + "/box64";
    private static final String TERMDEB_ADB_DIR = TERMDEB_ASSETS_DIR + "/adb";
    private static final String TERMDEB_BOX64_LIBS_DIR = TERMDEB_ASSETS_DIR + "/box64-libs";
    private static final String TERMDEB_PROOT_DISTRO_DIR = TERMDEB_ASSETS_DIR + "/proot-distro";
    private static final String TERMDEB_CONFIG_DIR = TERMDEB_ASSETS_DIR + "/config";

    /**
     * Shared path definitions for the TermDeb shell scripts. NOTE: paths derive from
     * $PREFIX instead of ${ANDROID_DATA:-...}: Android always sets ANDROID_DATA=/data,
     * so the fallback would never trigger and paths would resolve under /data instead
     * of the app's data directory.
     */
    private static final String TERMDEB_PATHS =
        "TERMDEB_PREFIX=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}\"\n" +
        "TERMDEB_APP_DIR=\"${TERMDEB_PREFIX%/files/usr}\"\n" +
        "TERMDEB_HOME=\"${TERMDEB_APP_DIR}/files/home\"\n" +
        "TERMDEB_UROOT=\"${TERMDEB_APP_DIR}/files/debian-root\"\n" +
        "PROOT=\"${TERMDEB_PREFIX}/bin/proot\"\n" +
        "BOX64=\"${TERMDEB_PREFIX}/bin/box64\"";

    /**
     * Real-root detection for the shell scripts. Must NOT use `command -v su`: the
     * Termux bootstrap ships its own su script in $PREFIX/bin (a termux-user switch,
     * not an Android root grant), so a bare su lookup is always "true" and would make
     * every session try (and fail) native mode. Only uid 0 or a known Android root
     * daemon/binary counts as real root.
     */
    private static final String TERMDEB_HAS_ROOT_FN =
        "termdeb_has_root() {\n" +
        "  [ \"$(id -u)\" = \"0\" ] && return 0\n" +
        "  for p in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /system/bin/magisk /data/adb/magisk/magisk /data/adb/ksu/bin/ksud; do\n" +
        "    [ -x \"$p\" ] && return 0\n" +
        "  done\n" +
        "  return 1\n" +
        "}";

    /**
     * Shared body of the termdeb-syscheck diagnostics command. Installed in both the
     * host ($PREFIX/bin) and the guest (/usr/local/bin) with the appropriate shebang.
     * Context-aware: when run inside the Debian guest it reports guest state; when run
     * on the host it reports the device capabilities that decide whether systemd can
     * run as PID 1 (native mode) or whether TermDeb must fall back to proot mode.
     */
    private static final String TERMDEB_SYSCHECK_BODY =
        "# termdeb-syscheck - TermDeb runtime & systemd capability diagnostics\n" +
        "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
        "# ---- Inside the Debian guest? ----\n" +
        "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ] && [ -x /usr/bin/apt-get ]; then\n" +
        "  echo \"=== TermDeb Guest Diagnostics ===\"\n" +
        "  . /etc/os-release 2>/dev/null\n" +
        "  echo \"Debian:            ${PRETTY_NAME:-unknown}\"\n" +
        "  echo \"Kernel (host):     $(uname -r) $(uname -m)\"\n" +
        "  echo \"PID 1:             $(cat /proc/1/comm 2>/dev/null || echo unknown)\"\n" +
        "  echo \"systemd binary:    $(test -x /usr/lib/systemd/systemd && echo present || echo 'missing (provisioning pending)')\"\n" +
        "  echo \"systemd running:   $(test -d /run/systemd/system && echo yes || echo no)\"\n" +
        "  if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then\n" +
        "    echo \"systemd version:   $(systemctl --version 2>/dev/null | head -1)\"\n" +
        "  fi\n" +
        "  echo \"/proc:              $(mountpoint -q /proc 2>/dev/null && echo mounted || echo 'not mounted')\"\n" +
        "  echo \"/sys/fs/cgroup:     $(test -d /sys/fs/cgroup && echo present || echo missing)\"\n" +
        "  echo \"Box64:             $(/usr/local/bin/box64 --version 2>&1 | head -1 || echo 'not runnable')\"\n" +
        "  echo \"Box64 layer:       $(test -x /usr/local/libexec/termdeb-x86_64-launcher && test -x /usr/local/bin/termdeb-wrap64 && echo 'on-disk wrapping (termdeb-wrap64)' || echo 'NOT INSTALLED')\"\n" +
        "  echo \"Wrap watcher:      $(test -x /usr/local/libexec/termdeb-wrapd && (pgrep -f termdeb-wrapd >/dev/null 2>&1 && echo 'running (termdeb-wrapd, inotify)' || echo 'installed (not running)') || echo MISSING)\"\n" +
        "  echo \"Wrapped x86_64:    $(find /root /opt /usr/local -name '.termdeb-x86_64' -type d 2>/dev/null | wc -l) dir(s) (launcher replaces each x86_64 binary)\"\n" +
        "  echo \"LD_PRELOAD:        ${LD_PRELOAD:-none (clean - no global interposer)}\"\n" +
        "  echo \"x86_64 glibc libs: $(test -f /usr/lib/x86_64-linux-gnu/libc.so.6 && echo present || echo MISSING)\"\n" +
        "  echo \"DNS:               $(getent hosts deb.debian.org >/dev/null 2>&1 && echo working || echo FAILING)\"\n" +
        "  echo \"apt:               $(test -x /usr/bin/apt-get && echo present || echo missing)\"\n" +
        "  exit 0\n" +
        "fi\n\n" +
        "# ---- Host context ----\n" +
        TERMDEB_PATHS + "\n" +
        TERMDEB_HAS_ROOT_FN + "\n\n" +
        "echo \"=== TermDeb Runtime Diagnostics ===\"\n" +
        "echo \"User:              uid=$(id -u) ($(id -un 2>/dev/null || echo '?'))\"\n" +
        "if [ \"$(id -u)\" = \"0\" ]; then\n" +
        "  ROOT_LINE=\"yes (process is root)\"\n" +
        "elif termdeb_has_root; then\n" +
        "  ROOT_LINE=\"yes (su/magisk available)\"\n" +
        "else\n" +
        "  ROOT_LINE=\"no (no root on this device - systemd unavailable)\"\n" +
        "fi\n" +
        "echo \"Root (su):         $ROOT_LINE\"\n" +
        "echo \"Kernel:            $(uname -r) $(uname -m)\"\n" +
        "echo \"SELinux:           $(getenforce 2>/dev/null || echo 'not readable')\"\n" +
        "echo \"Cgroup v2:         $(test -f /sys/fs/cgroup/cgroup.controllers && echo present || echo absent)\"\n" +
        "echo \"PID namespaces:    $(unshare --pid --fork true 2>/dev/null && echo available || echo 'NOT available (Android blocks unshare)')\"\n" +
        "echo \"Mount capability:  $(test -w /proc/sys && echo 'writable /proc/sys' || echo 'not writable (no mount capability)')\"\n" +
        "echo \"Debian rootfs:     $(test -f ${TERMDEB_UROOT}/etc/os-release && echo present || echo MISSING)\"\n" +
        "echo \"Merged /usr:       $(test -L ${TERMDEB_UROOT}/bin && test -L ${TERMDEB_UROOT}/lib64 && echo ok || echo 'incomplete')\"\n" +
        "echo \"Guest DNS:         $(test -s ${TERMDEB_UROOT}/etc/resolv.conf && echo configured || echo EMPTY)\"\n" +
        "echo \"systemd (guest):   $(test -x ${TERMDEB_UROOT}/usr/lib/systemd/systemd && echo installed || echo 'not yet (provision on next Debian start)')\"\n" +
        "echo \"Box64 (host):      $(test -x ${TERMDEB_PREFIX}/bin/box64 && echo present || echo MISSING)\"\n" +
        "echo \"Wrap watcher:      $(test -x ${TERMDEB_PREFIX}/bin/termdeb-wrapd && (pgrep -f termdeb-wrapd >/dev/null 2>&1 && echo 'running (termdeb-wrapd, inotify)' || echo 'installed (not running)') || echo MISSING)\"\n" +
        "echo \"proot:             $(test -x ${TERMDEB_PREFIX}/bin/proot && echo present || echo MISSING)\"\n" +
        "echo \"\"\n" +
        "if termdeb_has_root; then\n" +
        "  if test -f /sys/fs/cgroup/cgroup.controllers && unshare --pid --fork true 2>/dev/null; then\n" +
        "    echo \"SYSTEMD: AVAILABLE - TermDeb starts Debian with systemd as PID 1 (native mode).\"\n" +
        "  else\n" +
        "    echo \"SYSTEMD: NOT AVAILABLE - root exists but the kernel blocks PID namespaces or cgroups.\"\n" +
        "    echo \"  TermDeb uses proot mode instead. Unlock/root your device for systemd.\"\n" +
        "  fi\n" +
        "else\n" +
        "  echo \"SYSTEMD: NOT AVAILABLE - no root on this device; Android keeps apps unprivileged.\"\n" +
        "  echo \"  TermDeb uses proot mode (full Debian userspace, no systemd).\"\n" +
        "  echo \"  To get systemd: root the device (Magisk/KernelSU) and grant TermDeb root.\"\n" +
        "fi\n" +
        "exit 0\n";

    /**
     * Shared body of the termdeb-provision command. Installed in both the host
     * ($PREFIX/bin) and the guest (/usr/local/bin) with the appropriate shebang.
     * Context-aware: when run inside the Debian guest it runs apt to install systemd,
     * dbus, ca-certificates and locales (a complete userspace); when run on the host it
     * launches the guest copy through proot. Idempotent and resumable: the marker
     * /etc/termdeb-provisioned is written only on success, so an interrupted or offline
     * provisioning retries on the next session start.
     */
    private static final String TERMDEB_PROVISION_BODY =
        "# termdeb-provision - Provision the Debian userspace (systemd, dbus, ...)\n" +
        "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
        "# ---- Inside the Debian guest? ----\n" +
        "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ] && [ -x /usr/bin/apt-get ]; then\n" +
        "  [ -f /etc/termdeb-provisioned ] && { echo \"TermDeb: Debian userspace already provisioned.\"; exit 0; }\n" +
        "\n" +
        "  # Check if packages are already present (pre-provisioned rootfs bundled in APK).\n" +
        "  # When the rootfs ships with systemd/dbus/ca-certificates/locales pre-installed,\n" +
        "  # skip the slow apt-get network calls and write the marker directly.\n" +
        "  if [ -x /usr/lib/systemd/systemd ] && [ -x /usr/bin/dbus-daemon ]; then\n" +
        "    # Generate locale if missing (fast, no network needed). locale-gen\n" +
        "    # reads /etc/locale.gen, so enable it explicitly first.\n" +
        "    if ! locale -a 2>/dev/null | grep -qE 'en_US.UTF-8|en_US.utf8' && command -v locale-gen >/dev/null 2>&1; then\n" +
        "      if [ -f /etc/locale.gen ] && ! grep -qE '^en_US.UTF-8[[:space:]]+UTF-8' /etc/locale.gen; then\n" +
        "        printf 'en_US.UTF-8 UTF-8\\n' >> /etc/locale.gen\n" +
        "      fi\n" +
        "      timeout 120 locale-gen >/dev/null 2>&1 || true\n" +
        "    fi\n" +
        "    touch /etc/termdeb-provisioned\n" +
        "    echo \"TermDeb: Debian userspace already provisioned (packages bundled in rootfs).\"\n" +
        "    exit 0\n" +
        "  fi\n" +
        "\n" +
        "  # Packages not present - fall back to apt-get (online provisioning).\n" +
        "  # Debian trixie ships deb822 debian.sources; disable a duplicate legacy\n" +
        "  # sources.list while preserving it for diagnostics.\n" +
        "  if [ -s /etc/apt/sources.list.d/debian.sources ] && [ -f /etc/apt/sources.list ]; then\n" +
        "    mv /etc/apt/sources.list /etc/apt/sources.list.termdeb-disabled\n" +
        "  fi\n" +
        "  mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial /usr/share/man/man1\n" +
        "  export DEBIAN_FRONTEND=noninteractive\n" +
        "  export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
        "  echo \"TermDeb: running apt-get update...\"\n" +
        "  timeout 180 apt-get update -qq 2>&1 | tail -2\n" +
        "  rc=${PIPESTATUS[0]:-$?}\n" +
        "  if [ \"$rc\" != \"0\" ]; then\n" +
        "    echo \"TermDeb: apt-get update failed (offline?). Provisioning will retry on the next start.\"\n" +
        "    exit 1\n" +
        "  fi\n" +
        "  echo \"TermDeb: installing systemd, dbus, ca-certificates, locales...\"\n" +
        "  timeout 600 apt-get install -y --no-install-recommends systemd dbus ca-certificates locales 2>&1 | tail -3\n" +
        "  rc=${PIPESTATUS[0]:-$?}\n" +
        "  # dpkg maintainer scripts (systemd postinst) may warn under proot; what matters\n" +
        "  # is that the systemd binary landed in the guest.\n" +
        "  if [ \"$rc\" != \"0\" ] && [ ! -x /usr/lib/systemd/systemd ]; then\n" +
        "    echo \"TermDeb: package installation failed; provisioning will retry on the next start.\"\n" +
        "    exit 1\n" +
        "  fi\n" +
        "  # Ensure an en_US.UTF-8 locale exists (Debian trixie ships a minimal\n" +
        "  # locale-archive; regenerate only if needed). locale-gen can be very slow\n" +
        "  # under proot, so it is time-boxed - a timeout here must not block the\n" +
        "  # session or the marker.\n" +
        "  if ! locale -a 2>/dev/null | grep -qE 'en_US.UTF-8|en_US.utf8' && command -v locale-gen >/dev/null 2>&1; then\n" +
        "    if [ -f /etc/locale.gen ] && ! grep -qE '^en_US.UTF-8[[:space:]]+UTF-8' /etc/locale.gen; then\n" +
        "      printf 'en_US.UTF-8 UTF-8\\n' >> /etc/locale.gen\n" +
        "    fi\n" +
        "    timeout 120 locale-gen >/dev/null 2>&1 || true\n" +
        "  fi\n" +
        "  touch /etc/termdeb-provisioned\n" +
        "  echo \"TermDeb: Debian userspace provisioned - systemd is now installed in the guest.\"\n" +
        "  # The transparent Box64 layer is refreshed on the HOST by termdeb-debian at\n" +
        "  # every session start (no proot overhead), so nothing to do here.\n" +
        "  exit 0\n" +
        "fi\n\n" +
        "# ---- Host context: launch the guest copy through proot ----\n" +
        TERMDEB_PATHS + "\n" +
        "[ -d \"${TERMDEB_UROOT}\" ] || { echo \"TermDeb: Debian rootfs missing.\"; exit 1; }\n" +
        "[ -x \"${PROOT}\" ] || { echo \"TermDeb: proot binary not found at ${PROOT} - cannot provision Debian.\"; exit 1; }\n\n" +
        "mkdir -p \"${TERMDEB_HOME}/storage\" \"${TERMDEB_PREFIX}/tmp\" \"${TERMDEB_PREFIX}/tmp/shm\"\n" +
        "export PROOT_TMP_DIR=\"${TERMDEB_PREFIX}/tmp\"\n" +
        "\"${PROOT}\" \\\n" +
        "  --link2symlink --kill-on-exit --root-id --cwd=/root \\\n" +
        "  -b /dev -b /proc -b /sys \\\n" +
        "  -b \"${TERMDEB_HOME}:/root\" \\\n" +
        "  -b \"${TERMDEB_HOME}/storage:/mnt/storage\" \\\n" +
        "  -b \"/storage/emulated/0:/sdcard\" \\\n" +
        "  -b \"${TERMDEB_PREFIX}/tmp:/tmp\" \\\n" +
        "  -b \"${TERMDEB_PREFIX}/tmp/shm:/dev/shm\" \\\n" +
        "  -b \"${BOX64}:/usr/local/bin/box64\" \\\n" +
        "  -b \"${TERMDEB_PREFIX}/etc/resolv.conf:/etc/resolv.conf\" \\\n" +
        "  -r \"${TERMDEB_UROOT}\" \\\n" +
        "  /usr/bin/env -i HOME=/root USER=root TERM=\"${TERM:-xterm-256color}\" \\\n" +
        "  LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
        "  /usr/local/bin/termdeb-provision\n" +
        "exit $?\n";

    /**
     * Shared body of the termdeb-wrap64 command - TermDeb's transparent Box64
     * execution layer. Installed in both the host ($PREFIX/bin) and the guest
     * (/usr/local/bin) with the appropriate shebang.
     *
     * Every x86_64 ELF executable is replaced on disk by an ARM64 launcher (the
     * original moves to <dir>/.termdeb-x86_64/). Because the file is now an ARM64
     * binary, ALL exec mechanisms work - execve, execvp, posix_spawn, JDK 17's
     * jspawnhelper, direct kernel exec - with no LD_PRELOAD and no interposer, and
     * native ARM64 binaries are never touched. New x86_64 tools (e.g. an Android
     * SDK aapt2) then "just work", including as subprocesses of native ARM64
     * parents like Gradle's JVM.
     */
    private static final String TERMDEB_WRAP64_BODY =
        "# termdeb-wrap64 - TermDeb transparent Box64 execution layer\n" +
        "#\n" +
        "# Scans directories for x86_64 (Intel/AMD) ELF *executables* and replaces\n" +
        "# each with an ARM64 launcher that routes execution through box64\n" +
        "# automatically:\n" +
        "#\n" +
        "#   aapt2  ->  (launcher, ARM64)   +   .termdeb-x86_64/aapt2  (real x86_64)\n" +
        "#\n" +
        "# Because the file on disk is now an ARM64 binary, EVERY exec mechanism works\n" +
        "# - execve, execvp, posix_spawn, JDK 17's jspawnhelper, direct kernel exec -\n" +
        "# no LD_PRELOAD, no interposer, no binfmt_misc required. Native ARM64\n" +
        "# binaries, scripts and shared libraries (.so) are never touched. New x86_64\n" +
        "# tools (e.g. an Android SDK aapt2, including the copy the Android Gradle\n" +
        "# Plugin downloads into ~/.gradle/caches) just work, including as\n" +
        "# subprocesses of native ARM64 parents like Gradle's JVM.\n" +
        "#\n" +
        "# The Android SDK is discovered dynamically, so it works regardless of what\n" +
        "# the folder is called or where it lives: $ANDROID_HOME/$ANDROID_SDK_ROOT,\n" +
        "# ANY directory under $HOME containing a build-tools/ folder, any\n" +
        "# local.properties sdk.dir found under $HOME (guest /root paths are\n" +
        "# translated), and common locations on /sdcard (host /storage/emulated/0).\n" +
        "# `termdeb-wrap64 --print-sdk-roots` prints the discovered roots one per\n" +
        "# line so the caller can hand them to termdeb-wrapd for real-time watching.\n" +
        "#\n" +
        "# The heavy lifting is done by the compiled termdeb-scan64 (raw syscalls, no\n" +
        "# per-file process spawning), so scans stay fast even inside proot. Its\n" +
        "# freshness state lives in a sidecar file in the app's own data dir - the\n" +
        "# layer never writes anything into user/project directories (an earlier\n" +
        "# stamp-file design broke `aapt2 link`, which consumes whole directories).\n" +
        "#\n" +
        "# Usage: termdeb-wrap64 [dir...]\n" +
        "#   With no arguments, scans the PATH directories plus /usr/local, /opt,\n" +
        "#   $HOME (recursively), the Gradle/Android caches and the discovered SDK\n" +
        "#   roots. Explicit dir arguments replace the defaults.\n" +
        "#   termdeb-wrap64 --print-sdk-roots   Prints discovered SDK roots (1/line).\n" +
        "#\n" +
        "# Copyright (c) TermDeb Contributors - MIT License\n" +
        "\n" +
        "# ---- Context: inside the Debian guest or on the host? ----\n" +
        "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n" +
        "    # Inside the Debian guest.\n" +
        "    LAUNCHER=/usr/local/libexec/termdeb-x86_64-launcher\n" +
        "    SCANNER=/usr/local/libexec/termdeb-scan64\n" +
        "    STATE=/var/lib/termdeb/scan-state\n" +
        "    HOME_D=\"${HOME:-/root}\"\n" +
        "    SDCARD_ROOT=/sdcard\n" +
        "    DEFAULT_ROOTS=()\n" +
        "    IFS=: read -r -a DEFAULT_ROOTS <<< \"$PATH\"\n" +
        "    DEFAULT_ROOTS+=(\"/usr/local\" \"/opt\")\n" +
        "    DEFAULT_ROOTS+=(\"$HOME_D\")\n" +
        "else\n" +
        "    # On the host: operate directly on the app data dir (no proot needed).\n" +
        "    TERMDEB_PREFIX=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}\"\n" +
        "    TERMDEB_APP_DIR=\"${TERMDEB_PREFIX%/files/usr}\"\n" +
        "    TERMDEB_HOME=\"${TERMDEB_APP_DIR}/files/home\"\n" +
        "    TERMDEB_UROOT=\"${TERMDEB_APP_DIR}/files/debian-root\"\n" +
        "    LAUNCHER=\"${TERMDEB_UROOT}/usr/local/libexec/termdeb-x86_64-launcher\"\n" +
        "    SCANNER=\"${TERMDEB_UROOT}/usr/local/libexec/termdeb-scan64\"\n" +
        "    STATE=\"${TERMDEB_APP_DIR}/files/termdeb-scan-state\"\n" +
        "    HOME_D=\"${TERMDEB_HOME}\"\n" +
        "    SDCARD_ROOT=/storage/emulated/0\n" +
        "    DEFAULT_ROOTS=(\"${TERMDEB_UROOT}/usr/local\" \"${TERMDEB_UROOT}/usr/bin\"\n" +
        "                   \"${TERMDEB_UROOT}/usr/sbin\" \"${TERMDEB_UROOT}/opt\" \"${TERMDEB_HOME}\")\n" +
        "fi\n" +
        "\n" +
        "# Hidden binary caches that hold x86_64 toolchain binaries: the Android Gradle\n" +
        "# Plugin downloads its own aapt2/aidl/zipalign into\n" +
        "# ~/.gradle/caches/<version>/transforms and spawns them as subprocesses of the\n" +
        "# (native ARM64) JVM - they must be wrapped too. The general recursion skips\n" +
        "# hidden dirs for speed, so list them explicitly.\n" +
        "if [ -n \"${HOME_D:-}\" ]; then\n" +
        "    for t in \"$HOME_D\"/.gradle/caches/*/transforms; do\n" +
        "        [ -d \"$t\" ] && DEFAULT_ROOTS+=(\"$t\")\n" +
        "    done\n" +
        "    [ -d \"$HOME_D/.android\" ] && DEFAULT_ROOTS+=(\"$HOME_D/.android\")\n" +
        "fi\n" +
        "\n" +
        "# ---- SDK root discovery (name- and location-independent) ----\n" +
        "SDK_ROOTS=()\n" +
        "\n" +
        "sdk_root_contains() {\n" +
        "    local r\n" +
        "    for r in \"${SDK_ROOTS[@]}\"; do [ \"$r\" = \"$1\" ] && return 0; done\n" +
        "    return 1\n" +
        "}\n" +
        "\n" +
        "sdk_add_dir() {\n" +
        "    [ -n \"$1\" ] && [ -d \"$1\" ] || return\n" +
        "    sdk_root_contains \"$1\" || SDK_ROOTS+=(\"$1\")\n" +
        "}\n" +
        "\n" +
        "# Read sdk.dir from a local.properties, translating guest /root -> $HOME_D.\n" +
        "sdk_add_local_properties() {\n" +
        "    local lp sd\n" +
        "    [ -f \"$1\" ] || return\n" +
        "    sd=$(sed -n 's/^sdk\\.dir[[:space:]]*=[[:space:]]*//p' \"$1\" 2>/dev/null | head -1)\n" +
        "    [ -n \"$sd\" ] || return\n" +
        "    case \"$sd\" in\n" +
        "        /root/*) sd=\"${HOME_D}${sd#/root}\" ;;\n" +
        "    esac\n" +
        "    sdk_add_dir \"$sd\"\n" +
        "}\n" +
        "\n" +
        "discover_sdk_roots() {\n" +
        "    # 1. Explicit environment.\n" +
        "    sdk_add_dir \"$ANDROID_HOME\"\n" +
        "    sdk_add_dir \"$ANDROID_SDK_ROOT\"\n" +
        "    # 2. ANY directory under $HOME containing build-tools (whatever it is\n" +
        "    #    called): the parent of each build-tools dir is an SDK root.\n" +
        "    local bt\n" +
        "    while IFS= read -r bt; do\n" +
        "        [ -n \"$bt\" ] || continue\n" +
        "        sdk_add_dir \"$(dirname \"$bt\")\"\n" +
        "    done < <(find \"$HOME_D\" -maxdepth 5 -type d -name build-tools 2>/dev/null)\n" +
        "    # 3. local.properties sdk.dir references under $HOME (projects the user\n" +
        "    #    cloned/placed there).\n" +
        "    local lp\n" +
        "    while IFS= read -r lp; do\n" +
        "        sdk_add_local_properties \"$lp\"\n" +
        "    done < <(find \"$HOME_D\" -maxdepth 3 -name local.properties -type f 2>/dev/null)\n" +
        "    # 4. /sdcard (host: /storage/emulated/0): common locations + shallow\n" +
        "    #    local.properties scan (a full sdcard walk every session is too\n" +
        "    #    costly, so only look in likely places and project markers).\n" +
        "    if [ -d \"$SDCARD_ROOT\" ] && [ -r \"$SDCARD_ROOT\" ]; then\n" +
        "        local p\n" +
        "        for p in android-sdk Android/Sdk sdk tools termdeb-sdk Termux Android/Sdk/platform-tools; do\n" +
        "            sdk_add_dir \"$SDCARD_ROOT/$p\"\n" +
        "        done\n" +
        "        while IFS= read -r lp; do\n" +
        "            sdk_add_local_properties \"$lp\"\n" +
        "        done < <(find \"$SDCARD_ROOT\" -maxdepth 2 -name local.properties -type f 2>/dev/null)\n" +
        "    fi\n" +
        "}\n" +
        "\n" +
        "# Append discovered SDK roots to the default scan set (dedup).\n" +
        "sdk_append_to_defaults() {\n" +
        "    local r\n" +
        "    for r in \"${SDK_ROOTS[@]}\"; do\n" +
        "        sdk_root_contains \"$r\" && continue\n" +
        "        DEFAULT_ROOTS+=(\"$r\")\n" +
        "    done\n" +
        "}\n" +
        "\n" +
        "main() {\n" +
        "    if [ ! -x \"$LAUNCHER\" ]; then\n" +
        "        echo \"termdeb-wrap64: launcher missing at $LAUNCHER - reinstall TermDeb\" >&2\n" +
        "        return 1\n" +
        "    fi\n" +
        "    if [ ! -x \"$SCANNER\" ]; then\n" +
        "        echo \"termdeb-wrap64: scanner missing at $SCANNER - reinstall TermDeb\" >&2\n" +
        "        return 1\n" +
        "    fi\n" +
        "    discover_sdk_roots\n" +
        "    if [ \"$1\" = \"--print-sdk-roots\" ]; then\n" +
        "        local r\n" +
        "        for r in \"${SDK_ROOTS[@]}\"; do echo \"$r\"; done\n" +
        "        return 0\n" +
        "    fi\n" +
        "    sdk_append_to_defaults\n" +
        "    local roots=()\n" +
        "    if [ \"$#\" -gt 0 ]; then\n" +
        "        roots=(\"$@\")\n" +
        "    else\n" +
        "        roots=(\"${DEFAULT_ROOTS[@]}\")\n" +
        "    fi\n" +
        "    local existing=()\n" +
        "    local r\n" +
        "    for r in \"${roots[@]}\"; do\n" +
        "        [ -n \"$r\" ] || continue\n" +
        "        [ -d \"$r\" ] || continue\n" +
        "        existing+=(\"$r\")\n" +
        "    done\n" +
        "    if [ \"${#existing[@]}\" -gt 0 ]; then\n" +
        "        mkdir -p \"$(dirname \"$STATE\")\" 2>/dev/null || true\n" +
        "        \"$SCANNER\" \"$LAUNCHER\" \"$STATE\" \"${existing[@]}\"\n" +
        "    fi\n" +
        "    return 0\n" +
        "}\n" +
        "\n" +
        "main \"$@\"\n" +
        "\n";

    /**
     * Check if TermDeb offline runtime assets are bundled in the APK.
     */
    public static boolean hasTermDebAssets(Context context) {
        try {
            String[] assets = context.getAssets().list(TERMDEB_ASSETS_DIR);
            return assets != null && assets.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the TermDeb environment version from bundled assets.
     */
    public static String getTermDebVersion(Context context) {
        try {
            java.io.InputStream is = context.getAssets().open(TERMDEB_VERSION_FILE);
            byte[] data = new byte[is.available()];
            is.read(data);
            is.close();
            String json = new String(data);
            // Simple JSON parsing - extract termdeb_version value
            int idx = json.indexOf("\"termdeb_version\"");
            if (idx > 0) {
                int colonIdx = json.indexOf(":", idx);
                int quoteStart = json.indexOf("\"", colonIdx + 1);
                int quoteEnd = json.indexOf("\"", quoteStart + 1);
                return json.substring(quoteStart + 1, quoteEnd);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read TermDeb version: " + e.getMessage());
        }
        return "unknown";
    }

    /**
     * Install TermDeb offline runtime components.
     * This extracts Debian rootfs, proot-distro, Box64, and configuration
     * files from the APK assets to the app's data directory.
     *
     * This must be called during first-run initialization to ensure
     * the device can operate completely offline.
     */
    public static void installTermDebRuntime(Activity activity, Runnable whenDone) {
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing TermDeb offline runtime...");

                    Context context = activity.getApplicationContext();

                    // proot is the Debian launch path. proot-distro is bundled as
                    // reference scripts only: it is a Python program and the Termux
                    // bootstrap ships no python3, so it cannot launch the guest here.
                    File filesDir = context.getFilesDir();
                    File debianDir = new File(filesDir, "debian-root");
                    File readyMarker = new File(filesDir, ".termdeb-runtime-ready");

                    // Fast path: a previous install already validated the runtime. Just make
                    // sure the launch scripts are present and skip the heavy work.
                    boolean alreadyReady = readyMarker.isFile() && new File(debianDir, "etc/os-release").isFile();
                    if (alreadyReady) {
                        Logger.logInfo(LOG_TAG, "TermDeb runtime already installed and validated (fast path).");
                        try {
                            installTermDebShellScripts(context, filesDir);
                            installTermDebGuestScripts(context, filesDir);
                            installTermDebAdbClient(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Shell script refresh failed (non-fatal): " + e.getMessage());
                        }
                    } else {
                        // Resumable, interruption-safe installation:
                        //  * The Debian rootfs is extracted into a staging directory and only
                        //    renamed to debian-root after the whole archive was extracted, so an
                        //    interrupted install never leaves a half-written rootfs. A leftover
                        //    staging directory is removed on the next launch and extraction retried.
                        //  * Every configuration step below is idempotent, so a relaunch after an
                        //    interruption simply continues where it left off.
                        //  * The .termdeb-runtime-ready marker is written only after the whole
                        //    runtime has been validated (validateTermDebRuntime()).

                        File stagingDir = new File(filesDir, "debian-root.staging");
                        if (stagingDir.exists()) {
                            Logger.logWarn(LOG_TAG, "Removing leftover staging dir from an interrupted extraction");
                            FileUtils.deleteFile("termdeb debian staging directory", stagingDir.getAbsolutePath(), true);
                        }

                        // Extract proot binary and its runtime libraries (non-fatal if missing).
                        // proot is NOT part of the core Termux bootstrap, so the offline runtime
                        // bundles it under termdeb-runtime/proot/{bin,libexec,lib}.
                        // NOTE: do NOT use extractAssetDirectory() here - it flattens the directory
                        // structure, so proot's loaders would land at $PREFIX/libexec/loader instead
                        // of $PREFIX/libexec/proot/loader, which is what PROOT_LOADER references.
                        try {
                            extractAsset(context, TERMDEB_PROOT_DIR + "/bin/proot", new File(filesDir, "usr/bin/proot"));
                            extractAsset(context, TERMDEB_PROOT_DIR + "/libexec/loader", new File(filesDir, "usr/libexec/proot/loader"));
                            extractAsset(context, TERMDEB_PROOT_DIR + "/libexec/loader32", new File(filesDir, "usr/libexec/proot/loader32"));
                            extractAssetDirectory(context, TERMDEB_PROOT_DIR + "/lib", new File(filesDir, "usr/lib"));
                            Logger.logInfo(LOG_TAG, "proot extracted successfully");
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "proot extraction failed (non-fatal): " + e.getMessage());
                        }

                        // Extract Box64 binary (non-fatal if missing)
                        try {
                            extractAsset(context, TERMDEB_BOX64_DIR + "/box64",
                                new File(filesDir, "usr/bin/box64"));
                            Logger.logInfo(LOG_TAG, "Box64 extracted successfully");
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Box64 extraction failed (non-fatal): " + e.getMessage());
                        }

                        // Extract the continuous wrap watcher (termdeb-wrapd). It runs on the
                        // HOST at session start (backgrounded by termdeb-debian) and keeps the
                        // transparent Box64 layer up to date while the session runs - AGP
                        // re-extracts x86_64 tools into fresh Gradle transform dirs during a
                        // build, which a one-shot scan cannot see. (Non-fatal if missing.)
                        try {
                            extractAsset(context, TERMDEB_BOX64_DIR + "/termdeb-wrapd",
                                new File(filesDir, "usr/bin/termdeb-wrapd"));
                            setExecutable(new File(filesDir, "usr/bin/termdeb-wrapd"));
                            Logger.logInfo(LOG_TAG, "termdeb-wrapd extracted successfully");
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "termdeb-wrapd extraction failed (non-fatal): " + e.getMessage());
                        }

                        // Extract proot-distro scripts (non-fatal if missing)
                        try {
                            extractAssetDirectory(context, TERMDEB_PROOT_DISTRO_DIR,
                                new File(filesDir, "usr/share/proot-distro"));
                            Logger.logInfo(LOG_TAG, "proot-distro extracted successfully");
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "proot-distro extraction failed (non-fatal): " + e.getMessage());
                        }

                        // Extract Debian rootfs (large file). Extract into staging, then rename
                        // atomically so an interrupted extraction is detected and retried on the
                        // next launch instead of leaving a corrupt rootfs.
                        if (!debianDir.exists()) {
                            try {
                                if (!stagingDir.mkdirs() && !stagingDir.isDirectory()) {
                                    throw new Exception("Failed to create staging dir " + stagingDir.getAbsolutePath());
                                }
                                extractDebianRootfs(context, stagingDir);
                                if (!stagingDir.renameTo(debianDir)) {
                                    throw new Exception("Failed to move staged rootfs to " + debianDir.getAbsolutePath());
                                }
                                Logger.logInfo(LOG_TAG, "Debian rootfs extracted and staged successfully");
                            } catch (Exception e) {
                                Logger.logError(LOG_TAG, "Debian rootfs extraction failed (non-fatal, will retry on next launch): " + e.getMessage());
                            }
                        }

                        // Configure the extracted Debian rootfs: welcome message, filesystem
                        // layout, merged /usr, Android supplementary group names and DNS
                        // servers (idempotent, non-fatal).
                        try {
                            configureDebianRootfs(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Debian rootfs configuration failed (non-fatal): " + e.getMessage());
                        }

                        // Extract the x86_64 glibc runtime libraries into the guest. box64 needs
                        // these (libc, ld-linux-x86-64, libgcc_s, libstdc++, zlib) to run
                        // dynamically-linked x86_64 binaries such as aapt2 (non-fatal if missing).
                        if (debianDir.exists()) {
                            try {
                                extractTarAsset(context, TERMDEB_BOX64_LIBS_DIR + "/box64-libs.tar.gz", debianDir);
                                Logger.logInfo(LOG_TAG, "Box64 x86_64 libraries extracted successfully");
                            } catch (Exception e) {
                                Logger.logError(LOG_TAG, "Box64 x86_64 libraries extraction failed (non-fatal): " + e.getMessage());
                            }

                            // Make the rootfs a proper merged-usr layout (/lib64 -> usr/lib64).
                            try {
                                fixupMergedUsr(filesDir);
                            } catch (Exception e) {
                                Logger.logError(LOG_TAG, "Merged /usr fixup failed (non-fatal): " + e.getMessage());
                            }
                        }

                        // Extract TermDeb configuration scripts (non-fatal)
                        try {
                            extractAssetDirectory(context, TERMDEB_CONFIG_DIR,
                                new File(filesDir, "usr/share/termdeb/config"));
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Config extraction failed (non-fatal): " + e.getMessage());
                        }

                        // Rewrite bootstrap script shebangs that point at the official
                        // "com.termux" prefix so fork commands (am, termux-am,
                        // termux-setup-storage, apt helpers, ...) can actually run.
                        try {
                            fixBootstrapShebangs(filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Bootstrap shebang fix failed (non-fatal): " + e.getMessage());
                        }

                        // Install TermDeb shell scripts into $PREFIX/bin/
                        try {
                            installTermDebShellScripts(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Shell script installation failed (non-fatal): " + e.getMessage());
                        }

                        // Install TermDeb shell scripts into the Debian guest (so
                        // termdeb-host / termdeb-storage / termdeb-provision / termdeb-syscheck
                        // work from inside Debian too).
                        try {
                            installTermDebGuestScripts(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Guest shell script installation failed (non-fatal): " + e.getMessage());
                        }

                        // Install the ADB bridge guest client as /usr/bin/adb so
                        // programs inside Debian use the normal adb interface.
                        try {
                            installTermDebAdbClient(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "ADB client installation failed (non-fatal): " + e.getMessage());
                        }

                        // Set executable permissions on binaries
                        setExecutable(new File(filesDir, "usr/bin/proot"));
                        setExecutable(new File(filesDir, "usr/libexec/proot/loader"));
                        setExecutable(new File(filesDir, "usr/libexec/proot/loader32"));
                        setExecutable(new File(filesDir, "usr/bin/box64"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-debian"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-host"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-storage"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-native"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-syscheck"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-provision"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-shell-wrapper"));
                        setExecutable(new File(filesDir, "usr/bin/termdeb-exec"));

                        // Setup Box64 ELF detection wrapper (non-fatal)
                        try {
                            setupElfWrapper(filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "ELF wrapper setup failed (non-fatal): " + e.getMessage());
                        }

                        // Write the install-time runtime facts consumed by the shell scripts and
                        // the diagnostics command (non-fatal).
                        try {
                            writeRuntimeConfigFile(context, filesDir);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Runtime config write failed (non-fatal): " + e.getMessage());
                        }

                        // Validate the whole runtime (Debian + merged usr + Box64 + proot + DNS).
                        // The ready marker is written ONLY if validation passes, so a failed or
                        // interrupted install is re-attempted on the next launch.
                        boolean valid = validateTermDebRuntime(filesDir);
                        if (valid) {
                            writeReadyMarker(filesDir);
                            Logger.logInfo(LOG_TAG, "TermDeb runtime installed AND validated successfully.");
                        } else {
                            Logger.logError(LOG_TAG, "TermDeb runtime validation FAILED - first start will re-attempt installation.");
                        }
                    }

                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "TermDeb runtime installation failed (non-fatal): " + e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Installation Error", e);
                    // Do NOT call showBootstrapErrorDialog here - it deletes the entire prefix
                    // directory including the bootstrap and libandroid-support.so. TermDeb
                    // offline features (Debian, Box64) are optional; the native shell must
                    // still work even if the offline runtime fails to install.
                }

                // ALWAYS launch the terminal session, even if offline runtime install failed.
                // The native TermDeb shell works fine with just the Termux bootstrap.
                activity.runOnUiThread(whenDone);
            }
        }.start();
    }

    /**
     * Extract a single asset file to a destination.
     */
    private static void extractAsset(Context context, String assetPath, File destFile) throws Exception {
        try {
            java.io.InputStream is = context.getAssets().open(assetPath);
            destFile.getParentFile().mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.close();
            is.close();
            Logger.logInfo(LOG_TAG, "Extracted asset: " + assetPath + " -> " + destFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract asset " + assetPath + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract a tar archive asset (tar.gz / tar.xz / plain tar, auto-detected)
     * into a destination directory. Used for the box64 x86_64 library bundle.
     * Note: AGP may transparently decompress .gz assets during packaging, so the
     * plain .tar fallback is required.
     */
    private static void extractTarAsset(Context context, String assetPath, File destDir) throws Exception {
        try {
            // AGP may transparently decompress .tar.gz assets to plain .tar during
            // packaging (it does for the debian rootfs and box64-libs), so fall back
            // to the uncompressed name if the .gz variant is not present.
            String actualAsset = assetPath;
            boolean assetExists = false;
            try (java.io.InputStream probe = context.getAssets().open(assetPath)) {
                assetExists = true;
            } catch (Exception ignored) { /* fall through */ }
            if (!assetExists && assetPath.endsWith(".tar.gz")) {
                String alt = assetPath.substring(0, assetPath.length() - 3);
                try (java.io.InputStream probe = context.getAssets().open(alt)) {
                    actualAsset = alt;
                } catch (Exception ignored) { /* fall through */ }
            }

            java.io.InputStream is = context.getAssets().open(actualAsset);
            File tmpTar = File.createTempFile("termdeb-asset", ".tar", context.getCacheDir());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpTar);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                total += read;
            }
            fos.close();
            is.close();

            // Flags must match the actual asset bytes, not the requested name:
            // AGP may have decompressed a .tar.gz to a plain .tar.
            String tarFlags;
            if (actualAsset.endsWith(".tar.xz")) {
                tarFlags = "-xJf";
            } else if (actualAsset.endsWith(".tar.gz") || actualAsset.endsWith(".tgz")) {
                tarFlags = "-xzf";
            } else {
                tarFlags = "-xf";
            }

            ProcessBuilder pb = new ProcessBuilder(
                "tar", tarFlags, tmpTar.getAbsolutePath(),
                "-C", destDir.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));
            while (reader.readLine() != null) { /* consume output */ }
            proc.waitFor();
            tmpTar.delete();
            if (proc.exitValue() != 0) {
                throw new Exception("tar extraction failed with exit code " + proc.exitValue()
                    + " (asset: " + actualAsset + ")");
            }
            Logger.logInfo(LOG_TAG, "Extracted tar asset: " + assetPath + " (" + (total / (1024 * 1024)) + " MB)");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract tar asset " + assetPath + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract an entire asset directory to a destination.
     */
    private static void extractAssetDirectory(Context context, String assetDir, File destDir) throws Exception {
        try {
            String[] files = context.getAssets().list(assetDir);
            if (files == null || files.length == 0) {
                // It's a file, not a directory - extract it
                extractAsset(context, assetDir, destDir);
                return;
            }
            destDir.mkdirs();
            for (String file : files) {
                String assetPath = assetDir + "/" + file;
                File destFile = new File(destDir, file);
                // Check if it's a file or directory
                String[] subFiles = context.getAssets().list(assetPath);
                if (subFiles != null && subFiles.length > 0) {
                    extractAssetDirectory(context, assetPath, destFile);
                } else {
                    extractAsset(context, assetPath, destFile);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract directory " + assetDir + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract Debian rootfs from bundled tar.xz assets.
     * Uses streaming extraction to avoid loading the entire archive into RAM.
     * The archive is extracted incrementally to handle large rootfs files.
     */
    private static void extractDebianRootfs(Context context, File destDir) throws Exception {
        Logger.logInfo(LOG_TAG, "Extracting Debian rootfs to " + destDir.getAbsolutePath());

        // List Debian rootfs assets
        String[] debianAssets = context.getAssets().list(TERMDEB_DEBIAN_DIR);
        if (debianAssets == null || debianAssets.length == 0) {
            Logger.logError(LOG_TAG, "No Debian rootfs assets found");
            return;
        }

        for (String asset : debianAssets) {
            if (asset.endsWith(".tar.xz") || asset.endsWith(".tar.gz") || asset.endsWith(".tar")) {
                // Extract the tarball using streaming
                java.io.InputStream is = context.getAssets().open(TERMDEB_DEBIAN_DIR + "/" + asset);
                File tarFile = new File(destDir, asset);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tarFile);
                byte[] buffer = new byte[65536];
                int read;
                long total = 0;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    total += read;
                    if (total % (10 * 1024 * 1024) == 0) {
                        Logger.logInfo(LOG_TAG, "  Extracted " + (total / (1024 * 1024)) + " MB...");
                    }
                }
                fos.close();
                is.close();

                Logger.logInfo(LOG_TAG, "Debian rootfs extracted: " + (total / (1024 * 1024)) + " MB");

                // Extract the tarball, picking the flag to match the archive compression
                try {
                    String tarFlags;
                    if (asset.endsWith(".tar.xz")) {
                        tarFlags = "-xJf";
                    } else if (asset.endsWith(".tar.gz") || asset.endsWith(".tgz")) {
                        tarFlags = "-xzf";
                    } else {
                        tarFlags = "-xf";
                    }
                    ProcessBuilder pb = new ProcessBuilder(
                        "tar", tarFlags, tarFile.getAbsolutePath(),
                        "-C", destDir.getAbsolutePath(),
                        "--strip-components=0"
                    );
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()));
                    while (reader.readLine() != null) { /* consume output */ }
                    proc.waitFor();

                    // Clean up the tarball after successful extraction
                    tarFile.delete();
                    Logger.logInfo(LOG_TAG, "Debian rootfs extracted successfully");
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Debian rootfs tar extraction failed: " + e.getMessage());
                    Logger.logInfo(LOG_TAG, "Debian rootfs tarball preserved at: " + tarFile.getAbsolutePath());
                }

                break; // Only extract one rootfs archive
            }
        }
    }

    /**
     * Configure the extracted Debian rootfs with TermDeb-specific files:
     * - A welcome message (/etc/profile.d/termdeb-welcome.sh) shown on login that
     *   informs the user about the system, box64 and storage.
     * - /etc/group entries for the Android supplementary group ids the process
     *   carries (inet, everybody, app cache/shared gids, ...). Without these the
     *   `groups`, `id` and `ls -l` commands inside Debian warn that they cannot
     *   find a name for the group ids, even though the groups themselves work.
     * - /etc/resolv.conf with the device's real DNS servers so that DNS resolution
     *   (and therefore all networking) works inside the guest. The Debian trixie
     *   rootfs ships an empty resolv.conf, so without this every name lookup in
     *   Debian fails with "Temporary failure in name resolution".
     * Non-fatal: the shell works fine even if this fails.
     */
    private static void configureDebianRootfs(Context context, File filesDir) {
        File debianRoot = new File(filesDir, "debian-root");
        if (!debianRoot.isDirectory()) return;

        // 1. Install the welcome message shown on Debian login (idempotent).
        try {
            File profileDir = new File(debianRoot, "etc/profile.d");
            profileDir.mkdirs();
            String welcome = "#!/bin/sh\n" +
                "# TermDeb welcome message (generated at install time)\n" +
                "[ -t 0 ] || return 0\n\n" +
                "# Report the actual runtime mode: systemd (native, real root) or proot.\n" +
                "if [ -d /run/systemd/system ] && [ -e /run/systemd/pid1 ]; then\n" +
                "  RUNTIME=\"systemd as PID 1 (native mode)\"\n" +
                "else\n" +
                "  RUNTIME=\"proot mode (no real root; systemd needs root, see 'termdeb-syscheck')\"\n" +
                "fi\n\n" +
                "echo \"\"\n" +
                "echo -e \"\\033[1;32m   Welcome to TermDeb Debian!\\033[0m\"\n" +
                "echo -e \"\\033[1;36m   OS:\\033[0m      Debian 13 (trixie) - ARM64 (full userspace)\"\n" +
                "echo -e \"\\033[1;36m   Runtime:\\033[0m \\$RUNTIME\"\n" +
                "echo \"\"\n" +
                "echo -e \"\\033[1;36m   Box64\\033[0m (x86_64 emulation):\"\n" +
                "echo \"   x86_64 (Intel/AMD) binaries run transparently through box64,\"\n" +
                "echo \"   including as subprocesses of ARM64 programs (Gradle/JDK,\"\n" +
                "echo \"   e.g. aapt2) - no prefix and no LD_PRELOAD needed.\"\n" +
                "echo -e \"   Manual use: \\033[1;33mbox64 ./program\\033[0m\"\n" +
                "echo \"\"\n" +
                "echo -e \"\\033[1;36m   Storage:\\033[0m\"\n" +
                "echo \"   Android shared storage is mounted at /sdcard\"\n" +
                "echo -e \"   Grant access with \\033[1;33m'termdeb-storage'\\033[0m (asks TermDeb for storage permission).\"\n" +
                "echo \"\"\n" +
                "echo -e \"   Run \\033[1;33m'termdeb-host'\\033[0m to return to the native TermDeb shell.\"\n" +
                "echo -e \"   Run \\033[1;33m'termdeb-syscheck'\\033[0m for systemd/kernel diagnostics.\"\n" +
                "echo \"\"\n";
            java.io.FileWriter welcomeWriter = new java.io.FileWriter(new File(profileDir, "termdeb-welcome.sh"));
            welcomeWriter.write(welcome);
            welcomeWriter.close();

            // 1b. Colorful prompt, ls/grep colors and colored man pages for the
            // Debian session. The guest shell emits no color codes by default
            // (plain PS1, uncolored ls), which is why the terminal looks white.
            String colors = "#!/bin/sh\n" +
                "# TermDeb color configuration (generated at install time)\n" +
                "[ -t 0 ] || return 0\n\n" +
                "# Colorful root prompt: user@host:cwd#\n" +
                "PS1='\\[\\e[1;32m\\]\\u\\[\\e[0m\\]@\\[\\e[1;36m\\]\\h\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n\n" +
                "# ls / grep colors\n" +
                "if command -v dircolors >/dev/null 2>&1; then\n" +
                "  eval \"$(dircolors -b 2>/dev/null)\"\n" +
                "fi\n" +
                "alias ls='ls --color=auto'\n" +
                "alias ll='ls -l --color=auto'\n" +
                "alias la='ls -la --color=auto'\n" +
                "alias l='ls --color=auto'\n" +
                "alias grep='grep --color=auto'\n" +
                "alias egrep='egrep --color=auto'\n" +
                "alias fgrep='fgrep --color=auto'\n\n" +
                "# Colored man pages\n" +
                "export LESS_TERMCAP_mb=$'\\e[1;31m'\n" +
                "export LESS_TERMCAP_md=$'\\e[1;36m'\n" +
                "export LESS_TERMCAP_me=$'\\e[0m'\n" +
                "export LESS_TERMCAP_se=$'\\e[0m'\n" +
                "export LESS_TERMCAP_so=$'\\e[1;33m'\n" +
                "export LESS_TERMCAP_ue=$'\\e[0m'\n" +
                "export LESS_TERMCAP_us=$'\\e[1;4;32m'\n";
            java.io.FileWriter colorsWriter = new java.io.FileWriter(new File(profileDir, "termdeb-colors.sh"));
            colorsWriter.write(colors);
            colorsWriter.close();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Welcome/color setup failed (non-fatal): " + e.getMessage());
        }

        // 2. Add Android supplementary group names to the guest /etc/group so that
        // `groups`, `id` and `ls -l` resolve names instead of printing warnings.
        try {
            File groupFile = new File(debianRoot, "etc/group");
            if (!groupFile.isFile()) return;

            StringBuilder existing = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(groupFile))) {
                String line;
                while ((line = reader.readLine()) != null) existing.append(line).append('\n');
            }
            String existingContent = existing.toString();
            StringBuilder toAppend = new StringBuilder();

            // Read the supplementary group ids of this process from /proc/self/status
            // (android.system.Os does not expose getgroups()).
            String groupsLine = null;
            try (java.io.BufferedReader statusReader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))) {
                String line;
                while ((line = statusReader.readLine()) != null) {
                    if (line.startsWith("Groups:")) { groupsLine = line; break; }
                }
            }
            if (groupsLine == null) return;

            String[] groupIds = groupsLine.substring("Groups:".length()).trim().split("\\s+");
            for (String groupIdStr : groupIds) {
                if (groupIdStr.isEmpty()) continue;
                int gid;
                try {
                    gid = Integer.parseInt(groupIdStr);
                } catch (NumberFormatException e) {
                    continue;
                }

                // Skip if this gid already has an entry
                if (existingContent.contains(":" + gid + ":")) continue;

                String name;
                switch (gid) {
                    case 3003: name = "inet"; break;
                    case 9997: name = "everybody"; break;
                    case 1015: name = "sdcard_rw"; break;
                    case 1016: name = "sdcard_r"; break;
                    default:
                        if (gid >= 20000 && gid < 30000) name = "u0_a" + (gid - 20000) + "_cache";
                        else if (gid >= 30000 && gid < 40000) name = "u0_a" + (gid - 30000) + "_ext";
                        else if (gid >= 40000 && gid < 50000) name = "u0_a" + (gid - 40000) + "_ext_cache";
                        else if (gid >= 50000 && gid < 60000) name = "u0_a" + (gid - 50000) + "_shared";
                        else name = "gid_" + gid;
                        break;
                }
                toAppend.append(name).append(":x:").append(gid).append(":\n");
            }

            if (toAppend.length() > 0) {
                java.io.FileWriter writer = new java.io.FileWriter(groupFile, true);
                writer.write(toAppend.toString());
                writer.close();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Android group setup failed (non-fatal): " + e.getMessage());
        }

        // 2.5. Filesystem layout for a proper Linux userspace. The Debian trixie rootfs
        // ships mostly-empty /dev, /proc, /sys and /run directories and is missing
        // /dev/pts and /dev/shm entirely. Create the full layout with correct
        // permissions so both proot mode (which binds into these) and native mode
        // (which mounts into them) work. Also write /etc/hosts, /etc/hostname and an
        // /etc/fstab documenting the expected mounts.
        try {
            ensureDir(new File(debianRoot, "dev/pts"), 0755);
            ensureDir(new File(debianRoot, "dev/shm"), 01777);
            ensureDir(new File(debianRoot, "proc"), 0555);
            ensureDir(new File(debianRoot, "sys"), 0555);
            ensureDir(new File(debianRoot, "run"), 0755);
            ensureDir(new File(debianRoot, "run/lock"), 01777);
            ensureDir(new File(debianRoot, "tmp"), 01777);
            ensureDir(new File(debianRoot, "var/tmp"), 01777);
            ensureDir(new File(debianRoot, "root"), 0700);
            ensureDir(new File(debianRoot, "home"), 0755);
            ensureDir(new File(debianRoot, "mnt"), 0755);

            // /etc/hostname and /etc/hosts so hostname(1) and name resolution work.
            File hostnameFile = new File(debianRoot, "etc/hostname");
            if (!hostnameFile.isFile()) {
                java.io.FileWriter hw = new java.io.FileWriter(hostnameFile);
                hw.write("termdeb\n");
                hw.close();
            }
            File hostsFile = new File(debianRoot, "etc/hosts");
            String hosts = "127.0.0.1 localhost\n" +
                "127.0.1.1 termdeb\n" +
                "::1     localhost ip6-localhost ip6-loopback\n" +
                "ff02::1 ip6-allnodes\n" +
                "ff02::2 ip6-allrouters\n";
            if (!hostsFile.isFile()) {
                java.io.FileWriter hw = new java.io.FileWriter(hostsFile);
                hw.write(hosts);
                hw.close();
            }

            // /etc/fstab: informational in proot mode (proot emulates binds), used by
            // native/systemd mode for the real mounts.
            File fstabFile = new File(debianRoot, "etc/fstab");
            if (!fstabFile.isFile()) {
                java.io.FileWriter fw = new java.io.FileWriter(fstabFile);
                fw.write("# TermDeb mounts (proot mode: emulated; native/systemd mode: real)\n" +
                    "proc    /proc           proc    defaults        0 0\n" +
                    "sysfs   /sys            sysfs   defaults        0 0\n" +
                    "devpts  /dev/pts        devpts  gid=5,mode=620 0 0\n" +
                    "tmpfs   /dev/shm        tmpfs   defaults        0 0\n" +
                    "tmpfs   /run            tmpfs   defaults        0 0\n" +
                    "cgroup2 none            /sys/fs/cgroup  cgroup2 defaults 0 0\n");
                fw.close();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Filesystem layout setup failed (non-fatal): " + e.getMessage());
        }

        // 2.6. Remove a stale 0-byte /usr/local/bin/box64 placeholder that older installs
        // left inside the rootfs. The current design binds the host box64 into the guest
        // at /usr/local/bin/box64 at launch, and a leftover placeholder would shadow it.
        try {
            File staleBox64 = new File(debianRoot, "usr/local/bin/box64");
            if (staleBox64.isFile() && staleBox64.length() == 0) {
                staleBox64.delete();
                Logger.logInfo(LOG_TAG, "Removed stale 0-byte guest /usr/local/bin/box64 placeholder");
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Stale box64 cleanup failed (non-fatal): " + e.getMessage());
        }

        // 3. Write the device's DNS servers into the guest /etc/resolv.conf and the
        // host $PREFIX/etc/resolv.conf. The Debian trixie rootfs ships an empty
        // resolv.conf, so without real nameservers every DNS lookup inside Debian
        // fails ("Temporary failure in name resolution") and the terminal appears
        // to have no network at all. Writing both files keeps the guest working
        // immediately after install, and the proot launch binds the host file into
        // the guest (termdeb-debian: -b $PREFIX/etc/resolv.conf:/etc/resolv.conf)
        // so DNS stays correct across network/VPN changes.
        try {
            List<InetAddress> dnsServers = getAndroidDnsServers(context);
            if (dnsServers.isEmpty()) {
                Logger.logWarn(LOG_TAG, "No Android DNS servers found, keeping existing resolv.conf");
            } else {
                StringBuilder resolvConf = new StringBuilder();
                resolvConf.append("# Generated by TermDeb at install time from the active Android network.\n");
                for (InetAddress dns : dnsServers) {
                    resolvConf.append("nameserver ").append(dns.getHostAddress()).append('\n');
                }

                File guestResolvConf = new File(debianRoot, "etc/resolv.conf");
                java.io.FileWriter guestWriter = new java.io.FileWriter(guestResolvConf);
                guestWriter.write(resolvConf.toString());
                guestWriter.close();
                Logger.logInfo(LOG_TAG, "Wrote " + dnsServers.size() + " nameserver(s) to " + guestResolvConf.getAbsolutePath());

                // Keep the host resolv.conf in sync: it is bound into the guest at
                // proot launch time (see termdeb-debian), so it must carry the same
                // working nameservers, not the bootstrap's static defaults.
                File hostResolvConf = new File(filesDir, "usr/etc/resolv.conf");
                if (hostResolvConf.isFile()) {
                    java.io.FileWriter hostWriter = new java.io.FileWriter(hostResolvConf);
                    hostWriter.write(resolvConf.toString());
                    hostWriter.close();
                    Logger.logInfo(LOG_TAG, "Updated host resolv.conf at " + hostResolvConf.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "DNS setup failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Get the DNS servers of the currently active Android network (the same
     * nameservers the system resolver uses). Falls back to public DNS if the
     * active network exposes none.
     */
    private static List<InetAddress> getAndroidDnsServers(Context context) {
        List<InetAddress> dnsServers = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                if (linkProperties != null) {
                    dnsServers.addAll(linkProperties.getDnsServers());
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read Android DNS servers: " + e.getMessage());
        }

        // Fall back to well-known public resolvers if the platform reported none
        // (e.g. airplane-mode edge cases or restricted networks).
        if (dnsServers.isEmpty()) {
            try {
                dnsServers.add(InetAddress.getByName("8.8.8.8"));
                dnsServers.add(InetAddress.getByName("1.1.1.1"));
            } catch (Exception ignored) { /* leave empty */ }
        }
        return dnsServers;
    }

    /**
     * Set executable permission on a file.
     */
    private static void setExecutable(File file) {
        if (file.exists()) {
            file.setExecutable(true, false);
            file.setReadable(true, false);
        }
    }

    /**
     * Create a directory (and parents) if missing and clamp its permissions with chmod
     * (tar extraction preserves modes, but for directories created here the explicit
     * mode guarantees a proper Linux userspace layout, e.g. 01777 for /tmp).
     */
    private static void ensureDir(File dir, int mode) {
        if (!dir.isDirectory()) {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new RuntimeException("Failed to create directory " + dir.getAbsolutePath());
            }
        }
        try {
            //noinspection OctalInteger
            Os.chmod(dir.getAbsolutePath(), mode);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to chmod " + dir.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** Returns true if the file is a symbolic link (not a regular file/dir). */
    private static boolean isSymlink(File file) {
        try {
            return Os.readlink(file.getAbsolutePath()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Make the guest rootfs a proper merged-usr layout: /bin, /sbin, /lib and /lib64
     * must be symlinks into /usr. Debian 13 (trixie) ships /bin, /sbin and /lib
     * as symlinks already, but /lib64 is a real (empty) directory. The box64 x86_64
     * library bundle extracts ld-linux-x86-64.so.2 into /lib64, so move the contents
     * into /usr/lib64 first, then replace the directory with the symlink.
     */
    private static void fixupMergedUsr(File filesDir) {
        File debianRoot = new File(filesDir, "debian-root");
        if (!debianRoot.isDirectory()) return;

        ensureUsrSymlink(debianRoot, "bin", "usr/bin");
        ensureUsrSymlink(debianRoot, "sbin", "usr/sbin");
        ensureUsrSymlink(debianRoot, "lib", "usr/lib");

        File lib64 = new File(debianRoot, "lib64");
        File usrLib64 = new File(debianRoot, "usr/lib64");
        if (lib64.isDirectory() && !isSymlink(lib64)) {
            File[] entries = lib64.listFiles();
            if (entries != null && entries.length > 0) {
                ensureDir(usrLib64, 0755);
                for (File entry : entries) {
                    File dest = new File(usrLib64, entry.getName());
                    try {
                        if (!dest.exists()) {
                            entry.renameTo(dest);
                        } else {
                            entry.delete();
                        }
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Failed to move " + entry.getName() + " to usr/lib64: " + e.getMessage());
                    }
                }
            }
            lib64.delete();
        }
        ensureUsrSymlink(debianRoot, "lib64", "usr/lib64");
    }

    /** Turn a top-level rootfs directory into a symlink to a /usr path if it is not one yet. */
    private static void ensureUsrSymlink(File root, String name, String target) {
        File path = new File(root, name);
        if (isSymlink(path)) return;
        if (!path.exists()) {
            try {
                Os.symlink(target, path.getAbsolutePath());
                Logger.logInfo(LOG_TAG, "Created merged-usr symlink " + name + " -> " + target);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to create symlink " + name + ": " + e.getMessage());
            }
            return;
        }
        // Real directory with content we do not own: leave it, warn.
        File[] entries = path.listFiles();
        if (entries == null || entries.length == 0) {
            path.delete();
            try {
                Os.symlink(target, path.getAbsolutePath());
                Logger.logInfo(LOG_TAG, "Replaced empty dir " + name + " with merged-usr symlink -> " + target);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to create symlink " + name + ": " + e.getMessage());
            }
        } else {
            Logger.logWarn(LOG_TAG, "Merged-usr: " + name + " is a non-empty real dir; leaving as-is");
        }
    }

    /**
     * Write install-time runtime facts to $PREFIX/share/termdeb/termdeb-runtime.json.
     * These are informational; the shell scripts re-detect capabilities at session start
     * (termdeb-debian / termdeb-native / termdeb-syscheck), since root/su availability and
     * kernel features are dynamic.
     */
    private static void writeRuntimeConfigFile(Context context, File filesDir) throws Exception {
        File configDir = new File(filesDir, "usr/share/termdeb");
        configDir.mkdirs();

        String arch = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        String debianVersion = "unknown";
        File osRelease = new File(filesDir, "debian-root/etc/os-release");
        if (osRelease.isFile()) {
            String content = readFileContents(osRelease);
            for (String line : content.split("\n")) {
                if (line.startsWith("VERSION_ID=")) {
                    debianVersion = line.substring("VERSION_ID=".length()).replace("\"", "");
                    break;
                }
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"termdeb_version\": \"").append(getTermDebVersion(context)).append("\",\n");
        json.append("  \"runtime\": \"debian\",\n");
        json.append("  \"debian_version\": \"").append(debianVersion).append("\",\n");
        json.append("  \"arch\": \"").append(arch).append("\",\n");
        json.append("  \"kernel\": \"").append(System.getProperty("os.version", "unknown")).append("\",\n");
        json.append("  \"cgroup_v2\": ").append(new File("/sys/fs/cgroup/cgroup.controllers").canRead()).append(",\n");
        json.append("  \"root_available\": ").append(isRootAvailable()).append(",\n");
        json.append("  \"systemd_provisioned\": ").append(new File(filesDir, "debian-root/etc/termdeb-provisioned").isFile()).append("\n");
        json.append("}\n");

        java.io.FileWriter writer = new java.io.FileWriter(new File(configDir, "termdeb-runtime.json"));
        writer.write(json.toString());
        writer.close();
        Logger.logInfo(LOG_TAG, "Runtime config written to " + configDir.getAbsolutePath() + "/termdeb-runtime.json");
    }

    /** Best-effort check whether a root shell (su) exists on this device. */
    private static boolean isRootAvailable() {
        if (android.os.Process.myUid() == 0) return true;
        String[] suPaths = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
            "/system/bin/magisk", "/data/adb/magisk/magisk", "/data/adb/ksu/bin/ksud"
        };
        for (String path : suPaths) {
            if (new File(path).isFile()) return true;
        }
        return false;
    }

    private static String readFileContents(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Validate the installed runtime before declaring TermDeb ready. Checks the Debian
     * rootfs (os-release, merged /usr, DNS, filesystem layout, core tools), the proot
     * binary and its loaders, the Box64 stack (host binary, guest auto-router, guest
     * x86_64 glibc runtime) and the TermDeb launch scripts. Logs every check so failures
     * are diagnosable, and returns false if anything required is missing so the caller
     * does not write the ready marker (install is retried on next launch).
     */
    private static boolean validateTermDebRuntime(File filesDir) {
        File debianRoot = new File(filesDir, "debian-root");
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        boolean hasRootfs = debianRoot.isDirectory();

        // 1. Debian identification
        boolean osReleaseOk = false;
        if (hasRootfs) {
            File osRelease = new File(debianRoot, "etc/os-release");
            osReleaseOk = osRelease.isFile() && readFileContents(osRelease).contains("Debian");
        }
        addValidationResult(passed, failed, "Debian os-release", osReleaseOk);

        // 2. Merged /usr
        boolean mergedUsrOk = hasRootfs &&
            isSymlink(new File(debianRoot, "bin")) &&
            isSymlink(new File(debianRoot, "sbin")) &&
            isSymlink(new File(debianRoot, "lib")) &&
            isSymlink(new File(debianRoot, "lib64"));
        addValidationResult(passed, failed, "merged /usr (/bin /sbin /lib /lib64 -> usr/*)", mergedUsrOk);

        // 3. Filesystem layout
        boolean fsLayoutOk = hasRootfs &&
            new File(debianRoot, "dev").isDirectory() &&
            new File(debianRoot, "dev/pts").isDirectory() &&
            new File(debianRoot, "dev/shm").isDirectory() &&
            new File(debianRoot, "proc").isDirectory() &&
            new File(debianRoot, "sys").isDirectory() &&
            new File(debianRoot, "run").isDirectory() &&
            new File(debianRoot, "tmp").isDirectory();
        addValidationResult(passed, failed, "filesystem layout (/dev/pts /dev/shm /proc /sys /run /tmp)", fsLayoutOk);

        // 4. DNS (resolv.conf must be non-empty, else the guest has no network)
        boolean dnsOk = hasRootfs && new File(debianRoot, "etc/resolv.conf").length() > 0;
        addValidationResult(passed, failed, "guest /etc/resolv.conf (DNS)", dnsOk);

        // 5. proot + loaders
        boolean prootOk = isExecutableFile(new File(filesDir, "usr/bin/proot")) &&
            new File(filesDir, "usr/libexec/proot/loader").isFile() &&
            new File(filesDir, "usr/libexec/proot/loader32").isFile();
        addValidationResult(passed, failed, "proot + loaders", prootOk);

        // 6. Box64 stack: binary + transparent on-disk wrapping layer + x86_64 glibc.
        // The wrapping layer (termdeb-wrap64 + ARM64 launcher) replaces the old global
        // LD_PRELOAD interposer: it covers every exec path (execve/execvp/posix_spawn/
        // JDK jspawnhelper) without ever preloading into native ARM64 processes.
        boolean box64Ok = hasRootfs &&
            isExecutableFile(new File(filesDir, "usr/bin/box64")) &&
            new File(debianRoot, "usr/local/bin/termdeb-wrap64").isFile() &&
            new File(debianRoot, "usr/local/libexec/termdeb-x86_64-launcher").isFile() &&
            new File(debianRoot, "usr/local/libexec/termdeb-scan64").isFile() &&
            new File(debianRoot, "usr/local/libexec/termdeb-wrapd").isFile() &&
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-wrapd")) &&
            new File(debianRoot, "usr/lib/x86_64-linux-gnu/libc.so.6").isFile() &&
            new File(debianRoot, "lib64/ld-linux-x86-64.so.2").exists();
        addValidationResult(passed, failed, "Box64 stack (binary + wrap64 layer + wrapd watcher + x86_64 glibc)", box64Ok);

        // 7. Guest core tools
        boolean guestToolsOk = hasRootfs &&
            new File(debianRoot, "bin/bash").exists() &&
            new File(debianRoot, "usr/bin/env").exists() &&
            new File(debianRoot, "usr/bin/apt-get").exists();
        addValidationResult(passed, failed, "guest core tools (bash, env, apt-get)", guestToolsOk);

        // 8. Launch scripts
        boolean scriptsOk =
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-debian")) &&
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-shell-wrapper")) &&
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-native")) &&
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-syscheck")) &&
            isExecutableFile(new File(filesDir, "usr/bin/termdeb-provision"));
        addValidationResult(passed, failed, "TermDeb launch scripts", scriptsOk);

        // 9. Writable /tmp inside the guest (a proper Linux userspace needs it)
        boolean tmpWritable = false;
        if (hasRootfs) {
            File probe = new File(debianRoot, "tmp/.termdeb-write-probe");
            try {
                java.io.FileWriter w = new java.io.FileWriter(probe);
                w.write("ok");
                w.close();
                tmpWritable = probe.delete();
            } catch (Exception e) {
                tmpWritable = false;
            }
        }
        addValidationResult(passed, failed, "guest /tmp writable", tmpWritable);

        boolean ok = failed.isEmpty();
        Logger.logInfo(LOG_TAG, "--- TermDeb runtime validation " + (ok ? "PASSED" : "FAILED") + " ---");
        for (String s : passed) Logger.logInfo(LOG_TAG, "  [PASS] " + s);
        for (String s : failed) Logger.logError(LOG_TAG, "  [FAIL] " + s);
        return ok;
    }

    private static void addValidationResult(List<String> passed, List<String> failed, String name, boolean ok) {
        (ok ? passed : failed).add(name);
    }

    private static boolean isExecutableFile(File file) {
        return file.isFile() && file.canExecute();
    }

    /** Write the marker that declares the runtime installed and validated. */
    private static void writeReadyMarker(File filesDir) throws Exception {
        File marker = new File(filesDir, ".termdeb-runtime-ready");
        java.io.FileWriter writer = new java.io.FileWriter(marker);
        writer.write("TermDeb runtime installed and validated at " + new java.util.Date().toString() + "\n");
        writer.close();
        Logger.logInfo(LOG_TAG, "Ready marker written: " + marker.getAbsolutePath());
    }

    /**
     * Install TermDeb shell scripts into $PREFIX/bin/.
     *
     * These scripts provide:
     * - termdeb-debian: Main entry point that launches Debian via proot-distro
     * - termdeb-host: Escape command to exit Debian and return to native TermDeb shell
     * - termdeb-shell-wrapper: Default login shell that routes to Debian automatically
     *
     * The termdeb-shell-wrapper is placed at $PREFIX/bin/termdeb-shell-wrapper and
     * is prioritized in the login shell selection, so new terminal sessions automatically
     * enter the Debian ARM64 environment.
     *
     * To access the native TermDeb shell, users can:
     * - Run 'termdeb-host' from inside Debian
     * - Use failsafe mode
     */
    private static void installTermDebShellScripts(Context context, File filesDir) throws Exception {
        File binDir = new File(filesDir, "usr/bin");
        binDir.mkdirs();

        // Install termdeb-debian (main Debian entry point)
        // NOTE: the shebang must be the absolute path to the fork's bash, since /bin/bash
        // does not exist in the Android root filesystem.
        String shebang = "#!" + filesDir.getAbsolutePath() + "/usr/bin/bash\n";
        String debianScript = shebang +
            "# termdeb-debian - Enter Debian Linux (full userspace)\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            TERMDEB_PATHS + "\n" +
            "# Already inside the Debian guest (proot or native chroot)? Run a shell.\n" +
            "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n" +
            "  exec /bin/bash --login\n" +
            "fi\n\n" +
            "# Check if Debian rootfs exists\n" +
            "if [ ! -d \"${TERMDEB_UROOT}\" ] || [ ! -f \"${TERMDEB_UROOT}/etc/os-release\" ]; then\n" +
            "  echo \"TermDeb: Debian rootfs not found at ${TERMDEB_UROOT}\"\n" +
            "  echo \"TermDeb: Falling back to native TermDeb shell.\"\n" +
            "  exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n" +
            "fi\n\n" +
            "# First-start provisioning: install systemd, dbus, ca-certificates and locales\n" +
            "# into the guest so the environment is a complete Debian userspace. Idempotent\n" +
            "# and resumable - termdeb-provision writes /etc/termdeb-provisioned in the guest\n" +
            "# on success and is retried on the next session start until then. Skip with\n" +
            "# TERMDEB_SKIP_PROVISION=1.\n" +
            "if [ \"${TERMDEB_SKIP_PROVISION:-0}\" != \"1\" ] && [ ! -f \"${TERMDEB_UROOT}/etc/termdeb-provisioned\" ]; then\n" +
            "  echo \"TermDeb: Provisioning Debian userspace (systemd, dbus, ca-certificates)...\"\n" +
            "  if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-provision\" ]; then\n" +
            "    \"${TERMDEB_PREFIX}/bin/termdeb-provision\"\n" +
            "  fi\n" +
            "fi\n\n" +
            "# Export Box64 configuration\n" +
            "# NOTE: STRONGMEM and BLEEDING_EDGE are deliberately NOT set: on-device\n" +
            "# testing showed they hang some x86_64 binaries (e.g. Bun-compiled apps)\n" +
            "# under the dynarec. The plain dynarec defaults run everything tested.\n" +
            "export BOX64_DYNAREC=1\n" +
            "export BOX64_DYNAREC_BIGBLOCK=1\n" +
            "export BOX64_DYNAREC_SAFEFLAGS=1\n" +
            "export BOX64_ENV=0\n" +
            "export BOX64_NOBANNER=0\n\n" +
            "# Native mode (systemd as PID 1) needs real root plus kernel features.\n" +
            "# termdeb-native performs the capability checks, mounts and chroot+systemd\n" +
            "# launch; it exits non-zero with diagnostics when the Android kernel cannot\n" +
            "# support it, and we fall back to proot mode.\n" +
            TERMDEB_HAS_ROOT_FN + "\n" +
            "if termdeb_has_root; then\n" +
            "  if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-native\" ]; then\n" +
            "    \"${TERMDEB_PREFIX}/bin/termdeb-native\"\n" +
            "    native_status=\"$?\"\n" +
            "    if [ \"${TERMDEB_FORCE_NATIVE:-0}\" = \"1\" ]; then\n" +
            "      exit \"$native_status\"\n" +
            "    fi\n" +
            "    if [ \"$native_status\" -ne 0 ]; then\n" +
            "      echo \"TermDeb: native (systemd) launch unavailable, falling back to proot mode.\"\n" +
            "      echo \"TermDeb: run 'termdeb-syscheck' for systemd/kernel diagnostics.\"\n" +
            "    fi\n" +
            "  fi\n" +
            "fi\n\n" +
            "# Discover the Android SDK wherever it lives: termdeb-wrap64 locates it\n" +
            "# regardless of the folder's name or location (env vars, any directory\n" +
            "# containing build-tools, local.properties sdk.dir, /sdcard) - see\n" +
            "# termdeb-wrap64 --print-sdk-roots. The roots are handed to the watcher\n" +
            "# below so a freshly placed SDK gets real-time inotify coverage, not\n" +
            "# just the periodic sweep.\n" +
            "TERMDEB_SDK_ROOTS=\"\"\n" +
            "if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-wrap64\" ]; then\n" +
            "  TERMDEB_SDK_ROOTS=$(\"${TERMDEB_PREFIX}/bin/termdeb-wrap64\" --print-sdk-roots 2>/dev/null | tr '\\n' ' ')\n" +
            "fi\n\n" +
            "# Refresh the transparent Box64 layer (on the HOST, no proot/ptrace overhead):\n" +
            "# wrap any x86_64 ELF executables under the home tree, /usr/local, /usr/bin,\n" +
            "# the Gradle cache and the discovered SDK roots with ARM64 launchers so they\n" +
            "# run through box64 - including as subprocesses of native ARM64 parents\n" +
            "# (Gradle/JDK aapt2). Runs before the guest starts, so the environment is\n" +
            "# always up to date on entry. (termdeb-wrap64 rediscovers the SDK itself.)\n" +
            "if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-wrap64\" ] && [ -f \"${TERMDEB_UROOT}/etc/os-release\" ]; then\n" +
            "  \"${TERMDEB_PREFIX}/bin/termdeb-wrap64\" >/dev/null 2>&1 || true\n" +
            "fi\n\n" +
            "# Continuous Box64 wrap watcher (termdeb-wrapd): keeps the transparent\n" +
            "# layer up to date while the session runs. Gradle/AGP re-extracts\n" +
            "# x86_64 tools (aapt2, zipalign) into fresh\n" +
            "# ~/.gradle/caches/<ver>/transforms directories during a build - the\n" +
            "# one-shot wrap above cannot see those. The watcher uses inotify to wrap\n" +
            "# any new x86_64 ELF executable the instant it is written or moved in,\n" +
            "# BEFORE the ARM64 JVM can spawn it, plus a periodic full sweep.\n" +
            "# Discovered SDK roots are passed as CLI args so they are watched in\n" +
            "# real time too (any name, any location). Single-instance (flock),\n" +
            "# backgrounded, logs to the TermDeb tmp dir.\n" +
            "if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-wrapd\" ] && [ -f \"${TERMDEB_UROOT}/etc/os-release\" ]; then\n" +
            "  # shellcheck disable=SC2086  # intentional word-splitting of SDK root list\n" +
            "  nohup \"${TERMDEB_PREFIX}/bin/termdeb-wrapd\" ${TERMDEB_SDK_ROOTS} \\\n" +
            "    >\"${TERMDEB_PREFIX}/tmp/termdeb-wrapd.log\" 2>&1 &\n" +
            "  wrapd_pid=$!\n" +
            "  # Detach from the watcher: if it dies, don't kill the session.\n" +
            "  disown $wrapd_pid 2>/dev/null || true\n" +
            "fi\n\n" +
            "# Create bind sources\n" +
            "mkdir -p \"${TERMDEB_HOME}/storage\" \"${TERMDEB_PREFIX}/tmp\" \"${TERMDEB_PREFIX}/tmp/shm\"\n" +
            "# Source user ~/.bashrc at shell startup. The guest runs bash as a\n" +
            "# login shell, which reads ~/.profile (not ~/.bashrc) - and /root\n" +
            "# inside the guest is bind-mounted from ${TERMDEB_HOME}, which has no\n" +
            "# .profile. If the user placed config in .bashrc it silently never ran.\n" +
            "# Create a .profile that sources .bashrc (Debian's default), but only\n" +
            "# if the user has not provided their own .profile/.bash_profile.\n" +
            "if [ -f \"${TERMDEB_HOME}/.bashrc\" ] && \\\n" +
            "   [ ! -f \"${TERMDEB_HOME}/.profile\" ] && \\\n" +
            "   [ ! -f \"${TERMDEB_HOME}/.bash_profile\" ]; then\n" +
            "    cat > \"${TERMDEB_HOME}/.profile\" <<'TERMDEB_PROFILE_EOF'\n" +
            "# ~/.profile: executed by login shells. Source ~/.bashrc (Debian default).\n" +
            "if [ -n \"$BASH_VERSION\" ] && [ -f \"$HOME/.bashrc\" ]; then\n" +
            "    . \"$HOME/.bashrc\"\n" +
            "fi\n" +
            "TERMDEB_PROFILE_EOF\n" +
            "fi\n\n" +
            "# Keep proot temporary files in this app's writable sandbox; the bundled\n" +
            "# proot default points at the upstream com.termux package path.\n" +
            "export PROOT_TMP_DIR=\"${TERMDEB_PREFIX}/tmp\"\n\n" +
            "# Launch Debian with the bundled proot binary (no root required). proot-distro\n" +
            "# is also bundled, but it is a Python program and the Termux bootstrap ships no\n" +
            "# python3, so it cannot be used here - proot is the launch path.\n" +
            "# The upstream Termux proot launch script can fail on non-TMUX installs when\n" +
            "# $PREFIX/tmp is not writable (Android 11+ can restrict access to /data/data/com.termux\n" +
            "# paths), yielding a cryptic \"/bin/bash: No such file or directory\" (exit 127).\n" +
            "# If proot is missing or unusable, show a clear error and drop to the native shell.\n" +
            "if [ ! -x \"${PROOT}\" ]; then\n" +
            "  echo \"TermDeb: proot binary not found at ${PROOT}\"\n" +
            "  echo \"TermDeb: The Debian environment cannot start without it.\"\n" +
            "  echo \"TermDeb: Reinstall TermDeb, or run 'termdeb-syscheck' for diagnostics.\"\n" +
            "  echo \"TermDeb: Falling back to the native TermDeb shell (Debian features unavailable).\"\n" +
            "  exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n" +
            "fi\n\n" +
            "# Check if proot actually runs (some Android versions block it)\n" +
            "if ! \"${PROOT}\" --help >/dev/null 2>&1; then\n" +
            "  echo \"TermDeb: proot at ${PROOT} is present but not runnable (blocked by Android?).\"\n" +
            "  echo \"TermDeb: Falling back to the native TermDeb shell (Debian features unavailable).\"\n" +
            "  exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n" +
            "fi\n\n" +
            "# Launch Debian via proot (no root required): complete Linux userspace with\n" +
            "# /proc, /sys, /dev, /dev/pts, tmpfs-style /dev/shm, /run, /sdcard, storage,\n" +
            "# DNS and Box64 all bound in.\n" +
            "exec \"${PROOT}\" \\\n" +
            "  --link2symlink --kill-on-exit --root-id --cwd=/root \\\n" +
            "  -b /dev -b /proc -b /sys \\\n" +
            "  -b \"${TERMDEB_HOME}:/root\" \\\n" +
            "  -b \"${TERMDEB_HOME}/storage:/mnt/storage\" \\\n" +
            "  -b \"/storage/emulated/0:/sdcard\" \\\n" +
            "  -b \"${TERMDEB_PREFIX}/tmp:/tmp\" \\\n" +
            "  -b \"${TERMDEB_PREFIX}/tmp/shm:/dev/shm\" \\\n" +
            "  -b \"${BOX64}:/usr/local/bin/box64\" \\\n" +
            // Bind the host resolv.conf into the guest so DNS keeps working inside
            // Debian. The guest rootfs ships an empty /etc/resolv.conf; without
            // real nameservers every name lookup fails and the terminal appears
            // to have no network at all. Binding (instead of only writing the file
            // once at install time) keeps DNS correct across network/VPN changes.
            "  -b \"${TERMDEB_PREFIX}/etc/resolv.conf:/etc/resolv.conf\" \\\n" +
            "  -r \"${TERMDEB_UROOT}\" \\\n" +
            "  /usr/bin/env -i HOME=/root USER=root TERM=\"${TERM:-xterm-256color}\" \\\n" +
            "  LANG=en_US.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
            "  BOX64_LD_LIBRARY_PATH=/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu:/lib64:/usr/lib64 \\\n" +
            "  BOX64_DYNAREC=1 BOX64_DYNAREC_BIGBLOCK=1 BOX64_DYNAREC_SAFEFLAGS=1 \\\n" +
            "  /bin/bash --login\n";

        writeScriptToFile(new File(binDir, "termdeb-debian"), debianScript);

        // Install termdeb-native (real chroot + systemd as PID 1, requires root)
        String nativeScript = shebang +
            "# termdeb-native - Launch Debian as a real chroot with systemd as PID 1.\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "# Only possible when TermDeb has real root (su) AND the Android kernel\n" +
            "# exposes the features systemd needs: mountable /proc /sys /dev, a cgroup\n" +
            "# filesystem, and PID namespaces (unshare). When any piece is missing this\n" +
            "# prints a diagnostic and exits non-zero so termdeb-debian falls back to\n" +
            "# proot mode.\n\n" +
            TERMDEB_PATHS + "\n" +
            TERMDEB_HAS_ROOT_FN + "\n" +
            "# Re-exec as root when possible (Magisk/KernelSU su).\n" +
            "if [ \"$(id -u)\" != \"0\" ]; then\n" +
            "  if termdeb_has_root; then\n" +
            "    for p in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su; do\n" +
            "      if [ -x \"$p\" ]; then\n" +
            "        exec \"$p\" -c \"PREFIX='${TERMDEB_PREFIX}' HOME='${TERMDEB_HOME}' TERM='${TERM:-xterm-256color}' '$0' '$@'\"\n" +
            "      fi\n" +
            "    done\n" +
            "  fi\n" +
            "  echo \"termdeb-native: root access required but no Android su was found.\"\n" +
            "  echo \"termdeb-native: systemd needs real root; TermDeb will use proot mode instead.\"\n" +
            "  exit 1\n" +
            "fi\n\n" +
            "# ---- Kernel feature diagnostics (each failure explains why systemd cannot run) ----\n" +
            "if [ ! -d \"${TERMDEB_UROOT}\" ] || [ ! -f \"${TERMDEB_UROOT}/etc/os-release\" ]; then\n" +
            "  echo \"termdeb-native: Debian rootfs missing at ${TERMDEB_UROOT}\"\n" +
            "  exit 1\n" +
            "fi\n\n" +
            "if [ ! -d /sys/fs/cgroup ]; then\n" +
            "  echo \"termdeb-native: /sys/fs/cgroup not present - kernel cgroups unavailable.\"\n" +
            "  exit 1\n" +
            "fi\n\n" +
            "if ! unshare --pid --fork true 2>/dev/null; then\n" +
            "  echo \"termdeb-native: PID namespace creation (unshare) not permitted by this kernel.\"\n" +
            "  echo \"termdeb-native: systemd must be PID 1 in its own namespace; it cannot run otherwise.\"\n" +
            "  exit 1\n" +
            "fi\n\n" +
            "# ---- Mount the guest filesystems (idempotent, real mounts - requires root) ----\n" +
            "UROOT=\"${TERMDEB_UROOT}\"\n" +
            "mount_guest() {\n" +
            "  local spec=\"$1\" target=\"$2\"\n" +
            "  [ -e \"$target\" ] || mkdir -p \"$target\"\n" +
            "  mountpoint -q \"$target\" 2>/dev/null && return 0\n" +
            "  mount $spec \"$target\" 2>/dev/null || { echo \"termdeb-native: failed to mount $spec on $target\"; return 1; }\n" +
            "}\n" +
            "mount_guest -t proc proc \"${UROOT}/proc\" || exit 1\n" +
            "mount_guest -t sysfs sysfs \"${UROOT}/sys\" || exit 1\n" +
            "mount_guest --bind /dev \"${UROOT}/dev\" || true\n" +
            "mount_guest -t devpts devpts \"${UROOT}/dev/pts\" || true\n" +
            "mount_guest -t tmpfs tmpfs \"${UROOT}/dev/shm\" || true\n" +
            "mount_guest -t tmpfs tmpfs \"${UROOT}/run\" || true\n" +
            "mount_guest --bind /storage/emulated/0 \"${UROOT}/sdcard\" || true\n" +
            "mount_guest --bind \"${TERMDEB_PREFIX}/etc/resolv.conf\" \"${UROOT}/etc/resolv.conf\" || true\n" +
            "mount_guest --bind \"${BOX64}\" \"${UROOT}/usr/local/bin/box64\" || true\n" +
            "# cgroup v2 preferred, else bind the existing v1 hierarchy\n" +
            "if [ -f /sys/fs/cgroup/cgroup.controllers ]; then\n" +
            "  mount_guest -t cgroup2 cgroup2 \"${UROOT}/sys/fs/cgroup\" || true\n" +
            "else\n" +
            "  mount_guest --bind /sys/fs/cgroup \"${UROOT}/sys/fs/cgroup\" || true\n" +
            "fi\n\n" +
            "# ---- Launch systemd as PID 1 inside a new PID+mount namespace ----\n" +
            "if [ -x \"${UROOT}/usr/lib/systemd/systemd\" ]; then\n" +
            "  echo \"TermDeb: starting systemd as PID 1 (native mode)...\"\n" +
            "  mkdir -p \"${UROOT}/run\" \"${UROOT}/run/lock\"\n" +
            "  # --mount-proc needs util-linux unshare; toybox unshare lacks it. systemd\n" +
            "  # mounts /proc itself when running as PID 1 in a container.\n" +
            "  if unshare --mount --pid --fork --mount-proc true 2>/dev/null; then\n" +
            "    exec unshare --mount --pid --fork --mount-proc \\\n" +
            "      chroot \"${UROOT}\" /usr/lib/systemd/systemd --system\n" +
            "  else\n" +
            "    exec unshare --mount --pid --fork \\\n" +
            "      chroot \"${UROOT}\" /usr/lib/systemd/systemd --system\n" +
            "  fi\n" +
            "fi\n\n" +
            "# systemd not installed yet (provisioning pending): plain chroot shell.\n" +
            "echo \"TermDeb: systemd not found in the guest (run 'termdeb-debian' to provision).\"\n" +
            "echo \"TermDeb: starting Debian in a plain chroot shell.\"\n" +
            "exec chroot \"${UROOT}\" /bin/bash --login\n";

        writeScriptToFile(new File(binDir, "termdeb-native"), nativeScript);

        // Install termdeb-syscheck (runtime + systemd capability diagnostics)
        writeScriptToFile(new File(binDir, "termdeb-syscheck"), shebang + TERMDEB_SYSCHECK_BODY);

        // Install termdeb-provision (first-start Debian userspace provisioning)
        writeScriptToFile(new File(binDir, "termdeb-provision"), shebang + TERMDEB_PROVISION_BODY);

        // Install termdeb-wrap64 (transparent Box64 execution layer; the guest copy
        // in /usr/local/bin does the actual wrapping)
        writeScriptToFile(new File(binDir, "termdeb-wrap64"), shebang + TERMDEB_WRAP64_BODY);

        // Install termdeb-host (escape to native TermDeb shell)
        //
        // When run from inside the Debian proot guest it cannot exec a host binary
        // (proot ptrace-wraps the whole process tree, and the host paths do not
        // exist inside the fake root), so it writes a marker file into the bound
        // home (/root inside the guest == $HOME on the host) and exits. The login
        // shell wrapper (termdeb-shell-wrapper) then picks up the marker and drops
        // the user into the native TermDeb shell.
        String hostScript = shebang +
            "# termdeb-host - Exit Debian to native TermDeb shell\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "if [ \"${1:-}\" = \"help\" ] || [ \"${1:-}\" = \"--help\" ] || [ \"${1:-}\" = \"-h\" ]; then\n" +
            "  echo \"termdeb-host - Exit Debian to native TermDeb shell\"\n" +
            "  echo \"\"\n" +
            "  echo \"Usage: termdeb-host\"\n" +
            "  echo \"  termdeb-host    Exit Debian and return to TermDeb shell\"\n" +
            "  echo \"\"\n" +
            "  echo \"From the native TermDeb shell, re-enter Debian with: termdeb-debian\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "TERMDEB_PREFIX=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}\"\n" +
            "TERMDEB_APP_DIR=\"${TERMDEB_PREFIX%/files/usr}\"\n" +
            "TERMDEB_HOME=\"${TERMDEB_APP_DIR}/files/home\"\n\n" +
            "# Inside the Debian proot guest: uid is 0 and /etc/os-release is the guest's.\n" +
            "# Write the marker into the bound home (/root == host \\$HOME) and exit.\n" +
            "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n" +
            "  echo \"Exiting Debian...\"\n" +
            "  if touch /root/.termdeb-host 2>/dev/null; then\n" +
            "    exit 0\n" +
            "  fi\n" +
            "fi\n\n" +
            "# Not inside proot (or marker failed): just start a regular TermDeb shell.\n" +
            "exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n";

        writeScriptToFile(new File(binDir, "termdeb-host"), hostScript);

        // Install termdeb-storage (grant Android storage access)
        String storageScript = shebang +
            "# termdeb-storage - Grant Android storage access to TermDeb\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "if [ \"${1:-}\" = \"help\" ] || [ \"${1:-}\" = \"--help\" ] || [ \"${1:-}\" = \"-h\" ]; then\n" +
            "  echo \"termdeb-storage - Grant Android storage access to TermDeb\"\n" +
            "  echo \"\"\n" +
            "  echo \"Usage: termdeb-storage\"\n" +
            "  echo \"  termdeb-storage    Ask TermDeb for the storage permission\"\n" +
            "  echo \"\"\n" +
            "  echo \"Grant the permission dialog to access Android shared storage.\"\n" +
            "  echo \"Symlinks are then created at ~/storage (shared, downloads, dcim, ...).\"\n" +
            "  echo \"Inside Debian, shared storage is mounted at /sdcard.\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "TERMDEB_PREFIX=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}\"\n" +
            "TERMDEB_APP_DIR=\"${TERMDEB_PREFIX%/files/usr}\"\n" +
            "TERMDEB_HOME=\"${TERMDEB_APP_DIR}/files/home\"\n\n" +
            "# When run from inside Debian, delegate to the host shell: write the marker\n" +
            "# that termdeb-shell-wrapper picks up when the proot session exits, then leave.\n" +
            "if [ -f /etc/os-release ] && [ \"$(id -u 2>/dev/null)\" = \"0\" ]; then\n" +
            "  if touch /root/.termdeb-storage 2>/dev/null; then\n" +
            "    echo \"Requesting the storage permission from the TermDeb host shell...\"\n" +
            "    echo \"Leaving Debian to show the permission dialog; return with 'termdeb-debian'.\"\n" +
            "    exit 0\n" +
            "  fi\n" +
            "fi\n\n" +
            "echo \"Requesting Android storage permission...\"\n" +
            "ACTION=\"com.qali.termdeb.app.request_storage_permissions\"\n\n" +
            "status=1\n" +
            "# Preferred: go through the app's own am socket server (termux-am).\n" +
            "if [ -x \"${TERMDEB_PREFIX}/bin/termux-am\" ] && [ \"${TERMUX_APP__AM_SOCKET_SERVER_ENABLED:-false}\" = \"true\" ]; then\n" +
            "  if \"${TERMDEB_PREFIX}/bin/termux-am\" broadcast --action \"$ACTION\" >/dev/null 2>&1; then\n" +
            "    status=0\n" +
            "  fi\n" +
            "fi\n\n" +
            "# Fallback: system am binary. --user 0 is required when called from an app uid.\n" +
            "if [ \"$status\" != 0 ] && [ -x /system/bin/am ]; then\n" +
            "  if /system/bin/am broadcast --user 0 -a \"$ACTION\" >/dev/null 2>&1; then\n" +
            "    status=0\n" +
            "  fi\n" +
            "fi\n\n" +
            "if [ \"$status\" != 0 ]; then\n" +
            "  echo \"Failed to ask the TermDeb app for the storage permission.\"\n" +
            "  echo \"Make sure the TermDeb terminal is open and visible, then try again.\"\n" +
            "  exit 1\n" +
            "fi\n\n" +
            "echo \"A permission dialog should appear in the TermDeb app. Grant it to\"\n" +
            "echo \"access Android shared storage. Symlinks are created automatically\"\n" +
            "echo \"at: ${TERMDEB_HOME}/storage (shared, downloads, dcim, pictures, ...).\"\n" +
            "echo \"Inside Debian, shared storage is mounted at /sdcard.\"\n";

        writeScriptToFile(new File(binDir, "termdeb-storage"), storageScript);

        // Install termdeb-shell-wrapper (default login shell that routes to Debian)
        //
        // termdeb-debian is RUN (not exec'd) so that when the proot session ends
        // (either by 'exit' or by the marker written by termdeb-host/termdeb-storage
        // from inside the guest) the wrapper can act on it: drop into the native
        // TermDeb shell when requested. A plain 'exit' from Debian still closes the
        // session like before.
        String wrapperScript = shebang +
            "# termdeb-shell-wrapper - Default login shell for TermDeb\n" +
            "# Automatically enters Debian ARM64 environment\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "export TERMDEB_DEFAULT_SESSION=\"debian\"\n" +
            "export TERMDEB_HOST_SHELL=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}/bin/bash\"\n" +
            "TERMDEB_PREFIX=\"${PREFIX:-/data/data/com.qali.termdeb/files/usr}\"\n" +
            "TERMDEB_APP_DIR=\"${TERMDEB_PREFIX%/files/usr}\"\n" +
            "TERMDEB_HOME=\"${TERMDEB_APP_DIR}/files/home\"\n\n" +
            "# Shell startup: source the user's ~/.bashrc so aliases/functions/exports\n" +
            "# are applied every session. A login shell reads ~/.profile, not\n" +
            "# ~/.bashrc - so, mirroring Debian's default, create a ~/.profile that\n" +
            "# sources it (only if the user has no .profile/.bash_profile of their own).\n" +
            "if [ -f \"${TERMDEB_HOME}/.bashrc\" ] && \\\n" +
            "   [ ! -f \"${TERMDEB_HOME}/.profile\" ] && \\\n" +
            "   [ ! -f \"${TERMDEB_HOME}/.bash_profile\" ]; then\n" +
            "    cat > \"${TERMDEB_HOME}/.profile\" <<'TERMDEB_PROFILE_EOF'\n" +
            "# ~/.profile: executed by login shells. Source ~/.bashrc (Debian default).\n" +
            "if [ -n \"$BASH_VERSION\" ] && [ -f \"$HOME/.bashrc\" ]; then\n" +
            "    . \"$HOME/.bashrc\"\n" +
            "fi\n" +
            "TERMDEB_PROFILE_EOF\n" +
            "fi\n\n" +
            "# If bash was invoked with arguments, execute them directly\n" +
            "if [ \"$1\" = \"-c\" ] || [ \"$#\" -gt 0 ]; then\n" +
            "  exec \"${TERMDEB_PREFIX}/bin/bash\" \"$@\"\n" +
            "fi\n\n" +
            "# Try termdeb-debian entry point\n" +
            "TERMDEB_DEBIAN=\"${TERMDEB_PREFIX}/bin/termdeb-debian\"\n" +
            "if [ -x \"${TERMDEB_DEBIAN}\" ]; then\n" +
            "  # Run it, not exec: act on how the Debian session ended.\n" +
            "  \"${TERMDEB_DEBIAN}\"\n" +
            "  status=\"$?\"\n\n" +
            "  # termdeb-storage was requested from inside Debian: run it now (shows\n" +
            "  # the storage permission dialog) and then drop into the native shell.\n" +
            "  if [ -f \"${TERMDEB_HOME}/.termdeb-storage\" ]; then\n" +
            "    rm -f \"${TERMDEB_HOME}/.termdeb-storage\"\n" +
            "    if [ -x \"${TERMDEB_PREFIX}/bin/termdeb-storage\" ]; then\n" +
            "      \"${TERMDEB_PREFIX}/bin/termdeb-storage\"\n" +
            "    fi\n" +
            "    exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n" +
            "  fi\n\n" +
            "  # termdeb-host was requested from inside Debian: drop into native shell.\n" +
            "  if [ -f \"${TERMDEB_HOME}/.termdeb-host\" ]; then\n" +
            "    rm -f \"${TERMDEB_HOME}/.termdeb-host\"\n" +
            "    exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n" +
            "  fi\n\n" +
            "  # Plain 'exit' from Debian: close the session like before.\n" +
            "  exit \"$status\"\n" +
            "fi\n\n" +
            "# Fallback to native shell\n" +
            "exec \"${TERMDEB_PREFIX}/bin/bash\" --login\n";

        writeScriptToFile(new File(binDir, "termdeb-shell-wrapper"), wrapperScript);

        Logger.logInfo(LOG_TAG, "TermDeb shell scripts installed to " + binDir.getAbsolutePath());
    }

    /**
     * Install TermDeb shell scripts into the Debian guest rootfs so they are
     * available inside the proot environment (guest PATH includes /usr/local/bin):
     *
     * - termdeb-host: writes the /root/.termdeb-host marker (root == the bound host
     *   home) and exits, so termdeb-shell-wrapper drops the user into the native
     *   TermDeb shell.
     * - termdeb-storage: if /sdcard is not writable yet, writes the
     *   /root/.termdeb-storage marker and exits, so termdeb-shell-wrapper runs the
     *   host termdeb-storage (which shows the Android storage permission dialog).
     *
     * The guest copies use the guest's own /bin/bash shebang (the fork's bash path
     * does not exist inside the fake root).
     */
    private static void installTermDebGuestScripts(Context context, File filesDir) throws Exception {
        File debianRoot = new File(filesDir, "debian-root");
        if (!debianRoot.isDirectory()) return;

        File guestBinDir = new File(debianRoot, "usr/local/bin");
        guestBinDir.mkdirs();

        String guestHostScript = "#!/bin/bash\n" +
            "# termdeb-host - Exit Debian to native TermDeb shell (guest copy)\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "if [ \"${1:-}\" = \"help\" ] || [ \"${1:-}\" = \"--help\" ] || [ \"${1:-}\" = \"-h\" ]; then\n" +
            "  echo \"termdeb-host - Exit Debian to native TermDeb shell\"\n" +
            "  echo \"\"\n" +
            "  echo \"Usage: termdeb-host\"\n" +
            "  echo \"  termdeb-host    Exit Debian and return to TermDeb shell\"\n" +
            "  echo \"\"\n" +
            "  echo \"From the native TermDeb shell, re-enter Debian with: termdeb-debian\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "# We are inside the Debian proot guest (/root is the bound host home).\n" +
            "echo \"Exiting Debian...\"\n" +
            "touch /root/.termdeb-host 2>/dev/null\n" +
            "exit 0\n";
        writeScriptToFile(new File(guestBinDir, "termdeb-host"), guestHostScript);

        String guestStorageScript = "#!/bin/bash\n" +
            "# termdeb-storage - Grant Android storage access to TermDeb (guest copy)\n" +
            "# Copyright (c) TermDeb Contributors - MIT License\n\n" +
            "if [ \"${1:-}\" = \"help\" ] || [ \"${1:-}\" = \"--help\" ] || [ \"${1:-}\" = \"-h\" ]; then\n" +
            "  echo \"termdeb-storage - Grant Android storage access to TermDeb\"\n" +
            "  echo \"\"\n" +
            "  echo \"Android shared storage is mounted at /sdcard.\"\n" +
            "  echo \"If /sdcard is not writable yet, this command exits Debian, shows\"\n" +
            "  echo \"the storage permission dialog in the TermDeb app, and returns\"\n" +
            "  echo \"you to the native TermDeb shell. Re-enter Debian with 'termdeb-debian'.\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "# Already writable? /sdcard/Android is created by the system on every\n" +
            "# device, so require it: if the proot bind failed, /sdcard is an empty\n" +
            "# rootfs directory and the write test would give a false positive.\n" +
            "if [ -d /sdcard/Android ] && touch /sdcard/.termdeb-storage-test 2>/dev/null; then\n" +
            "  rm -f /sdcard/.termdeb-storage-test\n" +
            "  echo \"Storage is already accessible. Android shared storage is mounted at /sdcard.\"\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "# Ask the host shell to request the permission for us.\n" +
            "echo \"Storage permission not granted yet.\"\n" +
            "echo \"Exiting Debian to request it from the TermDeb app...\"\n" +
            "touch /root/.termdeb-storage 2>/dev/null\n" +
            "exit 0\n";
        writeScriptToFile(new File(guestBinDir, "termdeb-storage"), guestStorageScript);

        // Guest copies of termdeb-provision, termdeb-syscheck and termdeb-wrap64: the
        // shared bodies are context-aware (they detect being inside the guest), so only
        // the shebang differs. The guest shebang is /bin/bash (the fork's bash path does
        // not exist inside the fake root). termdeb-provision must exist in the guest
        // BEFORE the first provision run, since the host launcher executes
        // /usr/local/bin/termdeb-provision inside proot to do the apt work.
        String guestShebang = "#!/bin/bash\n";
        writeScriptToFile(new File(guestBinDir, "termdeb-provision"), guestShebang + TERMDEB_PROVISION_BODY);
        writeScriptToFile(new File(guestBinDir, "termdeb-syscheck"), guestShebang + TERMDEB_SYSCHECK_BODY);
        writeScriptToFile(new File(guestBinDir, "termdeb-wrap64"), guestShebang + TERMDEB_WRAP64_BODY);

        // The ARM64 launcher and scanner used by termdeb-wrap64 (launcher replaces
        // each x86_64 binary; the scanner finds them with raw syscalls so scans stay
        // fast even under proot). They must live at the exact paths the wrap tool
        // expects.
        File guestLibexecDir = new File(debianRoot, "usr/local/libexec");
        guestLibexecDir.mkdirs();
        try {
            extractAsset(context, TERMDEB_BOX64_DIR + "/termdeb-x86_64-launcher",
                new File(guestLibexecDir, "termdeb-x86_64-launcher"));
            setExecutable(new File(guestLibexecDir, "termdeb-x86_64-launcher"));
            extractAsset(context, TERMDEB_BOX64_DIR + "/termdeb-scan64",
                new File(guestLibexecDir, "termdeb-scan64"));
            setExecutable(new File(guestLibexecDir, "termdeb-scan64"));
            extractAsset(context, TERMDEB_BOX64_DIR + "/termdeb-wrapd",
                new File(guestLibexecDir, "termdeb-wrapd"));
            setExecutable(new File(guestLibexecDir, "termdeb-wrapd"));
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Box64 launcher/scanner/watcher installation failed (non-fatal): " + e.getMessage());
        }

        // The Box64 layer is refreshed on the HOST side by termdeb-debian before
        // entering the guest (no proot/ptrace overhead, so it is fast enough to run
        // at every session start). Remove the old guest login hook if present.
        try {
            new File(debianRoot, "etc/profile.d/termdeb-box64.sh").delete();
        } catch (Exception ignored) {
        }

        Logger.logInfo(LOG_TAG, "TermDeb guest scripts installed to " + guestBinDir.getAbsolutePath());
    }

    /**
     * Install the ADB bridge guest client as ${TERMDEB_UROOT}/usr/bin/adb so
     * programs inside the Debian/PRoot guest use the normal adb interface
     * (adb devices/shell/push/install/...). The server side runs in the app
     * process (TermdebAdbBridge) and is reached over the shared loopback, so
     * the guest only needs the binary. Idempotent: re-extracted on every app
     * start like the other TermDeb runtime files. Non-fatal on failure: the
     * guest then simply has no adb command, like a device without
     * platform-tools.
     */
    private static void installTermDebAdbClient(Context context, File filesDir) {
        File debianRoot = new File(filesDir, "debian-root");
        if (!debianRoot.isDirectory()) {
            Logger.logDebug(LOG_TAG, "ADB client install skipped: no Debian rootfs yet");
            return;
        }
        try {
            File adbBin = new File(debianRoot, "usr/bin/adb");
            adbBin.getParentFile().mkdirs();
            extractAsset(context, TERMDEB_ADB_DIR + "/adb", adbBin);
            setExecutable(adbBin);
            Logger.logInfo(LOG_TAG, "ADB guest client installed to " + adbBin.getAbsolutePath());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "ADB guest client not installed: " + e.getMessage());
        }
    }

    /**
     * Rewrite the shebang line of bootstrap shell scripts that point at the official
     * "com.termux" prefix. The bootstrap packages are built for com.termux, so 90+
     * scripts in $PREFIX/bin (am, termux-am, termux-setup-storage, apt helpers, ...)
     * start with "#!/data/data/com.termux/files/usr/bin/{bash,sh}", which cannot be
     * executed by a fork with a different package name ("Permission denied" from the
     * kernel when it tries to open the interpreter). Point them at this fork's own
     * bash/sh instead. Non-fatal and idempotent.
     */
    private static void fixBootstrapShebangs(File filesDir) {
        File binDir = new File(filesDir, "usr/bin");
        File[] scripts = binDir.listFiles();
        if (scripts == null) return;

        String oldBashShebang = "#!/data/data/com.termux/files/usr/bin/bash";
        String oldShShebang = "#!/data/data/com.termux/files/usr/bin/sh";
        String newBashShebang = "#!" + new File(binDir, "bash").getAbsolutePath();
        String newShShebang = "#!" + new File(binDir, "sh").getAbsolutePath();

        int fixed = 0;
        for (File script : scripts) {
            if (!script.isFile()) continue;
            try {
                String content = new String(java.nio.file.Files.readAllBytes(script.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
                // Replace the whole first line (NOT in-place byte patching): the fork's
                // shebang is longer than the com.termux one, so overwriting in place would
                // clobber the start of the second line and corrupt every script.
                String replacement = null;
                if (content.startsWith(oldBashShebang + "\n"))
                    replacement = newBashShebang + content.substring(oldBashShebang.length());
                else if (content.startsWith(oldBashShebang + "\r\n"))
                    replacement = newBashShebang + "\n" + content.substring(oldBashShebang.length() + 2);
                else if (content.startsWith(oldShShebang + "\n"))
                    replacement = newShShebang + content.substring(oldShShebang.length());
                else if (content.startsWith(oldShShebang + "\r\n"))
                    replacement = newShShebang + "\n" + content.substring(oldShShebang.length() + 2);

                if (replacement == null) continue;
                // Writing to the existing file preserves its permissions.
                java.nio.file.Files.write(script.toPath(),
                    replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fixed++;
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to fix shebang of " + script.getName() + ": " + e.getMessage());
            }
        }
        if (fixed > 0)
            Logger.logInfo(LOG_TAG, "Fixed " + fixed + " bootstrap script shebangs to the fork prefix");
    }

    /**
     * Write a script to a file and set it executable.
     */
    private static void writeScriptToFile(File file, String content) throws Exception {
        java.io.FileWriter writer = new java.io.FileWriter(file);
        writer.write(content);
        writer.close();
        file.setExecutable(true, false);
        file.setReadable(true, false);
    }

    /**
     * Setup the ELF architecture detection wrapper.
     * This creates a shell script that transparently routes x86_64 binaries through Box64.
     */
    private static void setupElfWrapper(File filesDir) throws Exception {
        File wrapperScript = new File(filesDir, "usr/bin/termdeb-exec");
        wrapperScript.getParentFile().mkdirs();

        String wrapperContent = "#!" + filesDir.getAbsolutePath() + "/usr/bin/bash\n" +
            "# TermDeb ELF Architecture Detection Wrapper\n" +
            "# Automatically routes x86_64 binaries through Box64\n" +
            "TERMDEB_PREFIX=\"" + filesDir.getParent() + "/files/usr\"\n" +
            "BOX64=\"${TERMDEB_PREFIX}/bin/box64\"\n" +
            "\n" +
            "if [ -z \"$1\" ]; then\n" +
            "  echo \"Usage: termdeb-exec <program> [args...]\"\n" +
            "  exit 1\n" +
            "fi\n" +
            "\n" +
            "# Check ELF magic and architecture\n" +
            "MAGIC=$(xxd -l 4 -p \"$1\" 2>/dev/null || head -c 4 \"$1\" 2>/dev/null | od -A n -t x1 | tr -d ' ')\n" +
            "if [ \"$MAGIC\" = \"7f454c46\" ]; then\n" +
            "  ELF_CLASS=$(xxd -s 4 -l 1 -p \"$1\" 2>/dev/null)\n" +
            "  if [ \"$ELF_CLASS\" = \"02\" ]; then\n" +
            "    ELF_MACHINE=$(xxd -s 18 -l 2 -e \"$1\" 2>/dev/null | awk '{print $2}')\n" +
            "    if [ \"$ELF_MACHINE\" = \"b7\" ]; then\n" +
            "      exec \"$1\" \"$@\"\n" +
            "    elif [ \"$ELF_MACHINE\" = \"3e\" ]; then\n" +
            "      if [ -x \"$BOX64\" ]; then\n" +
            "        exec \"$BOX64\" \"$1\" \"$@\"\n" +
            "      fi\n" +
            "    fi\n" +
            "  fi\n" +
            "fi\n" +
            "exec \"$1\" \"$@\"\n";

        java.io.FileWriter writer = new java.io.FileWriter(wrapperScript);
        writer.write(wrapperContent);
        writer.close();
        wrapperScript.setExecutable(true, false);
    }

}
