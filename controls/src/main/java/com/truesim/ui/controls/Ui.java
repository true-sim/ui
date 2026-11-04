package com.truesim.ui.controls;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.anim.Animator;
import com.truesim.ui.controls.anim.Visuals;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Layer;
import com.truesim.ui.core.Layers;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.core.event.DragSession;
import com.truesim.ui.core.event.EventType;
import com.truesim.ui.core.event.Interaction;
import com.truesim.ui.core.event.PickGeometry;
import com.truesim.ui.core.event.PointerEvents;
import com.truesim.ui.core.event.UiListener;
import com.truesim.ui.core.input.Cursor;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.layout.Layout;
import com.truesim.ui.layout.Measure;
import com.truesim.ui.layout.Unit;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.style.StyleResolver;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.Styles;
import com.truesim.ui.style.tss.StyleWatcher;
import com.truesim.ui.style.value.Anchors;
import com.truesim.ui.style.value.Border;
import com.truesim.ui.style.value.FontStyle;
import com.truesim.ui.style.value.GridPlacement;
import com.truesim.ui.style.value.ImageFit;
import com.truesim.ui.style.value.ImageSpec;
import com.truesim.ui.style.value.Insets;
import com.truesim.ui.style.value.Length;
import com.truesim.ui.style.value.Paint;
import com.truesim.ui.style.value.Radii;
import com.truesim.ui.style.value.Shadow;
import com.truesim.ui.style.value.TextOutline;
import com.truesim.ui.style.value.Track;
import com.truesim.ui.text.GlyphAtlas;
import com.truesim.ui.text.TextAlign;
import com.truesim.ui.text.TextBlock;
import com.truesim.ui.text.TextSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One retained UI: its elements, style resolution, layout, text and drawing, updated once a frame. The frame does
 * only what changed (design 4.2): the store queues changed elements; styles are resolved for those only, from the
 * resolver's cache (never looked up per frame otherwise); layout runs on dirty subtrees; only elements whose look or
 * place changed record their primitives again, into their range of the draw list, and only changed words are
 * uploaded. A frame where nothing changed costs a few comparisons and allocates nothing.
 *
 * <p>Everything presentational comes from the style sheet ({@link Styles}' keys): sizes, colours, fonts, spacing,
 * radii, borders, shadows, images, layout, clipping, focus rings, cursors, pointer-events. Code makes elements, sets
 * their type, id, style name, state and text, and listens to their events.
 *
 * <p><b>Layers.</b> The UI is a stack of {@link Layers}: the HUD (the {@link #root()}) and pop-up, tooltip, modal,
 * drag and debug layers made when first asked for ({@link #layer}), each with its own draw list; {@link #layers()}
 * hands them to the renderer back to front.
 *
 * <p><b>The frame, in two phases</b> (no frame of latency either way). {@link #input(UiInput, long)} at the frame's
 * start processes its input through the {@link Interaction} (picking against what was drawn last, events, states,
 * focus, commands, text), so the game's update after it sees this frame's clicks; {@link #update(float, float)} after
 * the game's update runs the frame listeners, animations, styles, layout and recording, so whatever the input and the
 * game changed shows in the same frame. {@link #update(float, float, UiInput, long)} does both at once.
 *
 * <pre>{@code
 * Ui ui = new Ui(Tss.defaultTheme(), textSystem);
 * Element hud = ui.panel("hud");
 * Element health = ui.label("value", "100");
 * ui.root().add(hud.add(health));
 * ui.on(health, EventType.CLICK, e -> heal());
 * // each frame:
 * input.beginFrame(); sources.poll(input);
 * ui.input(input, System.nanoTime());   // clicks reach heal() now
 * game.update();                        // ... which the game's update sees
 * ui.text(health, hpText);
 * ui.update(width, height);             // and its changes show this frame
 * renderer.submit(ui.layers(), ui.layerCount(), ui.textures(), width, height);
 * }</pre>
 *
 * <p><b>Animation.</b> Changed styles animate the keys their {@code transition} names ({@link Animator#restyled}),
 * driven by the clock {@link #input(UiInput, long)} is given; {@link #visuals()} holds the per-element
 * values animations and controls write over the style (opacity, scale, drawn offsets, colours, sizes, offsets,
 * anchors). An element's opacity and scale (its style's or animated) apply to its whole subtree.
 *
 * <p><b>Controls' code in the frame.</b> {@link FrameListener}s ({@link #addFrameListener}) run after input (timers,
 * models polled, rows bound) and after layout (what depends on layout, with further passes while they change
 * styles or layout inputs); layout inputs code sets are owned through {@link #codeLayout}, so restyles keep them.
 */
public final class Ui {

    /** {@link #codeLayout} bit: code sets the element's layout kind and direction (its style's are not applied). */
    public static final int CODE_KIND = 1;
    /** {@link #codeLayout} bit: code sets the element's anchors and offsets (a virtualized row's place). */
    public static final int CODE_PLACEMENT = 2;
    /** {@link #codeLayout} bit: code sets the element's width (a table column's, resized by the user). */
    public static final int CODE_WIDTH = 4;
    /** {@link #codeLayout} bit: code sets the element's height. */
    public static final int CODE_HEIGHT = 8;

    /** {@link #codeLayout} bit: code sets the element's flex grow, shrink and basis (a split's ratios). */
    public static final int CODE_FLEX = 16;

    /** The most layout passes a frame runs for {@link FrameListener#afterLayout} changes. */
    public static final int MAX_PASSES = 4;

    /**
     * Code a control runs inside every {@link #update} ({@link #addFrameListener}), in two phases around styles and
     * layout; both run in every frame, in the order listeners were added, and must not allocate in steady state.
     * Content a control draws itself is not a listener's business: see {@link #paint} and {@link #part}.
     *
     * <ol>
     * <li>{@link #frame}: after input was processed, before animations, styles, layout and recording: timers (a caret's
     * blink, tooltip delays), models polled, rows bound for the scroll position, layout inputs set in code (owned
     * through {@link #codeLayout}, so a restyle keeps them). Everything changed here shows in this frame.
     * <li>{@link #afterLayout}: after layout: read what layout found (viewport sizes, measured rows, an anchor's
     * place) and place what depends on it, focus what was just laid out. A change of styles, states, facts or layout
     * inputs made here is resolved and laid out again in the same frame, and the listeners called again, up to
     * {@link #MAX_PASSES} passes (an overlay anchored in another overlay).
     * </ol>
     */
    @FunctionalInterface
    public interface FrameListener {

        /** The frame at {@code nanos} on the UI clock (0 without one), after input, before styles and layout. */
        void frame(long nanos);

        /** After a layout pass; changes made here are laid out in another pass of this frame. */
        default void afterLayout() {
            // nothing by default
        }
    }

    private static final int NONE = 0;
    private static final int TEXT = 1;
    private static final int IMAGE = 2;
    private static final int PAINTED = 3;

    private static final com.truesim.ui.layout.LayoutKind[] KINDS = com.truesim.ui.layout.LayoutKind.values();
    private static final com.truesim.ui.layout.Direction[] DIRECTIONS = com.truesim.ui.layout.Direction.values();
    private static final com.truesim.ui.layout.Wrap[] WRAPS = com.truesim.ui.layout.Wrap.values();
    private static final com.truesim.ui.layout.Justify[] JUSTIFIES = com.truesim.ui.layout.Justify.values();
    private static final com.truesim.ui.layout.Align[] ALIGNS = com.truesim.ui.layout.Align.values();
    private static final com.truesim.ui.layout.BorderRegion[] REGIONS = com.truesim.ui.layout.BorderRegion.values();
    private static final Unit[] UNITS = {Unit.PX, Unit.EM, Unit.PERCENT, Unit.AUTO};
    private static final TextAlign[] TEXT_ALIGNS = TextAlign.values();
    private static final float RADIANS = 0.017453292f; // pi / 180

    private final UiStore store;
    private final StyleResolver resolver;
    private final Layout layout;
    private final TextSystem text;
    private final UiTextures textures;
    private final UiImages images;
    private final Layers layerStack;
    private final Interaction interaction;
    private final Visuals visuals;
    private final Animator animator;
    private final Measure measure = this::measure;
    private DrawList rec; // the list being recorded
    private float recordShiftX; // the shift of the element being recorded: its clip owner's scroll
    private float recordShiftY;
    private StyleWatcher watcher;

    // per slot
    private int[] content = new int[0];
    private Painter[] painters = new Painter[0];
    private boolean[] parts = new boolean[0];
    private TextBlock[] blocks = new TextBlock[0];
    private StringBuilder[] strings = new StringBuilder[0];
    private long[] drawnTextVersion = new long[0];
    private ResolvedStyle[] applied = new ResolvedStyle[0];
    private long[] appliedVersion = new long[0];
    private int[] textSlots = new int[16];
    private int textSlotCount;
    private int[] clipOf = new int[0]; // the clip index the element's primitives use
    private int[] ownClip = new int[0]; // the clip index the element gives its descendants, 0 if it clips nothing
    private int[] clipOwner = new int[0]; // the nearest clipping ancestor, or NONE
    private int[] clipStamp = new int[0];
    private float[] clipRect = new float[0];
    private boolean[] region = new boolean[0]; // a scroll region: its scroll moves its clip's offset, not its content
    private float[] shiftX = new float[0]; // per clip owner: the scroll its content is shifted by (cached per pass)
    private float[] shiftY = new float[0];
    private int[] shiftStamp = new int[0];
    private int shiftPass = 1;
    private float[] appliedShiftX = new float[0]; // per clip owner: the shift its clip's offset holds
    private float[] appliedShiftY = new float[0];
    private float[] recRect = new float[0]; // per slot: the rectangle last recorded, in its clip's content space
    private int clipPass;
    private boolean clipsMoved;
    private final Element[] previews = new Element[UiInput.MAX_POINTERS];
    private int[] codeLayout = new int[0];
    private FrameListener[] listeners = new FrameListener[4];
    private int listenerCount;
    private UiInput frameInput;
    private long frameNanos;
    private boolean inputGiven; // input() was called since the last update()

    private boolean[] rebuild = new boolean[8];
    private boolean[] rebuilt = new boolean[8];
    private long seenLayers = -1;
    private boolean worldSpace;
    private long seenCompletions = -1;
    private long seenTechnique = -1;
    private int recorded; // elements recorded in the last update
    private long frames;

    /** A UI styled by {@code sheet}, its text set by {@code text} (null: no text, for tests of boxes). */
    @AllowAllocation("one per UI")
    public Ui(StyleSheet sheet, TextSystem text) {
        this(sheet, text, text != null && text.atlas() != null ? null : new UiTextures());
    }

    /** As {@link #Ui(StyleSheet, TextSystem)}, its textures in {@code textures} (the atlas's, when it has one). */
    @AllowAllocation("one per UI")
    public Ui(StyleSheet sheet, TextSystem text, UiTextures textures) {
        this.store = new UiStore();
        this.resolver = new StyleResolver(store, sheet);
        this.layout = new Layout(store);
        this.text = text;
        UiTextures own = textures;
        if (own == null) {
            own = atlasTextures(text.atlas());
        }
        this.textures = own;
        this.images = new UiImages(own);
        this.layerStack = new Layers(store);
        this.interaction = new Interaction(layerStack, new Geometry());
        this.visuals = new Visuals(store, layout, resolver);
        this.animator = new Animator(store, visuals);
        visuals.restyler(this::restyleLayout);
        ensure();
        store.semantics(store.rootSlot(), Role.GROUP, null);
    }

    /** Applies {@code slot}'s style to its layout inputs again: a layout override was cleared. */
    private void restyleLayout(int slot) {
        ResolvedStyle style = applied[slot];
        if (style != null && store.alive(slot)) {
            apply(slot, style);
            visuals.applyLayout(slot);
        }
    }

    /** The per-element values animations and controls draw and lay out with instead of the style's. */
    public Visuals visuals() {
        return visuals;
    }

    /** The animations and style transitions of this UI. */
    public Animator animator() {
        return animator;
    }

    private static UiTextures atlasTextures(GlyphAtlas atlas) {
        return atlas.textures();
    }

    // ---------------------------------------------------------------- building

    /** The root: the screen the UI covers, the root of the {@link Layers#HUD} layer. */
    public Element root() {
        return store.root();
    }

    /**
     * The layer named {@code name} ({@link Layers#POPUP} and the other standard names), made the first time it is
     * asked for; add content under its {@link Layer#root()}.
     */
    public Layer layer(String name) {
        Layer layer = layerStack.get(name);
        ensure();
        return layer;
    }

    /** The layer stack: custom layers ({@link Layers#add}), inputs and modality per layer. */
    public Layers layerStack() {
        return layerStack;
    }

    /** The interaction: listeners, focus, capture, drag sessions, clipboard and text input. */
    public Interaction interaction() {
        return interaction;
    }

    /** Listens to events of {@code type} at {@code element}: {@link Interaction#on}. */
    public void on(Element element, EventType type, UiListener listener) {
        interaction.on(element, type, listener);
    }

    /** A new panel (type {@code panel}) with style name {@code style} (or null). */
    @AllowAllocation("elements are made when a UI is built")
    public Element panel(String style) {
        return element("panel", style);
    }

    /** A new element of {@code type} with style name {@code style} (or null). */
    @AllowAllocation("elements are made when a UI is built")
    public Element element(String type, String style) {
        Element e = store.create(type, null);
        if (style != null) {
            e.style(style);
        }
        ensure();
        content[e.slot()] = NONE;
        codeLayout[e.slot()] = 0;
        region[e.slot()] = false;
        painters[e.slot()] = null;
        parts[e.slot()] = false;
        applied[e.slot()] = null; // a reused slot applies its style again
        return e;
    }

    /**
     * Makes {@code painter} draw {@code element}'s content (a control's own drawing: text with a caret, a chart): it
     * measures the content for layout and records it after the element's box. Mark it with {@link #repaint} when its
     * look changed, {@link #remeasure} when its size may have.
     */
    public void paint(Element element, Painter painter) {
        int slot = element.slot();
        content[slot] = painter == null ? NONE : PAINTED;
        painters[slot] = painter;
        layout.measured(slot, painter != null);
        store.markDirty(slot, UiStore.DIRTY_LAYOUT | UiStore.DIRTY_DRAW);
    }

    /** Records {@code element} again this frame (its painter's look changed). */
    public void repaint(Element element) {
        store.markDirty(element.slot(), UiStore.DIRTY_DRAW);
    }

    /** Measures and records {@code element} again this frame (its painter's content changed size). */
    public void remeasure(Element element) {
        store.markDirty(element.slot(), UiStore.DIRTY_LAYOUT | UiStore.DIRTY_DRAW);
    }

    /**
     * A style part of {@code owner}: a hidden child of {@code type} with style name {@code style} (or null) that takes
     * no space and draws nothing, whose resolved style ({@code styles().styleOf(part.slot())}) a painter reads for a
     * piece of its drawing (a caret, a selection, a span class), so themes style the pieces with ordinary keys and
     * selectors ({@code textfield:focused caret}, {@code span.warning}). Set state bits on the part for state looks
     * ({@code link:hover}). When a part's style changes, its owner measures and records again.
     */
    @AllowAllocation("elements are made when a UI is built")
    public Element part(Element owner, String type, String style) {
        Element e = element(type, style);
        parts[e.slot()] = true;
        layout.visible(e.slot(), false);
        owner.add(e);
        return e;
    }

    /** Whether {@code element} is a {@linkplain #part style part}. */
    public boolean isPart(Element element) {
        return parts[element.slot()];
    }

    /** A new label (type {@code label}) showing {@code initial}. */
    @AllowAllocation("elements are made when a UI is built")
    public Element label(String style, CharSequence initial) {
        if (text == null) {
            throw new IllegalStateException("a UI without a text system has no labels");
        }
        Element e = element("label", style);
        int slot = e.slot();
        content[slot] = TEXT;
        if (blocks[slot] == null) {
            blocks[slot] = new TextBlock(text);
            strings[slot] = new StringBuilder();
        }
        blocks[slot].worldSpace(worldSpace);
        strings[slot].setLength(0);
        strings[slot].append(initial);
        drawnTextVersion[slot] = -1;
        layout.measured(slot, true);
        if (textSlotCount == textSlots.length) {
            textSlots = Arrays.copyOf(textSlots, textSlotCount * 2);
        }
        textSlots[textSlotCount++] = slot;
        e.semantics(Role.TEXT, strings[slot]); // the live text: the name follows text()
        return e;
    }

    /** A new image element (type {@code image}); the image and its fit come from the style's {@code image}. */
    @AllowAllocation("elements are made when a UI is built")
    public Element image(String style) {
        Element e = element("image", style);
        content[e.slot()] = IMAGE;
        layout.measured(e.slot(), true);
        e.semantics(Role.IMAGE, null);
        return e;
    }

    /** Sets a label's text; nothing happens (and nothing allocates) when it is the same. */
    public void text(Element label, CharSequence value) {
        int slot = label.slot();
        if (content[slot] != TEXT) {
            throw new IllegalArgumentException(label + " is not a label");
        }
        StringBuilder s = strings[slot];
        if (s.length() == value.length() && contentEquals(s, value)) {
            return;
        }
        s.setLength(0);
        s.append(value);
        store.markDirty(slot, UiStore.DIRTY_TEXT | UiStore.DIRTY_LAYOUT | UiStore.DIRTY_DRAW);
    }

    private static boolean contentEquals(StringBuilder a, CharSequence b) {
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** A label's current text. */
    public CharSequence text(Element label) {
        return strings[label.slot()];
    }

    /** Destroys {@code element} and its subtree. */
    public void destroy(Element element) {
        int slot = element.slot();
        element.destroy();
        for (int i = 0; i < textSlotCount; i++) {
            if (!store.alive(textSlots[i])) {
                textSlots[i--] = textSlots[--textSlotCount];
            }
        }
        content[slot] = NONE;
        painters[slot] = null;
        rebuildAll();
    }

    private void rebuildAll() {
        for (int i = 0; i < rebuild.length; i++) {
            rebuild[i] = true;
        }
    }

    /**
     * Marks this UI as a world-space panel (3D or VR: its text draws MTSDF at every size, sharp at any distance and
     * angle) or a screen UI (the default: small text may draw hinted raster glyphs, {@code ui.text.smallText}). Changes
     * no layout; the labels record again.
     */
    public void worldSpace(boolean on) {
        if (on == worldSpace) {
            return;
        }
        worldSpace = on;
        for (int i = 0; i < textSlotCount; i++) {
            int slot = textSlots[i];
            blocks[slot].worldSpace(on);
            store.markDirty(slot, UiStore.DIRTY_DRAW);
        }
    }

    /** Whether this UI is a world-space panel. */
    public boolean worldSpace() {
        return worldSpace;
    }

    /**
     * Makes code own some of {@code element}'s layout inputs ({@link #CODE_KIND}, {@link #CODE_PLACEMENT},
     * {@link #CODE_WIDTH}, {@link #CODE_HEIGHT}, {@link #CODE_FLEX} or'ed): the style's values for them are no longer applied, so what a
     * control sets on the {@link #layout()} stays when the element's style resolves again (a state changed). The rest
     * still comes from the style.
     */
    public void codeLayout(Element element, int bits) {
        codeLayout[element.slot()] = bits;
    }

    /** Adds {@code bits} to the layout inputs code owns for the element in {@code slot} ({@link #codeLayout}). */
    public void ownLayout(int slot, int bits) {
        codeLayout[slot] |= bits;
    }

    /**
     * Makes {@code element} a scroll region (or not): its children are recorded unscrolled under a clip of its own,
     * whose offset follows its {@code Layout.scroll} (and its ancestors'), so scrolling moves one clip entry instead of
     * recording the content again (design 6.1). The region clips like its nearest clipping ancestor; picking and
     * everything reading the layout see the scrolled positions, which are where the content draws. Scroll views make
     * their scrolled element one.
     */
    public void scrollRegion(Element element, boolean on) {
        int slot = element.slot();
        if (region[slot] != on) {
            region[slot] = on;
            int li = layerStack.indexOf(slot);
            if (li >= 0) {
                rebuild[li] = true; // clip indices are given out in tree order
            }
        }
    }

    /** Whether {@code element} is a {@linkplain #scrollRegion scroll region}. */
    public boolean scrollRegion(Element element) {
        return region[element.slot()];
    }

    /** The layout inputs code owns for {@code element} ({@link #codeLayout}). */
    public int codeLayout(Element element) {
        return codeLayout[element.slot()];
    }

    /** Runs {@code listener} in every {@link #update} ({@link FrameListener}'s phases), after those added before. */
    @AllowAllocation("listeners are added when a UI is built")
    public void addFrameListener(FrameListener listener) {
        if (listenerCount == listeners.length) {
            listeners = Arrays.copyOf(listeners, listenerCount * 2);
        }
        listeners[listenerCount++] = listener;
    }

    /** Stops running {@code listener}. */
    public void removeFrameListener(FrameListener listener) {
        for (int i = 0; i < listenerCount; i++) {
            if (listeners[i] == listener) {
                System.arraycopy(listeners, i + 1, listeners, i, listenerCount - i - 1);
                listeners[--listenerCount] = null;
                return;
            }
        }
    }

    /**
     * The input of the frame being updated: what {@link #input(UiInput, long)} processed this frame, in both phases
     * (null in frames without input): for frame listeners.
     */
    public UiInput input() {
        return frameInput;
    }

    /** Follows a development file watch: each update applies its last good sheet. */
    public void watch(StyleWatcher sheets) {
        this.watcher = sheets;
    }

    @AllowAllocation("growing with the store, rarely")
    private void ensure() {
        int n = store.capacity();
        if (content.length < n) {
            content = Arrays.copyOf(content, n);
            codeLayout = Arrays.copyOf(codeLayout, n);
            painters = Arrays.copyOf(painters, n);
            parts = Arrays.copyOf(parts, n);
            blocks = Arrays.copyOf(blocks, n);
            strings = Arrays.copyOf(strings, n);
            drawnTextVersion = Arrays.copyOf(drawnTextVersion, n);
            applied = Arrays.copyOf(applied, n);
            appliedVersion = Arrays.copyOf(appliedVersion, n);
            clipOf = Arrays.copyOf(clipOf, n);
            ownClip = Arrays.copyOf(ownClip, n);
            clipOwner = Arrays.copyOf(clipOwner, n);
            clipStamp = Arrays.copyOf(clipStamp, n);
            clipRect = Arrays.copyOf(clipRect, n * 4);
            int old = region.length;
            region = Arrays.copyOf(region, n);
            shiftX = Arrays.copyOf(shiftX, n);
            shiftY = Arrays.copyOf(shiftY, n);
            shiftStamp = Arrays.copyOf(shiftStamp, n);
            appliedShiftX = Arrays.copyOf(appliedShiftX, n);
            appliedShiftY = Arrays.copyOf(appliedShiftY, n);
            recRect = Arrays.copyOf(recRect, n * 4);
            Arrays.fill(recRect, old * 4, n * 4, Float.NaN);
        }
        if (rebuild.length < layerStack.count()) {
            rebuild = Arrays.copyOf(rebuild, layerStack.count() * 2);
            rebuilt = Arrays.copyOf(rebuilt, layerStack.count() * 2);
        }
    }

    // ---------------------------------------------------------------- the frame

    /**
     * The frame's first phase, at its start: processes {@code input} (null: none) at time {@code nanos} on the UI
     * clock (real time, for long presses, double clicks and tooltips). Pointers are picked against the layout drawn
     * last frame, events dispatched, states and focus changed, commands and text routed, so game code running after it
     * sees this frame's clicks, focus and text. Fill {@code input} first ({@link UiInput#beginFrame()}, then the
     * sources). The second phase, {@link #update(float, float)}, follows after the game's update; listeners there read
     * this input through {@link #input()}.
     */
    public void input(UiInput input, long nanos) {
        if (layerStack.version() != seenLayers) {
            seenLayers = layerStack.version();
            ensure();
            rebuildAll();
        }
        frameInput = input;
        frameNanos = nanos;
        inputGiven = true;
        if (input != null) {
            interaction.process(input, nanos);
            ensure();
        }
    }

    /**
     * The frame's second phase: brings the UI up to date for a {@code width x height} pixel screen: runs the frame
     * listeners (with the input and time of this frame's {@link #input}, if it had one), animations, hands over
     * finished glyphs, applies a reloaded sheet and token changes, resolves the styles of changed elements, lays out
     * and records what changed. Call once a frame after game code changed the UI's state (so its changes show in this
     * frame), before submitting. Without an {@link #input} call this frame it runs without input, at time 0.
     */
    public void update(float width, float height) {
        frames++;
        recorded = 0;
        long nanos = inputGiven ? frameNanos : 0;
        if (!inputGiven) {
            frameInput = null;
        }
        inputGiven = false;
        if (layerStack.version() != seenLayers) {
            seenLayers = layerStack.version();
            ensure();
            rebuildAll();
        }
        for (int i = 0; i < listenerCount; i++) {
            listeners[i].frame(nanos);
        }
        ensure();
        animator.advance(nanos);
        attachPreviews(); // before styles: a new preview (and the drag layer) is styled this frame
        GlyphAtlas atlas = text == null ? null : text.atlas();
        if (atlas != null) {
            atlas.beginFrame();
            long technique = atlas.settings().techniqueVersion();
            if (atlas.completions() != seenCompletions || technique != seenTechnique) {
                seenCompletions = atlas.completions();
                seenTechnique = technique;
                for (int i = 0; i < textSlotCount; i++) {
                    int slot = textSlots[i];
                    if (blocks[slot].version() != drawnTextVersion[slot]) {
                        store.markDirty(slot, UiStore.DIRTY_DRAW);
                    }
                }
            }
        }
        if (watcher != null) {
            watcher.apply(resolver);
        }
        resolver.update();
        ensure();
        resolveQueued();
        placePreviews(); // after styles: a restyled preview keeps the place set here
        layOut(width, height);
        for (int pass = 1; listenerCount > 0; pass++) {
            for (int i = 0; i < listenerCount; i++) {
                listeners[i].afterLayout();
            }
            if (pass == MAX_PASSES || !relayoutPending()) {
                break;
            }
            ensure();
            resolveQueued(); // what the listeners changed: states, facts, layout inputs
            placePreviews();
            layOut(width, height);
        }
        recordChanges();
    }

    /**
     * Both phases at once: {@link #input(UiInput, long)} then {@link #update(float, float)}, for a frame whose game code
     * changes nothing in between (tests, tools). An engine calls {@code input} at the frame's start and {@code update}
     * after the game's update, so neither the game's reaction to input nor its changes to the UI wait a frame.
     */
    public void update(float width, float height, UiInput input, long nanos) {
        input(input, nanos);
        update(width, height);
    }

    /** Records what changed into the layers' draw lists and ends the frame. */
    private void recordChanges() {
        for (int li = 0; li < layerStack.count(); li++) {
            rebuilt[li] = rebuild[li];
            if (rebuild[li]) {
                rebuildLayer(li);
            }
        }
        for (int i = 0; i < store.dirtyCount(); i++) {
            int slot = store.dirtySlots()[i];
            if (store.alive(slot) && (store.dirty(slot) & UiStore.DIRTY_DRAW) != 0) {
                store.clearDirty(slot, UiStore.DIRTY_DRAW);
                int li = layerStack.indexOf(slot);
                if (li >= 0 && !rebuilt[li] && !record(slot, layerStack.at(li).drawList())) {
                    rebuildLayer(li);
                    rebuilt[li] = true;
                }
            }
        }
        if (clipsMoved) {
            clipsMoved = false;
            moveClips();
        }
        for (int i = 0; i < store.dirtyCount(); i++) {
            store.clearDirty(store.dirtySlots()[i], -1);
        }
        store.clearDirtyQueue();
        interaction.laidOut();
    }

    /** Resolves and applies the styles of the queued elements. */
    private void resolveQueued() {
        // styles and text: the queue grows while resolving (children of changed contexts)
        for (int i = 0; i < store.dirtyCount(); i++) {
            int slot = store.dirtySlots()[i];
            if (!store.alive(slot)) {
                continue;
            }
            int bits = store.dirty(slot);
            if ((bits & UiStore.DIRTY_STYLE) != 0) {
                resolver.resolve(slot);
            }
            // compared, not taken from the bit: resolving a child resolves its parents first and clears their bits
            ResolvedStyle style = resolver.styleOf(slot);
            if (style != applied[slot] || style.version() != appliedVersion[slot]) {
                ResolvedStyle before = applied[slot];
                applied[slot] = style;
                appliedVersion[slot] = style.version();
                apply(slot, style);
                visuals.applyLayout(slot);
                restyled(slot, before, style);
                animator.restyled(slot, before, style);
                if (before != null && (before.getFloat(Styles.OPACITY) != style.getFloat(Styles.OPACITY)
                    || before.getFloat(Styles.SCALE) != style.getFloat(Styles.SCALE))) {
                    visuals.redrawSubtree(slot); // opacity and scale apply to the subtree
                }
                store.markDirty(slot, UiStore.DIRTY_DRAW | UiStore.DIRTY_TEXT);
                if (content[slot] == PAINTED) {
                    store.markDirty(slot, UiStore.DIRTY_LAYOUT); // its painter may measure fonts or sizes
                }
                if (parts[slot]) {
                    int owner = store.parent(slot);
                    if (owner != UiStore.NONE) {
                        store.markDirty(owner, UiStore.DIRTY_LAYOUT | UiStore.DIRTY_DRAW);
                    }
                }
            }
            if ((store.dirty(slot) & UiStore.DIRTY_STRUCTURE) != 0) {
                int li = layerStack.indexOf(slot);
                if (li >= 0) {
                    rebuild[li] = true;
                }
                interaction.picking().invalidate(slot);
            }
        }
    }

    /** Lays out what changed; what moved records again and tells picking. */
    private void layOut(float width, float height) {
        layout.update(measure, width, height);
        shiftPass++;
        int[] moved = layout.changedSlots();
        for (int i = 0; i < layout.changedCount(); i++) {
            int slot = moved[i];
            int owner = clipOwner[slot];
            if (owner != UiStore.NONE && store.alive(owner)) {
                shift(owner);
                if (shiftX[owner] != appliedShiftX[owner] || shiftY[owner] != appliedShiftY[owner]) {
                    clipsMoved = true; // a region scrolled: its clip's offset moves
                }
            }
            if (!recordedAt(slot, owner)) {
                store.markDirty(slot, UiStore.DIRTY_DRAW); // not just scrolled with its region: record again
            }
            interaction.picking().moved(slot);
            if (visuals.transformed(slot)) {
                visuals.redrawSubtree(slot); // the centre its subtree scales about moved
            }
            if (ownClip[slot] != DrawList.NO_CLIP) {
                clipsMoved = true;
            }
        }
    }

    /**
     * The scroll {@code owner}'s content is shifted by, in UI pixels: its own and its ancestors' (whole pixels when
     * the layout snaps), cached for this layout pass in {@link #shiftX}, {@link #shiftY}.
     */
    private void shift(int owner) {
        if (shiftStamp[owner] == shiftPass) {
            return;
        }
        shiftStamp[owner] = shiftPass;
        float x = 0;
        float y = 0;
        for (int a = owner; a != UiStore.NONE; a = store.parent(a)) {
            x += layout.scrollPixelsX(a);
            y += layout.scrollPixelsY(a);
        }
        shiftX[owner] = x;
        shiftY[owner] = y;
    }

    /** Whether {@code slot} is recorded where it is now, in the content space of its clip (owned by {@code owner}). */
    private boolean recordedAt(int slot, int owner) {
        float sx = 0;
        float sy = 0;
        if (owner != UiStore.NONE && store.alive(owner)) {
            sx = shiftX[owner];
            sy = shiftY[owner];
        }
        int o = slot * 4;
        return recRect[o] == layout.x(slot) + sx && recRect[o + 1] == layout.y(slot) + sy
            && recRect[o + 2] == layout.width(slot) && recRect[o + 3] == layout.height(slot);
    }

    /** Whether a listener changed styles or layout inputs after layout: another pass resolves and lays them out. */
    private boolean relayoutPending() {
        int[] queue = store.dirtySlots();
        for (int i = 0; i < store.dirtyCount(); i++) {
            int slot = queue[i];
            if (store.alive(slot) && (store.dirty(slot) & (UiStore.DIRTY_STYLE | UiStore.DIRTY_LAYOUT)) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Tells picking about a new style: a changed shape, clip or pointer-events of an element it may hold. */
    private void restyled(int slot, ResolvedStyle before, ResolvedStyle after) {
        boolean clips = after.get(Styles.CLIP);
        boolean clipChanged = before == null || clips != before.get(Styles.CLIP).booleanValue();
        if (clipChanged) {
            int li = layerStack.indexOf(slot);
            if (li >= 0) {
                rebuild[li] = true; // clip indices are given out in tree order
            }
        }
        if (clipChanged || after.get(Styles.POINTER_EVENTS) != before.get(Styles.POINTER_EVENTS)
            || !after.get(Styles.RADIUS).equals(before.get(Styles.RADIUS))) {
            interaction.picking().invalidate(slot);
        }
    }

    /** Puts drag previews on the drag layer; takes those of ended drags off. */
    private void attachPreviews() {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            DragSession session = interaction.sessionAt(p);
            Element preview = session.active() ? session.preview() : null;
            Element shown = previews[p];
            if (shown != null && shown != preview) {
                if (shown.alive() && shown.parent() != null) {
                    shown.detach();
                }
                previews[p] = null;
            }
            if (preview == null || !preview.alive()) {
                continue;
            }
            Layer drag = layerStack.get(Layers.DRAG);
            ensure();
            if (layerStack.version() != seenLayers) {
                seenLayers = layerStack.version();
                rebuildAll();
            }
            if (store.parent(preview.slot()) != drag.rootSlot()) {
                drag.root().add(preview);
            }
            previews[p] = preview;
        }
    }

    /** Puts drag previews under their pointers. */
    private void placePreviews() {
        for (int p = 0; p < UiInput.MAX_POINTERS; p++) {
            Element preview = previews[p];
            if (preview == null) {
                continue;
            }
            DragSession session = interaction.sessionAt(p);
            float scale = layout.scale() > 0 ? layout.scale() : 1;
            layout.offsets(preview.slot(), (session.x() - session.previewOffsetX()) / scale,
                (session.y() - session.previewOffsetY()) / scale, 0, 0);
        }
    }

    // ---------------------------------------------------------------- styles onto layout and text

    private void apply(int slot, ResolvedStyle s) {
        Border border = s.get(Styles.BORDER);
        Insets padding = s.get(Styles.PADDING);
        Insets margin = s.get(Styles.MARGIN);
        float bw = border.width();
        int code = codeLayout[slot];
        if ((code & CODE_KIND) == 0) {
            layout.kind(slot, KINDS[s.get(Styles.LAYOUT).ordinal()])
                .direction(slot, DIRECTIONS[s.get(Styles.DIRECTION).ordinal()]);
        }
        layout.wrap(slot, WRAPS[s.get(Styles.WRAP).ordinal()])
            .justifyContent(slot, JUSTIFIES[s.get(Styles.JUSTIFY_CONTENT).ordinal()])
            .alignItems(slot, ALIGNS[s.get(Styles.ALIGN_ITEMS).ordinal()])
            .alignSelf(slot, ALIGNS[s.get(Styles.ALIGN_SELF).ordinal()])
            .gap(slot, s.getFloat(Styles.GAP), s.getFloat(Styles.GAP))
            .padding(slot, padding.top() + bw, padding.right() + bw, padding.bottom() + bw, padding.left() + bw)
            .margin(slot, margin.top(), margin.right(), margin.bottom(), margin.left())
            .aspectRatio(slot, s.getFloat(Styles.ASPECT_RATIO))
            .region(slot, REGIONS[s.get(Styles.REGION).ordinal()])
            .fontSize(slot, s.getFloat(Styles.FONT_SIZE));
        if ((code & CODE_FLEX) == 0) {
            Length basis = s.get(Styles.BASIS);
            layout.grow(slot, s.getFloat(Styles.GROW)).shrink(slot, s.getFloat(Styles.SHRINK))
                .basis(slot, basis.value(), UNITS[basis.unit().ordinal()]);
        }
        Length v = s.get(Styles.WIDTH);
        if ((code & CODE_WIDTH) == 0) {
            layout.width(slot, v.value(), UNITS[v.unit().ordinal()]);
        }
        v = s.get(Styles.HEIGHT);
        if ((code & CODE_HEIGHT) == 0) {
            layout.height(slot, v.value(), UNITS[v.unit().ordinal()]);
        }
        v = s.get(Styles.MIN_WIDTH);
        layout.minWidth(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MIN_HEIGHT);
        layout.minHeight(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MAX_WIDTH);
        layout.maxWidth(slot, v.value(), UNITS[v.unit().ordinal()]);
        v = s.get(Styles.MAX_HEIGHT);
        layout.maxHeight(slot, v.value(), UNITS[v.unit().ordinal()]);
        if ((code & CODE_PLACEMENT) == 0) {
            Anchors anchors = s.get(Styles.ANCHORS);
            layout.anchors(slot, anchors.left(), anchors.top(), anchors.right(), anchors.bottom());
            Insets offsets = s.get(Styles.OFFSETS);
            layout.offsets(slot, offsets.left(), offsets.top(), offsets.right(), offsets.bottom());
        }
        GridPlacement column = s.get(Styles.GRID_COLUMN);
        layout.gridColumnPlacement(slot, column.start(), column.span());
        GridPlacement row = s.get(Styles.GRID_ROW);
        layout.gridRowPlacement(slot, row.start(), row.span());
        List<Track> columns = s.get(Styles.GRID_TEMPLATE_COLUMNS);
        layout.gridColumns(slot, columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Track t = columns.get(i);
            layout.gridColumn(slot, i, minUnit(t), minValue(t), maxUnit(t), maxValue(t));
        }
        List<Track> rows = s.get(Styles.GRID_TEMPLATE_ROWS);
        layout.gridRows(slot, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Track t = rows.get(i);
            layout.gridRow(slot, i, minUnit(t), minValue(t), maxUnit(t), maxValue(t));
        }
    }

    private static Unit minUnit(Track t) {
        return switch (t) {
            case Track.Fixed f -> Unit.PX;
            case Track.MinMax m -> minUnit(m.min());
            default -> Unit.AUTO;
        };
    }

    private static float minValue(Track t) {
        return switch (t) {
            case Track.Fixed f -> f.px();
            case Track.MinMax m -> minValue(m.min());
            default -> 0;
        };
    }

    private static Unit maxUnit(Track t) {
        return switch (t) {
            case Track.Fixed f -> Unit.PX;
            case Track.Fraction f -> Unit.FR;
            case Track.MinMax m -> maxUnit(m.max());
            default -> Unit.AUTO;
        };
    }

    private static float maxValue(Track t) {
        return switch (t) {
            case Track.Fixed f -> f.px();
            case Track.Fraction f -> f.fr();
            case Track.MinMax m -> maxValue(m.max());
            default -> 0;
        };
    }

    /** Sets the label's text block for a box {@code maxWidthPx} pixels wide. */
    private boolean shape(int slot, float maxWidthPx) {
        ResolvedStyle s = resolver.styleOf(slot);
        float scale = layout.scale();
        return blocks[slot].set(strings[slot], s.get(Styles.FONT_FAMILY), s.getFloat(Styles.FONT_SIZE) * scale,
            s.getInt(Styles.FONT_WEIGHT), s.get(Styles.FONT_STYLE) != FontStyle.NORMAL,
            TEXT_ALIGNS[s.get(Styles.TEXT_ALIGN).ordinal()], maxWidthPx, s.getFloat(Styles.LINE_HEIGHT));
    }

    private long measure(int slot, float width, int widthMode, float height, int heightMode) {
        float scale = layout.scale();
        if (content[slot] == TEXT) {
            shape(slot, widthMode == Measure.UNDEFINED ? Float.POSITIVE_INFINITY : width * scale + 0.5f);
            TextBlock b = blocks[slot];
            return Measure.size(b.width() / scale, b.height() / scale);
        }
        if (content[slot] == IMAGE) {
            UiTexture t = images.get(resolver.styleOf(slot).get(Styles.IMAGE).name());
            return t == null ? Measure.size(0, 0) : Measure.size(t.width(), t.height());
        }
        if (content[slot] == PAINTED) {
            return painters[slot].measure(slot, width, widthMode, height, heightMode);
        }
        return Measure.size(0, 0);
    }

    // ---------------------------------------------------------------- drawing

    private void rebuildLayer(int li) {
        rebuild[li] = false;
        DrawList list = layerStack.at(li).drawList();
        list.reset();
        allocate(layerStack.at(li).rootSlot(), list, UiStore.NONE);
    }

    /**
     * Gives {@code slot} and its subtree ranges in tree order (parents under children), recording them; {@code owner}
     * is the nearest clipping ancestor.
     */
    private void allocate(int slot, DrawList list, int owner) {
        clipOwner[slot] = owner;
        clipOf[slot] = owner == UiStore.NONE ? DrawList.NO_CLIP : ownClip[owner];
        int start = list.size();
        ownClip[slot] = DrawList.NO_CLIP; // its clip is made below: the count leaves it out
        list.begin(start, 0); // count only
        rec = list;
        recordPrimitives(slot);
        list.end();
        int needed = list.needed();
        // text grows by a few glyphs (a counter's digits), painted content by its painter's spare and focusable
        // elements by a focus ring without a rebuild
        int capacity = needed + (content[slot] == TEXT ? Math.max(4, needed / 4) : 0)
            + (content[slot] == PAINTED ? painters[slot].spare(needed) : 0)
            + (interaction.focusable(store.element(slot)) ? 1 : 0);
        if (capacity > 0) {
            start = list.allocate(capacity);
        }
        store.range(slot, start, capacity);
        int childOwner = owner;
        ownClip[slot] = DrawList.NO_CLIP;
        if (resolver.styleOf(slot).get(Styles.CLIP)) { // before recording: painted content clips to it
            clipBox(slot, owner);
            int o = slot * 4;
            ownClip[slot] = list.addClip(clipRect[o], clipRect[o + 1], clipRect[o + 2], clipRect[o + 3],
                clipRadius(slot));
            childOwner = slot;
        } else if (region[slot]) { // a scroll region clips like its owner; its clip carries the scroll
            regionBox(slot, owner);
            int o = slot * 4;
            ownClip[slot] = list.addClip(clipRect[o], clipRect[o + 1], clipRect[o + 2], clipRect[o + 3],
                owner == UiStore.NONE ? 0 : clipRadius(owner));
            childOwner = slot;
        }
        if (ownClip[slot] != DrawList.NO_CLIP) {
            shift(slot);
            list.setClipOffset(ownClip[slot], -shiftX[slot], -shiftY[slot]);
            appliedShiftX[slot] = shiftX[slot];
            appliedShiftY[slot] = shiftY[slot];
        }
        if (needed > 0) {
            record(slot, list);
        } else {
            remember(slot); // nothing to draw: moving with its region needs no recording either
        }
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            allocate(c, list, childOwner);
        }
    }

    /** The clip rectangle of {@code slot}: its box within its clipping ancestor's. */
    private void clipBox(int slot, int owner) {
        int o = slot * 4;
        float x0 = layout.x(slot);
        float y0 = layout.y(slot);
        float x1 = x0 + layout.width(slot);
        float y1 = y0 + layout.height(slot);
        if (owner != UiStore.NONE) {
            int p = owner * 4;
            x0 = Math.max(x0, clipRect[p]);
            y0 = Math.max(y0, clipRect[p + 1]);
            x1 = Math.min(x1, clipRect[p + 2]);
            y1 = Math.min(y1, clipRect[p + 3]);
        }
        clipRect[o] = x0;
        clipRect[o + 1] = y0;
        clipRect[o + 2] = x1;
        clipRect[o + 3] = y1;
    }

    /** A scroll region's clip rectangle: its clipping ancestor's (none: everything). */
    private void regionBox(int slot, int owner) {
        int o = slot * 4;
        if (owner != UiStore.NONE) {
            System.arraycopy(clipRect, owner * 4, clipRect, o, 4);
        } else {
            clipRect[o] = -Float.MAX_VALUE;
            clipRect[o + 1] = -Float.MAX_VALUE;
            clipRect[o + 2] = Float.MAX_VALUE;
            clipRect[o + 3] = Float.MAX_VALUE;
        }
    }

    private float clipRadius(int slot) {
        return resolver.styleOf(slot).get(Styles.RADIUS).topLeft() * layout.scale();
    }

    /** Moves every clip after a clipping element moved: outer clips first, each within its owner's. */
    private void moveClips() {
        clipPass++;
        for (int s = 0; s < store.highWater(); s++) {
            if (store.alive(s) && ownClip[s] != DrawList.NO_CLIP) {
                moveClip(s);
            }
        }
    }

    private void moveClip(int slot) {
        if (clipStamp[slot] == clipPass) {
            return;
        }
        clipStamp[slot] = clipPass;
        int owner = clipOwner[slot];
        if (owner != UiStore.NONE && store.alive(owner) && ownClip[owner] != DrawList.NO_CLIP) {
            moveClip(owner);
        }
        int li = layerStack.indexOf(slot);
        if (li < 0) {
            return;
        }
        boolean clips = resolver.styleOf(slot).get(Styles.CLIP);
        if (clips) {
            clipBox(slot, owner);
        } else {
            regionBox(slot, owner);
        }
        int o = slot * 4;
        DrawList list = layerStack.at(li).drawList();
        list.setClip(ownClip[slot], clipRect[o], clipRect[o + 1], clipRect[o + 2], clipRect[o + 3],
            clips ? clipRadius(slot) : owner == UiStore.NONE ? 0 : clipRadius(owner));
        shift(slot);
        list.setClipOffset(ownClip[slot], -shiftX[slot], -shiftY[slot]);
        appliedShiftX[slot] = shiftX[slot];
        appliedShiftY[slot] = shiftY[slot];
    }

    /** Records the element's primitives into its range; false if they do not fit (a rebuild gives it a larger one). */
    private boolean record(int slot, DrawList list) {
        list.begin(store.rangeStart(slot), store.rangeCapacity(slot));
        if (clipOf[slot] != DrawList.NO_CLIP) {
            list.clip(clipOf[slot]);
        }
        rec = list;
        remember(slot);
        recordPrimitives(slot);
        recorded++;
        return list.end();
    }

    /** Notes where {@code slot} is recorded: its rectangle in its clip's content space (the clip owner's shift). */
    private void remember(int slot) {
        int owner = clipOwner[slot];
        float sx = 0;
        float sy = 0;
        if (owner != UiStore.NONE && store.alive(owner)) {
            shift(owner);
            sx = shiftX[owner];
            sy = shiftY[owner];
        }
        recordShiftX = sx;
        recordShiftY = sy;
        int o = slot * 4;
        recRect[o] = layout.x(slot) + sx;
        recRect[o + 1] = layout.y(slot) + sy;
        recRect[o + 2] = layout.width(slot);
        recRect[o + 3] = layout.height(slot);
    }

    private void recordPrimitives(int slot) {
        if (store.isRoot(slot) && !resolver.styleOf(slot).isSet(Styles.BACKGROUND) || !layout.shown(slot)
            || parts[slot]) {
            return; // a hidden element (or one inside one) draws nothing; a style part's owner's painter reads its style
        }
        ResolvedStyle s = resolver.styleOf(slot);
        float scale = layout.scale();
        float x0 = layout.x(slot);
        float y0 = layout.y(slot);
        float x1 = x0 + layout.width(slot);
        float y1 = y0 + layout.height(slot);
        visuals.compose(slot);
        float opacity = visuals.composedOpacity();
        float composedX = visuals.composedX() + recordShiftX; // content space of its clip: unscrolled
        float composedY = visuals.composedY() + recordShiftY;
        if (visuals.composedScale() != 1 || composedX != 0 || composedY != 0) {
            rec.transform(visuals.composedScale(), composedX, composedY);
        }
        int overrides = visuals.flags(slot);
        Radii r = s.get(Styles.RADIUS);
        float tl = r.topLeft() * scale;
        float tr = r.topRight() * scale;
        float br = r.bottomRight() * scale;
        float bl = r.bottomLeft() * scale;

        List<Shadow> shadows = s.get(Styles.SHADOW);
        for (int i = 0; i < shadows.size(); i++) {
            Shadow sh = shadows.get(i);
            float spread = sh.spread() * scale;
            float dx = sh.x() * scale;
            float dy = sh.y() * scale;
            rec.shadow(x0 + dx - spread, y0 + dy - spread, x1 + dx + spread, y1 + dy + spread,
                Rgba.fade(sh.color().rgba(), opacity), tl + spread, tr + spread, br + spread, bl + spread,
                sh.blur() * scale * 0.5f);
        }

        Border border = s.get(Styles.BORDER);
        float bw = border.width() * scale;
        long borderColor = Rgba.fade((overrides & Visuals.BORDER_COLOR) != 0 ? visuals.borderColor(slot)
            : border.color().rgba(), opacity);
        boolean fill = (overrides & Visuals.BACKGROUND) != 0;
        Paint paint = s.get(Styles.BACKGROUND);
        switch (paint) {
            case Paint.Box box -> rec.box(x0, y0, x1, y1, Rgba.fade(fill ? visuals.backgroundA(slot)
                : box.color().rgba(), opacity), tl, tr, br, bl, bw, borderColor);
            case Paint.LinearGradient g -> rec.gradientBox(x0, y0, x1, y1, Rgba.fade(fill ? visuals.backgroundA(slot)
                : g.from().rgba(), opacity), Rgba.fade(fill ? visuals.backgroundB(slot) : g.to().rgba(), opacity),
                (g.angle() - 90) * RADIANS, tl, tr, br, bl, bw, borderColor);
            case Paint.Image image -> {
                UiTexture t = images.get(image.name());
                if (t != null) {
                    rec.image(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), Rgba.fade(image.tint().rgba(), opacity));
                }
                borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
            }
            case Paint.NineSlice nine -> {
                UiTexture t = images.get(nine.image());
                if (t != null) {
                    Insets d = nine.destination();
                    float l = d == null ? nine.left() * scale : d.left() * scale;
                    float tp = d == null ? nine.top() * scale : d.top() * scale;
                    float rt = d == null ? nine.right() * scale : d.right() * scale;
                    float bt = d == null ? nine.bottom() * scale : d.bottom() * scale;
                    rec.nineSlice(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), Rgba.fade(nine.tint().rgba(), opacity), l, tp, rt,
                        bt, nine.left(), nine.top(), nine.right(), nine.bottom());
                }
                borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
            }
            default -> borderOnly(x0, y0, x1, y1, tl, tr, br, bl, bw, borderColor);
        }

        Border ring = s.get(Styles.FOCUS_RING);
        if (ring.width() > 0) {
            float rw = ring.width() * scale;
            float off = s.getFloat(Styles.FOCUS_RING_OFFSET) * scale + rw;
            rec.box(x0 - off, y0 - off, x1 + off, y1 + off, Rgba.TRANSPARENT, ringRadius(tl, off), ringRadius(tr, off),
                ringRadius(br, off), ringRadius(bl, off), rw, Rgba.fade(ring.color().rgba(), opacity));
        }

        Insets padding = s.get(Styles.PADDING);
        float cx0 = x0 + bw + padding.left() * scale;
        float cy0 = y0 + bw + padding.top() * scale;
        float cx1 = x1 - bw - padding.right() * scale;
        float cy1 = y1 - bw - padding.bottom() * scale;
        if (content[slot] != NONE && ownClip[slot] != DrawList.NO_CLIP) {
            // content clips to its own element's box, as its children do (text that does not fit, a painter's)
            rec.clip(ownClip[slot]);
            shift(slot); // its own clip's content space: its own scroll too
            float ox = visuals.composedX() + shiftX[slot];
            float oy = visuals.composedY() + shiftY[slot];
            if (ox != composedX || oy != composedY) {
                rec.transform(visuals.composedScale(), ox, oy);
            }
        }
        if (content[slot] == TEXT) {
            shape(slot, cx1 - cx0 + 1); // snapping to whole pixels may have taken up to a pixel off the measured width
            TextOutline outline = s.get(Styles.TEXT_OUTLINE);
            TextBlock b = blocks[slot];
            long textColor = (overrides & Visuals.COLOR) != 0 ? visuals.color(slot) : s.rgba(Styles.COLOR);
            b.record(rec, cx0, cy0, Rgba.fade(textColor, opacity), outline.width() * scale,
                Rgba.fade(outline.color().rgba(), opacity), 0);
            drawnTextVersion[slot] = b.version();
        } else if (content[slot] == IMAGE) {
            recordImage(s.get(Styles.IMAGE), opacity, cx0, cy0, cx1, cy1);
        } else if (content[slot] == PAINTED) {
            painters[slot].paint(slot, rec, cx0, cy0, cx1, cy1, opacity);
        }
    }

    private static float ringRadius(float r, float off) {
        return r > 0 ? Math.max(0, r + off) : 0;
    }

    private void borderOnly(float x0, float y0, float x1, float y1, float tl, float tr, float br, float bl, float bw,
        long color) {
        if (bw > 0) {
            rec.box(x0, y0, x1, y1, Rgba.TRANSPARENT, tl, tr, br, bl, bw, color);
        }
    }

    private void recordImage(ImageSpec spec, float opacity, float x0, float y0, float x1, float y1) {
        UiTexture t = images.get(spec.name());
        if (t == null || x1 <= x0 || y1 <= y0) {
            return;
        }
        long tint = Rgba.fade(spec.tint().rgba(), opacity);
        float w = x1 - x0;
        float h = y1 - y0;
        float iw = t.width() * layout.scale();
        float ih = t.height() * layout.scale();
        ImageFit fit = spec.fit();
        if (fit == ImageFit.FILL) {
            rec.image(x0, y0, x1, y1, 0, 0, 1, 1, t.id(), tint);
        } else if (fit == ImageFit.COVER) {
            float k = Math.max(w / iw, h / ih);
            float du = (1 - w / (iw * k)) * 0.5f;
            float dv = (1 - h / (ih * k)) * 0.5f;
            rec.image(x0, y0, x1, y1, du, dv, 1 - du, 1 - dv, t.id(), tint);
        } else {
            float k = fit == ImageFit.CONTAIN ? Math.min(w / iw, h / ih) : 1;
            float dw = iw * k;
            float dh = ih * k;
            float ox = x0 + (w - dw) * 0.5f;
            float oy = y0 + (h - dh) * 0.5f;
            rec.image(ox, oy, ox + dw, oy + dh, 0, 0, 1, 1, t.id(), tint);
        }
    }

    // ---------------------------------------------------------------- access

    /** The element store. */
    public UiStore store() {
        return store;
    }

    /** The style resolver: tokens, inline styles, facts, a new sheet. */
    public StyleResolver styles() {
        return resolver;
    }

    /** The layout: positions and sizes after {@link #update}. */
    public Layout layout() {
        return layout;
    }

    /** The registered images. */
    public UiImages images() {
        return images;
    }

    /** The textures primitives name: atlas pages and images. */
    public UiTextures textures() {
        return textures;
    }

    /** The layers' draw lists, back to front: what the renderer draws. */
    public DrawList[] layers() {
        return layerStack.drawLists();
    }

    /** How many layers {@link #layers()} holds. */
    public int layerCount() {
        return layerStack.count();
    }

    /** The text system, or null. */
    public TextSystem textSystem() {
        return text;
    }

    /** Elements that recorded primitives in the last update. */
    public int recorded() {
        return recorded;
    }

    /** Updates so far. */
    public long frames() {
        return frames;
    }

    /** The tunables this UI reads, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public List<Tunable> tunables() {
        List<Tunable> all = new ArrayList<>();
        all.add(Layout.UI_SCALE);
        all.add(StyleResolver.CACHE_LIMIT);
        all.add(StyleWatcher.DELAY);
        all.add(Animator.SPEED);
        all.addAll(Interaction.tunables());
        all.addAll(com.truesim.ui.controls.text.TextEditor.tunables());
        all.add(com.truesim.ui.controls.text.NumberField.DRAG_PIXELS);
        all.addAll(com.truesim.ui.controls.basic.Controls.tunables());
        all.addAll(com.truesim.ui.controls.data.DataTunables.all());
        all.addAll(com.truesim.ui.controls.windows.Tooltips.tunables());
        all.addAll(com.truesim.ui.controls.windows.Popovers.tunables());
        all.addAll(com.truesim.ui.controls.windows.WindowManager.tunables());
        for (com.truesim.ui.core.TunableSource source : java.util.ServiceLoader.load(
            com.truesim.ui.core.TunableSource.class, Ui.class.getClassLoader())) {
            all.addAll(source.tunables()); // modules on top of the controls: charts
        }
        if (text != null && text.atlas() != null) {
            all.addAll(text.atlas().settings().all());
        }
        return all;
    }

    /** What picking and focus read: the layout's rectangles and the resolved styles' shape and interaction keys. */
    private final class Geometry implements PickGeometry {

        @Override
        public float x(int slot) {
            return layout.shown(slot) ? layout.x(slot) : 0;
        }

        @Override
        public float y(int slot) {
            return layout.shown(slot) ? layout.y(slot) : 0;
        }

        @Override
        public float width(int slot) {
            return layout.shown(slot) ? layout.width(slot) : 0;
        }

        @Override
        public float height(int slot) {
            return layout.shown(slot) ? layout.height(slot) : 0;
        }

        @Override
        public boolean shown(int slot) {
            return layout.shown(slot);
        }

        @Override
        public float radius(int slot, int corner) {
            Radii r = resolver.styleOf(slot).get(Styles.RADIUS);
            float v = switch (corner) {
                case 0 -> r.topLeft();
                case 1 -> r.topRight();
                case 2 -> r.bottomRight();
                default -> r.bottomLeft();
            };
            return v * layout.scale();
        }

        @Override
        public boolean clips(int slot) {
            return resolver.styleOf(slot).get(Styles.CLIP);
        }

        @Override
        public PointerEvents pointerEvents(int slot) {
            return resolver.styleOf(slot).get(Styles.POINTER_EVENTS);
        }

        @Override
        public Cursor cursor(int slot) {
            return resolver.styleOf(slot).get(Styles.CURSOR);
        }
    }
}
