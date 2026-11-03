package com.truesim.ui.style.tss;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.LayoutKind;
import com.truesim.ui.style.value.Paint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThemesTest {

    static final String[] TYPES = {"panel", "label", "image"};
    static final String[] STYLES = {null, "hud", "card", "title", "value", "caption", "primary", "danger", "meter"};

    /** A HUD with every type under every style name, in a card, as the demo HUD will nest them. */
    static List<Element> hud(UiStore store) {
        Element hud = store.create("panel", "hud").style("hud");
        store.root().add(hud);
        Element card = store.create("panel", "card").style("card");
        hud.add(card);
        List<Element> all = new ArrayList<>();
        for (String style : STYLES) {
            for (String type : TYPES) {
                Element e = store.create(type, null).style(style);
                card.add(e);
                all.add(e);
            }
        }
        return all;
    }

    private static boolean differs(ResolvedStyle a, ResolvedStyle b) {
        for (int i = 0; i < StyleKeys.count(); i++) {
            StyleKey<?> key = StyleKeys.key(i);
            if (!a.get(key).equals(b.get(key))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theShippedThemesCompile() {
        Compilation c = Tss.compileResource(Tss.THEMES + "/default-theme.tss");
        assertThat(c.sources()).contains(Tss.THEMES + "/default-theme.tss", Tss.THEMES + "/_tokens.tss",
            Tss.THEMES + "/_mixins.tss", Tss.THEMES + "/_basic.tss", Tss.THEMES + "/_data.tss",
            Tss.THEMES + "/_windows.tss");
        assertThat(c.warnings()).isEmpty();
        StyleSheet theme = Tss.defaultTheme();
        assertThat(theme).isEqualTo(c.sheet());
        assertThat(theme.tokenSets()).containsOnlyKeys("dark", "light", "high-contrast");
        assertThat(theme.tokens()).containsKeys("fg", "accent", "surface", "surface-raised", "danger");
        StyleSheet test = Tss.testTheme();
        assertThat(test.tokenSets()).containsOnlyKeys("dark", "light", "high-contrast");
        assertThat(test).isNotEqualTo(theme);
    }

    @Test
    void theDefaultThemeStylesEveryTypeAndStyleNameAndTheTestThemeRestylesThem() {
        UiStore store = new UiStore();
        List<Element> elements = hud(store);
        StyleResolver plain = new StyleResolver(store, StyleSheet.empty());
        StyleResolver normal = new StyleResolver(store, Tss.defaultTheme());
        StyleResolver garish = new StyleResolver(store, Tss.testTheme());
        for (Element e : elements) {
            ResolvedStyle d = normal.styleOf(e.slot());
            ResolvedStyle t = garish.styleOf(e.slot());
            assertThat(differs(d, plain.styleOf(e.slot()))).as("the default theme styles %s", e).isTrue();
            assertThat(differs(d, t)).as("the test theme restyles %s", e).isTrue();
        }
        ResolvedStyle label = normal.styleOf(elements.get(1).slot());
        assertThat(label.get(Styles.FONT_FAMILY)).containsExactly("Inter", "Segoe UI", "DejaVu Sans", "sans-serif");
        assertThat(label.get(Styles.COLOR)).isEqualTo(Color.hex("#e6edf3"));
        Element hudRoot = store.element(store.firstChild(store.rootSlot()));
        assertThat(normal.styleOf(hudRoot.slot()).get(Styles.LAYOUT)).isEqualTo(LayoutKind.ANCHORS);
    }

    @Test
    void bothThemesStyleLayersAndTheInteractionStates() {
        UiStore store = new UiStore();
        Element layer = store.createRoot("layer", "popup");
        StyleResolver plain = new StyleResolver(store, StyleSheet.empty());
        StyleResolver normal = new StyleResolver(store, Tss.defaultTheme());
        StyleResolver garish = new StyleResolver(store, Tss.testTheme());
        assertThat(differs(normal.styleOf(layer.slot()), plain.styleOf(layer.slot()))).isTrue();
        assertThat(differs(normal.styleOf(layer.slot()), garish.styleOf(layer.slot()))).isTrue();
        assertThat(normal.styleOf(layer.slot()).get(Styles.LAYOUT)).isEqualTo(LayoutKind.NONE);
        for (int state : new int[] {UiState.FOCUS_VISIBLE, UiState.DROP_TARGET, UiState.DRAGGING}) {
            Element panel = store.create("panel", null);
            store.root().add(panel);
            ResolvedStyle before = normal.styleOf(panel.slot());
            ResolvedStyle beforeGarish = garish.styleOf(panel.slot());
            panel.state(state, true);
            normal.resolve(panel.slot());
            garish.resolve(panel.slot());
            ResolvedStyle after = normal.styleOf(panel.slot());
            assertThat(differs(after, before)).as("the default theme styles :%s", UiState.name(state)).isTrue();
            assertThat(differs(garish.styleOf(panel.slot()), beforeGarish)).as("the test theme styles :%s",
                UiState.name(state)).isTrue();
            assertThat(differs(after, garish.styleOf(panel.slot()))).isTrue();
        }
        Element focused = store.create("panel", null);
        store.root().add(focused);
        focused.state(UiState.FOCUS_VISIBLE, true);
        assertThat(normal.styleOf(focused.slot()).get(Styles.FOCUS_RING).width()).isEqualTo(2);
        Element meter = store.create("panel", null).style("meter");
        store.root().add(meter);
        assertThat(normal.styleOf(meter.slot()).get(Styles.CLIP)).isTrue();
        Element card = store.create("panel", null).style("card");
        store.root().add(card);
        assertThat(normal.styleOf(card.slot()).get(Styles.POINTER_EVENTS))
            .isEqualTo(com.truesim.ui.core.event.PointerEvents.ALL);
    }

    @Test
    void switchingLightAndDarkReResolvesEachDependentStyleOnce() {
        UiStore store = new UiStore();
        List<Element> elements = hud(store);
        StyleResolver r = new StyleResolver(store, Tss.defaultTheme());
        Element card = store.element(store.parent(elements.get(0).slot()));
        card.state(UiState.HOVER, true);
        List<ResolvedStyle> styles = new ArrayList<>();
        for (Element e : elements) {
            styles.add(r.styleOf(e.slot()));
        }
        ResolvedStyle hovered = r.styleOf(card.slot());
        Paint before = hovered.get(Styles.BACKGROUND);
        int live = 0;
        for (int h = 0; h < r.cachedStyles(); h++) {
            live += r.style(h).isLive() ? 1 : 0;
        }
        assertThat(live).isPositive();

        r.tokens().use("light");
        r.update();
        assertThat(r.reevaluated()).isEqualTo(live); // every style using a palette token, once
        assertThat(hovered.get(Styles.BACKGROUND)).isNotEqualTo(before); // the dependent token followed
        assertThat(r.update()).isFalse(); // nothing more until the next change

        r.tokens().use("dark");
        r.update();
        assertThat(r.reevaluated()).isEqualTo(2 * live);
        assertThat(hovered.get(Styles.BACKGROUND)).isEqualTo(before);

        int computed = r.computed();
        for (Element e : elements) {
            r.resolve(e.slot()); // marked dirty by the switch: each resolves again from the cache
        }
        assertThat(r.computed()).isEqualTo(computed);

        r.tokens().set("accent", Color.rgb(255, 0, 255)); // one token: only the styles reading it
        r.update();
        int accentStyles = r.reevaluated() - 2 * live;
        assertThat(accentStyles).isPositive().isLessThan(live);
    }
}
