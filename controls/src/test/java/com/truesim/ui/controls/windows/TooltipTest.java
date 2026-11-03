package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.overlay.Placement;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tooltips: the delay on the UI clock, hiding, warm moves, follow mode, rich content, touch and focus. */
class TooltipTest {

    private static float delay() {
        return Interaction.TOOLTIP_DELAY.get();
    }

    @Test
    void itShowsAfterThePointerRestsForTheDelayAboveItsTargetAndHidesWhenThePointerLeaves() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element save = f.box(100, 100, 120, 30);
        tips.text(save, "Save the scene");
        f.run();
        f.mouse(150, 115);
        f.idle(delay() - 0.1);
        assertThat(tips.showing()).isFalse();
        f.idle(0.15);
        assertThat(tips.showing()).isTrue();
        assertThat(tips.showingFor()).isEqualTo(save);
        Element tip = tips.element();
        assertThat(tip.parent()).isEqualTo(f.ui.layer(com.truesim.ui.core.Layers.TOOLTIP).root());
        assertThat(f.y(tip) + f.h(tip)).isEqualTo(94); // above the target at the theme's 6 px gap
        assertThat(Math.abs(f.cx(tip) - f.cx(save))).isLessThanOrEqualTo(1);
        assertThat(tips.overlay().side()).isEqualTo(Placement.TOP);
        assertThat(Semantics.of(f.ui.store(), f.ui.interaction()).toString()).contains("TOOLTIP \"Save the scene\"");
        f.mouse(160, 118);
        assertThat(tips.showing()).isTrue(); // moving on the target keeps it
        f.mouse(600, 600);
        assertThat(tips.showing()).isFalse();
        assertThat(tips.showingFor()).isNull();
    }

    @Test
    void movingToAnotherTargetWhileOneShowsShowsItsTooltipAtOnceAndTheWarmTimeRunsOut() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        Element b = f.box(200, 100, 100, 30);
        tips.text(a, "A");
        tips.text(b, "B");
        f.run().mouse(150, 115).idle(delay() + 0.05);
        assertThat(tips.showingFor()).isEqualTo(a);
        f.mouse(250, 115);
        assertThat(tips.showingFor()).isEqualTo(b); // at once, no delay
        f.mouse(700, 600).idle(Tooltips.WARM_TIME.get() + 0.1);
        f.mouse(150, 115);
        assertThat(tips.showing()).isFalse(); // cold again: the delay applies
        f.idle(delay() + 0.05);
        assertThat(tips.showingFor()).isEqualTo(a);
        f.mouse(700, 600).mouse(250, 115); // left and came back within the warm time
        assertThat(tips.showingFor()).isEqualTo(b);
        tips.text(b, "B2"); // a new text while it shows
        f.run();
        assertThat(f.ui.text(tipLabel(tips)).toString()).isEqualTo("B2");
    }

    private static Element tipLabel(Tooltips tips) {
        Element tip = tips.element();
        return tip.store().element(tip.store().firstChild(tip.slot()));
    }

    @Test
    void aPressOrTheWheelHidesItAndItDoesNotComeBackUntilThePointerMovesOn() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        tips.text(a, "A");
        f.run().mouse(150, 115).idle(delay() + 0.05);
        assertThat(tips.showing()).isTrue();
        f.press(150, 115);
        assertThat(tips.showing()).isFalse();
        f.release().idle(delay() + 0.1);
        assertThat(tips.showing()).isFalse();
        f.mouse(700, 600).mouse(150, 115).idle(delay() + 0.05);
        assertThat(tips.showing()).isTrue();
        f.in.scroll(0, 0, 1);
        f.run();
        assertThat(tips.showing()).isFalse();
        // code shows and hides it too
        tips.show(a);
        f.run();
        assertThat(tips.showing()).isTrue();
        tips.hide();
        assertThat(tips.showing()).isFalse();
        tips.remove(a);
        assertThat(tips.has(a)).isFalse();
    }

    @Test
    void inFollowModeItSitsAtThePointerAndFollowsItAndRichContentReplacesTheText() {
        Fx f = new Fx(Fx.garish(), true); // the test theme: tooltip-mode follow, below the pointer, 13 px off
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 300, 100);
        Element rich = f.ui.panel(null);
        Element line = f.ui.label(null, "rich");
        rich.add(line);
        tips.content(a, rich, "Rich help");
        f.run().mouse(150, 120).idle(delay() + 0.05);
        Element tip = tips.element();
        assertThat(tips.showing()).isTrue();
        assertThat(rich.parent()).isEqualTo(tip);
        assertThat(f.x(tip)).isEqualTo(150);
        assertThat(f.y(tip)).isEqualTo(133);
        f.mouse(170, 140);
        assertThat(f.x(tip)).isEqualTo(170);
        assertThat(f.y(tip)).isEqualTo(153);
        assertThat(Semantics.of(f.ui.store(), f.ui.interaction()).toString()).contains("TOOLTIP \"Rich help\"");
        // a text tooltip on another target takes the label back
        Element b = f.box(500, 100, 100, 100);
        tips.text(b, "plain");
        f.run(); // b is laid out: pointers pick what was drawn
        f.mouse(550, 150);
        assertThat(tips.showingFor()).isEqualTo(b); // warm
        assertThat(rich.parent()).isNull();
    }

    @Test
    void aLongTouchShowsItWithoutClickingAndLiftingTheFingerHidesIt() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        tips.text(a, "A");
        List<String> clicks = new ArrayList<>();
        f.ui.on(a, EventType.CLICK, e -> clicks.add("click"));
        f.run();
        f.in.pointer(7, Pointer.Kind.TOUCH, 150, 115, Pointer.PRIMARY);
        f.run().idle(Interaction.LONG_PRESS_DELAY.get() + 0.05);
        assertThat(tips.showingFor()).isEqualTo(a);
        f.in.pointer(7, Pointer.Kind.TOUCH, 150, 115, 0);
        f.run();
        assertThat(tips.showing()).isFalse();
        assertThat(clicks).isEmpty();
        f.in.lift(7);
        f.run();
    }

    @Test
    void visibleFocusShowsItAfterTheDelayAndLosingFocusHidesIt() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        Element b = f.box(300, 100, 100, 30);
        f.ui.interaction().focusable(a, true);
        f.ui.interaction().focusable(b, true);
        a.semantics(Role.BUTTON, "A");
        b.semantics(Role.BUTTON, "B");
        tips.text(a, "Tip of A");
        f.run();
        f.command(UiCommand.NEXT);
        assertThat(f.ui.interaction().focused(0)).isEqualTo(a);
        f.idle(delay() - 0.1);
        assertThat(tips.showing()).isFalse();
        f.idle(0.15);
        assertThat(tips.showingFor()).isEqualTo(a);
        f.mouse(900, 700); // the pointer moving elsewhere leaves a focus tooltip alone
        assertThat(tips.showing()).isTrue();
        f.command(UiCommand.NEXT);
        assertThat(tips.showing()).isFalse();
        // focus by a click shows no tooltip (no focus ring either)
        f.command(UiCommand.PREVIOUS);
        f.command(UiCommand.NEXT); // away before the delay: nothing
        f.idle(delay() + 0.1);
        assertThat(tips.showing()).isFalse();
        assertThat(Tooltips.tunables()).contains(Tooltips.WARM_TIME);
    }

    @Test
    void aTargetThatGoesAwayTakesItsTooltipAlong() {
        Fx f = new Fx();
        Tooltips tips = new Tooltips(f.overlays);
        Element a = f.box(100, 100, 100, 30);
        tips.text(a, "A");
        f.run().mouse(150, 115).idle(delay() + 0.05);
        assertThat(tips.showing()).isTrue();
        a.state(com.truesim.ui.core.UiState.DISABLED, true);
        f.run();
        assertThat(tips.showing()).isFalse();
        tips.text(a, null);
        assertThat(tips.has(a)).isFalse();
    }
}
