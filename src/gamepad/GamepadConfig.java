package gamepad;

import arc.Core;
import arc.util.Log;

/**
 * Configuration persistence and key binding definitions.
 * All settings are stored in Mindustry's Core.settings with a prefix.
 */
public class GamepadConfig {
    private static final String PREFIX = "gpv2-";

    // --- Binding definitions ---
    // cat: 0=General, 1=Normal, 2=Build, 3=Command, 4=Menu, 5=Minimap
    public static final BindDef[] BIND_DEFS = {
        // General
        new BindDef(0, "Mode switching",       "GEN_MODE_NORMAL",  6,  "Switch to normal mode"),
        new BindDef(0, "Mode switching",       "GEN_MODE_BUILD",   5,  "Switch to build mode"),
        new BindDef(0, "Mode switching",       "GEN_MODE_COMMAND", 4,  "Switch to command mode"),
        new BindDef(0, "Camera and minimap",   "GEN_ZOOM_IN",      11, "Camera zoom in"),
        new BindDef(0, "Camera and minimap",   "GEN_ZOOM_OUT",     12, "Camera zoom out"),
        new BindDef(0, "Camera and minimap",   "GEN_RECENTER_CAM", 6,  "Recenter camera on player"),
        new BindDef(0, "Camera and minimap",   "GEN_MINIMAP_1",    11, "Open minimap (combo button 1)"),
        new BindDef(0, "Camera and minimap",   "GEN_MINIMAP_2",    12, "Open minimap (combo button 2)"),
        new BindDef(0, "System",               "GEN_PAUSE",        8,  "Tactical pause"),
        new BindDef(0, "System",               "GEN_MENU",         9,  "Open pause menu"),

        // Normal
        new BindDef(1, "Shooting and windows", "NRM_SHOOT",        7,  "Fire / shoot (hold)"),
        new BindDef(1, "Shooting and windows", "NRM_TAP_UI",       0,  "Click open UI panels in the world"),
        new BindDef(1, "Shooting and windows", "NRM_RESEARCH",     3,  "Open research tree"),
        new BindDef(1, "Shooting and windows", "NRM_SKIP_WAVE",    2,  "Skip wave"),
        new BindDef(1, "World interaction",    "NRM_CONFIG",       0,  "Open / press config of block under cursor"),
        new BindDef(1, "World interaction",    "NRM_INV",          0,  "Open inventory of block under cursor"),
        new BindDef(1, "World interaction",    "NRM_MINE",         0,  "Start / stop mining ore under cursor"),
        new BindDef(1, "Unit items",           "NRM_DROP_BUILD",   0,  "Give items to block under cursor (hold)"),
        new BindDef(1, "Unit items",           "NRM_DROP_GROUND",  0,  "Drop items on the ground at empty spot (hold)"),
        new BindDef(1, "Possess and respawn",  "NRM_POSSESS_UNIT", 1,  "Possess unit under cursor"),
        new BindDef(1, "Possess and respawn",  "NRM_POSSESS_BUILD",1,  "Possess block / turret / factory under cursor"),
        new BindDef(1, "Possess and respawn",  "NRM_RESPAWN_CORE", 1,  "Respawn at core (press on empty spot)"),

        // Build
        new BindDef(2, "Placing and breaking",    "BLD_PLACE",         7,  "Build block / line of blocks (hold)"),
        new BindDef(2, "Placing and breaking",    "BLD_PASTE",         7,  "Paste schematic from clipboard"),
        new BindDef(2, "Placing and breaking",    "BLD_REMOVE",        1,  "Deconstruct block / select area (hold)"),
        new BindDef(2, "Placing and breaking",    "BLD_CANCEL_PLACE",  1,  "Cancel line while dragging a build"),
        new BindDef(2, "Placing and breaking",    "BLD_CANCEL_REMOVE", 7,  "Cancel deconstruction while selecting area"),
        new BindDef(2, "Selection and rotation",  "BLD_SUB_CAT",       2,  "Switch d-pad to category select"),
        new BindDef(2, "Selection and rotation",  "BLD_SUB_BLOCK",     3,  "Switch d-pad to block select"),
        new BindDef(2, "Selection and rotation",  "BLD_ROTATE",        3,  "Rotate selected block / pasted schematic 90 degrees"),
        new BindDef(2, "Copy and cancel",         "BLD_CANCEL_ACTION", 0,  "Cancel build plans / clear clipboard"),
        new BindDef(2, "Copy and cancel",         "BLD_COPY_BLOCK",    0,  "Pipette: copy block under cursor"),
        new BindDef(2, "Copy and cancel",         "BLD_COPY_AREA",     0,  "Copy building area (hold and drag)"),
        new BindDef(2, "Copy and cancel",         "BLD_SAVE_1",        11, "Schematics: save from clipboard / open list (combo button 1)"),
        new BindDef(2, "Copy and cancel",         "BLD_SAVE_2",        12, "Schematics: save from clipboard / open list (combo button 2)"),

        // Command
        new BindDef(3, "Unit selection",   "CMD_SELECT_SINGLE", 7,  "Select / deselect unit or factory under cursor"),
        new BindDef(3, "Unit selection",   "CMD_SELECT_RECT",   7,  "Box-select units (hold and drag)"),
        new BindDef(3, "Unit selection",   "CMD_SELECT_ALL",    2,  "Select all available units"),
        new BindDef(3, "Unit selection",   "CMD_CLEAR_SEL",     1,  "Clear selection"),
        new BindDef(3, "Orders and panel", "CMD_ORDER_MOVE",    0,  "Order move to point (empty spot)"),
        new BindDef(3, "Orders and panel", "CMD_ORDER_TARGET",  0,  "Order attack enemy / building under cursor"),
        new BindDef(3, "Orders and panel", "CMD_PANEL_CLICK",   3,  "Press selected button on command panel"),

        // Menu
        new BindDef(4, "Menu control", "MNU_CLICK_1", 0,  "Click / drag sliders (main button)"),
        new BindDef(4, "Menu control", "MNU_CLICK_2", 7,  "Click / drag sliders (extra button)"),
        new BindDef(4, "Menu control", "MNU_BACK_1",  1,  "Back / close window (button 1)"),
        new BindDef(4, "Menu control", "MNU_BACK_2",  6,  "Back / close window (button 2)"),
        new BindDef(4, "Menu control", "MNU_BACK_3",  9,  "Back / close window (button 3)"),

        // Minimap
        new BindDef(5, "Minimap zoom",  "MAP_ZOOM_IN",       11, "Minimap zoom in"),
        new BindDef(5, "Minimap zoom",  "MAP_ZOOM_OUT",      12, "Minimap zoom out"),
        new BindDef(5, "Minimap close", "MAP_CLOSE_1",       1,  "Close minimap (button 1)"),
        new BindDef(5, "Minimap close", "MAP_CLOSE_2",       9,  "Close minimap (button 2)"),
        new BindDef(5, "Minimap close", "MAP_CLOSE_COMBO_1", 11, "Close minimap by combo (button 1)"),
        new BindDef(5, "Minimap close", "MAP_CLOSE_COMBO_2", 12, "Close minimap by combo (button 2)"),
    };

    public static final String[] PHYS_NAMES = {
        "Cross (X)", "Circle (○)", "Triangle (△)", "Square (□)",
        "L1", "R1", "L2", "R2", "Share", "Options", "PS", "L3", "R3"
    };

    public static final String[] TAB_NAMES = {
        "1. General", "2. Normal", "3. Build", "4. Command", "5. Menu", "6. Minimap"
    };

    private static final String[] CFG_KEYS = {
        "gp-enabled", "gp-cursor", "gp-pan", "gp-zoom", "gp-deadzone",
        "gp-hold", "gp-invzoom", "gp-flipy", "gp-scroll", "gp-scrollinv"
    };

    // --- Live config values ---
    public boolean enabled = true;
    public float cursorSpeed = 18f;
    public float camPanSpeed = 16f;
    public boolean edgePanEnabled = false;
    public float zoomSpeed = 0.05f;
    public float deadzone = 0.16f;
    public float holdFrames = 20f;
    public int zoomInSign = 1;
    public boolean sceneFlipY = false;
    public float scrollSpeed = 20f;
    public boolean scrollInvert = false;

    // --- Per-category Stick Inversion (0: General, 1: Normal, 2: Build, 3: Command, 4: Menu, 5: Minimap) ---
    public final boolean[] invLX = new boolean[6];
    public final boolean[] invLY = new boolean[6];
    public final boolean[] invRX = new boolean[6];
    public final boolean[] invRY = new boolean[6];

    public float getLX(float rawLX, int cat) {
        int c = Math.max(0, Math.min(cat, 5));
        boolean inv = invLX[c] ^ (c != 0 ? invLX[0] : false);
        return inv ? -rawLX : rawLX;
    }

    public float getLY(float rawLY, int cat) {
        int c = Math.max(0, Math.min(cat, 5));
        boolean inv = invLY[c] ^ (c != 0 ? invLY[0] : false);
        return inv ? -rawLY : rawLY;
    }

    public float getRX(float rawRX, int cat) {
        int c = Math.max(0, Math.min(cat, 5));
        boolean inv = invRX[c] ^ (c != 0 ? invRX[0] : false);
        return inv ? -rawRX : rawRX;
    }

    public float getRY(float rawRY, int cat) {
        int c = Math.max(0, Math.min(cat, 5));
        boolean inv = invRY[c] ^ (c != 0 ? invRY[0] : false);
        return inv ? -rawRY : rawRY;
    }

    // --- Bindings ---
    public final int[] bindings = new int[BIND_DEFS.length];

    public GamepadConfig() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        for (int i = 0; i < BIND_DEFS.length; i++) {
            bindings[i] = BIND_DEFS[i].def;
        }
    }

    /** Get binding value by key name. */
    public int bind(String key) {
        for (int i = 0; i < BIND_DEFS.length; i++) {
            if (BIND_DEFS[i].key.equals(key)) return bindings[i];
        }
        return 0;
    }

    // --- Settings persistence ---
    public static String cfgGet(String key) {
        try {
            String v = Core.settings.getString(PREFIX + key, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static int cfgInt(String key, int def) {
        try {
            String s = cfgGet(key);
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean cfgBool(String key, boolean def) {
        String s = cfgGet(key);
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        return def;
    }

    public static void cfgPut(String key, String v) {
        try {
            Core.settings.put(PREFIX + key, v);
        } catch (Exception e) {
            Log.err("[Gamepad] cfgPut error: " + e);
        }
    }

    public static void cfgRemove(String key) {
        try {
            Core.settings.remove(PREFIX + key);
        } catch (Exception e) { /* ignore */ }
    }

    public static void cfgFlush() {
        try {
            Core.settings.forceSave();
        } catch (Exception e) { /* ignore */ }
    }

    public static void saveInt(String key, int v) {
        cfgPut(key, String.valueOf(v));
    }

    public static void saveBool(String key, boolean v) {
        cfgPut(key, v ? "1" : "0");
    }

    public void load() {
        enabled = cfgBool("gp-enabled", true);
        cursorSpeed = cfgInt("gp-cursor", 18);
        camPanSpeed = cfgInt("gp-pan", 16);
        edgePanEnabled = false;
        zoomSpeed = cfgInt("gp-zoom", 50) / 1000f;
        deadzone = cfgInt("gp-deadzone", 16) / 100f;
        holdFrames = cfgInt("gp-hold", 20);
        zoomInSign = cfgBool("gp-invzoom", false) ? -1 : 1;
        sceneFlipY = cfgBool("gp-flipy", false);
        scrollSpeed = cfgInt("gp-scroll", 20);
        scrollInvert = cfgBool("gp-scrollinv", false);

        for (int c = 0; c < 6; c++) {
            invLX[c] = cfgBool("gp-inv-lx-" + c, false);
            invLY[c] = cfgBool("gp-inv-ly-" + c, false);
            invRX[c] = cfgBool("gp-inv-rx-" + c, false);
            invRY[c] = cfgBool("gp-inv-ry-" + c, false);
        }

        for (int i = 0; i < BIND_DEFS.length; i++) {
            bindings[i] = cfgInt("gp-bind-" + BIND_DEFS[i].key, BIND_DEFS[i].def);
        }

        // Migrate older configs where GEN_RECENTER_CAM was bound to 11 (same as GEN_ZOOM_IN)
        int zoomInIdx = -1, recenterIdx = -1;
        for (int i = 0; i < BIND_DEFS.length; i++) {
            if ("GEN_ZOOM_IN".equals(BIND_DEFS[i].key)) zoomInIdx = i;
            if ("GEN_RECENTER_CAM".equals(BIND_DEFS[i].key)) recenterIdx = i;
        }
        if (zoomInIdx >= 0 && recenterIdx >= 0 && bindings[recenterIdx] == bindings[zoomInIdx]) {
            bindings[recenterIdx] = 6;
            saveInt("gp-bind-GEN_RECENTER_CAM", 6);
        }
    }

    public void resetTab(int tab) {
        if (tab == 0) {
            for (String k : CFG_KEYS) cfgRemove(k);
        }
        if (tab >= 0 && tab < 6) {
            cfgRemove("gp-inv-lx-" + tab);
            cfgRemove("gp-inv-ly-" + tab);
            cfgRemove("gp-inv-rx-" + tab);
            cfgRemove("gp-inv-ry-" + tab);
        }
        for (BindDef d : BIND_DEFS) {
            if (d.cat == tab) cfgRemove("gp-bind-" + d.key);
        }
        load();
        cfgFlush();
    }

    public void resetAll() {
        for (String k : CFG_KEYS) cfgRemove(k);
        for (int c = 0; c < 6; c++) {
            cfgRemove("gp-inv-lx-" + c);
            cfgRemove("gp-inv-ly-" + c);
            cfgRemove("gp-inv-rx-" + c);
            cfgRemove("gp-inv-ry-" + c);
        }
        for (BindDef d : BIND_DEFS) cfgRemove("gp-bind-" + d.key);
        load();
        cfgFlush();
    }

    public String physName(int i) {
        String n = (i >= 0 && i < PHYS_NAMES.length) ? PHYS_NAMES[i] : "Button";
        return "#" + i + " " + n;
    }

    // --- BindDef data class ---
    public static class BindDef {
        public final int cat;
        public final String sub;
        public final String key;
        public final int def;
        public final String label;

        public BindDef(int cat, String sub, String key, int def, String label) {
            this.cat = cat;
            this.sub = sub;
            this.key = key;
            this.def = def;
            this.label = label;
        }
    }
}
