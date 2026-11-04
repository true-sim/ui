package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.charts.ChartStyles;
import com.truesim.ui.controls.profiler.ProfilerStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.draw.Rgba;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.core.input.UiCommand;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.text.TextBlock;

/**
 * The profiler's timeline: every lane of a {@link Recording} (CPU threads with their nested zones, GPU queues with
 * their passes, the runtime's markers) under a time ruler, zoomable from seconds down to microseconds. While
 * {@link #live()} it follows the newest data, showing the last {@link ProfilerTunables#LIVE_SPAN}; the wheel zooms
 * about the pointer (Shift: scrolls the lanes), dragging pans (both stop following), Shift-dragging selects a span, a
 * click selects the zone under the pointer, a click on the ruler selects that frame, a double click or
 * {@link UiCommand#CHART_RESET} goes live again.
 *
 * <p>Drawing: frames' starts as lines ({@code profiler-frame}), hitch frames shaded ({@code profiler-hitch}), lane
 * names in a gutter ({@code profiler-lane}: {@code gutter-width}, {@code lane-gap}), zones as boxes coloured by name
 * from the {@code profiler-timeline}'s {@code palette} with their names where they fit ({@code profiler-zone}:
 * {@code row-height}, {@code label-min-width}; {@code :selected} for the selected one), runs of zones narrower than a
 * pixel merged into one box in the zone part's {@code stroke}; the selected span ({@code profiler-selection}). At most
 * {@link ProfilerTunables#MAX_ZONES} boxes a recording. Records without allocating.
 */
public final class TimelineView extends PaintedView {

    private static final int TICK_LABELS = 48;

    private final Recording recording;
    private final Element lanePart;
    private final Element zonePart;
    private final Element selectedPart;
    private final Element rulerPart;
    private final Element framePart;
    private final Element hitchPart;
    private final Element selectionPart;
    private long viewStart;
    private long viewEnd = 1;
    private boolean live = true;
    private float scrollY;
    private long seen = -1;
    // selection
    private int selectedLane = -1;
    private long selectedZone = -1;
    private long selectFrom;
    private long selectTo;
    private long selectionVersion;
    // per lane where it was drawn
    private float[] laneTop = new float[16];
    private float[] laneBottom = new float[16];
    private float contentHeight;
    // merged runs per depth
    private float[] runX0 = new float[32];
    private float[] runX1 = new float[32];
    private float[] runY = new float[32];
    private int drawn;
    // text
    private final TextBlock[] laneBlocks = new TextBlock[64];
    private final TextBlock[] tickBlocks = new TextBlock[TICK_LABELS];
    private final StringBuilder scratch = new StringBuilder(32);
    // dragging
    private boolean pressed;
    private boolean dragging;
    private boolean selecting;
    private float pressX;
    private float lastX;
    private float lastY;

    /** A timeline of {@code recording} in {@code ui}. */
    @AllowAllocation("views are made when a UI is built")
    public TimelineView(Ui ui, Recording recording) {
        super(ui, "profiler-timeline");
        this.recording = recording;
        root.semantics(Role.IMAGE, "Profiler timeline");
        lanePart = ui.part(root, "profiler-lane", null);
        zonePart = ui.part(root, "profiler-zone", null);
        selectedPart = ui.part(root, "profiler-zone", null);
        selectedPart.state(UiState.SELECTED, true);
        rulerPart = ui.part(root, "profiler-ruler", null);
        framePart = ui.part(root, "profiler-frame", null);
        hitchPart = ui.part(root, "profiler-hitch", null);
        selectionPart = ui.part(root, "profiler-selection", null);
    }

    /** The recording shown. */
    public Recording recording() {
        return recording;
    }

    // ---------------------------------------------------------------- view range

    /** Whether it follows the newest data. */
    public boolean live() {
        return live;
    }

    /** Follows the newest data again ({@code true}) or stays where it is; returns this. */
    public TimelineView live(boolean on) {
        live = on;
        seen = -1;
        repaint();
        return this;
    }

    /** Shows {@code from..to} (nanoseconds on the recording's clock) and stops following. */
    public void show(long from, long to) {
        viewStart = from;
        viewEnd = Math.max(from + 1, to);
        live = false;
        repaint();
    }

    /** The visible span's start. */
    public long viewStart() {
        return viewStart;
    }

    /** The visible span's end. */
    public long viewEnd() {
        return viewEnd;
    }

    // ---------------------------------------------------------------- selection

    /** The lane of the selected zone, or -1. */
    public int selectedLane() {
        return selectedLane;
    }

    /** The selected zone (an index of {@link #selectedLane()}), or -1. */
    public long selectedZone() {
        return selectedZone;
    }

    /** Selects zone {@code zone} of {@code lane} (-1: none). */
    public void select(int lane, long zone) {
        selectedLane = lane;
        selectedZone = zone;
        selectionVersion++;
        repaint();
    }

    /** Selects the span {@code from..to} (to &lt;= from: none), which the flame graph and zone table sum up. */
    public void selectSpan(long from, long to) {
        selectFrom = from;
        selectTo = to;
        selectionVersion++;
        repaint();
    }

    /** Whether a span is selected. */
    public boolean hasSpan() {
        return selectTo > selectFrom;
    }

    /** The selected span's start (the visible span's without one). */
    public long spanFrom() {
        return hasSpan() ? selectFrom : viewStart;
    }

    /** The selected span's end (the visible span's without one). */
    public long spanTo() {
        return hasSpan() ? selectTo : viewEnd;
    }

    /** Grows with every change of the selection (zone or span). */
    public long selectionVersion() {
        return selectionVersion;
    }

    // ---------------------------------------------------------------- frame

    @Override
    protected void update(long nanos) {
        long v = recording.version();
        if (v == seen) {
            return;
        }
        seen = v;
        if (live) {
            long end = recording.endTime();
            if (end != Long.MIN_VALUE) {
                long span = (long) (ProfilerTunables.LIVE_SPAN.get() * 1e6);
                viewEnd = end;
                viewStart = end - Math.max(1, span);
            }
            repaint();
        } else if (recording.endTime() >= viewStart) {
            repaint(); // new data within or before the view (zones closing)
        }
    }

    @Override
    protected long ownTextVersion() {
        long v = 0;
        for (TextBlock b : laneBlocks) {
            if (b != null) {
                v += b.version();
            }
        }
        for (TextBlock b : tickBlocks) {
            if (b != null) {
                v += b.version();
            }
        }
        return v;
    }

    private float gutter(ResolvedStyle ls) {
        return Math.min((x1 - x0) * 0.5f, ls.getFloat(ProfilerStyles.GUTTER_WIDTH) * scale);
    }

    /** The pixel of time {@code t}. */
    private float px(long t, float left, float width) {
        return left + (float) ((double) (t - viewStart) * width / (double) (viewEnd - viewStart));
    }

    /** The time at pixel {@code x}. */
    private long time(float x, float left, float width) {
        return viewStart + (long) ((x - left) * (double) (viewEnd - viewStart) / Math.max(1, width));
    }

    @Override
    protected void record(DrawList list) {
        ResolvedStyle ts = styleOf(root);
        ResolvedStyle ls = styleOf(lanePart);
        ResolvedStyle zs = styleOf(zonePart);
        ResolvedStyle rs = styleOf(rulerPart);
        float gutter = gutter(ls);
        float left = x0 + gutter;
        float width = Math.max(1, x1 - left);
        float rulerH = rowHeight(rs);
        float row = rowHeight(zs);
        float gap = ls.getFloat(ProfilerStyles.LANE_GAP) * scale;
        float top = y0 + rulerH;
        drawn = 0;
        if (viewEnd <= viewStart) {
            return;
        }
        // hitch frames shaded and frame starts, under the lanes
        frames(list, left, width, top);
        // the selected span
        if (hasSpan()) {
            float a = Math.max(left, px(selectFrom, left, width));
            float b = Math.min(x1, px(selectTo, left, width));
            if (b > a) {
                box(list, styleOf(selectionPart), a, top, b, y1);
            }
        }
        ruler(list, rs, left, width, rulerH);
        // lanes
        float y = top - scrollY;
        int lanes = recording.lanes();
        if (laneTop.length < lanes) {
            growLanes(lanes);
        }
        float minLabel = zs.getFloat(ProfilerStyles.LABEL_MIN_WIDTH) * scale;
        float pad = padLeft(zs);
        long textC = textColor(zs);
        long busy = stroke(zs);
        int maxZones = (int) ProfilerTunables.MAX_ZONES.get();
        for (int l = 0; l < lanes; l++) {
            int depths = recording.maxDepth(l) + 1;
            float h = depths * row;
            laneTop[l] = y;
            laneBottom[l] = y + h;
            if (y + h >= top && y < y1) {
                laneName(list, ls, l, y, h, top);
                zones(list, ts, zs, l, left, width, y, row, top, minLabel, pad, textC, busy, maxZones);
            }
            y += h + gap;
        }
        contentHeight = y + scrollY - top;
    }

    private void frames(DrawList list, float left, float width, float top) {
        ResolvedStyle fs = styleOf(framePart);
        ResolvedStyle hs = styleOf(hitchPart);
        long line = stroke(fs);
        float lw = lineWidth(fs);
        long hitchFill = background(hs);
        long first = recording.frameAt(viewStart);
        if (first < recording.firstFrame()) {
            first = recording.firstFrame();
        }
        for (long f = first, end = recording.endFrame(); f < end; f++) {
            long s = recording.frameStart(f);
            if (s > viewEnd) {
                break;
            }
            float a = px(s, left, width);
            if (recording.frameHitch(f) && Rgba.a(hitchFill) > 0) {
                long next = f + 1 < end ? recording.frameStart(f + 1) : recording.frameEnd(f);
                float b = Math.min(x1, px(next, left, width));
                if (b > Math.max(left, a)) {
                    list.box(Math.max(left, a), top, b, y1, hitchFill, 0, 0, 0, 0, 0, 0);
                }
            }
            if (a >= left && a <= x1 && Rgba.a(line) > 0) {
                list.line(a, top, a, y1, lw, line);
            }
        }
    }

    private void ruler(DrawList list, ResolvedStyle rs, float left, float width, float h) {
        box(list, rs, x0, y0, x1, y0 + h);
        long origin = recording.startTime() == Long.MAX_VALUE ? 0 : recording.startTime();
        double span = viewEnd - viewStart;
        double step = niceStep(span * 90 * scale / width);
        long k = (long) Math.floor((viewStart - origin) / step);
        long tc = textColor(rs);
        long sc = stroke(rs);
        float lw = lineWidth(rs);
        int labels = 0;
        float lastRight = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 200; i++, k++) {
            long tick = origin + (long) (k * step);
            if (tick > viewEnd) {
                break;
            }
            float x = px(tick, left, width);
            if (x < left || x > x1) {
                continue;
            }
            list.line(x, y0 + h * 0.6f, x, y0 + h, lw, sc);
            if (labels < TICK_LABELS) {
                TextBlock b = tickBlocks[labels];
                if (b == null) {
                    b = newBlock();
                    tickBlocks[labels] = b;
                }
                if (b == null) {
                    continue;
                }
                scratch.setLength(0);
                Units.time(scratch, tick - origin, step);
                shape(b, scratch, rs);
                drew(b);
                float tx = x + 2 * scale;
                if (tx > lastRight && tx + b.width() <= x1 && b.height() <= h) {
                    text(list, b, tx, y0 + (h - b.height()) * 0.5f, tc);
                    lastRight = tx + b.width() + 4 * scale;
                    labels++;
                }
            }
        }
    }

    /** A time step of 1, 2 or 5 times a power of ten nanoseconds, at least {@code ns}. */
    static double niceStep(double ns) {
        double p = com.truesim.common.math.Maths.pow(10, Math.floor(com.truesim.common.math.Maths.log10(Math.max(1, ns))));
        double m = ns / p;
        return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * p;
    }

    private void laneName(DrawList list, ResolvedStyle ls, int lane, float y, float h, float top) {
        float gw = gutter(ls);
        box(list, ls, x0, Math.max(top, y), x0 + gw, Math.min(y1, y + h));
        if (lane >= laneBlocks.length) {
            return;
        }
        TextBlock b = laneBlocks[lane];
        if (b == null) {
            b = newBlock();
            laneBlocks[lane] = b;
        }
        if (b == null) {
            return;
        }
        shape(b, recording.laneName(lane), ls);
        drew(b);
        float pad = padLeft(ls);
        float ty = y + (rowHeight(styleOf(zonePart)) - b.height()) * 0.5f;
        if (ty >= top && ty + b.height() <= y1 && pad + b.width() <= gw) {
            text(list, b, x0 + pad, ty, textColor(ls));
        }
    }

    private void zones(DrawList list, ResolvedStyle ts, ResolvedStyle zs, int lane, float left, float width, float y,
        float row, float top, float minLabel, float pad, long textC, long busy, int maxZones) {
        int depths = recording.maxDepth(lane) + 1;
        if (runX0.length < depths) {
            growRuns(depths);
        }
        for (int d = 0; d < depths; d++) {
            runX0[d] = Float.NaN;
        }
        long end = recording.endZone(lane);
        for (long z = recording.seek(lane, viewStart); z < end; z++) {
            long s = recording.zoneStart(lane, z);
            if (s > viewEnd) {
                break;
            }
            long e = recording.zoneEnd(lane, z);
            if (e == Recording.OPEN) {
                e = recording.endTime();
            }
            if (e < viewStart) {
                continue;
            }
            int d = recording.zoneDepth(lane, z);
            float ry = y + d * row;
            if (ry + row < top || ry > y1) {
                continue;
            }
            float a = Math.max(left, px(s, left, width));
            float b = Math.min(x1, px(e, left, width));
            if (b - a < 1.5f || drawn >= maxZones) {
                // narrower than a pixel or two: merged into a run of this depth
                if (runX0[d] == runX0[d] && a <= runX1[d] + 1) {
                    runX1[d] = Math.max(runX1[d], Math.max(b, a + 1));
                } else {
                    flush(list, d, row, busy);
                    runX0[d] = a;
                    runX1[d] = Math.max(b, a + 1);
                    runY[d] = ry;
                }
                continue;
            }
            flush(list, d, row, busy);
            boolean selected = lane == selectedLane && z == selectedZone;
            int name = recording.zoneName(lane, z);
            long fill = palette(ts, name);
            float by0 = Math.max(top, ry + 1);
            float by1 = Math.min(y1, ry + row - 1);
            if (by1 <= by0) {
                continue;
            }
            box(list, selected ? styleOf(selectedPart) : zs, a, by0, b, by1, fill);
            drawn++;
            if (b - a >= minLabel && ry >= top && ry + row <= y1) {
                TextBlock t = nameBlock(recording, name, zs);
                if (t != null && t.width() + 2 * pad <= b - a && t.height() <= row) {
                    text(list, t, a + pad, ry + (row - t.height()) * 0.5f, textC);
                }
            }
        }
        for (int d = 0; d < depths; d++) {
            flush(list, d, row, busy);
        }
    }

    private void flush(DrawList list, int d, float row, long busy) {
        if (runX0[d] == runX0[d]) {
            float by0 = runY[d] + 1;
            float by1 = runY[d] + row - 1;
            if (by1 > by0 && by0 >= y0 && by1 <= y1 && Rgba.a(busy) > 0) {
                list.box(runX0[d], by0, Math.min(x1, runX1[d]), by1, busy, 0, 0, 0, 0, 0, 0);
                drawn++;
            }
            runX0[d] = Float.NaN;
        }
    }

    @AllowAllocation("grows to the most lanes met")
    private void growLanes(int n) {
        laneTop = java.util.Arrays.copyOf(laneTop, Math.max(n, laneTop.length * 2));
        laneBottom = java.util.Arrays.copyOf(laneBottom, laneTop.length);
    }

    @AllowAllocation("grows to the deepest lane met")
    private void growRuns(int n) {
        int m = Math.max(n, runX0.length * 2);
        runX0 = java.util.Arrays.copyOf(runX0, m);
        runX1 = java.util.Arrays.copyOf(runX1, m);
        runY = java.util.Arrays.copyOf(runY, m);
    }

    /** The drawing coordinate of time {@code t} as last recorded. */
    public float pixelOf(long t) {
        float left = x0 + gutter(styleOf(lanePart));
        return px(t, left, Math.max(1, x1 - left));
    }

    /** Where lane {@code lane}'s first row was last drawn (drawing coordinates), or NaN. */
    public float laneTopOf(int lane) {
        return lane >= 0 && lane < laneTop.length && lane < recording.lanes() ? laneTop[lane] : Float.NaN;
    }

    /** Zones (and merged runs) drawn by the last recording. */
    public int drawnZones() {
        return drawn;
    }

    // ---------------------------------------------------------------- pointer

    /** The lane at pixel row {@code y} (drawing coordinates) as last drawn, or -1. */
    public int laneAt(float y) {
        for (int l = 0; l < recording.lanes() && l < laneTop.length; l++) {
            if (y >= laneTop[l] && y < laneBottom[l]) {
                return l;
            }
        }
        return -1;
    }

    /** The zone of {@code lane} under pixel {@code x, y} (drawing coordinates) as last drawn, or -1. */
    public long zoneAt(int lane, float x, float y) {
        if (lane < 0) {
            return -1;
        }
        float row = rowHeight(styleOf(zonePart));
        int d = (int) ((y - laneTop[lane]) / row);
        float left = x0 + gutter(styleOf(lanePart));
        long t = time(x, left, Math.max(1, x1 - left));
        long end = recording.endZone(lane);
        for (long z = recording.seek(lane, t); z < end; z++) {
            long s = recording.zoneStart(lane, z);
            if (s > t) {
                break;
            }
            long e = recording.zoneEnd(lane, z);
            if (recording.zoneDepth(lane, z) == d && (e == Recording.OPEN || e >= t)) {
                return z;
            }
        }
        return -1;
    }

    @Override
    public void handle(UiEvent event) {
        float x = drawX(event.x());
        float y = drawY(event.y());
        ResolvedStyle ls = styleOf(lanePart);
        float left = x0 + gutter(ls);
        float width = Math.max(1, x1 - left);
        switch (event.type()) {
            case WHEEL -> {
                float notches = event.scrollY() != 0 ? event.scrollY() : event.scrollX();
                if (notches == 0 || !inside(x, y)) {
                    return;
                }
                event.consume();
                if ((event.modifiers() & Pointer.SHIFT) != 0) {
                    float max = Math.max(0, contentHeight - (y1 - y0 - rowHeight(styleOf(rulerPart))));
                    scrollY = Math.max(0, Math.min(max, scrollY - notches * rowHeight(styleOf(zonePart)) * 3));
                    repaint();
                    return;
                }
                zoom(time(Math.max(left, x), left, width), com.truesim.common.math.Maths.pow(1 - ProfilerTunables.ZOOM_STEP.get(), notches));
            }
            case PRESS -> {
                if ((event.buttons() & Pointer.PRIMARY) == 0) {
                    return;
                }
                event.consume();
                ui.interaction().focus(root, event.player());
                pressed = true;
                dragging = false;
                selecting = (event.modifiers() & Pointer.SHIFT) != 0;
                pressX = x;
                lastX = x;
                lastY = y;
            }
            case MOVE -> {
                if (!pressed) {
                    return;
                }
                if (!dragging && Math.abs(x - pressX) < 3 * scale) {
                    return;
                }
                dragging = true;
                if (selecting) {
                    long a = time(Math.max(left, Math.min(pressX, x)), left, width);
                    long b = time(Math.max(left, Math.max(pressX, x)), left, width);
                    selectSpan(a, b);
                } else {
                    long dt = (long) ((x - lastX) * (double) (viewEnd - viewStart) / width);
                    viewStart -= dt;
                    viewEnd -= dt;
                    float max = Math.max(0, contentHeight - (y1 - y0 - rowHeight(styleOf(rulerPart))));
                    scrollY = Math.max(0, Math.min(max, scrollY - (y - lastY)));
                    live = false;
                    repaint();
                }
                lastX = x;
                lastY = y;
            }
            case RELEASE -> {
                if (!pressed) {
                    return;
                }
                pressed = false;
                event.consume();
                if (!dragging) {
                    click(x, y, left, width);
                }
                dragging = false;
            }
            case CLICK -> {
                if (event.clickCount() == 2 && event.button() == Pointer.PRIMARY) {
                    event.consume();
                    selectSpan(0, 0);
                    live(true);
                }
            }
            case COMMAND -> {
                if (event.command() == UiCommand.CHART_RESET) {
                    event.consume();
                    selectSpan(0, 0);
                    live(true);
                }
            }
            default -> {
                // nothing else
            }
        }
    }

    private void click(float x, float y, float left, float width) {
        if (y < y0 + rowHeight(styleOf(rulerPart))) {
            // the ruler: the frame there becomes the span
            long f = recording.frameAt(time(x, left, width));
            if (f >= recording.firstFrame() && f + 1 < recording.endFrame()) {
                selectSpan(recording.frameStart(f), recording.frameStart(f + 1));
            }
            return;
        }
        int lane = laneAt(y);
        select(lane, zoneAt(lane, x, y));
    }

    /** Zooms about time {@code at} by {@code factor} (below 1: in). */
    public void zoom(long at, double factor) {
        double span = viewEnd - viewStart;
        double next = Math.max(ProfilerTunables.MIN_SPAN.get(), span * factor);
        double share = span <= 0 ? 0.5 : (at - viewStart) / span;
        viewStart = at - (long) (next * share);
        viewEnd = viewStart + Math.max(1, (long) next);
        live = false;
        repaint();
    }
}
