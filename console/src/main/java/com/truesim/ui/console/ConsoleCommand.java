package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A console command (design 5.10): a name, typed {@link Arg}uments, a description, who may run it (a
 * {@linkplain Tier tier} and a cheat flag) and what it does. Plugins and games register commands with a
 * {@link ConsoleShell}; the shell parses and checks the arguments before the action runs, so the action reads typed
 * values from its {@link Invocation}.
 *
 * <pre>{@code
 * shell.register(ConsoleCommand.named("spawn")
 *     .describe("Spawns creatures in front of the camera")
 *     .arg(Arg.integer("count").range(1, 100))
 *     .arg(Arg.choice("kind", "orc", "elf").optional())
 *     .developer().cheat()
 *     .action(call -> game.spawn(call.integer("count"), call.text("kind", "orc"))));
 * }</pre>
 */
@AllowAllocation("commands are declared once")
public final class ConsoleCommand {

    /** Who may run a command. */
    public enum Tier {
        /** Everyone, in shipped games too. */
        PLAYER,
        /** Developers only ({@link ConsolePermissions#developer()}). */
        DEVELOPER
    }

    /** What a command does with its checked arguments. */
    @FunctionalInterface
    public interface Action {
        void run(Invocation call);
    }

    private final String name;
    private final String description;
    private final List<Arg> args;
    private final Tier tier;
    private final boolean cheat;
    private final Action action;

    private ConsoleCommand(Builder b, Action action) {
        this.name = b.name;
        this.description = b.description;
        this.args = List.copyOf(b.args);
        this.tier = b.tier;
        this.cheat = b.cheat;
        this.action = Objects.requireNonNull(action, "action");
    }

    /** Starts declaring a command named {@code name} (no blanks, quotes or {@code ;}). */
    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public List<Arg> args() {
        return args;
    }

    public Tier tier() {
        return tier;
    }

    /** Whether it is a cheat (runs only with cheats on). */
    public boolean cheat() {
        return cheat;
    }

    Action action() {
        return action;
    }

    /** {@code spawn <count: 1..100> [kind: orc|elf]}. */
    public String usage() {
        StringBuilder s = new StringBuilder(name);
        for (Arg a : args) {
            s.append(' ').append(a.signature());
        }
        return s.toString();
    }

    @Override
    public String toString() {
        return usage();
    }

    /** Declares a command; {@link #action} ends the declaration. */
    @AllowAllocation("commands are declared once")
    public static final class Builder {

        private final String name;
        private String description = "";
        private final List<Arg> args = new ArrayList<>();
        private Tier tier = Tier.PLAYER;
        private boolean cheat;

        private Builder(String name) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == ';' || c == '\\') {
                    throw new IllegalArgumentException("a command name has no blanks, quotes or ';': " + name);
                }
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a command needs a name");
            }
            this.name = name;
        }

        /** What it does (help, the completion pop-up). */
        public Builder describe(String text) {
            description = text;
            return this;
        }

        /** Adds an argument (after a {@link Arg#rest} or an optional one, only optional ones). */
        public Builder arg(Arg arg) {
            if (!args.isEmpty()) {
                Arg last = args.getLast();
                if (last.isRest()) {
                    throw new IllegalArgumentException(name + ": nothing follows " + last.name());
                }
                if (last.isOptional() && !arg.isOptional()) {
                    throw new IllegalArgumentException(name + ": " + arg.name() + " follows an optional argument");
                }
            }
            args.add(arg);
            return this;
        }

        /** For developers only. */
        public Builder developer() {
            tier = Tier.DEVELOPER;
            return this;
        }

        /** A cheat: runs only with cheats on. */
        public Builder cheat() {
            cheat = true;
            return this;
        }

        /** The command, doing {@code action}. */
        public ConsoleCommand action(Action action) {
            return new ConsoleCommand(this, action);
        }
    }
}
