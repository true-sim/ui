package com.truesim.ui.a11y.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/** COM helpers: GUIDs, BSTRs, VARIANTs, vtables of upcall stubs. */
@SuppressWarnings("restricted") // FFM calls into the operating system
final class Com {

    private Com() {
    }

    /** A GUID as its 16 bytes in memory ({@code Data1..3} little-endian, {@code Data4} as written). */
    static MemorySegment guid(String text) {
        String hex = text.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException(text);
        }
        MemorySegment g = Arena.global().allocate(16, 8);
        g.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        g.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        g.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            g.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
        }
        return g;
    }

    /** Whether the GUID at {@code address} is {@code guid}. */
    static boolean same(MemorySegment address, MemorySegment guid) {
        return address.reinterpret(16).mismatch(guid) == -1;
    }

    /** A new BSTR holding {@code text} (the caller, or COM's receiver, frees it). */
    static MemorySegment bstr(String text) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment chars = arena.allocate(bytes.length + 2L, 2);
            MemorySegment.copy(bytes, 0, chars, JAVA_BYTE, 0, bytes.length);
            return (MemorySegment) Native.SYS_ALLOC_STRING_LEN.invokeExact(chars, text.length());
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** Reads the zero-terminated UTF-16 string at {@code address} (null for NULL). */
    static String wide(MemorySegment address) {
        if (address.address() == 0) {
            return null;
        }
        return address.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** Writes a pointer to {@code *out}. */
    static void out(MemorySegment out, long pointer) {
        out.reinterpret(8).set(JAVA_LONG, 0, pointer);
    }

    /** Writes an int to {@code *out}. */
    static void outInt(MemorySegment out, int value) {
        out.reinterpret(4).set(JAVA_INT, 0, value);
    }

    /** Clears a VARIANT ({@code VT_EMPTY}). */
    static void empty(MemorySegment variant) {
        variant.reinterpret(24).fill((byte) 0);
    }

    /** Sets a VARIANT to an int ({@code VT_I4}). */
    static void variantInt(MemorySegment variant, int value) {
        MemorySegment v = variant.reinterpret(24);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, Native.VT_I4);
        v.set(JAVA_INT, 8, value);
    }

    /** Sets a VARIANT to a boolean ({@code VT_BOOL}). */
    static void variantBool(MemorySegment variant, boolean value) {
        MemorySegment v = variant.reinterpret(24);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, Native.VT_BOOL);
        v.set(JAVA_SHORT, 8, value ? Native.VARIANT_TRUE : 0);
    }

    /** Sets a VARIANT to a new BSTR ({@code VT_BSTR}; whoever clears the VARIANT frees it). */
    static void variantString(MemorySegment variant, String value) {
        MemorySegment v = variant.reinterpret(24);
        v.fill((byte) 0);
        v.set(JAVA_SHORT, 0, Native.VT_BSTR);
        v.set(ADDRESS, 8, bstr(value));
    }

    /** Frees a VARIANT's BSTR if it holds one, and empties it. */
    static void clear(MemorySegment variant) {
        MemorySegment v = variant.reinterpret(24);
        if (v.get(JAVA_SHORT, 0) == Native.VT_BSTR) {
            try {
                Native.SYS_FREE_STRING.invokeExact(v.get(ADDRESS, 8));
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }
        v.fill((byte) 0);
    }

    /** A vtable of {@code stubs} in global memory (they live as long as the process). */
    static MemorySegment vtable(MemorySegment... stubs) {
        MemorySegment table = Arena.global().allocate(8L * stubs.length, 8);
        for (int i = 0; i < stubs.length; i++) {
            table.setAtIndex(ADDRESS, i, stubs[i]);
        }
        return table;
    }

    /** An upcall stub of {@code target}, alive as long as the process. */
    static MemorySegment stub(MethodHandle target, FunctionDescriptor descriptor) {
        return Native.LINKER.upcallStub(target, descriptor, Arena.global());
    }
}
