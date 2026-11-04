package com.truesim.ui.controls.data;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.UiTestAccess;
import com.truesim.ui.core.input.UiInput;
import com.truesim.ui.style.StyleSheet;
import com.truesim.ui.style.tss.Tss;
import com.truesim.ui.testkit.SyntheticInput;
import com.truesim.ui.text.TextSystem;

/** A UI with input and a clock for data-control tests, one frame at a time. */
final class DataFrame {

    /** Plain, exact geometry: 20 px rows, 10 px bars, 10 px buttons, no padding. */
    static final StyleSheet SHEET = Tss.compileString("data-test.tss", """
        root { layout: flex; direction: column; align-items: stretch; }
        panel { layout: stack; }
        label { font-family: "Inter"; font-size: 12px; color: white; line-height: 1.25; }
        scroll-pane, list-view, tree-view, table-view { height: 200px; width: 300px; scroll-step: 20; }
        scroll-viewport { clip: true; }
        scroll-bar { width: 10px; height: 10px; background: box(#202020); thumb-min: 8; }
        scroll-bar[active=true] { background: box(#404040); }
        scroll-button { width: 10px; height: 10px; background: box(#303030); }
        scroll-thumb { background: box(#808080); }
        list-item, tree-item, table-row { height: 20px; layout: flex; direction: row; }
        list-item:selected, tree-item:selected, table-row:selected { background: box(#0000ff); }
        list-item:focused, tree-item:focused { border: 1px #ffff00; }
        list-drop-indicator { height: 2px; background: box(#ff0000); }
        tree-item { indent: 12; }
        tree-indent { height: 4px; }
        tree-expander { width: 10px; height: 10px; background: box(#ffffff); }
        table-header-row, table-filter-row { height: 20px; }
        table-header-cell { layout: flex; direction: row; column-min-width: 30; }
        table-header-cell > label { grow: 1; }
        table-header-cell[sort=ascending] { background: box(#00ff00); }
        table-header-cell[sort=descending] { background: box(#ff0000); }
        table-column-grip { width: 4px; background: box(#00ffff); }
        table-column-drop-indicator { width: 2px; background: box(#ff00ff); }
        table-cell { layout: flex; direction: row; }
        table-cell:selected { background: box(#00ff00); }
        value-editor { layout: flex; grow: 1; }
        value-check { width: 10px; height: 10px; }
        """);

    final Ui ui;
    final UiInput input = new UiInput(32);
    final SyntheticInput synthetic = new SyntheticInput();
    long now = 1_000_000_000L;
    float width = 400;
    float height = 300;

    DataFrame() {
        this(SHEET, UiTestAccess.jdkText());
    }

    DataFrame(StyleSheet sheet, TextSystem text) {
        ui = new Ui(sheet, text);
    }

    DataFrame run() {
        input.beginFrame();
        synthetic.poll(input);
        ui.update(width, height, input, now);
        now += 16_000_000L;
        return this;
    }

    DataFrame run(int frames) {
        for (int i = 0; i < frames; i++) {
            run();
        }
        return this;
    }

    DataFrame mouse(float x, float y, int buttons) {
        synthetic.mouse(x, y, buttons);
        return run();
    }

    DataFrame wheel(float x, float y, float dy) {
        synthetic.mouse(x, y, 0);
        synthetic.scroll(SyntheticInput.MOUSE, 0, dy);
        return run();
    }
}
