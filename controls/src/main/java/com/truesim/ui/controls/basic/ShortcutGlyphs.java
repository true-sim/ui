package com.truesim.ui.controls.basic;

import com.truesim.common.state.Versioned;

/**
 * Where menus and toolbars get the label of a shortcut: the engine's input system answers with the glyph bound to an
 * action for the device used last ({@code Ctrl+S}, a gamepad button's glyph; design D62), engine-agnostic here. The
 * action is whatever object the engine names actions by. Its version grows when the labels change (another device
 * was used, a binding changed), so menus show the new ones without a refresh.
 */
public interface ShortcutGlyphs extends Versioned {

    /** Shows no shortcuts. */
    ShortcutGlyphs NONE = new ShortcutGlyphs() {
        @Override
        public CharSequence label(Object action) {
            return "";
        }

        @Override
        public long version() {
            return 0;
        }
    };

    /** The label of {@code action}'s shortcut now, empty when it has none. Called when the version changed. */
    CharSequence label(Object action);
}
