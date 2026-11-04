package com.truesim.ui.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The shape cache: what it keeps, that a hit gives what the shaper gave, starting over when full, off. */
class ShapeCacheTest {

    /** The fake shaper, counting its calls; marks the first glyph of each run unsafe to break. */
    private static final class Counting implements TextShaper {
        int calls;
        final Fakes.Shaper inner = new Fakes.Shaper();

        @Override
        public void shape(char[] text, int length, int start, int end, FontFace face, float sizePx,
            boolean rightToLeft, String language, ShapedGlyphs out) {
            calls++;
            int first = out.size();
            inner.shape(text, length, start, end, face, sizePx, rightToLeft, language, out);
            if (out.size() > first) { // the flag kept with the glyph
                ShapedGlyphs copy = new ShapedGlyphs();
                for (int i = first; i < out.size(); i++) {
                    copy.add(out.glyph(i), out.cluster(i), out.advance(i), out.offsetX(i), out.offsetY(i),
                        i == first ? ShapedGlyphs.UNSAFE_TO_BREAK : 0);
                }
                out.replace(first, out.size(), copy);
            }
        }

        @Override
        public boolean marksUnsafeBreaks() {
            return true;
        }
    }

    private final Counting shaper = new Counting();
    private final ShapeCache cache = new ShapeCache(shaper);
    private final FontFace face = new Fakes.Face(1, null, 0, 0x10FFFF);
    private final FontFace other = new Fakes.Face(2, null, 0, 0x10FFFF);

    @AfterEach
    void restore() {
        ShapeCache.ENTRIES.set(ShapeCache.ENTRIES.initial());
    }

    private ShapedGlyphs shape(String context, int start, int end, FontFace f, float size, boolean rtl) {
        ShapedGlyphs out = new ShapedGlyphs();
        out.add(7, 0, 1, 0, 0); // something before: the run's glyphs are appended
        cache.shape(context.toCharArray(), context.length(), start, end, f, size, rtl, null, out);
        return out;
    }

    private static void assertSame(ShapedGlyphs a, ShapedGlyphs b) {
        assertThat(a.size()).isEqualTo(b.size());
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.glyph(i)).isEqualTo(b.glyph(i));
            assertThat(a.cluster(i)).isEqualTo(b.cluster(i));
            assertThat(a.advance(i)).isEqualTo(b.advance(i));
            assertThat(a.flags(i)).isEqualTo(b.flags(i));
        }
    }

    @Test
    void aRunShownAgainIsNotShapedAgainAndGivesTheSameGlyphsWhereverItStands() {
        ShapedGlyphs first = shape("pass 17 recorded", 5, 7, face, 14, false);
        assertThat(shaper.calls).isEqualTo(1);
        ShapedGlyphs again = shape("pass 17 recorded", 5, 7, face, 14, false);
        assertThat(shaper.calls).as("a hit").isEqualTo(1);
        assertSame(again, first);
        assertThat(cache.hits()).isEqualTo(1);
        // the same characters elsewhere in other text: clusters follow the run
        ShapedGlyphs moved = shape("x 17", 2, 4, face, 14, false);
        assertThat(shaper.calls).isEqualTo(1);
        assertThat(moved.cluster(1)).isEqualTo(2);
        assertThat(moved.cluster(2)).isEqualTo(3);
        assertThat(moved.flags(1)).as("the flags kept").isEqualTo(ShapedGlyphs.UNSAFE_TO_BREAK);
    }

    @Test
    void theFaceTheSizeTheDirectionAndTheLanguageArePartOfTheKey() {
        shape("Frame", 0, 5, face, 14, false);
        shape("Frame", 0, 5, other, 14, false);
        shape("Frame", 0, 5, face, 15, false);
        shape("Frame", 0, 5, face, 14, true);
        assertThat(shaper.calls).isEqualTo(4);
        ShapedGlyphs out = new ShapedGlyphs();
        cache.shape("Frame".toCharArray(), 5, 0, 5, face, 14, false, "tr", out);
        assertThat(shaper.calls).isEqualTo(5);
        shape("Frame", 0, 5, face, 14, true);
        assertThat(shaper.calls).isEqualTo(5);
        // digits alone and digits in a run with letters shape as different scripts
        ShapedGlyphs a = new ShapedGlyphs();
        cache.shape("17".toCharArray(), 2, 0, 2, 0, 2, face, 14, false, null, a);
        cache.shape("x17".toCharArray(), 3, 0, 3, 1, 3, face, 14, false, null, a);
        assertThat(shaper.calls).isEqualTo(7);
    }

    @Test
    void textOtherThanLatinAndLongRunsAreShapedEveryTime() {
        shape("שלום", 0, 4, face, 14, true);
        shape("שלום", 0, 4, face, 14, true);
        assertThat(shaper.calls).as("Hebrew: shaped by its context").isEqualTo(2);
        shape("tab\there", 0, 8, face, 14, false);
        shape("tab\there", 0, 8, face, 14, false);
        assertThat(shaper.calls).as("a control character").isEqualTo(4);
        String long1 = "a".repeat(ShapeCache.MAX_RUN + 1);
        shape(long1, 0, long1.length(), face, 14, false);
        shape(long1, 0, long1.length(), face, 14, false);
        assertThat(shaper.calls).isEqualTo(6);
        // a Latin part of a run with Hebrew in it: the run decides the script
        ShapedGlyphs out = new ShapedGlyphs();
        cache.shape("abש".toCharArray(), 3, 0, 3, 0, 2, face, 14, false, null, out);
        cache.shape("abש".toCharArray(), 3, 0, 3, 0, 2, face, 14, false, null, out);
        assertThat(shaper.calls).isEqualTo(8);
        assertThat(cache.misses()).isEqualTo(8);
    }

    /**
     * Runs colliding in one probe chain (their home slots forced equal; the layout otherwise depends on the face's
     * identity hash, which differs between machines): a generation dying in the middle of a chain must not hide the
     * live runs after it. Failed before dead slots were deleted with a backward shift (Linux CI, not Windows).
     */
    @Test
    void runsOfOneProbeChainStayFoundWhenAGenerationInTheMiddleDies() {
        cache.homeBits(0); // every run's home slot is the same
        ShapeCache.ENTRIES.set(4);
        for (int i = 0; i < 4; i++) {
            shape("w" + i, 0, 2, face, 14, false); // the chain: w0 w1 w2 w3
        }
        shape("w4", 0, 2, face, 14, false); // a new generation; w0 to w3 the previous one
        shape("w0", 0, 2, face, 14, false); // w0 moves into the current one
        shape("w5", 0, 2, face, 14, false);
        shape("w6", 0, 2, face, 14, false); // the current one full: w4 w0 w5 w6
        shape("w7", 0, 2, face, 14, false); // w1 to w3 die: their slots sit between w0 and w4 w5 w6
        assertThat(shaper.calls).isEqualTo(8);
        shape("w6", 0, 2, face, 14, false);
        shape("w5", 0, 2, face, 14, false);
        shape("w4", 0, 2, face, 14, false);
        assertThat(shaper.calls).as("live runs behind the dead ones in the chain are found").isEqualTo(8);
    }

    /** Random runs over few home slots, against a model of the two generations: hits and misses exactly as it says. */
    @Test
    void randomRunsHitAndMissAsTheTwoGenerationsSay() {
        java.util.Random random = new java.util.Random(7);
        for (int bits = 0; bits <= 3; bits++) {
            ShapeCache c = new ShapeCache(shaper);
            c.homeBits(bits);
            ShapeCache.ENTRIES.set(8);
            int[] generationOf = new int[40];
            int generation = 2;
            int count = 0;
            for (int step = 0; step < 4000; step++) {
                int key = random.nextInt(random.nextBoolean() ? 12 : 40);
                boolean hit = generationOf[key] >= generation - 1;
                if (!hit || generationOf[key] != generation) { // a miss, or moved up from the previous generation
                    if (count + 1 > 8) {
                        generation++;
                        count = 0;
                    }
                    generationOf[key] = generation;
                    count++;
                }
                int before = shaper.calls;
                String text = "k" + (10 + key);
                ShapedGlyphs out = new ShapedGlyphs();
                c.shape(text.toCharArray(), text.length(), 0, text.length(), face, 14, false, null, out);
                assertThat(shaper.calls - before).as("step %d, key %d, home bits %d", step, key, bits)
                    .isEqualTo(hit ? 0 : 1);
                assertThat(out.size()).isEqualTo(text.length());
            }
        }
    }

    @Test
    void whenFullTheCurrentGenerationAgesAndWhatIsShownAgainStaysAndOffItShapesEverything() {
        ShapeCache.ENTRIES.set(4);
        for (int i = 0; i < 4; i++) {
            shape("w" + i, 0, 2, face, 14, false);
        }
        shape("w0", 0, 2, face, 14, false);
        assertThat(shaper.calls).isEqualTo(4);
        shape("w4", 0, 2, face, 14, false); // the fifth: a new generation, w0 to w3 the previous one
        shape("w4", 0, 2, face, 14, false);
        assertThat(shaper.calls).isEqualTo(5);
        shape("w0", 0, 2, face, 14, false);
        assertThat(shaper.calls).as("the previous generation is still kept: w0 moves into the current one").isEqualTo(5);
        for (int i = 5; i < 7; i++) { // fills the current generation (w4, w0, w5, w6): w1 to w3 die with the next
            shape("w" + i, 0, 2, face, 14, false);
        }
        shape("w7", 0, 2, face, 14, false);
        assertThat(shaper.calls).isEqualTo(8);
        shape("w0", 0, 2, face, 14, false);
        shape("w6", 0, 2, face, 14, false);
        assertThat(shaper.calls).as("shown recently: kept").isEqualTo(8);
        shape("w1", 0, 2, face, 14, false);
        assertThat(shaper.calls).as("two generations old: forgotten").isEqualTo(9);
        for (int i = 0; i < 40; i++) { // a stream of new text never shapes what is shown every time again
            shape("n" + (10 + i), 0, 3, face, 14, false);
            shape("w0", 0, 2, face, 14, false);
        }
        assertThat(shaper.calls).isEqualTo(49);
        ShapeCache.ENTRIES.set(0);
        shape("w0", 0, 2, face, 14, false);
        shape("w0", 0, 2, face, 14, false);
        assertThat(shaper.calls).isEqualTo(51);
        assertThat(cache.shaper()).isSameAs(shaper);
        assertThat(cache.marksUnsafeBreaks()).isTrue();
    }

    @Test
    void aTextSystemShapesThroughOne() {
        TextSystem system = new TextSystem(new FontRegistry(new Fakes.Backend()), shaper, null);
        assertThat(system.shaper()).isInstanceOf(ShapeCache.class);
        assertThat(((ShapeCache) system.shaper()).shaper()).isSameAs(shaper);
        assertThat(new TextSystem(system.fonts(), system.shaper(), null).shaper()).isSameAs(system.shaper());
        assertThat(ShapeCache.tunables()).containsExactly(ShapeCache.ENTRIES);
    }

    @Test
    void hitsAndMissesAllocateNothing() {
        char[] text = "Frame graph pass 17 recorded 1,204 draws".toCharArray();
        ShapedGlyphs out = new ShapedGlyphs();
        Counting plain = new Counting();
        ShapeCache quiet = new ShapeCache(new TextShaper() {
            @Override
            public void shape(char[] t, int length, int start, int end, FontFace f, float sizePx, boolean rtl,
                String language, ShapedGlyphs o) {
                for (int i = start; i < end; i++) {
                    o.add(t[i], i, sizePx / 2, 0, 0);
                }
            }
        });
        int[] k = {0};
        Runnable frame = () -> {
            out.clear();
            quiet.shape(text, text.length, 0, text.length, face, 14, false, null, out); // a hit
            text[17] = (char) ('0' + k[0]++ % 10);
            quiet.shape(text, text.length, 0, text.length, face, 14 + k[0] % 1000, false, null, out); // misses
        };
        for (int i = 0; i < 20_000; i++) {
            frame.run();
        }
        AllocationAssertions.assertAllocationFree(frame);
        assertThat(plain.calls).isZero();
    }
}
