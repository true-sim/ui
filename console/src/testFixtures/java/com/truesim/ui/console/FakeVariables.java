package com.truesim.ui.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Console variables standing in for an engine's settings in tests and the console gallery: a bloom technique whose
 * {@code PHYSICAL} value the "hardware" does not support (the constraint overlay's refusal), a render scale with a
 * range, an on/off setting, a setting locked by a preset (unavailable) and a text setting.
 */
public final class FakeVariables implements ConsoleVariables {

    /** One fake setting. */
    public static final class Var implements ConsoleVariable {

        private final String name;
        private final String description;
        private final Kind kind;
        private final String initial;
        private final double min;
        private final double max;
        private final List<String> choices;
        private String value;
        /** Why it cannot be set now, or null. */
        public String lock;
        /** A value the constraint overlay refuses, with its reason. */
        public String refused;
        public String refusal;
        /** How many times it was set. */
        public int sets;

        Var(String name, String description, Kind kind, String initial, double min, double max, List<String> choices) {
            this.name = name;
            this.description = description;
            this.kind = kind;
            this.initial = initial;
            this.value = initial;
            this.min = min;
            this.max = max;
            this.choices = choices;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String defaultValue() {
            return initial;
        }

        @Override
        public double min() {
            return min;
        }

        @Override
        public double max() {
            return max;
        }

        @Override
        public List<String> choices() {
            return choices;
        }

        @Override
        public String availability() {
            return lock;
        }

        @Override
        public String set(String newValue) {
            if (newValue.equals(refused)) {
                return refusal;
            }
            value = newValue;
            sets++;
            return null;
        }
    }

    private final Map<String, Var> vars = new TreeMap<>();

    /** The fake settings. */
    public FakeVariables() {
        Var bloom = add(new Var("render.bloom", "Bloom technique", ConsoleVariable.Kind.CHOICE, "CHEAP", Double.NaN,
            Double.NaN, List.of("OFF", "CHEAP", "PHYSICAL")));
        bloom.refused = "PHYSICAL";
        bloom.refusal = "PHYSICAL needs compute shaders this GPU lacks";
        add(new Var("render.scale", "Resolution scale", ConsoleVariable.Kind.DECIMAL, "1", 0.5, 2, List.of()));
        add(new Var("render.vsync", "Wait for the display", ConsoleVariable.Kind.BOOLEAN, "true", Double.NaN,
            Double.NaN, List.of()));
        add(new Var("render.shadows", "Shadow map size", ConsoleVariable.Kind.INTEGER, "2048", 256, 8192, List.of()))
            .lock = "locked by the Low preset";
        add(new Var("game.name", "The player's name", ConsoleVariable.Kind.TEXT, "Ada", Double.NaN, Double.NaN,
            List.of()));
    }

    private Var add(Var v) {
        vars.put(v.name, v);
        return v;
    }

    /** The fake setting named {@code name}. */
    public Var var(String name) {
        return vars.get(name);
    }

    @Override
    public List<String> names() {
        return new ArrayList<>(vars.keySet());
    }

    @Override
    public ConsoleVariable find(String name) {
        return vars.get(name);
    }
}
