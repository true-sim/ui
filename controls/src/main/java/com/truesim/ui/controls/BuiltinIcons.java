package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;

/**
 * The toolkit's own chrome icons, so controls never depend on a game registering pictures: white shapes on
 * transparent texels (tinted by the style's {@code image} colour), drawn as distance fields from a few lines, triangles,
 * circles and rings on a 16-unit grid and rendered once, at {@link #SIZE} texels, when a style first names them.
 * Themes choose them by name like any image ({@code image: "icons/close" var(--fg) contain}); a game's own image
 * registered under the same name ({@link UiImages#add}) replaces the built-in one.
 *
 * <p>Names (after {@code icons/}): {@code close}, {@code dismiss}, {@code minimise}, {@code maximise}, {@code restore},
 * {@code more}, {@code previous}, {@code next}, {@code up}, {@code down} (filled arrows), {@code dropdown-arrow},
 * {@code collapsible-arrow}, {@code menu-arrow}, {@code crumb-separator}, {@code chevron-left}, {@code chevron-right},
 * {@code chevron-up}, {@code chevron-down}, {@code check}, {@code minus}, {@code plus}, {@code eye}, {@code eye-off},
 * {@code search}, {@code grip} (a drag handle).
 */
public final class BuiltinIcons {

    /** The prefix of their names. */
    public static final String PREFIX = "icons/";

    /** Texels along each side. */
    public static final int SIZE = 32;

    private static final String[] NAMES = {"close", "dismiss", "minimise", "maximise", "restore", "more", "previous",
        "next", "up", "down", "dropdown-arrow", "collapsible-arrow", "menu-arrow", "crumb-separator", "chevron-left",
        "chevron-right", "chevron-up", "chevron-down", "check", "minus", "plus", "eye", "eye-off", "search", "grip"};

    private static final float STROKE = 1.6f; // line width in grid units (16 across)

    private BuiltinIcons() {
    }

    /** The built-in icon named {@code name} (with its {@code icons/} prefix): its index, or -1. Allocates nothing. */
    public static int find(String name) {
        int p = PREFIX.length();
        if (name == null || !name.startsWith(PREFIX)) {
            return -1;
        }
        for (int i = 0; i < NAMES.length; i++) {
            String n = NAMES[i];
            if (name.length() == p + n.length() && name.regionMatches(p, n, 0, n.length())) {
                return i;
            }
        }
        return -1;
    }

    /** How many there are. */
    public static int count() {
        return NAMES.length;
    }

    /** The name of icon {@code index}, without its prefix. */
    public static String name(int index) {
        return NAMES[index];
    }

    /** Icon {@code index} as {@link #SIZE} x {@link #SIZE} RGBA texels (straight alpha, white). */
    @AllowAllocation("an icon is drawn once, when a style first names it")
    public static byte[] draw(int index) {
        byte[] rgba = new byte[SIZE * SIZE * 4];
        float k = 16f / SIZE; // grid units per texel
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float d = distance(NAMES[index], (x + 0.5f) * k, (y + 0.5f) * k);
                float coverage = Math.max(0, Math.min(1, 0.5f - d / k)); // a texel of antialiasing
                int o = (y * SIZE + x) * 4;
                rgba[o] = (byte) 255;
                rgba[o + 1] = (byte) 255;
                rgba[o + 2] = (byte) 255;
                rgba[o + 3] = (byte) (int) (coverage * 255 + 0.5f);
            }
        }
        return rgba;
    }

    /** The signed distance (grid units, negative inside) from {@code (x, y)} to icon {@code name}'s shape. */
    static float distance(String name, float x, float y) {
        return switch (name) {
            case "close", "dismiss" -> Math.min(line(x, y, 4, 4, 12, 12), line(x, y, 12, 4, 4, 12));
            case "minimise" -> line(x, y, 4, 11, 12, 11);
            case "maximise" -> frame(x, y, 4, 4, 12, 12);
            case "restore" -> Math.min(frame(x, y, 3.5f, 6, 10, 12.5f), Math.min(line(x, y, 6, 3.5f, 12.5f, 3.5f),
                line(x, y, 12.5f, 3.5f, 12.5f, 10)));
            case "more" -> Math.min(disc(x, y, 3.5f, 8, 1.6f), Math.min(disc(x, y, 8, 8, 1.6f),
                disc(x, y, 12.5f, 8, 1.6f)));
            case "previous" -> triangle(x, y, 11, 3, 4, 8, 11, 13);
            case "next", "menu-arrow", "crumb-separator" -> triangle(x, y, 5, 3, 12, 8, 5, 13);
            case "up" -> triangle(x, y, 3, 11, 8, 4, 13, 11);
            case "down", "dropdown-arrow", "collapsible-arrow" -> triangle(x, y, 3, 5, 13, 5, 8, 12);
            case "chevron-left" -> Math.min(line(x, y, 10, 3.5f, 5.5f, 8), line(x, y, 5.5f, 8, 10, 12.5f));
            case "chevron-right" -> Math.min(line(x, y, 6, 3.5f, 10.5f, 8), line(x, y, 10.5f, 8, 6, 12.5f));
            case "chevron-up" -> Math.min(line(x, y, 3.5f, 10, 8, 5.5f), line(x, y, 8, 5.5f, 12.5f, 10));
            case "chevron-down" -> Math.min(line(x, y, 3.5f, 6, 8, 10.5f), line(x, y, 8, 10.5f, 12.5f, 6));
            case "check" -> Math.min(line(x, y, 3, 8.5f, 6.5f, 12), line(x, y, 6.5f, 12, 13, 4.5f));
            case "minus" -> line(x, y, 4, 8, 12, 8);
            case "plus" -> Math.min(line(x, y, 4, 8, 12, 8), line(x, y, 8, 4, 8, 12));
            case "eye" -> Math.min(ring(x, y, 8, 8, 2.2f), lens(x, y));
            case "eye-off" -> Math.min(Math.min(ring(x, y, 8, 8, 2.2f), lens(x, y)), line(x, y, 3, 13, 13, 3));
            case "search" -> Math.min(ring(x, y, 7, 7, 4), line(x, y, 10, 10, 13.5f, 13.5f));
            case "grip" -> Math.min(Math.min(disc(x, y, 6, 4, 1.2f), disc(x, y, 10, 4, 1.2f)),
                Math.min(Math.min(disc(x, y, 6, 8, 1.2f), disc(x, y, 10, 8, 1.2f)),
                    Math.min(disc(x, y, 6, 12, 1.2f), disc(x, y, 10, 12, 1.2f))));
            default -> Float.MAX_VALUE;
        };
    }

    /** A stroke from {@code (ax, ay)} to {@code (bx, by)}, round-capped. */
    private static float line(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float t = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        float px = x - (ax + dx * t);
        float py = y - (ay + dy * t);
        return (float) Math.sqrt(px * px + py * py) - STROKE * 0.5f;
    }

    /** A filled circle. */
    private static float disc(float x, float y, float cx, float cy, float r) {
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy) - r;
    }

    /** A circle's outline. */
    private static float ring(float x, float y, float cx, float cy, float r) {
        return Math.abs(disc(x, y, cx, cy, r)) - STROKE * 0.5f;
    }

    /** A rectangle's outline. */
    private static float frame(float x, float y, float x0, float y0, float x1, float y1) {
        return Math.min(Math.min(line(x, y, x0, y0, x1, y0), line(x, y, x1, y0, x1, y1)),
            Math.min(line(x, y, x1, y1, x0, y1), line(x, y, x0, y1, x0, y0)));
    }

    /** An eye's outline: two arcs, as the ring of an ellipse squashed vertically. */
    private static float lens(float x, float y) {
        float dx = (x - 8) / 6f;
        float dy = (y - 8) / 4f;
        return Math.abs((float) Math.sqrt(dx * dx + dy * dy) - 1) * 4f - STROKE * 0.5f;
    }

    /** A filled triangle (any winding). */
    private static float triangle(float x, float y, float ax, float ay, float bx, float by, float cx, float cy) {
        float e = Math.min(edge(x, y, ax, ay, bx, by), Math.min(edge(x, y, bx, by, cx, cy), edge(x, y, cx, cy, ax, ay)));
        float s = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        float d0 = side(x, y, ax, ay, bx, by) * s;
        float d1 = side(x, y, bx, by, cx, cy) * s;
        float d2 = side(x, y, cx, cy, ax, ay) * s;
        boolean inside = d0 >= 0 && d1 >= 0 && d2 >= 0;
        return inside ? -e : e;
    }

    private static float side(float x, float y, float ax, float ay, float bx, float by) {
        return (bx - ax) * (y - ay) - (by - ay) * (x - ax);
    }

    /** The distance to a segment (no stroke). */
    private static float edge(float x, float y, float ax, float ay, float bx, float by) {
        return line(x, y, ax, ay, bx, by) + STROKE * 0.5f;
    }
}
