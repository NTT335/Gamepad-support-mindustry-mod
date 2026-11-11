package gamepad;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Iconc;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;
import mindustry.world.Block;

import java.util.List;

/**
 * World overlays and UI cursor rendering for gamepad control.
 */
public class GamepadRenderer {
    private static final int TS = 8;
    private final GamepadInput input;

    public GamepadRenderer(GamepadInput input) {
        this.input = input;
    }

    public void drawWorld() {
        try {
            if (!input.active() || !Vars.state.isGame()) return;
            Vars.control.input.commandMode = (input.currentMode == GamepadInput.MODE_COMMAND);

            if (input.currentMode == GamepadInput.MODE_BUILD) {
                Draw.z(Layer.overlayUI);
                Block pb = Vars.control.input.block;

                if (input.placing && input.linePoints != null && pb != null) {
                    for (GamepadInput.LinePoint p : input.linePoints) {
                        float cx = p.x * TS + pb.offset;
                        float cy = p.y * TS + pb.offset;
                        Draw.color(p.valid ? Color.white : Pal.remove, 0.65f);
                        Draw.rect(pb.uiIcon, cx, cy, pb.size * TS, pb.size * TS, pb.rotate ? p.rot * 90 : 0);
                        Lines.stroke(1f, p.valid ? Pal.accent : Pal.remove);
                        Lines.square(cx, cy, pb.size * TS / 2f);
                    }
                }

                if (input.clipboard != null && input.pasteView != null) {
                    for (GamepadInput.PasteEntry pp : input.pasteView) {
                        float pcx = pp.x * TS + pp.block.offset;
                        float pcy = pp.y * TS + pp.block.offset;
                        Draw.color(pp.valid ? Color.white : Pal.remove, 0.6f);
                        Draw.rect(pp.block.uiIcon, pcx, pcy, pp.block.size * TS, pp.block.size * TS, pp.block.rotate ? pp.rot * 90 : 0);
                        Lines.stroke(1f, pp.valid ? Pal.accent : Pal.remove);
                        Lines.square(pcx, pcy, pp.block.size * TS / 2f);
                    }
                }

                if (input.copying) {
                    float cx0 = Math.min(input.copyStartX, input.copyEndX) * TS - TS / 2f;
                    float cy0 = Math.min(input.copyStartY, input.copyEndY) * TS - TS / 2f;
                    Lines.stroke(1.5f, Pal.heal);
                    Lines.rect(cx0, cy0, (Math.abs(input.copyEndX - input.copyStartX) + 1) * TS, (Math.abs(input.copyEndY - input.copyStartY) + 1) * TS);
                }

                if (input.removing) {
                    float rx = Math.min(input.breakStartX, input.breakEndX) * TS - TS / 2f;
                    float ry = Math.min(input.breakStartY, input.breakEndY) * TS - TS / 2f;
                    Lines.stroke(1.5f, Pal.remove);
                    Lines.rect(rx, ry, (Math.abs(input.breakEndX - input.breakStartX) + 1) * TS, (Math.abs(input.breakEndY - input.breakStartY) + 1) * TS);
                }

                if (input.rebuilding) {
                    float rx = Math.min(input.rebuildStartX, input.rebuildEndX) * TS - TS / 2f;
                    float ry = Math.min(input.rebuildStartY, input.rebuildEndY) * TS - TS / 2f;
                    float rw = (Math.abs(input.rebuildEndX - input.rebuildStartX) + 1) * TS;
                    float rh = (Math.abs(input.rebuildEndY - input.rebuildStartY) + 1) * TS;
                    Draw.color(Pal.heal, 0.22f);
                    Fill.rect(rx + rw / 2f, ry + rh / 2f, rw, rh);
                    Lines.stroke(1.5f, Pal.heal);
                    Lines.rect(rx, ry, rw, rh);
                }
                Draw.reset();
            }

            if (input.currentMode == GamepadInput.MODE_COMMAND) {
                Seq<Building> cbs = Vars.control.input.commandBuildings;
                if (cbs != null && cbs.size > 0) {
                    Draw.z(Layer.overlayUI);
                    Lines.stroke(1.5f, Pal.heal);
                    for (int qi = 0; qi < cbs.size; qi++) {
                        Building qb = cbs.get(qi);
                        if (qb != null && qb.isValid()) {
                            Lines.square(qb.x, qb.y, qb.block.size * TS / 2f + 1f);
                        }
                    }
                    Draw.reset();
                }

                if (input.cmdSelecting) {
                    Vec2 wp = Core.camera.unproject(Tmp.v1.set(input.cursorX, input.cursorY));
                    Draw.z(Layer.overlayUI);
                    Lines.stroke(1.5f, Pal.heal);
                    Lines.rect(
                        Math.min(input.cmdStartX, wp.x),
                        Math.min(input.cmdStartY, wp.y),
                        Math.abs(wp.x - input.cmdStartX),
                        Math.abs(wp.y - input.cmdStartY)
                    );
                    Draw.reset();
                }
            }
        } catch (Exception e) {
            Log.err("[GamepadRenderer] drawWorld error: " + e);
        }
    }

    private void drawCommandPanel() {
        List<List<GamepadInput.CmdButton>> rows = input.cmdPanelData();
        if (rows.isEmpty()) return;
        int r = Math.max(0, Math.min(input.cmdRow, rows.size() - 1));
        if (rows.get(r).isEmpty()) return;
        int c = Math.max(0, Math.min(input.cmdCol, rows.get(r).size() - 1));
        GamepadInput.CmdButton focused = rows.get(r).get(c);

        Lines.stroke(4f, Color.black);
        Lines.rect(focused.x - 3f, focused.y - 3f, focused.w + 6f, focused.h + 6f);
        Lines.stroke(2f, Color.white);
        Lines.rect(focused.x - 3f, focused.y - 3f, focused.w + 6f, focused.h + 6f);
        Draw.reset();
    }

    public void drawUI() {
        try {
            if (!input.active() || !input.cursorReady) return;
            if (Vars.ui.minimapfrag != null && Vars.ui.minimapfrag.shown()) return;

            float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
            Draw.proj(Tmp.m1.setOrtho(0, 0, w, h));

            boolean inUI = Vars.state.isMenu() || Core.scene.hasDialog();
            Color col = inUI ? Color.white :
                (input.currentMode == GamepadInput.MODE_NORMAL) ? Pal.accent :
                (input.currentMode == GamepadInput.MODE_BUILD) ? Pal.place : Pal.heal;

            if (!inUI && Vars.state.isGame() && input.currentMode == GamepadInput.MODE_COMMAND) {
                drawCommandPanel();
            }

            // Virtual crosshair cursor
            float cx = input.cursorX, cy = input.cursorY;
            Lines.stroke(4f, Color.black);
            Lines.circle(cx, cy, 6f);
            Lines.stroke(2f, col);
            Lines.circle(cx, cy, 6f);

            Lines.line(cx - 12f, cy, cx - 4f, cy);
            Lines.line(cx + 4f, cy, cx + 12f, cy);
            Lines.line(cx, cy - 12f, cx, cy - 4f);
            Lines.line(cx, cy + 4f, cx, cy + 12f);
            Draw.reset();

            // Status label above cursor
            if (inUI && input.searchMode) {
                Fonts.outline.draw("[accent]SEARCH MODE", cx, cy + 30f, Align.center);
                Fonts.outline.draw("[lightgray]Share: exit search", cx, cy + 18f, Align.center);
                Draw.reset();
            } else if (!inUI && Vars.state.isGame()) {
                String l1;
                String l2 = null;

                if (input.currentMode == GamepadInput.MODE_NORMAL) {
                    if (input.cameraDetached) {
                        l1 = "[accent]NORMAL [#ff79c6](FREE CAM)[]";
                        l2 = "[lightgray]L2: Snap to player";
                    } else {
                        l1 = "[accent]NORMAL";
                    }
                } else if (input.currentMode == GamepadInput.MODE_BUILD) {
                    Block blk = Vars.control.input.block;
                    if (input.rebuilding) {
                        l1 = "[#84f490]REBUILD / REPAIR DERELICTS";
                        l2 = "[white]Release R2 to commit repairs in area";
                    } else if (blk == null && input.clipboard == null) {
                        l1 = "[#9aa8ff]BUILD [lightgray](Empty cursor)";
                        l2 = "[#84f490]R2: Repair Derelicts & Broken Plans";
                    } else {
                        String[] dirs = {"" + Iconc.right, "" + Iconc.up, "" + Iconc.left, "" + Iconc.down};
                        int rot = Vars.control.input.rotation;
                        String dirStr = (rot >= 0 && rot < 4) ? dirs[rot] : "";
                        l1 = "[#9aa8ff]BUILD " + ("category".equals(input.buildSubMode) ? "[△ category]" : "[□ block]");
                        String catName = input.selectedCatIndex < mindustry.type.Category.all.length ?
                            mindustry.type.Category.all[input.selectedCatIndex].name() : "";
                        l2 = "[lightgray]" + catName + ": [white]" + (blk != null ? blk.localizedName + " " + dirStr : "—");
                    }
                } else {
                    Seq<Building> cbn = Vars.control.input.commandBuildings;
                    int bldCount = (cbn != null) ? cbn.size : 0;
                    l1 = "[green]COMMAND (" + Vars.control.input.selectedUnits.size + (bldCount > 0 ? " + " + bldCount + " bld" : "") + ")";
                }

                Fonts.outline.draw(l1, cx, cy + (l2 != null ? 42f : 30f), Align.center);
                if (l2 != null) {
                    Fonts.outline.draw(l2, cx, cy + 28f, Align.center);
                }
                Draw.reset();
            }
            Draw.flush();
        } catch (Exception e) {
            Log.err("[GamepadRenderer] drawUI error: " + e);
        }
    }
}
