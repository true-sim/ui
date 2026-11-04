package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Button;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.Dialog;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.controls.props.PropsStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.bind.Lens;
import com.truesim.ui.core.bind.Validator;
import com.truesim.ui.props.annotations.Addable;
import com.truesim.ui.props.annotations.ElementLabel;
import com.truesim.ui.props.annotations.Factory;
import com.truesim.ui.props.annotations.ListStyle;
import com.truesim.ui.props.annotations.MaxSize;
import com.truesim.ui.props.annotations.MinSize;
import com.truesim.ui.props.annotations.Removable;
import com.truesim.ui.props.annotations.Reorderable;
import com.truesim.ui.props.annotations.Unique;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The editor of a collection property (design 5.11, UI18): lists, sets, maps and arrays, of records (nested panels
 * through the lens chain root, list, item, field), sealed types and simple values. An element {@code collection}
 * (facts {@code property}, {@code element} (the element type's simple name, {@code Entry} for maps), {@code type} and
 * {@code kind}) with a header ({@code collection-header}: the count and the header operations) over the chosen
 * {@link CollectionKind}'s view. Its style picks the kind ({@code collection}), the operations shown ({@code ops}),
 * where they go ({@code ops-placement}), which ask first ({@code confirm}) and the virtualization threshold
 * ({@code virtualize-above}); operation buttons are {@code button.props-op#<op>} with icons {@code image#op-<op>} and
 * text from {@code op-label}.
 *
 * <p>Operations: {@code add} (the {@code @Factory}, the element type's {@code DEFAULTS} or constructor, or a type
 * chooser over a sealed type's permitted subtypes), {@code remove}, {@code reorder} (up and down, or drags in a
 * list), {@code duplicate} (a deep copy through the metadata), {@code clear}, and a {@code context-menu} per item with
 * the same and code's {@link #contextAction}s. One that would break {@code @MinSize}, {@code @MaxSize} or
 * {@code @Unique} is disabled, its tooltip (with {@link PropertyBuilder#tooltips}) and {@link #reason} saying why.
 * Each writes a new collection (and through the lenses a new root) and reports an {@link Edit}.
 */
@AllowAllocation("collection editors are built and rebuilt on edit")
public final class CollectionEditor implements PropertyEditor {

    /** The operations. */
    public static final List<String> OPS = List.of("add", "remove", "reorder", "duplicate", "clear", "context-menu");

    /** An extra item of the context menu. */
    @FunctionalInterface
    public interface ContextAction {
        /** Runs on item {@code index} of {@code editor}. */
        void run(CollectionEditor editor, int index);
    }

    private final PropertyRow row;
    private final Ui ui;
    private final Controls controls;
    private final Element root;
    private final Element header;
    private final Element count;
    private final Element headerOps;
    private final Element body;
    private final Class<?> type;
    private final Class<?> elementType;
    private final boolean map;
    private final List<View> views = new ArrayList<>();
    private final List<Button> opButtons = new ArrayList<>();
    private final List<int[]> opHandles = new ArrayList<>(); // each button's last style handle
    private Menu menu;
    private final StringBuilder countText = new StringBuilder();
    private final java.util.Map<String, ContextAction> contextActions = new java.util.LinkedHashMap<>();
    private List<Object> items = List.of();
    private String kind = "";
    private List<String> ops = OPS;
    private List<String> confirm = List.of();
    private PropsStyles.OpsPlacement placement = PropsStyles.OpsPlacement.HEADER;
    private int virtualizeAbove = 50;
    private int styleHandle = -2;
    private long seen = -1;
    private String message;
    private Dialog dialog;
    private String lastKind;
    private List<String> lastOps;

    /** A view and where it came from. */
    private record View(CollectionKind.View view, boolean main) {
    }

    private CollectionEditor(PropertyRow row) {
        this.row = row;
        this.ui = row.ui();
        this.controls = row.controls();
        this.type = row.property().type();
        this.map = Map.class.isAssignableFrom(type);
        Class<?> e = row.property().elementType();
        this.elementType = map ? Map.Entry.class : e != null ? e : type.isArray() ? type.getComponentType()
            : Object.class;
        root = ui.element("collection", null);
        root.semantics(Role.LIST, row.property().label());
        header = ui.element("collection-header", null);
        count = ui.label("collection-count", "");
        headerOps = ui.element("props", "ops");
        body = ui.element("collection-body", null);
        header.add(count).add(headerOps);
        root.add(header).add(body);
        items = CollectionOps.items(row.model().get());
        seen = row.model().version();
        kind = chooseKind("");
        ops = annotatedOps();
        facts();
        showCount();
        setView();
    }

    /** The editor of {@code row}'s collection. */
    static PropertyEditor create(PropertyRow row) {
        return new CollectionEditor(row);
    }

    // ---------------------------------------------------------------- what views use

    @Override
    public Element element() {
        return root;
    }

    /** The row. */
    public PropertyRow row() {
        return row;
    }

    /** The controls. */
    public Controls controls() {
        return controls;
    }

    /** The items (a map's entries) as last seen. */
    public List<Object> items() {
        return items;
    }

    /** The number of items. */
    public int size() {
        return items.size();
    }

    /** The element type ({@link Map.Entry} for maps). */
    public Class<?> elementType() {
        return elementType;
    }

    /** Whether it edits a map. */
    public boolean isMap() {
        return map;
    }

    /** The kind shown. */
    public String kind() {
        return kind;
    }

    /** The operations offered (style, else annotations). */
    public List<String> ops() {
        return ops;
    }

    /** Where operations show. */
    public PropsStyles.OpsPlacement placement() {
        return placement;
    }

    /** Above how many items views virtualize. */
    public int virtualizeAbove() {
        return virtualizeAbove;
    }

    /** Why the last write was refused, or null. */
    public String message() {
        return message;
    }

    /** The header's operations bar. */
    public Element headerOps() {
        return headerOps;
    }

    /** The main view. */
    public CollectionKind.View view() {
        for (View v : views) {
            if (v.main()) {
                return v.view();
            }
        }
        return null;
    }

    /** Adds an item to every item's context menu; returns this. */
    public CollectionEditor contextAction(String label, ContextAction action) {
        contextActions.put(label, action);
        return this;
    }

    /** The lens from the root to item {@code index}. */
    public Lens<Object, Object> itemLens(int index) {
        return row.fromRoot().then(CollectionOps.item(type, elementType, index));
    }

    /** The path of item {@code index} ({@code waypoints[2]}). */
    public String itemPath(int index) {
        return row.path() + "[" + index + "]";
    }

    /** Whether items are simple values (no properties of their own): edited by one editor each. */
    public boolean simple() {
        return !map && row.builder().models().of(elementType).properties().isEmpty()
            && row.builder().models().of(elementType).subtypes().isEmpty();
    }

    /**
     * The model item {@code item} is edited by: a map entry's key and value, a simple value as one {@code value}
     * property, else the item's class's model (a sealed type's items by their own subtype).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PropertyModel<Object> elementModel(Object item) {
        if (map) {
            Class<?> k = row.property().keyType() == null ? Object.class : row.property().keyType();
            Class<?> v = row.property().elementType() == null ? Object.class : row.property().elementType();
            return (PropertyModel) PropertyModel.builder(Map.Entry.class).source("entry")
                .property(Property.<Map.Entry, Object>builder("key", k).getter(Map.Entry::getKey)
                    .wither((en, x) -> new AbstractMap.SimpleImmutableEntry<>(x, en.getValue())).build())
                .property(Property.<Map.Entry, Object>builder("value", v).getter(Map.Entry::getValue)
                    .wither((en, x) -> new AbstractMap.SimpleImmutableEntry<>(en.getKey(), x))
                    .element(null).build())
                .build();
        }
        Class<?> c = item != null ? item.getClass() : elementType;
        PropertyModel<Object> m = (PropertyModel<Object>) row.builder().models().of(c);
        if (!m.properties().isEmpty() || item != null && !row.builder().models().of(elementType).properties()
            .isEmpty()) {
            return m;
        }
        return (PropertyModel) PropertyModel.builder(Object.class).source("value")
            .property(Property.builder("value", elementType).getter(o -> o).wither((o, x) -> x)
                .annotations(row.property().annotations()).build())
            .build();
    }

    /** A nested panel editing item {@code index}, laid out as {@code layout}, inside the row's panel. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PropertyPanel<?> panel(int index, String layout) {
        Object item = index < items.size() ? items.get(index) : null;
        PropertyBuilder b = row.builder().nested(elementModel(item));
        return b.buildAt(row.panel(), itemLens(index), itemPath(index), layout);
    }

    /** The label of item {@code index}: its {@code @ElementLabel} property, a sealed subtype's label, or its text. */
    public String label(int index) {
        Object item = items.get(index);
        if (item == null) {
            return "(none)";
        }
        ElementLabel el = row.annotation(ElementLabel.class);
        if (el != null) {
            PropertyModel<Object> m = elementModel(item);
            Property<Object, ?> p = m.property(el.value());
            if (p != null) {
                return String.valueOf(p.get(item));
            }
        }
        if (item instanceof Map.Entry<?, ?> e) {
            return e.getKey() + ": " + e.getValue();
        }
        if (item instanceof Enum<?> en) {
            return Editors.enumLabel(en);
        }
        PropertyModel<?> m = row.builder().models().of(item.getClass());
        if (!m.properties().isEmpty()) {
            return m.label() + " " + (index + 1);
        }
        return String.valueOf(item);
    }

    // ---------------------------------------------------------------- operations

    /** Whether {@code op} is offered (by the style's {@code ops}, else the annotations). */
    public boolean offered(String op) {
        return ops.contains(op);
    }

    /** Why {@code op} on item {@code index} cannot run now, or null when it can. */
    public String reason(String op, int index) {
        int n = items.size();
        MinSize min = row.annotation(MinSize.class);
        MaxSize max = row.annotation(MaxSize.class);
        boolean unique = row.annotation(Unique.class) != null || map || Set.class.isAssignableFrom(type);
        if (row.readOnly()) {
            return "read-only";
        }
        switch (op) {
            case "add" -> {
                if (max != null && n >= max.value()) {
                    return "at most " + max.value() + " items";
                }
                if (map && containsKey(defaultKey())) {
                    return "a new entry's key would repeat one";
                }
            }
            case "remove", "clear" -> {
                if (min != null && (op.equals("clear") ? 0 : n - 1) < min.value()) {
                    return "at least " + min.value() + " items";
                }
                if (op.equals("clear") && n == 0) {
                    return "empty";
                }
            }
            case "duplicate" -> {
                if (max != null && n >= max.value()) {
                    return "at most " + max.value() + " items";
                }
                if (unique) {
                    return "items must differ";
                }
            }
            case "up" -> {
                if (index <= 0) {
                    return "first";
                }
            }
            case "down" -> {
                if (index < 0 || index >= n - 1) {
                    return "last";
                }
            }
            default -> {
                // no rule
            }
        }
        if (!op.equals("add") && !op.equals("clear") && (index < 0 || index >= n)) {
            return "no item selected";
        }
        return null;
    }

    private Class<?> keyType() {
        return row.property().keyType() == null ? Object.class : row.property().keyType();
    }

    private Object defaultKey() {
        return Defaults.of(keyType());
    }

    private boolean containsKey(Object key) {
        for (Object o : items) {
            if (o instanceof Map.Entry<?, ?> e && Objects.equals(e.getKey(), key)) {
                return true;
            }
        }
        return false;
    }

    /** Runs {@code op} on item {@code index}, asking first when the style's {@code confirm} lists it. */
    public void run(String op, int index) {
        if (reason(op, index) != null) {
            return;
        }
        if (confirm.contains(op) || confirm.contains("reorder") && (op.equals("up") || op.equals("down"))) {
            Dialog d = controls.confirm(Property.words(op) + "?", row.property().label() + ": " + Property.words(op)
                + (op.equals("clear") || op.equals("add") ? "" : " " + label(index)) + "?", "OK", "Cancel");
            d.onClose(result -> {
                if (result == 1) {
                    perform(op, index);
                }
            });
            dialog = d;
            d.show();
            return;
        }
        perform(op, index);
    }

    /** The dialog shown last (a confirmation or a type chooser), or null. */
    public Dialog dialog() {
        return dialog;
    }

    private void perform(String op, int index) {
        switch (op) {
            case "add" -> add(index < 0 ? items.size() : index + 1);
            case "remove" -> write(CollectionOps.remove(items, index), "remove", itemPath(index));
            case "up" -> move(index, index - 1);
            case "down" -> move(index, index + 2);
            case "duplicate" -> write(CollectionOps.add(items, index + 1,
                CollectionOps.copy(items.get(index), row.builder().models())), "duplicate", itemPath(index));
            case "clear" -> write(List.of(), "clear", row.path());
            default -> throw new IllegalArgumentException("unknown operation " + op);
        }
    }

    /** Moves item {@code from} to land before item {@code before} (indices before the move); returns success. */
    public boolean move(int from, int before) {
        if (from < 0 || from >= items.size() || before < 0 || before > items.size() || before == from
            || before == from + 1) {
            return false;
        }
        return write(CollectionOps.move(items, from, before), "move", itemPath(from));
    }

    /** Adds a new item at {@code at}: made by the factory, or of a subtype chosen in a dialog; returns success. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean add(int at) {
        List<Class<?>> subtypes = (List) row.builder().models().of(elementType).subtypes();
        if (!map && subtypes.size() > 1 && row.annotation(Factory.class) == null) {
            Dialog d = new Dialog(controls, "Add " + row.property().label(), "Choose a type");
            for (int i = 0; i < subtypes.size(); i++) {
                d.button(row.builder().models().of(subtypes.get(i)).label(), i);
            }
            d.cancelButton("Cancel", -1);
            d.onClose(result -> {
                if (result >= 0 && result < subtypes.size()) {
                    insert(at, newItem(subtypes.get(result)));
                }
            });
            dialog = d;
            d.show();
            return false;
        }
        return insert(at, newItem(subtypes.size() == 1 ? subtypes.getFirst() : elementType));
    }

    /** Adds {@code item} at {@code at}; returns success. */
    public boolean insert(int at, Object item) {
        if (reason("add", -1) != null) {
            return false;
        }
        return write(CollectionOps.add(items, Math.min(at, items.size()), item), "add", itemPath(at));
    }

    /** A new item of {@code of}: the {@code @Factory}, the model's factory, or defaults. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object newItem(Class<?> of) {
        if (map) {
            return new AbstractMap.SimpleImmutableEntry<>(defaultKey(), Defaults.of(row.property().elementType()
                == null ? Object.class : row.property().elementType()));
        }
        Factory f = row.annotation(Factory.class);
        if (f != null) {
            try {
                return ((Supplier) f.value().getDeclaredConstructor().newInstance()).get();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot make factory " + f.value().getName(), e);
            }
        }
        PropertyModel<?> m = row.builder().models().of(of);
        if (m.factory() != null) {
            return m.factory().get();
        }
        return Defaults.of(of);
    }

    /**
     * Writes {@code next} as the collection (a new container, a new root through the lenses) and reports an edit
     * {@code op} at {@code path}; refuses (returns false, {@link #message} why) what breaks the row's validators or a
     * map's unique keys.
     */
    public boolean write(List<?> next, String op, String path) {
        if (map && !CollectionOps.uniqueKeys(next)) {
            return refuse("keys must differ");
        }
        Object value = CollectionOps.rebuild(type, elementType, next);
        Validator<Object> v = Steps.validator(row);
        String why = v.validate(value);
        if (why != null) {
            return refuse(why);
        }
        message = null;
        root.state(UiState.INVALID, false);
        Object before = row.panel().top().get();
        if (!row.model().set(value)) {
            return false;
        }
        row.panel().record(op, path, before, row.panel().top().get());
        refresh();
        return true;
    }

    private boolean refuse(String why) {
        message = why;
        root.state(UiState.INVALID, true);
        return false;
    }

    // ---------------------------------------------------------------- ops UI

    /** The operation button of {@code element}, or null. */
    public Button buttonOf(Element element) {
        for (Button b : opButtons) {
            if (b.element() == element) {
                return b;
            }
        }
        return null;
    }

    /**
     * A button ({@code button.props-op#<op>}, icon {@code op-<op>}, text from the style's {@code op-label}) running
     * {@code op} on the item {@code index} gives; enabled by the operation's rule.
     */
    public Button opButton(String op, java.util.function.IntSupplier index) {
        for (int i = opButtons.size() - 1; i >= 0; i--) {
            if (!opButtons.get(i).element().alive()) { // dropped with a rebuilt view
                opButtons.remove(i).dispose();
                opHandles.remove(i);
            }
        }
        String icon = "op-" + op;
        Button b = new Button(controls, icon, "", Property.words(op));
        b.element().style("props-op");
        b.element().id(op);
        ui.layout().visible(b.labelElement().slot(), false);
        b.onClick(() -> run(op, index.getAsInt()));
        opButtons.add(b);
        opHandles.add(new int[] {-2});
        return b;
    }

    /**
     * Adds an operations bar for item {@code index} to {@code item}: up and down, duplicate, remove, as the style
     * offers. With {@code ops-placement: hover} the bar has the fact {@code hover=true} and shows {@code :hover} while
     * the pointer is over the item (a state of its own: no rule needs a state on an ancestor).
     */
    public Element itemOps(Element item, int index) {
        Element bar = ui.element("props", "ops");
        boolean hover = placement == PropsStyles.OpsPlacement.HOVER;
        ui.styles().facts(bar.slot(), Facts.of("hover", Boolean.toString(hover)));
        addItemButtons(bar, () -> index);
        item.add(bar);
        if (hover) {
            ui.interaction().on(item, com.truesim.ui.core.event.EventType.ENTER.bit()
                | com.truesim.ui.core.event.EventType.EXIT.bit(), e -> com.truesim.ui.controls.basic.Control.deepState(
                    bar, UiState.HOVER, e.type() == com.truesim.ui.core.event.EventType.ENTER));
        }
        return bar;
    }

    private void addItemButtons(Element bar, java.util.function.IntSupplier index) {
        if (offered("reorder")) {
            bar.add(opButton("up", index).element());
            bar.add(opButton("down", index).element());
        }
        if (offered("duplicate")) {
            bar.add(opButton("duplicate", index).element());
        }
        if (offered("remove")) {
            bar.add(opButton("remove", index).element());
        }
    }

    /**
     * Whether item operations go on each item: in views without a selection (tables of editors, foldouts, chips),
     * at each item's end or on hover ({@code ops-placement}); views that select (lists, large tables) keep them in the
     * header, acting on the selection.
     */
    public boolean opsOnItems() {
        CollectionKind.View v = view();
        return v == null || !v.selects();
    }

    /** Opens the context menu of item {@code index} on {@code target}'s context event, when the style offers one. */
    public void contextMenu(Element target, int index) {
        if (!offered("context-menu")) {
            return;
        }
        ui.interaction().on(target, com.truesim.ui.core.event.EventType.CONTEXT, event -> {
            event.consume();
            Menu m = menu();
            m.clear();
            for (String op : List.of("duplicate", "up", "down", "remove")) {
                if (offered(op.equals("up") || op.equals("down") ? "reorder" : op)) {
                    m.item(Property.words(op), () -> run(op, index)).enabled(reason(op, index) == null);
                }
            }
            for (Map.Entry<String, ContextAction> e : contextActions.entrySet()) {
                m.item(e.getKey(), () -> e.getValue().run(this, index));
            }
            if (event.pointerId() < 0) {
                m.open(target, true);
            } else {
                m.openAt(event.x(), event.y());
            }
        });
    }

    /** The context menu (one per editor, filled for the item it opens on). */
    public Menu menu() {
        if (menu == null) {
            menu = controls.menu(row.property().label());
        }
        return menu;
    }

    private void buildHeaderOps() {
        for (Button b : opButtons) {
            b.dispose();
        }
        opButtons.clear();
        opHandles.clear();
        while (ui.store().firstChild(headerOps.slot()) != com.truesim.ui.core.UiStore.NONE) {
            ui.store().element(ui.store().firstChild(headerOps.slot())).destroy();
        }
        CollectionKind.View main = view();
        ui.layout().visible(count.slot(), main == null || !main.counts());
        if (offered("add") && (main == null || !main.adds())) {
            headerOps.add(opButton("add", () -> {
                CollectionKind.View v = view();
                return v == null ? -1 : v.selected();
            }).element());
        }
        if (!opsOnItems()) {
            addItemButtons(headerOps, () -> {
                CollectionKind.View v = view();
                return v == null ? -1 : v.selected();
            });
        }
        if (offered("clear")) {
            headerOps.add(opButton("clear", () -> -1).element());
        }
    }

    /** Enables each operation button by its rule and shows why not as its tooltip. */
    private void refreshOps() {
        for (int i = 0; i < opButtons.size(); i++) {
            Button b = opButtons.get(i);
            if (!b.element().alive()) {
                continue;
            }
            String op = b.element().id();
            int index = indexOf(b);
            String why = reason(op, index);
            b.enabled(why == null);
            if (row.builder().tooltips() != null) {
                row.builder().tooltips().text(b.element(), why == null ? Property.words(op) : why);
            }
        }
    }

    private int indexOf(Button b) {
        Element parent = b.element().parent();
        if (parent == headerOps) {
            CollectionKind.View v = view();
            return v == null ? -1 : v.selected();
        }
        for (Element e = parent; e != null; e = e.parent()) {
            String id = e.id();
            if (id != null && id.startsWith("item-")) { // an item's container: collection-item, chip
                return Integer.parseInt(id.substring(5));
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- kind and style

    private String chooseKind(String styled) {
        String code = row.builder().codeEditor(row.property().name() + "#collection");
        if (code != null && row.builder().collectionKinds().containsKey(code)) {
            return code;
        }
        if (!styled.isEmpty() && row.builder().collectionKinds().containsKey(styled)) {
            return styled;
        }
        ListStyle ls = row.annotation(ListStyle.class);
        if (ls != null && row.builder().collectionKinds().containsKey(ls.value())) {
            return ls.value();
        }
        if (map) {
            return CollectionKind.MAP;
        }
        if (Set.class.isAssignableFrom(type) && (elementType.isEnum() || elementType == String.class)) {
            return CollectionKind.CHIPS;
        }
        if (elementType.isSealed() || elementType.isInterface()) {
            return CollectionKind.FOLDOUTS;
        }
        return CollectionKind.TABLE;
    }

    private void facts() {
        ui.styles().facts(root.slot(), Facts.of("property", row.property().name(), "element",
            map ? "Entry" : elementType.getSimpleName(), "type", PropertyRow.typeName(row.property()), "kind", kind));
    }

    /** The operations the annotations allow ({@code @Addable}, {@code @Removable}, {@code @Reorderable}). */
    private List<String> annotatedOps() {
        List<String> list = new ArrayList<>(OPS);
        Addable a = row.annotation(Addable.class);
        Removable r = row.annotation(Removable.class);
        Reorderable o = row.annotation(Reorderable.class);
        if (a != null && !a.value()) {
            list.remove("add");
        }
        if (r != null && !r.value()) {
            list.remove("remove");
            list.remove("clear");
        }
        if (o != null && !o.value() || map) {
            list.remove("reorder");
        }
        return List.copyOf(list);
    }

    private void setView() {
        for (View v : List.copyOf(views)) {
            if (v.main()) {
                v.view().dispose();
                v.view().element().destroy();
                views.remove(v);
            }
        }
        CollectionKind k = row.builder().collectionKinds().get(kind);
        CollectionKind.View v = k.create(this);
        views.add(new View(v, true));
        body.add(v.element());
        buildHeaderOps();
        v.structure();
        refreshOps();
    }

    /** Opens another view of {@code kind} (the compact kind's dialog); close it with {@link #close}. */
    public CollectionKind.View open(String viewKind) {
        CollectionKind.View v = row.builder().collectionKinds().get(viewKind).create(this);
        views.add(new View(v, false));
        v.structure();
        return v;
    }

    /** Drops a view opened with {@link #open}. */
    public void close(CollectionKind.View view) {
        for (View v : List.copyOf(views)) {
            if (v.view() == view) {
                views.remove(v);
                view.dispose();
                view.element().destroy();
            }
        }
    }

    private void showCount() {
        countText.setLength(0);
        countText.append(items.size()).append(items.size() == 1 ? " item" : " items");
        ui.text(count, countText);
    }

    /** Takes the collection's current value: rebuilds views when the number of items changed. */
    private void refresh() {
        Object value = row.model().get();
        seen = row.model().version();
        List<Object> next = CollectionOps.items(value);
        // the items' rows are built for their types: rebuilt when the count changed, or an item's type at an index (a
        // polymorphic list reordered, an item replaced by one of another subtype), so no row reads another type's item
        boolean structure = next.size() != items.size() || typesMoved(items, next);
        items = next;
        showCount();
        for (int i = 0; i < views.size(); i++) {
            CollectionKind.View v = views.get(i).view();
            if (structure) {
                v.structure();
            } else {
                v.update();
            }
        }
        refreshOps();
    }

    /** Whether an item at some index is of another class than the item there before. */
    private static boolean typesMoved(List<Object> before, List<Object> after) {
        for (int i = 0; i < before.size() && i < after.size(); i++) {
            Object a = before.get(i);
            Object b = after.get(i);
            if ((a == null) != (b == null) || a != null && a.getClass() != b.getClass()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void poll() {
        if (row.model().version() != seen) {
            refresh();
        }
        for (int i = 0; i < views.size(); i++) {
            views.get(i).view().poll();
        }
    }

    @Override
    public void afterLayout() {
        int h = ui.styles().handle(root.slot());
        if (h != styleHandle) {
            styleHandle = h;
            restyle();
        }
        for (int i = 0; i < opButtons.size(); i++) {
            Button b = opButtons.get(i);
            int[] seenHandle = opHandles.get(i);
            int bh = ui.styles().handle(b.element().slot());
            if (bh != seenHandle[0]) {
                seenHandle[0] = bh;
                label(b);
            }
        }
    }

    private void label(Button b) {
        String text = ui.styles().styleOf(b.element().slot()).get(PropsStyles.OP_LABEL);
        Element l = b.labelElement();
        if (!text.contentEquals(ui.text(l))) {
            ui.text(l, text);
        }
        ui.layout().visible(l.slot(), !text.isEmpty());
    }

    private void restyle() {
        ResolvedStyle s = ui.styles().styleOf(root.slot());
        String styledKind = s.isSet(PropsStyles.COLLECTION) ? s.get(PropsStyles.COLLECTION) : "";
        List<String> styledOps = s.isSet(PropsStyles.OPS) ? s.get(PropsStyles.OPS) : null;
        PropsStyles.OpsPlacement p = s.get(PropsStyles.OPS_PLACEMENT);
        int va = s.get(PropsStyles.VIRTUALIZE_ABOVE);
        confirm = s.get(PropsStyles.CONFIRM);
        if (styledKind.equals(lastKind) && Objects.equals(styledOps, lastOps) && p == placement
            && va == virtualizeAbove) {
            return; // a hover restyled the editor: nothing it reads changed
        }
        lastKind = styledKind;
        lastOps = styledOps;
        String k = chooseKind(styledKind);
        List<String> o = styledOps != null ? List.copyOf(styledOps) : annotatedOps();
        if (!k.equals(kind) || !o.equals(ops) || p != placement || va != virtualizeAbove) {
            kind = k;
            ops = o;
            placement = p;
            virtualizeAbove = va;
            facts();
            setView();
        }
    }

    @Override
    public void dispose() {
        for (View v : views) {
            v.view().dispose();
        }
        views.clear();
        for (Button b : opButtons) {
            b.dispose();
        }
    }
}
