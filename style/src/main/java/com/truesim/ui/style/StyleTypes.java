package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.Operators;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.Value.Col;
import com.truesim.ui.style.expr.Value.Fn;
import com.truesim.ui.style.expr.Value.ListV;
import com.truesim.ui.style.expr.Value.Num;
import com.truesim.ui.style.expr.Value.Str;
import com.truesim.ui.style.expr.ValueException;
import com.truesim.ui.style.value.Anchors;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Easing;
import com.truesim.ui.style.value.FontSpec;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.ImageFit;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.Size;
import com.truesim.ui.style.value.TextOutline;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.style.value.Transition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The {@link StyleType}s of the standard value records, and builders for numbers, enumerations and lists. Parsing
 * runs when a sheet is compiled or a token changes; its messages say what was expected and what was found.
 */
@AllowAllocation("types parse when a sheet is compiled or a token changes")
public final class StyleTypes {

    private StyleTypes() {
    }

    /** A type from a description, a parser and a check of Java values. */
    public static <T> StyleType<T> of(String description, Function<Value, T> parser, UnaryOperator<T> check) {
        return new Simple<>(description, parser, check);
    }

    private record Simple<T>(String description, Function<Value, T> parser, UnaryOperator<T> checker)
        implements StyleType<T> {
        @Override
        public T parse(Value value) {
            return parser.apply(value);
        }

        @Override
        public T check(T value) {
            return checker.apply(StyleType.super.check(value));
        }
    }

    static ValueException expected(String what, Value got) {
        return new ValueException("expected " + what + ", got " + got.kind() + " " + got.show());
    }

    private static <T> UnaryOperator<T> any() {
        return v -> v;
    }

    // ---------------------------------------------------------------- numbers

    /** A number in {@code min..max}, unitless or in px. */
    public static StyleType<Float> number(float min, float max) {
        String what = "a number from " + Num.format(min) + " to " + Num.format(max);
        return of(what, v -> range(what, px(v, what), min, max, v), n -> {
            if (!(n >= min && n <= max)) {
                throw new IllegalArgumentException(n + " is not " + what);
            }
            return n;
        });
    }

    private static float range(String what, float n, float min, float max, Value v) {
        if (!(n >= min && n <= max)) {
            throw expected(what, v);
        }
        return n;
    }

    /** An integer in {@code min..max}. */
    public static StyleType<Integer> integer(int min, int max) {
        String what = "an integer from " + min + " to " + max;
        return of(what, v -> {
            if (v instanceof Num n && n.unit().isEmpty() && n.value() == Math.rint(n.value()) && n.value() >= min
                && n.value() <= max) {
                return (int) n.value();
            }
            throw expected(what, v);
        }, n -> {
            if (n < min || n > max) {
                throw new IllegalArgumentException(n + " is not " + what);
            }
            return n;
        });
    }

    /** A fraction from 0 to 1, unitless or a percentage ({@code opacity: 50%}). */
    public static StyleType<Float> fraction() {
        String what = "a fraction from 0 to 1 or a percentage";
        return of(what, v -> {
            if (v instanceof Num n && (n.unit().isEmpty() || n.unit().equals("%"))) {
                double f = n.unit().isEmpty() ? n.value() : n.value() / 100;
                if (f >= 0 && f <= 1) {
                    return (float) f;
                }
            }
            throw expected(what, v);
        }, n -> {
            if (!(n >= 0 && n <= 1)) {
                throw new IllegalArgumentException(n + " is not " + what);
            }
            return n;
        });
    }

    /** A duration in seconds, written in {@code ms} or {@code s}. */
    public static StyleType<Float> duration() {
        return of("a duration (ms or s)", StyleTypes::seconds, any());
    }

    private static float seconds(Value v) {
        if (v instanceof Num n && (n.unit().equals("ms") || n.unit().equals("s")) && n.value() >= 0) {
            return (float) Operators.convert(n.value(), n.unit(), "s");
        }
        throw expected("a duration (ms or s)", v);
    }

    /** A font weight, 1 to 1000, or {@code normal} (400) or {@code bold} (700). */
    public static StyleType<Integer> fontWeight() {
        String what = "a font weight (1 to 1000, normal or bold)";
        StyleType<Integer> numeric = integer(1, 1000);
        return of(what, v -> weight(v, what, numeric), numeric::check);
    }

    private static int weight(Value v, String what, StyleType<Integer> numeric) {
        if (v instanceof Str s && !s.quoted()) {
            switch (s.text()) {
                case "normal":
                    return 400;
                case "bold":
                    return 700;
                default:
                    throw expected(what, v);
            }
        }
        try {
            return numeric.parse(v);
        } catch (ValueException e) {
            throw expected(what, v);
        }
    }

    /** {@code v} as px: a number in px or unitless. */
    static float px(Value v, String what) {
        if (v instanceof Num n && (n.unit().isEmpty() || n.unit().equals("px"))) {
            return (float) n.value();
        }
        throw expected(what, v);
    }

    // ---------------------------------------------------------------- strings, colours, enumerations

    /** A string, quoted or not. */
    public static StyleType<String> string() {
        return of("a string", v -> {
            if (v instanceof Str s) {
                return s.text();
            }
            throw expected("a string", v);
        }, any());
    }

    /** A colour. */
    public static StyleType<Color> color() {
        return of("a colour", StyleTypes::color, any());
    }

    static Color color(Value v) {
        if (v instanceof Col c) {
            return c.color();
        }
        throw expected("a colour", v);
    }

    /** True or false. */
    public static StyleType<Boolean> bool() {
        return of("true or false", v -> {
            if (v instanceof Value.Bool b) {
                return b.value();
            }
            throw expected("true or false", v);
        }, any());
    }

    /** An enumeration; constants are written in kebab case ({@code SPACE_BETWEEN} as {@code space-between}). */
    public static <E extends Enum<E>> StyleType<E> enumeration(Class<E> type) {
        E[] constants = type.getEnumConstants();
        String[] names = new String[constants.length];
        for (int i = 0; i < constants.length; i++) {
            names[i] = kebab(constants[i].name());
        }
        String what = "one of " + String.join(", ", names);
        return of(what, v -> {
            if (v instanceof Str s && !s.quoted()) {
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(s.text())) {
                        return constants[i];
                    }
                }
                String close = StyleKeys.closest(s.text(), List.of(names));
                throw new ValueException("expected " + what + ", got " + s.text()
                    + (close == null ? "" : " (did you mean " + close + "?)"));
            }
            throw expected(what, v);
        }, any());
    }

    /** {@code SPACE_BETWEEN} as {@code space-between}. */
    public static String kebab(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A list of {@code element}s, space or comma separated; {@code none} is the empty list. */
    public static <E> StyleType<List<E>> list(StyleType<E> element) {
        String what = "a list of " + element.description();
        return of(what, v -> {
            if (isNone(v)) {
                return List.of();
            }
            List<E> out = new ArrayList<>();
            for (Value item : v.items()) {
                out.add(element.parse(item));
            }
            return List.copyOf(out);
        }, l -> {
            for (E e : l) {
                element.check(e);
            }
            return List.copyOf(l);
        });
    }

    /** A list of family names, comma separated, quoted or not ({@code "Inter", "Segoe UI", sans-serif}). */
    public static StyleType<List<String>> families() {
        StyleType<List<String>> list = list(string());
        return of("a list of font family names", v -> {
            List<String> families = list.parse(v);
            if (families.isEmpty()) {
                throw expected("a list of font family names", v);
            }
            return families;
        }, list::check);
    }

    static boolean isNone(Value v) {
        return v instanceof Str s && !s.quoted() && s.text().equals("none");
    }

    // ---------------------------------------------------------------- lengths, insets, radii

    /** A length: px (or unitless), em, %, or {@code auto}. */
    public static StyleType<Length> length() {
        return of("a length (px, em, % or auto)", StyleTypes::length, any());
    }

    static Length length(Value v) {
        if (v instanceof Str s && !s.quoted() && s.text().equals("auto")) {
            return Length.AUTO;
        }
        if (v instanceof Num n) {
            switch (n.unit()) {
                case "", "px":
                    return Length.px((float) n.value());
                case "em":
                    return Length.em((float) n.value());
                case "%":
                    return Length.percent((float) n.value());
                default:
                    break;
            }
        }
        throw expected("a length (px, em, % or auto)", v);
    }

    /** One to four px values in CSS order (top, right, bottom, left). */
    public static StyleType<Insets> insets() {
        String what = "1 to 4 px values (top right bottom left)";
        return of(what, v -> {
            float[] f = sides(v, what);
            return new Insets(f[0], f[1], f[2], f[3]);
        }, any());
    }

    /** One to four corner radii in px, CSS order (top left, top right, bottom right, bottom left). */
    public static StyleType<Radii> radii() {
        String what = "1 to 4 px radii";
        return of(what, v -> {
            float[] f = sides(v, what);
            return new Radii(f[0], f[1], f[2], f[3]);
        }, any());
    }

    private static float[] sides(Value v, String what) {
        List<Value> items = v instanceof ListV l && !l.comma() ? l.items() : List.of(v);
        if (items.isEmpty() || items.size() > 4) {
            throw expected(what, v);
        }
        float[] f = new float[items.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = px(items.get(i), what);
        }
        return switch (f.length) {
            case 1 -> new float[] {f[0], f[0], f[0], f[0]};
            case 2 -> new float[] {f[0], f[1], f[0], f[1]};
            case 3 -> new float[] {f[0], f[1], f[2], f[1]};
            default -> f;
        };
    }

    /** One or two lengths: width and height (one for both). */
    public static StyleType<Size> size() {
        String what = "one or two lengths (width height)";
        return of(what, v -> {
            List<Value> items = v instanceof ListV l && !l.comma() ? l.items() : List.of(v);
            if (items.isEmpty() || items.size() > 2) {
                throw expected(what, v);
            }
            Length w = length(items.get(0));
            return new Size(w, items.size() == 2 ? length(items.get(1)) : w);
        }, any());
    }

    // ---------------------------------------------------------------- borders, shadows, outlines

    /** {@code none}, {@code width colour} or {@code border(width, colour)}. */
    public static StyleType<Border> border() {
        String what = "a border (none, or width and colour)";
        return of(what, v -> {
            if (isNone(v)) {
                return Border.NONE;
            }
            List<Value> parts = v instanceof Fn f && f.name().equals("border") ? f.args() : spaceItems(v);
            if (parts.size() != 2) {
                throw expected(what, v);
            }
            return new Border(px(parts.get(0), what), colorIn(parts.get(1), what));
        }, any());
    }

    private static Color colorIn(Value v, String what) {
        if (v instanceof Col c) {
            return c.color();
        }
        throw expected(what, v);
    }

    private static List<Value> spaceItems(Value v) {
        return v instanceof ListV l && !l.comma() ? l.items() : List.of(v);
    }

    /** {@code none}, or shadows separated by commas, each {@code x y [blur [spread]] colour}. */
    public static StyleType<List<Shadow>> shadows() {
        String what = "shadows (x y [blur [spread]] colour, ...)";
        StyleType<Shadow> one = of(what, v -> {
            List<Value> parts = spaceItems(v);
            if (parts.size() < 3 || parts.size() > 5) {
                throw expected(what, v);
            }
            int n = parts.size() - 1;
            float[] f = new float[4];
            for (int i = 0; i < n; i++) {
                f[i] = px(parts.get(i), what);
            }
            return new Shadow(f[0], f[1], f[2], f[3], colorIn(parts.get(n), what));
        }, any());
        return of(what, v -> {
            if (isNone(v)) {
                return List.of();
            }
            List<Value> items = v instanceof ListV l && l.comma() ? l.items() : List.of(v);
            List<Shadow> out = new ArrayList<>();
            for (Value item : items) {
                out.add(one.parse(item));
            }
            return List.copyOf(out);
        }, List::copyOf);
    }

    /** {@code none} or {@code width colour}. */
    public static StyleType<TextOutline> textOutline() {
        String what = "a text outline (none, or width and colour)";
        return of(what, v -> {
            if (isNone(v)) {
                return TextOutline.NONE;
            }
            List<Value> parts = spaceItems(v);
            if (parts.size() != 2) {
                throw expected(what, v);
            }
            return new TextOutline(px(parts.get(0), what), colorIn(parts.get(1), what));
        }, any());
    }

    // ---------------------------------------------------------------- paints and images

    /**
     * A paint: {@code none}; a colour or {@code box(colour)}; {@code linear-gradient([angle,] from, to)};
     * {@code image("name"[, tint])}; {@code nine-slice("name", left top right bottom[, destination insets][, tint])}.
     */
    public static StyleType<Paint> paint() {
        return of("a paint (none, a colour, box(), linear-gradient(), image() or nine-slice())", StyleTypes::paint,
            any());
    }

    private static Paint paint(Value v) {
        String what = "a paint (none, a colour, box(), linear-gradient(), image() or nine-slice())";
        if (isNone(v)) {
            return Paint.NONE;
        }
        if (v instanceof Col c) {
            return Paint.box(c.color());
        }
        if (!(v instanceof Fn f)) {
            throw expected(what, v);
        }
        List<Value> args = f.args();
        switch (f.name()) {
            case "box" -> {
                arity(f, 1, 1);
                return Paint.box(colorIn(args.get(0), "box(colour)"));
            }
            case "linear-gradient" -> {
                arity(f, 2, 3);
                boolean angleFirst = args.size() == 3 && args.get(0) instanceof Num;
                Value angle = args.size() == 3 ? args.get(angleFirst ? 0 : 2) : Value.num(180, "deg");
                int first = angleFirst ? 1 : 0;
                return Paint.gradient(colorIn(args.get(first), "a gradient colour"),
                    colorIn(args.get(first + 1), "a gradient colour"), degrees(angle));
            }
            case "image" -> {
                arity(f, 1, 2);
                return Paint.image(text(args.get(0), "an image name"),
                    args.size() > 1 ? colorIn(args.get(1), "a tint colour") : Color.WHITE);
            }
            case "nine-slice" -> {
                arity(f, 2, 4);
                String insets = "source insets in texels (left top right bottom)";
                float[] s = sides(args.get(1), insets);
                int[] t = new int[4];
                for (int i = 0; i < 4; i++) {
                    if (s[i] != (int) s[i] || s[i] < 0) {
                        throw expected(insets, args.get(1));
                    }
                    t[i] = (int) s[i];
                }
                Insets destination = null;
                Color tint = Color.WHITE;
                for (int i = 2; i < args.size(); i++) {
                    if (args.get(i) instanceof Col c) {
                        tint = c.color();
                    } else {
                        float[] d = sides(args.get(i), "destination insets in px (left top right bottom)");
                        destination = new Insets(d[1], d[2], d[3], d[0]);
                    }
                }
                return new Paint.NineSlice(text(args.get(0), "an image name"), t[0], t[1], t[2], t[3], destination,
                    tint);
            }
            default -> throw expected(what, v);
        }
    }

    private static void arity(Fn f, int min, int max) {
        if (f.args().size() < min || f.args().size() > max) {
            throw new ValueException(f.name() + "() takes " + (min == max ? min : min + " to " + max)
                + " arguments, got " + f.args().size());
        }
    }

    private static float degrees(Value v) {
        if (v instanceof Num n && Operators.compatible(n.unit(), "deg")) {
            return (float) Operators.convert(n.value(), n.unit(), "deg");
        }
        throw expected("an angle", v);
    }

    private static String text(Value v, String what) {
        if (v instanceof Str s) {
            return s.text();
        }
        throw expected(what, v);
    }

    /** {@code none}, or {@code "name" [tint] [fit]} (fit: fill, contain, cover, none, scale-down). */
    public static StyleType<ImageSpec> image() {
        String what = "an image (\"name\" [tint colour] [fit])";
        StyleType<ImageFit> fits = enumeration(ImageFit.class);
        return of(what, v -> {
            if (isNone(v)) {
                return ImageSpec.NONE;
            }
            List<Value> parts = spaceItems(v);
            if (!(parts.get(0) instanceof Str name && name.quoted()) || parts.size() > 3) {
                throw expected(what, v);
            }
            Color tint = Color.WHITE;
            ImageFit fit = ImageFit.CONTAIN;
            for (int i = 1; i < parts.size(); i++) {
                if (parts.get(i) instanceof Col c) {
                    tint = c.color();
                } else {
                    fit = fits.parse(parts.get(i));
                }
            }
            return new ImageSpec(name.text(), tint, fit);
        }, any());
    }

    // ---------------------------------------------------------------- fonts and transitions

    /**
     * The {@code font} shorthand, as CSS: {@code [italic] [weight] size family, family, ...}
     * ({@code font: bold 14px "Inter", "Segoe UI"}).
     */
    public static StyleType<FontSpec> font() {
        String what = "a font ([italic] [weight] size family, ...)";
        StyleType<Integer> weights = fontWeight();
        return of(what, v -> {
            List<Value> comma = v instanceof ListV l && l.comma() ? l.items() : List.of(v);
            List<Value> head = spaceItems(comma.get(0));
            if (head.size() < 2) {
                throw expected(what, v);
            }
            FontStyle style = FontStyle.NORMAL;
            int weight = 400;
            int i = 0;
            if (head.get(i) instanceof Str s && !s.quoted() && s.text().equals("italic")) {
                style = FontStyle.ITALIC;
                i++;
            }
            if (head.size() - i == 3) {
                weight = weights.parse(head.get(i++));
            }
            if (head.size() - i != 2) {
                throw expected(what, v);
            }
            float size = px(head.get(i), what);
            List<String> families = new ArrayList<>();
            families.add(text(head.get(i + 1), "a font family"));
            for (int k = 1; k < comma.size(); k++) {
                families.add(text(comma.get(k), "a font family"));
            }
            return new FontSpec(families, size, weight, style);
        }, any());
    }

    /** {@code none}, or transitions separated by commas, each {@code property duration [easing] [delay]}. */
    public static StyleType<List<Transition>> transitions() {
        String what = "transitions (property duration [easing] [delay], ...)";
        StyleType<Easing> easings = enumeration(Easing.class);
        StyleType<Transition> one = of(what, v -> {
            List<Value> parts = spaceItems(v);
            if (parts.size() < 2 || parts.size() > 4 || !(parts.get(0) instanceof Str p) || p.quoted()) {
                throw expected(what, v);
            }
            String property = p.text();
            if (!property.equals("all") && StyleKeys.find(property) == null) {
                String close = StyleKeys.suggest(property);
                throw new ValueException("transition of unknown key " + property
                    + (close == null ? "" : " (did you mean " + close + "?)"));
            }
            float duration = seconds(parts.get(1));
            Easing easing = Easing.EASE;
            float delay = 0;
            for (int i = 2; i < parts.size(); i++) {
                if (parts.get(i) instanceof Num) {
                    delay = seconds(parts.get(i));
                } else {
                    easing = easings.parse(parts.get(i));
                }
            }
            return new Transition(property, duration, easing, delay);
        }, any());
        return of(what, v -> {
            if (isNone(v)) {
                return List.of();
            }
            List<Value> items = v instanceof ListV l && l.comma() ? l.items() : List.of(v);
            List<Transition> out = new ArrayList<>();
            for (Value item : items) {
                out.add(one.parse(item));
            }
            return List.copyOf(out);
        }, List::copyOf);
    }

    // ---------------------------------------------------------------- layout

    /** Grid tracks: {@code 100px 1fr auto minmax(50px, 1fr) repeat(3, 1fr)}. */
    public static StyleType<List<Track>> tracks() {
        String what = "grid tracks (px, fr, auto, minmax(), repeat())";
        return of(what, v -> {
            List<Track> out = new ArrayList<>();
            for (Value item : spaceItems(v)) {
                if (item instanceof Fn f && f.name().equals("repeat")) {
                    arity(f, 2, 2);
                    if (!(f.args().get(0) instanceof Num n && n.unit().isEmpty() && n.value() >= 1
                        && n.value() == (int) n.value())) {
                        throw expected("a repeat count", f.args().get(0));
                    }
                    for (int i = 0; i < (int) n.value(); i++) {
                        for (Value t : spaceItems(f.args().get(1))) {
                            out.add(track(t, what));
                        }
                    }
                } else {
                    out.add(track(item, what));
                }
            }
            return List.copyOf(out);
        }, List::copyOf);
    }

    private static Track track(Value v, String what) {
        if (v instanceof Str s && !s.quoted() && s.text().equals("auto")) {
            return Track.AUTO;
        }
        if (v instanceof Num n && n.unit().equals("fr")) {
            return Track.fr((float) n.value());
        }
        if (v instanceof Fn f && f.name().equals("minmax")) {
            arity(f, 2, 2);
            return Track.minmax(track(f.args().get(0), what), track(f.args().get(1), what));
        }
        return Track.px(px(v, what));
    }

    /** A grid placement: {@code auto}, {@code 2}, {@code 2 span 3} or {@code span 3}. */
    public static StyleType<GridPlacement> gridPlacement() {
        String what = "a grid placement (auto, start, start span n, or span n)";
        return of(what, v -> {
            List<Value> parts = spaceItems(v);
            if (parts.size() == 1 && parts.get(0) instanceof Str s && s.text().equals("auto")) {
                return GridPlacement.AUTO;
            }
            int start = 0;
            int i = 0;
            if (parts.get(0) instanceof Num) {
                start = positive(parts.get(0), what);
                i = 1;
            }
            int span = 1;
            if (i < parts.size()) {
                if (parts.size() != i + 2 || !(parts.get(i) instanceof Str s) || !s.text().equals("span")) {
                    throw expected(what, v);
                }
                span = positive(parts.get(i + 1), what);
            }
            return new GridPlacement(start, span);
        }, any());
    }

    private static int positive(Value v, String what) {
        if (v instanceof Num n && n.unit().isEmpty() && n.value() >= 1 && n.value() == (int) n.value()) {
            return (int) n.value();
        }
        throw expected(what, v);
    }

    /** Anchors: four fractions or percentages (left top right bottom), or {@code full}. */
    public static StyleType<Anchors> anchors() {
        String what = "anchors (left top right bottom fractions, or full)";
        return of(what, v -> {
            if (v instanceof Str s && s.text().equals("full")) {
                return Anchors.FULL;
            }
            List<Value> parts = spaceItems(v);
            if (parts.size() != 4) {
                throw expected(what, v);
            }
            float[] f = new float[4];
            for (int i = 0; i < 4; i++) {
                if (!(parts.get(i) instanceof Num n) || !(n.unit().isEmpty() || n.unit().equals("%"))) {
                    throw expected(what, v);
                }
                f[i] = (float) (n.unit().isEmpty() ? n.value() : n.value() / 100);
            }
            return new Anchors(f[0], f[1], f[2], f[3]);
        }, any());
    }

    /** Offsets from anchors in px: four values, left top right bottom (as anchors are written). */
    public static StyleType<Insets> offsets() {
        String what = "offsets in px (left top right bottom)";
        return of(what, v -> {
            List<Value> parts = spaceItems(v);
            if (parts.size() != 4) {
                throw expected(what, v);
            }
            return new Insets(px(parts.get(1), what), px(parts.get(2), what), px(parts.get(3), what),
                px(parts.get(0), what));
        }, any());
    }
}
