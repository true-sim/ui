package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.truesim.ui.a11y.AccessibleTree;
import com.truesim.ui.core.Role;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * The provider's COM methods called as UI Automation calls them (through their vtables), for the answers a client
 * cannot reach in a hidden window or only reaches in error cases: hit tests, the root's focus and navigation, unknown
 * interfaces, patterns and properties, read-only and disabled refusals, elements that went, and reference counts.
 */
class ProviderCallsTest extends UiThreadTest {

    private static final FunctionDescriptor SELF = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SELF_OUT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor SELF_INT_OUT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    private final Arena arena = Arena.ofShared();

    private static MemorySegment at(long pointer) {
        return MemorySegment.ofAddress(pointer);
    }

    private AccessibleTree tree() throws Exception {
        return onUi(() -> {
            rig.bridge.refresh();
            return rig.bridge.tree();
        });
    }

    private int node(AccessibleTree tree, String name) {
        for (int n = 0; n < tree.count(); n++) {
            if (name.equals(tree.name(n))) {
                return n;
            }
        }
        throw new AssertionError(name);
    }

    private UiaElement element(String name) throws Exception {
        AccessibleTree t = tree();
        return access.element(t, node(t, name));
    }

    /** Calls method {@code index} of {@code element}'s interface {@code cell}, writing to a fresh out pointer. */
    private long out(UiaElement element, int cell, int index, int expected) {
        MemorySegment out = arena.allocate(8, 8);
        assertEquals(expected, UiaClient.call(at(element.pointer(cell)), index, SELF_OUT, out));
        return out.get(JAVA_LONG, 0);
    }

    private Object property(long simple, int property) {
        MemorySegment v = arena.allocate(24, 8);
        assertEquals(0, UiaClient.call(at(simple), 5, SELF_INT_OUT, property, v));
        Object result = switch (v.get(JAVA_SHORT, 0)) {
            case 0 -> null;
            case 3 -> v.get(JAVA_INT, 8);
            case 8 -> Com.wide(v.get(ADDRESS, 8));
            case 11 -> v.get(JAVA_SHORT, 8) != 0;
            default -> "?";
        };
        Com.clear(v);
        return result;
    }

    /** The SIMPLE interface of a returned fragment pointer (through QueryInterface). */
    private long simple(long fragment) {
        MemorySegment out = arena.allocate(8, 8);
        assertEquals(0, UiaClient.call(at(fragment), 0, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            UiaElement.IID_SIMPLE, out));
        return out.get(JAVA_LONG, 0);
    }

    @Test
    void theRootAnswersForTheWindow() throws Exception {
        tree();
        UiaElement root = access.rootElement();
        assertEquals(1, out(root, UiaElement.SIMPLE, 3, 0) & 0xffff_ffffL, "a server-side provider");
        assertNotEquals(0, out(root, UiaElement.SIMPLE, 6, 0), "the window's host provider");
        assertEquals(0, out(root, UiaElement.FRAGMENT, 4, 0), "the host gives the runtime id");
        assertEquals(0, out(root, UiaElement.FRAGMENT, 6, 0), "no embedded roots");
        for (int direction : new int[] {0, 1, 2, 9}) {
            MemorySegment o = arena.allocate(8, 8);
            assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.FRAGMENT)), 3, SELF_INT_OUT, direction, o));
            assertEquals(0, o.get(JAVA_LONG, 0), "the root has no parent nor siblings: " + direction);
        }
        MemorySegment first = arena.allocate(8, 8);
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.FRAGMENT)), 3, SELF_INT_OUT, 3, first));
        MemorySegment last = arena.allocate(8, 8);
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.FRAGMENT)), 3, SELF_INT_OUT, 4, last));
        assertEquals(first.get(JAVA_LONG, 0), last.get(JAVA_LONG, 0), "one child: the HUD's root");
        MemorySegment rect = arena.allocate(32, 8);
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.FRAGMENT)), 5, SELF_OUT, rect));
        assertEquals(0, rect.get(JAVA_DOUBLE, 16), "the host gives the rectangle");
        assertEquals(0, property(root.pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_NAME) == null ? 0 : 1);
        assertEquals(UiaElement.FRAMEWORK, property(root.pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_FRAMEWORK_ID));
        MemorySegment pattern = arena.allocate(8, 8);
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.SIMPLE)), 4, SELF_INT_OUT, UiaElement.PATTERN_INVOKE,
            pattern));
        assertEquals(0, pattern.get(JAVA_LONG, 0));
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.FRAGMENT)), 7, SELF), "focusing the root: nothing");
        assertEquals(0, UiaClient.call(at(root.pointer(UiaElement.INVOKE)), 3, SELF), "invoking the root: nothing");

        // the root's focus: none, then the field the UI focused
        assertEquals(0, out(root, UiaElement.ROOT, 4, 0));
        onUi(() -> rig.ui.interaction().focus(rig.name.element()));
        tree();
        long focused = out(root, UiaElement.ROOT, 4, 0);
        assertEquals("Name", property(simple(focused), UiaElement.PROPERTY_NAME));
    }

    @Test
    void hitTestsInScreenPixels() throws Exception {
        AccessibleTree t = tree();
        int ok = node(t, "OK");
        double[] origin = access.origin();
        MemorySegment out = arena.allocate(8, 8);
        double x = origin[0] + t.x(ok) + t.width(ok) / 2;
        double y = origin[1] + t.y(ok) + t.height(ok) / 2;
        assertEquals(0, UiaClient.call(at(access.rootElement().pointer(UiaElement.ROOT)), 3,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), x, y, out));
        long hit = out.get(JAVA_LONG, 0);
        assertNotEquals(0, hit);
        String name = (String) property(simple(hit), UiaElement.PROPERTY_NAME);
        assertTrue("OK".equals(name) || name == null, "the button or a part of it: " + name);
        assertEquals(0, UiaClient.call(at(access.rootElement().pointer(UiaElement.ROOT)), 3,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS), origin[0] - 50,
            origin[1] - 50, out));
        assertEquals(0, out.get(JAVA_LONG, 0), "outside: the root itself");
    }

    @Test
    void interfacesPatternsAndProperties() throws Exception {
        UiaElement ok = element("OK");
        MemorySegment out = arena.allocate(8, 8);
        FunctionDescriptor qi = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        assertEquals(Native.E_NOINTERFACE, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 0, qi,
            UiaElement.IID_FRAGMENT_ROOT, out), "only the root is a fragment root");
        assertEquals(0, out.get(JAVA_LONG, 0));
        assertEquals(Native.E_NOINTERFACE, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 0, qi,
            UiaClient.IID_IUI_AUTOMATION, out));
        for (MemorySegment iid : new MemorySegment[] {UiaElement.IID_IUNKNOWN, UiaElement.IID_FRAGMENT,
            UiaElement.IID_INVOKE, UiaElement.IID_TOGGLE, UiaElement.IID_VALUE}) {
            assertEquals(0, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 0, qi, iid, out));
            assertEquals(1, UiaClient.call(at(out.get(JAVA_LONG, 0)), 2, SELF) >= 0 ? 1 : 0);
        }
        int before = ok.references();
        assertEquals(before + 1, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 1, SELF));
        assertEquals(before, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 2, SELF));

        MemorySegment p = arena.allocate(8, 8);
        assertEquals(0, UiaClient.call(at(ok.pointer(UiaElement.SIMPLE)), 4, SELF_INT_OUT, 12345, p));
        assertEquals(0, p.get(JAVA_LONG, 0), "an unknown pattern");
        assertEquals(0, out(ok, UiaElement.SIMPLE, 6, 0), "only the root has a host");
        assertEquals(null, property(ok.pointer(UiaElement.SIMPLE), 30_999), "an unknown property");
        assertEquals(false, property(ok.pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_IS_OFFSCREEN));
        assertEquals(false, property(ok.pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_IS_DIALOG));
        assertEquals(null, property(ok.pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_LOCALIZED_CONTROL_TYPE));
        assertEquals(null, property(element("Hello").pointer(UiaElement.SIMPLE), UiaElement.PROPERTY_AUTOMATION_ID));
        assertEquals(0, out(ok, UiaElement.FRAGMENT, 6, 0), "no embedded roots");
        assertEquals(access.rootElement().pointer(UiaElement.ROOT), out(ok, UiaElement.FRAGMENT, 8, 0));

        // read only, disabled
        UiaElement loading = element("Loading");
        assertEquals(1, (int) out(loading, UiaElement.VALUE, 5, 0));
        MemorySegment text = TestWindow.wide(arena, "1");
        FunctionDescriptor setValue = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
        assertEquals(Native.UIA_E_ELEMENTNOTENABLED, UiaClient.call(at(loading.pointer(UiaElement.VALUE)), 3, setValue,
            text));
        UiaElement off = element("Off");
        assertEquals(Native.UIA_E_ELEMENTNOTENABLED, UiaClient.call(at(off.pointer(UiaElement.INVOKE)), 3, SELF));
        assertEquals(0, UiaClient.call(at(off.pointer(UiaElement.FRAGMENT)), 7, SELF), "focus is asked, not refused");
        assertEquals(0, (int) out(element("Agree"), UiaElement.TOGGLE, 4, 0));
        assertEquals(0, (int) out(element("Name"), UiaElement.VALUE, 5, 0));
    }

    @Test
    void elementsThatWentAreNotAvailable() throws Exception {
        UiaElement hello = element("Hello");
        UiaElement agree = element("Agree");
        UiaElement name = element("Name");
        assertEquals(0, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 1, SELF) - 1, "a reference held");
        onUi(() -> {
            rig.ui.destroy(rig.hello);
            rig.ui.destroy(rig.agree.element());
            rig.ui.destroy(rig.name.element());
            return null;
        });
        tree();
        onUi(() -> {
            access.update(); // sweeps the elements that went
            return null;
        });
        int unavailable = Native.UIA_E_ELEMENTNOTAVAILABLE;
        MemorySegment o = arena.allocate(32, 8);
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 4, SELF_INT_OUT,
            UiaElement.PATTERN_INVOKE, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 5, SELF_INT_OUT,
            UiaElement.PROPERTY_NAME, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.FRAGMENT)), 3, SELF_INT_OUT, 0, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.FRAGMENT)), 4, SELF_OUT, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.FRAGMENT)), 5, SELF_OUT, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.FRAGMENT)), 7, SELF));
        assertEquals(unavailable, UiaClient.call(at(agree.pointer(UiaElement.TOGGLE)), 3, SELF));
        assertEquals(unavailable, UiaClient.call(at(agree.pointer(UiaElement.TOGGLE)), 4, SELF_OUT, o));
        assertEquals(unavailable, UiaClient.call(at(name.pointer(UiaElement.VALUE)), 3,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), TestWindow.wide(arena, "x")));
        assertEquals(unavailable, UiaClient.call(at(name.pointer(UiaElement.VALUE)), 4, SELF_OUT, o));
        assertEquals(unavailable, UiaClient.call(at(name.pointer(UiaElement.VALUE)), 5, SELF_OUT, o));

        // the last reference lets the object go
        int live = WindowsAccessibility.liveObjects();
        assertEquals(0, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 2, SELF));
        assertEquals(live - 1, WindowsAccessibility.liveObjects());
        assertEquals(0, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 2, SELF), "a call after the end");
        assertEquals(Native.E_NOINTERFACE, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 0,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), UiaElement.IID_SIMPLE, o));
        assertEquals(unavailable, UiaClient.call(at(hello.pointer(UiaElement.SIMPLE)), 5, SELF_INT_OUT,
            UiaElement.PROPERTY_NAME, o), "an object unknown to the provider");
    }

    @Test
    void comHelpers() {
        assertThrows(IllegalArgumentException.class, () -> Com.guid("1234"));
        assertEquals(null, Com.wide(MemorySegment.NULL));
        assertEquals(ControlTypes.BUTTON, ControlTypes.of(Role.SWITCH));
        assertEquals("switch", ControlTypes.localized(Role.SWITCH));
        assertEquals("toggle button", ControlTypes.localized(Role.TOGGLE_BUTTON));
        assertEquals("alert", ControlTypes.localized(Role.ALERT));
        assertEquals("control", ControlTypes.localized(Role.NONE));
        RuntimeException r = new IllegalStateException("x");
        assertEquals(r, Native.rethrow(r));
        assertEquals(IllegalStateException.class, Native.rethrow(new Exception("checked")).getClass());
        assertThrows(StackOverflowError.class, () -> Native.rethrow(new StackOverflowError()));
    }
}
