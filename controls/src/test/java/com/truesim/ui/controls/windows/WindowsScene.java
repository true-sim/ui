package com.truesim.ui.controls.windows;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiFrameHook;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.testkit.SyntheticInput;

/**
 * A representative scene of overlays, windows and docking for screenshots, goldens and benchmarks: an editor-like dock
 * space (hierarchy, scene and game tabs over a console, inspector) filling the screen, two floating windows (one
 * active), a tooltip at a toolbar button, a click popover open from another, and a tab being
 * dragged over the console pane so the drop preview and zone guides show.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * WindowsScene scene = WindowsScene.build(ui, 1280, 720);   // runs the frames that get it there
 * renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), 1280, 720);
 * }</pre>
 */
public final class WindowsScene {

    public final Ui ui;
    public final Overlays overlays;
    public final WindowManager windows;
    public final DockSpace dock;
    public final Tooltips tooltips;
    public final Popovers popovers;
    public final FloatingWindow inspectorWindow;
    public final FloatingWindow statsWindow;
    public final Popover popover;
    public final Element toolbar;
    public final UiInput input = new UiInput(32);
    public final SyntheticInput synthetic = new SyntheticInput();
    public long nanos = 1_000_000_000L;
    private final float width;
    private final float height;

    private WindowsScene(Ui ui, float width, float height) {
        this.ui = ui;
        this.width = width;
        this.height = height;
        overlays = new Overlays(ui);
        windows = new WindowManager(overlays);
        dock = new DockSpace(overlays, windows);
        tooltips = new Tooltips(overlays);
        popovers = new Popovers(overlays);

        // the HUD: a toolbar across the top, the dock space below it
        toolbar = ui.panel("card");
        toolbar.semantics(Role.TOOLBAR, null);
        Element save = button("Save");
        Element options = button("Options");
        toolbar.add(save).add(options);
        ui.root().add(toolbar).add(dock.root());
        ui.addFrameHook(new Place(toolbar, dock.root(), height));
        tooltips.text(save, "Save the scene (Ctrl+S)");
        Element popContent = ui.panel(null);
        popContent.add(ui.label("title", "Options")).add(ui.label("caption", "Grid snapping: 0.25 m"));
        popover = popovers.click(options, popContent, "Options");

        panel("scene", "Scene");
        panel("game", "Game");
        panel("hierarchy", "Hierarchy");
        panel("inspector", "Inspector");
        panel("console", "Console");
        panel("profiler", "Profiler");
        dock.restore(DockLayout.parse("dock split(row; 0.2 0.55 0.25; tabs(0; hierarchy) split(column; 0.65 0.35; "
            + "tabs(0; scene game) tabs(0; console profiler)) tabs(0; inspector))"));

        inspectorWindow = windows.create("material", "Material").bounds(960, 300, 280, 220);
        inspectorWindow.content().add(ui.label(null, "Roughness 0.4")).add(ui.label(null, "Metallic 1.0"));
        inspectorWindow.icon("caption");
        statsWindow = windows.create("stats", "Statistics").bounds(420, 380, 260, 160);
        statsWindow.content().add(ui.label("value", "144 fps"));
        statsWindow.open();
        inspectorWindow.open();
    }

    private Element button(String text) {
        Element b = ui.panel("primary");
        b.add(ui.label(null, text));
        b.semantics(Role.BUTTON, text);
        ui.interaction().focusable(b, true);
        return b;
    }

    private DockPanel panel(String id, String title) {
        DockPanel p = dock.panel(id, title);
        p.content().add(ui.label(null, title)).add(ui.label("caption", "the " + id + " panel"));
        return p;
    }

    /** Builds the scene in {@code ui} ({@code width x height} UI pixels) and runs the frames that bring it there. */
    public static WindowsScene build(Ui ui, float width, float height) {
        WindowsScene s = new WindowsScene(ui, width, height);
        s.frames(3);
        // a tab dragged over the console pane: the drop preview and the zone guides show
        DockPanel game = s.dock.find("game");
        int label = game.tabLabel.slot(); // on the title, not the close button
        float tx = s.ui.layout().x(label) + s.ui.layout().width(label) / 2;
        float ty = s.ui.layout().y(label) + s.ui.layout().height(label) / 2;
        Element console = s.dock.paneOf(s.dock.find("console"));
        float cx = s.ui.layout().x(console.slot()) + s.ui.layout().width(console.slot()) / 2;
        float cy = s.ui.layout().y(console.slot()) + s.ui.layout().height(console.slot()) / 2;
        s.mouse(tx, ty, Pointer.PRIMARY);
        for (int i = 1; i <= 4; i++) {
            s.mouse(tx + (cx - tx) * i / 4, ty + (cy - ty) * i / 4, Pointer.PRIMARY);
        }
        // after the press that started the drag (a press outside would close the popover)
        Element save = s.ui.store().element(s.ui.store().firstChild(s.toolbar.slot()));
        s.tooltips.show(save);
        s.popover.open();
        s.frames(2);
        return s;
    }

    /** Runs {@code n} frames with the input as it is. */
    public WindowsScene frames(int n) {
        for (int i = 0; i < n; i++) {
            input.beginFrame();
            synthetic.poll(input);
            ui.update(width, height, input, nanos);
            nanos += 16_666_667L;
        }
        return this;
    }

    /** Moves the mouse with {@code buttons} held and runs a frame. */
    public WindowsScene mouse(float x, float y, int buttons) {
        synthetic.mouse(x, y, buttons);
        return frames(1);
    }

    /** Keeps the toolbar across the top and the dock space under it, filling the screen. */
    private record Place(Element toolbar, Element dock, float height) implements UiFrameHook {
        @Override
        public void beforeLayout(Ui ui) {
            int root = ui.store().rootSlot();
            ui.layout().kind(root, LayoutKind.FLEX).padding(root, 0, 0, 0, 0);
            ui.layout().direction(toolbar.slot(), com.truesim.ui.layout.Direction.ROW);
            ui.layout().grow(dock.slot(), 1).width(dock.slot(), 100, Unit.PERCENT);
        }
    }
}
