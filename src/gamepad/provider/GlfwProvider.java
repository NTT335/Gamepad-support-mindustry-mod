package gamepad.provider;

import arc.util.Log;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * GamepadProvider implementation for desktop platforms (Windows, macOS, Linux)
 * using LWJGL's GLFW gamepad API accessed entirely through Java reflection,
 * since LWJGL is only available at runtime on desktop.
 *
 * Uses glfwGetGamepadState (GLFW 3.3+) as primary method, falling back to
 * raw glfwGetJoystickAxes/glfwGetJoystickButtons if gamepad mapping is unavailable.
 */
public class GlfwProvider implements GamepadProvider {
    // GLFW joystick scan range
    private static final int JOYSTICK_FIRST = 0;
    private static final int JOYSTICK_LAST = 15;

    // GLFW gamepad button constants
    private static final int GLFW_GAMEPAD_BUTTON_A = 0;
    private static final int GLFW_GAMEPAD_BUTTON_B = 1;
    private static final int GLFW_GAMEPAD_BUTTON_X = 2;
    private static final int GLFW_GAMEPAD_BUTTON_Y = 3;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_BUMPER = 4;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER = 5;
    private static final int GLFW_GAMEPAD_BUTTON_BACK = 6;
    private static final int GLFW_GAMEPAD_BUTTON_START = 7;
    private static final int GLFW_GAMEPAD_BUTTON_GUIDE = 8;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_THUMB = 9;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_THUMB = 10;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_UP = 11;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_RIGHT = 12;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_DOWN = 13;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_LEFT = 14;

    // GLFW gamepad axis constants
    private static final int GLFW_GAMEPAD_AXIS_LEFT_X = 0;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_Y = 1;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_X = 2;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_Y = 3;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_TRIGGER = 4;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER = 5;

    // L2/R2 trigger threshold for button detection
    private static final float TRIGGER_BUTTON_THRESHOLD = 0.3f;

    // Provider state
    private boolean available;
    private boolean connected;
    private int activeJid = -1;
    private final float[] axes = new float[8];
    private final boolean[] buttons = new boolean[20];
    private String name;

    // Reflected GLFW methods
    private Method glfwJoystickPresent;
    private Method glfwJoystickIsGamepad;
    private Method glfwGetJoystickName;
    private Method glfwGetJoystickAxes;
    private Method glfwGetJoystickButtons;
    private Method glfwGetJoystickHats;
    private Method glfwGetGamepadState;

    // GLFWGamepadState reflection
    private Class<?> gamepadStateClass;
    private Method gamepadStateCreate;
    private Method gamepadStateButtons;
    private Method gamepadStateAxes;

    // Error tracking - log once per error type
    private boolean loggedInitError;
    private boolean loggedUpdateError;
    private boolean loggedGamepadStateError;
    private boolean loggedRawFallbackError;

    public GlfwProvider() {
        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");

            glfwJoystickPresent = glfwClass.getMethod("glfwJoystickPresent", int.class);
            glfwJoystickIsGamepad = glfwClass.getMethod("glfwJoystickIsGamepad", int.class);
            glfwGetJoystickName = glfwClass.getMethod("glfwGetJoystickName", int.class);
            glfwGetJoystickAxes = glfwClass.getMethod("glfwGetJoystickAxes", int.class);
            glfwGetJoystickButtons = glfwClass.getMethod("glfwGetJoystickButtons", int.class);
            glfwGetGamepadState = glfwClass.getMethod("glfwGetGamepadState", int.class, Class.forName("org.lwjgl.glfw.GLFWGamepadState"));

            // Try to get glfwGetJoystickHats - may not exist in older GLFW
            try {
                glfwGetJoystickHats = glfwClass.getMethod("glfwGetJoystickHats", int.class);
            } catch (NoSuchMethodException ignored) {
                // Hats method not available, will skip hat reading in raw fallback
            }

            gamepadStateClass = Class.forName("org.lwjgl.glfw.GLFWGamepadState");
            gamepadStateCreate = gamepadStateClass.getMethod("create");
            gamepadStateButtons = gamepadStateClass.getMethod("buttons", int.class);
            gamepadStateAxes = gamepadStateClass.getMethod("axes", int.class);

            available = true;
            Log.info("[GlfwProvider] GLFW gamepad reflection initialized successfully.");
        } catch (Exception e) {
            available = false;
            if (!loggedInitError) {
                Log.info("[GlfwProvider] GLFW not available, provider disabled: @", e.getMessage());
                loggedInitError = true;
            }
        }
    }

    @Override
    public void update() {
        if (!available) return;

        try {
            // Clear state
            for (int i = 0; i < axes.length; i++) axes[i] = 0f;
            for (int i = 0; i < buttons.length; i++) buttons[i] = false;

            // If we have an active joystick, check if it's still present
            if (activeJid >= 0) {
                boolean present = (boolean) glfwJoystickPresent.invoke(null, activeJid);
                if (!present) {
                    activeJid = -1;
                    connected = false;
                    name = null;
                }
            }

            // Scan for a connected joystick if none active
            if (activeJid < 0) {
                for (int jid = JOYSTICK_FIRST; jid <= JOYSTICK_LAST; jid++) {
                    boolean present = (boolean) glfwJoystickPresent.invoke(null, jid);
                    if (present) {
                        activeJid = jid;
                        connected = true;
                        name = (String) glfwGetJoystickName.invoke(null, jid);
                        break;
                    }
                }
            }

            if (activeJid < 0) {
                connected = false;
                return;
            }

            // Try gamepad API first (standardized mapping)
            boolean isGamepad = (boolean) glfwJoystickIsGamepad.invoke(null, activeJid);
            if (isGamepad) {
                readGamepadState(activeJid);
            } else {
                readRawState(activeJid);
            }

        } catch (Exception e) {
            if (!loggedUpdateError) {
                Log.err("[GlfwProvider] Error during update: @", e.getMessage());
                loggedUpdateError = true;
            }
        }
    }

    /**
     * Read state using glfwGetGamepadState (standardized mapping).
     * Maps GLFW gamepad buttons/axes to our internal format matching Linux js* numbering.
     */
    private void readGamepadState(int jid) {
        try {
            Object state = gamepadStateCreate.invoke(null);
            boolean success = (boolean) glfwGetGamepadState.invoke(null, jid, state);
            if (!success) {
                readRawState(jid);
                return;
            }

            // Read axes with mapping
            // GLFW axis 0 (LeftX) → our axis 0 (LX)
            axes[AXIS_LX] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_LEFT_X);
            // GLFW axis 1 (LeftY) → our axis 1 (LY)
            axes[AXIS_LY] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_LEFT_Y);
            // GLFW axis 2 (RightX) → our axis 3 (RX)
            axes[AXIS_RX] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_RIGHT_X);
            // GLFW axis 3 (RightY) → our axis 4 (RY)
            axes[AXIS_RY] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_RIGHT_Y);
            // GLFW axis 4 (LeftTrigger) → our axis 2 (L2), GLFW range -1..1
            axes[AXIS_L2] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_LEFT_TRIGGER);
            // GLFW axis 5 (RightTrigger) → our axis 5 (R2), GLFW range -1..1
            axes[AXIS_R2] = (float) gamepadStateAxes.invoke(state, GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER);

            // Read buttons with mapping
            // GLFW button 0 (A) → our button 0 (Cross)
            buttons[0] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_A) != 0;
            // GLFW button 1 (B) → our button 1 (Circle)
            buttons[1] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_B) != 0;
            // GLFW button 2 (X) → our button 3 (Square) [SWAPPED]
            buttons[3] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_X) != 0;
            // GLFW button 3 (Y) → our button 2 (Triangle) [SWAPPED]
            buttons[2] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_Y) != 0;
            // GLFW button 4 (LB) → our button 4 (L1)
            buttons[4] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER) != 0;
            // GLFW button 5 (RB) → our button 5 (R1)
            buttons[5] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER) != 0;
            // GLFW button 6 (Back) → our button 8 (Share)
            buttons[8] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_BACK) != 0;
            // GLFW button 7 (Start) → our button 9 (Options)
            buttons[9] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_START) != 0;
            // GLFW button 8 (Guide) → our button 10 (PS)
            buttons[10] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_GUIDE) != 0;
            // GLFW button 9 (LeftThumb) → our button 11 (L3)
            buttons[11] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_LEFT_THUMB) != 0;
            // GLFW button 10 (RightThumb) → our button 12 (R3)
            buttons[12] = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_RIGHT_THUMB) != 0;

            // L2/R2 as buttons: set buttons[6] and buttons[7] based on trigger axes
            buttons[6] = axes[AXIS_L2] > TRIGGER_BUTTON_THRESHOLD;
            buttons[7] = axes[AXIS_R2] > TRIGGER_BUTTON_THRESHOLD;

            // DPad buttons → axes[6] (DPadX) and axes[7] (DPadY)
            boolean dpadUp = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_DPAD_UP) != 0;
            boolean dpadDown = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_DPAD_DOWN) != 0;
            boolean dpadLeft = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_DPAD_LEFT) != 0;
            boolean dpadRight = (byte) gamepadStateButtons.invoke(state, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) != 0;

            axes[AXIS_DPAD_X] = (dpadRight ? 1f : 0f) - (dpadLeft ? 1f : 0f);
            axes[AXIS_DPAD_Y] = (dpadDown ? 1f : 0f) - (dpadUp ? 1f : 0f);

        } catch (Exception e) {
            if (!loggedGamepadStateError) {
                Log.err("[GlfwProvider] Error reading gamepad state: @", e.getMessage());
                loggedGamepadStateError = true;
            }
        }
    }

    /**
     * Read state using raw glfwGetJoystickAxes/glfwGetJoystickButtons (no standardized mapping).
     * Axes and buttons are used as-is from the driver.
     */
    private void readRawState(int jid) {
        try {
            // Read raw axes
            FloatBuffer rawAxes = (FloatBuffer) glfwGetJoystickAxes.invoke(null, jid);
            if (rawAxes != null) {
                int count = Math.min(rawAxes.remaining(), axes.length);
                for (int i = 0; i < count; i++) {
                    axes[i] = rawAxes.get(i);
                }
            }

            // Read raw buttons
            ByteBuffer rawButtons = (ByteBuffer) glfwGetJoystickButtons.invoke(null, jid);
            if (rawButtons != null) {
                int count = Math.min(rawButtons.remaining(), buttons.length);
                for (int i = 0; i < count; i++) {
                    buttons[i] = rawButtons.get(i) != 0;
                }
            }

            // Read hats if available - map to DPad axes
            if (glfwGetJoystickHats != null) {
                ByteBuffer hats = (ByteBuffer) glfwGetJoystickHats.invoke(null, jid);
                if (hats != null && hats.remaining() > 0) {
                    int hat = hats.get(0) & 0xFF;
                    // GLFW hat bits: 1=up, 2=right, 4=down, 8=left
                    boolean hatUp = (hat & 1) != 0;
                    boolean hatRight = (hat & 2) != 0;
                    boolean hatDown = (hat & 4) != 0;
                    boolean hatLeft = (hat & 8) != 0;

                    axes[AXIS_DPAD_X] = (hatRight ? 1f : 0f) - (hatLeft ? 1f : 0f);
                    axes[AXIS_DPAD_Y] = (hatDown ? 1f : 0f) - (hatUp ? 1f : 0f);
                }
            }

        } catch (Exception e) {
            if (!loggedRawFallbackError) {
                Log.err("[GlfwProvider] Error reading raw joystick state: @", e.getMessage());
                loggedRawFallbackError = true;
            }
        }
    }

    @Override
    public boolean isConnected() {
        return available && connected;
    }

    @Override
    public float getAxis(int index) {
        if (index < 0 || index >= axes.length) return 0f;
        return axes[index];
    }

    @Override
    public boolean getButton(int index) {
        if (index < 0 || index >= buttons.length) return false;
        return buttons[index];
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void dispose() {
        connected = false;
        activeJid = -1;
        name = null;

        // Clear reflected references
        glfwJoystickPresent = null;
        glfwJoystickIsGamepad = null;
        glfwGetJoystickName = null;
        glfwGetJoystickAxes = null;
        glfwGetJoystickButtons = null;
        glfwGetJoystickHats = null;
        glfwGetGamepadState = null;
        gamepadStateClass = null;
        gamepadStateCreate = null;
        gamepadStateButtons = null;
        gamepadStateAxes = null;
    }
}
