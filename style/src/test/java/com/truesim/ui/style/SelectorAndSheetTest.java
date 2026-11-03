package com.truesim.ui.style;

import static com.truesim.ui.style.Selector.sel;
import static com.truesim.ui.style.Styles.COLOR;
import static com.truesim.ui.style.Styles.FONT;
import static com.truesim.ui.style.Styles.FONT_SIZE;
import static com.truesim.ui.style.Styles.PADDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.UiState;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.expr.ValueException;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.FontSpec;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.Insets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SelectorAndSheetTest {

    @Test
    void parsedAndBuiltSelectorsAreEqual() {
        assertThat(Selector.parse("button.glass:hover"))
            .isEqualTo(sel("button").style("glass").state(UiState.HOVER));
        assertThat(Selector.parse("panel.hud > label.title#hud.health[kind=bar]:pressed:focused"))
            .isEqualTo(sel("panel").style("hud").child(sel("label").style("title").id("hud.health")
                .fact("kind", "bar").state(UiState.PRESSED | UiState.FOCUSED)));
        assertThat(Selector.parse("props [annotation=Range]"))
            .isEqualTo(sel("props").descendant(Selector.any().fact("annotation", "Range")));
        assertThat(Selector.parse("* %pill")).isEqualTo(Selector.any().descendant(Selector.any().placeholder("pill")));
        assertThat(Selector.parse("row[group]")).isEqualTo(sel("row").fact("group", null));
        assertThat(Selector.parse("row[ name = \"a b\" ]")).isEqualTo(sel("row").fact("name", "a b"));
        assertThat(Selector.parse("row[v=1.5]")).isEqualTo(sel("row").fact("v", "1.5"));
        assertThat(Selector.parseList(" a , b>c ")).containsExactly(sel("a"), sel("b").child(sel("c")));
        assertThat(sel("*")).isEqualTo(Selector.any());
    }

    @Test
    void selectorsPrintAsTheyAreWritten() {
        for (String text : new String[] {"button.glass:hover", "panel.hud > label#a.b[k=v]:pressed:focused",
            "props [annotation]", "*", "%pill", "a b > c", "x:hover:pressed:focused:disabled:selected:checked"
                + ":expanded:editing"}) {
            assertThat(Selector.parse(text).toString()).isEqualTo(text);
        }
    }

    @Test
    void badSelectorsAreErrorsWithAColumn() {
        assertThatThrownBy(() -> Selector.parse("button:hvr")).isInstanceOf(ValueException.class)
            .hasMessage("bad selector 'button:hvr': unknown state :hvr (did you mean :hover?) at column 8");
        assertThatThrownBy(() -> Selector.parse("a:zzzzzzzz")).hasMessageContaining("unknown state :zzzzzzzz at");
        assertThatThrownBy(() -> Selector.parse("a.b.c")).hasMessageContaining("an element has one style name");
        assertThatThrownBy(() -> Selector.parse("a#b#c")).hasMessageContaining("one id path per compound");
        assertThatThrownBy(() -> Selector.parse("a[b")).hasMessageContaining("expected ']'");
        assertThatThrownBy(() -> Selector.parse("a[b=\"x")).hasMessageContaining("unterminated string");
        assertThatThrownBy(() -> Selector.parse("a[b=]")).hasMessageContaining("expected a fact value");
        assertThatThrownBy(() -> Selector.parse("a > ")).hasMessageContaining("expected a selector");
        assertThatThrownBy(() -> Selector.parse("a ~ b")).hasMessageContaining("unexpected '~'");
        assertThatThrownBy(() -> Selector.parse("a.")).hasMessageContaining("expected a style name");
        assertThatThrownBy(() -> Selector.parse("a$")).hasMessageContaining("unexpected '$'");
        assertThatThrownBy(() -> Selector.parse("a, b")).hasMessageContaining("expected one selector, got 2");
        assertThatThrownBy(() -> Selector.parseList("a b c d,")).hasMessageContaining("expected a selector");
        assertThatThrownBy(() -> sel("a").id("b..c")).hasMessageContaining("empty id segment");
        assertThatThrownBy(() -> new Selector(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void specificityCountsStylesThenIdsThenFactsThenTypesThenCompounds() {
        long style = Selector.parse(".glass").specificity();
        long ids = Selector.parse("#a.b.c").specificity();
        long fact = Selector.parse("[x=1][y=2]").specificity();
        long type = Selector.parse("a b c").specificity();
        assertThat(style).isGreaterThan(ids);
        assertThat(ids).isGreaterThan(fact);
        assertThat(fact).isGreaterThan(type);
        assertThat(Selector.parse("a b").specificity()).isGreaterThan(Selector.parse("a").specificity());
        assertThat(Selector.parse("a:hover:focused").stateCount()).isEqualTo(2);
        assertThat(Selector.parse("a %p").hasPlaceholder()).isTrue();
        assertThat(Selector.parse("a").hasPlaceholder()).isFalse();
    }

    @Test
    void javaSheetsCompareByContent() {
        Color red = Color.rgb(255, 0, 0);
        StyleSheet a = StyleSheet.builder()
            .token("accent", red)
            .token("gap", Value.num(4, "px"))
            .tokenSet("dark", "accent", Value.color(Color.BLACK))
            .rule(sel("label"), sel("panel")).set(FONT_SIZE, 12f).set(PADDING, Insets.of(2)).set(FONT_SIZE, 13f)
            .rule(sel("button").state(UiState.HOVER)).live(COLOR, TokenExpr.token("accent"))
            .with(r -> r.set(FONT, new FontSpec(List.of("Inter"), 14, 600, FontStyle.NORMAL)))
            .build();
        StyleSheet b = StyleSheet.builder()
            .token("accent", Value.color(red))
            .token("gap", Value.num(4, "px"))
            .tokenSet("dark", "accent", Value.color(Color.BLACK))
            .rule(sel("label"), sel("panel")).set(PADDING, Insets.of(2)).set(FONT_SIZE, 13f)
            .rule(sel("button").state(UiState.HOVER)).live(COLOR, TokenExpr.token("accent"))
            .set(Styles.FONT_FAMILY, List.of("Inter")).set(FONT_SIZE, 14f).set(Styles.FONT_WEIGHT, 600)
            .set(Styles.FONT_STYLE, FontStyle.NORMAL)
            .build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.version()).isNotEqualTo(b.version());
        assertThat(a.rules()).hasSize(3);
        assertThat(a.rules().get(0).declarations()).containsExactly(Declaration.of(PADDING, Insets.of(2)),
            Declaration.of(FONT_SIZE, 13f)); // a key set twice keeps the last value, last
        assertThat(a.tokens()).containsEntry("accent", Value.color(red));
        assertThat(a.tokenSets()).containsKey("dark");
        assertThat(a).isNotEqualTo(StyleSheet.empty()).isNotEqualTo("sheet");
        assertThat(a.toString()).contains("label [padding:").contains("tokens {accent=");
        assertThat(a.rules().get(2).declarations().get(0).isLive()).isTrue();
        assertThat(a.rules().get(2).declarations().get(0).toString()).isEqualTo("color: var(--accent)");
        assertThat(a.rules().get(2).toString()).startsWith("button:hover [color: var(--accent)");
        assertThatThrownBy(() -> new Declaration(COLOR, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Declaration.of(FONT_SIZE, 1000f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(InlineStyle.of(Declaration.of(FONT_SIZE, 2f)))
            .isEqualTo(InlineStyle.builder().set(FONT_SIZE, 1f).set(FONT_SIZE, 2f).build());
        assertThat(InlineStyle.builder().set(Styles.SIZE, new com.truesim.ui.style.value.Size(
            com.truesim.ui.style.value.Length.AUTO, com.truesim.ui.style.value.Length.AUTO))
            .live(COLOR, TokenExpr.token("x")).build().declarations()).hasSize(3);
        assertThat(Facts.of("a", "1").matches(new Selector.Fact("a", null))).isTrue();
        assertThat(Facts.of("a", "1").matches(new Selector.Fact("a", "2"))).isFalse();
        assertThatThrownBy(() -> Facts.of("a")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new StyleSheet(List.of(), Map.of(), Map.of())).isEqualTo(StyleSheet.empty());
    }

    @Test
    void tokensLayerCodeOverSetsOverDefaults() {
        Color red = Color.rgb(255, 0, 0);
        StyleSheet sheet = StyleSheet.builder()
            .token("accent", red)
            .token("hover", new Value.Live(TokenExpr.call("lighten", TokenExpr.token("accent"),
                TokenExpr.literal(Value.num(10, "%")))))
            .tokenSet("dark", "accent", Value.color(Color.BLACK))
            .build();
        Tokens t = Tokens.of(sheet);
        assertThat(t.names()).containsExactly("accent", "hover");
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.token("accent")).isEqualTo(Value.color(red));
        assertThat(t.value(t.slot("hover"))).isEqualTo(Value.color(com.truesim.ui.style.value.Colors.lighten(red,
            0.1)));
        long v = t.version();
        t.use("dark");
        assertThat(t.active()).isEqualTo("dark");
        assertThat(t.version()).isGreaterThan(v);
        assertThat(t.changed(t.slot("hover"))).isEqualTo(t.version()); // a token depending on a token changes too
        t.use("dark"); // no change
        assertThat(t.setNames()).containsExactly("dark");
        assertThatThrownBy(() -> t.use("drak")).hasMessage("no token set drak (did you mean dark?)");
        assertThatThrownBy(() -> t.use("zzzzzzzz")).hasMessage("no token set zzzzzzzz");
        assertThatThrownBy(() -> t.token("acent")).hasMessage("unknown token --acent (did you mean --accent?)");
        assertThatThrownBy(() -> t.token("zzzzzzzzzz")).hasMessage("unknown token --zzzzzzzzzz");
        assertThat(t.slot("nothing")).isEqualTo(-1);

        long before = t.version();
        assertThatThrownBy(() -> t.set("accent", Value.num(1, "px"))).isInstanceOf(ValueException.class)
            .hasMessageContaining("token --hover: lighten(): $color must be a colour");
        assertThat(t.version()).isEqualTo(before); // a bad value changes nothing
        t.set("accent", Color.WHITE);
        assertThatThrownBy(() -> t.set("accent", Value.num(2))).isInstanceOf(ValueException.class);
        assertThat(t.token("accent")).isEqualTo(Value.color(Color.WHITE)); // the previous override stays
        t.reset("accent");
        t.reset("accent"); // twice is nothing
        assertThat(t.token("accent")).isEqualTo(Value.color(Color.BLACK));
        t.use(null);
        assertThat(t.token("accent")).isEqualTo(Value.color(red));

        StyleSheet cyclic = StyleSheet.builder()
            .token("a", new Value.Live(TokenExpr.token("b")))
            .token("b", new Value.Live(TokenExpr.token("a")))
            .build();
        assertThatThrownBy(() -> t.load(cyclic)).hasMessageContaining("depends on itself");
        assertThat(t.token("accent")).isEqualTo(Value.color(red)); // the failed load changed nothing
        StyleSheet missing = StyleSheet.builder().token("a", new Value.Live(TokenExpr.token("nope"))).build();
        assertThatThrownBy(() -> Tokens.of(missing)).hasMessage("token --a: unknown token --nope");
        Tokens kept = Tokens.of(sheet);
        kept.use("dark");
        kept.load(StyleSheet.empty());
        assertThat(kept.active()).isNull(); // the new sheet has no such set
    }
}
