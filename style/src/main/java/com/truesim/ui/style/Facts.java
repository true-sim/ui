package com.truesim.ui.style;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An element's attribute facts, what {@code [name=value]} selectors test: for a generated property row
 * {@code type=float, annotation=Range, property=roughness, group=Surface}. Set when the element is built, so testing
 * them costs nothing per frame. A name may appear more than once ({@code annotation=Range, annotation=Tooltip}).
 * Value equality: elements with equal facts share resolved styles.
 */
@AllowAllocation("facts are made when an element is built")
public record Facts(List<Selector.Fact> facts) {

    /** No facts. */
    public static final Facts NONE = new Facts(List.of());

    public Facts {
        facts = List.copyOf(facts);
        for (Selector.Fact f : facts) {
            Objects.requireNonNull(f.value(), "a fact has a value");
        }
    }

    /** Facts from name, value pairs: {@code Facts.of("type", "Transform", "group", "Lighting")}. */
    public static Facts of(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("facts come in name, value pairs");
        }
        List<Selector.Fact> list = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            list.add(new Selector.Fact(namesAndValues[i], namesAndValues[i + 1]));
        }
        return new Facts(list);
    }

    /** Whether a fact matches {@code test}: same name, and same value unless the test has none. */
    public boolean matches(Selector.Fact test) {
        for (Selector.Fact f : facts) {
            if (f.name().equals(test.name()) && (test.value() == null || test.value().equals(f.value()))) {
                return true;
            }
        }
        return false;
    }
}
