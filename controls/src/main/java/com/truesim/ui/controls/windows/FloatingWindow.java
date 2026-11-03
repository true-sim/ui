package com.truesim.ui.controls.windows;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.overlay.HostBox;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.LayoutKind;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;

/**
 * A floating window, made by {@link WindowManager#create}: a title bar (an optional icon, the title, minimise,
 * maximise and close buttons), content, and resize edges and corners, in its manager's host (any container: the
 * overlay layer, a world panel's root, a docked pane).
 *
 * <p>Elements, for styles: {@code window} (the root: its fact {@code mode} is {@code normal}, {@code minimised} or
 * {@code maximised}; {@code :selected} while it is the active window), {@code window-frame} (the visible frame:
 * background, border, radius, shadow), {@code window-title-bar}, a {@code label} with style name
 * {@code window-title}, an optional {@code image} icon, {@code window-button} with ids {@code minimise},
 * {@code maximise}, {@code close} (each holding an {@code image.icon} of the same id), {@code window-content}, and eight {@code window-resize} edges with ids {@code n},
 * {@code s}, {@code e}, {@code w}, {@code ne}, {@code nw}, {@code se}, {@code sw} (their {@code cursor}s; their
 * thickness is the window's {@code resize-handle}). The smallest size is the window's {@code min-width} and
 * {@code min-height}.
 *
 * <p>Behaviour: dragging the title bar moves it, snapping to the host's edges and other windows' edges within
 * {@code ui.window.snapDistance}; a double click on it maximises or restores; dragging an edge or corner resizes it,
 * snapping too; a press anywhere raises and activates it. Modal windows show over a dimming {@code backdrop} on the
 * modal layer, trap focus and close on {@code CANCEL}. {@link #keyboardMove()} and {@link #keyboardResize()} let the
 * arrow keys or the d-pad move or resize it until {@code ACCEPT} (or {@code CANCEL}, which puts it back).
 */
public final class FloatingWindow {

    /** What a window tells its owner. */
    public interface Listener {
        /** The user asked to close the window; false keeps it open. */
        default boolean closing(FloatingWindow window) {
            return true;
        }

        /** The window closed. */
        default void closed(FloatingWindow window) {
        }

        /** The window was moved, resized or changed mode by the user (at the end of the gesture). */
        default void changed(FloatingWindow window) {
        }
    }

    private static final Listener NONE = new Listener() {
    };
    private static final Facts[] MODES = {Facts.of("mode", "normal"), Facts.of("mode", "minimised"),
        Facts.of("mode", "maximised")};
    private static final String[] HANDLE_IDS = {"n", "s", "w", "e", "nw", "ne", "sw", "se"};
    /** Edge bits per handle: left 1, right 2, top 4, bottom 8. */
    private static final int[] HANDLE_EDGES = {4, 8, 1, 2, 5, 6, 9, 10};
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int TOP = 4;
    private static final int BOTTOM = 8;
    private static final Unit[] UNITS = {Unit.PX, Unit.EM, Unit.PERCENT, Unit.AUTO};
    private static final int KEY_MOVE = 1;
    private static final int KEY_RESIZE = 2;

    final WindowManager manager;
    private final Ui ui;
    private final Layout layout;
    private final Interaction interaction;
    private final String id;
    private final Element root;
    private final Element frame;
    private final Element titleBar;
    private final Element title;
    private final Element content;
    private final Element[] buttons = new Element[3];
    private final Element[] handles = new Element[8];
    private Element icon;
    private Element backdrop;
    private Element host;
    private String titleText;
    private Listener listener = NONE;

    float x;
    float y;
    float width = 320;
    float height = 240;
    private WindowMode mode = WindowMode.NORMAL;
    private boolean modal;
    private boolean open;
    private boolean resizable = true;
    private boolean closable = true;
    private boolean minimisable = true;
    private boolean maximisable = true;
    private boolean placed;

    // gestures
    private int dragEdges;
    private boolean moving;
    private float startX;
    private float startY;
    private float startWidth;
    private float startHeight;
    private int keyMode;
    private Object dockSource;

    private final UiListener rootListener = new RootListener();
    private final UiListener titleListener = new TitleListener();
    private final UiListener handleListener = new HandleListener();
    private final UiListener buttonListener = new ButtonListener();
    private final Runnable cancel = new Cancel();

    @AllowAllocation("windows are made when a UI is built")
    FloatingWindow(WindowManager manager, String id, String titleText) {
        WindowLayout.checkId(id);
        this.manager = manager;
        this.ui = manager.ui();
        this.layout = ui.layout();
        this.interaction = ui.interaction();
        this.id = id;
        this.titleText = titleText;
        root = ui.element("window", null).id(id);
        frame = ui.element("window-frame", null);
        titleBar = ui.element("window-title-bar", null);
        title = ui.textSystem() == null ? null : ui.label("window-title", titleText);
        content = ui.element("window-content", null);
        root.add(frame);
        frame.add(titleBar);
        if (title != null) {
            titleBar.add(title);
        }
        String[] buttonIds = {"minimise", "maximise", "close"};
        for (int i = 0; i < 3; i++) {
            Element b = ui.element("window-button", null).id(buttonIds[i]);
            b.semantics(Role.BUTTON, manager.buttonName(i));
            b.add(ui.image("icon").id(buttonIds[i])); // the theme's picture: a built-in icon by default
            interaction.on(b, EventType.CLICK, buttonListener);
            buttons[i] = b;
            titleBar.add(b);
        }
        frame.add(content);
        for (int i = 0; i < handles.length; i++) {
            Element h = ui.element("window-resize", null).id(HANDLE_IDS[i]);
            h.semantics(Role.SEPARATOR, null);
            interaction.on(h, EventType.DRAG_START.bit() | EventType.DRAG.bit() | EventType.DRAG_END.bit(),
                handleListener);
            handles[i] = h;
            root.add(h);
        }
        root.semantics(Role.WINDOW, titleText);
        titleBar.semantics(Role.GROUP, null);
        interaction.focusable(root, true);
        interaction.rememberFocus(root, true);
        interaction.onCapture(root, EventType.PRESS.bit() | EventType.COMMAND.bit(), rootListener);
        interaction.on(root, EventType.FOCUS_IN, rootListener);
        interaction.on(titleBar, EventType.DRAG_START.bit() | EventType.DRAG.bit() | EventType.DRAG_END.bit()
            | EventType.CLICK.bit(), titleListener);
        ui.styles().facts(root.slot(), MODES[0]);
    }

    // ================================================================ parts

    /** The window's id: what its remembered layout is saved under. */
    public String id() {
        return id;
    }

    /** The root element ({@code window}). */
    public Element root() {
        return root;
    }

    /** The visible frame ({@code window-frame}). */
    public Element frame() {
        return frame;
    }

    /** The title bar ({@code window-title-bar}). */
    public Element titleBar() {
        return titleBar;
    }

    /** The title label, or null in a UI without text. */
    public Element titleLabel() {
        return title;
    }

    /** Where the window's content goes ({@code window-content}). */
    public Element content() {
        return content;
    }

    /** The minimise button. */
    public Element minimiseButton() {
        return buttons[0];
    }

    /** The maximise (and restore) button. */
    public Element maximiseButton() {
        return buttons[1];
    }

    /** The close button. */
    public Element closeButton() {
        return buttons[2];
    }

    /** The resize edge or corner with id {@code edge} ({@code n}, {@code se}...). */
    public Element handle(String edge) {
        for (int i = 0; i < HANDLE_IDS.length; i++) {
            if (HANDLE_IDS[i].equals(edge)) {
                return handles[i];
            }
        }
        throw new IllegalArgumentException("no edge " + edge);
    }

    /** The dimming backdrop of a modal window once shown, or null. */
    public Element backdrop() {
        return backdrop;
    }

    /** The title. */
    public String title() {
        return titleText;
    }

    /** Sets the title (and the window's accessible name); returns this. */
    @AllowAllocation("titles change on user action")
    public FloatingWindow title(String value) {
        titleText = value;
        if (title != null) {
            ui.text(title, value);
            title.semantics(Role.TEXT, value);
        }
        root.semantics(modal ? Role.DIALOG : Role.WINDOW, value);
        return this;
    }

    /** Shows an icon before the title: an {@code image} element with style name {@code style}; returns this. */
    @AllowAllocation("windows are made when a UI is built")
    public FloatingWindow icon(String style) {
        if (icon == null) {
            icon = ui.image(style);
            icon.detach();
            Element first = ui.store().element(ui.store().firstChild(titleBar.slot()));
            titleBar.add(icon);
            // the icon goes first: move the others after it
            for (int s = first == null ? UiStore.NONE : first.slot(); s != UiStore.NONE && s != icon.slot(); ) {
                int next = ui.store().nextSibling(s);
                titleBar.add(ui.store().element(s));
                s = next;
            }
        } else {
            icon.style(style);
        }
        return this;
    }

    /** The icon, or null. */
    public Element icon() {
        return icon;
    }

    /** Sets what is told when the window closes or changes; returns this. */
    public FloatingWindow listener(Listener value) {
        listener = value == null ? NONE : value;
        return this;
    }

    // ================================================================ options

    /** Makes the window modal (set before opening): it shows over a backdrop and traps focus. Returns this. */
    public FloatingWindow modal(boolean value) {
        if (open) {
            throw new IllegalStateException("set modality before opening");
        }
        modal = value;
        root.semantics(value ? Role.DIALOG : Role.WINDOW, titleText);
        return this;
    }

    /** Whether the window is modal. */
    public boolean modal() {
        return modal;
    }

    /** Lets the user resize the window or not; returns this. */
    public FloatingWindow resizable(boolean value) {
        resizable = value;
        return this;
    }

    /** Shows the close button or not; returns this. */
    public FloatingWindow closable(boolean value) {
        closable = value;
        return this;
    }

    /** Shows the minimise button or not; returns this. */
    public FloatingWindow minimisable(boolean value) {
        minimisable = value;
        return this;
    }

    /** Shows the maximise button or not; returns this. */
    public FloatingWindow maximisable(boolean value) {
        maximisable = value;
        return this;
    }

    // ================================================================ place and size

    /** Sets the position and size in panel units within the host; returns this. */
    public FloatingWindow bounds(float newX, float newY, float newWidth, float newHeight) {
        x = newX;
        y = newY;
        width = Math.max(newWidth, minWidth());
        height = Math.max(newHeight, minHeight());
        placed = true;
        return this;
    }

    /** The left edge in panel units within the host. */
    public float x() {
        return x;
    }

    /** The top edge in panel units within the host. */
    public float y() {
        return y;
    }

    /** The width in panel units (when normal). */
    public float width() {
        return width;
    }

    /** The height in panel units (when normal). */
    public float height() {
        return height;
    }

    /** Whether a position was given (or remembered); unplaced windows open centred in the host. */
    boolean placed() {
        return placed;
    }

    void placedAt(float newX, float newY) {
        x = newX;
        y = newY;
        placed = true;
    }

    /** The mode. */
    public WindowMode mode() {
        return mode;
    }

    /** Whether it shows. */
    public boolean isOpen() {
        return open;
    }

    /** The host element it shows in now (its manager's, or the modal host). */
    public Element host() {
        return host;
    }

    /** Whether it is the manager's active window. */
    public boolean active() {
        return root.is(UiState.SELECTED);
    }

    // ================================================================ open, close, modes

    /** Opens the window over the others and activates it. */
    @AllowAllocation("a window opens on user action")
    public FloatingWindow open() {
        if (open) {
            manager.raise(this, true);
            return this;
        }
        open = true;
        host = modal ? manager.modalHost() : manager.host();
        if (modal) {
            if (backdrop == null) {
                backdrop = ui.element("backdrop", null);
                interaction.pickable(backdrop, PointerEvents.ALL);
                backdrop.semantics(Role.NONE, null);
            }
            host.add(backdrop);
        }
        host.add(root);
        manager.opened(this);
        if (modal) {
            interaction.pushModal(root, cancel);
        }
        return this;
    }

    /** Asks to close: closes unless the listener keeps it open. Returns whether it closed. */
    public boolean requestClose() {
        if (!open || !listener.closing(this)) {
            return false;
        }
        close();
        return true;
    }

    /** Closes the window (no asking). */
    public void close() {
        if (!open) {
            return; // already closed
        }
        keyMode = 0;
        open = false;
        if (modal) {
            interaction.popModal(root);
        }
        root.detach();
        if (backdrop != null && backdrop.parent() != null) {
            backdrop.detach();
        }
        root.state(UiState.SELECTED, false);
        manager.closed(this);
        listener.closed(this);
    }

    /** Minimises the window to its title bar (or restores it from that). */
    public void minimise() {
        mode(mode == WindowMode.MINIMISED ? WindowMode.NORMAL : WindowMode.MINIMISED);
    }

    /** Maximises the window to fill its host (or restores it from that). */
    public void maximise() {
        mode(mode == WindowMode.MAXIMISED ? WindowMode.NORMAL : WindowMode.MAXIMISED);
    }

    /** Restores the window to its own place and size. */
    public void restore() {
        mode(WindowMode.NORMAL);
    }

    /** Sets the mode. */
    public void mode(WindowMode value) {
        if (value == mode) {
            return;
        }
        mode = value;
        ui.styles().facts(root.slot(), MODES[value.ordinal()]);
        buttons[1].state(UiState.CHECKED, value == WindowMode.MAXIMISED);
        buttons[0].state(UiState.CHECKED, value == WindowMode.MINIMISED);
        root.state(UiState.EXPANDED, value != WindowMode.MINIMISED);
    }

    // ================================================================ keyboard and gamepad

    /**
     * Starts moving the window with the arrow keys or the d-pad ({@code UP}, {@code DOWN}, {@code LEFT}, {@code RIGHT}
     * by {@code ui.window.keyStep}) until {@code ACCEPT}; {@code CANCEL} puts it back. Focuses the window.
     */
    public void keyboardMove() {
        keyStart(KEY_MOVE);
    }

    /** Starts resizing the window (its right and bottom edges) with the arrow keys or the d-pad, as {@link #keyboardMove()}. */
    public void keyboardResize() {
        if (resizable) {
            keyStart(KEY_RESIZE);
        }
    }

    /** Whether a keyboard move or resize is in progress. */
    public boolean keyboardActive() {
        return keyMode != 0;
    }

    private void keyStart(int kind) {
        if (mode != WindowMode.NORMAL) {
            restore();
        }
        keyMode = kind;
        rememberStart();
        interaction.focus(root);
    }

    private void rememberStart() {
        startX = x;
        startY = y;
        startWidth = width;
        startHeight = height;
    }

    private void command(UiEvent e) {
        float step = WindowManager.KEY_STEP.get();
        UiCommand c = e.command();
        float dx = c == UiCommand.LEFT ? -step : c == UiCommand.RIGHT ? step : 0;
        float dy = c == UiCommand.UP ? -step : c == UiCommand.DOWN ? step : 0;
        if (dx != 0 || dy != 0) {
            if (keyMode == KEY_MOVE) {
                x += dx;
                y += dy;
                manager.keepVisible(this);
            } else {
                width = Math.max(minWidth(), width + dx);
                height = Math.max(minHeight(), height + dy);
            }
            e.consume();
        } else if (c == UiCommand.ACCEPT || c == UiCommand.SUBMIT) {
            keyMode = 0;
            e.consume();
            changed();
        } else if (c == UiCommand.CANCEL) {
            keyMode = 0;
            x = startX;
            y = startY;
            width = startWidth;
            height = startHeight;
            e.consume();
        }
    }

    // ================================================================ the frame

    /** Sets the window's layout inputs for its place, size and mode (code-owned; its min-height follows the mode). */
    void assertLayout() {
        int r = root.slot();
        ui.ownLayout(r, Ui.CODE_KIND | Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        layout.kind(r, LayoutKind.ANCHORS);
        int f = frame.slot();
        ui.ownLayout(f, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
        layout.anchors(f, 0, 0, 0, 0);
        layout.offsets(f, 0, 0, 0, 0);
        boolean minimised = mode == WindowMode.MINIMISED;
        if (mode == WindowMode.MAXIMISED) {
            layout.offsets(r, 0, 0, 0, 0);
            layout.width(r, 100, Unit.PERCENT).height(r, 100, Unit.PERCENT);
            layout.width(f, 100, Unit.PERCENT).height(f, 100, Unit.PERCENT);
        } else {
            layout.offsets(r, x, y, 0, 0);
            layout.width(r, width, Unit.PX);
            layout.width(f, 100, Unit.PERCENT);
            if (minimised) {
                layout.height(r, 0, Unit.AUTO);
                layout.height(f, 0, Unit.AUTO);
                layout.minHeight(r, 0, Unit.AUTO); // as tall as its title bar, whatever its min-height
            } else {
                Length min = ui.styles().styleOf(r).get(Styles.MIN_HEIGHT);
                layout.minHeight(r, min.value(), UNITS[min.unit().ordinal()]);
                layout.height(r, height, Unit.PX);
                layout.height(f, 100, Unit.PERCENT);
            }
        }
        layout.visible(content.slot(), !minimised);
        layout.visible(buttons[0].slot(), minimisable);
        layout.visible(buttons[1].slot(), maximisable);
        layout.visible(buttons[2].slot(), closable);
        boolean edges = resizable && mode == WindowMode.NORMAL;
        float t = ui.styles().styleOf(r).getFloat(WindowStyles.RESIZE_HANDLE);
        float half = t * 0.5f;
        for (int i = 0; i < handles.length; i++) {
            int h = handles[i].slot();
            ui.ownLayout(h, Ui.CODE_PLACEMENT | Ui.CODE_WIDTH | Ui.CODE_HEIGHT);
            layout.visible(h, edges);
            int e = HANDLE_EDGES[i];
            float al = (e & RIGHT) != 0 ? 1 : 0;
            float at = (e & BOTTOM) != 0 ? 1 : 0;
            boolean horizontalEdge = (e & (LEFT | RIGHT)) == 0;
            boolean verticalEdge = (e & (TOP | BOTTOM)) == 0;
            if (horizontalEdge) {
                layout.anchors(h, 0, at, 1, at);
                layout.offsets(h, t, -half, -t, half);
            } else if (verticalEdge) {
                layout.anchors(h, al, 0, al, 1);
                layout.offsets(h, -half, t, half, -t);
            } else {
                layout.anchors(h, al, at, al, at);
                layout.offsets(h, -half, -half, half, half);
            }
            layout.width(h, 0, Unit.AUTO).height(h, 0, Unit.AUTO);
        }
    }

    float minWidth() {
        return px(ui.styles().styleOf(root.slot()).get(Styles.MIN_WIDTH));
    }

    float minHeight() {
        return px(ui.styles().styleOf(root.slot()).get(Styles.MIN_HEIGHT));
    }

    private static float px(Length length) {
        return length.unit() == Length.Unit.PX ? length.value() : 0;
    }

    private float scale() {
        return HostBox.scale(ui);
    }

    void changed() {
        manager.changed(this);
        listener.changed(this);
    }

    /** The docking source this window carries (a panel undocked into it), or null. */
    Object dockSource() {
        return dockSource;
    }

    void dockSource(Object value) {
        dockSource = value;
    }

    private void button(int button) {
        switch (button) {
            case 0 -> minimise();
            case 1 -> maximise();
            default -> requestClose();
        }
        if (button < 2) {
            changed();
        }
    }

    // ================================================================ events

    private final class RootListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            if (e.type() == EventType.PRESS) {
                manager.raise(FloatingWindow.this, false);
            } else if (e.type() == EventType.FOCUS_IN) {
                manager.activate(FloatingWindow.this);
            } else if (e.type() == EventType.COMMAND && keyMode != 0) {
                command(e);
            }
        }
    }

    private final class TitleListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            switch (e.type()) {
                case CLICK -> {
                    if (e.clickCount() == 2 && maximisable && e.pointerId() >= 0 && !onButton(e)) {
                        maximise();
                        changed();
                    }
                }
                case DRAG_START -> {
                    if (onButton(e)) {
                        return;
                    }
                    e.consume();
                    moving = true;
                    float s = scale();
                    if (mode == WindowMode.MAXIMISED) {
                        float hostLeft = HostBox.left(ui, host.slot());
                        float hostTop = HostBox.top(ui, host.slot());
                        float rootX = layout.x(root.slot());
                        float rootW = layout.width(root.slot());
                        float ratio = rootW > 0 ? (e.pressX() - rootX) / rootW : 0.5f;
                        mode(WindowMode.NORMAL); // back to its own size, under the pointer where it was pressed
                        x = (e.pressX() - hostLeft) / s - ratio * width;
                        y = (e.pressY() - hostTop) / s - (e.pressY() - layout.y(root.slot())) / s;
                    }
                    rememberStart();
                    move(e, s);
                }
                case DRAG -> {
                    if (moving) {
                        e.consume();
                        move(e, scale());
                    }
                }
                case DRAG_END -> {
                    if (moving) {
                        moving = false;
                        e.consume();
                        boolean docked = manager.windowDropped(FloatingWindow.this, e.x(), e.y(), e.modifiers());
                        if (!docked) {
                            changed();
                        }
                    }
                }
                default -> {
                }
            }
        }

        private void move(UiEvent e, float s) {
            float nx = startX + (e.x() - e.pressX()) / s;
            float ny = startY + (e.y() - e.pressY()) / s;
            manager.snapMove(FloatingWindow.this, nx, ny);
            manager.windowDragged(FloatingWindow.this, e.x(), e.y(), e.modifiers());
        }

        private boolean onButton(UiEvent e) {
            for (int s = e.targetSlot(); s != UiStore.NONE && s != titleBar.slot(); s = ui.store().parent(s)) {
                for (Element b : buttons) {
                    if (b.slot() == s) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final class HandleListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            int edges = 0;
            for (int i = 0; i < handles.length; i++) {
                if (handles[i].slot() == e.currentSlot()) {
                    edges = HANDLE_EDGES[i];
                }
            }
            switch (e.type()) {
                case DRAG_START -> {
                    e.consume();
                    dragEdges = edges;
                    rememberStart();
                    resize(e);
                }
                case DRAG -> {
                    if (dragEdges != 0) {
                        e.consume();
                        resize(e);
                    }
                }
                case DRAG_END -> {
                    if (dragEdges != 0) {
                        dragEdges = 0;
                        e.consume();
                        changed();
                    }
                }
                default -> {
                }
            }
        }

        private void resize(UiEvent e) {
            float s = scale();
            float dx = (e.x() - e.pressX()) / s;
            float dy = (e.y() - e.pressY()) / s;
            float left = startX;
            float top = startY;
            float right = startX + startWidth;
            float bottom = startY + startHeight;
            if ((dragEdges & LEFT) != 0) {
                left = manager.snapEdge(FloatingWindow.this, left + dx, true);
                left = Math.min(left, right - minWidth());
            }
            if ((dragEdges & RIGHT) != 0) {
                right = manager.snapEdge(FloatingWindow.this, right + dx, true);
                right = Math.max(right, left + minWidth());
            }
            if ((dragEdges & TOP) != 0) {
                top = manager.snapEdge(FloatingWindow.this, top + dy, false);
                top = Math.min(top, bottom - minHeight());
            }
            if ((dragEdges & BOTTOM) != 0) {
                bottom = manager.snapEdge(FloatingWindow.this, bottom + dy, false);
                bottom = Math.max(bottom, top + minHeight());
            }
            x = left;
            y = top;
            width = right - left;
            height = bottom - top;
        }
    }

    private final class ButtonListener implements UiListener {
        @Override
        public void handle(UiEvent e) {
            e.consume();
            for (int i = 0; i < buttons.length; i++) {
                if (buttons[i].slot() == e.currentSlot()) {
                    button(i);
                }
            }
        }
    }

    private final class Cancel implements Runnable {
        @Override
        public void run() {
            requestClose();
        }
    }

    @Override
    public String toString() {
        return "FloatingWindow[" + id + (open ? " open" : "") + " " + mode.key() + "]";
    }
}
