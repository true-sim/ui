package com.truesim.ui.style.expr;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A small compiled expression over live tokens: what a value that depends on {@code var(--accent)} (or a
 * {@code $name !live} variable) compiles to, for example {@code lighten(var(--accent), 8%)}. It is evaluated when the
 * sheet is resolved and again when one of its tokens changes, never per frame. Records with value equality, so a Java
 * builder that writes the same expression produces an equal sheet.
 */
@AllowAllocation("evaluated when a token changes, never per frame")
public sealed interface TokenExpr {

    /** Where token values come from. */
    interface Source {
        /**
         * The current value of token {@code name}.
         *
         * @throws ValueException if there is no such token
         */
        Value token(String name);
    }

    /** The expression a value stands for: a {@link Value.Live}'s own, or the value as a literal. */
    static TokenExpr of(Value value) {
        return value instanceof Value.Live live ? live.expression() : new Lit(value);
    }

    /** Token {@code name} (without the leading {@code --}). */
    static TokenExpr token(String name) {
        return new Ref(name);
    }

    /** A constant. */
    static TokenExpr literal(Value value) {
        return new Lit(value);
    }

    /** A call of a built-in function ({@code lighten}) or a function value ({@code box}). */
    static TokenExpr call(String function, TokenExpr... args) {
        return new Call(function, List.of(args));
    }

    /** The value now. */
    Value evaluate(Source tokens);

    /** Adds the names of the tokens it reads to {@code into}. */
    void tokens(Set<String> into);

    /** A token. */
    record Ref(String token) implements TokenExpr {
        public Ref {
            Objects.requireNonNull(token, "token");
        }

        @Override
        public Value evaluate(Source tokens) {
            return tokens.token(token);
        }

        @Override
        public void tokens(Set<String> into) {
            into.add(token);
        }

        @Override
        public String toString() {
            return "var(--" + token + ")";
        }
    }

    /** A constant. */
    record Lit(Value value) implements TokenExpr {
        public Lit {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Value evaluate(Source tokens) {
            return value;
        }

        @Override
        public void tokens(Set<String> into) {
        }

        @Override
        public String toString() {
            return value.show();
        }
    }

    /** A function call: a built-in, or else a function value. */
    record Call(String function, List<TokenExpr> args) implements TokenExpr {
        public Call {
            Objects.requireNonNull(function, "function");
            args = List.copyOf(args);
        }

        @Override
        public Value evaluate(Source tokens) {
            List<Value> values = new ArrayList<>(args.size());
            for (TokenExpr arg : args) {
                values.add(arg.evaluate(tokens));
            }
            return Builtins.call(function, values);
        }

        @Override
        public void tokens(Set<String> into) {
            for (TokenExpr arg : args) {
                arg.tokens(into);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(function).append('(');
            for (int i = 0; i < args.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(args.get(i));
            }
            return sb.append(')').toString();
        }
    }

    /** A binary operator ({@code + - * / % == != < <= > >= and or}). */
    record Op(String operator, TokenExpr left, TokenExpr right) implements TokenExpr {
        public Op {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }

        @Override
        public Value evaluate(Source tokens) {
            return Operators.binary(operator, left.evaluate(tokens), right.evaluate(tokens));
        }

        @Override
        public void tokens(Set<String> into) {
            left.tokens(into);
            right.tokens(into);
        }

        @Override
        public String toString() {
            return "(" + left + " " + operator + " " + right + ")";
        }
    }

    /** A unary operator ({@code -}, {@code +}, {@code not}). */
    record Unary(String operator, TokenExpr operand) implements TokenExpr {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public Value evaluate(Source tokens) {
            return Operators.unary(operator, operand.evaluate(tokens));
        }

        @Override
        public void tokens(Set<String> into) {
            operand.tokens(into);
        }

        @Override
        public String toString() {
            return operator + (operator.equals("not") ? " " : "") + operand;
        }
    }

    /** A list. */
    record Items(List<TokenExpr> items, boolean comma) implements TokenExpr {
        public Items {
            items = List.copyOf(items);
        }

        @Override
        public Value evaluate(Source tokens) {
            List<Value> values = new ArrayList<>(items.size());
            for (TokenExpr item : items) {
                values.add(item.evaluate(tokens));
            }
            return Value.list(values, comma);
        }

        @Override
        public void tokens(Set<String> into) {
            for (TokenExpr item : items) {
                item.tokens(into);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                sb.append(i > 0 ? (comma ? ", " : " ") : "").append(items.get(i));
            }
            return sb.toString();
        }
    }
}
