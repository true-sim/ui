package com.truesim.ui.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PickGeometry;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.input.Cursor;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayersTest {

    @Test
    void standardLayersAreMadeWhenAskedForAndKeptInZOrder() {
        UiStore store = new UiStore();
        Layers layers = new Layers(store);
        assertThat(layers.count()).isEqualTo(1);
        Layer hud = layers.at(0);
        assertThat(hud.name()).isEqualTo(Layers.HUD);
        assertThat(hud.root()).isEqualTo(store.root());
        long version = layers.version();
        Layer tooltip = layers.get(Layers.TOOLTIP);
        Layer popup = layers.get(Layers.POPUP);
        Layer modal = layers.get(Layers.MODAL);
        Layer minimap = layers.add("minimap", 50);
        assertThat(layers.version()).isGreaterThan(version);
        assertThat(layers.get(Layers.POPUP)).isSameAs(popup);
        assertThat(List.of(layers.at(0), layers.at(1), layers.at(2), layers.at(3), layers.at(4)))
            .containsExactly(hud, minimap, modal, popup, tooltip);
        assertThat(layers.drawLists()).containsExactly(hud.drawList(), minimap.drawList(), modal.drawList(),
            popup.drawList(), tooltip.drawList());
        assertThat(popup.root().type()).isEqualTo("layer");
        assertThat(popup.root().id()).isEqualTo("popup");
        assertThat(popup.z()).isEqualTo(300);
        assertThat(modal.modal()).isTrue();
        assertThat(tooltip.input()).isEqualTo(Layer.Input.NONE);
        assertThat(layers.get(Layers.DRAG).input()).isEqualTo(Layer.Input.NONE);
        assertThat(layers.get(Layers.DEBUG).z()).isEqualTo(1000);
        assertThat(layers.get(Layers.OVERLAY).input()).isEqualTo(Layer.Input.PASS_THROUGH);
        assertThat(minimap.toString()).isEqualTo("Layer[minimap z=50]");
        assertThat(layers.find("nope")).isNull();
        assertThatThrownBy(() -> layers.get("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> layers.add("minimap", 1)).isInstanceOf(IllegalArgumentException.class);

        Element item = store.create("panel", "item");
        assertThat(layers.of(item.slot())).isNull(); // detached
        popup.root().add(item);
        assertThat(layers.of(item.slot())).isSameAs(popup);
        assertThat(layers.indexOf(item.slot())).isEqualTo(4); // hud, minimap, overlay, modal, popup
        assertThat(layers.indexOfRoot(item.slot())).isEqualTo(-1);
        assertThat(popup.showing()).isTrue();
        assertThat(popup.blocking()).isFalse();
        popup.modal(true);
        assertThat(popup.blocking()).isTrue();
        popup.input(Layer.Input.NONE);
        assertThat(popup.blocking()).isFalse();
        popup.modal(false).input(Layer.Input.BLOCK);
        assertThat(popup.blocking()).isTrue();
        assertThat(layers.store()).isSameAs(store);
        assertThat(popup.rootSlot()).isEqualTo(popup.root().slot());
        for (int i = 0; i < 10; i++) {
            layers.add("extra" + i, 2000 + i); // grows past the first arrays
        }
        assertThat(layers.count()).isEqualTo(18);
    }

    @Test
    void storesHaveOneTreePerRoot() {
        UiStore store = new UiStore();
        Element second = store.createRoot("layer", "second");
        assertThat(store.rootCount()).isEqualTo(2);
        assertThat(store.rootAt(1)).isEqualTo(second.slot());
        assertThat(store.isRoot(second.slot())).isTrue();
        assertThat(store.isRoot(-1)).isFalse();
        Element child = store.create("panel", null);
        assertThat(store.rootOf(child.slot())).isEqualTo(UiStore.NONE);
        second.add(child);
        assertThat(store.rootOf(child.slot())).isEqualTo(second.slot());
        assertThatThrownBy(() -> store.root().add(second)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(second::destroy).isInstanceOf(IllegalArgumentException.class);
        store.clearDirtyQueue();
        store.markAll(UiStore.DIRTY_DRAW);
        assertThat(store.dirtyCount()).isEqualTo(3); // both roots' trees
        for (int i = 0; i < 10; i++) {
            store.createRoot("layer", "r" + i);
        }
        assertThat(store.rootCount()).isEqualTo(12);
    }

    @Test
    void semanticsShowRolesStatesAndActionsOfEveryLayer() {
        UiStore store = new UiStore();
        Layers layers = new Layers(store);
        Interaction ia = new Interaction(layers, new Flat());
        Element ok = store.create("panel", "ok").semantics(Role.BUTTON, "OK");
        store.root().add(ok);
        ia.on(ok, EventType.CLICK, e -> { });
        ia.focusable(ok, true);
        ok.state(UiState.DISABLED, true);
        Element anonymous = store.create("panel", null);
        store.root().add(anonymous);
        ia.on(anonymous, EventType.CONTEXT.bit() | EventType.WHEEL.bit() | EventType.DRAG_START.bit()
            | EventType.DROP.bit(), e -> { });
        Element field = store.create("panel", null).semantics(Role.TEXT_FIELD, "");
        layers.get(Layers.POPUP).root().add(field);
        ia.textInput(field, true);
        Element list = store.create("panel", null).semantics(Role.LIST, null);
        store.root().add(list);
        ia.on(list, EventType.WHEEL, e -> { });

        Semantics.Node tree = Semantics.of(store, ia);
        assertThat(tree.toString()).isEqualTo("""
            GROUP
              GROUP
                BUTTON "OK" :disabled +activate +focus
                NONE +show-menu +scroll +drag +drop
                LIST +scroll
              TEXT_FIELD "" +focus +edit-text
            """);
        Semantics.Node button = tree.children().getFirst().children().getFirst();
        assertThat(button.is(UiState.DISABLED)).isTrue();
        assertThat(button.can(Semantics.ACTIVATE)).isTrue();
        assertThat(button.can(Semantics.SCROLL)).isFalse();
        assertThat(Semantics.unnamed(tree)).extracting(Semantics.Node::role).containsExactly(Role.NONE,
            Role.TEXT_FIELD);
        assertThat(Semantics.of(store).toString()).doesNotContain("+");
    }

    /** Everything at the origin, nothing picked by style. */
    static final class Flat implements PickGeometry {
        @Override
        public float x(int slot) {
            return 0;
        }

        @Override
        public float y(int slot) {
            return 0;
        }

        @Override
        public float width(int slot) {
            return 10;
        }

        @Override
        public float height(int slot) {
            return 10;
        }

        @Override
        public boolean shown(int slot) {
            return true;
        }

        @Override
        public float radius(int slot, int corner) {
            return 0;
        }

        @Override
        public boolean clips(int slot) {
            return false;
        }

        @Override
        public PointerEvents pointerEvents(int slot) {
            return PointerEvents.AUTO;
        }

        @Override
        public Cursor cursor(int slot) {
            return Cursor.AUTO;
        }
    }
}
