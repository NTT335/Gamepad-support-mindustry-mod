package gamepad;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.Label;
import arc.scene.ui.TextArea;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.logic.LogicDialog;
import mindustry.ui.Styles;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.MessageBlock;

import java.lang.reflect.Field;

/**
 * On-screen virtual keyboard for text input (logic processors, message blocks,
 * search fields, save dialogs, chat, etc.).
 *
 * Toggled ON/OFF via L3 + R3 combination (left stick click + right stick click).
 * Initially disabled/hidden by default.
 * Compact and sleek with adjustable size levels ([-] and [+]).
 */
public class GamepadKeyboard {
    private Table root;
    private Table container;
    private boolean addedToScene = false;
    private boolean caps = false;
    public boolean userVisible = false;
    private TextField lastTargetField = null;

    // Size levels: 0 = Mini, 1 = Compact (Default), 2 = Medium
    public static int sizeLevel = 1;

    private static final String[] ROW_1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final String[] ROW_2_LOWER = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] ROW_2_UPPER = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
    private static final String[] ROW_3_LOWER = {"a", "s", "d", "f", "g", "h", "j", "k", "l", "⌫"};
    private static final String[] ROW_3_UPPER = {"A", "S", "D", "F", "G", "H", "J", "K", "L", "⌫"};
    private static final String[] ROW_4_LOWER = {"z", "x", "c", "v", "b", "n", "m", ".", ",", "@"};
    private static final String[] ROW_4_UPPER = {"Z", "X", "C", "V", "B", "N", "M", ".", ",", "@"};

    public GamepadKeyboard() {
        build();
    }

    private void build() {
        root = new Table();
        root.setFillParent(true);
        root.touchable = Touchable.childrenOnly;
        root.bottom();
        root.name = "gamepad-virtual-keyboard-root";
        root.visible = false;
    }

    public void toggle() {
        userVisible = !userVisible;
        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        if (userVisible) {
            TextField tf = getTargetField();
            if (tf != null) {
                GamepadInput.prepareTextField(tf);
                try {
                    Core.scene.setKeyboardFocus(tf);
                } catch (Throwable ignored) {}
            }
            rebuild();
            if (root != null) {
                root.visible = true;
                root.toFront();
            }
            try {
                Vars.ui.hudfrag.showToast(ru ? "[accent]Клавиатура: [white]ВКЛ [lightgray](L3+R3)" : "[accent]Keyboard: [white]ON [lightgray](L3+R3)");
            } catch (Throwable ignored) {}
        } else {
            if (root != null) root.visible = false;
            try {
                Vars.ui.hudfrag.showToast(ru ? "[lightgray]Клавиатура: [lightgray]ВЫКЛ" : "[lightgray]Keyboard: [lightgray]OFF");
            } catch (Throwable ignored) {}
        }
    }

    public void hide() {
        userVisible = false;
        if (root != null) root.visible = false;
    }

    public void show() {
        userVisible = true;
        rebuild();
        if (root != null) {
            root.visible = true;
            root.toFront();
        }
    }

    private float getKeyH() {
        switch (sizeLevel) {
            case 0: return 17f; // Mini
            case 1: return 20f; // Compact (Default)
            case 2: return 24f; // Medium
            default: return 20f;
        }
    }

    private float getFontScale() {
        switch (sizeLevel) {
            case 0: return 0.62f;
            case 1: return 0.70f;
            case 2: return 0.80f;
            default: return 0.70f;
        }
    }

    public void rebuild() {
        if (root == null) return;
        root.clearChildren();

        float sw = Core.scene != null ? Core.scene.getWidth() : Core.graphics.getWidth();
        float sh = Core.scene != null ? Core.scene.getHeight() : Core.graphics.getHeight();
        if (sw < 10) sw = 400f;

        boolean portrait = sw < sh || sw < 520f;
        float totalW = portrait ? Math.min(sw - 16f, 420f) : Math.min(sw - 32f, 460f);
        float padH = 2f;
        float innerW = totalW - padH * 2f;

        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());

        container = new Table(Styles.black9);
        container.margin(2f, padH, 2f, padH);
        container.touchable = Touchable.enabled;

        float fontScale = getFontScale();
        float keyH = getKeyH();
        float keyGap = 1.0f;

        // Top bar: title + size controls [-] [+] + close [✕]
        Table topBar = new Table();
        topBar.add(ru ? "[lightgray]Клавиатура [accent](L3+R3)" : "[lightgray]Keyboard [accent](L3+R3)").left().growX().padLeft(3f);

        // Size -
        topBar.button("-", Styles.flatBordert, () -> {
            if (sizeLevel > 0) {
                sizeLevel--;
                rebuild();
            }
        }).size(20f, 18f).padRight(2f);

        // Size +
        topBar.button("+", Styles.flatBordert, () -> {
            if (sizeLevel < 2) {
                sizeLevel++;
                rebuild();
            }
        }).size(20f, 18f).padRight(4f);

        // Close button
        topBar.button("[scarlet]✕", Styles.flatBordert, () -> {
            userVisible = false;
            if (root != null) root.visible = false;
        }).size(22f, 18f).right().padRight(2f);

        container.add(topBar).width(innerW).padBottom(2f).row();

        // 10-key standard width
        int n1 = 10;
        float keyW = (innerW - (n1 - 1) * keyGap) / n1;

        // Row 1: Numbers
        addRow(container, ROW_1, innerW, keyW, keyH, keyGap, fontScale);

        // Row 2: Q-P
        addRow(container, caps ? ROW_2_UPPER : ROW_2_LOWER, innerW, keyW, keyH, keyGap, fontScale);

        // Row 3: A-L + Backspace
        addRow(container, caps ? ROW_3_UPPER : ROW_3_LOWER, innerW, keyW, keyH, keyGap, fontScale);

        // Row 4: Z-M + Symbols
        addRow(container, caps ? ROW_4_UPPER : ROW_4_LOWER, innerW, keyW, keyH, keyGap, fontScale);

        // Row 5: Controls and math symbols
        // Weights: Caps(1.1), _(0.9), -(0.9), +(0.9), =(0.9), Space(2.3), Clear(1.4), Enter(1.6) -> sum = 10.0!
        Table row5 = new Table();
        int gaps5 = 7;
        float unitW = (innerW - gaps5 * keyGap) / 10.0f;

        // Caps
        TextButton capsBtn = new TextButton(caps ? "[accent]A" : "[lightgray]a", Styles.flatBordert);
        capsBtn.getLabel().setFontScale(fontScale);
        capsBtn.margin(0f);
        capsBtn.clicked(() -> {
            caps = !caps;
            rebuild();
        });
        row5.add(capsBtn).size(1.1f * unitW, keyH);
        row5.add().width(keyGap);

        // _
        addSymButton(row5, "_", 0.9f * unitW, keyH, fontScale);
        row5.add().width(keyGap);

        // -
        addSymButton(row5, "-", 0.9f * unitW, keyH, fontScale);
        row5.add().width(keyGap);

        // +
        addSymButton(row5, "+", 0.9f * unitW, keyH, fontScale);
        row5.add().width(keyGap);

        // =
        addSymButton(row5, "=", 0.9f * unitW, keyH, fontScale);
        row5.add().width(keyGap);

        // Space
        TextButton spcBtn = new TextButton(ru ? "Пробел" : "Space", Styles.flatBordert);
        spcBtn.getLabel().setFontScale(fontScale);
        spcBtn.margin(0f);
        spcBtn.clicked(() -> onKeyPressed(" "));
        row5.add(spcBtn).size(2.3f * unitW, keyH);
        row5.add().width(keyGap);

        // Clear (Сброс)
        TextButton clrBtn = new TextButton(ru ? "Сброс" : "Clear", Styles.flatBordert);
        clrBtn.getLabel().setFontScale(fontScale);
        clrBtn.margin(0f);
        clrBtn.clicked(this::onClear);
        row5.add(clrBtn).size(1.4f * unitW, keyH);
        row5.add().width(keyGap);

        // Enter
        TextButton entBtn = new TextButton(ru ? "[green]Ввод ⏎" : "[green]Enter ⏎", Styles.flatBordert);
        entBtn.getLabel().setFontScale(fontScale * 0.95f);
        entBtn.margin(0f);
        entBtn.clicked(this::onEnter);
        row5.add(entBtn).size(1.6f * unitW, keyH);

        container.add(row5).width(innerW).padBottom(2f).row();

        root.add(container).width(totalW).padBottom(2f);
    }

    private void addRow(Table parent, String[] keys, float innerW, float keyW, float keyH, float keyGap, float fontScale) {
        Table r = new Table();
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            TextButton b;
            if ("⌫".equals(k)) {
                b = new TextButton("[scarlet]⌫", Styles.flatBordert);
                b.clicked(this::onBackspace);
            } else {
                b = new TextButton(k, Styles.flatBordert);
                b.clicked(() -> onKeyPressed(k));
            }
            b.getLabel().setFontScale(fontScale);
            b.margin(0f);
            r.add(b).size(keyW, keyH);
            if (i < keys.length - 1) {
                r.add().width(keyGap);
            }
        }
        parent.add(r).width(innerW).padBottom(1.5f).row();
    }

    private void addSymButton(Table t, String sym, float w, float h, float fontScale) {
        TextButton b = new TextButton(sym, Styles.flatBordert);
        b.getLabel().setFontScale(fontScale);
        b.margin(0f);
        b.clicked(() -> onKeyPressed(sym));
        t.add(b).size(w, h);
    }

    private void onKeyPressed(String text) {
        TextField tf = getTargetField();
        if (tf == null) return;
        GamepadInput.prepareTextField(tf);
        int pos = tf.getCursorPosition();
        String cur = tf.getText();
        if (cur == null) cur = "";
        pos = Mathf.clamp(pos, 0, cur.length());
        String next = cur.substring(0, pos) + text + cur.substring(pos);
        tf.setText(next);
        tf.setCursorPosition(pos + text.length());
        tf.change();
        try {
            Core.scene.setKeyboardFocus(tf);
        } catch (Throwable ignored) {}
    }

    private void onBackspace() {
        TextField tf = getTargetField();
        if (tf == null) return;
        GamepadInput.prepareTextField(tf);
        int pos = tf.getCursorPosition();
        String cur = tf.getText();
        if (cur == null || cur.isEmpty() || pos <= 0) return;
        pos = Mathf.clamp(pos, 1, cur.length());
        String next = cur.substring(0, pos - 1) + cur.substring(pos);
        tf.setText(next);
        tf.setCursorPosition(pos - 1);
        tf.change();
        try {
            Core.scene.setKeyboardFocus(tf);
        } catch (Throwable ignored) {}
    }

    private void onClear() {
        TextField tf = getTargetField();
        if (tf == null) return;
        GamepadInput.prepareTextField(tf);
        tf.setText("");
        tf.setCursorPosition(0);
        tf.change();
        try {
            Core.scene.setKeyboardFocus(tf);
        } catch (Throwable ignored) {}
    }

    private void onEnter() {
        TextField tf = getTargetField();
        if (tf == null) return;
        GamepadInput.prepareTextField(tf);
        if (tf instanceof TextArea) {
            onKeyPressed("\n");
        } else {
            tf.change();
            Core.scene.keyDown(arc.input.KeyCode.enter);
            Core.scene.keyUp(arc.input.KeyCode.enter);
            // Hide on Enter for single-line inputs
            userVisible = false;
            if (root != null) root.visible = false;
        }
    }

    public TextField getTargetField() {
        if (Core.scene == null) return null;
        Element el = Core.scene.getKeyboardFocus();
        if (el instanceof TextField tf && !(el instanceof TextButton) && !isInsideVirtualKeyboard(tf)) {
            lastTargetField = tf;
            return tf;
        }
        if (lastTargetField != null && lastTargetField.getScene() != null && lastTargetField.visible && lastTargetField.touchable != Touchable.disabled) {
            return lastTargetField;
        }
        if (Core.scene.hasDialog()) {
            Dialog d = Core.scene.getDialog();
            if (d != null) {
                TextField tf = findChild(d, TextField.class);
                if (tf != null && !isInsideVirtualKeyboard(tf)) {
                    lastTargetField = tf;
                    return tf;
                }
            }
        }
        TextField inScene = findVisibleTextField(Core.scene.root);
        if (inScene != null) {
            lastTargetField = inScene;
            return inScene;
        }
        return null;
    }

    private static boolean isInsideVirtualKeyboard(Element el) {
        while (el != null) {
            if ("gamepad-virtual-keyboard-root".equals(el.name)) return true;
            el = el.parent;
        }
        return false;
    }

    private static TextField findVisibleTextField(Group group) {
        if (group == null) return null;
        Seq<Element> children = group.getChildren();
        for (int i = 0; i < children.size; i++) {
            Element c = children.get(i);
            if (c instanceof TextField tf && !(c instanceof TextButton) && tf.visible && tf.touchable != Touchable.disabled) {
                if (!isInsideVirtualKeyboard(tf)) {
                    return tf;
                }
            }
            if (c instanceof Group g && g.visible) {
                TextField res = findVisibleTextField(g);
                if (res != null) return res;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Element> T findChild(Group group, Class<T> type) {
        if (group == null) return null;
        Seq<Element> children = group.getChildren();
        for (int i = 0; i < children.size; i++) {
            Element c = children.get(i);
            if (type.isInstance(c) && !isInsideVirtualKeyboard(c)) return (T) c;
            if (c instanceof Group g) {
                T res = findChild(g, type);
                if (res != null) return res;
            }
        }
        return null;
    }

    public static boolean isTextInputActive() {
        if (Core.scene == null) return false;

        // If keyboard is currently open, L3+R3 can close it
        if (GamepadMod.keyboard != null && GamepadMod.keyboard.userVisible) {
            return true;
        }

        // Focused element is a TextField
        Element focus = Core.scene.getKeyboardFocus();
        if (focus instanceof TextField && !(focus instanceof TextButton) && !isInsideVirtualKeyboard(focus)) {
            return true;
        }

        // Logic processor UI
        if (Vars.ui != null && Vars.ui.logic != null && Vars.ui.logic.isShown()) {
            return true;
        }

        // In-game chat
        if (Vars.ui != null && Vars.ui.chatfrag != null && Vars.ui.chatfrag.shown()) {
            return true;
        }

        // Active dialog that has a TextField
        if (Core.scene.hasDialog()) {
            Dialog d = Core.scene.getDialog();
            if (d != null) {
                TextField tf = findChild(d, TextField.class);
                if (tf != null && !isInsideVirtualKeyboard(tf)) {
                    return true;
                }
            }
        }

        // Logic block or message block selected
        if (Vars.control != null && Vars.control.input != null && Vars.control.input.config != null) {
            mindustry.gen.Building b = Vars.control.input.config.getSelected();
            if (b != null && (b.block instanceof LogicBlock || b.block instanceof MessageBlock)) {
                return true;
            }
        }

        // GamepadInput search mode
        if (GamepadMod.input != null && GamepadMod.input.searchMode) {
            return true;
        }

        return false;
    }

    public static boolean isProcessorOrTextBlockActive() {
        if (Vars.ui != null && Vars.ui.logic != null && Vars.ui.logic.isShown()) {
            return true;
        }
        if (Core.scene != null && Core.scene.hasDialog()) {
            Dialog d = Core.scene.getDialog();
            if (d != null && (d instanceof LogicDialog || d.getClass().getName().contains("logic") || d.getClass().getName().contains("Logic"))) {
                return true;
            }
        }
        if (Vars.control != null && Vars.control.input != null && Vars.control.input.config != null) {
            mindustry.gen.Building b = Vars.control.input.config.getSelected();
            if (b != null && (b.block instanceof LogicBlock || b.block instanceof MessageBlock)) {
                return true;
            }
        }
        return false;
    }

    public void update() {
        if (Core.scene == null) return;

        if (!addedToScene) {
            Core.scene.add(root);
            addedToScene = true;
        }

        if (!userVisible) {
            if (root.visible) root.visible = false;
            return;
        }

        // If user enabled it, check if we still have a valid context for input
        TextField tf = getTargetField();
        boolean canInput = (tf != null) || Core.scene.hasDialog() || isProcessorOrTextBlockActive();
        if (!canInput) {
            userVisible = false;
            root.visible = false;
            return;
        }

        if (!root.visible) {
            root.visible = true;
            rebuild();
        }
        root.toFront();

        if (tf != null) {
            GamepadInput.prepareTextField(tf);
        }
    }
}
