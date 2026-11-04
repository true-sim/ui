package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;

/**
 * A uniform grid over a scatter series' points, in the axes' transformed units over the data's bounds, built when the
 * data changes (a counting sort: O(n), two int arrays): the point nearest the pointer in pixels is found by searching
 * rings of cells outwards from the pointer's cell, stopping once a ring cannot hold anything nearer. Queries allocate
 * nothing and touch a few cells, whatever the zoom.
 */
public final class ScatterGrid {

    private int cellsX;
    private int cellsY;
    private double x0;
    private double y0;
    private double cellW;
    private double cellH;
    private int[] start = new int[0];
    private int[] items = new int[0];
    private long first;
    private int count;
    private long best;
    private double bestDistance;

    /** Builds the grid over samples {@code a..b} of {@code x, y}, about {@code perCell} points to a cell. */
    public void build(Column x, Column y, long a, long b, Axis xAxis, Axis yAxis, float perCell) {
        first = a;
        count = (int) Math.max(0, Math.min(Integer.MAX_VALUE - 8, b - a));
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double tx = xAxis.forward(x.get(a + i));
            double ty = yAxis.forward(y.get(a + i));
            if (tx == tx && ty == ty) {
                minX = Math.min(minX, tx);
                maxX = Math.max(maxX, tx);
                minY = Math.min(minY, ty);
                maxY = Math.max(maxY, ty);
            }
        }
        int side = (int) Math.max(1, Math.min(4096, Math.ceil(Math.sqrt(count / Math.max(1f, perCell)))));
        cellsX = side;
        cellsY = side;
        x0 = minX;
        y0 = minY;
        cellW = maxX > minX ? (maxX - minX) / side : 1;
        cellH = maxY > minY ? (maxY - minY) / side : 1;
        ensure(side * side + 1, count);
        java.util.Arrays.fill(start, 0, side * side + 1, 0);
        for (int i = 0; i < count; i++) {
            int c = cell(xAxis.forward(x.get(a + i)), yAxis.forward(y.get(a + i)));
            if (c >= 0) {
                start[c + 1]++;
            }
        }
        for (int c = 0; c < side * side; c++) {
            start[c + 1] += start[c];
        }
        // place items: start[c] is the next free entry of cell c, so it ends at the next cell's start
        for (int i = 0; i < count; i++) {
            int c = cell(xAxis.forward(x.get(a + i)), yAxis.forward(y.get(a + i)));
            if (c >= 0) {
                items[start[c]++] = i;
            }
        }
        for (int c = side * side; c > 0; c--) {
            start[c] = start[c - 1];
        }
        start[0] = 0;
    }

    @AllowAllocation("the grid grows with its data, rarely")
    private void ensure(int cells, int points) {
        if (start.length < cells) {
            start = new int[cells];
        }
        if (items.length < points) {
            items = new int[Math.max(points, items.length * 3 / 2)];
        }
    }

    private int cell(double tx, double ty) {
        if (tx != tx || ty != ty) {
            return -1;
        }
        int cx = (int) Math.max(0, Math.min(cellsX - 1, Math.floor((tx - x0) / cellW)));
        int cy = (int) Math.max(0, Math.min(cellsY - 1, Math.floor((ty - y0) / cellH)));
        return cy * cellsX + cx;
    }

    /**
     * The index of the point nearest pixel {@code px, py} within {@code maxPixels} (-1 if none); {@link #distance()}
     * is its distance in pixels.
     */
    public long nearest(Column x, Column y, Axis xAxis, Axis yAxis, float px, float py, float maxPixels) {
        best = -1;
        bestDistance = maxPixels;
        if (count == 0) {
            return -1;
        }
        double tx = xAxis.forward(xAxis.fromPixel(px));
        double ty = yAxis.forward(yAxis.fromPixel(py));
        double kx = Math.abs(xAxis.pixelsPerUnit());
        double ky = Math.abs(yAxis.pixelsPerUnit());
        int cx = (int) Math.floor((tx - x0) / cellW);
        int cy = (int) Math.floor((ty - y0) / cellH);
        double ringX = cellW * kx;
        double ringY = cellH * ky;
        int maxRing = Math.max(cellsX, cellsY) + Math.max(Math.abs(cx), Math.abs(cy)) + 1;
        for (int r = 0; r <= maxRing; r++) {
            // a point in ring r lies at least (r - 1) cells away along one axis
            double reach = (r - 1) * Math.min(ringX, ringY);
            if (r > 1 && reach > bestDistance) {
                break;
            }
            for (int gy = cy - r; gy <= cy + r; gy++) {
                if (gy < 0 || gy >= cellsY) {
                    continue;
                }
                boolean edgeRow = gy == cy - r || gy == cy + r;
                for (int gx = cx - r; gx <= cx + r; gx += edgeRow ? 1 : 2 * r) {
                    if (gx >= 0 && gx < cellsX) {
                        visit(gy * cellsX + gx, x, y, xAxis, yAxis, px, py);
                    }
                    if (r == 0) {
                        break;
                    }
                }
            }
        }
        return best;
    }

    private void visit(int c, Column x, Column y, Axis xAxis, Axis yAxis, float px, float py) {
        for (int e = start[c], end = start[c + 1]; e < end; e++) {
            long i = first + items[e];
            float dx = xAxis.toPixel(x.get(i)) - px;
            float dy = yAxis.toPixel(y.get(i)) - py;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d <= bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
    }

    /** The distance in pixels of the last {@link #nearest} point. */
    public double distance() {
        return bestDistance;
    }

    /** Cells per side. */
    public int side() {
        return cellsX;
    }
}
