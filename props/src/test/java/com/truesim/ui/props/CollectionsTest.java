package com.truesim.ui.props;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Dialog;
import com.truesim.ui.controls.data.ListView;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.props.PropsGallery.Behaviour;
import com.truesim.ui.props.PropsGallery.Command;
import com.truesim.ui.props.PropsGallery.Hold;
import com.truesim.ui.props.PropsGallery.Move;
import com.truesim.ui.props.PropsGallery.Patrol;
import com.truesim.ui.props.PropsGallery.Squad;
import com.truesim.ui.props.PropsGallery.Waypoint;
import com.truesim.ui.props.annotations.Factory;
import com.truesim.ui.props.annotations.Reorderable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Collection editors (stage 13, UI18): kinds chosen by style, operations, validation, lenses, undo, virtualization. */
class CollectionsTest {

    static CollectionEditor editor(PropertyPanel<?> panel, String property) {
        return (CollectionEditor) panel.row(property).editor();
    }

    /** The header or item button of {@code op} in {@code editor}, the {@code n}th one. */
    static Button button(CollectionEditor editor, String op, int n) {
        List<Button> found = new ArrayList<>();
        collect(editor.element(), op, found, editor);
        return found.size() > n ? found.get(n) : null;
    }

    private static void collect(Element e, String op, List<Button> out, CollectionEditor editor) {
        UiStore s = e.store();
        if ("button".equals(e.type()) && op.equals(e.id()) && "props-op".equals(e.style())) {
            out.add(editor.buttonOf(e));
        }
        for (int c = s.firstChild(e.slot()); c != UiStore.NONE; c = s.nextSibling(c)) {
            collect(s.element(c), op, out, editor);
        }
    }

    @Test
    void aThemeAloneSwitchesTheKindAndHidesAnOperation() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor waypoints = editor(g.panel, "waypoints");
        assertThat(waypoints.kind()).isEqualTo(CollectionKind.TABLE);
        assertThat(waypoints.offered("duplicate")).isTrue();
        assertThat(button(waypoints, "duplicate", 0)).isNotNull();
        r.ui.styles().sheet(PipelineTest.themeWith("default-theme.tss", """
            collection[element=Waypoint] { collection: master-detail; ops: add remove reorder clear; }
            """));
        r.run();
        assertThat(waypoints.kind()).isEqualTo(CollectionKind.MASTER_DETAIL);
        assertThat(waypoints.view()).isInstanceOf(CollectionViews.MasterDetail.class);
        assertThat(waypoints.offered("duplicate")).isFalse();
        assertThat(button(waypoints, "duplicate", 0)).isNull();
        assertThat(button(waypoints, "remove", 0)).isNotNull(); // in the header, on the selection
        r.settle();
        // and back
        r.ui.styles().sheet(Rig.DEFAULT);
        r.run();
        assertThat(waypoints.kind()).isEqualTo(CollectionKind.TABLE);
        assertThat(button(waypoints, "duplicate", 2)).isNotNull();
    }

    @Test
    void operationsShowOnHoverWhenTheStyleSaysSo() {
        Rig r = new Rig(PipelineTest.themeWith("default-theme.tss", """
            collection[element=Waypoint] { ops-placement: hover; }
            collection[element=Patrol] { ops-placement: row-end; }
            """));
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor w = editor(g.panel, "waypoints");
        assertThat(w.placement()).isEqualTo(com.truesim.ui.controls.props.PropsStyles.OpsPlacement.HOVER);
        Element item = PipelineTest.find(w.element(), "collection-item");
        Element bar = PipelineTest.find(item, "props");
        while (bar != null && !"ops".equals(bar.style())) {
            bar = r.ui.store().nextSibling(bar.slot()) == UiStore.NONE ? null
                : r.ui.store().element(r.ui.store().nextSibling(bar.slot()));
        }
        assertThat(bar).isNotNull();
        r.ui.interaction().dispatch(EventType.ENTER, item, 0);
        assertThat(bar.is(UiState.HOVER)).isTrue();
        r.ui.interaction().dispatch(EventType.EXIT, item, 0);
        assertThat(bar.is(UiState.HOVER)).isFalse();
        CollectionEditor patrols = editor(g.panel, "patrols"); // a list: its item operations act on the selection
        assertThat(patrols.opsOnItems()).isFalse();
        assertThat(button(patrols, "remove", 0)).isNotNull();
    }

    @Test
    void addingToASealedTypeOffersItsSubtypes() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor orders = editor(g.panel, "orders");
        assertThat(orders.kind()).isEqualTo(CollectionKind.FOLDOUTS);
        r.click(button(orders, "add", 0).element());
        Dialog d = orders.dialog();
        assertThat(d.showing()).isTrue();
        assertThat(r.ui.text(d.button(0).labelElement()).toString()).isEqualTo("Move");
        assertThat(r.ui.text(d.button(1).labelElement()).toString()).isEqualTo("Hold");
        r.settle();
        r.click(d.button(1).element());
        r.settle();
        assertThat(g.squad.get().orders()).hasSize(3);
        assertThat(g.squad.get().orders().get(2)).isEqualTo(new Hold(0));
        // cancelled: nothing
        orders.add(0);
        orders.dialog().close(Dialog.CANCELLED);
        r.settle();
        assertThat(g.squad.get().orders()).hasSize(3);
        assertThat(orders.label(0)).isEqualTo("Move 1");
    }

    @Test
    void sizesAndUniquenessDisableOperations() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor w = editor(g.panel, "waypoints"); // @MinSize(1) @MaxSize(6)
        for (int i = 0; i < 3; i++) {
            assertThat(w.reason("add", -1)).isNull();
            w.run("add", -1);
        }
        r.run();
        assertThat(g.squad.get().waypoints()).hasSize(6);
        assertThat(g.squad.get().waypoints().get(5)).isEqualTo(Waypoint.DEFAULTS);
        assertThat(w.reason("add", -1)).isEqualTo("at most 6 items");
        assertThat(w.reason("duplicate", 0)).isEqualTo("at most 6 items");
        assertThat(button(w, "add", 0).enabled()).isFalse();
        assertThat(w.insert(0, Waypoint.DEFAULTS)).isFalse();
        while (g.squad.get().waypoints().size() > 1) {
            w.run("remove", 0);
        }
        r.run();
        assertThat(w.reason("remove", 0)).isEqualTo("at least 1 items");
        assertThat(w.reason("clear", -1)).isEqualTo("at least 1 items");
        assertThat(button(w, "remove", 0).enabled()).isFalse();
        assertThat(w.write(List.of(), "clear", "waypoints")).isFalse();
        assertThat(w.message()).isEqualTo("needs at least 1 items");
        assertThat(w.element().is(UiState.INVALID)).isTrue();
        assertThat(w.reason("up", 0)).isEqualTo("first");
        assertThat(w.reason("down", 0)).isEqualTo("last");
        assertThat(w.reason("remove", 5)).isNotNull();

        CollectionEditor notes = editor(g.panel, "notes"); // @Unique
        assertThat(notes.reason("duplicate", 0)).isEqualTo("items must differ");
        assertThat(notes.write(List.of("a", "a"), "set", "notes")).isFalse();
        assertThat(notes.message()).isEqualTo("items must differ");
        assertThat(notes.write(List.of("a", "b"), "set", "notes")).isTrue();
        assertThat(g.squad.get().notes()).containsExactly("a", "b");
        assertThat(notes.element().is(UiState.INVALID)).isFalse();

        CollectionEditor stats = editor(g.panel, "stats"); // a map: unique keys
        assertThat(stats.isMap()).isTrue();
        assertThat(stats.reason("add", -1)).isNull();
        stats.run("add", -1);
        r.run();
        assertThat(g.squad.get().stats()).containsEntry("", 0).containsEntry("kills", 3);
        assertThat(stats.reason("add", -1)).isEqualTo("a new entry's key would repeat one");
        assertThat(stats.reason("duplicate", 0)).isEqualTo("items must differ");
        assertThat(stats.write(List.of(Map.entry("a", 1), Map.entry("a", 2)), "set", "stats")).isFalse();
        assertThat(stats.message()).isEqualTo("keys must differ");
        assertThat(stats.label(0)).isEqualTo("kills: 3");
    }

    @Test
    void nestedEditsInListsMakeANewRootAndUndo() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        List<Edit> edits = new ArrayList<>();
        g.panel.onEdit(edits::add);
        Squad start = g.squad.get();
        CollectionEditor w = editor(g.panel, "waypoints");
        PropertyPanel<?> second = PipelineTest.nested(g.panel, "waypoints[1]");
        Binding<Object, Double> speed = PipelineTest.binding(second, "speed");
        speed.control().set(12.0);
        r.run();
        Squad after = g.squad.get();
        assertThat(after.waypoints().get(1)).isEqualTo(new Waypoint("Tower", 12, true));
        assertThat(after.waypoints().get(0)).isSameAs(start.waypoints().get(0)); // only the path is copied
        assertThat(after.home()).isSameAs(start.home());
        assertThat(edits).hasSize(1);
        assertThat(edits.getFirst().path()).isEqualTo("waypoints[1].speed");
        w.run("up", 1);
        w.run("duplicate", 0);
        w.run("remove", 3);
        r.run();
        assertThat(g.squad.get().waypoints()).extracting(Waypoint::name).containsExactly("Tower", "Tower", "Gate");
        assertThat(g.squad.get().waypoints().get(0)).isNotSameAs(g.squad.get().waypoints().get(1));
        assertThat(edits).extracting(Edit::operation).containsExactly("set", "move", "duplicate", "remove");
        assertThat(w.move(0, 0)).isFalse();
        assertThat(w.move(0, 3)).isTrue();
        r.run();
        for (int i = edits.size() - 1; i >= 0; i--) {
            edits.get(i).undo();
        }
        r.run();
        assertThat(g.squad.get()).isEqualTo(start);
        assertThat(speed.control().get()).isEqualTo(12.0); // that panel was dropped by the duplicate
        Binding<Object, Double> now = PipelineTest.binding(PipelineTest.nested(g.panel, "waypoints[1]"), "speed");
        assertThat(now.control().get()).isEqualTo(7.5); // the item's panel shows the undone value
        for (Edit e : edits) {
            e.redo();
        }
        r.run();
        assertThat(g.squad.get().waypoints()).extracting(Waypoint::name).containsExactly("Tower", "Gate", "Tower");
        assertThat(w.itemPath(2)).isEqualTo("waypoints[2]");
    }

    @Test
    void clearAsksFirst() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor patrols = editor(g.panel, "patrols"); // master-detail: item ops in the header
        assertThat(patrols.view().selects()).isTrue();
        assertThat(patrols.view().selected()).isZero();
        r.click(button(patrols, "clear", 0).element()); // the default theme: confirm: clear
        r.settle();
        assertThat(patrols.dialog().showing()).isTrue();
        r.click(patrols.dialog().button(1).element()); // OK
        r.settle();
        assertThat(g.squad.get().patrols()).isEmpty();
        assertThat(patrols.view().selected()).isEqualTo(-1);
        assertThat(patrols.reason("clear", -1)).isEqualTo("empty");
        patrols.insert(0, new Patrol("South", 1, Behaviour.SEARCH));
        r.run();
        assertThat(patrols.view().selected()).isZero();
        CollectionViews.MasterDetail md = (CollectionViews.MasterDetail) patrols.view();
        ListView<Object> list = md.list();
        assertThat(list.reorderable()).isTrue();
        patrols.insert(1, new Patrol("West", 2, Behaviour.GUARD));
        r.run();
        list.selection().select(1, list.rows().get(1));
        r.run();
        assertThat(PipelineTest.nested(g.panel, "patrols[1]").value()).isEqualTo(new Patrol("West", 2,
            Behaviour.GUARD));
    }

    @Test
    void chipsCompactAndContextMenus() {
        Rig r = new Rig();
        PropsGallery g = PropsGallery.build(r.c, r.ui.root());
        r.settle();
        CollectionEditor tags = editor(g.panel, "tags"); // a set of enums
        assertThat(tags.kind()).isEqualTo(CollectionKind.CHIPS);
        CollectionViews.Chips chips = (CollectionViews.Chips) tags.view();
        assertThat(chips.adds()).isTrue();
        r.click(chips.addButton().element());
        r.settle();
        assertThat(chips.menu().isOpen()).isTrue();
        assertThat(chips.menu().count()).isEqualTo(2); // the constants not in the set
        chips.menu().item(0).element();
        r.click(chips.menu().item(1).element());
        r.settle();
        assertThat(g.squad.get().tags()).containsExactly(Behaviour.GUARD, Behaviour.SEARCH);
        r.click(button(tags, "remove", 0).element());
        r.settle();
        assertThat(g.squad.get().tags()).containsExactly(Behaviour.SEARCH);

        CollectionEditor notes = editor(g.panel, "notes");
        assertThat(notes.kind()).isEqualTo(CollectionKind.COMPACT);
        CollectionViews.Compact compact = (CollectionViews.Compact) notes.view();
        r.click(PipelineTest.find(compact.element(), "button"));
        r.settle();
        assertThat(compact.dialog().showing()).isTrue();
        notes.insert(2, "Spare");
        r.settle();
        assertThat(r.ui.text(PipelineTest.find(compact.element(), "label")).toString()).isEqualTo("3 items");
        compact.dialog().close(0);
        r.settle();
        assertThat(compact.dialog().showing()).isFalse();

        CollectionEditor waypoints = editor(g.panel, "waypoints");
        waypoints.contextAction("Rename", (e, i) -> e.write(CollectionOps.set(e.items(), i,
            new Waypoint("Renamed", 1, false)), "rename", e.itemPath(i)));
        Element item = PipelineTest.find(waypoints.element(), "collection-item");
        r.ui.interaction().dispatch(EventType.CONTEXT, item, 0);
        r.settle();
        assertThat(waypoints.menu().isOpen()).isTrue();
        assertThat(waypoints.menu().count()).isEqualTo(5); // duplicate, up, down, remove, rename
        r.click(waypoints.menu().item(4).element());
        r.settle();
        assertThat(g.squad.get().waypoints().getFirst().name()).isEqualTo("Renamed");
    }

    /** A squad's route with many points: virtualized kinds. */
    public record Route(@Factory(PointFactory.class) List<Waypoint> points, @Reorderable(false) List<Command> orders,
        int[] numbers, List<String> names) {
    }

    /** Makes points for {@link Route}. */
    public static final class PointFactory implements Supplier<Waypoint> {
        @Override
        public Waypoint get() {
            return new Waypoint("Made", 1, false);
        }
    }

    @Test
    void largeCollectionsVirtualize() {
        Rig r = new Rig();
        List<Waypoint> many = new ArrayList<>();
        List<Command> orders = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(new Waypoint("P" + i, i % 20, i % 2 == 0));
            orders.add(i % 2 == 0 ? new Move("T" + i, 1) : new Hold(i));
        }
        VersionedValue<Route> route = r.c.clock().value(new Route(many, orders, new int[] {1, 2}, List.of("a")));
        PropertyPanel<Route> p = PropertyBuilder.forType(Route.class).collection("orders", CollectionKind.FOLDOUTS)
            .build(r.c, route);
        r.add(p.element()).settle();
        CollectionEditor points = editor(p, "points");
        CollectionViews.Switching table = (CollectionViews.Switching) points.view();
        assertThat(table.current()).isInstanceOf(CollectionViews.VirtualTable.class);
        assertThat(table.selects()).isTrue();
        CollectionEditor os = editor(p, "orders");
        assertThat(((CollectionViews.Switching) os.view()).current()).isInstanceOf(CollectionViews.MasterDetail.class);
        assertThat(os.offered("reorder")).isFalse();
        points.run("add", -1);
        r.run();
        assertThat(route.get().points().getLast()).isEqualTo(new Waypoint("Made", 1, false));
        while (route.get().points().size() > 50) {
            points.run("remove", 0);
        }
        r.run();
        assertThat(table.current()).isInstanceOf(CollectionViews.InlineTable.class);

        CollectionEditor numbers = editor(p, "numbers"); // an array of simple values
        assertThat(numbers.simple()).isTrue();
        assertThat(numbers.kind()).isEqualTo(CollectionKind.TABLE);
        numbers.run("add", -1);
        r.run();
        assertThat(route.get().numbers()).containsExactly(1, 2, 0);
        Binding<Object, Double> first = PipelineTest.binding(PipelineTest.nested(p, "numbers[0]"), "value");
        first.control().set(9.0);
        r.run();
        assertThat(route.get().numbers()).containsExactly(9, 2, 0);
        CollectionEditor names = editor(p, "names");
        names.run("duplicate", 0);
        r.run();
        assertThat(route.get().names()).containsExactly("a", "a");
        assertThat(names.label(0)).isEqualTo("a");
    }

    @Test
    void largeTablesEditCellsInPlace() {
        Rig r = new Rig();
        List<Waypoint> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(new Waypoint("P" + i, 1, false));
        }
        VersionedValue<Route> route = r.c.clock().value(new Route(many, List.of(), new int[0], List.of()));
        PropertyPanel<Route> p = PropertyBuilder.forType(Route.class).build(r.c, route);
        r.add(p.element()).settle();
        CollectionEditor points = editor(p, "points");
        CollectionViews.VirtualTable t = (CollectionViews.VirtualTable) ((CollectionViews.Switching) points.view())
            .current();
        t.mirror().set(3, new Waypoint("Edited", 2, true)); // what a cell editor commits
        r.run();
        assertThat(route.get().points().get(3)).isEqualTo(new Waypoint("Edited", 2, true));
        assertThat(t.table().columns()).hasSize(3);
        Set<String> headers = new java.util.HashSet<>();
        t.table().columns().forEach(c -> headers.add(c.header()));
        assertThat(headers).containsExactlyInAnyOrder("Name", "Speed", "Pause");
        assertThat(t.table().columns()).allMatch(com.truesim.ui.controls.data.Column::editable);
    }
}
