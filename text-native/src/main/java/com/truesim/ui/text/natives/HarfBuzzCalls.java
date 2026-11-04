package com.truesim.ui.text.natives;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.lwjgl.util.harfbuzz.HarfBuzz;

/**
 * The HarfBuzz functions a reshape calls, each through a downcall handle made once, pointers passed as {@code long}s.
 * LWJGL wraps a function's address in a new {@code MemorySegment} on every call; the JIT removes that only where it
 * inlines the whole call, which differs between machines and JIT decisions (the engine's pitfall "LWJGL Vulkan calls
 * allocate per call", which its {@code Commands} answers the same way). With these, reshaping allocates nothing
 * whatever the JIT inlines.
 */
final class HarfBuzzCalls {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle CLEAR_CONTENTS = handle(HarfBuzz.Functions.buffer_clear_contents,
        FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle ADD_UTF16 = handle(HarfBuzz.Functions.buffer_add_utf16,
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle SET_DIRECTION = handle(HarfBuzz.Functions.buffer_set_direction,
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT));
    private static final MethodHandle SET_SCRIPT = handle(HarfBuzz.Functions.buffer_set_script,
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT));
    private static final MethodHandle UNICODE_SCRIPT = handle(HarfBuzz.Functions.unicode_script,
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SET_LANGUAGE = handle(HarfBuzz.Functions.buffer_set_language,
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GUESS_SEGMENT_PROPERTIES = handle(
        HarfBuzz.Functions.buffer_guess_segment_properties, FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle SHAPE = handle(HarfBuzz.Functions.shape,
        FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle GET_LENGTH = handle(HarfBuzz.Functions.buffer_get_length,
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle GET_GLYPH_INFOS = handle(HarfBuzz.Functions.buffer_get_glyph_infos,
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GET_GLYPH_POSITIONS = handle(HarfBuzz.Functions.buffer_get_glyph_positions,
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GET_NOMINAL_GLYPH = handle(HarfBuzz.Functions.font_get_nominal_glyph,
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));

    private HarfBuzzCalls() {
    }

    @AllowAllocation("made once per function")
    private static MethodHandle handle(long address, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(MemorySegment.ofAddress(address), descriptor);
    }

    static void clearContents(long buffer) {
        try {
            CLEAR_CONTENTS.invokeExact(buffer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void addUtf16(long buffer, long text, int length, int offset, int count) {
        try {
            ADD_UTF16.invokeExact(buffer, text, length, offset, count);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void setDirection(long buffer, int direction) {
        try {
            SET_DIRECTION.invokeExact(buffer, direction);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void setScript(long buffer, int script) {
        try {
            SET_SCRIPT.invokeExact(buffer, script);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** {@code hb_unicode_script}: the script of {@code codepoint} by {@code funcs}. */
    static int unicodeScript(long funcs, int codepoint) {
        try {
            return (int) UNICODE_SCRIPT.invokeExact(funcs, codepoint);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void setLanguage(long buffer, long language) {
        try {
            SET_LANGUAGE.invokeExact(buffer, language);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void guessSegmentProperties(long buffer) {
        try {
            GUESS_SEGMENT_PROPERTIES.invokeExact(buffer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static void shape(long font, long buffer, long features, int count) {
        try {
            SHAPE.invokeExact(font, buffer, features, count);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static int length(long buffer) {
        try {
            return (int) GET_LENGTH.invokeExact(buffer);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static long glyphInfos(long buffer) {
        try {
            return (long) GET_GLYPH_INFOS.invokeExact(buffer, 0L);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    static long glyphPositions(long buffer) {
        try {
            return (long) GET_GLYPH_POSITIONS.invokeExact(buffer, 0L);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** {@code hb_font_get_nominal_glyph}: non-zero if {@code font} has a glyph for {@code codepoint}, written to {@code glyph}. */
    static int nominalGlyph(long font, int codepoint, long glyph) {
        try {
            return (int) GET_NOMINAL_GLYPH.invokeExact(font, codepoint, glyph);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    @AllowAllocation("a native call failed: never in a working program")
    private static IllegalStateException failed(Throwable t) {
        return new IllegalStateException("HarfBuzz call failed", t);
    }
}
