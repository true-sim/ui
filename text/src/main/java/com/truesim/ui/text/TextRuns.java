package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Style runs over a text for a {@link TextBlock}: rich text. Each run starts at a character and lasts until the next
 * run's start (the first starts at 0), with its own font (families, size, weight, italic), colour, decorations
 * (underline, line through), background highlight, an optional inline object (an image or icon taking a box of its
 * own: the run's one character, U+FFFC, is not drawn) and a tag the owner gives it (a link's number).
 *
 * <p>Runs whose fonts are the same are shaped together, so kerning and ligatures go on across a change of colour or
 * decoration; a change of font, size, weight or style, or an object, separates shaping. Lines take the height of
 * their tallest run. Change the runs, then {@link #changed()}, and set the block again: only a changed
 * {@link #version()} reshapes it. Reused: {@link #clear()} keeps the arrays, so restyling allocates nothing once they
 * are large enough.
 */
public final class TextRuns {

    /** Decoration bit: a line under the text. */
    public static final int UNDERLINE = 1;
    /** Decoration bit: a line through the text. */
    public static final int LINE_THROUGH = 2;

    private int count;
    private int[] start = new int[8];
    private Object[] families = new Object[8];
    private float[] size = new float[8];
    private int[] weight = new int[8];
    private boolean[] italic = new boolean[8];
    private long[] color = new long[8];
    private boolean[] hasColor = new boolean[8];
    private int[] decoration = new int[8];
    private long[] background = new long[8];
    private float[] objectWidth = new float[8];
    private float[] objectHeight = new float[8];
    private int[] tag = new int[8];
    private long version;

    /** No runs. */
    @AllowAllocation("one per rich text element")
    public TextRuns() {
    }

    /** Removes every run; keeps the arrays. */
    public void clear() {
        count = 0;
    }

    /**
     * Adds a run from character {@code from} (at or after the last run's start; the first run starts at 0) in the
     * given font, without colour (the block's colour), decoration, background, object or tag; returns its index.
     *
     * @param fontFamilies the families, first choice first; kept, so pass an immutable list
     */
    public int add(int from, List<String> fontFamilies, float sizePx, int fontWeight, boolean fontItalic) {
        if (count == 0 ? from != 0 : from < start[count - 1]) {
            throw new IllegalArgumentException("a run starting at " + from + " is out of order");
        }
        if (!(sizePx > 0)) {
            throw new IllegalArgumentException("size " + sizePx);
        }
        if (count == start.length) {
            grow();
        }
        int r = count++;
        start[r] = from;
        families[r] = Objects.requireNonNull(fontFamilies, "fontFamilies");
        size[r] = sizePx;
        weight[r] = fontWeight;
        italic[r] = fontItalic;
        color[r] = 0;
        hasColor[r] = false;
        decoration[r] = 0;
        background[r] = 0;
        objectWidth[r] = 0;
        objectHeight[r] = 0;
        tag[r] = -1;
        return r;
    }

    /** Sets run {@code r}'s colour ({@link com.truesim.ui.core.draw.Rgba}); without one it takes the block's. */
    public TextRuns color(int r, long rgba) {
        color[r] = rgba;
        hasColor[r] = true;
        return this;
    }

    /** Sets run {@code r}'s decorations ({@link #UNDERLINE}, {@link #LINE_THROUGH}). */
    public TextRuns decoration(int r, int bits) {
        decoration[r] = bits;
        return this;
    }

    /** Sets run {@code r}'s background highlight (an {@link com.truesim.ui.core.draw.Rgba}, 0 for none). */
    public TextRuns background(int r, long rgba) {
        background[r] = rgba;
        return this;
    }

    /**
     * Makes run {@code r} an inline object: its one character (U+FFFC) takes a box {@code widthPx} wide and
     * {@code heightPx} high standing on the baseline, and draws no glyph; its owner draws the image there.
     */
    public TextRuns object(int r, float widthPx, float heightPx) {
        if (!(widthPx >= 0) || !(heightPx >= 0)) {
            throw new IllegalArgumentException("object " + widthPx + " x " + heightPx);
        }
        objectWidth[r] = widthPx;
        objectHeight[r] = heightPx;
        return this;
    }

    /** Sets run {@code r}'s tag: the owner's number for it (a link, an image), -1 for none. */
    public TextRuns tag(int r, int value) {
        tag[r] = value;
        return this;
    }

    /** Marks the runs changed: a block set with them shapes again. */
    public void changed() {
        version++;
    }

    /** Grows with each {@link #changed()}. */
    public long version() {
        return version;
    }

    /** Runs. */
    public int count() {
        return count;
    }

    /** Run {@code r}'s first character. */
    public int start(int r) {
        return start[r];
    }

    /** The run covering character {@code index} (the last run starting at or before it); -1 without runs. */
    public int runAt(int index) {
        int lo = 0;
        int hi = count - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] <= index) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Run {@code r}'s font families. */
    @SuppressWarnings("unchecked")
    public List<String> families(int r) {
        return (List<String>) families[r];
    }

    /** Run {@code r}'s size, pixels per em. */
    public float size(int r) {
        return size[r];
    }

    /** Run {@code r}'s weight. */
    public int weight(int r) {
        return weight[r];
    }

    /** Whether run {@code r} is italic. */
    public boolean italic(int r) {
        return italic[r];
    }

    /** Whether run {@code r} has a colour of its own. */
    public boolean hasColor(int r) {
        return hasColor[r];
    }

    /** Run {@code r}'s colour, if {@link #hasColor}. */
    public long color(int r) {
        return color[r];
    }

    /** Run {@code r}'s decoration bits. */
    public int decoration(int r) {
        return decoration[r];
    }

    /** Run {@code r}'s background highlight, 0 for none. */
    public long background(int r) {
        return background[r];
    }

    /** Whether run {@code r} is an inline object. */
    public boolean isObject(int r) {
        return objectWidth[r] > 0 || objectHeight[r] > 0;
    }

    /** Run {@code r}'s object width, pixels. */
    public float objectWidth(int r) {
        return objectWidth[r];
    }

    /** Run {@code r}'s object height, pixels. */
    public float objectHeight(int r) {
        return objectHeight[r];
    }

    /** Run {@code r}'s tag, -1 for none. */
    public int tag(int r) {
        return tag[r];
    }

    /** Whether runs {@code a} and {@code b} use the same font: they shape together. */
    public boolean sameFont(int a, int b) {
        return size[a] == size[b] && weight[a] == weight[b] && italic[a] == italic[b]
            && (families[a] == families[b] || families[a].equals(families[b]));
    }

    @AllowAllocation("growing, rarely")
    private void grow() {
        int n = start.length * 2;
        start = Arrays.copyOf(start, n);
        families = Arrays.copyOf(families, n);
        size = Arrays.copyOf(size, n);
        weight = Arrays.copyOf(weight, n);
        italic = Arrays.copyOf(italic, n);
        color = Arrays.copyOf(color, n);
        hasColor = Arrays.copyOf(hasColor, n);
        decoration = Arrays.copyOf(decoration, n);
        background = Arrays.copyOf(background, n);
        objectWidth = Arrays.copyOf(objectWidth, n);
        objectHeight = Arrays.copyOf(objectHeight, n);
        tag = Arrays.copyOf(tag, n);
    }
}
