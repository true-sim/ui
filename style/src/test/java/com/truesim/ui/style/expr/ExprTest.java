package com.truesim.ui.style.expr;

import static com.truesim.ui.style.expr.Value.num;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.ui.style.expr.Value.Num;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Colors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExprTest {

    private static Value op(String op, Value a, Value b) {
        return Operators.binary(op, a, b);
    }

    private static Value call(String name, Value... args) {
        return Builtins.call(name, Arrays.asList(args));
    }

    private static final Value RED = Value.color(Color.rgb(255, 0, 0));
    private static final Value BLUE = Value.color(Color.rgb(0, 0, 255));

    @Test
    void arithmeticKeepsAndConvertsUnits() {
        assertThat(op("+", num(1, "px"), num(2, "px"))).isEqualTo(num(3, "px"));
        assertThat(op("+", num(1, "px"), num(2))).isEqualTo(num(3, "px"));
        assertThat(op("+", num(1), num(2, "em"))).isEqualTo(num(3, "em"));
        assertThat(op("+", num(1, "s"), num(200, "ms"))).isEqualTo(num(1.2, "s"));
        assertThat(op("-", num(200, "ms"), num(0.1, "s"))).isEqualTo(num(100, "ms"));
        assertThat(((Num) op("+", num(90, "deg"), num(0.25, "turn"))).value()).isEqualTo(180);
        assertThat(((Num) op("+", num(0, "deg"), num(Math.PI, "rad"))).value()).isCloseTo(180, within(1e-9));
        assertThat(((Num) op("+", num(0, "deg"), num(100, "grad"))).value()).isCloseTo(90, within(1e-9));
        assertThat(op("*", num(2, "px"), num(3))).isEqualTo(num(6, "px"));
        assertThat(op("*", num(2), num(3, "%"))).isEqualTo(num(6, "%"));
        assertThat(op("/", num(6, "px"), num(3))).isEqualTo(num(2, "px"));
        assertThat(op("/", num(6, "px"), num(3, "px"))).isEqualTo(num(2));
        assertThat(op("/", num(1, "s"), num(500, "ms"))).isEqualTo(num(2));
        assertThat(op("%", num(7, "px"), num(4))).isEqualTo(num(3, "px"));
        assertThat(Operators.unary("-", num(2, "px"))).isEqualTo(num(-2, "px"));
        assertThat(Operators.unary("+", num(2, "px"))).isEqualTo(num(2, "px"));
        assertThat(Operators.isUnit("fr")).isTrue();
        assertThat(Operators.isUnit("pt")).isFalse();
        assertThat(Operators.convert(1, "s", "ms")).isEqualTo(1000);
        assertThat(Operators.convert(5, "", "px")).isEqualTo(5);
    }

    @Test
    void unitErrorsSayWhatWentWrong() {
        assertThatThrownBy(() -> op("+", num(1, "px"), num(1, "em"))).isInstanceOf(ValueException.class)
            .hasMessageContaining("incompatible units in 1px + 1em");
        assertThatThrownBy(() -> op("*", num(1, "px"), num(1, "px")))
            .hasMessageContaining("cannot multiply 1px by 1px");
        assertThatThrownBy(() -> op("/", num(1), num(1, "px"))).hasMessageContaining("cannot divide unitless 1");
        assertThatThrownBy(() -> op("/", num(1, "px"), num(0))).hasMessageContaining("division by zero");
        assertThatThrownBy(() -> op("/", num(1, "px"), num(2, "em"))).hasMessageContaining("incompatible units");
        assertThatThrownBy(() -> op("%", num(1, "px"), num(0))).hasMessageContaining("modulo by zero");
        assertThatThrownBy(() -> op("-", Value.ident("a"), num(1))).hasMessageContaining("'-' needs numbers");
        assertThatThrownBy(() -> op("<", num(1, "px"), num(1, "s"))).hasMessageContaining("incompatible");
        assertThatThrownBy(() -> op("^", num(1), num(1))).hasMessageContaining("unknown operator ^");
        assertThatThrownBy(() -> Operators.unary("~", num(1))).hasMessageContaining("unknown operator ~");
        assertThatThrownBy(() -> Operators.convert(1, "px", "s")).hasMessageContaining("incompatible units px and s");
    }

    @Test
    void comparisonsLogicAndStrings() {
        assertThat(op("<", num(1, "s"), num(1500, "ms"))).isEqualTo(Value.TRUE);
        assertThat(op("<=", num(2), num(2))).isEqualTo(Value.TRUE);
        assertThat(op(">", num(2, "px"), num(3))).isEqualTo(Value.FALSE);
        assertThat(op(">=", num(3), num(3, "px"))).isEqualTo(Value.TRUE);
        assertThat(op("==", num(1, "s"), num(1000, "ms"))).isEqualTo(Value.TRUE);
        assertThat(op("==", num(1, "px"), num(1))).isEqualTo(Value.FALSE);
        assertThat(op("==", num(1, "px"), num(1, "s"))).isEqualTo(Value.FALSE);
        assertThat(op("==", num(1, "px"), num(1, "px"))).isEqualTo(Value.TRUE);
        assertThat(op("!=", Value.ident("a"), Value.string("a"))).isEqualTo(Value.FALSE);
        assertThat(op("and", Value.TRUE, num(1))).isEqualTo(num(1));
        assertThat(op("and", Value.NULL, num(1))).isEqualTo(Value.NULL);
        assertThat(op("or", Value.FALSE, num(1))).isEqualTo(num(1));
        assertThat(op("or", num(2), num(1))).isEqualTo(num(2));
        assertThat(Operators.unary("not", Value.FALSE)).isEqualTo(Value.TRUE);
        assertThat(op("+", Value.string("icons/"), Value.ident("heart"))).isEqualTo(Value.string("icons/heart"));
        assertThat(op("+", num(2, "px"), Value.string("x"))).isEqualTo(Value.string("2pxx"));
        assertThat(((Value.Str) op("+", Value.string("a"), num(1))).quoted()).isTrue();
    }

    @Test
    void valuesShowAsTheLanguageWritesThem() {
        assertThat(num(1.5, "px").show()).isEqualTo("1.5px");
        assertThat(num(2).show()).isEqualTo("2");
        assertThat(num(1.0 / 3).show()).isEqualTo("0.333333");
        assertThat(Value.string("a").show()).isEqualTo("\"a\"");
        assertThat(Value.ident("a").show()).isEqualTo("a");
        assertThat(Value.list(List.of(num(1), Value.list(List.of(num(2), num(3)), false)), true).show())
            .isEqualTo("1, 2 3");
        assertThat(Value.list(List.of(num(1), Value.list(List.of(num(2), num(3)), true)), false).show())
            .isEqualTo("1 (2, 3)");
        Map<Value, Value> m = new LinkedHashMap<>();
        m.put(Value.ident("a"), num(1));
        m.put(Value.ident("b"), RED);
        Value map = new Value.MapV(m);
        assertThat(map.show()).isEqualTo("(a: 1, b: #ff0000)");
        assertThat(map.items()).hasSize(2);
        assertThat(map.kind()).isEqualTo("map");
        assertThat(Value.fn("box", List.of(RED)).show()).isEqualTo("box(#ff0000)");
        assertThat(Value.fn("box", List.of(RED, num(1))).kind()).isEqualTo("box()");
        assertThat(Value.NULL.show()).isEqualTo("null");
        assertThat(Value.NULL.items()).isEmpty();
        assertThat(Value.NULL.truthy()).isFalse();
        assertThat(Value.TRUE.show()).isEqualTo("true");
        assertThat(Value.FALSE.show()).isEqualTo("false");
        assertThat(Value.FALSE.truthy()).isFalse();
        assertThat(num(1).truthy()).isTrue();
        assertThat(Value.bool(true).kind()).isEqualTo("boolean");
        assertThat(Value.NULL.kind()).isEqualTo("null");
        assertThat(RED.kind()).isEqualTo("colour");
        assertThat(Value.ident("a").kind()).isEqualTo("string");
        assertThat(Value.list(List.of(), true).kind()).isEqualTo("list");
        assertThat(Value.ident("a").hashCode()).isEqualTo(Value.string("a").hashCode());
    }

    @Test
    void colourBuiltinsMatchTheJavaFunctions() {
        Color red = Color.rgb(255, 0, 0);
        assertThat(call("rgb", num(255), num(0), num(0))).isEqualTo(RED);
        assertThat(call("rgba", num(100, "%"), num(0), num(0), num(50, "%")))
            .isEqualTo(Value.color(red.withAlpha(0.5f)));
        assertThat(call("rgba", RED, num(0.5))).isEqualTo(Value.color(red.withAlpha(0.5f)));
        assertThat(call("rgba", RED)).isEqualTo(RED);
        assertThat(call("hsl", num(0), num(100, "%"), num(50, "%"))).isEqualTo(Value.color(Colors.hsl(0, 1, 0.5, 1)));
        assertThat(call("hsla", num(0.5, "turn"), num(1), num(0.5), num(0.5)))
            .isEqualTo(Value.color(Colors.hsl(180, 1, 0.5, 0.5)));
        assertThat(call("oklch", num(70, "%"), num(0.1), num(250, "deg")))
            .isEqualTo(Value.color(Colors.oklch(0.7, 0.1, 250, 1)));
        assertThat(call("lighten", RED, num(8, "%"))).isEqualTo(Value.color(Colors.lighten(red, 0.08)));
        assertThat(call("darken", RED, num(0.1))).isEqualTo(Value.color(Colors.darken(red, 0.1)));
        assertThat(call("saturate", RED, num(10, "%"))).isEqualTo(Value.color(Colors.saturate(red, 0.1)));
        assertThat(call("desaturate", RED, num(10, "%"))).isEqualTo(Value.color(Colors.desaturate(red, 0.1)));
        assertThat(call("mix", RED, BLUE)).isEqualTo(Value.color(Colors.mix(red, Color.rgb(0, 0, 255), 0.5)));
        assertThat(call("mix", RED, BLUE, num(30, "%")))
            .isEqualTo(Value.color(Colors.mix(red, Color.rgb(0, 0, 255), 0.3)));
        assertThat(call("alpha", RED)).isEqualTo(num(1));
        assertThat(call("alpha", RED, num(0.25))).isEqualTo(Value.color(red.withAlpha(0.25f)));
        assertThat(call("fade", RED, num(25, "%"))).isEqualTo(Value.color(red.withAlpha(0.25f)));
        assertThat(call("contrast", RED)).isEqualTo(Value.color(Colors.contrast(red)));
        assertThat(call("contrast", Value.color(Color.WHITE), BLUE)).isEqualTo(BLUE);
        assertThat(call("contrast", Value.color(Color.BLACK), BLUE, RED)).isEqualTo(RED);
    }

    @Test
    void numberListAndMapBuiltins() {
        assertThat(call("percentage", num(0.5))).isEqualTo(num(50, "%"));
        assertThat(call("round", num(2.5, "px"))).isEqualTo(num(2, "px"));
        assertThat(call("floor", num(2.7))).isEqualTo(num(2));
        assertThat(call("ceil", num(2.1))).isEqualTo(num(3));
        assertThat(call("abs", num(-2, "em"))).isEqualTo(num(2, "em"));
        assertThat(call("min", num(2, "px"), num(1, "px"), num(3))).isEqualTo(num(1, "px"));
        assertThat(call("max", num(1, "s"), num(1500, "ms"))).isEqualTo(num(1500, "ms"));
        assertThat(call("clamp", num(0), num(5), num(3))).isEqualTo(num(3));
        assertThat(call("clamp", num(0), num(-1), num(3))).isEqualTo(num(0));
        assertThat(call("clamp", num(0), num(2), num(3))).isEqualTo(num(2));
        assertThat(call("unitless", num(1))).isEqualTo(Value.TRUE);
        assertThat(call("unit", num(1, "px"))).isEqualTo(Value.string("px"));
        Value list = Value.list(List.of(num(1), num(2), num(3)), false);
        assertThat(call("length", list)).isEqualTo(num(3));
        assertThat(call("length", num(3))).isEqualTo(num(1));
        assertThat(call("nth", list, num(1))).isEqualTo(num(1));
        assertThat(call("nth", list, num(-1))).isEqualTo(num(3));
        Map<Value, Value> m = new LinkedHashMap<>();
        m.put(Value.ident("a"), num(1));
        Value map = new Value.MapV(m);
        assertThat(call("map-get", map, Value.ident("a"))).isEqualTo(num(1));
        assertThat(call("map-get", map, Value.ident("b"))).isEqualTo(Value.NULL);
        assertThat(call("map-has-key", map, Value.string("a"))).isEqualTo(Value.TRUE);
        assertThat(call("map-keys", map)).isEqualTo(Value.list(List.of(Value.ident("a")), true));
        assertThat(call("map-values", map)).isEqualTo(Value.list(List.of(num(1)), true));
        assertThat(call("if", Value.TRUE, num(1), num(2))).isEqualTo(num(1));
        assertThat(call("if", Value.NULL, num(1), num(2))).isEqualTo(num(2));
        assertThat(call("type-of", RED)).isEqualTo(Value.string("colour"));
        assertThat(call("box", RED)).isEqualTo(Value.fn("box", List.of(RED)));
        assertThat(Builtins.isBuiltin("mix")).isTrue();
        assertThat(Builtins.isBuiltin("box")).isFalse();
        assertThat(Builtins.parameters("mix")).containsExactly("color1", "color2", "weight");
    }

    @Test
    void builtinErrorsNameTheFunctionAndParameter() {
        assertThatThrownBy(() -> call("lighten", num(1), num(1)))
            .hasMessage("lighten(): $color must be a colour, got number 1");
        assertThatThrownBy(() -> call("lighten", RED)).hasMessage("lighten() is missing $amount");
        assertThatThrownBy(() -> call("lighten", RED, num(1, "px")))
            .hasMessageContaining("$amount must be a percentage or a fraction");
        assertThatThrownBy(() -> call("rgb", num(1, "px"), num(0), num(0))).hasMessageContaining("0 to 255");
        assertThatThrownBy(() -> call("rgba", RED, num(1), num(1))).hasMessageContaining("takes two arguments");
        assertThatThrownBy(() -> call("hsl", num(1, "px"), num(0), num(0))).hasMessageContaining("an angle");
        assertThatThrownBy(() -> call("percentage", num(1, "px"))).hasMessageContaining("unitless");
        assertThatThrownBy(() -> call("map-get", num(1), num(1))).hasMessageContaining("a map");
        assertThatThrownBy(() -> call("round", RED)).hasMessageContaining("a number");
        assertThatThrownBy(() -> call("nth", num(1), num(2))).hasMessageContaining("index 2 is not in a list of 1");
        assertThatThrownBy(() -> call("nth", num(1), num(0))).hasMessageContaining("index 0");
        assertThatThrownBy(() -> call("nth", num(1), num(1.5))).hasMessageContaining("index 1.5");
        assertThatThrownBy(() -> call("unit", num(1), num(2))).hasMessageContaining("at most 1 arguments");
        List<Value> withGap = new ArrayList<>();
        withGap.add(RED);
        withGap.add(null);
        withGap.add(num(1));
        assertThatThrownBy(() -> Builtins.call("mix", withGap)).hasMessageContaining("missing $color2");
    }

    @Test
    void liveOperandsMakeLiveExpressions() {
        Value accent = new Value.Live(TokenExpr.token("accent"));
        Value lighter = call("lighten", accent, num(8, "%"));
        assertThat(lighter).isEqualTo(new Value.Live(TokenExpr.call("lighten", TokenExpr.token("accent"),
            TokenExpr.literal(num(8, "%")))));
        Value box = Value.fn("box", List.of(lighter));
        Value list = Value.list(List.of(num(1, "px"), accent), false);
        Value sum = op("+", accent, num(1));
        Value neg = Operators.unary("-", accent);
        Value notted = Operators.unary("not", accent);
        Map<String, Value> tokens = new LinkedHashMap<>();
        tokens.put("accent", RED);
        TokenExpr.Source source = name -> {
            Value v = tokens.get(name);
            if (v == null) {
                throw new ValueException("no " + name);
            }
            return v;
        };
        Color red = Color.rgb(255, 0, 0);
        assertThat(((Value.Live) box).expression().evaluate(source))
            .isEqualTo(Value.fn("box", List.of(Value.color(Colors.lighten(red, 0.08)))));
        assertThat(((Value.Live) list).expression().evaluate(source))
            .isEqualTo(Value.list(List.of(num(1, "px"), RED), false));
        tokens.put("accent", num(2));
        assertThat(((Value.Live) sum).expression().evaluate(source)).isEqualTo(num(3));
        assertThat(((Value.Live) neg).expression().evaluate(source)).isEqualTo(num(-2));
        assertThat(((Value.Live) notted).expression().evaluate(source)).isEqualTo(Value.FALSE);
        Set<String> names = new HashSet<>();
        ((Value.Live) box).expression().tokens(names);
        ((Value.Live) sum).expression().tokens(names);
        assertThat(names).containsExactly("accent");
        assertThat(box.show()).isEqualTo("box(lighten(var(--accent), 8%))");
        assertThat(sum.show()).isEqualTo("(var(--accent) + 1)");
        assertThat(neg.show()).isEqualTo("-var(--accent)");
        assertThat(notted.show()).isEqualTo("not var(--accent)");
        assertThat(list.show()).isEqualTo("1px var(--accent)");
        assertThat(Value.list(List.of(num(1), accent), true).show()).isEqualTo("1, var(--accent)");
        assertThat(box.kind()).isEqualTo("live value");
        assertThat(TokenExpr.of(num(1))).isEqualTo(TokenExpr.literal(num(1)));
        assertThatThrownBy(() -> TokenExpr.token("missing").evaluate(source)).hasMessage("no missing");
    }
}
