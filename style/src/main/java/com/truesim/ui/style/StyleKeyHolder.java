package com.truesim.ui.style;

/**
 * A class declaring style keys that every style sheet may use, found on the class path as a
 * {@link java.util.ServiceLoader service} ({@code META-INF/services/com.truesim.ui.style.StyleKeyHolder}): a module of
 * controls lists its key holder there, so the shipped themes (which style those controls in their partials) compile
 * wherever the module is present, as the standard {@link Styles} always do. The holder needs a public no-argument
 * constructor for the service loader; creating it only initialises the class, which registers its keys.
 */
public interface StyleKeyHolder {
}
