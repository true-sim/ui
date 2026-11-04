package com.truesim.ui.controls.data;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Records by their metadata (design 4.3, the registry's second step): a record value shows its components in order,
 * each as a {@code record-field} (id: the component's name) holding a {@code label.record-name} with the name and the
 * component's view, all in a {@code record-view} panel (id: the record's simple class name). Components show through
 * the registry's renderer of their declared type (records nest), primitives as numbers or a {@code value-check}
 * without boxing. Accessors are found once per class by reflection and turned into direct calls
 * ({@link LambdaMetafactory}), so binding allocates nothing; where the record's class cannot be looked into that way
 * (a named module that does not open it), reflective calls are used, which box. Each record class is a kind of its
 * own, so mixed records recycle their views per class. The cell is named by its first component's text.
 */
final class RecordRenderer implements ValueRenderer<Record> {

    private static final int OBJECT = 0;
    private static final int INT = 1;
    private static final int LONG = 2;
    private static final int DOUBLE = 3;
    private static final int BOOLEAN = 4;
    private static final int CHAR = 5;

    /** A record class's components and how to read them. */
    private static final class Shape {
        final int kind;
        final String name;
        final String[] names;
        final int[] types;
        final Object[] readers;
        final ValueRenderer<Object>[] renderers;
        final int[] subKinds;

        @SuppressWarnings("unchecked")
        Shape(int kind, String name, int n) {
            this.kind = kind;
            this.name = name;
            names = new String[n];
            types = new int[n];
            readers = new Object[n];
            renderers = (ValueRenderer<Object>[]) new ValueRenderer<?>[n];
            subKinds = new int[n];
        }
    }

    private final ValueRenderers registry;
    private final Map<Class<?>, Shape> shapes = new HashMap<>();
    private final List<Shape> byKind = new ArrayList<>();
    private final StringBuilder scratch = new StringBuilder();

    RecordRenderer(ValueRenderers registry) {
        this.registry = registry;
    }

    @Override
    public Object viewType() {
        return "record-view";
    }

    @Override
    public int kind(Record value) {
        return value == null ? 0 : shape(value.getClass()).kind;
    }

    /** The shape of {@code type}, read once. */
    @AllowAllocation("once per record class")
    private Shape shape(Class<?> type) {
        Shape s = shapes.get(type);
        if (s == null) {
            s = read(type);
        }
        return s;
    }

    @AllowAllocation("once per record class")
    @SuppressWarnings("unchecked")
    private Shape read(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        int n = components == null ? 0 : components.length;
        Shape s = new Shape(byKind.size(), type.getSimpleName(), n);
        shapes.put(type, s); // before the components: a record holding its own type ends here
        byKind.add(s);
        for (int i = 0; i < n; i++) {
            RecordComponent c = components[i];
            Class<?> t = c.getType();
            s.names[i] = c.getName();
            int kind = t == int.class || t == short.class || t == byte.class ? INT : t == long.class ? LONG
                : t == double.class || t == float.class ? DOUBLE : t == boolean.class ? BOOLEAN
                : t == char.class ? CHAR : OBJECT;
            s.types[i] = kind;
            s.readers[i] = reader(type, c.getAccessor(), t, kind);
            if (kind == OBJECT) {
                ValueRenderer<?> r = t.isRecord() ? this : registry.find(t);
                s.renderers[i] = (ValueRenderer<Object>) r;
                s.subKinds[i] = r == this ? shape(t).kind : 0;
            }
        }
        return s;
    }

    /** A direct call reading {@code accessor} (a functional interface of the component's kind), or the method. */
    @AllowAllocation("once per record component")
    private static Object reader(Class<?> type, Method accessor, Class<?> t, int kind) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            MethodHandle handle = lookup.unreflect(accessor);
            Class<?> face;
            String method;
            Class<?> result;
            switch (kind) {
                case INT, CHAR -> {
                    face = ToIntFunction.class;
                    method = "applyAsInt";
                    result = int.class;
                }
                case LONG -> {
                    face = ToLongFunction.class;
                    method = "applyAsLong";
                    result = long.class;
                }
                case DOUBLE -> {
                    face = ToDoubleFunction.class;
                    method = "applyAsDouble";
                    result = double.class;
                }
                case BOOLEAN -> {
                    face = Predicate.class;
                    method = "test";
                    result = boolean.class;
                }
                default -> {
                    face = Function.class;
                    method = "apply";
                    result = Object.class;
                }
            }
            CallSite site = LambdaMetafactory.metafactory(lookup, method, MethodType.methodType(face),
                MethodType.methodType(result, Object.class), handle,
                MethodType.methodType(kind == OBJECT ? t : result, type));
            return site.getTarget().invoke();
        } catch (Throwable e) {
            accessor.setAccessible(true); // a module that does not open the record: reflective calls
            return accessor;
        }
    }

    @Override
    @AllowAllocation("views are made while the visible window fills")
    public Element create(int kind, ViewContext context) {
        Shape s = byKind.get(kind);
        Ui ui = context.ui();
        Element view = ui.element("record-view", null);
        view.id(s.name);
        for (int i = 0; i < s.names.length; i++) {
            Element field = ui.element("record-field", null);
            field.id(s.names[i]);
            field.add(ui.label("record-name", s.names[i]));
            Element value = switch (s.types[i]) {
                case BOOLEAN -> {
                    Element check = ui.element("value-check", null);
                    check.semantics(Role.CHECKBOX, "false");
                    yield check;
                }
                case INT, LONG, DOUBLE -> Renderers.textView(context, "number");
                case CHAR -> Renderers.textView(context, "text");
                default -> s.renderers[i].create(s.subKinds[i], context);
            };
            field.add(value);
            view.add(field);
        }
        return view;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void bind(Element view, Record value, CellState state) {
        if (value == null) {
            return;
        }
        Shape s = shape(value.getClass());
        UiStore store = view.store();
        Ui ui = state.ui();
        CharSequence first = null;
        int field = store.firstChild(view.slot());
        for (int i = 0; i < s.names.length && field != UiStore.NONE; i++, field = store.nextSibling(field)) {
            Element v = store.element(store.lastChild(field));
            Object reader = s.readers[i];
            switch (s.types[i]) {
                case INT, CHAR -> {
                    int x = reader instanceof ToIntFunction<?> f ? ((ToIntFunction<Object>) f).applyAsInt(value)
                        : ((Number) invoke(reader, value)).intValue();
                    scratch.setLength(0);
                    if (s.types[i] == CHAR) {
                        scratch.append((char) x);
                    } else {
                        scratch.append(x);
                    }
                    ui.text(v, scratch);
                }
                case LONG -> {
                    long x = reader instanceof ToLongFunction<?> f ? ((ToLongFunction<Object>) f).applyAsLong(value)
                        : ((Number) invoke(reader, value)).longValue();
                    scratch.setLength(0);
                    ui.text(v, scratch.append(x));
                }
                case DOUBLE -> {
                    double x = reader instanceof ToDoubleFunction<?> f
                        ? ((ToDoubleFunction<Object>) f).applyAsDouble(value)
                        : ((Number) invoke(reader, value)).doubleValue();
                    scratch.setLength(0);
                    ui.text(v, Format.append(scratch, x, 3));
                }
                case BOOLEAN -> {
                    boolean on = reader instanceof Predicate<?> f ? ((Predicate<Object>) f).test(value)
                        : Boolean.TRUE.equals(invoke(reader, value));
                    v.state(UiState.CHECKED, on);
                    v.semantics(Role.CHECKBOX, on ? "true" : "false");
                }
                default -> {
                    Object x = reader instanceof Function<?, ?> f ? ((Function<Object, Object>) f).apply(value)
                        : invoke(reader, value);
                    s.renderers[i].bind(v, x, state);
                }
            }
            if (first == null && s.types[i] != BOOLEAN) {
                first = s.types[i] == OBJECT ? state.name() : ui.text(v);
            }
        }
        state.name(first == null ? s.name : first);
    }

    @AllowAllocation("a record the renderer cannot look into: reflective calls box")
    private static Object invoke(Object accessor, Object target) {
        try {
            return ((Method) accessor).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + accessor, e);
        }
    }
}
