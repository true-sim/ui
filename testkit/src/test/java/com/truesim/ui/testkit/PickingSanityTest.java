package com.truesim.ui.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Radii;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link PickingSanity} over clips, rounded corners, layers taking no input and a blocking layer. */
class PickingSanityTest {

    private final Ui ui = new Ui(StyleSheet.empty(), null);

    private Element panel(float w, float h) {
        Element e = ui.panel(null);
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
        ui.on(e, EventType.PRESS, ev -> { });
        return e;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) {
            ui.update(400, 300);
        }
    }

    @Test
    void theHitTestAgreesWithWhatIsDrawnThroughClipsLayersAndBlocking() {
        Element viewport = panel(200, 120);
        ui.styles().inline(viewport.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(200))
            .set(Styles.HEIGHT, Length.px(120)).set(Styles.CLIP, true).set(Styles.RADIUS, Radii.of(30)).build());
        ui.root().add(viewport);
        viewport.add(panel(300, 300)); // overflows the viewport: clipped away outside it, and in its corners
        ui.root().add(panel(100, 50));
        Layer deco = ui.layerStack().add("test.deco", 50).input(Layer.Input.NONE);
        deco.root().add(panel(400, 300)); // takes no input: neither side counts it
        settle();
        assertThat(PickingSanity.check(ui, 400, 300)).isEmpty();
        PickingSanity.assertPicksWhatIsDrawn(ui, 400, 300, "clips and layers");
        // a modal dialog blocks what is below it
        ui.layer("modal").root().add(panel(50, 50));
        settle();
        assertThat(PickingSanity.check(ui, 400, 300)).isEmpty();
    }

    @Test
    void disagreementsAreListed() {
        PickingSanity.report("nothing", List.of());
        assertThatThrownBy(() -> PickingSanity.report("a scene", List.of("at (1, 2): hit a, drawn on top b")))
            .isInstanceOf(AssertionError.class).hasMessageContaining("a scene").hasMessageContaining("1 points")
            .hasMessageContaining("drawn on top b");
    }
}
