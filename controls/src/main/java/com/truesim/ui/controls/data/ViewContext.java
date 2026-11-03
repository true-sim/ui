package com.truesim.ui.controls.data;

import com.truesim.ui.controls.Ui;

/**
 * What a {@link ValueRenderer} or {@link ValueEditor} builds its views with: the UI, and the container asking (for a
 * renderer that adapts to a list or a table).
 *
 * @param ui the UI the views live in
 * @param container the asking container's element type ({@code list-view}, {@code tree-view}, {@code table-view},
 *     {@code combo-box}, ...)
 */
public record ViewContext(Ui ui, String container) {
}
