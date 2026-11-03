package com.truesim.ui.style;

import static com.truesim.ui.style.Selector.sel;
import static com.truesim.ui.style.Styles.BACKGROUND;
import static com.truesim.ui.style.Styles.COLOR;
import static com.truesim.ui.style.Styles.FONT_SIZE;
import static com.truesim.ui.style.Styles.FONT_WEIGHT;
import static com.truesim.ui.style.Styles.OPACITY;
import static com.truesim.ui.style.Styles.PADDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.expr.TokenExpr;
import com.truesim.ui.style.expr.Value;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Colors;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Paint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StyleResolverTest {

    private static final Color RED = Color.rgb(255, 0, 0);
    private static final Color GREEN = Color.rgb(0, 255, 0);
    private static final Color BLUE = Color.rgb(0, 0, 255);

    private static float size(StyleResolver r, Element e) {
        return r.style(r.resolve(e.slot())).getFloat(FONT_SIZE);
    }

    /** One rule per selector, each setting font-size to its position, so the winner is visible. */
    private static StyleSheet ranked(String... selectors) {
        StyleSheet.Builder b = StyleSheet.builder();
        for (int i = 0; i < selectors.length; i++) {
            b.rule(Selector.parse(selectors[i])).set(FONT_SIZE, (float) (i + 1));
        }
        return b.build();
    }

    @Test
    void lemursOrderStyleRulesBeatRootRulesAndLongerIdsBeatShorter() {
        UiStore store = new UiStore();
        Element slider = store.create("slider", "slider");
        Element thumb = store.create("panel", "thumb");
        Element button = store.create("button", "button").style("glass");
        store.root().add(slider);
        slider.add(thumb);
        thumb.add(button);

        // root style default < root style id match < style default < style id match < longer id in the style
        String[] order = {"*", "button", "#button", "#thumb.button", ".glass", "button.glass", ".glass#button",
            ".glass#thumb.button", ".glass#slider.thumb.button"};
        for (int winner = 0; winner < order.length; winner++) {
            String[] some = new String[winner + 1];
            System.arraycopy(order, 0, some, 0, winner + 1);
            // reversed source order, so specificity, not position, decides
            String[] reversed = new String[some.length];
            for (int i = 0; i < some.length; i++) {
                reversed[i] = some[some.length - 1 - i];
            }
            StyleResolver r = new StyleResolver(store, ranked(reversed));
            assertThat(size(r, button)).as("winner %s", order[winner]).isEqualTo(1f);
        }
    }

    @Test
    void containmentBeatsAPlainSelectorAndStatesBeatSpecificity() {
        UiStore store = new UiStore();
        Element panel = store.create("panel", "hud");
        Element label = store.create("label", "name");
        store.root().add(panel);
        panel.add(label);
        StyleResolver r = new StyleResolver(store, ranked("label", "panel label", ":hover", "label:hover:focused",
            "panel > label:hover"));
        assertThat(size(r, label)).isEqualTo(2f); // containment wins over the plain type
        label.state(UiState.HOVER, true);
        assertThat(size(r, label)).isEqualTo(5f); // any state rule beats rules without states; then specificity
        label.state(UiState.FOCUSED, true);
        assertThat(size(r, label)).isEqualTo(5f); // panel > label:hover is more specific than label:hover:focused
        StyleResolver r2 = new StyleResolver(store, ranked("label:hover", "label:hover:focused", ":hover"));
        assertThat(size(r2, label)).isEqualTo(2f); // equal specificity: more states win
        StyleResolver r3 = new StyleResolver(store, ranked("label", "label"));
        assertThat(size(r3, label)).isEqualTo(2f); // equal: the later rule wins
    }

    @Test
    void inlineValuesBeatEverythingAndMergeAttributeByAttribute() {
        UiStore store = new UiStore();
        Element label = store.create("label", null);
        store.root().add(label);
        StyleSheet sheet = StyleSheet.builder()
            .rule(sel("label")).set(FONT_SIZE, 10f).set(COLOR, RED)
            .rule(sel("label").state(UiState.HOVER)).set(FONT_SIZE, 20f)
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        label.state(UiState.HOVER, true);
        ResolvedStyle s = r.style(r.resolve(label.slot()));
        assertThat(s.getFloat(FONT_SIZE)).isEqualTo(20f);
        assertThat(s.get(COLOR)).isEqualTo(RED); // from the plain rule: first match per attribute
        assertThat(s.isSet(PADDING)).isFalse();
        assertThat(s.get(PADDING)).isEqualTo(Insets.NONE);

        r.inline(label.slot(), InlineStyle.builder().set(FONT_SIZE, 30f).build());
        assertThat(store.dirty(label.slot()) & UiStore.DIRTY_STYLE).isNotZero();
        s = r.style(r.resolve(label.slot()));
        assertThat(s.getFloat(FONT_SIZE)).isEqualTo(30f);
        assertThat(s.get(COLOR)).isEqualTo(RED);
        assertThat(s.toString()).contains("font-size: 30.0");
    }

    @Test
    void styleNamesInheritFromTheParent() {
        UiStore store = new UiStore();
        Element hud = store.create("panel", null).style("hud");
        Element card = store.create("panel", null);
        Element title = store.create("label", null).style("title");
        Element plain = store.create("label", null);
        store.root().add(hud);
        hud.add(card);
        card.add(title).add(plain);
        StyleSheet sheet = StyleSheet.builder()
            .rule(sel("label").style("hud")).set(FONT_SIZE, 12f)
            .rule(sel("label").style("title")).set(FONT_SIZE, 20f)
            .rule(sel("panel").style("hud").descendant(sel("label"))).set(COLOR, GREEN)
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        assertThat(size(r, plain)).isEqualTo(12f);
        assertThat(size(r, title)).isEqualTo(20f);
        assertThat(r.styleOf(title.slot()).get(COLOR)).isEqualTo(GREEN);
    }

    @Test
    void childCombinatorsAndFactsAndTailMatching() {
        UiStore store = new UiStore();
        Element props = store.create("props", "inspector");
        Element group = store.create("panel", null);
        Element row = store.create("row", "roughness");
        store.root().add(props);
        props.add(group);
        group.add(row);
        StyleSheet sheet = StyleSheet.builder()
            .rule(Selector.parse("props > row")).set(FONT_SIZE, 1f)
            .rule(Selector.parse("props row[annotation=Range]")).set(FONT_SIZE, 2f)
            .rule(Selector.parse("row[group]")).set(COLOR, BLUE)
            .rule(Selector.parse("#inspector.roughness")).set(FONT_WEIGHT, 700)
            .rule(Selector.parse("#other.roughness")).set(OPACITY, 0.5f)
            .rule(Selector.parse("#a.b.c.d.roughness")).set(OPACITY, 0.25f)
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        ResolvedStyle s = r.styleOf(row.slot());
        assertThat(s.isSet(FONT_SIZE)).isFalse(); // not a child of props, no facts yet
        assertThat(s.getInt(FONT_WEIGHT)).isEqualTo(700); // the id path tail inspector.roughness
        assertThat(s.getFloat(OPACITY)).isEqualTo(1f);

        r.facts(row.slot(), Facts.of("annotation", "Range", "group", "Surface", "annotation", "Tooltip"));
        s = r.style(r.resolve(row.slot()));
        assertThat(s.getFloat(FONT_SIZE)).isEqualTo(2f);
        assertThat(s.get(COLOR)).isEqualTo(BLUE);

        group.detach();
        props.add(row);
        s = r.style(r.resolve(row.slot()));
        assertThat(s.getFloat(FONT_SIZE)).isEqualTo(2f); // facts are more specific than the child combinator
        r.facts(row.slot(), Facts.NONE);
        assertThat(size(r, row)).isEqualTo(1f);
    }

    @Test
    void idSelectorsNeedTheElementsOwnId() {
        UiStore store = new UiStore();
        Element button = store.create("panel", "button");
        Element inner = store.create("label", null);
        Element dotted = store.create("label", "thumb.button");
        store.root().add(button);
        button.add(inner);
        store.root().add(dotted);
        StyleResolver r = new StyleResolver(store, ranked("#button"));
        assertThat(size(r, button)).isEqualTo(1f);
        assertThat(r.styleOf(inner.slot()).isSet(FONT_SIZE)).isFalse(); // its path ends in button, but it has no id
        assertThat(size(r, dotted)).isEqualTo(1f); // a dotted id is a path of its own
        StyleResolver r2 = new StyleResolver(store, ranked("#thumb.button"));
        assertThat(size(r2, dotted)).isEqualTo(1f);
        assertThat(r2.styleOf(button.slot()).isSet(FONT_SIZE)).isFalse();
    }

    @Test
    void equalInputsShareOneStyleAndHitsAllocateNothing() {
        UiStore store = new UiStore();
        Element panel = store.create("panel", null);
        store.root().add(panel);
        List<Element> labels = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Element l = store.create("label", null).style("value");
            panel.add(l);
            labels.add(l);
        }
        StyleSheet sheet = StyleSheet.builder()
            .rule(sel("label").style("value")).set(FONT_SIZE, 18f).set(COLOR, RED)
            .rule(sel("label").style("value").state(UiState.HOVER)).set(COLOR, GREEN)
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        int first = r.resolve(labels.get(0).slot());
        for (Element l : labels) {
            assertThat(r.resolve(l.slot())).isEqualTo(first);
        }
        int computed = r.computed();
        labels.get(5).state(UiState.HOVER, true);
        int hover = r.resolve(labels.get(5).slot());
        assertThat(hover).isNotEqualTo(first);
        labels.get(6).state(UiState.HOVER, true);
        assertThat(r.resolve(labels.get(6).slot())).isEqualTo(hover);
        assertThat(r.computed()).isEqualTo(computed + 1);
        assertThat(r.style(hover).rgba(COLOR)).isEqualTo(GREEN.rgba());

        int slot = labels.get(7).slot();
        ResolvedStyle style = r.style(first);
        float[] sink = new float[1];
        long[] colors = new long[1];
        AllocationAssertions.assertAllocationFree(() -> {
            int h = r.resolve(slot);
            ResolvedStyle s = r.style(h);
            sink[0] += s.getFloat(FONT_SIZE) + style.getFloat(OPACITY);
            colors[0] ^= s.rgba(COLOR);
            r.update();
        });
        assertThat(sink[0]).isPositive();
    }

    @Test
    void replacingTheSheetBumpsTheVersionAndReResolvesEachElementOnce() {
        UiStore store = new UiStore();
        Element a = store.create("label", null);
        Element b = store.create("label", null);
        store.root().add(a);
        store.root().add(b);
        StyleResolver r = new StyleResolver(store, ranked("label"));
        assertThat(size(r, a)).isEqualTo(1f);
        assertThat(size(r, b)).isEqualTo(1f);
        assertThat(store.dirty(a.slot()) & UiStore.DIRTY_STYLE).isZero();
        long v = r.version();
        int computed = r.computed();

        r.sheet(ranked("panel", "label"));
        assertThat(r.version()).isGreaterThan(v);
        assertThat(store.dirty(a.slot()) & UiStore.DIRTY_STYLE).isNotZero();
        assertThat(store.dirty(b.slot()) & UiStore.DIRTY_STYLE).isNotZero();
        assertThat(r.handle(a.slot())).isEqualTo(-1);
        assertThat(size(r, a)).isEqualTo(2f);
        assertThat(size(r, b)).isEqualTo(2f);
        // a and b share one style; the root has its own
        assertThat(r.computed() - computed).isEqualTo(2);
        assertThat(r.cachedStyles()).isEqualTo(2);
        assertThat(r.sheet().rules()).hasSize(2);
        assertThat(r.store()).isSameAs(store);
    }

    @Test
    void anAncestorsStateReachesDescendantsOnlyWhenSelectorsAskForIt() {
        UiStore store = new UiStore();
        Element panel = store.create("panel", null);
        Element label = store.create("label", null);
        store.root().add(panel);
        panel.add(label);
        StyleResolver plain = new StyleResolver(store, ranked("label"));
        plain.resolve(panel.slot());
        plain.resolve(label.slot());
        panel.state(UiState.HOVER, true);
        plain.resolve(panel.slot());
        assertThat(store.dirty(label.slot()) & UiStore.DIRTY_STYLE).isZero(); // nobody selects on panel:hover

        StyleResolver hovering = new StyleResolver(store, ranked("label", "panel:hover label"));
        panel.state(UiState.HOVER, false);
        hovering.resolve(panel.slot());
        assertThat(size(hovering, label)).isEqualTo(1f);
        panel.state(UiState.HOVER, true);
        hovering.resolve(panel.slot());
        assertThat(store.dirty(label.slot()) & UiStore.DIRTY_STYLE).isNotZero();
        assertThat(size(hovering, label)).isEqualTo(2f);
    }

    @Test
    void aChildResolvesItsParentFirstAndReusedSlotsForgetTheirInputs() {
        UiStore store = new UiStore();
        Element panel = store.create("panel", null).style("hud");
        Element label = store.create("label", null);
        store.root().add(panel);
        panel.add(label);
        StyleResolver r = new StyleResolver(store, ranked(".hud"));
        assertThat(size(r, label)).isEqualTo(1f); // resolving the label resolved the panel for its style name
        r.inline(label.slot(), InlineStyle.of(Declaration.of(FONT_SIZE, 50f)));
        assertThat(size(r, label)).isEqualTo(50f);
        int slot = label.slot();
        label.destroy();
        Element reused = store.create("label", null);
        assertThat(reused.slot()).isEqualTo(slot);
        panel.add(reused);
        assertThat(r.handle(slot)).isEqualTo(-1);
        assertThat(r.styleOf(slot).getFloat(FONT_SIZE)).isEqualTo(1f); // the inline value went with the old element
    }

    @Test
    void theCacheIsRebuiltWhenItOutgrowsItsLimit() {
        UiStore store = new UiStore();
        StyleResolver r = new StyleResolver(store, ranked("label"));
        float limit = StyleResolver.CACHE_LIMIT.get();
        StyleResolver.CACHE_LIMIT.set(256);
        try {
            long v = r.version();
            for (int i = 0; i < 300; i++) {
                Element e = store.create("label", "unique" + i);
                store.root().add(e);
                r.resolve(e.slot());
            }
            assertThat(r.version()).isGreaterThan(v); // dropped once on the way
            assertThat(r.cachedStyles()).isLessThan(300);
            Element e = store.create("label", "last");
            store.root().add(e);
            assertThat(size(r, e)).isEqualTo(1f);
        } finally {
            StyleResolver.CACHE_LIMIT.set(limit);
        }
    }

    @Test
    void tokenChangesReResolveOnlyTheDependentStyles() {
        UiStore store = new UiStore();
        Element primary = store.create("button", null).style("primary");
        Element danger = store.create("button", null).style("danger");
        Element plain = store.create("label", null);
        store.root().add(primary);
        store.root().add(danger);
        store.root().add(plain);
        TokenExpr accentHover = TokenExpr.call("lighten", TokenExpr.token("accent"),
            TokenExpr.literal(Value.num(8, "%")));
        StyleSheet sheet = StyleSheet.builder()
            .token("accent", RED)
            .token("warn", BLUE)
            .tokenSet("dark", "accent", Value.color(GREEN))
            .tokenSet("contrast", "warn", Value.color(Color.WHITE))
            .rule(sel("button").style("primary"))
            .live(BACKGROUND, TokenExpr.call("box", accentHover))
            .rule(sel("button").style("danger")).live(COLOR, TokenExpr.token("warn"))
            .rule(sel("label")).set(FONT_SIZE, 11f)
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        ResolvedStyle p = r.styleOf(primary.slot());
        ResolvedStyle d = r.styleOf(danger.slot());
        ResolvedStyle l = r.styleOf(plain.slot());
        assertThat(p.get(BACKGROUND)).isEqualTo(Paint.box(Colors.lighten(RED, 0.08)));
        assertThat(p.isLive()).isTrue();
        assertThat(l.isLive()).isFalse();
        long pv = p.version();
        long dv = d.version();
        long lv = l.version();
        assertThat(r.update()).isFalse();

        r.tokens().use("dark");
        assertThat(r.reevaluated()).isZero(); // nothing before the next update
        assertThat(r.update()).isTrue();
        assertThat(r.reevaluated()).isEqualTo(1); // only the primary style depends on --accent
        assertThat(p.get(BACKGROUND)).isEqualTo(Paint.box(Colors.lighten(GREEN, 0.08)));
        assertThat(p.version()).isGreaterThan(pv);
        assertThat(d.version()).isEqualTo(dv);
        assertThat(l.version()).isEqualTo(lv);
        assertThat(store.dirty(primary.slot()) & UiStore.DIRTY_STYLE).isNotZero();
        assertThat(store.dirty(danger.slot()) & UiStore.DIRTY_STYLE).isZero();
        assertThat(r.resolve(primary.slot())).isEqualTo(p.handle()); // the handle stays

        r.tokens().set("warn", GREEN);
        r.update();
        assertThat(r.reevaluated()).isEqualTo(2);
        assertThat(d.get(COLOR)).isEqualTo(GREEN);
        r.tokens().use("contrast"); // accent back to red, warn stays overridden in code
        r.update();
        assertThat(r.reevaluated()).isEqualTo(3);
        r.tokens().reset("warn");
        r.update();
        assertThat(d.get(COLOR)).isEqualTo(Color.WHITE);
        assertThat(r.reevaluated()).isEqualTo(4);

        List<String> errors = new ArrayList<>();
        r.errors(errors::add);
        r.tokens().set("accent", Value.num(3, "px"));
        r.update();
        assertThat(p.get(BACKGROUND)).isEqualTo(Paint.NONE); // the key falls back to its default
        assertThat(errors).hasSize(1);
        assertThat(r.lastError()).contains("background").contains("lighten");
    }

    @Test
    void anElementAddedAfterATokenChangeSeesTheNewValue() {
        UiStore store = new UiStore();
        StyleSheet sheet = StyleSheet.builder()
            .token("fg", RED)
            .rule(sel("label")).live(COLOR, TokenExpr.token("fg"))
            .build();
        StyleResolver r = new StyleResolver(store, sheet);
        r.tokens().set("fg", BLUE);
        Element label = store.create("label", null);
        store.root().add(label);
        assertThat(r.styleOf(label.slot()).get(COLOR)).isEqualTo(BLUE);
        assertThat(r.reevaluated()).isZero(); // no style depended on --fg before
    }

    @Test
    void placeholderRulesAreNotApplied() {
        UiStore store = new UiStore();
        Element label = store.create("label", null);
        store.root().add(label);
        StyleSheet sheet = StyleSheet.builder().rule(sel("label").placeholder("pill")).set(FONT_SIZE, 3f).build();
        StyleResolver r = new StyleResolver(store, sheet);
        assertThat(r.styleOf(label.slot()).isSet(FONT_SIZE)).isFalse();
    }
}
