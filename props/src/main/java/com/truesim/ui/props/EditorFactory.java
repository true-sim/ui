package com.truesim.ui.props;

/**
 * Makes the editor of a property row (design 5.11). Registered by name in {@link Editors} (and per type or annotation
 * there), then chosen by code, styles ({@code editor: <name>}) or annotations: a new editor kind is code, which
 * editor a row uses is the theme's.
 */
@FunctionalInterface
public interface EditorFactory {

    /** The editor of {@code row}: it binds its control with {@link PropertyRow#bind}. */
    PropertyEditor create(PropertyRow row);
}
