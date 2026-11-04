package com.truesim.ui.controls.imm;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.testkit.SyntheticInput;

/**
 * A small tool written with the immediate-mode façade on one screen (1280 x 720): a spawner window (text, a slider,
 * a drag, a checkbox, a text field, a combo, buttons on one line, a separator) and a scene window (an open tree of
 * selectable nodes, a table, two groups side by side). Its {@link #frame} runs the tool's calls between the UI's input
 * and update, as a game would. For the façade's tests and the engine's GPU screenshots.
 *
 * <pre>{@code
 * ImmGallery g = ImmGallery.build(ui, 1280, 720);  // runs frames until it settles
 * g.frames(1);                                     // one more frame of the tool
 * }</pre>
 */
public final class ImmGallery {

    private static final String[] KINDS = {"Orc", "Elf", "Ogre", "Wisp"};
    private static final String[] NODES = {"Camera", "Sun", "Terrain", "Player", "Rock 1", "Rock 2"};

    /** The UI. */
    public final Ui ui;
    /** The façade. */
    public final Imm imm;
    /** The synthetic input the frames use. */
    public final SyntheticInput input = new SyntheticInput();
    private final UiInput frameInput = new UiInput(16);
    private final float width;
    private final float height;
    private long nanos = 1_000_000_000L;

    // the tool's state
    /** How many to spawn. */
    public int count = 12;
    /** The spawn radius. */
    public float radius = 7.5f;
    /** The kind's index. */
    public int kind = 1;
    /** Whether spawning is in waves. */
    public boolean waves = true;
    /** The squad's name. */
    public final StringBuilder name = new StringBuilder("Northern squad");
    /** Times Spawn was pressed. */
    public int spawned;
    /** The selected node. */
    public int selected = 3;
    /** Frames run. */
    public long frames;
    private final StringBuilder status = new StringBuilder(64);
    private final StringBuilder cell = new StringBuilder(16);

    private ImmGallery(Ui ui, float width, float height) {
        this.ui = ui;
        this.imm = new Imm(ui);
        this.width = width;
        this.height = height;
    }

    /**
     * The gallery over {@code ui} as a live UI runs it: no frames run; call {@link #tool()} in each frame between the
     * UI's input and its update.
     */
    public static ImmGallery live(Ui ui) {
        return new ImmGallery(ui, 0, 0);
    }

    /** Builds the gallery into {@code ui} and runs frames until it settles. */
    public static ImmGallery build(Ui ui, float width, float height) {
        ImmGallery g = new ImmGallery(ui, width, height);
        g.frames(60);
        return g;
    }

    /** The tool: one frame of calls. */
    public void tool() {
        imm.begin("Spawner", 40, 40);
        status.setLength(0);
        status.append("Spawned ").append(spawned).append(" waves");
        imm.text(status);
        count = imm.sliderInt("Count", count, 1, 100);
        radius = imm.drag("Radius", radius, 0.1f, 0, 50);
        waves = imm.checkbox("Waves", waves);
        imm.inputText("Squad", name);
        kind = imm.combo("Kind", kind, KINDS);
        imm.separator();
        if (imm.button("Spawn")) {
            spawned++;
        }
        imm.sameLine();
        if (imm.button("Clear")) {
            spawned = 0;
        }
        imm.sameLine();
        imm.text("ready", "imm-label");
        imm.end();

        imm.begin("Scene", 460, 40);
        if (imm.treeNode(1, "World", true)) {
            for (int i = 0; i < NODES.length; i++) {
                if (imm.selectable(i, NODES[i], i == selected)) {
                    selected = i;
                }
            }
            imm.treePop();
        }
        if (imm.treeNode("Lights")) {
            imm.text("Sun");
            imm.treePop();
        }
        imm.beginTable("stats", 3);
        imm.tableNextColumn();
        imm.text("Pass", "imm-label");
        imm.tableNextColumn();
        imm.text("GPU ms", "imm-label");
        imm.tableNextColumn();
        imm.text("Draws", "imm-label");
        for (int r = 0; r < 3; r++) {
            imm.tableNextRow();
            imm.tableNextColumn();
            imm.text(r == 0 ? "Shadows" : r == 1 ? "Opaque" : "Post");
            imm.tableNextColumn();
            cell.setLength(0);
            Imm.number(cell, 0.42 + r * 0.31, 2);
            imm.text(cell);
            imm.tableNextColumn();
            cell.setLength(0);
            cell.append(120 * (r + 1));
            imm.text(cell);
        }
        imm.endTable();
        imm.beginGroup();
        imm.labelText("Nodes", "1,204");
        imm.labelText("Lights", "3");
        imm.endGroup();
        imm.sameLine();
        imm.beginGroup();
        imm.pushId("second");
        imm.labelText("Nodes", "88");
        imm.popId();
        imm.labelText("Meshes", "41");
        imm.endGroup();
        imm.end();
    }

    /** Runs {@code n} frames of 16 ms: input, the tool's calls, the update. */
    public ImmGallery frames(int n) {
        for (int i = 0; i < n; i++) {
            frameInput.beginFrame();
            input.poll(frameInput);
            ui.input(frameInput, nanos);
            tool();
            ui.update(width, height);
            nanos += 16_000_000L;
            frames++;
        }
        return this;
    }
}
