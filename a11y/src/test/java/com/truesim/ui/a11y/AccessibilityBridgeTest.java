package com.truesim.ui.a11y;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.basic.CheckState;
import com.truesim.ui.core.AccessibleValue;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessibilityBridgeTest {

    /** Records what the bridge tells. */
    private static final class Recorder implements AccessibilityBridge.Events {
        final List<String> told = new ArrayList<>();

        @Override
        public void focusChanged(AccessibleTree tree, int node) {
            told.add("focus " + (node == AccessibleTree.NONE ? "none" : tree.name(node)));
        }

        @Override
        public void propertyChanged(AccessibleTree tree, int node, AccessibilityBridge.Property property,
            AccessibleTree old, int oldNode) {
            String was = switch (property) {
                case NAME -> old.name(oldNode);
                case VALUE -> old.value(oldNode);
                case TOGGLE_STATE -> Integer.toString(old.toggleState(oldNode));
                case ENABLED -> Boolean.toString(!old.is(oldNode, UiState.DISABLED));
            };
            String now = switch (property) {
                case NAME -> tree.name(node);
                case VALUE -> tree.value(node);
                case TOGGLE_STATE -> Integer.toString(tree.toggleState(node));
                case ENABLED -> Boolean.toString(!tree.is(node, UiState.DISABLED));
            };
            told.add(property + " " + was + " -> " + now);
        }

        @Override
        public void structureChanged(AccessibleTree tree) {
            told.add("structure " + tree.count());
        }
    }

    private static int named(AccessibleTree tree, String name) {
        for (int n = 0; n < tree.count(); n++) {
            if (name.equals(tree.name(n))) {
                return n;
            }
        }
        throw new AssertionError("no node named " + name + " in\n" + tree);
    }

    @Test
    void snapshotsNamesRolesValuesAndLinks() {
        AccessibilityRig rig = new AccessibilityRig();
        AccessibilityBridge bridge = rig.bridge;
        assertNull(bridge.tree(), "nothing taken while nobody listens or asks");
        assertTrue(bridge.refresh());
        assertFalse(bridge.refresh(), "unchanged: no new snapshot");
        AccessibleTree t = bridge.tree();
        assertEquals(Role.WINDOW, t.role(AccessibleTree.ROOT));
        assertEquals(-1, t.key(AccessibleTree.ROOT));
        assertEquals(AccessibleTree.NONE, t.parent(AccessibleTree.ROOT));

        int ok = named(t, "OK");
        assertEquals(Role.BUTTON, t.role(ok));
        assertEquals("ok", t.id(ok));
        assertTrue(t.can(ok, Semantics.ACTIVATE));
        assertTrue(t.can(ok, Semantics.FOCUS));
        assertFalse(t.hasValue(ok));
        assertNull(t.value(ok));
        assertFalse(t.toggles(ok));
        assertEquals(rig.ok.element().slot(), t.slot(ok));
        assertEquals(ok, t.find(t.slot(ok), t.generation(ok)));
        assertEquals(ok, t.find(t.key(ok)));
        assertEquals(AccessibleTree.NONE, t.find(t.slot(ok), t.generation(ok) + 1));

        assertEquals(Role.TEXT, t.role(named(t, "Hello")));
        int name = named(t, "Name");
        assertEquals(Role.TEXT_FIELD, t.role(name));
        assertEquals("abc", t.value(name));
        assertTrue(t.hasValue(name));
        assertFalse(t.valueReadOnly(name));
        assertFalse(t.password(name));
        int secret = named(t, "Secret");
        assertTrue(t.password(secret));
        assertEquals("", t.value(secret), "a password's text is never exposed");
        int volume = named(t, "Volume");
        assertEquals(Role.SLIDER, t.role(volume));
        assertEquals("0.4", t.value(volume));
        int loading = named(t, "Loading");
        assertEquals("0.25", t.value(loading));
        assertTrue(t.valueReadOnly(loading));
        int agree = named(t, "Agree");
        assertTrue(t.toggles(agree));
        assertEquals(0, t.toggleState(agree));
        assertTrue(t.is(named(t, "Off"), UiState.DISABLED));
        assertEquals(t.state(named(t, "Off")) & UiState.DISABLED, UiState.DISABLED);
        assertEquals(t.actions(ok) & Semantics.ACTIVATE, Semantics.ACTIVATE);

        // links: the HUD's root group under the window, the controls in order under it (the form's panel has no
        // role: its children pass up)
        int form = t.parent(ok);
        assertEquals(Role.GROUP, t.role(form));
        assertEquals("root", t.id(form));
        assertEquals(AccessibleTree.ROOT, t.parent(form));
        assertEquals(form, t.firstChild(AccessibleTree.ROOT));
        assertEquals(t.firstChild(form), named(t, "Hello"));
        assertEquals(t.nextSibling(t.firstChild(form)), ok);
        assertEquals(t.previousSibling(ok), named(t, "Hello"));
        assertEquals(t.lastChild(form), loading);
        assertEquals(AccessibleTree.NONE, t.nextSibling(loading));

        // boxes, and the node at a point
        assertEquals(rig.ui.layout().x(rig.ok.element().slot()), t.x(ok));
        assertEquals(rig.ui.layout().y(rig.ok.element().slot()), t.y(ok));
        assertTrue(t.width(ok) > 0 && t.height(ok) > 0);
        int at = t.at(t.x(ok) + t.width(ok) / 2, t.y(ok) + t.height(ok) / 2);
        assertTrue(at == ok || t.parent(at) == ok, "the button or a part of it: " + t.name(at));
        assertEquals(AccessibleTree.ROOT, t.at(-10, -10));
        assertEquals(AccessibleTree.NONE, t.focus());
        assertTrue(t.version() > 0);
        assertTrue(t.toString().contains("BUTTON \"OK\""), t.toString());
        assertTrue(t.toString().contains("TEXT_FIELD \"Name\" = \"abc\""), t.toString());
    }

    @Test
    void hiddenElementsAreLeftOut() {
        AccessibilityRig rig = new AccessibilityRig();
        rig.ui.layout().visible(rig.ok.element().slot(), false);
        rig.frame();
        rig.bridge.refresh();
        AccessibleTree t = rig.bridge.tree();
        for (int n = 0; n < t.count(); n++) {
            assertNotEquals("OK", t.name(n));
        }
    }

    @Test
    void requestsRunOnTheNextFrame() {
        AccessibilityRig rig = new AccessibilityRig();
        AccessibilityBridge bridge = rig.bridge;
        bridge.refresh();
        AccessibleTree t = bridge.tree();
        int ok = named(t, "OK");
        bridge.request(AccessibilityBridge.Action.INVOKE, t.slot(ok), t.generation(ok), null);
        assertEquals(0, rig.ok.clicks(), "not before the UI thread's frame");
        rig.frame();
        assertEquals(1, rig.ok.clicks());

        int agree = named(t, "Agree");
        bridge.request(AccessibilityBridge.Action.TOGGLE, t.slot(agree), t.generation(agree), null);
        rig.frame();
        assertEquals(CheckState.CHECKED, rig.agree.state());

        int name = named(t, "Name");
        bridge.request(AccessibilityBridge.Action.FOCUS, t.slot(name), t.generation(name), null);
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(name), t.generation(name), "typed");
        rig.frame();
        assertEquals("typed", rig.name.text().toString());
        assertSame(rig.name.element(), rig.ui.interaction().focused(0));
        assertEquals(name, bridge.tree().focus(), "asked: the next frame refreshed the tree");
        assertEquals("typed", bridge.tree().value(name));

        int volume = named(t, "Volume");
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(volume), t.generation(volume), " 0.73 ");
        rig.frame();
        assertEquals(0.7, rig.volumeModel.get(), 1e-9, "snapped to the step as a drag is");
        assertEquals(0, bridge.refused());

        // refused: not a number, read only, disabled, gone, no value
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(volume), t.generation(volume), "loud");
        int loading = named(t, "Loading");
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(loading), t.generation(loading), "1");
        int off = named(t, "Off");
        bridge.request(AccessibilityBridge.Action.INVOKE, t.slot(off), t.generation(off), null);
        bridge.request(AccessibilityBridge.Action.INVOKE, t.slot(ok), t.generation(ok) + 1, null);
        bridge.request(AccessibilityBridge.Action.INVOKE, -1, 0, null);
        bridge.request(AccessibilityBridge.Action.INVOKE, 1_000_000, 0, null);
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(ok), t.generation(ok), "x");
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(name), t.generation(name), null);
        rig.frame();
        assertEquals(8, bridge.refused());
        assertEquals(0, rig.off.clicks());
        assertEquals(1, rig.ok.clicks());
        rig.name.readOnly(true);
        bridge.request(AccessibilityBridge.Action.SET_VALUE, t.slot(name), t.generation(name), "no");
        rig.frame();
        assertEquals(9, bridge.refused());
        assertEquals("typed", rig.name.text().toString());
        assertThrows(NullPointerException.class, () -> bridge.request(null, 0, 0, null));
    }

    @Test
    void tellsWhatChanged() {
        AccessibilityRig rig = new AccessibilityRig();
        Recorder recorder = new Recorder();
        boolean[] listening = {false};
        rig.bridge.events(recorder).listening(() -> listening[0]);
        rig.frame();
        assertNull(rig.bridge.tree(), "nobody listens");
        listening[0] = true;
        rig.frame();
        assertTrue(recorder.told.isEmpty(), "the first snapshot tells nothing: " + recorder.told);

        rig.ui.interaction().focus(rig.ok.element());
        rig.frame();
        assertEquals(List.of("focus OK"), recorder.told);
        recorder.told.clear();

        rig.ok.text("Okay");
        rig.name.text("xyz");
        rig.agree.model().set(CheckState.INDETERMINATE);
        rig.off.enabled(true);
        rig.frame();
        assertTrue(recorder.told.contains("NAME OK -> Okay"), recorder.told.toString());
        assertTrue(recorder.told.contains("VALUE abc -> xyz"), recorder.told.toString());
        assertTrue(recorder.told.contains("TOGGLE_STATE 0 -> 2"), recorder.told.toString());
        assertTrue(recorder.told.contains("ENABLED false -> true"), recorder.told.toString());
        assertFalse(recorder.told.stream().anyMatch(s -> s.startsWith("focus")), "the focus stayed: "
            + recorder.told);
        recorder.told.clear();

        rig.ui.destroy(rig.hello);
        rig.ui.interaction().blur(0);
        rig.frame();
        assertTrue(recorder.told.stream().anyMatch(s -> s.startsWith("structure")), recorder.told.toString());
        assertTrue(recorder.told.contains("focus none"), recorder.told.toString());
        recorder.told.clear();

        rig.frame();
        assertTrue(recorder.told.isEmpty(), "nothing changed: " + recorder.told);
        rig.bridge.events(null);
        rig.ok.text("OK");
        rig.frame();
        assertTrue(recorder.told.isEmpty());
    }

    @Test
    void aFrameWithNobodyListeningAllocatesNothing() {
        AccessibilityRig rig = new AccessibilityRig();
        AccessibilityBridge bridge = rig.bridge;
        AllocationAssertions.assertAllocationFree(bridge::update);
        bridge.listening(() -> true);
        bridge.update();
        AllocationAssertions.assertAllocationFree(bridge::update); // listening, nothing changed: no new snapshot
    }

    @Test
    void numbersAndValues() {
        assertEquals("0", AccessibleValue.number(0));
        assertEquals("12", AccessibleValue.number(12.00001));
        assertEquals("-3.1416", AccessibleValue.number(-3.14159265));
        assertEquals("0.5", AccessibleValue.number(0.5));
        assertEquals("NaN", AccessibleValue.number(Double.NaN));
        assertEquals("Infinity", AccessibleValue.number(Double.POSITIVE_INFINITY));
        assertEquals("1000000000000000", AccessibleValue.number(1e15));
        assertEquals(2.5, AccessibleValue.parse(" 2.5 "));
        assertTrue(Double.isNaN(AccessibleValue.parse("x")));
        AccessibleValue plain = () -> "v";
        assertTrue(plain.readOnly());
        assertFalse(plain.set("w"));
        assertFalse(plain.password());
        UiStore store = new UiStore();
        int slot = store.create("panel", null).accessibleValue(plain).slot();
        assertSame(plain, store.accessibleValue(slot));
        assertEquals(List.of(AccessibilityTunables.BRIDGE), new AccessibilityTunables().tunables());
    }
}
