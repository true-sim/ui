package com.truesim.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.ControlsGallery;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.LayoutSanity;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pseudo-localisation, the text scale and the palette: settings applied to a running UI. */
class TextSettingsTest {

    private final UiInput input = new UiInput(4);
    private long nanos = 1_000_000_000L;

    @AfterEach
    void reset() {
        PseudoLocale.MODE.select("off");
        PseudoLocale.EXPANSION.set(PseudoLocale.EXPANSION.initial());
        StyleResolver.TEXT_SCALE.set(1);
        Ui.PALETTE.select("theme");
    }

    private void frames(Ui ui, int n) {
        for (int i = 0; i < n; i++) {
            input.beginFrame();
            ui.update(800, 600, input, nanos += 16_000_000L);
        }
    }

    @Test
    void theTransformAccentsExpandsAndBrackets() {
        StringBuilder out = new StringBuilder();
        assertThat(PseudoLocale.apply("Save", out, 0.5f, false).toString()).isEqualTo("[\u0160\u00E0v\u00E9 \u0160\u00E0]");
        assertThat(PseudoLocale.apply("Open file", out, 0, false).toString())
            .isEqualTo("[\u00D6p\u00E9\u00F1 f\u00EE\u013C\u00E9]");
        assertThat(PseudoLocale.apply("", out, 0.35f, false).toString()).isEqualTo("[]");
        assertThat(PseudoLocale.apply("ab", out, 2f, true).toString())
            .isEqualTo("\u202E[\u00E0b \u00E0b\u00E0b]\u202C");
        assertThat(PseudoLocale.tunables()).containsExactly(PseudoLocale.MODE, PseudoLocale.EXPANSION);
    }

    @Test
    void labelsShowPseudoLocalisedTextAndKeepTheirNames() {
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        Element label = ui.label(null, "Settings");
        Element row = ui.panel(null);
        ui.styles().inline(row.slot(), com.truesim.ui.style.InlineStyle.builder()
            .set(Styles.LAYOUT, com.truesim.ui.style.value.LayoutKind.FLEX)
            .set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW)
            .set(Styles.ALIGN_ITEMS, com.truesim.ui.style.value.Align.START).build());
        ui.root().add(row.add(label));
        frames(ui, 2);
        float plain = ui.layout().width(label.slot());
        PseudoLocale.MODE.select("on");
        frames(ui, 2);
        assertThat(PseudoLocale.active()).isTrue();
        float pseudo = ui.layout().width(label.slot());
        assertThat(pseudo).isGreaterThan(plain * 1.3f); // longer: brackets and 35 % more letters
        assertThat(ui.text(label).toString()).isEqualTo("Settings");
        assertThat(ui.store().label(label.slot())).isEqualTo("Settings");
        PseudoLocale.MODE.select("mirrored");
        frames(ui, 2);
        PseudoLocale.MODE.select("off");
        frames(ui, 2);
        assertThat(ui.layout().width(label.slot())).isEqualTo(plain);
    }

    @Test
    void theTextScaleScalesEveryFontSize() {
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        Element label = ui.label(null, "Scaled");
        ui.root().add(label);
        frames(ui, 2);
        float size = ui.styles().styleOf(label.slot()).getFloat(Styles.FONT_SIZE);
        float height = ui.layout().height(label.slot());
        StyleResolver.TEXT_SCALE.set(1.5f);
        frames(ui, 2);
        assertThat(ui.styles().styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(size * 1.5f);
        assertThat(ui.layout().height(label.slot())).isGreaterThan(height * 1.3f);
        StyleResolver.TEXT_SCALE.set(1);
        frames(ui, 2);
        assertThat(ui.styles().styleOf(label.slot()).getFloat(Styles.FONT_SIZE)).isEqualTo(size);
        assertThat(ui.tunables()).contains(StyleResolver.TEXT_SCALE, Ui.PALETTE, PseudoLocale.MODE);
    }

    @Test
    void thePaletteSwitchesTokenSets() {
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        Element panel = ui.panel("card");
        ui.root().add(panel);
        frames(ui, 2);
        Object before = ui.styles().styleOf(panel.slot()).get(Styles.BACKGROUND);
        Ui.PALETTE.select("high-contrast");
        frames(ui, 2);
        assertThat(ui.styles().tokens().active()).isEqualTo("high-contrast");
        assertThat(ui.styles().styleOf(panel.slot()).get(Styles.BACKGROUND)).isNotEqualTo(before);
        Ui.PALETTE.select("theme");
        frames(ui, 2);
        assertThat(ui.styles().tokens().active()).isNull();
        assertThat(ui.styles().styleOf(panel.slot()).get(Styles.BACKGROUND)).isEqualTo(before);
    }

    /**
     * The controls gallery pseudo-localised, in high contrast at a larger text scale: built and laid out without
     * failing. Its {@link LayoutSanity} findings are printed, not asserted: they are the other controls' room for
     * translations, for their areas to look at (the report lists them).
     */
    @Test
    void theControlsGalleryPseudoLocalised() {
        PseudoLocale.MODE.select("on");
        Ui.PALETTE.select("high-contrast");
        StyleResolver.TEXT_SCALE.set(1.25f);
        Ui ui = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        ControlsGallery.registerIcons(ui);
        ControlsGallery.build(new Controls(ui), ui.root());
        frames(ui, 60);
        List<LayoutSanity.Violation> defects = LayoutSanity.check(ui);
        System.out.println("pseudo-localised controls gallery: " + defects.size() + " layout findings");
        defects.stream().limit(20).forEach(d -> System.out.println("  " + d));
        int labels = 0;
        UiStore store = ui.store();
        for (int s = 0; s < store.highWater(); s++) {
            if (store.alive(s) && "label".equals(store.type(s))) {
                labels++;
            }
        }
        assertThat(labels).isPositive();
    }
}
