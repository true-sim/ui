package com.truesim.ui.text.natives;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.freetype.FreeType.FT_Done_FreeType;
import static org.lwjgl.util.freetype.FreeType.FT_Init_FreeType;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.GlyphRasterizer;
import com.truesim.ui.text.MsdfGenerator;
import com.truesim.ui.text.TextBackend;
import com.truesim.ui.text.TextShaper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * The text backend of the engine (UI9, UI10, UI11): FreeType faces and outlines, HarfBuzz shaping, msdfgen MTSDF
 * glyphs, FreeType's hinted raster for the small-text comparison, all through LWJGL. Owns its FreeType library and the
 * faces it opened; {@link #close()} frees them.
 */
public final class NativeTextBackend implements TextBackend, AutoCloseable {

    private final long library;
    private final List<NativeFontFace> faces = new ArrayList<>();
    private final MsdfgenGenerator generator = new MsdfgenGenerator();
    private boolean closed;

    /** Starts FreeType. */
    @AllowAllocation("made once")
    public NativeTextBackend() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            int error = FT_Init_FreeType(p);
            if (error != 0) {
                throw new IllegalStateException("FreeType did not start: error " + error);
            }
            library = p.get(0);
        }
    }

    /** The FreeType library: faces open through it, one at a time. */
    long library() {
        return library;
    }

    @Override
    @AllowAllocation("a face per font, when it is first used")
    public synchronized FontFace open(FontSource source, int id) throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        NativeFontFace face = new NativeFontFace(this, source, id);
        faces.add(face);
        return face;
    }

    @Override
    @AllowAllocation("a shaper per thread, once")
    public TextShaper newShaper() {
        return new HarfBuzzShaper();
    }

    @Override
    public MsdfGenerator msdfGenerator() {
        return generator;
    }

    @Override
    @AllowAllocation("a rasterizer per thread, once")
    public GlyphRasterizer newRasterizer() {
        return new FreeTypeRasterizer();
    }

    /** Frees every face and FreeType. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (NativeFontFace face : faces) {
            face.close();
        }
        faces.clear();
        FT_Done_FreeType(library);
    }
}
