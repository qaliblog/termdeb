package com.termux.app.desktop;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;

import java.util.HashMap;
import java.util.Map;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;

/**
 * Mini-keyboard client for {@link LomiriDesktopActivity}.
 *
 * <p>The mini-keyboard widget ({@link ExtraKeysView}) and the layout that hosts it are
 * reused <b>unchanged</b>; only the sink differs. Where the terminal routes key presses
 * into a {@link com.termux.view.TerminalView}, this client routes them into the desktop
 * input bridge: special keys are translated to Linux input (evdev) codes and injected
 * into Mir inside the guest via the Wayland virtual-keyboard protocol, while ordinary
 * characters are sent as Unicode text.
 */
public final class LomiriDesktopExtraKeys implements ExtraKeysView.IExtraKeysView {

    public interface SpecialKeyHandler {
        void onToggleSoftKeyboard();
        void onPasteFromClipboard();
    }

    // Modifier bits shared with termdeb-mir-bridge.c.
    public static final int MOD_CTRL = 1;
    public static final int MOD_ALT = 2;
    public static final int MOD_SHIFT = 4;

    private final LomiriDesktopDisplay mDisplay;
    private final SpecialKeyHandler mSpecialKeyHandler;

    public LomiriDesktopExtraKeys(LomiriDesktopDisplay display, SpecialKeyHandler specialKeyHandler) {
        mDisplay = display;
        mSpecialKeyHandler = specialKeyHandler;
    }

    @Override
    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (buttonInfo.isMacro()) {
            String[] keys = buttonInfo.getKey().split(" ");
            boolean ctrlDown = false, altDown = false, shiftDown = false, fnDown = false;
            for (String key : keys) {
                if (SpecialButton.CTRL.getKey().equals(key)) ctrlDown = true;
                else if (SpecialButton.ALT.getKey().equals(key)) altDown = true;
                else if (SpecialButton.SHIFT.getKey().equals(key)) shiftDown = true;
                else if (SpecialButton.FN.getKey().equals(key)) fnDown = true;
                else {
                    dispatch(view, key, ctrlDown, altDown, shiftDown, fnDown);
                    ctrlDown = false; altDown = false; shiftDown = false; fnDown = false;
                }
            }
        } else {
            dispatch(view, buttonInfo.getKey(), false, false, false, false);
        }
    }

    private void dispatch(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if (mSpecialKeyHandler != null) mSpecialKeyHandler.onToggleSoftKeyboard();
            return;
        }
        if ("PASTE".equals(key)) {
            if (mSpecialKeyHandler != null) mSpecialKeyHandler.onPasteFromClipboard();
            return;
        }
        if ("DRAWER".equals(key) || "SCROLL".equals(key)) {
            // The desktop has no terminal drawer/scrollback to toggle; ignore.
            return;
        }

        int modifiers = 0;
        if (ctrlDown) modifiers |= MOD_CTRL;
        if (altDown) modifiers |= MOD_ALT;
        if (shiftDown) modifiers |= MOD_SHIFT;

        Integer androidKeyCode = PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
        if (androidKeyCode != null) {
            int evdev = androidKeyCodeToEvdev(androidKeyCode);
            if (evdev > 0) {
                mDisplay.sendKey(true, evdev, modifiers);
                mDisplay.sendKey(false, evdev, modifiers);
                return;
            }
        }

        // Not a special key: send the literal characters as text.
        if (key == null || key.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            key.codePoints().forEach(mDisplay::sendText);
        } else {
            for (char c : key.toCharArray()) mDisplay.sendText(c);
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        return false;
    }

    /** Android {@link KeyEvent} code -> Linux input event code (linux/input-event-codes.h). */
    private static final Map<Integer, Integer> ANDROID_TO_EVDEV = new HashMap<Integer, Integer>() {{
        put(KeyEvent.KEYCODE_SPACE, 57);       // KEY_SPACE
        put(KeyEvent.KEYCODE_ESCAPE, 1);       // KEY_ESC
        put(KeyEvent.KEYCODE_TAB, 15);         // KEY_TAB
        put(KeyEvent.KEYCODE_ENTER, 28);       // KEY_ENTER
        put(KeyEvent.KEYCODE_DEL, 14);         // KEY_BACKSPACE
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111); // KEY_DELETE
        put(KeyEvent.KEYCODE_INSERT, 110);     // KEY_INSERT
        put(KeyEvent.KEYCODE_MOVE_HOME, 102);  // KEY_HOME
        put(KeyEvent.KEYCODE_MOVE_END, 107);   // KEY_END
        put(KeyEvent.KEYCODE_PAGE_UP, 104);    // KEY_PAGEUP
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109);  // KEY_PAGEDOWN
        put(KeyEvent.KEYCODE_DPAD_UP, 103);    // KEY_UP
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108);  // KEY_DOWN
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105);  // KEY_LEFT
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106); // KEY_RIGHT
        put(KeyEvent.KEYCODE_F1, 59);
        put(KeyEvent.KEYCODE_F2, 60);
        put(KeyEvent.KEYCODE_F3, 61);
        put(KeyEvent.KEYCODE_F4, 62);
        put(KeyEvent.KEYCODE_F5, 63);
        put(KeyEvent.KEYCODE_F6, 64);
        put(KeyEvent.KEYCODE_F7, 65);
        put(KeyEvent.KEYCODE_F8, 66);
        put(KeyEvent.KEYCODE_F9, 67);
        put(KeyEvent.KEYCODE_F10, 68);
        put(KeyEvent.KEYCODE_F11, 87);
        put(KeyEvent.KEYCODE_F12, 88);
    }};

    private static int androidKeyCodeToEvdev(int androidKeyCode) {
        Integer code = ANDROID_TO_EVDEV.get(androidKeyCode);
        return code != null ? code : -1;
    }

    static void showToast(@NonNull LomiriDesktopActivity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
