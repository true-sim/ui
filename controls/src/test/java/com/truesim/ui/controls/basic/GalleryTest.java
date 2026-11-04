package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.UiStore;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleKey;
import com.truesim.ui.style.StyleKeys;
import com.truesim.ui.testkit.DrawListDump;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The control gallery: draw-list goldens in both themes, semantics, and the garish theme restyling every part. */
class GalleryTest {

    static Fixture gallery(com.truesim.ui.style.StyleSheet sheet, ControlsGallery.Gallery[] out) {
        Fixture f = new Fixture(sheet, Fixture.newJdkText()); // its own atlas: the goldens do not depend on test order
        ControlsGallery.Gallery g = ControlsGallery.build(f.c, f.ui.root());
        if (out != null) {
            out[0] = g;
        }
        f.settle();
        return f;
    }

    @Test
    void theGalleryDrawsAsItsGoldens() {
        ControlsGallery.Gallery[] g = new ControlsGallery.Gallery[1];
        Fixture plain = gallery(Fixture.DEFAULT, g);
        DrawListDump.assertMatches(plain.ui.layers()[0], Path.of("src/test/resources/goldens/basic-gallery.txt"));
        Fixture garish = gallery(Fixture.GARISH, null);
        DrawListDump.assertMatches(garish.ui.layers()[0],
            Path.of("src/test/resources/goldens/basic-gallery-test-theme.txt"));
        assertThat(DrawListDump.of(plain.ui.layers()[0])).isNotEqualTo(DrawListDump.of(garish.ui.layers()[0]));

        g[0].openOverlays();
        plain.settle();
        StringBuilder layers = new StringBuilder();
        for (int i = 1; i < plain.ui.layerCount(); i++) {
            layers.append("# layer ").append(plain.ui.layerStack().at(i).name()).append('\n');
            layers.append(DrawListDump.of(plain.ui.layers()[i]));
        }
        assertThat(layers.toString()).contains("# layer modal", "# layer popup", "# layer notifications");
        assertThat(plain.unnamed()).isEmpty();
    }

    @Test
    void everyControlIsAnnouncedWithARoleAndAName() {
        Fixture f = gallery(Fixture.DEFAULT, null);
        assertThat(f.unnamed()).isEmpty();
        String tree = f.semantics();
        assertThat(tree).contains("BUTTON \"Save\" +activate +focus", "CHECKBOX \"Checked\" :checked",
            "CHECKBOX \"Mixed\" :indeterminate", "RADIO \"Normal\" :checked", "SWITCH \"V-sync\" :checked",
            "SLIDER \"Volume\"", "SLIDER \"Level range minimum\"", "PROGRESS \"Loading level\"",
            "PROGRESS \"Connecting\" :indeterminate", "COMBO_BOX \"Quality\"", "MENU_BAR \"Main menu\"",
            "TOOLBAR \"Tools\"", "STATUS \"Status\"", "TAB \"Scene\" :selected", "TAB_PANEL \"Panels\"",
            "GROUP \"General\" :expanded", "GROUP \"Fieldset\" :disabled", "SEPARATOR \"Editor split\"",
            "SPIN_BUTTON \"Hours\"", "TOGGLE_BUTTON \"Bold\" :checked");
    }

    /** The element types of the controls (the garish theme must restyle each). */
    static final Set<String> TYPES = new TreeSet<>(List.of("button", "toggle-button", "checkbox", "check-box",
        "check-mark", "switch", "switch-track", "switch-knob", "radio-group", "radio", "radio-box", "radio-dot",
        "segmented", "segment", "icon-label", "separator", "badge", "progress", "progress-fill", "spinner",
        "spinner-dot", "slider", "slider-track", "slider-fill", "slider-tick", "slider-thumb", "color-picker",
        "color-area", "color-area-white", "color-area-black", "color-area-cursor", "color-hue", "color-hue-strip",
        "color-hue-segment", "color-hue-cursor", "color-alpha", "color-alpha-checker", "color-alpha-gradient",
        "color-alpha-cursor", "color-preview", "color-hex-slot", "color-swatches", "color-swatch", "date-picker",
        "date-header", "date-weekdays", "date-grid", "date-day", "time-picker", "time-field", "key-binding",
        "dropdown", "dropdown-list", "dropdown-viewport", "dropdown-items", "dropdown-item", "menu", "menu-viewport",
        "menu-items", "menu-item", "menu-check", "menu-separator", "menu-bar", "menu-title", "dialog-backdrop",
        "dialog", "dialog-buttons", "toast-stack", "toast", "toolbar", "toolbar-viewport", "toolbar-items",
        "status-bar", "breadcrumbs", "crumb",
        "tab-pane", "tab-header", "tab-strip", "tab-row", "tab", "tab-content", "collapsible", "expander",
        "collapsible-header", "collapsible-body", "accordion", "group-box", "group-content", "card", "card-header",
        "card-body", "card-footer", "split-pane", "split-first", "split-second", "splitter", "carousel",
        "carousel-viewport", "carousel-track", "carousel-page", "carousel-dots", "stepper", "stepper-steps", "step",
        "stepper-content", "stepper-buttons", "drawer", "drawer-content", "drawer-backdrop", "image", "label",
        "color-intensity", "color-preview-clipped", "color-preview-hdr", "span"));

    @Test
    void theGarishThemeRestylesEveryPartOfEveryControl() {
        ControlsGallery.Gallery[] a = new ControlsGallery.Gallery[1];
        ControlsGallery.Gallery[] b = new ControlsGallery.Gallery[1];
        Fixture plain = gallery(Fixture.DEFAULT, a);
        Fixture garish = gallery(Fixture.GARISH, b);
        a[0].openOverlays();
        b[0].openOverlays();
        plain.settle();
        garish.settle();
        UiStore ps = plain.ui.store();
        UiStore gs = garish.ui.store();
        assertThat(ps.rootCount()).isEqualTo(gs.rootCount());
        List<String> same = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (int i = 0; i < ps.rootCount(); i++) {
            compare(plain, ps.rootAt(i), garish, gs.rootAt(i), same, seen);
        }
        assertThat(same).isEmpty();
        assertThat(seen).as("the gallery shows every part").containsAll(TYPES);
    }

    /**
     * Compares the trees at {@code a} and {@code b} element by element, where they have the same shape (the themes'
     * spinners have different dot counts, a toolbar may overflow in one only).
     */
    private static void compare(Fixture plain, int a, Fixture garish, int b, List<String> same, Set<String> seen) {
        UiStore ps = plain.ui.store();
        UiStore gs = garish.ui.store();
        if (!gs.type(b).equals(ps.type(a))) {
            return; // laid out differently (a toolbar overflowing in one theme)
        }
        if (TYPES.contains(ps.type(a))) {
            seen.add(ps.type(a));
            if (!differs(plain.ui.styles().styleOf(a), garish.ui.styles().styleOf(b))) {
                same.add(ps.type(a) + "@" + a);
            }
        }
        int ca = ps.firstChild(a);
        int cb = gs.firstChild(b);
        while (ca != UiStore.NONE && cb != UiStore.NONE) {
            compare(plain, ca, garish, cb, same, seen);
            ca = ps.nextSibling(ca);
            cb = gs.nextSibling(cb);
        }
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
