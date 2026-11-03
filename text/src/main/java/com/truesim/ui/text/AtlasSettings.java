package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;

/**
 * The glyph atlas's tunables (settings, by the project's rule). Changing the em size, pixel range or page size makes
 * the atlas start over at the next {@link GlyphAtlas#beginFrame()}: every glyph is generated again (or found in a
 * cache baked with the same values).
 *
 * <p><b>Small text</b> (UI16, both techniques behind a setting): with {@link #smallText()} {@link #HINTED} (the
 * default), screen-space text below {@link #hintedBelow()} pixels per em draws FreeType's hinted coverage glyphs at its
 * exact size; text at or above it, world-space (3D, VR) text, and text with an outline or a weight offset (effects
 * only the distance field has) draw MTSDF. With {@link #MTSDF} every glyph is MTSDF. Layout never depends on it:
 * advances and positions come from shaping in both, so switching does not reflow.
 */
public final class AtlasSettings {

    /** {@link #smallText()}: hinted raster glyphs below {@link #hintedBelow()}. */
    public static final int HINTED = 0;
    /** {@link #smallText()}: MTSDF at every size. */
    public static final int MTSDF = 1;

    private final Tunable emSize = Tunable.of("ui.text.atlas.emSize", 40, 16, 128,
        "MTSDF texels per em of an atlas glyph: larger keeps sharper corners at large sizes, costs atlas space");
    private final Tunable pixelRange = Tunable.of("ui.text.atlas.pixelRange", 6, 2, 16,
        "The distance range of atlas glyphs in texels: wider allows thicker outlines and glows, costs precision");
    private final Tunable pageSize = Tunable.of("ui.text.atlas.pageSize", 1024, 256, 4096,
        "Width and height of an atlas page in texels");
    private final Tunable glyphsPerFrame = Tunable.of("ui.text.atlas.glyphsPerFrame", 32, 1, 4096,
        "New glyphs the atlas starts generating per frame; the rest wait (drawn as nothing) for later frames");
    private final Tunable smallText = Tunable.choice("ui.text.smallText", List.of("HINTED", "MTSDF"), "HINTED",
        "Small screen text: HINTED draws FreeType's hinted raster below ui.text.hintedBelow (crisper), MTSDF draws "
            + "distance fields at every size (softer when small); the layout is the same in both");
    private final Tunable hintedBelow = Tunable.of("ui.text.hintedBelow", 13, 0, 64,
        "With ui.text.smallText HINTED: screen text smaller than this many pixels per em draws hinted raster glyphs");

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

    /** How small text is drawn: {@link #HINTED} or {@link #MTSDF}, as the choice's index. */
    public Tunable smallText() {
        return smallText;
    }

    /** The size in pixels per em below which {@link #HINTED} draws raster glyphs. */
    public Tunable hintedBelow() {
        return hintedBelow;
    }

    /** Whether text of {@code sizePx} pixels per em, in world space or not, draws hinted raster glyphs. */
    public boolean hinted(float sizePx, boolean worldSpace) {
        return smallText.index() == HINTED && !worldSpace && sizePx < hintedBelow.get();
    }

    /** Grows when the choice between raster and MTSDF glyphs may have changed: text records again. */
    public long techniqueVersion() {
        return smallText.version() + hintedBelow.version();
    }

    /** Every tunable, for registering them as engine settings. */
    @AllowAllocation("called once, when settings are registered")
    public List<Tunable> all() {
        return List.of(emSize, pixelRange, pageSize, glyphsPerFrame, smallText, hintedBelow);
    }

    /** Grows when a value that changes the atlas's content changes. */
    long layoutVersion() {
        return emSize.version() + pixelRange.version() + pageSize.version();
    }
}
