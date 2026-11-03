package com.truesim.ui.fonts.emoji;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.ColorBitmaps;
import com.truesim.ui.text.FontRegistry;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.natives.HarfBuzzShaper;
import com.truesim.ui.text.natives.NativeTextBackend;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Emoji through HarfBuzz and FreeType over the bundled Noto Color Emoji: shaping goldens (each sequence one glyph of
 * the emoji face, its id pinned), colour glyphs on sRGB colour pages at a pre-scaled level, and a CPU preview
 * ({@code TRUESIM_SCREENSHOTS=true}, into {@code TRUESIM_SCREENSHOTS_DIR}).
 */
class EmojiTest {

    private static final float INF = Float.POSITIVE_INFINITY;
    private static final List<String> SANS = List.of("sans-serif");
    private static final NativeTextBackend BACKEND = new NativeTextBackend();
    private static final FontRegistry FONTS = BundledEmojiFonts.registry(BACKEND);
    private static final String EMOJI = BundledEmojiFonts.FAMILY;

    private final UiTextures textures = new UiTextures();
    private final GlyphAtlas atlas = new GlyphAtlas(textures, BACKEND.msdfGenerator(), BACKEND.newRasterizer(), null,
        null, new AtlasSettings());
    private final TextSystem system = new TextSystem(FONTS, new HarfBuzzShaper(), atlas);

    @AfterAll
    static void close() {
        BACKEND.close();
    }

    private TextBlock block(String text, float size) {
        TextBlock b = new TextBlock(system);
        b.set(text, SANS, size, 400, false, TextAlign.START, INF, 0);
        return b;
    }

    /** The text's glyphs as "family:id" in visual order. */
    private String glyphs(String text) {
        TextBlock b = block(text, 32);
        StringBuilder out = new StringBuilder();
        for (int g = 0; g < b.glyphCount(); g++) {
            out.append(g == 0 ? "" : " ").append(b.glyphFace(g).source().family()).append(':').append(b.glyphId(g));
        }
        return out.toString();
    }

    @Test
    void sequencesShapeToOneColourGlyph() {
        assertThat(glyphs("\uD83D\uDE00")).isEqualTo(EMOJI + ":883");
        assertThat(glyphs("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66")).as("ZWJ family").isEqualTo(EMOJI + ":2032");
        assertThat(glyphs("\uD83D\uDC4D\uD83C\uDFFD")).as("skin tone").isEqualTo(EMOJI + ":1978");
        assertThat(glyphs("\uD83C\uDDE9\uD83C\uDDEA")).as("flag").isEqualTo(EMOJI + ":1605");
        assertThat(glyphs("1\uFE0F\u20E3")).as("keycap").isEqualTo(EMOJI + ":1494");
        assertThat(glyphs("\u2764\uFE0F")).as("VS16").isEqualTo(EMOJI + ":168");
        assertThat(glyphs("a\uD83D\uDE00 1")).as("text around: the digit and space stay text")
            .isEqualTo("Inter:507 " + EMOJI + ":883 Inter:1342");
        assertThat(glyphs("1")).as("a plain digit").startsWith("Inter:");
    }

    @Test
    void aSequenceIsOneCaretStep() {
        TextBlock b = block("a\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66b", 32);
        int family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66".length();
        assertThat(b.moveVisually(1, true)).isEqualTo(1 + family);
        assertThat(b.moveVisually(1 + family, false)).isEqualTo(1);
        assertThat(b.hitTest(b.caretX(1) + 3, 10)).isEqualTo(1);
        assertThat(b.caretX(1 + family) - b.caretX(1)).as("one emoji advance: 136/109 em")
            .isCloseTo(32 * 136 / 109f, org.assertj.core.data.Offset.offset(0.5f));
    }

    @Test
    void colourGlyphsDrawFromSrgbPagesAtAPreScaledLevel() throws InterruptedException {
        TextBlock b = block("\uD83D\uDE00", 14);
        DrawList list = new DrawList();
        int start = list.allocate(1);
        list.begin(start, 1);
        b.record(list, 0, 0, Rgba.pack(0.2f, 0.2f, 0.2f, 0.5f), 0, 0, 0);
        list.end();
        assertThat(list.floatAt(start, Primitive.X1)).as("a placeholder first").isEqualTo(list.floatAt(start,
            Primitive.X0));
        atlas.finishAll();
        assertThat(atlas.colorPageCount()).isEqualTo(1);
        UiTexture page = atlas.colorPage(0);
        assertThat(page.format()).isEqualTo(UiTexture.Format.RGBA8_SRGB);
        assertThat(atlas.pageCount()).as("the MTSDF page stays apart").isEqualTo(1);
        list.begin(start, 1);
        b.record(list, 0, 0, Rgba.pack(0.2f, 0.2f, 0.2f, 0.5f), 0, 0, 0);
        list.end();
        assertThat(Primitive.flags(list.header(start))).isEqualTo(Primitive.FLAG_COLOR);
        assertThat(list.intAt(start, Primitive.TEXTURE)).isEqualTo(page.id());
        long tint = list.colorAt(start, Primitive.COLOR_A);
        assertThat(Rgba.r(tint)).as("white: the emoji's own colours").isEqualTo(1);
        assertThat(Rgba.a(tint)).as("the text's alpha").isEqualTo(0.5f);
        float w = list.floatAt(start, Primitive.X1) - list.floatAt(start, Primitive.X0);
        assertThat(w).as("about one em and a quarter wide at 14 px").isBetween(15f, 21f);
        assertThat(ColorBitmaps.level(109, 14)).as("109 px halved twice: 27 px, under 2 texels a pixel").isEqualTo(2);
        int uvB = list.intAt(start, Primitive.UV + 1);
        int uvA = list.intAt(start, Primitive.UV);
        float texels = ((uvB & 0xFFFF) - (uvA & 0xFFFF)) / 65535f * page.width();
        assertThat(texels / w).as("texels per pixel").isBetween(1f, 2.2f);
        int opaque = 0;
        for (int i = 3; i < page.pixels().length; i += 4) {
            opaque += (page.pixels()[i] & 0xFF) == 255 ? 1 : 0;
        }
        assertThat(opaque).isGreaterThan(200);
    }

    @Test
    void preview() throws IOException, InterruptedException {
        String dir = System.getenv("TRUESIM_SCREENSHOTS_DIR");
        if (!"true".equals(System.getenv("TRUESIM_SCREENSHOTS")) || dir == null) {
            return;
        }
        int w = 900;
        int h = 560;
        float[] rgb = new float[w * h * 3];
        java.util.Arrays.fill(rgb, 1);
        DrawList list = new DrawList();
        String line = "Emoji \uD83D\uDE00 \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66 \uD83D\uDC4D\uD83C\uDFFD \uD83C\uDDE9\uD83C\uDDEA 1\uFE0F\u20E3 \u2764\uFE0F \uD83D\uDE80\u2728 text \u2764\uFE0E";
        float[] sizes = {10, 13, 16, 20, 28, 40, 64};
        TextBlock[] blocks = new TextBlock[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            blocks[i] = block(line, sizes[i]);
        }
        int start = list.allocate(4096);
        for (int pass = 0; pass < 2; pass++) { // the first records queue the glyphs
            list.begin(start, 4096);
            float y = 10;
            for (TextBlock b : blocks) {
                b.record(list, 10, y, Rgba.BLACK, 0, 0, 0);
                y += b.height() + 6;
            }
            list.end();
            atlas.finishAll();
        }
        SoftwareGlyphRenderer.render(list, textures, rgb, w, h);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 3;
                image.setRGB(x, y, enc(rgb[o]) << 16 | enc(rgb[o + 1]) << 8 | enc(rgb[o + 2]));
            }
        }
        Files.createDirectories(Path.of(dir));
        ImageIO.write(image, "png", Path.of(dir, "emoji-sizes.png").toFile());
    }

    private static int enc(float linear) {
        return Math.round(Rgba.toSrgb(Math.max(0, Math.min(1, linear))) * 255);
    }
}
