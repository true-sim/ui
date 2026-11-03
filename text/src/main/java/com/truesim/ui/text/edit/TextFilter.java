package com.truesim.ui.text.edit;

/**
 * Decides what an edit may put into an {@link EditableText}: typed and pasted text pass through it before they
 * replace a range. Return the text to insert (the same, changed, or empty) or null to refuse the edit. Composition
 * (IME preedit) is not filtered until it is committed.
 */
@FunctionalInterface
public interface TextFilter {

    /**
     * What replaces {@code [start, end)} of {@code text} instead of {@code replacement}; null refuses the edit.
     *
     * @param text the text before the edit
     */
    CharSequence filter(CharSequence text, int start, int end, CharSequence replacement);
}
