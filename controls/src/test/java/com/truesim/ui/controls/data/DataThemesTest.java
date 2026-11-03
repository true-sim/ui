package com.truesim.ui.controls.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shipped themes style every data-control element type and state, and the garish test theme restyles each
 * (design 13: a control still looking like the default under it holds a presentational value in code).
 */
class DataThemesTest {

    static final int[] STATES = {0, UiState.HOVER, UiState.PRESSED, UiState.SELECTED, UiState.FOCUSED,
        UiState.FOCUS_VISIBLE, UiState.EXPANDED, UiState.DISABLED, UiState.CHECKED, UiState.EDITING,
        UiState.DROP_TARGET};

    /** Every element type (and id) of the gallery, one element each, styled by {@code sheet}. */
    static Map<String, ResolvedStyle> styles(StyleSheet sheet, int state) {
        Ui ui = new Ui(sheet, UiTestAccess.jdkText());
        DataGallery.build(ui);
        ui.update(1600, 1000);
        UiStore store = ui.store();
        Map<String, ResolvedStyle> out = new HashMap<>();
        for (int s = 0; s < store.highWater(); s++) {
            if (!store.alive(s)) {
                continue;
            }
            String key = store.type(s) + (store.id(s) == null ? "" : "#" + store.id(s));
            if (key.startsWith("table-") || key.startsWith("scroll") || key.startsWith("list-")
                || key.startsWith("tree-") || key.startsWith("value-") || key.startsWith("label#")) {
                if (!out.containsKey(key)) {
                    if (state != 0) {
                        store.state(s, state, true);
                        ui.styles().resolve(s);
                    }
                    out.put(key, ui.styles().styleOf(s));
                }
            }
        }
        return out;
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
    void theDefaultThemeStylesEveryPartAndTheTestThemeRestylesIt() {
        Map<String, ResolvedStyle> plain = styles(StyleSheet.empty(), 0);
        Map<String, ResolvedStyle> normal = styles(Tss.defaultTheme(), 0);
        Map<String, ResolvedStyle> garish = styles(Tss.testTheme(), 0);
        assertThat(normal.keySet()).contains("scroll-pane", "scroll-viewport", "scroll-content", "scroll-section",
            "scroll-sticky", "scroll-bar#vertical", "scroll-bar#horizontal", "scroll-button#start",
            "scroll-button#end", "scroll-thumb", "list-view", "list-body", "list-item", "list-drop-indicator",
            "tree-view", "tree-item", "tree-indent", "tree-expander", "tree-drop-indicator", "table-view",
            "table-header", "table-header-row", "table-header-cell", "table-sort-indicator", "table-column-grip",
            "table-filter-row", "table-filter-cell", "table-row", "table-cells", "table-frozen", "table-cell",
            "table-column-drop-indicator", "value-check", "label#number", "label#text", "label#enum");
        for (String type : normal.keySet()) {
            if (type.equals("list-body") || type.equals("tree-body") || type.equals("table-body")
                || type.equals("table-cells") || type.equals("table-frozen") || type.equals("scroll-section")
                || type.equals("scroll-stick")) {
                continue; // structure only: placed by code, nothing to see
            }
            assertThat(differs(normal.get(type), plain.get(type))).as("the default theme styles %s", type).isTrue();
            assertThat(differs(normal.get(type), garish.get(type))).as("the test theme restyles %s", type).isTrue();
        }
    }

    @Test
    void statesLookDifferentInBothThemes() {
        String[] parts = {"list-item", "tree-item", "table-row", "table-cell", "scroll-thumb", "tree-expander",
            "value-check", "table-header-cell"};
        for (StyleSheet sheet : new StyleSheet[] {Tss.defaultTheme(), Tss.testTheme()}) {
            Map<String, ResolvedStyle> base = styles(sheet, 0);
            int differing = 0;
            for (int state : STATES) {
                if (state == 0) {
                    continue;
                }
                Map<String, ResolvedStyle> stated = styles(sheet, state);
                for (String part : parts) {
                    if (differs(base.get(part), stated.get(part))) {
                        differing++;
                    }
                }
            }
            assertThat(differing).isGreaterThanOrEqualTo(20);
        }
        VersionedList<String> unused = new com.truesim.common.state.ChangeClock().list();
        assertThat(unused).isEmpty();
    }
}
