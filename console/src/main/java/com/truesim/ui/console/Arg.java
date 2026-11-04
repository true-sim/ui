package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A typed argument of a {@link ConsoleCommand} (design 5.10): what it accepts, how it completes and how it shows in
 * the command's signature. Made by the factories ({@code Arg.integer("count").range(1, 100)}), refined by the
 * chained setters, which return the argument.
 *
 * <ul>
 *   <li>{@link #integer}, {@link #decimal}: numbers, optionally in a {@link #range} (the hint shows it);</li>
 *   <li>{@link #bool}: {@code true false on off yes no 1 0};</li>
 *   <li>{@link #text}: any word, or with {@link #rest} the rest of the line;</li>
 *   <li>{@link #choice}: one of fixed options (any case; the option as declared is passed on), completed;</li>
 *   <li>{@link #asset}, {@link #node}: an asset of a type or a scene node, completed and checked by the console's
 *       {@link AssetProvider} and {@link NodeProvider} (the engine's);</li>
 *   <li>{@link #setting}, {@link #command}, {@link #script}: a console variable, a command, a script file.</li>
 * </ul>
 */
@AllowAllocation("arguments are declared once; parsing and completion run on events")
public final class Arg {

    /** What an argument accepts. */
    public enum Type {
        INTEGER, DECIMAL, BOOLEAN, TEXT, CHOICE, ASSET, NODE, SETTING, COMMAND, SCRIPT
    }

    /** Thrown when a word is not a valid value; the message says why. */
    public static final class Invalid extends Exception {

        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private final String name;
    private final Type type;
    private String description = "";
    private boolean optional;
    private boolean rest;
    private double min = Double.NaN;
    private double max = Double.NaN;
    private List<String> options = List.of();
    private String assetType;

    private Arg(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    /** A whole number. */
    public static Arg integer(String name) {
        return new Arg(name, Type.INTEGER);
    }

    /** A number. */
    public static Arg decimal(String name) {
        return new Arg(name, Type.DECIMAL);
    }

    /** A truth value. */
    public static Arg bool(String name) {
        return new Arg(name, Type.BOOLEAN);
    }

    /** A word (or, with {@link #rest}, the rest of the line). */
    public static Arg text(String name) {
        return new Arg(name, Type.TEXT);
    }

    /** One of {@code options}. */
    public static Arg choice(String name, String... options) {
        Arg a = new Arg(name, Type.CHOICE);
        a.options = List.of(options);
        return a;
    }

    /** One of the constants of {@code type}, by name. */
    public static Arg choice(String name, Class<? extends Enum<?>> type) {
        List<String> names = new ArrayList<>();
        for (Enum<?> e : type.getEnumConstants()) {
            names.add(e.name());
        }
        Arg a = new Arg(name, Type.CHOICE);
        a.options = List.copyOf(names);
        return a;
    }

    /** An asset of {@code assetType} ({@code texture}, {@code mesh}), by path, through the {@link AssetProvider}. */
    public static Arg asset(String name, String assetType) {
        Arg a = new Arg(name, Type.ASSET);
        a.assetType = assetType;
        return a;
    }

    /** A scene node, by name or path, through the {@link NodeProvider}. */
    public static Arg node(String name) {
        return new Arg(name, Type.NODE);
    }

    /** A console variable (a setting). */
    public static Arg setting(String name) {
        return new Arg(name, Type.SETTING);
    }

    /** A command, alias or setting name. */
    public static Arg command(String name) {
        return new Arg(name, Type.COMMAND);
    }

    /** A script file, through the {@link ScriptSource}. */
    public static Arg script(String name) {
        return new Arg(name, Type.SCRIPT);
    }

    /** Limits a number to {@code min..max}; returns this. */
    public Arg range(double low, double high) {
        if (type != Type.INTEGER && type != Type.DECIMAL) {
            throw new IllegalStateException(name + " is not a number");
        }
        min = low;
        max = high;
        return this;
    }

    /** Makes it optional (it may be left out; later arguments must be optional too); returns this. */
    public Arg optional() {
        optional = true;
        return this;
    }

    /** Makes a text argument take the rest of the line (the last argument only); returns this. */
    public Arg rest() {
        if (type != Type.TEXT) {
            throw new IllegalStateException(name + ": only text takes the rest of the line");
        }
        rest = true;
        return this;
    }

    /** Describes it (shown by {@code help}); returns this. */
    public Arg describe(String text) {
        description = text;
        return this;
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    public String description() {
        return description;
    }

    public boolean isOptional() {
        return optional;
    }

    public boolean isRest() {
        return rest;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** A choice's options. */
    public List<String> options() {
        return options;
    }

    /** An asset argument's type, or null. */
    public String assetType() {
        return assetType;
    }

    /** {@code <count: 1..100>} or {@code [kind: orc|elf]}: how the signature shows it. */
    public String signature() {
        StringBuilder s = new StringBuilder(optional ? "[" : "<").append(name);
        String detail = switch (type) {
            case INTEGER, DECIMAL -> Double.isNaN(min) ? (type == Type.INTEGER ? "int" : "number")
                : number(min) + ".." + number(max);
            case BOOLEAN -> "on|off";
            case CHOICE -> String.join("|", options);
            case ASSET -> assetType;
            case NODE -> "node";
            case SETTING -> "setting";
            case COMMAND -> "command";
            case SCRIPT -> "file";
            case TEXT -> rest ? "text..." : null;
        };
        if (detail != null) {
            s.append(": ").append(detail);
        }
        return s.append(optional ? "]" : ">").toString();
    }

    static String number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    /** The value of {@code word} for this argument (Integer, Double, Boolean or String), or {@link Invalid}. */
    Object parse(String word, ConsoleShell shell) throws Invalid {
        switch (type) {
            case INTEGER -> {
                long v;
                try {
                    v = Long.parseLong(word);
                } catch (NumberFormatException e) {
                    throw new Invalid(name + ": " + word + " is not a whole number");
                }
                checkRange(v, word);
                if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                    throw new Invalid(name + ": " + word + " is too large");
                }
                return (int) v;
            }
            case DECIMAL -> {
                double v;
                try {
                    v = Double.parseDouble(word);
                } catch (NumberFormatException e) {
                    throw new Invalid(name + ": " + word + " is not a number");
                }
                if (Double.isNaN(v)) {
                    throw new Invalid(name + ": " + word + " is not a number");
                }
                checkRange(v, word);
                return v;
            }
            case BOOLEAN -> {
                Boolean b = parseBoolean(word);
                if (b == null) {
                    throw new Invalid(name + ": " + word + " is not on or off");
                }
                return b;
            }
            case CHOICE -> {
                for (String o : options) {
                    if (o.equalsIgnoreCase(word)) {
                        return o;
                    }
                }
                throw new Invalid(name + ": " + word + " is not one of " + String.join(", ", options));
            }
            case ASSET -> {
                if (shell.assets() != null && !shell.assets().exists(assetType, word)) {
                    throw new Invalid(name + ": no " + assetType + " " + word);
                }
                return word;
            }
            case NODE -> {
                if (shell.nodes() != null && !shell.nodes().exists(word)) {
                    throw new Invalid(name + ": no node " + word);
                }
                return word;
            }
            case SETTING -> {
                if (shell.variables().find(word) == null) {
                    throw new Invalid(name + ": no setting " + word);
                }
                return word;
            }
            case COMMAND -> {
                if (shell.command(word) == null && shell.alias(word) == null && shell.variables().find(word) == null) {
                    throw new Invalid(name + ": no command " + word);
                }
                return word;
            }
            default -> {
                return word;
            }
        }
    }

    private void checkRange(double v, String word) throws Invalid {
        if (!Double.isNaN(min) && (v < min || v > max)) {
            throw new Invalid(name + ": " + word + " is not in " + number(min) + ".." + number(max));
        }
    }

    /** {@code word} as a truth value, or null. */
    static Boolean parseBoolean(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Adds the values starting with {@code prefix} (ignoring case) to {@code out}. */
    void complete(String prefix, ConsoleShell shell, List<String> out) {
        switch (type) {
            case BOOLEAN -> Completer.filter(List.of("on", "off"), prefix, out);
            case CHOICE -> Completer.filter(options, prefix, out);
            case ASSET -> {
                if (shell.assets() != null) {
                    shell.assets().complete(assetType, prefix, out);
                }
            }
            case NODE -> {
                if (shell.nodes() != null) {
                    shell.nodes().complete(prefix, out);
                }
            }
            case SETTING -> Completer.filter(shell.variables().names(), prefix, out);
            case COMMAND -> {
                List<String> names = new ArrayList<>(shell.commandNames());
                names.addAll(new java.util.TreeSet<>(shell.aliases().keySet()));
                names.addAll(shell.variables().names());
                Completer.filter(names, prefix, out);
            }
            case SCRIPT -> {
                if (shell.scripts() != null) {
                    shell.scripts().list(prefix, out);
                }
            }
            default -> {
                // numbers and text: the hint says what to type
            }
        }
    }

    @Override
    public String toString() {
        return signature();
    }
}
