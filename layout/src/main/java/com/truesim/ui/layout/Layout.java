package com.truesim.ui.layout;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Tunable;
import com.truesim.ui.core.UiStore;
import java.util.Arrays;

/**
 * Measure and arrange for the elements of one {@link UiStore} (design 4.6, UI14): per-slot layout inputs as
 * structure-of-arrays, set by the controls module from resolved styles, and per-slot outputs in UI pixels.
 *
 * <p><b>Inputs.</b> Every setter takes the element's slot, marks it {@code DIRTY_LAYOUT} when the value changes and is
 * a no-op otherwise, so styles can be applied every time they resolve. Lengths are in panel units; a slot reused for a
 * new element starts from the defaults again. Left and right of padding, margins, offsets and anchors are start and
 * end: right-to-left elements ({@link #textDirection}) mirror their children.
 *
 * <p><b>Update.</b> {@link #update} runs once per frame. It reads the store's dirty queue (without clearing it) for
 * {@code DIRTY_LAYOUT} slots, clears that bit, invalidates their caches and their ancestors' up to the first element
 * whose size cannot depend on its content (a fixed width and height), and lays out only those subtrees. Each element
 * caches its last arrangement and a few measurements by constraints, so unchanged siblings cost one comparison. Two
 * passes: measure (sizes for constraints) and arrange (the final exact sizes, recursively), then absolute positions,
 * scaled by {@code ui.scale} times the content scale and snapped to whole UI pixels.
 *
 * <p><b>Outputs.</b> {@link #x}, {@link #y}, {@link #width}, {@link #height} in UI pixels (the draw list's), and the
 * slots whose rectangle changed in this update: {@link #changedSlots()} (the first {@link #changedCount()}) or
 * {@link #changedIn(int)}, so only they record their drawing again.
 *
 * <p>Steady-state updates allocate nothing. Not thread-safe: the UI thread.
 */
public final class Layout {

    /** The UI scale players set, on top of the display's content scale. */
    public static final Tunable UI_SCALE =
        Tunable.of("ui.scale", 1, 0.5f, 4, "Size of the user interface, on top of the display's scale");

    static final int UNDEFINED = Measure.UNDEFINED;
    static final int EXACTLY = Measure.EXACTLY;
    static final int AT_MOST = Measure.AT_MOST;
    static final float INF = Float.POSITIVE_INFINITY;

    private static final LayoutKind[] KIND_VALUES = LayoutKind.values();
    static final byte K_NONE = 0;
    static final byte K_STACK = 1;
    static final byte K_FLEX = 2;
    static final byte K_GRID = 3;
    static final byte K_BORDER = 4;
    static final byte K_ANCHORS = 5;
    static final byte K_OVERLAY = 6;

    static final byte U_PX = 0;
    static final byte U_EM = 1;
    static final byte U_PERCENT = 2;
    static final byte U_AUTO = 3;
    static final byte U_FR = 4;

    static final byte A_AUTO = 0;
    static final byte A_START = 1;
    static final byte A_END = 2;
    static final byte A_CENTER = 3;
    static final byte A_STRETCH = 4;

    static final byte D_ROW = 0;
    static final byte D_COLUMN = 1;
    static final byte D_ROW_REVERSE = 2;
    static final byte D_COLUMN_REVERSE = 3;

    static final byte W_NO_WRAP = 0;
    static final byte W_WRAP_REVERSE = 2;

    static final byte R_CENTER = 0;
    static final byte R_NORTH = 1;
    static final byte R_SOUTH = 2;
    static final byte R_EAST = 3;
    static final byte R_WEST = 4;

    static final byte G_END = 0;
    static final byte G_BEGIN = 1;

    static final byte T_INHERIT = 0;
    static final byte T_RTL = 2;

    /** Measurements cached per element besides its arrangement. */
    static final int MEASURE_CACHE = 4;
    private static final int MC_FLOATS = 6;

    final UiStore store;
    private final Tunable scale;
    private float contentScale = 1;
    private boolean snapping = true;
    Measure measure;
    int capacity;

    // ---------------------------------------------------------------- inputs
    private Element[] owner;
    private boolean[] selfChanged;
    byte[] kind;
    byte[] direction;
    byte[] wrap;
    byte[] justify;
    byte[] alignItems;
    byte[] alignContent;
    byte[] justifyItems;
    byte[] alignSelf;
    byte[] justifySelf;
    byte[] region;
    byte[] growH;
    byte[] growV;
    byte[] textDir;
    boolean[] hidden;
    /** Inside a hidden element's subtree: collapsed to an empty rectangle, not {@link #shown}. */
    private boolean[] collapsed;
    boolean[] measured;
    float[] width;
    float[] height;
    float[] minWidth;
    float[] minHeight;
    float[] maxWidth;
    float[] maxHeight;
    float[] basis;
    byte[] widthUnit;
    byte[] heightUnit;
    byte[] minWidthUnit;
    byte[] minHeightUnit;
    byte[] maxWidthUnit;
    byte[] maxHeightUnit;
    byte[] basisUnit;
    float[] aspect;
    float[] grow;
    float[] shrink;
    float[] fontSize;
    float[] rowGap;
    float[] columnGap;
    float[] padTop;
    float[] padRight;
    float[] padBottom;
    float[] padLeft;
    float[] marTop;
    float[] marRight;
    float[] marBottom;
    float[] marLeft;
    float[] anchorLeft;
    float[] anchorTop;
    float[] anchorRight;
    float[] anchorBottom;
    float[] offLeft;
    float[] offTop;
    float[] offRight;
    float[] offBottom;
    int[] gridColumnStart;
    int[] gridColumnSpan;
    int[] gridRowStart;
    int[] gridRowSpan;
    /** Grid tracks, four floats each: minimum unit, minimum, maximum unit, maximum. */
    float[][] columnTracks;
    float[][] rowTracks;
    int[] columnCount;
    int[] rowCount;
    float[] scrollX;
    float[] scrollY;
    private boolean[] independent;
    boolean[] clipsContent;
    private boolean[] scrollMoved;
    private int[] scrollQueue = new int[16];
    private int scrollQueueCount;

    // ---------------------------------------------------------------- caches
    private boolean[] lcValid;
    private float[] lcAw;
    private float[] lcAh;
    private float[] lcOw;
    private float[] lcOh;
    private int[] lcModes;
    private float[] lcRw;
    private float[] lcRh;
    private boolean[] lcRtl;
    private float[] mc;
    private int[] mcModes;
    private byte[] mcCount;
    private byte[] mcNext;
    private int[] invalidAt;
    private int[] laidOutAt;
    private int[] visitedAt;

    // ---------------------------------------------------------------- scratch of a container for its children
    float[] sBasis;
    float[] sHypo;
    float[] sTarget;
    float[] sCross;
    float[] sTemp;
    float[] sAutoMin; // a flex item's automatic minimum main size, NaN until needed in this run
    // per slot, its min-content main size and what it was measured for (cross size, modes, owner sizes), kept until
    // its layout changes: an unchanged item does not measure it again
    float[] amValue;
    float[] amKey;
    int[] amModes;
    boolean[] amValid;
    float[] sCrossAvail; // the cross size its flex base size was measured in, for its min-content measure
    int[] sCrossMode;
    boolean[] sFrozen;
    int[] sCol;
    int[] sRow;
    int[] sColSpan;
    int[] sRowSpan;
    /** A stack of floats containers push their lines and tracks on (re-read after every nested call: it may grow). */
    float[] fs = new float[256];
    int fsTop;
    int[] is = new int[256];
    int isTop;

    // ---------------------------------------------------------------- outputs
    private float[] relX;
    private float[] relY;
    private float[] absX;
    private float[] absY;
    private float[] outX;
    private float[] outY;
    private float[] outW;
    private float[] outH;
    private int[] changedAt;
    private int[] changed = new int[64];
    private int changedCount;
    private int[] roots = new int[16];
    private int rootCount;

    /** Return registers of {@link #layoutNode}: the border-box size. */
    float resW;
    float resH;

    private int update;
    private float lastScale = -1;
    private boolean lastSnapping;
    private boolean[] topRan = new boolean[4];
    private float lastRootW = -1;
    private float lastRootH = -1;

    /** A layout of {@code store}'s elements scaled by {@link #UI_SCALE}. */
    public Layout(UiStore store) {
        this(store, UI_SCALE);
    }

    /** A layout of {@code store}'s elements scaled by {@code scale} (a world panel's own, say). */
    public Layout(UiStore store, Tunable scale) {
        this.store = store;
        this.scale = scale;
        allocate(store.capacity());
    }

    // ================================================================ setters: the container

    /** How the element arranges its children (default {@link LayoutKind#STACK}). */
    public Layout kind(int slot, LayoutKind value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (kind[slot] != v) {
            kind[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** The main axis of a stack or flex (default {@link Direction#COLUMN}). */
    public Layout direction(int slot, Direction value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (direction[slot] != v) {
            direction[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Whether flex items wrap (default {@link Wrap#NO_WRAP}). */
    public Layout wrap(int slot, Wrap value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (wrap[slot] != v) {
            wrap[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Main-axis distribution of a stack or flex (default {@link Justify#START}). */
    public Layout justifyContent(int slot, Justify value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (justify[slot] != v) {
            justify[slot] = v;
            changed(slot);
        }
        return this;
    }

    /**
     * Cross-axis alignment of the items of a stack or flex, vertical alignment in grid cells and overlays (default
     * {@link Align#STRETCH}).
     */
    public Layout alignItems(int slot, Align value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (alignItems[slot] != v) {
            alignItems[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Distribution of a wrapping flex's lines (default {@link AlignContent#START}). */
    public Layout alignContent(int slot, AlignContent value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (alignContent[slot] != v) {
            alignContent[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Horizontal alignment in grid cells and overlays (default {@link Align#STRETCH}). */
    public Layout justifyItems(int slot, Align value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (justifyItems[slot] != v) {
            justifyItems[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** The gaps between rows (or lines, or north and south regions) and between columns, in panel units. */
    public Layout gap(int slot, float row, float column) {
        own(slot);
        if (rowGap[slot] != row || columnGap[slot] != column) {
            rowGap[slot] = row;
            columnGap[slot] = column;
            changed(slot);
        }
        return this;
    }

    /** Padding in panel units, in ui-style's {@code Insets} order; left is the start side, right the end. */
    public Layout padding(int slot, float top, float right, float bottom, float left) {
        own(slot);
        if (padTop[slot] != top || padRight[slot] != right || padBottom[slot] != bottom || padLeft[slot] != left) {
            padTop[slot] = top;
            padRight[slot] = right;
            padBottom[slot] = bottom;
            padLeft[slot] = left;
            changed(slot);
        }
        return this;
    }

    /** The grid's explicit columns: {@code count} tracks, each {@code auto} until set by {@link #gridColumn}. */
    public Layout gridColumns(int slot, int count) {
        own(slot);
        columnTracks[slot] = tracks(columnTracks[slot], count);
        if (columnCount[slot] != count) {
            columnCount[slot] = count;
            changed(slot);
        }
        return this;
    }

    /** The grid's explicit rows: {@code count} tracks, each {@code auto} until set by {@link #gridRow}. */
    public Layout gridRows(int slot, int count) {
        own(slot);
        rowTracks[slot] = tracks(rowTracks[slot], count);
        if (rowCount[slot] != count) {
            rowCount[slot] = count;
            changed(slot);
        }
        return this;
    }

    /**
     * Column {@code index} (0-based) as {@code minmax(min, max)}: the minimum {@link Unit#PX} or {@link Unit#AUTO}
     * (content), the maximum {@link Unit#PX}, {@link Unit#AUTO} (content) or {@link Unit#FR}. ui-style's tracks map
     * as: {@code 120px} is {@code (PX 120, PX 120)}, {@code auto} {@code (AUTO, AUTO)}, {@code 2fr}
     * {@code (AUTO, FR 2)}.
     */
    public Layout gridColumn(int slot, int index, Unit minUnit, float min, Unit maxUnit, float max) {
        own(slot);
        if (setTrack(columnTracks[slot], index, minUnit, min, maxUnit, max)) {
            changed(slot);
        }
        return this;
    }

    /** Row {@code index} (0-based), as {@link #gridColumn}. */
    public Layout gridRow(int slot, int index, Unit minUnit, float min, Unit maxUnit, float max) {
        own(slot);
        if (setTrack(rowTracks[slot], index, minUnit, min, maxUnit, max)) {
            changed(slot);
        }
        return this;
    }

    @AllowAllocation("grid tracks are set when a style resolves; arrays grow rarely")
    private static float[] tracks(float[] tracks, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("negative track count");
        }
        if (tracks != null && tracks.length >= count * 4) {
            return tracks;
        }
        float[] grown = new float[count * 4];
        if (tracks != null) {
            System.arraycopy(tracks, 0, grown, 0, tracks.length);
        }
        for (int i = tracks == null ? 0 : tracks.length / 4; i < count; i++) {
            grown[i * 4] = U_AUTO;
            grown[i * 4 + 2] = U_AUTO;
        }
        return grown;
    }

    private static boolean setTrack(float[] tracks, int index, Unit minUnit, float min, Unit maxUnit, float max) {
        if (minUnit != Unit.PX && minUnit != Unit.AUTO) {
            throw new IllegalArgumentException("a track's minimum is px or auto");
        }
        if (maxUnit != Unit.PX && maxUnit != Unit.AUTO && maxUnit != Unit.FR) {
            throw new IllegalArgumentException("a track's maximum is px, auto or fr");
        }
        if (tracks == null || index < 0 || index * 4 >= tracks.length) {
            throw new IndexOutOfBoundsException(index);
        }
        int i = index * 4;
        float mu = minUnit.ordinal();
        float xu = maxUnit.ordinal();
        if (tracks[i] == mu && tracks[i + 1] == min && tracks[i + 2] == xu && tracks[i + 3] == max) {
            return false;
        }
        tracks[i] = mu;
        tracks[i + 1] = min;
        tracks[i + 2] = xu;
        tracks[i + 3] = max;
        return true;
    }

    /** Left to right or right to left; a change marks the subtree (its mirroring changes). */
    public Layout textDirection(int slot, TextDirection value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (textDir[slot] != v) {
            textDir[slot] = v;
            changed(slot);
            markDescendants(slot);
        }
        return this;
    }

    private void markDescendants(int slot) {
        for (int c = store.firstChild(slot); c != UiStore.NONE; c = store.nextSibling(c)) {
            store.markDirty(c, UiStore.DIRTY_LAYOUT);
            markDescendants(c);
        }
    }

    // ================================================================ setters: the element in its parent

    /** The width (default {@link Unit#AUTO}). */
    public Layout width(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (width[slot] != value || widthUnit[slot] != u) {
            width[slot] = value;
            widthUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** The height (default {@link Unit#AUTO}). */
    public Layout height(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (height[slot] != value || heightUnit[slot] != u) {
            height[slot] = value;
            heightUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** The minimum width ({@link Unit#AUTO}: none). */
    public Layout minWidth(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (minWidth[slot] != value || minWidthUnit[slot] != u) {
            minWidth[slot] = value;
            minWidthUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** The minimum height ({@link Unit#AUTO}: none). */
    public Layout minHeight(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (minHeight[slot] != value || minHeightUnit[slot] != u) {
            minHeight[slot] = value;
            minHeightUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** The maximum width ({@link Unit#AUTO}: none). */
    public Layout maxWidth(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (maxWidth[slot] != value || maxWidthUnit[slot] != u) {
            maxWidth[slot] = value;
            maxWidthUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** The maximum height ({@link Unit#AUTO}: none). */
    public Layout maxHeight(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (maxHeight[slot] != value || maxHeightUnit[slot] != u) {
            maxHeight[slot] = value;
            maxHeightUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** Width over height to keep when only one of them is known; 0 keeps none. */
    public Layout aspectRatio(int slot, float value) {
        own(slot);
        if (aspect[slot] != value) {
            aspect[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** Margins in panel units, in ui-style's {@code Insets} order; left is the start side. */
    public Layout margin(int slot, float top, float right, float bottom, float left) {
        own(slot);
        if (marTop[slot] != top || marRight[slot] != right || marBottom[slot] != bottom || marLeft[slot] != left) {
            marTop[slot] = top;
            marRight[slot] = right;
            marBottom[slot] = bottom;
            marLeft[slot] = left;
            changed(slot);
        }
        return this;
    }

    /** Share of free space a flex item grows by (default 0). */
    public Layout grow(int slot, float value) {
        own(slot);
        if (grow[slot] != value) {
            grow[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** Share of missing space a flex item shrinks by, weighted by its basis (default 1). */
    public Layout shrink(int slot, float value) {
        own(slot);
        if (shrink[slot] != value) {
            shrink[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** A flex item's size before growing and shrinking (default {@link Unit#AUTO}: its main size or content). */
    public Layout basis(int slot, float value, Unit unit) {
        own(slot);
        byte u = (byte) unit.ordinal();
        if (basis[slot] != value || basisUnit[slot] != u) {
            basis[slot] = value;
            basisUnit[slot] = u;
            changed(slot);
        }
        return this;
    }

    /** Cross-axis (flex) or vertical (grid, overlay) alignment overriding the parent's (default {@link Align#AUTO}). */
    public Layout alignSelf(int slot, Align value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (alignSelf[slot] != v) {
            alignSelf[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Horizontal alignment in a grid cell or overlay overriding the parent's (default {@link Align#AUTO}). */
    public Layout justifySelf(int slot, Align value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (justifySelf[slot] != v) {
            justifySelf[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Grid column placement: the first track (1-based, 0 for the next free one) and the tracks spanned. */
    public Layout gridColumnPlacement(int slot, int start, int span) {
        own(slot);
        if (gridColumnStart[slot] != start || gridColumnSpan[slot] != span) {
            gridColumnStart[slot] = start;
            gridColumnSpan[slot] = span;
            changed(slot);
        }
        return this;
    }

    /** Grid row placement: the first track (1-based, 0 for the next free one) and the tracks spanned. */
    public Layout gridRowPlacement(int slot, int start, int span) {
        own(slot);
        if (gridRowStart[slot] != start || gridRowSpan[slot] != span) {
            gridRowStart[slot] = start;
            gridRowSpan[slot] = span;
            changed(slot);
        }
        return this;
    }

    /** The region in a border layout (default {@link BorderRegion#CENTER}). */
    public Layout region(int slot, BorderRegion value) {
        own(slot);
        byte v = (byte) value.ordinal();
        if (region[slot] != v) {
            region[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** Anchors in an anchors layout: each edge as a fraction of the parent's content box (0 start or top, 1 end). */
    public Layout anchors(int slot, float left, float top, float right, float bottom) {
        own(slot);
        if (anchorLeft[slot] != left || anchorTop[slot] != top || anchorRight[slot] != right
            || anchorBottom[slot] != bottom) {
            anchorLeft[slot] = left;
            anchorTop[slot] = top;
            anchorRight[slot] = right;
            anchorBottom[slot] = bottom;
            changed(slot);
        }
        return this;
    }

    /** Offsets of the edges from their anchors (anchors layout), or the position (left, top) in a {@code NONE} one. */
    public Layout offsets(int slot, float left, float top, float right, float bottom) {
        own(slot);
        if (offLeft[slot] != left || offTop[slot] != top || offRight[slot] != right || offBottom[slot] != bottom) {
            offLeft[slot] = left;
            offTop[slot] = top;
            offRight[slot] = right;
            offBottom[slot] = bottom;
            changed(slot);
        }
        return this;
    }

    /**
     * Scrolls the element's children by {@code x, y} panel units (left and up): their positions are shifted without
     * a new layout, so scrolling costs a visit of the subtree's positions, not a measure. A negative value shifts
     * them the other way (a sticky header following the viewport). Default 0, 0. With snapping (the default) the
     * shift is whole pixels ({@link #scrollPixelsX}), so a scrolled child's rectangle moves by exactly the scroll and a
     * renderer can move recorded content by it instead of recording it again.
     */
    public Layout scroll(int slot, float x, float y) {
        own(slot);
        if (scrollX[slot] != x || scrollY[slot] != y) {
            scrollX[slot] = x;
            scrollY[slot] = y;
            if (!scrollMoved[slot]) {
                scrollMoved[slot] = true;
                if (scrollQueueCount == scrollQueue.length) {
                    growScrollQueue();
                }
                scrollQueue[scrollQueueCount++] = slot;
            }
        }
        return this;
    }

    /** The element's horizontal scroll in panel units ({@link #scroll}). */
    public float scrollX(int slot) {
        return slot < capacity && owner[slot] == store.element(slot) ? scrollX[slot] : 0;
    }

    /** The element's vertical scroll in panel units ({@link #scroll}). */
    public float scrollY(int slot) {
        return slot < capacity && owner[slot] == store.element(slot) ? scrollY[slot] : 0;
    }

    /** How far the element's children are shifted left by its scroll, in UI pixels (whole when snapping). */
    public float scrollPixelsX(int slot) {
        return slot < capacity && owner[slot] == store.element(slot) ? pixels(scrollX[slot], lastScale) : 0;
    }

    /** How far the element's children are shifted up by its scroll, in UI pixels (whole when snapping). */
    public float scrollPixelsY(int slot) {
        return slot < capacity && owner[slot] == store.element(slot) ? pixels(scrollY[slot], lastScale) : 0;
    }

    private float pixels(float panelUnits, float scale) {
        return snapping ? (float) Math.rint(panelUnits * scale) : panelUnits * scale;
    }

    /**
     * Declares that the element's size never depends on its content (a scroll viewport, a virtualized list's body:
     * its parent or its style sizes it). It measures as if empty (its padding, minimum and style sizes), so content
     * larger than it scrolls instead of growing it, and a change below it is laid out from it alone, at its last
     * constraints, as for an element of fixed size, instead of from its ancestors up. Default off.
     */
    public Layout independent(int slot, boolean value) {
        own(slot);
        independent[slot] = value;
        return this;
    }

    /**
     * Declares that the element clips its content (CSS {@code overflow} other than visible: a clipping or scrolling
     * element), so as a flex item its automatic minimum size is 0, not its min-content size: it may shrink below its
     * content, which it cuts or scrolls. Default off.
     */
    public Layout clipsContent(int slot, boolean value) {
        own(slot);
        if (clipsContent[slot] != value) {
            clipsContent[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** Which way an anchored element grows past its anchors to reach its minimum size (default end). */
    public Layout growDirection(int slot, GrowDirection horizontal, GrowDirection vertical) {
        own(slot);
        byte h = (byte) horizontal.ordinal();
        byte v = (byte) vertical.ordinal();
        if (growH[slot] != h || growV[slot] != v) {
            growH[slot] = h;
            growV[slot] = v;
            changed(slot);
        }
        return this;
    }

    /** The element's resolved font size in panel units: what {@link Unit#EM} lengths multiply (default 14). */
    public Layout fontSize(int slot, float value) {
        own(slot);
        if (fontSize[slot] != value) {
            fontSize[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** Whether the element takes part in layout; a hidden one takes no space and gets an empty rectangle. */
    public Layout visible(int slot, boolean value) {
        own(slot);
        if (hidden[slot] == value) {
            hidden[slot] = !value;
            changed(slot);
        }
        return this;
    }

    /** Whether the element's content is measured by the {@link Measure} (text, images). */
    public Layout measured(int slot, boolean value) {
        own(slot);
        if (measured[slot] != value) {
            measured[slot] = value;
            changed(slot);
        }
        return this;
    }

    /** The display's content scale (OS scaling), multiplied with the scale tunable. */
    public Layout contentScale(float value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("content scale must be positive: " + value);
        }
        contentScale = value;
        return this;
    }

    /** Whether outputs snap to whole UI pixels (default on; world panels turn it off). */
    public Layout snapping(boolean value) {
        snapping = value;
        return this;
    }

    private void changed(int slot) {
        selfChanged[slot] = true;
        store.markDirty(slot, UiStore.DIRTY_LAYOUT);
    }

    /** Resets {@code slot}'s inputs if the store reused it for a new element. */
    private void own(int slot) {
        if (slot >= capacity) {
            allocate(store.capacity());
        }
        Element e = store.element(slot);
        if (e == null) {
            throw new IllegalArgumentException("no element in slot " + slot);
        }
        if (owner[slot] != e) {
            owner[slot] = e;
            reset(slot);
        }
    }

    private void reset(int s) {
        selfChanged[s] = false;
        kind[s] = K_STACK;
        direction[s] = D_COLUMN;
        wrap[s] = W_NO_WRAP;
        justify[s] = 0;
        alignItems[s] = A_STRETCH;
        alignContent[s] = 0;
        justifyItems[s] = A_STRETCH;
        alignSelf[s] = A_AUTO;
        justifySelf[s] = A_AUTO;
        region[s] = R_CENTER;
        growH[s] = G_END;
        growV[s] = G_END;
        textDir[s] = T_INHERIT;
        hidden[s] = false;
        collapsed[s] = false;
        measured[s] = false;
        width[s] = 0;
        height[s] = 0;
        minWidth[s] = 0;
        minHeight[s] = 0;
        maxWidth[s] = 0;
        maxHeight[s] = 0;
        basis[s] = 0;
        widthUnit[s] = U_AUTO;
        heightUnit[s] = U_AUTO;
        minWidthUnit[s] = U_AUTO;
        minHeightUnit[s] = U_AUTO;
        maxWidthUnit[s] = U_AUTO;
        maxHeightUnit[s] = U_AUTO;
        basisUnit[s] = U_AUTO;
        aspect[s] = 0;
        grow[s] = 0;
        shrink[s] = 1;
        fontSize[s] = 14;
        rowGap[s] = 0;
        columnGap[s] = 0;
        padTop[s] = 0;
        padRight[s] = 0;
        padBottom[s] = 0;
        padLeft[s] = 0;
        marTop[s] = 0;
        marRight[s] = 0;
        marBottom[s] = 0;
        marLeft[s] = 0;
        anchorLeft[s] = 0;
        anchorTop[s] = 0;
        anchorRight[s] = 0;
        anchorBottom[s] = 0;
        offLeft[s] = 0;
        offTop[s] = 0;
        offRight[s] = 0;
        offBottom[s] = 0;
        gridColumnStart[s] = 0;
        gridColumnSpan[s] = 1;
        gridRowStart[s] = 0;
        gridRowSpan[s] = 1;
        columnCount[s] = 0;
        rowCount[s] = 0;
        scrollX[s] = 0;
        scrollY[s] = 0;
        independent[s] = false;
        clipsContent[s] = false;
        scrollMoved[s] = false;
        lcValid[s] = false;
        mcCount[s] = 0;
        amValid[s] = false;
        laidOutAt[s] = 0;
        relX[s] = 0;
        relY[s] = 0;
        absX[s] = Float.NaN;
        absY[s] = Float.NaN;
        outX[s] = 0;
        outY[s] = 0;
        outW[s] = 0;
        outH[s] = 0;
    }

    @AllowAllocation("growing with the store, rarely")
    private void allocate(int n) {
        owner = owner == null ? new Element[n] : Arrays.copyOf(owner, n);
        selfChanged = grow(selfChanged, n);
        kind = grow(kind, n);
        direction = grow(direction, n);
        wrap = grow(wrap, n);
        justify = grow(justify, n);
        alignItems = grow(alignItems, n);
        alignContent = grow(alignContent, n);
        justifyItems = grow(justifyItems, n);
        alignSelf = grow(alignSelf, n);
        justifySelf = grow(justifySelf, n);
        region = grow(region, n);
        growH = grow(growH, n);
        growV = grow(growV, n);
        textDir = grow(textDir, n);
        hidden = grow(hidden, n);
        collapsed = grow(collapsed, n);
        measured = grow(measured, n);
        width = grow(width, n);
        height = grow(height, n);
        minWidth = grow(minWidth, n);
        minHeight = grow(minHeight, n);
        maxWidth = grow(maxWidth, n);
        maxHeight = grow(maxHeight, n);
        basis = grow(basis, n);
        widthUnit = grow(widthUnit, n);
        heightUnit = grow(heightUnit, n);
        minWidthUnit = grow(minWidthUnit, n);
        minHeightUnit = grow(minHeightUnit, n);
        maxWidthUnit = grow(maxWidthUnit, n);
        maxHeightUnit = grow(maxHeightUnit, n);
        basisUnit = grow(basisUnit, n);
        aspect = grow(aspect, n);
        grow = grow(grow, n);
        shrink = grow(shrink, n);
        fontSize = grow(fontSize, n);
        rowGap = grow(rowGap, n);
        columnGap = grow(columnGap, n);
        padTop = grow(padTop, n);
        padRight = grow(padRight, n);
        padBottom = grow(padBottom, n);
        padLeft = grow(padLeft, n);
        marTop = grow(marTop, n);
        marRight = grow(marRight, n);
        marBottom = grow(marBottom, n);
        marLeft = grow(marLeft, n);
        anchorLeft = grow(anchorLeft, n);
        anchorTop = grow(anchorTop, n);
        anchorRight = grow(anchorRight, n);
        anchorBottom = grow(anchorBottom, n);
        offLeft = grow(offLeft, n);
        offTop = grow(offTop, n);
        offRight = grow(offRight, n);
        offBottom = grow(offBottom, n);
        gridColumnStart = grow(gridColumnStart, n);
        gridColumnSpan = grow(gridColumnSpan, n);
        gridRowStart = grow(gridRowStart, n);
        gridRowSpan = grow(gridRowSpan, n);
        columnTracks = columnTracks == null ? new float[n][] : Arrays.copyOf(columnTracks, n);
        rowTracks = rowTracks == null ? new float[n][] : Arrays.copyOf(rowTracks, n);
        columnCount = grow(columnCount, n);
        rowCount = grow(rowCount, n);
        scrollX = grow(scrollX, n);
        scrollY = grow(scrollY, n);
        independent = grow(independent, n);
        clipsContent = grow(clipsContent, n);
        scrollMoved = grow(scrollMoved, n);
        lcValid = grow(lcValid, n);
        lcAw = grow(lcAw, n);
        lcAh = grow(lcAh, n);
        lcOw = grow(lcOw, n);
        lcOh = grow(lcOh, n);
        lcModes = grow(lcModes, n);
        lcRw = grow(lcRw, n);
        lcRh = grow(lcRh, n);
        lcRtl = grow(lcRtl, n);
        mc = mc == null ? new float[n * MEASURE_CACHE * MC_FLOATS] : Arrays.copyOf(mc, n * MEASURE_CACHE * MC_FLOATS);
        mcModes = mcModes == null ? new int[n * MEASURE_CACHE] : Arrays.copyOf(mcModes, n * MEASURE_CACHE);
        mcCount = grow(mcCount, n);
        mcNext = grow(mcNext, n);
        invalidAt = grow(invalidAt, n);
        laidOutAt = grow(laidOutAt, n);
        visitedAt = grow(visitedAt, n);
        sBasis = grow(sBasis, n);
        sHypo = grow(sHypo, n);
        sTarget = grow(sTarget, n);
        sCross = grow(sCross, n);
        sTemp = grow(sTemp, n);
        sAutoMin = grow(sAutoMin, n);
        amValue = grow(amValue, n);
        amKey = amKey == null ? new float[n * 3] : Arrays.copyOf(amKey, n * 3);
        amModes = grow(amModes, n);
        amValid = grow(amValid, n);
        sCrossAvail = grow(sCrossAvail, n);
        sCrossMode = grow(sCrossMode, n);
        sFrozen = grow(sFrozen, n);
        sCol = grow(sCol, n);
        sRow = grow(sRow, n);
        sColSpan = grow(sColSpan, n);
        sRowSpan = grow(sRowSpan, n);
        relX = grow(relX, n);
        relY = grow(relY, n);
        absX = grow(absX, n);
        absY = grow(absY, n);
        outX = grow(outX, n);
        outY = grow(outY, n);
        outW = grow(outW, n);
        outH = grow(outH, n);
        changedAt = grow(changedAt, n);
        capacity = n;
    }

    private static float[] grow(float[] a, int n) {
        return a == null ? new float[n] : Arrays.copyOf(a, n);
    }

    private static int[] grow(int[] a, int n) {
        return a == null ? new int[n] : Arrays.copyOf(a, n);
    }

    private static byte[] grow(byte[] a, int n) {
        return a == null ? new byte[n] : Arrays.copyOf(a, n);
    }

    private static boolean[] grow(boolean[] a, int n) {
        return a == null ? new boolean[n] : Arrays.copyOf(a, n);
    }

    @AllowAllocation("scratch grows to the deepest layout once")
    void reserveFloats(int n) {
        if (fsTop + n > fs.length) {
            fs = Arrays.copyOf(fs, Math.max(fs.length * 2, fsTop + n));
        }
    }

    @AllowAllocation("scratch grows to the deepest layout once")
    void reserveInts(int n) {
        if (isTop + n > is.length) {
            is = Arrays.copyOf(is, Math.max(is.length * 2, isTop + n));
        }
    }

    @AllowAllocation("grows to the most changes in one update, once")
    private void growChanged() {
        changed = Arrays.copyOf(changed, changed.length * 2);
    }

    @AllowAllocation("grows to the most elements scrolled in one update, once")
    private void growScrollQueue() {
        scrollQueue = Arrays.copyOf(scrollQueue, scrollQueue.length * 2);
    }

    @AllowAllocation("grows to the most relayout roots in one update, once")
    private void growRoots() {
        roots = Arrays.copyOf(roots, roots.length * 2);
    }

    // ================================================================ update

    /**
     * Lays out what changed since the last update: the root covers a viewport of {@code viewportWidth} x
     * {@code viewportHeight} UI pixels, {@code measure} measures {@link #measured} leaves.
     */
    public void update(Measure measure, float viewportWidth, float viewportHeight) {
        this.measure = measure;
        if (capacity < store.capacity()) {
            allocate(store.capacity());
        }
        update++;
        changedCount = 0;
        rootCount = 0;
        float s = scale.get() * contentScale;
        boolean rescaled = s != lastScale || snapping != lastSnapping;
        lastScale = s;
        lastSnapping = snapping;
        float rw = viewportWidth / s;
        float rh = viewportHeight / s;
        int tops = store.rootCount();
        if (topRan.length < tops) {
            growTops(tops);
        }
        for (int t = 0; t < tops; t++) {
            own(store.rootAt(t));
        }

        int[] queue = store.dirtySlots();
        int n = store.dirtyCount();
        for (int i = 0; i < n; i++) {
            int d = queue[i];
            if (store.alive(d) && (store.dirty(d) & UiStore.DIRTY_LAYOUT) != 0) {
                store.clearDirty(d, UiStore.DIRTY_LAYOUT);
                own(d);
                bubble(d);
            }
        }

        // every root (the main one and each layer's) covers the viewport
        boolean viewportChanged = rw != lastRootW || rh != lastRootH;
        lastRootW = rw;
        lastRootH = rh;
        for (int t = 0; t < tops; t++) {
            int top = store.rootAt(t);
            topRan[t] = !lcValid[top] || viewportChanged;
            if (topRan[t]) {
                layoutNode(top, rw, EXACTLY, rh, EXACTLY, rw, rh, true, false);
            }
        }
        for (int i = 0; i < rootCount; i++) {
            int r = roots[i];
            if (!inShownTree(r)) {
                roots[i] = UiStore.NONE; // detached or hidden: laid out when it is shown again
            } else if (!lcValid[r]) {
                layoutNode(r, lcAw[r], lcModes[r] & 3, lcAh[r], lcModes[r] >> 2, lcOw[r], lcOh[r], true, lcRtl[r]);
            } else {
                roots[i] = UiStore.NONE; // laid out within an ancestor's relayout
            }
        }

        for (int t = 0; t < tops; t++) {
            if (rescaled || topRan[t]) {
                visit(store.rootAt(t), 0, 0, s, rescaled);
            }
        }
        if (!rescaled) {
            for (int i = 0; i < rootCount; i++) {
                int r = roots[i];
                if (r != UiStore.NONE && visitedAt[r] != update) {
                    int p = store.parent(r);
                    visit(r, absX[p], absY[p], s, false);
                }
            }
        }
        // scrolled elements not visited above: only their children's positions change
        for (int i = 0; i < scrollQueueCount; i++) {
            int r = scrollQueue[i];
            if (scrollMoved[r] && store.alive(r) && owner[r] == store.element(r)) {
                scrollMoved[r] = false;
                if (laidOutAt[r] != 0 && !hidden[r] && (store.isRoot(r) || inShownTree(r))) {
                    visitChildren(r, absX[r], absY[r], s, true);
                }
            }
        }
        scrollQueueCount = 0;
    }

    @AllowAllocation("grows with the UI's layers, rarely")
    private void growTops(int count) {
        topRan = new boolean[count];
    }

    /** Invalidates {@code d} and its ancestors up to one whose size cannot depend on its content. */
    private void bubble(int d) {
        boolean self = selfChanged[d];
        selfChanged[d] = false;
        int s = d;
        while (true) {
            if (invalidAt[s] == update && s != d) {
                return;
            }
            invalidAt[s] = update;
            lcValid[s] = false;
            mcCount[s] = 0;
            amValid[s] = false;
            if (store.isRoot(s)) {
                return;
            }
            if (!(s == d && self) && laidOutAt[s] != 0 && (fixedSize(s) || independent[s] && !hidden[s])) {
                if (rootCount == roots.length) {
                    growRoots();
                }
                roots[rootCount++] = s;
                return;
            }
            int p = store.parent(s);
            if (p == UiStore.NONE) {
                return;
            }
            s = p;
        }
    }

    /** Whether {@code s} is in the tree and no ancestor is hidden. */
    private boolean inShownTree(int s) {
        for (int p = store.parent(s); !store.isRoot(p); p = store.parent(p)) {
            if (p == UiStore.NONE || hidden[p]) {
                return false;
            }
        }
        return true;
    }

    private boolean fixedSize(int s) {
        return (widthUnit[s] == U_PX || widthUnit[s] == U_EM) && (heightUnit[s] == U_PX || heightUnit[s] == U_EM)
            && !hidden[s];
    }

    /** Absolute positions, snapped outputs and the changed list for {@code s} and what moved below it. */
    private void visit(int s, float pax, float pay, float scale, boolean force) {
        visitedAt[s] = update;
        collapsed[s] = false;
        float ax = pax + relX[s];
        float ay = pay + relY[s];
        boolean moved = force || ax != absX[s] || ay != absY[s];
        absX[s] = ax;
        absY[s] = ay;
        output(s, ax, ay, ax + lcRw[s], ay + lcRh[s], scale);
        boolean scrolled = scrollMoved[s];
        scrollMoved[s] = false;
        if (moved || scrolled || laidOutAt[s] == update) {
            visitChildren(s, ax, ay, scale, moved || scrolled);
        }
    }

    /** Visits the children of {@code s}, whose border box is at {@code ax, ay}, shifted by its scroll. */
    private void visitChildren(int s, float ax, float ay, float scale, boolean moved) {
        float cx = ax - (scrollX[s] == 0 ? 0 : pixels(scrollX[s], scale) / scale);
        float cy = ay - (scrollY[s] == 0 ? 0 : pixels(scrollY[s], scale) / scale);
        // Hidden children and their subtrees collapse to an empty rectangle (not shown: nothing draws or picks
        // there); their layout is kept for when they show again.
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                collapse(c, cx, cy, scale);
            } else {
                visit(c, cx, cy, scale, moved);
            }
        }
    }

    private void collapse(int s, float x, float y, float scale) {
        visitedAt[s] = update;
        absX[s] = x;
        absY[s] = y;
        if (!collapsed[s] || outW[s] != 0 || outH[s] != 0) {
            collapsed[s] = true;
            output(s, x, y, x, y, scale);
            for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
                own(c);
                collapse(c, x, y, scale);
            }
        }
    }

    private void output(int s, float x0, float y0, float x1, float y1, float scale) {
        float px0 = snap(x0 * scale);
        float py0 = snap(y0 * scale);
        float w = snap(x1 * scale) - px0;
        float h = snap(y1 * scale) - py0;
        if (px0 != outX[s] || py0 != outY[s] || w != outW[s] || h != outH[s] || changedAt[s] == 0) {
            outX[s] = px0;
            outY[s] = py0;
            outW[s] = w;
            outH[s] = h;
            if (changedAt[s] != update) {
                changedAt[s] = update;
                if (changedCount == changed.length) {
                    growChanged();
                }
                changed[changedCount++] = s;
            }
        }
    }

    private float snap(float v) {
        return snapping ? (float) Math.floor(v + 0.5f) : v;
    }

    // ================================================================ outputs

    /**
     * Whether the element has a rectangle from an update and takes part in layout itself (not {@link #visible hidden}):
     * what picking and focus check before reading {@link #x}, {@link #y}, {@link #width}, {@link #height}.
     */
    public boolean shown(int slot) {
        return slot < capacity && owner[slot] == store.element(slot) && changedAt[slot] != 0 && !hidden[slot]
            && !collapsed[slot];
    }

    /** How the element arranges its children: {@link #kind(int, LayoutKind)}'s value (checks of a laid-out tree read it). */
    public LayoutKind kind(int slot) {
        return KIND_VALUES[kind[slot]];
    }

    /** The element's left edge in UI pixels (snapped). */
    public float x(int slot) {
        return outX[slot];
    }

    /** The element's top edge in UI pixels (snapped). */
    public float y(int slot) {
        return outY[slot];
    }

    /** The element's width in UI pixels (snapped edges: neighbours share them). */
    public float width(int slot) {
        return outW[slot];
    }

    /** The element's height in UI pixels. */
    public float height(int slot) {
        return outH[slot];
    }

    /** The slots whose rectangle changed in the last update: the first {@link #changedCount()} entries. */
    public int[] changedSlots() {
        return changed;
    }

    /** How many slots changed in the last update. */
    public int changedCount() {
        return changedCount;
    }

    /** The update (see {@link #updates()}) in which {@code slot}'s rectangle last changed; 0 never. */
    public int changedIn(int slot) {
        return slot < capacity ? changedAt[slot] : 0;
    }

    /** How many updates ran: the current update's number. */
    public int updates() {
        return update;
    }

    /** The scale from panel units to UI pixels in the last update. */
    public float scale() {
        return lastScale;
    }

    // ================================================================ the recursion

    /**
     * Lays out {@code s} for the constraints (border box incl. its padding, excluding its margins); with
     * {@code perform} (both modes exact) also arranges its children. The size is left in {@link #resW} and
     * {@link #resH}. {@code ow} and {@code oh} are the parent's content box for percentages, NaN when not definite.
     */
    void layoutNode(int s, float aw, int wm, float ah, int hm, float ow, float oh, boolean perform, boolean rtl) {
        if (wm == UNDEFINED) {
            aw = 0;
        }
        if (hm == UNDEFINED) {
            ah = 0;
        }
        if (ow != ow) {
            ow = -1;
        }
        if (oh != oh) {
            oh = -1;
        }
        int modes = wm | (hm << 2);
        if (lcValid[s] && lcModes[s] == modes && lcAw[s] == aw && lcAh[s] == ah && lcOw[s] == ow && lcOh[s] == oh) {
            resW = lcRw[s];
            resH = lcRh[s];
            return;
        }
        if (!perform) {
            int base = s * MEASURE_CACHE;
            for (int i = 0, n = mcCount[s]; i < n; i++) {
                int e = (base + i) * MC_FLOATS;
                if (mcModes[base + i] == modes && mc[e] == aw && mc[e + 1] == ah && mc[e + 2] == ow
                    && mc[e + 3] == oh) {
                    resW = mc[e + 4];
                    resH = mc[e + 5];
                    return;
                }
            }
        }
        boolean nodeRtl = textDir[s] == T_INHERIT ? rtl : textDir[s] == T_RTL;
        compute(s, aw, wm, ah, hm, ow < 0 ? Float.NaN : ow, oh < 0 ? Float.NaN : oh, perform, nodeRtl);
        if (perform) {
            lcValid[s] = true;
            lcModes[s] = modes;
            lcAw[s] = aw;
            lcAh[s] = ah;
            lcOw[s] = ow;
            lcOh[s] = oh;
            lcRw[s] = resW;
            lcRh[s] = resH;
            lcRtl[s] = rtl;
            laidOutAt[s] = update;
        } else {
            int i = mcCount[s] < MEASURE_CACHE ? mcCount[s]++ : mcNext[s];
            mcNext[s] = (byte) ((i + 1) % MEASURE_CACHE);
            int base = s * MEASURE_CACHE + i;
            int e = base * MC_FLOATS;
            mcModes[base] = modes;
            mc[e] = aw;
            mc[e + 1] = ah;
            mc[e + 2] = ow;
            mc[e + 3] = oh;
            mc[e + 4] = resW;
            mc[e + 5] = resH;
        }
    }

    private void compute(int s, float aw, int wm, float ah, int hm, float ow, float oh, boolean perform,
            boolean rtl) {
        float em = fontSize[s];
        float minW = resolveMin(minWidth[s], minWidthUnit[s], ow, em);
        float maxW = resolveMax(maxWidth[s], maxWidthUnit[s], ow, em);
        float minH = resolveMin(minHeight[s], minHeightUnit[s], oh, em);
        float maxH = resolveMax(maxHeight[s], maxHeightUnit[s], oh, em);
        float padH = padLeft[s] + padRight[s];
        float padV = padTop[s] + padBottom[s];
        float w = Float.NaN;
        float h = Float.NaN;
        if (wm == EXACTLY) {
            w = aw;
        } else {
            float sw = resolve(width[s], widthUnit[s], ow, em);
            if (sw == sw) {
                w = Math.max(clamp(sw, minW, maxW), padH);
            }
        }
        if (hm == EXACTLY) {
            h = ah;
        } else {
            float sh = resolve(height[s], heightUnit[s], oh, em);
            if (sh == sh) {
                h = Math.max(clamp(sh, minH, maxH), padV);
            }
        }
        float ratio = aspect[s];
        if (ratio > 0) {
            if (w == w && h != h) {
                h = Math.max(clamp(w / ratio, minH, maxH), padV);
            } else if (h == h && w != w) {
                w = Math.max(clamp(h * ratio, minW, maxW), padH);
            }
        }
        if (w == w && h == h) {
            if (perform) {
                arrange(s, w, h, rtl);
            }
            resW = w;
            resH = h;
            return;
        }
        // Measure: the content within the constraints left for it.
        float iw;
        int iwm;
        if (w == w) {
            iw = Math.max(0, w - padH);
            iwm = EXACTLY;
        } else if (wm == AT_MOST || maxW < INF) {
            iw = Math.max(0, Math.min(wm == AT_MOST ? aw : INF, maxW) - padH);
            iwm = AT_MOST;
        } else {
            iw = 0;
            iwm = UNDEFINED;
        }
        float ih;
        int ihm;
        if (h == h) {
            ih = Math.max(0, h - padV);
            ihm = EXACTLY;
        } else if (hm == AT_MOST || maxH < INF) {
            ih = Math.max(0, Math.min(hm == AT_MOST ? ah : INF, maxH) - padV);
            ihm = AT_MOST;
        } else {
            ih = 0;
            ihm = UNDEFINED;
        }
        boolean leaf = measured[s];
        if (leaf) {
            long size = measure == null ? 0 : measure.measure(s, iw, iwm, ih, ihm);
            resW = Measure.width(size);
            resH = Measure.height(size);
        } else if (store.firstChild(s) != UiStore.NONE && !independent[s]) {
            content(s, iw, iwm, ih, ihm, minW - padH, maxW - padH, minH - padV, maxH - padV, rtl);
        } else {
            resW = 0;
            resH = 0;
        }
        float cw = resW;
        float ch = resH;
        if (w != w) {
            // Content wider than an at-most constraint overflows (Yoga's choice; CSS would need min-content sizes):
            // the parent shrinks the element in its final layout if it may shrink.
            w = Math.max(clamp(cw + padH, minW, maxW), padH);
            if (ratio > 0 && h != h) {
                h = Math.max(clamp(w / ratio, minH, maxH), padV);
            }
        }
        if (h != h) {
            h = Math.max(clamp(ch + padV, minH, maxH), padV);
        }
        resW = w;
        resH = h;
    }

    /** The content size of {@code s}'s children for inner constraints, into {@link #resW} and {@link #resH}. */
    private void content(int s, float iw, int iwm, float ih, int ihm, float minIW, float maxIW, float minIH,
            float maxIH, boolean rtl) {
        switch (kind[s]) {
            case K_STACK, K_FLEX -> FlexLayout.run(this, s, iw, iwm, ih, ihm, minIW, maxIW, minIH, maxIH, false, rtl, 0);
            case K_GRID -> GridLayout.run(this, s, iw, iwm, ih, ihm, minIW, maxIW, minIH, maxIH, false, rtl, 0);
            case K_BORDER -> border(s, iw, iwm, ih, ihm, false, rtl, 0);
            case K_ANCHORS -> anchorsContent(s, iw, iwm, ih, ihm, rtl);
            case K_OVERLAY -> overlay(s, iw, iwm, ih, ihm, false, rtl, 0);
            default -> absoluteContent(s, iw, iwm, ih, ihm, rtl);
        }
    }

    /** Arranges {@code s}'s children in its final border box {@code w x h}. */
    private void arrange(int s, float w, float h, boolean rtl) {
        if (store.firstChild(s) == UiStore.NONE) {
            return;
        }
        float iw = Math.max(0, w - padLeft[s] - padRight[s]);
        float ih = Math.max(0, h - padTop[s] - padBottom[s]);
        switch (kind[s]) {
            case K_STACK, K_FLEX -> FlexLayout.run(this, s, iw, EXACTLY, ih, EXACTLY, 0, INF, 0, INF, true, rtl, w);
            case K_GRID -> GridLayout.run(this, s, iw, EXACTLY, ih, EXACTLY, 0, INF, 0, INF, true, rtl, w);
            case K_BORDER -> border(s, iw, EXACTLY, ih, EXACTLY, true, rtl, w);
            case K_ANCHORS -> anchors(s, iw, ih, rtl, w);
            case K_OVERLAY -> overlay(s, iw, EXACTLY, ih, EXACTLY, true, rtl, w);
            default -> absolute(s, iw, ih, rtl, w);
        }
    }

    /**
     * Lays {@code c} out at exactly {@code w x h} (border box) with its border box's top left at {@code x, y} in its
     * parent's border box (left to right), mirrored in a right-to-left parent {@code parentW} wide.
     */
    void place(int c, float x, float y, float w, float h, float ow, float oh, boolean rtl, float parentW) {
        layoutNode(c, w, EXACTLY, h, EXACTLY, ow, oh, true, rtl);
        relX[c] = rtl ? parentW - x - w : x;
        relY[c] = y;
    }

    // ================================================================ simple layouts

    /** NONE: each child at its offsets (left, top) at its own size. */
    private void absoluteContent(int s, float iw, int iwm, float ih, int ihm, boolean rtl) {
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float cw = 0;
        float ch = 0;
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            layoutNode(c, 0, UNDEFINED, 0, UNDEFINED, ow, oh, false, rtl);
            cw = Math.max(cw, offLeft[c] + marLeft[c] + resW + marRight[c]);
            ch = Math.max(ch, offTop[c] + marTop[c] + resH + marBottom[c]);
        }
        resW = cw;
        resH = ch;
    }

    private void absolute(int s, float iw, float ih, boolean rtl, float w) {
        float px = padLeft[s];
        float py = padTop[s];
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            layoutNode(c, 0, UNDEFINED, 0, UNDEFINED, iw, ih, false, rtl);
            place(c, px + offLeft[c] + marLeft[c], py + offTop[c] + marTop[c], resW, resH, iw, ih, rtl, w);
        }
    }

    /** OVERLAY: every child in the whole content box, aligned by justify-self and align-self (stretch). */
    private void overlay(int s, float iw, int iwm, float ih, int ihm, boolean perform, boolean rtl, float w) {
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float cw = 0;
        float ch = 0;
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            float mh = marLeft[c] + marRight[c];
            float mv = marTop[c] + marBottom[c];
            int ja = selfAlign(justifySelf[c], justifyItems[s]);
            int va = selfAlign(alignSelf[c], alignItems[s]);
            boolean stretchW = ja == A_STRETCH && iwm == EXACTLY && widthUnit[c] == U_AUTO;
            boolean stretchH = va == A_STRETCH && ihm == EXACTLY && heightUnit[c] == U_AUTO;
            float aw = Math.max(0, iw - mh);
            float ah = Math.max(0, ih - mv);
            layoutNode(c, stretchW ? stretchWidth(c, aw, ow) : aw, stretchW ? EXACTLY : sub(iwm),
                stretchH ? stretchHeight(c, ah, oh) : ah, stretchH ? EXACTLY : sub(ihm), ow, oh, false, rtl);
            float rw = resW;
            float rh = resH;
            if (perform) {
                float x = padLeft[s] + marLeft[c] + alignOffset(ja, aw - rw);
                float y = padTop[s] + marTop[c] + alignOffset(va, ah - rh);
                place(c, x, y, rw, rh, iw, ih, rtl, w);
            }
            cw = Math.max(cw, rw + mh);
            ch = Math.max(ch, rh + mv);
        }
        resW = cw;
        resH = ch;
    }

    /** ANCHORS: each edge at a fraction of the content box plus an offset, grown to the element's minimum size. */
    private void anchors(int s, float iw, float ih, boolean rtl, float w) {
        float px = padLeft[s];
        float py = padTop[s];
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            float em = fontSize[c];
            float l = anchorLeft[c] * iw + offLeft[c];
            float r = anchorRight[c] * iw + offRight[c];
            float t = anchorTop[c] * ih + offTop[c];
            float b = anchorBottom[c] * ih + offBottom[c];
            float sw = resolve(width[c], widthUnit[c], iw, em);
            float sh = resolve(height[c], heightUnit[c], ih, em);
            float cw = sw == sw ? sw : r - l;
            float ch = sh == sh ? sh : b - t;
            // The minimum: the min-size inputs and the content's own size.
            layoutNode(c, 0, UNDEFINED, 0, UNDEFINED, iw, ih, false, rtl);
            float needW = Math.max(resW, resolveMin(minWidth[c], minWidthUnit[c], iw, em));
            float fw = Math.min(Math.max(cw, needW), resolveMax(maxWidth[c], maxWidthUnit[c], iw, em));
            layoutNode(c, fw, EXACTLY, 0, UNDEFINED, iw, ih, false, rtl);
            float needH = Math.max(resH, resolveMin(minHeight[c], minHeightUnit[c], ih, em));
            float fh = Math.min(Math.max(ch, needH), resolveMax(maxHeight[c], maxHeightUnit[c], ih, em));
            float x = growStart(growH[c], l, r, fw);
            float y = growStart(growV[c], t, b, fh);
            place(c, px + x, py + y, fw, fh, iw, ih, rtl, w);
        }
    }

    private static float growStart(byte grow, float start, float end, float size) {
        if (grow == G_END) {
            return start;
        }
        if (grow == G_BEGIN) {
            return end - size;
        }
        return (start + end - size) * 0.5f;
    }

    /**
     * The content size of an anchors layout: what keeps every child's start-anchored edges and size (children
     * anchored elsewhere count with their size only).
     */
    private void anchorsContent(int s, float iw, int iwm, float ih, int ihm, boolean rtl) {
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float cw = 0;
        float ch = 0;
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            layoutNode(c, 0, UNDEFINED, 0, UNDEFINED, ow, oh, false, rtl);
            float rw = Math.max(resW, offRight[c] - offLeft[c]);
            float rh = Math.max(resH, offBottom[c] - offTop[c]);
            cw = Math.max(cw, anchorLeft[c] == 0 ? offLeft[c] + rw : rw);
            ch = Math.max(ch, anchorTop[c] == 0 ? offTop[c] + rh : rh);
        }
        resW = cw;
        resH = ch;
    }

    /** BORDER: north and south across the top and bottom, west and east down the sides, the centre in between. */
    private void border(int s, float iw, int iwm, float ih, int ihm, boolean perform, boolean rtl, float w) {
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float vGap = rowGap[s];
        float hGap = columnGap[s];
        // Pass 1: each region's preferred size (outer).
        float nH = -1;
        float sH = -1;
        float wW = -1;
        float eW = -1;
        float nW = 0;
        float sW = 0;
        float sideH = 0;
        float cW = -1;
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            float mh = marLeft[c] + marRight[c];
            float mv = marTop[c] + marBottom[c];
            byte r = region[c];
            if (r == R_NORTH || r == R_SOUTH) {
                layoutNode(c, Math.max(0, iw - mh), sub(iwm), 0, UNDEFINED, ow, oh, false, rtl);
                if (r == R_NORTH) {
                    nH = Math.max(nH, resH + mv);
                    nW = Math.max(nW, resW + mh);
                } else {
                    sH = Math.max(sH, resH + mv);
                    sW = Math.max(sW, resW + mh);
                }
            } else {
                layoutNode(c, 0, UNDEFINED, Math.max(0, ih - mv), sub(ihm), ow, oh, false, rtl);
                if (r == R_WEST) {
                    wW = Math.max(wW, resW + mh);
                } else if (r == R_EAST) {
                    eW = Math.max(eW, resW + mh);
                } else {
                    cW = Math.max(cW, resW + mh);
                }
                sideH = Math.max(sideH, resH + mv);
            }
        }
        float top = nH < 0 ? 0 : nH + vGap;
        float bottom = sH < 0 ? 0 : sH + vGap;
        float left = wW < 0 ? 0 : wW + hGap;
        float right = eW < 0 ? 0 : eW + hGap;
        if (!perform) {
            float middle = left + right + Math.max(cW, 0);
            if (cW < 0) {
                middle = Math.max(0, middle - (eW >= 0 ? hGap : wW >= 0 ? hGap : 0));
            }
            resW = Math.max(Math.max(nW, sW), middle);
            resH = top + bottom + sideH;
            if (sideH == 0 && cW < 0 && wW < 0 && eW < 0) {
                resH = Math.max(0, resH - (sH >= 0 ? vGap : nH >= 0 ? vGap : 0));
            }
            return;
        }
        float px = padLeft[s];
        float py = padTop[s];
        float midH = Math.max(0, ih - top - bottom);
        for (int c = store.firstChild(s); c != UiStore.NONE; c = store.nextSibling(c)) {
            if (hidden[c]) {
                continue;
            }
            float ml = marLeft[c];
            float mt = marTop[c];
            float mh = ml + marRight[c];
            float mv = mt + marBottom[c];
            switch (region[c]) {
                case R_NORTH -> place(c, px + ml, py + mt, Math.max(0, iw - mh), Math.max(0, nH - mv), iw, ih, rtl, w);
                case R_SOUTH -> place(c, px + ml, py + ih - sH + mt, Math.max(0, iw - mh), Math.max(0, sH - mv), iw,
                    ih, rtl, w);
                case R_WEST -> place(c, px + ml, py + top + mt, Math.max(0, wW - mh), Math.max(0, midH - mv), iw, ih,
                    rtl, w);
                case R_EAST -> place(c, px + iw - eW + ml, py + top + mt, Math.max(0, eW - mh), Math.max(0, midH - mv),
                    iw, ih, rtl, w);
                default -> place(c, px + left + ml, py + top + mt, Math.max(0, iw - left - right - mh),
                    Math.max(0, midH - mv), iw, ih, rtl, w);
            }
        }
    }

    // ================================================================ helpers

    /** A width stretched to {@code avail}, within the element's minimum and maximum. */
    float stretchWidth(int c, float avail, float owner) {
        float em = fontSize[c];
        return Math.max(clamp(avail, resolveMin(minWidth[c], minWidthUnit[c], owner, em),
            resolveMax(maxWidth[c], maxWidthUnit[c], owner, em)), padLeft[c] + padRight[c]);
    }

    /** A height stretched to {@code avail}, within the element's minimum and maximum. */
    float stretchHeight(int c, float avail, float owner) {
        float em = fontSize[c];
        return Math.max(clamp(avail, resolveMin(minHeight[c], minHeightUnit[c], owner, em),
            resolveMax(maxHeight[c], maxHeightUnit[c], owner, em)), padTop[c] + padBottom[c]);
    }

    /** An at-most constraint where the parent's is definite, none where it is not. */
    static int sub(int mode) {
        return mode == UNDEFINED ? UNDEFINED : AT_MOST;
    }

    static int selfAlign(byte self, byte items) {
        return self == A_AUTO ? items : self;
    }

    static float alignOffset(int align, float free) {
        return switch (align) {
            case A_END -> free;
            case A_CENTER -> free * 0.5f;
            default -> 0;
        };
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }

    /** A length in panel units, or NaN for auto (and for percent of an indefinite size). */
    static float resolve(float value, byte unit, float of, float em) {
        return switch (unit) {
            case U_PX -> value;
            case U_EM -> value * em;
            case U_PERCENT -> of == of ? value * of * 0.01f : Float.NaN;
            default -> Float.NaN;
        };
    }

    static float resolveMin(float value, byte unit, float of, float em) {
        float v = resolve(value, unit, of, em);
        return v == v ? v : 0;
    }

    static float resolveMax(float value, byte unit, float of, float em) {
        float v = resolve(value, unit, of, em);
        return v == v ? v : INF;
    }
}
