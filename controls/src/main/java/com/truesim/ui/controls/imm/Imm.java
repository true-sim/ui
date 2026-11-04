package com.truesim.ui.controls.imm;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.text.TextEditor;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.Facts;
import java.util.Arrays;

/**
 * The immediate-mode façade over retained elements (design UI3): tools written as ImGui-like calls each frame
 * ({@code if (imm.button("Save")) save();}) that build and update ordinary elements, styled by the same sheets and
 * drawn by the same pipeline as every other control.
 *
 * <ul>
 *   <li><b>State by id.</b> Each call names a widget by a hash of its label (the text after {@code ##} only names, it
 *       is not shown: {@code "Delete##row7"}) within its window, group, tree node or {@link #pushId} scope; unnamed
 *       items (text, separators, lines, table rows and cells) by their place. The widget's element and state
 *       (a window's place, a tree node's openness, a slider being dragged) are kept by that id between frames.</li>
 *   <li><b>Reuse and collection.</b> A widget called again reuses its element; one not called in a frame is
 *       destroyed when the UI updates. Elements move only when the calls' order changes.</li>
 *   <li><b>No latency.</b> Input is processed before the game's code runs ({@code ui.input}), so a click shows as
 *       {@code button(...) == true} in the frame it happened, and what the calls change shows in that frame
 *       ({@code ui.update} after the game's code). Call between the two.</li>
 *   <li><b>Allocation-free in steady state</b>: labels are strings the caller keeps (constants), text is any
 *       {@link CharSequence} (a reused {@link StringBuilder}); nothing is made while the calls stay the same.</li>
 * </ul>
 *
 * <p><b>Layout.</b> Items stack in lines: each item starts a new line unless {@link #sameLine()} came before it.
 * {@link #beginGroup()} makes a column that sits in a line like an item; tables lay cells out in equal columns.
 *
 * <p><b>Elements and styles.</b> Windows ({@code imm-window} with {@code imm-title} and {@code imm-window-body}) sit on
 * the {@value #LAYER} layer (or a host given), at their place, moved by dragging the title. Lines are
 * {@code imm-line}; text is {@code label.imm-text} (or the style given); buttons, checkboxes ({@code check-box},
 * {@code check-mark}), text fields ({@code textfield.imm}) and separators are the theme's ordinary types; sliders are
 * {@code imm-slider} ({@code imm-slider-fill}, {@code label.imm-value}), drags {@code imm-drag}, combos
 * {@code imm-combo} ({@code imm-combo-button}, {@code imm-combo-list}, {@code imm-combo-item}), tree nodes
 * {@code imm-tree} ({@code imm-tree-header} with {@code imm-tree-arrow}, {@code :expanded}; {@code imm-tree-body}),
 * selectables {@code imm-selectable} ({@code :selected}), groups {@code imm-group}, tables {@code imm-table},
 * {@code imm-row}, {@code imm-cell}; captions beside a widget are {@code label.imm-label}. Every interactive widget
 * has a role and its label as its name.
 *
 * <pre>{@code
 * // each frame, between ui.input(...) and ui.update(...):
 * imm.begin("Spawner");
 * imm.text(status);                       // a StringBuilder the tool reuses
 * count = imm.sliderInt("Count", count, 1, 100);
 * imm.sameLine();
 * if (imm.button("Spawn")) spawn(count);
 * imm.end();
 * }</pre>
 */
public final class Imm implements Ui.FrameListener {

    /** The layer windows go on (unless a host is given). */
    public static final String LAYER = "imm";
    /** Its z: above overlays, below modal dialogs. */
    public static final int LAYER_Z = 150;

    private static final int WINDOW = 1;
    private static final int LINE = 2;
    private static final int TEXT = 3;
    private static final int BUTTON = 4;
    private static final int CHECKBOX = 5;
    private static final int SLIDER = 6;
    private static final int DRAG = 7;
    private static final int INPUT = 8;
    private static final int COMBO = 9;
    private static final int TREE = 10;
    private static final int SEPARATOR = 11;
    private static final int TABLE = 12;
    private static final int ROW = 13;
    private static final int CELL = 14;
    private static final int GROUP = 15;
    private static final int SELECTABLE = 16;
    private static final int LABEL_TEXT = 17;
    private static final int CHILD = 18;

    private static final Facts HORIZONTAL = Facts.of("orientation", "horizontal");
    private static final int NONE = UiStore.NONE;
    private static final long SEED = 0x2545F4914F6CDD1DL;

    private final Ui ui;
    private final UiStore store;
    private final Layout layout;
    private final Interaction interaction;
    private final Element host;
    private final Events events = new Events();
    private final Slice slice = new Slice();
    private final StringBuilder scratch = new StringBuilder(32);

    // nodes
    private int capacity = 64;
    private int count;
    private long[] nodeId = new long[capacity];
    private int[] kind = new int[capacity];
    private long[] touched = new long[capacity];
    private Element[] el = new Element[capacity];
    private Element[] inner = new Element[capacity]; // where children go
    private Element[] partA = new Element[capacity];
    private Element[] partB = new Element[capacity];
    private Object[] object = new Object[capacity];
    private String[] source = new String[capacity]; // the label string last shown
    private int[] childLast = new int[capacity]; // the slot of the last child placed this frame
    private int[] clicks = new int[capacity];
    private boolean[] open = new boolean[capacity];
    private boolean[] changed = new boolean[capacity];
    private boolean[] dragging = new boolean[capacity];
    private float[] value = new float[capacity];
    private float[] low = new float[capacity];
    private float[] high = new float[capacity];
    private float[] x = new float[capacity];
    private float[] y = new float[capacity];
    private float[] grab = new float[capacity];
    private int[] decimals = new int[capacity];
    private int[] free = new int[capacity];
    private int freeCount;
    private long[] table = new long[256];
    private int[] tableNode = new int[256];
    private int used;
    private int[] nodeOfSlot = new int[256];

    // the call stack: containers, their current line, tables
    private int depth;
    private int[] frameNode = new int[16];
    private int[] frameKind = new int[16];
    private int[] frameLine = new int[16];
    private int[] frameLines = new int[16];
    private int[] frameAnon = new int[16];
    private int[] frameRows = new int[16];
    private int[] frameRow = new int[16];
    private int[] frameColumn = new int[16];
    private int[] frameColumns = new int[16];
    private int[] frameIdBase = new int[16];
    private long[] ids = new long[16];
    private int idDepth;
    private boolean sameLine;
    private int hostLast = NONE;
    private int windows;
    private long frame = 1;
    private int created;
    private int destroyed;

    /** A façade over {@code ui} whose windows go on the {@value #LAYER} layer. */
    @AllowAllocation("one per tool UI")
    public Imm(Ui ui) {
        this(ui, layer(ui).root());
    }

    /** A façade over {@code ui} whose windows go into {@code host} (an element laid out with {@code layout: none}). */
    @AllowAllocation("one per tool UI")
    public Imm(Ui ui, Element host) {
        this.ui = ui;
        this.store = ui.store();
        this.layout = ui.layout();
        this.interaction = ui.interaction();
        this.host = host;
        Arrays.fill(table, 0);
        ui.addFrameListener(this);
    }

    @AllowAllocation("the layer is made once")
    private static Layer layer(Ui ui) {
        Layer layer = ui.layerStack().find(LAYER);
        return layer != null ? layer : ui.layerStack().add(LAYER, LAYER_Z);
    }

    /** The UI. */
    public Ui ui() {
        return ui;
    }

    /** Where windows go. */
    public Element host() {
        return host;
    }

    // ---------------------------------------------------------------- windows, groups, scopes

    /** Starts window {@code title} (its id too), placed where it was or cascaded on first use; {@link #end()} ends it. */
    public void begin(String title) {
        begin(title, Float.NaN, Float.NaN);
    }

    /** As {@link #begin(String)}, first placed at {@code px, py} (panel units in the host). */
    public void begin(String title, float px, float py) {
        if (depth != 0) {
            throw new IllegalStateException("begin inside a window: end it first");
        }
        long id = hash(SEED, title, displayEnd(title));
        int n = touch(id, WINDOW);
        if (el[n] == null) {
            makeWindow(n);
            x[n] = Float.isNaN(px) ? 24 + 28 * (windows % 8) : px;
            y[n] = Float.isNaN(py) ? 24 + 28 * (windows % 8) : py;
            windows++;
        }
        showLabel(n, partA[n], title);
        layout.offsets(el[n].slot(), x[n], y[n], 0, 0);
        placeIn(el[n], host, true);
        push(n, WINDOW, id);
    }

    @AllowAllocation("a window is made when first begun")
    private void makeWindow(int n) {
        Element w = element(n, "imm-window", null);
        w.semantics(Role.WINDOW, null);
        ui.codeLayout(w, Ui.CODE_PLACEMENT);
        Element title = ui.element("imm-title", null);
        Element caption = ui.label("imm-title", "");
        title.add(caption);
        title.semantics(Role.GROUP, null); // a drag handle; the window is named by its title
        interaction.pickable(title, PointerEvents.ALL);
        listen(n, title, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit());
        Element body = ui.element("imm-window-body", null);
        w.add(title).add(body);
        partA[n] = caption;
        partB[n] = title;
        inner[n] = body;
        w.semantics(Role.WINDOW, ui.text(caption));
    }

    /** Ends the window begun last. */
    public void end() {
        if (depth == 0 || frameKind[depth - 1] != WINDOW) {
            throw new IllegalStateException("end without begin (or a group, tree node or table left open)");
        }
        pop();
    }

    /** Starts a column that sits in the current line like an item ({@link #sameLine()} puts groups side by side). */
    public void beginGroup() {
        int f = top();
        long id = hash(scope(), GROUP * 1_000_003L + frameAnon[f]++);
        int n = item(id, GROUP);
        if (el[n] == null) {
            inner[n] = element(n, "imm-group", null);
        }
        push(n, GROUP, id);
    }

    /** Ends the group begun last. */
    public void endGroup() {
        if (depth == 0 || frameKind[depth - 1] != GROUP) {
            throw new IllegalStateException("endGroup without beginGroup");
        }
        pop();
    }

    /**
     * Starts a region {@code height} panel units high named {@code id} whose items scroll with the wheel (a long list
     * in a window); {@link #endChild()} ends it.
     */
    public void beginChild(String id, float height) {
        long key = hash(scope(), id, id.length());
        int n = item(key, CHILD);
        if (el[n] == null) {
            Element view = element(n, "imm-child", null);
            Element content = ui.element("imm-child-content", null);
            view.add(content);
            inner[n] = content;
            ui.codeLayout(view, Ui.CODE_HEIGHT);
            ui.scrollRegion(view, true);
            interaction.pickable(view, PointerEvents.ALL);
            listen(n, view, EventType.WHEEL.bit());
            high[n] = Float.NaN;
        }
        if (height != high[n]) {
            high[n] = height;
            layout.height(el[n].slot(), height, Unit.PX);
        }
        push(n, CHILD, key);
    }

    /** Ends the region begun last. */
    public void endChild() {
        if (depth == 0 || frameKind[depth - 1] != CHILD) {
            throw new IllegalStateException("endChild without beginChild");
        }
        pop();
    }

    /** Scopes the ids of the next calls by {@code name} (rows of a list with equal labels); pop with {@link #popId}. */
    public void pushId(String name) {
        pushScope(hash(scope(), name, name.length()));
    }

    /** Scopes the ids of the next calls by {@code index}. */
    public void pushId(long index) {
        pushScope(hash(scope(), index));
    }

    /** Ends the last {@link #pushId}. */
    public void popId() {
        if (idDepth <= (depth == 0 ? 0 : frameIdBase[depth - 1] + 1)) {
            throw new IllegalStateException("popId without pushId");
        }
        idDepth--;
    }

    /** The next item goes on the line of the item before it. */
    public void sameLine() {
        sameLine = true;
    }

    // ---------------------------------------------------------------- items

    /** Text in the current line, styled {@code label.imm-text}. */
    public void text(CharSequence text) {
        text(text, "imm-text");
    }

    /** Text in the current line, styled {@code label.<style>}. */
    public void text(CharSequence text, String style) {
        int f = top();
        int n = item(hash(scope(), TEXT * 1_000_003L + frameAnon[f]++), TEXT);
        if (el[n] == null) {
            element(n, null, style);
        } else if (!style.equals(store.style(el[n].slot()))) {
            el[n].style(style);
        }
        ui.text(el[n], text);
    }

    /** A caption and a value side by side ({@code label.imm-label} and {@code label.imm-text}). */
    public void labelText(String label, CharSequence text) {
        long id = hash(scope(), label, label.length());
        int n = item(id, LABEL_TEXT);
        if (el[n] == null) {
            Element row = element(n, "imm-field", null);
            partA[n] = ui.label("imm-label", "");
            partB[n] = ui.label("imm-text", "");
            row.add(partA[n]).add(partB[n]);
        }
        showLabel(n, partA[n], label);
        ui.text(partB[n], text);
    }

    /** A horizontal separator on a line of its own. */
    public void separator() {
        int f = top();
        sameLine = false;
        int n = item(hash(scope(), SEPARATOR * 1_000_003L + frameAnon[f]++), SEPARATOR);
        if (el[n] == null) {
            Element s = element(n, "separator", null);
            ui.styles().facts(s.slot(), HORIZONTAL);
            s.semantics(Role.SEPARATOR, null);
        }
        frameLine[f] = -1;
    }

    /** A push button; true in the frame it was clicked (or activated by ACCEPT). */
    public boolean button(String label) {
        int n = item(hash(scope(), label, label.length()), BUTTON);
        if (el[n] == null) {
            Element b = element(n, "button", null);
            partA[n] = ui.label(null, "");
            b.add(partA[n]);
            b.semantics(Role.BUTTON, ui.text(partA[n]));
            interaction.focusable(b, true);
            listen(n, b, EventType.CLICK.bit() | EventType.COMMAND.bit());
        }
        showLabel(n, partA[n], label);
        return takeClick(n);
    }

    /**
     * A row that can be selected (a list's, a tree's leaf): {@code text} shown, {@code selected} showing as
     * {@code :selected} (a clicked row too, in the frame of the click); true in the frame it was clicked. Named by
     * {@code id} in the current scope.
     */
    public boolean selectable(long id, CharSequence text, boolean selected) {
        int n = item(hash(scope(), id ^ 0x5E1EC7AB1EL), SELECTABLE);
        if (el[n] == null) {
            Element s = element(n, "imm-selectable", null);
            partA[n] = ui.label(null, "");
            s.add(partA[n]);
            s.semantics(Role.LIST_ITEM, ui.text(partA[n]));
            interaction.pickable(s, PointerEvents.ALL);
            interaction.focusable(s, true);
            listen(n, s, EventType.CLICK.bit() | EventType.COMMAND.bit());
        }
        ui.text(partA[n], text);
        boolean clicked = takeClick(n);
        el[n].state(UiState.SELECTED, selected || clicked); // a click shows in its own frame
        return clicked;
    }

    /** A checkbox showing {@code checked}; returns the value after this frame's clicks. */
    public boolean checkbox(String label, boolean checked) {
        int n = item(hash(scope(), label, label.length()), CHECKBOX);
        if (el[n] == null) {
            Element c = element(n, "checkbox", null);
            Element box = ui.element("check-box", null);
            Element mark = ui.element("check-mark", null);
            box.add(mark);
            partA[n] = ui.label(null, "");
            partB[n] = box;
            c.add(box).add(partA[n]);
            c.semantics(Role.CHECKBOX, ui.text(partA[n]));
            interaction.focusable(c, true);
            listen(n, c, EventType.CLICK.bit() | EventType.COMMAND.bit());
        }
        showLabel(n, partA[n], label);
        boolean result = takeClick(n) != checked;
        Element box = partB[n];
        el[n].state(UiState.CHECKED, result);
        box.state(UiState.CHECKED, result);
        store.element(store.firstChild(box.slot())).state(UiState.CHECKED, result);
        return result;
    }

    /** A slider of {@code value} in {@code min..max} with two decimals; returns the value after this frame's input. */
    public float slider(String label, float current, float min, float max) {
        return slider(label, current, min, max, 2);
    }

    /** A slider of whole numbers in {@code min..max}. */
    public int sliderInt(String label, int current, int min, int max) {
        return Math.round(slider(label, current, min, max, 0));
    }

    private float slider(String label, float current, float min, float max, int places) {
        int n = item(hash(scope(), label, label.length()), SLIDER);
        if (el[n] == null) {
            Element row = element(n, "imm-field", null);
            Element frameEl = ui.element("imm-slider", null);
            Element fill = ui.element("imm-slider-fill", null);
            Element text = ui.label("imm-value", "");
            frameEl.add(fill).add(text);
            partA[n] = ui.label("imm-label", "");
            row.add(frameEl).add(partA[n]);
            inner[n] = frameEl;
            partB[n] = text;
            object[n] = fill;
            frameEl.semantics(Role.SLIDER, ui.text(partA[n]));
            interaction.pickable(frameEl, PointerEvents.ALL);
            interaction.focusable(frameEl, true);
            listen(n, frameEl, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
                | EventType.COMMAND.bit());
            value[n] = Float.NaN;
        }
        showLabel(n, partA[n], label);
        low[n] = min;
        high[n] = max;
        decimals[n] = places;
        float v = takeValue(n, current);
        if (places == 0) {
            v = Math.round(v);
        }
        v = Math.max(min, Math.min(max, v));
        if (v != value[n] || Float.isNaN(value[n])) {
            value[n] = v;
            float share = max > min ? (v - min) / (max - min) : 0;
            ui.visuals().width(((Element) object[n]).slot(), share * 100, Unit.PERCENT);
            showNumber(partB[n], v, places);
        }
        return v;
    }

    /** A number dragged sideways ({@code speed} per panel unit), unbounded; returns the value after this frame. */
    public float drag(String label, float current, float speed) {
        return drag(label, current, speed, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    /** A number dragged sideways ({@code speed} per panel unit) within {@code min..max}. */
    public float drag(String label, float current, float speed, float min, float max) {
        int n = item(hash(scope(), label, label.length()), DRAG);
        if (el[n] == null) {
            Element row = element(n, "imm-field", null);
            Element box = ui.element("imm-drag", null);
            Element text = ui.label("imm-value", "");
            box.add(text);
            partA[n] = ui.label("imm-label", "");
            row.add(box).add(partA[n]);
            inner[n] = box;
            partB[n] = text;
            box.semantics(Role.SPIN_BUTTON, ui.text(partA[n]));
            interaction.pickable(box, PointerEvents.ALL);
            interaction.focusable(box, true);
            listen(n, box, EventType.PRESS.bit() | EventType.MOVE.bit() | EventType.RELEASE.bit()
                | EventType.COMMAND.bit());
            value[n] = Float.NaN;
        }
        showLabel(n, partA[n], label);
        low[n] = min;
        high[n] = max;
        x[n] = speed;
        decimals[n] = 2;
        float v = Math.max(min, Math.min(max, takeValue(n, current)));
        if (v != value[n] || Float.isNaN(value[n])) {
            value[n] = v;
            showNumber(partB[n], v, 2);
        }
        return v;
    }

    /**
     * A text field editing {@code text}: the field shows it, and edits are copied into it; returns true in a frame
     * the user changed it. Changing {@code text} in code shows in the field while the field is not focused.
     */
    public boolean inputText(String label, StringBuilder text) {
        int n = item(hash(scope(), label, label.length()), INPUT);
        if (el[n] == null) {
            makeInput(n, text);
        }
        showLabel(n, partA[n], label);
        TextEditor editor = (TextEditor) object[n];
        if (changed[n]) {
            changed[n] = false;
            text.setLength(0);
            text.append(editor.text());
            return true;
        }
        if (!editor.focused() && !same(editor.text(), text)) {
            editor.text(text);
        }
        return false;
    }

    @AllowAllocation("a text field is made when first called")
    private void makeInput(int n, StringBuilder text) {
        Element row = element(n, "imm-field", null);
        int node = n;
        TextEditor editor = TextEditor.field(ui, "imm", "");
        editor.text(text);
        editor.listener(new TextEditor.Listener() {
            @Override
            public void changed(TextEditor e) {
                Imm.this.changed[node] = true;
            }
        });
        partA[n] = ui.label("imm-label", "");
        row.add(editor.element()).add(partA[n]);
        editor.element().semantics(Role.TEXT_FIELD, ui.text(partA[n]));
        object[n] = editor;
        inner[n] = editor.element();
    }

    /** A drop-down of {@code items} showing {@code current}; returns the index chosen by this frame's clicks. */
    public int combo(String label, int current, String[] items) {
        int n = item(hash(scope(), label, label.length()), COMBO);
        if (el[n] == null) {
            Element row = element(n, "imm-field", null);
            Element combo = ui.element("imm-combo", null);
            Element button = ui.element("imm-combo-button", null);
            Element shown = ui.label("imm-value", "");
            button.add(shown).add(ui.element("imm-combo-arrow", null));
            Element list = ui.element("imm-combo-list", null);
            list.semantics(Role.LIST, null);
            combo.add(button).add(list);
            partA[n] = ui.label("imm-label", "");
            row.add(combo).add(partA[n]);
            button.semantics(Role.COMBO_BOX, ui.text(partA[n]));
            interaction.pickable(button, PointerEvents.ALL);
            interaction.focusable(button, true);
            listen(n, button, EventType.CLICK.bit() | EventType.COMMAND.bit());
            listen(n, list, EventType.CLICK.bit());
            inner[n] = list;
            partB[n] = shown;
            layout.visible(list.slot(), false);
            value[n] = -1;
        }
        showLabel(n, partA[n], label);
        if (takeClick(n)) {
            open[n] = !open[n];
        }
        int chosen = changed[n] ? (int) high[n] : current;
        if (changed[n]) {
            changed[n] = false;
            open[n] = false;
        }
        Element list = inner[n];
        layout.visible(list.slot(), open[n]);
        el[n].state(UiState.EXPANDED, open[n]);
        if (object[n] != items) {
            object[n] = items;
            fillCombo(list, items);
        }
        if (chosen != (int) value[n]) {
            value[n] = chosen;
            ui.text(partB[n], chosen >= 0 && chosen < items.length ? items[chosen] : "");
            int i = 0;
            for (int c = store.firstChild(list.slot()); c != NONE; c = store.nextSibling(c), i++) {
                store.element(c).state(UiState.SELECTED, i == chosen);
            }
        }
        return chosen;
    }

    @AllowAllocation("a combo's items change: an event")
    private void fillCombo(Element list, String[] items) {
        int i = 0;
        int c = store.firstChild(list.slot());
        for (; i < items.length; i++) {
            Element item;
            if (c != NONE) {
                item = store.element(c);
                c = store.nextSibling(c);
            } else {
                item = ui.label("imm-combo-item", "");
                interaction.pickable(item, PointerEvents.ALL);
                list.add(item);
            }
            ui.text(item, items[i]);
            item.semantics(Role.LIST_ITEM, ui.text(item));
            layout.visible(item.slot(), true);
        }
        for (; c != NONE; c = store.nextSibling(c)) {
            layout.visible(c, false);
        }
    }

    /**
     * A tree node, closed at first; true while it is open, and then the calls up to {@link #treePop()} make its
     * children (call {@code treePop} only when it returned true).
     */
    public boolean treeNode(String label) {
        return tree(hash(scope(), label, label.length()), label, false);
    }

    /** A tree node named by {@code id} in the current scope, showing {@code text}, open at first if {@code initially}. */
    public boolean treeNode(long id, CharSequence text, boolean initially) {
        return tree(hash(scope(), id ^ 0x7EE0B0DEL), text, initially);
    }

    private boolean tree(long key, CharSequence text, boolean initially) {
        int n = item(key, TREE);
        if (el[n] == null) {
            Element tree = element(n, "imm-tree", null);
            Element header = ui.element("imm-tree-header", null);
            Element arrow = ui.element("imm-tree-arrow", null);
            partA[n] = ui.label(null, "");
            header.add(arrow).add(partA[n]);
            Element body = ui.element("imm-tree-body", null);
            tree.add(header).add(body);
            partB[n] = header;
            inner[n] = body;
            header.semantics(Role.TREE_ITEM, ui.text(partA[n]));
            interaction.pickable(header, PointerEvents.ALL);
            interaction.focusable(header, true);
            listen(n, header, EventType.CLICK.bit() | EventType.COMMAND.bit());
            open[n] = initially;
            value[n] = -1;
        }
        if (text instanceof String s) {
            showLabel(n, partA[n], s);
        } else {
            ui.text(partA[n], text);
        }
        if (takeClick(n)) {
            open[n] = !open[n];
        }
        if ((open[n] ? 1 : 0) != (int) value[n]) {
            value[n] = open[n] ? 1 : 0;
            Element header = partB[n];
            header.state(UiState.EXPANDED, open[n]);
            store.element(store.firstChild(header.slot())).state(UiState.EXPANDED, open[n]);
            layout.visible(inner[n].slot(), open[n]);
        }
        if (open[n]) {
            push(n, TREE, nodeId[n]);
        }
        return open[n];
    }

    /** Ends the children of the tree node opened last. */
    public void treePop() {
        if (depth == 0 || frameKind[depth - 1] != TREE) {
            throw new IllegalStateException("treePop without an open treeNode");
        }
        pop();
    }

    // ---------------------------------------------------------------- tables

    /** Starts a table of {@code columns} equal columns named {@code id}; rows and cells follow, then {@link #endTable}. */
    public void beginTable(String id, int columns) {
        long key = hash(scope(), id, id.length());
        int n = item(key, TABLE);
        if (el[n] == null) {
            element(n, "imm-table", null).semantics(Role.TABLE, id);
            inner[n] = el[n];
        }
        push(n, TABLE, key);
        int f = depth - 1;
        frameRows[f] = 0;
        frameRow[f] = -1;
        frameColumn[f] = 0;
        frameColumns[f] = columns;
    }

    /** Starts the table's next row. */
    public void tableNextRow() {
        int f = tableFrame();
        int t = frameNode[f];
        long id = hash(nodeId[t], ROW * 1_000_003L + frameRows[f]++);
        int r = touch(id, ROW);
        if (el[r] == null) {
            element(r, "imm-row", null).semantics(Role.ROW, null);
            inner[r] = el[r];
        }
        placeIn(r, t);
        frameRow[f] = r;
        frameColumn[f] = 0;
    }

    /** Starts the next cell of the current row (the first row if none was started); items go into it. */
    public void tableNextColumn() {
        int f = tableFrame();
        if (frameRow[f] < 0) {
            tableNextRow();
        }
        if (frameColumn[f] >= frameColumns[f]) {
            throw new IllegalStateException("the table has " + frameColumns[f] + " columns");
        }
        int r = frameRow[f];
        long id = hash(nodeId[r], CELL * 1_000_003L + frameColumn[f]++);
        int c = touch(id, CELL);
        if (el[c] == null) {
            element(c, "imm-cell", null).semantics(Role.CELL, null);
            inner[c] = el[c];
        }
        placeIn(c, r);
        push(c, CELL, id);
    }

    /** Ends the table begun last. */
    public void endTable() {
        tableFrame();
        pop();
    }

    /** The table's frame (a cell's is ended first). */
    private int tableFrame() {
        if (depth > 0 && frameKind[depth - 1] == CELL) {
            pop();
        }
        if (depth == 0 || frameKind[depth - 1] != TABLE) {
            throw new IllegalStateException("not in a table");
        }
        return depth - 1;
    }

    // ---------------------------------------------------------------- nodes

    /** The node of {@code id}: made, or found and touched this frame. */
    private int touch(long id, int k) {
        int n = find(id);
        if (n >= 0 && (kind[n] != k || el[n] != null && !el[n].alive())) {
            drop(n);
            n = -1;
        }
        if (n < 0) {
            n = make(id, k);
        } else if (touched[n] == frame) {
            throw duplicate(n);
        }
        touched[n] = frame;
        childLast[n] = NONE;
        return n;
    }

    @AllowAllocation("an error")
    private IllegalStateException duplicate(int n) {
        return new IllegalStateException("two widgets with one id in a frame (" + source[n]
            + "): give one a ## suffix or push an id");
    }

    /** An item of the current container: in the current line, or a new one. */
    private int item(long id, int k) {
        int f = top();
        int line = frameLine[f];
        if (!sameLine || line < 0) {
            long lineId = hash(ids[idDepth - 1], LINE * 1_000_003L + frameLines[f]++);
            line = touch(lineId, LINE);
            if (el[line] == null) {
                element(line, "imm-line", null);
                inner[line] = el[line];
            }
            placeIn(line, frameNode[f]);
            frameLine[f] = line;
        }
        sameLine = false;
        int n = touch(id, k);
        if (el[n] != null) {
            placeIn(n, line);
        } else {
            pendingParent = line;
        }
        return n;
    }

    private int pendingParent = -1;

    /** A new element of {@code type} styled {@code style} for node {@code n}, placed where its node goes. */
    @AllowAllocation("an element is made when its widget first shows")
    private Element element(int n, String type, String style) {
        Element e = type == null ? ui.label(style, "") : ui.element(type, style);
        el[n] = e;
        mapSlot(e.slot(), n);
        created++;
        if (kind[n] != WINDOW && pendingParent >= 0) {
            placeIn(n, pendingParent);
        }
        pendingParent = -1;
        return e;
    }

    /** Moves node {@code n}'s element to be the next child of node {@code parent}'s inner element. */
    private void placeIn(int n, int parent) {
        Element child = el[n];
        Element into = inner[parent];
        int last = childLast[parent];
        int expected = last == NONE ? store.firstChild(into.slot()) : store.nextSibling(last);
        if (store.parent(child.slot()) != into.slot() || expected != child.slot()) {
            if (store.parent(child.slot()) != NONE) {
                child.detach();
            }
            into.add(child);
        }
        childLast[parent] = child.slot();
    }

    /** Moves a window's element to be the host's next child. */
    private void placeIn(Element child, Element into, boolean window) {
        int expected = hostLast == NONE ? store.firstChild(into.slot()) : store.nextSibling(hostLast);
        if (store.parent(child.slot()) != into.slot() || expected != child.slot()) {
            if (store.parent(child.slot()) != NONE) {
                child.detach();
            }
            into.add(child);
        }
        hostLast = child.slot();
    }

    private int find(long id) {
        int mask = table.length - 1;
        for (int at = (int) mix(id) & mask; ; at = (at + 1) & mask) {
            int n = tableNode[at];
            if (n == 0) {
                return -1;
            }
            if (n > 0 && table[at] == id) {
                return n - 1;
            }
        }
    }

    @AllowAllocation("a widget shows for the first time")
    private int make(long id, int k) {
        if (freeCount == 0 && count == capacity) {
            grow();
        }
        int n = freeCount > 0 ? free[--freeCount] : count++;
        nodeId[n] = id;
        kind[n] = k;
        el[n] = null;
        inner[n] = null;
        partA[n] = null;
        partB[n] = null;
        object[n] = null;
        source[n] = null;
        clicks[n] = 0;
        open[n] = false;
        changed[n] = false;
        dragging[n] = false;
        value[n] = 0;
        if ((used + 1) * 2 > table.length) {
            rehash();
        }
        int mask = table.length - 1;
        int at = (int) mix(id) & mask;
        while (tableNode[at] > 0) {
            at = (at + 1) & mask;
        }
        if (tableNode[at] == 0) {
            used++;
        }
        table[at] = id;
        tableNode[at] = n + 1;
        return n;
    }

    @AllowAllocation("growing with the tool")
    private void grow() {
        capacity *= 2;
        nodeId = Arrays.copyOf(nodeId, capacity);
        kind = Arrays.copyOf(kind, capacity);
        touched = Arrays.copyOf(touched, capacity);
        el = Arrays.copyOf(el, capacity);
        inner = Arrays.copyOf(inner, capacity);
        partA = Arrays.copyOf(partA, capacity);
        partB = Arrays.copyOf(partB, capacity);
        object = Arrays.copyOf(object, capacity);
        source = Arrays.copyOf(source, capacity);
        childLast = Arrays.copyOf(childLast, capacity);
        clicks = Arrays.copyOf(clicks, capacity);
        open = Arrays.copyOf(open, capacity);
        changed = Arrays.copyOf(changed, capacity);
        dragging = Arrays.copyOf(dragging, capacity);
        value = Arrays.copyOf(value, capacity);
        low = Arrays.copyOf(low, capacity);
        high = Arrays.copyOf(high, capacity);
        x = Arrays.copyOf(x, capacity);
        y = Arrays.copyOf(y, capacity);
        grab = Arrays.copyOf(grab, capacity);
        decimals = Arrays.copyOf(decimals, capacity);
        free = Arrays.copyOf(free, capacity);
    }

    /** Rebuilds the id table without its tombstones, at twice the size when it is half full of live ids. */
    @AllowAllocation("growing with the tool")
    private void rehash() {
        int live = count - freeCount;
        int size = table.length;
        while ((live + 1) * 2 > size) {
            size *= 2;
        }
        table = new long[size];
        tableNode = new int[size];
        used = 0;
        int mask = size - 1;
        boolean[] isFree = new boolean[count];
        for (int i = 0; i < freeCount; i++) {
            isFree[free[i]] = true;
        }
        for (int n = 0; n < count; n++) {
            if (isFree[n]) {
                continue;
            }
            int at = (int) mix(nodeId[n]) & mask;
            while (tableNode[at] != 0) {
                at = (at + 1) & mask;
            }
            table[at] = nodeId[n];
            tableNode[at] = n + 1;
            used++;
        }
    }

    /** Forgets node {@code n}: its element (and subtree) destroyed, its id a tombstone. */
    private void drop(int n) {
        Element e = el[n];
        if (e != null && e.alive()) {
            if (object[n] instanceof TextEditor editor) {
                ui.removeFrameListener(editor);
            }
            unmap(e);
            ui.destroy(e);
            destroyed++;
        } else if (object[n] instanceof TextEditor editor) {
            ui.removeFrameListener(editor);
        }
        int mask = table.length - 1;
        for (int at = (int) mix(nodeId[n]) & mask; ; at = (at + 1) & mask) {
            if (tableNode[at] == 0) {
                break;
            }
            if (tableNode[at] == n + 1) {
                tableNode[at] = -1; // a tombstone keeps the probe chains of other ids
                break;
            }
        }
        el[n] = null;
        inner[n] = null;
        partA[n] = null;
        partB[n] = null;
        object[n] = null;
        kind[n] = 0;
        free[freeCount++] = n;
    }

    private void unmap(Element e) {
        int s = e.slot();
        if (s < nodeOfSlot.length) {
            nodeOfSlot[s] = 0;
        }
        for (int c = store.firstChild(s); c != NONE; c = store.nextSibling(c)) {
            unmap(store.element(c));
        }
    }

    private void mapSlot(int slot, int n) {
        if (slot >= nodeOfSlot.length) {
            growSlots(slot);
        }
        nodeOfSlot[slot] = n + 1;
    }

    @AllowAllocation("growing with the store")
    private void growSlots(int slot) {
        nodeOfSlot = Arrays.copyOf(nodeOfSlot, Math.max(slot + 1, nodeOfSlot.length * 2));
    }

    @AllowAllocation("listeners are added when a widget is made")
    private void listen(int n, Element e, int mask) {
        mapSlot(e.slot(), n);
        interaction.on(e, mask, events);
    }

    // ---------------------------------------------------------------- the stack

    private int top() {
        if (depth == 0) {
            throw new IllegalStateException("no window: call begin first");
        }
        return depth - 1;
    }

    @AllowAllocation("the stack grows with nesting, rarely")
    private void push(int n, int k, long id) {
        if (depth == frameNode.length) {
            int size = depth * 2;
            frameNode = Arrays.copyOf(frameNode, size);
            frameKind = Arrays.copyOf(frameKind, size);
            frameLine = Arrays.copyOf(frameLine, size);
            frameLines = Arrays.copyOf(frameLines, size);
            frameAnon = Arrays.copyOf(frameAnon, size);
            frameRows = Arrays.copyOf(frameRows, size);
            frameRow = Arrays.copyOf(frameRow, size);
            frameColumn = Arrays.copyOf(frameColumn, size);
            frameColumns = Arrays.copyOf(frameColumns, size);
            frameIdBase = Arrays.copyOf(frameIdBase, size);
        }
        frameIdBase[depth] = idDepth;
        frameNode[depth] = n;
        frameKind[depth] = k;
        frameLine[depth] = -1;
        frameLines[depth] = 0;
        frameAnon[depth] = 0;
        depth++;
        pushScope(id);
        sameLine = false;
    }

    private void pop() {
        depth--;
        idDepth = frameIdBase[depth]; // ids pushed inside and not popped go with it
        sameLine = false;
    }

    @AllowAllocation("the stack grows with nesting, rarely")
    private void pushScope(long id) {
        if (idDepth == ids.length) {
            ids = Arrays.copyOf(ids, idDepth * 2);
        }
        ids[idDepth++] = id;
    }

    private long scope() {
        return idDepth == 0 ? SEED : ids[idDepth - 1];
    }

    // ---------------------------------------------------------------- values and labels

    private boolean takeClick(int n) {
        boolean clicked = clicks[n] > 0;
        clicks[n] = 0;
        return clicked;
    }

    /** The widget's value after this frame's input, else {@code current} (the caller's). */
    private float takeValue(int n, float current) {
        if (changed[n] || dragging[n]) {
            changed[n] = false;
            return grab[n];
        }
        return current;
    }

    /** Shows {@code label} up to {@code ##} in {@code target}, when it is another string than last time. */
    private void showLabel(int n, Element target, String label) {
        if (label != source[n]) {
            source[n] = label;
            slice.set(label, displayEnd(label));
            ui.text(target, slice);
        }
    }

    private static int displayEnd(String label) {
        int at = label.indexOf("##");
        return at < 0 ? label.length() : at;
    }

    private void showNumber(Element target, float v, int places) {
        scratch.setLength(0);
        number(scratch, v, places);
        ui.text(target, scratch);
    }

    /** Appends {@code v} with {@code places} decimals (rounded half away from zero), allocation-free. */
    public static void number(StringBuilder out, double v, int places) {
        if (Double.isNaN(v)) {
            out.append("NaN");
            return;
        }
        if (Double.isInfinite(v)) {
            out.append(v > 0 ? "\u221E" : "-\u221E");
            return;
        }
        double scale = 1;
        for (int i = 0; i < places; i++) {
            scale *= 10;
        }
        long scaled = Math.round(Math.abs(v) * scale);
        if (v < 0 && scaled != 0) {
            out.append('-');
        }
        long whole = (long) (scaled / scale);
        out.append(whole);
        if (places > 0) {
            out.append('.');
            long fraction = scaled - (long) (whole * scale);
            long digit = (long) scale / 10;
            for (int i = 0; i < places; i++) {
                out.append((char) ('0' + fraction / digit % 10));
                digit /= 10;
            }
        }
    }

    private static boolean same(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- ids

    private static long hash(long seed, CharSequence s, int end) {
        long h = seed ^ 0xCBF29CE484222325L;
        for (int i = 0; i < end; i++) {
            h = (h ^ s.charAt(i)) * 0x100000001B3L;
        }
        // the hidden part names too: "Delete##3" and "Delete##4" differ
        for (int i = end; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * 0x100000001B3L;
        }
        return mix(h);
    }

    private static long hash(long seed, long v) {
        return mix(seed * 0x9E3779B97F4A7C15L + v);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ---------------------------------------------------------------- the frame

    /**
     * After the tool's calls of this frame, before styles and layout: destroys the widgets not called this frame,
     * clears unused clicks, checks the calls were balanced.
     */
    @Override
    public void frame(long nanos) {
        if (depth != 0) {
            depth = 0;
            idDepth = 0;
            throw new IllegalStateException("a window, group, tree node or table was left open");
        }
        for (int n = 0; n < count; n++) {
            if (kind[n] != 0 && touched[n] != frame) {
                drop(n);
            }
            clicks[n] = 0;
        }
        frame++;
        hostLast = NONE;
        sameLine = false;
    }

    /** How many widgets (and lines, rows and cells) exist. */
    public int nodes() {
        return count - freeCount;
    }

    /** How many elements were made so far (widgets reuse theirs while called). */
    public int created() {
        return created;
    }

    /** How many widgets were collected so far. */
    public int destroyed() {
        return destroyed;
    }

    /** The element of the widget labelled {@code label} directly in window {@code title} (tests, tools), or null. */
    public Element element(String title, String label) {
        int n = find(hash(hash(SEED, title, displayEnd(title)), label, label.length()));
        return n < 0 ? null : el[n];
    }

    /** The element of window {@code title}, or null. */
    public Element window(String title) {
        int n = find(hash(SEED, title, displayEnd(title)));
        return n < 0 ? null : el[n];
    }

    // ---------------------------------------------------------------- input

    /** Clicks, drags and commands of every widget. */
    private final class Events implements UiListener {

        @Override
        public void handle(UiEvent e) {
            int cs = e.currentSlot();
            int n = cs < nodeOfSlot.length ? nodeOfSlot[cs] - 1 : -1;
            if (n < 0 || kind[n] == 0) {
                return;
            }
            switch (kind[n]) {
                case WINDOW -> moveWindow(n, e);
                case SLIDER, DRAG -> dragValue(n, e);
                case CHILD -> scroll(n, e);
                case COMBO -> {
                    if (cs == inner[n].slot()) {
                        pick(n, e);
                    } else {
                        clickOrAccept(n, e);
                    }
                }
                default -> clickOrAccept(n, e);
            }
        }

        private void scroll(int n, UiEvent e) {
            float s = layout.scale() > 0 ? layout.scale() : 1;
            float view = layout.height(el[n].slot()) / s;
            float content = layout.height(inner[n].slot()) / s;
            float to = Math.max(0, Math.min(content - view, value[n] - e.scrollY() * 40));
            if (to != value[n]) {
                value[n] = to;
                layout.scroll(el[n].slot(), 0, to);
                e.consume();
            }
        }

        private void clickOrAccept(int n, UiEvent e) {
            if (e.type() == EventType.CLICK || e.type() == EventType.COMMAND && e.command() == UiCommand.ACCEPT) {
                e.consume();
                clicks[n]++;
            }
        }

        private void pick(int n, UiEvent e) {
            int i = 0;
            int target = e.targetSlot();
            for (int c = store.firstChild(inner[n].slot()); c != NONE; c = store.nextSibling(c), i++) {
                if (c == target) {
                    e.consume();
                    high[n] = i;
                    changed[n] = true;
                    return;
                }
            }
        }

        private void moveWindow(int n, UiEvent e) {
            float s = layout.scale() > 0 ? layout.scale() : 1;
            switch (e.type()) {
                case PRESS -> {
                    interaction.capture(e.pointerId(), store.element(e.currentSlot()));
                    dragging[n] = true;
                    grab[n] = e.x() / s - x[n];
                    low[n] = e.y() / s - y[n];
                    e.consume();
                }
                case MOVE -> {
                    if (dragging[n]) {
                        x[n] = e.x() / s - grab[n];
                        y[n] = e.y() / s - low[n];
                        layout.offsets(el[n].slot(), x[n], y[n], 0, 0);
                        e.consume();
                    }
                }
                case RELEASE -> {
                    dragging[n] = false;
                    interaction.releaseCapture(e.pointerId(), store.element(e.currentSlot()));
                }
                default -> {
                }
            }
        }

        private void dragValue(int n, UiEvent e) {
            float s = layout.scale() > 0 ? layout.scale() : 1;
            switch (e.type()) {
                case PRESS -> {
                    interaction.capture(e.pointerId(), inner[n]);
                    interaction.focus(inner[n]);
                    dragging[n] = true;
                    if (kind[n] == SLIDER) {
                        grab[n] = along(n, e.x());
                    } else {
                        grab[n] = value[n];
                        y[n] = e.x() / s;
                    }
                    changed[n] = true;
                    e.consume();
                }
                case MOVE -> {
                    if (dragging[n]) {
                        if (kind[n] == SLIDER) {
                            grab[n] = along(n, e.x());
                        } else {
                            grab[n] = Math.max(low[n], Math.min(high[n], grab[n] + (e.x() / s - y[n]) * x[n]));
                            y[n] = e.x() / s;
                        }
                        changed[n] = true;
                        e.consume();
                    }
                }
                case RELEASE -> {
                    dragging[n] = false;
                    interaction.releaseCapture(e.pointerId(), inner[n]);
                }
                case COMMAND -> {
                    UiCommand c = e.command();
                    if (c == UiCommand.LEFT || c == UiCommand.RIGHT) {
                        float range = high[n] - low[n];
                        float step = kind[n] == SLIDER ? (decimals[n] == 0 ? 1 : range / 100) : x[n];
                        float v = value[n] + (c == UiCommand.RIGHT ? step : -step);
                        grab[n] = Math.max(low[n], Math.min(high[n], v));
                        changed[n] = true;
                        e.consume();
                    }
                }
                default -> {
                }
            }
        }

        /** The slider value at pointer x. */
        private float along(int n, float px) {
            int f = inner[n].slot();
            float w = layout.width(f);
            float share = w > 0 ? (px - layout.x(f)) / w : 0;
            share = Math.max(0, Math.min(1, share));
            return low[n] + share * (high[n] - low[n]);
        }
    }

    /** A part of a label string, shown without copying it. */
    private static final class Slice implements CharSequence {

        private String text = "";
        private int length;

        void set(String value, int n) {
            text = value;
            length = n;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            return text.charAt(index);
        }

        @Override
        @AllowAllocation("copies on request")
        public CharSequence subSequence(int start, int end) {
            return text.substring(start, end);
        }

        @Override
        @AllowAllocation("copies on request")
        public String toString() {
            return text.substring(0, length);
        }
    }
}
