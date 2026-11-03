package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;

/**
 * The glyph atlas's tunables (settings, by the project's rule). Changing the em size, pixel range or page size makes
 * the atlas start over at the next {@link GlyphAtlas#beginFrame()}: every glyph is generated again (or found in a
 * cache baked with the same values).
 */
public final class AtlasSettings {

    private final Tunable emSize = Tunable.of("ui.text.atlas.emSize", 40, 16, 128,
        "MTSDF texels per em of an atlas glyph: larger keeps sharper corners at large sizes, costs atlas space");
    private final Tunable pixelRange = Tunable.of("ui.text.atlas.pixelRange", 6, 2, 16,
        "The distance range of atlas glyphs in texels: wider allows thicker outlines and glows, costs precision");
    private final Tunable pageSize = Tunable.of("ui.text.atlas.pageSize", 1024, 256, 4096,
        "Width and height of an atlas page in texels");
    private final Tunable glyphsPerFrame = Tunable.of("ui.text.atlas.glyphsPerFrame", 32, 1, 4096,
        "New glyphs the atlas starts generating per frame; the rest wait (drawn as nothing) for later frames");

    /** The defaults. */
    @AllowAllocation("made once per atlas")
    public AtlasSettings() {
    }

    /** MTSDF texels per em. */
    public Tunable emSize() {
        return emSize;
    }

    /** The distance range in atlas texels. */
    public Tunable pixelRange() {
        return pixelRange;
    }

    /** A page's width and height in texels. */
    public Tunable pageSize() {
        return pageSize;
    }

    /** New glyphs started per frame. */
    public Tunable glyphsPerFrame() {
        return glyphsPerFrame;
    }

    /** Every tunable, for registering them as engine settings. */
    @AllowAllocation("called once, when settings are registered")
    public List<Tunable> all() {
        return List.of(emSize, pixelRange, pageSize, glyphsPerFrame);
    }

    /** Grows when a value that changes the atlas's content changes. */
    long layoutVersion() {
        return emSize.version() + pixelRange.version() + pageSize.version();
    }
}
