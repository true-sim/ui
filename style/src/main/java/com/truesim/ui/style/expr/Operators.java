package com.truesim.ui.style.expr;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.style.expr.Value.Bool;
import com.truesim.ui.style.expr.Value.Live;
import com.truesim.ui.style.expr.Value.Num;
import com.truesim.ui.style.expr.Value.Str;

/**
 * The language's operators: arithmetic with units ({@code px}, {@code em}, {@code %}, {@code deg}, {@code ms},
 * {@code s}, ... and unitless), with conversions inside a family ({@code 1s + 200ms}, {@code 0.5turn} in
 * {@code deg}) and errors across families ({@code 1px + 1em}); comparisons; string concatenation; {@code and},
 * {@code or}, {@code not}. An operand that depends on a live token makes the result live.
 */
@AllowAllocation("evaluated when a sheet is compiled or a token changes")
public final class Operators {

    private static final String[] UNITS = {"", "px", "em", "%", "fr", "ms", "s", "deg", "rad", "turn", "grad"};

    private Operators() {
    }

    /** Whether {@code unit} is one the language knows. */
    public static boolean isUnit(String unit) {
        for (String u : UNITS) {
            if (u.equals(unit)) {
                return true;
            }
        }
        return false;
    }

    /** {@code unit}'s family ({@code time}, {@code angle}) or the unit itself, and its size in the family's base. */
    private static String family(String unit) {
        return switch (unit) {
            case "ms", "s" -> "time";
            case "deg", "rad", "turn", "grad" -> "angle";
            default -> unit;
        };
    }

    private static double factor(String unit) {
        return switch (unit) {
            case "s" -> 1000;
            case "rad" -> 180 / Math.PI;
            case "turn" -> 360;
            case "grad" -> 0.9;
            default -> 1;
        };
    }

    /** Whether numbers in {@code a} and {@code b} can be added and compared. */
    public static boolean compatible(String a, String b) {
        return a.isEmpty() || b.isEmpty() || family(a).equals(family(b));
    }

    /**
     * {@code value} in {@code from} converted to {@code to}.
     *
     * @throws ValueException if the units are of different families
     */
    public static double convert(double value, String from, String to) {
        if (from.equals(to) || from.isEmpty() || to.isEmpty()) {
            return value;
        }
        if (!family(from).equals(family(to))) {
            throw new ValueException("incompatible units " + from + " and " + to);
        }
        return value * factor(from) / factor(to);
    }

    /** {@code left op right}. */
    public static Value binary(String op, Value left, Value right) {
        if (left instanceof Live || right instanceof Live) {
            return new Live(new TokenExpr.Op(op, TokenExpr.of(left), TokenExpr.of(right)));
        }
        return switch (op) {
            case "and" -> left.truthy() ? right : left;
            case "or" -> left.truthy() ? left : right;
            case "==" -> Value.bool(same(left, right));
            case "!=" -> Value.bool(!same(left, right));
            case "+" -> plus(left, right);
            case "<", "<=", ">", ">=" -> compare(op, left, right);
            case "-", "*", "/", "%" -> arithmetic(op, number(op, left), number(op, right));
            default -> throw new ValueException("unknown operator " + op);
        };
    }

    /** {@code op operand}: {@code -}, {@code +} or {@code not}. */
    public static Value unary(String op, Value operand) {
        if (operand instanceof Live) {
            return new Live(new TokenExpr.Unary(op, TokenExpr.of(operand)));
        }
        return switch (op) {
            case "not" -> Value.bool(!operand.truthy());
            case "-" -> {
                Num n = number(op, operand);
                yield new Num(-n.value(), n.unit());
            }
            case "+" -> number(op, operand);
            default -> throw new ValueException("unknown operator " + op);
        };
    }

    private static boolean same(Value a, Value b) {
        if (a instanceof Num x && b instanceof Num y) {
            if (x.unit().equals(y.unit())) {
                return x.value() == y.value();
            }
            if (x.unit().isEmpty() || y.unit().isEmpty() || !family(x.unit()).equals(family(y.unit()))) {
                return false;
            }
            return x.value() == convert(y.value(), y.unit(), x.unit());
        }
        return a.equals(b);
    }

    private static Num number(String op, Value v) {
        if (v instanceof Num n) {
            return n;
        }
        throw new ValueException("'" + op + "' needs numbers, got " + v.kind() + " " + v.show());
    }

    private static Value plus(Value left, Value right) {
        if (left instanceof Str s) {
            return new Str(s.text() + text(right), s.quoted());
        }
        if (right instanceof Str s) {
            return new Str(text(left) + s.text(), s.quoted());
        }
        return arithmetic("+", number("+", left), number("+", right));
    }

    private static String text(Value v) {
        return v instanceof Str s ? s.text() : v.show();
    }

    private static Value compare(String op, Value left, Value right) {
        Num a = number(op, left);
        Num b = number(op, right);
        double y = convertForOp(op, a, b);
        double x = a.value();
        boolean result = switch (op) {
            case "<" -> x < y;
            case "<=" -> x <= y;
            case ">" -> x > y;
            default -> x >= y;
        };
        return Value.bool(result);
    }

    /** {@code b}'s value in {@code a}'s unit. */
    private static double convertForOp(String op, Num a, Num b) {
        if (!compatible(a.unit(), b.unit())) {
            throw new ValueException("incompatible units in " + a.show() + " " + op + " " + b.show());
        }
        return convert(b.value(), b.unit(), a.unit().isEmpty() ? b.unit() : a.unit());
    }

    private static Num arithmetic(String op, Num a, Num b) {
        switch (op) {
            case "+", "-", "%" -> {
                String unit = a.unit().isEmpty() ? b.unit() : a.unit();
                double y = convertForOp(op, a, b);
                double x = a.value();
                double r;
                if (op.equals("+")) {
                    r = x + y;
                } else if (op.equals("-")) {
                    r = x - y;
                } else {
                    if (y == 0) {
                        throw new ValueException("modulo by zero: " + a.show() + " % " + b.show());
                    }
                    r = x % y;
                }
                return new Num(r, unit);
            }
            case "*" -> {
                if (!a.unit().isEmpty() && !b.unit().isEmpty()) {
                    throw new ValueException("cannot multiply " + a.show() + " by " + b.show()
                        + ": the result would be in " + a.unit() + "*" + b.unit());
                }
                return new Num(a.value() * b.value(), a.unit().isEmpty() ? b.unit() : a.unit());
            }
            default -> {
                if (b.value() == 0) {
                    throw new ValueException("division by zero: " + a.show() + " / " + b.show());
                }
                if (b.unit().isEmpty()) {
                    return new Num(a.value() / b.value(), a.unit());
                }
                if (a.unit().isEmpty()) {
                    throw new ValueException("cannot divide unitless " + a.show() + " by " + b.show());
                }
                if (!compatible(a.unit(), b.unit())) {
                    throw new ValueException("incompatible units in " + a.show() + " / " + b.show());
                }
                return new Num(a.value() / convert(b.value(), b.unit(), a.unit()), "");
            }
        }
    }
}
