package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * The Windows functions the provider calls (user32, oleaut32, UIAutomationCore), bound once when first used: only
 * on Windows. Handles ({@code HWND}, {@code WPARAM}, {@code LPARAM}, {@code LRESULT}) pass as {@code long}s.
 */
@SuppressWarnings("restricted") // FFM calls into the operating system
final class Native {

    /** A {@code VARIANT}: {@code vt} and three reserved shorts, then 16 bytes of value (x64). */
    static final MemoryLayout VARIANT = MemoryLayout.structLayout(JAVA_SHORT.withName("vt"), JAVA_SHORT, JAVA_SHORT,
        JAVA_SHORT, JAVA_LONG.withName("value"), JAVA_LONG);

    static final short VT_EMPTY = 0;
    static final short VT_I4 = 3;
    static final short VT_BSTR = 8;
    static final short VT_BOOL = 11;
    static final short VARIANT_TRUE = -1;

    static final int S_OK = 0;
    static final int E_FAIL = 0x80004005;
    static final int E_NOINTERFACE = 0x80004002;
    static final int E_INVALIDARG = 0x80070057;
    static final int UIA_E_ELEMENTNOTAVAILABLE = 0x80040201;
    static final int UIA_E_ELEMENTNOTENABLED = 0x80040200;

    static final int WM_GETOBJECT = 0x003D;
    static final int WM_NCDESTROY = 0x0082;
    static final int GWLP_WNDPROC = -4;
    static final int UIA_ROOT_OBJECT_ID = -25;

    static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIBS = Arena.global();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", LIBS);
    private static final SymbolLookup OLEAUT32 = SymbolLookup.libraryLookup("oleaut32", LIBS);
    private static final SymbolLookup UIA = SymbolLookup.libraryLookup("UIAutomationCore", LIBS);

    static final MethodHandle SET_WINDOW_LONG_PTR = bind(USER32, "SetWindowLongPtrW",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
    static final MethodHandle CALL_WINDOW_PROC = bind(USER32, "CallWindowProcW",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG));
    static final MethodHandle CLIENT_TO_SCREEN = bind(USER32, "ClientToScreen",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));

    static final MethodHandle SYS_ALLOC_STRING_LEN = bind(OLEAUT32, "SysAllocStringLen",
        FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle SYS_FREE_STRING = bind(OLEAUT32, "SysFreeString",
        FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle SAFE_ARRAY_CREATE_VECTOR = bind(OLEAUT32, "SafeArrayCreateVector",
        FunctionDescriptor.of(ADDRESS, JAVA_SHORT, JAVA_INT, JAVA_INT));
    static final MethodHandle SAFE_ARRAY_ACCESS_DATA = bind(OLEAUT32, "SafeArrayAccessData",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle SAFE_ARRAY_UNACCESS_DATA = bind(OLEAUT32, "SafeArrayUnaccessData",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));

    static final MethodHandle RETURN_RAW_ELEMENT_PROVIDER = bind(UIA, "UiaReturnRawElementProvider",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
    static final MethodHandle HOST_PROVIDER_FROM_HWND = bind(UIA, "UiaHostProviderFromHwnd",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
    static final MethodHandle RAISE_AUTOMATION_EVENT = bind(UIA, "UiaRaiseAutomationEvent",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle RAISE_PROPERTY_CHANGED = bind(UIA, "UiaRaiseAutomationPropertyChangedEvent",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, VARIANT, VARIANT));
    static final MethodHandle CLIENTS_ARE_LISTENING = bind(UIA, "UiaClientsAreListening",
        FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle DISCONNECT_PROVIDER = bind(UIA, "UiaDisconnectProvider",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private Native() {
    }

    @com.truesim.annotations.AllowAllocation("bound once")
    private static MethodHandle bind(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    /** Whether assistive technology listens for UI Automation events (no allocation). */
    static boolean clientsListening() {
        try {
            return (int) CLIENTS_ARE_LISTENING.invokeExact() != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
