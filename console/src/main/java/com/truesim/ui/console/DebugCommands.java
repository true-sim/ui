package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.controls.debug.UiDebugger;

/**
 * Console commands of the UI debugger ({@link UiDebugger}): {@code ui.inspect [on|off]} shows or hides (toggles)
 * its windows, {@code ui.overlay <bounds|clips|picking|dirty> [on|off]} turns an overlay on or off (toggles), and
 * {@code ui.pick} picks the next element pressed. Developer commands.
 */
@AllowAllocation("commands are declared once and run on events")
public final class DebugCommands {

    private static final String[] OVERLAYS = {"bounds", "clips", "picking", "dirty"};
    private static final int[] BITS = {UiDebugger.BOUNDS, UiDebugger.CLIPS, UiDebugger.PICKING, UiDebugger.DIRTY};

    private DebugCommands() {
    }

    /** Registers the commands of {@code debugger} with {@code shell}. */
    public static void register(ConsoleShell shell, UiDebugger debugger) {
        shell.register(ConsoleCommand.named("ui.inspect")
            .describe("Shows or hides the UI inspector and frame timings")
            .arg(Arg.bool("on").optional())
            .developer()
            .action(call -> debugger.show(call.has("on") ? call.bool("on") : !debugger.shown())));
        shell.register(ConsoleCommand.named("ui.overlay")
            .describe("Turns a UI debug overlay on or off")
            .arg(Arg.choice("overlay", OVERLAYS))
            .arg(Arg.bool("on").optional())
            .developer()
            .action(call -> {
                int bit = BITS[java.util.Arrays.asList(OVERLAYS).indexOf(call.text("overlay"))];
                boolean on = call.has("on") ? call.bool("on") : (debugger.overlays() & bit) == 0;
                debugger.overlay(bit, on);
            }));
        shell.register(ConsoleCommand.named("ui.pick")
            .describe("Selects the next UI element pressed in the inspector")
            .developer()
            .action(call -> {
                debugger.show(true);
                debugger.pick(true);
            }));
    }
}
