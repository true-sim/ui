package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeyHolder;

/**
 * The style keys of the text controls beyond {@link com.truesim.ui.style.Styles}: the squiggle under misspelt words
 * (read from an editor's {@code span.misspelt} part) and the magnification of the touch handles' loupe
 * ({@code text-loupe}). Registered for {@code .tss} as a {@link StyleKeyHolder} service.
 */
@AllowAllocation("keys are declared once")
public final class TextStyles implements StyleKeyHolder {

    /** The squiggle's wavelength in panel units (0: a straight line). */
    public static final StyleKey<Float> SQUIGGLE_WAVELENGTH = StyleKey.number("squiggle-wavelength", 4, 0, 64);
    /** The squiggle's height from trough to crest in panel units. */
    public static final StyleKey<Float> SQUIGGLE_AMPLITUDE = StyleKey.number("squiggle-amplitude", 2, 0, 16);
    /** The squiggle's line thickness in panel units. */
    public static final StyleKey<Float> SQUIGGLE_THICKNESS = StyleKey.number("squiggle-thickness", 1, 0.25f, 8);

    /** How many times the text's size a dragged touch selection handle's loupe ({@code text-loupe}) shows it. */
    public static final StyleKey<Float> LOUPE_ZOOM = StyleKey.number("loupe-zoom", 1.5f, 1, 4);

    /** For the service loader: creating it registers the keys. */
    public TextStyles() {
        // the keys are static
    }
}
