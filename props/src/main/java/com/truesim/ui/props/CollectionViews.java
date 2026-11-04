package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedList;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.controls.basic.Dialog;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.controls.data.Column;
import com.truesim.ui.controls.data.ListView;
import com.truesim.ui.controls.data.RowSource;
import com.truesim.ui.controls.data.TableView;
import com.truesim.ui.controls.data.Renderers;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The shipped {@link CollectionKind}s' views (design 5.11, UI18). Elements, all styled by the themes:
 * {@code collection-table} with a {@code collection-header-row} of {@code label.collection-column}s and a
 * {@code collection-item} per item (a nested panel laid out as {@code row}, then its operations);
 * {@code collection-foldouts} of {@code collection-item}s holding a collapsible each; {@code collection-master-detail}
 * with a {@code list-view} and a {@code collection-detail}; {@code collection-chips} of {@code chip}s (a label and a
 * remove button) and a {@code chip-input}; {@code collection-compact} with a summary and an edit button.
 */
@AllowAllocation("collection views are built and rebuilt on edit")
final class CollectionViews {

    private CollectionViews() {
    }

    /** The table kind: rows of editors, or a virtualized table above the threshold. */
    static CollectionKind.View table(CollectionEditor editor) {
        return new Switching(editor, InlineTable::new, VirtualTable::new);
    }

    /** The foldouts kind: collapsible items, or a virtualized master-detail list above the threshold. */
    static CollectionKind.View foldouts(CollectionEditor editor) {
        return new Switching(editor, Foldouts::new, MasterDetail::new);
    }

    /** An item's container {@code collection-item} (id {@code item-<index>}), with its context menu. */
    private static Element item(CollectionEditor editor, int index) {
        Ui ui = editor.row().ui();
        Element e = ui.element("collection-item", null);
        e.id("item-" + index);
        e.semantics(Role.LIST_ITEM, editor.label(index));
        editor.contextMenu(e, index);
        return e;
    }

    /** A view that is one of two, by the number of items against {@code virtualize-above}. */
    static final class Switching implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Function<CollectionEditor, CollectionKind.View> small;
        private final Function<CollectionEditor, CollectionKind.View> large;
        private final Element root;
        private CollectionKind.View current;
        private boolean big;

        Switching(CollectionEditor editor, Function<CollectionEditor, CollectionKind.View> small,
            Function<CollectionEditor, CollectionKind.View> large) {
            this.editor = editor;
            this.small = small;
            this.large = large;
            root = editor.row().ui().element("props", "collection-view");
        }

        /** The view shown now. */
        CollectionKind.View current() {
            return current;
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public void structure() {
            boolean b = editor.size() > editor.virtualizeAbove();
            if (current == null || b != big) {
                if (current != null) {
                    current.dispose();
                    current.element().destroy();
                }
                big = b;
                current = (b ? large : small).apply(editor);
                root.add(current.element());
            }
            current.structure();
        }

        @Override
        public void update() {
            current.update();
        }

        @Override
        public void poll() {
            if (current != null) {
                current.poll();
            }
        }

        @Override
        public int selected() {
            return current == null ? -1 : current.selected();
        }

        @Override
        public boolean selects() {
            return current != null && current.selects();
        }

        @Override
        public void dispose() {
            if (current != null) {
                current.dispose();
            }
        }
    }

    /** Rows of editors under a header of the element's property labels; maps: a key and a value column. */
    static final class InlineTable implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Element root;
        private final List<PropertyPanel<?>> panels = new ArrayList<>();
        private Element header;

        InlineTable(CollectionEditor editor) {
            this.editor = editor;
            root = editor.row().ui().element("collection-table", null);
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public void structure() {
            Ui ui = editor.row().ui();
            for (PropertyPanel<?> p : panels) {
                p.dispose();
            }
            panels.clear();
            while (ui.store().firstChild(root.slot()) != com.truesim.ui.core.UiStore.NONE) {
                ui.store().element(ui.store().firstChild(root.slot())).destroy();
            }
            header = ui.element("collection-header-row", null);
            root.add(header);
            PropertyModel<Object> m = editor.elementModel(editor.size() > 0 ? editor.items().getFirst() : null);
            for (Property<Object, ?> p : m.properties()) {
                header.add(ui.label("collection-column", p.label()));
            }
            for (int i = 0; i < editor.size(); i++) {
                Element item = item(editor, i);
                PropertyPanel<?> panel = editor.panel(i, Layouts.ROW);
                panels.add(panel);
                item.add(panel.element());
                if (editor.opsOnItems()) {
                    editor.itemOps(item, i);
                }
                root.add(item);
            }
        }

        @Override
        public void dispose() {
            for (PropertyPanel<?> p : panels) {
                p.dispose();
            }
            panels.clear();
        }
    }

    /** Collapsible items, each a nested panel titled with the item's label. */
    static final class Foldouts implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Element root;
        private final List<PropertyPanel<?>> panels = new ArrayList<>();
        private final List<Collapsible> foldouts = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();

        Foldouts(CollectionEditor editor) {
            this.editor = editor;
            root = editor.row().ui().element("collection-foldouts", null);
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public void structure() {
            dispose();
            Ui ui = editor.row().ui();
            while (ui.store().firstChild(root.slot()) != com.truesim.ui.core.UiStore.NONE) {
                ui.store().element(ui.store().firstChild(root.slot())).destroy();
            }
            for (int i = 0; i < editor.size(); i++) {
                Element item = item(editor, i);
                PropertyPanel<?> panel = editor.panel(i, Layouts.COMPACT);
                panels.add(panel);
                String title = editor.label(i);
                titles.add(title);
                Collapsible c = editor.controls().collapsible(title, panel.element(),
                    editor.controls().clock().booleanValue(true));
                foldouts.add(c);
                item.add(c.element());
                if (editor.opsOnItems()) {
                    editor.itemOps(item, i);
                }
                root.add(item);
            }
        }

        @Override
        public void update() {
            for (int i = 0; i < foldouts.size() && i < editor.size(); i++) {
                String title = editor.label(i);
                if (!title.equals(titles.get(i))) {
                    titles.set(i, title);
                    Button header = foldouts.get(i).header();
                    header.text(title);
                }
            }
        }

        @Override
        public void dispose() {
            for (PropertyPanel<?> p : panels) {
                p.dispose();
            }
            for (Collapsible c : foldouts) {
                c.dispose();
            }
            panels.clear();
            foldouts.clear();
            titles.clear();
        }
    }

    /** Mirrors the editor's items into a versioned list for the data controls. */
    private static void sync(CollectionEditor editor, VersionedList<Object> mirror) {
        List<Object> items = editor.items();
        while (mirror.size() > items.size()) {
            mirror.remove(mirror.size() - 1);
        }
        for (int i = 0; i < items.size(); i++) {
            if (i < mirror.size()) {
                if (mirror.get(i) != items.get(i)) {
                    mirror.set(i, items.get(i));
                }
            } else {
                mirror.add(items.get(i));
            }
        }
    }

    /** A virtualized list of the items' labels (drag rows to reorder) and the selected item's panel. */
    static final class MasterDetail implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Element root;
        private final Element detail;
        private final VersionedList<Object> mirror;
        private final ListView<Object> list;
        private PropertyPanel<?> panel;
        private int shown = -1;
        private long selectionSeen = -1;

        MasterDetail(CollectionEditor editor) {
            this.editor = editor;
            Ui ui = editor.row().ui();
            root = ui.element("collection-master-detail", null);
            mirror = editor.controls().clock().list();
            list = new ListView<>(ui, editor.row().property().label(), RowSource.of(mirror),
                Renderers.ofText(o -> editor.label(mirror.indexOf(o))));
            list.textOf(o -> editor.label(mirror.indexOf(o)));
            list.reorderable(editor.offered("reorder"));
            list.reorder(editor::move);
            detail = ui.element("collection-detail", null);
            root.add(list.root()).add(detail);
        }

        /** The list. */
        ListView<Object> list() {
            return list;
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public boolean selects() {
            return true;
        }

        @Override
        public int selected() {
            if (list.selection().size() == 0) {
                return -1;
            }
            int a = list.selection().anchor();
            return a >= 0 && a < editor.size() ? a : -1;
        }

        @Override
        public void structure() {
            sync(editor, mirror);
            int s = selected();
            if (s < 0 && editor.size() > 0) {
                list.selection().select(0, mirror.get(0)); // the first item's panel shows from the start
            } else if (s >= editor.size() && s > 0) {
                list.selection().select(editor.size() - 1, mirror.get(editor.size() - 1));
            }
            selectionSeen = list.selection().version();
            show(true);
        }

        @Override
        public void update() {
            sync(editor, mirror);
            show(false);
        }

        private void show(boolean rebuild) {
            int s = selected();
            if (s == shown && !rebuild && panel != null) {
                return;
            }
            if (panel != null) {
                panel.dispose();
                panel = null;
            }
            shown = s;
            if (s >= 0) {
                panel = editor.panel(s, Layouts.FORM);
                detail.add(panel.element());
            }
        }

        @Override
        public void poll() {
            if (list.selection().version() != selectionSeen) {
                selectionSeen = list.selection().version();
                if (selected() != shown) {
                    show(true);
                }
            }
        }

        @Override
        public void dispose() {
            if (panel != null) {
                panel.dispose();
                panel = null;
            }
            list.dispose();
        }
    }

    /** A virtualized table: a column per property of the element type, cells edited in place. */
    static final class VirtualTable implements CollectionKind.View {
        private final CollectionEditor editor;
        private final VersionedList<Object> mirror;
        private final TableView<Object> table;
        private long mirrorSeen;

        @SuppressWarnings({"unchecked", "rawtypes"})
        VirtualTable(CollectionEditor editor) {
            this.editor = editor;
            Ui ui = editor.row().ui();
            mirror = editor.controls().clock().list();
            table = TableView.of(ui, editor.row().property().label(), editor.controls().clock(), mirror);
            PropertyModel<Object> m = editor.elementModel(null);
            for (Property<Object, ?> p : m.properties()) {
                Property<Object, Object> q = (Property<Object, Object>) p;
                Column<Object> c = Column.ofText(p.label(), o -> o == null ? "" : String.valueOf(q.get(o)));
                com.truesim.ui.controls.data.ValueEditor ve = cellEditor(q);
                if (ve != null && q.writable() && !editor.row().readOnly()) {
                    c.editor(ve, o -> q.get(o), (o, v) -> q.with(o, v));
                }
                table.add(c);
            }
            mirrorSeen = mirror.version();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static com.truesim.ui.controls.data.ValueEditor cellEditor(Property<Object, Object> p) {
            Class<?> t = p.type();
            com.truesim.ui.props.annotations.Range r = p.annotation(com.truesim.ui.props.annotations.Range.class);
            double min = r == null ? -1e9 : r.min();
            double max = r == null ? 1e9 : r.max();
            Class<?> u = Editors.unboxed(t);
            if (u == int.class) {
                return com.truesim.ui.controls.data.Editors.integer((int) min, (int) max, 1);
            }
            if (u == double.class) {
                return com.truesim.ui.controls.data.Editors.decimal(min, max, 0.1, 3);
            }
            if (u == boolean.class) {
                return com.truesim.ui.controls.data.Editors.bool();
            }
            if (t.isEnum()) {
                return com.truesim.ui.controls.data.Editors.enumeration((Class) t);
            }
            if (t == String.class) {
                return com.truesim.ui.controls.text.TextCellEditor.create(p.label());
            }
            return null;
        }

        @Override
        public Element element() {
            return table.root();
        }

        @Override
        public boolean selects() {
            return true;
        }

        @Override
        public int selected() {
            if (table.selection().size() == 0) {
                return -1;
            }
            int a = table.selection().anchor();
            return a >= 0 && a < editor.size() ? a : -1;
        }

        @Override
        public void structure() {
            sync(editor, mirror);
            mirrorSeen = mirror.version();
        }

        @Override
        public void update() {
            structure();
        }

        @Override
        public void dispose() {
            table.dispose();
        }

        /** The table. */
        TableView<Object> table() {
            return table;
        }

        /** The versioned list the table shows (cell edits land here first). */
        VersionedList<Object> mirror() {
            return mirror;
        }

        @Override
        public void poll() {
            if (mirror.version() != mirrorSeen) {
                mirrorSeen = mirror.version();
                List<Object> now = new ArrayList<>(mirror);
                if (!now.equals(editor.items())) {
                    editor.write(now, "set", editor.row().path());
                }
            }
        }
    }

    /** Tags: a chip per item with a remove button; enum constants added from a menu, text typed. */
    static final class Chips implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Element root;
        private final Element chips;
        private final List<Button> buttons = new ArrayList<>();
        private Menu menu;
        private TextEditor input;
        private Button add;

        Chips(CollectionEditor editor) {
            this.editor = editor;
            Ui ui = editor.row().ui();
            root = ui.element("collection-chips", null);
            chips = ui.element("props", "chips");
            root.add(chips);
            Class<?> t = editor.elementType();
            if (editor.offered("add") && t == String.class) {
                input = TextEditor.field(ui, "chip-input", "Add " + editor.row().property().label());
                input.placeholder("Add");
                input.listener(new TextEditor.Listener() {
                    @Override
                    public void submitted(TextEditor e) {
                        String text = e.text().toString().trim();
                        if (!text.isEmpty() && !editor.items().contains(text)
                            && editor.insert(editor.size(), text)) {
                            e.text("");
                        }
                    }
                });
                root.add(input.element());
            } else if (editor.offered("add") && t.isEnum()) {
                add = new Button(editor.controls(), "op-add", "", "Add " + editor.row().property().label());
                add.element().style("props-op");
                add.element().id("add-chip");
                ui.layout().visible(add.labelElement().slot(), false);
                menu = editor.controls().menu(editor.row().property().label());
                add.onClick(() -> {
                    menu.clear();
                    for (Object c : t.getEnumConstants()) {
                        if (!editor.items().contains(c)) {
                            menu.item(Editors.enumLabel((Enum<?>) c), () -> editor.insert(editor.size(), c));
                        }
                    }
                    if (menu.count() > 0) {
                        menu.open(add.element(), true);
                    }
                });
                buttons.add(add);
                root.add(add.element());
            }
        }

        @Override
        public boolean adds() {
            return input != null || menu != null;
        }

        /** The button adding an enum constant, or null. */
        Button addButton() {
            return add;
        }

        /** The text field adding text, or null. */
        TextEditor input() {
            return input;
        }

        /** The menu of constants to add, or null. */
        Menu menu() {
            return menu;
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public void structure() {
            Ui ui = editor.row().ui();
            while (ui.store().firstChild(chips.slot()) != com.truesim.ui.core.UiStore.NONE) {
                ui.store().element(ui.store().firstChild(chips.slot())).destroy();
            }
            for (int i = 0; i < editor.size(); i++) {
                Element chip = ui.element("chip", null);
                chip.id("item-" + i);
                chip.add(ui.label("chip-label", editor.label(i)));
                if (editor.offered("remove")) {
                    int index = i;
                    Button x = editor.opButton("remove", () -> index);
                    x.name("Remove " + editor.label(i));
                    x.enabled(editor.reason("remove", index) == null);
                    chip.add(x.element());
                }
                chips.add(chip);
            }
        }

        @Override
        public void update() {
            structure();
        }
    }

    /** A summary ("12 items") and a button opening a dialog with a master-detail editor of the same items. */
    static final class Compact implements CollectionKind.View {
        private final CollectionEditor editor;
        private final Element root;
        private final Element summary;
        private final Button edit;
        private final StringBuilder text = new StringBuilder();
        private Dialog dialog;
        private CollectionKind.View inner;

        Compact(CollectionEditor editor) {
            this.editor = editor;
            Ui ui = editor.row().ui();
            root = ui.element("collection-compact", null);
            summary = ui.label("collection-summary", "");
            edit = new Button(editor.controls(), "Edit");
            edit.element().style("props-op");
            edit.element().id("edit");
            edit.onClick(this::open);
            root.add(summary).add(edit.element());
        }

        @Override
        public boolean counts() {
            return true;
        }

        /** The dialog while open, or null. */
        Dialog dialog() {
            return dialog;
        }

        /** Opens the dialog. */
        void open() {
            if (dialog != null && dialog.showing()) {
                return;
            }
            dialog = new Dialog(editor.controls(), editor.row().property().label(), "");
            inner = editor.open(CollectionKind.MASTER_DETAIL);
            Element buttons = dialog.defaultButton("Close", 0).element().parent();
            buttons.detach();
            dialog.element().add(inner.element()).add(buttons);
            dialog.onClose(r -> {
                editor.close(inner);
                inner = null;
            });
            dialog.show();
        }

        @Override
        public Element element() {
            return root;
        }

        @Override
        public void structure() {
            text.setLength(0);
            text.append(editor.size()).append(editor.size() == 1 ? " item" : " items");
            editor.row().ui().text(summary, text);
        }

        @Override
        public void dispose() {
            if (dialog != null && dialog.showing()) {
                dialog.close(Dialog.CANCELLED);
            }
            edit.dispose();
        }
    }
}
