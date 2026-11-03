package com.truesim.ui.text;

/**
 * Where a glyph's outline lands in its MTSDF bitmap: one definition shared by every {@link MsdfGenerator}, the atlas
 * and the cache, so the same outline gets the same bitmap size and placement at build time and at run time.
 *
 * <p>A texel {@code (x, y)} (y up, row 0 at the bottom) samples the outline at
 * {@code ((x + 0.5) / scale - translateX, (y + 0.5) / scale - translateY)} ems. Distances are mapped so that
 * {@code value = 0.5 + distance / range} (positive inside), clamped to 0 to 1: {@code range} ems is
 * {@code pixelRange} texels. The outline's bounds are padded by half the range and half a texel, so the bitmap's
 * border is outside the glyph and a quad drawn over the whole bitmap shows the whole glyph.
 *
 * @param width texels
 * @param height texels
 * @param scale texels per em
 * @param translateX ems added to x before scaling
 * @param translateY ems added to y before scaling
 * @param range the distance range in ems (the atlas pixel range over {@code scale})
 */
public record MsdfGeometry(int width, int height, double scale, double translateX, double translateY, double range) {

    /**
     * The bitmap for an outline with {@code bounds} (left, bottom, right, top, ems) at {@code emSize} texels per em and
     * a distance range of {@code pixelRange} texels.
     */
    public static MsdfGeometry fit(float[] bounds, float emSize, float pixelRange) {
        double scale = emSize;
        double pad = pixelRange * 0.5 + 0.5;
        double x0 = Math.floor(bounds[0] * scale - pad);
        double y0 = Math.floor(bounds[1] * scale - pad);
        double x1 = Math.ceil(bounds[2] * scale + pad);
        double y1 = Math.ceil(bounds[3] * scale + pad);
        return new MsdfGeometry((int) (x1 - x0), (int) (y1 - y0), scale, -x0 / scale, -y0 / scale,
            pixelRange / scale);
    }

    /** The bitmap's left edge in ems right of the glyph origin. */
    public float planeLeft() {
        return (float) -translateX;
    }

    /** The bitmap's bottom edge in ems above the baseline. */
    public float planeBottom() {
        return (float) -translateY;
    }

    /** The bitmap's right edge in ems. */
    public float planeRight() {
        return (float) (width / scale - translateX);
    }

    /** The bitmap's top edge in ems. */
    public float planeTop() {
        return (float) (height / scale - translateY);
    }
}
