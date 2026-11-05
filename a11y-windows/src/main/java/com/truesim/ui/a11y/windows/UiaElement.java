package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.a11y.AccessibilityBridge;
import com.truesim.ui.a11y.AccessibleTree;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One UI Automation element: a COM object written with FFM for a node of the {@link AccessibleTree} (by its element's
 * identity, so it stays the same object across snapshots), or for the window's root. Its interfaces are cells of 16
 * bytes, a vtable pointer and the object's id, so an upcall finds the object from {@code this}:
 * {@code IRawElementProviderSimple} (its identity, {@code IUnknown}), {@code IRawElementProviderFragment},
 * {@code IRawElementProviderFragmentRoot} (the root only), and the patterns {@code IInvokeProvider},
 * {@code IToggleProvider} and {@code IValueProvider}. Every method reads the bridge's newest snapshot; actions are
 * queued to the UI thread. Called on UI Automation's threads; an exception never leaves an upcall (it would end the
 * process): it becomes {@code E_FAIL}.
 */
@AllowAllocation("UI Automation's calls, on its threads: not the frame")
@SuppressWarnings("restricted") // FFM: COM objects and the pointers UI Automation hands over
final class UiaElement {

    static final int SIMPLE = 0;
    static final int FRAGMENT = 1;
    static final int ROOT = 2;
    static final int INVOKE = 3;
    static final int TOGGLE = 4;
    static final int VALUE = 5;
    private static final int CELLS = 6;
    private static final int CELL = 16;

    static final MemorySegment IID_IUNKNOWN = Com.guid("00000000-0000-0000-C000-000000000046");
    static final MemorySegment IID_SIMPLE = Com.guid("d6dd68d1-86fd-4332-8666-9abedea2d24c");
    static final MemorySegment IID_FRAGMENT = Com.guid("f7063da8-8359-439c-9297-bbc5299a7d87");
    static final MemorySegment IID_FRAGMENT_ROOT = Com.guid("620ce2a5-ab8f-40a9-86cb-de3c75599b58");
    static final MemorySegment IID_INVOKE = Com.guid("54fcb24b-e18e-47a2-b4d3-eccbe77599a2");
    static final MemorySegment IID_TOGGLE = Com.guid("56d00bd0-c4f4-433c-a836-1a52a57e0892");
    static final MemorySegment IID_VALUE = Com.guid("c7935180-6fb3-4201-b174-7df73adbf64a");

    // UI Automation's ids
    static final int PATTERN_INVOKE = 10000;
    static final int PATTERN_VALUE = 10002;
    static final int PATTERN_TOGGLE = 10015;
    static final int PROPERTY_CONTROL_TYPE = 30003;
    static final int PROPERTY_LOCALIZED_CONTROL_TYPE = 30004;
    static final int PROPERTY_NAME = 30005;
    static final int PROPERTY_HAS_KEYBOARD_FOCUS = 30008;
    static final int PROPERTY_IS_KEYBOARD_FOCUSABLE = 30009;
    static final int PROPERTY_IS_ENABLED = 30010;
    static final int PROPERTY_AUTOMATION_ID = 30011;
    static final int PROPERTY_IS_PASSWORD = 30019;
    static final int PROPERTY_IS_OFFSCREEN = 30022;
    static final int PROPERTY_FRAMEWORK_ID = 30024;
    static final int PROPERTY_VALUE_VALUE = 30045;
    static final int PROPERTY_TOGGLE_STATE = 30086;
    static final int PROPERTY_IS_DIALOG = 30174;
    static final int EVENT_FOCUS_CHANGED = 20005;
    private static final int PROVIDER_OPTIONS_SERVER_SIDE = 1;
    private static final int APPEND_RUNTIME_ID = 3;
    static final String FRAMEWORK = "true-sim";

    private static final ConcurrentHashMap<Long, UiaElement> OBJECTS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);
    private static final MemorySegment[] VTABLES = vtables();

    private final WindowsAccessibility owner;
    private final long key;
    private final long id;
    private final MemorySegment cells;
    private final AtomicInteger references = new AtomicInteger();
    private volatile boolean dead;

    UiaElement(WindowsAccessibility owner, long key) {
        this.owner = owner;
        this.key = key;
        this.id = IDS.getAndIncrement();
        this.cells = Arena.ofAuto().allocate((long) CELLS * CELL, 8);
        for (int i = 0; i < CELLS; i++) {
            cells.set(ADDRESS, (long) i * CELL, VTABLES[i]);
            cells.set(JAVA_LONG, (long) i * CELL + 8, id);
        }
        OBJECTS.put(id, this);
    }

    /** The element's identity ({@link AccessibleTree#key}); -1 for the root. */
    long key() {
        return key;
    }

    boolean root() {
        return key == -1;
    }

    /** Its interface {@code cell} as a pointer (without a reference). */
    long pointer(int cell) {
        return cells.address() + (long) cell * CELL;
    }

    /** Its interface {@code cell} as a pointer, with a reference for the receiver. */
    long handOut(int cell) {
        references.incrementAndGet();
        return pointer(cell);
    }

    /** The element went: every call fails; the object goes when the last reference does. */
    void kill() {
        dead = true;
        if (references.get() <= 0) {
            OBJECTS.remove(id);
        }
    }

    int references() {
        return references.get();
    }

    static int live() {
        return OBJECTS.size();
    }

    /** Its node in {@code tree}, or {@link AccessibleTree#NONE}. */
    private int node(AccessibleTree tree) {
        if (root()) {
            return AccessibleTree.ROOT;
        }
        return dead || tree == null ? AccessibleTree.NONE : tree.find(key);
    }

    private static UiaElement of(MemorySegment self) {
        return OBJECTS.get(self.reinterpret(CELL).get(JAVA_LONG, 8));
    }

    // ================================================================ IUnknown

    private static int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment out) {
        try {
            UiaElement e = of(self);
            int cell = e == null ? -1 : e.cellFor(iid);
            if (cell < 0) {
                Com.out(out, 0);
                return Native.E_NOINTERFACE;
            }
            Com.out(out, e.handOut(cell));
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private int cellFor(MemorySegment iid) {
        if (Com.same(iid, IID_IUNKNOWN) || Com.same(iid, IID_SIMPLE)) {
            return SIMPLE;
        }
        if (Com.same(iid, IID_FRAGMENT)) {
            return FRAGMENT;
        }
        if (Com.same(iid, IID_FRAGMENT_ROOT)) {
            return root() ? ROOT : -1;
        }
        if (Com.same(iid, IID_INVOKE)) {
            return INVOKE;
        }
        if (Com.same(iid, IID_TOGGLE)) {
            return TOGGLE;
        }
        if (Com.same(iid, IID_VALUE)) {
            return VALUE;
        }
        return -1;
    }

    private static int addRef(MemorySegment self) {
        UiaElement e = of(self);
        return e == null ? 1 : e.references.incrementAndGet();
    }

    private static int release(MemorySegment self) {
        UiaElement e = of(self);
        if (e == null) {
            return 0;
        }
        int left = e.references.decrementAndGet();
        if (left <= 0 && e.dead) {
            OBJECTS.remove(e.id);
        }
        return Math.max(left, 0);
    }

    // ================================================================ IRawElementProviderSimple

    private static int providerOptions(MemorySegment self, MemorySegment out) {
        Com.outInt(out, PROVIDER_OPTIONS_SERVER_SIDE);
        return Native.S_OK;
    }

    private static int patternProvider(MemorySegment self, int pattern, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            int n = e.node(tree);
            if (n == AccessibleTree.NONE) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (n == AccessibleTree.ROOT) {
                return Native.S_OK;
            }
            int cell = switch (pattern) {
                case PATTERN_INVOKE -> tree.can(n, Semantics.ACTIVATE) && !tree.toggles(n) ? INVOKE : -1;
                case PATTERN_TOGGLE -> tree.toggles(n) ? TOGGLE : -1;
                case PATTERN_VALUE -> tree.hasValue(n) ? VALUE : -1;
                default -> -1;
            };
            if (cell >= 0) {
                Com.out(out, e.handOut(cell));
            }
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int propertyValue(MemorySegment self, int property, MemorySegment out) {
        try {
            Com.empty(out);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            int n = e.node(tree);
            if (n == AccessibleTree.NONE) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (property == PROPERTY_FRAMEWORK_ID) {
                Com.variantString(out, FRAMEWORK);
                return Native.S_OK;
            }
            if (n == AccessibleTree.ROOT) {
                return Native.S_OK; // the window's host provider answers the rest
            }
            answer(tree, n, property, out);
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    /** Writes {@code property} of node {@code n} to {@code out} (left empty when it has none). */
    static void answer(AccessibleTree tree, int n, int property, MemorySegment out) {
        switch (property) {
            case PROPERTY_CONTROL_TYPE -> Com.variantInt(out, ControlTypes.of(tree.role(n)));
            case PROPERTY_LOCALIZED_CONTROL_TYPE -> {
                String localized = ControlTypes.localized(tree.role(n));
                if (localized != null) {
                    Com.variantString(out, localized);
                }
            }
            case PROPERTY_NAME -> {
                if (tree.name(n) != null) {
                    Com.variantString(out, tree.name(n));
                }
            }
            case PROPERTY_AUTOMATION_ID -> {
                if (tree.id(n) != null) {
                    Com.variantString(out, tree.id(n));
                }
            }
            case PROPERTY_HAS_KEYBOARD_FOCUS -> Com.variantBool(out, tree.focus() == n);
            case PROPERTY_IS_KEYBOARD_FOCUSABLE -> Com.variantBool(out, tree.can(n, Semantics.FOCUS));
            case PROPERTY_IS_ENABLED -> Com.variantBool(out, !tree.is(n, UiState.DISABLED));
            case PROPERTY_IS_PASSWORD -> Com.variantBool(out, tree.password(n));
            case PROPERTY_IS_OFFSCREEN -> Com.variantBool(out, false);
            case PROPERTY_IS_DIALOG -> Com.variantBool(out, tree.role(n) == com.truesim.ui.core.Role.DIALOG);
            default -> {
                // not ours: UI Automation supplies a default
            }
        }
    }

    private static int hostProvider(MemorySegment self, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e != null && e.root()) {
                return (int) Native.HOST_PROVIDER_FROM_HWND.invokeExact(e.owner.hwnd(), out);
            }
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    // ================================================================ IRawElementProviderFragment

    private static int navigate(MemorySegment self, int direction, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            int n = e.node(tree);
            if (n == AccessibleTree.NONE) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (tree == null) {
                return Native.S_OK;
            }
            int to = switch (direction) {
                case 0 -> n == AccessibleTree.ROOT ? AccessibleTree.NONE : tree.parent(n);
                case 1 -> n == AccessibleTree.ROOT ? AccessibleTree.NONE : tree.nextSibling(n);
                case 2 -> n == AccessibleTree.ROOT ? AccessibleTree.NONE : tree.previousSibling(n);
                case 3 -> tree.firstChild(n);
                case 4 -> tree.lastChild(n);
                default -> AccessibleTree.NONE;
            };
            if (to != AccessibleTree.NONE) {
                Com.out(out, e.owner.element(tree, to).handOut(FRAGMENT));
            }
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int runtimeId(MemorySegment self, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e == null || e.dead) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (e.root()) {
                return Native.S_OK; // the host's id
            }
            MemorySegment array = (MemorySegment) Native.SAFE_ARRAY_CREATE_VECTOR.invokeExact(Native.VT_I4, 0, 3);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(ADDRESS);
                int hr = (int) Native.SAFE_ARRAY_ACCESS_DATA.invokeExact(array, data);
                if (hr != Native.S_OK) {
                    return hr;
                }
                MemorySegment ints = data.get(ADDRESS, 0).reinterpret(12);
                ints.setAtIndex(JAVA_INT, 0, APPEND_RUNTIME_ID);
                ints.setAtIndex(JAVA_INT, 1, (int) (e.key >>> 32));
                ints.setAtIndex(JAVA_INT, 2, (int) e.key);
                hr = (int) Native.SAFE_ARRAY_UNACCESS_DATA.invokeExact(array);
                if (hr != Native.S_OK) {
                    return hr;
                }
            }
            Com.out(out, array.address());
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int boundingRectangle(MemorySegment self, MemorySegment out) {
        try {
            MemorySegment rect = out.reinterpret(32);
            rect.fill((byte) 0);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            int n = e.node(tree);
            if (n == AccessibleTree.NONE) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (n == AccessibleTree.ROOT) {
                return Native.S_OK; // the host's rectangle
            }
            double[] origin = e.owner.origin();
            double scale = e.owner.scale();
            rect.set(JAVA_DOUBLE, 0, origin[0] + tree.x(n) * scale);
            rect.set(JAVA_DOUBLE, 8, origin[1] + tree.y(n) * scale);
            rect.set(JAVA_DOUBLE, 16, tree.width(n) * scale);
            rect.set(JAVA_DOUBLE, 24, tree.height(n) * scale);
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int embeddedRoots(MemorySegment self, MemorySegment out) {
        Com.out(out, 0);
        return Native.S_OK;
    }

    private static int setFocus(MemorySegment self) {
        return request(self, AccessibilityBridge.Action.FOCUS, null);
    }

    private static int fragmentRoot(MemorySegment self, MemorySegment out) {
        try {
            UiaElement e = of(self);
            if (e == null) {
                Com.out(out, 0);
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            Com.out(out, e.owner.rootElement().handOut(ROOT));
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    // ================================================================ IRawElementProviderFragmentRoot

    private static int fromPoint(MemorySegment self, double x, double y, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            if (tree == null) {
                return Native.S_OK;
            }
            double[] origin = e.owner.origin();
            double scale = e.owner.scale();
            int n = tree.at((float) ((x - origin[0]) / scale), (float) ((y - origin[1]) / scale));
            if (n != AccessibleTree.ROOT) {
                Com.out(out, e.owner.element(tree, n).handOut(FRAGMENT));
            }
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int focus(MemorySegment self, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            if (e == null) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            AccessibleTree tree = e.owner.tree();
            if (tree != null && tree.focus() != AccessibleTree.NONE) {
                Com.out(out, e.owner.element(tree, tree.focus()).handOut(FRAGMENT));
            }
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    // ================================================================ patterns

    private static int invoke(MemorySegment self) {
        return request(self, AccessibilityBridge.Action.INVOKE, null);
    }

    private static int toggle(MemorySegment self) {
        return request(self, AccessibilityBridge.Action.TOGGLE, null);
    }

    private static int toggleState(MemorySegment self, MemorySegment out) {
        try {
            Com.outInt(out, 0);
            UiaElement e = of(self);
            AccessibleTree tree = e == null ? null : e.owner.tree();
            int n = e == null ? AccessibleTree.NONE : e.node(tree);
            if (n == AccessibleTree.NONE || n == AccessibleTree.ROOT) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            Com.outInt(out, tree.toggleState(n));
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int setValue(MemorySegment self, MemorySegment text) {
        try {
            UiaElement e = of(self);
            AccessibleTree tree = e == null ? null : e.owner.tree();
            int n = e == null ? AccessibleTree.NONE : e.node(tree);
            if (n == AccessibleTree.NONE || n == AccessibleTree.ROOT) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (tree.valueReadOnly(n) || tree.is(n, UiState.DISABLED)) {
                return Native.UIA_E_ELEMENTNOTENABLED;
            }
            return request(self, AccessibilityBridge.Action.SET_VALUE, Com.wide(text));
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int value(MemorySegment self, MemorySegment out) {
        try {
            Com.out(out, 0);
            UiaElement e = of(self);
            AccessibleTree tree = e == null ? null : e.owner.tree();
            int n = e == null ? AccessibleTree.NONE : e.node(tree);
            if (n == AccessibleTree.NONE || n == AccessibleTree.ROOT) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            String v = tree.value(n);
            Com.out(out, Com.bstr(v == null ? "" : v).address());
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int readOnly(MemorySegment self, MemorySegment out) {
        try {
            Com.outInt(out, 1);
            UiaElement e = of(self);
            AccessibleTree tree = e == null ? null : e.owner.tree();
            int n = e == null ? AccessibleTree.NONE : e.node(tree);
            if (n == AccessibleTree.NONE || n == AccessibleTree.ROOT) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            Com.outInt(out, tree.valueReadOnly(n) ? 1 : 0);
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    private static int request(MemorySegment self, AccessibilityBridge.Action action, String text) {
        try {
            UiaElement e = of(self);
            if (e == null || e.dead) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (e.root()) {
                return Native.S_OK;
            }
            AccessibleTree tree = e.owner.tree();
            int n = e.node(tree);
            if (n == AccessibleTree.NONE) {
                return Native.UIA_E_ELEMENTNOTAVAILABLE;
            }
            if (action != AccessibilityBridge.Action.FOCUS && tree.is(n, UiState.DISABLED)) {
                return Native.UIA_E_ELEMENTNOTENABLED;
            }
            e.owner.bridge().request(action, tree.slot(n), tree.generation(n), text);
            return Native.S_OK;
        } catch (Throwable t) {
            return Native.E_FAIL;
        }
    }

    // ================================================================ vtables

    private static MemorySegment[] vtables() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            var p = java.lang.foreign.ValueLayout.ADDRESS;
            var i = JAVA_INT;
            MemorySegment qi = stub(lookup, "queryInterface", FunctionDescriptor.of(i, p, p, p));
            MemorySegment addRef = stub(lookup, "addRef", FunctionDescriptor.of(i, p));
            MemorySegment release = stub(lookup, "release", FunctionDescriptor.of(i, p));
            FunctionDescriptor selfOut = FunctionDescriptor.of(i, p, p);
            FunctionDescriptor selfOnly = FunctionDescriptor.of(i, p);
            MemorySegment[] tables = new MemorySegment[CELLS];
            tables[SIMPLE] = Com.vtable(qi, addRef, release,
                stub(lookup, "providerOptions", selfOut),
                stub(lookup, "patternProvider", FunctionDescriptor.of(i, p, i, p)),
                stub(lookup, "propertyValue", FunctionDescriptor.of(i, p, i, p)),
                stub(lookup, "hostProvider", selfOut));
            tables[FRAGMENT] = Com.vtable(qi, addRef, release,
                stub(lookup, "navigate", FunctionDescriptor.of(i, p, i, p)),
                stub(lookup, "runtimeId", selfOut),
                stub(lookup, "boundingRectangle", selfOut),
                stub(lookup, "embeddedRoots", selfOut),
                stub(lookup, "setFocus", selfOnly),
                stub(lookup, "fragmentRoot", selfOut));
            tables[ROOT] = Com.vtable(qi, addRef, release,
                stub(lookup, "fromPoint", FunctionDescriptor.of(i, p, JAVA_DOUBLE, JAVA_DOUBLE, p)),
                stub(lookup, "focus", selfOut));
            tables[INVOKE] = Com.vtable(qi, addRef, release, stub(lookup, "invoke", selfOnly));
            tables[TOGGLE] = Com.vtable(qi, addRef, release, stub(lookup, "toggle", selfOnly),
                stub(lookup, "toggleState", selfOut));
            tables[VALUE] = Com.vtable(qi, addRef, release, stub(lookup, "setValue", selfOut),
                stub(lookup, "value", selfOut), stub(lookup, "readOnly", selfOut));
            return tables;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
        throws ReflectiveOperationException {
        MethodType type = descriptor.toMethodType();
        return Com.stub(lookup.findStatic(UiaElement.class, name, type), descriptor);
    }
}
