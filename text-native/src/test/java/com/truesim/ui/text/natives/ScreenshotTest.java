package com.truesim.ui.text.natives;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.AtlasSettings;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.SoftwareGlyphRenderer;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * CPU previews of text through the real atlas and the shader's formula ({@link SoftwareGlyphRenderer}) over the bundled
 * fonts, and for UI16 the two small-text techniques ({@code ui.text.smallText} MTSDF and HINTED) one above the other.
 * Black on white, blended in linear light as the engine's UI pass does. Runs with {@code TRUESIM_SCREENSHOTS=true};
 * writes PNGs to {@code TRUESIM_SCREENSHOT_DIR} (default {@code build/screenshots}).
 */
class ScreenshotTest {

    private static final float INF = Float.POSITIVE_INFINITY;
    private static final String LATIN = "The quick brown fox jumps over the lazy dog. AV To Wa 0123456789";
    private static final String LIGATURES = "office affine fjord — a->b --> AV To Wa";
    private static final String ARABIC = "مرحبا بالعالم";
    private static final String DEVANAGARI = "नमस्ते दुनिया";
    private static final String CJK = "你好，世界 こんにちは";

    /** A linear RGB image, white. */
    private static final class Canvas {
        final int w;
        final int h;
        final float[] rgb;

        Canvas(int w, int h) {
            this.w = w;
            this.h = h;
            rgb = new float[w * h * 3];
            java.util.Arrays.fill(rgb, 1);
        }

        void save(Path file, int zoom) throws IOException {
            BufferedImage image = new BufferedImage(w * zoom, h * zoom, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h * zoom; y++) {
                for (int x = 0; x < w * zoom; x++) {
                    int o = ((y / zoom) * w + x / zoom) * 3;
                    image.setRGB(x, y, encode(rgb[o]) << 16 | encode(rgb[o + 1]) << 8 | encode(rgb[o + 2]));
                }
            }
            ImageIO.write(image, "png", file.toFile());
        }

        static int encode(float linear) {
            float c = Math.max(0, Math.min(1, linear));
            return Math.round(Rgba.toSrgb(c) * 255);
        }
    }

    private final UiTextures textures = new UiTextures();
    private final AtlasSettings settings = new AtlasSettings();
    private final GlyphAtlas atlas = new GlyphAtlas(textures, NativeFonts.BACKEND.msdfGenerator(),
        NativeFonts.BACKEND.newRasterizer(), null, null, settings);
    private final TextSystem system = new TextSystem(NativeFonts.REGISTRY, new HarfBuzzShaper(), atlas);

    private TextBlock block(String text, List<String> families, float size) {
        TextBlock b = new TextBlock(system);
        b.set(text, families, size, 400, false, TextAlign.START, INF, 0);
        try {
            atlas.finishAll();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return b;
    }

    /** Draws {@code b} as the frame would with the current settings (MTSDF, or raster where they say so). */
    private void draw(Canvas canvas, TextBlock b, float x, float y, long color, float outline, long outlineColor,
        float weight) {
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        list.begin(start, b.glyphCount());
        b.record(list, x, y, color, outline, outlineColor, weight); // looks the glyphs up: queued if new
        list.end();
        try {
            atlas.finishAll();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        list.begin(start, b.glyphCount());
        b.record(list, x, y, color, outline, outlineColor, weight);
        list.end();
        SoftwareGlyphRenderer.render(list, textures, canvas.rgb, canvas.w, canvas.h);
    }

    @Test
    void screenshots() throws IOException {
        assumeTrue("true".equals(System.getenv("TRUESIM_SCREENSHOTS")), "screenshots: TRUESIM_SCREENSHOTS=true");
        String dirName = System.getenv("TRUESIM_SCREENSHOT_DIR");
        Path dir = Files.createDirectories(Path.of(dirName == null || dirName.isEmpty() ? "build/screenshots"
            : dirName));
        List<String> latin = NativeFonts.LATIN;
        String[] texts = {LATIN, LIGATURES, ARABIC, DEVANAGARI, CJK};
        long black = Rgba.BLACK;

        // small sizes, MTSDF
        settings.smallText().select("MTSDF");
        Canvas small = new Canvas(560, 300);
        float y = 4;
        for (int size : new int[] {10, 12, 14}) {
            for (String text : texts) {
                TextBlock b = block(text, latin, size);
                draw(small, b, 6, y, black, 0, 0, 0);
                y += b.height();
            }
            y += 6;
        }
        small.save(dir.resolve("mtsdf-small.png"), 1);
        small.save(dir.resolve("mtsdf-small-x3.png"), 3);

        // large sizes, MTSDF, plus outline and weight from the same atlas
        Canvas large = new Canvas(1400, 560);
        y = 4;
        for (int size : new int[] {32, 64, 128}) {
            TextBlock b = block(size == 128 ? "Ag office مرحبا 你"
                : "AV To Wa office مرحبا नमस्ते 你好",
                latin, size);
            draw(large, b, 8, y, black, 0, 0, 0);
            y += b.height();
        }
        TextBlock effects = block("Outline  Bold  Thin", latin, 40);
        draw(large, effects, 8, 420, Rgba.pack(1, 0.8f, 0.1f, 1), 2.5f, black, 0);
        TextBlock bold = block("weight +0.6 px", latin, 24);
        draw(large, bold, 480, 430, black, 0, 0, 0.6f);
        TextBlock thin = block("weight -0.3 px", latin, 24);
        draw(large, thin, 480, 470, black, 0, 0, -0.3f);
        large.save(dir.resolve("mtsdf-large.png"), 1);

        // UI16: the same layout drawn with ui.text.smallText MTSDF (upper row) and HINTED (lower row, the default:
        // hinted raster below ui.text.hintedBelow, 13 px; 14 px is MTSDF in both), both through the atlas
        Canvas compare = new Canvas(470, 250);
        y = 4;
        for (int size : new int[] {10, 12, 14}) {
            for (String text : new String[] {LATIN, ARABIC + "   " + DEVANAGARI + "   " + CJK}) {
                TextBlock b = block(text, latin, size);
                settings.smallText().select("MTSDF");
                draw(compare, b, 6, y, black, 0, 0, 0);
                y += b.height();
                settings.smallText().select("HINTED");
                draw(compare, b, 6, y, black, 0, 0, 0);
                y += b.height() + 3;
            }
            y += 6;
        }
        compare.save(dir.resolve("ui16-small-mtsdf-vs-hinted.png"), 1);
        compare.save(dir.resolve("ui16-small-mtsdf-vs-hinted-x3.png"), 3);

        // the atlas page: colour channels and the true distance in alpha
        UiTexture page = atlas.page(0);
        BufferedImage rgb = new BufferedImage(page.width(), page.height(), BufferedImage.TYPE_INT_RGB);
        BufferedImage alpha = new BufferedImage(page.width(), page.height(), BufferedImage.TYPE_INT_RGB);
        byte[] p = page.pixels();
        for (int i = 0; i < page.width() * page.height(); i++) {
            int r = p[i * 4] & 0xFF;
            int g = p[i * 4 + 1] & 0xFF;
            int b = p[i * 4 + 2] & 0xFF;
            int a = p[i * 4 + 3] & 0xFF;
            rgb.setRGB(i % page.width(), i / page.width(), r << 16 | g << 8 | b);
            alpha.setRGB(i % page.width(), i / page.width(), a << 16 | a << 8 | a);
        }
        ImageIO.write(rgb, "png", dir.resolve("atlas-page.png").toFile());
        ImageIO.write(alpha, "png", dir.resolve("atlas-page-alpha.png").toFile());
        System.out.println("screenshots in " + dir.toAbsolutePath() + "; atlas: " + atlas.size() + " glyphs on "
            + atlas.pageCount() + " page(s)");
    }
}
