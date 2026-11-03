package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Primitive;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.text.jdk.JdkRasterizer;
import com.truesim.ui.text.jdk.JdkSdfGenerator;
import java.lang.Character.UnicodeScript;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Layout semantics with made-up fonts: every character is one glyph half an em wide (marks none), so positions are
 * exact. Real shaping is tested in {@code JdkTextTest} and, with HarfBuzz, in {@code ui-text-native}.
 */
class TextBlockTest {

    private static final List<String> LATIN = List.of("Latin");
    private static final float INF = Float.POSITIVE_INFINITY;

    @TempDir
    Path dir;
    private FontRegistry fonts;
    private AtlasSettings settings;
    private GlyphAtlas atlas;
    private TextSystem system;

    @BeforeEach
    void setUp() {
        fonts = new FontRegistry(new Fakes.Backend());
        fonts.register(Fakes.source(dir, "latin.ttf", "Latin", 400, false));
        fonts.register(Fakes.source(dir, "latin-bold.ttf", "Latin", 700, false));
        fonts.register(Fakes.source(dir, "hebrew.ttf", "Hebrew", 400, false));
        fonts.register(Fakes.source(dir, "symbols.ttf", "Symbols", 400, false));
        fonts.fallback(UnicodeScript.HEBREW, "Hebrew");
        fonts.lastResort("Symbols");
        settings = new AtlasSettings();
        atlas = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), null, null, settings);
        system = new TextSystem(fonts, new Fakes.Shaper(), atlas);
    }

    private TextBlock block(String text, float maxWidth) {
        TextBlock b = new TextBlock(system);
        b.set(text, LATIN, 10, 400, false, TextAlign.START, maxWidth, 0);
        return b;
    }

    @Test
    void aLineIsMeasuredAndOnlyChangesReshape() {
        TextBlock b = new TextBlock(system);
        assertThat(b.set("Hello", LATIN, 10, 400, false, TextAlign.START, INF, 0)).isTrue();
        assertThat(b.width()).isEqualTo(25);
        assertThat(b.lineCount()).isEqualTo(1);
        assertThat(b.lineHeightPx()).isCloseTo(11, offset(1e-5f)); // ascender + descender + gap
        assertThat(b.height()).isCloseTo(11, offset(1e-5f));
        assertThat(b.baseline()).isEqualTo(9); // (11 - 10) / 2 + 8, rounded
        assertThat(b.glyphCount()).isEqualTo(5);
        assertThat(b.length()).isEqualTo(5);
        assertThat(b.size()).isEqualTo(10);
        assertThat(b.glyphId(1)).isEqualTo('e');
        assertThat(b.glyphX(1)).isEqualTo(5);
        assertThat(b.glyphY(1)).isEqualTo(9);
        assertThat(b.glyphFace(0).source().family()).isEqualTo("Latin");
        long version = b.version();

        assertThat(b.set(new StringBuilder("Hello"), LATIN, 10, 400, false, TextAlign.START, INF, 0)).isFalse();
        assertThat(b.set("Hello", List.of("Latin"), 10, 400, false, TextAlign.START, INF, 0)).isFalse();
        assertThat(b.version()).isEqualTo(version);
        assertThat(b.set("Hellp", LATIN, 10, 400, false, TextAlign.START, INF, 0)).isTrue();
        assertThat(b.set("Hellp", LATIN, 10, 400, false, TextAlign.START, INF, 1.5f)).isTrue();
        assertThat(b.lineHeightPx()).isEqualTo(15);
        assertThat(b.set("Hellp", LATIN, 12, 400, false, TextAlign.START, INF, 1.5f)).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, false, TextAlign.START, INF, 1.5f)).isTrue();
        assertThat(b.glyphFace(0).source().weight()).isEqualTo(700);
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.START, INF, 1.5f)).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, INF, 1.5f)).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, 100, 1.5f)).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, 100, 1.5f, TextDirection.LTR, null)).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, 100, 1.5f, TextDirection.LTR, "en")).isTrue();
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, 100, 1.5f)).isFalse(); // keeps them
        assertThat(b.version()).isGreaterThan(version);
        fonts.register(Fakes.source(dir, "more.ttf", "More", 400, false));
        assertThat(b.set("Hellp", LATIN, 12, 700, true, TextAlign.END, 100, 1.5f)).isTrue(); // fonts changed

        assertThatThrownBy(() -> b.set("x", LATIN, 0, 400, false, TextAlign.START, INF, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.set("x", LATIN, 10, 400, false, TextAlign.START, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> b.set("x", LATIN, 10, 400, false, TextAlign.START, INF, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linesBreakAtWordsAndLongWordsBetweenGraphemes() {
        TextBlock b = block("aaa bbb ccc", 40);
        assertThat(b.lineCount()).isEqualTo(2);
        assertThat(b.lineStart(0)).isZero();
        assertThat(b.lineEnd(0)).isEqualTo(8);
        assertThat(b.lineWidth(0)).isEqualTo(35); // the trailing space does not count
        assertThat(b.lineStart(1)).isEqualTo(8);
        assertThat(b.width()).isEqualTo(35);
        assertThat(b.height()).isCloseTo(22, offset(1e-4f));
        assertThat(b.lineBaseline(1)).isEqualTo(20); // 11 + 8.5 rounded
        assertThat(b.glyphCount()).isEqualTo(9); // spaces draw nothing

        TextBlock word = block("abcdefghij", 22);
        assertThat(word.lineCount()).isEqualTo(3);
        assertThat(word.lineEnd(0)).isEqualTo(4);
        assertThat(word.lineEnd(1)).isEqualTo(8);
        assertThat(word.lineEnd(2)).isEqualTo(10);

        TextBlock tail = block("ab abcdefghij", 22);
        assertThat(tail.lineStart(1)).isEqualTo(3);
        assertThat(tail.lineCount()).isEqualTo(4);
    }

    @Test
    void lineFeedsMakeParagraphs() {
        TextBlock b = block("a\n\nb", INF);
        assertThat(b.lineCount()).isEqualTo(3);
        assertThat(b.lineStart(1)).isEqualTo(2);
        assertThat(b.lineEnd(1)).isEqualTo(2);
        assertThat(b.caretX(1)).isEqualTo(5); // end of the first line
        assertThat(b.caretX(2)).isEqualTo(b.lineLeft(1)); // the empty line
        assertThat(b.caretLine(3)).isEqualTo(2);
        assertThat(b.caretX(4)).isEqualTo(5);

        TextBlock empty = block("", INF);
        assertThat(empty.lineCount()).isEqualTo(1);
        assertThat(empty.width()).isZero();
        assertThat(empty.height()).isCloseTo(11, offset(1e-5f));
        assertThat(empty.caretX(0)).isZero();
        assertThat(empty.hitTest(10, 10)).isZero();
        assertThatThrownBy(() -> empty.caretX(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> empty.caretLine(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void linesAlignInTheBoxFollowingTheirDirection() {
        TextBlock b = new TextBlock(system);
        b.set("aaa", LATIN, 10, 400, false, TextAlign.CENTER, 100, 0);
        assertThat(b.lineLeft(0)).isEqualTo(42.5f);
        assertThat(b.glyphX(0)).isEqualTo(42.5f);
        b.set("aaa", LATIN, 10, 400, false, TextAlign.END, 100, 0);
        assertThat(b.lineLeft(0)).isEqualTo(85);
        b.set("אבג", LATIN, 10, 400, false, TextAlign.START, 100, 0);
        assertThat(b.lineRightToLeft(0)).isTrue();
        assertThat(b.lineLeft(0)).isEqualTo(85);
        b.set("אבג", LATIN, 10, 400, false, TextAlign.END, 100, 0);
        assertThat(b.lineLeft(0)).isZero();
        b.set("aaa", LATIN, 10, 400, false, TextAlign.START, 100, 0, TextDirection.RTL, null);
        assertThat(b.lineRightToLeft(0)).isTrue();
        assertThat(b.lineLeft(0)).isEqualTo(85);
        assertThat(b.caretX(0)).isEqualTo(85); // left-to-right text in a right-to-left paragraph
        b.set("", LATIN, 10, 400, false, TextAlign.START, 100, 0, TextDirection.RTL, null);
        assertThat(b.lineRightToLeft(0)).isTrue();
        b.set("aaa", LATIN, 10, 400, false, TextAlign.START, 100, 0, TextDirection.LTR, null);
        assertThat(b.lineRightToLeft(0)).isFalse();
    }

    @Test
    void aWiderOrNarrowerBoxThatStillFitsOnlyRealigns() {
        TextBlock b = new TextBlock(system);
        b.set("aaa a\nbb", LATIN, 10, 400, false, TextAlign.CENTER, INF, 0);
        long v = b.version();
        assertThat(b.set("aaa a\nbb", LATIN, 10, 400, false, TextAlign.CENTER, 200, 0)).isTrue();
        assertThat(b.version()).isGreaterThan(v);
        TextBlock fresh = new TextBlock(system);
        fresh.set("aaa a\nbb", LATIN, 10, 400, false, TextAlign.CENTER, 200, 0);
        assertThat(b.lineCount()).isEqualTo(fresh.lineCount());
        for (int g = 0; g < b.glyphCount(); g++) {
            assertThat(b.glyphX(g)).isCloseTo(fresh.glyphX(g), offset(1e-4f));
        }
        for (int i = 0; i <= 8; i++) {
            assertThat(b.caretX(i)).isCloseTo(fresh.caretX(i), offset(1e-4f));
        }
        // start-aligned left-to-right lines do not move: nothing changed
        TextBlock start = new TextBlock(system);
        start.set("aaa", LATIN, 10, 400, false, TextAlign.START, 100, 0);
        assertThat(start.set("aaa", LATIN, 10, 400, false, TextAlign.START, 120, 0)).isFalse();
        // too narrow now: lines break again
        float narrow = b.lineWidth(0) - 1;
        assertThat(b.set("aaa a\nbb", LATIN, 10, 400, false, TextAlign.CENTER, narrow, 0)).isTrue();
        assertThat(b.lineCount()).isEqualTo(3);
    }

    @Test
    void mixedDirectionsAreReorderedVisually() {
        TextBlock b = block("ab אב cd", INF);
        assertThat(b.lineRightToLeft(0)).isFalse();
        // visual: a b _ bet alef _ c d
        assertThat(b.glyphId(2)).isEqualTo(0x5D1);
        assertThat(b.glyphId(3)).isEqualTo(0x5D0);
        assertThat(b.glyphFace(2).source().family()).isEqualTo("Hebrew");
        assertThat(b.caretX(3)).isEqualTo(25); // before alef: its right edge
        assertThat(b.caretX(4)).isEqualTo(20);
        assertThat(b.caretX(5)).isEqualTo(25); // after bet, at the space
        assertThat(b.caretX(6)).isEqualTo(30);

        TextBlock rtl = block("אב ab ג", INF);
        assertThat(rtl.lineRightToLeft(0)).isTrue();
        // visual: gimel _ a b _ bet alef
        assertThat(rtl.glyphId(0)).isEqualTo(0x5D2);
        assertThat(rtl.glyphId(1)).isEqualTo('a');
        assertThat(rtl.glyphId(4)).isEqualTo(0x5D0);
        assertThat(rtl.caretX(0)).isEqualTo(35);
        assertThat(rtl.caretX(7)).isEqualTo(0); // the end: left of gimel
    }

    @Test
    void hitTestsFindTheNearestCaret() {
        TextBlock b = block("abcd", INF);
        assertThat(b.hitTest(7, 5)).isEqualTo(1);
        assertThat(b.hitTest(8, 5)).isEqualTo(2);
        assertThat(b.hitTest(-5, 5)).isZero();
        assertThat(b.hitTest(100, 5)).isEqualTo(4);
        assertThat(b.hitTest(100, 500)).isEqualTo(4);
        for (int i = 0; i <= 4; i++) {
            assertThat(b.hitTest(b.caretX(i) + 0.1f, 5)).isEqualTo(i);
        }

        TextBlock rtl = block("אב", INF);
        assertThat(rtl.hitTest(9, 5)).isZero();
        assertThat(rtl.hitTest(6, 5)).isEqualTo(1);
        assertThat(rtl.hitTest(1, 5)).isEqualTo(2);
        assertThat(rtl.hitTest(-3, 5)).isEqualTo(2);
        assertThat(rtl.hitTest(30, 5)).isZero();

        TextBlock lines = block("ab\ncd", INF);
        assertThat(lines.hitTest(1, 15)).isEqualTo(3);
        assertThat(lines.hitTest(1, -15)).isZero();
    }

    @Test
    void caretsStayAtGraphemeBoundaries() {
        TextBlock accent = block("éx", INF);
        assertThat(accent.glyphCount()).isEqualTo(3);
        assertThat(accent.hitTest(4, 5)).isZero(); // not between the e and its accent
        assertThat(accent.hitTest(6, 5)).isEqualTo(2);

        TextBlock emoji = block("a😀b", INF);
        assertThat(emoji.glyphFace(1).source().family()).isEqualTo("Symbols");
        assertThat(emoji.caretX(1)).isEqualTo(5);
        assertThat(emoji.caretX(2)).isEqualTo(7.5f); // inside the pair: half its width, never a hit result
        assertThat(emoji.hitTest(7.6f, 5)).isEqualTo(1);
        assertThat(emoji.hitTest(9.9f, 5)).isEqualTo(3);
    }

    @Test
    void marksStayWithTheirBaseFace() {
        TextBlock b = block("א́", INF);
        assertThat(b.glyphFace(0).source().family()).isEqualTo("Hebrew");
        assertThat(b.glyphFace(1).source().family()).isEqualTo("Hebrew");
        TextBlock scripts = new TextBlock(system);
        scripts.set("1 abc где", List.of("Symbols"), 10, 400, false, TextAlign.START, INF, 0);
        assertThat(scripts.glyphCount()).isEqualTo(7);
        assertThat(scripts.width()).isEqualTo(45);
    }

    @Test
    void recordingDrawsPlaceholdersUntilGlyphsAreReady() {
        TextBlock b = block("ab c", INF);
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        long v0 = b.version();
        list.begin(start, b.glyphCount());
        b.record(list, 10.3f, 20.4f, Rgba.WHITE, 0, Rgba.BLACK, 0);
        assertThat(list.end()).isTrue();
        assertThat(Primitive.kind(list.header(start))).isEqualTo(Primitive.GLYPH);
        assertThat(list.floatAt(start, Primitive.X1)).isEqualTo(list.floatAt(start, Primitive.X0)); // zero-sized
        assertThat(b.version()).isEqualTo(v0);

        atlas.beginFrame();
        long v1 = b.version();
        assertThat(v1).isGreaterThan(v0);
        assertThat(b.version()).isEqualTo(v1);
        list.begin(start, b.glyphCount());
        b.record(list, 10.3f, 20.4f, Rgba.WHITE, 1, Rgba.BLACK, 0.25f);
        assertThat(list.end()).isTrue();
        int e = atlas.entry(b.glyphFace(0), 'a');
        float baseline = 20 + b.baseline();
        assertThat(list.floatAt(start, Primitive.X0)).isEqualTo(10.3f + atlas.plane(e, 0) * 10);
        assertThat(list.floatAt(start, Primitive.Y0)).isEqualTo(baseline - atlas.plane(e, 3) * 10);
        assertThat(list.floatAt(start, Primitive.PIXEL_RANGE)).isEqualTo(6 * 10 / 40f);
        assertThat(list.intAt(start, Primitive.TEXTURE)).isEqualTo(atlas.page(0).id());
        assertThat(list.floatAt(start, Primitive.OUTLINE)).isEqualTo(1);
        assertThat(list.floatAt(start, Primitive.WEIGHT)).isEqualTo(0.25f);
        assertThat(list.floatAt(start + 2, Primitive.X0)).isEqualTo(list.floatAt(start, Primitive.X0) + 15); // after the space

        list.clean();
        list.begin(start, b.glyphCount());
        b.record(list, 10.3f, 20.4f, Rgba.WHITE, 1, Rgba.BLACK, 0.25f);
        list.end();
        assertThat(list.dirtyTo()).isEqualTo(list.dirtyFrom()); // nothing changed
        assertThat(b.version()).isEqualTo(v1);
    }

    @Test
    void anUnchangedBlockSetsAndRecordsWithoutAllocating() {
        TextBlock b = block("Hello אב world", 60);
        atlas.beginFrame();
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        String text = "Hello אב world";
        AllocationAssertions.assertAllocationFree(() -> {
            b.set(text, LATIN, 10, 400, false, TextAlign.START, 60, 0);
            list.begin(start, b.glyphCount());
            b.record(list, 0, 0, Rgba.WHITE, 0, 0, 0);
            list.end();
            b.version();
        });
    }

    @Test
    void anAtlasRestartLooksGlyphsUpAgain() {
        TextBlock b = block("ab", INF);
        atlas.beginFrame();
        long v = b.version();
        settings.emSize().set(32);
        atlas.beginFrame();
        assertThat(b.version()).isGreaterThan(v);
        DrawList list = new DrawList();
        int start = list.allocate(2);
        list.begin(start, 2);
        b.record(list, 0, 0, Rgba.WHITE, 0, 0, 0);
        list.end();
        assertThat(atlas.size()).isEqualTo(2); // looked up again
        atlas.beginFrame();
        list.begin(start, 2);
        b.record(list, 0, 0, Rgba.WHITE, 0, 0, 0);
        list.end();
        assertThat(list.floatAt(start, Primitive.PIXEL_RANGE)).isEqualTo(6 * 10 / 32f);
    }

    @Test
    void aMeasuringSystemHasNoAtlas() {
        TextSystem measuring = new TextSystem(fonts, new Fakes.Shaper(), null);
        TextBlock b = new TextBlock(measuring);
        b.set("ab", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        assertThat(b.width()).isEqualTo(10);
        assertThat(b.version()).isEqualTo(1);
        assertThat(measuring.atlas()).isNull();
        assertThat(measuring.fonts()).isSameAs(fonts);
        assertThatThrownBy(() -> b.record(new DrawList(), 0, 0, 0, 0, 0, 0))
            .isInstanceOf(IllegalStateException.class);
        FontRegistry none = new FontRegistry(new Fakes.Backend());
        TextBlock nothing = new TextBlock(new TextSystem(none, new Fakes.Shaper(), null));
        assertThatThrownBy(() -> nothing.set("a", LATIN, 10, 400, false, TextAlign.START, INF, 0))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void longTextsGrowEveryTable() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            s.append(i % 3 == 0 ? "אבג " : "word ").append(i % 10 == 9 ? "\n" : "");
        }
        TextBlock b = block(s.toString(), 80);
        assertThat(b.lineCount()).isGreaterThan(30);
        assertThat(b.glyphCount()).isGreaterThan(400);
        for (int l = 0; l < b.lineCount(); l++) {
            assertThat(b.lineWidth(l)).isLessThanOrEqualTo(80);
        }
        assertThat(b.hitTest(b.caretX(5) + 0.1f, b.lineBaseline(b.caretLine(5)))).isEqualTo(5);
    }

    @Test
    void theCharacterIteratorWalksItsRange() {
        CharArrayIterator it = new CharArrayIterator().reset("xabcx".toCharArray(), 1, 4);
        assertThat(it.first()).isEqualTo('a');
        assertThat(it.next()).isEqualTo('b');
        assertThat(it.previous()).isEqualTo('a');
        assertThat(it.previous()).isEqualTo(java.text.CharacterIterator.DONE);
        assertThat(it.last()).isEqualTo('c');
        assertThat(it.next()).isEqualTo(java.text.CharacterIterator.DONE);
        assertThat(it.getIndex()).isEqualTo(4);
        assertThat(it.current()).isEqualTo(java.text.CharacterIterator.DONE);
        assertThat(it.setIndex(2)).isEqualTo('b');
        CharArrayIterator copy = (CharArrayIterator) it.clone();
        assertThat(copy.getIndex()).isEqualTo(2);
        assertThat(copy.getBeginIndex()).isEqualTo(1);
        assertThat(copy.getEndIndex()).isEqualTo(4);
        assertThatThrownBy(() -> it.setIndex(5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CharArrayIterator().reset(new char[0], 0, 0).last()).isEqualTo(java.text.CharacterIterator.DONE);
    }

    // ---------------------------------------------------------------- small text (UI16)

    private TextSystem rasterSystem() {
        GlyphAtlas raster = new GlyphAtlas(new UiTextures(), new JdkSdfGenerator(), new JdkRasterizer(), null, null,
            settings);
        return new TextSystem(fonts, new Fakes.Shaper(), raster);
    }

    /** Records {@code b} at (10.3, 20.4) after a frame (so its glyphs are ready); returns the list. */
    private static DrawList recorded(TextBlock b, float outline, float weight) {
        DrawList list = new DrawList();
        int start = list.allocate(b.glyphCount());
        list.begin(start, b.glyphCount());
        b.record(list, 10.3f, 20.4f, Rgba.WHITE, outline, Rgba.BLACK, weight);
        list.end();
        b.version();
        b.system().atlas().beginFrame();
        list.begin(start, b.glyphCount());
        b.record(list, 10.3f, 20.4f, Rgba.WHITE, outline, Rgba.BLACK, weight);
        list.end();
        return list;
    }

    private static boolean raster(DrawList list) {
        return (Primitive.flags(list.header(0)) & Primitive.FLAG_RASTER) != 0;
    }

    @Test
    void smallTextDrawsRasterGlyphsOnWholePixels() {
        TextSystem rs = rasterSystem();
        TextBlock b = new TextBlock(rs);
        b.set("ab c", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        DrawList list = recorded(b, 0, 0);
        assertThat(b.drewRaster()).isTrue();
        assertThat(raster(list)).isTrue();
        GlyphAtlas atlas = rs.atlas();
        // the pen at 10.3 px: pixel 10, phase 1 (10.25, the nearest quarter)
        assertThat(b.glyphX(0)).isZero();
        int e = atlas.rasterEntry(b.glyphFace(0), 'a', 10, 1);
        assertThat(atlas.raster(e)).isTrue();
        float x0 = list.floatAt(0, Primitive.X0);
        float y0 = list.floatAt(0, Primitive.Y0);
        assertThat(x0).isEqualTo(10 + atlas.plane(e, 0));
        assertThat(x0 % 1).as("on a whole pixel").isZero();
        assertThat(y0 % 1).isZero();
        assertThat(list.floatAt(0, Primitive.X1) - x0).isEqualTo(atlas.plane(e, 2) - atlas.plane(e, 0));
        assertThat(list.intAt(0, Primitive.TEXTURE)).isEqualTo(atlas.texture(e));
        assertThat(list.floatAt(0, Primitive.PIXEL_RANGE)).isZero();
    }

    @Test
    void switchingTechniqueRedrawsWithoutReflowing() {
        TextSystem rs = rasterSystem();
        TextBlock b = new TextBlock(rs);
        b.set("ab c", LATIN, 10, 400, false, TextAlign.START, INF, 0);
        recorded(b, 0, 0);
        float width = b.width();
        float[] xs = {b.glyphX(0), b.glyphX(1), b.glyphX(2)};
        long v = b.version();

        settings.smallText().select("MTSDF");
        assertThat(b.version()).as("records again").isGreaterThan(v);
        assertThat(b.set("ab c", LATIN, 10, 400, false, TextAlign.START, INF, 0)).as("no new layout").isFalse();
        assertThat(raster(recorded(b, 0, 0))).isFalse();
        assertThat(b.drewRaster()).isFalse();
        assertThat(b.width()).isEqualTo(width);
        assertThat(new float[] {b.glyphX(0), b.glyphX(1), b.glyphX(2)}).isEqualTo(xs);

        settings.smallText().select("HINTED");
        assertThat(raster(recorded(b, 0, 0))).isTrue();
        assertThat(b.width()).isEqualTo(width);
        assertThat(new float[] {b.glyphX(0), b.glyphX(1), b.glyphX(2)}).isEqualTo(xs);
    }

    @Test
    void worldSpaceOutlinesWeightAndLargeTextDrawMtsdf() {
        TextBlock b = new TextBlock(rasterSystem());
        b.set("ab", LATIN, 12, 400, false, TextAlign.START, INF, 0);
        assertThat(b.worldSpace()).isFalse();
        long v = b.version();
        b.worldSpace(true);
        b.worldSpace(true);
        assertThat(b.version()).isEqualTo(v + 1);
        assertThat(raster(recorded(b, 0, 0))).as("world space").isFalse();
        b.worldSpace(false);
        assertThat(raster(recorded(b, 0, 0))).isTrue();
        assertThat(raster(recorded(b, 1, 0))).as("an outline").isFalse();
        // a new layout looks glyphs up for the last record's outline: still MTSDF
        b.set("abc", LATIN, 12, 400, false, TextAlign.START, INF, 0);
        assertThat(b.drewRaster()).isFalse();
        assertThat(raster(recorded(b, 0, 0.3f))).as("a weight offset").isFalse();
        assertThat(raster(recorded(b, 0, 0))).isTrue();
        b.set("abc", LATIN, 13, 400, false, TextAlign.START, INF, 0);
        assertThat(raster(recorded(b, 0, 0))).as("at the threshold").isFalse();
    }

    @Test
    void rasterTextSetsAndRecordsWithoutAllocating() {
        TextBlock b = new TextBlock(rasterSystem());
        String text = "Hello אב world";
        b.set(text, LATIN, 10, 400, false, TextAlign.START, 60, 0);
        DrawList list = recorded(b, 0, 0);
        assertThat(raster(list)).isTrue();
        AllocationAssertions.assertAllocationFree(() -> {
            b.set(text, LATIN, 10, 400, false, TextAlign.START, 60, 0);
            list.begin(0, b.glyphCount());
            b.record(list, 0, 0, Rgba.WHITE, 0, 0, 0);
            list.end();
            b.version();
        });
    }
}
