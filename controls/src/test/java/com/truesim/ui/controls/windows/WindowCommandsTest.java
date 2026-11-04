package com.truesim.ui.controls.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** Windows and docking by commands: moving and resizing from the keyboard, cycling windows, panes and tabs. */
class WindowCommandsTest {

    @Test
    void windowMoveAndResizeStartKeyboardModes() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow w = windows.create("tool", "Tool").bounds(100, 100, 300, 200);
        Element inside = f.ui.panel(null);
        f.ui.interaction().focusable(inside, true);
        w.content().add(inside);
        w.open();
        f.frames(3);
        f.ui.interaction().focus(inside);
        f.command(UiCommand.WINDOW_MOVE);
        assertThat(w.keyboardActive()).isTrue();
        f.command(UiCommand.RIGHT, UiCommand.RIGHT, UiCommand.DOWN);
        assertThat(w.x()).isEqualTo(100 + 2 * WindowManager.KEY_STEP.get());
        assertThat(w.y()).isEqualTo(100 + WindowManager.KEY_STEP.get());
        f.command(UiCommand.ACCEPT);
        assertThat(w.keyboardActive()).isFalse();
        f.command(UiCommand.WINDOW_RESIZE);
        assertThat(w.keyboardActive()).isTrue();
        f.command(UiCommand.RIGHT);
        assertThat(w.width()).isEqualTo(300 + WindowManager.KEY_STEP.get());
        f.command(UiCommand.CANCEL);
        assertThat(w.width()).as("cancel puts it back").isEqualTo(300);
        f.ui.interaction().blur(0);
        f.run();
        f.command(UiCommand.WINDOW_MOVE);
        assertThat(w.keyboardActive()).as("nothing focused: the active window").isTrue();
        f.command(UiCommand.ACCEPT);
        w.resizable(false);
        f.ui.interaction().focus(inside);
        f.command(UiCommand.WINDOW_RESIZE);
        assertThat(w.keyboardActive()).as("a fixed-size window does not resize").isFalse();
    }

    @Test
    void nextWindowCyclesTheOpenWindows() {
        Fx f = new Fx();
        WindowManager windows = new WindowManager(f.overlays);
        FloatingWindow a = windows.create("a", "A").bounds(10, 10, 200, 100);
        FloatingWindow b = windows.create("b", "B").bounds(300, 10, 200, 100);
        f.command(UiCommand.NEXT_WINDOW);
        a.open();
        b.open();
        f.frames(3);
        assertThat(windows.active()).isEqualTo(b);
        f.command(UiCommand.NEXT_WINDOW);
        f.frames(2);
        assertThat(windows.active()).isEqualTo(a);
        f.command(UiCommand.NEXT_WINDOW);
        f.frames(2);
        assertThat(windows.active()).isEqualTo(b);
    }

    @Test
    void tabCommandsSwitchAndCloseDockedPanelsAndNextWindowMovesBetweenPanes() {
        DockTest.Rig r = new DockTest.Rig().editor();
        Fx f = r.f;
        f.ui.interaction().focus(r.game.tab());
        f.run();
        assertThat(r.dock.pane(paneOf(r, r.game)).get(0)).isNotNull();
        Element pane = r.dock.paneOf(r.scene);
        assertThat(r.dock.paneOf(r.game)).isEqualTo(pane);
        f.command(UiCommand.NEXT_TAB);
        assertThat(r.scene.tab().is(com.truesim.ui.core.UiState.SELECTED)).as("wrapping").isTrue();
        assertThat(f.ui.interaction().focused(0)).as("focus follows on the tabs").isEqualTo(r.scene.tab());
        f.command(UiCommand.PREVIOUS_TAB);
        assertThat(r.game.tab().is(com.truesim.ui.core.UiState.SELECTED)).isTrue();
        f.command(UiCommand.CLOSE_TAB);
        f.run();
        assertThat(r.game.isOpen()).isFalse();
        assertThat(f.ui.interaction().focused(0)).isEqualTo(r.scene.tab());
        f.command(UiCommand.NEXT_TAB);
        assertThat(r.scene.tab().is(com.truesim.ui.core.UiState.SELECTED)).as("one tab: nothing to switch").isTrue();
        Element before = f.ui.interaction().focused(0);
        f.command(UiCommand.NEXT_WINDOW);
        f.run();
        Element after = f.ui.interaction().focused(0);
        assertThat(after).isNotEqualTo(before);
        assertThat(r.dock.paneOf(panelOfTab(r, after))).isNotEqualTo(pane);
        for (int i = 0; i < r.dock.paneCount() - 1; i++) {
            f.command(UiCommand.NEXT_WINDOW);
            f.run();
        }
        assertThat(f.ui.interaction().focused(0)).as("all the way round").isEqualTo(before);
    }

    private static int paneOf(DockTest.Rig r, DockPanel p) {
        for (int i = 0; i < r.dock.paneCount(); i++) {
            if (r.dock.pane(i).contains(p)) {
                return i;
            }
        }
        return -1;
    }

    private static DockPanel panelOfTab(DockTest.Rig r, Element tab) {
        for (DockPanel p : new DockPanel[] {r.scene, r.hierarchy, r.inspector, r.console, r.game}) {
            if (p.tab() == tab) {
                return p;
            }
        }
        throw new AssertionError("not a tab: " + tab);
    }
}
