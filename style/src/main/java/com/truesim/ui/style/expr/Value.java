package com.truesim.ui.style.expr;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.value.Color;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A value of the style language before it is typed: what {@code .tss} expressions evaluate to and what live token
 * expressions produce when a token changes. A key's {@code StyleType} turns it into the typed value the key holds.
 * Immutable, with value equality, so sheets built from the same values compare equal.
 *
 * <p>A value that depends on a live token is a {@link Live}: its expression is kept and evaluated again when the token
 * changes. Lists and function values never contain a {@code Live}; {@link #list} and {@link #fn} lift it to the whole
 * value instead, so a consumer checks one place.
 */
@AllowAllocation("values are made when a sheet is compiled or a token changes, never per frame")
public sealed interface Value {

    /** The null value. */
    Value NULL = new Null();
    /** True. */
    Value TRUE = new Bool(true);
    /** False. */
    Value FALSE = new Bool(false);

    /** A unitless number. */
    static Num num(double value) {
        return new Num(value, "");
    }

    /** A number with a unit ({@code px}, {@code %}, {@code ms}, ... or "" for none). */
    static Num num(double value, String unit) {
        return new Num(value, unit);
    }

    /** An unquoted string (an identifier: {@code center}, {@code ease-out}). */
    static Str ident(String text) {
        return new Str(text, false);
    }

    /** A quoted string. */
    static Str string(String text) {
        return new Str(text, true);
    }

    /** A colour. */
    static Col color(Color color) {
        return new Col(color);
    }

    /** True or false. */
    static Value bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** A list of {@code items}, comma or space separated; a {@link Live} if any item is. */
    static Value list(List<Value> items, boolean comma) {
        for (Value item : items) {
            if (item instanceof Live) {
                List<TokenExpr> exprs = new ArrayList<>(items.size());
                for (Value v : items) {
                    exprs.add(TokenExpr.of(v));
                }
                return new Live(new TokenExpr.Items(exprs, comma));
            }
        }
        return new ListV(items, comma);
    }

    /** A function value that is not a built-in ({@code box(...)}, {@code minmax(...)}); a {@link Live} if an argument is. */
    static Value fn(String name, List<Value> args) {
        for (Value arg : args) {
            if (arg instanceof Live) {
                List<TokenExpr> exprs = new ArrayList<>(args.size());
                for (Value v : args) {
                    exprs.add(TokenExpr.of(v));
                }
                return new Live(new TokenExpr.Call(name, exprs));
            }
        }
        return new Fn(name, args);
    }

    /** Whether conditions treat it as true: everything but {@code false} and {@code null}. */
    default boolean truthy() {
        return true;
    }

    /** The name of its kind, for messages: {@code number}, {@code colour}, ... */
    String kind();

    /** The value as the language writes it, used by interpolation and messages. */
    String show();

    /** As a list of items: a list's items, or this alone. */
    default List<Value> items() {
        return List.of(this);
    }

    /** A number with a unit ("" for none). */
    record Num(double value, String unit) implements Value {
        public Num {
            Objects.requireNonNull(unit, "unit");
        }

        @Override
        public String kind() {
            return "number";
        }

        @Override
        public String show() {
            return format(value) + unit;
        }

        /** {@code value} without trailing zeros, at most six decimals. */
        public static String format(double value) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return Long.toString((long) value);
            }
            return BigDecimal.valueOf(Math.round(value * 1e6) / 1e6).stripTrailingZeros().toPlainString();
        }
    }

    /** A string; unquoted ones are identifiers. Equality ignores the quotes, as in Sass. */
    record Str(String text, boolean quoted) implements Value {
        public Str {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String kind() {
            return "string";
        }

        @Override
        public String show() {
            return quoted ? "\"" + text + "\"" : text;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Str s && s.text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }
    }

    /** A colour. */
    record Col(Color color) implements Value {
        public Col {
            Objects.requireNonNull(color, "color");
        }

        @Override
        public String kind() {
            return "colour";
        }

        @Override
        public String show() {
            return color.toString();
        }
    }

    /** True or false. */
    record Bool(boolean value) implements Value {
        @Override
        public boolean truthy() {
            return value;
        }

        @Override
        public String kind() {
            return "boolean";
        }

        @Override
        public String show() {
            return value ? "true" : "false";
        }
    }

    /** Nothing. */
    record Null() implements Value {
        @Override
        public boolean truthy() {
            return false;
        }

        @Override
        public String kind() {
            return "null";
        }

        @Override
        public String show() {
            return "null";
        }

        @Override
        public List<Value> items() {
            return List.of();
        }
    }

    /** A list, comma or space separated. */
    record ListV(List<Value> items, boolean comma) implements Value {
        public ListV {
            items = List.copyOf(items);
        }

        @Override
        public String kind() {
            return "list";
        }

        @Override
        public String show() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(comma ? ", " : " ");
                }
                Value item = items.get(i);
                boolean nested = item instanceof ListV l && (l.comma || !comma);
                sb.append(nested ? "(" + item.show() + ")" : item.show());
            }
            return sb.toString();
        }
    }

    /** A map, in insertion order; equality ignores the order. */
    record MapV(Map<Value, Value> entries) implements Value {
        public MapV {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        @Override
        public String kind() {
            return "map";
        }

        @Override
        public String show() {
            StringBuilder sb = new StringBuilder("(");
            for (Map.Entry<Value, Value> e : entries.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(e.getKey().show()).append(": ").append(e.getValue().show());
            }
            return sb.append(')').toString();
        }

        @Override
        public List<Value> items() {
            List<Value> pairs = new ArrayList<>();
            for (Map.Entry<Value, Value> e : entries.entrySet()) {
                pairs.add(new ListV(List.of(e.getKey(), e.getValue()), false));
            }
            return pairs;
        }
    }

    /** A function value the language does not evaluate itself ({@code box(#fff)}): style types read it. */
    record Fn(String name, List<Value> args) implements Value {
        public Fn {
            Objects.requireNonNull(name, "name");
            args = List.copyOf(args);
        }

        @Override
        public String kind() {
            return name + "()";
        }

        @Override
        public String show() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args.get(i).show());
            }
            return sb.append(')').toString();
        }
    }

    /** A value depending on live tokens: an expression evaluated again whenever one of them changes. */
    record Live(TokenExpr expression) implements Value {
        public Live {
            Objects.requireNonNull(expression, "expression");
        }

        @Override
        public String kind() {
            return "live value";
        }

        @Override
        public String show() {
            return expression.toString();
        }
    }
}
