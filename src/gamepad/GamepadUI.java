package gamepad;

import arc.Core;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * In-game UI for Gamepad Settings and binding capture.
 * Adds a "Controller" button inside Mindustry's standard Settings -> Controls menu.
 */
public class GamepadUI {
    private final GamepadState pad;
    private final GamepadConfig cfg;

    private BaseDialog ctrlDialog;
    private Table ctrlContent;
    private Button ctrlBtn;
    private Table menuObj;
    private boolean menuSearched = false;
    private int currentTab = 0;

    private final Map<String, TextButton> bindButtons = new HashMap<>();
    private final Map<String, Label> bindLabels = new HashMap<>();

    private String capKey = null;
    private long capStart = 0;
    private long capEnd = 0;
    private boolean capWaitRelease = true;
    private int capShownSec = -1;
    private static final long CAP_TIMEOUT_MS = 8000;

    public GamepadUI(GamepadState pad, GamepadConfig cfg) {
        this.pad = pad;
        this.cfg = cfg;
    }

    private float rowWidth() {
        return Math.min(Core.graphics.getWidth() / 1.12f, 760f);
    }

    private void addHeader(Table t, String text) {
        t.add(text).color(Pal.accent).padTop(16).padBottom(4).left().row();
        t.image().color(Pal.accent).height(3).growX().padBottom(6).row();
    }

    private void addDivider(Table t) {
        t.image().color(Pal.darkerGray).height(1).growX().padTop(3).padBottom(3).row();
    }

    private void addBindRow(Table t, GamepadConfig.BindDef def) {
        int currentVal = cfg.bind(def.key);
        boolean isDefault = (currentVal == def.def);
        Table row = new Table();
        row.add(def.label).left().growX().wrap().padRight(12);

        Label keyLbl = new Label(cfg.physName(currentVal), Styles.outlineLabel);
        keyLbl.setColor(isDefault ? Pal.accent : Color.white);
        row.add(keyLbl).width(150).right().padRight(12);
        bindLabels.put(def.key, keyLbl);

        TextButton changeBtn = row.button("Change", () -> {
            if (def.key.equals(capKey)) endCapture();
            else beginCapture(def.key);
        }).size(140, 42).padRight(6).get();
        bindButtons.put(def.key, changeBtn);

        row.button("Reset", () -> {
            capKey = null;
            cfg.saveInt("gp-bind-" + def.key, def.def);
            GamepadConfig.cfgFlush();
            cfg.load();
            rebuildCtrlContent();
        }).size(115, 42);

        t.add(row).width(rowWidth()).left().padTop(2).row();
        addDivider(t);
    }

    public interface ValueFormatter {
        String format(int val);
    }

    private void addSlider(Table t, String title, String key, int def, int min, int max, int step, ValueFormatter fmt) {
        Table row = new Table();
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(GamepadConfig.cfgInt(key, def));

        Label valueLbl = new Label("", Styles.outlineLabel);
        Table content = new Table();
        content.add(title, Styles.outlineLabel).left().growX().wrap();
        content.add(valueLbl).color(Pal.accent).padLeft(10).right();
        content.margin(3, 24, 3, 24);
        content.touchable = Touchable.disabled;

        valueLbl.setText(fmt.format(Math.round(slider.getValue())));
        slider.changed(() -> {
            int v = Math.round(slider.getValue());
            GamepadConfig.saveInt(key, v);
            valueLbl.setText(fmt.format(v));
            cfg.load();
        });

        Stack st = new Stack();
        st.add(slider);
        st.add(content);

        row.add(st).growX().padRight(8);
        row.button("Reset", () -> {
            GamepadConfig.cfgRemove(key);
            cfg.load();
            GamepadConfig.cfgFlush();
            rebuildCtrlContent();
        }).size(115, 42);

        t.add(row).width(rowWidth()).left().padTop(4).row();
        addDivider(t);
    }

    private void addCheck(Table t, String title, String key, boolean def) {
        Table row = new Table();
        CheckBox cb = new CheckBox(title);
        cb.setChecked(GamepadConfig.cfgBool(key, def));
        cb.changed(() -> {
            GamepadConfig.saveBool(key, cb.isChecked());
            cfg.load();
        });

        row.add(cb).left().growX().padRight(8);
        row.button("Reset", () -> {
            GamepadConfig.cfgRemove(key);
            cfg.load();
            GamepadConfig.cfgFlush();
            rebuildCtrlContent();
        }).size(115, 42);

        t.add(row).width(rowWidth()).left().padTop(4).row();
        addDivider(t);
    }

    private void resetCurrentCategory() {
        capKey = null;
        cfg.resetTab(currentTab);
        rebuildCtrlContent();
        toast("Tab \"" + GamepadConfig.TAB_NAMES[currentTab] + "\" reset");
    }

    private void resetControllerSettings() {
        capKey = null;
        cfg.resetAll();
        rebuildCtrlContent();
        toast("All controller settings reset");
    }

    private void toast(String msg) {
        try {
            Vars.ui.hudfrag.showToast("[accent]" + msg);
        } catch (Exception ignored) {}
    }

    private void addTabButton(Table bar, int idx, float w) {
        String label = (currentTab == idx) ? ("[accent]" + GamepadConfig.TAB_NAMES[idx]) : GamepadConfig.TAB_NAMES[idx];
        bar.button(label, () -> {
            currentTab = idx;
            rebuildCtrlContent();
        }).size(w, 44).pad(3);
    }

    public void rebuildCtrlContent() {
        if (ctrlContent == null) return;
        capKey = null;
        bindButtons.clear();
        bindLabels.clear();
        ctrlContent.clearChildren();
        ctrlContent.top();

        Table statusRow = new Table();
        String devName = pad.getName() != null ? " (" + pad.getName() + ")" : "";
        statusRow.add(pad.connected ? "[green]● Controller connected" + devName : "[scarlet]● Controller not found").left().growX();
        statusRow.button("Reset tab", this::resetCurrentCategory).size(190, 38);
        ctrlContent.add(statusRow).width(rowWidth()).left().padTop(6).padBottom(6).row();

        Table tabBar = new Table();
        float btnW = (rowWidth() - 18f) / 3f;
        for (int ti = 0; ti < 6; ti++) {
            addTabButton(tabBar, ti, btnW);
            if (ti == 2 || ti == 5) tabBar.row();
        }
        ctrlContent.add(tabBar).width(rowWidth()).left().padBottom(8).row();

        if (currentTab == 0) {
            addHeader(ctrlContent, "Main settings");
            addCheck(ctrlContent, "Enable controller support", "gp-enabled", true);

            addHeader(ctrlContent, "Stick and camera sensitivity");
            addSlider(ctrlContent, "Cursor speed (stick)", "gp-cursor", 18, 4, 50, 1, String::valueOf);
            addSlider(ctrlContent, "Camera pan speed (D-pad)", "gp-pan", 16, 4, 40, 1, String::valueOf);
            addSlider(ctrlContent, "Zoom speed", "gp-zoom", 50, 5, 200, 5, v -> String.format("%.3f", v / 1000f));
            addSlider(ctrlContent, "Stick deadzone", "gp-deadzone", 16, 0, 50, 1, v -> v + "%");
            addSlider(ctrlContent, "Button hold time", "gp-hold", 20, 5, 60, 1, v -> String.format("%.2f s", v / 60f));
            addSlider(ctrlContent, "Menu scroll speed (right stick)", "gp-scroll", 20, 2, 80, 1, String::valueOf);

            addHeader(ctrlContent, "Zoom and scroll");
            addCheck(ctrlContent, "Invert camera zoom", "gp-invzoom", false);
            addCheck(ctrlContent, "Invert menu scroll", "gp-scrollinv", false);
        }

        // Stick axis inversion for this category (available in all 6 tabs)
        addHeader(ctrlContent, "Stick axis inversion (" + GamepadConfig.TAB_NAMES[currentTab] + ")");
        addCheck(ctrlContent, "Invert Left Stick X (OX)", "gp-inv-lx-" + currentTab, false);
        addCheck(ctrlContent, "Invert Left Stick Y (OY)", "gp-inv-ly-" + currentTab, false);
        addCheck(ctrlContent, "Invert Right Stick X (OX)", "gp-inv-rx-" + currentTab, false);
        addCheck(ctrlContent, "Invert Right Stick Y (OY)", "gp-inv-ry-" + currentTab, false);

        String lastSub = "";
        for (GamepadConfig.BindDef d : GamepadConfig.BIND_DEFS) {
            if (d.cat != currentTab) continue;
            String subName = d.sub != null ? d.sub : GamepadConfig.TAB_NAMES[currentTab];
            if (!subName.equals(lastSub)) {
                lastSub = subName;
                addHeader(ctrlContent, subName);
            }
            addBindRow(ctrlContent, d);
        }
    }

    public void buildCtrlDialog() {
        ctrlDialog = new BaseDialog("Controller settings");
        ctrlDialog.addCloseButton();
        ctrlDialog.buttons.button("Reset all", Icon.refresh, this::resetControllerSettings).size(240, 64);
        ctrlDialog.hidden(() -> {
            capKey = null;
            GamepadConfig.cfgFlush();
        });

        ctrlContent = new Table();
        ScrollPane pane = new ScrollPane(ctrlContent);
        pane.setScrollingDisabled(true, false);
        pane.setFlickScroll(false);
        pane.setFadeScrollBars(false);
        ctrlDialog.cont.add(pane).grow();
    }

    public void openCtrlSettings() {
        try {
            if (ctrlDialog == null) buildCtrlDialog();
            rebuildCtrlContent();
            ctrlDialog.show();
        } catch (Exception e) {
            Log.err("[GamepadUI] openControllerSettings error: " + e);
        }
    }

    public void beginCapture(String key) {
        capKey = key;
        capStart = System.currentTimeMillis();
        capEnd = capStart + CAP_TIMEOUT_MS;
        capWaitRelease = true;
        capShownSec = -1;
        refreshBindButtons();
    }

    public void endCapture() {
        capKey = null;
        refreshBindButtons();
    }

    public void bindButton(String key, int idx) {
        cfg.saveInt("gp-bind-" + key, idx);
        GamepadConfig.cfgFlush();
        cfg.load();
        rebuildCtrlContent();
    }

    private void refreshBindButtons() {
        long now = System.currentTimeMillis();
        for (GamepadConfig.BindDef d : GamepadConfig.BIND_DEFS) {
            String k = d.key;
            TextButton btn = bindButtons.get(k);
            Label lbl = bindLabels.get(k);
            if (btn == null) continue;
            try {
                if (k.equals(capKey)) {
                    int rem = (int) Math.max(0, Math.ceil((capEnd - now) / 1000f));
                    btn.setText("[accent]Press... " + rem);
                    if (lbl != null) lbl.setText("...");
                } else {
                    btn.setText("Change");
                    if (lbl != null) lbl.setText(cfg.physName(cfg.bind(k)));
                }
            } catch (Exception ignored) {}
        }
    }

    public boolean captureTick() {
        if (capKey == null) return false;
        long now = System.currentTimeMillis();
        if (ctrlDialog == null || (now - capStart > 500 && !ctrlDialog.isShown())) {
            capKey = null;
            return false;
        }
        if (now >= capEnd) {
            endCapture();
            return false;
        }
        int sec = (int) Math.ceil((capEnd - now) / 1000f);
        if (sec != capShownSec) {
            capShownSec = sec;
            refreshBindButtons();
        }

        if (capWaitRelease) {
            boolean any = false;
            for (int i = 0; i < 20; i++) {
                if (pad.rawDown(i)) { any = true; break; }
            }
            if (!any) capWaitRelease = false;
            return true;
        }

        for (int b = 0; b < 20; b++) {
            if (pad.rawDown(b)) {
                String key = capKey;
                capKey = null;
                bindButton(key, b);
                pad.suppressAllHeld();
                return true;
            }
        }
        return true;
    }

    // === Menu integration ===
    private String cellText(Element a) {
        try {
            if (a instanceof Label l) return String.valueOf(l.getText());
            if (a instanceof TextButton b) return String.valueOf(b.getText());
        } catch (Exception ignored) {}
        return null;
    }

    private boolean menuHasControls(Table tbl, String ctrlText) {
        try {
            for (Cell<?> c : tbl.getCells()) {
                Element a = c.get();
                if (a != null && ctrlText.equals(cellText(a))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Table findSettingsMenu(Object dlg) {
        String ctrlText = Core.bundle.get("settings.controls");
        Class<?> c = dlg.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(dlg);
                    if (v instanceof Table tbl && menuHasControls(tbl, ctrlText)) return tbl;
                } catch (Exception ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private Button createCtrlButton() {
        Table tmp = new Table();
        var ic = Icon.wrench != null ? Icon.wrench : Icon.settings;
        float isz = Vars.iconMed;
        Button b = tmp.button("Controller", ic, Styles.flatt, isz, this::openCtrlSettings).get();
        b.marginLeft(8);
        b.name = "gamepad-settings-button";
        return b;
    }

    public void ensureCtrlButton() {
        if (Vars.ui == null || Vars.ui.settings == null) return;
        if (!menuSearched) {
            menuSearched = true;
            menuObj = findSettingsMenu(Vars.ui.settings);
            if (menuObj == null) {
                Log.warn("[GamepadUI] Settings menu table not found; controller settings button not added to main settings");
            }
        }
        Table menu = menuObj;
        if (menu == null) return;
        if (ctrlBtn == null) ctrlBtn = createCtrlButton();
        if (ctrlBtn.parent == menu) return;

        java.util.List<Element> actors = new java.util.ArrayList<>();
        for (Cell<?> c : menu.getCells()) {
            Element a = c.get();
            if (a != null && a != ctrlBtn) actors.add(a);
        }

        String ctrlText = Core.bundle.get("settings.controls");
        int at = -1;
        for (int j = 0; j < actors.size(); j++) {
            if (ctrlText.equals(cellText(actors.get(j)))) { at = j; break; }
        }
        if (at < 0) at = Math.min(4, actors.size() - 1);

        menu.clearChildren();
        menu.defaults().size(300, 60);
        try { ctrlBtn.remove(); } catch (Exception ignored) {}
        for (int k = 0; k < actors.size(); k++) {
            menu.add(actors.get(k)).row();
            if (k == at) menu.add(ctrlBtn).row();
        }
        if (actors.isEmpty()) menu.add(ctrlBtn).row();
    }

    public void update() {
        ensureCtrlButton();
    }
}
