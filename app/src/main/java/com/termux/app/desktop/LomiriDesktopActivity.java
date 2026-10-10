package com.termux.app.desktop;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;

/**
 * Full Lomiri desktop environment rendered through Mir.
 *
 * <p>The heavy lifting happens inside the packaged Debian trixie guest: the
 * {@code termdeb-desktop} host script enters Debian via proot and starts the
 * {@code termdeb-desktop-session} script, which brings up a Mir compositor with a
 * software (egl-generic) renderer on a virtual output, starts the Lomiri shell on it,
 * and runs {@code termdeb-mir-bridge} to pump frames into the shared framebuffer and
 * inject injected input.
 *
 * <p>This activity owns the Android side of that bridge: the shared framebuffer is
 * presented on {@link LomiriDesktopView}, and the existing mini-keyboard (reused
 * unchanged) plus touch gestures feed input back to the guest.
 *
 * <p>No VNC or remote-desktop protocol is involved: frames travel through a local
 * memory-mapped file and input through a local abstract socket.
 */
public final class LomiriDesktopActivity extends AppCompatActivity implements ServiceConnection {

    private static final String LOG_TAG = "LomiriDesktopActivity";

    public static final String EXTRA_FORCE_TERMINAL = "com.termux.app.desktop.FORCE_TERMINAL";

    /** Desktop sessions are background Termux sessions; this name deduplicates them. */
    private static final String DESKTOP_SESSION_NAME = "termdeb-desktop";

    private static final float DEFAULT_EXTRA_KEYS_ROW_DP = 37.5f;

    private TermuxService mTermuxService;
    private boolean mSessionRequested;
    private boolean mIsInvalidState;

    private LomiriDesktopDisplay mDisplay;
    private ExtraKeysView mExtraKeysView;
    private TermuxAppSharedProperties mProperties;
    private TermuxAppSharedPreferences mPreferences;
    private float mDefaultExtraKeysRowHeightPx;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        super.onCreate(savedInstanceState);

        mProperties = TermuxAppSharedProperties.getProperties();
        mProperties.loadTermuxPropertiesFromDisk();

        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            mIsInvalidState = true;
            finish();
            return;
        }

        if (mProperties.isUsingFullScreen())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_lomiri_desktop);

        mDefaultExtraKeysRowHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            DEFAULT_EXTRA_KEYS_ROW_DP, getResources().getDisplayMetrics());

        int maxWidth = 1920;
        int maxHeight = 1200;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            // The guest renders at the device resolution (a reasonable default desktop size).
            maxWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
            maxHeight = Math.min(metrics.widthPixels, metrics.heightPixels);
        }

        try {
            File runtimeDir = LomiriDesktopDisplay.runtimeDir(getFilesDir());
            mDisplay = new LomiriDesktopDisplay(runtimeDir, roundUpTo(maxWidth, 16), roundUpTo(maxHeight, 16));
            mDisplay.open();
            mDisplay.startInputServer();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to initialise desktop display bridge", e);
            Logger.showToast(this, "TermDeb desktop: display bridge failed: " + e.getMessage(), true);
            mIsInvalidState = true;
            finish();
            return;
        }

        LomiriDesktopView surfaceView = findViewById(R.id.lomiri_desktop_surface);
        surfaceView.setDisplay(mDisplay);

        setUpMiniKeyboard();

        findViewById(R.id.lomiri_desktop_terminal_button).setOnClickListener(v -> openTerminal());

        Intent serviceIntent = new Intent(this, TermuxService.class);
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            Log.e(LOG_TAG, "bindService() failed");
            mIsInvalidState = true;
            finish();
        }
    }

    private static int roundUpTo(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

    /**
     * Inflate the existing mini-keyboard (its layout and widget are unchanged) and size
     * it exactly like {@link TermuxActivity} sizes the terminal toolbar.
     */
    private void setUpMiniKeyboard() {
        FrameLayout container = findViewById(R.id.lomiri_desktop_keyboard_container);
        mExtraKeysView = findViewById(R.id.terminal_toolbar_extra_keys);
        if (mExtraKeysView == null || container == null) return;

        ExtraKeysInfo extraKeysInfo = buildExtraKeysInfo();
        if (extraKeysInfo == null) {
            container.setVisibility(View.GONE);
            return;
        }

        LomiriDesktopExtraKeys client = new LomiriDesktopExtraKeys(mDisplay, new LomiriDesktopExtraKeys.SpecialKeyHandler() {
            @Override
            public void onToggleSoftKeyboard() {
                // The desktop consumes the hardware/virtual keyboard; nothing to toggle here.
            }

            @Override
            public void onPasteFromClipboard() {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(LomiriDesktopActivity.this);
                    if (text != null)
                        for (int i = 0; i < text.length(); i++) mDisplay.sendText(text.charAt(i));
                }
            }
        });

        mExtraKeysView.setExtraKeysViewClient(client);
        mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
        mExtraKeysView.reload(extraKeysInfo, mDefaultExtraKeysRowHeightPx);

        ViewGroup.LayoutParams params = container.getLayoutParams();
        params.height = Math.round(mDefaultExtraKeysRowHeightPx * extraKeysInfo.getMatrix().length);
        container.setLayoutParams(params);
        container.setVisibility(View.VISIBLE);
    }

    /** Build {@link ExtraKeysInfo} from termux.properties, mirroring the terminal's logic. */
    @Nullable
    private ExtraKeysInfo buildExtraKeysInfo() {
        try {
            String extraKeys = (String) mProperties.getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mProperties.getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
            return new ExtraKeysInfo(extraKeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not build extra keys info", e);
            try {
                return new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
                    TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e2) {
                return null;
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermuxService = ((TermuxService.LocalBinder) service).service;
        if (mSessionRequested) return;
        mSessionRequested = true;
        ensureRuntimeAndStartDesktop();
    }

    /**
     * Make sure the Termux bootstrap, the Debian runtime and the Lomiri desktop
     * overlay are all present before launching the guest session.
     */
    private void ensureRuntimeAndStartDesktop() {
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            if (isFinishing() || mTermuxService == null) return;

            File debianRoot = new File(getFilesDir(), "debian-root");
            boolean runtimeInstalled = new File(debianRoot, "etc/os-release").isFile();

            Runnable installDesktop = () -> TermuxInstaller.installDesktopRuntime(this, this::startDesktopSession);

            if (!runtimeInstalled && TermuxInstaller.hasTermDebAssets(this)) {
                TermuxInstaller.installTermDebRuntime(this, installDesktop);
            } else if (!TermuxInstaller.isDesktopProvisioned(debianRoot) && TermuxInstaller.hasTermDebAssets(this)) {
                // The installed rootfs came from an APK that shipped a rootfs without the
                // Lomiri/Mir payload (or its extraction was incomplete). Reinstall the
                // runtime so the bundled desktop-provisioned rootfs is unpacked; otherwise
                // the guest session aborts with "no Mir server binary found", because the
                // extraction step only runs when debian-root is absent.
                TermuxInstaller.reinstallTermDebRuntime(this, installDesktop);
            } else {
                installDesktop.run();
            }
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mTermuxService = null;
    }

    /** Launch {@code $PREFIX/bin/termdeb-desktop} as a background Termux session. */
    private void startDesktopSession() {
        if (mTermuxService == null) return;

        String executable = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/termdeb-desktop";
        if (mTermuxService.getTermuxSessionForShellName(DESKTOP_SESSION_NAME) != null) {
            Log.i(LOG_TAG, "Desktop session already running");
            return;
        }

        Log.i(LOG_TAG, "Starting desktop session: " + executable);
        mTermuxService.createTermuxSession(executable, new String[0], null,
            TermuxConstants.TERMUX_HOME_DIR_PATH, false, DESKTOP_SESSION_NAME);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward hardware volume/system keys to the guest where meaningful; leave the
        // Android back button to close the desktop.
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDisplay != null) {
            mDisplay.close();
            mDisplay = null;
        }
        try {
            unbindService(this);
        } catch (Exception ignored) {
        }
    }

    /** Open the classic terminal UI instead of the desktop. */
    public void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.putExtra(TermuxActivity.EXTRA_FORCE_TERMINAL, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }
}
