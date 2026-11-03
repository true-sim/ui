package com.truesim.ui.style.expr;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.Value.Col;
import com.truesim.ui.style.expr.Value.Live;
import com.truesim.ui.style.expr.Value.MapV;
import com.truesim.ui.style.expr.Value.Num;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Colors;
import java.util.ArrayList;
import java.util.List;

/**
 * The language's built-in functions: colours ({@code rgb}, {@code rgba}, {@code hsl}, {@code oklch}, {@code lighten},
 * {@code darken}, {@code saturate}, {@code desaturate}, {@code mix}, {@code alpha}, {@code fade}, {@code contrast}),
 * numbers ({@code percentage}, {@code round}, {@code floor}, {@code ceil}, {@code abs}, {@code min}, {@code max},
 * {@code clamp}, {@code unitless}, {@code unit}), lists and maps ({@code length}, {@code nth}, {@code map-get},
 * {@code map-has-key}, {@code map-keys}, {@code map-values}) and {@code if}, {@code type-of}. Colour functions call
 * {@link Colors}, which Java sheets call too. Any other name is a function value ({@code box(...)}) left to the style
 * types. Amounts take percentages ({@code 8%}) or fractions ({@code 0.08}).
 */
@AllowAllocation("evaluated when a sheet is compiled or a token changes")
public final class Builtins {

    private Builtins() {
    }

    /** The parameter names of built-in {@code name}, for named arguments, or null if it is not a built-in. */
    public static String[] parameters(String name) {
        return switch (name) {
            case "rgb", "rgba" -> new String[] {"red", "green", "blue", "alpha"};
            case "hsl", "hsla" -> new String[] {"hue", "saturation", "lightness", "alpha"};
            case "oklch" -> new String[] {"lightness", "chroma", "hue", "alpha"};
            case "lighten", "darken", "saturate", "desaturate" -> new String[] {"color", "amount"};
            case "mix" -> new String[] {"color1", "color2", "weight"};
            case "alpha", "fade" -> new String[] {"color", "alpha"};
            case "contrast" -> new String[] {"background", "dark", "light"};
            case "percentage", "round", "floor", "ceil", "abs", "unitless", "unit" -> new String[] {"number"};
            case "min", "max" -> new String[] {"a", "b", "c", "d"};
            case "clamp" -> new String[] {"min", "number", "max"};
            case "length" -> new String[] {"list"};
            case "nth" -> new String[] {"list", "n"};
            case "map-get", "map-has-key" -> new String[] {"map", "key"};
            case "map-keys", "map-values" -> new String[] {"map"};
            case "if" -> new String[] {"condition", "if-true", "if-false"};
            case "type-of" -> new String[] {"value"};
            default -> null;
        };
    }

    /** Whether {@code name} is a built-in. */
    public static boolean isBuiltin(String name) {
        return parameters(name) != null;
    }

    /**
     * Calls built-in {@code name} with positional {@code args} (trailing ones may be missing; a named argument left
     * out in the middle is null), or makes a function value if {@code name} is not a built-in.
     *
     * @throws ValueException if the arguments do not fit
     */
    public static Value call(String name, List<Value> args) {
        for (Value arg : args) {
            if (arg instanceof Live) {
                List<TokenExpr> exprs = new ArrayList<>(args.size());
                for (Value v : args) {
                    exprs.add(TokenExpr.of(v == null ? Value.NULL : v));
                }
                return new Live(new TokenExpr.Call(name, exprs));
            }
        }
        String[] params = parameters(name);
        if (params == null) {
            return Value.fn(name, args);
        }
        if (args.size() == 1 && args.get(0) instanceof Value.ListV list && !list.comma() && isColorSpace(name)) {
            args = list.items(); // CSS syntax: oklch(70% 0.1 250), rgb(255 0 0)
        }
        if (args.size() > params.length) {
            throw new ValueException(name + "() takes at most " + params.length + " arguments, got " + args.size());
        }
        Args a = new Args(name, params, args);
        return switch (name) {
            case "rgb", "rgba" -> rgb(a);
            case "hsl", "hsla" -> Value.color(Colors.hsl(a.angle(0), a.fraction(1), a.fraction(2), a.fraction(3, 1)));
            case "oklch" -> Value.color(Colors.oklch(a.fraction(0), a.number(1), a.angle(2), a.fraction(3, 1)));
            case "lighten" -> Value.color(Colors.lighten(a.color(0), a.fraction(1)));
            case "darken" -> Value.color(Colors.darken(a.color(0), a.fraction(1)));
            case "saturate" -> Value.color(Colors.saturate(a.color(0), a.fraction(1)));
            case "desaturate" -> Value.color(Colors.desaturate(a.color(0), a.fraction(1)));
            case "mix" -> Value.color(Colors.mix(a.color(0), a.color(1), a.fraction(2, 0.5)));
            case "alpha" -> a.has(1) ? Value.color(Colors.alpha(a.color(0), a.fraction(1)))
                : Value.num(a.color(0).a());
            case "fade" -> Value.color(Colors.alpha(a.color(0), a.fraction(1)));
            case "contrast" -> Value.color(a.has(1)
                ? Colors.contrast(a.color(0), a.color(1), a.has(2) ? a.color(2) : Color.WHITE)
                : Colors.contrast(a.color(0)));
            case "percentage" -> Value.num(a.unitless(0) * 100, "%");
            case "round" -> a.map(Math.rint(a.num(0).value()));
            case "floor" -> a.map(Math.floor(a.num(0).value()));
            case "ceil" -> a.map(Math.ceil(a.num(0).value()));
            case "abs" -> a.map(Math.abs(a.num(0).value()));
            case "unitless" -> Value.bool(a.num(0).unit().isEmpty());
            case "unit" -> Value.string(a.num(0).unit());
            case "min", "max" -> extreme(a, name.equals("min"));
            case "clamp" -> {
                Value low = Operators.binary("<", a.num(1), a.num(0)).truthy() ? a.num(0) : a.num(1);
                yield Operators.binary(">", low, a.num(2)).truthy() ? a.num(2) : low;
            }
            case "length" -> Value.num(a.get(0).items().size());
            case "nth" -> nth(a);
            case "map-get" -> {
                Value v = a.map(0).entries().get(a.get(1));
                yield v == null ? Value.NULL : v;
            }
            case "map-has-key" -> Value.bool(a.map(0).entries().containsKey(a.get(1)));
            case "map-keys" -> Value.list(new ArrayList<>(a.map(0).entries().keySet()), true);
            case "map-values" -> Value.list(new ArrayList<>(a.map(0).entries().values()), true);
            case "if" -> a.get(0).truthy() ? a.get(1) : a.get(2);
            default -> Value.string(a.get(0).kind()); // type-of
        };
    }

    private static boolean isColorSpace(String name) {
        return switch (name) {
            case "rgb", "rgba", "hsl", "hsla", "oklch" -> true;
            default -> false;
        };
    }

    private static Value rgb(Args a) {
        if (a.get(0) instanceof Col) {
            // rgba(color, alpha), as Sass
            if (a.has(2)) {
                throw new ValueException(a.name + "(color, alpha) takes two arguments");
            }
            return Value.color(Colors.alpha(a.color(0), a.fraction(1, a.color(0).a())));
        }
        return Value.color(Color.srgb((float) a.channel(0), (float) a.channel(1), (float) a.channel(2),
            (float) a.fraction(3, 1)));
    }

    private static Value extreme(Args a, boolean min) {
        Value best = a.num(0);
        for (int i = 1; i < a.args.size(); i++) {
            Value v = a.num(i);
            if (Operators.binary(min ? "<" : ">", v, best).truthy()) {
                best = v;
            }
        }
        return best;
    }

    private static Value nth(Args a) {
        List<Value> items = a.get(0).items();
        double n = a.unitless(1);
        int index = (int) n;
        if (index != n || index == 0 || Math.abs(index) > items.size()) {
            throw new ValueException("nth(): index " + Num.format(n) + " is not in a list of " + items.size());
        }
        return items.get(index > 0 ? index - 1 : items.size() + index);
    }

    /** The arguments of one call, with typed accessors that name the function and parameter in errors. */
    private static final class Args {
        final String name;
        final String[] params;
        final List<Value> args;

        Args(String name, String[] params, List<Value> args) {
            this.name = name;
            this.params = params;
            this.args = args;
        }

        boolean has(int i) {
            return i < args.size() && args.get(i) != null;
        }

        Value get(int i) {
            if (!has(i)) {
                throw new ValueException(name + "() is missing $" + params[i]);
            }
            return args.get(i);
        }

        ValueException wrong(int i, String expected) {
            Value v = get(i);
            return new ValueException(name + "(): $" + params[i] + " must be " + expected + ", got " + v.kind()
                + " " + v.show());
        }

        Color color(int i) {
            if (get(i) instanceof Col c) {
                return c.color();
            }
            throw wrong(i, "a colour");
        }

        Num num(int i) {
            if (get(i) instanceof Num n) {
                return n;
            }
            throw wrong(i, "a number");
        }

        MapV map(int i) {
            if (get(i) instanceof MapV m) {
                return m;
            }
            throw wrong(i, "a map");
        }

        Value map(double value) {
            return Value.num(value, num(0).unit());
        }

        double number(int i) {
            return num(i).value();
        }

        double unitless(int i) {
            Num n = num(i);
            if (!n.unit().isEmpty()) {
                throw wrong(i, "unitless");
            }
            return n.value();
        }

        /** A percentage or a fraction, as a fraction. */
        double fraction(int i) {
            Num n = num(i);
            return switch (n.unit()) {
                case "%" -> n.value() / 100;
                case "" -> n.value();
                default -> throw wrong(i, "a percentage or a fraction");
            };
        }

        double fraction(int i, double fallback) {
            return has(i) ? fraction(i) : fallback;
        }

        /** A colour channel: 0 to 255 unitless, or a percentage; as 0 to 1. */
        double channel(int i) {
            Num n = num(i);
            return switch (n.unit()) {
                case "%" -> n.value() / 100;
                case "" -> n.value() / 255;
                default -> throw wrong(i, "0 to 255 or a percentage");
            };
        }

        /** An angle in degrees (unitless means degrees). */
        double angle(int i) {
            Num n = num(i);
            if (n.unit().isEmpty()) {
                return n.value();
            }
            if (!Operators.compatible(n.unit(), "deg")) {
                throw wrong(i, "an angle");
            }
            return Operators.convert(n.value(), n.unit(), "deg");
        }
    }
}
