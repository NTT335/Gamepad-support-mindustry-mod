package gamepad.provider;

import arc.Core;
import arc.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * GamepadProvider implementation for Android devices.
 * Uses reflection and dynamic proxy to hook the Activity's Window.Callback,
 * capturing dispatchGenericMotionEvent and dispatchKeyEvent directly from the Android OS.
 */
public class AndroidProvider implements GamepadProvider {

    private boolean available;
    private boolean connected;
    private final float[] axes = new float[8];
    private final boolean[] buttons = new boolean[20];
    private String name = "Android Gamepad";

    // D-pad key state tracker
    private int dpadKeyX = 0;
    private int dpadKeyY = 0;

    // Window callback hooking
    private Object hookedWindow;
    private Object hookedCallback;
    private Object proxyCallback;
    private float hookRetryTimer = 0f;

    // MotionEvent reflection
    private Method getSourceMotion;
    private Method getAxisValueMotion;
    private Method getDeviceMotion;

    // KeyEvent reflection
    private Method getSourceKey;
    private Method getActionKey;
    private Method getKeyCodeKey;
    private Method getDeviceKey;

    // InputDevice reflection
    private Method getDeviceIdsMethod;
    private Method getDeviceStaticMethod;
    private Method getSourcesMethod;
    private Method getNameMethod;
    private boolean hasDeviceReflection;
    private float scanTimer = 0f;

    public AndroidProvider() {
        try {
            Class.forName("android.view.InputDevice");
            Class.forName("android.view.MotionEvent");
            Class.forName("android.view.KeyEvent");
        } catch (ClassNotFoundException e) {
            available = false;
            Log.info("[AndroidProvider] Not on Android, provider disabled.");
            return;
        }

        available = true;
        Log.info("[AndroidProvider] Android detected, initializing reflection hooks...");

        try {
            Class<?> motionEventClass = Class.forName("android.view.MotionEvent");
            getSourceMotion = motionEventClass.getMethod("getSource");
            getAxisValueMotion = motionEventClass.getMethod("getAxisValue", int.class);
            try { getDeviceMotion = motionEventClass.getMethod("getDevice"); } catch (Throwable ignored) {}

            Class<?> keyEventClass = Class.forName("android.view.KeyEvent");
            getSourceKey = keyEventClass.getMethod("getSource");
            getActionKey = keyEventClass.getMethod("getAction");
            getKeyCodeKey = keyEventClass.getMethod("getKeyCode");
            try { getDeviceKey = keyEventClass.getMethod("getDevice"); } catch (Throwable ignored) {}

            setupDeviceReflection();
            hookWindow();
            updateDeviceInfo();
            Log.info("[AndroidProvider] Initialization complete.");
        } catch (Throwable t) {
            Log.err("[AndroidProvider] Init error: " + t);
        }
    }

    private void setupDeviceReflection() {
        try {
            Class<?> inputDeviceClass = Class.forName("android.view.InputDevice");
            getDeviceIdsMethod = inputDeviceClass.getMethod("getDeviceIds");
            getDeviceStaticMethod = inputDeviceClass.getMethod("getDevice", int.class);
            getNameMethod = inputDeviceClass.getMethod("getName");
            getSourcesMethod = inputDeviceClass.getMethod("getSources");
            hasDeviceReflection = true;
        } catch (Throwable t) {
            hasDeviceReflection = false;
        }
    }

    private void hookWindow() {
        try {
            if (Core.app == null) return;

            Class<?> activityClass = Class.forName("android.app.Activity");
            Object activity = null;
            if (activityClass.isInstance(Core.app)) {
                activity = Core.app;
            } else {
                for (Method m : Core.app.getClass().getMethods()) {
                    if (activityClass.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                        activity = m.invoke(Core.app);
                        if (activity != null) break;
                    }
                }
                if (activity == null) {
                    for (Field f : Core.app.getClass().getDeclaredFields()) {
                        if (activityClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            activity = f.get(Core.app);
                            if (activity != null) break;
                        }
                    }
                }
            }

            if (activity == null) return;

            Method getWindowMethod = activity.getClass().getMethod("getWindow");
            Object window = getWindowMethod.invoke(activity);
            if (window == null) return;

            Method getCallbackMethod = window.getClass().getMethod("getCallback");
            Object currentCallback = getCallbackMethod.invoke(window);
            if (currentCallback == null) return;

            // If already hooked to this window, nothing to do
            if (window == hookedWindow && currentCallback == proxyCallback) {
                return;
            }

            hookedWindow = window;
            hookedCallback = currentCallback;

            Class<?> callbackClass = Class.forName("android.view.Window$Callback");
            proxyCallback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        try {
                            String mName = method.getName();
                            if ("dispatchGenericMotionEvent".equals(mName) && args != null && args.length == 1) {
                                if (handleGenericMotionEvent(args[0])) {
                                    return true;
                                }
                            } else if ("dispatchKeyEvent".equals(mName) && args != null && args.length == 1) {
                                if (handleKeyEvent(args[0])) {
                                    return true;
                                }
                            }
                        } catch (Throwable t) {
                            Log.err("[AndroidProvider] Hook event error: " + t);
                        }
                        return method.invoke(hookedCallback, args);
                    }
                }
            );

            Method setCallbackMethod = window.getClass().getMethod("setCallback", callbackClass);
            setCallbackMethod.invoke(window, proxyCallback);
            Log.info("[AndroidProvider] Window.Callback successfully hooked!");
        } catch (Throwable t) {
            Log.err("[AndroidProvider] hookWindow failed: " + t);
        }
    }

    private boolean handleGenericMotionEvent(Object event) {
        if (event == null || getSourceMotion == null || getAxisValueMotion == null) return false;
        try {
            int source = (Integer) getSourceMotion.invoke(event);
            // SOURCE_JOYSTICK = 0x01000010, SOURCE_GAMEPAD = 0x00000401
            if ((source & 0x01000010) == 0 && (source & 0x00000401) == 0) {
                return false;
            }

            connected = true;

            // LX and LY
            axes[AXIS_LX] = (Float) getAxisValueMotion.invoke(event, 0); // AXIS_X
            axes[AXIS_LY] = (Float) getAxisValueMotion.invoke(event, 1); // AXIS_Y

            // RX and RY (AXIS_Z = 11, AXIS_RZ = 14; fallback AXIS_RX = 12, AXIS_RY = 13)
            float rx = (Float) getAxisValueMotion.invoke(event, 11);
            if (rx == 0f) rx = (Float) getAxisValueMotion.invoke(event, 12);
            float ry = (Float) getAxisValueMotion.invoke(event, 14);
            if (ry == 0f) ry = (Float) getAxisValueMotion.invoke(event, 13);
            axes[AXIS_RX] = rx;
            axes[AXIS_RY] = ry;

            // Triggers (AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18; fallback AXIS_BRAKE = 23, AXIS_GAS = 22)
            float lt = (Float) getAxisValueMotion.invoke(event, 17);
            if (lt == 0f) lt = (Float) getAxisValueMotion.invoke(event, 23);
            float rt = (Float) getAxisValueMotion.invoke(event, 18);
            if (rt == 0f) rt = (Float) getAxisValueMotion.invoke(event, 22);
            axes[AXIS_L2] = lt;
            axes[AXIS_R2] = rt;

            // D-Pad Hat (AXIS_HAT_X = 15, AXIS_HAT_Y = 16)
            float hatX = (Float) getAxisValueMotion.invoke(event, 15);
            float hatY = (Float) getAxisValueMotion.invoke(event, 16);
            if (Math.abs(hatX) > 0.1f) axes[AXIS_DPAD_X] = (hatX > 0f ? 1f : -1f);
            else if (dpadKeyX == 0) axes[AXIS_DPAD_X] = 0f;

            if (Math.abs(hatY) > 0.1f) axes[AXIS_DPAD_Y] = (hatY > 0f ? 1f : -1f);
            else if (dpadKeyY == 0) axes[AXIS_DPAD_Y] = 0f;

            // Trigger buttons
            buttons[6] = lt > 0.3f;
            buttons[7] = rt > 0.3f;

            if (getDeviceMotion != null) {
                try {
                    Object dev = getDeviceMotion.invoke(event);
                    if (dev != null && getNameMethod != null) {
                        name = (String) getNameMethod.invoke(dev);
                    }
                } catch (Throwable ignored) {}
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean handleKeyEvent(Object event) {
        if (event == null || getSourceKey == null || getActionKey == null || getKeyCodeKey == null) return false;
        try {
            int source = (Integer) getSourceKey.invoke(event);
            int action = (Integer) getActionKey.invoke(event);
            int keyCode = (Integer) getKeyCodeKey.invoke(event);
            boolean down = (action == 0); // ACTION_DOWN = 0

            boolean isGamepad = (source & 0x00000401) != 0 || (source & 0x01000010) != 0 || (source & 0x00000201) != 0;
            if (!isGamepad && ((keyCode >= 96 && keyCode <= 110) || (keyCode >= 19 && keyCode <= 22))) {
                isGamepad = true;
            }

            if (!isGamepad) return false;

            connected = true;

            if (getDeviceKey != null) {
                try {
                    Object dev = getDeviceKey.invoke(event);
                    if (dev != null && getNameMethod != null) {
                        name = (String) getNameMethod.invoke(dev);
                    }
                } catch (Throwable ignored) {}
            }

            switch (keyCode) {
                case 96: // BUTTON_A (Cross)
                    buttons[0] = down; return true;
                case 97: // BUTTON_B (Circle)
                    buttons[1] = down; return true;
                case 99: // BUTTON_X (Square)
                    buttons[3] = down; return true;
                case 100: // BUTTON_Y (Triangle)
                    buttons[2] = down; return true;
                case 102: // BUTTON_L1
                    buttons[4] = down; return true;
                case 103: // BUTTON_R1
                    buttons[5] = down; return true;
                case 104: // BUTTON_L2
                    buttons[6] = down; return true;
                case 105: // BUTTON_R2
                    buttons[7] = down; return true;
                case 106: // BUTTON_THUMBL (L3)
                    buttons[11] = down; return true;
                case 107: // BUTTON_THUMBR (R3)
                    buttons[12] = down; return true;
                case 108: // BUTTON_START (Options)
                    buttons[9] = down; return true;
                case 109: // BUTTON_SELECT (Share)
                    buttons[8] = down; return true;
                case 110: // BUTTON_MODE (Guide)
                    buttons[10] = down; return true;
                case 19: // DPAD_UP
                    dpadKeyY = down ? -1 : 0;
                    axes[AXIS_DPAD_Y] = (float) dpadKeyY;
                    return true;
                case 20: // DPAD_DOWN
                    dpadKeyY = down ? 1 : 0;
                    axes[AXIS_DPAD_Y] = (float) dpadKeyY;
                    return true;
                case 21: // DPAD_LEFT
                    dpadKeyX = down ? -1 : 0;
                    axes[AXIS_DPAD_X] = (float) dpadKeyX;
                    return true;
                case 22: // DPAD_RIGHT
                    dpadKeyX = down ? 1 : 0;
                    axes[AXIS_DPAD_X] = (float) dpadKeyX;
                    return true;
                case 4: // KEYCODE_BACK from controller
                    buttons[8] = down;
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void update() {
        if (!available) return;

        // Ensure window callback is hooked (re-hook on activity recreate or rotation)
        if (hookedWindow == null || proxyCallback == null) {
            hookWindow();
        } else {
            hookRetryTimer += 1f;
            if (hookRetryTimer >= 60f) {
                hookRetryTimer = 0f;
                hookWindow();
            }
        }

        // Keep soft keyboard hidden and useKeyboard enabled while gamepad is connected
        if (connected && Core.input != null) {
            try {
                Core.input.setOnscreenKeyboardVisible(false);
                Core.input.setUseKeyboard(true);
            } catch (Throwable ignored) {}
        }

        // Periodic device list check
        scanTimer += 1f;
        if (scanTimer >= 120f) {
            scanTimer = 0f;
            if (hasDeviceReflection) {
                updateDeviceInfo();
            }
        }
    }

    private void updateDeviceInfo() {
        if (!hasDeviceReflection || getDeviceIdsMethod == null) return;
        try {
            int[] deviceIds = (int[]) getDeviceIdsMethod.invoke(null);
            boolean found = false;
            if (deviceIds != null) {
                for (int id : deviceIds) {
                    Object device = getDeviceStaticMethod.invoke(null, id);
                    if (device == null) continue;
                    int sources = (Integer) getSourcesMethod.invoke(device);
                    if ((sources & 0x00000401) != 0 || (sources & 0x01000010) != 0) {
                        found = true;
                        if (name == null || name.equals("Android Gamepad")) {
                            String n = (String) getNameMethod.invoke(device);
                            if (n != null && !n.isEmpty()) name = n;
                        }
                        break;
                    }
                }
            }
            if (found) {
                connected = true;
            }
        } catch (Throwable ignored) {}
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
        try {
            if (hookedWindow != null && hookedCallback != null) {
                Method setCallbackMethod = hookedWindow.getClass().getMethod("setCallback", Class.forName("android.view.Window$Callback"));
                setCallbackMethod.invoke(hookedWindow, hookedCallback);
            }
        } catch (Throwable ignored) {}
        hookedWindow = null;
        hookedCallback = null;
        proxyCallback = null;
        Log.info("[AndroidProvider] Disposed.");
    }
}
