package com.truesim.ui.style.value;

import com.truesim.annotations.AllowStrictMath;
import com.truesim.ui.core.draw.Rgba;

/**
 * Colour functions, the same ones {@code .tss} offers as built-ins ({@code lighten}, {@code mix}, {@code oklch}, ...),
 * so a sheet built in Java and one written in {@code .tss} compute identical colours. They run when a sheet is built or
 * a token changes, never per frame. Lightness and saturation follow Sass: HSL over the sRGB-encoded channels.
 */
public final class Colors {

    private Colors() {
    }

    /** A colour from HSL: hue in degrees, saturation and lightness 0 to 1, alpha 0 to 1. */
    public static Color hsl(double hue, double saturation, double lightness, double alpha) {
        double h = ((hue % 360) + 360) % 360 / 360;
        double s = clamp01(saturation);
        double l = clamp01(lightness);
        if (s == 0) {
            return Color.srgb((float) l, (float) l, (float) l, (float) alpha);
        }
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        return Color.srgb((float) hue(p, q, h + 1.0 / 3), (float) hue(p, q, h), (float) hue(p, q, h - 1.0 / 3),
            (float) alpha);
    }

    private static double hue(double p, double q, double t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1.0 / 6) {
            return p + (q - p) * 6 * t;
        }
        if (t < 0.5) {
            return q;
        }
        if (t < 2.0 / 3) {
            return p + (q - p) * (2.0 / 3 - t) * 6;
        }
        return p;
    }

    /** The colour's HSL: {@code {hue in degrees, saturation, lightness}}. */
    public static double[] toHsl(Color c) {
        double r = clamp01(c.r());
        double g = clamp01(c.g());
        double b = clamp01(c.b());
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double d = max - min;
        if (d == 0) {
            return new double[] {0, 0, l};
        }
        double s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        double h;
        if (max == r) {
            h = (g - b) / d + (g < b ? 6 : 0);
        } else if (max == g) {
            h = (b - r) / d + 2;
        } else {
            h = (r - g) / d + 4;
        }
        return new double[] {h * 60, s, l};
    }

    /** Lightness raised by {@code amount} (0 to 1, absolute, as Sass's {@code lighten}). */
    public static Color lighten(Color c, double amount) {
        double[] hsl = toHsl(c);
        return hsl(hsl[0], hsl[1], hsl[2] + amount, c.a());
    }

    /** Lightness lowered by {@code amount} (0 to 1). */
    public static Color darken(Color c, double amount) {
        return lighten(c, -amount);
    }

    /** Saturation raised by {@code amount} (0 to 1, absolute; negative desaturates). */
    public static Color saturate(Color c, double amount) {
        double[] hsl = toHsl(c);
        return hsl(hsl[0], hsl[1] + amount, hsl[2], c.a());
    }

    /** Saturation lowered by {@code amount}. */
    public static Color desaturate(Color c, double amount) {
        return saturate(c, -amount);
    }

    /**
     * {@code weight} of {@code a} and the rest of {@code b} (0 to 1), as Sass's {@code mix}: the weights account for
     * the alphas, so a transparent colour pulls the mix less.
     */
    public static Color mix(Color a, Color b, double weight) {
        double p = clamp01(weight);
        double w = 2 * p - 1;
        double da = a.a() - b.a();
        double w1 = ((w * da == -1 ? w : (w + da) / (1 + w * da)) + 1) / 2;
        double w2 = 1 - w1;
        return Color.srgb((float) (a.r() * w1 + b.r() * w2), (float) (a.g() * w1 + b.g() * w2),
            (float) (a.b() * w1 + b.b() * w2), (float) (a.a() * p + b.a() * (1 - p)));
    }

    /** The same colour with alpha {@code alpha} (Less's {@code fade}, Sass's {@code rgba(color, alpha)}). */
    public static Color alpha(Color c, double alpha) {
        return c.withAlpha((float) alpha);
    }

    /** WCAG relative luminance, 0 to 1, from the linear channels. */
    public static double luminance(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float encoded) {
        return encoded <= 0 ? 0 : (encoded >= 1 ? encoded : Rgba.toLinear(encoded));
    }

    /** The WCAG contrast ratio of two colours, 1 to 21. */
    public static double contrastRatio(Color a, Color b) {
        double la = luminance(a) + 0.05;
        double lb = luminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    /** Whichever of {@code dark} and {@code light} reads better on {@code background} (higher contrast ratio). */
    public static Color contrast(Color background, Color dark, Color light) {
        return contrastRatio(background, dark) >= contrastRatio(background, light) ? dark : light;
    }

    /** Black or white, whichever reads better on {@code background}. */
    public static Color contrast(Color background) {
        return contrast(background, Color.BLACK, Color.WHITE);
    }

    /**
     * A colour from OKLCH (Björn Ottosson's perceptual space): lightness 0 to 1, chroma (about 0 to 0.4), hue in
     * degrees. Out-of-gamut colours are clipped per channel.
     */
    @AllowStrictMath("colour conversion when a sheet is built: the hue's cosine and sine")
    public static Color oklch(double lightness, double chroma, double hue, double alpha) {
        double radians = hue * (StrictMath.PI / 180);
        double a = chroma * StrictMath.cos(radians);
        double b = chroma * StrictMath.sin(radians);
        double l = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s = lightness - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        double red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
        return Color.linear((float) clamp01(red), (float) clamp01(green), (float) clamp01(blue), (float) alpha);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
