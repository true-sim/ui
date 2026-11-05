package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import java.lang.Character.UnicodeScript;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One element's text, shaped and laid out, kept between frames: a label, a button caption, a paragraph.
 *
 * <p><b>Pipeline.</b> {@link #set} compares its inputs with the last ones and does only what changed:
 * <ol>
 * <li><b>shaping</b> (text, fonts, size, direction, language): paragraphs at line feeds; BiDi levels per character
 * ({@link Bidi}, UAX #9, skipped for text without right-to-left characters); runs of one level, one script
 * ({@link UnicodeScript}, common and inherited characters joining their neighbours) and one face (the first of the
 * {@link FontChain} that covers the character; marks and joiners stay with their base); each run shaped once;</li>
 * <li><b>layout</b> (alignment, maximum width, line height): lines broken at the JDK's line break opportunities
 * (UAX #14), a word wider than the line broken between grapheme clusters; each line's runs put in visual order (rule
 * L2), trailing white space left out (rule L1, simplified); lines aligned in the box; caret positions per
 * character.</li>
 * </ol>
 * Unchanged inputs cost a comparison.
 *
 * <p><b>Incremental reshaping.</b> When only the text changed (typing into a long paragraph), the block redoes only
 * what the edit reaches ({@link #incremental(boolean)}, on by default; blocks shorter than {@value #INCREMENTAL_MIN}
 * characters reshape whole): the edit is found by the common prefix and suffix; BiDi levels are kept for paragraphs
 * without right-to-left text and inserted text without any, else resolved again with the changed range found by
 * comparison; itemisation restarts at a run that starts at a character of a script of its own (whose run does not
 * depend on what came before) a margin before the edit and stops where its runs meet the old ones again past it;
 * within a long run, only a segment between split points is shaped again: clusters whose glyphs carry neither of the
 * shaper's unsafe-to-break and unsafe-to-concat flags (HarfBuzz), or that follow white space (shapers without flags),
 * with the old glyphs kept on either side once the new segment's tail matches the old glyphs over a margin; lines are
 * broken again from a clean line start before the edit until a line starts where an old one did past it. The result
 * equals shaping from scratch (glyphs, lines, carets); a change of a paragraph's direction, of paragraphs (line feeds)
 * or of anything but the text shapes from scratch.
 *
 * <p>{@link #record} writes one {@link DrawList#glyph GLYPH} primitive per visible
 * glyph and allocates nothing.
 *
 * <p><b>Small text</b> (UI16): {@link #record} draws hinted raster glyphs ({@link DrawList#rasterGlyph}, on whole
 * pixels) instead of MTSDF when the atlas's settings ask for them at this size ({@link AtlasSettings#smallText()}),
 * the block is not in world space ({@link #worldSpace(boolean)}) and it has no outline and no weight offset (effects
 * only the distance field has). Which technique draws never changes the layout: positions come from shaping.
 *
 * <p><b>Rich text</b> ({@link #set(CharSequence, TextRuns, TextAlign, float, float, TextDirection, String)}): style
 * runs give parts of the text their own font, size, colour, decorations, highlight or an inline object box. Runs of
 * the same font shape together (a colour change keeps kerning and ligatures); lines take the height of their tallest
 * run, so line tops are kept per line ({@link #lineTop}, {@link #lineHeight}).
 *
 * <p><b>Emoji</b>: characters shown as emoji (emoji presentation, U+FE0F, keycaps, flags, skin tones; ZWJ sequences
 * stay together) take the registry's emoji faces first ({@link FontRegistry#emoji}); a face with colour bitmaps
 * ({@link FontFace#colorStrike()}) draws them as colour glyphs ({@link DrawList#colorGlyph}) from the atlas's colour
 * pages, white with the text colour's alpha.
 *
 * <p><b>Editing support</b>: carets per character ({@link #caretX}, {@link #hitTest}), visual caret movement through
 * mixed directions ({@link #moveVisually}), the boxes a range covers per line ({@link #rangeBoxes}) and recording only
 * some lines ({@link #recordLines}) for scrolled text.
 *
 * <p>Coordinates are pixels relative to the block's top left, y down; baselines are on whole pixels.
 */
public final class TextBlock {

    /** Blocks shorter than this reshape whole on a text change: incremental bookkeeping pays on long text only. */
    public static final int INCREMENTAL_MIN = 128;
    /** Characters around an edit that are itemised and shaped again beyond it: more than a shaper's context. */
    private static final int MARGIN = 16;
    /** How far split points are looked for, characters. */
    private static final int SPLIT_SEARCH = 64;
    /** Characters before a line start a break iterator sees when breaking from there. */
    private static final int BREAK_CONTEXT = 64;
    /** Attempts at a shorter segment before a run is shaped whole. */
    private static final int ATTEMPTS = 4;

    private static final UnicodeScript[] SCRIPTS = UnicodeScript.values();

    private final TextSystem system;

    // inputs
    private char[] chars = new char[32];
    private int length;
    private List<String> families = List.of();
    private float size = 16;
    private int weight = 400;
    private boolean italic;
    private TextAlign align = TextAlign.START;
    private float maxWidth = Float.POSITIVE_INFINITY;
    private float lineHeight;
    private TextDirection direction = TextDirection.AUTO;
    private String language;
    private boolean shaped;
    private long fontsVersion;
    private TextRuns runs; // null: one style
    private long runsVersion;
    private boolean incremental = true;
    private long incrementalEdits;
    private int editPrefix; // diff's result: characters equal at the start and at the end
    private int editSuffix;

    // shaping, in logical order
    private FontFace primary;
    private FontChain chain;
    private byte[] levels = new byte[32];
    private float[] charAdvance = new float[32];
    private int paraCount;
    private int[] paraStart = new int[4];
    private int[] paraEnd = new int[4];
    private boolean[] paraRtl = new boolean[4];
    private boolean[] paraBidi = new boolean[4]; // levels resolved by Bidi (else all 0)
    private boolean[] paraComplex = new boolean[4]; // with explicit embeddings, isolates or brackets
    private int[] paraLine = new int[4]; // the first line
    private int[] paraRunStart = new int[4];
    private int[] paraRunEnd = new int[4];
    private int runCount;
    private int[] runStart = new int[8];
    private int[] runEnd = new int[8];
    private int[] runGlyphStart = new int[8];
    private int[] runGlyphEnd = new int[8];
    private byte[] runLevel = new byte[8];
    private FontFace[] runFace = new FontFace[8];
    private float[] runSize = new float[8];
    private int[] runStyle = new int[8]; // the style run of the run's first character, -1 without runs
    private boolean[] runMono = new boolean[8]; // clusters in visual order: increasing (left to right) or decreasing
    private final ShapedGlyphs shapedGlyphs = new ShapedGlyphs();
    private int[] charStyle = new int[32];
    private FontChain[] styleChain = new FontChain[8];
    private float[] styleAbove = new float[8]; // a style's line box above and below the baseline, pixels
    private float[] styleBelow = new float[8];

    // layout, in visual order
    private int glyphCount;
    private int[] glyphIds = new int[32];
    private FontFace[] glyphFaces = new FontFace[32];
    private float[] glyphX = new float[32]; // the pen from the line's left edge before alignment (lineLeft)
    private float[] glyphDy = new float[32]; // from the line's baseline, y down
    private int[] glyphEntries = new int[32];
    private int[] glyphCluster = new int[32];
    private float[] glyphSize = new float[32];
    private int[] glyphStyle = new int[32];
    private int lineCount;
    private int[] lineStart = new int[4];
    private int[] lineEnd = new int[4];
    private int[] lineGlyphStart = new int[4];
    private int[] lineGlyphEnd = new int[4];
    private float[] lineLeft = new float[4];
    private float[] lineWidth = new float[4];
    private float[] lineBaseline = new float[4];
    private float[] lineTop = new float[4];
    private float[] lineBox = new float[4];
    private boolean[] lineRtl = new boolean[4];
    private boolean[] lineClean = new boolean[4]; // starts where breaking may start: not inside a split word
    private float[] charX0 = new float[32]; // from the line's left edge before alignment
    private float[] charX1 = new float[32];
    private int[] pieceRun = new int[8];
    private int[] pieceStart = new int[8];
    private int[] pieceEnd = new int[8];
    private int[] pieceOrder = new int[8];
    private float width;
    private float height;
    private float lineHeightPx;
    private float baselineOffset;
    private boolean clean; // whether the next line starts clean
    private int rangeLo; // glyphRange's result
    private int rangeHi;

    // incremental reshaping: where itemisation and line breaking may meet the old result, and scratch
    private int convergeFrom = Integer.MAX_VALUE;
    private int convergeDelta;
    private int convergeLo;
    private int convergeHi;
    private boolean bidiUsed;
    private byte[] oldLevels = new byte[32];
    private int oldRunCount;
    private int[] oRunStart = new int[8];
    private int[] oRunEnd = new int[8];
    private int[] oRunGlyphStart = new int[8];
    private int[] oRunGlyphEnd = new int[8];
    private byte[] oRunLevel = new byte[8];
    private FontFace[] oRunFace = new FontFace[8];
    private float[] oRunSize = new float[8];
    private boolean[] oRunMono = new boolean[8];
    private final ShapedGlyphs segment = new ShapedGlyphs();
    private final ShapedGlyphs fresh = new ShapedGlyphs();
    private int head; // shaping splice: old glyphs kept in place before and after the fresh ones
    private int tail;
    private boolean started;
    private int[] tmpInt = new int[16];
    private float[] tmpFloat = new float[16];
    private boolean[] tmpBool = new boolean[16];
    private Object[] tmpObject = new Object[16];

    // drawing
    private long version;
    private int atlasEpoch = -1; // the epoch the glyph entries belong to
    private int versionEpoch; // the epoch version() last saw
    private int pending;
    private long seenCompletions = -1;
    private boolean worldSpace;
    private boolean entriesRaster; // whether glyphEntries were looked up for raster drawing
    private boolean entriesWorld; // the world space the entries were looked up for
    private long entriesTechnique = -1; // the technique version the entries were looked up at
    private int[] stops = new int[16]; // visual caret movement's scratch
    private float[] stopX = new float[16];
    private float[] spanX0 = new float[16]; // range boxes' scratch
    private float[] spanX1 = new float[16];
    private long seenTechnique; // the atlas settings' technique version version() last saw
    private float lastOutline; // the outline and weight of the last record, for looking up glyphs on layout
    private float lastWeight;

    /** An empty block of {@code system}'s text. */
    @AllowAllocation("one per text element, made when the element is")
    public TextBlock(TextSystem system) {
        this.system = Objects.requireNonNull(system, "system");
    }

    /**
     * Sets the text and its look, keeping the current direction and language; see
     * {@link #set(CharSequence, List, float, int, boolean, TextAlign, float, float, TextDirection, String)}.
     */
    public boolean set(CharSequence text, List<String> families, float sizePx, int weight, boolean italic,
        TextAlign align, float maxWidth, float lineHeight) {
        return set(text, families, sizePx, weight, italic, align, maxWidth, lineHeight, direction, language);
    }

    /**
     * Sets the text and its look; reshapes and lays out only if something changed. True if the layout changed.
     *
     * @param text the characters; copied
     * @param families font families, first choice first (the style's font list); kept, so pass an immutable list
     * @param sizePx the font size in pixels per em
     * @param weight 100 to 900
     * @param italic the italic style
     * @param align line alignment in the box
     * @param maxWidth the box width: lines break to fit it and align in it; {@link Float#POSITIVE_INFINITY} for one
     *     line per paragraph, aligned in the widest line
     * @param lineHeight line spacing as a multiple of the size (the style's {@code line-height}), or 0 for the font's
     * @param direction the paragraphs' base direction
     * @param language a BCP 47 tag for language-specific shaping, or null
     */
    public boolean set(CharSequence text, List<String> families, float sizePx, int weight, boolean italic,
        TextAlign align, float maxWidth, float lineHeight, TextDirection direction, String language) {
        return set(text, null, families, sizePx, weight, italic, align, maxWidth, lineHeight, direction, language);
    }

    /**
     * Sets rich text: {@code text} in the style {@code runs} give it (at least one run, the first at 0); otherwise as
     * {@link #set(CharSequence, List, float, int, boolean, TextAlign, float, float, TextDirection, String)}. The first
     * run's font gives the block's {@link #size()} and the metrics of an empty block. Reshapes when the text, the runs'
     * {@link TextRuns#version()}, the direction or language changed.
     */
    public boolean set(CharSequence text, TextRuns styleRuns, TextAlign align, float maxWidth, float lineHeight,
        TextDirection direction, String language) {
        if (styleRuns.count() == 0) {
            throw new IllegalArgumentException("rich text needs at least one run");
        }
        return set(text, styleRuns, styleRuns.families(0), styleRuns.size(0), styleRuns.weight(0),
            styleRuns.italic(0), align, maxWidth, lineHeight, direction, language);
    }

    private boolean set(CharSequence text, TextRuns styleRuns, List<String> families, float sizePx, int weight,
        boolean italic, TextAlign align, float maxWidth, float lineHeight, TextDirection direction, String language) {
        boolean sameStyle = shaped && (families == this.families || families.equals(this.families))
            && sizePx == size && weight == this.weight && italic == this.italic && direction == this.direction
            && Objects.equals(language, this.language) && system.fonts().version() == fontsVersion
            && styleRuns == runs && (styleRuns == null || styleRuns.version() == runsVersion);
        boolean reshape = !sameStyle || !diff(text);
        boolean sameBox = align == this.align && maxWidth == this.maxWidth && lineHeight == this.lineHeight;
        if (!reshape && sameBox) {
            return false;
        }
        if (!(sizePx > 0) || !(maxWidth > 0) || !(lineHeight >= 0)) {
            throw new IllegalArgumentException("size " + sizePx + ", max width " + maxWidth + ", line height "
                + lineHeight);
        }
        // Only the box width changed, and every paragraph was one line that still fits: the lines stay as they are and
        // only move to their new alignment. The layout engine measures a label several times a frame at different
        // widths; this keeps that from breaking lines again each time.
        if (!reshape && align == this.align && lineHeight == this.lineHeight && lineCount == paraCount
            && width <= maxWidth) {
            this.maxWidth = maxWidth;
            if (realign()) {
                version++;
                return true;
            }
            return false;
        }
        if (reshape && sameStyle && sameBox && styleRuns == null && edit(text)) {
            incrementalEdits++;
            version++;
            return true;
        }
        this.align = Objects.requireNonNull(align, "align");
        this.maxWidth = maxWidth;
        this.lineHeight = lineHeight;
        if (reshape) {
            copy(text);
            this.families = Objects.requireNonNull(families, "families");
            this.size = sizePx;
            this.weight = weight;
            this.italic = italic;
            this.direction = Objects.requireNonNull(direction, "direction");
            this.language = language;
            this.runs = styleRuns;
            this.runsVersion = styleRuns == null ? 0 : styleRuns.version();
            shape();
        }
        layout();
        version++;
        return true;
    }

    /**
     * Turns incremental reshaping on (the default) or off: off, every text change shapes and lays out the block from
     * scratch (for comparisons and benchmarks).
     */
    public void incremental(boolean on) {
        incremental = on;
    }

    /** Whether text changes reshape incrementally. */
    public boolean incremental() {
        return incremental;
    }

    /** How many text changes were applied incrementally (the rest shaped from scratch): for tests and benchmarks. */
    public long incrementalEdits() {
        return incrementalEdits;
    }

    /** Compares {@code text} with the block's: how many characters are equal at its start and end; true if all. */
    private boolean diff(CharSequence text) {
        int n = text.length();
        int max = Math.min(n, length);
        int p = 0;
        while (p < max && text.charAt(p) == chars[p]) {
            p++;
        }
        int s = 0;
        while (s < max - p && text.charAt(n - 1 - s) == chars[length - 1 - s]) {
            s++;
        }
        editPrefix = p;
        editSuffix = s;
        return n == length && p == length;
    }

    // ---------------------------------------------------------------- incremental reshaping

    /**
     * Applies a change of the text alone (found by {@link #diff}) to the shaped and laid out block, redoing only what
     * it reaches; false when it cannot be done incrementally, having changed nothing.
     */
    private boolean edit(CharSequence text) {
        int newLength = text.length();
        if (!incremental || newLength < INCREMENTAL_MIN || length < INCREMENTAL_MIN) {
            return false;
        }
        int p = editPrefix;
        int s = editSuffix;
        if (p > 0 && Character.isHighSurrogate(chars[p - 1])) {
            p--; // whole code points
        }
        if (s > 0 && Character.isLowSurrogate(chars[length - s])) {
            s--;
        }
        int oldEnd = length - s;
        int newEnd = newLength - s;
        for (int i = p; i < oldEnd; i++) {
            if (separator(chars[i])) {
                return false; // paragraphs change
            }
        }
        for (int i = p; i < newEnd; i++) {
            if (separator(text.charAt(i))) {
                return false;
            }
        }
        int para = paragraphAt(p);
        int delta = newLength - length;
        replaceText(text, p, oldEnd, newEnd);
        paraEnd[para] += delta;
        for (int q = para + 1; q < paraCount; q++) {
            paraStart[q] += delta;
            paraEnd[q] += delta;
        }
        int ps = paraStart[para];
        int pe = paraEnd[para];

        // BiDi levels, and the range where they changed
        int levelFrom = p;
        int levelTo = newEnd;
        if (paraBidi[para] || direction == TextDirection.RTL || Bidi.requiresBidi(chars, p, newEnd)) {
            if (oldLevels.length < pe - ps) {
                growOldLevels(pe - ps);
            }
            // Without explicit embeddings, isolates and brackets, a level depends on the strong characters around it
            // only: the levels from the last strong character before the edit to the first after it are resolved
            // again (when one before keeps the paragraph's direction), else the whole paragraph's.
            int w0 = paraBidi[para] && !paraComplex[para] && !complex(p, newEnd) ? strongBefore(ps, p) : -1;
            int w1 = w0 >= 0 ? strongAfter(newEnd, pe) : pe;
            if (w0 < 0) {
                w0 = ps;
            }
            System.arraycopy(levels, w0, oldLevels, 0, w1 - w0); // the edit's own part is stale: not compared
            if (w0 > ps || w1 < pe) {
                Bidi bidi = bidi(w0, w1, paraRtl[para] ? TextDirection.RTL : TextDirection.LTR);
                for (int r = 0; r < bidi.getRunCount(); r++) {
                    Arrays.fill(levels, w0 + bidi.getRunStart(r), w0 + bidi.getRunLimit(r),
                        (byte) bidi.getRunLevel(r));
                }
            } else {
                boolean rtl = levels(ps, pe);
                paraBidi[para] = bidiUsed;
                paraComplex[para] = bidiUsed && complex(ps, pe);
                if (rtl != paraRtl[para]) {
                    shape(); // the paragraph's direction changed
                    layout();
                    return true;
                }
            }
            for (int i = w0; i < p; i++) {
                if (levels[i] != oldLevels[i - w0]) {
                    levelFrom = i;
                    break;
                }
            }
            for (int i = w1 - 1; i >= newEnd; i--) {
                if (levels[i] != oldLevels[i - w0]) {
                    levelTo = i + 1;
                    break;
                }
            }
        } else {
            Arrays.fill(levels, p, newEnd, (byte) 0);
        }

        // itemisation: from a run that does not depend on what came before, a margin before the change
        int a = Math.max(ps, levelFrom - MARGIN);
        int r0 = paraRunStart[para];
        int r1 = paraRunEnd[para];
        int rA = r0;
        for (int lo = r0, hi = r1 - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (runStart[mid] <= a) {
                rA = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // a character with a script of its own decides the itemiser's state alone: start there, inside its run
        int anchor = -1;
        for (int c = a; r1 > r0 && c >= runStart[rA] && c > a - SPLIT_SEARCH; c--) {
            if (!Character.isLowSurrogate(chars[c]) && independent(c)) {
                anchor = c;
                break;
            }
        }
        while (anchor < 0 && rA > r0 && !independent(runStart[rA])) {
            rA--; // else from a run starting at such a character, or the paragraph's start
        }
        int restart = rA < r1 ? runStart[rA] : ps;
        if (anchor < 0 && rA > r0) {
            anchor = restart;
        }
        saveRuns(rA);
        int oldInPara = r1 - rA;
        runCount = rA;
        convergeFrom = levelTo + MARGIN;
        convergeDelta = delta;
        convergeHi = oldInPara;
        int k = itemize(anchor >= 0 ? anchor : restart, restart, pe, chain);
        convergeFrom = Integer.MAX_VALUE;
        int newRunsEnd = runCount;
        if (k < 0) {
            k = oldInPara;
        }
        int changeEnd = Math.max(newEnd, levelTo);
        if (newRunsEnd - rA >= 2) {
            changeEnd = Math.max(changeEnd, runStart[newRunsEnd - 1]);
        }
        if (k >= 2) {
            changeEnd = Math.max(changeEnd, oRunStart[k - 1] + delta);
        }
        for (int o = k; o < oldRunCount; o++) { // the old runs past the meeting point, moved
            if (runCount == runStart.length) {
                growRuns();
            }
            runStart[runCount] = oRunStart[o] + delta;
            runEnd[runCount] = oRunEnd[o] + delta;
            runLevel[runCount] = oRunLevel[o];
            runFace[runCount] = oRunFace[o];
            runSize[runCount] = oRunSize[o];
            runStyle[runCount] = -1;
            runMono[runCount] = oRunMono[o];
            runCount++;
        }
        int runDelta = newRunsEnd - rA - k;
        paraRunEnd[para] += runDelta;
        for (int q = para + 1; q < paraCount; q++) {
            paraRunStart[q] += runDelta;
            paraRunEnd[q] += runDelta;
        }

        // shaping: the new runs, keeping the old glyphs a long run has on either side of the edit
        int g0 = oldRunCount > 0 ? oRunGlyphStart[0] : shapedGlyphs.size();
        int g1 = k > 0 ? oRunGlyphEnd[k - 1] : g0;
        fresh.clear();
        head = g0;
        tail = g1;
        started = false;
        int cursor = g0;
        int firstShaped = Integer.MAX_VALUE;
        int shapedEnd = p;
        for (int j = rA; j < newRunsEnd; j++) {
            boolean last = j == newRunsEnd - 1;
            int ns = runStart[j];
            int ne = runEnd[j];
            boolean rtl = (runLevel[j] & 1) != 0;
            int o1 = j == rA && k > 0 && oRunStart[0] == ns && sameRun(j, 0) && anchor >= 0 ? 0 : -1;
            int o2 = last && k > 0 && oRunEnd[k - 1] + delta == ne && sameRun(j, k - 1) ? k - 1 : -1;
            // the old glyphs stay before x and from z: x and z are split points with a margin of old glyphs that
            // the new segment [w, z) must give again ([w, x) and [y, z)), so the edit's effect ends inside the segment
            int x = o1 >= 0 ? splitBefore(o1, Math.min(a, ne - 1), ns, p, delta) : ns;
            int w = x > ns ? splitBefore(o1, x - MARGIN, ns, p, delta) : ns;
            int y = o2 >= 0 ? splitAfter(o2, Math.max(oldEnd, levelTo - delta), p, delta) : -1;
            int z = y >= 0 ? splitAfter(o2, y + MARGIN, p, delta) : -1;
            for (int attempt = 1; ; attempt++) {
                if (w == ns) {
                    x = ns; // shaped from the run's start: nothing to keep before
                }
                if (attempt >= ATTEMPTS) {
                    w = ns; // shape the whole run
                    x = ns;
                    z = -1;
                }
                int segEnd = z >= 0 ? z + delta : ne;
                segment.clear();
                system.shaper().shape(chars, length, ns, ne, w, segEnd, runFace[j], runSize[j], rtl, language,
                    segment);
                boolean leftOk = x == ns || segmentMatches(o1, w, x, 0, rtl);
                boolean rightOk = z < 0 || segmentMatches(o2, y, z, delta, rtl);
                if (leftOk && rightOk) {
                    break;
                }
                if (!leftOk) {
                    x = w;
                    w = splitBefore(o1, x - MARGIN, ns, p, delta);
                }
                if (!rightOk) {
                    y = z;
                    z = splitAfter(o2, y + MARGIN, p, delta);
                }
            }
            int segEnd = z >= 0 ? z + delta : ne;
            // the segment's glyphs from x on (left to right at its end, mirrored at its start)
            int s0 = 0;
            int s1 = segment.size();
            if (rtl) {
                while (s1 > s0 && segment.cluster(s1 - 1) < x) {
                    s1--;
                }
            } else {
                while (s0 < s1 && segment.cluster(s0) < x) {
                    s0++;
                }
            }
            firstShaped = Math.min(firstShaped, x);
            shapedEnd = Math.max(shapedEnd, segEnd);
            Arrays.fill(charAdvance, x, segEnd, 0);
            for (int g = s0; g < s1; g++) {
                charAdvance[segment.cluster(g)] += segment.advance(g);
            }
            boolean mono = monotone(segment, s0, s1, rtl);
            runGlyphStart[j] = cursor;
            if (x > ns) {
                mono &= oRunMono[o1];
            }
            if (z >= 0) {
                mono &= oRunMono[o2];
            }
            // pieces in visual order: left to right the part before x, the segment, the part from z; mirrored
            int gx = x > ns ? oldSplit(o1, x) : -1;
            int gz = z >= 0 ? oldSplit(o2, z) : -1;
            if (rtl) {
                if (z >= 0) {
                    cursor = emitOld(cursor, oRunGlyphStart[o2], gz, g0, g1, false, oldEnd, delta);
                }
                cursor = emitSegment(cursor, s0, s1);
                if (x > ns) {
                    cursor = emitOld(cursor, gx, oRunGlyphEnd[o1], g0, g1, last, oldEnd, delta);
                }
            } else {
                if (x > ns) {
                    cursor = emitOld(cursor, oRunGlyphStart[o1], gx, g0, g1, false, oldEnd, delta);
                }
                cursor = emitSegment(cursor, s0, s1);
                if (z >= 0) {
                    cursor = emitOld(cursor, gz, oRunGlyphEnd[o2], g0, g1, last, oldEnd, delta);
                }
            }
            runGlyphEnd[j] = cursor;
            runMono[j] = mono;
        }
        int glyphDelta = cursor - g1;
        shapedGlyphs.moveClusters(g0, head, oldEnd, delta);
        shapedGlyphs.replace(head, tail, fresh);
        shapedGlyphs.moveClusters(head + fresh.size(), shapedGlyphs.size(), oldEnd, delta);
        for (int r = newRunsEnd; r < runCount; r++) {
            runGlyphStart[r] = oRunGlyphStart[k + r - newRunsEnd] + glyphDelta;
            runGlyphEnd[r] = oRunGlyphEnd[k + r - newRunsEnd] + glyphDelta;
        }
        if (firstShaped == Integer.MAX_VALUE) {
            firstShaped = Math.min(restart, p); // nothing shaped: the paragraph became empty
        }
        relayout(para, Math.min(firstShaped, p), Math.max(changeEnd, shapedEnd), delta);
        return true;
    }

    /**
     * Whether {@code chars[from, to)} has characters whose BiDi levels reach beyond their strong neighbours: explicit
     * embeddings, overrides and isolates, and brackets (paired by rule N0).
     */
    private boolean complex(int from, int to) {
        for (int i = from; i < to; ) {
            int cp = Character.codePointAt(chars, i, to);
            byte d = Character.getDirectionality(cp);
            int type = Character.getType(cp);
            if (d >= Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                && d <= Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT
                || d >= Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE
                && d <= Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean strong(int cp) {
        byte d = Character.getDirectionality(cp);
        return d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    /** The last strong character of {@code chars[from, to)}, or -1. */
    private int strongBefore(int from, int to) {
        for (int i = to; i > from; ) {
            int cp = Character.codePointBefore(chars, i, from);
            i -= Character.charCount(cp);
            if (strong(cp)) {
                return i;
            }
        }
        return -1;
    }

    /** The end of the first strong character of {@code chars[from, to)}, or {@code to}. */
    private int strongAfter(int from, int to) {
        for (int i = from; i < to; ) {
            int cp = Character.codePointAt(chars, i, to);
            i += Character.charCount(cp);
            if (strong(cp)) {
                return i;
            }
        }
        return to;
    }

    private static boolean separator(char c) {
        return c == '\n' || c == ' ';
    }

    /** The paragraph character {@code i} (or the caret position) is in. */
    private int paragraphAt(int i) {
        int found = 0;
        for (int lo = 0, hi = paraCount - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (paraStart[mid] <= i) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Replaces characters {@code [p, oldEnd)} by {@code text[p, newEnd)}, moving the per-character data after them. */
    private void replaceText(CharSequence text, int p, int oldEnd, int newEnd) {
        int newLength = length + newEnd - oldEnd;
        if (chars.length < newLength) {
            growText(newLength);
        }
        int rest = length - oldEnd;
        System.arraycopy(chars, oldEnd, chars, newEnd, rest);
        System.arraycopy(levels, oldEnd, levels, newEnd, rest);
        System.arraycopy(charAdvance, oldEnd, charAdvance, newEnd, rest);
        System.arraycopy(charX0, oldEnd, charX0, newEnd, rest);
        System.arraycopy(charX1, oldEnd, charX1, newEnd, rest);
        for (int i = p; i < newEnd; i++) {
            chars[i] = text.charAt(i);
        }
        length = newLength;
    }

    @AllowAllocation("growing, rarely")
    private void growOldLevels(int n) {
        oldLevels = new byte[Math.max(n, oldLevels.length * 2)];
    }

    /**
     * Whether character {@code i} starts the same run whatever came before it: it has a script of its own and is not a
     * mark, joiner or modifier (which stay with their base), so its face, script and level are its own.
     */
    private boolean independent(int i) {
        int cp = Character.codePointAt(chars, i, length);
        UnicodeScript us = UnicodeScript.of(cp);
        return us != UnicodeScript.COMMON && us != UnicodeScript.INHERITED && !staysWithBase(cp);
    }

    /** Keeps runs {@code [from, runCount)} (old coordinates) in the scratch. */
    private void saveRuns(int from) {
        int n = runCount - from;
        if (oRunStart.length < n) {
            growOldRuns(n);
        }
        System.arraycopy(runStart, from, oRunStart, 0, n);
        System.arraycopy(runEnd, from, oRunEnd, 0, n);
        System.arraycopy(runGlyphStart, from, oRunGlyphStart, 0, n);
        System.arraycopy(runGlyphEnd, from, oRunGlyphEnd, 0, n);
        System.arraycopy(runLevel, from, oRunLevel, 0, n);
        System.arraycopy(runFace, from, oRunFace, 0, n);
        System.arraycopy(runSize, from, oRunSize, 0, n);
        System.arraycopy(runMono, from, oRunMono, 0, n);
        oldRunCount = n;
    }

    @AllowAllocation("growing, rarely")
    private void growOldRuns(int n) {
        int c = Math.max(n, oRunStart.length * 2);
        oRunStart = new int[c];
        oRunEnd = new int[c];
        oRunGlyphStart = new int[c];
        oRunGlyphEnd = new int[c];
        oRunLevel = new byte[c];
        oRunFace = new FontFace[c];
        oRunSize = new float[c];
        oRunMono = new boolean[c];
    }

    /** The old run of the edited paragraph holding character {@code index} (old coordinates). */
    private int oldRunContaining(int index) {
        int found = 0;
        for (int lo = 0, hi = convergeHi - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (oRunStart[mid] <= index) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Whether new run {@code j} shapes as old run {@code o} did: the same level, face and size. */
    private boolean sameRun(int j, int o) {
        return runLevel[j] == oRunLevel[o] && runFace[j] == oRunFace[o] && runSize[j] == oRunSize[o];
    }

    /**
     * Old run {@code o}'s glyph where clusters reach character {@code c} (old coordinates): left to right the first
     * glyph of a cluster at or after {@code c}, right to left the first of a cluster before it.
     */
    private int oldSplit(int o, int c) {
        int lo = oRunGlyphStart[o];
        int hi = oRunGlyphEnd[o];
        boolean rtl = (oRunLevel[o] & 1) != 0;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int cl = shapedGlyphs.cluster(mid);
            if (rtl ? cl >= c : cl < c) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Character {@code c} of the old text (outside the edit {@code [p, p + its old length)}). */
    private char oldChar(int c, int p, int delta) {
        return c < p ? chars[c] : chars[c + delta];
    }

    /**
     * Whether old run {@code o} may be split before character {@code c} (old coordinates): its clusters are in visual
     * order, a cluster starts at {@code c}, and its glyphs are free of the unsafe-to-break flag (or, from a shaper
     * without flags, it follows white space). The unsafe-to-concat flag would be the strict condition for reusing
     * glyphs after an edit, but it marks nearly every glyph a lookup looked at (every kerned font's letters); the new
     * segment's overlap with the old glyphs on both sides is checked instead.
     */
    private boolean safeSplit(int o, int c, int p, int delta) {
        if (!oRunMono[o] || c <= oRunStart[o] || c >= oRunEnd[o]) {
            return false;
        }
        boolean flags = system.shaper().marksUnsafeBreaks();
        if (!flags && !Character.isWhitespace(oldChar(c - 1, p, delta))) {
            return false;
        }
        int g = oldSplit(o, c);
        boolean rtl = (oRunLevel[o] & 1) != 0;
        int step = rtl ? -1 : 1;
        int first = rtl ? g - 1 : g;
        if (first < oRunGlyphStart[o] || first >= oRunGlyphEnd[o] || shapedGlyphs.cluster(first) != c) {
            return false;
        }
        for (int i = first; flags && i >= oRunGlyphStart[o] && i < oRunGlyphEnd[o] && shapedGlyphs.cluster(i) == c;
            i += step) {
            if ((shapedGlyphs.flags(i) & ShapedGlyphs.UNSAFE_TO_BREAK) != 0) {
                return false;
            }
        }
        return true;
    }

    /** The last split point of old run {@code o} at or before {@code from} and after {@code min}; else {@code min}. */
    private int splitBefore(int o, int from, int min, int p, int delta) {
        for (int c = from; c > min && c > from - SPLIT_SEARCH; c--) {
            if (safeSplit(o, c, p, delta)) {
                return c;
            }
        }
        return min;
    }

    /** The first split point of old run {@code o} at or after {@code from} (old coordinates); else -1. */
    private int splitAfter(int o, int from, int p, int delta) {
        for (int c = Math.max(from, oRunStart[o] + 1); c < oRunEnd[o] && c < from + SPLIT_SEARCH; c++) {
            if (safeSplit(o, c, p, delta)) {
                return c;
            }
        }
        return -1;
    }

    /**
     * Whether the segment's glyphs of characters {@code [c0, c1)} (old coordinates, moved by {@code delta} in the new
     * text) are old run {@code o}'s glyphs of those characters.
     */
    private boolean segmentMatches(int o, int c0, int c1, int delta, boolean rtl) {
        int from = oldSplit(o, rtl ? c1 : c0);
        int to = oldSplit(o, rtl ? c0 : c1);
        int lo = c0 + delta;
        int hi = c1 + delta;
        int s0 = 0;
        int s1 = segment.size();
        if (rtl) { // clusters fall along the segment
            while (s0 < s1 && segment.cluster(s0) >= hi) {
                s0++;
            }
            int e = s0;
            while (e < s1 && segment.cluster(e) >= lo) {
                e++;
            }
            s1 = e;
        } else {
            while (s0 < s1 && segment.cluster(s0) < lo) {
                s0++;
            }
            int e = s0;
            while (e < s1 && segment.cluster(e) < hi) {
                e++;
            }
            s1 = e;
        }
        if (s1 - s0 != to - from) {
            return false;
        }
        for (int i = 0; i < s1 - s0; i++) {
            if (!segment.same(s0 + i, shapedGlyphs, from + i, delta)) {
                return false;
            }
        }
        return true;
    }

    /** Whether glyphs {@code [from, to)}'s clusters only grow (left to right) or only shrink. */
    private static boolean monotone(ShapedGlyphs glyphs, int from, int to, boolean rtl) {
        for (int i = from + 1; i < to; i++) {
            int d = glyphs.cluster(i) - glyphs.cluster(i - 1);
            if (rtl ? d > 0 : d < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts old glyphs {@code [from, to)} next in the new glyphs of the replaced range {@code [g0, g1)}: left where
     * they are when they open the range (or, {@code last}, close it), else copied into the fresh glyphs.
     */
    private int emitOld(int cursor, int from, int to, int g0, int g1, boolean last, int oldEnd, int delta) {
        if (!started && from == g0) {
            head = to;
        } else if (last && to == g1) {
            tail = from;
        } else {
            fresh.append(shapedGlyphs, from, to, oldEnd, delta);
        }
        started = true;
        return cursor + to - from;
    }

    private int emitSegment(int cursor, int from, int to) {
        fresh.append(segment, from, to, Integer.MAX_VALUE, 0);
        started = true;
        return cursor + to - from;
    }

    private void copy(CharSequence text) {
        length = text.length();
        if (chars.length < length) {
            growText(length);
        }
        for (int i = 0; i < length; i++) {
            chars[i] = text.charAt(i);
        }
    }

    @AllowAllocation("growing, rarely")
    private void growText(int n) {
        int c = Math.max(n, chars.length * 2);
        chars = Arrays.copyOf(chars, c);
        charStyle = Arrays.copyOf(charStyle, c);
        levels = Arrays.copyOf(levels, c);
        charAdvance = Arrays.copyOf(charAdvance, c);
        charX0 = Arrays.copyOf(charX0, c);
        charX1 = Arrays.copyOf(charX1, c);
    }

    // ---------------------------------------------------------------- shaping

    private void shape() {
        FontRegistry fonts = system.fonts();
        fontsVersion = fonts.version();
        chain = fonts.chain(families, weight, italic);
        primary = chain.primary();
        if (primary == null) {
            throw new IllegalStateException("no font is registered");
        }
        paraCount = 0;
        runCount = 0;
        shapedGlyphs.clear();
        styles(fonts);
        int start = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || chars[i] == '\n' || chars[i] == ' ') {
                paragraph(start, i, chain);
                if (i < length) {
                    levels[i] = (byte) (paraRtl[paraCount - 1] ? 1 : 0); // a separator: the paragraph's level (L1)
                }
                start = i + 1;
            }
        }
        Arrays.fill(charAdvance, 0, length, 0);
        for (int g = 0; g < shapedGlyphs.size(); g++) {
            charAdvance[shapedGlyphs.cluster(g)] += shapedGlyphs.advance(g);
        }
        shaped = true;
    }

    /** Each character's style run, each style's font chain and line box (rich text). */
    private void styles(FontRegistry fonts) {
        if (runs == null) {
            return;
        }
        int n = runs.count();
        if (styleChain.length < n) {
            growStyles(n);
        }
        for (int st = 0; st < n; st++) {
            FontChain c = fonts.chain(runs.families(st), runs.weight(st), runs.italic(st));
            styleChain[st] = c;
            FontFace f = c.primary() != null ? c.primary() : primary;
            float sz = runs.size(st);
            float a = f.ascender() * sz;
            float d = f.descender() * sz;
            float h = lineHeight > 0 ? lineHeight * sz : a + d + f.lineGap() * sz;
            float above = (h - (a + d)) * 0.5f + a;
            float below = h - above;
            if (runs.isObject(st)) {
                above = Math.max(above, runs.objectHeight(st));
            }
            styleAbove[st] = above;
            styleBelow[st] = below;
            int end = st + 1 < n ? Math.min(length, runs.start(st + 1)) : length;
            for (int i = Math.min(length, runs.start(st)); i < end; i++) {
                charStyle[i] = st;
            }
        }
    }

    @AllowAllocation("growing, rarely")
    private void growStyles(int n) {
        int c = Math.max(n, styleChain.length * 2);
        styleChain = new FontChain[c];
        styleAbove = new float[c];
        styleBelow = new float[c];
    }

    private void paragraph(int start, int end, FontChain chain) {
        boolean rtl = levels(start, end);
        int p = paraCount++;
        if (p == paraStart.length) {
            growParagraphs();
        }
        paraStart[p] = start;
        paraEnd[p] = end;
        paraRtl[p] = rtl;
        paraBidi[p] = bidiUsed;
        paraRunStart[p] = runCount;
        paraComplex[p] = bidiUsed && complex(start, end);
        itemize(start, start, end, chain);
        paraRunEnd[p] = runCount;
        TextShaper shaper = system.shaper();
        for (int r = paraRunStart[p]; r < runCount; r++) {
            runGlyphStart[r] = shapedGlyphs.size();
            int st = runStyle[r];
            if (st >= 0 && runs.isObject(st)) {
                for (int i = runStart[r]; i < runEnd[r]; i++) { // a box per character, no glyph
                    shapedGlyphs.add(-1, i, runs.objectWidth(st), 0, 0);
                }
            } else {
                shaper.shape(chars, length, runStart[r], runEnd[r], runStart[r], runEnd[r], runFace[r], runSize[r],
                    (runLevel[r] & 1) != 0, language, shapedGlyphs);
            }
            runGlyphEnd[r] = shapedGlyphs.size();
            runMono[r] = monotone(shapedGlyphs, runGlyphStart[r], runGlyphEnd[r], (runLevel[r] & 1) != 0);
        }
    }

    /** Resolves the BiDi levels of {@code chars[start, end)}; true if the paragraph is right to left. */
    private boolean levels(int start, int end) {
        if (start == end || direction != TextDirection.RTL && !Bidi.requiresBidi(chars, start, end)) {
            Arrays.fill(levels, start, end, (byte) 0);
            bidiUsed = false;
            return start == end && direction == TextDirection.RTL;
        }
        bidiUsed = true;
        Bidi bidi = bidi(start, end, direction);
        for (int r = 0; r < bidi.getRunCount(); r++) {
            Arrays.fill(levels, start + bidi.getRunStart(r), start + bidi.getRunLimit(r), (byte) bidi.getRunLevel(r));
        }
        return (bidi.getBaseLevel() & 1) != 0;
    }

    @AllowAllocation("BiDi analysis of a paragraph with right-to-left text, when its text changes")
    private Bidi bidi(int start, int end, TextDirection paragraphDirection) {
        int flags = switch (paragraphDirection) {
            case AUTO -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT;
            case LTR -> Bidi.DIRECTION_LEFT_TO_RIGHT;
            case RTL -> Bidi.DIRECTION_RIGHT_TO_LEFT;
        };
        return new Bidi(chars, start, null, 0, end - start, flags);
    }

    /**
     * Splits {@code chars[start, end)} into runs. With {@link #convergeFrom} set (incremental reshaping), stops at the
     * first character from there on that has a script of its own, where the old runs meet the new ones: the current
     * run ends where the old one holding that character did; returns the first old run after it (an index among the
     * saved ones); else -1. Starts at {@code start} as if the current run had started at {@code runFrom} (an
     * incremental restart at a character with a script of its own, inside its run).
     */
    private int itemize(int start, int runFrom, int end, FontChain chain) {
        int from = runFrom;
        byte level = 0;
        int script = -1; // none yet: common characters take the script that follows
        FontFace face = null;
        boolean emojiFace = false;
        int style = -1;
        FontChain c = chain;
        for (int i = start; i < end; ) {
            int cp = Character.codePointAt(chars, i, end);
            int st = runs == null ? -1 : charStyle[i];
            boolean newStyle = i > start && st != style && (runs.isObject(st) || runs.isObject(style)
                || !runs.sameFont(st, style));
            if (st >= 0) {
                c = styleChain[st];
            }
            UnicodeScript us = UnicodeScript.of(cp);
            boolean neutral = us == UnicodeScript.COMMON || us == UnicodeScript.INHERITED;
            int s = neutral ? -1 : us.ordinal();
            FontFace f;
            FontFace emoji;
            if (face != null && !newStyle && staysWithBase(cp)) {
                f = face; // marks, joiners, variation selectors and skin tones stay with their base
            } else if (wantsEmoji(cp, i + Character.charCount(cp), end) && (emoji = c.emojiFace(cp)) != null) {
                f = emoji;
            } else if (face != null && !newStyle && !emojiFace && neutral && face.covers(cp)) {
                f = face;
            } else {
                f = c.faceFor(cp, neutral && script >= 0 ? SCRIPTS[script] : us);
            }
            byte l = levels[i];
            if (i >= convergeFrom && s >= 0 && !staysWithBase(cp)) {
                // A character with a script of its own decides its run's state alone: from here the old runs go on,
                // the one it is in continuing the current run (or starting with it).
                int old = oldRunContaining(i - convergeDelta);
                boolean split = i > start && (l != level || f != face || s != script && script >= 0);
                if (split) {
                    addRun(from, i, level, face, style);
                    from = i;
                }
                addRun(from, oRunEnd[old] + convergeDelta, l, f, -1);
                return old + 1;
            }
            if (i > start && (l != level || f != face || newStyle || s >= 0 && script >= 0 && s != script)) {
                addRun(from, i, level, face, style);
                from = i;
                script = s;
            } else if (script < 0) {
                script = s;
            }
            level = l;
            if (f != face) {
                emojiFace = c.isEmoji(f);
            }
            face = f;
            style = i == from ? st : style;
            i += Character.charCount(cp);
        }
        if (end > start) {
            addRun(from, end, level, face, style);
        }
        return -1;
    }

    /**
     * Whether {@code cp}, followed by {@code chars[next, end)}, is shown as an emoji: emoji presentation by default
     * (unless U+FE0E follows), an emoji asked for with U+FE0F, a keycap ({@code 0-9 # *} with U+20E3), a regional
     * indicator (flags), an emoji modifier base with a skin tone.
     */
    private boolean wantsEmoji(int cp, int next, int end) {
        if (cp < 0x80 && !isKeycapBase(cp)) {
            return false; // the common case, without a look at the next character
        }
        int after = next < end ? Character.codePointAt(chars, next, end) : -1;
        if (after == 0xFE0E) {
            return false;
        }
        if (isKeycapBase(cp)) {
            if (after == 0xFE0F && next + 1 < end) {
                after = Character.codePointAt(chars, next + 1, end);
            }
            return after == 0x20E3;
        }
        return after == 0xFE0F && Character.isEmoji(cp) || cp >= 0x1F1E6 && cp <= 0x1F1FF
            || Character.isEmojiPresentation(cp)
            || after >= 0x1F3FB && after <= 0x1F3FF && Character.isEmojiModifierBase(cp);
    }

    private static boolean isKeycapBase(int cp) {
        return cp >= '0' && cp <= '9' || cp == '#' || cp == '*';
    }

    /** Marks, joiners and variation selectors are shaped with their base character's face. */
    private static boolean staysWithBase(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK || type == Character.FORMAT
            || cp >= 0x1F3FB && cp <= 0x1F3FF; // emoji skin tone modifiers
    }

    private void addRun(int start, int end, byte level, FontFace face, int style) {
        if (runCount == runStart.length) {
            growRuns();
        }
        runStart[runCount] = start;
        runEnd[runCount] = end;
        runLevel[runCount] = level;
        runFace[runCount] = face;
        runStyle[runCount] = style;
        runSize[runCount] = style < 0 ? size : runs.size(style);
        runCount++;
    }

    // ---------------------------------------------------------------- layout

    private void layout() {
        float a = primary.ascender() * size;
        float d = primary.descender() * size;
        lineHeightPx = lineHeight > 0 ? lineHeight * size : a + d + primary.lineGap() * size;
        baselineOffset = (lineHeightPx - (a + d)) * 0.5f + a;
        lineCount = 0;
        glyphCount = 0;
        width = 0;
        Arrays.fill(charX0, 0, length, Float.NaN);
        Arrays.fill(charX1, 0, length, Float.NaN);
        convergeFrom = Integer.MAX_VALUE;
        for (int p = 0; p < paraCount; p++) {
            paraLine[p] = lineCount;
            breakLines(p, paraStart[p]);
        }
        height = lineCount == 0 ? 0 : lineTop[lineCount - 1] + lineBox[lineCount - 1];
        if (runs == null) {
            height = lineCount * lineHeightPx;
        }
        for (int l = 0; l < lineCount; l++) {
            lineLeft[l] = Float.NaN;
        }
        realign();
        lookUpGlyphs(rasterMode(lastOutline, lastWeight));
    }

    /**
     * Lays out again the lines of paragraph {@code para} that characters {@code [changeStart, changeEnd)} (new
     * coordinates; the text after them moved by {@code delta}) may have changed: from a clean line start before the
     * change until a line starts, past it, where an old line did; the lines after move.
     */
    private void relayout(int para, int changeStart, int changeEnd, int delta) {
        int oldLines = lineCount;
        int oldGlyphs = glyphCount;
        int first = paraLine[para];
        int end = para + 1 < paraCount ? paraLine[para + 1] : oldLines;
        int l = first;
        for (int lo = first, hi = end - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (lineStart[mid] <= changeStart) {
                l = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (l > first) {
            l--; // the change may let its line's first word move up
        }
        while (l > first && !lineClean[l]) {
            l--;
        }
        convergeFrom = changeEnd + MARGIN;
        convergeDelta = delta;
        convergeLo = l + 1;
        convergeHi = end;
        int k = breakLines(para, lineStart[l]); // new lines go after the old ones
        convergeFrom = Integer.MAX_VALUE;
        if (k < 0) {
            k = end;
        }
        int newLines = lineCount - oldLines;
        int newGlyphs = glyphCount - oldGlyphs;
        int gl = lineGlyphStart[l];
        int gk = k < oldLines ? lineGlyphStart[k] : oldGlyphs;
        int glyphDelta = gl + newGlyphs - gk;
        for (int i = oldLines; i < lineCount; i++) {
            lineGlyphStart[i] += gl - oldGlyphs;
            lineGlyphEnd[i] += gl - oldGlyphs;
        }
        for (int i = k; i < oldLines; i++) {
            lineStart[i] += delta;
            lineEnd[i] += delta;
            lineGlyphStart[i] += glyphDelta;
            lineGlyphEnd[i] += glyphDelta;
        }
        for (int g = gk; g < oldGlyphs; g++) {
            glyphCluster[g] += delta;
        }
        // lines [l, k) give way to the new ones, which were added at the end
        rotate(lineStart, l, k, oldLines, lineCount);
        rotate(lineEnd, l, k, oldLines, lineCount);
        rotate(lineGlyphStart, l, k, oldLines, lineCount);
        rotate(lineGlyphEnd, l, k, oldLines, lineCount);
        rotate(lineWidth, l, k, oldLines, lineCount);
        rotate(lineLeft, l, k, oldLines, lineCount);
        rotate(lineRtl, l, k, oldLines, lineCount);
        rotate(lineClean, l, k, oldLines, lineCount);
        rotate(glyphIds, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphFaces, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphX, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphDy, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphEntries, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphCluster, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphSize, gl, gk, oldGlyphs, glyphCount);
        rotate(glyphStyle, gl, gk, oldGlyphs, glyphCount);
        lineCount = l + newLines + oldLines - k;
        glyphCount = gl + newGlyphs + oldGlyphs - gk;
        int lineDelta = newLines - (k - l);
        for (int q = para + 1; q < paraCount; q++) {
            paraLine[q] += lineDelta;
        }
        for (int i = l; i < lineCount; i++) { // uniform lines (incremental reshaping is for one style)
            lineTop[i] = i * lineHeightPx;
            lineBox[i] = lineHeightPx;
            lineBaseline[i] = Math.round(i * lineHeightPx + baselineOffset);
        }
        for (int i = l; i < l + newLines; i++) {
            lineLeft[i] = Float.NaN;
        }
        width = 0;
        for (int i = 0; i < lineCount; i++) {
            width = Math.max(width, lineWidth[i]);
        }
        height = lineCount * lineHeightPx;
        realign();
        GlyphAtlas atlas = system.atlas();
        if (atlas != null) {
            lookUpRange(atlas, gl, gl + newGlyphs, entriesRaster);
            pending = glyphCount;
        }
    }

    /** The old line of the edited paragraph that starts clean at {@code start} (new coordinates), or -1. */
    private int oldLineAt(int start) {
        int old = start - convergeDelta;
        for (int lo = convergeLo, hi = convergeHi - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (lineStart[mid] == old) {
                return lineClean[mid] ? mid : -1;
            } else if (lineStart[mid] < old) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    @AllowAllocation("growing, rarely")
    private void growScratch(int n) {
        int c = Math.max(n, tmpInt.length * 2);
        tmpInt = new int[c];
        tmpFloat = new float[c];
        tmpBool = new boolean[c];
        tmpObject = new Object[c];
    }

    /** Moves {@code a[c, d)} to {@code a[b0, ...)}, followed by what was at {@code a[b, c)}: drops {@code a[b0, b)}. */
    private void rotate(int[] a, int b0, int b, int c, int d) {
        int n = d - c;
        if (tmpInt.length < n) {
            growScratch(n);
        }
        System.arraycopy(a, c, tmpInt, 0, n);
        System.arraycopy(a, b, a, b0 + n, c - b);
        System.arraycopy(tmpInt, 0, a, b0, n);
    }

    private void rotate(float[] a, int b0, int b, int c, int d) {
        int n = d - c;
        if (tmpFloat.length < n) {
            growScratch(n);
        }
        System.arraycopy(a, c, tmpFloat, 0, n);
        System.arraycopy(a, b, a, b0 + n, c - b);
        System.arraycopy(tmpFloat, 0, a, b0, n);
    }

    private void rotate(boolean[] a, int b0, int b, int c, int d) {
        int n = d - c;
        if (tmpBool.length < n) {
            growScratch(n);
        }
        System.arraycopy(a, c, tmpBool, 0, n);
        System.arraycopy(a, b, a, b0 + n, c - b);
        System.arraycopy(tmpBool, 0, a, b0, n);
    }

    private void rotate(Object[] a, int b0, int b, int c, int d) {
        int n = d - c;
        if (tmpObject.length < n) {
            growScratch(n);
        }
        System.arraycopy(a, c, tmpObject, 0, n);
        System.arraycopy(a, b, a, b0 + n, c - b);
        System.arraycopy(tmpObject, 0, a, b0, n);
        Arrays.fill(tmpObject, 0, n, null);
    }

    /** Moves the lines to their alignment in the current box; true if any moved. */
    private boolean realign() {
        float box = maxWidth < Float.POSITIVE_INFINITY ? maxWidth : width;
        boolean moved = false;
        for (int l = 0; l < lineCount; l++) {
            float shift = (box - lineWidth[l]) * align.leftShare(lineRtl[l]);
            if (Float.floatToRawIntBits(shift) != Float.floatToRawIntBits(lineLeft[l])) {
                moved = true;
                lineLeft[l] = shift;
            }
        }
        return moved;
    }

    /** Whether glyphs drawn with {@code outlineWidth} and {@code weight} may be hinted raster glyphs (at their size). */
    private boolean rasterMode(float outlineWidth, float weight) {
        return system.atlas() != null && outlineWidth == 0 && weight == 0;
    }

    /** Whether glyph {@code g} draws as a hinted raster glyph in raster mode {@code raster}. */
    private boolean rasterGlyph(GlyphAtlas atlas, int g, boolean raster) {
        return raster && glyphFaces[g].colorStrike() == 0 && atlas.rasterFor(glyphSize[g], worldSpace);
    }

    private void lookUpGlyphs(boolean raster) {
        GlyphAtlas atlas = system.atlas();
        if (atlas == null) {
            return;
        }
        lookUpRange(atlas, 0, glyphCount, raster);
        entriesRaster = raster;
        entriesWorld = worldSpace;
        entriesTechnique = atlas.settings().techniqueVersion();
        atlasEpoch = atlas.generation();
        versionEpoch = atlasEpoch;
        pending = glyphCount; // until recorded: the glyphs may still be generating
    }

    private void lookUpRange(GlyphAtlas atlas, int from, int to, boolean raster) {
        for (int g = from; g < to; g++) {
            if (glyphIds[g] < 0) {
                glyphEntries[g] = -1; // an inline object
            } else if (glyphFaces[g].colorStrike() > 0) {
                glyphEntries[g] = atlas.colorEntry(glyphFaces[g], glyphIds[g], glyphSize[g]);
            } else if (!rasterGlyph(atlas, g, raster)) {
                glyphEntries[g] = atlas.entry(glyphFaces[g], glyphIds[g]);
            } else {
                glyphEntries[g] = -1; // raster entries depend on where the pen falls within a pixel: record finds them
            }
        }
    }

    /**
     * Breaks paragraph {@code p}'s lines from {@code start} (its start, or a clean line start of it) on, greedily.
     * With {@link #convergeFrom} set (incremental reshaping), stops at a clean line start from there on where an old
     * line started clean: returns that old line; else -1.
     */
    private int breakLines(int p, int start) {
        int end = paraEnd[p];
        clean = true;
        // A paragraph that fits needs no break opportunities: greedy breaking would give this one line. It also spares
        // BreakIterator.setText, which allocates, for the common one-line label whose text changes every frame.
        if (!(maxWidth < Float.POSITIVE_INFINITY) || start == end
            || start == paraStart[p] && advance(start, end) - trailingSpace(start, end) <= maxWidth) {
            line(start, end, p);
            return -1;
        }
        // from a line start inside the paragraph, the break iterator sees a window from a little before it: its
        // breaks are those of the whole paragraph (rules look back a few characters), without a walk from the start
        BreakIterator breaks = system.lineBreaks(chars, Math.max(paraStart[p], start - BREAK_CONTEXT), end);
        int from = start;
        float used = 0;
        int prev = start;
        for (int b = breaks.following(start); b != BreakIterator.DONE; b = breaks.next()) {
            float segment = advance(prev, b);
            float ink = segment - trailingSpace(prev, b);
            if (from < prev && used + ink > maxWidth) {
                line(from, prev, p);
                from = prev;
                used = 0;
                clean = true;
                if (prev >= convergeFrom) {
                    int old = oldLineAt(prev);
                    if (old >= 0) {
                        return old;
                    }
                }
            }
            if (from == prev && ink > maxWidth) {
                from = splitWord(prev, b, p);
                used = advance(from, b);
            } else {
                used += segment;
            }
            prev = b;
        }
        line(from, end, p);
        return -1;
    }

    /** Breaks a word wider than the line between grapheme clusters; returns the start of its last piece. */
    private int splitWord(int start, int end, int p) {
        BreakIterator graphemes = system.characterBreaks(chars, start, end);
        int from = start;
        float used = 0;
        int prev = start;
        for (int c = graphemes.next(); c != BreakIterator.DONE; c = graphemes.next()) {
            float w = advance(prev, c);
            if (prev > from && used + w > maxWidth) {
                line(from, prev, p);
                from = prev;
                used = 0;
            }
            used += w;
            prev = c;
        }
        return from;
    }

    private float advance(int from, int to) {
        float sum = 0;
        for (int i = from; i < to; i++) {
            sum += charAdvance[i];
        }
        return sum;
    }

    private float trailingSpace(int from, int to) {
        float sum = 0;
        for (int i = to - 1; i >= from && Character.isWhitespace(chars[i]); i--) {
            sum += charAdvance[i];
        }
        return sum;
    }

    /** Lays out line {@code chars[start, end)} of paragraph {@code p}, left-aligned at x 0. */
    private void line(int start, int end, int p) {
        if (lineCount == lineStart.length) {
            growLines();
        }
        int l = lineCount++;
        lineClean[l] = clean;
        clean = false;
        Arrays.fill(charX0, start, end, Float.NaN);
        Arrays.fill(charX1, start, end, Float.NaN);
        int visible = end;
        while (visible > start && Character.isWhitespace(chars[visible - 1])) {
            visible--;
        }
        boolean rtl = paraRtl[p];
        lineStart[l] = start;
        lineEnd[l] = end;
        lineRtl[l] = rtl;
        lineGlyphStart[l] = glyphCount;

        int pieces = 0;
        int first = paraRunStart[p];
        for (int lo = first, hi = paraRunEnd[p] - 1; lo <= hi; ) { // the first run ending after the line's start
            int mid = (lo + hi) >>> 1;
            if (runEnd[mid] <= start) {
                lo = mid + 1;
                first = lo;
            } else {
                hi = mid - 1;
            }
        }
        for (int r = first; r < paraRunEnd[p] && runStart[r] < visible; r++) {
            int s = Math.max(runStart[r], start);
            int e = Math.min(runEnd[r], visible);
            if (s < e) {
                pieceRun[pieces] = r;
                pieceStart[pieces] = s;
                pieceEnd[pieces] = e;
                pieceOrder[pieces] = pieces;
                pieces++;
            }
        }
        reorder(pieces);
        lineMetrics(l, pieces, start);

        float pen = 0;
        for (int k = 0; k < pieces; k++) {
            int piece = pieceOrder[k];
            int r = pieceRun[piece];
            int s = pieceStart[piece];
            int e = pieceEnd[piece];
            glyphRange(r, s, e);
            for (int g = rangeLo; g < rangeHi; g++) {
                int c = shapedGlyphs.cluster(g);
                if (c < s || c >= e) {
                    continue;
                }
                float advance = shapedGlyphs.advance(g);
                if (Float.isNaN(charX0[c])) {
                    charX0[c] = pen;
                    charX1[c] = pen + advance;
                } else {
                    charX0[c] = Math.min(charX0[c], pen);
                    charX1[c] = Math.max(charX1[c], pen + advance);
                }
                if (!Character.isWhitespace(chars[c])) {
                    addGlyph(shapedGlyphs.glyph(g), runFace[r], pen + shapedGlyphs.offsetX(g),
                        -shapedGlyphs.offsetY(g), c, runSize[r], runs == null ? -1 : charStyle[c]);
                }
                pen += advance;
            }
        }
        lineWidth[l] = pen;
        lineGlyphEnd[l] = glyphCount;
        width = Math.max(width, pen);
        carets(start, visible, end, rtl, pen);
    }

    /**
     * Run {@code r}'s glyphs that may belong to characters {@code [s, e)}, into {@link #rangeLo} and {@link #rangeHi}:
     * found by binary search when the run's clusters are in visual order, else all of them.
     */
    private void glyphRange(int r, int s, int e) {
        int gs = runGlyphStart[r];
        int ge = runGlyphEnd[r];
        rangeLo = gs;
        rangeHi = ge;
        if (runMono[r] && ge - gs > 16) {
            boolean rtl = (runLevel[r] & 1) != 0;
            rangeLo = clusterSplit(gs, ge, rtl ? e : s, rtl);
            rangeHi = clusterSplit(gs, ge, rtl ? s : e, rtl);
        }
    }

    /** In glyphs {@code [lo, hi)} in visual order, the first of a cluster at or after {@code c} (mirrored: before). */
    private int clusterSplit(int lo, int hi, int c, boolean rtl) {
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int cl = shapedGlyphs.cluster(mid);
            if (rtl ? cl >= c : cl < c) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Line {@code l}'s top, box height and baseline from the runs of its {@code pieces} (rich text: the tallest run's
     * box above and below the baseline; an empty line the style at its start), or the block's uniform line height.
     */
    private void lineMetrics(int l, int pieces, int start) {
        if (runs == null) {
            lineTop[l] = l * lineHeightPx;
            lineBox[l] = lineHeightPx;
            lineBaseline[l] = Math.round(l * lineHeightPx + baselineOffset);
            return;
        }
        float above = 0;
        float below = 0;
        for (int k = 0; k < pieces; k++) {
            int st = runStyle[pieceRun[k]];
            above = Math.max(above, styleAbove[st]);
            below = Math.max(below, styleBelow[st]);
        }
        if (pieces == 0) {
            int st = length == 0 ? 0 : charStyle[Math.min(start, length - 1)];
            above = styleAbove[st];
            below = styleBelow[st];
        }
        float top = l == 0 ? 0 : lineTop[l - 1] + lineBox[l - 1];
        lineTop[l] = top;
        lineBox[l] = above + below;
        lineBaseline[l] = Math.round(top + above);
    }

    /** Splits each cluster's extent among its characters and puts characters without a glyph at the line's end. */
    private void carets(int start, int visible, int end, boolean rtl, float lineEndX) {
        for (int i = start; i < visible; i++) {
            if (Float.isNaN(charX0[i])) {
                continue;
            }
            int k = i + 1;
            while (k < visible && Float.isNaN(charX0[k])) {
                k++;
            }
            int n = k - i;
            if (n > 1) { // a ligature or a multi-character cluster: equal shares, in the characters' direction
                float x0 = charX0[i];
                float w = (charX1[i] - x0) / n;
                boolean charRtl = (levels[i] & 1) != 0;
                for (int j = 0; j < n; j++) {
                    int slot = charRtl ? n - 1 - j : j;
                    charX0[i + j] = x0 + slot * w;
                    charX1[i + j] = x0 + (slot + 1) * w;
                }
            }
            i = k - 1;
        }
        float rest = rtl ? 0 : lineEndX;
        for (int i = start; i < end; i++) {
            if (Float.isNaN(charX0[i])) {
                charX0[i] = rest;
                charX1[i] = rest;
            }
        }
    }

    /** Puts the line's pieces in visual order: UAX #9 rule L2 over their levels. */
    private void reorder(int pieces) {
        int max = 0;
        int min = Integer.MAX_VALUE;
        for (int k = 0; k < pieces; k++) {
            int level = runLevel[pieceRun[k]];
            max = Math.max(max, level);
            min = Math.min(min, level);
        }
        int lowestOdd = (min & 1) != 0 ? min : min + 1;
        for (int level = max; level >= lowestOdd; level--) {
            for (int i = 0; i < pieces; ) {
                if (runLevel[pieceRun[pieceOrder[i]]] >= level) {
                    int j = i;
                    while (j < pieces && runLevel[pieceRun[pieceOrder[j]]] >= level) {
                        j++;
                    }
                    for (int a = i, b = j - 1; a < b; a++, b--) {
                        int t = pieceOrder[a];
                        pieceOrder[a] = pieceOrder[b];
                        pieceOrder[b] = t;
                    }
                    i = j;
                } else {
                    i++;
                }
            }
        }
    }

    private void addGlyph(int glyph, FontFace face, float x, float dy, int cluster, float glyphPx, int style) {
        if (glyphCount == glyphIds.length) {
            growGlyphs();
        }
        glyphIds[glyphCount] = glyph;
        glyphFaces[glyphCount] = face;
        glyphX[glyphCount] = x;
        glyphDy[glyphCount] = dy;
        glyphCluster[glyphCount] = cluster;
        glyphSize[glyphCount] = glyphPx;
        glyphStyle[glyphCount] = style;
        glyphCount++;
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Records the visible glyphs into {@code list}'s open range (between its {@code begin} and {@code end}), the
     * block's top left at {@code (x, y)} (y is rounded to a whole pixel, so baselines stay on pixel rows).
     * {@code color} and {@code outlineColor} are {@link com.truesim.ui.core.draw.Rgba} colours, {@code outlineWidth}
     * pixels of outline (0 for none), {@code weight} pixels the edge moves out (bolder) or in. A glyph still generating
     * is recorded as a zero-sized quad, so the primitive count does not change when it arrives. Small text without
     * outline and weight draws hinted raster glyphs where the atlas's settings say so, each on whole pixels.
     * Allocation-free.
     */
    public void record(DrawList list, float x, float y, long color, float outlineWidth, long outlineColor,
        float weight) {
        record(list, x, y, color, outlineWidth, outlineColor, weight, 1, 0, lineCount);
    }

    /**
     * Records lines {@code firstLine} up to {@code endLine} (exclusive) without outline or weight: the visible lines of
     * scrolled text. Runs with a colour of their own draw in it faded by {@code opacity}; the rest in {@code color}.
     * Allocation-free.
     */
    public void recordLines(DrawList list, float x, float y, long color, float opacity, int firstLine, int endLine) {
        record(list, x, y, color, 0, 0, 0, opacity, firstLine, endLine);
    }

    private void record(DrawList list, float x, float y, long color, float outlineWidth, long outlineColor,
        float weight, float opacity, int firstLine, int endLine) {
        GlyphAtlas atlas = system.atlas();
        if (atlas == null) {
            throw new IllegalStateException("this text system has no atlas: it measures only");
        }
        boolean raster = rasterMode(outlineWidth, weight);
        lastOutline = outlineWidth;
        lastWeight = weight;
        if (atlasEpoch != atlas.generation() || raster != entriesRaster || entriesWorld != worldSpace
            || entriesTechnique != atlas.settings().techniqueVersion()) {
            lookUpGlyphs(raster);
        }
        float top = (float) Math.floor(y + 0.5f);
        int waiting = 0;
        int fl = Math.max(0, firstLine);
        int el = Math.min(lineCount, endLine);
        for (int l = fl; l < el; l++) {
            waiting += recordLine(list, x, top, l, color, outlineWidth, outlineColor, weight, opacity, atlas, raster);
        }
        pending = waiting;
        seenCompletions = atlas.completions();
    }

    /** Records line {@code l}'s glyphs; returns how many are still generating. */
    private int recordLine(DrawList list, float x, float top, int l, long color, float outlineWidth,
        long outlineColor, float weight, float opacity, GlyphAtlas atlas, boolean raster) {
        int waiting = 0;
        float left = lineLeft[l];
        float baseline = lineBaseline[l];
        for (int g = lineGlyphStart[l]; g < lineGlyphEnd[l]; g++) {
            if (glyphIds[g] < 0) {
                continue; // an inline object: its owner draws it
            }
            float px = x + (glyphX[g] + left);
            float py = top + (baseline + glyphDy[g]);
            float gs = glyphSize[g];
            long c = color;
            if (runs != null && glyphStyle[g] >= 0 && runs.hasColor(glyphStyle[g])) {
                c = Rgba.fade(runs.color(glyphStyle[g]), opacity);
            }
            FontFace face = glyphFaces[g];
            int e;
            float qx = 0;
            boolean rasterHere = rasterGlyph(atlas, g, raster);
            if (rasterHere) {
                // the pen to a quarter pixel: a whole pixel and the phase the bitmap was rendered at
                int quarters = (int) Math.floor(px * GlyphAtlas.RASTER_PHASES + 0.5f);
                int whole = Math.floorDiv(quarters, GlyphAtlas.RASTER_PHASES);
                qx = whole;
                e = atlas.rasterEntry(face, glyphIds[g], gs, quarters - whole * GlyphAtlas.RASTER_PHASES);
            } else {
                e = glyphEntries[g];
            }
            byte state = atlas.state(e);
            if (state == GlyphAtlas.READY && rasterHere) {
                // the bitmap's planes are whole pixels from the pen's whole pixel: texel for pixel
                float qy = (float) Math.floor(py + 0.5f);
                list.rasterGlyph(qx + atlas.plane(e, 0), qy - atlas.plane(e, 3), qx + atlas.plane(e, 2),
                    qy - atlas.plane(e, 1), atlas.uv(e, 0), atlas.uv(e, 1), atlas.uv(e, 2), atlas.uv(e, 3),
                    atlas.texture(e), c);
            } else if (state == GlyphAtlas.READY && atlas.color(e)) {
                list.colorGlyph(px + atlas.plane(e, 0) * gs, py - atlas.plane(e, 3) * gs,
                    px + atlas.plane(e, 2) * gs, py - atlas.plane(e, 1) * gs,
                    atlas.uv(e, 0), atlas.uv(e, 1), atlas.uv(e, 2), atlas.uv(e, 3), atlas.texture(e),
                    Rgba.pack(1, 1, 1, Rgba.a(c)));
            } else if (state == GlyphAtlas.READY) {
                list.glyph(px + atlas.plane(e, 0) * gs, py - atlas.plane(e, 3) * gs,
                    px + atlas.plane(e, 2) * gs, py - atlas.plane(e, 1) * gs,
                    atlas.uv(e, 0), atlas.uv(e, 1), atlas.uv(e, 2), atlas.uv(e, 3), atlas.texture(e),
                    atlas.pixelRange() * gs / atlas.emSize(), c, outlineWidth, outlineColor, weight);
            } else if (state != GlyphAtlas.EMPTY) {
                list.glyph(px, py, px, py, 0, 0, 0, 0, atlas.page(0).id(), atlas.pixelRange() * gs / atlas.emSize(), c,
                    outlineWidth, outlineColor, weight);
                waiting++;
            }
        }
        return waiting;
    }

    /**
     * Grows whenever what {@link #record} writes changes: the layout changed, or glyphs it drew as placeholders may
     * have become ready since. An element records again when this differs from the value it last recorded at.
     */
    public long version() {
        GlyphAtlas atlas = system.atlas();
        if (atlas != null) {
            long technique = atlas.settings().techniqueVersion();
            if (technique != seenTechnique) {
                seenTechnique = technique;
                version++; // raster or MTSDF may have changed: record again
            }
            long completions = atlas.completions();
            if (atlas.generation() != versionEpoch || pending > 0 && completions != seenCompletions) {
                version++;
                versionEpoch = atlas.generation();
                seenCompletions = completions;
            }
        }
        return version;
    }

    /**
     * Marks the block as drawn in world space (a 3D or VR panel: MTSDF at every size, sharp at any distance and
     * angle) or on screen (the default: small text may draw hinted raster glyphs). Changes no layout.
     */
    public void worldSpace(boolean on) {
        if (on != worldSpace) {
            worldSpace = on;
            version++;
        }
    }

    /** The text system it belongs to. */
    public TextSystem system() {
        return system;
    }

    /** Whether the block is drawn in world space. */
    public boolean worldSpace() {
        return worldSpace;
    }

    /** Whether the last {@link #record} drew hinted raster glyphs (at the block's size). */
    public boolean drewRaster() {
        GlyphAtlas atlas = system.atlas();
        return entriesRaster && atlas != null && atlas.rasterFor(size, worldSpace);
    }

    // ---------------------------------------------------------------- measures

    /** The widest line, pixels: the width the layout engine measures. */
    public float width() {
        return width;
    }

    /** All lines, pixels. */
    public float height() {
        return height;
    }

    /** The first line's baseline below the top, pixels (for baseline alignment). */
    public float baseline() {
        return lineCount > 0 ? lineBaseline[0] : 0;
    }

    /** The distance between baselines, pixels. */
    public float lineHeightPx() {
        return lineHeightPx;
    }

    /** At most how many primitives {@link #record} writes: the visible glyphs. */
    public int glyphCount() {
        return glyphCount;
    }

    /** Lines. */
    public int lineCount() {
        return lineCount;
    }

    /** The first character of line {@code l}. */
    public int lineStart(int l) {
        return lineStart[l];
    }

    /** The character after line {@code l} (its break or its paragraph's end). */
    public int lineEnd(int l) {
        return lineEnd[l];
    }

    /** Line {@code l}'s baseline below the top. */
    public float lineBaseline(int l) {
        return lineBaseline[l];
    }

    /** Line {@code l}'s top below the block's top, pixels. */
    public float lineTop(int l) {
        return lineTop[l];
    }

    /** Line {@code l}'s box height, pixels (the block's line height, or its tallest run's for rich text). */
    public float lineHeight(int l) {
        return lineBox[l];
    }

    /** The line at {@code y} pixels below the top (clamped to the first and last line); 0 without lines. */
    public int lineAt(float y) {
        int lo = 0;
        int hi = lineCount - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTop[mid] <= y) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The first visible glyph of line {@code l}. */
    public int lineGlyphStart(int l) {
        return lineGlyphStart[l];
    }

    /** The visible glyph after line {@code l}'s last. */
    public int lineGlyphEnd(int l) {
        return lineGlyphEnd[l];
    }

    /** Line {@code l}'s left edge after alignment. */
    public float lineLeft(int l) {
        return lineLeft[l];
    }

    /** Line {@code l}'s width without trailing white space. */
    public float lineWidth(int l) {
        return lineWidth[l];
    }

    /** Whether line {@code l}'s paragraph is right to left. */
    public boolean lineRightToLeft(int l) {
        return lineRtl[l];
    }

    /** Visible glyph {@code g}'s id in its face (visual order, line by line). */
    public int glyphId(int g) {
        return glyphIds[g];
    }

    /** Visible glyph {@code g}'s face. */
    public FontFace glyphFace(int g) {
        return glyphFaces[g];
    }

    /** Visible glyph {@code g}'s pen position, pixels from the left. */
    public float glyphX(int g) {
        return glyphX[g] + lineLeft[glyphLine(g)];
    }

    /** Visible glyph {@code g}'s baseline, pixels from the top (offset by its shaping offset). */
    public float glyphY(int g) {
        return lineBaseline[glyphLine(g)] + glyphDy[g];
    }

    /** The line visible glyph {@code g} is on. */
    private int glyphLine(int g) {
        int found = 0;
        for (int lo = 0, hi = lineCount - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (lineGlyphStart[mid] <= g) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The first character visible glyph {@code g} came from. */
    public int glyphCluster(int g) {
        return glyphCluster[g];
    }

    /** Visible glyph {@code g}'s size, pixels per em (its run's, for rich text). */
    public float glyphSize(int g) {
        return glyphSize[g];
    }

    /** Whether character {@code index} is laid out right to left (its BiDi level is odd). */
    public boolean rightToLeft(int index) {
        return (levels[index] & 1) != 0;
    }

    /** Character {@code index}'s left edge, pixels (its line's end for characters without a glyph). */
    public float charLeft(int index) {
        return charX0[index] + lineLeft[caretLine(index)];
    }

    /** Character {@code index}'s right edge, pixels. */
    public float charRight(int index) {
        return charX1[index] + lineLeft[caretLine(index)];
    }

    /** The style runs the block was set with, or null. */
    public TextRuns runs() {
        return runs;
    }

    /** Characters. */
    public int length() {
        return length;
    }

    /** The font size, pixels per em. */
    public float size() {
        return size;
    }

    // ---------------------------------------------------------------- carets

    /** The line caret position {@code index} (0 to {@link #length()}) is on. */
    public int caretLine(int index) {
        checkIndex(index);
        int found = 0;
        for (int lo = 1, hi = lineCount - 1; lo <= hi; ) {
            int mid = (lo + hi) >>> 1;
            if (lineStart[mid] <= index) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /**
     * The x of the caret before character {@code index} (after the last at {@link #length()}): the leading edge of
     * that character in its direction, or at a line's end, the trailing edge of the character before.
     */
    public float caretX(int index) {
        int l = caretLine(index);
        if (index < lineEnd[l]) {
            return ((levels[index] & 1) != 0 ? charX1[index] : charX0[index]) + lineLeft[l];
        }
        if (index == lineStart[l]) { // an empty line
            return lineLeft[l];
        }
        int c = index - 1;
        return ((levels[c] & 1) != 0 ? charX0[c] : charX1[c]) + lineLeft[l];
    }

    /**
     * The caret position closest to the point {@code (x, y)}: a character index from 0 to {@link #length()}, at a
     * grapheme cluster boundary.
     */
    public int hitTest(float x, float y) {
        if (lineCount == 0) {
            return 0;
        }
        int l = lineAt(y);
        int start = lineStart[l];
        int end = lineEnd[l];
        int best = lineRtl[l] ? end : start;
        float bestDistance = Float.MAX_VALUE;
        float left = lineLeft[l];
        for (int i = start; i < end; i++) {
            float x0 = charX0[i] + left;
            float x1 = charX1[i] + left;
            if (x1 <= x0) {
                continue; // hidden: trailing space, a mark
            }
            boolean rtl = (levels[i] & 1) != 0;
            if (x >= x0 && x < x1) {
                boolean leftHalf = x < (x0 + x1) * 0.5f;
                best = leftHalf != rtl ? i : i + 1;
                bestDistance = 0;
                break;
            }
            float distance = x < x0 ? x0 - x : x - x1;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (x < x0) != rtl ? i : i + 1;
            }
        }
        return graphemeStart(Math.min(best, end), start, end);
    }

    /**
     * The caret position one step left ({@code right} false) or right of {@code index} on screen: through the line's
     * grapheme boundaries in visual order, so in mixed-direction text the caret moves the way the arrow points; past a
     * line's visual end it goes to the next line in reading order (right in a left-to-right paragraph, left in a
     * right-to-left one) at that line's start, or back to the previous line's end. Positions with the same x (the two sides of a direction change)
     * count once. Allocation-free once its scratch is large enough.
     */
    public int moveVisually(int index, boolean right) {
        checkIndex(index);
        if (lineCount == 0) {
            return index;
        }
        int l = caretLine(index);
        int n = lineStops(l);
        float x = caretX(index);
        int best = -1;
        float bestX = 0;
        for (int k = 0; k < n; k++) {
            float sx = stopX[k];
            boolean beyond = right ? sx > x + 0.01f : sx < x - 0.01f;
            if (beyond && (best < 0 || (right ? sx < bestX : sx > bestX))) {
                best = stops[k];
                bestX = sx;
            }
        }
        if (best >= 0) {
            return best;
        }
        boolean forward = right != lineRtl[l]; // reading order: off the line's end goes on to the next line
        int next = forward ? l + 1 : l - 1;
        if (next < 0 || next >= lineCount) {
            return index;
        }
        int m = lineStops(next);
        // the next line in reading order is entered at its reading start, the previous one at its reading end
        return forward ? stops[0] : stops[m - 1];
    }

    /** Line {@code l}'s caret positions (grapheme boundaries, the line's start and end) with their x into the scratch. */
    private int lineStops(int l) {
        int start = lineStart[l];
        int end = lineEnd[l];
        int n = 0;
        BreakIterator graphemes = start < end ? system.characterBreaks(chars, start, end) : null;
        for (int b = start; b != BreakIterator.DONE && b <= end; b = graphemes == null ? BreakIterator.DONE
            : graphemes.following(b)) {
            if (b == end && l + 1 < lineCount && lineStart[l + 1] == end) {
                break; // a soft break: that position is the next line's start
            }
            if (n == stops.length) {
                growStops();
            }
            stops[n] = b;
            stopX[n] = caretX(b);
            n++;
            if (b == end) {
                break;
            }
        }
        return n;
    }

    @AllowAllocation("growing, rarely")
    private void growStops() {
        stops = Arrays.copyOf(stops, stops.length * 2);
        stopX = Arrays.copyOf(stopX, stopX.length * 2);
    }

    /**
     * The boxes characters {@code [from, to)} cover, line by line in visual pieces (a range crossing a direction change
     * covers separate pieces): four floats a box into {@code out} ({@code x0, y0, x1, y1}, pixels), returning how many
     * boxes there are; when {@code out} is too small the boxes that fit are written and the count still returned. A
     * range that goes on past a hard line break covers {@code breakWidth} pixels more at that line's end (a selected
     * line feed). Allocation-free once its scratch is large enough.
     */
    public int rangeBoxes(int from, int to, float breakWidth, float[] out) {
        int a = Math.max(0, Math.min(from, to));
        int b = Math.min(length, Math.max(from, to));
        int boxes = 0;
        if (a >= b || lineCount == 0) {
            return 0;
        }
        for (int l = caretLine(a); l < lineCount && lineStart[l] < b; l++) {
            int s = Math.max(a, lineStart[l]);
            int e = Math.min(b, lineEnd[l]);
            int n = 0;
            float left = lineLeft[l];
            for (int i = s; i < e; i++) {
                float x0 = charX0[i] + left;
                float x1 = charX1[i] + left;
                if (!(x1 > x0)) {
                    continue;
                }
                if (n == spanX0.length) {
                    growSpans();
                }
                // insertion by x0: lines are short
                int k = n++;
                while (k > 0 && spanX0[k - 1] > x0) {
                    spanX0[k] = spanX0[k - 1];
                    spanX1[k] = spanX1[k - 1];
                    k--;
                }
                spanX0[k] = x0;
                spanX1[k] = x1;
            }
            boolean hardBreak = lineEnd[l] < b && (l + 1 >= lineCount || lineStart[l + 1] > lineEnd[l]);
            if (hardBreak && breakWidth > 0) {
                float edge = lineRtl[l] ? lineLeft[l] - breakWidth : lineLeft[l] + lineWidth[l];
                if (lineEnd[l] == lineStart[l]) {
                    edge = lineRtl[l] ? lineLeft[l] - breakWidth : lineLeft[l];
                }
                if (n == spanX0.length) {
                    growSpans();
                }
                int k = n++;
                while (k > 0 && spanX0[k - 1] > edge) {
                    spanX0[k] = spanX0[k - 1];
                    spanX1[k] = spanX1[k - 1];
                    k--;
                }
                spanX0[k] = edge;
                spanX1[k] = edge + breakWidth;
            }
            float y0 = lineTop[l];
            float y1 = lineTop[l] + lineBox[l];
            for (int k = 0; k < n; ) {
                float x0 = spanX0[k];
                float x1 = spanX1[k];
                k++;
                while (k < n && spanX0[k] <= x1 + 0.5f) {
                    x1 = Math.max(x1, spanX1[k]);
                    k++;
                }
                if (boxes * 4 + 3 < out.length) {
                    out[boxes * 4] = x0;
                    out[boxes * 4 + 1] = y0;
                    out[boxes * 4 + 2] = x1;
                    out[boxes * 4 + 3] = y1;
                }
                boxes++;
            }
        }
        return boxes;
    }

    @AllowAllocation("growing, rarely")
    private void growSpans() {
        spanX0 = Arrays.copyOf(spanX0, spanX0.length * 2);
        spanX1 = Arrays.copyOf(spanX1, spanX1.length * 2);
    }

    private int graphemeStart(int index, int start, int end) {
        if (index <= start || index >= end) {
            return index;
        }
        char before = chars[index - 1];
        char after = chars[index];
        if (before < 0x300 && after < 0x300 && (before != '\r' || after != '\n')) {
            // no grapheme rule joins two code points below the combining marks but CR LF (UAX #29): a boundary, found
            // without the JDK's break iterator, whose setText allocates (a pointer dragging over text hit-tests every
            // frame)
            return index;
        }
        BreakIterator graphemes = system.characterBreaks(chars, start, end);
        return graphemes.isBoundary(index) ? index : graphemes.preceding(index);
    }

    private void checkIndex(int index) {
        if (index < 0 || index > length) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    // ---------------------------------------------------------------- growing

    @AllowAllocation("growing, rarely")
    private void growParagraphs() {
        int n = paraStart.length * 2;
        paraStart = Arrays.copyOf(paraStart, n);
        paraEnd = Arrays.copyOf(paraEnd, n);
        paraRtl = Arrays.copyOf(paraRtl, n);
        paraBidi = Arrays.copyOf(paraBidi, n);
        paraComplex = Arrays.copyOf(paraComplex, n);
        paraLine = Arrays.copyOf(paraLine, n);
        paraRunStart = Arrays.copyOf(paraRunStart, n);
        paraRunEnd = Arrays.copyOf(paraRunEnd, n);
    }

    @AllowAllocation("growing, rarely")
    private void growRuns() {
        int n = runStart.length * 2;
        runStart = Arrays.copyOf(runStart, n);
        runEnd = Arrays.copyOf(runEnd, n);
        runGlyphStart = Arrays.copyOf(runGlyphStart, n);
        runGlyphEnd = Arrays.copyOf(runGlyphEnd, n);
        runLevel = Arrays.copyOf(runLevel, n);
        runFace = Arrays.copyOf(runFace, n);
        runSize = Arrays.copyOf(runSize, n);
        runStyle = Arrays.copyOf(runStyle, n);
        runMono = Arrays.copyOf(runMono, n);
        pieceRun = new int[n];
        pieceStart = new int[n];
        pieceEnd = new int[n];
        pieceOrder = new int[n];
    }

    @AllowAllocation("growing, rarely")
    private void growLines() {
        int n = lineStart.length * 2;
        lineStart = Arrays.copyOf(lineStart, n);
        lineEnd = Arrays.copyOf(lineEnd, n);
        lineGlyphStart = Arrays.copyOf(lineGlyphStart, n);
        lineGlyphEnd = Arrays.copyOf(lineGlyphEnd, n);
        lineLeft = Arrays.copyOf(lineLeft, n);
        lineWidth = Arrays.copyOf(lineWidth, n);
        lineBaseline = Arrays.copyOf(lineBaseline, n);
        lineTop = Arrays.copyOf(lineTop, n);
        lineBox = Arrays.copyOf(lineBox, n);
        lineRtl = Arrays.copyOf(lineRtl, n);
        lineClean = Arrays.copyOf(lineClean, n);
    }

    @AllowAllocation("growing, rarely")
    private void growGlyphs() {
        int n = glyphIds.length * 2;
        glyphIds = Arrays.copyOf(glyphIds, n);
        glyphFaces = Arrays.copyOf(glyphFaces, n);
        glyphX = Arrays.copyOf(glyphX, n);
        glyphDy = Arrays.copyOf(glyphDy, n);
        glyphEntries = Arrays.copyOf(glyphEntries, n);
        glyphCluster = Arrays.copyOf(glyphCluster, n);
        glyphSize = Arrays.copyOf(glyphSize, n);
        glyphStyle = Arrays.copyOf(glyphStyle, n);
    }
}
