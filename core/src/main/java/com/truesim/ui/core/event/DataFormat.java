package com.truesim.ui.core.event;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A typed key for a drag session's payload (design 4.7): an inventory item, a list row, a file, a scene node. A drop
 * target asks the session whether it {@linkplain DragSession#has has} a format it takes. Declared once as a constant;
 * identity equality.
 *
 * <pre>{@code
 * public static final DataFormat<Item> ITEM = DataFormat.of("game.item", Item.class);
 * }</pre>
 *
 * @param <T> the payload type
 */
public final class DataFormat<T> {

    /** Plain text: what text-taking elements accept. */
    public static final DataFormat<String> TEXT = of("text", String.class);

    private final String name;
    private final Class<T> type;

    private DataFormat(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    /** A format named {@code name} for payloads of {@code type}. */
    @AllowAllocation("formats are declared once")
    public static <T> DataFormat<T> of(String name, Class<T> type) {
        return new DataFormat<>(name, type);
    }

    /** Its name. */
    public String name() {
        return name;
    }

    /** Its payload type. */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return name;
    }
}
