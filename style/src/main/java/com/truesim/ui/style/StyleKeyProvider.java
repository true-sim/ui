package com.truesim.ui.style;

import java.util.List;

/**
 * Names the classes declaring style keys of a module (a control package's key holder), found through
 * {@link java.util.ServiceLoader} ({@code META-INF/services/com.truesim.ui.style.StyleKeyProvider}) whenever a sheet
 * is compiled, so the shipped themes, which style every control, compile with every control's keys whichever classes
 * were loaded first.
 */
public interface StyleKeyProvider {

    /** The classes whose static style keys to register. */
    List<Class<?>> holders();
}
