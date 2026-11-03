package com.truesim.ui.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FocusTest {

    /** A 3 x 3 grid of focusable buttons named by row and column ("b11" is the centre), 50 px apart. */
    private static Element[][] grid(Rig r, Element parent, float x0, float y0) {
        Element[][] b = new Element[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                b[row][col] = r.box(parent, "b" + row + col, x0 + col * 50, y0 + row * 50, 40, 40);
                r.ia.focusable(b[row][col], true);
            }
        }
        return b;
    }

    private static String focused(Rig r, int player) {
        Element f = r.ia.focused(player);
        return f == null ? null : f.id();
    }

    @Test
    void arrowsMoveToTheNearestElementThatWayAndTabFollowsTreeOrder() {
        Rig r = new Rig();
        Element[][] b = grid(r, r.root(), 0, 0);
        r.command(UiCommand.DOWN).frame();
        assertThat(focused(r, 0)).isEqualTo("b00"); // nothing focused: the first
        assertThat(b[0][0].is(UiState.FOCUSED) && b[0][0].is(UiState.FOCUS_VISIBLE)).isTrue();
        r.command(UiCommand.RIGHT).command(UiCommand.DOWN).frame();
        assertThat(focused(r, 0)).isEqualTo("b11");
        r.command(UiCommand.RIGHT).command(UiCommand.UP).frame();
        assertThat(focused(r, 0)).isEqualTo("b02");
        r.command(UiCommand.UP).frame();
        assertThat(focused(r, 0)).isEqualTo("b02"); // nothing above: stays
        r.command(UiCommand.LEFT).command(UiCommand.LEFT).command(UiCommand.DOWN).command(UiCommand.DOWN).frame();
        assertThat(focused(r, 0)).isEqualTo("b20");
        r.command(UiCommand.NEXT).frame();
        assertThat(focused(r, 0)).isEqualTo("b21");
        r.command(UiCommand.NEXT).command(UiCommand.NEXT).frame();
        assertThat(focused(r, 0)).isEqualTo("b00"); // wraps
        r.command(UiCommand.PREVIOUS).frame();
        assertThat(focused(r, 0)).isEqualTo("b22");
        r.command(UiCommand.HOME).frame();
        assertThat(focused(r, 0)).isEqualTo("b00");
        r.command(UiCommand.END).frame();
        assertThat(focused(r, 0)).isEqualTo("b22");
        assertThat(b[0][0].is(UiState.FOCUSED)).isFalse();
    }

    @Test
    void spatialSearchPrefersAlignedCandidatesOverCloserDiagonalOnes() {
        Rig r = new Rig();
        Element from = r.box(r.root(), "from", 0, 100, 40, 40);
        Element aligned = r.box(r.root(), "aligned", 300, 100, 40, 40);
        Element diagonal = r.box(r.root(), "diagonal", 60, 200, 40, 40);
        Element behind = r.box(r.root(), "behind", -100, 100, 40, 40);
        for (Element e : List.of(from, aligned, diagonal, behind)) {
            r.ia.focusable(e, true);
        }
        r.ia.focus(from);
        assertThat(r.ia.navigate(0, FocusDirection.RIGHT)).isTrue();
        assertThat(focused(r, 0)).isEqualTo("aligned");
        r.ia.focus(from);
        r.ia.navigate(0, FocusDirection.DOWN);
        assertThat(focused(r, 0)).isEqualTo("diagonal");
        r.ia.navigate(0, FocusDirection.UP);
        assertThat(focused(r, 0)).isEqualTo("from"); // "aligned" is more to the side than up
        r.ia.navigate(0, FocusDirection.LEFT);
        assertThat(focused(r, 0)).isEqualTo("behind");
        assertThat(r.ia.navigate(0, FocusDirection.LEFT)).isFalse();
        assertThat(r.ia.navigate(0, FocusDirection.UP)).isFalse();
    }

    @Test
    void explicitNeighboursComeFirstThenContainersFromTheNearestUp() {
        Rig r = new Rig();
        Element outer = r.box(r.root(), "outer", 0, 0, 500, 500);
        Element inner = r.box(outer, "inner", 0, 0, 200, 200);
        Element[][] b = grid(r, inner, 0, 0);
        Element far = r.box(outer, "far", 400, 400, 40, 40);
        r.ia.focusable(far, true);
        r.ia.neighbour(b[0][0], FocusDirection.RIGHT, far);
        r.ia.focus(b[0][0]);
        r.ia.navigate(0, FocusDirection.RIGHT);
        assertThat(focused(r, 0)).isEqualTo("far");
        r.ia.neighbour(b[0][0], FocusDirection.RIGHT, null);

        List<String> asked = new ArrayList<>();
        r.ia.traversal(inner, (container, from, direction) -> {
            asked.add("inner " + direction);
            return direction == FocusDirection.NEXT ? b[2][2].slot() : UiStore.NONE;
        });
        r.ia.traversal(outer, (container, from, direction) -> {
            asked.add("outer " + direction);
            return direction == FocusDirection.DOWN ? far.slot() : UiStore.NONE;
        });
        r.ia.focus(b[0][0]);
        r.ia.navigate(0, FocusDirection.NEXT);
        assertThat(focused(r, 0)).isEqualTo("b22");
        r.ia.navigate(0, FocusDirection.DOWN);
        assertThat(focused(r, 0)).isEqualTo("far"); // inner had no answer: outer's
        r.ia.focus(b[0][0]);
        r.ia.navigate(0, FocusDirection.RIGHT);
        assertThat(focused(r, 0)).isEqualTo("b01"); // neither: the default
        assertThat(asked).containsExactly("inner NEXT", "inner DOWN", "outer DOWN", "inner RIGHT", "outer RIGHT");
        assertThatThrownBy(() -> r.ia.neighbour(far, FocusDirection.FIRST, far))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachPlayerHasAFocusAndContainersRememberTheirs() {
        Rig r = new Rig();
        Element left = r.box(r.root(), "left", 0, 0, 100, 300);
        Element right = r.box(r.root(), "right", 200, 0, 100, 300);
        Element[] l = new Element[3];
        Element[] rr = new Element[3];
        for (int i = 0; i < 3; i++) {
            l[i] = r.box(left, "l" + i, 0, i * 100, 90, 90);
            rr[i] = r.box(right, "r" + i, 200, i * 100, 90, 90);
            r.ia.focusable(l[i], true);
            r.ia.focusable(rr[i], true);
        }
        r.ia.rememberFocus(right, true);
        r.ia.focus(l[0], 0);
        r.ia.focus(rr[0], 1);
        assertThat(l[0].is(UiState.FOCUSED) && rr[0].is(UiState.FOCUSED)).isTrue();
        r.command(UiCommand.DOWN, 1).command(UiCommand.DOWN, 1).frame();
        assertThat(focused(r, 1)).isEqualTo("r2");
        assertThat(focused(r, 0)).isEqualTo("l0");
        assertThat(r.ia.remembered(right)).isEqualTo(rr[2]);
        r.ia.focus(rr[2], 0); // both players on one element: one FOCUSED state, two counts
        r.ia.blur(1);
        assertThat(rr[2].is(UiState.FOCUSED)).isTrue();
        r.ia.focus(l[1], 0);
        assertThat(rr[2].is(UiState.FOCUSED)).isFalse();
        r.ia.navigate(0, FocusDirection.RIGHT); // into the right column: back to what it remembers
        assertThat(focused(r, 0)).isEqualTo("r2");
        r.ia.rememberFocus(right, false);
        assertThat(r.ia.remembered(right)).isNull();
        r.ia.focus(l[1], 0);
        r.ia.navigate(0, FocusDirection.RIGHT);
        assertThat(focused(r, 0)).isEqualTo("r1");
    }

    @Test
    void modalScopesTrapFocusAndRestoreItWhenTheyClose() {
        Rig r = new Rig();
        Element[][] b = grid(r, r.root(), 0, 0);
        r.ia.focus(b[1][1]);
        Layer modal = r.layers.get(Layers.MODAL);
        Element dialog = r.box(modal.root(), "dialog", 300, 300, 200, 100);
        Element ok = r.box(dialog, "ok", 300, 350, 90, 40);
        Element cancel = r.box(dialog, "cancel", 400, 350, 90, 40);
        r.ia.focusable(ok, true);
        r.ia.focusable(cancel, true);
        List<String> cancelled = new ArrayList<>();
        r.ia.pushModal(dialog, () -> cancelled.add("closed"));
        assertThat(focused(r, 0)).isEqualTo("ok");
        assertThat(r.ia.modalRoot()).isEqualTo(dialog);
        assertThat(r.ia.focus(b[0][0])).isFalse(); // outside the scope
        r.command(UiCommand.RIGHT).command(UiCommand.RIGHT).command(UiCommand.UP).frame();
        assertThat(focused(r, 0)).isEqualTo("cancel");
        r.command(UiCommand.NEXT).frame();
        assertThat(focused(r, 0)).isEqualTo("ok"); // tab wraps inside the dialog
        r.click(5, 5); // a click outside the dialog does not take focus out
        assertThat(focused(r, 0)).isEqualTo("ok");
        r.command(UiCommand.CANCEL).frame();
        assertThat(cancelled).containsExactly("closed"); // the action closes the dialog:
        dialog.detach();
        r.ia.popModal(dialog);
        assertThat(focused(r, 0)).isEqualTo("b11"); // restored
        assertThat(r.ia.modalRoot()).isNull();
        assertThat(r.ia.cancelModal()).isFalse();

        // a modal scope without a cancel action, closed by destroying its root
        Element sheet = r.box(r.root(), "sheet", 600, 0, 100, 100);
        Element field = r.box(sheet, "field", 600, 0, 50, 20);
        r.ia.focusable(field, true);
        r.ia.pushModal(sheet, null);
        assertThat(focused(r, 0)).isEqualTo("field");
        assertThat(r.ia.cancelModal()).isFalse();
        sheet.destroy();
        r.frame();
        assertThat(focused(r, 0)).isEqualTo("b11");
        r.ia.popModal(sheet); // gone already: nothing
    }

    @Test
    void aModalLayerShowingSomethingTrapsFocusOnItsOwn() {
        Rig r = new Rig();
        Element[][] b = grid(r, r.root(), 0, 0);
        r.ia.focus(b[0][0]);
        Layer modal = r.layers.get(Layers.MODAL);
        Element box = r.box(modal.root(), "box", 300, 0, 100, 100);
        Element inside = r.box(box, "inside", 300, 0, 50, 50);
        r.ia.focusable(inside, true);
        r.frame();
        assertThat(focused(r, 0)).isEqualTo("inside");
        box.detach();
        r.frame();
        assertThat(r.ia.focus(b[0][0])).isTrue();
    }

    @Test
    void pressesFocusWithoutARingAndTheFirstKeyShowsIt() {
        Rig r = new Rig();
        Element button = r.box(r.root(), "button", 0, 0, 100, 100);
        Element label = r.button(button, "label", 10, 10, 50, 20);
        Element other = r.box(r.root(), "other", 200, 0, 100, 100);
        r.ia.focusable(button, true);
        r.ia.focusable(other, true);
        r.click(20, 20); // on the label: its focusable ancestor takes focus
        assertThat(focused(r, 0)).isEqualTo("button");
        assertThat(button.is(UiState.FOCUSED)).isTrue();
        assertThat(button.is(UiState.FOCUS_VISIBLE)).isFalse();
        r.command(UiCommand.RIGHT).frame();
        assertThat(focused(r, 0)).isEqualTo("other");
        assertThat(other.is(UiState.FOCUS_VISIBLE)).isTrue();
        assertThat(button.is(UiState.FOCUS_VISIBLE)).isFalse();
        r.ia.focus(button); // programmatic focus follows the last modality: keys
        assertThat(button.is(UiState.FOCUS_VISIBLE)).isTrue();
        r.click(500, 500); // a press on nothing takes focus away
        assertThat(r.ia.focused(0)).isNull();
        assertThat(label.alive()).isTrue();
    }

    @Test
    void focusLeavesElementsThatAreDisabledHiddenOrDestroyed() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 0, 0, 10, 10);
        Element b = r.box(r.root(), "b", 20, 0, 10, 10);
        r.ia.focusable(a, true);
        r.ia.focusable(b, true);
        List<String> events = new ArrayList<>();
        r.ia.on(r.root(), EventType.FOCUS_IN.bit() | EventType.FOCUS_OUT.bit(), e -> events.add(e.type() + " "
            + e.target().id() + " " + (e.related() == null ? "-" : e.related().id()) + " " + e.focusVisible()));
        r.ia.focus(a);
        r.ia.focus(b);
        assertThat(events).containsExactly("FOCUS_IN a - false", "FOCUS_OUT a b false", "FOCUS_IN b a false");
        assertThat(r.ia.focusable(b)).isTrue();
        b.state(UiState.DISABLED, true);
        r.frame();
        assertThat(r.ia.focused(0)).isNull();
        assertThat(r.ia.focus(b)).isFalse();
        b.state(UiState.DISABLED, false);
        r.geo.hidden[b.slot()] = true;
        assertThat(r.ia.focus(b)).isFalse();
        r.geo.hidden[b.slot()] = false;
        assertThat(r.ia.focus(b)).isTrue();
        assertThat(r.ia.focus(b)).isTrue(); // again: nothing changes
        b.destroy();
        r.frame();
        assertThat(r.ia.focused(0)).isNull();
        assertThat(r.ia.focusedSlot(0)).isEqualTo(UiStore.NONE);
        r.ia.blur(0);
        r.ia.focusable(a, false);
        assertThat(r.ia.focus(a)).isFalse();
        assertThat(r.ia.navigate(0, FocusDirection.NEXT)).isFalse(); // nothing focusable anywhere
    }

    @Test
    void aFocusOutListenerMayMoveFocusElsewhere() {
        Rig r = new Rig();
        Element a = r.box(r.root(), "a", 0, 0, 10, 10);
        Element b = r.box(r.root(), "b", 20, 0, 10, 10);
        Element c = r.box(r.root(), "c", 40, 0, 10, 10);
        for (Element e : List.of(a, b, c)) {
            r.ia.focusable(e, true);
        }
        r.ia.focus(a);
        r.ia.on(a, EventType.FOCUS_OUT, e -> r.ia.focus(c));
        assertThat(r.ia.focus(b)).isTrue();
        assertThat(focused(r, 0)).isEqualTo("c");
        assertThat(b.is(UiState.FOCUSED)).isFalse();
        assertThat(Pointer.PRIMARY).isEqualTo(1);
    }

    @Test
    void queriesGrowTheirArraysForElementsMadeAfterTheInteraction() {
        Rig r = new Rig();
        com.truesim.ui.core.Element last = null;
        for (int i = 0; i < 600; i++) {
            last = r.store.create("panel", null);
        }
        assertThat(r.ia.focusable(last)).isFalse();
        com.truesim.ui.core.Element more = null;
        for (int i = 0; i < 600; i++) {
            more = r.store.create("panel", null);
        }
        assertThat(r.ia.listening(more)).isZero();
        com.truesim.ui.core.Element another = null;
        for (int i = 0; i < 1200; i++) {
            another = r.store.create("panel", null);
        }
        assertThat(r.ia.remembered(another)).isNull();
        for (int i = 0; i < 2400; i++) {
            another = r.store.create("panel", null);
        }
        r.ia.traversal(another, null);
        for (int i = 0; i < 4800; i++) {
            another = r.store.create("panel", null);
        }
        r.ia.neighbour(another, FocusDirection.UP, null);
    }
}
