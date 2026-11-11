package gamepad;

import arc.*;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock;

import java.lang.reflect.*;
import java.util.*;

/**
 * Main gamepad logic: handles all 3 gameplay modes (normal, build, command),
 * menu navigation, minimap control, cursor movement, and all gamepad actions.
 */
public class GamepadInput {
    // Mode constants
    public static final int MODE_NORMAL = 0, MODE_BUILD = 1, MODE_COMMAND = 2;
    private static final int TS = 8;
    private static final int MAX_LINE = 100;

    // References
    private final GamepadState pad;
    private final GamepadConfig cfg;

    // Mode state
    public int currentMode = MODE_NORMAL;
    public String buildSubMode = "category";
    public int selectedCatIndex = 0, selectedBlockIndex = 0;

    // Camera detachment and free panning
    public boolean cameraDetached = false;
    public final Vec2 camPos = new Vec2();
    private float panHeldTime = 0f;

    // Cursor
    public float cursorX = -1, cursorY = -1;
    public boolean cursorReady = false;

    // Frame state
    private boolean wasInUI = true;
    private float dpadCooldown = 0;
    private float menuDpadCd = 0;
    public int cmdRow = 0, cmdCol = 0;

    // Normal mode hold
    private boolean nArmed = false;
    private float nHold = 0;
    private boolean nDropped = false;
    private int nHoldBtn = -1;

    // Build mode: placing
    public boolean placing = false;
    public int placeStartX, placeStartY;
    public List<LinePoint> linePoints = null;

    // Build mode: removing
    public boolean removing = false;
    public int breakStartX, breakStartY, breakEndX, breakEndY;

    // Build mode: rebuild & repair derelicts (empty cursor)
    public boolean rebuilding = false;
    public int rebuildStartX, rebuildStartY, rebuildEndX, rebuildEndY;
    private Method tryRepairDerelictMethod = null;

    // Build mode: copy
    private float xHoldFrames = 0;
    private boolean copyArmed = false;
    public boolean copying = false;
    private int copyBtn = -1;
    public int copyStartX, copyStartY, copyEndX, copyEndY;

    // Clipboard
    public List<ClipEntry> clipboard = null;
    public List<PasteEntry> pasteView = null;
    private int pasteLastTX = -9999, pasteLastTY = -9999;

    // Mining
    private Tile miningTile = null;
    private Method canMineMethod = null;
    private Method tryBeginMineMethod = null;

    // Command mode: selection
    public boolean cmdSelecting = false;
    public float cmdStartX, cmdStartY;

    // UI mouse
    private boolean uiMouseDown = false;

    // Reflection fields for mouse position
    private Field mouseXField, mouseYField;
    private boolean mouseFieldsInit = false;

    // Reflection fields for minimap
    private Field mapPanXField, mapPanYField, mapZoomField;
    private boolean mapFieldsInit = false;

    // Reflection for category sync
    private Field catField = null;
    private Field elemVisField = null;

    // Error logging dedup
    private final Set<String> errLogged = new HashSet<>();

    // Symbol constants for gamepad text input (render properly in Mindustry font)
    public static final String SYM_X = "X";
    public static final String SYM_CIRCLE = "○";
    public static final String SYM_TRIANGLE = "△";
    public static final String SYM_SQUARE = "□";
    public static final String SYM_LEFT = "" + Iconc.left;
    public static final String SYM_RIGHT = "" + Iconc.right;
    public static final String SYM_UP = "" + Iconc.up;
    public static final String SYM_DOWN = "" + Iconc.down;

    // System schematics sync
    private Schematic lastSyncedSchematic = null;
    private float textDpadCdX = 0f;
    private float textDpadCdY = 0f;

    // Search mode in menus/dialogs (Triangle to enable, Share/Screenshot to disable)
    public boolean searchMode = false;

    public GamepadInput(GamepadState pad, GamepadConfig cfg) {
        this.pad = pad;
        this.cfg = cfg;
        try {
            tryRepairDerelictMethod = InputHandler.class.getDeclaredMethod("tryRepairDerelict", Tile.class);
            tryRepairDerelictMethod.setAccessible(true);
        } catch (Throwable ignored) {}
    }

    // === Utility ===
    private void logOnce(String tag, Object e) {
        if (errLogged.contains(tag)) return;
        errLogged.add(tag);
        Log.err("[GamepadInput] " + tag + ": " + e);
    }

    private void toast(String msg) {
        try { Vars.ui.hudfrag.showToast(msg); } catch (Exception ignored) {}
    }

    private int buildRot(Building b) {
        try {
            if (!b.block.rotate) return 0;
            return ((Math.round(b.rotation) % 4) + 4) % 4;
        } catch (Exception e) { return 0; }
    }

    private boolean isCoreBuild(Building b) {
        return b != null && b.block instanceof CoreBlock;
    }

    public boolean active() {
        return pad.connected && cfg.enabled;
    }

    // === Mouse reflection ===
    private void initMouseFields() {
        if (mouseFieldsInit) return;
        mouseFieldsInit = true;
        try {
            mouseXField = findField(Core.input.getClass(), "mousex");
            mouseYField = findField(Core.input.getClass(), "mousey");
            if (mouseXField == null || mouseYField == null) {
                Log.warn("[GamepadInput] mouseX/mouseY fields not found in " + Core.input.getClass());
            }
        } catch (Exception e) {
            Log.warn("[GamepadInput] Mouse reflection failed: " + e);
        }
    }

    private Field findField(Class<?> cls, String wanted) {
        Class<?> c = cls;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                String nm = f.getName().toLowerCase();
                String tp = f.getType().getName();
                if (nm.equals(wanted) && (tp.equals("int") || tp.equals("float"))) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void setField(Field f, float v) {
        if (f == null) return;
        try {
            if (f.getType().getName().equals("int")) f.setInt(Core.input, Math.round(v));
            else f.setFloat(Core.input, v);
        } catch (Exception e) { logOnce("setField", e); }
    }

    private float sceneY() {
        float y = Math.round(cursorY);
        return cfg.sceneFlipY ? (Core.graphics.getHeight() - 1 - y) : y;
    }

    private void syncMousePosition() {
        initMouseFields();
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        if (!cursorReady) { cursorX = w / 2f; cursorY = h / 2f; cursorReady = true; }
        cursorX = Mathf.clamp(cursorX, 0, w - 1);
        cursorY = Mathf.clamp(cursorY, 0, h - 1);
        setField(mouseXField, cursorX);
        setField(mouseYField, cursorY);
        Core.scene.mouseMoved(Math.round(cursorX), (int) sceneY());
    }

    private void moveCursor(float sx, float sy, float dt) {
        cursorX += sx * Math.abs(sx) * cfg.cursorSpeed * dt;
        cursorY += sy * Math.abs(sy) * cfg.cursorSpeed * dt;
    }

    // === Stick getters with per-category axis inversion ===
    public float lx(int cat) { return cfg.getLX(pad.applyDeadzone(pad.axes[0], cfg.deadzone), cat); }
    public float ly(int cat) { return cfg.getLY(pad.applyDeadzone(pad.axes[1], cfg.deadzone), cat); }
    public float rx(int cat) { return cfg.getRX(pad.applyDeadzone(pad.axes[3], cfg.deadzone), cat); }
    public float ry(int cat) { return cfg.getRY(pad.applyDeadzone(pad.axes[4], cfg.deadzone), cat); }

    public int activeCategory() {
        if (Vars.state.isMenu() || Core.scene.hasDialog()) return 4;
        if (Vars.ui.minimapfrag != null && Vars.ui.minimapfrag.shown()) return 5;
        if (currentMode == MODE_NORMAL) return 1;
        if (currentMode == MODE_BUILD) return 2;
        if (currentMode == MODE_COMMAND) return 3;
        return 0;
    }

    private float lx() { return lx(activeCategory()); }
    private float ly() { return ly(activeCategory()); }
    private float rx() { return rx(activeCategory()); }
    private float ry() { return ry(activeCategory()); }
    private int dpadX() { return Math.round(pad.axes[6]); }
    private int dpadY() { return Math.round(pad.axes[7]); }

    // === Binding shortcut ===
    private int b(String key) { return cfg.bind(key); }
    private boolean jp(int btn) { return pad.justPressed(btn); }
    private boolean jr(int btn) { return pad.justReleased(btn); }
    private boolean dn(int btn) { return pad.isDown(btn); }

    // === UI interaction ===
    private void updateMenuMouseClick(boolean clickHeld) {
        int sx = Math.round(cursorX);
        int sy = (int) sceneY();
        if (clickHeld && !uiMouseDown) {
            uiMouseDown = true;
            Core.scene.touchDown(sx, sy, 0, KeyCode.mouseLeft);
        } else if (clickHeld && uiMouseDown) {
            Core.scene.touchDragged(sx, sy, 0);
        } else if (!clickHeld && uiMouseDown) {
            uiMouseDown = false;
            Core.scene.touchUp(sx, sy, 0, KeyCode.mouseLeft);
        }
    }

    private void clickSceneAtCursor() {
        int sx = Math.round(cursorX);
        int sy = (int) sceneY();
        Core.scene.touchDown(sx, sy, 0, KeyCode.mouseLeft);
        Core.scene.touchUp(sx, sy, 0, KeyCode.mouseLeft);
    }

    private Element elementUnderCursor() {
        try {
            Vec2 v = Core.scene.screenToStageCoordinates(new Vec2(Math.round(cursorX), sceneY()));
            return Core.scene.hit(v.x, v.y, true);
        } catch (Exception e) { return null; }
    }

    private void triggerEscape() {
        if (Core.scene.hasDialog()) {
            Dialog d = Core.scene.getDialog();
            if (d != null) d.hide();
            return;
        }
        if (Vars.state.isGame()) Vars.ui.paused.show();
    }

    private void togglePause() {
        if (Vars.net.active()) return;
        if (Vars.state.isPaused()) Vars.state.set(GameState.State.playing);
        else if (Vars.state.isPlaying()) Vars.state.set(GameState.State.paused);
    }

    // === Menu handling ===
    public void handleMenu(float dt, boolean capturing) {
        placing = false; removing = false; linePoints = null; rebuilding = false;
        copyArmed = false; copying = false;
        nArmed = false; nDropped = false;
        rebuilding = false;

        moveCursor(lx(4), -ly(4), dt);
        syncMousePosition();

        float rsx = rx(4), rsy = ry(4);
        if (rsx != 0 || rsy != 0) {
            try { scrollMenu(rsx, rsy, dt); } catch (Exception e) { logOnce("menu scroll", e); }
        }

        if (capturing) {
            if (uiMouseDown) updateMenuMouseClick(false);
            return;
        }

        // Triangle (Button 2) turns search mode ON
        if (jp(2) && !searchMode) {
            TextField tf = getActiveTextField();
            if (tf != null) {
                searchMode = true;
                Core.scene.setKeyboardFocus(tf);
                toast("[accent]Search ON [lightgray](Share to exit)");
                return;
            }
        }

        // Share / Screenshot (Button 8) turns search mode OFF (or triggers escape if not in search mode)
        if (jp(8)) {
            if (searchMode) {
                searchMode = false;
                Core.scene.setKeyboardFocus(null);
                toast("[lightgray]Search OFF");
                return;
            } else {
                triggerEscape();
                return;
            }
        }

        if (searchMode) {
            TextField activeField = getActiveTextField();
            if (activeField == null) {
                searchMode = false;
            } else {
                handleTextFieldGamepadInput(activeField, dt);
                boolean hoverButton = isButtonElement(elementUnderCursor());
                if (hoverButton) {
                    updateMenuMouseClick(dn(b("MNU_CLICK_1")) || dn(b("MNU_CLICK_2")));
                } else {
                    updateMenuMouseClick(dn(b("MNU_CLICK_2")));
                }
                return;
            }
        }

        // --- Normal menu navigation (when searchMode is false) ---
        if (menuDpadCd > 0) menuDpadCd -= dt;
        int ddx = dpadX();
        if (ddx == 0) menuDpadCd = 0;
        else if (menuDpadCd <= 0) {
            try { if (adjustSliderUnderCursor(ddx)) menuDpadCd = 6; } catch (Exception e) { logOnce("slider dpad", e); }
        }

        // Standard clicking with Button 0 (Cross) and Button 7 (R2)
        updateMenuMouseClick(dn(b("MNU_CLICK_1")) || dn(b("MNU_CLICK_2")));

        if (jp(b("MNU_BACK_1")) || jp(b("MNU_BACK_2")) || jp(b("MNU_BACK_3"))) {
            triggerEscape();
        }
    }

    private TextField getActiveTextField() {
        try {
            Element el = Core.scene.getKeyboardFocus();
            if (el instanceof TextField tf) return tf;
            Element under = elementUnderCursor();
            if (under instanceof TextField tf) return tf;
            if (Core.scene.hasDialog()) {
                Dialog d = Core.scene.getDialog();
                if (d != null) {
                    return findTextFieldInGroup(d);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private TextField findTextFieldInGroup(Group group) {
        if (group == null) return null;
        Seq<Element> children = group.getChildren();
        for (int i = 0; i < children.size; i++) {
            Element c = children.get(i);
            if (c instanceof TextField tf) return tf;
            if (c instanceof Group g) {
                TextField res = findTextFieldInGroup(g);
                if (res != null) return res;
            }
        }
        return null;
    }

    private boolean isButtonElement(Element el) {
        while (el != null) {
            if (el instanceof Button) return true;
            el = el.parent;
        }
        return false;
    }

    private void handleTextFieldGamepadInput(TextField tf, float dt) {
        if (textDpadCdX > 0) textDpadCdX -= dt;
        if (textDpadCdY > 0) textDpadCdY -= dt;

        int ddx = dpadX();
        int ddy = -dpadY();

        if (ddx == 0) textDpadCdX = 0;
        else if (textDpadCdX <= 0) {
            if (ddx < 0) appendSymbolToField(tf, SYM_LEFT);
            else if (ddx > 0) appendSymbolToField(tf, SYM_RIGHT);
            textDpadCdX = 14f;
        }

        if (ddy == 0) textDpadCdY = 0;
        else if (textDpadCdY <= 0) {
            if (ddy > 0) appendSymbolToField(tf, SYM_UP);
            else if (ddy < 0) appendSymbolToField(tf, SYM_DOWN);
            textDpadCdY = 14f;
        }

        Element el = elementUnderCursor();
        boolean hoverButton = isButtonElement(el);

        // Face buttons (X, Circle, Triangle, Square)
        if (!hoverButton) {
            if (jp(0)) appendSymbolToField(tf, SYM_X);
            if (jp(1)) appendSymbolToField(tf, SYM_CIRCLE);
        }

        if (jp(2)) appendSymbolToField(tf, SYM_TRIANGLE);
        if (jp(3)) appendSymbolToField(tf, SYM_SQUARE);

        // L1 (Button 4) -> Backspace
        if (jp(4)) backspaceField(tf);

        // Options (Button 9) -> Submit dialog
        if (jp(9)) {
            Core.scene.keyDown(KeyCode.enter);
            Core.scene.keyUp(KeyCode.enter);
        }
    }

    private void appendSymbolToField(TextField tf, String sym) {
        if (tf == null || sym == null || sym.isEmpty()) return;
        int pos = tf.getCursorPosition();
        String cur = tf.getText();
        if (cur == null) cur = "";
        pos = Mathf.clamp(pos, 0, cur.length());
        String next = cur.substring(0, pos) + sym + cur.substring(pos);
        tf.setText(next);
        tf.setCursorPosition(pos + sym.length());
    }

    private void backspaceField(TextField tf) {
        if (tf == null) return;
        int pos = tf.getCursorPosition();
        String cur = tf.getText();
        if (cur == null || cur.isEmpty() || pos <= 0) return;
        pos = Mathf.clamp(pos, 1, cur.length());
        String next = cur.substring(0, pos - 1) + cur.substring(pos);
        tf.setText(next);
        tf.setCursorPosition(pos - 1);
    }

    private void scrollMenu(float sx, float sy, float dt) {
        float dirY = cfg.scrollInvert ? -1 : 1;
        float dx = sx * Math.abs(sx) * cfg.scrollSpeed * dt;
        float dy = sy * Math.abs(sy) * cfg.scrollSpeed * dt * dirY;
        Element el = elementUnderCursor();
        while (el != null) {
            if (el instanceof ScrollPane pane) {
                boolean movedY = false, movedX = false;
                if (dy != 0 && pane.getMaxY() > 0) { pane.setScrollY(pane.getScrollY() + dy); movedY = true; }
                if (dx != 0 && pane.getMaxX() > 0) { pane.setScrollX(pane.getScrollX() + dx); movedX = true; }
                if (movedY || movedX) return;
            }
            el = el.parent;
        }
        try { Core.scene.scrolled(dx * 0.02f, dy * 0.02f); } catch (Exception ignored) {}
    }

    private boolean adjustSliderUnderCursor(int dir) {
        Element el = elementUnderCursor();
        while (el != null) {
            if (el instanceof Slider s) {
                s.setValue(s.getValue() + dir * s.getStepSize());
                return true;
            }
            el = el.parent;
        }
        return false;
    }

    // === Minimap ===
    private void handleMinimap(float dt) {
        if (!mapFieldsInit && Vars.ui.minimapfrag != null) {
            mapFieldsInit = true;
            mapPanXField = findField(Vars.ui.minimapfrag.getClass(), "panx");
            mapPanYField = findField(Vars.ui.minimapfrag.getClass(), "pany");
            mapZoomField = findField(Vars.ui.minimapfrag.getClass(), "zoom");
        }

        int mc1 = b("MAP_CLOSE_COMBO_1"), mc2 = b("MAP_CLOSE_COMBO_2");
        boolean closeCombo = (mc1 == mc2) ? jp(mc1) : ((dn(mc1) && jp(mc2)) || (dn(mc2) && jp(mc1)));

        if (closeCombo || jp(b("MAP_CLOSE_1")) || jp(b("MAP_CLOSE_2"))) {
            Vars.ui.minimapfrag.hide();
            pad.suppressed[mc1] = true; pad.suppressed[mc2] = true;
            pad.suppressed[b("MAP_CLOSE_1")] = true; pad.suppressed[b("MAP_CLOSE_2")] = true;
            return;
        }

        float curZoom = 1;
        try { curZoom = Vars.renderer.minimap.getZoom(); } catch (Exception ignored) {}

        float rxv = rx(5), ryv = ry(5);
        if ((rxv != 0 || ryv != 0) && mapPanXField != null && mapPanYField != null) {
            try {
                Object mf = Vars.ui.minimapfrag;
                float px = mapPanXField.getFloat(mf), py = mapPanYField.getFloat(mf);
                float step = (cfg.camPanSpeed * 1.5f / Math.max(curZoom, 0.2f)) * dt;
                mapPanXField.setFloat(mf, px + rxv * step);
                mapPanYField.setFloat(mf, py + ryv * step);
            } catch (Exception e) { logOnce("map pan", e); }
        }

        int zDir = (dn(b("MAP_ZOOM_IN")) ? 1 : 0) - (dn(b("MAP_ZOOM_OUT")) ? 1 : 0);
        if (zDir != 0) {
            try {
                float nextZ = Mathf.clamp(curZoom * (1 + cfg.zoomInSign * zDir * cfg.zoomSpeed * dt * 0.5f), 0.25f, 16f);
                Vars.renderer.minimap.setZoom(nextZ);
                if (mapZoomField != null) mapZoomField.setFloat(Vars.ui.minimapfrag, nextZ);
            } catch (Exception e) { logOnce("map zoom", e); }
        }
    }

    // === Game handling ===
    public void handleGame(float dt) {
        if (wasInUI) pad.suppressAllHeld();
        if (uiMouseDown) updateMenuMouseClick(false);

        Player player = Vars.player;
        if (player == null) return;
        boolean alive = !player.dead();
        Unit unit = alive ? player.unit() : null;

        // Synchronize schematic chosen from system Schematics dialog
        syncSystemSchematic();

        // Minimap
        if (Vars.ui.minimapfrag != null) {
            if (Vars.ui.minimapfrag.shown()) { handleMinimap(dt); return; }

            // Build save combo
            if (currentMode == MODE_BUILD) {
                int sv1 = b("BLD_SAVE_1"), sv2 = b("BLD_SAVE_2");
                boolean saveCombo = (sv1 == sv2) ? jp(sv1) : ((dn(sv1) && jp(sv2)) || (dn(sv2) && jp(sv1)));
                if (saveCombo) {
                    if (clipboard != null && !clipboard.isEmpty()) openSystemSaveDialog();
                    else openSystemSchematicsDialog();
                    pad.suppressed[sv1] = true; pad.suppressed[sv2] = true;
                    return;
                }
            }

            // Minimap open combo
            int mm1 = b("GEN_MINIMAP_1"), mm2 = b("GEN_MINIMAP_2");
            boolean openMap = (mm1 == mm2) ? jp(mm1) : ((dn(mm1) && jp(mm2)) || (dn(mm2) && jp(mm1)));
            if (openMap) {
                Vars.ui.minimapfrag.toggle();
                pad.suppressed[mm1] = true; pad.suppressed[mm2] = true;
                return;
            }
        }

        if (jp(b("GEN_MENU"))) { triggerEscape(); return; }
        if (jp(b("GEN_PAUSE"))) togglePause();

        // Mode switching & Camera recenter
        if (jp(b("GEN_MODE_NORMAL"))) {
            if (currentMode == MODE_NORMAL && cameraDetached) {
                recenterCamera(player, alive, unit);
            } else {
                setMode(MODE_NORMAL);
            }
        } else if (jp(b("GEN_MODE_BUILD"))) {
            setMode(MODE_BUILD);
        } else if (jp(b("GEN_MODE_COMMAND"))) {
            setMode(MODE_COMMAND);
        }

        if (b("GEN_RECENTER_CAM") != b("GEN_ZOOM_IN") && jp(b("GEN_RECENTER_CAM"))) {
            recenterCamera(player, alive, unit);
        }

        // Initialize camera target when attached
        if (!cameraDetached) {
            if (alive && unit != null) {
                camPos.set(player.x, player.y);
            } else if (Core.camera != null) {
                camPos.set(Core.camera.position);
            }
        }

        int activeCat = (currentMode == MODE_NORMAL) ? 1 : (currentMode == MODE_BUILD) ? 2 : 3;

        // Cursor & world position
        moveCursor(rx(activeCat), -ry(activeCat), dt);
        syncMousePosition();

        Vec2 wp = Core.camera.unproject(Tmp.v1.set(cursorX, cursorY));
        float wx = wp.x, wy = wp.y;
        player.mouseX = wx;
        player.mouseY = wy;

        // Movement (does NOT forcibly reset camera panning when detached)
        float lxv = lx(activeCat), lyv = -ly(activeCat);
        if (unit != null && (lxv != 0 || lyv != 0)) {
            Vec2 mv = Tmp.v2.set(lxv, lyv);
            if (mv.len() > 1) mv.nor();
            mv.scl(unit.speed());
            unit.movePref(mv);
        }

        // Zoom
        if (dn(b("GEN_ZOOM_IN"))) Vars.renderer.scaleCamera(cfg.zoomInSign * cfg.zoomSpeed * dt);
        if (dn(b("GEN_ZOOM_OUT"))) Vars.renderer.scaleCamera(-cfg.zoomInSign * cfg.zoomSpeed * dt);

        int ddx = dpadX(), ddy = -dpadY();

        if (currentMode == MODE_NORMAL) normalMode(player, unit, alive, wx, wy, ddx, ddy, dt);
        else if (currentMode == MODE_BUILD) buildMode(player, unit, alive, wx, wy, ddx, ddy, dt);
        else commandMode(player, unit, wx, wy, ddx, ddy, dt);

        // Keep camera locked to target position if detached, preventing snapback to player
        if (cameraDetached) {
            try {
                Field panningField = findField(Vars.control.input.getClass(), "panning");
                if (panningField != null) panningField.setBoolean(Vars.control.input, true);
            } catch (Exception ignored) {}
            Core.camera.position.set(camPos);
        } else {
            if (alive && unit != null && Core.camera != null) {
                camPos.set(Core.camera.position);
            }
        }
    }

    public void recenterCamera(Player player, boolean alive, Unit unit) {
        cameraDetached = false;
        panHeldTime = 0f;
        if (alive && unit != null) {
            camPos.set(player.x, player.y);
        } else if (Core.camera != null) {
            camPos.set(Core.camera.position);
        }
        if (Core.camera != null) {
            Core.camera.position.set(camPos);
        }
        toast("[accent]Camera centered on player");
    }

    // === Mode switching ===
    public void setMode(int m) {
        currentMode = m;
        InputHandler input = Vars.control.input;
        try { input.commandMode = (m == MODE_COMMAND); } catch (Exception ignored) {}
        if (m == MODE_BUILD) {
            for (int k = 0; k < Category.all.length; k++) {
                if (!getBlocksInCategory(selectedCatIndex).isEmpty()) break;
                selectedCatIndex = (selectedCatIndex + 1) % Category.all.length;
            }
            selectedBlockIndex = 0;
            syncCategoryUI();
            updateSelectedBlock();
        } else {
            input.block = null;
        }
        if (m != MODE_COMMAND) cmdSelecting = false;
        if (m == MODE_COMMAND) { cmdRow = 0; cmdCol = 0; }
        placing = false; removing = false; linePoints = null; rebuilding = false;
        xHoldFrames = 0; copyArmed = false; copying = false;
        clipboard = null; pasteView = null;
        miningTile = null; nArmed = false; nDropped = false;
    }

    // === Camera pan (D-pad) ===
    private void panCamera(int dpadX, int dpadY, float dt) {
        if (dpadX == 0 && dpadY == 0) {
            panHeldTime = 0f;
            return;
        }
        cameraDetached = true;
        panHeldTime += dt;
        try {
            Field f = findField(Vars.control.input.getClass(), "panning");
            if (f != null) f.setBoolean(Vars.control.input, true);
        } catch (Exception ignored) {}
        float scale = 4f;
        try { scale = Vars.renderer.getScale(); } catch (Exception ignored) {}
        float step = (cfg.camPanSpeed * 0.7f / Math.max(scale, 0.2f)) * dt;
        camPos.add(dpadX * step, dpadY * step);
        if (Vars.world != null) {
            camPos.x = Mathf.clamp(camPos.x, 0, Vars.world.unitWidth());
            camPos.y = Mathf.clamp(camPos.y, 0, Vars.world.unitHeight());
        }
        Core.camera.position.set(camPos);
    }

    // === Block availability ===
    private boolean isBlockAvailable(Block block) {
        if (block == null) return false;
        try { if (!block.isVisible()) return false; } catch (Exception ignored) {}
        try { if (!block.environmentBuildable()) return false; } catch (Exception ignored) {}
        try { if (!block.supportsEnv(Vars.state.rules.env)) return false; } catch (Exception ignored) {}
        if (Vars.state.rules.infiniteResources || Vars.state.isEditor()) return true;
        try { return block.unlockedNow(); } catch (Exception e) { return false; }
    }

    private List<Block> getBlocksInCategory(int catIndex) {
        Category cat = Category.all[Mathf.clamp(catIndex, 0, Category.all.length - 1)];
        List<Block> list = new ArrayList<>();
        Seq<Block> blocks = Vars.content.blocks();
        for (int i = 0; i < blocks.size; i++) {
            Block block = blocks.get(i);
            if (block.category == cat && isBlockAvailable(block)) list.add(block);
        }
        return list;
    }

    private void updateSelectedBlock() {
        clipboard = null; pasteView = null;
        List<Block> list = getBlocksInCategory(selectedCatIndex);
        if (list.isEmpty()) { Vars.control.input.block = null; return; }
        selectedBlockIndex = Mathf.clamp(selectedBlockIndex, 0, list.size() - 1);
        Vars.control.input.block = list.get(selectedBlockIndex);
    }

    private void syncCategoryUI() {
        try {
            Category cat = Category.all[selectedCatIndex];
            Object frag = Vars.ui.hudfrag.blockfrag;
            if (catField == null) {
                Class<?> c = frag.getClass();
                while (c != null) {
                    try { catField = c.getDeclaredField("currentCategory"); catField.setAccessible(true); break; }
                    catch (Exception e) { c = c.getSuperclass(); }
                }
            }
            if (catField != null && String.valueOf(catField.get(frag)).equals(String.valueOf(cat))) return;
            Element el = Core.scene.find("category-" + cat.name());
            if (el != null) try { el.fireClick(); } catch (Exception ignored) {}
            if (catField != null && !String.valueOf(catField.get(frag)).equals(String.valueOf(cat)))
                catField.set(frag, cat);
        } catch (Exception e) { logOnce("category UI", e); }
    }

    // === Mining ===
    private void stopMining(Unit unit) {
        miningTile = null;
        try { if (unit != null) unit.mineTile = null; } catch (Exception ignored) {}
    }

    private boolean tryStartMining(Unit unit, Tile tile) {
        InputHandler input = Vars.control.input;
        boolean ok = false;
        try {
            if (canMineMethod == null) {
                canMineMethod = InputHandler.class.getDeclaredMethod("canMine", Tile.class);
                canMineMethod.setAccessible(true);
            }
            ok = (Boolean) canMineMethod.invoke(input, tile);
        } catch (Exception e) {
            try {
                ok = (tile.drop() != null || tile.wallDrop() != null) && unit.canMine() && unit.validMine(tile);
            } catch (Exception e2) {
                ok = false;
            }
        }
        if (!ok) return false;
        try {
            if (tryBeginMineMethod == null) {
                tryBeginMineMethod = InputHandler.class.getDeclaredMethod("tryBeginMine", Tile.class);
                tryBeginMineMethod.setAccessible(true);
            }
            tryBeginMineMethod.invoke(input, tile);
        } catch (Exception ignored) {}
        if (unit.mineTile == null) unit.mineTile = tile;
        miningTile = tile;
        return true;
    }

    // === Core spawn ===
    private void spawnAtCore(Player player, Unit unit, boolean alive, Building core) {
        stopMining(unit);
        try { Vars.control.input.inv.hide(); } catch (Exception ignored) {}
        try { Vars.control.input.config.hideConfig(); } catch (Exception ignored) {}
        if (alive) Call.unitClear(player);
        if (Vars.net.client()) return;
        try { core.getClass().getMethod("requestSpawn", Player.class).invoke(core, player); }
        catch (Exception e) {
            try { Call.playerSpawn(core.tile, player); }
            catch (Exception e2) { logOnce("core spawn", e2); }
        }
        toast("[accent]Respawning at core: [white]" + core.block.localizedName);
    }

    // === Possess ===
    private boolean tryControlBuilding(Player player, Unit unit, Building b) {
        if (b == null || b.team != player.team()) return false;
        if (b.block.name.startsWith("core")) return false;
        stopMining(unit);

        try {
            Method m = b.getClass().getMethod("unit");
            Object bu = m.invoke(b);
            if (bu != null) {
                if (unit == bu) return false;
                Call.unitControl(player, (Unit) bu);
                toast("[accent]Controlling: [white]" + b.block.localizedName);
                return true;
            }
        } catch (Exception ignored) {}

        try {
            if (unit != null && b.canControlSelect(unit)) {
                Call.buildingControlSelect(player, b);
                toast("[accent]Controlling: [white]" + b.block.localizedName);
                return true;
            }
        } catch (Exception ignored) {}

        if (!Vars.net.client()) {
            try {
                if (unit != null && unit.type == UnitTypes.block && unit.tileOn() != null && unit.tileOn().build == b) return false;
                Unit customBu = UnitTypes.block.create(b.team);
                try {
                    Field tileF = findField(customBu.getClass(), "tile");
                    if (tileF != null) tileF.set(customBu, b);
                } catch (Exception ignored) {}
                customBu.set(b.x, b.y);
                customBu.health = b.health;
                player.unit(customBu);
                toast("[accent]Controlling: [white]" + b.block.localizedName);
                return true;
            } catch (Exception e) { logOnce("force possess", e); }
        }
        return false;
    }

    // === Inventory fragment ===
    private Object invFrag() {
        InputHandler input = Vars.control.input;
        try { if (input.inv != null) return input.inv; } catch (Exception ignored) {}
        try {
            Field fragF = findField(input.getClass(), "frag");
            if (fragF != null) {
                Object frag = fragF.get(input);
                if (frag != null) {
                    Field invF = findField(frag.getClass(), "inv");
                    if (invF != null) return invF.get(frag);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // === Tile tap ===
    private boolean tileTappedWorld(Player player, Building b, boolean allowConfig, boolean allowInv) {
        InputHandler input = Vars.control.input;
        Object inv = invFrag();

        if (b == null) {
            if (allowInv && inv != null) try { inv.getClass().getMethod("hide").invoke(inv); } catch (Exception ignored) {}
            if (allowConfig) try { input.config.hideConfig(); } catch (Exception ignored) {}
            return false;
        }
        boolean consumed = false;

        if (allowConfig) {
            try {
                if (b.block.configurable && b.interactable(player.team())) {
                    consumed = true;
                    if ((!input.config.isShown() && b.shouldShowConfigure(player)) ||
                        (input.config.isShown() && input.config.getSelected() != null && input.config.getSelected().onConfigureBuildTapped(b))) {
                        try { Sounds.click.at(b); } catch (Exception ignored) {}
                        input.config.showConfig(b);
                    }
                } else if (!input.config.hasConfigMouse()) {
                    if (input.config.isShown() && input.config.getSelected() != null && input.config.getSelected().onConfigureBuildTapped(b)) consumed = true;
                    else input.config.hideConfig();
                }
            } catch (Exception e) { logOnce("config tap", e); }
        }

        if (allowInv) {
            try {
                boolean hasStuff = b.block.hasItems && b.items != null && b.items.total() > 0 && b.interactable(player.team()) && b.block.itemCapacity > 0;
                if (hasStuff && inv != null) {
                    inv.getClass().getMethod("showFor", Building.class).invoke(inv, b);
                    consumed = true;
                } else if (inv != null) {
                    inv.getClass().getMethod("hide").invoke(inv);
                }
            } catch (Exception e) { logOnce("inv tap", e); }
        }
        return consumed;
    }

    // === Normal mode tap/drop ===
    private boolean isNormalTapBtn(int btn) {
        return btn == b("NRM_TAP_UI") || btn == b("NRM_CONFIG") || btn == b("NRM_INV") || btn == b("NRM_MINE");
    }
    private boolean isNormalDropBtn(int btn) {
        return btn == b("NRM_DROP_BUILD") || btn == b("NRM_DROP_GROUND");
    }

    private void tapActionForBtn(Player player, Unit unit, float wx, float wy, int btn) {
        if (btn == b("NRM_TAP_UI") && elementUnderCursor() != null) { clickSceneAtCursor(); return; }

        InputHandler input = Vars.control.input;
        if (btn == b("NRM_CONFIG")) {
            try {
                if (input.config.isShown() && input.config.getSelected() != null && input.config.getSelected().onConfigureTapped(wx, wy))
                    return;
            } catch (Exception ignored) {}
        }

        Building build = Vars.world.buildWorld(wx, wy);
        if (btn == b("NRM_CONFIG") || btn == b("NRM_INV")) {
            if (tileTappedWorld(player, build, btn == b("NRM_CONFIG"), btn == b("NRM_INV"))) return;
        }

        if (btn == b("NRM_MINE")) {
            Tile ore = Vars.world.tileWorld(wx, wy);
            if (unit != null && ore != null && ((ore.block() == Blocks.air && ore.drop() != null) || ore.wallDrop() != null)) {
                if (!tryStartMining(unit, ore)) toast("[scarlet]Can't mine here (too far or unit can't)");
            } else {
                stopMining(unit);
            }
        }
    }

    private void dropItemsForBtn(Player player, Unit unit, float wx, float wy, int btn) {
        if (unit == null || unit.stack.amount <= 0) { toast("[lightgray]Unit has no items"); return; }
        ItemStack st = unit.stack;
        Building build = Vars.world.buildWorld(wx, wy);
        boolean canGive = false;
        if (build != null) {
            try {
                canGive = build.interactable(player.team()) && build.block.hasItems && build.acceptStack(st.item, st.amount, unit) > 0;
                if (canGive && Vars.state.rules.onlyDepositCore && !build.block.name.startsWith("core")) canGive = false;
            } catch (Exception e) { canGive = false; }
        }
        if (canGive && btn == b("NRM_DROP_BUILD")) {
            float range = 220f;
            try { range = Vars.itemTransferRange; } catch (Exception ignored) {}
            if (!unit.within(build, range)) { toast("[scarlet]Too far"); return; }
            Call.transferInventory(player, build);
            toast("[accent]Transferred to: [white]" + build.block.localizedName);
        } else if (!canGive && btn == b("NRM_DROP_GROUND")) {
            Call.dropItem(player.angleTo(wx, wy));
            toast("[accent]Items dropped");
        } else if (btn == b("NRM_DROP_GROUND") && b("NRM_DROP_GROUND") != b("NRM_DROP_BUILD")) {
            Call.dropItem(player.angleTo(wx, wy));
            toast("[accent]Items dropped");
        }
    }

    // === Normal mode ===
    private void normalMode(Player player, Unit unit, boolean alive, float wx, float wy, int dpadX, int dpadY, float dt) {
        panCamera(dpadX, dpadY, dt);

        // Mining tick
        if (miningTile != null) {
            if (!alive || unit == null) {
                miningTile = null;
            } else {
                boolean stillOk = false;
                try { stillOk = unit.validMine(miningTile); } catch (Exception ignored) {}
                if (!stillOk) stopMining(unit);
                else if (unit.mineTile == null) unit.mineTile = miningTile;
            }
        }

        // Button actions
        int[] nBtns = { b("NRM_TAP_UI"), b("NRM_CONFIG"), b("NRM_INV"), b("NRM_MINE"), b("NRM_DROP_BUILD"), b("NRM_DROP_GROUND") };
        Set<Integer> checked = new HashSet<>();
        for (int btn : nBtns) {
            if (checked.contains(btn)) continue;
            checked.add(btn);
            if (jp(btn)) {
                if (isNormalTapBtn(btn) && isNormalDropBtn(btn)) {
                    nArmed = true; nHoldBtn = btn; nHold = 0; nDropped = false;
                } else if (isNormalDropBtn(btn)) {
                    if (alive) dropItemsForBtn(player, unit, wx, wy, btn);
                } else if (isNormalTapBtn(btn)) {
                    tapActionForBtn(player, unit, wx, wy, btn);
                }
            }
        }

        if (nArmed) {
            if (dn(nHoldBtn)) {
                nHold += dt;
                if (nHold >= cfg.holdFrames && !nDropped) {
                    nDropped = true;
                    if (alive) dropItemsForBtn(player, unit, wx, wy, nHoldBtn);
                }
            } else {
                nArmed = false;
                if (!nDropped) tapActionForBtn(player, unit, wx, wy, nHoldBtn);
            }
        }

        // Possess unit
        boolean didPossess = false;
        if (jp(b("NRM_POSSESS_UNIT"))) {
            Unit target = Units.closest(player.team(), wx, wy, 12f,
                u -> !u.isPlayer() && u.type.playerControllable && !u.dead() && u.within(wx, wy, u.hitSize * 0.7f + 2f));
            if (target != null) {
                stopMining(unit);
                Call.unitControl(player, target);
                toast("[accent]Controlling: [white]" + target.type.localizedName);
                didPossess = true;
            }
        }

        if (!didPossess && jp(b("NRM_POSSESS_BUILD"))) {
            Building bPoss = Vars.world.buildWorld(wx, wy);
            if (bPoss != null && bPoss.team == player.team()) {
                if (isCoreBuild(bPoss)) { spawnAtCore(player, unit, alive, bPoss); didPossess = true; }
                else if (tryControlBuilding(player, unit, bPoss)) didPossess = true;
            }
        }

        if (!didPossess && jp(b("NRM_RESPAWN_CORE"))) {
            Building under = Vars.world.buildWorld(wx, wy);
            if (under != null && under.team == player.team() && isCoreBuild(under)) {
                spawnAtCore(player, unit, alive, under);
            } else {
                Unit onUnit = Units.closest(player.team(), wx, wy, 12f,
                    u -> !u.isPlayer() && u.type.playerControllable && !u.dead() && u.within(wx, wy, u.hitSize * 0.7f + 2f));
                boolean onFriendly = under != null && under.team == player.team();
                if (onUnit == null && !onFriendly) {
                    stopMining(unit);
                    try { Vars.control.input.inv.hide(); } catch (Exception ignored) {}
                    try { Vars.control.input.config.hideConfig(); } catch (Exception ignored) {}
                    Call.unitClear(player);
                }
            }
        }

        // Research
        if (jp(b("NRM_RESEARCH"))) {
            if (Vars.state.isCampaign()) Vars.ui.research.show();
            else toast("[scarlet]Research is only available in campaign");
        }

        // Skip wave
        if (jp(b("NRM_SKIP_WAVE"))) {
            try {
                if (Vars.net.client()) {
                    if (player.admin) {
                        Class<?> adminAction = Class.forName("mindustry.net.Packets$AdminAction");
                        Object wave = Enum.valueOf((Class<Enum>) adminAction, "wave");
                        Call.class.getMethod("adminRequest", Player.class, adminAction, Object.class).invoke(null, player, wave, null);
                    }
                } else {
                    Vars.logic.skipWave();
                }
            } catch (Exception e) { logOnce("skip wave", e); }
        }

        // Shooting
        boolean shooting = alive && dn(b("NRM_SHOOT"));
        player.shooting = shooting;
        if (unit != null) { unit.aim(wx, wy); unit.controlWeapons(true, shooting); }
    }

    // === Build mode ===
    private void buildMode(Player player, Unit unit, boolean alive, float wx, float wy, int dpadX, int dpadY, float dt) {
        InputHandler input = Vars.control.input;
        player.shooting = false;

        if (jp(b("BLD_SUB_CAT"))) { buildSubMode = "category"; toast("[accent]Category select"); }

        boolean rotConsumed = false;
        if (clipboard != null && jp(b("BLD_ROTATE"))) { rotateClipboard(); rotConsumed = true; }

        if (b("BLD_SUB_BLOCK") == b("BLD_ROTATE")) {
            if (jp(b("BLD_SUB_BLOCK")) && !rotConsumed) {
                if ("block".equals(buildSubMode)) input.rotation = (input.rotation + 1) % 4;
                else { buildSubMode = "block"; toast("[accent]Block select"); }
                updateSelectedBlock();
            }
        } else {
            if (jp(b("BLD_SUB_BLOCK"))) { buildSubMode = "block"; toast("[accent]Block select"); updateSelectedBlock(); }
            if (jp(b("BLD_ROTATE")) && !rotConsumed) { input.rotation = (input.rotation + 1) % 4; updateSelectedBlock(); }
        }

        // DPad navigation
        if (dpadCooldown > 0) dpadCooldown -= dt;
        if ((dpadX != 0 || dpadY != 0) && dpadCooldown <= 0) {
            dpadCooldown = 10;
            if ("category".equals(buildSubMode)) {
                int catCols = 2, totalCats = Category.all.length;
                int cRow = selectedCatIndex / catCols, cCol = selectedCatIndex % catCols;
                if (dpadX != 0) {
                    int nextCol = Mathf.clamp(cCol + dpadX, 0, catCols - 1);
                    int cand = cRow * catCols + nextCol;
                    if (cand < totalCats) selectedCatIndex = cand;
                }
                if (dpadY != 0) {
                    int cand = selectedCatIndex + (-dpadY * catCols);
                    if (cand >= 0 && cand < totalCats) selectedCatIndex = cand;
                }
                selectedBlockIndex = 0;
                syncCategoryUI();
                updateSelectedBlock();
            } else {
                List<Block> blocks = getBlocksInCategory(selectedCatIndex);
                if (!blocks.isEmpty()) {
                    int blkCols = 4;
                    int bRow = selectedBlockIndex / blkCols, bCol = selectedBlockIndex % blkCols;
                    if (dpadX != 0) {
                        int maxCol = Math.min(blkCols - 1, blocks.size() - 1 - bRow * blkCols);
                        int nextBCol = Mathf.clamp(bCol + dpadX, 0, maxCol);
                        selectedBlockIndex = bRow * blkCols + nextBCol;
                    }
                    if (dpadY != 0) {
                        int cand = selectedBlockIndex + (-dpadY * blkCols);
                        if (cand >= 0 && cand < blocks.size()) selectedBlockIndex = cand;
                    }
                    updateSelectedBlock();
                }
            }
        }
        if (dpadX == 0 && dpadY == 0) dpadCooldown = 0;

        Block blk = input.block;
        float off = blk != null ? blk.offset : 0;
        int px = World.toTile(wx - off), py = World.toTile(wy - off);
        int tx = World.toTile(wx), ty = World.toTile(wy);
        boolean canB = unit != null && unit.canBuild();
        boolean cancelPlaceConsumed = false;

        boolean emptyAim = (blk == null && clipboard == null);

        // Placing (when block is selected)
        if (!emptyAim && (clipboard == null || b("BLD_PLACE") != b("BLD_PASTE")) && !placing && !removing && !rebuilding && jp(b("BLD_PLACE")) && blk != null && canB) {
            placing = true; placeStartX = px; placeStartY = py;
        }
        if (placing) {
            if (blk == null || !canB) {
                placing = false; linePoints = null;
            } else {
                linePoints = computeLine(placeStartX, placeStartY, px, py, blk, input.rotation);
                for (LinePoint lp : linePoints) {
                    lp.valid = Build.validPlace(blk, player.team(), lp.x, lp.y, lp.rot);
                }
                if (jp(b("BLD_CANCEL_PLACE"))) {
                    placing = false; linePoints = null; cancelPlaceConsumed = true;
                    toast("[lightgray]Build cancelled");
                } else if (!dn(b("BLD_PLACE"))) {
                    for (LinePoint q : linePoints) {
                        if (q.valid) unit.addBuild(new BuildPlan(q.x, q.y, q.rot, blk, blk.lastConfig));
                    }
                    input.isBuilding = true;
                    placing = false; linePoints = null;
                }
            }
        }

        // Rebuild & Repair derelicts (when cursor is empty)
        if (emptyAim && !placing && !removing && !rebuilding && jp(b("BLD_PLACE")) && canB) {
            rebuilding = true;
            rebuildStartX = tx; rebuildStartY = ty;
            rebuildEndX = tx; rebuildEndY = ty;
        }
        if (rebuilding) {
            rebuildEndX = Mathf.clamp(tx, rebuildStartX - (MAX_LINE - 1), rebuildStartX + (MAX_LINE - 1));
            rebuildEndY = Mathf.clamp(ty, rebuildStartY - (MAX_LINE - 1), rebuildStartY + (MAX_LINE - 1));
            if (!canB || jp(b("BLD_CANCEL_PLACE")) || jp(b("BLD_CANCEL_ACTION"))) {
                rebuilding = false; cancelPlaceConsumed = true;
                toast("[lightgray]Rebuild cancelled");
            } else if (!dn(b("BLD_PLACE"))) {
                commitRebuild(player, unit, rebuildStartX, rebuildStartY, rebuildEndX, rebuildEndY);
                rebuilding = false;
            }
        }

        // Removing
        if (!removing && !placing && !rebuilding && !cancelPlaceConsumed && jp(b("BLD_REMOVE")) && canB) {
            removing = true; breakStartX = tx; breakStartY = ty;
        }
        if (removing) {
            breakEndX = Mathf.clamp(tx, breakStartX - (MAX_LINE - 1), breakStartX + (MAX_LINE - 1));
            breakEndY = Mathf.clamp(ty, breakStartY - (MAX_LINE - 1), breakStartY + (MAX_LINE - 1));
            if (!canB || jp(b("BLD_CANCEL_REMOVE"))) { removing = false; toast("[lightgray]Deconstruction cancelled"); }
            else if (!dn(b("BLD_REMOVE"))) { commitRemove(player, unit, breakStartX, breakStartY, breakEndX, breakEndY); removing = false; }
        }

        // Paste
        if (clipboard != null) {
            if (tx != pasteLastTX || ty != pasteLastTY) {
                pasteLastTX = tx; pasteLastTY = ty;
                pasteView = buildPasteView(player, tx, ty);
            }
            if (jp(b("BLD_PASTE")) && canB && !placing && !removing && !rebuilding) {
                int okCount = 0;
                for (PasteEntry pv : pasteView) {
                    if (pv.valid) { unit.addBuild(new BuildPlan(pv.x, pv.y, pv.rot, pv.block, pv.config)); okCount++; }
                }
                if (okCount > 0) input.isBuilding = true;
                pasteLastTX = -9999;
                toast("[accent]Placed: [white]" + okCount + " of " + pasteView.size());
            }
        }

        // Cancel / Copy
        int cAct = b("BLD_CANCEL_ACTION"), cBlk = b("BLD_COPY_BLOCK"), cArea = b("BLD_COPY_AREA");

        if (cAct != cBlk && cAct != cArea && jp(cAct)) {
            if (placing || removing || rebuilding) { placing = false; linePoints = null; removing = false; rebuilding = false; toast("[lightgray]Cancelled"); }
            else cancelAll(unit);
        }

        if (cBlk != cAct && cBlk != cArea && jp(cBlk)) copyBlockAt(player, tx, ty);

        int holdBtnPressed = -1;
        if (jp(cArea)) holdBtnPressed = cArea;
        else if (cBlk == cAct && jp(cBlk)) holdBtnPressed = cBlk;

        if (holdBtnPressed >= 0) {
            if ((placing || removing || rebuilding) && holdBtnPressed == cAct) {
                placing = false; linePoints = null; removing = false; rebuilding = false;
                toast("[lightgray]Cancelled");
            } else {
                copyArmed = true; copyBtn = holdBtnPressed; copying = false; xHoldFrames = 0;
                copyStartX = tx; copyStartY = ty; copyEndX = tx; copyEndY = ty;
            }
        }

        if (copyArmed) {
            if (dn(copyBtn)) {
                xHoldFrames += dt;
                copyEndX = Mathf.clamp(tx, copyStartX - (MAX_LINE - 1), copyStartX + (MAX_LINE - 1));
                copyEndY = Mathf.clamp(ty, copyStartY - (MAX_LINE - 1), copyStartY + (MAX_LINE - 1));
                copying = (xHoldFrames >= cfg.holdFrames) || (copyBtn == cArea && cArea != cAct);
            } else {
                if (!copying) {
                    if (copyBtn == cAct) cancelAll(unit);
                } else if (copyEndX == copyStartX && copyEndY == copyStartY) {
                    if (copyBtn == cBlk) copyBlockAt(player, copyEndX, copyEndY);
                    else if (copyBtn == cArea) copyArea(player, copyStartX, copyStartY, copyEndX, copyEndY);
                } else {
                    if (copyBtn == cArea) copyArea(player, copyStartX, copyStartY, copyEndX, copyEndY);
                    else if (copyBtn == cBlk) copyBlockAt(player, copyEndX, copyEndY);
                }
                copyArmed = false; copying = false; xHoldFrames = 0;
            }
        }
    }

    // === Build helpers ===
    private void cancelAll(Unit unit) {
        InputHandler input = Vars.control.input;
        rebuilding = false;
        lastSyncedSchematic = null;
        input.lastSchematic = null;
        if (clipboard != null) { clipboard = null; pasteView = null; toast("[lightgray]Clipboard cleared"); return; }
        boolean hadPlans = unit != null && unit.plans.size > 0;
        if (unit != null) unit.clearBuilding();
        try { input.selectPlans.clear(); } catch (Exception ignored) {}
        if (!hadPlans) input.block = null;
        toast("[accent]Cancelled");
    }

    private List<LinePoint> computeLine(int sx, int sy, int ex, int ey, Block blk, int baseRot) {
        int step = Math.max(1, blk.size);
        int dx = ex - sx, dy = ey - sy;
        boolean horizontal = Math.abs(dx) >= Math.abs(dy);
        int len = Math.min(horizontal ? Math.abs(dx) : Math.abs(dy), MAX_LINE - 1);
        int dirx = horizontal ? (dx < 0 ? -1 : 1) : 0;
        int diry = horizontal ? 0 : (dy < 0 ? -1 : 1);
        int rot = baseRot;
        if (len > 0 && blk.conveyorPlacement) rot = horizontal ? (dirx > 0 ? 0 : 2) : (diry > 0 ? 1 : 3);
        List<LinePoint> pts = new ArrayList<>();
        for (int i = 0; i <= len; i += step) pts.add(new LinePoint(sx + dirx * i, sy + diry * i, rot, true));
        return pts;
    }

    private void commitRemove(Player player, Unit unit, int x1, int y1, int x2, int y2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile t = Vars.world.tile(x, y);
                if (t == null) continue;
                if (t.build != null) t = t.build.tile;
                String key = t.x + "," + t.y;
                if (seen.contains(key)) continue;
                seen.add(key);
                if (Build.validBreak(player.team(), t.x, t.y)) { unit.addBuild(new BuildPlan(t.x, t.y)); count++; }
            }
        }
        if (count > 0) Vars.control.input.isBuilding = true;
    }

    private void commitRebuild(Player player, Unit unit, int x1, int y1, int x2, int y2) {
        if (unit == null || !unit.canBuild()) return;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);

        int prevPlans = unit.plans.size;

        // 1. Mindustry native rebuildArea (handles destroyed team plans & derelicts)
        try {
            Vars.control.input.rebuildArea(minX, minY, maxX, maxY);
        } catch (Throwable e) {
            logOnce("rebuildArea", e);
        }

        // 2. Extra derelict scan across all tiles in the rectangle to guarantee all derelict buildings are queued
        IntSet seen = new IntSet();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;
                Building b = tile.build;
                if (b != null && b.team == Team.derelict && seen.add(b.id)) {
                    boolean repaired = false;
                    if (tryRepairDerelictMethod != null) {
                        try {
                            repaired = (boolean) tryRepairDerelictMethod.invoke(Vars.control.input, tile);
                        } catch (Throwable ignored) {}
                    }
                    if (!repaired && b.block != null && b.block.unlockedNow()) {
                        if (Build.validPlace(b.block, player.team(), b.tileX(), b.tileY(), b.rotation)) {
                            unit.addBuild(new BuildPlan(b.tileX(), b.tileY(), b.rotation, b.block, b.config()));
                        }
                    }
                }
            }
        }

        int added = unit.plans.size - prevPlans;
        if (added > 0) {
            Vars.control.input.isBuilding = true;
            toast("[accent]Queued repair/rebuild: [white]" + added);
        } else {
            toast("[lightgray]No damaged or derelict structures in area");
        }
    }

    private void copyBlockAt(Player player, int tileX, int tileY) {
        InputHandler input = Vars.control.input;
        Building tb = Vars.world.build(tileX, tileY);
        if (tb == null || tb.team != player.team() || !isBlockAvailable(tb.block)) return;
        Block blk = tb.block;
        int rot = buildRot(tb);
        selectedCatIndex = blk.category.ordinal();
        List<Block> list = getBlocksInCategory(selectedCatIndex);
        for (int i = 0; i < list.size(); i++) if (list.get(i) == blk) selectedBlockIndex = i;
        syncCategoryUI();
        clipboard = null; pasteView = null;
        input.block = blk;
        input.rotation = rot;
        if (blk.configurable) blk.lastConfig = tb.config();
        toast("[accent]Copied: [white]" + blk.localizedName);
    }

    private void copyArea(Player player, int x1, int y1, int x2, int y2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int ax = (minX + maxX) / 2, ay = (minY + maxY) / 2;
        Set<String> seen = new HashSet<>();
        List<ClipEntry> list = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile t = Vars.world.tile(x, y);
                if (t == null || t.build == null) continue;
                Building b = t.build;
                String key = b.tile.x + "," + b.tile.y;
                if (seen.contains(key)) continue;
                seen.add(key);
                Block blk = b.block;
                if (b.team != player.team() || !isBlockAvailable(blk)) continue;
                list.add(new ClipEntry(b.tile.x - ax, b.tile.y - ay, buildRot(b), blk, blk.configurable ? b.config() : null));
            }
        }
        if (list.isEmpty()) { toast("[scarlet]Nothing to copy in the selected area"); return; }
        clipboard = list; pasteView = null; pasteLastTX = -9999;
        Vars.control.input.block = null;
        toast("[accent]Copied buildings: [white]" + list.size());
    }

    private void rotateClipboard() {
        if (clipboard == null) return;
        for (ClipEntry c : clipboard) {
            float coff = c.block.offset;
            float cwx = c.dx * TS + coff, cwy = c.dy * TS + coff;
            float nwx = -cwy, nwy = cwx;
            c.dx = World.toTile(nwx - coff);
            c.dy = World.toTile(nwy - coff);
            c.rot = (c.rot + 1) % 4;
            c.config = rotateConfig(c.config);
        }
        pasteLastTX = -9999;
        toast("[accent]Schematic rotated");
    }

    @SuppressWarnings("unchecked")
    private Object rotateConfig(Object cfg) {
        if (cfg == null) return null;
        try {
            Class<?> point2Class = Class.forName("arc.math.geom.Point2");
            if (point2Class.isInstance(cfg)) {
                int px = (Integer) point2Class.getField("x").get(cfg);
                int py = (Integer) point2Class.getField("y").get(cfg);
                return point2Class.getConstructor(int.class, int.class).newInstance(-py, px);
            }
            if (cfg.getClass().isArray() && cfg.getClass().getComponentType().getName().equals("arc.math.geom.Point2")) {
                int len = Array.getLength(cfg);
                Object out = Array.newInstance(point2Class, len);
                for (int i = 0; i < len; i++) {
                    Object p = Array.get(cfg, i);
                    if (p == null) { Array.set(out, i, null); continue; }
                    int ppx = (Integer) point2Class.getField("x").get(p);
                    int ppy = (Integer) point2Class.getField("y").get(p);
                    Array.set(out, i, point2Class.getConstructor(int.class, int.class).newInstance(-ppy, ppx));
                }
                return out;
            }
        } catch (Exception e) { logOnce("rotateConfig", e); }
        return cfg;
    }

    private List<PasteEntry> buildPasteView(Player player, int tx, int ty) {
        List<PasteEntry> out = new ArrayList<>();
        if (clipboard == null) return out;
        for (ClipEntry c : clipboard) {
            int x = tx + c.dx, y = ty + c.dy;
            out.add(new PasteEntry(x, y, c.rot, c.block, c.config, Build.validPlace(c.block, player.team(), x, y, c.rot)));
        }
        return out;
    }

    // === Command mode ===
    private void commandMode(Player player, Unit unit, float wx, float wy, int dpadX, int dpadY, float dt) {
        InputHandler input = Vars.control.input;
        Seq<Unit> sel = input.selectedUnits;
        Seq<Building> cb = input.commandBuildings;
        player.shooting = false;
        input.commandMode = true;

        List<List<CmdButton>> pdata = cmdPanelData();
        cmdPanelNav(pdata, dpadX, dpadY, dt);

        int bSingle = b("CMD_SELECT_SINGLE"), bRect = b("CMD_SELECT_RECT");

        if (bSingle != bRect && jp(bSingle)) toggleSelectAt(player, sel, cb, wx, wy);

        if (jp(bRect)) {
            cmdSelecting = true; cmdStartX = wx; cmdStartY = wy;
        } else if (jr(bRect) && cmdSelecting) {
            cmdSelecting = false;
            if (Mathf.dst(cmdStartX, cmdStartY, wx, wy) < 12) {
                if (bSingle == bRect) toggleSelectAt(player, sel, cb, wx, wy);
            } else {
                float minX = Math.min(cmdStartX, wx), maxX = Math.max(cmdStartX, wx);
                float minY = Math.min(cmdStartY, wy), maxY = Math.max(cmdStartY, wy);
                sel.clear();
                if (cb != null) cb.clear();
                Groups.unit.each(u -> {
                    if (u.team == player.team() && u.isCommandable() && u.x >= minX && u.x <= maxX && u.y >= minY && u.y <= maxY) sel.add(u);
                });
                fireCmdChange();
            }
        }

        Unit eu = Units.closestEnemy(player.team(), wx, wy, 20f, u -> true);
        Building eb = Vars.world.buildWorld(wx, wy);
        if (eb != null && eb.team == player.team()) eb = null;
        boolean hasTarget = eu != null || eb != null;

        boolean orderTarget = hasTarget && jp(b("CMD_ORDER_TARGET"));
        boolean orderMove = !orderTarget && jp(b("CMD_ORDER_MOVE")) && (!hasTarget || b("CMD_ORDER_MOVE") != b("CMD_ORDER_TARGET"));

        if (orderTarget) {
            sendUnitsCommand(player, sel, eb, eu, new Vec2(wx, wy));
            sendBuildingsCommand(player, cb, new Vec2(wx, wy));
        } else if (orderMove) {
            sendUnitsCommand(player, sel, null, null, new Vec2(wx, wy));
            sendBuildingsCommand(player, cb, new Vec2(wx, wy));
        }

        if (jp(b("CMD_CLEAR_SEL"))) { sel.clear(); if (cb != null) cb.clear(); fireCmdChange(); }

        if (jp(b("CMD_SELECT_ALL"))) {
            sel.clear();
            if (cb != null) cb.clear();
            Groups.unit.each(u -> { if (u.team == player.team() && u.isCommandable()) sel.add(u); });
            fireCmdChange();
        }

        if (jp(b("CMD_PANEL_CLICK"))) cmdPanelActivate(player, sel, pdata);
    }

    private void fireCmdChange() {
        try { Events.fire(Trigger.unitCommandChange); } catch (Exception ignored) {}
    }

    private void toggleSelectAt(Player player, Seq<Unit> sel, Seq<Building> cb, float wx, float wy) {
        Unit u = Units.closest(player.team(), wx, wy, 24f, x -> x.isCommandable());
        Building bld = Vars.world.buildWorld(wx, wy);
        Building factory = (bld != null && bld.team == player.team() && bld.block.commandable) ? bld : null;

        if (factory != null && cb != null && (u == null || !u.within(wx, wy, u.hitSize / 2f + 4f))) {
            sel.clear();
            if (cb.contains(factory)) cb.remove(factory); else cb.add(factory);
            fireCmdChange();
            return;
        }
        if (u != null) {
            if (cb != null) cb.clear();
            if (sel.contains(u)) sel.remove(u); else sel.add(u);
            fireCmdChange();
        }
    }

    private void sendUnitsCommand(Player player, Seq<Unit> units, Building buildTarget, Unit unitTarget, Vec2 pos) {
        if (units.size == 0) return;
        int[] ids = new int[units.size];
        for (int i = 0; i < units.size; i++) ids[i] = units.get(i).id;
        try {
            Call.commandUnits(player, ids, buildTarget, unitTarget, pos, false, true);
        } catch (Throwable t) {
            try {
                for (Method m : Call.class.getMethods()) {
                    if (m.getName().equals("commandUnits")) {
                        int paramCount = m.getParameterTypes().length;
                        if (paramCount == 7) {
                            m.invoke(null, player, ids, buildTarget, unitTarget, pos, false, true);
                            break;
                        } else if (paramCount == 6) {
                            m.invoke(null, player, ids, buildTarget, unitTarget, pos, false);
                            break;
                        } else if (paramCount == 5) {
                            m.invoke(null, player, ids, buildTarget, unitTarget, pos);
                            break;
                        }
                    }
                }
            } catch (Exception e2) {
                logOnce("commandUnits", e2);
            }
        }
        try { Fx.commandSend.at(pos.x, pos.y); } catch (Exception ignored) {}
    }

    private void sendBuildingsCommand(Player player, Seq<Building> builds, Vec2 pos) {
        if (builds == null || builds.size == 0) return;
        int[] ids = new int[builds.size];
        for (int i = 0; i < builds.size; i++) ids[i] = builds.get(i).pos();
        try {
            Call.commandBuilding(player, ids, pos);
        } catch (Throwable t) {
            try {
                Method m = Call.class.getMethod("commandBuilds", Player.class, int[].class, Vec2.class);
                m.invoke(null, player, ids, pos);
            } catch (Throwable t2) {
                for (int j = 0; j < builds.size; j++) {
                    try { builds.get(j).onCommand(pos); } catch (Exception ignored) {}
                }
            }
        }
        try { Fx.commandSend.at(pos.x, pos.y); } catch (Exception ignored) {}
        toast("[accent]Rally point set");
    }

    // === Command panel navigation ===
    private boolean isElemVisible(Element el) {
        if (el == null) return false;
        try {
            if (elemVisField == null) {
                Class<?> c = el.getClass();
                while (c != null) {
                    try { elemVisField = c.getDeclaredField("visible"); elemVisField.setAccessible(true); break; }
                    catch (Exception e0) { c = c.getSuperclass(); }
                }
            }
            if (elemVisField != null) return elemVisField.getBoolean(el);
        } catch (Exception ignored) {}
        return true;
    }

    private Element findCmdHeaderLabel(Element el, String title) {
        if (!isElemVisible(el)) return null;
        try { if (title.equals(String.valueOf(((Label) el).getText()))) return el; } catch (Exception ignored) {}
        if (el instanceof Group g) {
            for (int i = 0; i < g.getChildren().size; i++) {
                Element found = findCmdHeaderLabel(g.getChildren().get(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Element findCmdPanelRoot() {
        try {
            String title = Core.bundle.get("commandmode.name");
            Element lbl = findCmdHeaderLabel(Vars.ui.hudGroup, title);
            if (lbl != null) {
                Element p = lbl.parent;
                while (p != null && p.parent != null && p.parent != Vars.ui.hudGroup) p = p.parent;
                return p;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void collectCmdButtons(Element el, List<CmdButton> out, float sx, float sy, boolean limitArea) {
        if (!isElemVisible(el)) return;
        if (el instanceof Button) {
            float w = el.getWidth() * sx, h = el.getHeight() * sy;
            if (w > 10 && h > 10) {
                Vec2 p = el.localToStageCoordinates(Tmp.v1.set(0, 0));
                float bx = p.x * sx, by = p.y * sy;
                if (!limitArea || (bx < Core.graphics.getWidth() * 0.45f && by < Core.graphics.getHeight() * 0.55f))
                    out.add(new CmdButton(el, bx, by, w, h));
            }
            return;
        }
        if (el instanceof Group g) {
            for (int i = 0; i < g.getChildren().size; i++) {
                collectCmdButtons(g.getChildren().get(i), out, sx, sy, limitArea);
            }
        }
    }

    public List<List<CmdButton>> cmdPanelData() {
        List<CmdButton> raw = new ArrayList<>();
        try {
            float sx = Core.graphics.getWidth() / Math.max(1f, Core.scene.getWidth());
            float sy = Core.graphics.getHeight() / Math.max(1f, Core.scene.getHeight());
            Element root = findCmdPanelRoot();
            if (root != null) collectCmdButtons(root, raw, sx, sy, false);
            else collectCmdButtons(Vars.ui.hudGroup, raw, sx, sy, true);
        } catch (Exception ignored) {}

        raw.sort((a, b2) -> Float.compare(b2.y, a.y));
        List<List<CmdButton>> rows = new ArrayList<>();
        for (CmdButton it : raw) {
            boolean placed = false;
            for (List<CmdButton> r : rows) {
                if (Math.abs(r.get(0).y - it.y) < 14) { r.add(it); placed = true; break; }
            }
            if (!placed) { List<CmdButton> nr = new ArrayList<>(); nr.add(it); rows.add(nr); }
        }
        for (List<CmdButton> r : rows) r.sort((a, b2) -> Float.compare(a.x, b2.x));
        return rows;
    }

    private void cmdPanelClamp(List<List<CmdButton>> rows) {
        if (rows.isEmpty()) { cmdRow = 0; cmdCol = 0; return; }
        cmdRow = Mathf.clamp(cmdRow, 0, rows.size() - 1);
        cmdCol = Mathf.clamp(cmdCol, 0, rows.get(cmdRow).size() - 1);
    }

    private void cmdPanelNav(List<List<CmdButton>> rows, int dpadX, int dpadY, float dt) {
        if (dpadCooldown > 0) dpadCooldown -= dt;
        if (dpadX == 0 && dpadY == 0) { dpadCooldown = 0; return; }
        if (dpadCooldown > 0 || rows.isEmpty()) return;
        dpadCooldown = 10;
        cmdPanelClamp(rows);
        if (dpadY != 0) cmdRow = Mathf.clamp(cmdRow - dpadY, 0, rows.size() - 1);
        cmdPanelClamp(rows);
        if (dpadX != 0) cmdCol = Mathf.clamp(cmdCol + dpadX, 0, rows.get(cmdRow).size() - 1);
    }

    public void cmdPanelActivate(Player player, Seq<Unit> sel, List<List<CmdButton>> rows) {
        cmdPanelClamp(rows);
        if (rows.isEmpty() || rows.get(cmdRow).isEmpty()) return;
        CmdButton f = rows.get(cmdRow).get(cmdCol);
        try {
            int cx = Math.round(f.x + f.w / 2f);
            int cy = Math.round(f.y + f.h / 2f);
            int sy = cfg.sceneFlipY ? (Core.graphics.getHeight() - 1 - cy) : cy;
            Core.scene.touchDown(cx, sy, 0, KeyCode.mouseLeft);
            Core.scene.touchUp(cx, sy, 0, KeyCode.mouseLeft);
            syncMousePosition();
        } catch (Exception e) { logOnce("cmd click", e); }
    }

    // === System Schematics Dialogs ===
    private void openSystemSaveDialog() {
        if (clipboard == null || clipboard.isEmpty()) {
            toast("[scarlet]Clipboard is empty");
            return;
        }
        try {
            Schematic schem = createSchematicFromClipboard();
            if (schem == null) return;
            Vars.control.input.lastSchematic = schem;

            searchMode = true;
            Vars.ui.showTextInput("@schematic.add", "@name", 1000, "", text -> {
                searchMode = false;
                if (text == null || text.trim().isEmpty()) return;
                Schematic found = Vars.schematics.all().find(s -> s.name().equals(text));
                if (found != null) {
                    Vars.ui.showConfirm("@confirm", "@schematic.replace", () -> {
                        Vars.schematics.overwrite(found, schem);
                        Vars.ui.showInfoFade("@schematic.saved");
                        Vars.ui.schematics.showInfo(found);
                    });
                } else {
                    schem.tags.put("name", text);
                    Vars.schematics.add(schem);
                    Vars.ui.showInfoFade("@schematic.saved");
                    Vars.ui.schematics.showInfo(schem);
                }
            });
        } catch (Exception e) {
            logOnce("openSystemSaveDialog", e);
            toast("[scarlet]Can't open save dialog: " + e);
        }
    }

    private void openSystemSchematicsDialog() {
        try {
            Vars.ui.schematics.show();
        } catch (Exception e) {
            logOnce("openSystemSchematicsDialog", e);
            toast("[scarlet]Can't open schematics: " + e);
        }
    }

    private Schematic createSchematicFromClipboard() {
        if (clipboard == null || clipboard.isEmpty()) return null;
        int minx = 1000000, miny = 1000000, maxx = -1000000, maxy = -1000000;
        for (ClipEntry c : clipboard) {
            int size = c.block.size;
            int top = size / 2;
            int bot = (size % 2 == 1) ? -(size / 2) : -((size - 1) / 2);
            minx = Math.min(minx, c.dx + bot); maxx = Math.max(maxx, c.dx + top);
            miny = Math.min(miny, c.dy + bot); maxy = Math.max(maxy, c.dy + top);
        }
        Seq<Stile> tiles = new Seq<>();
        for (ClipEntry q : clipboard) {
            tiles.add(new Stile(q.block, q.dx - minx, q.dy - miny, q.config, (byte)(q.rot & 3)));
        }
        return new Schematic(tiles, new arc.struct.StringMap(), maxx - minx + 1, maxy - miny + 1);
    }

    private void syncSystemSchematic() {
        Schematic cur = Vars.control.input.lastSchematic;
        if (cur != null && cur != lastSyncedSchematic) {
            lastSyncedSchematic = cur;
            loadSchematicIntoClipboard(cur);
        }
    }

    public void loadSchematicIntoClipboard(Schematic schem) {
        if (schem == null || schem.tiles == null || schem.tiles.isEmpty()) return;
        clipboard = new ArrayList<>();
        int cx = schem.width / 2;
        int cy = schem.height / 2;
        for (int i = 0; i < schem.tiles.size; i++) {
            Stile st = schem.tiles.get(i);
            clipboard.add(new ClipEntry(st.x - cx, st.y - cy, st.rotation, st.block, st.config));
        }
        pasteView = null;
        pasteLastTX = -9999;
        Vars.control.input.block = null;
        toast("[accent]Schematic in clipboard: [white]" + schem.name());
    }

    // === Main frame ===
    public void onFrame() {
        if (!active()) {
            if (uiMouseDown) updateMenuMouseClick(false);
            return;
        }
        float dt = Math.min(Core.graphics.getDeltaTime() * 60f, 4f);
        boolean inUI = Vars.state.isMenu() || Core.scene.hasDialog();

        if (inUI) {
            handleMenu(dt, false);
        } else {
            searchMode = false;
            handleGame(dt);
        }

        wasInUI = inUI;
        pad.savePrevState();
    }

    // === Data classes ===
    public static class LinePoint {
        public int x, y, rot;
        public boolean valid;
        public LinePoint(int x, int y, int rot, boolean valid) {
            this.x = x; this.y = y; this.rot = rot; this.valid = valid;
        }
    }

    public static class ClipEntry {
        public int dx, dy, rot;
        public Block block;
        public Object config;
        public ClipEntry(int dx, int dy, int rot, Block block, Object config) {
            this.dx = dx; this.dy = dy; this.rot = rot; this.block = block; this.config = config;
        }
    }

    public static class PasteEntry {
        public int x, y, rot;
        public Block block;
        public Object config;
        public boolean valid;
        public PasteEntry(int x, int y, int rot, Block block, Object config, boolean valid) {
            this.x = x; this.y = y; this.rot = rot; this.block = block; this.config = config; this.valid = valid;
        }
    }

    public static class CmdButton {
        public Element btn;
        public float x, y, w, h;
        public CmdButton(Element btn, float x, float y, float w, float h) {
            this.btn = btn; this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }
}
