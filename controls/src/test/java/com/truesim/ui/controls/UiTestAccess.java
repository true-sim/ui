package com.truesim.ui.controls;

import com.truesim.ui.text.TextSystem;

/** The text systems of {@link UiTest} for tests in other packages (the data controls'). */
public final class UiTestAccess {

    private UiTestAccess() {
    }

    /** The JDK fallback over the bundled fonts. */
    public static TextSystem jdkText() {
        return UiTest.jdkText();
    }

    /** FreeType, HarfBuzz and msdfgen over the bundled fonts. */
    public static TextSystem nativeText() {
        return UiTest.nativeText();
    }
}
