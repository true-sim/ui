package com.truesim.ui.text.natives;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.fonts.emoji.BundledEmojiFonts;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphRasterizer;
import org.junit.jupiter.api.Test;

/** FreeType's colour bitmaps of the bundled Noto Color Emoji (CBDT): straight-alpha sRGB at the 109 px strike. */
class ColorGlyphTest {

    @Test
    void anEmojiFontHasAColourStrikeAndBitmaps() {
        FontRegistry fonts = BundledEmojiFonts.registry(NativeFonts.BACKEND);
        NativeFontFace emoji = (NativeFontFace) fonts.match(BundledEmojiFonts.FAMILY, 400, false);
        assertThat(emoji.colorStrike()).isEqualTo(109);
        GlyphRasterizer rasterizer = NativeFonts.BACKEND.newRasterizer();
        GlyphRasterizer.ColorBitmap grin = rasterizer.color(emoji, emoji.glyph(0x1F600));
        assertThat(grin.width()).isEqualTo(136);
        assertThat(grin.height()).isEqualTo(128);
        assertThat(grin.top()).isEqualTo(101);
        assertThat(grin.strike()).isEqualTo(109);
        int opaque = 0;
        int clear = 0;
        int yellowish = 0;
        byte[] p = grin.pixels();
        for (int i = 0; i < p.length; i += 4) {
            int a = p[i + 3] & 0xFF;
            opaque += a == 255 ? 1 : 0;
            clear += a == 0 ? 1 : 0;
            if (a == 255 && (p[i] & 0xFF) > 200 && (p[i + 1] & 0xFF) > 150 && (p[i + 2] & 0xFF) < 100) {
                yellowish++;
            }
        }
        assertThat(opaque).isGreaterThan(5000);
        assertThat(clear).isGreaterThan(1000);
        assertThat(yellowish).as("a yellow face: red and green high, blue low (RGBA, not BGRA)").isGreaterThan(3000);
        assertThat(emoji.outline(emoji.glyph(0x1F600), new com.truesim.ui.text.GlyphOutline())).as("no outlines")
            .isFalse();
        NativeFontFace inter = NativeFonts.face("Inter");
        assertThat(inter.colorStrike()).isZero();
        assertThat(rasterizer.color(inter, inter.glyph('a'))).as("an outline face").isNull();
        assertThat(rasterizer.color(emoji, 0xFFFFF)).as("no such glyph").isNull();
    }
}
