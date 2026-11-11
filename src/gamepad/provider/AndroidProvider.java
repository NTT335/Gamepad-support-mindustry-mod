package gamepad.provider;

import arc.Core;
import arc.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GamepadProvider implementation for Android devices.
 * Uses reflection to access Arc's controller KeyCode entries and axis methods,
 * since android.* classes and controller-specific KeyCodes may not be available
 * at compile time in a Mindustry mod.
 */
public class AndroidProvider implements GamepadProvider {

    private boolean available;
    private boolean connected;
    private final float[] axes = new float[8];
    private final boolean[] buttons = new boolean[20];
    private String name;

    // Reflected KeyCode enum constants for controller buttons
    private Object kcA, kcB, kcY, kcX;
    private Object kcLBumper, kcRBumper;
    private Object kcBack, kcStart, kcGuide;
    private Object kcLStickPress, kcRStickPress;
    private Object kcDpadUp, kcDpadDown, kcDpadLeft, kcDpadRight;

    // Reflected KeyCode enum constants for axes
    private Object kcLStickXAxis, kcLStickYAxis;
    private Object kcRStickXAxis, kcRStickYAxis;
    private Object kcLTrigger, kcRTrigger;

    // Reflected methods
    private Method keyDownMethod;
    private Method axisMethod;
    private boolean hasAxisMethod;

    // Android InputDevice reflection for name/connection
    private Method getDeviceIdsMethod;
    private Method getDeviceMethod;
    private Method getNameMethod;
    private Method getSourcesMethod;
    private int sourceGamepad;
    private boolean hasDeviceReflection;

    public AndroidProvider() {
        try {
            Class.forName("android.view.InputDevice");
        } catch (ClassNotFoundException e) {
            available = false;
            Log.info("[GamepadMod] AndroidProvider: not on Android, unavailable.");
            return;
        }

        available = true;
        Log.info("[GamepadMod] AndroidProvider: Android detected, initializing...");

        // Resolve KeyCode enum class via reflection
        Class<?> keyCodeClass = null;
        try {
            keyCodeClass = Class.forName("arc.input.KeyCode");
        } catch (ClassNotFoundException e) {
            Log.warn("[GamepadMod] AndroidProvider: arc.input.KeyCode not found.");
            available = false;
            return;
        }

        // Resolve button KeyCodes
        kcA = getKeyCode(keyCodeClass, "controllerA");
        kcB = getKeyCode(keyCodeClass, "controllerB");
        kcY = getKeyCode(keyCodeClass, "controllerY");
        kcX = getKeyCode(keyCodeClass, "controllerX");
        kcLBumper = getKeyCode(keyCodeClass, "controllerLBumper");
        kcRBumper = getKeyCode(keyCodeClass, "controllerRBumper");
        kcBack = getKeyCode(keyCodeClass, "controllerBack");
        kcStart = getKeyCode(keyCodeClass, "controllerStart");
        kcGuide = getKeyCode(keyCodeClass, "controllerGuide");
        kcLStickPress = getKeyCode(keyCodeClass, "controllerLStickPress");
        kcRStickPress = getKeyCode(keyCodeClass, "controllerRStickPress");
        kcDpadUp = getKeyCode(keyCodeClass, "controllerDpadUp");
        kcDpadDown = getKeyCode(keyCodeClass, "controllerDpadDown");
        kcDpadLeft = getKeyCode(keyCodeClass, "controllerDpadLeft");
        kcDpadRight = getKeyCode(keyCodeClass, "controllerDpadRight");

        // Resolve axis KeyCodes
        kcLStickXAxis = getKeyCode(keyCodeClass, "controllerLStickXAxis");
        kcLStickYAxis = getKeyCode(keyCodeClass, "controllerLStickYAxis");
        kcRStickXAxis = getKeyCode(keyCodeClass, "controllerRStickXAxis");
        kcRStickYAxis = getKeyCode(keyCodeClass, "controllerRStickYAxis");
        kcLTrigger = getKeyCode(keyCodeClass, "controllerLTrigger");
        kcRTrigger = getKeyCode(keyCodeClass, "controllerRTrigger");

        // Resolve Core.input.keyDown(KeyCode) method
        try {
            keyDownMethod = Core.input.getClass().getMethod("keyDown", keyCodeClass);
        } catch (Exception e) {
            Log.warn("[GamepadMod] AndroidProvider: keyDown(KeyCode) method not found: @", e.getMessage());
        }

        // Resolve Core.input.axis(KeyCode) method for analog sticks/triggers
        try {
            axisMethod = Core.input.getClass().getMethod("axis", keyCodeClass);
            hasAxisMethod = true;
        } catch (Exception e) {
            Log.info("[GamepadMod] AndroidProvider: axis(KeyCode) method not found, analog axes unavailable.");
            hasAxisMethod = false;
        }

        // Try to set up Android InputDevice reflection for name/connection detection
        setupDeviceReflection();

        Log.info("[GamepadMod] AndroidProvider: initialization complete.");
    }

    private Object getKeyCode(Class<?> keyCodeClass, String name) {
        try {
            Field field = keyCodeClass.getField(name);
            return field.get(null);
        } catch (Exception e) {
            // Not all versions of Arc have all controller KeyCodes
            return null;
        }
    }

    private void setupDeviceReflection() {
        try {
            Class<?> inputDeviceClass = Class.forName("android.view.InputDevice");
            getDeviceIdsMethod = inputDeviceClass.getMethod("getDeviceIds");
            getDeviceMethod = inputDeviceClass.getMethod("getDevice", int.class);
            getNameMethod = inputDeviceClass.getMethod("getName");
            getSourcesMethod = inputDeviceClass.getMethod("getSources");

            // android.view.InputDevice.SOURCE_GAMEPAD = 0x00000401
            Field sourceField = inputDeviceClass.getField("SOURCE_GAMEPAD");
            sourceGamepad = sourceField.getInt(null);

            hasDeviceReflection = true;
        } catch (Exception e) {
            Log.info("[GamepadMod] AndroidProvider: InputDevice reflection failed, using fallback detection: @", e.getMessage());
            hasDeviceReflection = false;
        }
    }

    @Override
    public void update() {
        if (!available || keyDownMethod == null) return;

        // Poll button states via Arc's input system
        buttons[0] = pollKey(kcA);
        buttons[1] = pollKey(kcB);
        buttons[2] = pollKey(kcY);
        buttons[3] = pollKey(kcX);
        buttons[4] = pollKey(kcLBumper);
        buttons[5] = pollKey(kcRBumper);
        buttons[8] = pollKey(kcBack);
        buttons[9] = pollKey(kcStart);
        buttons[10] = pollKey(kcGuide);
        buttons[11] = pollKey(kcLStickPress);
        buttons[12] = pollKey(kcRStickPress);

        // DPad as axes
        boolean dpadRight = pollKey(kcDpadRight);
        boolean dpadLeft = pollKey(kcDpadLeft);
        boolean dpadDown = pollKey(kcDpadDown);
        boolean dpadUp = pollKey(kcDpadUp);
        axes[AXIS_DPAD_X] = (dpadRight ? 1f : 0f) - (dpadLeft ? 1f : 0f);
        axes[AXIS_DPAD_Y] = (dpadDown ? 1f : 0f) - (dpadUp ? 1f : 0f);

        // Poll analog axes if axis method is available
        if (hasAxisMethod) {
            axes[AXIS_LX] = pollAxis(kcLStickXAxis);
            axes[AXIS_LY] = pollAxis(kcLStickYAxis);
            axes[AXIS_RX] = pollAxis(kcRStickXAxis);
            axes[AXIS_RY] = pollAxis(kcRStickYAxis);
            axes[AXIS_L2] = pollAxis(kcLTrigger);
            axes[AXIS_R2] = pollAxis(kcRTrigger);
        }

        // Detect connection: any button pressed or any significant axis value
        boolean anyInput = false;
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i]) {
                anyInput = true;
                break;
            }
        }
        if (!anyInput) {
            for (int i = 0; i < axes.length; i++) {
                if (Math.abs(axes[i]) > 0.1f) {
                    anyInput = true;
                    break;
                }
            }
        }

        if (anyInput) {
            connected = true;
        }

        // Periodically check Android InputDevice list for connection and name
        if (hasDeviceReflection) {
            updateDeviceInfo();
        }
    }

    private boolean pollKey(Object keyCode) {
        if (keyCode == null || keyDownMethod == null) return false;
        try {
            Object result = keyDownMethod.invoke(Core.input, keyCode);
            return (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private float pollAxis(Object keyCode) {
        if (keyCode == null || axisMethod == null) return 0f;
        try {
            Object result = axisMethod.invoke(Core.input, keyCode);
            return (Float) result;
        } catch (Exception e) {
            return 0f;
        }
    }

    private void updateDeviceInfo() {
        try {
            int[] deviceIds = (int[]) getDeviceIdsMethod.invoke(null);
            boolean found = false;
            for (int id : deviceIds) {
                Object device = getDeviceMethod.invoke(null, id);
                if (device == null) continue;

                int sources = (Integer) getSourcesMethod.invoke(device);
                if ((sources & sourceGamepad) == sourceGamepad) {
                    found = true;
                    if (name == null) {
                        name = (String) getNameMethod.invoke(device);
                        Log.info("[GamepadMod] AndroidProvider: gamepad found: @", name);
                    }
                    break;
                }
            }
            connected = connected || found;
            if (!found) {
                connected = false;
                name = null;
            }
        } catch (Exception e) {
            // Silently ignore - device scanning is best-effort
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
        available = false;
        connected = false;
        name = null;
        Log.info("[GamepadMod] AndroidProvider: disposed.");
    }
}
