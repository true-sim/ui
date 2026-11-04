package com.truesim.ui.text.natives;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.freetype.FreeType.FT_CURVE_TAG;
import static org.lwjgl.util.freetype.FreeType.FT_CURVE_TAG_CONIC;
import static org.lwjgl.util.freetype.FreeType.FT_CURVE_TAG_CUBIC;
import static org.lwjgl.util.freetype.FreeType.FT_CURVE_TAG_ON;
import static org.lwjgl.util.freetype.FreeType.FT_Done_Face;
import static org.lwjgl.util.freetype.FreeType.FT_FACE_FLAG_COLOR;
import static org.lwjgl.util.freetype.FreeType.FT_GLYPH_FORMAT_OUTLINE;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_BITMAP;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_HINTING;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_NO_SCALE;
import static org.lwjgl.util.freetype.FreeType.FT_Load_Glyph;
import static org.lwjgl.util.freetype.FreeType.FT_New_Face;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_create_from_file_or_fail;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_face_get_upem;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_create;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_destroy;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_get_h_extents;
import static org.lwjgl.util.harfbuzz.HarfBuzz.hb_font_set_scale;
import static org.lwjgl.util.harfbuzz.HarfBuzz.nhb_font_get_nominal_glyph;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.GlyphOutline;
import java.io.IOException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_Bitmap_Size;
import org.lwjgl.util.freetype.FT_Outline;
import org.lwjgl.util.freetype.FT_Vector;
import org.lwjgl.util.harfbuzz.hb_font_extents_t;

/**
 * A font face opened twice over one file: by HarfBuzz for coverage, metrics and shaping (its face and font are
 * immutable, so shaping needs no lock), and by FreeType for outlines and hinted raster (a FreeType face is used by one
 * thread at a time: those calls synchronise on this object). HarfBuzz's scale is the font's units per em, so shaped
 * positions are exact font units.
 */
public final class NativeFontFace implements FontFace, AutoCloseable {

    private final int id;
    private final FontSource source;
    private final FT_Face ftFace;
    private final long hbFace;
    private final long hbFont;
    private final int unitsPerEm;
    private final float ascender;
    private final float descender;
    private final float lineGap;
    private final float colorStrike;
    private final int strikeIndex;
    private final long scratch = MemoryUtil.nmemAllocChecked(4); // a glyph id written by HarfBuzz
    private boolean closed;

    @AllowAllocation("a face per font, when it is first used")
    NativeFontFace(NativeTextBackend backend, FontSource source, int id) throws IOException {
        this.id = id;
        this.source = source;
        String path = source.path().toAbsolutePath().toString();
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            int error;
            synchronized (backend) { // FreeType's library makes one face at a time
                error = FT_New_Face(backend.library(), path, source.index(), p);
            }
            if (error != 0) {
                MemoryUtil.nmemFree(scratch);
                throw new IOException("FreeType cannot open " + source + ": error " + error);
            }
            ftFace = FT_Face.create(p.get(0));
            hbFace = hb_face_create_from_file_or_fail(path, source.index());
            if (hbFace == 0) {
                FT_Done_Face(ftFace);
                MemoryUtil.nmemFree(scratch);
                throw new IOException("HarfBuzz cannot open " + source);
            }
            unitsPerEm = hb_face_get_upem(hbFace);
            hbFont = hb_font_create(hbFace);
            hb_font_set_scale(hbFont, unitsPerEm, unitsPerEm);
            hb_font_extents_t extents = hb_font_extents_t.malloc(stack);
            hb_font_get_h_extents(hbFont, extents);
            ascender = extents.ascender() / (float) unitsPerEm;
            descender = -extents.descender() / (float) unitsPerEm;
            lineGap = extents.line_gap() / (float) unitsPerEm;
        }
        // colour bitmaps (CBDT, sbix): the largest strike; scaled outlines are not used for such a face
        float strike = 0;
        int index = -1;
        FT_Bitmap_Size.Buffer sizes = ftFace.available_sizes();
        if ((ftFace.face_flags() & FT_FACE_FLAG_COLOR) != 0 && sizes != null) {
            for (int i = 0; i < ftFace.num_fixed_sizes(); i++) {
                float ppem = sizes.get(i).y_ppem() / 64f;
                if (ppem > strike) {
                    strike = ppem;
                    index = i;
                }
            }
        }
        colorStrike = strike;
        strikeIndex = index;
    }

    /** The pixels per em of the colour bitmaps (the largest strike), or 0 for an outline face. */
    @Override
    public float colorStrike() {
        return colorStrike;
    }

    /** The FreeType strike the colour bitmaps are read from, or -1. */
    int strikeIndex() {
        return strikeIndex;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public FontSource source() {
        return source;
    }

    @Override
    public float ascender() {
        return ascender;
    }

    @Override
    public float descender() {
        return descender;
    }

    @Override
    public float lineGap() {
        return lineGap;
    }

    /** Font units per em: HarfBuzz's positions are in these. */
    public int unitsPerEm() {
        return unitsPerEm;
    }

    /** The HarfBuzz font, scaled to font units. */
    long hbFont() {
        return hbFont;
    }

    /** The FreeType face; use it synchronised on this object. */
    FT_Face ftFace() {
        return ftFace;
    }

    /** Coverage from the UI thread (the scratch word is not shared with other threads). */
    @Override
    public boolean covers(int codepoint) {
        return HarfBuzzCalls.nominalGlyph(hbFont, codepoint, scratch) != 0;
    }

    /** The nominal glyph of {@code codepoint} (before shaping), or 0; from the UI thread. */
    public int glyph(int codepoint) {
        return HarfBuzzCalls.nominalGlyph(hbFont, codepoint, scratch) != 0 ? MemoryUtil.memGetInt(scratch) : 0;
    }

    /**
     * Reads the glyph's unscaled, unhinted outline from FreeType and converts it as {@code FT_Outline_Decompose} does:
     * on-curve points, conic (quadratic) control points with implied on-curve midpoints, cubic control pairs.
     */
    @Override
    @AllowAllocation("once per new glyph: LWJGL wraps the glyph slot")
    public synchronized boolean outline(int glyph, GlyphOutline out) {
        out.reset();
        if (closed || FT_Load_Glyph(ftFace, glyph, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) != 0) {
            return false;
        }
        FT_GlyphSlot slot = ftFace.glyph();
        if (slot == null || slot.format() != FT_GLYPH_FORMAT_OUTLINE) {
            return false;
        }
        long outline = slot.address() + FT_GlyphSlot.OUTLINE;
        int contours = FT_Outline.nn_contours(outline) & 0xFFFF;
        if (contours == 0) {
            return false;
        }
        long points = FT_Outline.npoints(outline).address();
        long tags = MemoryUtil.memAddress(FT_Outline.ntags(outline));
        long ends = MemoryUtil.memAddress(FT_Outline.ncontours(outline));
        new Decomposer(out, points, tags, 1f / unitsPerEm).contours(ends, contours);
        return !out.isEmpty();
    }

    /**
     * {@code FT_Outline_Decompose} over FreeType's raw arrays: {@code points} ({@code FT_Vector}s), {@code tags} (one
     * byte each) and the contours' last point indices (unsigned shorts).
     */
    static final class Decomposer {
        private final GlyphOutline out;
        private final long points;
        private final long tags;
        private final float scale;
        private float curX;
        private float curY;

        Decomposer(GlyphOutline out, long points, long tags, float scale) {
            this.out = out;
            this.points = points;
            this.tags = tags;
            this.scale = scale;
        }

        private float x(int i) {
            return FT_Vector.nx(points + (long) i * FT_Vector.SIZEOF) * scale;
        }

        private float y(int i) {
            return FT_Vector.ny(points + (long) i * FT_Vector.SIZEOF) * scale;
        }

        private int tag(int i) {
            return FT_CURVE_TAG(MemoryUtil.memGetByte(tags + i));
        }

        void contours(long ends, int count) {
            int first = 0;
            for (int n = 0; n < count; n++) {
                int last = MemoryUtil.memGetShort(ends + 2L * n) & 0xFFFF;
                contour(first, last);
                first = last + 1;
            }
        }

        private void contour(int first, int last) {
            int limit = last;
            float startX = x(first);
            float startY = y(first);
            int p = first;
            int tag = tag(first);
            if (tag == FT_CURVE_TAG_CUBIC) {
                return; // invalid: a contour cannot start with a cubic control point
            }
            if (tag == FT_CURVE_TAG_CONIC) {
                if (tag(last) == FT_CURVE_TAG_ON) { // start at the last point
                    startX = x(last);
                    startY = y(last);
                    limit--;
                } else { // both off: start between them
                    startX = (startX + x(last)) * 0.5f;
                    startY = (startY + y(last)) * 0.5f;
                }
                p--;
            }
            out.moveTo(startX, startY);
            curX = startX;
            curY = startY;
            while (p < limit) {
                p++;
                tag = tag(p);
                if (tag == FT_CURVE_TAG_ON) {
                    line(x(p), y(p));
                } else if (tag == FT_CURVE_TAG_CONIC) {
                    float cx = x(p);
                    float cy = y(p);
                    boolean closedByConic = true;
                    while (p < limit) {
                        p++;
                        float vx = x(p);
                        float vy = y(p);
                        if (tag(p) == FT_CURVE_TAG_ON) {
                            quad(cx, cy, vx, vy);
                            closedByConic = false;
                            break;
                        }
                        if (tag(p) != FT_CURVE_TAG_CONIC) {
                            return; // invalid
                        }
                        quad(cx, cy, (cx + vx) * 0.5f, (cy + vy) * 0.5f);
                        cx = vx;
                        cy = vy;
                    }
                    if (closedByConic) {
                        quad(cx, cy, startX, startY);
                        return;
                    }
                } else {
                    if (p + 1 > limit || tag(p + 1) != FT_CURVE_TAG_CUBIC) {
                        return; // invalid
                    }
                    float ax = x(p);
                    float ay = y(p);
                    float bx = x(p + 1);
                    float by = y(p + 1);
                    p += 2;
                    if (p <= limit) {
                        out.cubicTo(ax, ay, bx, by, x(p), y(p));
                        curX = x(p);
                        curY = y(p);
                    } else {
                        out.cubicTo(ax, ay, bx, by, startX, startY);
                        return;
                    }
                }
            }
            line(startX, startY); // close
        }

        private void line(float x, float y) {
            if (x != curX || y != curY) { // no zero-length edges: they upset edge colouring
                out.lineTo(x, y);
                curX = x;
                curY = y;
            }
        }

        private void quad(float cx, float cy, float x, float y) {
            out.quadTo(cx, cy, x, y);
            curX = x;
            curY = y;
        }
    }

    /** Frees FreeType's and HarfBuzz's faces. */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            hb_font_destroy(hbFont);
            hb_face_destroy(hbFace);
            FT_Done_Face(ftFace);
            MemoryUtil.nmemFree(scratch);
        }
    }

    @Override
    public String toString() {
        return "NativeFontFace[" + id + " " + source + "]";
    }
}
