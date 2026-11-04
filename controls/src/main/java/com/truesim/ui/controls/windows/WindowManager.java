package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.HostBox;
import com.truesim.ui.controls.overlay.Overlays;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The floating windows of one host: by default a {@code window-host} element covering the overlay layer, or any
 * container given (a world panel's root for windows in 3D and VR, a docked pane): nothing here assumes the screen.
 * Keeps their z-order (a press raises a window), the active window ({@code window:selected}), snapping, keyboard
 * cycling ({@link #focusNext()}, the {@code NEXT_WINDOW} command), the remembered layout ({@link #persistence}) and the drop targets windows are
 * docked into. Modal windows show on the modal layer (or, in a given host, in that host).
 *
 * <pre>{@code
 * WindowManager windows = new WindowManager(overlays);
 * FloatingWindow w = windows.create("inspector", "Inspector").bounds(40, 40, 320, 400);
 * w.content().add(inspectorPanel);
 * w.open();
 * }</pre>
 */
public final class WindowManager implements Ui.FrameListener {

    /** How near (UI pixels) a moved or resized edge snaps to the host's or another window's edge. */
    public static final Tunable SNAP_DISTANCE = Tunable.of("ui.window.snapDistance", 8, 0, 64,
        "UI pixels within which a dragged window edge snaps to the screen's or another window's edge (0: no snapping).");
    /** How far one arrow key or d-pad press moves or resizes a window in keyboard mode. */
    public static final Tunable KEY_STEP = Tunable.of("ui.window.keyStep", 16, 1, 256,
        "Panel units a window moves or grows per arrow key or d-pad press while moved or resized from the keyboard.");
    /** How much of a window stays inside its host when moved off its edge. */
    public static final Tunable KEEP_VISIBLE = Tunable.of("ui.window.keepVisible", 40, 0, 512,
        "Panel units of a window that stay on screen when it is dragged off an edge.");

    private static final String[] DEFAULT_NAMES = {"Minimise", "Maximise", "Close"};

    private final Ui ui;
    private final Overlays overlays;
    private final Layout layout;
    private final Interaction interaction;
    private final UiStore store;
    private final Element host;
    private final boolean ownHost;
    private Element modalHost;
    private FloatingWindow[] windows = new FloatingWindow[8];
    private int count;
    private FloatingWindow[] open = new FloatingWindow[8];
    private int openCount;
    private FloatingWindow active;
    private WindowDropTarget[] targets = new WindowDropTarget[0];
    private final String[] names = DEFAULT_NAMES.clone();
    private final Map<String, WindowLayout.State> pending = new HashMap<>();
    private LayoutStore persistence;
    private String persistenceKey;
    private float lastHostWidth = -1;
    private FloatingWindow focusPending;
    private float lastHostHeight = -1;

    /** The windows of the UI of {@code overlays}, in a {@code window-host} on the overlay layer. */
    @AllowAllocation("one per UI")
    public WindowManager(Overlays overlays) {
        this(overlays, null);
    }

    /**
     * The windows of the UI of {@code overlays} in {@code host} (laid out as {@code layout: none}; null: a
     * {@code window-host} on the overlay layer).
     */
    @AllowAllocation("one per host")
    public WindowManager(Overlays overlays, Element host) {
        this.overlays = overlays;
        this.ui = overlays.ui();
        this.layout = ui.layout();
        this.interaction = ui.interaction();
        this.store = ui.store();
        this.ownHost = host == null;
        if (host == null) {
            host = ui.element("window-host", null);
            ui.layer(Layers.OVERLAY).root().add(host);
            interaction.pickable(host, PointerEvents.NONE);
        }
        this.host = host;
        if (!ownHost) {
            modalHost = host;
        }
        ui.addFrameListener(this);
        interaction.addCommandFallback(this::command);
    }

    /**
     * Commands nothing else took: {@code NEXT_WINDOW} cycles the open windows ({@link #focusNext()});
     * {@code WINDOW_MOVE} and {@code WINDOW_RESIZE} with nothing focused move or resize the active window from the
     * keyboard (with the focus in a window, the window takes them itself).
     */
    private boolean command(UiCommand command, int player, int flags) {
        switch (command) {
            case NEXT_WINDOW -> {
                if (openCount == 0) {
                    return false;
                }
                focusNext();
                return true;
            }
            case WINDOW_MOVE, WINDOW_RESIZE -> {
                if (active == null || !active.isOpen() || interaction.focused(player) != null) {
                    return false;
                }
                if (command == UiCommand.WINDOW_MOVE) {
                    active.keyboardMove();
                } else {
                    active.keyboardResize();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The tunables windows read. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(SNAP_DISTANCE, KEY_STEP, KEEP_VISIBLE);
    }

    Ui ui() {
        return ui;
    }

    /** The overlays of the UI. */
    public Overlays overlays() {
        return overlays;
    }

    /** The element windows show in. */
    public Element host() {
        return host;
    }

    /** Where modal windows show: a {@code window-host} on the modal layer, or the given host. */
    @AllowAllocation("made when the first modal window opens")
    Element modalHost() {
        if (modalHost == null) {
            modalHost = ui.element("window-host", null);
            interaction.pickable(modalHost, PointerEvents.NONE);
        }
        if (modalHost.parent() == null) {
            ui.layer(Layers.MODAL).root().add(modalHost); // the modal layer blocks while it holds anything
        }
        return modalHost;
    }

    /**
     * Sets the accessible names of the title bar buttons (localised): minimise, maximise, close. For windows made
     * afterwards.
     */
    public void buttonNames(String minimise, String maximise, String close) {
        names[0] = minimise;
        names[1] = maximise;
        names[2] = close;
    }

    String buttonName(int i) {
        return names[i];
    }

    /** A new window with id {@code id} (no white space; its remembered layout's key) and title {@code title}. */
    @AllowAllocation("windows are made when a UI is built")
    public FloatingWindow create(String id, String title) {
        if (find(id) != null) {
            throw new IllegalArgumentException("a window " + id + " exists");
        }
        FloatingWindow w = new FloatingWindow(this, id, title);
        if (count == windows.length) {
            windows = Arrays.copyOf(windows, count * 2);
        }
        windows[count++] = w;
        WindowLayout.State remembered = pending.remove(id);
        if (remembered != null) {
            apply(w, remembered);
        }
        return w;
    }

    /** The window with id {@code id}, or null. */
    public FloatingWindow find(String id) {
        for (int i = 0; i < count; i++) {
            if (windows[i].id().equals(id)) {
                return windows[i];
            }
        }
        return null;
    }

    /** Forgets {@code window} (closing it first): its elements are destroyed. */
    public void destroy(FloatingWindow window) {
        window.close();
        for (int i = 0; i < count; i++) {
            if (windows[i] == window) {
                System.arraycopy(windows, i + 1, windows, i, count - i - 1);
                windows[--count] = null;
                break;
            }
        }
        ui.destroy(window.root());
        if (window.backdrop() != null) {
            ui.destroy(window.backdrop());
        }
    }

    /** How many windows exist. */
    public int count() {
        return count;
    }

    /** Window {@code index}, in the order they were made. */
    public FloatingWindow at(int index) {
        return windows[index];
    }

    /** How many windows are open. */
    public int openCount() {
        return openCount;
    }

    /** Open window {@code index}, back to front. */
    public FloatingWindow openAt(int index) {
        return open[index];
    }

    /** The active window (the last raised or focused), or null. */
    public FloatingWindow active() {
        return active;
    }

    /** Adds something windows may be docked into while dragged (a dock space does this itself). */
    @AllowAllocation("drop targets are added when a UI is built")
    public void addDropTarget(WindowDropTarget target) {
        targets = Arrays.copyOf(targets, targets.length + 1);
        targets[targets.length - 1] = target;
    }

    // ================================================================ z-order and activation

    @AllowAllocation("growing with the open windows, rarely")
    void opened(FloatingWindow w) {
        if (openCount == open.length) {
            open = Arrays.copyOf(open, openCount * 2);
        }
        open[openCount++] = w;
        activate(w);
        if (!w.modal()) {
            focusPending = w; // once it is laid out: focus goes to shown elements only
        }
        save();
    }

    void closed(FloatingWindow w) {
        for (int i = 0; i < openCount; i++) {
            if (open[i] == w) {
                System.arraycopy(open, i + 1, open, i, openCount - i - 1);
                open[--openCount] = null;
                break;
            }
        }
        if (focusPending == w) {
            focusPending = null;
        }
        if (modalHost != null && modalHost != host && store.firstChild(modalHost.slot()) == UiStore.NONE
            && modalHost.parent() != null) {
            modalHost.detach(); // no modal window left: the modal layer stops blocking
        }
        if (active == w) {
            active = null;
            if (openCount > 0) {
                FloatingWindow next = open[openCount - 1];
                activate(next);
                if (!next.modal()) {
                    focusInside(next);
                }
            }
        }
        save();
    }

    /** Brings {@code w} to the front of its host and activates it; with {@code focus}, focuses it. */
    void raise(FloatingWindow w, boolean focus) {
        Element h = w.host();
        int r = w.root().slot();
        if (store.lastChild(h.slot()) != r) {
            if (w.backdrop() != null && w.backdrop().parent() == h) {
                h.add(w.backdrop());
            }
            h.add(w.root());
        }
        for (int i = 0; i < openCount; i++) {
            if (open[i] == w) {
                System.arraycopy(open, i + 1, open, i, openCount - i - 1);
                open[openCount - 1] = w;
                break;
            }
        }
        activate(w);
        if (focus) {
            focusInside(w);
        }
    }

    private void focusInside(FloatingWindow w) {
        Element remembered = interaction.remembered(w.root());
        if (remembered == null || !interaction.focus(remembered)) {
            interaction.focus(w.root());
        }
    }

    void activate(FloatingWindow w) {
        if (active == w) {
            return;
        }
        if (active != null) {
            active.root().state(UiState.SELECTED, false);
        }
        active = w;
        w.root().state(UiState.SELECTED, true);
    }

    /** Activates and focuses the next open window (the back-most goes to the front), as Ctrl+F6 does. */
    public void focusNext() {
        if (openCount == 0) {
            return;
        }
        FloatingWindow next = open[0];
        if (active == null || openCount == 1) {
            next = open[openCount - 1];
        }
        raise(next, true);
    }

    // ================================================================ moving, snapping

    private float hostWidth() {
        return HostBox.width(ui, host.slot()) / HostBox.scale(ui);
    }

    private float hostHeight() {
        return HostBox.height(ui, host.slot()) / HostBox.scale(ui);
    }

    /** Moves {@code w} to {@code nx, ny} (panel units), snapped to the host's and other windows' edges. */
    void snapMove(FloatingWindow w, float nx, float ny) {
        float d = SNAP_DISTANCE.get() / HostBox.scale(ui);
        float ww = w.width();
        float wh = sizeOf(w, false);
        float bestX = nx;
        float bestDx = d;
        float bestY = ny;
        float bestDy = d;
        if (d > 0) {
            if (w.host() == host && layout.shown(host.slot())) {
                float hw = hostWidth();
                float hh = hostHeight();
                if (Math.abs(nx) < bestDx) {
                    bestDx = Math.abs(nx);
                    bestX = 0;
                }
                float right = hw - ww;
                if (Math.abs(right - nx) < bestDx) {
                    bestDx = Math.abs(right - nx);
                    bestX = right;
                }
                if (Math.abs(ny) < bestDy) {
                    bestDy = Math.abs(ny);
                    bestY = 0;
                }
                float bottom = hh - wh;
                if (Math.abs(bottom - ny) < bestDy) {
                    bestDy = Math.abs(bottom - ny);
                    bestY = bottom;
                }
            }
            for (int i = 0; i < openCount; i++) {
                FloatingWindow o = open[i];
                if (o == w || o.host() != w.host() || o.mode() == WindowMode.MAXIMISED) {
                    continue;
                }
                float ox = o.x();
                float oy = o.y();
                float ow = o.width();
                float oh = sizeOf(o, false);
                if (ny < oy + oh + d && ny + wh > oy - d) { // side by side: abut or align left and right edges
                    float[] xs = scratch;
                    xs[0] = ox + ow;
                    xs[1] = ox - ww;
                    xs[2] = ox;
                    xs[3] = ox + ow - ww;
                    for (int k = 0; k < 4; k++) {
                        float dist = Math.abs(xs[k] - nx);
                        if (dist < bestDx) {
                            bestDx = dist;
                            bestX = xs[k];
                        }
                    }
                }
                if (nx < ox + ow + d && nx + ww > ox - d) { // one above the other
                    float[] ys = scratch;
                    ys[0] = oy + oh;
                    ys[1] = oy - wh;
                    ys[2] = oy;
                    ys[3] = oy + oh - wh;
                    for (int k = 0; k < 4; k++) {
                        float dist = Math.abs(ys[k] - ny);
                        if (dist < bestDy) {
                            bestDy = dist;
                            bestY = ys[k];
                        }
                    }
                }
            }
        }
        w.placedAt(bestX, bestY);
        keepVisible(w);
    }

    private final float[] scratch = new float[4];

    /** {@code value}, an edge of {@code w} being resized (panel units), snapped to the host's and others' edges. */
    float snapEdge(FloatingWindow w, float value, boolean horizontal) {
        float d = SNAP_DISTANCE.get() / HostBox.scale(ui);
        if (d <= 0) {
            return value;
        }
        float best = value;
        float bestD = d;
        if (w.host() == host && layout.shown(host.slot())) {
            float far = horizontal ? hostWidth() : hostHeight();
            if (Math.abs(value) < bestD) {
                bestD = Math.abs(value);
                best = 0;
            }
            if (Math.abs(far - value) < bestD) {
                bestD = Math.abs(far - value);
                best = far;
            }
        }
        for (int i = 0; i < openCount; i++) {
            FloatingWindow o = open[i];
            if (o == w || o.host() != w.host() || o.mode() == WindowMode.MAXIMISED) {
                continue;
            }
            float start = horizontal ? o.x() : o.y();
            float end = start + (horizontal ? o.width() : sizeOf(o, false));
            if (Math.abs(start - value) < bestD) {
                bestD = Math.abs(start - value);
                best = start;
            }
            if (Math.abs(end - value) < bestD) {
                bestD = Math.abs(end - value);
                best = end;
            }
        }
        return best;
    }

    /** {@code w}'s laid-out width or height in panel units (a minimised window is as tall as its title bar). */
    private float sizeOf(FloatingWindow w, boolean horizontal) {
        if (w.mode() == WindowMode.MINIMISED && !horizontal && layout.shown(w.root().slot())) {
            return layout.height(w.root().slot()) / HostBox.scale(ui);
        }
        return horizontal ? w.width() : w.height();
    }

    /** Keeps {@code w}'s title bar reachable: at least {@code ui.window.keepVisible} of it inside the host. */
    void keepVisible(FloatingWindow w) {
        float scale = HostBox.scale(ui);
        float hw = HostBox.width(ui, w.host().slot()) / scale;
        float hh = HostBox.height(ui, w.host().slot()) / scale;
        float keep = KEEP_VISIBLE.get();
        float nx = Math.min(Math.max(w.x(), keep - w.width()), hw - keep);
        float ny = Math.min(Math.max(w.y(), 0), hh - keep);
        w.placedAt(nx, Math.max(ny, 0));
    }

    // ================================================================ docking drops

    void windowDragged(FloatingWindow w, float x, float y, int modifiers) {
        for (WindowDropTarget t : targets) {
            t.windowDragged(w, x, y, modifiers);
        }
    }

    boolean windowDropped(FloatingWindow w, float x, float y, int modifiers) {
        boolean taken = false;
        for (WindowDropTarget t : targets) {
            taken |= t.windowDropped(w, x, y, modifiers);
        }
        return taken;
    }

    // ================================================================ the frame

    @Override
    public void frame(long nanos) {
        assertLayout();
    }

    /** Sets the layout inputs the windows own: hosts, places, sizes and modes, backdrops. */
    private void assertLayout() {
        if (ownHost) {
            int h = host.slot();
            ui.ownLayout(h, Ui.CODE_KIND | Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            layout.kind(h, LayoutKind.NONE);
            layout.offsets(h, 0, 0, 0, 0);
            layout.width(h, 100, Unit.PERCENT).height(h, 100, Unit.PERCENT);
        } else {
            ui.ownLayout(host.slot(), Ui.CODE_KIND);
            layout.kind(host.slot(), LayoutKind.NONE);
        }
        if (modalHost != null && modalHost != host) {
            int h = modalHost.slot();
            ui.ownLayout(h, Ui.CODE_KIND | Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            layout.kind(h, LayoutKind.NONE);
            layout.offsets(h, 0, 0, 0, 0);
            layout.width(h, 100, Unit.PERCENT).height(h, 100, Unit.PERCENT);
        }
        for (int i = 0; i < openCount; i++) {
            FloatingWindow w = open[i];
            w.assertLayout();
            Element b = w.backdrop();
            if (b != null && b.parent() != null) {
                int s = b.slot();
                ui.ownLayout(s, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
                layout.offsets(s, 0, 0, 0, 0);
                layout.width(s, 100, Unit.PERCENT).height(s, 100, Unit.PERCENT);
            }
        }
    }

    @Override
    public void afterLayout() {
        assertLayout(); // what a restyle in this frame took (a minimised window's min-height): laid out again
        FloatingWindow pendingFocus = focusPending;
        if (pendingFocus != null && layout.shown(pendingFocus.root().slot())) {
            focusPending = null;
            if (pendingFocus.isOpen()) {
                focusInside(pendingFocus);
            }
        }
        if (!layout.shown(host.slot())) {
            return;
        }
        float scale = HostBox.scale(ui);
        float hw = HostBox.width(ui, host.slot()) / scale;
        float hh = HostBox.height(ui, host.slot()) / scale;
        boolean resized = hw != lastHostWidth || hh != lastHostHeight;
        lastHostWidth = hw;
        lastHostHeight = hh;
        for (int i = 0; i < openCount; i++) {
            FloatingWindow w = open[i];
            if (!w.placed()) {
                Element h = w.host();
                float pw = HostBox.width(ui, h.slot()) / scale;
                float ph = HostBox.height(ui, h.slot()) / scale;
                w.placedAt((pw - w.width()) * 0.5f, (ph - w.height()) * 0.5f);
                w.assertLayout();
            } else if (resized) {
                keepVisible(w);
                w.assertLayout();
            }
        }
    }

    // ================================================================ remembered layout

    /** The state of every window, open or not. */
    @AllowAllocation("layouts are saved on user action")
    public WindowLayout layout() {
        List<WindowLayout.State> states = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FloatingWindow w = windows[i];
            states.add(new WindowLayout.State(w.id(), w.x(), w.y(), w.width(), w.height(), w.mode(), w.isOpen()));
        }
        for (WindowLayout.State s : pending.values()) {
            states.add(s);
        }
        return new WindowLayout(states);
    }

    /**
     * Applies {@code remembered}: windows that exist take their place, size and mode, and open or close; the states of
     * windows not made yet apply when they are made.
     */
    @AllowAllocation("layouts are restored on user action")
    public void restore(WindowLayout remembered) {
        LayoutStore saving = persistence;
        persistence = null; // no saving while restoring
        try {
            for (WindowLayout.State s : remembered.windows()) {
                FloatingWindow w = find(s.id());
                if (w == null) {
                    pending.put(s.id(), s);
                } else {
                    apply(w, s);
                    if (s.open() && !w.isOpen()) {
                        w.open();
                    } else if (!s.open() && w.isOpen()) {
                        w.close();
                    }
                }
            }
        } finally {
            persistence = saving;
        }
    }

    private void apply(FloatingWindow w, WindowLayout.State s) {
        w.bounds(s.x(), s.y(), s.width(), s.height());
        w.mode(s.mode());
    }

    /**
     * Remembers the layout in {@code store} under {@code key}: what is saved there is restored now (windows made later
     * take theirs when made), and every move, resize, mode change, opening and closing saves it.
     */
    @AllowAllocation("set up once")
    public void persistence(LayoutStore store, String key) {
        persistence = null;
        String text = store == null ? null : store.load(key);
        if (text != null) {
            restore(WindowLayout.parse(text));
        }
        persistence = store;
        persistenceKey = key;
    }

    /** Saves the layout to the persistence store, if one is set. */
    @AllowAllocation("layouts are saved on user action")
    public void save() {
        if (persistence != null) {
            persistence.save(persistenceKey, layout().toText());
        }
    }

    void changed(FloatingWindow w) {
        save();
    }
}
