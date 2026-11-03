package com.truesim.ui.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiStoreTest {

    @Test
    void elementsFormATree() {
        UiStore store = new UiStore();
        Element root = store.root();
        Element a = store.create("panel", "a");
        Element b = store.create("label", "b");
        Element c = store.create("label", "c");
        root.add(a);
        a.add(b).add(c);
        assertThat(b.parent()).isSameAs(a);
        assertThat(store.firstChild(a.slot())).isEqualTo(b.slot());
        assertThat(store.lastChild(a.slot())).isEqualTo(c.slot());
        assertThat(store.nextSibling(b.slot())).isEqualTo(c.slot());
        assertThat(store.previousSibling(c.slot())).isEqualTo(b.slot());
        assertThat(root.parent()).isNull();
        assertThat(store.count()).isEqualTo(4);
        assertThat(b.type()).isEqualTo("label");
        assertThat(b.id()).isEqualTo("b");
        assertThat(b.store()).isSameAs(store);

        b.detach();
        assertThat(store.firstChild(a.slot())).isEqualTo(c.slot());
        assertThat(store.previousSibling(c.slot())).isEqualTo(UiStore.NONE);
        b.detach(); // twice is nothing
        a.add(b);
        c.detach();
        assertThat(store.lastChild(a.slot())).isEqualTo(b.slot());
        a.add(c);
        a.add(b); // moves to the end
        assertThat(store.lastChild(a.slot())).isEqualTo(b.slot());
        assertThat(store.nextSibling(c.slot())).isEqualTo(b.slot());
    }

    @Test
    void destroyedSlotsAreReusedWithANewGeneration() {
        UiStore store = new UiStore();
        Element a = store.create("panel", null);
        Element child = store.create("panel", null);
        store.root().add(a);
        a.add(child);
        int slot = a.slot();
        a.destroy();
        assertThat(a.alive()).isFalse();
        assertThat(child.alive()).isFalse();
        assertThat(a.toString()).contains("destroyed");
        assertThatThrownBy(a::slot).isInstanceOf(IllegalStateException.class);
        assertThat(store.alive(slot)).isFalse();
        assertThat(store.element(slot)).isNull();
        assertThat(store.element(-1)).isNull();
        Element again = store.create("panel", null);
        Element again2 = store.create("panel", null);
        assertThat(List.of(again.slot(), again2.slot())).contains(slot);
        assertThat(a.alive()).isFalse();
    }

    @Test
    void misuseIsRefused() {
        UiStore store = new UiStore(new ChangeClock());
        Element a = store.create("panel", null);
        Element b = store.create("panel", null);
        a.add(b);
        assertThatThrownBy(() -> b.add(a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.add(store.root())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.destroy(store.rootSlot())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.detach(999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.detach(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changesQueueTheirSlotsOnce() {
        UiStore store = new UiStore();
        store.clearDirtyQueue();
        Element a = store.create("panel", "a");
        store.root().add(a);
        for (int i = 0; i < store.dirtyCount(); i++) {
            store.clearDirty(store.dirtySlots()[i], -1);
        }
        store.clearDirtyQueue();
        long version = store.version();

        a.state(UiState.HOVER, true);
        a.state(UiState.HOVER, true); // no change
        assertThat(store.dirtyCount()).isEqualTo(1);
        assertThat(store.dirty(a.slot())).isEqualTo(UiStore.DIRTY_STYLE);
        assertThat(a.is(UiState.HOVER)).isTrue();
        assertThat(store.version()).isGreaterThan(version);
        assertThat(store.changed(a.slot())).isEqualTo(store.version());
        a.markDirty(UiStore.DIRTY_DRAW);
        assertThat(store.dirtyCount()).isEqualTo(1);
        assertThat(store.dirty(a.slot())).isEqualTo(UiStore.DIRTY_STYLE | UiStore.DIRTY_DRAW);
        store.clearDirty(a.slot(), UiStore.DIRTY_STYLE);
        assertThat(store.dirty(a.slot())).isEqualTo(UiStore.DIRTY_DRAW);

        a.id("b").style("glass");
        assertThat(a.id()).isEqualTo("b");
        assertThat(a.style()).isEqualTo("glass");
        a.state(UiState.HOVER, false);
        assertThat(a.is(UiState.HOVER)).isFalse();

        store.markAll(UiStore.DIRTY_LAYOUT);
        assertThat(store.dirty(store.rootSlot()) & UiStore.DIRTY_LAYOUT).isNotZero();
    }

    @Test
    void theStoreGrowsAndTheQueueWithIt() {
        UiStore store = new UiStore();
        List<Element> made = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Element e = store.create("panel", null);
            store.root().add(e);
            made.add(e);
        }
        assertThat(store.capacity()).isGreaterThanOrEqualTo(301);
        assertThat(store.highWater()).isEqualTo(301);
        // destroy and remake while still queued: slots are queued again
        for (int round = 0; round < 3; round++) {
            for (Element e : made) {
                e.destroy();
            }
            made.clear();
            for (int i = 0; i < 300; i++) {
                made.add(store.create("panel", null));
            }
        }
        assertThat(store.dirtyCount()).isGreaterThan(store.capacity());
    }

    @Test
    void rangesAndSemanticsArePerSlot() {
        UiStore store = new UiStore();
        Element panel = store.create("panel", "hud").semantics(Role.GROUP, "HUD");
        Element label = store.create("label", null).semantics(Role.TEXT, "Health");
        Element deco = store.create("panel", null);
        Element icon = store.create("image", null).semantics(Role.IMAGE, null);
        store.root().add(panel);
        panel.add(deco);
        deco.add(label);
        deco.add(icon);
        store.range(label.slot(), 5, 8);
        assertThat(store.rangeStart(label.slot())).isEqualTo(5);
        assertThat(store.rangeCapacity(label.slot())).isEqualTo(8);
        assertThat(store.role(label.slot())).isEqualTo(Role.TEXT);
        assertThat(store.label(label.slot())).isEqualTo("Health");

        Semantics.Node tree = Semantics.of(store);
        assertThat(tree.role()).isEqualTo(Role.GROUP);
        assertThat(tree.children()).hasSize(1);
        Semantics.Node hud = tree.children().getFirst();
        assertThat(hud.label()).isEqualTo("HUD");
        assertThat(hud.children()).extracting(Semantics.Node::role).containsExactly(Role.TEXT, Role.IMAGE);
        assertThat(tree.toString()).contains("TEXT \"Health\"").contains("IMAGE\n");

        store.semantics(store.rootSlot(), Role.NONE, null);
        assertThat(Semantics.of(store).children()).hasSize(1);
        Element second = store.create("label", null).semantics(Role.TEXT, "x");
        store.root().add(second);
        store.semantics(store.rootSlot(), Role.DIALOG, "d");
        assertThat(Semantics.of(store).role()).isEqualTo(Role.DIALOG);
        assertThat(panel.toString()).isEqualTo("panel#hud@" + panel.slot());
        assertThat(deco.toString()).isEqualTo("panel@" + deco.slot());
    }

    @Test
    void stateBitsHaveNames() {
        assertThat(UiState.of("pressed")).isEqualTo(UiState.PRESSED);
        assertThat(UiState.of("nope")).isZero();
        assertThat(UiState.name(UiState.EDITING)).isEqualTo("editing");
        assertThat(UiState.count()).isEqualTo(8);
        assertThatThrownBy(() -> UiState.name(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiState.name(1 << 9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tunablesClampAndVersion() {
        Tunable scale = Tunable.of("ui.scale", 1, 0.5f, 4, "the UI's scale");
        assertThat(scale.get()).isEqualTo(1);
        long v = scale.version();
        scale.set(10);
        assertThat(scale.get()).isEqualTo(4);
        scale.set(0);
        assertThat(scale.get()).isEqualTo(0.5f);
        scale.set(0.5f);
        assertThat(scale.version()).isEqualTo(v + 2);
        assertThat(scale.name()).isEqualTo("ui.scale");
        assertThat(scale.min()).isEqualTo(0.5f);
        assertThat(scale.max()).isEqualTo(4);
        assertThat(scale.initial()).isEqualTo(1);
        assertThat(scale.description()).isEqualTo("the UI's scale");
        assertThat(scale.toString()).isEqualTo("ui.scale=0.5");
        assertThatThrownBy(() -> Tunable.of("x", 5, 0, 1, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tunable.of("x", -1, 0, 1, "")).isInstanceOf(IllegalArgumentException.class);
    }
}
