package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * Icons as the glyphs of a face, so the {@link GlyphAtlas} makes their MTSDFs as it makes text's: generated on its
 * lane, packed on its pages, evicted with them, drawn as glyph primitives tinted like text. Glyph {@code i} is the
 * {@code i}-th icon {@link #add added}; its outline is in ems, its view box 1 em high from 0, 0 ({@link SvgIcon}).
 * Not cached on disk (its {@link #source()} is null: its glyph numbers depend on the order icons are added).
 *
 * <p>Icons are added on the UI thread; outlines are read from the atlas's job threads, so added icons never change.
 */
public final class IconFace implements FontFace {

    /** The atlas key's face number of icons: above every font's (the registry counts from 0). */
    public static final int ID = 0x7FFF;

    private final List<SvgIcon> icons = new ArrayList<>();

    /** No icons. */
    @AllowAllocation("one per UI")
    public IconFace() {
    }

    /** Adds {@code icon}: its glyph number. */
    @AllowAllocation("icons are added when loaded")
    public synchronized int add(SvgIcon icon) {
        icons.add(icon);
        return icons.size() - 1;
    }

    /** Icon {@code glyph}. */
    public synchronized SvgIcon icon(int glyph) {
        return icons.get(glyph);
    }

    /** Icons added. */
    public synchronized int count() {
        return icons.size();
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public FontSource source() {
        return null;
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
        return false;
    }

    @Override
    public boolean outline(int glyph, GlyphOutline out) {
        out.reset();
        SvgIcon icon;
        synchronized (this) {
            if (glyph < 0 || glyph >= icons.size()) {
                return false;
            }
            icon = icons.get(glyph);
        }
        GlyphOutline o = icon.outline();
        byte[] verbs = o.verbs();
        float[] p = o.points();
        int pi = 0;
        for (int i = 0; i < o.verbCount(); i++) {
            switch (verbs[i]) {
                case GlyphOutline.MOVE -> {
                    out.moveTo(p[pi], p[pi + 1]);
                    pi += 2;
                }
                case GlyphOutline.LINE -> {
                    out.lineTo(p[pi], p[pi + 1]);
                    pi += 2;
                }
                case GlyphOutline.QUAD -> {
                    out.quadTo(p[pi], p[pi + 1], p[pi + 2], p[pi + 3]);
                    pi += 4;
                }
                default -> {
                    out.cubicTo(p[pi], p[pi + 1], p[pi + 2], p[pi + 3], p[pi + 4], p[pi + 5]);
                    pi += 6;
                }
            }
        }
        return !out.isEmpty();
    }
}
