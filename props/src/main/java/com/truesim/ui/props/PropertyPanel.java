package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Collapsible;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.props.PropsStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Lens;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.props.annotations.Foldout;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A property panel built by a {@link PropertyBuilder} (design 5.11): a {@code props.panel} element (facts
 * {@code model=<Type>} and {@code layout=<name>}) whose rows edit a value held in a versioned holder, live: an edit
 * writes a new root through the row's lens chain at once, and a change made elsewhere (undo, a script, the network)
 * shows in the frame it happened. The pipeline's choices that styles make (layout, editors, visibility, order, label
 * placement) are read again whenever the theme or a rule's inputs change, after styles resolve, so a theme switch
 * rebuilds what changed in the same frame. A frame with no change allocates nothing.
 *
 * @param <T> the value type
 */
@AllowAllocation("panels are built when a UI is built or restyled")
public final class PropertyPanel<T> implements Ui.FrameListener {

    private final PropertyBuilder<T> builder;
    private final Controls controls;
    private final Ui ui;
    private final PropertyModel<T> model;
    private final Bindable<Object> top;
    private final Lens<Object, T> base;
    private final String path;
    private final PropertyPanel<?> parent;
    private final Element root;
    private final List<PropertyRow> rows = new ArrayList<>();
    private final List<PropertyRow> ordered = new ArrayList<>();
    private final Map<String, Collapsible> foldouts = new HashMap<>();
    private final List<Element> containers = new ArrayList<>();
    private final List<Edit.Listener> listeners = new ArrayList<>();
    private final List<PropertyPanel<?>> children = new ArrayList<>();
    private String layoutName = "";
    private String styledLayout = "";
    private int styleHandle = -2;
    private long seen = -1;
    private Edit pending;
    private boolean disposed;

    @SuppressWarnings("unchecked")
    PropertyPanel(PropertyBuilder<T> builder, Controls controls, Bindable<?> top, Lens<Object, T> base, String path,
        PropertyPanel<?> parent) {
        this.builder = builder;
        this.controls = controls;
        this.ui = controls.ui();
        this.model = builder.model();
        this.top = (Bindable<Object>) top;
        this.base = base;
        this.path = path;
        this.parent = parent;
        root = ui.element("props", "panel");
        root.semantics(Role.GROUP, model.label());
        for (Property<T, ?> p : model.properties()) {
            rows.add(new PropertyRow(this, p));
        }
        for (PropertyRow r : rows) {
            r.group(builder.step(Steps.GROUP).group(r));
            r.facts();
            r.chooseEditor("");
        }
        layoutName = chooseLayout("");
        facts();
        arrange();
        filter();
        if (parent == null) {
            ui.addFrameListener(this); // nested panels run in their outermost panel's frame
        } else {
            parent.children.add(this);
        }
    }

    // ---------------------------------------------------------------- what it is

    /** The panel's element ({@code props.panel}): add it to a parent to show it. */
    public Element element() {
        return root;
    }

    /** The model the panel shows. */
    public PropertyModel<T> model() {
        return model;
    }

    /** The rows in declaration order. */
    public List<PropertyRow> rows() {
        return rows;
    }

    /** The row of {@code property}, or null. */
    public PropertyRow row(String property) {
        for (PropertyRow r : rows) {
            if (r.property().name().equals(property)) {
                return r;
            }
        }
        return null;
    }

    /** The rows in their shown order. */
    public List<PropertyRow> ordered() {
        return ordered;
    }

    /** The chosen layout's name. */
    public String layout() {
        return layoutName;
    }

    /** The value shown (the root's, through the lens chain), or null. */
    public T value() {
        Object r = top.get();
        return r == null ? null : base.get(r);
    }

    /** The builder. */
    public PropertyBuilder<T> builder() {
        return builder;
    }

    /** The controls. */
    public Controls controls() {
        return controls;
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /** The root holder (the outermost panel's value). */
    public Bindable<Object> top() {
        return top;
    }

    /** The lens from the root to this panel's value. */
    public Lens<Object, T> base() {
        return base;
    }

    /** The path from the root ({@code ""} for the outermost panel). */
    public String path() {
        return path;
    }

    /** The panels nested in this one (records, collection items). */
    List<PropertyPanel<?>> children() {
        return children;
    }

    /** The section foldouts the layout made, by group name (the inspector layout). */
    public Map<String, Collapsible> foldouts() {
        return foldouts;
    }

    /** Tells {@code listener} of each edit (outermost panels; nested ones report to theirs); returns this. */
    @AllowAllocation("listeners are added when a UI is built")
    public PropertyPanel<T> onEdit(Edit.Listener listener) {
        outermost().listeners.add(listener);
        return this;
    }

    @SuppressWarnings("unchecked")
    Property<Object, ?> propertyOf(String name) {
        return (Property<Object, ?>) model.property(name);
    }

    private PropertyPanel<?> outermost() {
        PropertyPanel<?> p = this;
        while (p.parent != null) {
            p = p.parent;
        }
        return p;
    }

    // ---------------------------------------------------------------- pipeline

    private String chooseLayout(String styled) {
        String code = builder.codeLayout();
        if (code != null && builder.layouts().containsKey(code)) {
            return code;
        }
        if (!styled.isEmpty() && builder.layouts().containsKey(styled)) {
            return styled;
        }
        return builder.defaultLayout();
    }

    private void facts() {
        ui.styles().facts(root.slot(), Facts.of("model", model.type().getSimpleName(), "layout", layoutName));
    }

    /** Orders and groups the rows and lays them out with the layout step (again after a style change). */
    private void arrange() {
        for (PropertyRow r : rows) {
            r.element().detach();
        }
        for (Collapsible c : foldouts.values()) {
            c.element().detach();
        }
        for (Element e : containers) {
            if (e.alive()) {
                e.destroy(); // a container's labels go with it
            }
        }
        containers.clear();
        ordered.clear();
        ordered.addAll(rows);
        builder.step(Steps.ORDER).order(ordered);
        Map<String, List<PropertyRow>> groups = new LinkedHashMap<>();
        for (PropertyRow r : ordered) {
            groups.computeIfAbsent(r.group(), g -> new ArrayList<>()).add(r);
        }
        List<LayoutStrategy.Section> sections = new ArrayList<>();
        for (Map.Entry<String, List<PropertyRow>> e : groups.entrySet()) {
            boolean foldout = false;
            boolean expanded = true;
            for (PropertyRow r : e.getValue()) {
                Foldout f = r.annotation(Foldout.class);
                if (f != null && !r.editorName().equals("record")) {
                    foldout = true;
                    expanded = f.expanded();
                }
            }
            sections.add(new LayoutStrategy.Section(e.getKey(), List.copyOf(e.getValue()), foldout, expanded));
        }
        builder.step(Steps.LAYOUT).arrange(new LayoutStrategy.Context(this, layoutName, sections));
    }

    /** Keeps {@code container} until the next arrangement (layouts' own elements). */
    void container(Element container) {
        containers.add(container);
    }

    /** The section foldout of {@code group}, made once and kept across arrangements. */
    Collapsible foldout(String group, boolean expanded) {
        Collapsible c = foldouts.get(group);
        if (c == null) {
            Element body = ui.element("props", "section-body");
            c = controls.collapsible(group.isEmpty() ? model.label() : group, body,
                controls.clock().booleanValue(expanded));
            c.element().id(group.isEmpty() ? "general" : group);
            foldouts.put(group, c);
        }
        return c;
    }

    private void filter() {
        Object v = value();
        for (PropertyRow r : rows) {
            r.filter(builder.step(Steps.FILTER).visible(r, v));
        }
    }

    // ---------------------------------------------------------------- edits

    /** A row wrote {@code before}'s replacement into the root: an edit at {@code editPath}. */
    void edited(PropertyRow row, String operation, String editPath, Object before) {
        Object after = top.get();
        Object rootBefore = row.fromRoot().with(after, before);
        record(operation, editPath, rootBefore, after);
    }

    /** Records an edit of the root from {@code before} to {@code after} (collections call it too). */
    void record(String operation, String editPath, Object before, Object after) {
        PropertyPanel<?> o = outermost();
        if (o != this) {
            o.record(operation, editPath, before, after);
            return;
        }
        if (pending != null && pending.path().equals(editPath) && pending.operation().equals(operation)
            && gesture()) {
            pending = new Edit(editPath, operation, pending.before(), after, top);
            return;
        }
        flush();
        pending = new Edit(editPath, operation, before, after, top);
        if (!gesture()) {
            flush();
        }
    }

    private void flush() {
        if (pending != null) {
            Edit e = pending;
            pending = null;
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).edited(e);
            }
        }
    }

    /** Whether a pointer is held: a drag or a slider's continuous change is one gesture, one edit. */
    private boolean gesture() {
        UiInput input = ui.input();
        if (input == null) {
            return false;
        }
        for (int i = 0; i < input.pointerCount(); i++) {
            Pointer p = input.pointerAt(i);
            if (p.buttons() != 0) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the frame

    @Override
    public void frame(long nanos) {
        if (disposed) {
            return;
        }
        long v = top.version();
        if (v != seen) {
            seen = v;
            filter();
        }
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).poll();
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).frame(nanos);
        }
        if (pending != null && !gesture()) {
            flush();
        }
    }

    @Override
    public void afterLayout() {
        if (disposed) {
            return;
        }
        boolean rearrange = false;
        int h = ui.styles().handle(root.slot());
        if (h != styleHandle) {
            styleHandle = h;
            ResolvedStyle s = ui.styles().styleOf(root.slot());
            String styled = s.isSet(PropsStyles.PROPS_LAYOUT) ? s.get(PropsStyles.PROPS_LAYOUT) : "";
            String name = styled.equals(styledLayout) ? layoutName : chooseLayout(styled);
            styledLayout = styled;
            if (!name.equals(layoutName)) {
                layoutName = name;
                facts();
                rearrange = true;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).restyle() != 0) {
                rearrange = true;
            }
            rows.get(i).afterLayout();
        }
        if (rearrange) {
            arrange();
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).afterLayout();
        }
    }

    /** Removes the panel: its bindings, editors, frame listener and elements. */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        flush();
        if (parent == null) {
            ui.removeFrameListener(this);
        } else {
            parent.children.remove(this);
        }
        for (PropertyPanel<?> c : List.copyOf(children)) {
            c.dispose();
        }
        for (PropertyRow r : rows) {
            r.dispose();
        }
        for (Collapsible c : foldouts.values()) {
            c.dispose();
        }
        root.destroy();
    }

    /** Whether {@link #dispose} was called. */
    public boolean disposed() {
        return disposed;
    }

    @Override
    public String toString() {
        return "PropertyPanel " + model.type().getSimpleName() + " " + layoutName + " " + rows;
    }
}
