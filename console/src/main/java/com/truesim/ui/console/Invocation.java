package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/**
 * One run of a {@link ConsoleCommand}: its arguments, parsed and checked against their {@link Arg}s, read by name,
 * and the console's output. Made per run (an event, not a frame).
 */
@AllowAllocation("one per command run")
public final class Invocation {

    private final ConsoleShell shell;
    private final ConsoleCommand command;
    private final Object[] values;
    private final String line;
    private boolean failed;

    Invocation(ConsoleShell shell, ConsoleCommand command, Object[] values, String line) {
        this.shell = shell;
        this.command = command;
        this.values = values;
        this.line = line;
    }

    /** The console that runs it. */
    public ConsoleShell shell() {
        return shell;
    }

    /** The command. */
    public ConsoleCommand command() {
        return command;
    }

    /** The statement as typed. */
    public String line() {
        return line;
    }

    private int index(String name) {
        List<Arg> args = command.args();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException(command.name() + " has no argument " + name);
    }

    /** Whether argument {@code name} was given. */
    public boolean has(String name) {
        return values[index(name)] != null;
    }

    private Object value(String name) {
        Object v = values[index(name)];
        if (v == null) {
            throw new IllegalStateException(name + " was not given");
        }
        return v;
    }

    /** An {@link Arg#integer} argument. */
    public int integer(String name) {
        return (Integer) value(name);
    }

    /** An {@link Arg#integer} argument, or {@code otherwise} when it was left out. */
    public int integer(String name, int otherwise) {
        return has(name) ? integer(name) : otherwise;
    }

    /** An {@link Arg#decimal} argument. */
    public double decimal(String name) {
        return (Double) value(name);
    }

    /** An {@link Arg#bool} argument. */
    public boolean bool(String name) {
        return (Boolean) value(name);
    }

    /** Any other argument as text (a choice as declared, an asset path, a node). */
    public String text(String name) {
        return (String) value(name);
    }

    /** As {@link #text(String)}, or {@code otherwise} when it was left out. */
    public String text(String name, String otherwise) {
        return has(name) ? text(name) : otherwise;
    }

    /** Prints a line of output. */
    public void print(CharSequence text) {
        shell.print(LogLevel.INFO, text);
    }

    /** Prints a warning. */
    public void warn(CharSequence text) {
        shell.print(LogLevel.WARN, text);
    }

    /** Prints an error: the command failed. */
    public void error(CharSequence text) {
        shell.print(LogLevel.ERROR, text);
        failed = true;
    }

    /** Marks the command failed (it printed why itself). */
    public void fail() {
        failed = true;
    }

    /** Whether the command failed. */
    public boolean failed() {
        return failed;
    }
}
