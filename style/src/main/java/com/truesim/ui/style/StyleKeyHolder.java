package com.truesim.ui.style;

/**
 * A class declaring style keys of a module above ui-style (a control library), found through
 * {@link java.util.ServiceLoader} when a sheet is compiled ({@link StyleKeys#loadInstalled()}), so the shipped themes'
 * partials can style controls ui-style does not know. A module lists its holder (with a public no-argument
 * constructor) in {@code META-INF/services/com.truesim.ui.style.StyleKeyHolder}; making it initialises the class, which
 * registers its keys.
 */
public interface StyleKeyHolder {
}
