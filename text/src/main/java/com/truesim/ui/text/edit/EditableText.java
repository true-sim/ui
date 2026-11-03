package com.truesim.ui.text.edit;

import com.truesim.annotations.AllowAllocation;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The editing engine of a text field or area (design 5.3): the text (a {@link GapBuffer}), its rich-text
 * {@link Spans}, the selection (an anchor and a caret, character indices at grapheme boundaries), the IME's
 * composition and the undo history; edits through a {@link TextFilter} and a maximum length. Engine-agnostic and
 * layout-free: moves that need the layout (up and down, visual moves in mixed directions, line start and end of
 * wrapped lines) are made by the control, which then calls {@link #moveTo}.
 *
 * <p><b>Undo</b> keeps each edit's removed and inserted text (and, for rich text, the spans before and after), and
 * coalesces: typing continues the last typing edit while it goes on at its end within
 * {@link #coalesceNanos()} and does not start a new word; deleting backwards or forwards continues likewise.
 *
 * <p><b>Composition</b>: the IME's preedit sits in the text as a marked range ({@link #compositionStart()},
 * {@link #compositionEnd()}) outside the undo history and the filter; committed text replaces it as one typed edit;
 * an empty composition removes it.
 *
 * <p>Edits happen when input arrives: they may allocate (history records, break iterator setup). Reading the text
 * and the selection does not.
 */
@AllowAllocation("edits record history and set up break iterators when input arrives, not per frame")
public final class EditableText {

    /** Edit kinds for coalescing. */
    private static final int OTHER = 0;
    private static final int TYPING = 1;
    private static final int DELETE_BACK = 2;
    private static final int DELETE_FORWARD = 3;

    /** How many characters around a position the break iterators see (a paragraph is cut there). */
    private static final int WINDOW = 4096;

    private final GapBuffer text = new GapBuffer(64);
    private final Spans spans = new Spans();
    private int anchor;
    private int caret;
    private int compositionStart = -1;
    private int compositionEnd = -1;
    private int compositionCursor;
    private boolean multiLine;
    private int maxLength = Integer.MAX_VALUE;
    private TextFilter filter;
    private long version;
    private long selectionVersion;
    private long now;
    private long coalesceNanos = 1_000_000_000L;
    private int historyLimit = 1000;

    private final List<Edit> undo = new ArrayList<>();
    private final List<Edit> redo = new ArrayList<>();

    private int[] breaks = new int[16]; // the line feeds' positions, ascending
    private int breakCount;

    private final BufferIterator iterator = new BufferIterator();
    private BreakIterator graphemes;
    private BreakIterator words;

    /** One recorded edit. */
    private static final class Edit {
        int at;
        String removed;
        String inserted;
        Spans spansBefore; // rich text only
        Spans spansAfter;
        int anchorBefore;
        int caretBefore;
        int anchorAfter;
        int caretAfter;
        int kind;
        long time;
    }

    /** Empty, single-line text. */
    public EditableText() {
    }

    // ---------------------------------------------------------------- reading

    /** The text (without a copy: valid until the next edit). */
    public CharSequence text() {
        return text;
    }

    /** The rich-text spans (change them through the edits, or {@link #changedSpans()} after changing them). */
    public Spans spans() {
        return spans;
    }

    /** Characters. */
    public int length() {
        return text.length();
    }

    /** Grows with every change of the text or spans. */
    public long version() {
        return version;
    }

    /** Grows with every change of the selection or composition (and of the text). */
    public long selectionVersion() {
        return selectionVersion;
    }

    /** Where the selection started (equal to the caret when nothing is selected). */
    public int anchor() {
        return anchor;
    }

    /** The caret: where typing goes and the selection's moving end. */
    public int caret() {
        return caret;
    }

    /** The selection's first character. */
    public int selectionStart() {
        return Math.min(anchor, caret);
    }

    /** The character after the selection. */
    public int selectionEnd() {
        return Math.max(anchor, caret);
    }

    /** Whether characters are selected. */
    public boolean hasSelection() {
        return anchor != caret;
    }

    /** The selected text (a copy). */
    public String selectedText() {
        return text.subSequence(selectionStart(), selectionEnd()).toString();
    }

    /** The composition's first character, or -1 when none runs. */
    public int compositionStart() {
        return compositionStart;
    }

    /** The character after the composition, or -1. */
    public int compositionEnd() {
        return compositionEnd;
    }

    /** Whether the IME is composing. */
    public boolean composing() {
        return compositionStart >= 0;
    }

    // ---------------------------------------------------------------- settings

    /** Whether line breaks may be typed and pasted (a text area); otherwise they become spaces. */
    public EditableText multiLine(boolean on) {
        multiLine = on;
        return this;
    }

    /** Whether line breaks are kept. */
    public boolean multiLine() {
        return multiLine;
    }

    /** At most {@code n} characters (UTF-16 units, never splitting a pair); longer input is cut. */
    public EditableText maxLength(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("max length " + n);
        }
        maxLength = n;
        return this;
    }

    /** The maximum length. */
    public int maxLength() {
        return maxLength;
    }

    /** The filter edits pass through, or null. */
    public EditableText filter(TextFilter value) {
        filter = value;
        return this;
    }

    /** How long typing may pause and still undo as one edit. */
    public EditableText coalesceNanos(long nanos) {
        coalesceNanos = nanos;
        return this;
    }

    /** How long typing may pause and still undo as one edit, nanoseconds. */
    public long coalesceNanos() {
        return coalesceNanos;
    }

    /** At most {@code n} edits are kept for undo. */
    public EditableText historyLimit(int n) {
        historyLimit = Math.max(1, n);
        return this;
    }

    /** The time of the edits that follow (the UI clock), for coalescing. */
    public void now(long nanos) {
        now = nanos;
    }

    // ---------------------------------------------------------------- setting

    /** Replaces the text (plain: no spans), clears the history, puts the caret at the end. */
    public void set(CharSequence value) {
        endComposition(false);
        CharSequence v = clean(value);
        remove(0, text.length());
        put(0, v);
        spans.clear();
        undo.clear();
        redo.clear();
        anchor = caret = text.length();
        changed();
    }

    /** Replaces the text and spans with {@code markup} ({@link Markup}), clears the history, caret at the end. */
    public void setMarkup(CharSequence markup) {
        endComposition(false);
        StringBuilder plain = new StringBuilder();
        Markup.parse(markup, plain, spans);
        remove(0, text.length());
        put(0, clean(plain));
        undo.clear();
        redo.clear();
        anchor = caret = text.length();
        changed();
    }

    /** The text with its spans as markup. */
    public String markup() {
        return Markup.format(text, spans);
    }

    /** Tells the text its spans were changed directly (a style applied to the selection). */
    public void changedSpans() {
        changed();
    }

    private void changed() {
        version++;
        selectionVersion++;
    }

    /** Line breaks as line feeds (a text area) or spaces (a field). */
    private CharSequence clean(CharSequence s) {
        boolean breaks = false;
        for (int i = 0; i < s.length() && !breaks; i++) {
            breaks = s.charAt(i) == '\r' || !multiLine && s.charAt(i) == '\n';
        }
        if (!breaks) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                continue;
            }
            boolean lineBreak = c == '\n' || c == '\r';
            b.append(lineBreak ? (multiLine ? '\n' : ' ') : c);
        }
        return b;
    }

    // ---------------------------------------------------------------- selection

    /** Puts the caret at {@code index} (snapped into the text); {@code extend} keeps the anchor (Shift held). */
    public void moveTo(int index, boolean extend) {
        int i = Math.max(0, Math.min(text.length(), index));
        if (!extend) {
            anchor = i;
        }
        if (caret != i || !extend) {
            caret = i;
        }
        selectionVersion++;
    }

    /** Selects {@code [from, to)}: the anchor at {@code from}, the caret at {@code to}. */
    public void select(int from, int to) {
        anchor = Math.max(0, Math.min(text.length(), from));
        caret = Math.max(0, Math.min(text.length(), to));
        selectionVersion++;
    }

    /** Selects everything. */
    public void selectAll() {
        select(0, text.length());
    }

    /** One grapheme left or right of the caret ({@code forward}), or a word; collapses a selection first. */
    public void moveLogically(boolean forward, boolean word, boolean extend) {
        if (hasSelection() && !extend && !word) {
            moveTo(forward ? selectionEnd() : selectionStart(), false);
            return;
        }
        int to = word ? (forward ? nextWord(caret) : previousWord(caret))
            : (forward ? nextGrapheme(caret) : previousGrapheme(caret));
        moveTo(to, extend);
    }

    /** The grapheme cluster boundary after {@code index} (the text's length at its end). */
    public int nextGrapheme(int index) {
        if (index >= text.length()) {
            return text.length();
        }
        if (text.charAt(index) == '\n') {
            return index + 1;
        }
        BreakIterator g = graphemes(index);
        int b = g.following(index);
        return b == BreakIterator.DONE ? text.length() : b;
    }

    /** The grapheme cluster boundary before {@code index} (0 at the start). */
    public int previousGrapheme(int index) {
        if (index <= 0) {
            return 0;
        }
        if (text.charAt(index - 1) == '\n') {
            return index - 1;
        }
        BreakIterator g = graphemes(index);
        int b = g.preceding(index);
        return b == BreakIterator.DONE ? 0 : b;
    }

    /** The start of the next word after {@code index}, or the end of the line or text (Ctrl+Right). */
    public int nextWord(int index) {
        int n = text.length();
        int i = index;
        if (i < n && text.charAt(i) == '\n') {
            return i + 1;
        }
        // over the rest of this word, then the spaces after it
        while (i < n && wordChar(i)) {
            i = nextGrapheme(i);
        }
        if (i == index && i < n && !wordChar(i) && !Character.isWhitespace(text.charAt(i))) {
            i = nextGrapheme(i); // punctuation is a word of its own
        }
        while (i < n && text.charAt(i) != '\n' && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The start of the word before {@code index} (Ctrl+Left). */
    public int previousWord(int index) {
        int i = index;
        if (i > 0 && text.charAt(i - 1) == '\n') {
            return i - 1;
        }
        while (i > 0 && text.charAt(i - 1) != '\n' && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && !wordChar(i - 1)) {
            return previousGrapheme(i); // punctuation is a word of its own
        }
        while (i > 0 && wordChar(i - 1)) {
            i = previousGrapheme(i);
        }
        return i;
    }

    /** Whether the character at {@code i} belongs to a word (letters, digits, marks, connectors). */
    private boolean wordChar(int i) {
        char c = text.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_') {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            || Character.isSurrogate(c) && Character.isLetterOrDigit(Character.codePointAt(text, i > 0
            && Character.isLowSurrogate(c) ? i - 1 : i));
    }

    /** The word at {@code index} as {@code [start, end)} in a long: {@code start << 32 | end} (a double click). */
    public long wordAt(int index) {
        int n = text.length();
        if (n == 0) {
            return 0;
        }
        int i = Math.min(index, n - 1);
        if (i > 0 && (i == n || !wordChar(i)) && wordChar(i - 1)) {
            i--; // just after a word: that word
        }
        int start;
        int end;
        if (wordChar(i)) {
            start = i;
            while (start > 0 && wordChar(start - 1)) {
                start--;
            }
            end = i;
            while (end < n && wordChar(end)) {
                end++;
            }
        } else {
            BreakIterator w = words(i);
            start = w.isBoundary(i) ? i : w.preceding(i);
            end = w.following(i);
            if (start == BreakIterator.DONE) {
                start = 0;
            }
            if (end == BreakIterator.DONE) {
                end = n;
            }
        }
        return (long) start << 32 | end;
    }

    /** The hard line (paragraph) around {@code index} as {@code start << 32 | end} (a triple click; end before the break). */
    public long lineAt(int index) {
        int start = Math.min(index, text.length());
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        int end = Math.max(0, index);
        while (end < text.length() && text.charAt(end) != '\n') {
            end++;
        }
        return (long) start << 32 | end;
    }

    private BreakIterator graphemes(int index) {
        if (graphemes == null) {
            graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        }
        graphemes.setText(window(index));
        return graphemes;
    }

    private BreakIterator words(int index) {
        if (words == null) {
            words = BreakIterator.getWordInstance(Locale.ROOT);
        }
        words.setText(window(index));
        return words;
    }

    /** The paragraph around {@code index}, cut at {@link #WINDOW} characters each way. */
    private BufferIterator window(int index) {
        int from = Math.max(0, index - WINDOW);
        int to = Math.min(text.length(), index + WINDOW);
        for (int i = index - 1; i >= from; i--) {
            if (text.charAt(i) == '\n') {
                from = i + 1;
                break;
            }
        }
        for (int i = index; i < to; i++) {
            if (text.charAt(i) == '\n') {
                to = i;
                break;
            }
        }
        if (from > index) {
            from = index;
        }
        return iterator.reset(text, from, Math.max(to, index));
    }

    // ---------------------------------------------------------------- editing

    /**
     * Typed or committed text: replaces the composition if one runs, else the selection. Returns false if the filter
     * refused it.
     */
    public boolean type(CharSequence typed) {
        endComposition(false); // committed text replaces the preedit
        return replace(selectionStart(), selectionEnd(), typed, null, TYPING);
    }

    /** Inserts {@code value} (pasted text, with {@code fragment}'s spans or none) over the selection. */
    public boolean paste(CharSequence value, Spans fragment) {
        endComposition(true);
        return replace(selectionStart(), selectionEnd(), value, fragment, OTHER);
    }

    /** Replaces {@code [from, to)} with {@code value}, as one undoable edit; false if the filter refused it. */
    public boolean replace(int from, int to, CharSequence value) {
        endComposition(true);
        return replace(from, to, value, null, OTHER);
    }

    /** Deletes the selection, or the grapheme (or word) before the caret. */
    public boolean deleteBack(boolean word) {
        endComposition(true);
        if (hasSelection()) {
            return replace(selectionStart(), selectionEnd(), "", null, OTHER);
        }
        if (caret == 0) {
            return false;
        }
        int from = word ? previousWord(caret) : previousCharacter(caret);
        return replace(from, caret, "", null, DELETE_BACK);
    }

    /**
     * The position Backspace deletes back to: one code point of a cluster of letters and marks (so a mark typed last
     * goes first), a whole emoji or other cluster otherwise.
     */
    private int previousCharacter(int index) {
        int g = previousGrapheme(index);
        int cp = Character.codePointBefore(text, index);
        if (index - g > Character.charCount(cp) && Character.getType(cp) == Character.NON_SPACING_MARK) {
            return index - Character.charCount(cp);
        }
        return g;
    }

    /** Deletes the selection, or the grapheme (or word) after the caret. */
    public boolean deleteForward(boolean word) {
        endComposition(true);
        if (hasSelection()) {
            return replace(selectionStart(), selectionEnd(), "", null, OTHER);
        }
        if (caret == text.length()) {
            return false;
        }
        int to = word ? nextWord(caret) : nextGrapheme(caret);
        return replace(caret, to, "", null, DELETE_FORWARD);
    }

    private boolean replace(int from, int to, CharSequence value, Spans fragment, int kind) {
        CharSequence v = clean(value);
        if (filter != null) {
            v = filter.filter(text, from, to, v);
            if (v == null) {
                return false;
            }
        }
        int room = maxLength - (text.length() - (to - from));
        if (v.length() > room) {
            int cut = Math.max(0, room);
            if (cut > 0 && Character.isHighSurrogate(v.charAt(cut - 1))) {
                cut--;
            }
            v = v.subSequence(0, cut);
            if (v.length() == 0 && from == to) {
                return false;
            }
        }
        if (from == to && v.length() == 0) {
            return false;
        }
        Edit last = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        boolean rich = spans.count() > 0 || fragment != null && fragment.count() > 0;
        if (last != null && coalesces(last, from, to, v, kind, rich)) {
            if (kind == TYPING) {
                last.inserted = last.inserted + v;
            } else if (kind == DELETE_BACK) {
                last.removed = text.subSequence(from, to) + last.removed;
                last.at = from;
            } else {
                last.removed = last.removed + text.subSequence(from, to);
            }
            apply(from, to, v, fragment);
            last.anchorAfter = anchor;
            last.caretAfter = caret;
            last.time = now;
            if (rich) {
                last.spansAfter.set(spans);
            }
        } else {
            Edit e = new Edit();
            e.at = from;
            e.removed = text.subSequence(from, to).toString();
            e.inserted = v.toString();
            e.anchorBefore = anchor;
            e.caretBefore = caret;
            e.kind = kind;
            e.time = now;
            if (rich) {
                e.spansBefore = new Spans();
                e.spansBefore.set(spans);
            }
            apply(from, to, v, fragment);
            e.anchorAfter = anchor;
            e.caretAfter = caret;
            if (rich) {
                e.spansAfter = new Spans();
                e.spansAfter.set(spans);
            }
            undo.add(e);
            if (undo.size() > historyLimit) {
                undo.remove(0);
            }
        }
        redo.clear();
        return true;
    }

    private boolean coalesces(Edit last, int from, int to, CharSequence v, int kind, boolean rich) {
        if (last.kind != kind || kind == OTHER || now - last.time > coalesceNanos
            || rich != (last.spansAfter != null)) {
            return false;
        }
        if (kind == TYPING) {
            boolean contiguous = from == to && from == last.at + last.inserted.length() && last.removed.isEmpty()
                && caret == from;
            // a new word starts a new edit: a non-space typed after a space
            boolean newWord = v.length() > 0 && !Character.isWhitespace(v.charAt(0)) && !last.inserted.isEmpty()
                && Character.isWhitespace(last.inserted.charAt(last.inserted.length() - 1));
            return contiguous && !newWord && v.toString().indexOf('\n') < 0;
        }
        if (kind == DELETE_BACK) {
            return v.length() == 0 && to == last.at && last.inserted.isEmpty();
        }
        return v.length() == 0 && from == last.at && last.inserted.isEmpty();
    }

    /** Changes the buffer and spans, puts the caret after the new text. */
    private void apply(int from, int to, CharSequence v, Spans fragment) {
        remove(from, to);
        spans.deleted(from, to);
        put(from, v);
        spans.inserted(from, v.length());
        if (fragment != null && fragment.count() > 0) {
            spans.paste(from, fragment);
        }
        anchor = caret = from + v.length();
        changed();
    }

    /** Undoes the last edit; false if there is none. */
    public boolean undo() {
        endComposition(true);
        if (undo.isEmpty()) {
            return false;
        }
        Edit e = undo.remove(undo.size() - 1);
        remove(e.at, e.at + e.inserted.length());
        put(e.at, e.removed);
        if (e.spansBefore != null) {
            spans.set(e.spansBefore);
        } else {
            spans.deleted(e.at, e.at + e.inserted.length());
            spans.inserted(e.at, e.removed.length());
        }
        anchor = e.anchorBefore;
        caret = e.caretBefore;
        redo.add(e);
        changed();
        return true;
    }

    /** Redoes the last undone edit; false if there is none. */
    public boolean redo() {
        endComposition(true);
        if (redo.isEmpty()) {
            return false;
        }
        Edit e = redo.remove(redo.size() - 1);
        remove(e.at, e.at + e.removed.length());
        put(e.at, e.inserted);
        if (e.spansAfter != null) {
            spans.set(e.spansAfter);
        } else {
            spans.deleted(e.at, e.at + e.removed.length());
            spans.inserted(e.at, e.inserted.length());
        }
        anchor = e.anchorAfter;
        caret = e.caretAfter;
        e.time = Long.MIN_VALUE / 2; // a redone edit does not coalesce with what is typed next
        undo.add(e);
        changed();
        return true;
    }

    /** Whether there is an edit to undo. */
    public boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Whether there is an edit to redo. */
    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Forgets the history. */
    public void clearHistory() {
        undo.clear();
        redo.clear();
    }

    // ---------------------------------------------------------------- lines

    /** Removes {@code [from, to)} from the buffer, keeping the line index. */
    private void remove(int from, int to) {
        if (from == to) {
            return;
        }
        int n = to - from;
        int w = 0;
        for (int i = 0; i < breakCount; i++) {
            int b = breaks[i];
            if (b < from) {
                breaks[w++] = b;
            } else if (b >= to) {
                breaks[w++] = b - n;
            }
        }
        breakCount = w;
        text.delete(from, to);
    }

    /** Inserts {@code value} at {@code at}, keeping the line index. */
    private void put(int at, CharSequence value) {
        int n = value.length();
        if (n == 0) {
            return;
        }
        int first = breakCount;
        for (int i = 0; i < breakCount; i++) {
            if (breaks[i] >= at) {
                if (first == breakCount) {
                    first = i;
                }
                breaks[i] += n;
            }
        }
        int added = 0;
        for (int i = 0; i < n; i++) {
            if (value.charAt(i) == '\n') {
                added++;
            }
        }
        if (added > 0) {
            if (breakCount + added > breaks.length) {
                breaks = java.util.Arrays.copyOf(breaks, Math.max(breaks.length * 2, breakCount + added));
            }
            System.arraycopy(breaks, first, breaks, first + added, breakCount - first);
            int k = first;
            for (int i = 0; i < n; i++) {
                if (value.charAt(i) == '\n') {
                    breaks[k++] = at + i;
                }
            }
            breakCount += added;
        }
        text.insert(at, value);
    }

    /** Hard lines (paragraphs): line feeds plus one. */
    public int lineCount() {
        return breakCount + 1;
    }

    /** Hard line {@code l}'s first character. */
    public int lineStart(int l) {
        return l == 0 ? 0 : breaks[l - 1] + 1;
    }

    /** The character after hard line {@code l} (its line feed, or the text's end). */
    public int lineEnd(int l) {
        return l < breakCount ? breaks[l] : text.length();
    }

    /** The hard line character {@code index} is on (a line feed belongs to the line it ends). */
    public int lineOf(int index) {
        int lo = 0;
        int hi = breakCount - 1;
        int found = breakCount;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (breaks[mid] >= index) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- composition

    /**
     * The IME's composition is now {@code preedit} with its cursor at {@code cursor} (within it): it replaces the
     * selection (when it starts) or the previous composition, unfiltered and outside the history. Empty ends it,
     * removing the preedit.
     */
    public void compose(CharSequence preedit, int cursor) {
        if (preedit.length() == 0) {
            endComposition(false);
            return;
        }
        int s;
        int e;
        if (composing()) {
            s = compositionStart;
            e = compositionEnd;
        } else {
            s = selectionStart();
            e = selectionEnd();
            if (s != e) { // composing over a selection replaces it, as typing would
                replace(s, e, "", null, OTHER);
                s = e = caret;
            }
        }
        CharSequence p = clean(preedit);
        remove(s, e);
        spans.deleted(s, e);
        put(s, p);
        spans.inserted(s, p.length());
        compositionStart = s;
        compositionEnd = s + p.length();
        compositionCursor = Math.max(0, Math.min(p.length(), cursor));
        anchor = caret = s + compositionCursor;
        changed();
    }

    /** The cursor within the composition, characters from its start. */
    public int compositionCursor() {
        return compositionCursor;
    }

    /**
     * Ends a composition: {@code keep} leaves the preedit as typed text (committing it as an edit), otherwise it is
     * removed.
     */
    public void endComposition(boolean keep) {
        if (!composing()) {
            return;
        }
        int s = compositionStart;
        int e = compositionEnd;
        compositionStart = compositionEnd = -1;
        String preedit = text.subSequence(s, e).toString();
        remove(s, e);
        spans.deleted(s, e);
        anchor = caret = s;
        changed();
        if (keep) {
            replace(s, s, preedit, null, TYPING);
        }
    }
}
