package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.core.Tunable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tunables as console variables: a UI's ({@code Ui.tunables()}) and the console's ({@link ConsoleTunables#all()})
 * where no engine settings registry stands behind the console (tools, tests, the galleries). A choice tunable is a
 * {@link ConsoleVariable.Kind#CHOICE}, a number a {@link ConsoleVariable.Kind#DECIMAL} (an
 * {@link ConsoleVariable.Kind#INTEGER} when its range and default are whole); values outside the range are refused,
 * not clamped, so the console says why. In an engine the tunables are settings, so its variables hold them already.
 */
@AllowAllocation("made once; read on events")
public final class TunableVariables implements ConsoleVariables {

    private final Map<String, Variable> byName = new TreeMap<>();

    /** The variables of {@code tunables}. */
    public TunableVariables(List<Tunable> tunables) {
        for (Tunable t : tunables) {
            byName.put(t.name(), new Variable(t));
        }
    }

    @Override
    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    @Override
    public ConsoleVariable find(String name) {
        return byName.get(name);
    }

    /** One tunable as a variable. */
    private static final class Variable implements ConsoleVariable {

        private final Tunable tunable;
        private final Kind kind;

        Variable(Tunable tunable) {
            this.tunable = tunable;
            boolean whole = whole(tunable.min()) && whole(tunable.max()) && whole(tunable.initial());
            this.kind = tunable.isChoice() ? Kind.CHOICE : whole ? Kind.INTEGER : Kind.DECIMAL;
        }

        private static boolean whole(float v) {
            return v == Math.rint(v);
        }

        @Override
        public String name() {
            return tunable.name();
        }

        @Override
        public String description() {
            return tunable.description() == null ? "" : tunable.description();
        }

        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public String value() {
            return text(tunable.get());
        }

        @Override
        public String defaultValue() {
            return text(tunable.initial());
        }

        private String text(float v) {
            if (kind == Kind.CHOICE) {
                return tunable.options().get((int) v);
            }
            return Arg.number(v);
        }

        @Override
        public double min() {
            return kind == Kind.CHOICE ? Double.NaN : tunable.min();
        }

        @Override
        public double max() {
            return kind == Kind.CHOICE ? Double.NaN : tunable.max();
        }

        @Override
        public List<String> choices() {
            return tunable.options();
        }

        @Override
        public String set(String value) {
            if (kind == Kind.CHOICE) {
                tunable.select(value);
                return null;
            }
            double v = Double.parseDouble(value);
            if (v < tunable.min() || v > tunable.max()) {
                return value + " is not in " + Arg.number(tunable.min()) + ".." + Arg.number(tunable.max());
            }
            tunable.set((float) v);
            return null;
        }
    }
}
