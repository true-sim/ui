package com.truesim.ui.profiler;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.profiler.ProfilerStyles;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.Role;
import com.truesim.ui.core.UiState;
import com.truesim.ui.core.draw.DrawList;
import com.truesim.ui.core.event.UiEvent;
import com.truesim.ui.core.input.Pointer;
import com.truesim.ui.style.ResolvedStyle;
import com.truesim.ui.text.TextBlock;

/**
 * The zones summed up by name ({@link ZoneStats}): a header ({@code profiler-table-header}) and a row per name
 * ({@code profiler-table-row}, every other one also {@code .stripe}; {@code :selected} for the selected one) with the
 * calls, total, self, mean, 99th percentile and longest time. A click on a header sorts by its column (again:
 * reverses), a click on a row selects its name, the wheel scrolls. Records without allocating once its text blocks
 * exist.
 */
public final class ZoneTableView extends PaintedView {

    private static final ZoneStats.Column[] COLUMNS = ZoneStats.Column.values();
    private static final String[] TITLES = {"Zone", "Calls", "Total", "Self", "Mean", "p99", "Max"};

    private final Recording recording;
    private final ZoneStats stats = new ZoneStats();
    private final Element headerPart;
    private final Element rowPart;
    private final Element stripePart;
    private final Element selectedPart;
    private final TextBlock[] headers = new TextBlock[COLUMNS.length];
    private TextBlock[] cells = new TextBlock[0]; // visible rows x 6 numeric columns
    private final StringBuilder scratch = new StringBuilder(32);
    private int scroll;
    private int selectedName = -1;

    /** A zone table of {@code recording} in {@code ui}; fill it with {@link #show}. */
    @AllowAllocation("views are made when a UI is built")
    public ZoneTableView(Ui ui, Recording recording) {
        super(ui, "profiler-table");
        this.recording = recording;
        stats.names(recording);
        root.semantics(Role.TABLE, "Zones");
        headerPart = ui.part(root, "profiler-table-header", null);
        rowPart = ui.part(root, "profiler-table-row", null);
        stripePart = ui.part(root, "profiler-table-row", "stripe");
        selectedPart = ui.part(root, "profiler-table-row", null);
        selectedPart.state(UiState.SELECTED, true);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = newBlock();
        }
    }

    /** Sums up lane {@code lane}'s zones (every thread's when negative) ending within {@code from..to}. */
    public void show(int lane, long from, long to) {
        stats.compute(recording, lane, from, to);
        if (scroll > Math.max(0, stats.rows() - 1)) {
            scroll = 0;
        }
        repaint();
    }

    /** The statistics shown. */
    public ZoneStats stats() {
        return stats;
    }

    /** The selected name id, or -1. */
    public int selectedName() {
        return selectedName;
    }

    /** Sorts by {@code column}, {@code down} largest first. */
    public void sort(ZoneStats.Column column, boolean down) {
        stats.sort(column, down);
        repaint();
    }

    @Override
    protected void update(long nanos) {
        // filled on request (show)
    }

    @Override
    protected long ownTextVersion() {
        long v = 0;
        for (TextBlock b : headers) {
            if (b != null) {
                v += b.version();
            }
        }
        for (TextBlock b : cells) {
            if (b != null) {
                v += b.version();
            }
        }
        return v;
    }

    private float nameWidth(ResolvedStyle hs) {
        return Math.min((x1 - x0) * 0.4f, hs.getFloat(ProfilerStyles.GUTTER_WIDTH) * scale);
    }

    private float columnX(int c, float nameW) {
        if (c == 0) {
            return x0;
        }
        float rest = (x1 - x0 - nameW) / (COLUMNS.length - 1);
        return x0 + nameW + (c - 1) * rest;
    }

    @Override
    protected void record(DrawList list) {
        ResolvedStyle hs = styleOf(headerPart);
        ResolvedStyle rs = styleOf(rowPart);
        float hh = rowHeight(hs);
        float rh = rowHeight(rs);
        float nameW = nameWidth(hs);
        box(list, hs, x0, y0, x1, Math.min(y1, y0 + hh));
        float pad = padLeft(hs);
        long htc = textColor(hs);
        for (int c = 0; c < COLUMNS.length; c++) {
            TextBlock b = headers[c];
            if (b == null) {
                continue;
            }
            scratch.setLength(0);
            scratch.append(TITLES[c]);
            if (stats.sortedBy() == COLUMNS[c]) {
                scratch.append(stats.descending() ? " v" : " ^");
            }
            shape(b, scratch, hs);
            drew(b);
            float cx0 = columnX(c, nameW);
            float cx1 = c + 1 < COLUMNS.length ? columnX(c + 1, nameW) : x1;
            if (b.width() + 2 * pad <= cx1 - cx0 && b.height() <= hh && y0 + hh <= y1) {
                float tx = c == 0 ? cx0 + pad : cx1 - pad - b.width();
                text(list, b, tx, y0 + (hh - b.height()) * 0.5f, htc);
            }
        }
        int visible = (int) Math.max(0, (y1 - y0 - hh) / rh);
        if (cells.length < visible * 6) {
            growCells(visible * 6);
        }
        float rpad = padLeft(rs);
        for (int i = 0; i < visible; i++) {
            int r = scroll + i;
            if (r >= stats.rows()) {
                break;
            }
            float ry = y0 + hh + i * rh;
            int name = stats.name(r);
            ResolvedStyle row = name == selectedName ? styleOf(selectedPart) : (r & 1) == 1 ? styleOf(stripePart) : rs;
            box(list, row, x0, ry, x1, ry + rh);
            long tc = textColor(row);
            TextBlock nb = nameBlock(recording, name, row);
            if (nb != null && nb.width() + 2 * rpad <= nameW && nb.height() <= rh) {
                text(list, nb, x0 + rpad, ry + (rh - nb.height()) * 0.5f, tc);
            }
            for (int c = 1; c < COLUMNS.length; c++) {
                TextBlock b = cells[i * 6 + c - 1];
                if (b == null) {
                    b = newBlock();
                    cells[i * 6 + c - 1] = b;
                    if (b == null) {
                        continue;
                    }
                }
                scratch.setLength(0);
                switch (COLUMNS[c]) {
                    case CALLS -> scratch.append(stats.calls(r));
                    case TOTAL -> Units.duration(scratch, stats.total(r));
                    case SELF -> Units.duration(scratch, stats.self(r));
                    case AVG -> Units.duration(scratch, stats.avg(r));
                    case P99 -> Units.duration(scratch, stats.p99(r));
                    default -> Units.duration(scratch, stats.max(r));
                }
                shape(b, scratch, row);
                drew(b);
                float cx0 = columnX(c, nameW);
                float cx1 = c + 1 < COLUMNS.length ? columnX(c + 1, nameW) : x1;
                if (b.width() + 2 * rpad <= cx1 - cx0 && b.height() <= rh) {
                    text(list, b, cx1 - rpad - b.width(), ry + (rh - b.height()) * 0.5f, tc);
                }
            }
        }
    }

    @AllowAllocation("grows to the most rows shown")
    private void growCells(int n) {
        cells = java.util.Arrays.copyOf(cells, n);
    }

    @Override
    public void handle(UiEvent event) {
        float x = drawX(event.x());
        float y = drawY(event.y());
        switch (event.type()) {
            case WHEEL -> {
                if (!inside(x, y) || event.scrollY() == 0) {
                    return;
                }
                event.consume();
                int max = Math.max(0, stats.rows() - 1);
                scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(event.scrollY()) * 3));
                repaint();
            }
            case CLICK -> {
                if (event.button() != Pointer.PRIMARY || !inside(x, y)) {
                    return;
                }
                event.consume();
                ResolvedStyle hs = styleOf(headerPart);
                float hh = rowHeight(hs);
                if (y < y0 + hh) {
                    float nameW = nameWidth(hs);
                    for (int c = COLUMNS.length - 1; c >= 0; c--) {
                        if (x >= columnX(c, nameW)) {
                            boolean down = stats.sortedBy() == COLUMNS[c] ? !stats.descending()
                                : COLUMNS[c] != ZoneStats.Column.NAME;
                            sort(COLUMNS[c], down);
                            return;
                        }
                    }
                    return;
                }
                int r = scroll + (int) ((y - y0 - hh) / rowHeight(styleOf(rowPart)));
                selectedName = r < stats.rows() ? stats.name(r) : -1;
                repaint();
            }
            default -> {
                // nothing else
            }
        }
    }
}
