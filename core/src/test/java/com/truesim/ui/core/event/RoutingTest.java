package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.Pointer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingTest {

    @Test
    void eventsRunCaptureThenTargetThenBubbleAndConsumingStopsThem() {
        Rig r = new Rig();
        Element panel = r.box(r.root(), "panel", 0, 0, 200, 200);
        Element button = r.button(panel, "button", 10, 10, 50, 50);
        r.logCapture(r.root());
        r.logAll(r.root());
        r.logCapture(panel);
        r.logAll(panel);
        r.logCapture(button);
        r.click(20, 20);
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK button capture@root", "CLICK button capture@panel",
            "CLICK button", "CLICK button", "CLICK button bubble@panel", "CLICK button bubble@root");

        r.ia.onCapture(panel, EventType.CLICK, UiEvent::consume);
        r.click(20, 20);
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK button capture@root",
            "CLICK button capture@panel");
    }

    @Test
    void anUnconsumedPressPassesToTheNextHitBelowWithoutVisitingAncestorsTwice() {
        Rig r = new Rig();
        Element under = r.button(r.root(), "under", 0, 0, 100, 100);
        Element card = r.box(r.root(), "card", 0, 0, 100, 100);
        Element over = r.button(card, "over", 10, 10, 50, 50);
        r.logAll(r.root());
        r.mouse(20, 20, Pointer.PRIMARY);
        r.frame();
        // the root listens, so it is a hit too (behind everything): it got its bubble from "over" already
        assertThat(r.take(EventType.PRESS)).containsExactly("PRESS over", "PRESS over bubble@root", "PRESS under",
            "PRESS root");
        assertThat(r.ia.captured(0)).isEqualTo(over); // nobody consumed: the front-most hit captures

        r.mouse(20, 20, 0);
        r.frame();
        r.take();
        r.ia.on(under, EventType.PRESS, UiEvent::consume);
        r.mouse(20, 20, Pointer.PRIMARY);
        r.frame();
        assertThat(r.ia.captured(0)).isEqualTo(under); // the consumer captures
        assertThat(under.is(UiState.PRESSED)).isTrue();
        assertThat(over.is(UiState.PRESSED)).isFalse();
        r.mouse(20, 20, 0);
        r.frame();
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK under", "CLICK under bubble@root");
        assertThat(under.is(UiState.PRESSED)).isFalse();
        assertThat(card.alive()).isTrue();
    }

    @Test
    void aPressCapturesThePointerAndAClickNeedsTheReleaseOverThePressedElement() {
        Rig r = new Rig();
        Element a = r.button(r.root(), "a", 0, 0, 50, 50);
        Element b = r.button(r.root(), "b", 100, 0, 50, 50);
        r.mouse(10, 10, Pointer.PRIMARY);
        r.frame();
        r.take();
        r.mouse(120, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.MOVE, EventType.ENTER, EventType.EXIT)).containsExactly("EXIT a", "ENTER b",
            "MOVE a"); // hover follows the pointer, the move goes to the capture
        assertThat(a.is(UiState.PRESSED)).isTrue();
        r.mouse(120, 10, 0);
        r.frame();
        assertThat(r.take(EventType.RELEASE, EventType.CLICK)).containsExactly("RELEASE a"); // dragged off: no click
        assertThat(r.ia.captured(0)).isNull();
        assertThat(b.is(UiState.HOVER)).isTrue();

        // c touches a; moves under the drag threshold
        Element c = r.button(r.root(), "c", 50, 0, 50, 50);
        r.mouse(48, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(52, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(52, 10, 0);
        r.frame();
        assertThat(r.take(EventType.RELEASE, EventType.CLICK)).containsExactly("RELEASE a"); // released over c
        r.mouse(48, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(52, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(49, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(49, 10, 0);
        r.frame();
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK a"); // back over it before release: a click
        assertThat(c.is(UiState.PRESSED)).isFalse();
    }

    @Test
    void clicksCountWithinTheDoubleClickTimeAndDistance() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 0, 0, 100, 100);
        List<Integer> counts = new ArrayList<>();
        r.ia.on(a, EventType.CLICK, e -> counts.add(e.clickCount()));
        r.click(10, 10).advance(0.1f).click(11, 10).advance(0.1f).click(12, 11);
        r.advance(1).click(12, 11);
        r.advance(0.1f).click(60, 60); // too far from the last
        assertThat(counts).containsExactly(1, 2, 3, 1, 1);
    }

    @Test
    void hoverEntersAndExitsEachElementOfTheChainPerPointer() {
        Rig r = new Rig();
        Element panel = r.box(r.root(), "panel", 0, 0, 200, 100);
        r.logAll(panel);
        r.ia.pickable(panel, PointerEvents.ALL);
        Element a = r.button(panel, "a", 0, 0, 50, 50);
        Element b = r.button(panel, "b", 100, 0, 50, 50);
        r.mouse(10, 10, 0);
        r.frame();
        assertThat(r.take(EventType.ENTER, EventType.EXIT)).containsExactly("ENTER panel", "ENTER a");
        assertThat(panel.is(UiState.HOVER) && a.is(UiState.HOVER)).isTrue();
        assertThat(r.store.root().is(UiState.HOVER)).isTrue();
        r.mouse(120, 10, 0);
        r.frame();
        assertThat(r.take(EventType.ENTER, EventType.EXIT)).containsExactly("EXIT a", "ENTER b");
        assertThat(a.is(UiState.HOVER)).isFalse();

        // a finger over a too: two pointers, one hover count each
        r.pointer(7, Pointer.Kind.TOUCH, 10, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(a.is(UiState.HOVER) && b.is(UiState.HOVER)).isTrue();
        assertThat(r.ia.hovered(7)).isEqualTo(a);
        assertThat(r.ia.hovered(0)).isEqualTo(b);
        assertThat(r.ia.captured(7)).isEqualTo(a);
        r.mouse(170, 10, 0);
        r.frame();
        assertThat(b.is(UiState.HOVER)).isFalse();
        assertThat(panel.is(UiState.HOVER)).isTrue(); // the finger is still over it
        r.input.removePointer(7);
        r.frame();
        assertThat(r.take(EventType.RELEASE, EventType.CLICK, EventType.EXIT)).containsExactly("EXIT b", "RELEASE a",
            "RELEASE a bubble@panel", "CLICK a", "CLICK a bubble@panel", "EXIT a", "EXIT panel"); // lifted: a tap
        assertThat(a.is(UiState.HOVER) || a.is(UiState.PRESSED)).isFalse();
        assertThat(r.ia.hovered(7)).isNull();
        assertThat(r.ia.overUi(7)).isFalse();
    }

    @Test
    void disabledElementsGetNoEventsButKeepThePointerFromWhatIsBelow() {
        Rig r = new Rig();
        Element under = r.button(r.root(), "under", 0, 0, 100, 100);
        Element group = r.box(r.root(), "group", 0, 0, 100, 100);
        Element over = r.button(group, "over", 0, 0, 100, 100);
        r.ia.focusable(over, true);
        group.state(UiState.DISABLED, true);
        r.click(10, 10);
        assertThat(r.take(EventType.PRESS, EventType.CLICK, EventType.FOCUS_IN)).isEmpty();
        assertThat(r.ia.focused(0)).isNull();
        assertThat(r.ia.disabled(over.slot())).isTrue();
        assertThat(under.is(UiState.PRESSED)).isFalse();
        group.state(UiState.DISABLED, false);
        r.click(10, 10);
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK over");
        assertThat(r.ia.focused(0)).isEqualTo(over);
    }

    @Test
    void secondaryPressesAskForAContextMenuAndTheWheelPassesThroughToItsConsumer() {
        Rig r = new Rig();
        Element list = r.box(r.root(), "list", 0, 0, 100, 100);
        Element item = r.button(list, "item", 0, 0, 100, 20);
        List<Float> scrolled = new ArrayList<>();
        r.ia.on(list, EventType.WHEEL, e -> {
            scrolled.add(e.scrollY());
            e.consume();
        });
        r.mouse(10, 10, Pointer.SECONDARY);
        r.frame();
        assertThat(r.take(EventType.PRESS, EventType.CONTEXT)).containsExactly("PRESS item", "CONTEXT item");
        r.mouse(10, 10, 0).scroll(0, -3);
        r.frame();
        assertThat(scrolled).containsExactly(-3f);
        assertThat(r.take(EventType.WHEEL, EventType.CLICK)).containsExactly("WHEEL item"); // no click: secondary
        assertThat(item.alive()).isTrue();
    }

    @Test
    void aLongPressFiresOnceAndWhenConsumedSuppressesTheClick() {
        Rig r = new Rig();
        Element a = r.button(r.root(), "a", 0, 0, 100, 100);
        r.mouse(10, 10, Pointer.PRIMARY);
        r.frame();
        r.advance(0.3f).frame();
        assertThat(r.take(EventType.LONG_PRESS)).isEmpty();
        r.advance(0.3f).frame();
        r.advance(0.3f).frame();
        assertThat(r.take(EventType.LONG_PRESS, EventType.CONTEXT)).containsExactly("LONG_PRESS a"); // a mouse: no menu
        r.mouse(10, 10, 0);
        r.frame();
        assertThat(r.take(EventType.CLICK)).containsExactly("CLICK a"); // not consumed: still a click

        r.ia.on(a, EventType.LONG_PRESS, UiEvent::consume);
        r.pointer(3, Pointer.Kind.TOUCH, 10, 10, Pointer.PRIMARY);
        r.frame();
        r.advance(0.6f).frame();
        assertThat(r.take(EventType.LONG_PRESS, EventType.CONTEXT)).containsExactly("LONG_PRESS a", "CONTEXT a");
        r.pointer(3, Pointer.Kind.TOUCH, 10, 10, 0);
        r.frame();
        assertThat(r.take(EventType.CLICK)).isEmpty();
    }

    @Test
    void aRestingPointerAsksForATooltipOncePerElement() {
        Rig r = new Rig();
        Element a = r.button(r.root(), "a", 0, 0, 50, 50);
        Element b = r.button(r.root(), "b", 100, 0, 50, 50);
        r.mouse(10, 10, 0);
        r.frame();
        r.advance(0.5f).frame();
        assertThat(r.take(EventType.TOOLTIP)).isEmpty();
        r.advance(0.2f).frame();
        r.advance(1).frame();
        assertThat(r.take(EventType.TOOLTIP)).containsExactly("TOOLTIP a");
        r.mouse(11, 10, 0);
        r.frame(); // moving restarts the rest
        r.advance(0.7f).frame();
        assertThat(r.take(EventType.TOOLTIP)).isEmpty(); // once per element
        r.mouse(110, 10, 0);
        r.frame();
        r.advance(0.7f).frame();
        assertThat(r.take(EventType.TOOLTIP)).containsExactly("TOOLTIP b");
        assertThat(a.alive() && b.alive()).isTrue();
    }

    @Test
    void pressesOutsideReachElementsWatchingForThem() {
        Rig r = new Rig();
        Element popup = r.button(r.layers.get("popup").root(), "popup", 0, 0, 50, 50);
        Element item = r.button(popup, "item", 0, 0, 50, 20);
        Element other = r.button(r.root(), "other", 100, 100, 50, 50);
        List<String> closed = new ArrayList<>();
        UiListener close = e -> closed.add(r.name(e.targetSlot()));
        r.ia.on(popup, EventType.PRESS_OUTSIDE, close);
        r.click(10, 10); // inside
        assertThat(closed).isEmpty();
        r.click(120, 120);
        assertThat(closed).containsExactly("popup");
        r.ia.off(popup, close);
        r.click(120, 120);
        assertThat(closed).hasSize(1);
        assertThat(item.alive() && other.alive()).isTrue();
    }

    @Test
    void listenersRunInOrderAndAreForgottenWhenTheSlotIsReused() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 0, 0, 50, 50);
        List<String> order = new ArrayList<>();
        UiListener first = e -> order.add("first");
        r.ia.on(a, EventType.CLICK, first);
        r.ia.on(a, EventType.CLICK.bit() | EventType.PRESS.bit(), e -> order.add("second " + e.type()));
        r.click(10, 10);
        assertThat(order).containsExactly("second PRESS", "first", "second CLICK");
        assertThat(r.ia.listening(a)).isEqualTo(EventType.CLICK.bit() | EventType.PRESS.bit());
        int slot = a.slot();
        a.destroy();
        Element b = r.store.create("panel", "b");
        r.root().add(b);
        assertThat(b.slot()).isEqualTo(slot);
        r.place(b, 0, 0, 50, 50);
        assertThat(r.ia.listening(b)).isZero();
        order.clear();
        r.click(10, 10);
        assertThat(order).isEmpty(); // not interactive any more: not even picked
        assertThatThrownBy(() -> r.ia.on(b, EventType.CLICK, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customEventsCaptureCursorsAndQueries() {
        Rig r = new Rig();
        Element panel = r.box(r.root(), "panel", 0, 0, 100, 100);
        Element a = r.button(panel, "a", 0, 0, 50, 50);
        r.geo.cursors[panel.slot()] = Cursor.POINTER;
        r.mouse(10, 10, 0);
        r.frame();
        assertThat(r.ia.cursor(0)).isEqualTo(Cursor.POINTER); // from the ancestor
        r.geo.cursors[a.slot()] = Cursor.TEXT;
        assertThat(r.ia.cursor(0)).isEqualTo(Cursor.TEXT);
        assertThat(r.ia.cursor(42)).isEqualTo(Cursor.DEFAULT);
        r.mouse(500, 500, 0);
        r.frame();
        assertThat(r.ia.cursor(0)).isEqualTo(Cursor.DEFAULT);
        assertThat(r.ia.overUi(0)).isFalse();

        r.ia.on(a, EventType.CLICK, UiEvent::consume);
        assertThat(r.ia.dispatch(EventType.CLICK, a, 0)).isTrue();
        assertThat(r.ia.dispatch(EventType.MOVE, panel, 0)).isFalse();

        r.ia.capture(0, a);
        assertThat(r.ia.captured(0)).isEqualTo(a);
        assertThat(r.ia.overUi(0)).isTrue(); // captured: the UI keeps it
        r.mouse(520, 500, 0);
        r.frame();
        assertThat(r.take(EventType.MOVE)).containsExactly("MOVE a");
        r.ia.releaseCapture(0, panel); // not the capturing element: nothing
        assertThat(r.ia.captured(0)).isEqualTo(a);
        r.ia.releaseCapture(0, a);
        assertThat(r.ia.captured(0)).isNull();
        r.ia.capture(42, a); // unknown pointer: nothing
        r.ia.releaseCapture(42, a);
        assertThat(r.ia.captured(42)).isNull();
        assertThat(r.ia.hovered(42)).isNull();
        assertThat(r.ia.now()).isEqualTo(r.now);
        assertThat(r.ia.store()).isSameAs(r.store);
    }

    @Test
    void eventsCarryThePointerAndLocalPositions() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 40, 30, 100, 100);
        List<String> seen = new ArrayList<>();
        r.ia.on(a, EventType.PRESS, e -> seen.add(e.pointerId() + " " + e.pointerKind() + " " + e.player() + " "
            + e.localX() + "," + e.localY() + " " + e.button() + " " + e.buttons() + " " + e.modifiers() + " "
            + e.currentTarget() + " " + e.target() + " " + (e.related() == null) + " " + e.time()));
        r.pointer(5, Pointer.Kind.PEN, 50, 40, Pointer.PRIMARY).player(2).modifiers(Pointer.SHIFT);
        r.frame();
        assertThat(seen).containsExactly("5 PEN 2 10.0,10.0 1 1 1 " + a + " " + a + " true " + r.now);
        UiEvent event = new UiEvent(r.ia);
        event.reset(EventType.CLICK, a.slot(), 0);
        assertThat(event.toString()).isEqualTo("CLICK@" + a.slot());
        event.phase = Phase.BUBBLE;
        assertThat(event.toString()).contains("BUBBLE");
        assertThatThrownBy(() -> event.startDrag(DragOperation.MOVE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.acceptDrop(DragOperation.MOVE)).isInstanceOf(IllegalStateException.class);
        assertThat(event.dropOperation()).isEqualTo(DragOperation.NONE);
        assertThat(event.interaction()).isSameAs(r.ia);
        assertThat(UiStore.NONE).isEqualTo(-1);
    }

    @Test
    void nestedDispatchesHaveTheirOwnEvents() {
        Rig r = new Rig();
        Element a = r.button(r.root(), "a", 0, 0, 50, 50);
        Element b = r.box(r.root(), "b", 100, 0, 50, 50);
        r.ia.focusable(b, true);
        List<String> seen = new ArrayList<>();
        r.ia.on(a, EventType.CLICK, e -> {
            r.ia.focus(b); // dispatches FOCUS_IN while the click is being dispatched
            seen.add(e.type() + " " + e.clickCount());
        });
        r.click(10, 10);
        assertThat(seen).containsExactly("CLICK 1");
        assertThat(r.ia.focused(0)).isEqualTo(b);
        UiListener deep = new UiListener() {
            int depth;

            @Override
            public void handle(UiEvent e) {
                if (depth++ < 40) {
                    r.ia.dispatch(EventType.TOOLTIP, b, 0);
                }
            }
        };
        r.ia.on(b, EventType.TOOLTIP, deep);
        assertThatThrownBy(() -> r.ia.dispatch(EventType.TOOLTIP, b, 0)).isInstanceOf(IllegalStateException.class);
        r.click(10, 10); // the interaction still works after a listener threw
        assertThat(seen).hasSize(2);
    }
}
