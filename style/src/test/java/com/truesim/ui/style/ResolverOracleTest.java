package com.truesim.ui.style;

import static com.truesim.ui.style.Styles.COLOR;
import static com.truesim.ui.style.Styles.FONT_SIZE;
import static com.truesim.ui.style.Styles.PADDING;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.Selector.Compound;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The indexed, cached resolver against the plain linear one, on random trees and sheets. */
class ResolverOracleTest {

    private static final String[] TYPES = {"panel", "label", "image", "button"};
    private static final String[] STYLES = {"a", "b", "c"};
    private static final String[] IDS = {"x", "y", "z", "x.y"};
    private static final int[] STATES = {UiState.HOVER, UiState.PRESSED, UiState.FOCUSED};
    private static final Color[] COLORS = {Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(0, 0, 255)};

    private static <T> T pick(Random random, T[] values) {
        return values[random.nextInt(values.length)];
    }

    private static Compound compound(Random random) {
        String type = random.nextInt(3) == 0 ? null : pick(random, TYPES);
        String style = random.nextInt(2) == 0 ? null : pick(random, STYLES);
        List<String> id = random.nextInt(4) == 0 ? List.of(pick(random, IDS).split("\\.")) : List.of();
        int states = random.nextInt(4) == 0 ? STATES[random.nextInt(STATES.length)] : 0;
        List<Selector.Fact> facts = random.nextInt(5) == 0
            ? List.of(new Selector.Fact("k", random.nextBoolean() ? null : String.valueOf(random.nextInt(2))))
            : List.of();
        return new Compound(type, style, id, states, facts, null);
    }

    private static StyleSheet sheet(Random random, int rules) {
        StyleSheet.Builder b = StyleSheet.builder();
        for (int i = 0; i < rules; i++) {
            List<Compound> compounds = new ArrayList<>();
            List<Selector.Combinator> combinators = new ArrayList<>();
            compounds.add(compound(random));
            int more = random.nextInt(3);
            for (int k = 0; k < more; k++) {
                combinators.add(random.nextBoolean() ? Selector.Combinator.CHILD : Selector.Combinator.DESCENDANT);
                compounds.add(compound(random));
            }
            StyleSheet.RuleBuilder r = b.rule(new Selector(compounds, combinators));
            if (random.nextBoolean()) {
                r.set(FONT_SIZE, (float) (i + 1));
            }
            if (random.nextBoolean()) {
                r.set(COLOR, pick(random, COLORS));
            }
            if (random.nextBoolean()) {
                r.set(PADDING, Insets.of(i));
            }
        }
        return b.build();
    }

    @Test
    void indexedCachedResolutionAgreesWithTheLinearScan() {
        for (int seed = 0; seed < 40; seed++) {
            Random random = new Random(seed);
            UiStore store = new UiStore();
            List<Element> elements = new ArrayList<>();
            elements.add(store.root());
            StyleSheet sheet = sheet(random, 60);
            StyleResolver resolver = new StyleResolver(store, sheet);
            LinearResolver linear = new LinearResolver(store, sheet);
            for (int i = 0; i < 150; i++) {
                Element e = store.create(pick(random, TYPES), random.nextInt(3) == 0 ? pick(random, IDS) : null);
                if (random.nextInt(3) == 0) {
                    e.style(pick(random, STYLES));
                }
                for (int bit : STATES) {
                    e.state(bit, random.nextInt(4) == 0);
                }
                elements.get(random.nextInt(elements.size())).add(e);
                if (random.nextInt(4) == 0) {
                    Facts f = Facts.of("k", String.valueOf(random.nextInt(2)));
                    resolver.facts(e.slot(), f);
                    linear.facts(e.slot(), f);
                }
                elements.add(e);
            }
            for (int round = 0; round < 2; round++) {
                for (Element e : elements) {
                    ResolvedStyle s = resolver.styleOf(e.slot());
                    Object[] expected = linear.resolve(e.slot());
                    for (StyleKey<?> key : List.of(FONT_SIZE, COLOR, PADDING)) {
                        Object want = expected[key.index()] == null ? key.defaultValue() : expected[key.index()];
                        assertThat(s.get(key)).as("seed %d, %s, %s", seed, e, key).isEqualTo(want);
                    }
                }
                // change states, then resolve the dirty elements again, in tree order
                for (Element e : elements) {
                    e.state(UiState.HOVER, random.nextInt(3) == 0);
                }
                for (Element e : elements) {
                    resolver.resolve(e.slot());
                }
            }
        }
    }
}
