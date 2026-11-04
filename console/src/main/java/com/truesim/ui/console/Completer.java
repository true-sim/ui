package com.truesim.ui.console;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Completion of a console line at a caret (design 5.10): the word under the caret is a command, alias or setting
 * name (first word) or an argument value (the others: by the command's {@link Arg} or the setting's kind); the hint is
 * the statement's signature with the argument at the caret marked. Names match by prefix first, then by a part of
 * the name (ignoring case).
 */
@AllowAllocation("completion runs on keys typed")
final class Completer {

    private Completer() {
    }

    static Completion complete(ConsoleShell shell, CharSequence line, int caret) {
        CommandLine.Statement st = CommandLine.at(line, caret);
        List<CommandLine.Token> tokens = st.tokens();
        int index = -1;
        int start = caret;
        int end = caret;
        String prefix = "";
        for (int i = 0; i < tokens.size(); i++) {
            CommandLine.Token t = tokens.get(i);
            if (t.start() <= caret && caret <= t.end()) {
                index = i;
                start = t.start();
                end = t.end();
                prefix = caret == t.end() ? t.text() : unquoted(line.subSequence(t.start(), caret).toString());
                break;
            }
        }
        if (index < 0) {
            index = 0;
            for (CommandLine.Token t : tokens) {
                if (t.end() <= caret) {
                    index++;
                }
            }
        }
        List<Completion.Candidate> out = new ArrayList<>();
        if (index == 0) {
            names(shell, prefix, out);
            String hint = "";
            int hs = -1;
            int he = -1;
            ConsoleCommand exact = shell.command(prefix);
            if (exact != null) {
                hint = exact.usage() + description(exact.description());
                hs = 0;
                he = exact.name().length();
            }
            return new Completion(start, end, out, hint, hs, he);
        }
        String name = tokens.getFirst().text();
        String alias = shell.alias(name);
        if (alias != null) {
            return new Completion(start, end, out, name + " = " + alias, -1, -1);
        }
        ConsoleCommand command = shell.command(name);
        if (command != null) {
            return argument(shell, command, index - 1, prefix, start, end, out);
        }
        ConsoleVariable v = shell.variables().find(name);
        if (v != null) {
            if (index == 1) {
                List<String> values = new ArrayList<>();
                if (v.kind() == ConsoleVariable.Kind.BOOLEAN) {
                    filter(List.of("on", "off"), prefix, values);
                } else {
                    filter(v.choices(), prefix, values);
                }
                for (String value : values) {
                    out.add(new Completion.Candidate(value, value.equalsIgnoreCase(v.value()) ? "current" : ""));
                }
            }
            String signature = variableSignature(v);
            String hint = v.name() + " " + signature + "   = " + v.value() + description(v.description());
            int hs = v.name().length() + 1;
            return new Completion(start, end, out, hint, index == 1 ? hs : -1, index == 1 ? hs + signature.length()
                : -1);
        }
        return new Completion(start, end, out, "", -1, -1);
    }

    private static String description(String text) {
        return text == null || text.isEmpty() ? "" : "   " + text;
    }

    private static String unquoted(String raw) {
        return !raw.isEmpty() && (raw.charAt(0) == '"' || raw.charAt(0) == '\'') ? raw.substring(1) : raw;
    }

    /** {@code <OFF|CHEAP|PHYSICAL>}, {@code <0.5..2>}, {@code <on|off>}. */
    static String variableSignature(ConsoleVariable v) {
        return switch (v.kind()) {
            case BOOLEAN -> "<on|off>";
            case CHOICE -> "<" + String.join("|", v.choices()) + ">";
            case INTEGER, DECIMAL -> Double.isNaN(v.min()) ? "<number>"
                : "<" + Arg.number(v.min()) + ".." + Arg.number(v.max()) + ">";
            case TEXT -> "<text>";
        };
    }

    private static Completion argument(ConsoleShell shell, ConsoleCommand command, int argIndex, String prefix,
        int start, int end, List<Completion.Candidate> out) {
        List<Arg> args = command.args();
        Arg arg = null;
        if (argIndex < args.size()) {
            arg = args.get(argIndex);
        } else if (!args.isEmpty() && args.getLast().isRest()) {
            arg = args.getLast();
        }
        StringBuilder hint = new StringBuilder(command.name());
        int hs = -1;
        int he = -1;
        for (Arg a : args) {
            hint.append(' ');
            if (a == arg) {
                hs = hint.length();
            }
            hint.append(a.signature());
            if (a == arg) {
                he = hint.length();
            }
        }
        hint.append(description(arg != null && !arg.description().isEmpty() ? arg.description()
            : command.description()));
        if (arg != null) {
            List<String> values = new ArrayList<>();
            arg.complete(prefix, shell, values);
            for (String value : values) {
                out.add(new Completion.Candidate(value, detail(shell, arg, value)));
            }
        }
        return new Completion(start, end, out, hint.toString(), hs, he);
    }

    private static String detail(ConsoleShell shell, Arg arg, String value) {
        switch (arg.type()) {
            case SETTING -> {
                ConsoleVariable v = shell.variables().find(value);
                return v == null ? "" : v.value();
            }
            case COMMAND -> {
                ConsoleCommand c = shell.command(value);
                if (c != null) {
                    return c.description();
                }
                ConsoleVariable v = shell.variables().find(value);
                return v == null ? "alias" : "= " + v.value();
            }
            default -> {
                return "";
            }
        }
    }

    /** Command, alias and setting names for {@code prefix}: those starting with it, then those containing it. */
    private static void names(ConsoleShell shell, String prefix, List<Completion.Candidate> out) {
        List<Completion.Candidate> contains = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String name : shell.commandNames()) {
            ConsoleCommand c = shell.command(name);
            if (shell.refusal(c) == null) {
                add(name, c.description(), p, out, contains);
            }
        }
        for (String name : new java.util.TreeSet<>(shell.aliases().keySet())) {
            add(name, "alias: " + shell.alias(name), p, out, contains);
        }
        for (String name : shell.variables().names()) {
            add(name, "= " + shell.variables().find(name).value(), p, out, contains);
        }
        out.sort((a, b) -> a.text().compareToIgnoreCase(b.text()));
        contains.sort((a, b) -> a.text().compareToIgnoreCase(b.text()));
        out.addAll(contains);
    }

    private static void add(String name, String detail, String lowerPrefix, List<Completion.Candidate> starts,
        List<Completion.Candidate> contains) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(lowerPrefix)) {
            starts.add(new Completion.Candidate(name, detail));
        } else if (!lowerPrefix.isEmpty() && lower.contains(lowerPrefix)) {
            contains.add(new Completion.Candidate(name, detail));
        }
    }

    /** Adds the {@code options} starting with {@code prefix} (ignoring case) to {@code out}. */
    static void filter(List<String> options, String prefix, List<String> out) {
        for (String o : options) {
            if (o.regionMatches(true, 0, prefix, 0, prefix.length())) {
                out.add(o);
            }
        }
    }
}
