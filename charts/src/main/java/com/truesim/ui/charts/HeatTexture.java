package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;

/**
 * A grid of values as a texture (one of the UI's {@code UiTextures}) coloured by the chart's colormap: what heatmaps
 * and 2D histograms draw with one image primitive. Each cell takes {@code ui.chart.heatmapTexels} texels a side, so
 * cells stay sharp under the renderer's linear filtering. Recoloured when the values, their scale or the colormap
 * change; a grid of another size makes a new texture (the old one stays in the UI's textures, unused).
 */
final class HeatTexture {

    private UiTexture texture;
    private int rows;
    private int columns;
    private int texels;
    private double[] values = new double[0];
    private double min;
    private double max;

    /** The values, row-major, the first row at the top: fill {@link #values()} after {@link #size}. */
    @AllowAllocation("the grid grows when its size changes")
    void size(int newRows, int newColumns) {
        rows = Math.max(0, newRows);
        columns = Math.max(0, newColumns);
        if (values.length < rows * columns) {
            values = new double[rows * columns];
        }
    }

    double[] values() {
        return values;
    }

    int rows() {
        return rows;
    }

    int columns() {
        return columns;
    }

    /** The scale's low end of the last colouring. */
    double min() {
        return min;
    }

    /** The scale's high end of the last colouring. */
    double max() {
        return max;
    }

    /** Colours the values from {@code low} to {@code high} (NaN: the values' own extent) into the texture. */
    void color(Chart chart, double low, double high, float opacity) {
        if (rows == 0 || columns == 0) {
            return;
        }
        double lo = low;
        double hi = high;
        if (!(lo == lo && hi == hi && hi > lo)) {
            lo = Double.POSITIVE_INFINITY;
            hi = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < rows * columns; i++) {
                double v = values[i];
                if (v - v == 0) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
            if (!(hi > lo)) {
                hi = lo + 1;
            }
            if (lo != lo || Double.isInfinite(lo)) {
                lo = 0;
                hi = 1;
            }
        }
        min = lo;
        max = hi;
        int k = (int) Math.max(1, Math.min(ChartTunables.HEATMAP_TEXELS.get(),
            Math.floor(4096.0 / Math.max(rows, columns))));
        ensure(chart, k);
        byte[] px = texture.pixels();
        int w = columns * k;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                double v = values[r * columns + c];
                long rgba = v == v ? chart.colormap((v - lo) / (hi - lo)) : Rgba.TRANSPARENT;
                byte br = (byte) Math.round(Rgba.toSrgb(Rgba.r(rgba)) * 255);
                byte bg = (byte) Math.round(Rgba.toSrgb(Rgba.g(rgba)) * 255);
                byte bb = (byte) Math.round(Rgba.toSrgb(Rgba.b(rgba)) * 255);
                byte ba = (byte) Math.round(Math.max(0, Math.min(1, Rgba.a(rgba) * opacity)) * 255);
                for (int ty = r * k; ty < (r + 1) * k; ty++) {
                    int o = (ty * w + c * k) * 4;
                    for (int tx = 0; tx < k; tx++, o += 4) {
                        px[o] = br;
                        px[o + 1] = bg;
                        px[o + 2] = bb;
                        px[o + 3] = ba;
                    }
                }
            }
        }
        texture.markDirty(0, 0, texture.width(), texture.height());
    }

    @AllowAllocation("a texture is made when the grid's size changes")
    private void ensure(Chart chart, int k) {
        if (texture == null || k != texels || texture.width() != columns * k || texture.height() != rows * k) {
            texels = k;
            texture = chart.textures().create("chart-heatmap", columns * k, rows * k, UiTexture.Format.RGBA8_SRGB);
        }
    }

    /** The texture (null before the first colouring). */
    UiTexture texture() {
        return texture;
    }
}
