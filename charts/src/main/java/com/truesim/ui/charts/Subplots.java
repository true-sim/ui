package com.truesim.ui.charts;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;

/**
 * A grid of charts (ImPlot's subplots): an element {@code chart-subplots} holding rows {@code chart-subplots-row} of
 * charts (sizes and gaps from the theme), whose axes can be linked by column (shared X ranges: zoom one, all of the
 * column follow) and by row (shared Y ranges), with one crosshair across all of them.
 */
public final class Subplots {

    private final Element root;
    private final Chart[][] charts;
    private final Crosshair crosshair;

    /** {@code rows} by {@code columns} charts, named {@code name} and their row and column. */
    @AllowAllocation("subplots are made when a UI is built")
    public Subplots(Ui ui, String name, int rows, int columns) {
        if (rows < 1 || columns < 1) {
            throw new IllegalArgumentException("subplots need a row and a column: " + rows + " x " + columns);
        }
        root = ui.element("chart-subplots", null);
        root.semantics(com.truesim.ui.core.Role.GROUP, name);
        charts = new Chart[rows][columns];
        crosshair = new Crosshair(ui.store().clock());
        for (int r = 0; r < rows; r++) {
            Element row = ui.element("chart-subplots-row", null);
            root.add(row);
            for (int c = 0; c < columns; c++) {
                Chart chart = new Chart(ui, name + " " + (r + 1) + "," + (c + 1));
                chart.crosshair(crosshair);
                charts[r][c] = chart;
                row.add(chart.element());
            }
        }
    }

    /** The grid's element. */
    public Element element() {
        return root;
    }

    /** The chart at {@code row, column}. */
    public Chart chart(int row, int column) {
        return charts[row][column];
    }

    /** Rows. */
    public int rows() {
        return charts.length;
    }

    /** Columns. */
    public int columns() {
        return charts[0].length;
    }

    /** The crosshair all the charts share. */
    public Crosshair crosshair() {
        return crosshair;
    }

    /** Links the first X axis of each column's charts (the top chart's range); returns this. */
    public Subplots linkColumns() {
        for (int c = 0; c < columns(); c++) {
            AxisRange shared = charts[0][c].x().range();
            for (int r = 1; r < rows(); r++) {
                charts[r][c].x().link(shared);
            }
        }
        return this;
    }

    /** Links the first Y axis of each row's charts (the left chart's range); returns this. */
    public Subplots linkRows() {
        for (int r = 0; r < rows(); r++) {
            AxisRange shared = charts[r][0].y().range();
            for (int c = 1; c < columns(); c++) {
                charts[r][c].y().link(shared);
            }
        }
        return this;
    }

    /** Links every chart's first X axis to the first chart's; returns this. */
    public Subplots linkAllX() {
        AxisRange shared = charts[0][0].x().range();
        for (Chart[] row : charts) {
            for (Chart chart : row) {
                if (chart != charts[0][0]) {
                    chart.x().link(shared);
                }
            }
        }
        return this;
    }
}
