package com.truesim.ui.layout;

import static com.truesim.ui.layout.Layout.AT_MOST;
import static com.truesim.ui.layout.Layout.A_STRETCH;
import static com.truesim.ui.layout.Layout.EXACTLY;
import static com.truesim.ui.layout.Layout.UNDEFINED;
import static com.truesim.ui.layout.Layout.U_AUTO;
import static com.truesim.ui.layout.Layout.U_FR;
import static com.truesim.ui.layout.Layout.U_PX;
import static com.truesim.ui.layout.Layout.alignOffset;
import static com.truesim.ui.layout.Layout.clamp;
import static com.truesim.ui.layout.Layout.selfAlign;

import com.truesim.ui.core.UiStore;

/**
 * A CSS grid subset: explicit tracks ({@code px}, {@code auto}, {@code fr}, {@code minmax(px|auto, px|auto|fr)}),
 * implicit {@code auto} rows (and columns for placements past the explicit ones), placements with spans, and
 * justify-self and align-self in the cells.
 *
 * <p>Auto placement is row-major and sparse: items with both lines are placed first in order with the others (in
 * document order, not CSS's two phases); a row-locked item takes the first free column of its row, a column-locked
 * item the first free row from the cursor, a free item the next free cell after the cursor. Track sizing follows
 * CSS's steps simplified: bases from fixed minimums and the content of single-span items in auto minimums,
 * spanning items spread their extra size evenly over the auto-minimum tracks they span, auto tracks grow to their
 * content and then share leftover space up to their limits, {@code fr} tracks share what is left (tracks whose
 * content needs more than their share keep their content size), and in a definite grid auto tracks stretch over
 * what is left (justify-content and align-content are always CSS's {@code normal}).
 */
final class GridLayout {

    /** Floats per track on the scratch stack: minimum unit, minimum, maximum unit, maximum, size, limit, position. */
    private static final int T = 7;
    private static final int BASE = 4;
    private static final int LIMIT = 5;
    private static final int POS = 6;

    private GridLayout() {
    }

    static void run(Layout l, int s, float iw, int iwm, float ih, int ihm, float minIW, float maxIW, float minIH,
            float maxIH, boolean perform, boolean rtl, float borderW) {
        UiStore st = l.store;
        int definedCols = l.columnCount[s];
        int definedRows = l.rowCount[s];
        int cols = Math.max(definedCols, 1);
        int rowBound = definedRows;
        int spanSum = 0;
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            if (l.hidden[c]) {
                continue;
            }
            int cs = l.gridColumnStart[c];
            int rs = l.gridRowStart[c];
            int rspan = Math.max(1, l.gridRowSpan[c]);
            if (cs > 0) {
                cols = Math.max(cols, cs - 1 + Math.max(1, l.gridColumnSpan[c]));
            }
            if (rs > 0) {
                rowBound = Math.max(rowBound, rs - 1 + rspan);
            }
            spanSum += rspan;
        }
        rowBound += spanSum;

        // Placement over an occupancy map on the int stack.
        int occBase = l.isTop;
        int cells = cols * rowBound;
        l.reserveInts(cells);
        l.isTop += cells;
        for (int i = 0; i < cells; i++) {
            l.is[occBase + i] = 0;
        }
        int cursorRow = 0;
        int cursorCol = 0;
        int rowsUsed = 0;
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            if (l.hidden[c]) {
                continue;
            }
            int cs = l.gridColumnStart[c];
            int rs = l.gridRowStart[c];
            int rspan = Math.max(1, l.gridRowSpan[c]);
            int cspan = Math.max(1, l.gridColumnSpan[c]);
            if (cs <= 0) {
                cspan = Math.min(cspan, cols);
            }
            int r;
            int col;
            if (cs > 0 && rs > 0) {
                r = rs - 1;
                col = cs - 1;
            } else if (rs > 0) {
                r = rs - 1;
                col = 0;
                while (col + cspan <= cols && !free(l, occBase, cols, r, col, rspan, cspan)) {
                    col++;
                }
                if (col + cspan > cols) {
                    col = 0;
                }
            } else if (cs > 0) {
                col = cs - 1;
                r = col < cursorCol ? cursorRow + 1 : cursorRow;
                while (r + rspan < rowBound && !free(l, occBase, cols, r, col, rspan, cspan)) {
                    r++;
                }
                cursorRow = r;
                cursorCol = col + cspan;
            } else {
                while (true) {
                    if (cursorCol + cspan > cols) {
                        cursorCol = 0;
                        cursorRow++;
                    }
                    if (cursorRow + rspan >= rowBound || free(l, occBase, cols, cursorRow, cursorCol, rspan, cspan)) {
                        break;
                    }
                    cursorCol++;
                }
                r = cursorRow;
                col = cursorCol;
                cursorCol += cspan;
            }
            for (int y = r; y < r + rspan; y++) {
                for (int x = col; x < col + cspan; x++) {
                    l.is[occBase + y * cols + x] = 1;
                }
            }
            l.sRow[c] = r;
            l.sCol[c] = col;
            l.sRowSpan[c] = rspan;
            l.sColSpan[c] = cspan;
            rowsUsed = Math.max(rowsUsed, r + rspan);
        }
        int rows = Math.max(definedRows, rowsUsed);
        l.isTop = occBase;

        // Tracks on the float stack.
        int colBase = l.fsTop;
        int rowBase = colBase + cols * T;
        l.reserveFloats((cols + rows) * T);
        l.fsTop = rowBase + rows * T;
        define(l, colBase, cols, l.columnTracks[s], definedCols);
        define(l, rowBase, rows, l.rowTracks[s], definedRows);
        float colGap = l.columnGap[s];
        float rowGap = l.rowGap[s];
        float ow = iwm == EXACTLY ? iw : Float.NaN;
        float oh = ihm == EXACTLY ? ih : Float.NaN;
        float width = size(l, s, true, colBase, cols, iw, iwm, colGap, colBase, ow, oh, rtl);
        float height = size(l, s, false, rowBase, rows, ih, ihm, rowGap, colBase, ow, oh, rtl);

        if (perform) {
            byte justifyItems = l.justifyItems[s];
            byte alignItems = l.alignItems[s];
            for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
                if (l.hidden[c]) {
                    continue;
                }
                float x0 = l.fs[colBase + l.sCol[c] * T + POS];
                float y0 = l.fs[rowBase + l.sRow[c] * T + POS];
                float w = span(l, colBase, l.sCol[c], l.sColSpan[c], colGap);
                float h = span(l, rowBase, l.sRow[c], l.sRowSpan[c], rowGap);
                int ja = selfAlign(l.justifySelf[c], justifyItems);
                int va = selfAlign(l.alignSelf[c], alignItems);
                float aw = Math.max(0, w - l.marLeft[c] - l.marRight[c]);
                float ah = Math.max(0, h - l.marTop[c] - l.marBottom[c]);
                boolean stretchW = ja == A_STRETCH && l.widthUnit[c] == U_AUTO && l.aspect[c] <= 0;
                boolean stretchH = va == A_STRETCH && l.heightUnit[c] == U_AUTO && l.aspect[c] <= 0;
                l.layoutNode(c, stretchW ? l.stretchWidth(c, aw, iw) : aw, stretchW ? EXACTLY : AT_MOST,
                    stretchH ? l.stretchHeight(c, ah, ih) : ah, stretchH ? EXACTLY : AT_MOST, iw, ih, false, rtl);
                float rw = l.resW;
                float rh = l.resH;
                float x = l.padLeft[s] + x0 + l.marLeft[c] + alignOffset(ja, aw - rw);
                float y = l.padTop[s] + y0 + l.marTop[c] + alignOffset(va, ah - rh);
                l.place(c, x, y, rw, rh, iw, ih, rtl, borderW);
            }
        }
        l.fsTop = colBase;
        l.resW = iwm == EXACTLY ? iw : clamp(width, minIW, maxIW);
        l.resH = ihm == EXACTLY ? ih : clamp(height, minIH, maxIH);
    }

    private static boolean free(Layout l, int occBase, int cols, int r, int col, int rspan, int cspan) {
        for (int y = r; y < r + rspan; y++) {
            for (int x = col; x < col + cspan; x++) {
                if (l.is[occBase + y * cols + x] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void define(Layout l, int base, int n, float[] defs, int defined) {
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            if (i < defined) {
                l.fs[t] = defs[i * 4];
                l.fs[t + 1] = defs[i * 4 + 1];
                l.fs[t + 2] = defs[i * 4 + 2];
                l.fs[t + 3] = defs[i * 4 + 3];
            } else {
                l.fs[t] = U_AUTO;
                l.fs[t + 1] = 0;
                l.fs[t + 2] = U_AUTO;
                l.fs[t + 3] = 0;
            }
        }
    }

    private static float span(Layout l, int base, int first, int count, float gap) {
        float sum = gap * (count - 1);
        for (int i = first; i < first + count; i++) {
            sum += l.fs[base + i * T + BASE];
        }
        return sum;
    }

    /** An item's outer size on the axis: max-content for columns, at its columns' width for rows. */
    private static float contribution(Layout l, int c, boolean columns, int colBase, float colGap, float ow, float oh,
            boolean rtl, byte justifyItems) {
        if (columns) {
            l.layoutNode(c, 0, UNDEFINED, 0, UNDEFINED, ow, oh, false, rtl);
            return l.resW + l.marLeft[c] + l.marRight[c];
        }
        float w = Math.max(0, span(l, colBase, l.sCol[c], l.sColSpan[c], colGap) - l.marLeft[c] - l.marRight[c]);
        boolean stretch = selfAlign(l.justifySelf[c], justifyItems) == A_STRETCH && l.widthUnit[c] == U_AUTO;
        l.layoutNode(c, stretch ? l.stretchWidth(c, w, ow) : w, stretch ? EXACTLY : AT_MOST, 0, UNDEFINED, ow, oh,
            false, rtl);
        return l.resH + l.marTop[c] + l.marBottom[c];
    }

    /** Sizes the tracks of one axis and sets their positions; returns their total with gaps. */
    private static float size(Layout l, int s, boolean columns, int base, int n, float avail, int mode, float gap,
            int colBase, float ow, float oh, boolean rtl) {
        UiStore st = l.store;
        byte justifyItems = l.justifyItems[s];
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            l.fs[t + BASE] = l.fs[t] == U_PX ? l.fs[t + 1] : 0;
            l.fs[t + LIMIT] = 0; // the content of single-span items, for auto maximums
        }
        // Single-span items.
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            if (l.hidden[c] || (columns ? l.sColSpan[c] : l.sRowSpan[c]) != 1) {
                continue;
            }
            int t = base + (columns ? l.sCol[c] : l.sRow[c]) * T;
            boolean autoMin = l.fs[t] == U_AUTO;
            boolean autoMax = l.fs[t + 2] == U_AUTO;
            if (!autoMin && !autoMax) {
                continue;
            }
            float size = contribution(l, c, columns, colBase, gap(l, s, true), ow, oh, rtl, justifyItems);
            if (autoMin) {
                l.fs[t + BASE] = Math.max(l.fs[t + BASE], size);
            }
            if (autoMax) {
                l.fs[t + LIMIT] = Math.max(l.fs[t + LIMIT], size);
            }
        }
        // Spanning items: extra size spread evenly over the auto-minimum tracks they span.
        for (int c = st.firstChild(s); c != UiStore.NONE; c = st.nextSibling(c)) {
            int spanned = columns ? l.sColSpan[c] : l.sRowSpan[c];
            if (l.hidden[c] || spanned == 1) {
                continue;
            }
            int first = columns ? l.sCol[c] : l.sRow[c];
            int autos = 0;
            for (int i = first; i < first + spanned; i++) {
                if (l.fs[base + i * T] == U_AUTO) {
                    autos++;
                }
            }
            if (autos == 0) {
                continue;
            }
            float size = contribution(l, c, columns, colBase, gap(l, s, true), ow, oh, rtl, justifyItems);
            float extra = size - span(l, base, first, spanned, gap);
            if (extra > 0) {
                for (int i = first; i < first + spanned; i++) {
                    int t = base + i * T;
                    if (l.fs[t] == U_AUTO) {
                        l.fs[t + BASE] += extra / autos;
                    }
                }
            }
        }
        // Limits; then grow the non-flexible tracks to them.
        float used = gap * (n - 1);
        float sumFr = 0;
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            float b = l.fs[t + BASE];
            float maxUnit = l.fs[t + 2];
            if (maxUnit == U_FR) {
                l.fs[t + LIMIT] = b;
                sumFr += l.fs[t + 3];
            } else if (maxUnit == U_PX) {
                l.fs[t + LIMIT] = Math.max(l.fs[t + 3], b);
            } else {
                l.fs[t + LIMIT] = Math.max(l.fs[t + LIMIT], b);
            }
            used += b;
        }
        if (mode == UNDEFINED) {
            for (int i = 0; i < n; i++) {
                int t = base + i * T;
                used += l.fs[t + LIMIT] - l.fs[t + BASE];
                l.fs[t + BASE] = l.fs[t + LIMIT];
            }
        } else {
            float free = avail - used;
            for (int round = 0; round < n && free > 1e-4f; round++) {
                int growing = 0;
                for (int i = 0; i < n; i++) {
                    int t = base + i * T;
                    if (l.fs[t + 2] != U_FR && l.fs[t + BASE] < l.fs[t + LIMIT]) {
                        growing++;
                    }
                }
                if (growing == 0) {
                    break;
                }
                float share = free / growing;
                for (int i = 0; i < n; i++) {
                    int t = base + i * T;
                    if (l.fs[t + 2] != U_FR && l.fs[t + BASE] < l.fs[t + LIMIT]) {
                        float add = Math.min(share, l.fs[t + LIMIT] - l.fs[t + BASE]);
                        l.fs[t + BASE] += add;
                        free -= add;
                        used += add;
                    }
                }
            }
        }
        if (sumFr > 0) {
            used = flex(l, base, n, avail, mode, gap, used);
        }
        if (mode == EXACTLY && avail > used) {
            // Stretch the auto tracks over what is left (justify-content and align-content normal).
            int autos = 0;
            for (int i = 0; i < n; i++) {
                if (l.fs[base + i * T + 2] == U_AUTO) {
                    autos++;
                }
            }
            if (autos > 0) {
                float share = (avail - used) / autos;
                for (int i = 0; i < n; i++) {
                    int t = base + i * T;
                    if (l.fs[t + 2] == U_AUTO) {
                        l.fs[t + BASE] += share;
                    }
                }
                used = avail;
            }
        }
        float pos = 0;
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            l.fs[t + POS] = pos;
            pos += l.fs[t + BASE] + gap;
        }
        return used;
    }

    private static float gap(Layout l, int s, boolean columns) {
        return columns ? l.columnGap[s] : l.rowGap[s];
    }

    /** Sizes the {@code fr} tracks (CSS "find the size of an fr"); returns the new total. */
    private static float flex(Layout l, int base, int n, float avail, int mode, float gap, float used) {
        float fixed = gap * (n - 1);
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            l.fs[t + LIMIT] = l.fs[t + 2] == U_FR ? 1 : 0; // 1: flexible, still sharing
            if (l.fs[t + 2] != U_FR) {
                fixed += l.fs[t + BASE];
            }
        }
        float frSize = 0;
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            if (l.fs[t + 2] == U_FR) {
                float fr = l.fs[t + 3];
                frSize = Math.max(frSize, fr > 1 ? l.fs[t + BASE] / fr : l.fs[t + BASE]);
            }
        }
        float total = fixed;
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            if (l.fs[t + 2] == U_FR) {
                total += Math.max(l.fs[t + BASE], frSize * l.fs[t + 3]);
            }
        }
        if (mode == EXACTLY || (mode == AT_MOST && total > avail)) {
            // Definite space: share what the fixed tracks leave; a track whose content needs more keeps it.
            for (int round = 0; round <= n; round++) {
                float left = avail - fixed;
                float sumFr = 0;
                for (int i = 0; i < n; i++) {
                    int t = base + i * T;
                    if (l.fs[t + 2] == U_FR) {
                        if (l.fs[t + LIMIT] > 0) {
                            sumFr += l.fs[t + 3];
                        } else {
                            left -= l.fs[t + BASE];
                        }
                    }
                }
                frSize = Math.max(0, left) / Math.max(1, sumFr);
                boolean stable = true;
                for (int i = 0; i < n; i++) {
                    int t = base + i * T;
                    if (l.fs[t + 2] == U_FR && l.fs[t + LIMIT] > 0 && l.fs[t + BASE] > frSize * l.fs[t + 3]) {
                        l.fs[t + LIMIT] = 0;
                        stable = false;
                    }
                }
                if (stable) {
                    break;
                }
            }
        }
        total = fixed;
        for (int i = 0; i < n; i++) {
            int t = base + i * T;
            if (l.fs[t + 2] == U_FR) {
                l.fs[t + BASE] = Math.max(l.fs[t + BASE], frSize * l.fs[t + 3]);
                total += l.fs[t + BASE];
            }
        }
        return total;
    }
}
