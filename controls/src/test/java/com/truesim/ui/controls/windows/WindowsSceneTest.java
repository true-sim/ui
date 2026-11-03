package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.testkit.DrawListDump;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The representative scene: draw-list goldens in both themes, semantics, and the test theme restyling everything. */
class WindowsSceneTest {

    static final Path GOLDENS = Path.of("src/test/resources/goldens/windows");

    private static WindowsScene scene(StyleSheet sheet) {
        return WindowsScene.build(new Ui(sheet, Fx.nativeText()), 1280, 720);
    }

    @Test
    void theSceneShowsWindowsATooltipAPopoverAndADropPreviewAndMatchesItsGoldens() {
        WindowsScene s = scene(Fx.theme());
        assertThat(s.tooltips.showing()).isTrue();
        assertThat(s.popover.isOpen()).isTrue();
        assertThat(s.dock.previewShowing()).isTrue();
        assertThat(s.windows.openCount()).isEqualTo(2);
        assertThat(s.windows.active()).isSameAs(s.inspectorWindow);
        DrawList[] layers = s.ui.layers();
        assertThat(s.ui.layerCount()).isEqualTo(5); // HUD, overlay (windows), pop-up, tooltip, drag
        String[] names = {"hud", "overlay", "popup", "tooltip", "drag"};
        for (int i = 0; i < s.ui.layerCount(); i++) {
            assertThat(s.ui.layerStack().at(i).name()).isEqualTo(names[i]);
            DrawListDump.assertMatches(layers[i], GOLDENS.resolve("scene-" + names[i] + ".txt"));
        }
        WindowsScene garish = scene(Fx.garish());
        assertThat(garish.dock.previewShowing()).isTrue();
        assertThat(garish.popover.isOpen()).isTrue();
        for (int i = 0; i < garish.ui.layerCount(); i++) {
            String name = garish.ui.layerStack().at(i).name();
            DrawListDump.assertMatches(garish.ui.layers()[i], GOLDENS.resolve("scene-test-theme-" + name + ".txt"));
            assertThat(DrawListDump.of(garish.ui.layers()[i])).isNotEqualTo(DrawListDump.of(layers[i]));
        }
    }

    @Test
    void everyControlHasARoleAndANameForScreenReaders() {
        WindowsScene s = scene(Fx.theme());
        Semantics.Node tree = Semantics.of(s.ui.store(), s.ui.interaction());
        assertThat(Semantics.unnamed(tree)).isEmpty();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (com.truesim.ui.core.Tunable t : s.ui.tunables()) {
            names.add(t.name());
        }
        assertThat(names).doesNotHaveDuplicates().contains("ui.tooltip.warmTime", "ui.hover.openDelay",
            "ui.hover.closeDelay", "ui.hover.intentTime", "ui.window.snapDistance", "ui.window.keyStep",
            "ui.window.keepVisible", "ui.input.tooltipDelay");
        assertThat(tree.toString()).contains("WINDOW \"Material\"", "WINDOW \"Statistics\"", "BUTTON \"Close\"",
            "BUTTON \"Minimise\"", "BUTTON \"Maximise\"", "TOOLTIP \"Save the scene (Ctrl+S)\"", "DIALOG \"Options\"",
            "TAB_LIST", "TAB \"Hierarchy\"", "TAB_PANEL \"Hierarchy\"", "SEPARATOR", "TOOLBAR");
    }

    @Test
    void theTestThemeRestylesEveryElementAndStateOfOverlaysWindowsAndDocking() {
        WindowsScene s = scene(Fx.theme());
        UiStore store = s.ui.store();
        StyleResolver normal = new StyleResolver(store, Fx.theme());
        StyleResolver garish = new StyleResolver(store, Fx.garish());
        Set<String> types = new LinkedHashSet<>();
        String[] ours = {"backdrop", "tooltip", "hover-card", "popover", "window-host", "window", "window-frame",
            "window-title-bar", "window-button", "window-content", "window-resize", "dock-space", "dock-split",
            "dock-splitter", "dock-tabs", "dock-tab-strip", "dock-tab", "dock-tab-close", "dock-pane-body",
            "dock-drop-preview", "dock-zones", "dock-zone"};
        // the scene has no backdrop or hover card: add them
        Element extra = s.ui.element("hover-card", null);
        s.ui.root().add(extra);
        Element backdrop = s.ui.element("backdrop", null);
        s.ui.root().add(backdrop);
        for (int slot = 0; slot < store.highWater(); slot++) {
            if (!store.alive(slot) || store.rootOf(slot) == UiStore.NONE) {
                continue;
            }
            String type = store.type(slot);
            if (!java.util.Arrays.asList(ours).contains(type)) {
                continue;
            }
            types.add(type);
            assertThat(differs(normal.styleOf(slot), garish.styleOf(slot)))
                .as("the test theme restyles %s", store.element(slot)).isTrue();
        }
        assertThat(types).containsExactlyInAnyOrder(ours);
        // and every state they style
        int[] states = {UiState.HOVER, UiState.PRESSED, UiState.SELECTED, UiState.CHECKED, UiState.FOCUS_VISIBLE,
            UiState.DRAGGING, UiState.DROP_TARGET};
        String[][] stateful = {{"window-button", "hover", "pressed", "checked"}, {"dock-tab", "hover", "selected",
            "dragging", "focus-visible"}, {"dock-splitter", "hover", "pressed"}, {"dock-zone", "selected"},
            {"dock-tab-strip", "drop-target"}, {"window", "focus-visible"}, {"dock-tab-close", "hover"}};
        for (String[] row : stateful) {
            Element e = first(store, row[0]);
            for (int k = 1; k < row.length; k++) {
                int bit = UiState.of(row[k]);
                int before = store.state(e.slot());
                ResolvedStyle plainNormal = normal.styleOf(e.slot());
                ResolvedStyle plainGarish = garish.styleOf(e.slot());
                e.state(bit, true);
                ResolvedStyle n = resolve(normal, e);
                ResolvedStyle g = resolve(garish, e);
                assertThat(differs(n, plainNormal) || (before & bit) != 0)
                    .as("the default theme styles %s:%s", row[0], row[k]).isTrue();
                assertThat(differs(g, plainGarish) || (before & bit) != 0)
                    .as("the test theme styles %s:%s", row[0], row[k]).isTrue();
                assertThat(differs(n, g)).as("%s:%s differ between themes", row[0], row[k]).isTrue();
                e.state(bit, (before & bit) != 0);
                resolve(normal, e);
                resolve(garish, e);
            }
        }
        assertThat(states).hasSize(7);
    }

    private static ResolvedStyle resolve(StyleResolver resolver, Element e) {
        resolver.resolve(e.slot());
        return resolver.styleOf(e.slot());
    }

    private static Element first(UiStore store, String type) {
        for (int slot = 0; slot < store.highWater(); slot++) {
            if (store.alive(slot) && type.equals(store.type(slot)) && store.rootOf(slot) != UiStore.NONE) {
                return store.element(slot);
            }
        }
        throw new AssertionError("no " + type);
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
}
