package com.truesim.ui.text.jdk;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.text.FontFace;
import com.truesim.ui.text.FontSource;
import com.truesim.ui.text.GlyphOutline;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.Map;

/**
 * A font face through {@code java.awt.Font}: coverage by {@link Font#canDisplay(int)}, metrics by {@link LineMetrics},
 * outlines from glyph vectors at a large size (so no hinting shows), scaled to ems.
 */
@AllowAllocation("the JDK fallback for headless tests and tools: java.awt allocates")
public final class JdkFontFace implements FontFace {

    /** The size outlines and metrics are read at: large, so they are unhinted and precise. */
    private static final float OUTLINE_SIZE = 2048;

    private final int id;
    private final FontSource source;
    private final Font font;
    private final Font outlineFont;
    private final FontRenderContext frc = new FontRenderContext(null, true, true);
    private final float ascender;
    private final float descender;
    private final float lineGap;
    private Font shapingFont;
    private float shapingSize;

    /** Opens {@code source} as face {@code id}. */
    public JdkFontFace(FontSource source, int id) throws IOException {
        Font[] fonts;
        try {
            fonts = Font.createFonts(source.path().toFile());
        } catch (FontFormatException e) {
            throw new IOException(source + " is not a font", e);
        }
        if (source.index() >= fonts.length) {
            throw new IOException(source + ": the file has " + fonts.length + " faces");
        }
        this.id = id;
        this.source = source;
        this.font = fonts[source.index()];
        this.outlineFont = font.deriveFont(OUTLINE_SIZE);
        LineMetrics metrics = outlineFont.getLineMetrics("Xg", frc);
        this.ascender = metrics.getAscent() / OUTLINE_SIZE;
        this.descender = metrics.getDescent() / OUTLINE_SIZE;
        this.lineGap = metrics.getLeading() / OUTLINE_SIZE;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public FontSource source() {
        return source;
    }

    @Override
    public float ascender() {
        return ascender;
    }

    @Override
    public float descender() {
        return descender;
    }

    @Override
    public float lineGap() {
        return lineGap;
    }

    @Override
    public boolean covers(int codepoint) {
        return font.canDisplay(codepoint);
    }

    /** The glyph id of {@code codepoint} (its nominal glyph, before shaping), or 0 if not covered. */
    public int glyph(int codepoint) {
        if (!covers(codepoint)) {
            return 0;
        }
        return outlineFont.createGlyphVector(frc, Character.toChars(codepoint)).getGlyphCode(0);
    }

    @Override
    public synchronized boolean outline(int glyph, GlyphOutline out) {
        out.reset();
        if (glyph < 0 || glyph >= font.getNumGlyphs()) {
            return false;
        }
        GlyphVector gv = outlineFont.createGlyphVector(frc, new int[] {glyph});
        Shape shape = gv.getGlyphOutline(0);
        float[] c = new float[6];
        float s = 1 / OUTLINE_SIZE;
        float startX = 0;
        float startY = 0;
        float lastX = 0;
        float lastY = 0;
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    startX = c[0] * s;
                    startY = -c[1] * s;
                    lastX = startX;
                    lastY = startY;
                    out.moveTo(startX, startY);
                }
                case PathIterator.SEG_LINETO -> {
                    lastX = c[0] * s;
                    lastY = -c[1] * s;
                    out.lineTo(lastX, lastY);
                }
                case PathIterator.SEG_QUADTO -> {
                    lastX = c[2] * s;
                    lastY = -c[3] * s;
                    out.quadTo(c[0] * s, -c[1] * s, lastX, lastY);
                }
                case PathIterator.SEG_CUBICTO -> {
                    lastX = c[4] * s;
                    lastY = -c[5] * s;
                    out.cubicTo(c[0] * s, -c[1] * s, c[2] * s, -c[3] * s, lastX, lastY);
                }
                default -> { // close: back to the start, if not there
                    if (lastX != startX || lastY != startY) {
                        out.lineTo(startX, startY);
                    }
                    lastX = startX;
                    lastY = startY;
                }
            }
        }
        return !out.isEmpty();
    }

    /** The font at {@code size} with kerning and ligatures on, for shaping; kept for the last size asked. */
    synchronized Font shapingFont(float size) {
        if (shapingFont == null || shapingSize != size) {
            shapingFont = font.deriveFont(Map.of(TextAttribute.SIZE, size, TextAttribute.KERNING,
                TextAttribute.KERNING_ON, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
            shapingSize = size;
        }
        return shapingFont;
    }

    /** The JDK font, at size 1. */
    public Font font() {
        return font;
    }

    @Override
    public String toString() {
        return "JdkFontFace[" + id + " " + source + "]";
    }
}
