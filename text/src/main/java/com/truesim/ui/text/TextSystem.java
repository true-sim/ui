package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.Objects;

/**
 * What every {@link TextBlock} of one UI shares: the fonts, the shaper, the glyph atlas, and scratch for itemisation
 * and line breaking. Used from the UI thread.
 *
 * <pre>{@code
 * TextBackend backend = new NativeTextBackend();                 // or new JdkTextBackend()
 * FontRegistry fonts = FontRegistry.system(backend);
 * GlyphAtlas atlas = new GlyphAtlas(uiTextures, backend.msdfGenerator(), scheduler.lane(GLYPHS), cache, settings);
 * TextSystem text = new TextSystem(fonts, backend.newShaper(), atlas);
 * TextBlock label = new TextBlock(text);
 * // each frame: atlas.beginFrame(); label.set(...); label.record(drawList, x, y, color, 0, 0, 0);
 * }</pre>
 */
public final class TextSystem {

    private final FontRegistry fonts;
    private final TextShaper shaper;
    private final GlyphAtlas atlas;
    private final CharArrayIterator lineText = new CharArrayIterator();
    private final CharArrayIterator characterText = new CharArrayIterator();
    private BreakIterator lineBreaks;
    private BreakIterator characterBreaks;

    /**
     * Text over {@code fonts}, shaped by {@code shaper} through a {@link ShapeCache} (runs shown before are not shaped
     * again), drawn from {@code atlas} (null to lay out and measure only: tools, servers).
     */
    @AllowAllocation("made once per UI")
    public TextSystem(FontRegistry fonts, TextShaper shaper, GlyphAtlas atlas) {
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        Objects.requireNonNull(shaper, "shaper");
        this.shaper = shaper instanceof ShapeCache cache ? cache : new ShapeCache(shaper);
        this.atlas = atlas;
    }

    /** The fonts. */
    public FontRegistry fonts() {
        return fonts;
    }

    /** The shaper: a {@link ShapeCache} over the one it was made with. */
    public TextShaper shaper() {
        return shaper;
    }

    /** The atlas, or null if this system only measures. */
    public GlyphAtlas atlas() {
        return atlas;
    }

    /** The line break iterator over {@code chars[begin, end)} (UAX #14 by the JDK). */
    @AllowAllocation("made once; setting text happens on layout, not per frame")
    BreakIterator lineBreaks(char[] chars, int begin, int end) {
        if (lineBreaks == null) {
            lineBreaks = BreakIterator.getLineInstance(Locale.ROOT);
        }
        lineBreaks.setText(lineText.reset(chars, begin, end));
        return lineBreaks;
    }

    /** The grapheme cluster iterator over {@code chars[begin, end)} (extended grapheme clusters, JDK 20+). */
    @AllowAllocation("made once; setting text happens on layout and hit tests, not per frame")
    BreakIterator characterBreaks(char[] chars, int begin, int end) {
        if (characterBreaks == null) {
            characterBreaks = BreakIterator.getCharacterInstance(Locale.ROOT);
        }
        characterBreaks.setText(characterText.reset(chars, begin, end));
        return characterBreaks;
    }
}
