package com.truesim.ui.props;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.VersionedValue;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.windows.Tooltips;
import com.truesim.ui.core.bind.Bindable;
import com.truesim.ui.core.bind.Lens;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds property panels from metadata through a pipeline of replaceable steps (design 5.11, UI17): filter, order,
 * group, editor choice, layout, bind ({@link Steps}). Everything presentational is the theme's: the layout, each
 * row's editor, label placement, visibility and order, the collection editor kinds and their operations are chosen by
 * style rules over the rows' facts; code adds behaviour (editors, layouts, collection kinds, annotation handlers by
 * name) and may pin a choice for one panel. Priority, highest first: this builder's code overrides, style rules,
 * annotations, the defaults per type.
 *
 * <pre>{@code
 * PropertyPanel<Material> panel = PropertyBuilder.forType(Material.class)
 *     .replace(Steps.ORDER, rows -> rows.sort(byName))
 *     .editor("roughness", "number")            // this panel only: beats the theme
 *     .build(controls, material);               // a VersionedValue<Material>, edited live
 * parent.add(panel.element());
 * }</pre>
 *
 * @param <T> the value type
 */
@AllowAllocation("panels are built when a UI is built")
public final class PropertyBuilder<T> {

    private final PropertyModel<T> model;
    private PropertyModels models;
    private final Map<Steps.Key<?>, Object> steps;
    private final Editors editors;
    private final Map<String, LayoutStrategy> layouts;
    private final Map<String, CollectionKind> kinds;
    private final Map<Class<? extends Annotation>, AnnotationHandler<?>> handlers;
    private final Map<String, String> codeEditors;
    private final Map<String, Boolean> codeVisible;
    private String codeLayout;
    private String defaultLayout = Layouts.FORM;
    private Tooltips tooltips;

    private PropertyBuilder(PropertyModel<T> model, PropertyModels models) {
        this.model = Objects.requireNonNull(model, "model");
        this.models = models;
        steps = new HashMap<>();
        editors = Editors.standard();
        layouts = Layouts.standard();
        kinds = CollectionKind.standard();
        handlers = new LinkedHashMap<>();
        codeEditors = new HashMap<>();
        codeVisible = new HashMap<>();
    }

    /** A nested builder sharing {@code parent}'s steps, registries and overrides. */
    private PropertyBuilder(PropertyModel<T> model, PropertyBuilder<?> parent) {
        this.model = model;
        this.models = parent.models;
        steps = parent.steps;
        editors = parent.editors;
        layouts = parent.layouts;
        kinds = parent.kinds;
        handlers = parent.handlers;
        codeEditors = parent.codeEditors;
        codeVisible = parent.codeVisible;
        codeLayout = null; // a pinned layout is the outer panel's
        defaultLayout = parent.defaultLayout;
        tooltips = parent.tooltips;
    }

    /** A builder of {@code type}'s panels, its model from the shared {@link PropertyModels}. */
    public static <T> PropertyBuilder<T> forType(Class<T> type) {
        return new PropertyBuilder<>(PropertyModels.shared().of(type), PropertyModels.shared());
    }

    /** A builder of {@code model}'s panels (nested types' models from the shared {@link PropertyModels}). */
    public static <T> PropertyBuilder<T> of(PropertyModel<T> model) {
        return new PropertyBuilder<>(model, PropertyModels.shared());
    }

    /** Where nested types' models come from; returns this. */
    public PropertyBuilder<T> models(PropertyModels value) {
        models = Objects.requireNonNull(value);
        return this;
    }

    /** The models of nested types. */
    public PropertyModels models() {
        return models;
    }

    /** The model. */
    public PropertyModel<T> model() {
        return model;
    }

    // ---------------------------------------------------------------- steps

    /** Replaces the step {@code key} with {@code step}; returns this. */
    public <S> PropertyBuilder<T> replace(Steps.Key<S> key, S step) {
        steps.put(key, Objects.requireNonNull(step));
        return this;
    }

    /** Removes the step {@code key}: its neutral form runs (all rows shown, declaration order, no groups...). */
    public PropertyBuilder<T> remove(Steps.Key<?> key) {
        steps.put(key, key.neutral());
        return this;
    }

    /** The step {@code key} as configured. */
    @SuppressWarnings("unchecked")
    public <S> S step(Steps.Key<S> key) {
        Object s = steps.get(key);
        return s != null ? (S) s : key.standard();
    }

    // ---------------------------------------------------------------- registries

    /** The editor registry. */
    public Editors editors() {
        return editors;
    }

    /** Registers editor {@code name}; returns this. */
    public PropertyBuilder<T> editor(String name, EditorFactory factory) {
        editors.register(name, factory);
        return this;
    }

    /** The layout strategies by name. */
    public Map<String, LayoutStrategy> layouts() {
        return layouts;
    }

    /** Registers layout strategy {@code name} (styles select it with {@code props-layout}); returns this. */
    public PropertyBuilder<T> layout(String name, LayoutStrategy strategy) {
        layouts.put(name, strategy);
        return this;
    }

    /** The collection editor kinds by name. */
    public Map<String, CollectionKind> collectionKinds() {
        return kinds;
    }

    /** Registers collection editor kind {@code name} (styles select it with {@code collection}); returns this. */
    public PropertyBuilder<T> collectionKind(String name, CollectionKind kind) {
        kinds.put(name, kind);
        return this;
    }

    /** The annotation handlers by annotation type. */
    public Map<Class<? extends Annotation>, AnnotationHandler<?>> handlers() {
        return handlers;
    }

    /** Registers {@code handler} for its annotation; returns this. */
    public PropertyBuilder<T> handler(AnnotationHandler<?> handler) {
        handlers.put(handler.type(), handler);
        return this;
    }

    /** Shows row and operation tooltips ({@code @Tooltip}, why an operation is disabled) through these; returns this. */
    public PropertyBuilder<T> tooltips(Tooltips value) {
        tooltips = value;
        return this;
    }

    /** The tooltips, or null. */
    public Tooltips tooltips() {
        return tooltips;
    }

    // ---------------------------------------------------------------- code overrides (beat styles)

    /** Edits {@code property} (at any depth) with editor {@code name}, whatever styles say; returns this. */
    public PropertyBuilder<T> editor(String property, String name) {
        codeEditors.put(property, name);
        return this;
    }

    /** Edits the collection {@code property} (at any depth) with collection kind {@code kind}; returns this. */
    public PropertyBuilder<T> collection(String property, String kind) {
        codeEditors.put(property + "#collection", kind);
        return this;
    }

    /** Shows or hides {@code property} (at any depth), whatever styles and annotations say; returns this. */
    public PropertyBuilder<T> visible(String property, boolean on) {
        codeVisible.put(property, on);
        return this;
    }

    /** Lays this panel out with strategy {@code name}, whatever styles say; returns this. */
    public PropertyBuilder<T> layout(String name) {
        codeLayout = name;
        return this;
    }

    /** The layout used when neither code nor styles choose one (default: form); returns this. */
    public PropertyBuilder<T> defaultLayout(String name) {
        defaultLayout = Objects.requireNonNull(name);
        return this;
    }

    String codeEditor(String property) {
        return codeEditors.get(property);
    }

    Boolean codeVisible(String property) {
        return codeVisible.get(property);
    }

    String codeLayout() {
        return codeLayout;
    }

    String defaultLayout() {
        return defaultLayout;
    }

    // ---------------------------------------------------------------- building

    /** A panel editing {@code holder}'s value live. */
    public PropertyPanel<T> build(Controls controls, VersionedValue<T> holder) {
        return build(controls, Bindable.of(holder));
    }

    /** A panel editing {@code root}'s value live. */
    public PropertyPanel<T> build(Controls controls, Bindable<T> root) {
        @SuppressWarnings("unchecked")
        Lens<Object, T> identity = (Lens<Object, T>) (Lens<?, ?>) Lens.<Object>identity();
        return new PropertyPanel<>(this, controls, root, identity, "", null);
    }

    /** A builder of the nested type {@code type} sharing this builder's configuration. */
    @SuppressWarnings("unchecked")
    <U> PropertyBuilder<U> nested(Class<U> type) {
        return new PropertyBuilder<>(models.of(type), this);
    }

    /** A builder of {@code nestedModel} sharing this builder's configuration. */
    <U> PropertyBuilder<U> nested(PropertyModel<U> nestedModel) {
        return new PropertyBuilder<>(nestedModel, this);
    }

    /** A panel of the value of {@code row} (a nested record), inside {@code row}'s panel. */
    @SuppressWarnings("unchecked")
    PropertyPanel<T> buildNested(PropertyRow row) {
        return new PropertyPanel<>(this, row.controls(), row.panel().top(), (Lens<Object, T>) (Lens<?, ?>)
            row.fromRoot(), row.path(), row.panel());
    }

    /** A panel of the value at {@code lens} from {@code parent}'s root, named {@code path}, laid out as {@code layout}. */
    @SuppressWarnings("unchecked")
    PropertyPanel<T> buildAt(PropertyPanel<?> parent, Lens<Object, ?> lens, String path, String layout) {
        PropertyBuilder<T> b = this;
        b.codeLayout = layout;
        return new PropertyPanel<>(b, parent.controls(), parent.top(), (Lens<Object, T>) lens, path, parent);
    }
}
