package com.truesim.ui.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowStrictMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SVG icon's filled shapes as one {@link GlyphOutline}, for the MTSDF atlas: an icon is drawn as a glyph is (a
 * distance field, crisp at any size, tinted by its style's colour). Parsed once, when the icon is loaded.
 *
 * <p>The outline is in ems, y up: the icon's view box is scaled so that its height is 1 em, its left bottom corner at
 * 0, 0; {@link #aspect()} is its width in ems. {@link #width()} and {@link #height()} are its size in SVG units (its
 * {@code width} and {@code height}, else its view box): the size it measures at.
 *
 * <p>Read: {@code svg} ({@code viewBox}, {@code width}, {@code height}), {@code g}, {@code path} (every command:
 * {@code M L H V C S Q T A Z}, relative and absolute; arcs become cubic curves), {@code rect} (with {@code rx},
 * {@code ry}), {@code circle}, {@code ellipse}, {@code polygon}, {@code polyline}, and {@code transform} on any of
 * them ({@code matrix}, {@code translate}, {@code scale}, {@code rotate}, {@code skewX}, {@code skewY}). A shape with
 * {@code fill="none"} is left out. Not read: strokes (an icon's lines must be filled shapes), CSS styles, gradients,
 * {@code use}, text, clipping and masks; overlapping shapes are a union (the distance field's nonzero fill), so
 * {@code fill-rule="evenodd"} holes need contours wound the other way. A small parser of our own: NanoSVG (lwjgl-nanovg)
 * would read more, but it is not among the build's libraries.
 */
@AllowStrictMath("arcs and rotations, parsed once per icon: StrictMath gives the same outline on every platform")
public final class SvgIcon {

    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([A-Za-z][\\w:-]*)([^>]*?)(/?)\\s*>",
        Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM = Pattern.compile("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)");

    private final GlyphOutline outline;
    private final float aspect;
    private final float width;
    private final float height;

    private SvgIcon(GlyphOutline outline, float aspect, float width, float height) {
        this.outline = outline;
        this.aspect = aspect;
        this.width = width;
        this.height = height;
    }

    /** The icon's outline: in ems, y up, its view box 1 em high from 0, 0. */
    public GlyphOutline outline() {
        return outline;
    }

    /** Its width in ems (its view box's width over its height). */
    public float aspect() {
        return aspect;
    }

    /** Its width in SVG units: what it measures at. */
    public float width() {
        return width;
    }

    /** Its height in SVG units. */
    public float height() {
        return height;
    }

    /**
     * Parses {@code svg} (a whole document). Throws {@link IllegalArgumentException} when it is not an SVG document or
     * a number or path is malformed.
     */
    @AllowAllocation("icons are parsed once, when loaded")
    public static SvgIcon parse(String svg) {
        Parser p = new Parser();
        p.run(svg);
        return new SvgIcon(p.out, p.vw / p.vh, p.width, p.height);
    }

    /** The parse: elements in document order, a stack of transforms. */
    private static final class Parser {
        final GlyphOutline out = new GlyphOutline();
        final List<double[]> stack = new ArrayList<>(); // transforms of open groups: a b c d e f
        double[] m = {1, 0, 0, 1, 0, 0}; // the current element's transform into ems
        boolean seenSvg;
        float vx;
        float vy;
        float vw;
        float vh;
        float width;
        float height;
        // the current point and the last control point (for S and T), in SVG units of the element
        double cx;
        double cy;
        double sx;
        double sy;
        double lastCx;
        double lastCy;
        char last;

        void run(String svg) {
            Matcher t = TAG.matcher(svg);
            while (t.find()) {
                boolean closing = !t.group(1).isEmpty();
                String name = t.group(2).toLowerCase(Locale.ROOT);
                if (name.startsWith("!") || name.startsWith("?")) {
                    continue;
                }
                boolean empty = !t.group(4).isEmpty();
                String attributes = t.group(3);
                if (closing) {
                    if ((name.equals("g") || name.equals("svg")) && !stack.isEmpty()) {
                        m = stack.remove(stack.size() - 1);
                    }
                    continue;
                }
                if (name.equals("svg")) {
                    if (!seenSvg) {
                        root(attributes);
                    }
                    if (!empty) {
                        stack.add(m);
                    }
                    continue;
                }
                if (!seenSvg) {
                    continue;
                }
                double[] parent = m;
                double[] own = transform(attribute(attributes, "transform"));
                double[] element = own == null ? parent : multiply(parent, own);
                if (name.equals("g")) {
                    if (!empty) {
                        stack.add(parent);
                        m = element;
                    }
                    continue;
                }
                if ("none".equals(attribute(attributes, "fill"))) {
                    continue;
                }
                m = element;
                shape(name, attributes);
                m = parent;
            }
            if (!seenSvg) {
                throw new IllegalArgumentException("not an SVG document");
            }
        }

        void root(String attributes) {
            seenSvg = true;
            String box = attribute(attributes, "viewBox");
            float w = length(attribute(attributes, "width"));
            float h = length(attribute(attributes, "height"));
            if (box != null) {
                double[] v = numbers(box);
                if (v.length != 4 || v[2] <= 0 || v[3] <= 0) {
                    throw new IllegalArgumentException("bad viewBox: " + box);
                }
                vx = (float) v[0];
                vy = (float) v[1];
                vw = (float) v[2];
                vh = (float) v[3];
            } else if (w > 0 && h > 0) {
                vw = w;
                vh = h;
            } else {
                throw new IllegalArgumentException("an SVG icon needs a viewBox or a width and height");
            }
            width = w > 0 ? w : vw;
            height = h > 0 ? h : vh;
            // view box to ems: x right from its left, y up from its bottom, its height 1
            double k = 1.0 / vh;
            m = new double[] {k, 0, 0, -k, -vx * k, 1 + vy * k};
        }

        void shape(String name, String a) {
            switch (name) {
                case "path" -> path(attribute(a, "d"));
                case "rect" -> rect(num(a, "x"), num(a, "y"), num(a, "width"), num(a, "height"), a);
                case "circle" -> ellipse(num(a, "cx"), num(a, "cy"), num(a, "r"), num(a, "r"));
                case "ellipse" -> ellipse(num(a, "cx"), num(a, "cy"), num(a, "rx"), num(a, "ry"));
                case "polygon", "polyline" -> poly(attribute(a, "points"));
                default -> {
                    // not a filled shape this parser reads
                }
            }
        }

        void rect(double x, double y, double w, double h, String a) {
            if (w <= 0 || h <= 0) {
                return;
            }
            String rxs = attribute(a, "rx");
            String rys = attribute(a, "ry");
            double rx = rxs != null ? number(rxs) : rys != null ? number(rys) : 0;
            double ry = rys != null ? number(rys) : rx;
            rx = Math.min(Math.max(rx, 0), w / 2);
            ry = Math.min(Math.max(ry, 0), h / 2);
            if (rx <= 0 || ry <= 0) {
                move(x, y);
                line(x + w, y);
                line(x + w, y + h);
                line(x, y + h);
                return;
            }
            move(x + rx, y);
            line(x + w - rx, y);
            arc(rx, ry, 0, false, true, x + w, y + ry);
            line(x + w, y + h - ry);
            arc(rx, ry, 0, false, true, x + w - rx, y + h);
            line(x + rx, y + h);
            arc(rx, ry, 0, false, true, x, y + h - ry);
            line(x, y + ry);
            arc(rx, ry, 0, false, true, x + rx, y);
        }

        void ellipse(double x, double y, double rx, double ry) {
            if (rx <= 0 || ry <= 0) {
                return;
            }
            move(x + rx, y);
            arc(rx, ry, 0, false, true, x, y + ry);
            arc(rx, ry, 0, false, true, x - rx, y);
            arc(rx, ry, 0, false, true, x, y - ry);
            arc(rx, ry, 0, false, true, x + rx, y);
        }

        void poly(String points) {
            if (points == null) {
                return;
            }
            double[] v = numbers(points);
            for (int i = 0; i + 1 < v.length; i += 2) {
                if (i == 0) {
                    move(v[0], v[1]);
                } else {
                    line(v[i], v[i + 1]);
                }
            }
        }

        void path(String d) {
            if (d == null) {
                return;
            }
            Tokens t = new Tokens(d);
            char command = 0;
            last = 0;
            while (t.more()) {
                char c = t.command();
                if (c != 0) {
                    command = c;
                } else if (command == 0) {
                    throw new IllegalArgumentException("path data must start with a command: " + d);
                }
                boolean rel = Character.isLowerCase(command);
                double ox = rel ? cx : 0;
                double oy = rel ? cy : 0;
                switch (Character.toUpperCase(command)) {
                    case 'M' -> {
                        move(ox + t.number(), oy + t.number());
                        command = rel ? 'l' : 'L'; // further pairs are lines
                    }
                    case 'L' -> line(ox + t.number(), oy + t.number());
                    case 'H' -> line(ox + t.number(), cy);
                    case 'V' -> line(cx, oy + t.number());
                    case 'C' -> cubic(ox + t.number(), oy + t.number(), ox + t.number(), oy + t.number(),
                        ox + t.number(), oy + t.number());
                    case 'S' -> {
                        double x1 = last == 'C' ? 2 * cx - lastCx : cx;
                        double y1 = last == 'C' ? 2 * cy - lastCy : cy;
                        cubic(x1, y1, ox + t.number(), oy + t.number(), ox + t.number(), oy + t.number());
                    }
                    case 'Q' -> quad(ox + t.number(), oy + t.number(), ox + t.number(), oy + t.number());
                    case 'T' -> {
                        double x1 = last == 'Q' ? 2 * cx - lastCx : cx;
                        double y1 = last == 'Q' ? 2 * cy - lastCy : cy;
                        quad(x1, y1, ox + t.number(), oy + t.number());
                    }
                    case 'A' -> {
                        double rx = t.number();
                        double ry = t.number();
                        double rotation = t.number();
                        boolean large = t.flag();
                        boolean sweep = t.flag();
                        arc(rx, ry, rotation, large, sweep, ox + t.number(), oy + t.number());
                    }
                    case 'Z' -> {
                        cx = sx;
                        cy = sy;
                        last = 'Z';
                        command = 0;
                    }
                    default -> throw new IllegalArgumentException("unknown path command " + command + ": " + d);
                }
            }
        }

        void move(double x, double y) {
            cx = x;
            cy = y;
            sx = x;
            sy = y;
            out.moveTo(tx(x, y), ty(x, y));
            last = 'M';
        }

        void line(double x, double y) {
            cx = x;
            cy = y;
            out.lineTo(tx(x, y), ty(x, y));
            last = 'L';
        }

        void cubic(double x1, double y1, double x2, double y2, double x, double y) {
            out.cubicTo(tx(x1, y1), ty(x1, y1), tx(x2, y2), ty(x2, y2), tx(x, y), ty(x, y));
            lastCx = x2;
            lastCy = y2;
            cx = x;
            cy = y;
            last = 'C';
        }

        void quad(double x1, double y1, double x, double y) {
            out.quadTo(tx(x1, y1), ty(x1, y1), tx(x, y), ty(x, y));
            lastCx = x1;
            lastCy = y1;
            cx = x;
            cy = y;
            last = 'Q';
        }

        /** An elliptical arc (SVG implementation notes, F.6.5) as cubic curves of at most a quarter turn each. */
        void arc(double rx, double ry, double degrees, boolean large, boolean sweep, double x, double y) {
            double x0 = cx;
            double y0 = cy;
            if (x0 == x && y0 == y) {
                return;
            }
            rx = Math.abs(rx);
            ry = Math.abs(ry);
            if (rx == 0 || ry == 0) {
                line(x, y);
                return;
            }
            double phi = StrictMath.toRadians(degrees);
            double cos = StrictMath.cos(phi);
            double sin = StrictMath.sin(phi);
            double dx = (x0 - x) / 2;
            double dy = (y0 - y) / 2;
            double x1 = cos * dx + sin * dy;
            double y1 = -sin * dx + cos * dy;
            double lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry);
            if (lambda > 1) {
                double s = Math.sqrt(lambda);
                rx *= s;
                ry *= s;
            }
            double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
            double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
            double coef = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
            double ccx = coef * rx * y1 / ry;
            double ccy = -coef * ry * x1 / rx;
            double centreX = cos * ccx - sin * ccy + (x0 + x) / 2;
            double centreY = sin * ccx + cos * ccy + (y0 + y) / 2;
            double start = StrictMath.atan2((y1 - ccy) / ry, (x1 - ccx) / rx);
            double end = StrictMath.atan2((-y1 - ccy) / ry, (-x1 - ccx) / rx);
            double delta = end - start;
            if (sweep && delta < 0) {
                delta += 2 * Math.PI;
            } else if (!sweep && delta > 0) {
                delta -= 2 * Math.PI;
            }
            int pieces = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9);
            double step = delta / pieces;
            double k = 4.0 / 3 * StrictMath.tan(step / 4);
            double a = start;
            for (int i = 0; i < pieces; i++) {
                double b = a + step;
                double ca = StrictMath.cos(a);
                double sa = StrictMath.sin(a);
                double cb = StrictMath.cos(b);
                double sb = StrictMath.sin(b);
                double px1 = rx * (ca - k * sa);
                double py1 = ry * (sa + k * ca);
                double px2 = rx * (cb + k * sb);
                double py2 = ry * (sb - k * cb);
                double px = rx * cb;
                double py = ry * sb;
                double ex = i == pieces - 1 ? x : centreX + cos * px - sin * py;
                double ey = i == pieces - 1 ? y : centreY + sin * px + cos * py;
                cubic(centreX + cos * px1 - sin * py1, centreY + sin * px1 + cos * py1,
                    centreX + cos * px2 - sin * py2, centreY + sin * px2 + cos * py2, ex, ey);
                a = b;
            }
            last = 'A';
        }

        float tx(double x, double y) {
            return (float) (m[0] * x + m[2] * y + m[4]);
        }

        float ty(double x, double y) {
            return (float) (m[1] * x + m[3] * y + m[5]);
        }
    }

    /** A path's tokens: commands, numbers (with SVG's compact forms: "1.5.5", "1-2"), arc flags. */
    private static final class Tokens {
        final String s;
        int i;

        Tokens(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
        }

        boolean more() {
            skip();
            return i < s.length();
        }

        /** The next command letter, or 0 if a number follows (the command repeats). */
        char command() {
            skip();
            char c = s.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') {
                i++;
                return c;
            }
            return 0;
        }

        double number() {
            skip();
            Matcher n = NUMBER.matcher(s);
            if (i >= s.length() || !n.find(i) || n.start() != i) {
                throw new IllegalArgumentException("a number expected at " + i + ": " + s);
            }
            i = n.end();
            return Double.parseDouble(n.group());
        }

        boolean flag() {
            skip();
            if (i < s.length() && (s.charAt(i) == '0' || s.charAt(i) == '1')) {
                return s.charAt(i++) == '1';
            }
            throw new IllegalArgumentException("an arc flag expected at " + i + ": " + s);
        }
    }

    // ---------------------------------------------------------------- attributes and transforms

    static String attribute(String attributes, String name) {
        Matcher a = ATTRIBUTE.matcher(attributes);
        while (a.find()) {
            if (a.group(1).equals(name)) {
                return a.group(3) != null ? a.group(3) : a.group(4);
            }
        }
        return null;
    }

    private static double num(String attributes, String name) {
        String v = attribute(attributes, name);
        return v == null ? 0 : number(v);
    }

    private static double number(String v) {
        Matcher n = NUMBER.matcher(v.trim());
        if (!n.lookingAt()) {
            throw new IllegalArgumentException("not a number: " + v);
        }
        return Double.parseDouble(n.group());
    }

    /** A length attribute in SVG units (px, or no unit), or 0 when absent or relative (a percentage, em). */
    private static float length(String v) {
        if (v == null) {
            return 0;
        }
        String t = v.trim();
        if (t.endsWith("%") || t.endsWith("em") || t.endsWith("ex")) {
            return 0;
        }
        return (float) number(t);
    }

    private static double[] numbers(String v) {
        Matcher n = NUMBER.matcher(v);
        double[] a = new double[8];
        int count = 0;
        while (n.find()) {
            if (count == a.length) {
                a = java.util.Arrays.copyOf(a, count * 2);
            }
            a[count++] = Double.parseDouble(n.group());
        }
        return java.util.Arrays.copyOf(a, count);
    }

    /** A transform attribute as a matrix {@code a b c d e f}, or null. */
    private static double[] transform(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        double[] m = {1, 0, 0, 1, 0, 0};
        Matcher t = TRANSFORM.matcher(v);
        while (t.find()) {
            double[] a = numbers(t.group(2));
            double[] next = switch (t.group(1)) {
                case "matrix" -> {
                    if (a.length != 6) {
                        throw new IllegalArgumentException("matrix needs six numbers: " + v);
                    }
                    yield a;
                }
                case "translate" -> new double[] {1, 0, 0, 1, a[0], a.length > 1 ? a[1] : 0};
                case "scale" -> new double[] {a[0], 0, 0, a.length > 1 ? a[1] : a[0], 0, 0};
                case "rotate" -> {
                    double r = StrictMath.toRadians(a[0]);
                    double c = StrictMath.cos(r);
                    double s = StrictMath.sin(r);
                    double[] rot = {c, s, -s, c, 0, 0};
                    if (a.length == 3) {
                        rot = multiply(multiply(new double[] {1, 0, 0, 1, a[1], a[2]}, rot),
                            new double[] {1, 0, 0, 1, -a[1], -a[2]});
                    }
                    yield rot;
                }
                case "skewX" -> new double[] {1, 0, StrictMath.tan(StrictMath.toRadians(a[0])), 1, 0, 0};
                default -> new double[] {1, StrictMath.tan(StrictMath.toRadians(a[0])), 0, 1, 0, 0};
            };
            m = multiply(m, next);
        }
        return m;
    }

    /** {@code p} then... : the matrix applying {@code q} first, then {@code p}. */
    private static double[] multiply(double[] p, double[] q) {
        return new double[] {
            p[0] * q[0] + p[2] * q[1], p[1] * q[0] + p[3] * q[1],
            p[0] * q[2] + p[2] * q[3], p[1] * q[2] + p[3] * q[3],
            p[0] * q[4] + p[2] * q[5] + p[4], p[1] * q[4] + p[3] * q[5] + p[5],
        };
    }
}
