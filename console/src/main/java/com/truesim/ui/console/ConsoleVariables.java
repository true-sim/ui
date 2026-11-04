package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The console variables (design 5.10): every registered setting, by name. The engine implements it over its settings
 * registry; {@link TunableVariables} exposes a UI's tunables; {@link #of} joins several. Asked on events (a command
 * run, a key typed in the console).
 */
public interface ConsoleVariables {

    /** No variables. */
    ConsoleVariables NONE = of();

    /** Every variable's name, sorted. */
    List<String> names();

    /** The variable named {@code name}, or null. */
    ConsoleVariable find(String name);

    /** The variables of {@code parts}, the first holding a name winning. */
    @AllowAllocation("made once")
    static ConsoleVariables of(ConsoleVariables... parts) {
        List<ConsoleVariables> all = List.of(parts);
        return new ConsoleVariables() {
            @Override
            @AllowAllocation("asked on events")
            public List<String> names() {
                TreeSet<String> names = new TreeSet<>();
                for (ConsoleVariables p : all) {
                    names.addAll(p.names());
                }
                return new ArrayList<>(names);
            }

            @Override
            public ConsoleVariable find(String name) {
                for (ConsoleVariables p : all) {
                    ConsoleVariable v = p.find(name);
                    if (v != null) {
                        return v;
                    }
                }
                return null;
            }
        };
    }
}
