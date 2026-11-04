package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.HostBox;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.controls.overlay.Viewports;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Role;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import java.util.Arrays;
import java.util.List;

/**
 * Docking into OS windows: a {@link DockHost} whose undocked panels go into dock areas of their own, one OS window
 * each ({@link OsWindows}: an engine's, or a fake in tests). A tab dragged beyond every window tears out into a new
 * window at the pointer, which follows the pointer until released; its tab dragged over a dock space of any window
 * docks it there (the window closes when its last panel leaves); more tabs dock into it, split and tab as in the main
 * area; closing it re-docks its panels where they last were in the main area. The layout saves each window's monitor,
 * place, size, maximised state, paper white and tree ({@link DockLayout.Window}); restored on a missing monitor, a
 * window goes to the primary one, kept inside its usable area.
 *
 * <p><b>Paper white per window.</b> Each window, the main one too, may have a paper white of its own
 * ({@link #paperWhite(int, float)}: a window on a dimmer or brighter HDR display); without one it follows the one
 * every window follows (the engine's {@code display.paperWhite} setting). The layout saves it beside the window's
 * place ({@code main paper-white} for the main window).
 *
 * <p><b>Translucent while dragged.</b> A window following a drag is drawn at its {@code dock-window}'s
 * {@code drag-opacity} ({@link WindowStyles#DRAG_OPACITY}) through {@link OsWindows#opacity}, so the dock targets
 * under it show; opaque again on drop. Where the system refuses, it stays opaque.
 *
 * <p><b>One UI, several windows.</b> Every window shows a region of the same {@link Ui}: its content (a
 * {@code dock-window} holding the area's {@code dock-space}) lies on a layer of its own ({@code dock-window-<n>}, z
 * {@link #LAYER_Z} + n) far right of the main window's region, at {@link Window#originX()} (UI pixels), sized to the
 * window. So style sheet, tokens, text, atlas, focus, commands and drag and drop are shared, and a tab drags between
 * windows like between panes. The engine draws each window's region into it ({@link Window#originX()},
 * {@link Window#width()}...) and maps the mouse into the region of the window under it ({@link #toUi}); a window's
 * UI pixels are scaled to its monitor ({@link Window#uiPerScreen()}), so content keeps its size in points on monitors
 * of other scales. Anchored overlays (pop-ups, tooltips) are kept inside the region of their anchor's window.
 *
 * <pre>{@code
 * DockSpace dock = new DockSpace(overlays, null);
 * OsDockHost host = new OsDockHost(overlays, dock, osWindows); // dock.host() is it now
 * // engine, each frame: mouse positions through host.toUi(screenX, screenY, out); a window's close button:
 * // host.closeRequested(id); OS focus: host.focused(id); draw window i's region into it
 * }</pre>
 *
 * <p>Per frame it reads each window's bounds and moves the window following a drag; it allocates nothing but when a
 * window opens or closes, or a layout is saved (once a moved or resized window has kept still for a few frames).
 */
public final class OsDockHost implements DockHost, Ui.FrameListener, Viewports {

    /** Where the first window's region starts, in UI pixels right of the main window's. */
    public static final float ORIGIN_X = 65_536;
    /** How far apart windows' regions are: the widest window, in UI pixels. */
    public static final float STRIDE = 16_384;
    /** The z of the first window's layer: over the HUD, under overlays and modal dialogs. */
    public static final int LAYER_Z = 50;
    /** Frames a moved or resized window keeps still before the layout is saved. */
    static final int SAVE_AFTER = 20;

    /** One OS window: its id, the area it holds, its region of the UI and its place on the screen. */
    public static final class Window {
        final int slot;
        final Element root;
        DockArea area;
        int id;
        String title;
        float originX;
        float originY;
        float width;
        float height;
        int x;
        int y;
        int w;
        int h;
        float uiPerScreen = 1;
        float laidScale = -1;
        float paperWhite = Float.NaN;
        float opacity = 1;

        Window(int slot, Element root) {
            this.slot = slot;
            this.root = root;
            this.originX = ORIGIN_X + slot * STRIDE;
        }

        /** The OS window's id ({@link OsWindows}). */
        public int id() {
            return id;
        }

        /** The dock area it holds. */
        public DockArea area() {
            return area;
        }

        /** Its {@code dock-window} element, holding the area. */
        public Element root() {
            return root;
        }

        /** The left of its region of the UI, in UI pixels. */
        public float originX() {
            return originX;
        }

        /** The top of its region of the UI, in UI pixels. */
        public float originY() {
            return originY;
        }

        /** Its region's width in UI pixels: the window's width times {@link #uiPerScreen()}. */
        public float width() {
            return width;
        }

        /** Its region's height in UI pixels. */
        public float height() {
            return height;
        }

        /** Its left on the screen. */
        public int x() {
            return x;
        }

        /** Its top on the screen. */
        public int y() {
            return y;
        }

        /** Its width on the screen. */
        public int screenWidth() {
            return w;
        }

        /** Its height on the screen. */
        public int screenHeight() {
            return h;
        }

        /** UI pixels per screen coordinate in it: its pixel density times the main window's scale over its own. */
        public float uiPerScreen() {
            return uiPerScreen;
        }

        /** Its own paper white in nits, or NaN: it follows the one every window follows. */
        public float paperWhite() {
            return paperWhite;
        }

        /** The opacity it was last asked to show at: below 1 while it follows a drag. */
        public float opacity() {
            return opacity;
        }

        @Override
        public String toString() {
            return "OsDockHost.Window[" + (area == null ? "-" : area.id()) + " #" + id + "]";
        }
    }

    private final Ui ui;
    private final Layout layout;
    private final DockSpace space;
    private final OsWindows os;
    private Window[] slots = new Window[0];
    private Layer[] layers = new Layer[0];
    private Window[] order = new Window[0]; // front to back; MAIN_WINDOW stands for the main window
    private int orderCount;
    private final Window mainWindow = new Window(-1, null);
    private Window following;
    private float grabX;
    private float grabY;
    private final int[] rect = new int[4];
    private final float[] cursor = new float[2];
    private int ids;
    private boolean unsaved;
    private long changedFrame;

    /**
     * Docking of {@code space} into OS windows of {@code os}: becomes {@code space}'s host, keeps {@code overlays}'
     * anchored overlays inside their window's region, and runs in every frame of their UI (after the space).
     */
    @AllowAllocation("one per dock space")
    public OsDockHost(Overlays overlays, DockSpace space, OsWindows os) {
        this.ui = overlays.ui();
        this.layout = ui.layout();
        this.space = space;
        this.os = os;
        mainWindow.originX = 0;
        mainWindow.id = OsWindows.MAIN;
        order = new Window[] {mainWindow};
        orderCount = 1;
        space.host(this);
        overlays.viewports(this);
        ui.addFrameListener(this);
        refreshMain();
    }

    /** The dock space. */
    public DockSpace space() {
        return space;
    }

    /** The OS windows. */
    public OsWindows os() {
        return os;
    }

    /** How many windows are open. */
    public int windowCount() {
        int n = 0;
        for (Window w : slots) {
            n += w == null ? 0 : 1;
        }
        return n;
    }

    /** How many window slots there are: {@link #windowAt} is null for a free one. */
    public int slotCount() {
        return slots.length;
    }

    /** The window in slot {@code slot}, or null. */
    public Window windowAt(int slot) {
        return slots[slot];
    }

    /** The window with OS id {@code id}, or null. */
    public Window window(int id) {
        for (Window w : slots) {
            if (w != null && w.id == id) {
                return w;
            }
        }
        return null;
    }

    /** The window holding {@code area}, or null. */
    public Window window(DockArea area) {
        return area != null && area.host instanceof Window w ? w : null;
    }

    /** The window following a drag now, or null. */
    public Window followingWindow() {
        return following;
    }

    /**
     * Gives window {@code id} ({@link OsWindows#MAIN}: the main window) paper white {@code nits} of its own (NaN: it
     * follows the one every window follows again), and saves the layout once settled. Unknown windows are ignored.
     */
    public void paperWhite(int id, float nits) {
        if (!Float.isNaN(nits) && !(nits > 0 && nits < Float.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("a paper white is a positive number of nits: " + nits);
        }
        Window w = id == OsWindows.MAIN ? mainWindow : window(id);
        if (w == null || Float.compare(w.paperWhite, nits) == 0) {
            return;
        }
        w.paperWhite = nits;
        os.paperWhite(w.id, nits);
        unsaved = true; // saved once it keeps still, so a slider's drag saves once
        changedFrame = ui.frames();
    }

    /** Window {@code id}'s own paper white ({@link OsWindows#MAIN}: the main window's), or NaN: none, or unknown. */
    public float paperWhite(int id) {
        Window w = id == OsWindows.MAIN ? mainWindow : window(id);
        return w == null ? Float.NaN : w.paperWhite;
    }

    @Override
    public float mainPaperWhite() {
        return mainWindow.paperWhite;
    }

    @Override
    public void mainPaperWhite(float nits) {
        paperWhite(OsWindows.MAIN, nits);
    }

    /** UI pixels per screen coordinate in the main window. */
    public float mainUiPerScreen() {
        return mainWindow.uiPerScreen;
    }

    // ================================================================ coordinates

    /**
     * The UI position (pixels) of screen point {@code sx, sy}, into {@code out}: in the region of the front-most
     * window under it (but the one following a drag, which the pointer is always over), else relative to the main
     * window (beyond it when no window is under it). Returns the OS id of that window ({@link OsWindows#MAIN} for none).
     */
    public int toUi(float sx, float sy, float[] out) {
        for (int i = 0; i < orderCount; i++) {
            Window w = order[i];
            if (w != following && sx >= w.x && sy >= w.y && sx < w.x + w.w && sy < w.y + w.h) {
                out[0] = w.originX + (sx - w.x) * w.uiPerScreen;
                out[1] = w.originY + (sy - w.y) * w.uiPerScreen;
                return w.id;
            }
        }
        out[0] = (sx - mainWindow.x) * mainWindow.uiPerScreen;
        out[1] = (sy - mainWindow.y) * mainWindow.uiPerScreen;
        return OsWindows.MAIN;
    }

    /** The screen position of UI point {@code x, y}, into {@code out}: through the window whose region holds it. */
    public void toScreen(float x, float y, float[] out) {
        Window w = regionAt(x, y);
        if (w == null) {
            w = mainWindow;
        }
        out[0] = w.x + (x - w.originX) / w.uiPerScreen;
        out[1] = w.y + (y - w.originY) / w.uiPerScreen;
    }

    /** The window whose region holds UI point {@code x, y}, or null (the main window's or none). */
    public Window regionAt(float x, float y) {
        for (Window w : slots) {
            if (w != null && x >= w.originX && y >= w.originY && x < w.originX + w.width && y < w.originY + w.height) {
                return w;
            }
        }
        return null;
    }

    @Override
    public boolean viewportAt(float x, float y, float[] out) {
        Window w = regionAt(x, y);
        if (w == null) {
            return false;
        }
        out[0] = w.originX;
        out[1] = w.originY;
        out[2] = w.width;
        out[3] = w.height;
        return true;
    }

    // ================================================================ events from the engine

    /** The user asked to close window {@code id} (its close button): its panels dock again, it closes. */
    @AllowAllocation("closing a window is a user action")
    public void closeRequested(int id) {
        Window w = window(id);
        if (w != null) {
            space.closeArea(w.area);
        }
    }

    /** Window {@code id} ({@link OsWindows#MAIN} too) got the OS focus: it is the front-most now. */
    public void focused(int id) {
        Window w = id == OsWindows.MAIN ? mainWindow : window(id);
        if (w != null) {
            front(w);
        }
    }

    private void front(Window w) {
        int i = indexInOrder(w);
        if (i > 0) {
            System.arraycopy(order, 0, order, 1, i);
            order[0] = w;
        }
    }

    private int indexInOrder(Window w) {
        for (int i = 0; i < orderCount; i++) {
            if (order[i] == w) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================ DockHost

    @Override
    public boolean areas() {
        return true;
    }

    @Override
    public void undock(DockPanel panel, float x, float y, float width, float height) {
        throw new UnsupportedOperationException("panels go into OS windows' areas");
    }

    @Override
    public void release(DockPanel panel) {
        // panels never float one by one here
    }

    @Override
    public void raise(DockPanel panel) {
        // panels never float one by one here
    }

    @Override
    public boolean bounds(DockPanel panel, float[] out) {
        return false;
    }

    /** Screen coordinates. */
    @Override
    public void toHost(float x, float y, float[] out) {
        toScreen(x, y, out);
    }

    @Override
    @AllowAllocation("a window opens on user action")
    public DockArea openArea(DockSpace dockSpace, float x, float y, float width, float height, String title) {
        float toScreen = HostBox.scale(ui) / mainWindow.uiPerScreen; // panel units to screen coordinates
        return open(nextId(), title, Math.round(x), Math.round(y), Math.max(1, Math.round(width * toScreen)),
            Math.max(1, Math.round(height * toScreen)), false);
    }

    @AllowAllocation("a window opens on user action")
    private String nextId() {
        String id;
        do {
            id = "w" + ++ids;
        } while (space.findArea(id) != null);
        return id;
    }

    @AllowAllocation("a window opens on user action")
    private DockArea open(String id, String title, int x, int y, int width, int height, boolean maximized) {
        int slot = freeSlot();
        Layer layer = layer(slot);
        Element root = ui.element("dock-window", null);
        root.semantics(Role.GROUP, title);
        layer.root().add(root);
        Window w = new Window(slot, root);
        slots[slot] = w;
        w.title = title;
        w.id = os.open(title, x, y, width, height);
        if (maximized) {
            os.maximize(w.id, true);
        }
        DockArea area = space.newArea(id);
        area.host = w;
        w.area = area;
        root.add(area.root());
        if (orderCount == order.length) {
            order = Arrays.copyOf(order, orderCount * 2);
        }
        order[orderCount++] = w;
        front(w);
        refreshMain();
        refresh(w);
        return area;
    }

    @AllowAllocation("a window opens on user action")
    private int freeSlot() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        slots = Arrays.copyOf(slots, slots.length + 1);
        return slots.length - 1;
    }

    /** Window slot {@code slot}'s layer, made the first time (layers stay: slots are reused). */
    @AllowAllocation("a window opens on user action")
    private Layer layer(int slot) {
        if (slot >= layers.length) {
            layers = Arrays.copyOf(layers, slot + 1);
        }
        if (layers[slot] == null) {
            String name = "dock-window-" + slot;
            ui.layerStack().add(name, LAYER_Z + slot);
            Layer layer = ui.layer(name); // the UI grows its arrays for the layer's root
            int r = layer.rootSlot();
            ui.ownLayout(r, Ui.CODE_KIND);
            layout.kind(r, LayoutKind.NONE);
            layers[slot] = layer;
        }
        return layers[slot];
    }

    @Override
    @AllowAllocation("a window closes on user action")
    public void closeArea(DockArea area) {
        Window w = window(area);
        if (w == null) {
            return;
        }
        area.host = null;
        if (following == w) {
            following = null;
        }
        slots[w.slot] = null;
        int i = indexInOrder(w);
        System.arraycopy(order, i + 1, order, i, orderCount - i - 1);
        order[--orderCount] = null;
        os.close(w.id);
        if (w.root.alive()) {
            ui.destroy(w.root);
        }
    }

    @Override
    public void titleArea(DockArea area, String title) {
        Window w = window(area);
        if (w != null && !title.equals(w.title)) {
            w.title = title;
            os.title(w.id, title);
            w.root.semantics(Role.GROUP, title);
        }
    }

    @Override
    public void raiseArea(DockArea area) {
        Window w = window(area);
        if (w != null) {
            os.raise(w.id);
            front(w);
        }
    }

    /** Beyond every window: neither in the main window's region nor in another's (but the one following). */
    @Override
    public boolean outside(float x, float y) {
        if (x >= 0 && y >= 0 && x < mainWindow.width && y < mainWindow.height) {
            return false;
        }
        Window w = regionAt(x, y);
        return w == null || w == following;
    }

    @Override
    public void follow(DockArea area, float gx, float gy) {
        Window w = window(area);
        if (following != null && following != w) {
            opacity(following, 1); // dropped: opaque again
        }
        following = w;
        grabX = gx;
        grabY = gy;
        if (w != null) {
            if (os.maximized(w.id)) {
                os.maximize(w.id, false);
            }
            os.raise(w.id);
            front(w);
            opacity(w, dragOpacity(w));
        } else {
            unsaved = true; // saved once it keeps still
            changedFrame = ui.frames();
        }
    }

    @Override
    public DockArea following() {
        return following == null ? null : following.area;
    }

    // ================================================================ layouts

    @Override
    @AllowAllocation("layouts are saved on user action")
    public DockLayout.Window describe(DockArea area, DockLayout.Node root) {
        Window w = window(area);
        if (w == null || !os.bounds(w.id, rect)) {
            return null;
        }
        List<OsWindows.Monitor> monitors = os.monitors();
        OsWindows.Monitor on = null;
        float cx = rect[0] + rect[2] * 0.5f;
        float cy = rect[1] + rect[3] * 0.5f;
        for (OsWindows.Monitor m : monitors) {
            if (cx >= m.x() && cy >= m.y() && cx < m.x() + m.width() && cy < m.y() + m.height()) {
                on = m;
                break;
            }
        }
        if (on == null && !monitors.isEmpty()) {
            on = monitors.get(0);
        }
        int mx = on == null ? 0 : on.x();
        int my = on == null ? 0 : on.y();
        return new DockLayout.Window(area.id(), on == null ? "" : on.key().replace('"', '\''), rect[0] - mx,
            rect[1] - my, rect[2], rect[3], os.maximized(w.id), root, w.paperWhite);
    }

    /**
     * Opens a window as {@code saved} says: on its monitor (the primary one if it is missing) at its place, kept
     * inside the monitor's usable area, then maximised if it was, at its own paper white if it had one.
     */
    @Override
    @AllowAllocation("layouts are restored on user action")
    public DockArea restoreArea(DockSpace dockSpace, DockLayout.Window saved) {
        List<OsWindows.Monitor> monitors = os.monitors();
        OsWindows.Monitor on = null;
        for (OsWindows.Monitor m : monitors) {
            if (m.key().equals(saved.monitor())) {
                on = m;
            }
        }
        if (on == null) {
            for (OsWindows.Monitor m : monitors) {
                if (m.primary() && on == null) {
                    on = m;
                }
            }
        }
        if (on == null && !monitors.isEmpty()) {
            on = monitors.get(0);
        }
        int w = Math.max(1, Math.round(saved.width()));
        int h = Math.max(1, Math.round(saved.height()));
        int x = Math.round(saved.x());
        int y = Math.round(saved.y());
        if (on != null) {
            x += on.x();
            y += on.y();
            w = Math.min(w, on.usableWidth());
            h = Math.min(h, on.usableHeight());
            x = Math.min(Math.max(x, on.usableX()), on.usableX() + on.usableWidth() - w);
            y = Math.min(Math.max(y, on.usableY()), on.usableY() + on.usableHeight() - h);
        }
        String id = space.findArea(saved.id()) == null ? saved.id() : nextId();
        DockArea area = open(id, "", x, y, w, h, saved.maximized());
        Window opened = window(area);
        if (!Float.isNaN(saved.paperWhite())) {
            opened.paperWhite = saved.paperWhite();
            os.paperWhite(opened.id, opened.paperWhite);
        }
        return area;
    }

    // ================================================================ the frame

    @Override
    public void frame(long nanos) {
        refreshMain();
        for (int i = 0; i < slots.length; i++) {
            Window w = slots[i];
            if (w != null && !refresh(w)) {
                space.closeArea(w.area); // gone without asking: its panels dock again
            }
        }
        if (following != null) {
            if (space.dragged() == null) {
                follow(null, 0, 0);
            } else {
                opacity(following, dragOpacity(following)); // as styled now (a theme may change meanwhile)
                track(following);
            }
        }
        if (unsaved && following == null && ui.frames() - changedFrame > SAVE_AFTER) {
            unsaved = false;
            space.save();
        }
    }

    /** {@code w}'s {@code drag-opacity}, from its {@code dock-window}'s style. */
    private float dragOpacity(Window w) {
        return ui.styles().styleOf(w.root.slot()).getFloat(WindowStyles.DRAG_OPACITY);
    }

    /** Asks the system to show {@code w} at {@code opacity}, if that is not what it was last asked. */
    private void opacity(Window w, float opacity) {
        if (opacity != w.opacity) {
            w.opacity = opacity;
            os.opacity(w.id, opacity);
        }
    }

    /** Moves {@code w} under the cursor, held where it was grabbed. */
    private void track(Window w) {
        DockPanel panel = space.dragged();
        if (!os.cursor(cursor)) {
            // no global cursor: the pointer's place in the UI, mapped to the screen
            float px = 0;
            float py = 0;
            for (int p = 0; p < com.truesim.ui.core.input.UiInput.MAX_POINTERS; p++) {
                com.truesim.ui.core.event.DragSession s = ui.interaction().sessionAt(p);
                if (s.active() && s.get(DockSpace.PANEL) == panel) {
                    px = s.x();
                    py = s.y();
                }
            }
            toScreen(px, py, cursor);
        }
        int nx = Math.round(cursor[0] - grabX);
        int ny = Math.round(cursor[1] - grabY);
        if (nx != w.x || ny != w.y) {
            os.move(w.id, nx, ny);
            w.x = nx;
            w.y = ny;
        }
    }

    private void refreshMain() {
        if (os.bounds(OsWindows.MAIN, rect)) {
            mainWindow.x = rect[0];
            mainWindow.y = rect[1];
            mainWindow.w = rect[2];
            mainWindow.h = rect[3];
        }
        float ratio = os.pixelRatio(OsWindows.MAIN);
        mainWindow.uiPerScreen = ratio > 0 ? ratio : 1;
        mainWindow.width = mainWindow.w * mainWindow.uiPerScreen;
        mainWindow.height = mainWindow.h * mainWindow.uiPerScreen;
    }

    /** Reads {@code w}'s bounds and scale; lays its region out again when they changed. False if it is gone. */
    private boolean refresh(Window w) {
        if (!os.bounds(w.id, rect)) {
            return false;
        }
        float mainScale = os.contentScale(OsWindows.MAIN);
        float own = os.contentScale(w.id);
        float ratio = os.pixelRatio(w.id);
        float ui = (ratio > 0 ? ratio : 1) * (mainScale > 0 && own > 0 ? mainScale / own : 1);
        boolean moved = rect[0] != w.x || rect[1] != w.y;
        boolean sized = rect[2] != w.w || rect[3] != w.h || ui != w.uiPerScreen;
        w.x = rect[0];
        w.y = rect[1];
        w.w = rect[2];
        w.h = rect[3];
        w.uiPerScreen = ui;
        float scale = HostBox.scale(this.ui);
        if (sized || scale != w.laidScale) {
            w.width = Math.min(w.w * ui, STRIDE);
            w.height = w.h * ui;
            w.laidScale = scale;
            int r = w.root.slot();
            this.ui.ownLayout(r, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            layout.offsets(r, w.originX / scale, w.originY / scale, 0, 0);
            layout.width(r, w.width / scale, Unit.PX).height(r, w.height / scale, Unit.PX);
        }
        if ((moved || sized) && following != w) {
            unsaved = true;
            changedFrame = this.ui.frames();
        }
        return true;
    }

    @Override
    public String toString() {
        return "OsDockHost[" + windowCount() + " windows]";
    }
}
