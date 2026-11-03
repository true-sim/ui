package com.truesim.ui.text;

/**
 * Turns an outline into a multi-channel signed distance field with the true distance in alpha (MTSDF).
 * Implementations are thread-safe: the {@link GlyphAtlas} calls one generator from its job threads, the build-time
 * baker from its own. The output depends on the inputs only, so build-time and run-time atlases are byte-identical.
 */
public interface MsdfGenerator {

    /**
     * The MTSDF of {@code outline} laid out by {@code geometry}: {@code width * height * 4} bytes, rows top to bottom
     * (the texture's order), RGBA. Red, green and blue hold the multi-channel distance (their median is the edge
     * distance with sharp corners), alpha the true signed distance; all map distance as {@link MsdfGeometry} says,
     * 255 for 1.0, rounded.
     */
    byte[] generate(GlyphOutline outline, MsdfGeometry geometry);
}
