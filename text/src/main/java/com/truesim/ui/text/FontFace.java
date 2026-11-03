package com.truesim.ui.text;

/**
 * An opened font face: coverage, metrics and outlines. Shaping goes through a {@link TextShaper}, which knows the face
 * by its backend's type. Metrics and outlines are in ems (1.0 is the font size), y up.
 *
 * <p>Faces are used from the UI thread; {@link #outline} may also be called from other threads (tools, a rasterizer)
 * and is safe to call concurrently.
 */
public interface FontFace {

    /** Its number in the {@link FontRegistry}: unique among the registry's faces, part of the glyph atlas key. */
    int id();

    /** Where it was loaded from. */
    FontSource source();

    /** Height above the baseline the font reserves, in ems, positive. */
    float ascender();

    /** Depth below the baseline the font reserves, in ems, positive. */
    float descender();

    /** Extra space between lines the font asks for, in ems. */
    float lineGap();

    /** Whether the face has a glyph for {@code codepoint}. */
    boolean covers(int codepoint);

    /**
     * Writes glyph {@code glyph}'s outline into {@code out} (reset first) in ems, y up. False if the glyph has no
     * outline (a space, a missing glyph id); {@code out} is then empty.
     */
    boolean outline(int glyph, GlyphOutline out);
}
