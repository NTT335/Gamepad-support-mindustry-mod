package gamepad;

import arc.func.Cons;
import mindustry.core.UI;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Wraps Vars.ui to intercept showTextInput(...) on Android/mobile.
 * When a gamepad is active, redirects showTextInput to an in-game BaseDialog
 * with 8 controller symbols instead of calling Core.input.getTextInput(...)
 * which triggers Android's native AlertDialog and soft keyboard.
 */
public class GamepadUIWrapper extends UI {
    public final UI orig;

    public GamepadUIWrapper(UI orig) {
        this.orig = orig;
        if (orig != null) {
            Class<?> c = UI.class;
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        f.set(this, f.get(orig));
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        }
    }

    @Override
    public void showTextInput(String title, String text, int maxLength, String def, boolean allowEmpty, boolean numeric, Cons<String> confirmed, Runnable canceled) {
        if (GamepadMod.input != null && GamepadMod.input.active()) {
            arc.Input.TextInput ti = new arc.Input.TextInput();
            ti.title = title;
            ti.text = def != null ? def : "";
            ti.maxLength = maxLength;
            ti.allowEmpty = allowEmpty;
            ti.numeric = numeric;
            ti.accepted = confirmed;
            ti.canceled = canceled;
            GamepadMod.input.showInGameTextInput(ti);
        } else if (orig != null) {
            orig.showTextInput(title, text, maxLength, def, allowEmpty, numeric, confirmed, canceled);
        }
    }

    @Override
    public void showTextInput(String title, String text, int maxLength, String def, boolean allowEmpty, Cons<String> confirmed) {
        showTextInput(title, text, maxLength, def, allowEmpty, false, confirmed, () -> {});
    }

    @Override
    public void showTextInput(String title, String text, int maxLength, String def, Cons<String> confirmed) {
        showTextInput(title, text, maxLength, def, false, false, confirmed, () -> {});
    }

    @Override
    public void showTextInput(String title, String text, String def, Cons<String> confirmed) {
        showTextInput(title, text, 32, def, false, false, confirmed, () -> {});
    }

    @Override
    public void update() {
        if (orig != null) orig.update();
    }

    @Override
    public void resize(int width, int height) {
        if (orig != null) orig.resize(width, height);
    }
}
