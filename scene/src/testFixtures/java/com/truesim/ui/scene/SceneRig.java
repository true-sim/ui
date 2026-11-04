package com.truesim.ui.scene;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SceneLayer;
import com.truesim.scene.core.SpatialIndex;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.TextSystem;

/**
 * A UI over a scene for tests, galleries and benchmarks: a {@link SceneGraph} with its {@link SpatialIndex}, a
 * {@link ScenePicking} with a main view looking down -Z from the origin over the whole screen, synthetic pointers, and
 * XR pointers with recorded rays. {@link #frame()} runs one frame as an engine does: scene and index updated, input,
 * update.
 */
public final class SceneRig {

    /** The layer interactive nodes are on. */
    public static final SceneLayer INTERACTIVE = SceneLayer.of("interactive", 5);

    public final Ui ui;
    public final ChangeClock clock = new ChangeClock();
    public final SceneGraph scene;
    public final SpatialIndex index;
    public final ScenePicking picking;
    public final SceneView view = new SceneView();
    public final SyntheticInput pointers = new SyntheticInput();
    public final UiInput input = new UiInput(16);
    public final float width;
    public final float height;
    public long nanos = 1_000_000_000L;
    private final int[] rayIds = new int[UiInput.MAX_POINTERS];
    private final double[][] rays = new double[UiInput.MAX_POINTERS][6];
    private final int[] rayButtons = new int[UiInput.MAX_POINTERS];
    private int rayCount;

    /** A rig of {@code width x height} UI pixels styled by {@code sheet}, with {@code text} (or null). */
    public SceneRig(StyleSheet sheet, TextSystem text, float width, float height) {
        this.ui = new Ui(sheet, text);
        this.scene = new SceneGraph(clock);
        this.index = new SpatialIndex(scene);
        this.width = width;
        this.height = height;
        view.perspective(Math.toRadians(60), 0.1, 10_000).viewport(0, 0, width, height);
        this.picking = new ScenePicking(ui, scene, index, INTERACTIVE).mainView(view);
    }

    /** A node with a box of half size {@code half} at {@code x, y, z}. */
    public long box(double x, double y, double z, double half, Mobility mobility) {
        long node = scene.create();
        scene.setLocalBounds(node, -half, -half, -half, half, half, half);
        scene.setTranslation(node, x, y, z);
        scene.setMobility(node, mobility);
        return node;
    }

    /** Sets (or moves) XR pointer {@code id}'s ray and buttons for the next frames. */
    public SceneRig ray(int id, double ox, double oy, double oz, double dx, double dy, double dz, int buttons) {
        int i = 0;
        while (i < rayCount && rayIds[i] != id) {
            i++;
        }
        if (i == rayCount) {
            rayIds[rayCount++] = id;
        }
        double[] r = rays[i];
        r[0] = ox;
        r[1] = oy;
        r[2] = oz;
        r[3] = dx;
        r[4] = dy;
        r[5] = dz;
        rayButtons[i] = buttons;
        return this;
    }

    /** Takes XR pointer {@code id} away. */
    public SceneRig dropRay(int id) {
        for (int i = 0; i < rayCount; i++) {
            if (rayIds[i] == id) {
                rayIds[i] = rayIds[--rayCount];
                rays[i] = rays[rayCount];
                rays[rayCount] = new double[6];
                rayButtons[i] = rayButtons[rayCount];
                input.removePointer(id);
                return this;
            }
        }
        return this;
    }

    /** One frame, 16 ms on: the scene and index updated, input picked, the UI updated. */
    public SceneRig frame() {
        scene.update();
        index.update();
        input.beginFrame();
        pointers.poll(input);
        for (int i = 0; i < rayCount; i++) {
            double[] r = rays[i];
            input.pointer(rayIds[i], Pointer.Kind.XR_RAY).ray(r[0], r[1], r[2], r[3], r[4], r[5])
                .buttons(rayButtons[i]);
        }
        nanos += 16_000_000L;
        picking.input(input, nanos);
        picking.update(width, height);
        return this;
    }

    /** {@code n} frames. */
    public SceneRig frames(int n) {
        for (int i = 0; i < n; i++) {
            frame();
        }
        return this;
    }

    /** Moves the mouse to {@code x, y} with {@code buttons} and runs a frame. */
    public SceneRig mouse(float x, float y, int buttons) {
        pointers.mouse(x, y, buttons);
        return frame();
    }

    /** A click of the mouse at {@code x, y}: press and release, a frame each. */
    public SceneRig click(float x, float y) {
        return mouse(x, y, Pointer.PRIMARY).mouse(x, y, 0);
    }

    /** Places {@code element} at {@code x, y} with size {@code w x h} in UI pixels, in code (anchors layout). */
    public static void place(Ui ui, com.truesim.ui.core.Element element, float x, float y, float w, float h) {
        ui.codeLayout(element, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        int slot = element.slot();
        ui.layout().anchors(slot, 0, 0, 0, 0).offsets(slot, x, y, 0, 0)
            .width(slot, w, com.truesim.ui.layout.Unit.PX).height(slot, h, com.truesim.ui.layout.Unit.PX);
    }
}
