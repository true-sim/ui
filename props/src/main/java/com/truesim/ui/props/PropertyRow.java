package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.props.PropsStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Binding;
import com.truesim.ui.core.bind.Converter;
import com.truesim.ui.core.bind.Lens;
import com.truesim.ui.props.annotations.ReadOnly;
import com.truesim.ui.style.Facts;
import com.truesim.ui.style.ResolvedStyle;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One property's row in a {@link PropertyPanel} (design 5.11): an element {@code props.row} holding a label
 * ({@code label.prop-label}) and the chosen editor, with facts for styles: {@code type} (a primitive's name,
 * {@code string}, {@code enum}, {@code list}, {@code set}, {@code map}, {@code array}, or the simple name of a record
 * or class), {@code kind} ({@code value}, {@code record}, {@code collection}), one {@code annotation} per
 * annotation, {@code property}, {@code group}, {@code model} (the owning type) and {@code label} (where the label
 * goes). It is also the editor's context: its value
 * ({@link #model()}, a lens from the panel's root), its annotations, and {@link #bind} for the editor's control.
 */
@AllowAllocation("rows are built when a panel is built or restyled")
public final class PropertyRow implements Binding.Listener {

    private final PropertyPanel<?> panel;
    private final Property<Object, Object> property;
    private final Element element;
    private final Element label;
    private final Bindable<Object> model;
    private final Lens<Object, Object> fromRoot;
    private final String path;
    private final List<Binding<?, ?>> bindings = new ArrayList<>();
    private PropertyEditor editor;
    private String editorName = "";
    private String styledEditor = "";
    private String group = "";
    private int styleHandle = -2;
    private Integer styledOrder;
    private Boolean styledVisible;
    private PropsStyles.LabelPlacement placement;
    private boolean filtered = true;
    private boolean shown = true;

    @SuppressWarnings("unchecked")
    PropertyRow(PropertyPanel<?> panel, Property<?, ?> property) {
        this.panel = panel;
        this.property = (Property<Object, Object>) property;
        Ui ui = panel.ui();
        this.fromRoot = ((Lens<Object, Object>) (Lens<?, ?>) panel.base()).then(this.property);
        this.model = Bindable.through(panel.top(), fromRoot);
        this.path = panel.path().isEmpty() ? property.name() : panel.path() + "." + property.name();
        element = ui.element("props", "row");
        element.id(property.name());
        label = ui.label("prop-label", property.label());
        element.add(label);
        element.semantics(com.truesim.ui.core.Role.GROUP, property.label());
        if (readOnly()) {
            element.state(UiState.READ_ONLY, true);
        }
    }

    /** The panel. */
    public PropertyPanel<?> panel() {
        return panel;
    }

    /** The builder's configuration. */
    public PropertyBuilder<?> builder() {
        return panel.builder();
    }

    /** The controls. */
    public Controls controls() {
        return panel.controls();
    }

    /** The UI. */
    public Ui ui() {
        return panel.ui();
    }

    /** The property. */
    public Property<Object, Object> property() {
        return property;
    }

    /** The annotation of {@code type} on the property, or null. */
    public <A extends Annotation> A annotation(Class<A> type) {
        return property.annotation(type);
    }

    /** The row element ({@code props.row}). */
    public Element element() {
        return element;
    }

    /** The label element. */
    public Element label() {
        return label;
    }

    /** The value: the property of the panel's value, through the lens chain from the root. */
    public Bindable<Object> model() {
        return model;
    }

    /** The lens from the panel's root to this value. */
    public Lens<Object, Object> fromRoot() {
        return fromRoot;
    }

    /** The path from the root ({@code waypoints[2].position.x}). */
    public String path() {
        return path;
    }

    /** The editor, or null before the pipeline chose one. */
    public PropertyEditor editor() {
        return editor;
    }

    /** The editor's name. */
    public String editorName() {
        return editorName;
    }

    /** The group. */
    public String group() {
        return group;
    }

    /** The order the style gives ({@code order}), or null when no rule sets it. */
    public Integer styledOrder() {
        return styledOrder;
    }

    /** Whether the property can be edited (writable and not {@code @ReadOnly}). */
    public boolean readOnly() {
        return !property.writable() || property.annotation(ReadOnly.class) != null;
    }

    /** Whether the row shows. */
    public boolean shown() {
        return shown;
    }

    /**
     * Binds {@code control} to this row's value through {@code converter} by the pipeline's BIND step; {@code invalid}
     * shows {@code :invalid} while the control holds a value that was not written.
     */
    public <C> Binding<Object, C> bind(Bindable<C> control, Converter<Object, C> converter, Element invalid) {
        return builder().step(Steps.BIND).bind(this, control, converter, invalid);
    }

    /** The bindings of the row's editor (its control's ends). */
    public List<Binding<?, ?>> bindings() {
        return bindings;
    }

    /** Keeps {@code binding} polled by the UI until the row's editor is rebuilt. */
    public void add(Binding<?, ?> binding) {
        bindings.add(binding);
        ui().bindings().add(binding);
    }

    @Override
    public void edited(Binding<?, ?> binding, Object before, Object after) {
        panel.edited(this, "set", path, before);
    }

    // ---------------------------------------------------------------- pipeline

    void group(String value) {
        group = value;
    }

    /** Sets the facts styles select by (type, annotations, property, group, model, label). */
    void facts() {
        List<String> f = new ArrayList<>();
        f.add("type");
        f.add(typeName(property));
        for (Annotation a : property.annotations().list()) {
            f.add("annotation");
            f.add(a.annotationType().getSimpleName());
            @SuppressWarnings({"unchecked", "rawtypes"})
            AnnotationHandler<Annotation> h = (AnnotationHandler) builder().handlers().get(a.annotationType());
            if (h != null) {
                h.facts(a, property, f);
            }
        }
        f.add("kind");
        f.add(kindName(property));
        f.add("property");
        f.add(property.name());
        f.add("group");
        f.add(group.isEmpty() ? "none" : group);
        f.add("model");
        f.add(panel.model().type().getSimpleName());
        f.add("label");
        f.add(placement == null ? "start" : placement.name().toLowerCase(java.util.Locale.ROOT));
        ui().styles().facts(element.slot(), Facts.of(f.toArray(new String[0])));
    }

    /** The {@code kind} fact of {@code p}: {@code collection}, {@code record} (a nested panel) or {@code value}. */
    static String kindName(Property<?, ?> p) {
        Class<?> t = p.type();
        if (t.isArray() || java.util.Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t)) {
            return "collection";
        }
        if (t.isPrimitive() || t.isEnum() || t.getName().startsWith("java.") || Editors.unboxed(t) != null) {
            return "value";
        }
        return "record";
    }

    /** The {@code type} fact of {@code p}. */
    static String typeName(Property<?, ?> p) {
        Class<?> t = p.type();
        if (t.isPrimitive()) {
            return t.getName();
        }
        if (t == String.class) {
            return "string";
        }
        if (t.isEnum()) {
            return "enum";
        }
        if (t.isArray()) {
            return "array";
        }
        if (List.class.isAssignableFrom(t)) {
            return "list";
        }
        if (Set.class.isAssignableFrom(t)) {
            return "set";
        }
        if (Map.class.isAssignableFrom(t)) {
            return "map";
        }
        Class<?> unboxed = Editors.unboxed(t);
        return unboxed != null ? unboxed.getName() : t.getSimpleName();
    }

    /** Chooses and builds the editor if the choice changed; returns whether it did. */
    boolean chooseEditor(String styled) {
        String name = builder().step(Steps.EDITOR).choose(this, styled);
        if (editor != null && name.equals(editorName)) {
            return false;
        }
        disposeEditor();
        editorName = name;
        editor = builder().editors().get(name).create(this);
        element.add(editor.element());
        if (readOnly()) {
            editor.element().state(UiState.DISABLED, true);
        }
        return true;
    }

    void disposeEditor() {
        for (Binding<?, ?> b : bindings) {
            ui().bindings().remove(b);
        }
        bindings.clear();
        if (editor != null) {
            editor.dispose();
            editor.element().destroy();
            editor = null;
        }
    }

    /** Applies the filter's verdict; returns whether visibility changed. */
    boolean filter(boolean visible) {
        filtered = visible;
        return applyVisibility();
    }

    private boolean applyVisibility() {
        boolean code = builder().codeVisible(property.name()) != null;
        boolean v = code ? builder().codeVisible(property.name())
            : styledVisible != null ? styledVisible : filtered;
        if (v == shown) {
            return false;
        }
        shown = v;
        ui().layout().visible(element.slot(), v);
        return true;
    }

    /**
     * Reads the row's style if it changed: its editor, visibility, order and label placement. Returns 1 when the
     * order changed (the panel re-arranges), 0 otherwise.
     */
    int restyle() {
        int h = ui().styles().handle(element.slot());
        if (h == styleHandle) {
            return 0;
        }
        styleHandle = h;
        ResolvedStyle s = ui().styles().styleOf(element.slot());
        String styled = s.isSet(PropsStyles.EDITOR) ? s.get(PropsStyles.EDITOR) : "";
        if (!styled.equals(styledEditor)) { // a hover restyles rows too: choose only when the rule changed
            styledEditor = styled;
            chooseEditor(styled);
        }
        styledVisible = s.isSet(PropsStyles.VISIBLE) ? s.get(PropsStyles.VISIBLE) : null;
        applyVisibility();
        PropsStyles.LabelPlacement lp = s.get(PropsStyles.LABEL_PLACEMENT);
        if (lp != placement) {
            placement = lp;
            ui().layout().visible(label.slot(), lp != PropsStyles.LabelPlacement.NONE);
            facts();
        }
        Integer order = s.isSet(PropsStyles.ORDER) ? s.get(PropsStyles.ORDER) : null;
        if (!java.util.Objects.equals(order, styledOrder)) {
            styledOrder = order;
            return 1;
        }
        return 0;
    }

    void poll() {
        if (editor != null) {
            editor.poll();
        }
    }

    void afterLayout() {
        if (editor != null) {
            editor.afterLayout();
        }
    }

    void dispose() {
        disposeEditor();
    }

    @Override
    public String toString() {
        return path + " (" + editorName + ")";
    }
}
