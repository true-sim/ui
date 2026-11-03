package com.truesim.ui.text.natives;

import static org.lwjgl.util.freetype.FreeType.FT_LOAD_RENDER;
import static org.lwjgl.util.freetype.FreeType.FT_LOAD_TARGET_NORMAL;
import static org.lwjgl.util.freetype.FreeType.FT_Load_Glyph;
import static org.lwjgl.util.freetype.FreeType.FT_Set_Pixel_Sizes;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.GlyphRasterizer;
import java.nio.ByteBuffer;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_GlyphSlot;

/**
 * FreeType's hinted, anti-aliased glyph bitmaps at one pixel size (normal hinting: the font's own instructions with
 * FreeType's default interpreter, or its auto-hinter): the small-text alternative that open decision UI16 compares
 * with MTSDF.
 */
@AllowAllocation("a comparison tool: a bitmap per glyph and size")
public final class FreeTypeRasterizer implements GlyphRasterizer {

    @Override
    public Coverage rasterize(FontFace face, int glyph, int sizePx) {
        NativeFontFace f = (NativeFontFace) face;
        synchronized (f) {
            if (FT_Set_Pixel_Sizes(f.ftFace(), 0, sizePx) != 0
                || FT_Load_Glyph(f.ftFace(), glyph, FT_LOAD_RENDER | FT_LOAD_TARGET_NORMAL) != 0) {
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
