package com.truesim.ui.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.style.value.Length;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the UI debugger reads: each resolved value's rule and {@code file:line}, and the text scale. */
class StyleExplainTest {

    private static final String SHEET = """
        panel { gap: 4px; padding: 2px; }
        panel.card {
          gap: 8px;
        }
        panel:hover { opacity: 0.5; }
        label { font-size: 12px; }
        """;

    @AfterEach
    void reset() {
        StyleResolver.TEXT_SCALE.set(1);
    }

    @Test
    void explainNamesTheWinningRuleAndItsLine() {
        UiStore store = new UiStore();
        StyleResolver r = new StyleResolver(store, Tss.compileString("explain.tss", SHEET));
        Element card = store.create("panel", null).style("card");
        store.root().add(card);
        r.inline(card.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(100)).build());
        card.state(UiState.HOVER, true);
        List<StyleResolver.Origin> origins = r.explain(card.slot());
        assertThat(origins).extracting(o -> o.key().name()).containsExactly("width", "opacity", "gap", "padding");
        assertThat(origins.get(0).source()).isEqualTo("inline");
        assertThat(origins.get(0).selector()).isEqualTo("(inline)");
        assertThat(origins.get(1).source()).isEqualTo("explain.tss:5");
        assertThat(origins.get(2).source()).isEqualTo("explain.tss:2");
        assertThat(origins.get(2).value()).isEqualTo(8f);
        assertThat(origins.get(3).source()).isEqualTo("explain.tss:1");
        assertThat(origins.get(2).selector()).contains("card");
        assertThat(r.factsOf(card.slot())).isEqualTo(Facts.NONE);
        r.facts(card.slot(), Facts.of("kind", "x"));
        assertThat(r.factsOf(card.slot())).isEqualTo(Facts.of("kind", "x"));
        assertThat(r.factsOf(9999)).isEqualTo(Facts.NONE);
    }

    @Test
    void javaRulesAreNamedJavaAndEqualTssRules() {
        StyleSheet java = StyleSheet.builder().rule(Selector.parse("panel")).set(Styles.GAP, 4f).build();
        UiStore store = new UiStore();
        StyleResolver r = new StyleResolver(store, java);
        Element panel = store.create("panel", null);
        store.root().add(panel);
        assertThat(r.explain(panel.slot()).getFirst().source()).isEqualTo("java");
        Rule fromTss = Tss.compileString("x.tss", "panel { gap: 4px; }").rules().getFirst();
        assertThat(fromTss.source()).isEqualTo("x.tss:1");
        assertThat(fromTss).isEqualTo(java.rules().getFirst()).hasSameHashCodeAs(java.rules().getFirst());
        assertThat(fromTss).isNotEqualTo("panel");
    }

    @Test
    void theTextScaleScalesFontSizesOnce() {
        UiStore store = new UiStore();
        StyleResolver r = new StyleResolver(store, Tss.compileString("scale.tss", SHEET));
        Element label = store.create("label", null);
        Element panel = store.create("panel", null);
        store.root().add(label).add(panel);
        assertThat(r.styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(12f);
        StyleResolver.TEXT_SCALE.set(1.5f);
        r.update();
        assertThat(r.styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(18f);
        assertThat(r.styleOf(panel.slot()).getFloat(Styles.FONT_SIZE))
            .isEqualTo(Styles.FONT_SIZE.defaultValue() * 1.5f);
        assertThat(r.update()).isFalse(); // once
        StyleResolver.TEXT_SCALE.set(1);
        r.update();
        assertThat(r.styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(12f);
    }

    @Test
    void liveFontSizesAreScaledToo() {
        String sheet = ":root { --size: 10px; }\nlabel { font-size: var(--size); }\n";
        UiStore store = new UiStore();
        StyleResolver r = new StyleResolver(store, Tss.compileString("live.tss", sheet));
        Element label = store.create("label", null);
        store.root().add(label);
        StyleResolver.TEXT_SCALE.set(2);
        r.update();
        assertThat(r.styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(20f);
        r.tokens().set("size", com.truesim.ui.style.expr.Value.num(11, "px"));
        r.update();
        assertThat(r.styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(22f);
    }
}
