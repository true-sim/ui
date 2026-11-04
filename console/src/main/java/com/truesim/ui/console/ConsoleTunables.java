package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.List;

/**
 * The console's tunables (the project's rule: caps and limits are settings). {@code engine-ui} registers
 * {@link #all()} beside {@code Ui.tunables()}; the console module sits above {@code ui-controls}, so the UI's list
 * cannot name them.
 */
public final class ConsoleTunables {

    private ConsoleTunables() {
    }

    /** Log records the console keeps (read when a ring or inbox is made). */
    public static final Tunable LOG_CAPACITY = Tunable.of("ui.console.logCapacity", 10_000, 100, 1_000_000,
        "Log lines the console keeps");
    /** Characters of a log line kept; longer lines are cut with an ellipsis. */
    public static final Tunable MAX_LINE = Tunable.of("ui.console.maxLine", 2_000, 80, 65_536,
        "Characters of a log line the console keeps");
    /** Command lines remembered per app. */
    public static final Tunable HISTORY_LIMIT = Tunable.of("ui.console.historyLimit", 200, 0, 10_000,
        "Command lines the console remembers");
    /** Completions the pop-up lists at most. */
    public static final Tunable COMPLETIONS = Tunable.of("ui.console.completions", 10, 1, 50,
        "Completions the console's pop-up lists");
    /** How deep aliases and scripts may call further aliases and scripts. */
    public static final Tunable EXEC_DEPTH = Tunable.of("ui.console.execDepth", 8, 1, 64,
        "How deeply console aliases and scripts may nest");

    /** Every console tunable, for an engine to register as settings. */
    @AllowAllocation("asked once")
    public static List<Tunable> all() {
        return List.of(LOG_CAPACITY, MAX_LINE, HISTORY_LIMIT, COMPLETIONS, EXEC_DEPTH);
    }
}
