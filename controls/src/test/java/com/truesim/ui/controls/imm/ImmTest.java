package com.truesim.ui.controls.imm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.testing.AllocationAssertions;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Semantics;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.style.tss.Tss;
import org.junit.jupiter.api.Test;

/** The immediate-mode façade: state by id, reuse and collection, input without latency, widgets, allocation. */
class ImmTest {

    private final ImmGallery g = ImmGallery.build(new Ui(Tss.defaultTheme(), UiTestAccess.nativeText()), 1280, 720);
    private final Ui ui = g.ui;
    private final Imm imm = g.imm;
    private final Layout layout = ui.layout();
    private final UiStore store = ui.store();

    private Element el(String window, String label) {
        Element e = imm.element(window, label);
        assertThat(e).as(label).isNotNull();
        return e;
    }

    /** The first child of {@code e} of {@code type}, depth first. */
    private Element find(Element e, String type) {
        for (int c = store.firstChild(e.slot()); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (type.equals(store.type(c))) {
                return store.element(c);
            }
            Element inner = find(store.element(c), type);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    private float cx(Element e) {
        return layout.x(e.slot()) + layout.width(e.slot()) / 2;
    }

    private float cy(Element e) {
        return layout.y(e.slot()) + layout.height(e.slot()) / 2;
    }

    private void click(Element e) {
        float x = cx(e);
        float y = cy(e);
        g.input.mouse(x, y, 0);
        g.frames(1);
        g.input.mouse(x, y, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(x, y, 0);
        g.frames(1);
    }

    @Test
    void elementsAreKeptByIdBetweenFrames() {
        Element spawn = el("Spawner", "Spawn");
        int created = imm.created();
        int nodes = imm.nodes();
        g.frames(10);
        assertThat(el("Spawner", "Spawn")).isSameAs(spawn);
        assertThat(imm.created()).isEqualTo(created);
        assertThat(imm.nodes()).isEqualTo(nodes);
        assertThat(imm.window("Spawner").type()).isEqualTo("imm-window");
        assertThat(imm.host()).isSameAs(ui.layerStack().find(Imm.LAYER).root());
        assertThat(imm.ui()).isSameAs(ui);
    }

    @Test
    void aClickShowsInTheFrameItHappened() {
        Element spawn = el("Spawner", "Spawn");
        float x = cx(spawn);
        float y = cy(spawn);
        g.input.mouse(x, y, 0);
        g.frames(1);
        g.input.mouse(x, y, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(x, y, 0);
        int before = g.spawned;
        g.frames(1); // the release: the click is processed by input, then the tool's call sees it
        assertThat(g.spawned).isEqualTo(before + 1);
        g.frames(2);
        assertThat(g.spawned).isEqualTo(before + 1);
        Element clear = el("Spawner", "Clear");
        click(clear);
        assertThat(g.spawned).isZero();
        // ACCEPT on a focused button clicks it too
        ui.interaction().focus(spawn);
        g.input.press(UiCommand.ACCEPT);
        g.frames(1);
        assertThat(g.spawned).isEqualTo(1);
    }

    @Test
    void sameLinePutsItemsOnOneLine() {
        Element spawn = el("Spawner", "Spawn");
        Element clear = el("Spawner", "Clear");
        assertThat(store.parent(spawn.slot())).isEqualTo(store.parent(clear.slot()));
        assertThat(layout.y(spawn.slot())).isEqualTo(layout.y(clear.slot()));
        assertThat(layout.x(clear.slot())).isGreaterThan(layout.x(spawn.slot()));
        Element count = el("Spawner", "Count");
        assertThat(store.parent(count.slot())).isNotEqualTo(store.parent(spawn.slot()));
        assertThat(store.type(store.parent(spawn.slot()))).isEqualTo("imm-line");
    }

    @Test
    void widgetsNotCalledAreCollected() {
        Element spawn = el("Spawner", "Spawn");
        int nodes = imm.nodes();
        // a frame without the scene window: it goes
        g.input.mouse(-10, -10, 0);
        ui.input(null, 0);
        imm.begin("Spawner");
        imm.end();
        ui.update(1280, 720);
        assertThat(imm.window("Scene")).isNull();
        assertThat(spawn.alive()).isFalse();
        assertThat(imm.nodes()).isLessThan(nodes);
        assertThat(imm.destroyed()).isPositive();
        g.frames(2); // and comes back
        assertThat(imm.window("Scene")).isNotNull();
        assertThat(el("Spawner", "Spawn").alive()).isTrue();
    }

    @Test
    void theOrderFollowsTheCalls() {
        Ui other = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        Imm m = new Imm(other);
        boolean[] swap = {false};
        Runnable frame = () -> {
            other.input(null, 0);
            m.begin("W");
            m.button(swap[0] ? "B" : "A");
            m.button(swap[0] ? "A" : "B");
            m.button("C");
            m.end();
            other.update(800, 600);
        };
        frame.run();
        Element a = m.element("W", "A");
        Element b = m.element("W", "B");
        assertThat(layout(other, a)).isLessThan(layout(other, b));
        swap[0] = true;
        frame.run();
        assertThat(m.element("W", "A")).isSameAs(a);
        assertThat(layout(other, b)).isLessThan(layout(other, a));
        assertThat(layout(other, a)).isLessThan(layout(other, m.element("W", "C")));
    }

    private static float layout(Ui u, Element e) {
        return u.layout().y(e.slot());
    }

    @Test
    void checkboxSliderDragComboAndTextFieldChangeTheirValues() {
        Element waves = el("Spawner", "Waves");
        assertThat(waves.is(UiState.CHECKED)).isTrue();
        click(waves);
        assertThat(g.waves).isFalse();
        assertThat(waves.is(UiState.CHECKED)).isFalse();
        // the slider: a press at its left end, a drag to its right end
        Element slider = find(el("Spawner", "Count"), "imm-slider");
        float y = cy(slider);
        float left = layout.x(slider.slot());
        float right = layout.x(slider.slot()) + layout.width(slider.slot()) - 1;
        g.input.mouse(left, y, Pointer.PRIMARY);
        g.frames(1);
        assertThat(g.count).isEqualTo(1);
        g.input.mouse(right + 50, y, Pointer.PRIMARY);
        g.frames(1);
        assertThat(g.count).isEqualTo(100);
        g.input.mouse(right + 50, y, 0);
        g.frames(1);
        g.count = 40; // the tool's code changes it: the slider follows
        g.frames(1);
        assertThat(g.count).isEqualTo(40);
        ui.interaction().focus(slider);
        g.input.press(UiCommand.RIGHT);
        g.frames(1);
        assertThat(g.count).isEqualTo(41);
        // the drag: sideways by 20 units at 0.1 a unit
        Element drag = find(el("Spawner", "Radius"), "imm-drag");
        float dx = cx(drag);
        float dy = cy(drag);
        g.input.mouse(dx, dy, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(dx + 20 * layout.scale(), dy, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(dx + 20 * layout.scale(), dy, 0);
        g.frames(1);
        assertThat(g.radius).isCloseTo(9.5f, org.assertj.core.data.Offset.offset(0.01f));
        ui.interaction().focus(drag);
        g.input.press(UiCommand.LEFT);
        g.frames(1);
        assertThat(g.radius).isCloseTo(9.4f, org.assertj.core.data.Offset.offset(0.01f));
        // the combo: open it, pick the third item
        Element combo = el("Spawner", "Kind");
        Element button = find(combo, "imm-combo-button");
        Element list = find(combo, "imm-combo-list");
        assertThat(layout.shown(list.slot())).isFalse();
        click(button);
        assertThat(layout.shown(list.slot())).isTrue();
        Element third = store.element(store.nextSibling(store.nextSibling(store.firstChild(list.slot()))));
        click(third);
        assertThat(g.kind).isEqualTo(2);
        assertThat(layout.shown(list.slot())).isFalse();
        assertThat(third.is(UiState.SELECTED)).isTrue();
        // the text field: typing changes the tool's buffer; the tool's changes show in the field
        Element squad = find(el("Spawner", "Squad"), "textfield");
        ui.interaction().focus(squad);
        g.frames(1);
        g.input.press(UiCommand.DOC_END);
        g.input.type("!");
        g.frames(1);
        assertThat(g.name.toString()).isEqualTo("Northern squad!");
        ui.interaction().blur(0);
        g.name.setLength(0);
        g.name.append("Southern");
        g.frames(2);
        assertThat(g.name.toString()).isEqualTo("Southern");
        assertThat(semanticsName(squad)).isEqualTo("Squad");
    }

    private String semanticsName(Element e) {
        return store.label(e.slot());
    }

    @Test
    void treeNodesOpenAndClose() {
        Element lights = el("Scene", "Lights");
        Element header = find(lights, "imm-tree-header");
        Element body = find(lights, "imm-tree-body");
        assertThat(layout.shown(body.slot())).isFalse();
        click(header);
        assertThat(header.is(UiState.EXPANDED)).isTrue();
        assertThat(layout.shown(body.slot())).isTrue();
        assertThat(store.firstChild(body.slot())).isNotEqualTo(UiStore.NONE); // "Sun" is in it
        click(header);
        assertThat(layout.shown(body.slot())).isFalse();
        // the World node's selectables
        Element scene = imm.window("Scene");
        Element first = find(scene, "imm-selectable");
        click(first);
        assertThat(g.selected).isZero();
        assertThat(first.is(UiState.SELECTED)).isTrue();
    }

    @Test
    void tablesLayCellsOutInColumns() {
        Element table = find(imm.window("Scene"), "imm-table");
        assertThat(table).isNotNull();
        int rows = 0;
        for (int r = store.firstChild(table.slot()); r != UiStore.NONE; r = store.nextSibling(r)) {
            rows++;
            int cells = 0;
            float lastX = -1;
            for (int c = store.firstChild(r); c != UiStore.NONE; c = store.nextSibling(c)) {
                cells++;
                assertThat(layout.x(c)).isGreaterThan(lastX);
                lastX = layout.x(c);
            }
            assertThat(cells).isEqualTo(3);
        }
        assertThat(rows).isEqualTo(4);
    }

    @Test
    void windowsMoveByTheirTitle() {
        Element w = imm.window("Spawner");
        Element title = find(w, "imm-title");
        float x0 = layout.x(w.slot());
        float tx = cx(title);
        float ty = cy(title);
        g.input.mouse(tx, ty, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(tx + 100, ty + 30, Pointer.PRIMARY);
        g.frames(1);
        g.input.mouse(tx + 100, ty + 30, 0);
        g.frames(2);
        assertThat(layout.x(w.slot())).isCloseTo(x0 + 100, org.assertj.core.data.Offset.offset(1f));
    }

    @Test
    void misuseIsReported() {
        Ui other = new Ui(Tss.defaultTheme(), UiTestAccess.nativeText());
        Imm m = new Imm(other);
        assertThatThrownBy(() -> m.button("x")).hasMessageContaining("begin");
        assertThatThrownBy(m::end).hasMessageContaining("end without begin");
        assertThatThrownBy(m::endGroup).hasMessageContaining("endGroup");
        assertThatThrownBy(m::treePop).hasMessageContaining("treePop");
        assertThatThrownBy(m::popId).hasMessageContaining("popId");
        assertThatThrownBy(m::tableNextRow).hasMessageContaining("table");
        m.begin("W");
        assertThatThrownBy(() -> m.begin("V")).hasMessageContaining("begin inside");
        m.button("same");
        assertThatThrownBy(() -> m.button("same")).hasMessageContaining("one id");
        m.button("same##2"); // another id, the same text
        m.beginTable("t", 1);
        m.tableNextColumn();
        assertThatThrownBy(m::tableNextColumn).hasMessageContaining("1 columns");
        m.endTable();
        m.beginGroup(); // left open
        assertThatThrownBy(() -> other.update(400, 300)).hasMessageContaining("left open");
        other.update(400, 300); // the fa\u00E7ade starts over
    }

    @Test
    void numbersAreFormattedWithoutAllocating() {
        StringBuilder s = new StringBuilder();
        Imm.number(s, 3.14159, 2);
        s.append(' ');
        Imm.number(s, -0.004, 2);
        s.append(' ');
        Imm.number(s, -2.5, 0);
        s.append(' ');
        Imm.number(s, 41, 0);
        s.append(' ');
        Imm.number(s, Double.NaN, 1);
        s.append(' ');
        Imm.number(s, Double.NEGATIVE_INFINITY, 1);
        s.append(' ');
        Imm.number(s, 0.05, 3);
        assertThat(s.toString()).isEqualTo("3.14 0.00 -3 41 NaN -\u221E 0.050");
    }

    @Test
    void everyWidgetIsNamed() {
        click(find(el("Scene", "Lights"), "imm-tree-header"));
        click(find(el("Spawner", "Kind"), "imm-combo-button"));
        Semantics.Node tree = Semantics.of(store, ui.interaction());
        assertThat(Semantics.unnamed(tree)).isEmpty();
        assertThat(store.role(el("Spawner", "Spawn").slot())).isEqualTo(Role.BUTTON);
        assertThat(store.label(el("Spawner", "Spawn").slot())).isEqualTo("Spawn");
        assertThat(store.role(imm.window("Scene").slot())).isEqualTo(Role.WINDOW);
    }

    @Test
    void steadyFramesAllocateNothing() {
        Element slider = find(el("Spawner", "Count"), "imm-slider");
        int[] step = {0};
        Runnable idle = () -> g.frames(1);
        Runnable changing = () -> {
            int s = step[0]++;
            g.count = 1 + s % 100;
            g.radius = s % 50;
            g.spawned = s % 1000;
            g.selected = s % 6;
            g.frames(1);
        };
        Runnable hovering = () -> {
            int s = step[0]++;
            g.input.mouse(cx(slider) + s % 40 - 20, cy(slider), 0);
            g.frames(1);
        };
        for (int i = 0; i < 300; i++) {
            changing.run();
            hovering.run();
        }
        AllocationAssertions.assertAllocationFree(idle);
        AllocationAssertions.assertAllocationFree(changing);
        AllocationAssertions.assertAllocationFree(hovering);
    }
}
