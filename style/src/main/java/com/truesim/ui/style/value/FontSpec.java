package com.truesim.ui.style.value;

import java.util.List;
import java.util.Objects;

/**
 * A font: a fallback chain of family names (the first one installed and covering a character wins), a size in panel
 * units, a weight (100 to 900) and a style. The {@code font} shorthand sets the four separate keys from one.
 */
public record FontSpec(List<String> families, float size, int weight, FontStyle style) {

    public FontSpec {
        families = List.copyOf(families);
        Objects.requireNonNull(style, "style");
    }
}
