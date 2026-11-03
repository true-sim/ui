package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hover cards with hover intent, click popovers, nesting. */
class PopoverTest {

    private static Element label(Fx f, String text) {
        return f.ui.label(null, text);
    }

    @Test
    void aHoverCardOpensAfterTheDelayAndStaysWhileThePointerTravelsToIt() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        Popover card = popovers.hover(trigger, label(f, "A card about the trigger"));
        List<String> events = new ArrayList<>();
        card.listener(new Popover.Listener() {
            @Override
            public void opened(Popover p) {
                events.add("opened");
            }

            @Override
            public void closed(Popover p) {
                events.add("closed");
            }
        });
        f.run().mouse(150, 120);
        f.idle(Popovers.OPEN_DELAY.get() - 0.1);
        assertThat(card.isOpen()).isFalse();
        f.idle(0.15);
        assertThat(card.isOpen()).isTrue();
        Element surface = card.surface();
        assertThat(surface.type()).isEqualTo("hover-card");
        assertThat(f.y(surface)).isEqualTo(134); // below the trigger at the theme's 4 px gap
        assertThat(f.x(surface)).isEqualTo(100); // start-aligned
        // across the gap between trigger and card: over neither, but heading for the card
        f.mouse(150, 128).mouse(152, 132);
        f.idle(Popovers.CLOSE_DELAY.get() + 0.05);
        assertThat(card.isOpen()).isTrue();
        f.mouse(155, 150); // on the card
        f.idle(1);
        assertThat(card.isOpen()).isTrue();
        // away from both, not towards the card: the grace period, then it closes
        f.mouse(900, 600);
        assertThat(card.isOpen()).isTrue();
        f.idle(Popovers.CLOSE_DELAY.get() + 0.05);
        assertThat(card.isOpen()).isFalse();
        assertThat(events).containsExactly("opened", "closed");
    }

    @Test
    void lingeringInTheSafeTriangleRunsOutAndLeavingSidewaysClosesAfterTheGracePeriod() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        Popover card = popovers.hover(trigger, label(f, "A card"));
        f.run().mouse(150, 120).idle(Popovers.OPEN_DELAY.get() + 0.05);
        assertThat(card.isOpen()).isTrue();
        f.mouse(150, 129).mouse(150, 132); // in the triangle, then resting there
        f.idle(Popovers.INTENT_TIME.get() + 0.05);
        assertThat(card.isOpen()).isFalse();
        f.mouse(150, 120).idle(Popovers.OPEN_DELAY.get() + 0.05);
        assertThat(card.isOpen()).isTrue();
        f.mouse(150, 129).mouse(260, 115); // sideways: out of the triangle at once
        f.idle(Popovers.CLOSE_DELAY.get() - 0.1);
        assertThat(card.isOpen()).isTrue();
        f.idle(0.15);
        assertThat(card.isOpen()).isFalse();
        assertThat(Popovers.inTriangle(5, 5, 0, 0, 10, 0, 0, 10)).isTrue();
        assertThat(Popovers.inTriangle(9, 9, 0, 0, 10, 0, 0, 10)).isFalse();
    }

    @Test
    void aCardOpenedFromACardKeepsItsParentOpenAndBothCloseWhenThePointerLeavesThem() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        Element inner = f.ui.panel("card");
        inner.add(label(f, "more"));
        Popover parent = popovers.hover(trigger, inner);
        Popover child = popovers.hover(inner, label(f, "the child card"));
        f.run().mouse(150, 120).idle(Popovers.OPEN_DELAY.get() + 0.05);
        assertThat(parent.isOpen()).isTrue();
        f.mouse(150, 128).mouse(f.cx(inner), f.cy(inner));
        f.idle(Popovers.OPEN_DELAY.get() + 0.05);
        assertThat(child.isOpen()).isTrue();
        assertThat(child.parent()).isSameAs(parent);
        assertThat(popovers.top()).isSameAs(child);
        f.mouse(f.cx(child.surface()), f.cy(child.surface()));
        f.idle(1);
        assertThat(parent.isOpen()).isTrue(); // the pointer in the child keeps the parent: no flicker
        assertThat(child.isOpen()).isTrue();
        f.mouse(1000, 700).idle(Popovers.CLOSE_DELAY.get() + 0.1);
        assertThat(child.isOpen()).isFalse();
        assertThat(parent.isOpen()).isFalse();
    }

    @Test
    void aClickPopoverTogglesOnItsTriggerAndClosesOnAPressOutsideAndOnCancel() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        trigger.semantics(Role.BUTTON, "Options");
        f.ui.interaction().focusable(trigger, true);
        Element content = f.ui.panel("card");
        Element field = f.ui.panel("card");
        field.semantics(Role.BUTTON, "Field");
        f.ui.interaction().focusable(field, true);
        content.add(label(f, "Options")).add(field);
        Popover pop = popovers.click(trigger, content, "Options");
        f.run();
        f.click(150, 115);
        assertThat(pop.isOpen()).isTrue();
        assertThat(pop.surface().type()).isEqualTo("popover");
        f.run();
        assertThat(Semantics.unnamed(Semantics.of(f.ui.store(), f.ui.interaction()))).isEmpty();
        assertThat(Semantics.of(f.ui.store(), f.ui.interaction()).toString()).contains("DIALOG \"Options\"");
        f.click(150, 115); // the trigger again: closes (the press outside it does not first close and reopen)
        assertThat(pop.isOpen()).isFalse();
        f.click(150, 115).run();
        assertThat(pop.isOpen()).isTrue();
        f.click(f.cx(pop.surface()), f.cy(pop.surface()) - 2); // inside: stays
        assertThat(pop.isOpen()).isTrue();
        f.click(900, 600);
        assertThat(pop.isOpen()).isFalse();
        // from the keyboard: ACCEPT opens it and focus moves in; CANCEL closes it and focus returns
        f.ui.interaction().focus(trigger);
        f.run().command(UiCommand.ACCEPT);
        assertThat(pop.isOpen()).isTrue();
        f.run().run();
        f.ui.interaction().focus(field);
        f.run();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(field);
        f.command(UiCommand.CANCEL);
        assertThat(pop.isOpen()).isFalse();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(trigger);
        f.click(150, 115).run();
        f.ui.interaction().focus(trigger);
        f.command(UiCommand.CANCEL); // CANCEL on the trigger closes it too
        assertThat(pop.isOpen()).isFalse();
        assertThat(popovers.count()).isEqualTo(1);
        assertThat(Popovers.tunables()).hasSize(3);
    }

    @Test
    void aPopoverOpenedFromAPopoverIsItsChildPressesInsideItKeepTheParentAndClosingTheParentClosesIt() {
        Fx f = new Fx();
        Popovers popovers = new Popovers(f.overlays);
        Element trigger = f.box(100, 100, 100, 30);
        trigger.semantics(Role.BUTTON, "Open");
        Element inner = f.ui.panel("card");
        inner.add(label(f, "Inner"));
        inner.semantics(Role.BUTTON, "Inner");
        Popover parent = popovers.click(trigger, inner, "Parent");
        Popover child = popovers.click(inner, label(f, "Child content"), "Child");
        f.run().click(150, 115).run();
        f.click(f.cx(inner), f.cy(inner)).run();
        assertThat(child.isOpen()).isTrue();
        assertThat(child.parent()).isSameAs(parent);
        f.click(f.cx(child.surface()), f.cy(child.surface()));
        assertThat(parent.isOpen()).isTrue();
        assertThat(child.isOpen()).isTrue();
        parent.close();
        assertThat(child.isOpen()).isFalse();
        f.run().click(150, 115).run().click(f.cx(inner), f.cy(inner)).run();
        assertThat(child.isOpen()).isTrue();
        f.click(1000, 650);
        assertThat(child.isOpen()).isFalse();
        assertThat(parent.isOpen()).isFalse();
        parent.open();
        child.open();
        popovers.closeAll();
        assertThat(parent.isOpen() || child.isOpen()).isFalse();
        assertThat(parent.toString()).startsWith("Popover[");
    }
}
