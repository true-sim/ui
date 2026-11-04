package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The console's model (design 5.10): commands, aliases and variables by name, run from command lines and completed
 * at a caret, with output to a {@link LogSink} (the console's log, logger {@value #LOGGER}). No UI: the
 * {@link Console} widget shows it, a server's remote console or a test drives it directly.
 *
 * <ul>
 *   <li><b>Commands</b> ({@link #register}) with typed arguments, checked before their action runs; permissions
 *       ({@link #permissions}: the developer tier, cheats) say why a command may not run.</li>
 *   <li><b>Variables</b> ({@link #variables}): every setting by name. {@code render.bloom} prints its value, default,
 *       range or choices and availability; {@code render.bloom PHYSICAL} sets it, through the setting's constraints,
 *       printing why a value is refused.</li>
 *   <li><b>Aliases</b> ({@code alias name "a; b"}) run their commands, with the words after them appended;
 *       <b>scripts</b> ({@code exec file}) run a file's lines through the {@link ScriptSource}; both nest up to
 *       {@link ConsoleTunables#EXEC_DEPTH}.</li>
 *   <li><b>Built in</b>: {@code help}, {@code alias}, {@code unalias}, {@code exec}, {@code echo}, {@code reset},
 *       {@code toggle}, {@code find}.</li>
 * </ul>
 *
 * Running and completing are events (a line submitted, a key typed): they may allocate.
 */
@AllowAllocation("commands run and complete on events, not per frame")
public final class ConsoleShell {

    /** The logger name of the console's own output. */
    public static final String LOGGER = "console";

    private final Map<String, ConsoleCommand> commands = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> aliases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private ConsoleVariables variables = ConsoleVariables.NONE;
    private ConsolePermissions permissions = ConsolePermissions.ALL;
    private AssetProvider assets;
    private NodeProvider nodes;
    private ScriptSource scripts;
    private final LogSink output;
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private int depth;

    /** A shell printing to {@code output}, with the built-in commands. */
    public ConsoleShell(LogSink output) {
        this.output = output;
        Builtins.register(this);
    }

    // ---------------------------------------------------------------- configuration

    /** Registers {@code command} (replacing one of the same name); returns this. */
    public ConsoleShell register(ConsoleCommand command) {
        commands.put(command.name(), command);
        return this;
    }

    /** Removes the command named {@code name}. */
    public void unregister(String name) {
        commands.remove(name);
    }

    /** The command named {@code name} (any case), or null. */
    public ConsoleCommand command(String name) {
        return commands.get(name);
    }

    /** Every command's name, sorted. */
    public List<String> commandNames() {
        return new ArrayList<>(commands.keySet());
    }

    /** Sets the variables (the engine's settings); returns this. */
    public ConsoleShell variables(ConsoleVariables value) {
        variables = value == null ? ConsoleVariables.NONE : value;
        return this;
    }

    public ConsoleVariables variables() {
        return variables;
    }

    /** Sets who may run what; returns this. */
    public ConsoleShell permissions(ConsolePermissions value) {
        permissions = value == null ? ConsolePermissions.ALL : value;
        return this;
    }

    public ConsolePermissions permissions() {
        return permissions;
    }

    /** Sets the assets {@link Arg#asset} arguments complete from; returns this. */
    public ConsoleShell assets(AssetProvider value) {
        assets = value;
        return this;
    }

    public AssetProvider assets() {
        return assets;
    }

    /** Sets the nodes {@link Arg#node} arguments complete from; returns this. */
    public ConsoleShell nodes(NodeProvider value) {
        nodes = value;
        return this;
    }

    public NodeProvider nodes() {
        return nodes;
    }

    /** Sets where {@code exec} reads scripts; returns this. */
    public ConsoleShell scripts(ScriptSource value) {
        scripts = value;
        return this;
    }

    public ScriptSource scripts() {
        return scripts;
    }

    /** Defines alias {@code name} as {@code text} (statements); null removes it. */
    public void alias(String name, String text) {
        if (text == null) {
            aliases.remove(name);
        } else {
            aliases.put(name, text);
        }
    }

    /** The text of alias {@code name}, or null. */
    public String alias(String name) {
        return aliases.get(name);
    }

    /** The aliases by name. */
    public Map<String, String> aliases() {
        return Map.copyOf(aliases);
    }

    /** Null when {@code command} may run now, else why not. */
    public String refusal(ConsoleCommand command) {
        if (command.tier() == ConsoleCommand.Tier.DEVELOPER && !permissions.developer()) {
            return command.name() + " is a developer command";
        }
        if (command.cheat() && !permissions.cheats()) {
            return command.name() + " is a cheat and cheats are off";
        }
        return null;
    }

    // ---------------------------------------------------------------- output

    /** Prints {@code text} at {@code level} ({@link LogLevel}) to the console's output. */
    public void print(int level, CharSequence text) {
        output.log(level, LOGGER, text, null, clock.getAsLong());
    }

    /** Sets the clock output is stamped with (milliseconds since the epoch; tests and screenshots fix it). */
    public ConsoleShell clock(java.util.function.LongSupplier value) {
        clock = value;
        return this;
    }

    /** The time output is stamped with now. */
    public long now() {
        return clock.getAsLong();
    }

    // ---------------------------------------------------------------- running

    /** Runs every statement of {@code line}; returns whether all ran without an error. */
    public boolean execute(CharSequence line) {
        boolean ok = true;
        for (CommandLine.Statement s : CommandLine.parse(line)) {
            ok &= run(s, line);
        }
        return ok;
    }

    private boolean run(CommandLine.Statement s, CharSequence line) {
        String name = s.name();
        String text = line.subSequence(s.start(), Math.min(s.end(), line.length())).toString().strip();
        String alias = aliases.get(name);
        if (alias != null) {
            StringBuilder expanded = new StringBuilder(alias);
            for (int i = 1; i < s.tokens().size(); i++) {
                expanded.append(' ').append(CommandLine.quote(s.tokens().get(i).text()));
            }
            return nested(expanded, name);
        }
        ConsoleCommand command = commands.get(name);
        if (command != null) {
            return invoke(command, s.tokens(), text);
        }
        ConsoleVariable variable = variables.find(name);
        if (variable != null) {
            return variable(variable, s.tokens());
        }
        String near = nearest(name);
        print(LogLevel.ERROR, "Unknown command " + name + (near == null ? "" : "; did you mean " + near + "?"));
        return false;
    }

    /** Runs {@code text} one level deeper (an alias, a script line); false when nesting is too deep. */
    boolean nested(CharSequence text, String what) {
        if (depth >= (int) ConsoleTunables.EXEC_DEPTH.get()) {
            print(LogLevel.ERROR, what + ": aliases and scripts nest too deeply");
            return false;
        }
        depth++;
        try {
            return execute(text);
        } finally {
            depth--;
        }
    }

    private boolean invoke(ConsoleCommand command, List<CommandLine.Token> tokens, String text) {
        String refusal = refusal(command);
        if (refusal != null) {
            print(LogLevel.ERROR, refusal);
            return false;
        }
        List<Arg> args = command.args();
        Object[] values = new Object[args.size()];
        int given = tokens.size() - 1;
        int i = 0;
        for (; i < args.size(); i++) {
            Arg a = args.get(i);
            if (i >= given) {
                if (!a.isOptional()) {
                    print(LogLevel.ERROR, command.name() + ": " + a.name() + " is missing; usage: " + command.usage());
                    return false;
                }
                continue;
            }
            String word = tokens.get(i + 1).text();
            if (a.isRest()) {
                StringBuilder rest = new StringBuilder(word);
                for (int k = i + 2; k < tokens.size(); k++) {
                    rest.append(' ').append(tokens.get(k).text());
                }
                word = rest.toString();
                given = i + 1; // every word is taken
            }
            try {
                values[i] = a.parse(word, this);
            } catch (Arg.Invalid e) {
                print(LogLevel.ERROR, command.name() + ": " + e.getMessage() + "; usage: " + command.usage());
                return false;
            }
        }
        if (given > args.size()) {
            print(LogLevel.ERROR, command.name() + ": too many arguments; usage: " + command.usage());
            return false;
        }
        try {
            Invocation call = new Invocation(this, command, values, text);
            command.action().run(call);
            return !call.failed();
        } catch (RuntimeException e) {
            print(LogLevel.ERROR, command.name() + " failed: " + e);
            return false;
        }
    }

    private boolean variable(ConsoleVariable v, List<CommandLine.Token> tokens) {
        if (tokens.size() == 1) {
            print(LogLevel.INFO, describe(v));
            return true;
        }
        if (tokens.size() > 2) {
            print(LogLevel.ERROR, v.name() + " takes one value; quote it if it has blanks");
            return false;
        }
        return set(v, tokens.get(1).text());
    }

    /** Sets {@code v} to {@code word}, checked against its kind; prints the result. */
    boolean set(ConsoleVariable v, String word) {
        String value = word;
        String problem = null;
        switch (v.kind()) {
            case BOOLEAN -> {
                Boolean b = Arg.parseBoolean(word);
                if (b == null) {
                    problem = word + " is not on or off";
                } else {
                    value = b ? "true" : "false";
                }
            }
            case INTEGER, DECIMAL -> {
                try {
                    double d = Double.parseDouble(word);
                    if (Double.isNaN(d) || v.kind() == ConsoleVariable.Kind.INTEGER && d != Math.rint(d)) {
                        problem = word + " is not a " + (v.kind() == ConsoleVariable.Kind.INTEGER ? "whole number"
                            : "number");
                    } else if (!Double.isNaN(v.min()) && (d < v.min() || d > v.max())) {
                        problem = word + " is not in " + Arg.number(v.min()) + ".." + Arg.number(v.max());
                    }
                } catch (NumberFormatException e) {
                    problem = word + " is not a number";
                }
            }
            case CHOICE -> {
                value = null;
                for (String c : v.choices()) {
                    if (c.equalsIgnoreCase(word)) {
                        value = c;
                    }
                }
                if (value == null) {
                    problem = word + " is not one of " + String.join(", ", v.choices());
                }
            }
            default -> {
                // text: as typed
            }
        }
        if (problem == null) {
            String availability = v.availability();
            problem = availability != null ? availability : v.set(value);
        }
        if (problem != null) {
            print(LogLevel.WARN, v.name() + ": " + problem);
            return false;
        }
        print(LogLevel.INFO, v.name() + " = " + v.value());
        return true;
    }

    /** {@code render.bloom = CHEAP (default OFF; one of OFF, CHEAP, PHYSICAL): Bloom technique}. */
    public static String describe(ConsoleVariable v) {
        StringBuilder s = new StringBuilder(v.name()).append(" = ").append(v.value()).append(" (default ")
            .append(v.defaultValue());
        if (!v.choices().isEmpty()) {
            s.append("; one of ").append(String.join(", ", v.choices()));
        } else if (!Double.isNaN(v.min())) {
            s.append("; range ").append(Arg.number(v.min())).append("..").append(Arg.number(v.max()));
        }
        s.append(')');
        if (v.description() != null && !v.description().isEmpty()) {
            s.append(": ").append(v.description());
        }
        if (v.availability() != null) {
            s.append(" [unavailable: ").append(v.availability()).append(']');
        }
        return s.toString();
    }

    /** The command, alias or variable named most like {@code name} (an edit or two away), or null. */
    String nearest(String name) {
        String best = null;
        int bestDistance = 3;
        List<String> all = new ArrayList<>(commands.keySet());
        all.addAll(aliases.keySet());
        all.addAll(variables.names());
        for (String candidate : all) {
            int d = distance(name.toLowerCase(java.util.Locale.ROOT), candidate.toLowerCase(java.util.Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] t = previous;
            previous = current;
            current = t;
        }
        return previous[b.length()];
    }

    // ---------------------------------------------------------------- completing

    /** The completions of the word under {@code caret} in {@code line}, and the hint of its statement. */
    public Completion complete(CharSequence line, int caret) {
        return Completer.complete(this, line, Math.max(0, Math.min(caret, line.length())));
    }
}
