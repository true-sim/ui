package com.truesim.ui.controls.data;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.UiState;

/**
 * Where a value is shown and how (design 4.3): one reused object a container fills before each
 * {@link ValueRenderer#bind}. The states are also on the cell's element ({@link #cell()}), so styles select
 * {@code list-item:selected label} without the renderer's help; the renderer reads them for what styles cannot do.
 * The renderer names the value for assistive technology with {@link #name}.
 */
public final class CellState {

    private final Ui ui;
    private int states;
    private int index;
    private int column;
    private int depth;
    private boolean expanded;
    private boolean leaf = true;
    private Element cell;
    private CharSequence name;

    /** A state for cells of {@code ui}. */
    public CellState(Ui ui) {
        this.ui = ui;
    }

    /** The UI the cell lives in: renderers write text with {@code ui().text(label, value)}. */
    public Ui ui() {
        return ui;
    }

    /** Clears it for a cell of row {@code index}, column {@code column} (-1 outside tables). For containers. */
    public CellState reset(Element cellElement, int rowIndex, int columnIndex) {
        cell = cellElement;
        index = rowIndex;
        column = columnIndex;
        states = cellElement == null ? 0 : cellElement.store().state(cellElement.slot());
        depth = 0;
        expanded = false;
        leaf = true;
        name = null;
        return this;
    }

    /** Sets the tree place; for containers. */
    public CellState tree(int level, boolean isExpanded, boolean isLeaf) {
        depth = level;
        expanded = isExpanded;
        leaf = isLeaf;
        return this;
    }

    /** Whether state {@code bit} ({@link UiState}) is on for the cell. */
    public boolean is(int bit) {
        return (states & bit) != 0;
    }

    public boolean selected() {
        return is(UiState.SELECTED);
    }

    public boolean focused() {
        return is(UiState.FOCUSED);
    }

    public boolean hovered() {
        return is(UiState.HOVER);
    }

    public boolean pressed() {
        return is(UiState.PRESSED);
    }

    public boolean disabled() {
        return is(UiState.DISABLED);
    }

    public boolean editing() {
        return is(UiState.EDITING);
    }

    /** The row's index in the container's (sorted, filtered, flattened) rows. */
    public int index() {
        return index;
    }

    /** The column's index in a table's shown columns, or -1. */
    public int column() {
        return column;
    }

    /** The depth in a tree (0 for top-level nodes and outside trees). */
    public int depth() {
        return depth;
    }

    /** Whether a tree node is expanded. */
    public boolean expanded() {
        return expanded;
    }

    /** Whether a tree node has no children (loaded or to load). */
    public boolean leaf() {
        return leaf;
    }

    /** The cell's element (the row or the table cell): its id path and states are what styles select. */
    public Element cell() {
        return cell;
    }

    /** Names the shown value for assistive technology: live text is best (a label's {@code Ui.text}). */
    public void name(CharSequence value) {
        name = value;
    }

    /** The name the renderer gave, or null. */
    public CharSequence name() {
        return name;
    }
}
