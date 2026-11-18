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
        float sw = (Core.scene != null && Core.scene.getWidth() > 10) ? Core.scene.getWidth() : Core.graphics.getWidth();
        float sh = (Core.scene != null && Core.scene.getHeight() > 10) ? Core.scene.getHeight() : Core.graphics.getHeight();
        boolean portrait = sw < sh;
        if (portrait || Vars.mobile) {
            return Math.max(260f, Math.min(sw - 40f, 520f));
        }
        return Math.min(sw - 80f, 650f);
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
        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        Table row = new Table();

        Label keyLbl = new Label(cfg.physName(currentVal), Styles.outlineLabel);
        keyLbl.setColor(isDefault ? Pal.accent : Color.white);
        bindLabels.put(def.key, keyLbl);

        TextButton changeBtn = new TextButton(ru ? "Сменить" : "Change");
        changeBtn.clicked(() -> {
            if (def.key.equals(capKey)) endCapture();
            else beginCapture(def.key);
        });
        bindButtons.put(def.key, changeBtn);

        Runnable resetAction = () -> {
            capKey = null;
            cfg.saveInt("gp-bind-" + def.key, def.def);
            GamepadConfig.cfgFlush();
            cfg.load();
            rebuildCtrlContent();
        };

        if (rowWidth() < 520f) {
            row.add(def.label).left().growX().wrap().padBottom(2).row();
            Table sub = new Table();
            sub.add(keyLbl).growX().left();
            sub.add(changeBtn).size(105, 38).padRight(4);
            sub.button(ru ? "Сброс" : "Reset", resetAction).size(75, 38);
            row.add(sub).growX().row();
        } else {
            row.add(def.label).left().growX().wrap().padRight(12);
            row.add(keyLbl).width(150).right().padRight(12);
            row.add(changeBtn).size(130, 42).padRight(6);
            row.button(ru ? "Сброс" : "Reset", resetAction).size(100, 42);
        }

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
        content.margin(3, 16, 3, 16);
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

        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        Runnable resetSlider = () -> {
            GamepadConfig.cfgRemove(key);
            cfg.load();
            GamepadConfig.cfgFlush();
            rebuildCtrlContent();
        };

        if (rowWidth() < 520f) {
            row.add(st).growX().row();
            row.button(ru ? "По умолч." : "Reset", resetSlider).size(110, 36).padTop(2).right().row();
        } else {
            row.add(st).growX().padRight(8);
            row.button(ru ? "Reset" : "Reset", resetSlider).size(105, 42);
        }

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

        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        Runnable resetCheck = () -> {
            GamepadConfig.cfgRemove(key);
            cfg.load();
            GamepadConfig.cfgFlush();
            rebuildCtrlContent();
        };

        if (rowWidth() < 520f) {
            row.add(cb).left().growX().row();
            row.button(ru ? "Сброс" : "Reset", resetCheck).size(80, 34).padTop(2).right().row();
        } else {
            row.add(cb).left().growX().padRight(8);
            row.button(ru ? "Сброс" : "Reset", resetCheck).size(105, 40);
        }

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

    private String getTabName(int idx) {
        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        switch (idx) {
            case 0: return ru ? "1. Общие" : "1. General";
            case 1: return ru ? "2. В игре" : "2. In-Game";
            case 2: return ru ? "3. Постройка" : "3. Build";
            case 3: return ru ? "4. Отряды" : "4. Command";
            case 4: return ru ? "5. Меню" : "5. Menu";
            case 5: return ru ? "6. Карта" : "6. Minimap";
            default: return GamepadConfig.TAB_NAMES[idx];
        }
    }

    private void addTabButton(Table bar, int idx, float w) {
        String label = (currentTab == idx) ? ("[accent]" + getTabName(idx)) : getTabName(idx);
        TextButton b = bar.button(label, () -> {
            currentTab = idx;
            rebuildCtrlContent();
        }).size(w, 38).pad(2).get();
        b.getLabel().setFontScale(0.9f);
        b.getLabel().setWrap(false);
    }

    public boolean isCtrlDialogOpen() {
        return ctrlDialog != null && ctrlDialog.isShown();
    }

    public void rebuildCtrlContent() {
        if (ctrlContent == null) return;
        capKey = null;
        bindButtons.clear();
        bindLabels.clear();
        ctrlContent.clearChildren();
        ctrlContent.top();

        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        Table statusRow = new Table();
        String devName = pad.getName() != null ? " (" + pad.getName() + ")" : "";
        String connStr = pad.connected ? (ru ? "[green]● Контроллер подключен" : "[green]● Controller connected") + devName 
                                       : (ru ? "[scarlet]● Контроллер не найден" : "[scarlet]● Controller not found");
        statusRow.add(connStr).left().growX().wrap();
        if (rowWidth() < 520f) {
            statusRow.row();
            statusRow.button(ru ? "Сброс вкладки" : "Reset tab", this::resetCurrentCategory).size(150, 36).padTop(4).left();
        } else {
            statusRow.button(ru ? "Сброс вкладки" : "Reset tab", this::resetCurrentCategory).size(170, 38);
        }
        ctrlContent.add(statusRow).width(rowWidth()).left().padTop(6).padBottom(6).row();

        Table tabBar = new Table();
        boolean isPortrait = (Core.scene != null && Core.scene.getWidth() < Core.scene.getHeight());
        int cols = (Vars.mobile || isPortrait || Core.scene.getWidth() < 850f) ? 2 : 3;
        float btnW = (rowWidth() - (cols - 1) * 6f) / cols;
        for (int ti = 0; ti < 6; ti++) {
            addTabButton(tabBar, ti, btnW);
            if ((ti + 1) % cols == 0) tabBar.row();
        }
        ctrlContent.add(tabBar).width(rowWidth()).center().padBottom(8).row();

        if (currentTab == 0) {
            addHeader(ctrlContent, ru ? "Основные настройки" : "Main settings");
            addCheck(ctrlContent, ru ? "Включить поддержку контроллера" : "Enable controller support", "gp-enabled", true);

            addHeader(ctrlContent, ru ? "Чувствительность стиков и камеры" : "Stick and camera sensitivity");
            addSlider(ctrlContent, ru ? "Скорость курсора (стик)" : "Cursor speed (stick)", "gp-cursor", 18, 4, 50, 1, String::valueOf);
            addSlider(ctrlContent, ru ? "Скорость камеры (D-pad)" : "Camera pan speed (D-pad)", "gp-pan", 16, 4, 40, 1, String::valueOf);
            addSlider(ctrlContent, ru ? "Скорость приближения" : "Zoom speed", "gp-zoom", 50, 5, 200, 5, v -> String.format("%.3f", v / 1000f));
            addSlider(ctrlContent, ru ? "Мёртвая зона стика" : "Stick deadzone", "gp-deadzone", 16, 0, 50, 1, v -> v + "%");
            addSlider(ctrlContent, ru ? "Время удержания кнопок" : "Button hold time", "gp-hold", 20, 5, 60, 1, v -> String.format("%.2f s", v / 60f));
            addSlider(ctrlContent, ru ? "Скорость прокрутки меню (прав. стик)" : "Menu scroll speed (right stick)", "gp-scroll", 20, 2, 80, 1, String::valueOf);

            addHeader(ctrlContent, ru ? "Зум и прокрутка" : "Zoom and scroll");
            addCheck(ctrlContent, ru ? "Инвертировать приближение камеры" : "Invert camera zoom", "gp-invzoom", false);
            addCheck(ctrlContent, ru ? "Инвертировать прокрутку меню" : "Invert menu scroll", "gp-scrollinv", false);
        }

        // Stick axis inversion for this category (available in all 6 tabs)
        addHeader(ctrlContent, (ru ? "Инверсия осей стиков (" : "Stick axis inversion (") + getTabName(currentTab) + ")");
        addCheck(ctrlContent, ru ? "Инвертировать левый стик X (OX)" : "Invert Left Stick X (OX)", "gp-inv-lx-" + currentTab, false);
        addCheck(ctrlContent, ru ? "Инвертировать левый стик Y (OY)" : "Invert Left Stick Y (OY)", "gp-inv-ly-" + currentTab, false);
        addCheck(ctrlContent, ru ? "Инвертировать правый стик X (OX)" : "Invert Right Stick X (OX)", "gp-inv-rx-" + currentTab, false);
        addCheck(ctrlContent, ru ? "Инвертировать правый стик Y (OY)" : "Invert Right Stick Y (OY)", "gp-inv-ry-" + currentTab, false);

        String lastSub = "";
        for (GamepadConfig.BindDef d : GamepadConfig.BIND_DEFS) {
            if (d.cat != currentTab) continue;
            String subName = d.sub != null ? d.sub : getTabName(currentTab);
            if (!subName.equals(lastSub)) {
                lastSub = subName;
                addHeader(ctrlContent, subName);
            }
            addBindRow(ctrlContent, d);
        }
    }

    public void buildCtrlDialog() {
        boolean ru = Core.bundle != null && Core.bundle.getLocale() != null && "ru".equals(Core.bundle.getLocale().getLanguage());
        ctrlDialog = new BaseDialog(ru ? "Настройки контроллера" : "Controller settings");
        ctrlDialog.background(Styles.black);
        ctrlDialog.cont.background(Styles.black);
        ctrlDialog.addCloseButton();
        float sw = (Core.scene != null && Core.scene.getWidth() > 10) ? Core.scene.getWidth() : Core.graphics.getWidth();
        float bSize = sw < 500f ? 135f : 170f;
        ctrlDialog.buttons.defaults().size(bSize, 46f).pad(4f);
        ctrlDialog.buttons.button(ru ? "Сбросить всё" : "Reset all", Icon.refresh, this::resetControllerSettings);
        ctrlDialog.hidden(() -> {
            capKey = null;
            GamepadConfig.cfgFlush();
        });

        ctrlContent = new Table();
        ctrlContent.background(Styles.black);
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

    public boolean isCapturing() {
        return capKey != null;
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
        if (dlg == null) return null;
        try {
            Field mf = dlg.getClass().getDeclaredField("menu");
            mf.setAccessible(true);
            Object v = mf.get(dlg);
            if (v instanceof Table tbl) return tbl;
        } catch (Throwable ignored) {}

        String ctrlText = Core.bundle != null ? Core.bundle.get("settings.controls", "") : "";
        String gameText = Core.bundle != null ? Core.bundle.get("settings.game", "") : "";
        Class<?> c = dlg.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(dlg);
                    if (v instanceof Table tbl) {
                        if ((!ctrlText.isEmpty() && menuHasControls(tbl, ctrlText)) || 
                            (!gameText.isEmpty() && menuHasControls(tbl, gameText))) {
                            return tbl;
                        }
                    }
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
        String title = "Controller";
        if (Core.bundle != null && Core.bundle.getLocale() != null) {
            String lang = Core.bundle.getLocale().getLanguage();
            if ("ru".equals(lang) || "uk".equals(lang) || "be".equals(lang)) {
                title = "Контроллер";
            }
        }
        Button b = tmp.button(title, ic, Styles.flatt, isz, this::openCtrlSettings).get();
        b.marginLeft(8);
        b.name = "gamepad-settings-button";
        return b;
    }

    public void ensureCtrlButton() {
        if (Vars.ui == null || Vars.ui.settings == null) return;
        if (!menuSearched || menuObj == null) {
            menuObj = findSettingsMenu(Vars.ui.settings);
            if (menuObj != null) menuSearched = true;
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

        String ctrlText = Core.bundle != null ? Core.bundle.get("settings.controls", "") : "";
        String gameText = Core.bundle != null ? Core.bundle.get("settings.game", "") : "";
        int at = -1;
        for (int j = 0; j < actors.size(); j++) {
            String txt = cellText(actors.get(j));
            if (!ctrlText.isEmpty() && ctrlText.equals(txt)) { at = j; break; }
        }
        if (at < 0) {
            for (int j = 0; j < actors.size(); j++) {
                String txt = cellText(actors.get(j));
                if (!gameText.isEmpty() && gameText.equals(txt)) { at = j; break; }
            }
        }
        if (at < 0) at = Math.min(0, actors.size() - 1);

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
