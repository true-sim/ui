package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** A plain Win32 window for the tests (hidden), made and pumped on the thread that calls it, through FFM. */
@SuppressWarnings("restricted")
final class TestWindow {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final MethodHandle REGISTER_CLASS = bind(USER32, "RegisterClassExW",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_WINDOW = bind(USER32, "CreateWindowExW",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle DESTROY_WINDOW = bind(USER32, "DestroyWindow",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle PEEK_MESSAGE = bind(USER32, "PeekMessageW",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE = bind(USER32, "TranslateMessage",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE = bind(USER32, "DispatchMessageW",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle MODULE_HANDLE = bind(KERNEL32, "GetModuleHandleW",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MemorySegment DEF_WINDOW_PROC = USER32.find("DefWindowProcW").orElseThrow();
    private static final String CLASS = "TrueSimUiaTest";
    private static boolean registered;

    private final Arena arena = Arena.ofConfined();
    private final MemorySegment message = arena.allocate(48, 8);
    final long hwnd;

    TestWindow(String title) {
        try {
            long instance = (long) MODULE_HANDLE.invokeExact(MemorySegment.NULL);
            MemorySegment className = wide(arena, CLASS);
            synchronized (TestWindow.class) {
                if (!registered) {
                    MemorySegment wc = Arena.global().allocate(80, 8);
                    wc.set(JAVA_INT, 0, 80);
                    wc.set(ADDRESS, 8, DEF_WINDOW_PROC);
                    wc.set(JAVA_LONG, 24, instance);
                    wc.set(ADDRESS, 64, wide(Arena.global(), CLASS));
                    if ((int) REGISTER_CLASS.invokeExact(wc) == 0) {
                        throw new IllegalStateException("RegisterClassExW failed");
                    }
                    registered = true;
                }
            }
            int overlapped = 0x00CF0000; // WS_OVERLAPPEDWINDOW, not visible
            hwnd = (long) CREATE_WINDOW.invokeExact(0, className, wide(arena, title), overlapped, 100, 100, 800, 600,
                0L, 0L, instance, MemorySegment.NULL);
            if (hwnd == 0) {
                throw new IllegalStateException("CreateWindowExW failed");
            }
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /** Handles every message waiting for this thread. */
    void pump() {
        try {
            while ((int) PEEK_MESSAGE.invokeExact(message, 0L, 0, 0, 1) != 0) {
                int ignored = (int) TRANSLATE_MESSAGE.invokeExact(message);
                long result = (long) DISPATCH_MESSAGE.invokeExact(message);
            }
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    void destroy() {
        try {
            int ok = (int) DESTROY_WINDOW.invokeExact(hwnd);
            pump();
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        } finally {
            arena.close();
        }
    }

    static MemorySegment wide(Arena arena, String text) {
        byte[] bytes = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment s = arena.allocate(bytes.length, 2);
        MemorySegment.copy(bytes, 0, s, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        return s;
    }

    private static MethodHandle bind(SymbolLookup lib, String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(), d);
    }
}
