package com.truesim.ui.controls.text;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Painter;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.basic.Controls;
import com.truesim.ui.controls.basic.Menu;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.TextInputHost;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.value.Color;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.TextDecoration;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextDirection;
import com.truesim.ui.text.TextRuns;
import com.truesim.ui.text.TextSystem;
import com.truesim.ui.text.edit.EditableText;
import com.truesim.ui.text.edit.Emoticons;
import com.truesim.ui.text.edit.Spans;
import com.truesim.ui.text.spell.SpellChecker;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * An editable text element (design 5.3): a single-line field ({@link #field}) or a multi-line area ({@link #area}),
 * plain or rich, editable, read-only or a selectable label. It owns an {@link EditableText} (the editing engine:
 * selection, undo, composition), draws through the frame as a {@link Painter}, and takes its input as events:
 *
 * <ul>
 *   <li><b>commands</b> ({@link UiCommand}): arrows (visual or logical per {@link #CARET_MOVEMENT}; with
 *   {@link UiInput#EXTEND_SELECTION} they extend), {@code WORD_LEFT/RIGHT}, {@code LINE_START/END} and
 *   {@code HOME/END} (the visual line), {@code DOC_START/END}, {@code UP/DOWN} and {@code PAGE_UP/DOWN} in an area
 *   (keeping the column), {@code SELECT_ALL}, {@code COPY}, {@code CUT}, {@code PASTE} (the interaction's
 *   clipboard), {@code UNDO}, {@code REDO}, {@code DELETE_*}, {@code SUBMIT}, {@code NEWLINE}, Tab in an area per
 *   {@link #AREA_TAB};</li>
 *   <li><b>text</b> and <b>composition</b> (the IME's preedit, drawn inline with an underline; the candidate window
 *   placed at the caret through {@link Interaction#imeArea});</li>
 *   <li><b>pointers</b>: press places the caret, Shift extends, a double press selects a word and a triple press the
 *   line, dragging extends by the unit pressed; touch drags scroll, a long press selects a word and shows a
 *   selection handle at each end ({@code selection-handle}), dragged to move that end; the wheel scrolls;
 *   links in rich text are hovered ({@code link:hover}) and clicked ({@link Listener#link}).</li>
 * </ul>
 *
 * <p><b>Style.</b> Everything presentational comes from the theme: the element's own keys (font, colour,
 * {@code line-height}, {@code text-align}, {@code text-mask} for passwords, padding, background, {@code clip}) and
 * its parts' ({@link Ui#part}): {@code caret} (width, background, opacity), {@code selection} (background, radius),
 * {@code placeholder} (colour, font), {@code composition} (border: the underline), {@code selection-handle} (size,
 * background, radius: the touch selection handles), {@code gutter} (background,
 * colour, padding: line numbers), and for rich text {@code span.<class>}, {@code link}, {@code link:hover} and
 * {@code img} (height), which may set font keys, colour, {@code text-decoration} and background. States:
 * {@code :focused}, {@code :disabled}, {@code :read-only}, {@code :invalid} (the {@link #validator}).
 *
 * <p><b>Scale.</b> A text area keeps one shaped block per visible paragraph only (a pool), a height per paragraph in
 * a prefix-sum index (estimated until shaped), and the edits' line damage, so typing and scrolling in a megabyte of
 * text cost the visible lines. Steady frames (idle, caret blink, scrolling over shaped text) allocate nothing.
 */
public final class TextEditor implements Painter, UiListener, Ui.FrameListener {

    /** How long the caret shows, then hides, seconds; 0 keeps it steady (an accessibility setting). */
    public static final Tunable CARET_BLINK = Tunable.of("ui.text.caretBlink", 0.53f, 0, 2,
        "Seconds the text caret shows before it hides, and hides before it shows; 0 keeps it steady.");
    /** How Left and Right move the caret through mixed-direction text. */
    public static final Tunable CARET_MOVEMENT = Tunable.choice("ui.text.caretMovement", List.of("visual",
        "logical"), "visual", "Left and Right in mixed-direction text: visual (the way the arrow points) or logical "
            + "(in reading order).");
    /** What Tab does in a text area. */
    public static final Tunable AREA_TAB = Tunable.choice("ui.text.areaTab", List.of("insert", "focus"), "insert",
        "Tab in a multi-line text area: insert a tab character, or move the focus on.");
    /** Emoticon and shortcode replacement while typing. */
    public static final Tunable EMOJI_REPLACEMENT = Tunable.choice("ui.text.emojiReplacement", List.of("off",
        "emoticons", "shortcodes", "both"), "off", "Replace typed emoticons such as :) and shortcodes such as "
            + ":smile: with emoji.");

    /**
     * Milliseconds a frame spends spell checking paragraphs out of view (the visible ones are checked as they are drawn,
     * whatever the budget): a large text is checked over several frames.
     */
    public static final Tunable SPELL_BUDGET = Tunable.of("ui.text.spellBudget", 0.25f, 0.01f, 10,
        "Milliseconds a frame spends spell checking text out of view (visible text is checked at once)");

    /** Every tunable the text editors read. */
    @AllowAllocation("asked once")
    public static List<Tunable> tunables() {
        List<Tunable> all = new java.util.ArrayList<>(List.of(CARET_BLINK, CARET_MOVEMENT, AREA_TAB,
            EMOJI_REPLACEMENT, SPELL_BUDGET));
        all.addAll(SpellChecker.tunables());
        return all;
    }

    /** What an editor tells its owner. */
    public interface Listener {
        /** The text changed (typed, deleted, pasted, undone, set). */
        default void changed(TextEditor editor) {
        }

        /** Enter in a field, or in an area that submits on Enter. */
        default void submitted(TextEditor editor) {
        }

        /** A link of rich text was clicked: its target (a URL, a console command). */
        default void link(TextEditor editor, String target) {
        }

        /** The editor lost the focus (a field commits its value then). */
        default void left(TextEditor editor) {
        }
    }

    private static final float INF = Float.POSITIVE_INFINITY;
    private static final TextAlign[] TEXT_ALIGNS = TextAlign.values();
    private static final int POOL = 96; // shaped paragraphs kept: the visible ones and some around them
    private static final int NONE = -1;

    private final Ui ui;
    private final Element element;
    private final Interaction interaction;
    private final TextSystem system;
    private final EditableText text = new EditableText();
    private final boolean multiLine;
    private boolean wrap = true;
    private boolean readOnly;
    private boolean editable = true; // false: a rich label (selectable, not editable)
    private boolean rich;
    private boolean submitOnEnter;
    private boolean passSubmit;
    private boolean lineNumbers;
    private char mask;
    private boolean revealed;
    private Listener listener;
    private Predicate<CharSequence> validator;

    // parts
    private final Element caretPart;
    private final Element selectionPart;
    private final Element placeholderPart;
    private final Element compositionPart;
    private final Element handlePart;
    private Element gutterPart;
    private Element linkPart;
    private Element linkHoverPart;
    private Element imagePart;
    private final Map<String, Element> classParts = new HashMap<>();
    private Element[] spanParts = new Element[8]; // the parts rich text reads, for noticing their restyles
    private int spanPartCount;
    private long partsSeen;

    // font of the element
    private List<String> families = List.of("sans-serif");
    private float size = 14;
    private int weight = 400;
    private boolean italic;
    private float lineHeight;
    private long color = Rgba.WHITE;
    private TextAlign align = TextAlign.START;
    private long styleSeen = -1;
    private int styleHandleSeen = -1;
    private float scaleSeen = -1;

    // paragraphs
    private final HeightIndex heights = new HeightIndex();
    private int[] stamp = new int[16];
    private int[] block = new int[16];
    private int nextStamp = 1;
    private float wrapWidth = -1;
    private float charWidth; // average advance, for estimating unshaped paragraphs
    private long textSeen = -1;
    private long selectionSeen = -1;

    // the pool of shaped paragraphs
    private final TextBlock[] pool = new TextBlock[POOL];
    private final TextRuns[] poolRuns = new TextRuns[POOL];
    private final int[] poolPara = new int[POOL];
    private final int[] poolStamp = new int[POOL];
    private final long[] poolUsed = new long[POOL];
    private final long[] poolDrawn = new long[POOL]; // the block's version when last recorded
    private long placeholderDrawn;
    private long useClock;
    private final Slice slice = new Slice();

    // spell checking: per paragraph its misspelt words (paragraph-local start, end pairs), dirty until checked
    private SpellChecker speller;
    private Controls spellControls;
    private Menu spellMenu;
    private Element misspeltPart;
    private long spellVersionSeen = -1;
    private int[][] spellErrors = new int[16][];
    private int[] spellCount = new int[16];
    private boolean[] spellDirty = new boolean[16];
    private boolean spellPending;
    private int spellCursor;
    private int spellPara;
    private int spellChecks; // paragraphs checked so far (for tests)
    private final SpellChecker.Misspelt collector = this::misspelt;
    private String spellAdd = "Add to dictionary";
    private String spellIgnore = "Ignore";
    private String spellNone = "No suggestions";
    private boolean[] maskStarts = new boolean[0];
    private final StringBuilder placeholder = new StringBuilder();
    private TextBlock placeholderBlock;

    // view
    private int anchor; // the paragraph at the top of the view
    private float anchorOffset; // pixels of it above the view
    private float scrollX;
    private float viewW;
    private float viewH;
    private float textX0; // where text starts on screen in the last paint
    private float textY0;
    private int visibleFrom;
    private int visibleTo;
    private boolean revealCaret = true;
    private float goalX = Float.NaN; // the column Up and Down keep
    private final float[] boxes = new float[256];

    // caret blink
    private long now;
    private long blinkStart;
    private boolean blinkOn = true;

    // pointer
    private int clicks;
    private long lastPress = Long.MIN_VALUE / 2;
    private float lastPressX;
    private float lastPressY;
    private int unitStart; // the word or line pressed, which a drag extends from
    private int unitEnd;
    private boolean selecting;
    private boolean touchPress;
    private float touchY;
    private float touchX;
    private boolean touchMoved;
    // touch selection handles: shown while a touch made the selection; one of them dragged (1 start, 2 end)
    private boolean touchSelection;
    private int draggedHandle;
    private float grabX;
    private float grabY;
    private final float[] handles = new float[4]; // the two handles' centres where last painted
    private int hoveredLink = NONE;
    private int pressedLink = NONE;

    // clipboard: the rich fragment of the last copy, pasted when the clipboard still holds its text
    private String copiedText;
    private final Spans copiedSpans = new Spans();
    private final Spans fragment = new Spans();

    private TextEditor(Ui ui, String type, String style, String label, boolean multiLine) {
        if (ui.textSystem() == null) {
            throw new IllegalStateException("a text editor needs a UI with a text system");
        }
        this.ui = ui;
        this.system = ui.textSystem();
        this.interaction = ui.interaction();
        this.multiLine = multiLine;
        this.element = ui.element(type, style);
        text.multiLine(multiLine);
        caretPart = ui.part(element, "caret", null);
        selectionPart = ui.part(element, "selection", null);
        placeholderPart = ui.part(element, "placeholder", null);
        compositionPart = ui.part(element, "composition", null);
        handlePart = ui.part(element, "selection-handle", null);
        ui.paint(element, this);
        element.semantics(Role.TEXT_FIELD, label);
        interaction.textInput(element, true);
        interaction.textInputKind(element, multiLine ? TextInputHost.MULTILINE : TextInputHost.TEXT);
        int mask = EventType.COMMAND.bit() | EventType.TEXT.bit() | EventType.COMPOSITION.bit()
            | EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit() | EventType.WHEEL.bit()
            | EventType.FOCUS_IN.bit() | EventType.FOCUS_OUT.bit() | EventType.EXIT.bit()
            | EventType.LONG_PRESS.bit() | EventType.DRAG_START.bit() | EventType.CONTEXT.bit();
        interaction.on(element, mask, this);
        ui.addFrameListener(this);
        Arrays.fill(poolPara, NONE);
        Arrays.fill(block, NONE);
        heights.resize(1);
    }

    /**
     * A single-line text field (type {@code textfield}) with style name {@code style} (or null), announced as
     * {@code label}.
     */
    @AllowAllocation("controls are made when a UI is built")
    public static TextEditor field(Ui ui, String style, String label) {
        return new TextEditor(ui, "textfield", style, label, false);
    }

    /** A multi-line text area (type {@code textarea}), word-wrapped, announced as {@code label}. */
    @AllowAllocation("controls are made when a UI is built")
    public static TextEditor area(Ui ui, String style, String label) {
        return new TextEditor(ui, "textarea", style, label, true);
    }

    /**
     * Rich text to read (type {@code richtext}): {@code markup} ({@link com.truesim.ui.text.edit.Markup}) shown with
     * its spans styled by the theme, selectable and copyable, links clickable; not editable.
     */
    @AllowAllocation("controls are made when a UI is built")
    public static TextEditor richText(Ui ui, String style, String label, CharSequence markup) {
        TextEditor e = new TextEditor(ui, "richtext", style, label, true);
        e.editable = false;
        e.rich = true;
        e.element.semantics(Role.TEXT, label);
        e.interaction.textInput(e.element, false);
        e.interaction.focusable(e.element, true);
        e.markup(markup);
        return e;
    }

    // ---------------------------------------------------------------- settings

    /** The element (add it to a parent). */
    public Element element() {
        return element;
    }

    /** The editing engine: the text, selection, spans and history. */
    public EditableText editable() {
        return text;
    }

    /** The text (valid until the next edit). */
    public CharSequence text() {
        return text.text();
    }

    /** Replaces the text (plain), clearing the history; the caret goes to the end. */
    public TextEditor text(CharSequence value) {
        text.set(value);
        changed(false);
        return this;
    }

    /** Replaces the text with rich {@code markup} and makes the editor rich. */
    public TextEditor markup(CharSequence value) {
        rich = true;
        text.setMarkup(value);
        changed(false);
        return this;
    }

    /** The text with its spans as markup. */
    public String markup() {
        return text.markup();
    }

    /** Whether spans are drawn (set by {@link #markup(CharSequence)}). */
    public TextEditor rich(boolean on) {
        rich = on;
        layoutChanged();
        return this;
    }

    /** The text shown while the editor is empty. */
    public TextEditor placeholder(CharSequence value) {
        placeholder.setLength(0);
        placeholder.append(value);
        ui.repaint(element);
        return this;
    }

    /** At most {@code n} characters. */
    public TextEditor maxLength(int n) {
        text.maxLength(n);
        return this;
    }

    /** The filter typed and pasted text passes through. */
    public TextEditor filter(com.truesim.ui.text.edit.TextFilter value) {
        text.filter(value);
        return this;
    }

    /** Whether the text is valid ({@code :invalid} otherwise); checked on every change. */
    public TextEditor validator(Predicate<CharSequence> value) {
        validator = value;
        validate();
        return this;
    }

    /** Whether the text passes the validator. */
    public boolean valid() {
        return !element.is(UiState.INVALID);
    }

    /** Read-only: selectable and copyable, not changed by input ({@code :read-only}). */
    public TextEditor readOnly(boolean on) {
        readOnly = on;
        element.state(UiState.READ_ONLY, on);
        return this;
    }

    /** Whether it is read-only. */
    public boolean readOnly() {
        return readOnly || !editable;
    }

    /** Whether an area wraps its lines at its width (the default) or scrolls sideways. */
    public TextEditor wrap(boolean on) {
        wrap = on;
        layoutChanged();
        return this;
    }

    /** Whether Enter submits ({@link Listener#submitted}) in an area too; Shift+Enter then makes the line break. */
    public TextEditor submitOnEnter(boolean on) {
        submitOnEnter = on;
        return this;
    }

    /**
     * Whether Enter, after {@link Listener#submitted}, passes on to the containers (a table cell's editor commits, a
     * dialog's default button), instead of ending at the editor.
     */
    public TextEditor passSubmit(boolean on) {
        passSubmit = on;
        return this;
    }

    /** Line numbers in a gutter (part {@code gutter}). */
    @AllowAllocation("a part is made the first time")
    public TextEditor lineNumbers(boolean on) {
        lineNumbers = on;
        if (on && gutterPart == null) {
            gutterPart = ui.part(element, "gutter", null);
        }
        layoutChanged();
        return this;
    }

    /** Hides the characters behind the style's {@code text-mask} (a password); no IME, copy or cut. */
    TextEditor password(boolean on) {
        mask = on ? '\u2022' : 0; // the style's text-mask replaces it when the style is read
        interaction.textInputKind(element, on ? TextInputHost.PASSWORD : TextInputHost.TEXT);
        styleSeen = -1;
        layoutChanged();
        return this;
    }

    /** Whether a password shows its characters. */
    void reveal(boolean on) {
        revealed = on;
        layoutChanged();
    }

    /** Whether the editor masks its text (a password). */
    public boolean masked() {
        return mask != 0 && !revealed;
    }

    /** Whether it is a password editor. */
    boolean password() {
        return mask != 0;
    }

    /**
     * Checks the spelling with {@code checker} (null: no more): misspelt words are underlined with a squiggle (the part
     * {@code span.misspelt}: its {@code color}, and {@code squiggle-wavelength}, {@code squiggle-amplitude},
     * {@code squiggle-thickness}), and the context menu on one (a {@link Menu} of {@code controls}) offers its
     * corrections, "Add to dictionary" and "Ignore". Only changed paragraphs are checked again; the visible ones as they
     * are drawn, the rest within {@code ui.text.spellBudget} a frame. Passwords are never checked; returns this.
     */
    @AllowAllocation("set up when a UI is built")
    public TextEditor spelling(SpellChecker checker, Controls controls) {
        speller = checker;
        spellControls = controls;
        if (checker != null && misspeltPart == null) {
            misspeltPart = ui.part(element, "span", "misspelt");
        }
        spellVersionSeen = -1;
        ui.repaint(element);
        return this;
    }

    /** The spell checker, or null. */
    public SpellChecker spelling() {
        return speller;
    }

    /** The spelling menu's texts (localised): add to the dictionary, ignore, and what shows without suggestions. */
    public TextEditor spellMenuText(String add, String ignore, String none) {
        spellAdd = add;
        spellIgnore = ignore;
        spellNone = none;
        return this;
    }

    /** The spelling menu (null until first opened). */
    public Menu spellMenu() {
        return spellMenu;
    }

    /** Whether paragraphs wait to be spell checked. */
    public boolean spellPending() {
        return speller != null && spellPending;
    }

    /** Paragraphs spell checked so far. */
    public int spellChecks() {
        return spellChecks;
    }

    /** The misspelt words found, as {@code [start, end)} text ranges (a test helper). */
    @AllowAllocation("a test helper")
    public List<int[]> misspelt() {
        List<int[]> out = new java.util.ArrayList<>();
        for (int p = 0; p < Math.min(heights.count(), text.lineCount()); p++) {
            int s = text.lineStart(p);
            for (int k = 0; k < spellCount[p]; k++) {
                out.add(new int[] {s + spellErrors[p][k * 2], s + spellErrors[p][k * 2 + 1]});
            }
        }
        return out;
    }

    /** The listener for changes, submits and links (null for none). */
    public TextEditor listener(Listener value) {
        listener = value;
        return this;
    }

    /** Focuses the editor (for {@code player} 0). */
    public void focus() {
        interaction.focus(element);
    }

    /** Whether it has player 0's focus. */
    public boolean focused() {
        return interaction.focused(0) == element;
    }

    // ---------------------------------------------------------------- changes

    /** The text or selection changed: lines to redo, the caret to reveal, the owner to tell. */
    private void changed(boolean byUser) {
        selectionSeen = text.selectionVersion();
        revealCaret = true;
        goalX = Float.NaN;
        blinkStart = now;
        if (text.version() != textSeen) {
            textSeen = text.version();
            validate();
            ui.remeasure(element);
            if (byUser && listener != null) {
                listener.changed(this);
            }
        }
        ui.repaint(element);
    }

    private void validate() {
        element.state(UiState.INVALID, validator != null && !validator.test(text.text()));
    }

    /** Everything is shaped again (font, width, wrapping, masking changed). */
    private void layoutChanged() {
        invalidate();
        ui.remeasure(element);
    }

    private void invalidate() {
        Arrays.fill(poolPara, NONE);
        Arrays.fill(block, 0, heights.count(), NONE);
        wrapWidth = -1;
    }

    // ---------------------------------------------------------------- paragraphs

    /** Brings the paragraph arrays up to the text's line damage. */
    private void syncParagraphs() {
        int n = text.lineCount();
        int old = heights.count();
        int from = text.damageFrom();
        if (from == Integer.MAX_VALUE && n == old) {
            return;
        }
        int tail = text.damageTail();
        text.clearDamage();
        int common = Math.min(n, old);
        from = Math.min(from, common);
        tail = Math.max(0, Math.min(tail, common - from));
        if (stamp.length < n) {
            grow(n);
        }
        heights.ensure(Math.max(n, old));
        float[] h = heights.heights();
        int shift = n - old;
        for (int i = 0; i < POOL; i++) { // shaped paragraphs: the tail moves, the damaged ones go
            int p = poolPara[i];
            if (p == NONE) {
                continue;
            }
            if (p >= old - tail && p < old) {
                poolPara[i] = p + shift;
            } else if (p >= from) {
                poolPara[i] = NONE;
            }
        }
        if (shift != 0) {
            System.arraycopy(stamp, old - tail, stamp, n - tail, tail);
            System.arraycopy(block, old - tail, block, n - tail, tail);
            System.arraycopy(h, old - tail, h, n - tail, tail);
        }
        spellDamaged(from, tail, shift, old, n);
        heights.resize(n);
        for (int p = from; p < n - tail; p++) {
            stamp[p] = nextStamp++;
            block[p] = NONE;
            h[p] = estimate(p);
        }
        heights.rebuild();
        if (anchor >= n) {
            anchor = Math.max(0, n - 1);
            anchorOffset = 0;
        }
    }

    /**
     * Paragraphs {@code [from, n - tail)} changed ({@code shift}: lines added or removed; their old results go, the
     * tail's moved with the other arrays): they are checked again.
     */
    private void spellDamaged(int from, int tail, int shift, int old, int n) {
        if (shift != 0) {
            System.arraycopy(spellErrors, old - tail, spellErrors, n - tail, tail);
            System.arraycopy(spellCount, old - tail, spellCount, n - tail, tail);
            System.arraycopy(spellDirty, old - tail, spellDirty, n - tail, tail);
        }
        for (int p = from; p < n - tail; p++) {
            if (shift != 0) {
                spellErrors[p] = null; // shared with a moved paragraph's: a new one when needed
            }
            spellCount[p] = 0;
            spellDirty[p] = true;
        }
        if (from < n - tail) {
            spellPending = true;
            spellCursor = Math.min(spellCursor, from);
        }
    }

    @AllowAllocation("growing with the text, rarely")
    private void grow(int n) {
        int c = Math.max(n, stamp.length * 2);
        stamp = Arrays.copyOf(stamp, c);
        int o = block.length;
        block = Arrays.copyOf(block, c);
        Arrays.fill(block, o, c, NONE);
        spellErrors = Arrays.copyOf(spellErrors, c);
        spellCount = Arrays.copyOf(spellCount, c);
        spellDirty = Arrays.copyOf(spellDirty, c);
    }

    /** A paragraph's height before it is shaped: its characters over the wrap width, at least one line. */
    private float estimate(int p) {
        float line = lineHeightPx();
        if (!multiLine || !wrap || !(wrapWidth > 0) || wrapWidth == INF) {
            return line;
        }
        int chars = text.lineEnd(p) - text.lineStart(p);
        float w = chars * (charWidth > 0 ? charWidth : size * 0.5f);
        return line * Math.max(1, (float) Math.ceil(w / wrapWidth));
    }

    private float lineHeightPx() {
        return lineHeight > 0 ? lineHeight * size : size * 1.25f;
    }

    /** Paragraph {@code p} shaped at the current font and width (from the pool, or shaped now into it). */
    private TextBlock shaped(int p) {
        int b = block[p];
        if (b != NONE && poolPara[b] == p && poolStamp[b] == stamp[p]) {
            poolUsed[b] = ++useClock;
            return pool[b];
        }
        b = freeSlot();
        if (pool[b] == null) {
            pool[b] = newBlock();
            poolRuns[b] = newRuns();
        }
        TextBlock tb = pool[b];
        int s = text.lineStart(p);
        int e = text.lineEnd(p);
        slice.set(text.text(), s, e, masked() ? mask : 0);
        if (masked()) {
            slice.clusters(clusterStarts(s, e));
        }
        float max = multiLine && wrap ? wrapWidth : INF;
        if (rich && text.spans().count() > 0) {
            TextRuns runs = poolRuns[b];
            buildRuns(runs, s, e);
            tb.set(slice, runs, TextAlign.START, max, lineHeight, TextDirection.AUTO, null);
        } else {
            tb.set(slice, families, size, weight, italic, TextAlign.START, max, lineHeight);
        }
        poolPara[b] = p;
        poolStamp[b] = stamp[p];
        poolUsed[b] = ++useClock;
        block[p] = b;
        heights.set(p, Math.max(tb.height(), lineHeightPx() * 0.5f));
        if (e > s && tb.width() > 0) {
            charWidth = charWidth == 0 ? tb.width() / (e - s) : charWidth * 0.9f + tb.width() / (e - s) * 0.1f;
        }
        return tb;
    }

    /** Which characters of {@code [s, e)} start a grapheme cluster (one mask glyph each). */
    private boolean[] clusterStarts(int s, int e) {
        if (maskStarts.length < e - s) {
            maskStarts = growStarts(e - s);
        }
        java.util.Arrays.fill(maskStarts, 0, e - s, false);
        for (int i = s; i < e; i = text.nextGrapheme(i)) {
            maskStarts[i - s] = true;
        }
        return maskStarts;
    }

    @AllowAllocation("growing with the password's length, rarely")
    private static boolean[] growStarts(int n) {
        return new boolean[Math.max(64, n * 2)];
    }

    @AllowAllocation("the pool fills once")
    private TextBlock newBlock() {
        return new TextBlock(system);
    }

    @AllowAllocation("the pool fills once")
    private static TextRuns newRuns() {
        return new TextRuns();
    }

    private int freeSlot() {
        int best = 0;
        for (int i = 0; i < POOL; i++) {
            if (poolPara[i] == NONE) {
                return i;
            }
            if (poolUsed[i] < poolUsed[best]) {
                best = i;
            }
        }
        int old = poolPara[best];
        if (old != NONE && old < heights.count() && block[old] == best) {
            block[old] = NONE;
        }
        poolPara[best] = NONE;
        return best;
    }

    // ---------------------------------------------------------------- style

    /** Reads the element's font and colour; true if they changed (everything is shaped again). */
    private boolean readStyle() {
        ResolvedStyle s = ui.styles().styleOf(element.slot());
        float scale = ui.layout().scale() > 0 ? ui.layout().scale() : 1;
        int handle = ui.styles().handle(element.slot());
        long parts = partsKey();
        if (handle == styleHandleSeen && s.version() == styleSeen && scale == scaleSeen && parts == partsSeen) {
            return false;
        }
        partsSeen = parts;
        styleHandleSeen = handle;
        styleSeen = s.version();
        scaleSeen = scale;
        families = s.get(Styles.FONT_FAMILY);
        size = s.getFloat(Styles.FONT_SIZE) * scale;
        weight = s.getInt(Styles.FONT_WEIGHT);
        italic = s.get(Styles.FONT_STYLE) != FontStyle.NORMAL;
        lineHeight = s.getFloat(Styles.LINE_HEIGHT);
        color = s.rgba(Styles.COLOR);
        align = TEXT_ALIGNS[s.get(Styles.TEXT_ALIGN).ordinal()];
        if (mask != 0) {
            String m = s.get(Styles.TEXT_MASK);
            mask = m.isEmpty() ? '\u2022' : m.charAt(0);
        }
        charWidth = 0;
        invalidate();
        return true;
    }

    /** A key that changes when a rich text part (a span class, a link, an image) is restyled. */
    private long partsKey() {
        long key = 0;
        for (int i = 0; i < spanPartCount; i++) {
            int slot = spanParts[i].slot();
            key = key * 31 + ui.styles().handle(slot) * 1_000_003L + ui.styles().styleOf(slot).version();
        }
        return key;
    }

    @AllowAllocation("a part is made the first time a span needs it")
    private Element keep(Element part) {
        if (spanPartCount == spanParts.length) {
            spanParts = Arrays.copyOf(spanParts, spanPartCount * 2);
        }
        spanParts[spanPartCount++] = part;
        return part;
    }

    private ResolvedStyle partStyle(Element part) {
        return ui.styles().styleOf(part.slot());
    }

    private static long paintColor(ResolvedStyle s, long fallback) {
        Paint p = s.get(Styles.BACKGROUND);
        return p instanceof Paint.Box box ? box.color().rgba() : fallback;
    }

    private float scale() {
        return scaleSeen > 0 ? scaleSeen : 1;
    }

    // ---------------------------------------------------------------- rich runs

    /** The style runs of paragraph {@code [s, e)} from the spans: the theme's span classes, links, images. */
    private void buildRuns(TextRuns runs, int s, int e) {
        runs.clear();
        Spans spans = text.spans();
        int at = s;
        while (true) {
            int next = e;
            for (int i = 0; i < spans.count(); i++) {
                int a = spans.start(i);
                int b = spans.end(i);
                if (a > at && a < next) {
                    next = a;
                }
                if (b > at && b < next) {
                    next = b;
                }
            }
            segment(runs, spans, at - s, at);
            if (next >= e) {
                break;
            }
            at = next;
        }
        runs.changed();
    }

    /** Adds the run starting at paragraph character {@code local} (text index {@code at}). */
    private void segment(TextRuns runs, Spans spans, int local, int at) {
        List<String> f = families;
        float sz = size;
        int wt = weight;
        boolean it = italic;
        long col = 0;
        boolean hasCol = false;
        int deco = 0;
        long bg = 0;
        int tag = NONE;
        String image = null;
        for (int i = 0; i < spans.count(); i++) {
            if (spans.start(i) > at || spans.end(i) <= at) {
                continue;
            }
            switch (spans.kind(i)) {
                case BOLD -> wt = 700;
                case ITALIC -> it = true;
                case UNDERLINE -> deco |= TextRuns.UNDERLINE;
                case STRIKE -> deco |= TextRuns.LINE_THROUGH;
                case COLOR -> {
                    long c = parseColor(spans.value(i));
                    if (c != 0) {
                        col = c;
                        hasCol = true;
                    }
                }
                case SIZE -> {
                    float v = parseSize(spans.value(i));
                    if (v > 0) {
                        sz = v * scale();
                    }
                }
                case HIGHLIGHT -> bg = parseColor(spans.value(i));
                case CLASS, LINK -> {
                    Element part = spans.kind(i) == Spans.Kind.LINK ? linkPart(i == hoveredLink)
                        : classPart(spans.value(i));
                    ResolvedStyle ps = partStyle(part);
                    if (ps.isSet(Styles.FONT_FAMILY)) {
                        f = ps.get(Styles.FONT_FAMILY);
                    }
                    if (ps.isSet(Styles.FONT_SIZE)) {
                        sz = ps.getFloat(Styles.FONT_SIZE) * scale();
                    }
                    if (ps.isSet(Styles.FONT_WEIGHT)) {
                        wt = ps.getInt(Styles.FONT_WEIGHT);
                    }
                    if (ps.isSet(Styles.FONT_STYLE)) {
                        it = ps.get(Styles.FONT_STYLE) != FontStyle.NORMAL;
                    }
                    if (ps.isSet(Styles.COLOR)) {
                        col = ps.rgba(Styles.COLOR);
                        hasCol = true;
                    }
                    if (ps.isSet(Styles.TEXT_DECORATION)) {
                        TextDecoration d = ps.get(Styles.TEXT_DECORATION);
                        deco = (d.underline() ? TextRuns.UNDERLINE : 0) | (d.lineThrough() ? TextRuns.LINE_THROUGH : 0);
                    }
                    if (ps.isSet(Styles.BACKGROUND)) {
                        bg = paintColor(ps, bg);
                    }
                    if (spans.kind(i) == Spans.Kind.LINK) {
                        tag = i;
                    }
                }
                case IMAGE -> image = spans.value(i);
            }
        }
        int r = runs.add(local, f, sz, wt, it);
        if (hasCol) {
            runs.color(r, col);
        }
        runs.decoration(r, deco).background(r, bg).tag(r, tag);
        if (image != null) {
            UiTexture t = ui.images().get(image);
            ResolvedStyle is = partStyle(imagePart());
            Length h = is.get(Styles.HEIGHT);
            float height = h.unit() == Length.AUTO.unit() ? sz : h.value() * scale();
            float width = t == null || t.height() == 0 ? height : height * t.width() / t.height();
            runs.object(r, width, height);
        }
    }

    @AllowAllocation("a part per span class, made when first used")
    private Element classPart(String name) {
        Element part = classParts.get(name);
        if (part == null) {
            part = keep(ui.part(element, "span", name));
            classParts.put(name, part);
        }
        return part;
    }

    @AllowAllocation("made when first used")
    private Element linkPart(boolean hover) {
        if (linkPart == null) {
            linkPart = keep(ui.part(element, "link", null));
            linkHoverPart = keep(ui.part(element, "link", null));
            linkHoverPart.state(UiState.HOVER, true);
        }
        return hover ? linkHoverPart : linkPart;
    }

    @AllowAllocation("made when first used")
    private Element imagePart() {
        if (imagePart == null) {
            imagePart = keep(ui.part(element, "img", null));
        }
        return imagePart;
    }

    private static long parseColor(String value) {
        try {
            return (value.startsWith("#") ? Color.hex(value) : Color.named(value)).rgba();
        } catch (RuntimeException e) {
            return 0; // not a colour: no change
        }
    }

    private static float parseSize(String value) {
        try {
            return Float.parseFloat(value.endsWith("px") ? value.substring(0, value.length() - 2) : value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- measure and paint

    @Override
    public long measure(int slot, float width, int widthMode, float height, int heightMode) {
        readStyle();
        float scale = scale();
        if (!multiLine) {
            prepare(INF);
            TextBlock b = shaped(0);
            float w = Math.max(b.width(), placeholderWidth()) + caretWidth();
            return Measure.size(w / scale, Math.max(b.height(), lineHeightPx()) / scale);
        }
        if (widthMode == Measure.EXACTLY && heightMode == Measure.EXACTLY) {
            return Measure.size(width, height); // a sized area: its content scrolls
        }
        float w = widthMode == Measure.UNDEFINED ? 300 * scale : width * scale;
        prepare(w - gutterWidth());
        return Measure.size(w / scale, heights.total() / scale);
    }

    private float placeholderWidth() {
        if (placeholder.isEmpty() || text.length() > 0) {
            return 0;
        }
        return placeholderBlock().width();
    }

    private TextBlock placeholderBlock() {
        if (placeholderBlock == null) {
            placeholderBlock = newBlock();
        }
        ResolvedStyle ps = partStyle(placeholderPart);
        List<String> f = ps.isSet(Styles.FONT_FAMILY) ? ps.get(Styles.FONT_FAMILY) : families;
        float sz = ps.isSet(Styles.FONT_SIZE) ? ps.getFloat(Styles.FONT_SIZE) * scale() : size;
        boolean it = ps.isSet(Styles.FONT_STYLE) ? ps.get(Styles.FONT_STYLE) != FontStyle.NORMAL : italic;
        placeholderBlock.set(placeholder, f, sz, weight, it, TextAlign.START, multiLine && wrap && wrapWidth > 0
            ? wrapWidth : INF, lineHeight);
        return placeholderBlock;
    }

    /** Lays the paragraphs out for {@code width} pixels of text (the wrap width of an area). */
    private void prepare(float width) {
        float w = multiLine && wrap ? Math.max(1, width) : INF;
        if (w != wrapWidth) {
            wrapWidth = w;
            Arrays.fill(poolPara, NONE);
            int n = text.lineCount();
            int damage = text.damageFrom();
            int damageTail = text.damageTail();
            int old = heights.count();
            text.clearDamage();
            if (n != old) {
                if (stamp.length < n) {
                    grow(n);
                }
                spellDamaged(0, 0, 1, old, n); // the lines changed: all checked again
            } else if (damage != Integer.MAX_VALUE) {
                spellDamaged(Math.min(damage, n), Math.max(0, Math.min(damageTail, n - Math.min(damage, n))), 0, old,
                    n);
            }
            if (stamp.length < n) {
                grow(n);
            }
            heights.resize(n);
            heights.ensure(n);
            float[] h = heights.heights();
            for (int p = 0; p < n; p++) {
                stamp[p] = nextStamp++;
                block[p] = NONE;
                h[p] = estimate(p);
            }
            heights.rebuild();
        } else {
            syncParagraphs();
        }
    }

    @Override
    public int spare(int recorded) {
        return recorded + 64;
    }

    @Override
    public void paint(int slot, DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        readStyle();
        float gutter = gutterWidth();
        textX0 = x0 + gutter;
        viewW = Math.max(0, x1 - textX0);
        viewH = Math.max(0, y1 - y0);
        prepare(viewW);
        layoutVisible();
        if (revealCaret) {
            revealCaret = false;
            revealCaretNow();
            layoutVisible();
        }
        spellVisible();
        float lh = lineHeightPx();
        textY0 = multiLine ? y0 : y0 + Math.max(0, (viewH - lh) * 0.5f);
        float ox = textX0 - scrollX + alignShift();
        if (gutter > 0) {
            paintGutterBox(list, x0, y0, textX0, y1, opacity);
        }
        paintSelection(list, ox, opacity);
        if (rich) {
            paintBackgrounds(list, ox, opacity);
        }
        long c = Rgba.fade(color, opacity);
        if (text.length() == 0 && !text.composing() && !placeholder.isEmpty()) {
            ResolvedStyle ps = partStyle(placeholderPart);
            long pc = Rgba.fade(ps.isSet(Styles.COLOR) ? ps.rgba(Styles.COLOR) : color, opacity);
            TextBlock pb = placeholderBlock();
            pb.recordLines(list, ox, textY0, pc, opacity, 0, pb.lineCount());
            placeholderDrawn = pb.version();
        }
        float y = paraTop(visibleFrom);
        for (int p = visibleFrom; p < visibleTo; p++) {
            TextBlock b = shaped(p);
            int first = b.lineAt(textY0 - y);
            int last = b.lineAt(textY0 + viewH - y) + 1;
            b.recordLines(list, ox, y, c, opacity, first, last);
            poolDrawn[block[p]] = b.version();
            if (rich) {
                paintDecorations(list, b, ox, y, opacity, first, last);
                paintImages(list, b, p, ox, y, opacity);
            }
            if (speller != null && spellCount[p] > 0) {
                paintSquiggles(list, b, p, ox, y, opacity, first, last);
            }
            y += heights.get(p);
        }
        paintComposition(list, ox, opacity);
        if (gutter > 0) {
            paintLineNumbers(list, x0 + gutterPad(true), opacity);
        }
        paintCaret(list, ox, opacity);
        paintHandles(list, ox, opacity);
    }

    /** Whether the touch selection handles show: a touch made the selection, and the editor has the focus. */
    public boolean touchHandles() {
        return touchSelection && text.hasSelection() && focused();
    }

    /**
     * The touch selection handles: a {@code selection-handle} part (size, background, radius) centred on the bottom
     * of the line at each end of the selection, dragged to move that end.
     */
    private void paintHandles(DrawList list, float ox, float opacity) {
        handles[0] = Float.NaN;
        handles[2] = Float.NaN;
        if (!touchHandles()) {
            return;
        }
        ResolvedStyle hs = partStyle(handlePart);
        float w = handleSize(hs.get(Styles.WIDTH));
        float h = handleSize(hs.get(Styles.HEIGHT));
        float r = hs.get(Styles.RADIUS).topLeft() * scale();
        long hc = Rgba.fade(paintColor(hs, color), opacity * hs.getFloat(Styles.OPACITY));
        for (int k = 0; k < 2; k++) {
            int at = k == 0 ? text.selectionStart() : text.selectionEnd();
            int p = text.lineOf(at);
            if (p < visibleFrom || p >= visibleTo) {
                continue;
            }
            TextBlock b = shaped(p);
            int local = at - text.lineStart(p);
            int line = b.caretLine(local);
            float x = ox + b.caretX(local);
            float y = paraTop(p) + b.lineTop(line) + b.lineHeight(line);
            list.box(x - w * 0.5f, y - h * 0.5f, x + w * 0.5f, y + h * 0.5f, hc, r, r, r, r, 0, 0);
            handles[k * 2] = x;
            handles[k * 2 + 1] = y;
        }
    }

    /** Word {@code i} of the handles' centres as last painted: start x, start y, end x, end y (NaN: not shown). */
    float handle(int i) {
        return handles[i];
    }

    /** A handle's side in pixels: the part's size, or most of a line's height when the theme gives none. */
    private float handleSize(Length l) {
        return l.unit() == Length.AUTO.unit() ? lineHeightPx() * 0.6f : l.value() * scale();
    }

    /** The handle (1 start, 2 end) a touch at {@code (x, y)} grabs, or 0: within a line height of its centre. */
    private int handleAt(float x, float y) {
        if (!touchHandles()) {
            return 0;
        }
        float reach = lineHeightPx();
        int best = 0;
        float bestD = reach * reach;
        for (int k = 0; k < 2; k++) {
            float dx = x - handles[k * 2];
            float dy = y - handles[k * 2 + 1];
            float d = dx * dx + dy * dy;
            if (d <= bestD) { // a handle not shown (NaN) never is
                bestD = d;
                best = k + 1;
            }
        }
        return best;
    }

    /** Shapes the visible paragraphs, top to bottom from the anchor, keeping their heights. */
    private void layoutVisible() {
        int n = text.lineCount();
        if (!multiLine) {
            anchor = 0;
            anchorOffset = 0;
            shaped(0);
            visibleFrom = 0;
            visibleTo = 1;
            return;
        }
        float total = heights.total();
        float scrollY = clampScroll(heights.top(anchor) + anchorOffset, total);
        anchor = heights.at(scrollY);
        anchorOffset = scrollY - heights.top(anchor);
        float y = -anchorOffset;
        int p = anchor;
        while (p < n && (y < viewH || p == anchor)) {
            shaped(p);
            y += heights.get(p);
            p++;
        }
        visibleFrom = anchor;
        visibleTo = p;
    }

    private float clampScroll(float y, float total) {
        return Math.max(0, Math.min(y, Math.max(0, total - viewH)));
    }

    /** The top of paragraph {@code p} on screen. */
    private float paraTop(int p) {
        if (!multiLine) {
            return textY0;
        }
        return textY0 + heights.top(p) - (heights.top(anchor) + anchorOffset);
    }

    /** A field's shift for its {@code text-align} while its text fits. */
    private float alignShift() {
        if (multiLine) {
            return 0;
        }
        TextBlock b = shaped(0);
        float w = text.length() == 0 && !placeholder.isEmpty() ? placeholderWidth() : b.width();
        if (w >= viewW) {
            return 0;
        }
        return (viewW - w - caretWidth()) * align.leftShare(b.lineCount() > 0 && b.lineRightToLeft(0));
    }

    /** Scrolls so the caret is in view (with a margin of the caret part's horizontal margin). */
    private void revealCaretNow() {
        int p = text.lineOf(text.caret());
        TextBlock b = shaped(p);
        int local = text.caret() - text.lineStart(p);
        float cx = b.caretX(local);
        float margin = partStyle(caretPart).get(Styles.MARGIN).left() * scale();
        if (cx - scrollX < margin) {
            scrollX = Math.max(0, cx - margin);
        } else if (cx - scrollX > viewW - margin - caretWidth()) {
            scrollX = cx - viewW + margin + caretWidth();
        }
        float maxScroll = Math.max(0, b.width() + caretWidth() - viewW);
        if (!multiLine || !wrap) {
            scrollX = Math.min(scrollX, Math.max(maxScroll, 0));
        } else {
            scrollX = 0;
        }
        if (multiLine) {
            int line = b.caretLine(local);
            float top = heights.top(p) + b.lineTop(line);
            float bottom = top + b.lineHeight(line);
            float scrollY = heights.top(anchor) + anchorOffset;
            if (top < scrollY) {
                scrollTo(top);
            } else if (bottom > scrollY + viewH) {
                scrollTo(bottom - viewH);
            }
        }
    }

    /** Scrolls an area so {@code y} pixels of its content are at the top. */
    private void scrollTo(float y) {
        float clamped = clampScroll(y, heights.total());
        anchor = heights.at(clamped);
        anchorOffset = clamped - heights.top(anchor);
    }

    /** Scrolls an area by {@code dy} pixels (positive: down the text); true if it moved. */
    public boolean scrollBy(float dy) {
        float before = heights.top(anchor) + anchorOffset;
        scrollTo(before + dy);
        boolean moved = heights.top(anchor) + anchorOffset != before;
        if (moved) {
            ui.repaint(element);
        }
        return moved;
    }

    /** The scroll position of an area: pixels of content above the view. */
    public float scrollY() {
        return heights.top(anchor) + anchorOffset;
    }

    /** The content's height, pixels (estimated for paragraphs not yet shaped). */
    public float contentHeight() {
        return heights.total();
    }

    /** A field's sideways scroll, pixels. */
    public float scrollX() {
        return scrollX;
    }

    private float caretWidth() {
        Length w = partStyle(caretPart).get(Styles.WIDTH);
        return w.unit() == Length.AUTO.unit() ? scale() : w.value() * scale();
    }

    private void paintCaret(DrawList list, float ox, float opacity) {
        if (!focused() || readOnly() || !blinkOn && CARET_BLINK.get() > 0) {
            updateImeArea(ox);
            return;
        }
        ResolvedStyle cs = partStyle(caretPart);
        int p = text.lineOf(text.caret());
        if (p < visibleFrom || p >= visibleTo) {
            return;
        }
        TextBlock b = shaped(p);
        int local = text.caret() - text.lineStart(p);
        int line = b.caretLine(local);
        float x = ox + b.caretX(local);
        float top = paraTop(p) + b.lineTop(line);
        float w = caretWidth();
        float r = cs.get(Styles.RADIUS).topLeft() * scale();
        long cc = Rgba.fade(paintColor(cs, color), opacity * cs.getFloat(Styles.OPACITY));
        list.box(x - w * 0.5f, top, x + w * 0.5f, top + b.lineHeight(line), cc, r, r, r, r, 0, 0);
        updateImeArea(ox);
    }

    /** Puts the IME's candidate window at the caret. */
    private void updateImeArea(float ox) {
        if (interaction.textOwner() != element) {
            return;
        }
        int p = text.lineOf(text.caret());
        if (p < visibleFrom || p >= visibleTo) {
            return;
        }
        TextBlock b = shaped(p);
        int local = text.caret() - text.lineStart(p);
        int line = b.caretLine(local);
        float x = ox + b.caretX(local);
        float top = paraTop(p) + b.lineTop(line);
        float s = scale();
        interaction.imeArea(element, x / s, top / s, caretWidth() / s, b.lineHeight(line) / s);
    }

    private void paintSelection(DrawList list, float ox, float opacity) {
        if (!text.hasSelection()) {
            return;
        }
        ResolvedStyle ss = partStyle(selectionPart);
        long sc = Rgba.fade(paintColor(ss, Rgba.pack(0.2f, 0.4f, 1, 0.4f)), opacity * ss.getFloat(Styles.OPACITY));
        float r = ss.get(Styles.RADIUS).topLeft() * scale();
        int a = text.selectionStart();
        int z = text.selectionEnd();
        for (int p = visibleFrom; p < visibleTo; p++) {
            int s = text.lineStart(p);
            int e = text.lineEnd(p);
            if (z < s || a > e || a == z) {
                continue;
            }
            TextBlock b = shaped(p);
            float top = paraTop(p);
            int n = b.rangeBoxes(Math.max(a, s) - s, Math.min(z, e) - s, 0, boxes);
            for (int k = 0; k < Math.min(n, boxes.length / 4); k++) {
                list.box(ox + boxes[k * 4], top + boxes[k * 4 + 1], ox + boxes[k * 4 + 2], top + boxes[k * 4 + 3], sc,
                    r, r, r, r, 0, 0);
            }
            if (z > e && multiLine) { // the selected line break
                int last = b.lineCount() - 1;
                float x = ox + (e > s ? b.caretX(e - s) : b.lineLeft(last));
                float bw = size * 0.35f;
                list.box(x, top + b.lineTop(last), x + bw, top + b.lineTop(last) + b.lineHeight(last), sc, r, r, r,
                    r, 0, 0);
            }
        }
    }

    private void paintBackgrounds(DrawList list, float ox, float opacity) {
        for (int p = visibleFrom; p < visibleTo; p++) {
            TextBlock b = shaped(p);
            TextRuns runs = b.runs();
            if (runs == null) {
                continue;
            }
            float top = paraTop(p);
            for (int r = 0; r < runs.count(); r++) {
                if (runs.background(r) == 0) {
                    continue;
                }
                int end = r + 1 < runs.count() ? runs.start(r + 1) : b.length();
                int n = b.rangeBoxes(runs.start(r), end, 0, boxes);
                long bc = Rgba.fade(runs.background(r), opacity);
                for (int k = 0; k < Math.min(n, boxes.length / 4); k++) {
                    list.box(ox + boxes[k * 4], top + boxes[k * 4 + 1], ox + boxes[k * 4 + 2], top + boxes[k * 4 + 3],
                        bc, 0, 0, 0, 0, 0, 0);
                }
            }
        }
    }

    /** Underlines and lines through of the runs on lines {@code [first, last)}. */
    private void paintDecorations(DrawList list, TextBlock b, float ox, float top, float opacity, int first,
        int last) {
        TextRuns runs = b.runs();
        if (runs == null) {
            return;
        }
        for (int r = 0; r < runs.count(); r++) {
            int deco = runs.decoration(r);
            if (deco == 0) {
                continue;
            }
            int end = r + 1 < runs.count() ? runs.start(r + 1) : b.length();
            float sz = runs.size(r);
            float thick = Math.max(1, sz * 0.07f);
            long dc = Rgba.fade(runs.hasColor(r) ? runs.color(r) : color, opacity);
            int n = b.rangeBoxes(runs.start(r), end, 0, boxes);
            for (int k = 0; k < Math.min(n, boxes.length / 4); k++) {
                int line = b.lineAt(boxes[k * 4 + 1] + 0.5f);
                if (line < first || line >= last) {
                    continue;
                }
                float base = top + b.lineBaseline(line);
                if ((deco & TextRuns.UNDERLINE) != 0) {
                    float y = base + sz * 0.12f;
                    list.box(ox + boxes[k * 4], y, ox + boxes[k * 4 + 2], y + thick, dc, 0, 0, 0, 0, 0, 0);
                }
                if ((deco & TextRuns.LINE_THROUGH) != 0) {
                    float y = base - sz * 0.3f;
                    list.box(ox + boxes[k * 4], y, ox + boxes[k * 4 + 2], y + thick, dc, 0, 0, 0, 0, 0, 0);
                }
            }
        }
    }

    private void paintImages(DrawList list, TextBlock b, int p, float ox, float top, float opacity) {
        TextRuns runs = b.runs();
        if (runs == null) {
            return;
        }
        Spans spans = text.spans();
        int s = text.lineStart(p);
        for (int r = 0; r < runs.count(); r++) {
            if (!runs.isObject(r)) {
                continue;
            }
            int i = runs.start(r);
            int span = spans.find(Spans.Kind.IMAGE, s + i);
            UiTexture t = span < 0 ? null : ui.images().get(spans.value(span));
            if (t == null) {
                continue;
            }
            int line = b.caretLine(i);
            float x = ox + Math.min(b.caretX(i), b.caretX(i + 1));
            float base = top + b.lineBaseline(line);
            list.image(x, base - runs.objectHeight(r), x + runs.objectWidth(r), base, 0, 0, 1, 1, t.id(),
                Rgba.fade(Rgba.WHITE, opacity));
        }
    }

    private void paintComposition(DrawList list, float ox, float opacity) {
        if (!text.composing()) {
            return;
        }
        ResolvedStyle cs = partStyle(compositionPart);
        float thick = Math.max(1, cs.get(Styles.BORDER).width() * scale());
        long cc = Rgba.fade(cs.get(Styles.BORDER).width() > 0 ? cs.get(Styles.BORDER).color().rgba() : color,
            opacity);
        long bg = paintColor(cs, 0);
        int a = text.compositionStart();
        int z = text.compositionEnd();
        int p = text.lineOf(a);
        if (p < visibleFrom || p >= visibleTo) {
            return;
        }
        TextBlock b = shaped(p);
        int s = text.lineStart(p);
        float top = paraTop(p);
        int n = b.rangeBoxes(a - s, Math.min(z, text.lineEnd(p)) - s, 0, boxes);
        for (int k = 0; k < Math.min(n, boxes.length / 4); k++) {
            int line = b.lineAt(boxes[k * 4 + 1] + 0.5f);
            float y = top + b.lineBaseline(line) + size * 0.12f;
            if (bg != 0) {
                list.box(ox + boxes[k * 4], top + boxes[k * 4 + 1], ox + boxes[k * 4 + 2], top + boxes[k * 4 + 3],
                    Rgba.fade(bg, opacity), 0, 0, 0, 0, 0, 0);
            }
            list.box(ox + boxes[k * 4], y, ox + boxes[k * 4 + 2], y + thick, cc, 0, 0, 0, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------- line numbers

    private TextBlock[] numbers = new TextBlock[0];
    private final char[] digits = new char[12];
    private final Slice digitSlice = new Slice();
    private final DigitText digitText = new DigitText();

    /** The digits of a number as a reused char sequence. */
    private final class DigitText implements CharSequence {
        int from;

        DigitText set(int value) {
            int v = value;
            int i = digits.length;
            do {
                digits[--i] = (char) ('0' + v % 10);
                v /= 10;
            } while (v > 0);
            from = i;
            return this;
        }

        @Override
        public int length() {
            return digits.length - from;
        }

        @Override
        public char charAt(int index) {
            return digits[from + index];
        }

        @Override
        @AllowAllocation("a copy, when asked for")
        public CharSequence subSequence(int start, int end) {
            return new String(digits, from + start, end - start);
        }

        @Override
        @AllowAllocation("a copy, when asked for")
        public String toString() {
            return new String(digits, from, length());
        }
    }

    private float gutterWidth() {
        if (!lineNumbers || gutterPart == null) {
            return 0;
        }
        int n = text.lineCount();
        int d = 1;
        while (n >= 10) {
            n /= 10;
            d++;
        }
        return Math.max(2, d) * gutterSize() * 0.62f + gutterPad(true) + gutterPad(false);
    }

    private float gutterSize() {
        ResolvedStyle gs = partStyle(gutterPart);
        return gs.isSet(Styles.FONT_SIZE) ? gs.getFloat(Styles.FONT_SIZE) * scale() : size;
    }

    private float gutterPad(boolean left) {
        Insets pad = partStyle(gutterPart).get(Styles.PADDING);
        return (left ? pad.left() : pad.right()) * scale();
    }

    private void paintGutterBox(DrawList list, float x0, float y0, float x1, float y1, float opacity) {
        long bg = paintColor(partStyle(gutterPart), 0);
        if (bg != 0) {
            list.box(x0, y0, x1, y1, Rgba.fade(bg, opacity), 0, 0, 0, 0, 0, 0);
        }
    }

    private void paintLineNumbers(DrawList list, float x, float opacity) {
        ResolvedStyle gs = partStyle(gutterPart);
        long nc = Rgba.fade(gs.isSet(Styles.COLOR) ? gs.rgba(Styles.COLOR) : color, opacity);
        float right = textX0 - gutterPad(false);
        if (numbers.length < visibleTo - visibleFrom) {
            growNumbers(visibleTo - visibleFrom);
        }
        for (int p = visibleFrom; p < visibleTo; p++) {
            TextBlock nb = numbers[p - visibleFrom];
            nb.set(digitText.set(p + 1), families, gutterSize(), weight, false, TextAlign.START, INF, 0);
            TextBlock b = shaped(p);
            float baseline = paraTop(p) + (b.lineCount() > 0 ? b.lineBaseline(0) : 0);
            nb.recordLines(list, Math.max(x, right - nb.width()), baseline - nb.baseline(), nc, opacity, 0, 1);
        }
    }

    @AllowAllocation("growing with the view, rarely")
    private void growNumbers(int n) {
        int old = numbers.length;
        numbers = Arrays.copyOf(numbers, Math.max(n, old * 2));
        for (int i = old; i < numbers.length; i++) {
            numbers[i] = newBlock();
        }
    }

    // ---------------------------------------------------------------- frame

    /** The caret's blink, from the frame's clock. */
    @Override
    public void frame(long nanos) {
        now = nanos;
        if (glyphsArrived()) {
            ui.repaint(element);
        }
        if (speller != null && !password() && wrapWidth >= 0) {
            spellStep();
        }
        if (text.selectionVersion() != selectionSeen) {
            changed(false); // the text or selection was changed through editable() by code
        }
        if (!focused() || readOnly()) {
            return;
        }
        float period = CARET_BLINK.get();
        boolean on = period <= 0 || (nanos - blinkStart) / (long) (period * 1e9f) % 2 == 0;
        if (on != blinkOn) {
            blinkOn = on;
            ui.repaint(element);
        }
    }

    // ---------------------------------------------------------------- spelling

    /** Everything is checked again (the dictionary, the ignored words or the settings changed). */
    private void spellAll() {
        spellVersionSeen = speller.version();
        int n = Math.min(heights.count(), spellDirty.length);
        for (int p = 0; p < n; p++) {
            spellDirty[p] = true;
            spellCount[p] = 0;
        }
        spellPending = true;
        spellCursor = 0;
    }

    /** Checks paragraph {@code p} now. */
    private void checkParagraph(int p) {
        spellPara = p;
        spellCount[p] = 0;
        speller.check(text.text(), text.lineStart(p), text.lineEnd(p), collector);
        spellDirty[p] = false;
        spellChecks++;
    }

    private void misspelt(int start, int end) {
        int p = spellPara;
        int s = text.lineStart(p);
        int c = spellCount[p];
        int[] a = spellErrors[p];
        if (a == null || a.length < c * 2 + 2) {
            a = growErrors(a, c * 2 + 2);
            spellErrors[p] = a;
        }
        a[c * 2] = start - s;
        a[c * 2 + 1] = end - s;
        spellCount[p] = c + 1;
    }

    @AllowAllocation("a paragraph's misspelt words outgrow their array, rarely")
    private static int[] growErrors(int[] a, int n) {
        return a == null ? new int[Math.max(8, n)] : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }

    /** The visible paragraphs not checked yet are checked as they are drawn (no frame waits for them). */
    private void spellVisible() {
        if (speller == null || password()) {
            return;
        }
        if (speller.version() != spellVersionSeen) {
            spellAll();
        }
        for (int p = visibleFrom; p < visibleTo && p < text.lineCount(); p++) {
            if (spellDirty[p]) {
                checkParagraph(p);
            }
        }
    }

    /** Checks paragraphs out of view within the frame's budget. */
    private void spellStep() {
        if (speller.version() != spellVersionSeen) {
            spellAll();
            ui.repaint(element);
        }
        int n = heights.count();
        if (!spellPending || text.damageFrom() != Integer.MAX_VALUE || text.lineCount() != n) {
            return; // edits not laid out yet: the visible ones are checked when drawn, the rest next frame
        }
        long deadline = System.nanoTime() + (long) (SPELL_BUDGET.get() * 1e6f);
        for (int scanned = 0; scanned < n; scanned++) {
            if (spellCursor >= n) {
                spellCursor = 0;
            }
            int p = spellCursor;
            if (spellDirty[p]) {
                checkParagraph(p);
                if (p >= visibleFrom && p < visibleTo) {
                    ui.repaint(element);
                }
                if (System.nanoTime() >= deadline) {
                    return;
                }
            } else if ((scanned & 4095) == 4095 && System.nanoTime() >= deadline) {
                return;
            }
            spellCursor++;
        }
        spellPending = false;
    }

    /** The squiggles under paragraph {@code p}'s misspelt words, on its lines {@code [first, last)}. */
    private void paintSquiggles(DrawList list, TextBlock b, int p, float ox, float top, float opacity, int first,
        int last) {
        ResolvedStyle ms = partStyle(misspeltPart);
        long col = Rgba.fade(ms.isSet(Styles.COLOR) ? ms.rgba(Styles.COLOR) : color, opacity);
        float sc = scale();
        float wave = ms.getFloat(TextStyles.SQUIGGLE_WAVELENGTH) * sc;
        float amp = ms.getFloat(TextStyles.SQUIGGLE_AMPLITUDE) * sc;
        float thick = Math.max(0.5f, ms.getFloat(TextStyles.SQUIGGLE_THICKNESS) * sc);
        int caretLocal = -1;
        if (focused() && !text.hasSelection() && text.lineOf(text.caret()) == p) {
            caretLocal = text.caret() - text.lineStart(p); // the word being typed is not marked yet
        }
        int[] errs = spellErrors[p];
        for (int k = 0; k < spellCount[p]; k++) {
            int a = errs[k * 2];
            int z = errs[k * 2 + 1];
            if (z == caretLocal || z > b.length()) {
                continue;
            }
            int n = Math.min(b.rangeBoxes(a, z, 0, boxes), boxes.length / 4);
            for (int i = 0; i < n; i++) {
                int line = b.lineAt(boxes[i * 4 + 1] + 0.5f);
                if (line < first || line >= last) {
                    continue;
                }
                float base = top + b.lineBaseline(line) + Math.max(1, size * 0.12f);
                float x0 = ox + boxes[i * 4];
                float x1 = ox + boxes[i * 4 + 2];
                if (wave <= 0 || amp <= 0) {
                    list.box(x0, base, x1, base + thick, col, 0, 0, 0, 0, 0, 0);
                    continue;
                }
                float half = wave * 0.5f;
                boolean up = true;
                for (float x = x0; x < x1; x += half) {
                    float nx = Math.min(x1, x + half);
                    float t = (nx - x) / half;
                    list.line(x, base + (up ? amp : 0), nx, base + (up ? amp - amp * t : amp * t), thick, col);
                    up = !up;
                }
            }
        }
    }

    /** The misspelt word at text index {@code index} as {@code start << 32 | end}, or -1. */
    private long misspeltAt(int index) {
        int p = text.lineOf(index);
        if (spellDirty[p]) {
            checkParagraph(p);
        }
        int local = index - text.lineStart(p);
        int[] errs = spellErrors[p];
        for (int k = 0; k < spellCount[p]; k++) {
            if (local >= errs[k * 2] && local <= errs[k * 2 + 1]) {
                int s = text.lineStart(p);
                return (long) (s + errs[k * 2]) << 32 | (s + errs[k * 2 + 1]);
            }
        }
        return -1;
    }

    /** The context menu on a misspelt word: its corrections, add to the dictionary, ignore. */
    @AllowAllocation("the spelling menu is filled when it opens")
    private void spellContext(UiEvent e) {
        if (speller == null || spellControls == null || password() || readOnly() || !editable) {
            return;
        }
        boolean pointer = e.pointerId() >= 0;
        int index = pointer ? hit(e.x(), e.y()) : text.caret();
        long word = misspeltAt(index);
        if (word < 0) {
            return;
        }
        e.consume();
        int start = (int) (word >>> 32);
        int end = (int) word;
        String w = text.text().subSequence(start, end).toString();
        if (pointer && (!text.hasSelection() || index < text.selectionStart() || index > text.selectionEnd())) {
            text.moveTo(index, false);
            changed(false);
        }
        if (spellMenu == null) {
            spellMenu = spellControls.menu("Spelling");
        }
        spellMenu.clear();
        List<String> suggestions = speller.suggest(w);
        for (String s : suggestions) {
            spellMenu.item(s.replace("&", "&&"), () -> replaceWord(start, end, w, s));
        }
        if (suggestions.isEmpty()) {
            spellMenu.item(spellNone, () -> { }).enabled(false);
        }
        spellMenu.separator();
        spellMenu.item(spellAdd, () -> speller.addToDictionary(w));
        spellMenu.item(spellIgnore, () -> speller.ignore(w));
        if (pointer) {
            spellMenu.openAt(e.x(), e.y());
            return;
        }
        int p = text.lineOf(start); // from the keyboard: below the word
        if (p >= visibleFrom && p < visibleTo) {
            TextBlock b = shaped(p);
            int local = start - text.lineStart(p);
            int line = b.caretLine(local);
            spellMenu.openAt(textX0 - scrollX + alignShift() + b.caretX(local),
                paraTop(p) + b.lineTop(line) + b.lineHeight(line));
        } else {
            spellMenu.open(element, true);
        }
    }

    /** Replaces the misspelt {@code word} at {@code [start, end)} by {@code with} (if the text there is unchanged). */
    @AllowAllocation("a user action")
    private void replaceWord(int start, int end, String word, String with) {
        if (end > text.length() || !text.text().subSequence(start, end).toString().equals(word)) {
            return;
        }
        text.replace(start, end, with);
        text.moveTo(start + with.length(), false);
        changed(true);
        interaction.focus(element);
    }

    /** Whether glyphs drawn as placeholders became ready since the last record (a block's version moved). */
    private boolean glyphsArrived() {
        for (int p = visibleFrom; p < visibleTo && p < heights.count(); p++) {
            int b = block[p];
            if (b != NONE && poolPara[b] == p && pool[b].version() != poolDrawn[b]) {
                return true;
            }
        }
        return placeholderBlock != null && text.length() == 0 && placeholderBlock.version() != placeholderDrawn;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void handle(UiEvent e) {
        now = e.time();
        text.now(e.time());
        switch (e.type()) {
            case COMMAND -> command(e);
            case TEXT -> typed(e);
            case COMPOSITION -> {
                if (!readOnly() && !password()) {
                    text.compose(e.text(), e.compositionCursor());
                    changed(true);
                }
                e.consume();
            }
            case PRESS -> press(e);
            case MOVE -> move(e);
            case RELEASE -> release(e);
            case WHEEL -> wheel(e);
            case LONG_PRESS -> {
                selectUnit(hit(e.x(), e.y()), 2);
                touchSelection = e.pointerKind() == Pointer.Kind.TOUCH; // with handles to drag its ends
                touchMoved = true; // no caret placing on release
                e.consume();
            }
            case DRAG_START -> e.consume(); // dragging selects text: no drag session
            case FOCUS_IN -> {
                blinkStart = now;
                blinkOn = true;
                if (e.focusVisible() && !multiLine) {
                    text.selectAll();
                }
                changed(false);
            }
            case FOCUS_OUT -> {
                text.endComposition(true);
                changed(true);
                if (listener != null) {
                    listener.left(this);
                }
            }
            case EXIT -> hoverLink(NONE);
            case CONTEXT -> spellContext(e);
            default -> {
            }
        }
    }

    private void typed(UiEvent e) {
        e.consume();
        touchSelection = false;
        if (readOnly()) {
            return;
        }
        text.type(e.text());
        int choice = (int) EMOJI_REPLACEMENT.get();
        if (choice != 0 && !password()) {
            Emoticons.builtIn().apply(text, choice == 1 || choice == 3, choice >= 2);
        }
        changed(true);
    }

    private void command(UiEvent e) {
        UiCommand c = e.command();
        boolean extend = (e.commandFlags() & UiInput.EXTEND_SELECTION) != 0;
        boolean handled = true;
        boolean edit = false;
        float keepGoal = Float.NaN;
        switch (c) {
            case LEFT, RIGHT -> {
                boolean right = c == UiCommand.RIGHT;
                if (text.hasSelection() && !extend) {
                    text.moveTo(right == visualRightIsForward() ? text.selectionEnd() : text.selectionStart(), false);
                } else if (CARET_MOVEMENT.index() == 0 && !masked()) {
                    text.moveTo(visualMove(right), extend);
                } else {
                    text.moveLogically(right, false, extend);
                }
            }
            case WORD_LEFT, WORD_RIGHT -> text.moveLogically(c == UiCommand.WORD_RIGHT, true, extend);
            case LINE_START, HOME -> text.moveTo(lineEdge(false), extend);
            case LINE_END, END -> text.moveTo(lineEdge(true), extend);
            case DOC_START -> text.moveTo(0, extend);
            case DOC_END -> text.moveTo(text.length(), extend);
            case UP, DOWN, PAGE_UP, PAGE_DOWN -> {
                if (!multiLine) {
                    handled = false;
                } else {
                    float page = Math.max(lineHeightPx(), viewH - lineHeightPx());
                    float dy = switch (c) {
                        case UP -> -1;
                        case DOWN -> 1;
                        case PAGE_UP -> -page;
                        default -> page;
                    };
                    keepGoal = vertical(dy, extend, c == UiCommand.PAGE_UP || c == UiCommand.PAGE_DOWN);
                }
            }
            case SELECT_ALL -> text.selectAll();
            case COPY -> copy();
            case CUT -> {
                if (!readOnly() && !password() && text.hasSelection()) {
                    copy();
                    text.deleteBack(false);
                    edit = true;
                }
            }
            case PASTE -> {
                if (!readOnly()) {
                    paste();
                    edit = true;
                }
            }
            case UNDO -> edit = !readOnly() && text.undo();
            case REDO -> edit = !readOnly() && text.redo();
            case DELETE_BACK -> edit = !readOnly() && (password() ? text.deleteBackCluster() : text.deleteBack(false));
            case DELETE_FORWARD -> edit = !readOnly() && text.deleteForward(false);
            case DELETE_WORD_BACK -> edit = !readOnly() && text.deleteBack(!password());
            case DELETE_WORD_FORWARD -> edit = !readOnly() && text.deleteForward(!password());
            case SUBMIT -> {
                if (multiLine && !submitOnEnter) {
                    edit = !readOnly() && text.type("\n");
                } else {
                    if (listener != null) {
                        listener.submitted(this);
                    }
                    handled = !passSubmit;
                }
            }
            case NEWLINE -> {
                if (multiLine) {
                    edit = !readOnly() && text.type("\n");
                } else {
                    handled = false;
                }
            }
            case NEXT -> {
                if (multiLine && !readOnly() && AREA_TAB.index() == 0) {
                    edit = text.type("\t");
                } else {
                    handled = false;
                }
            }
            case ACCEPT -> {
                // typed Space and Enter arrive as text and SUBMIT: the accept click does not apply to text
            }
            default -> handled = false;
        }
        if (handled) {
            e.consume();
            changed(edit);
            goalX = keepGoal;
        }
    }

    /** Whether, in the caret's line, moving right goes forward in the text. */
    private boolean visualRightIsForward() {
        int p = text.lineOf(text.caret());
        TextBlock b = shaped(p);
        return b.lineCount() == 0 || !b.lineRightToLeft(0);
    }

    /** One visual step left or right, across paragraphs at their ends. */
    private int visualMove(boolean right) {
        int caret = text.caret();
        int p = text.lineOf(caret);
        int s = text.lineStart(p);
        TextBlock b = shaped(p);
        int local = caret - s;
        int moved = b.moveVisually(local, right);
        if (moved != local) {
            return s + moved;
        }
        boolean forward = right != (b.lineCount() > 0 && b.lineRightToLeft(b.caretLine(local)));
        if (forward && p + 1 < text.lineCount()) {
            return text.lineStart(p + 1);
        }
        if (!forward && p > 0) {
            return text.lineEnd(p - 1);
        }
        return caret;
    }

    /** The start or end of the caret's visual line (a wrapped line, or the paragraph). */
    private int lineEdge(boolean end) {
        int caret = text.caret();
        int p = text.lineOf(caret);
        int s = text.lineStart(p);
        TextBlock b = shaped(p);
        int line = b.caretLine(caret - s);
        if (end) {
            int e = b.lineEnd(line);
            boolean soft = line + 1 < b.lineCount();
            // at a soft break the end of the line is before its trailing space, so the caret stays on it
            while (soft && e > b.lineStart(line) && Character.isWhitespace(text.text().charAt(s + e - 1))) {
                e--;
            }
            return s + e;
        }
        return s + b.lineStart(line);
    }

    /**
     * Moves the caret {@code dy} lines (or pixels, for a page) up or down keeping its column; returns the column to
     * keep for the next move.
     */
    private float vertical(float dy, boolean extend, boolean page) {
        int caret = text.caret();
        int p = text.lineOf(caret);
        int s = text.lineStart(p);
        TextBlock b = shaped(p);
        int local = caret - s;
        float x = Float.isNaN(goalX) ? b.caretX(local) : goalX;
        int line = b.caretLine(local);
        float y = heights.top(p) + b.lineTop(line) + b.lineHeight(line) * 0.5f;
        float target = page ? y + dy : (dy < 0 ? heights.top(p) + b.lineTop(line) - 1
            : heights.top(p) + b.lineTop(line) + b.lineHeight(line) + 1);
        if (target < 0) {
            text.moveTo(0, extend);
            return x;
        }
        if (target >= heights.total()) {
            text.moveTo(text.length(), extend);
            return x;
        }
        int q = heights.at(target);
        TextBlock qb = shaped(q);
        int hit = qb.hitTest(x, target - heights.top(q));
        text.moveTo(text.lineStart(q) + hit, extend);
        if (page) {
            scrollBy(dy);
        }
        return x;
    }

    // ---------------------------------------------------------------- clipboard

    private void copy() {
        if (!text.hasSelection() || password()) {
            return;
        }
        copiedText = text.selectedText();
        text.spans().copy(text.selectionStart(), text.selectionEnd(), copiedSpans);
        interaction.clipboard().text(copiedText);
    }

    private void paste() {
        String value = interaction.clipboard().text();
        if (value.isEmpty()) {
            return;
        }
        if (rich && value.equals(copiedText)) {
            fragment.set(copiedSpans);
            text.paste(value, fragment);
        } else {
            text.paste(value, null);
        }
    }

    // ---------------------------------------------------------------- pointer

    /** The text index at screen point {@code (x, y)} from the last paint. */
    private int hit(float x, float y) {
        float ox = textX0 - scrollX + alignShift();
        if (!multiLine) {
            return onCluster(shaped(0).hitTest(x - ox, 0));
        }
        float contentY = y - textY0 + heights.top(anchor) + anchorOffset;
        int p = heights.at(Math.max(0, contentY));
        TextBlock b = shaped(p);
        return text.lineStart(p) + b.hitTest(x - ox, contentY - heights.top(p));
    }

    /**
     * {@code index}, or the start of the grapheme cluster it falls in while the text is masked: a masked cluster is one
     * mask glyph, so the caret never stops inside it.
     */
    private int onCluster(int index) {
        if (!masked() || index <= 0 || index >= text.length()) {
            return index;
        }
        int start = text.previousGrapheme(index);
        return text.nextGrapheme(start) == index ? index : start;
    }

    private void press(UiEvent e) {
        if (e.button() != Pointer.PRIMARY) {
            return;
        }
        e.consume(); // the interaction captures the pointer for the element that consumed its press
        int link = linkAt(e.x(), e.y());
        pressedLink = link;
        if (e.pointerKind() == Pointer.Kind.TOUCH) {
            draggedHandle = handleAt(e.x(), e.y());
            if (draggedHandle != 0) { // its end follows the touch, kept where it was grabbed from
                int k = draggedHandle - 1;
                grabX = e.x() - handles[k * 2];
                grabY = e.y() - handles[k * 2 + 1] + lineHeightPx() * 0.5f;
                return;
            }
            touchPress = true;
            touchMoved = false;
            touchX = e.x();
            touchY = e.y();
            return;
        }
        touchPress = false;
        touchSelection = false; // a mouse selects without handles
        float dist = Interaction.DOUBLE_CLICK_DISTANCE.get();
        boolean again = e.time() - lastPress <= (long) (Interaction.DOUBLE_CLICK_TIME.get() * 1e9f)
            && Math.abs(e.x() - lastPressX) <= dist && Math.abs(e.y() - lastPressY) <= dist;
        clicks = again ? clicks % 3 + 1 : 1;
        lastPress = e.time();
        lastPressX = e.x();
        lastPressY = e.y();
        int at = hit(e.x(), e.y());
        if (clicks == 1) {
            boolean extend = (e.modifiers() & Pointer.SHIFT) != 0;
            text.moveTo(at, extend);
            unitStart = unitEnd = extend ? text.anchor() : at;
        } else {
            selectUnit(at, clicks);
        }
        selecting = true;
        changed(false);
    }

    /** Selects the word ({@code unit} 2) or line (3) at {@code at}, which a drag then extends by. */
    private void selectUnit(int at, int unit) {
        // a masked password shows no words: a double click selects it all
        long range = masked() ? text.length() : unit == 2 ? text.wordAt(at) : text.lineAt(at);
        unitStart = (int) (range >>> 32);
        unitEnd = (int) range;
        text.select(unitStart, unitEnd);
        clicks = unit;
        changed(false);
    }

    private void move(UiEvent e) {
        if (draggedHandle != 0) {
            if ((e.buttons() & Pointer.PRIMARY) != 0) {
                int at = hit(e.x() - grabX, e.y() - grabY);
                int other = draggedHandle == 1 ? text.selectionEnd() : text.selectionStart();
                if (at != other) {
                    text.select(other, at);
                    draggedHandle = at < other ? 1 : 2; // dragged past the other end: it is the start now
                    changed(false);
                }
                e.consume();
            }
            return;
        }
        if (touchPress) {
            if ((e.buttons() & Pointer.PRIMARY) != 0) {
                float dy = touchY - e.y();
                float dx = touchX - e.x();
                if (Math.abs(dy) + Math.abs(dx) > Interaction.TOUCH_DRAG_THRESHOLD.get() || touchMoved) {
                    touchMoved = true;
                    if (multiLine) {
                        scrollBy(dy);
                    } else {
                        scrollX = Math.max(0, scrollX + dx);
                        ui.repaint(element);
                    }
                    touchY = e.y();
                    touchX = e.x();
                }
                e.consume();
            }
            return;
        }
        if (selecting && (e.buttons() & Pointer.PRIMARY) != 0) {
            int at = hit(e.x(), e.y());
            if (clicks <= 1) {
                text.moveTo(at, true);
            } else {
                long range = clicks == 2 ? text.wordAt(at) : text.lineAt(at);
                int s = (int) (range >>> 32);
                int en = (int) range;
                if (s < unitStart) {
                    text.select(unitEnd, s);
                } else {
                    text.select(unitStart, Math.max(en, unitEnd));
                }
            }
            if (multiLine && (e.y() < textY0 || e.y() > textY0 + viewH)) {
                scrollBy(e.y() < textY0 ? e.y() - textY0 : e.y() - textY0 - viewH); // drag past the edge
            }
            changed(false);
            e.consume();
            return;
        }
        hoverLink(linkAt(e.x(), e.y()));
    }

    private void release(UiEvent e) {
        if (e.button() != Pointer.PRIMARY) {
            return;
        }
        e.consume();
        if (draggedHandle != 0) {
            draggedHandle = 0;
            return;
        }
        if (touchPress && !touchMoved) {
            text.moveTo(hit(e.x(), e.y()), false);
            touchSelection = false;
            changed(false);
        }
        int link = linkAt(e.x(), e.y());
        if (link != NONE && link == pressedLink && !text.hasSelection() && listener != null) {
            listener.link(this, text.spans().value(link));
        }
        selecting = false;
        touchPress = false;
        pressedLink = NONE;
    }

    private void wheel(UiEvent e) {
        boolean moved;
        if (multiLine) {
            moved = scrollBy(-e.scrollY() * lineHeightPx() * 3);
        } else {
            float before = scrollX;
            TextBlock b = shaped(0);
            scrollX = Math.max(0, Math.min(scrollX - (e.scrollX() + e.scrollY()) * size * 2,
                Math.max(0, b.width() + caretWidth() - viewW)));
            moved = scrollX != before;
            if (moved) {
                ui.repaint(element);
            }
        }
        if (moved) {
            e.consume();
        }
    }

    /** The link span under the point, or none; without hit tests' break iterators: hovering allocates nothing. */
    private int linkAt(float x, float y) {
        if (!rich || text.spans().count() == 0 || viewW <= 0) {
            return NONE;
        }
        int at = charAt(x, y);
        int span = at < 0 ? -1 : text.spans().find(Spans.Kind.LINK, at);
        return span < 0 ? NONE : span;
    }

    /** The character whose box holds screen point {@code (x, y)}, or -1. */
    private int charAt(float x, float y) {
        float ox = textX0 - scrollX + alignShift();
        int p = 0;
        float contentY = 0;
        if (multiLine) {
            contentY = y - textY0 + heights.top(anchor) + anchorOffset;
            if (contentY < 0 || contentY >= heights.total()) {
                return -1;
            }
            p = heights.at(contentY);
        }
        TextBlock b = shaped(p);
        float local = multiLine ? contentY - heights.top(p) : y - textY0;
        if (local < 0 || local >= b.height()) {
            return -1;
        }
        int line = b.lineAt(local);
        float lx = x - ox;
        for (int i = b.lineStart(line); i < b.lineEnd(line); i++) {
            if (lx >= b.charLeft(i) && lx < b.charRight(i)) {
                return text.lineStart(p) + i;
            }
        }
        return -1;
    }

    private void hoverLink(int link) {
        if (link == hoveredLink) {
            return;
        }
        hoveredLink = link;
        // the runs take link:hover's style: shape the paragraphs again
        for (int i = 0; i < POOL; i++) {
            poolPara[i] = NONE;
        }
        ui.repaint(element);
    }

    /** The span index of the link under a pointer, or -1 (for tests and tooltips). */
    public int hoveredLink() {
        return hoveredLink;
    }
}
