package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import java.lang.Character.UnicodeScript;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rich text (style runs), editing support (visual caret moves, range boxes, line ranges), emoji itemisation and colour
 * glyph bitmaps, over the fake fonts (one glyph per character, half an em wide).
 */
class RichTextBlockTest {

    private static final float INF = Float.POSITIVE_INFINITY;
    private static final List<String> LATIN = List.of("Latin");
    private static final List<String> BOLD = List.of("Latin");

    @TempDir
    Path dir;
    private FontRegistry fonts;
    private GlyphAtlas atlas;
    private TextSystem system;

    @BeforeEach
    void setUp() {
        fonts = new FontRegistry(new Fakes.Backend());
        fonts.register(Fakes.source(dir, "latin.ttf", "Latin", 400, false));
        fonts.register(Fakes.source(dir, "latin-bold.ttf", "Latin", 700, false));
        fonts.register(Fakes.source(dir, "hebrew.ttf", "Hebrew", 400, false));
        fonts.register(Fakes.source(dir, "emoji.ttf", "Emoji", 400, false));
        fonts.register(Fakes.source(dir, "symbols.ttf", "Symbols", 400, false));
        fonts.fallback(UnicodeScript.HEBREW, "Hebrew");
        fonts.lastResort("Symbols");
        fonts.emoji("Emoji");
        atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, new AtlasSettings());
        system = new TextSystem(fonts, new Fakes.Shaper(), atlas);
    }

    @Test
    void runsOfOneFontShapeTogetherAndTallerRunsMakeTallerLines() {
        TextRuns runs = new TextRuns();
        runs.add(0, LATIN, 10, 400, false);
        runs.color(runs.add(2, LATIN, 10, 400, false), Rgba.pack(1, 0, 0, 1)).decoration(1, TextRuns.UNDERLINE);
        int big = runs.add(4, BOLD, 20, 700, false);
        runs.background(big, Rgba.pack(1, 1, 0, 1)).tag(big, 7);
        runs.changed();
        TextBlock b = new TextBlock(system);
        assertThat(b.set("abcdef\ngh", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null)).isTrue();
        assertThat(b.set("abcdef\ngh", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null)).isFalse();
        assertThat(b.runs()).isSameAs(runs);
        assertThat(b.caretX(4)).as("a colour change keeps the pen going").isEqualTo(4 * 5);
        assertThat(b.caretX(5)).as("the large run: 10 px a glyph").isEqualTo(30);
        assertThat(b.glyphSize(4)).isEqualTo(20);
        assertThat(b.glyphCluster(5)).isEqualTo(5);
        // line box: the large run's (0.8 + 0.2 + 0.1) em = 22 px; the second line is all large too
        assertThat(b.lineHeight(0)).isEqualTo(22);
        assertThat(b.lineTop(1)).isEqualTo(22);
        assertThat(b.lineHeight(1)).isEqualTo(22);
        assertThat(b.height()).isEqualTo(44);
        assertThat(b.lineAt(30)).isEqualTo(1);
        assertThat(b.lineAt(-5)).isZero();
        assertThat(b.hitTest(1, 30)).isEqualTo(7);
        assertThat(runs.runAt(3)).isEqualTo(1);
        assertThat(runs.runAt(100)).isEqualTo(2);
        assertThat(runs.tag(big)).isEqualTo(7);
        assertThat(runs.decoration(1)).isEqualTo(TextRuns.UNDERLINE);
        assertThat(runs.background(big)).isNotZero();
        runs.color(0, Rgba.pack(0, 1, 0, 1));
        runs.changed();
        assertThat(b.set("abcdef\ngh", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null)).as("new version")
            .isTrue();

        DrawList list = record(b);
        assertThat(list.colorAt(0, Primitive.COLOR_A)).as("run 0's own colour, faded").isEqualTo(Rgba.pack(0, 1, 0,
            0.5f));
        assertThat(list.colorAt(4, Primitive.COLOR_A)).as("no colour: the block's").isEqualTo(Rgba.BLACK);
        assertThat(list.colorAt(2, Primitive.COLOR_A)).as("a colour change within a shaping run")
            .isEqualTo(Rgba.pack(1, 0, 0, 0.5f));
        assertThat(list.floatAt(4, Primitive.PIXEL_RANGE)).as("the run's size").isEqualTo(6 * 20 / 40f);

        DrawList second = new DrawList();
        int start = second.allocate(8);
        second.begin(start, 8);
        b.recordLines(second, 0, 0, Rgba.BLACK, 1, 1, 2);
        second.end();
        assertThat(second.needed()).as("only the second line").isEqualTo(2);
        second.begin(start, 8);
        b.recordLines(second, 0, 0, Rgba.BLACK, 1, 5, 9);
        second.end();
        assertThat(second.needed()).isZero();
    }

    private DrawList record(TextBlock b) {
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        for (int pass = 0; pass < 2; pass++) {
            atlas.beginFrame();
            list.begin(start, b.glyphCount());
            b.recordLines(list, 0, 0, Rgba.BLACK, 0.5f, 0, b.lineCount());
            list.end();
        }
        return list;
    }

    @Test
    void inlineObjectsTakeTheirBoxAndDrawNothing() {
        TextRuns runs = new TextRuns();
        runs.add(0, LATIN, 10, 400, false);
        runs.object(runs.add(1, LATIN, 10, 400, false), 24, 30);
        runs.add(2, LATIN, 10, 400, false);
        TextBlock b = new TextBlock(system);
        b.set("a\uFFFCb", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null);
        assertThat(b.caretX(2) - b.caretX(1)).isEqualTo(24);
        assertThat(b.glyphId(1)).as("an object has no glyph").isEqualTo(-1);
        assertThat(b.lineHeight(0)).as("30 px above the baseline plus the font's below").isGreaterThan(30);
        assertThat(b.lineBaseline(0)).isEqualTo(30);
        DrawList list = record(b);
        assertThat(list.needed()).isEqualTo(2);
        assertThat(runs.isObject(1)).isTrue();
        assertThat(runs.objectWidth(1)).isEqualTo(24);
        assertThat(runs.objectHeight(1)).isEqualTo(30);
        assertThatThrownBy(() -> runs.object(0, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.add(0, LATIN, 10, 400, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runs.add(5, LATIN, 0, 400, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextBlock(system).set("x", new TextRuns(), TextAlign.START, INF, 0,
            TextDirection.AUTO, null)).isInstanceOf(IllegalArgumentException.class);
        TextRuns many = new TextRuns();
        for (int i = 0; i < 20; i++) {
            many.add(i, i % 2 == 0 ? LATIN : BOLD, 10 + i, 400, i % 3 == 0);
        }
        assertThat(many.count()).isEqualTo(20);
        assertThat(many.italic(3)).isTrue();
        assertThat(many.weight(3)).isEqualTo(400);
        assertThat(many.hasColor(3)).isFalse();
        TextBlock longer = new TextBlock(system);
        longer.set("abcdefghijklmnopqrstuvwxyz", many, TextAlign.START, INF, 0, TextDirection.AUTO, null);
        assertThat(longer.glyphSize(19)).isEqualTo(29);
        many.clear();
        assertThat(many.count()).isZero();
    }

    @Test
    void theCaretMovesVisuallyThroughMixedDirections() {
        TextBlock b = new TextBlock(system);
        // logical: a b ' ' alef bet ' ' c d ; visual: a b ' ' [bet alef] ' ' c d
        b.set("ab \u05D0\u05D1 cd", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        int[] right = new int[12];
        int at = 0;
        int n = 0;
        for (int step = 0; step < 12; step++) {
            int next = b.moveVisually(at, true);
            if (next == at) {
                break;
            }
            right[n++] = next;
            at = next;
        }
        float last = -1;
        for (int i = 0; i < n; i++) {
            float x = b.caretX(right[i]);
            assertThat(x).as("each step goes right").isGreaterThan(last);
            last = x;
        }
        assertThat(at).as("ends at the line's right end").isEqualTo(8);
        assertThat(b.moveVisually(8, true)).isEqualTo(8);
        assertThat(b.moveVisually(0, false)).isZero();
        assertThat(b.rightToLeft(3)).isTrue();
        assertThat(b.rightToLeft(0)).isFalse();
        assertThat(b.charLeft(3)).isGreaterThan(b.charLeft(4)).as("alef is right of bet");
        assertThat(b.charRight(0)).isEqualTo(5);
    }

    @Test
    void movingOffALineGoesToTheNextInReadingOrder() {
        TextBlock b = new TextBlock(system);
        b.set("ab\ncd", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(b.moveVisually(2, true)).isEqualTo(3);
        assertThat(b.moveVisually(3, false)).isEqualTo(2);
        TextBlock rtl = new TextBlock(system);
        rtl.set("\u05D0\u05D1\n\u05D2\u05D3", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(rtl.moveVisually(2, false)).as("left in a right-to-left paragraph is forward").isEqualTo(3);
        assertThat(rtl.moveVisually(3, true)).isEqualTo(2);
        TextBlock wrapped = new TextBlock(system);
        wrapped.set("aaaa bbbb", LATIN, 10, 400, false, TextAlign.START, 30, 0);
        assertThat(wrapped.lineCount()).isEqualTo(2);
        assertThat(wrapped.moveVisually(4, true)).as("the soft break's position belongs to the next line")
            .isEqualTo(5);
        TextBlock empty = new TextBlock(system);
        empty.set("", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(empty.moveVisually(0, true)).isZero();
    }

    @Test
    void rangesCoverBoxesPerLineAndPiece() {
        TextBlock b = new TextBlock(system);
        b.set("ab \u05D0\u05D1 cd\nef", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        float[] out = new float[64];
        int n = b.rangeBoxes(1, 4, 0, out);
        assertThat(n).as("b and the space, then alef apart").isEqualTo(2);
        n = b.rangeBoxes(0, 11, 4, out);
        assertThat(n).as("the first line in one box, the line feed's extra, the second line").isEqualTo(2);
        assertThat(out[2]).isEqualTo(8 * 5 + 4);
        assertThat(out[5]).isEqualTo(b.lineTop(1));
        assertThat(b.rangeBoxes(5, 5, 4, out)).isZero();
        assertThat(b.rangeBoxes(4, 2, 0, out)).as("reversed").isEqualTo(2);
        assertThat(b.rangeBoxes(0, 11, 4, new float[4])).as("counted beyond the room").isEqualTo(2);
        TextBlock blank = new TextBlock(system);
        blank.set("a\n\nb", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(blank.rangeBoxes(0, 4, 4, out)).as("an empty line shows its line feed").isEqualTo(3);
        TextBlock many = new TextBlock(system);
        many.set("a".repeat(40), LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(many.rangeBoxes(0, 40, 0, out)).isEqualTo(1);
        many.set("ab ".repeat(30), LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(many.moveVisually(0, true)).isEqualTo(1);
    }

    @Test
    void emojiTakeTheEmojiFaceAndTextStaysText() {
        TextBlock b = new TextBlock(system);
        b.set("1 1\uFE0F\u20E3 \uD83D\uDE00\u200D\uD83D\uDE00 \u2764\uFE0E \uD83C\uDDE9\uD83C\uDDEA x", LATIN, 10, 400,
            false, TextAlign.START, INF, 0);
        StringBuilder faces = new StringBuilder();
        for (int g = 0; g < b.glyphCount(); g++) {
            faces.append(b.glyphFace(g).source().family().charAt(0));
        }
        // 1, keycap (3 code points), ZWJ pair (3), heart with FE0E (2: text), flag (2), x
        assertThat(faces.toString()).isEqualTo("LEEEEEESSEEL");
    }

    @Test
    void anUnchangedRichBlockRecordsWithoutAllocating() {
        TextRuns runs = new TextRuns();
        runs.add(0, LATIN, 10, 400, false);
        runs.color(runs.add(3, LATIN, 12, 400, false), Rgba.WHITE);
        TextBlock b = new TextBlock(system);
        b.set("abcdef", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null);
        DrawList list = record(b);
        float[] boxes = new float[64];
        AllocationAssertions.assertAllocationFree(() -> {
            b.set("abcdef", runs, TextAlign.START, INF, 0, TextDirection.AUTO, null);
            list.begin(0, b.glyphCount());
            b.recordLines(list, 0, 0, Rgba.BLACK, 1, 0, 1);
            list.end();
            b.rangeBoxes(1, 5, 2, boxes);
        });
    }

    @Test
    void colourBitmapsArePreScaledPaddedAndBled() {
        byte[] px = new byte[4 * 4 * 4];
        for (int i = 0; i < 16; i++) { // a red 2x2 square in the top left, the rest transparent
            int x = i % 4;
            int y = i / 4;
            if (x < 2 && y < 2) {
                px[i * 4] = (byte) 255;
                px[i * 4 + 3] = (byte) 255;
            }
        }
        GlyphRasterizer.ColorBitmap bitmap = new GlyphRasterizer.ColorBitmap(4, 4, 1, 3, 4, px);
        ColorBitmaps.Prepared same = ColorBitmaps.prepare(bitmap, 0);
        assertThat(same.width()).isEqualTo(4 + 2 * ColorBitmaps.PAD);
        assertThat(same.left()).isEqualTo(0); // (1 - 1) / 4 ems
        assertThat(same.top()).isEqualTo(1);
        assertThat(same.emWidth()).isEqualTo(1.5f);
        int border = 0; // the transparent border took the square's colour: no dark fringe when filtered
        assertThat(same.pixels()[border]).isEqualTo((byte) 255);
        assertThat(same.pixels()[3]).isZero();
        ColorBitmaps.Prepared half = ColorBitmaps.prepare(bitmap, 1);
        assertThat(half.width()).isEqualTo(2 + 2 * ColorBitmaps.PAD);
        int inner = (ColorBitmaps.PAD * half.width() + ColorBitmaps.PAD) * 4;
        assertThat(half.pixels()[inner + 3]).isEqualTo((byte) 255);
        assertThat(half.pixels()[inner]).isEqualTo((byte) 255);
        assertThat(half.emWidth()).as("the same ems at half the texels").isEqualTo(2f);
        assertThat(ColorBitmaps.level(109, 109)).isZero();
        assertThat(ColorBitmaps.level(109, 50)).isEqualTo(1);
        assertThat(ColorBitmaps.level(109, 1)).isEqualTo(5);
        assertThat(ColorBitmaps.linear(255)).isEqualTo(1);
        assertThat(ColorBitmaps.linear(0)).isZero();
    }

    @Test
    void colourGlyphsGoToSrgbPages() throws InterruptedException {
        GlyphRasterizer colours = new GlyphRasterizer() {
            @Override
            public Coverage rasterize(FontFace face, int glyph, float sizePx, float offsetX) {
                return null;
            }

            @Override
            public ColorBitmap color(FontFace face, int glyph) {
                if (glyph == 0x1F4A9) {
                    return null; // no bitmap: nothing drawn
                }
                byte[] px = new byte[8 * 8 * 4];
                java.util.Arrays.fill(px, (byte) 200);
                return new ColorBitmap(8, 8, 0, 8, 8, px);
            }
        };
        UiTextures textures = new UiTextures();
        GlyphAtlas colourAtlas = new GlyphAtlas(textures, new JdkSdfGenerator(), colours, null, null,
            new AtlasSettings());
        FontSource colourSource = Fakes.source(dir, "c.ttf", "C", 400, false);
        FontFace emojiFace = new FontFace() {
            @Override
            public int id() {
                return 99;
            }

            @Override
            public FontSource source() {
                return colourSource;
            }

            @Override
            public float ascender() {
                return 1;
            }

            @Override
            public float descender() {
                return 0;
            }

            @Override
            public float lineGap() {
                return 0;
            }

            @Override
            public boolean covers(int codepoint) {
                return true;
            }

            @Override
            public boolean outline(int glyph, GlyphOutline out) {
                return false;
            }

            @Override
            public float colorStrike() {
                return 8;
            }
        };
        int e = colourAtlas.colorEntry(emojiFace, 0x1F600, 8);
        int none = colourAtlas.colorEntry(emojiFace, 0x1F4A9, 8);
        assertThat(colourAtlas.colorEntry(emojiFace, 0x1F600, 8)).isEqualTo(e);
        assertThat(colourAtlas.colorEntry(emojiFace, 0x1F600, 4)).as("another level").isNotEqualTo(e);
        assertThat(colourAtlas.color(e)).isTrue();
        assertThat(colourAtlas.raster(e)).isFalse();
        colourAtlas.finishAll();
        assertThat(colourAtlas.state(e)).isEqualTo(GlyphAtlas.READY);
        assertThat(colourAtlas.state(none)).isEqualTo(GlyphAtlas.EMPTY);
        assertThat(colourAtlas.colorPageCount()).isEqualTo(1);
        UiTexture page = colourAtlas.colorPage(0);
        assertThat(page.format()).isEqualTo(UiTexture.Format.RGBA8_SRGB);
        assertThat(colourAtlas.texture(e)).isEqualTo(page.id());
        assertThat(colourAtlas.plane(e, 3)).as("the top in ems, padded").isEqualTo(9 / 8f);
        assertThatThrownBy(() -> colourAtlas.colorPage(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> colourAtlas.colorEntry(emojiFace, -1, 8)).isInstanceOf(
            IllegalArgumentException.class);
        // the default rasterizer has no colour
        assertThat(new GlyphRasterizer() {
            @Override
            public Coverage rasterize(FontFace face, int glyph, float sizePx, float offsetX) {
                return null;
            }
        }.color(emojiFace, 1)).isNull();
        assertThat(new Fakes.Face(1, Fakes.source(dir, "d.ttf", "D", 400, false), 0, 1).colorStrike()).isZero();
    }
}
