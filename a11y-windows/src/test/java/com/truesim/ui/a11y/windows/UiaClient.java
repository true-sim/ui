package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A UI Automation client for the tests, as a screen reader is one: {@code IUIAutomation} from COM through FFM,
 * elements found from a window, their current properties read, their patterns called. On the calling thread (COM
 * initialised as a multithreaded apartment there).
 */
@SuppressWarnings("restricted")
final class UiaClient implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());
    private static final SymbolLookup OLEAUT32 = SymbolLookup.libraryLookup("oleaut32", Arena.global());
    private static final MethodHandle CO_INITIALIZE = LINKER.downcallHandle(OLE32.find("CoInitializeEx").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_UNINITIALIZE = LINKER.downcallHandle(
        OLE32.find("CoUninitialize").orElseThrow(), FunctionDescriptor.ofVoid());
    private static final MethodHandle CO_CREATE = LINKER.downcallHandle(OLE32.find("CoCreateInstance").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle VARIANT_CLEAR = LINKER.downcallHandle(
        OLEAUT32.find("VariantClear").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SYS_FREE_STRING = LINKER.downcallHandle(
        OLEAUT32.find("SysFreeString").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS));

    static final MemorySegment CLSID_CUI_AUTOMATION = Com.guid("ff48dba4-60ef-4201-aa87-54103eef594e");
    static final MemorySegment IID_IUI_AUTOMATION = Com.guid("30cbe57d-d9d0-452a-ab13-7ac5ac4825ee");
    static final MemorySegment IID_INVOKE_PATTERN = Com.guid("fb377fbe-8ea6-46d5-9c73-6499642d3059");
    static final MemorySegment IID_TOGGLE_PATTERN = Com.guid("94cf8058-9b8d-4ab9-8bfd-4cd0a33c8c70");
    static final MemorySegment IID_VALUE_PATTERN = Com.guid("a94cd8b1-0844-4cd6-9d2d-640537ab39e9");

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment automation;
    private final List<MemorySegment> held = new ArrayList<>();

    UiaClient() {
        int hr = (int) invoke(CO_INITIALIZE, MemorySegment.NULL, 0);
        check(hr == 0 || hr == 1 ? 0 : hr, "CoInitializeEx");
        MemorySegment out = arena.allocate(ADDRESS);
        check((int) invoke(CO_CREATE, CLSID_CUI_AUTOMATION, MemorySegment.NULL, 1, IID_IUI_AUTOMATION, out),
            "CoCreateInstance(CUIAutomation)");
        automation = hold(out.get(ADDRESS, 0));
    }

    /** The element of window {@code hwnd} ({@code IUIAutomation::ElementFromHandle}). */
    MemorySegment fromWindow(long hwnd) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(call(automation, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS), hwnd, out),
            "ElementFromHandle");
        return hold(out.get(ADDRESS, 0));
    }

    /** Every element under {@code element} ({@code FindAll(TreeScope_Descendants, TrueCondition)}). */
    List<MemorySegment> descendants(MemorySegment element) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(call(automation, 21, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out), "CreateTrueCondition");
        MemorySegment condition = hold(out.get(ADDRESS, 0));
        check(call(element, 6, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), 4, condition,
            out), "FindAll");
        MemorySegment array = hold(out.get(ADDRESS, 0));
        List<MemorySegment> all = new ArrayList<>();
        if (array.address() == 0) {
            return all;
        }
        MemorySegment length = arena.allocate(JAVA_INT);
        check(call(array, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), length), "get_Length");
        for (int i = 0; i < length.get(JAVA_INT, 0); i++) {
            check(call(array, 4, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), i, out), "GetElement");
            all.add(hold(out.get(ADDRESS, 0)));
        }
        return all;
    }

    /** {@code GetCurrentPropertyValue}: a string, an int, a boolean, or null when empty. */
    Object property(MemorySegment element, int property) {
        MemorySegment v = arena.allocate(24, 8);
        check(call(element, 10, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), property, v),
            "GetCurrentPropertyValue " + property);
        try {
            short vt = v.get(JAVA_SHORT, 0);
            return switch (vt) {
                case 0 -> null;
                case 3 -> v.get(JAVA_INT, 8);
                case 5 -> v.get(JAVA_DOUBLE, 8);
                case 8 -> Com.wide(v.get(ADDRESS, 8));
                case 11 -> v.get(JAVA_SHORT, 8) != 0;
                default -> "vt " + vt;
            };
        } finally {
            invoke(VARIANT_CLEAR, v);
        }
    }

    /** The element's name ({@code UIA_NamePropertyId}). */
    String name(MemorySegment element) {
        return (String) property(element, UiaElement.PROPERTY_NAME);
    }

    /** {@code GetCurrentPatternAs(pattern, iid)}, or NULL when the element has no such pattern. */
    MemorySegment pattern(MemorySegment element, int pattern, MemorySegment iid) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(call(element, 14, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), pattern, iid,
            out), "GetCurrentPatternAs " + pattern);
        MemorySegment p = out.get(ADDRESS, 0);
        return p.address() == 0 ? p : hold(p);
    }

    /** {@code IUIAutomationInvokePattern::Invoke}. */
    void invoke(MemorySegment element) {
        MemorySegment p = pattern(element, UiaElement.PATTERN_INVOKE, IID_INVOKE_PATTERN);
        check(call(p, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS)), "Invoke");
    }

    /** {@code IUIAutomationTogglePattern::Toggle}. */
    void toggle(MemorySegment element) {
        MemorySegment p = pattern(element, UiaElement.PATTERN_TOGGLE, IID_TOGGLE_PATTERN);
        check(call(p, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS)), "Toggle");
    }

    /** {@code IUIAutomationValuePattern::SetValue}; returns the HRESULT. */
    int setValue(MemorySegment element, String value) {
        MemorySegment p = pattern(element, UiaElement.PATTERN_VALUE, IID_VALUE_PATTERN);
        MemorySegment bstr = Com.bstr(value);
        try {
            return call(p, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), bstr);
        } finally {
            invoke(SYS_FREE_STRING, bstr);
        }
    }

    /** {@code IUIAutomationElement::SetFocus}. */
    void focus(MemorySegment element) {
        check(call(element, 3, FunctionDescriptor.of(JAVA_INT, ADDRESS)), "SetFocus");
    }

    /** The runtime id ({@code GetRuntimeId}) as ints. */
    int[] runtimeId(MemorySegment element) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(call(element, 4, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out), "GetRuntimeId");
        MemorySegment array = out.get(ADDRESS, 0).reinterpret(32);
        // SAFEARRAY: cDims (2), fFeatures (2), cbElements (4), cLocks (4), pad, pvData (8 at 16), then bounds
        int count = array.get(JAVA_INT, 24);
        MemorySegment data = array.get(ADDRESS, 16).reinterpret(4L * count);
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = data.getAtIndex(JAVA_INT, i);
        }
        return ids;
    }

    /** {@code get_CurrentBoundingRectangle}: left, top, right, bottom. */
    double[] bounds(MemorySegment element) {
        MemorySegment rect = arena.allocate(16, 4);
        check(call(element, 43, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), rect), "BoundingRectangle");
        return new double[] {rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 4), rect.get(JAVA_INT, 8),
            rect.get(JAVA_INT, 12)};
    }

    /** {@code IUIAutomation::ElementFromPoint} at screen pixel ({@code x}, {@code y}). */
    MemorySegment fromPoint(int x, int y) {
        MemorySegment out = arena.allocate(ADDRESS);
        long point = (long) y << 32 | x & 0xffff_ffffL;
        check(call(automation, 7, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS), point, out),
            "ElementFromPoint");
        return hold(out.get(ADDRESS, 0));
    }

    @Override
    public void close() {
        for (int i = held.size() - 1; i >= 0; i--) {
            MemorySegment p = held.get(i);
            if (p.address() != 0) {
                call(p, 2, FunctionDescriptor.of(JAVA_INT, ADDRESS));
            }
        }
        held.clear();
        arena.close();
        invoke(CO_UNINITIALIZE);
    }

    private MemorySegment hold(MemorySegment p) {
        held.add(p);
        return p;
    }

    /** Calls method {@code index} of COM object {@code self}'s vtable. */
    static int call(MemorySegment self, int index, FunctionDescriptor d, Object... args) {
        MemorySegment vtable = self.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * (index + 1));
        MethodHandle h = LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, index), d);
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return (int) invoke(h, all);
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    static void check(int hr, String what) {
        if (hr != 0) {
            throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
        }
    }

    static String text(MemorySegment wide) {
        return wide.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }
}
