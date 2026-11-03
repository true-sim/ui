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
 * Unchanged inputs cost a comparison. {@link #record} writes one {@link DrawList#glyph GLYPH} primitive per visible
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

    // shaping, in logical order
    private FontFace primary;
    private byte[] levels = new byte[32];
    private float[] charAdvance = new float[32];
    private int paraCount;
    private int[] paraStart = new int[4];
    private int[] paraEnd = new int[4];
    private boolean[] paraRtl = new boolean[4];
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
    private final ShapedGlyphs shapedGlyphs = new ShapedGlyphs();
    private int[] charStyle = new int[32];
    private FontChain[] styleChain = new FontChain[8];
    private float[] styleAbove = new float[8]; // a style's line box above and below the baseline, pixels
    private float[] styleBelow = new float[8];

    // layout, in visual order
    private int glyphCount;
    private int[] glyphIds = new int[32];
    private FontFace[] glyphFaces = new FontFace[32];
    private float[] glyphX = new float[32];
    private float[] glyphY = new float[32];
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
    private float[] charX0 = new float[32];
    private float[] charX1 = new float[32];
    private int[] pieceRun = new int[8];
    private int[] pieceStart = new int[8];
    private int[] pieceEnd = new int[8];
    private int[] pieceOrder = new int[8];
    private float width;
    private float height;
    private float lineHeightPx;
    private float baselineOffset;

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
        boolean reshape = !shaped || !sameText(text) || families != this.families && !families.equals(this.families)
            || sizePx != size || weight != this.weight || italic != this.italic || direction != this.direction
            || !Objects.equals(language, this.language) || system.fonts().version() != fontsVersion
            || styleRuns != runs || styleRuns != null && styleRuns.version() != runsVersion;
        boolean relayout = reshape || align != this.align || maxWidth != this.maxWidth
            || lineHeight != this.lineHeight;
        if (!relayout) {
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

    private boolean sameText(CharSequence text) {
        if (text.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (text.charAt(i) != chars[i]) {
                return false;
            }
        }
        return true;
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
        chars = new char[c];
        charStyle = new int[c];
        levels = new byte[c];
        charAdvance = new float[c];
        charX0 = new float[c];
        charX1 = new float[c];
    }

    // ---------------------------------------------------------------- shaping

    private void shape() {
        FontRegistry fonts = system.fonts();
        fontsVersion = fonts.version();
        FontChain chain = fonts.chain(families, weight, italic);
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
        paraRunStart[p] = runCount;
        itemize(start, end, chain);
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
                shaper.shape(chars, length, runStart[r], runEnd[r], runFace[r], runSize[r], (runLevel[r] & 1) != 0,
                    language, shapedGlyphs);
            }
            runGlyphEnd[r] = shapedGlyphs.size();
        }
    }

    /** Resolves the BiDi levels of {@code chars[start, end)}; true if the paragraph is right to left. */
    private boolean levels(int start, int end) {
        if (start == end || direction != TextDirection.RTL && !Bidi.requiresBidi(chars, start, end)) {
            Arrays.fill(levels, start, end, (byte) 0);
            return start == end && direction == TextDirection.RTL;
        }
        Bidi bidi = bidi(start, end);
        for (int r = 0; r < bidi.getRunCount(); r++) {
            Arrays.fill(levels, start + bidi.getRunStart(r), start + bidi.getRunLimit(r), (byte) bidi.getRunLevel(r));
        }
        return (bidi.getBaseLevel() & 1) != 0;
    }

    @AllowAllocation("BiDi analysis of a paragraph with right-to-left text, when its text changes")
    private Bidi bidi(int start, int end) {
        int flags = switch (direction) {
            case AUTO -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT;
            case LTR -> Bidi.DIRECTION_LEFT_TO_RIGHT;
            case RTL -> Bidi.DIRECTION_RIGHT_TO_LEFT;
        };
        return new Bidi(chars, start, null, 0, end - start, flags);
    }

    private void itemize(int start, int end, FontChain chain) {
        int from = start;
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
        for (int p = 0; p < paraCount; p++) {
            breakLines(p);
        }
        height = lineCount == 0 ? 0 : lineTop[lineCount - 1] + lineBox[lineCount - 1];
        if (runs == null) {
            height = lineCount * lineHeightPx;
        }
        float box = maxWidth < Float.POSITIVE_INFINITY ? maxWidth : width;
        for (int l = 0; l < lineCount; l++) {
            float shift = (box - lineWidth[l]) * align.leftShare(lineRtl[l]);
            lineLeft[l] = shift;
            for (int g = lineGlyphStart[l]; g < lineGlyphEnd[l]; g++) {
                glyphX[g] += shift;
            }
            for (int i = lineStart[l]; i < lineEnd[l]; i++) {
                charX0[i] += shift;
                charX1[i] += shift;
            }
        }
        lookUpGlyphs(rasterMode(lastOutline, lastWeight));
    }

    /** Moves the lines to their alignment in the current box; true if any moved. */
    private boolean realign() {
        float box = maxWidth < Float.POSITIVE_INFINITY ? maxWidth : width;
        boolean moved = false;
        for (int l = 0; l < lineCount; l++) {
            float shift = (box - lineWidth[l]) * align.leftShare(lineRtl[l]);
            float delta = shift - lineLeft[l];
            if (delta == 0) {
                continue;
            }
            moved = true;
            lineLeft[l] = shift;
            for (int g = lineGlyphStart[l]; g < lineGlyphEnd[l]; g++) {
                glyphX[g] += delta;
            }
            for (int i = lineStart[l]; i < lineEnd[l]; i++) {
                charX0[i] += delta;
                charX1[i] += delta;
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
        for (int g = 0; g < glyphCount; g++) {
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
        entriesRaster = raster;
        entriesWorld = worldSpace;
        entriesTechnique = atlas.settings().techniqueVersion();
        atlasEpoch = atlas.epoch();
        versionEpoch = atlasEpoch;
        pending = glyphCount; // until recorded: the glyphs may still be generating
    }

    private void breakLines(int p) {
        int start = paraStart[p];
        int end = paraEnd[p];
        // A paragraph that fits needs no break opportunities: greedy breaking would give this one line. It also spares
        // BreakIterator.setText, which allocates, for the common one-line label whose text changes every frame.
        if (!(maxWidth < Float.POSITIVE_INFINITY) || start == end
            || advance(start, end) - trailingSpace(start, end) <= maxWidth) {
            line(start, end, p);
            return;
        }
        BreakIterator breaks = system.lineBreaks(chars, start, end);
        int from = start;
        float used = 0;
        int prev = start;
        for (int b = breaks.next(); b != BreakIterator.DONE; b = breaks.next()) {
            float segment = advance(prev, b);
            float ink = segment - trailingSpace(prev, b);
            if (from < prev && used + ink > maxWidth) {
                line(from, prev, p);
                from = prev;
                used = 0;
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
        for (int r = paraRunStart[p]; r < paraRunEnd[p]; r++) {
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
        float baseline = lineMetrics(l, pieces, start);

        float pen = 0;
        for (int k = 0; k < pieces; k++) {
            int piece = pieceOrder[k];
            int r = pieceRun[piece];
            int s = pieceStart[piece];
            int e = pieceEnd[piece];
            for (int g = runGlyphStart[r]; g < runGlyphEnd[r]; g++) {
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
                        baseline - shapedGlyphs.offsetY(g), c, runSize[r], runStyle[r]);
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
     * Line {@code l}'s top, box height and baseline from the runs of its {@code pieces} (rich text: the tallest run's
     * box above and below the baseline; an empty line the style at its start), or the block's uniform line height.
     */
    private float lineMetrics(int l, int pieces, int start) {
        if (runs == null) {
            lineTop[l] = l * lineHeightPx;
            lineBox[l] = lineHeightPx;
            lineBaseline[l] = Math.round(l * lineHeightPx + baselineOffset);
            return lineBaseline[l];
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
        return lineBaseline[l];
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

    private void addGlyph(int glyph, FontFace face, float x, float y, int cluster, float glyphPx, int style) {
        if (glyphCount == glyphIds.length) {
            growGlyphs();
        }
        glyphIds[glyphCount] = glyph;
        glyphFaces[glyphCount] = face;
        glyphX[glyphCount] = x;
        glyphY[glyphCount] = y;
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
        if (atlasEpoch != atlas.epoch() || raster != entriesRaster || entriesWorld != worldSpace
            || entriesTechnique != atlas.settings().techniqueVersion()) {
            lookUpGlyphs(raster);
        }
        float top = (float) Math.floor(y + 0.5f);
        int waiting = 0;
        int fl = Math.max(0, firstLine);
        int el = Math.min(lineCount, endLine);
        int from = fl < el ? lineGlyphStart[fl] : 0;
        int to = fl < el ? lineGlyphEnd[el - 1] : 0;
        for (int g = from; g < to; g++) {
            if (glyphIds[g] < 0) {
                continue; // an inline object: its owner draws it
            }
            float px = x + glyphX[g];
            float py = top + glyphY[g];
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
        pending = waiting;
        seenCompletions = atlas.completions();
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
            if (atlas.epoch() != versionEpoch || pending > 0 && completions != seenCompletions) {
                version++;
                versionEpoch = atlas.epoch();
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
        return glyphX[g];
    }

    /** Visible glyph {@code g}'s baseline, pixels from the top (offset by its shaping offset). */
    public float glyphY(int g) {
        return glyphY[g];
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
        return charX0[index];
    }

    /** Character {@code index}'s right edge, pixels. */
    public float charRight(int index) {
        return charX1[index];
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
        int l = 0;
        while (l + 1 < lineCount && lineStart[l + 1] <= index) {
            l++;
        }
        return l;
    }

    /**
     * The x of the caret before character {@code index} (after the last at {@link #length()}): the leading edge of
     * that character in its direction, or at a line's end, the trailing edge of the character before.
     */
    public float caretX(int index) {
        int l = caretLine(index);
        if (index < lineEnd[l]) {
            return (levels[index] & 1) != 0 ? charX1[index] : charX0[index];
        }
        if (index == lineStart[l]) { // an empty line
            return lineLeft[l];
        }
        int c = index - 1;
        return (levels[c] & 1) != 0 ? charX0[c] : charX1[c];
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
        for (int i = start; i < end; i++) {
            float x0 = charX0[i];
            float x1 = charX1[i];
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
            for (int i = s; i < e; i++) {
                float x0 = charX0[i];
                float x1 = charX1[i];
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
    }

    @AllowAllocation("growing, rarely")
    private void growGlyphs() {
        int n = glyphIds.length * 2;
        glyphIds = Arrays.copyOf(glyphIds, n);
        glyphFaces = Arrays.copyOf(glyphFaces, n);
        glyphX = Arrays.copyOf(glyphX, n);
        glyphY = Arrays.copyOf(glyphY, n);
        glyphEntries = Arrays.copyOf(glyphEntries, n);
        glyphCluster = Arrays.copyOf(glyphCluster, n);
        glyphSize = Arrays.copyOf(glyphSize, n);
        glyphStyle = Arrays.copyOf(glyphStyle, n);
    }
}
