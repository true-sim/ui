package com.truesim.ui.controls.data;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.VersionedList;
import com.truesim.common.state.VersionedTree;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.style.InlineStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Length;

/**
 * A gallery of the data controls for screenshots and theme checks: a scroll pane with sticky section headers, a
 * 10,000-row list with a multiple selection, a list of mixed values through the built-in renderers, a lazy tree with
 * open nodes, and a 1,000-row table with a frozen column, a sort, a filter row and a cell being edited. Build it in a
 * UI of about 1600 x 1000 pixels and update a few frames ({@link #settle}); every control's state is set by code, so
 * the picture is the same each time. The integrator renders it through the engine's {@code GpuUiRenderer} under
 * both shipped themes ({@code Tss.defaultTheme()}, {@code Tss.testTheme()}).
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * DataGallery gallery = DataGallery.build(ui);
 * DataGallery.settle(ui, 1600, 1000);
 * renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), 1600, 1000);
 * }</pre>
 */
public final class DataGallery {

    enum Kind { SCOUT, TANK, MEDIC, ENGINEER }

    record Unit(String name, int hp, double speed, boolean alive, Kind kind, int squad) {
        Unit withHp(int value) {
            return new Unit(name, value, speed, alive, kind, squad);
        }

        Unit withName(String value) {
            return new Unit(value, hp, speed, alive, kind, squad);
        }
    }

    public final ScrollPane pane;
    public final ListView<String> list;
    public final ListView<Object> mixed;
    public final TreeView<String> tree;
    public final TableView<Unit> table;

    private DataGallery(ScrollPane pane, ListView<String> list, ListView<Object> mixed, TreeView<String> tree,
        TableView<Unit> table) {
        this.pane = pane;
        this.list = list;
        this.mixed = mixed;
        this.tree = tree;
        this.table = table;
    }

    private static Element sized(Ui ui, Element e, float w, float h) {
        ui.styles().inline(e.slot(), InlineStyle.builder().set(Styles.WIDTH, Length.px(w))
            .set(Styles.HEIGHT, Length.px(h)).build());
        return e;
    }

    /** Builds the gallery into {@code ui}'s root. */
    public static DataGallery build(Ui ui) {
        ChangeClock clock = ui.store().clock();
        Element root = ui.panel("hud");
        ui.root().add(root);
        ui.styles().inline(root.slot(), InlineStyle.builder()
            .set(Styles.LAYOUT, com.truesim.ui.style.value.LayoutKind.FLEX)
            .set(Styles.DIRECTION, com.truesim.ui.style.value.Direction.ROW)
            .set(Styles.WRAP, com.truesim.ui.style.value.Wrap.WRAP).set(Styles.GAP, 16f)
            .set(Styles.ALIGN_ITEMS, com.truesim.ui.style.value.Align.START).build());

        ScrollPane pane = new ScrollPane(ui, "Settings");
        root.add(sized(ui, pane.root(), 320, 300));
        String[] groups = {"Audio", "Video", "Controls", "Network", "Accessibility", "Debug"};
        for (String g : groups) {
            Element body = ui.panel(null);
            for (int i = 0; i < 4; i++) {
                body.add(ui.label(null, g + " setting " + (i + 1)));
            }
            pane.content().add(pane.section(ui.label("title", g), body));
        }

        VersionedList<String> names = clock.list();
        for (int i = 0; i < 10_000; i++) {
            names.add("Player " + i);
        }
        ListView<String> list = ListView.of(ui, "Players", names);
        list.selection().mode(Selection.Mode.MULTIPLE);
        list.reorderable(true);
        root.add(sized(ui, list.root(), 240, 300));

        VersionedList<Object> values = clock.list();
        values.add("Text");
        values.add(42);
        values.add(3.14159);
        values.add(true);
        values.add(false);
        values.add(Kind.TANK);
        values.add(new com.truesim.common.math.Vec3(1, 2.5, -3));
        values.add(com.truesim.ui.style.value.Color.hex("#3fa7ff"));
        ListView<Object> mixed = ListView.of(ui, "Values", values);
        root.add(sized(ui, mixed.root(), 240, 300));

        VersionedTree<String> model = clock.tree("project");
        VersionedTree.Node<String> assets = model.root().add("assets");
        VersionedTree.Node<String> textures = assets.add("textures");
        for (int i = 0; i < 5; i++) {
            textures.add("rock_" + i + ".png");
        }
        VersionedTree.Node<String> models = assets.add("models");
        VersionedTree.Node<String> src = model.root().add("src");
        src.add("Main.java");
        src.add("Game.java");
        model.root().add("README.md");
        TreeView<String> tree = new TreeView<>(ui, "Project", model, Renderers.text());
        tree.draggable(true);
        tree.lazy(models, node -> {
            node.add("ship.glb");
            node.add("station.glb");
        });
        tree.expand(assets, true);
        tree.expand(textures, true);
        tree.expand(models, true);
        root.add(sized(ui, tree.root(), 260, 300));

        VersionedList<Unit> units = clock.list();
        Kind[] kinds = {Kind.SCOUT, Kind.TANK, Kind.MEDIC, Kind.ENGINEER};
        for (int i = 0; i < 1_000; i++) {
            units.add(new Unit("Unit " + i, (i * 37) % 100, (i % 17) * 0.75, i % 5 != 0, kinds[i % 4], i % 8));
        }
        TableView<Unit> table = new TableView<>(ui, "Units", new DataView<>(clock, units),
            TableView.SelectionMode.MULTI_ROW);
        Column<Unit> name = Column.<Unit>ofText("Name", Unit::name).width(110)
            .editor(com.truesim.ui.controls.text.TextCellEditor.create("Name"), Unit::name, Unit::withName);
        table.add(name);
        com.truesim.ui.controls.text.TextColumnFilter.attach(ui, name, Unit::name, "Filter names");
        table.add(Column.<Unit>ofInt("HP", Unit::hp).width(70)
            .editor(Editors.integer(0, 100, 5), Unit::hp, Unit::withHp));
        table.add(Column.<Unit>ofDouble("Speed", Unit::speed, 2).width(80));
        table.add(Column.<Unit>ofBoolean("Alive", Unit::alive).width(60));
        table.add(Column.of("Kind", Unit::kind, Renderers.enumeration(), Enum::compareTo).width(100));
        table.add(Column.<Unit>ofInt("Squad", Unit::squad).width(70));
        table.freeze(1);
        table.column(0).filterView(ui.panel(null));
        root.add(sized(ui, table.root(), 560, 300));

        // state shown in the picture
        list.selection().select(2, names.get(2));
        list.selection().toggle(4, names.get(4));
        list.selection().toggle(5, names.get(5));
        mixed.selection().select(1, values.get(1));
        tree.selection().select(2, textures);
        table.sort(table.column(1), false);
        return new DataGallery(pane, list, mixed, tree, table);
    }

    /** Updates {@code ui} until the gallery settled (rows bound and measured), then scrolls to the shown places. */
    public static void settle(Ui ui, float width, float height) {
        for (int i = 0; i < 4; i++) {
            ui.update(width, height);
        }
    }

    /** Scrolls the views to their pictured places and starts an edit; then settle again. */
    public void pose(Ui ui, float width, float height) {
        pane.scrollTo(0, 70);
        list.scrollTo(0, 30);
        table.scrollTo(40, 0);
        settle(ui, width, height);
        table.edit(1, table.column(1));
        settle(ui, width, height);
    }
}
