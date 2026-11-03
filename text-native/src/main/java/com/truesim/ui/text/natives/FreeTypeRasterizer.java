package com.truesim.ui.text.natives;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_RENDER;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_TARGET_LIGHT;
import static org.lwjgl.util.freetype.FreeType.FT_Load_Glyph;
import static org.lwjgl.util.freetype.FreeType.FT_Set_Char_Size;
import static org.lwjgl.util.freetype.FreeType.FT_Set_Transform;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.GlyphRasterizer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FT_Vector;

/**
 * FreeType's hinted, anti-aliased glyph bitmaps at one pixel size: small text's raster glyphs (UI16). Light hinting
 * (vertical only: baselines, x-height and horizontal stems snap to the pixel grid, shapes keep their widths), then
 * the glyph moved by its subpixel phase and rendered: the hinting that suits pens placed by shaping to a quarter
 * pixel (normal hinting would also snap vertical stems, which the phase then moves off the grid again).
 * Thread-safe: each call holds its face's lock (the face's size, transform and glyph slot are shared state). LWJGL
 * ships its own FreeType build, the same version on every platform, so the bitmaps are the same everywhere.
 */
@AllowAllocation("a bitmap per new glyph and size, on the atlas's job")
public final class FreeTypeRasterizer implements GlyphRasterizer {

    @Override
    public Coverage rasterize(FontFace face, int glyph, float sizePx, float offsetX) {
        NativeFontFace f = (NativeFontFace) face;
        long size64 = (long) (sizePx * 64 + 0.5f); // 26.6 fixed point at 72 dpi: pixels per em to 1/64
        synchronized (f) {
            if (size64 <= 0 || FT_Set_Char_Size(f.ftFace(), 0, size64, 72, 72) != 0) {
                return null;
            }
            int error;
            try (MemoryStack stack = stackPush()) {
                // moved after hinting, before rendering: the subpixel phase; set back for the next call
                FT_Vector delta = FT_Vector.malloc(stack).x((long) (offsetX * 64 + 0.5f)).y(0);
                FT_Set_Transform(f.ftFace(), null, delta);
                error = FT_Load_Glyph(f.ftFace(), glyph, FT_LOAD_RENDER | FT_LOAD_TARGET_LIGHT);
                FT_Set_Transform(f.ftFace(), null, null);
            }
            if (error != 0) {
                return null;
            }
            FT_GlyphSlot slot = f.ftFace().glyph();
            FT_Bitmap bitmap = slot.bitmap();
            int w = bitmap.width();
            int h = bitmap.rows();
            if (w == 0 || h == 0) {
                return null;
            }
            int pitch = bitmap.pitch();
            ByteBuffer buffer = bitmap.buffer(Math.abs(pitch) * h);
            byte[] pixels = new byte[w * h];
            for (int y = 0; y < h; y++) {
                int row = pitch >= 0 ? y : h - 1 - y; // a negative pitch stores rows bottom up
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = buffer.get(row * Math.abs(pitch) + x);
                }
            }
            return new Coverage(w, h, slot.bitmap_left(), slot.bitmap_top(), pixels);
        }
    }
}
