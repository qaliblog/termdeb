package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        environment.put(ENV_HOME, TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put(ENV_PREFIX, TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        // TermDeb Debian session configuration
        // When TERMDEB_DEFAULT_SESSION is set to "debian", the login shell wrapper
        // will automatically enter the Debian ARM64 environment via proot-distro.
        environment.put("TERMDEB_DEFAULT_SESSION", "debian");
        environment.put("TERMDEB_HOST_SHELL", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");

        // proot (bundled with the TermDeb offline runtime) was compiled with the default
        // "com.termux" prefix hardcoded, so its loader path and tmp dir must be overridden
        // to point at this fork's own prefix, otherwise execve() of guest binaries fails.
        environment.put("PROOT_LOADER", TermuxConstants.TERMUX_LIBEXEC_PREFIX_DIR_PATH + "/proot/loader");
        environment.put("PROOT_LOADER_32", TermuxConstants.TERMUX_LIBEXEC_PREFIX_DIR_PATH + "/proot/loader32");
        environment.put("PROOT_TMP_DIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

        // The bootstrap binaries were compiled with the official "com.termux" prefix
        // hardcoded, so their compiled-in CA bundle path
        // (/data/data/com.termux/files/usr/etc/tls/cert.pem) is not readable by forks.
        // Point curl/openssl at this fork's own CA bundle, otherwise HTTPS fails with
        // "error adding trust anchors" (curl exit 77) and the terminal looks offline.
        // For the official package this is a no-op (same path), so it is safe to always set.
        environment.put("CURL_CA_BUNDLE", TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH + "/tls/cert.pem");
        environment.put("SSL_CERT_FILE", TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH + "/tls/cert.pem");

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
                environment.put(ENV_LD_LIBRARY_PATH, TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            } else {
                // Termux binaries on Android 7+ rely on DT_RUNPATH, so LD_LIBRARY_PATH should be unset
                // by default when running as the official "com.termux" package.
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
                if (TermuxConstants.TERMUX_PACKAGE_NAME.equals("com.termux")) {
                    environment.remove(ENV_LD_LIBRARY_PATH);
                } else {
                    // Forks using a different package name cannot access the prefix that the bootstrap
                    // binaries were compiled with (DT_RUNPATH points to /data/data/com.termux/files/usr/lib),
                    // so LD_LIBRARY_PATH must point to the fork's own $PREFIX/lib for the dynamic linker
                    // to find libraries like libandroid-support.so.
                    environment.put(ENV_LD_LIBRARY_PATH, TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
                }
            }
        }

        return environment;
    }


    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    /**
     * Override login shell binaries to prioritize TermDeb shell wrapper.
     * When termdeb-shell-wrapper is installed in $PREFIX/bin/, it will be
     * selected as the default login shell, which automatically enters Debian.
     *
     * The termdeb-shell-wrapper checks TERMDEB_DEFAULT_SESSION and routes
     * to proot-distro debian if set.
     */
    @NonNull
    @Override
    public String[] getLoginShellBinaries() {
        String[] baseBinaries = super.getLoginShellBinaries();
        // Prepend TermDeb Debian entry point
        String[] termdebBinaries = new String[baseBinaries.length + 1];
        termdebBinaries[0] = "termdeb-shell-wrapper";
        System.arraycopy(baseBinaries, 0, termdebBinaries, 1, baseBinaries.length);
        return termdebBinaries;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

}
