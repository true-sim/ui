package com.truesim.ui.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.ui.controls.Ui;
import com.truesim.ui.controls.debug.UiDebugger;
import org.junit.jupiter.api.Test;

/** The UI debugger's console commands. */
class DebugCommandsTest {

    @Test
    void commandsToggleTheInspectorOverlaysAndPicking() {
        Ui ui = ConsoleGalleryTest.ui("default");
        UiDebugger debugger = new UiDebugger(ui);
        ConsoleShell shell = new ConsoleShell(new LogRing(32));
        DebugCommands.register(shell, debugger);
        assertThat(shell.execute("ui.inspect")).isTrue();
        assertThat(debugger.shown()).isTrue();
        assertThat(shell.execute("ui.inspect off")).isTrue();
        assertThat(debugger.shown()).isFalse();
        assertThat(shell.execute("ui.overlay bounds")).isTrue();
        assertThat(debugger.overlays()).isEqualTo(UiDebugger.BOUNDS);
        assertThat(shell.execute("ui.overlay dirty on; ui.overlay bounds")).isTrue();
        assertThat(debugger.overlays()).isEqualTo(UiDebugger.DIRTY);
        assertThat(shell.execute("ui.overlay clips off")).isTrue();
        assertThat(shell.execute("ui.pick")).isTrue();
        assertThat(debugger.picking()).isTrue();
        assertThat(debugger.shown()).isTrue();
        assertThat(shell.complete("ui.overlay p", 12).candidates()).extracting(Completion.Candidate::text)
            .containsExactly("picking");
        shell.permissions(ConsolePermissions.PLAYER);
        assertThat(shell.execute("ui.inspect")).isFalse();
    }
}
