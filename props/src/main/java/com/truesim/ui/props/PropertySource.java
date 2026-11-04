package com.truesim.ui.props;

/**
 * Provides property models for some types (design 5.11): the classes the annotation processor generated, reflection
 * over annotated records and classes, and sources outside the UI the engine registers: its settings ({@code Setting}
 * ranges, choices, labels and groups as properties of a settings type, so the settings screen is generated) and the
 * Slang-generated material records (their {@code [Range]} checks and withers). {@link PropertyModels} merges every
 * source's model of a type by {@link #priority()}, the highest first.
 */
public interface PropertySource {

    /** The priority: a higher one's properties and annotations win. */
    int priority();

    /** The model of {@code type}, or null when this source knows none. */
    <T> PropertyModel<T> model(Class<T> type);
}
