package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DragDropTest {

    record Item(String name) {
    }

    static final DataFormat<Item> ITEM = DataFormat.of("test.item", Item.class);

    /** A slot with an item that starts a drag, a bag taking items, a shelf taking text only. */
    private static final class Scene {
        final Rig r = new Rig();
        final Element slot = r.button(r.root(), "slot", 0, 0, 50, 50);
        final Element bag = r.button(r.root(), "bag", 100, 0, 100, 100);
        final Element shelf = r.button(r.root(), "shelf", 300, 0, 100, 100);
        final Element icon = r.store.create("panel", "icon");
        final List<String> drops = new ArrayList<>();

        Scene() {
            r.ia.on(slot, EventType.DRAG_START, e -> e.startDrag(DragOperation.MOVE | DragOperation.COPY)
                .put(ITEM, new Item("sword")).put(DataFormat.TEXT, "sword").preview(icon, 4, 4));
            r.ia.on(bag, EventType.DROP_OVER, e -> {
                if (e.drag().has(ITEM)) {
                    e.acceptDrop(DragOperation.ALL);
                }
            });
            r.ia.on(bag, EventType.DROP, e -> drops.add(e.drag().get(ITEM).name() + " " + e.dropOperation()));
            r.ia.on(shelf, EventType.DROP_OVER, e -> {
                if (e.drag().has(DataFormat.of("other", String.class))) {
                    e.acceptDrop(DragOperation.MOVE);
                }
            });
        }
    }

    @Test
    void aDragStartsPastTheThresholdAndDropsWhereATargetAccepts() {
        Scene s = new Scene();
        Rig r = s.r;
        r.mouse(10, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(13, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.DRAG_START)).isEmpty(); // within the threshold
        r.mouse(30, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.DRAG_START, EventType.DRAG)).containsExactly("DRAG_START slot");
        DragSession session = r.ia.dragSession(0);
        assertThat(session.active()).isTrue();
        assertThat(session.source()).isEqualTo(s.slot);
        assertThat(session.preview()).isEqualTo(s.icon);
        assertThat(session.previewOffsetX()).isEqualTo(4);
        assertThat(session.previewOffsetY()).isEqualTo(4);
        assertThat(session.pointerId()).isZero();
        assertThat(session.allowed()).isEqualTo(DragOperation.MOVE | DragOperation.COPY);
        assertThat(s.slot.is(UiState.DRAGGING)).isTrue();
        assertThat(session.target()).isNull();

        r.mouse(120, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.DRAG, EventType.DROP_ENTER, EventType.DROP_OVER)).containsExactly("DRAG slot",
            "DROP_OVER bag", "DROP_ENTER bag");
        assertThat(session.target()).isEqualTo(s.bag);
        assertThat(session.operation()).isEqualTo(DragOperation.MOVE);
        assertThat(s.bag.is(UiState.DROP_TARGET)).isTrue();
        r.mouse(130, 10, Pointer.PRIMARY).modifiers(Pointer.DRAG_COPY);
        r.frame();
        assertThat(session.operation()).isEqualTo(DragOperation.COPY);
        assertThat(session.x()).isEqualTo(130);
        assertThat(session.y()).isEqualTo(10);

        r.mouse(320, 10, Pointer.PRIMARY).modifiers(0);
        r.frame();
        assertThat(r.take(EventType.DROP_LEAVE, EventType.DROP_ENTER)).containsExactly("DROP_LEAVE bag");
        assertThat(session.operation()).isEqualTo(DragOperation.NONE); // the shelf does not take items
        assertThat(s.bag.is(UiState.DROP_TARGET)).isFalse();

        r.mouse(150, 50, Pointer.PRIMARY);
        r.frame();
        r.mouse(150, 50, 0);
        r.frame();
        assertThat(s.drops).containsExactly("sword 1");
        assertThat(r.take(EventType.DROP, EventType.DRAG_END, EventType.CLICK)).containsExactly("DROP bag",
            "DRAG_END slot");
        assertThat(session.active()).isFalse();
        assertThat(s.slot.is(UiState.DRAGGING) || s.bag.is(UiState.DROP_TARGET)).isFalse();
    }

    @Test
    void releasingOverNothingOrCancellingEndsTheDragWithoutADrop() {
        Scene s = new Scene();
        Rig r = s.r;
        List<Integer> ended = new ArrayList<>();
        r.ia.on(s.slot, EventType.DRAG_END, e -> ended.add(e.dropOperation()));
        r.mouse(10, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(500, 500, Pointer.PRIMARY);
        r.frame();
        r.mouse(500, 500, 0);
        r.frame();
        assertThat(ended).containsExactly(DragOperation.NONE);
        r.take();

        r.mouse(10, 10, Pointer.PRIMARY);
        r.frame();
        r.mouse(120, 10, Pointer.PRIMARY);
        r.frame();
        assertThat(r.ia.dragSession(0).target()).isEqualTo(s.bag);
        r.command(UiCommand.CANCEL).frame();
        assertThat(r.take(EventType.DROP_LEAVE, EventType.DROP, EventType.DRAG_END)).containsExactly(
            "DROP_LEAVE bag", "DRAG_END slot");
        assertThat(r.ia.dragSession(0).active()).isFalse();
        r.mouse(120, 10, 0);
        r.frame();
        assertThat(s.drops).isEmpty();
        assertThat(r.take(EventType.CLICK)).isEmpty(); // a cancelled drag is no click
        assertThat(ended).containsExactly(DragOperation.NONE, DragOperation.NONE);
    }

    @Test
    void plainDragsWithoutASessionAndProgrammaticCancels() {
        Rig r = new Rig();
        Element thumb = r.button(r.root(), "thumb", 0, 0, 20, 20);
        List<String> moved = new ArrayList<>();
        r.ia.on(thumb, EventType.DRAG, e -> moved.add(e.deltaX() + " " + (e.x() - e.pressX()) + " " + e.drag()));
        r.mouse(5, 5, Pointer.PRIMARY);
        r.frame();
        r.mouse(15, 5, Pointer.PRIMARY);
        r.frame();
        r.mouse(25, 5, Pointer.PRIMARY);
        r.frame();
        assertThat(moved).containsExactly("10.0 20.0 null");
        r.ia.cancelDrag(0);
        r.ia.cancelDrag(42);
        assertThat(r.take(EventType.DRAG_END)).containsExactly("DRAG_END thumb");
        r.mouse(25, 5, 0);
        r.frame();
        assertThat(r.take(EventType.CLICK, EventType.DRAG_END)).isEmpty();
        assertThat(r.ia.dragSession(42)).isNull();

        // a finger needs to move further
        r.pointer(3, Pointer.Kind.TOUCH, 5, 5, Pointer.PRIMARY);
        r.frame();
        r.pointer(3, Pointer.Kind.TOUCH, 14, 5, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.DRAG_START)).isEmpty();
        r.pointer(3, Pointer.Kind.TOUCH, 25, 5, Pointer.PRIMARY);
        r.frame();
        assertThat(r.take(EventType.DRAG_START)).containsExactly("DRAG_START thumb");
        r.input.removePointer(3);
        r.frame();
        assertThat(r.take(EventType.DRAG_END)).containsExactly("DRAG_END thumb");
    }

    @Test
    void sessionsKeepTypedPayloadsAndOperationsFollowPreference() {
        DragSession s = new DragSession();
        s.begin(1, null, DragOperation.ALL, 0, 0);
        s.put(ITEM, new Item("a")).put(ITEM, new Item("b"));
        assertThat(s.get(ITEM).name()).isEqualTo("b");
        assertThat(s.get(DataFormat.TEXT)).isNull();
        assertThat(s.has(DataFormat.TEXT)).isFalse();
        for (int i = 1; i < DragSession.MAX_FORMATS; i++) {
            s.put(DataFormat.of("f" + i, String.class), "x");
        }
        assertThatThrownBy(() -> s.put(DataFormat.TEXT, "x")).isInstanceOf(IllegalStateException.class);
        s.clear();
        assertThat(s.has(ITEM)).isFalse();
        assertThat(ITEM.name()).isEqualTo("test.item");
        assertThat(ITEM.type()).isEqualTo(Item.class);
        assertThat(ITEM.toString()).isEqualTo("test.item");

        assertThat(DragOperation.preferred(DragOperation.ALL, 0)).isEqualTo(DragOperation.MOVE);
        assertThat(DragOperation.preferred(DragOperation.ALL, Pointer.DRAG_LINK)).isEqualTo(DragOperation.LINK);
        assertThat(DragOperation.preferred(DragOperation.MOVE, Pointer.DRAG_LINK)).isEqualTo(DragOperation.MOVE);
        assertThat(DragOperation.preferred(DragOperation.MOVE, Pointer.DRAG_COPY)).isEqualTo(DragOperation.MOVE);
        assertThat(DragOperation.preferred(DragOperation.COPY | DragOperation.LINK, 0)).isEqualTo(DragOperation.COPY);
        assertThat(DragOperation.preferred(DragOperation.LINK, 0)).isEqualTo(DragOperation.LINK);
        assertThat(DragOperation.preferred(DragOperation.NONE, 0)).isEqualTo(DragOperation.NONE);
    }
}
