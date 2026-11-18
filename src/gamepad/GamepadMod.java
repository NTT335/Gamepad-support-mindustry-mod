package gamepad;

import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.util.Log;
import gamepad.provider.AndroidProvider;
import gamepad.provider.CompositeProvider;
import gamepad.provider.GlfwProvider;
import gamepad.provider.LinuxJsProvider;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.mod.Mod;

/**
 * Main entry point for the Cross-Platform Gamepad Support Mod (Mindustry v154+, Java 17).
 */
public class GamepadMod extends Mod {
    public static GamepadState state;
    public static GamepadConfig config;
    public static GamepadInput input;
    public static GamepadUI ui;
    public static GamepadRenderer renderer;
    public static GamepadKeyboard keyboard;

    @Override
    public void init() {
        Log.info("[GamepadMod] Initializing cross-platform Gamepad Support for Mindustry v154+ (Java 17)...");

        config = new GamepadConfig();
        try {
            config.load();
        } catch (Exception e) {
            Log.err("[GamepadMod] Error loading config: " + e);
        }

        state = new GamepadState();
        CompositeProvider composite = new CompositeProvider();

        // 1. GLFW provider for desktop (Windows 10/11, macOS, Linux)
        try {
            GlfwProvider glfw = new GlfwProvider();
            composite.add(glfw);
        } catch (Throwable t) {
            Log.warn("[GamepadMod] GlfwProvider init skipped: " + t);
        }

        // 2. Linux direct /dev/input/js* provider (backup / zero-dependency for Linux)
        try {
            LinuxJsProvider linuxJs = new LinuxJsProvider();
            composite.add(linuxJs);
        } catch (Throwable t) {
            Log.warn("[GamepadMod] LinuxJsProvider init skipped: " + t);
        }

        // 3. Android provider (for modern and legacy Android)
        try {
            AndroidProvider android = new AndroidProvider();
            composite.add(android);
        } catch (Throwable t) {
            Log.warn("[GamepadMod] AndroidProvider init skipped: " + t);
        }

        state.setProvider(composite);

        input = new GamepadInput(state, config);
        ui = new GamepadUI(state, config);
        renderer = new GamepadRenderer(input);
        keyboard = new GamepadKeyboard();

        // Wrap Vars.ui on mobile to intercept showTextInput and prevent native Android AlertDialog
        if (Vars.ui != null && !(Vars.ui instanceof GamepadUIWrapper)) {
            try {
                Vars.ui = new GamepadUIWrapper(Vars.ui);
                Log.info("[GamepadMod] Vars.ui wrapped with GamepadUIWrapper");
            } catch (Throwable t) {
                Log.warn("[GamepadMod] Failed to wrap Vars.ui: " + t);
            }
        }

        if (Core.input != null) {
            try {
                Core.input.setUseKeyboard(true);
                Core.input.setOnscreenKeyboardVisible(false);
            } catch (Throwable ignored) {}
        }

        Core.app.post(() -> {
            Core.app.addListener(new ApplicationListener() {
                @Override
                public void update() {
                    try {
                        state.poll();
                        ui.update();

                        // L3 + R3 combination (sticks click: buttons 11 and 12) toggles on-screen keyboard
                        // ONLY when text input is active or keyboard is already shown!
                        // In normal gameplay without text input, L3+R3 is used for opening minimap or schematics.
                        if (state.connected && (ui == null || !ui.isCapturing()) && GamepadKeyboard.isTextInputActive()) {
                            boolean l3 = state.rawDown(11);
                            boolean r3 = state.rawDown(12);
                            boolean l3Just = state.justPressed(11);
                            boolean r3Just = state.justPressed(12);
                            if ((l3Just && r3) || (r3Just && l3)) {
                                state.suppressed[11] = true;
                                state.suppressed[12] = true;
                                if (keyboard != null) {
                                    keyboard.toggle();
                                }
                            }
                        }

                        if (keyboard != null) {
                            keyboard.update();
                        }
                        if (ui.captureTick()) {
                            input.handleMenu(Math.min(Core.graphics.getDeltaTime() * 60f, 4f), true);
                            state.savePrevState();
                            return;
                        }
                        input.onFrame();
                    } catch (Throwable t) {
                        Log.err("[GamepadMod] Update error: " + t);
                    }
                }

                @Override
                public void resize(int width, int height) {
                    try {
                        if (input != null && width > 10 && height > 10) {
                            input.cursorX = arc.math.Mathf.clamp(input.cursorX, 0, width - 1);
                            input.cursorY = arc.math.Mathf.clamp(input.cursorY, 0, height - 1);
                        }
                        if (ui != null && ui.isCtrlDialogOpen()) {
                            ui.rebuildCtrlContent();
                        }
                        if (keyboard != null) {
                            keyboard.rebuild();
                        }
                    } catch (Throwable ignored) {}
                }
            });
        });

        try {
            Events.run(Trigger.draw, renderer::drawWorld);
        } catch (Throwable t) {
            Log.warn("[GamepadMod] Could not hook Trigger.draw: " + t);
        }

        try {
            Events.run(Trigger.uiDrawEnd, renderer::drawUI);
        } catch (Throwable t) {
            Log.warn("[GamepadMod] Could not hook Trigger.uiDrawEnd: " + t);
        }

        Log.info("[GamepadMod] Cross-platform Gamepad Support initialized successfully.");
    }
}
