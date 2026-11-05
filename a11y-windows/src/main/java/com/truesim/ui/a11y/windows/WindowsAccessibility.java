package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.a11y.AccessibilityBridge;
import com.truesim.ui.a11y.AccessibilityTunables;
import com.truesim.ui.a11y.AccessibleTree;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows UI Automation for a UI drawn in a window (design UI27): screen readers (Narrator, NVDA, JAWS) and other
 * assistive tools read the controls' names, roles, values, states and focus, and invoke, toggle, focus and set them.
 *
 * <p>{@link #attach} subclasses the window's procedure (on the window's thread) to answer {@code WM_GETOBJECT} with
 * the root of a provider tree: COM objects written with FFM ({@link UiaElement}) over the {@link AccessibilityBridge}'s
 * snapshots. The UI thread calls {@link #update()} once a frame: the bridge runs what was asked and, while a client
 * listens, follows changes; this raises UI Automation's focus-changed event (what a screen reader follows) and
 * property-changed events for names, values, toggle states and the enabled state. With no assistive technology, a
 * frame is a call asking Windows whether a client listens: no allocation. {@link AccessibilityTunables#BRIDGE} off
 * hides the UI from UI Automation.
 *
 * <p>Element boxes are the UI's pixels times {@link #scale} from the window's client area's top left.
 */
@SuppressWarnings("restricted") // FFM calls into the operating system
public final class WindowsAccessibility implements AutoCloseable {

    private static final Map<Long, WindowsAccessibility> WINDOWS = new ConcurrentHashMap<>();
    /** The procedure each subclassed window had before ours, kept while ours may still be called for it. */
    private static final Map<Long, Long> PREVIOUS = new ConcurrentHashMap<>();
    private static volatile MemorySegment procedure;

    private final long hwnd;
    private final AccessibilityBridge bridge;
    private final Thread windowThread;
    private final Map<Long, UiaElement> elements = new ConcurrentHashMap<>();
    private final UiaElement rootElement;
    private final MemorySegment event = Arena.ofAuto().allocate(Native.VARIANT);
    private final MemorySegment eventOld = Arena.ofAuto().allocate(Native.VARIANT);
    private volatile float scale = 1;
    private long previousProcedure;
    private boolean on;
    private boolean closed;

    private WindowsAccessibility(long hwnd, AccessibilityBridge bridge) {
        this.hwnd = hwnd;
        this.bridge = bridge;
        this.windowThread = Thread.currentThread();
        this.rootElement = new UiaElement(this, -1);
        bridge.listening(Native::clientsListening);
        bridge.events(new Events());
    }

    /** Whether UI Automation is there: Windows. */
    public static boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Exposes {@code bridge}'s UI through window {@code hwnd}; called on the window's thread (the one pumping its
     * messages), which should be the UI thread.
     */
    @AllowAllocation("once per window")
    public static WindowsAccessibility attach(long hwnd, AccessibilityBridge bridge) {
        if (!supported()) {
            throw new UnsupportedOperationException("UI Automation needs Windows");
        }
        Objects.requireNonNull(bridge);
        WindowsAccessibility a = new WindowsAccessibility(hwnd, bridge);
        if (WINDOWS.putIfAbsent(hwnd, a) != null) {
            throw new IllegalStateException("the window already has an accessibility bridge");
        }
        try {
            a.previousProcedure = (long) Native.SET_WINDOW_LONG_PTR.invokeExact(hwnd, Native.GWLP_WNDPROC,
                procedure().address());
        } catch (Throwable t) {
            WINDOWS.remove(hwnd);
            throw Native.rethrow(t);
        }
        PREVIOUS.put(hwnd, a.previousProcedure);
        a.on = AccessibilityTunables.BRIDGE.index() == 0;
        return a;
    }

    /** UI pixels to the window's client pixels (1 when the UI is drawn at the window's resolution); returns this. */
    public WindowsAccessibility scale(float value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("scale " + value);
        }
        scale = value;
        return this;
    }

    /** The UI thread's frame: see the class comment. */
    public void update() {
        if (closed) {
            return;
        }
        boolean wanted = AccessibilityTunables.BRIDGE.index() == 0;
        if (wanted != on) {
            on = wanted;
            if (!on) {
                withdraw();
            }
        }
        if (on && bridge.update()) {
            sweep();
        }
    }

    /** Stops answering, disconnects the provider and gives the window its procedure back (on the window's thread). */
    @Override
    @AllowAllocation("once per window")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        withdraw();
        try {
            long current = (long) Native.SET_WINDOW_LONG_PTR.invokeExact(hwnd, Native.GWLP_WNDPROC, previousProcedure);
            if (current != procedure().address()) {
                // subclassed after us: leave theirs in place; ours keeps forwarding to the procedure before it
                long ignored = (long) Native.SET_WINDOW_LONG_PTR.invokeExact(hwnd, Native.GWLP_WNDPROC, current);
            } else {
                PREVIOUS.remove(hwnd);
            }
        } catch (Throwable t) {
            throw Native.rethrow(t);
        } finally {
            WINDOWS.remove(hwnd);
        }
    }

    /** The bridge. */
    public AccessibilityBridge bridge() {
        return bridge;
    }

    /** The window. */
    public long hwnd() {
        return hwnd;
    }

    /** UI pixels to client pixels. */
    public float scale() {
        return scale;
    }

    /** COM objects alive for UI Automation (every bridge's), for tests and leak checks. */
    public static int liveObjects() {
        return UiaElement.live();
    }

    AccessibleTree tree() {
        bridge.asked();
        return bridge.tree();
    }

    UiaElement rootElement() {
        return rootElement;
    }

    /** The element object of {@code node} (the same object for the same element). */
    @AllowAllocation("UI Automation's calls, not the frame")
    UiaElement element(AccessibleTree tree, int node) {
        if (node == AccessibleTree.ROOT) {
            return rootElement;
        }
        return elements.computeIfAbsent(tree.key(node), k -> new UiaElement(this, k));
    }

    /** The client area's top left in screen pixels. */
    @AllowAllocation("UI Automation's calls, not the frame")
    double[] origin() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(8, 4);
            int ok = (int) Native.CLIENT_TO_SCREEN.invokeExact(hwnd, point);
            return ok == 0 ? new double[2] : new double[] {point.get(JAVA_INT, 0), point.get(JAVA_INT, 4)};
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** Kills the objects of elements that went (after a new snapshot). */
    @AllowAllocation("after a new snapshot, taken only while assistive technology listens")
    private void sweep() {
        AccessibleTree tree = bridge.tree();
        for (Iterator<UiaElement> it = elements.values().iterator(); it.hasNext(); ) {
            UiaElement e = it.next();
            if (tree == null || tree.find(e.key()) == AccessibleTree.NONE) {
                it.remove();
                e.kill();
            }
        }
    }

    private void withdraw() {
        try {
            long ignored = (long) Native.RETURN_RAW_ELEMENT_PROVIDER.invokeExact(hwnd, 0L, 0L, MemorySegment.NULL);
            int hr = (int) Native.DISCONNECT_PROVIDER.invokeExact(MemorySegment.ofAddress(
                rootElement.pointer(UiaElement.SIMPLE)));
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
        for (UiaElement e : elements.values()) {
            e.kill();
        }
        elements.clear();
    }

    // ================================================================ the window procedure

    private static MemorySegment procedure() {
        MemorySegment p = procedure;
        if (p == null) {
            synchronized (WindowsAccessibility.class) {
                p = procedure;
                if (p == null) {
                    try {
                        FunctionDescriptor d = FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
                            JAVA_LONG);
                        p = Com.stub(MethodHandles.lookup().findStatic(WindowsAccessibility.class, "windowProcedure",
                            d.toMethodType()), d);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    procedure = p;
                }
            }
        }
        return p;
    }

    @AllowAllocation("a window message for UI Automation, not the frame")
    private static long windowProcedure(long hwnd, int message, long wParam, long lParam) {
        WindowsAccessibility a = WINDOWS.get(hwnd);
        try {
            if (a == null) {
                Long previous = PREVIOUS.get(hwnd);
                return previous == null ? 0
                    : (long) Native.CALL_WINDOW_PROC.invokeExact((long) previous, hwnd, message, wParam, lParam);
            }
            if (message == Native.WM_GETOBJECT && (int) lParam == Native.UIA_ROOT_OBJECT_ID && a.on && !a.closed) {
                if (Thread.currentThread() == a.windowThread) {
                    a.bridge.refresh(); // answer from the present state
                }
                return (long) Native.RETURN_RAW_ELEMENT_PROVIDER.invokeExact(hwnd, wParam, lParam,
                    MemorySegment.ofAddress(a.rootElement.pointer(UiaElement.SIMPLE)));
            }
            long previous = a.previousProcedure;
            if (message == Native.WM_NCDESTROY) {
                a.close();
            }
            return (long) Native.CALL_WINDOW_PROC.invokeExact(previous, hwnd, message, wParam, lParam);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ================================================================ events

    /** Raises UI Automation's events for what the bridge saw change (UI thread, while a client listens). */
    private final class Events implements AccessibilityBridge.Events {

        @Override
        @AllowAllocation("a change while assistive technology listens, not every frame")
        public void focusChanged(AccessibleTree tree, int node) {
            if (node == AccessibleTree.NONE) {
                return;
            }
            try {
                int hr = (int) Native.RAISE_AUTOMATION_EVENT.invokeExact(
                    MemorySegment.ofAddress(element(tree, node).pointer(UiaElement.SIMPLE)),
                    UiaElement.EVENT_FOCUS_CHANGED);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        @Override
        @AllowAllocation("a change while assistive technology listens, not every frame")
        public void propertyChanged(AccessibleTree tree, int node, AccessibilityBridge.Property property,
            AccessibleTree old, int oldNode) {
            int id = switch (property) {
                case NAME -> UiaElement.PROPERTY_NAME;
                case VALUE -> UiaElement.PROPERTY_VALUE_VALUE;
                case TOGGLE_STATE -> UiaElement.PROPERTY_TOGGLE_STATE;
                case ENABLED -> UiaElement.PROPERTY_IS_ENABLED;
            };
            try {
                switch (property) {
                    case NAME -> {
                        Com.variantString(eventOld, Objects.requireNonNullElse(old.name(oldNode), ""));
                        Com.variantString(event, Objects.requireNonNullElse(tree.name(node), ""));
                    }
                    case VALUE -> {
                        Com.variantString(eventOld, Objects.requireNonNullElse(old.value(oldNode), ""));
                        Com.variantString(event, Objects.requireNonNullElse(tree.value(node), ""));
                    }
                    case TOGGLE_STATE -> {
                        Com.variantInt(eventOld, old.toggleState(oldNode));
                        Com.variantInt(event, tree.toggleState(node));
                    }
                    case ENABLED -> {
                        UiaElement.answer(old, oldNode, UiaElement.PROPERTY_IS_ENABLED, eventOld);
                        UiaElement.answer(tree, node, UiaElement.PROPERTY_IS_ENABLED, event);
                    }
                }
                int hr = (int) Native.RAISE_PROPERTY_CHANGED.invokeExact(
                    MemorySegment.ofAddress(element(tree, node).pointer(UiaElement.SIMPLE)), id, eventOld, event);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            } finally {
                Com.clear(eventOld);
                Com.clear(event);
            }
        }

        @Override
        public void structureChanged(AccessibleTree tree) {
            // UI Automation asks the fragments again as it walks; elements that went answer "not available"
        }
    }
}
