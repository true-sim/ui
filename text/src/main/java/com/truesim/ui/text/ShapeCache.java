package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;
import java.util.Objects;

/**
 * A {@link TextShaper} that remembers what its shaper made of short runs of simple text, so text seen before is not
 * shaped again: a log flooding the console shows new records every frame whose words (and logger names, levels,
 * sources) it has shown many times; tick labels, table cells and recycled list rows repeat theirs. Every
 * {@link TextSystem} shapes through one.
 *
 * <p><b>What is kept.</b> Runs of at most {@link #MAX_RUN} characters, all of them (and all of the run a part is shaped
 * for) printable Basic Latin to Latin Extended-B ({@code U+0020} to {@code U+024F}): text no shaper shapes by its
 * context (HarfBuzz looks at the characters around a run only for scripts that join or reorder), so the glyphs depend
 * on the characters, the face, the size, the direction, the language and whether the run holds a letter (the script
 * a run of digits is shaped as) alone, and their clusters are kept relative to the run. Anything else goes to the
 * shaper each time.
 *
 * <p><b>Memory.</b> At most {@link #ENTRIES} runs ({@code ui.text.shapeCache}; 0 turns the cache off), in tables made
 * when that changes; when they are full the cache starts over (a generation, nothing cleared), so a stream of new text
 * costs a shaping per run as without the cache, plus a hash. Allocation-free otherwise. Use from the text system's
 * thread.
 */
public final class ShapeCache implements TextShaper {

    /** Runs kept: 0 shapes everything every time. */
    public static final Tunable ENTRIES = Tunable.of("ui.text.shapeCache", 4096, 0, 1 << 20,
        "short runs of Latin text whose shaping is kept and reused (labels shown again: log rows, ticks); 0: none");

    /** The longest run kept, in characters. */
    public static final int MAX_RUN = 256;

    private static final int GLYPHS_PER_ENTRY = 12; // pools are sized for runs of about this many glyphs
    private static final int CHARS_PER_ENTRY = 12;

    private final TextShaper shaper;
    private int entries = -1; // the tunable's value the tables were made for
    private int mask;
    private int generation = 1;
    private int count;
    // open addressing: per slot its generation (another: empty), hash, key and where its characters and glyphs are
    private int[] slotGeneration = new int[0];
    private int[] hashes = new int[0];
    private FontFace[] faces = new FontFace[0];
    private float[] sizes = new float[0];
    private byte[] bits = new byte[0]; // 1: right to left, 2: the run holds a letter
    private String[] languages = new String[0];
    private int[] charAt = new int[0];
    private int[] charCount = new int[0];
    private int[] glyphAt = new int[0];
    private int[] glyphCount = new int[0];
    // pools, filled from the start of each generation
    private char[] chars = new char[0];
    private int charsUsed;
    private int[] glyphs = new int[0];
    private int[] clusters = new int[0];
    private float[] advances = new float[0];
    private float[] offsetsX = new float[0];
    private float[] offsetsY = new float[0];
    private byte[] flags = new byte[0];
    private int glyphsUsed;
    private long hits;
    private long misses;

    /** Caches what {@code shaper} shapes. */
    @AllowAllocation("made once per text system")
    public ShapeCache(TextShaper shaper) {
        this.shaper = Objects.requireNonNull(shaper, "shaper");
    }

    /** The tunables it reads. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        return List.of(ENTRIES);
    }

    /** The shaper it asks on a miss. */
    public TextShaper shaper() {
        return shaper;
    }

    /** Runs answered from the cache since it was made. */
    public long hits() {
        return hits;
    }

    /** Runs shaped by the shaper since it was made, those it does not keep included. */
    public long misses() {
        return misses;
    }

    @Override
    public boolean marksUnsafeBreaks() {
        return shaper.marksUnsafeBreaks();
    }

    @Override
    public void shape(char[] text, int length, int start, int end, FontFace face, float sizePx, boolean rightToLeft,
        String language, ShapedGlyphs out) {
        shape(text, length, start, end, start, end, face, sizePx, rightToLeft, language, out);
    }

    @Override
    public void shape(char[] text, int length, int runStart, int runEnd, int start, int end, FontFace face,
        float sizePx, boolean rightToLeft, String language, ShapedGlyphs out) {
        int size = (int) ENTRIES.get();
        if (size != entries) {
            resize(size);
        }
        int n = end - start;
        if (size == 0 || n <= 0 || n > MAX_RUN || !simple(text, start, end)
            || (runStart != start || runEnd != end) && !simple(text, runStart, runEnd)) {
            misses++;
            shaper.shape(text, length, runStart, runEnd, start, end, face, sizePx, rightToLeft, language, out);
            return;
        }
        int key = (rightToLeft ? 1 : 0) | (letter(text, runStart, runEnd) ? 2 : 0);
        int hash = hash(text, start, end, face, sizePx, key);
        int slot = hash & mask;
        while (slotGeneration[slot] == generation) {
            if (hashes[slot] == hash && faces[slot] == face && sizes[slot] == sizePx && bits[slot] == key
                && charCount[slot] == n && Objects.equals(languages[slot], language) && same(text, start, slot)) {
                hits++;
                int g = glyphAt[slot];
                for (int i = 0; i < glyphCount[slot]; i++) {
                    out.add(glyphs[g + i], start + clusters[g + i], advances[g + i], offsetsX[g + i],
                        offsetsY[g + i], flags[g + i]);
                }
                return;
            }
            slot = slot + 1 & mask;
        }
        misses++;
        int first = out.size();
        shaper.shape(text, length, runStart, runEnd, start, end, face, sizePx, rightToLeft, language, out);
        int made = out.size() - first;
        if (count + 1 > entries || charsUsed + n > chars.length || glyphsUsed + made > glyphs.length) {
            if (made > glyphs.length || n > chars.length) {
                return; // larger than the pools: not kept
            }
            generation++; // full: start over
            count = 0;
            charsUsed = 0;
            glyphsUsed = 0;
            slot = hash & mask;
        }
        slotGeneration[slot] = generation;
        hashes[slot] = hash;
        faces[slot] = face;
        sizes[slot] = sizePx;
        bits[slot] = (byte) key;
        languages[slot] = language;
        charAt[slot] = charsUsed;
        charCount[slot] = n;
        System.arraycopy(text, start, chars, charsUsed, n);
        charsUsed += n;
        glyphAt[slot] = glyphsUsed;
        glyphCount[slot] = made;
        for (int i = 0; i < made; i++) {
            int g = glyphsUsed + i;
            glyphs[g] = out.glyph(first + i);
            clusters[g] = out.cluster(first + i) - start;
            advances[g] = out.advance(first + i);
            offsetsX[g] = out.offsetX(first + i);
            offsetsY[g] = out.offsetY(first + i);
            flags[g] = (byte) out.flags(first + i);
        }
        glyphsUsed += made;
        count++;
    }

    /** Printable Basic Latin to Latin Extended-B: no shaper shapes these by their context. */
    private static boolean simple(char[] text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text[i];
            if (c < 0x20 || c > 0x24F || c >= 0x7F && c < 0xA0) {
                return false;
            }
        }
        return true;
    }

    /** Whether the run holds a letter, which gives it its script (Latin) rather than none. */
    private static boolean letter(char[] text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text[i];
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= 0xC0 && c != 0xD7 && c != 0xF7) {
                return true;
            }
        }
        return false;
    }

    private boolean same(char[] text, int start, int slot) {
        int at = charAt[slot];
        for (int i = 0; i < charCount[slot]; i++) {
            if (chars[at + i] != text[start + i]) {
                return false;
            }
        }
        return true;
    }

    private static int hash(char[] text, int start, int end, FontFace face, float size, int key) {
        int h = System.identityHashCode(face) * 31 + Float.floatToRawIntBits(size);
        h = h * 31 + key;
        for (int i = start; i < end; i++) {
            h = h * 31 + text[i];
        }
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        return h ^ h >>> 13;
    }

    @AllowAllocation("the tunable changed")
    private void resize(int size) {
        entries = size;
        int slots = Integer.highestOneBit(Math.max(1, size) * 2) * 2; // a power of two, at most half full
        mask = slots - 1;
        slotGeneration = new int[slots];
        hashes = new int[slots];
        faces = new FontFace[slots];
        sizes = new float[slots];
        bits = new byte[slots];
        languages = new String[slots];
        charAt = new int[slots];
        charCount = new int[slots];
        glyphAt = new int[slots];
        glyphCount = new int[slots];
        chars = new char[size * CHARS_PER_ENTRY];
        int g = size * GLYPHS_PER_ENTRY;
        glyphs = new int[g];
        clusters = new int[g];
        advances = new float[g];
        offsetsX = new float[g];
        offsetsY = new float[g];
        flags = new byte[g];
        generation = 1;
        count = 0;
        charsUsed = 0;
        glyphsUsed = 0;
    }
}
