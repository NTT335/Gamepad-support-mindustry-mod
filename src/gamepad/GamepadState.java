package gamepad;

import arc.util.Log;
import gamepad.provider.GamepadProvider;

/**
 * Holds the unified gamepad state: axes, buttons, previous frame state,
 * suppression flags, and provides helper methods for input queries.
 */
public class GamepadState {
    public static final int MAX_AXES = 8;
    public static final int MAX_BUTTONS = 20;

    public boolean connected;
    public final float[] axes = new float[MAX_AXES];
    public final boolean[] buttons = new boolean[MAX_BUTTONS];
    public final boolean[] prevButtons = new boolean[MAX_BUTTONS];
    public final boolean[] suppressed = new boolean[MAX_BUTTONS];

    private GamepadProvider provider;
    private String name;

    public GamepadState() {
        resetAxes();
    }

    public void setProvider(GamepadProvider provider) {
        this.provider = provider;
    }

    public GamepadProvider getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    /** Poll the provider and update state. Call from the main thread or reader thread. */
    public void poll() {
        if (provider == null) {
            connected = false;
            return;
        }
        try {
            provider.update();
            connected = provider.isConnected();
            if (connected) {
                for (int i = 0; i < MAX_AXES; i++) axes[i] = provider.getAxis(i);
                for (int i = 0; i < MAX_BUTTONS; i++) buttons[i] = provider.getButton(i);
                name = provider.getName();
            } else {
                resetAxes();
                resetButtons();
            }
        } catch (Exception e) {
            Log.err("[Gamepad] Poll error: " + e);
            connected = false;
        }
    }

    /** Raw button state (without suppression). L2/R2 also check trigger axis. */
    public boolean rawDown(int btn) {
        if (btn < 0 || btn >= MAX_BUTTONS) return false;
        if (btn == 6) return buttons[6] || (axes[GamepadProvider.AXIS_L2] > 0.3f);
        if (btn == 7) return buttons[7] || (axes[GamepadProvider.AXIS_R2] > 0.3f);
        return buttons[btn];
    }

    /** Button state with suppression. */
    public boolean isDown(int btn) {
        if (btn < 0 || btn >= MAX_BUTTONS) return false;
        if (suppressed[btn]) {
            if (!rawDown(btn)) suppressed[btn] = false;
            return false;
        }
        return rawDown(btn);
    }

    public boolean justPressed(int btn) {
        return isDown(btn) && !prevButtons[btn];
    }

    public boolean justReleased(int btn) {
        return !isDown(btn) && prevButtons[btn];
    }

    /** Save current state as previous (call at end of frame). */
    public void savePrevState() {
        for (int i = 0; i < MAX_BUTTONS; i++) prevButtons[i] = isDown(i);
    }

    /** Suppress all currently held buttons. */
    public void suppressAllHeld() {
        for (int i = 0; i < MAX_BUTTONS; i++) {
            if (rawDown(i)) suppressed[i] = true;
        }
    }

    public float applyDeadzone(float v, float deadzone) {
        float a = Math.abs(v);
        if (a < deadzone) return 0;
        return (v < 0 ? -1 : 1) * Math.min(1f, (a - deadzone) / (1f - deadzone));
    }

    public void resetButtons() {
        for (int i = 0; i < MAX_BUTTONS; i++) buttons[i] = false;
    }

    public void resetAxes() {
        for (int a = 0; a < MAX_AXES; a++) {
            axes[a] = (a == GamepadProvider.AXIS_L2 || a == GamepadProvider.AXIS_R2) ? -1f : 0f;
        }
    }

    public void dispose() {
        if (provider != null) {
            provider.dispose();
            provider = null;
        }
    }
}
