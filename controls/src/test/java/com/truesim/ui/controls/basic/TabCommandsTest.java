package com.truesim.ui.controls.basic;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.VersionedInt;
import com.truesim.ui.core.Element;
import com.truesim.ui.core.input.UiCommand;
import org.junit.jupiter.api.Test;

/** The tab commands in a tab pane: next and previous anywhere in it (wrapping), closing the selected tab. */
class TabCommandsTest {

    @Test
    void nextPreviousAndCloseTab() {
        Fixture f = new Fixture();
        VersionedInt selected = f.c.clock().intValue(0);
        TabPane tabs = f.c.tabs("Panels", TabPane.Side.TOP, selected);
        Button inside = f.c.button("Inside");
        tabs.add("Scene", inside.element(), false);
        tabs.add("Assets", f.ui.panel(null), true);
        tabs.add("Log", f.ui.panel(null), true);
        f.add(tabs.element()).settle();
        f.focus(inside.element());
        f.press(UiCommand.PREVIOUS_TAB);
        assertThat(selected.get()).as("wrapping back").isEqualTo(2);
        f.press(UiCommand.NEXT_TAB);
        assertThat(selected.get()).isZero();
        f.focus(tabs.tab(0).button().element());
        f.press(UiCommand.NEXT_TAB);
        assertThat(selected.get()).isEqualTo(1);
        assertThat(f.ui.interaction().focused(0)).as("the focus follows the tab")
            .isEqualTo(tabs.tab(1).button().element());
        f.press(UiCommand.CLOSE_TAB);
        assertThat(tabs.count()).isEqualTo(2);
        Element focused = f.ui.interaction().focused(0);
        assertThat(focused).isEqualTo(tabs.tab(selected.get()).button().element());
        tabs.select(0);
        f.run();
        f.press(UiCommand.CLOSE_TAB);
        assertThat(tabs.count()).as("Scene is not closable").isEqualTo(2);
    }
}
